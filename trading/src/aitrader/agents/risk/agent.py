"""Risk agent: the absolute veto (deterministic).

It sizes every order itself; the boss only asks for a fraction of the risk budget. It then
checks every hard limit against the portfolio after the trade, and only when all pass builds
the exact ``UnsignedInstruction`` and signs a single-use approval token bound to its digest.
Execution refuses anything without a valid token, so no code path can route around this
class.

Opening trades also need a passed R:R verdict and a liquidity plan for the same decision. A
trading freeze blocks every opening order; orders that only reduce risk remain allowed so
positions can always be cut. Limits shrink through ``limit_multiplier`` when analysts are
missing, the regime is uncertain, an event window is active or drawdown builds up.
"""

from __future__ import annotations

import itertools
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import ROUND_CEILING, ROUND_FLOOR, ROUND_HALF_EVEN, Decimal
from uuid import UUID, uuid5

import numpy as np

from aitrader.agents.data.agent import MarketData, index_instrument
from aitrader.agents.risk.portfolio import PortfolioSnapshot
from aitrader.agents.risk.tokens import TokenSigner
from aitrader.agents.risk_reward.agent import blocking_flags
from aitrader.config.settings import RiskLimits, Stage
from aitrader.schemas.base import Instrument, InstrumentType, Timeframe
from aitrader.schemas.decision import Action, BossDecision
from aitrader.schemas.gates import ExecAlgo, LiquidityPlan, RRVerdict
from aitrader.schemas.market import Severity
from aitrader.schemas.news import EventRiskFlag
from aitrader.schemas.orders import (
    InstructionIntent,
    OrderInstruction,
    OrderType,
    Product,
    Side,
    UnsignedInstruction,
)
from aitrader.schemas.risk import AlertType, RiskAlert, RiskApproval, RiskRule, RuleResult
from aitrader.schemas.signals import ChartSignal
from aitrader.state.interface import StateStore
from aitrader.tools.sizing import fixed_fractional

_NAMESPACE = UUID("a7f3c1de-2b8e-4f5a-9d61-3e4c8b2f0a95")
_Q = Decimal("0.05")  # NSE tick size for most equities and F&O


def _tick(price: float) -> Decimal:
    return (Decimal(str(price)) / _Q).to_integral_value(rounding=ROUND_HALF_EVEN) * _Q


def _limit_tick(price: float, side: Side) -> Decimal:
    """Round a limit to the tick on the conservative side (buys down, sells up), so rounding
    never takes it past the price-deviation guard execution enforces."""
    rounding = ROUND_FLOOR if side is Side.BUY else ROUND_CEILING
    return (Decimal(str(price)) / _Q).to_integral_value(rounding=rounding) * _Q


@dataclass
class RiskOutcome:
    approval: RiskApproval
    instruction: OrderInstruction | None
    alerts: list[RiskAlert] = field(default_factory=list)


def limit_multiplier(
    limits: RiskLimits,
    *,
    missing_analysts: int,
    trend_missing: bool,
    exposure_multiplier: float,
    portfolio: PortfolioSnapshot,
) -> float:
    """Combined tightening factor in (0, 1]."""
    d = limits.degradation
    m = d.missing_agent_multiplier**missing_analysts
    if trend_missing:
        m *= d.missing_trend_agent_multiplier
    m *= exposure_multiplier
    dd = limits.drawdown
    for used, limit in (
        (portfolio.daily_drawdown, dd.daily_limit),
        (portfolio.total_drawdown, dd.total_limit),
    ):
        start = dd.tighten_from_fraction_of_limit * limit
        if used > start:
            m *= max(0.25, 1 - 0.75 * (used - start) / (limit - start))
    return min(max(m, 0.05), 1.0)


class RiskAgent:
    def __init__(
        self,
        market: MarketData,
        limits: RiskLimits,
        state: StateStore,
        signer: TokenSigner,
        *,
        stage: Stage = Stage.BACKTEST,
        min_analysts: int | None = None,
    ) -> None:
        self._market = market
        self._limits = limits
        self._state = state
        self._signer = signer
        self._stage = stage
        self._min_analysts = (
            min_analysts if min_analysts is not None else limits.degradation.min_agents_for_new_entries
        )

    # ------------------------------------------------------------------ alerts
    def handle_alert(self, alert: RiskAlert, now: datetime) -> None:
        if alert.freeze_new_orders:
            self._state.set_frozen(alert.alert_id, now)

    def drawdown_alerts(self, portfolio: PortfolioSnapshot, now: datetime) -> list[RiskAlert]:
        alerts = []
        dd = self._limits.drawdown
        if portfolio.daily_drawdown >= dd.daily_limit:
            alerts.append(
                RiskAlert(
                    alert_id=uuid5(_NAMESPACE, f"daily-dd|{now.date()}"),
                    alert_type=AlertType.DRAWDOWN_BREACH,
                    severity=Severity.CRITICAL,
                    freeze_new_orders=True,
                    detail=f"daily drawdown {portfolio.daily_drawdown:.2%} breached {dd.daily_limit:.2%}",
                )
            )
        if portfolio.total_drawdown >= dd.total_limit:
            alerts.append(
                RiskAlert(
                    alert_id=uuid5(_NAMESPACE, f"total-dd|{now.date()}"),
                    alert_type=AlertType.DRAWDOWN_BREACH,
                    severity=Severity.CRITICAL,
                    freeze_new_orders=True,
                    flatten_positions=True,
                    detail=f"total drawdown {portfolio.total_drawdown:.2%} breached {dd.total_limit:.2%}",
                )
            )
        for alert in alerts:
            self.handle_alert(alert, now)
        return alerts

    def flatten_instructions(
        self, alert: RiskAlert, portfolio: PortfolioSnapshot, as_of: datetime
    ) -> list[OrderInstruction]:
        """Signed exits for every holding, for an alert that asks to flatten. Exits are
        risk-reducing, so they are allowed while trading is frozen. Ids derive from the
        alert, so re-handling the same alert cannot issue a second set."""
        if not alert.flatten_positions:
            return []
        out = []
        lim = self._limits
        deviation = lim.execution.max_price_deviation_bps / 1e4
        for h in sorted(portfolio.holdings, key=lambda h: h.instrument.key):
            if h.quantity == 0:
                continue
            inst = h.instrument
            side = Side.SELL if h.quantity > 0 else Side.BUY
            price = h.price * (1 - deviation) if side is Side.SELL else h.price * (1 + deviation)
            ref = f"flatten|{alert.alert_id}|{inst.key}"
            unsigned = UnsignedInstruction(
                instruction_id=uuid5(_NAMESPACE, ref),
                idempotency_key=f"flatten:{uuid5(_NAMESPACE, ref)}",
                decision_id=uuid5(_NAMESPACE, f"decision|{ref}"),
                action=Action.SELL if side is Side.SELL else Action.REDUCE,
                intent=InstructionIntent.CLOSE,
                instrument=inst,
                side=side,
                quantity=abs(h.quantity),
                order_type=OrderType.LIMIT,
                limit_price=_limit_tick(price, side),
                product=Product.CNC if inst.instrument_type is InstrumentType.EQUITY else Product.NRML,
                algo=ExecAlgo.MARKETABLE_LIMIT,
                participation_rate=lim.liquidity.max_participation_rate,
                horizon_minutes=30,
                max_price_deviation_bps=lim.execution.max_price_deviation_bps,
                valid_until=as_of + timedelta(seconds=lim.execution.approval_token_ttl_seconds),
            )
            out.append(OrderInstruction(**unsigned.model_dump(), approval=self._signer.sign(unsigned, as_of)))
        return out

    # ------------------------------------------------------------------ assessment
    def assess(
        self,
        decision: BossDecision,
        *,
        chart: ChartSignal | None,
        plan: LiquidityPlan | None,
        verdict: RRVerdict | None,
        portfolio: PortfolioSnapshot,
        flags: Sequence[EventRiskFlag],
        live_analysts: int,
        cycle_id: UUID,
        as_of: datetime,
        human_approved: bool = False,
        sector: str | None = None,
    ) -> RiskOutcome:
        alerts = self.drawdown_alerts(portfolio, as_of)
        held = portfolio.holding(decision.instrument.key)
        opening = decision.action in (Action.BUY, Action.HEDGE) or (
            decision.action is Action.SELL and decision.open_short
        )
        if decision.action in (Action.HOLD, Action.WAIT):
            return self._reject(
                decision, [RuleResult(rule=RiskRule.MAX_POSITION, passed=False, observed=0, limit=0)], alerts
            )
        if opening:
            return self._assess_open(
                decision,
                chart,
                plan,
                verdict,
                portfolio,
                flags,
                live_analysts,
                cycle_id,
                as_of,
                human_approved,
                alerts,
                sector,
            )
        return self._assess_close(
            decision, held.quantity if held else 0, plan, portfolio, cycle_id, as_of, alerts
        )

    def _assess_open(
        self,
        decision: BossDecision,
        chart: ChartSignal | None,
        plan: LiquidityPlan | None,
        verdict: RRVerdict | None,
        portfolio: PortfolioSnapshot,
        flags: Sequence[EventRiskFlag],
        live_analysts: int,
        cycle_id: UUID,
        as_of: datetime,
        human_approved: bool,
        alerts: list[RiskAlert],
        sector: str | None,
    ) -> RiskOutcome:
        lim = self._limits
        inst = decision.instrument
        m = self._state.limit_multiplier()
        did = decision.decision_id
        gates_ok = (
            plan is not None
            and verdict is not None
            and verdict.passed
            and plan.decision_id == did
            and verdict.decision_id == did
            and chart is not None
            and chart.structural_stop is not None
        )
        frozen = self._state.freeze_status().frozen
        if not gates_ok or plan is None or verdict is None or chart is None or chart.structural_stop is None:
            return self._reject(
                decision,
                [RuleResult(rule=RiskRule.GATE_NOT_PASSED, passed=False, observed=0, limit=1)],
                alerts,
            )
        bars = self._market.bars(inst, Timeframe.D1, as_of, start=as_of - timedelta(days=10)).bars
        entry = float(bars[-1].close)
        stop = float(chart.structural_stop)
        event_cut = min(
            (f.exposure_multiplier for f in flags if f.instrument is None or f.instrument.key == inst.key),
            default=1.0,
        )
        m_eff = max(m * event_cut, 0.01)
        risk_fraction = min(lim.per_trade.max_risk_fraction * decision.risk_budget_fraction * m_eff, 0.02)
        qty = 0
        if risk_fraction > 0:
            qty = fixed_fractional(
                equity=portfolio.equity,
                risk_fraction=risk_fraction,
                entry=entry,
                stop=stop,
                lot_size=inst.lot_size,
                cost_per_unit=verdict.costs_per_unit,
                max_position_value=lim.per_trade.max_position_fraction * portfolio.equity * m_eff,
            ).quantity
        qty = min(qty, (plan.max_quantity // inst.lot_size) * inst.lot_size)
        sign = 1 if decision.action in (Action.BUY, Action.HEDGE) else -1
        value = sign * qty * entry
        equity = portfolio.equity
        held = portfolio.holding(inst.key)
        position_after = abs((held.value if held else 0.0) + value)
        gross_after = (portfolio.gross + abs(value)) / equity
        net_after = abs(portfolio.net + value) / equity
        sector = sector or self._sector_of(inst, portfolio)
        sector_after = abs(portfolio.sector_exposure().get(sector, 0.0) + value) / equity
        risk_amount = qty * (abs(entry - stop) + verdict.costs_per_unit)
        var, cvar, cluster, stress = self._portfolio_risk(portfolio, inst, value, as_of)
        blocking = blocking_flags(flags, inst.key, as_of, as_of + timedelta(days=14))
        human_needed = self._stage.is_live and (
            self._stage is Stage.LIVE_HUMAN_APPROVAL
            or risk_amount / equity > lim.per_trade.human_approval_above_risk_fraction
        )
        results = [
            RuleResult(rule=RiskRule.TRADING_FROZEN, passed=not frozen, observed=float(frozen), limit=0),
            RuleResult(
                rule=RiskRule.DEGRADED_COVERAGE,
                passed=live_analysts >= self._min_analysts,
                observed=live_analysts,
                limit=self._min_analysts,
            ),
            RuleResult(
                rule=RiskRule.MAX_RISK_PER_TRADE,
                passed=qty > 0 and risk_amount / equity <= lim.per_trade.max_risk_fraction + 1e-12,
                observed=risk_amount / equity,
                limit=lim.per_trade.max_risk_fraction,
            ),
            RuleResult(
                rule=RiskRule.MAX_POSITION,
                passed=position_after / equity <= lim.per_trade.max_position_fraction + 1e-12,
                observed=position_after / equity,
                limit=lim.per_trade.max_position_fraction,
            ),
            RuleResult(
                rule=RiskRule.MAX_OPEN_POSITIONS,
                passed=held is not None or len(portfolio.holdings) < lim.portfolio.max_open_positions,
                observed=len(portfolio.holdings),
                limit=lim.portfolio.max_open_positions,
            ),
            RuleResult(
                rule=RiskRule.GROSS_EXPOSURE,
                passed=gross_after <= lim.portfolio.max_gross_exposure * m,
                observed=gross_after,
                limit=lim.portfolio.max_gross_exposure * m,
            ),
            RuleResult(
                rule=RiskRule.NET_EXPOSURE,
                passed=net_after <= lim.portfolio.max_net_exposure * m
                or net_after <= abs(portfolio.net) / equity,
                observed=net_after,
                limit=lim.portfolio.max_net_exposure * m,
            ),
            RuleResult(
                rule=RiskRule.SECTOR_LIMIT,
                passed=sector_after <= lim.portfolio.max_sector_exposure,
                observed=sector_after,
                limit=lim.portfolio.max_sector_exposure,
            ),
            RuleResult(
                rule=RiskRule.CORRELATION_LIMIT,
                passed=cluster <= lim.portfolio.max_pairwise_correlation_cluster,
                observed=cluster,
                limit=lim.portfolio.max_pairwise_correlation_cluster,
            ),
            RuleResult(
                rule=RiskRule.LEVERAGE,
                passed=gross_after <= lim.portfolio.max_leverage,
                observed=gross_after,
                limit=lim.portfolio.max_leverage,
            ),
            RuleResult(
                rule=RiskRule.DAILY_DRAWDOWN,
                passed=portfolio.daily_drawdown < lim.drawdown.daily_limit,
                observed=portfolio.daily_drawdown,
                limit=lim.drawdown.daily_limit,
            ),
            RuleResult(
                rule=RiskRule.TOTAL_DRAWDOWN,
                passed=portfolio.total_drawdown < lim.drawdown.total_limit,
                observed=portfolio.total_drawdown,
                limit=lim.drawdown.total_limit,
            ),
            RuleResult(
                rule=RiskRule.LIQUIDITY_LIMIT,
                passed=qty <= plan.max_quantity
                and plan.days_to_liquidate <= lim.liquidity.max_days_to_liquidate,
                observed=plan.days_to_liquidate,
                limit=lim.liquidity.max_days_to_liquidate,
            ),
            RuleResult(
                rule=RiskRule.EVENT_EXPOSURE, passed=not blocking, observed=float(len(blocking)), limit=0
            ),
            RuleResult(
                rule=RiskRule.VAR_LIMIT,
                passed=var <= lim.var.max_var_fraction and cvar <= lim.var.max_cvar_fraction,
                observed=var,
                limit=lim.var.max_var_fraction,
            ),
            RuleResult(
                rule=RiskRule.STRESS_LIMIT,
                passed=stress <= lim.stress.max_stress_loss_fraction,
                observed=stress,
                limit=lim.stress.max_stress_loss_fraction,
            ),
            RuleResult(
                rule=RiskRule.HUMAN_APPROVAL_PENDING,
                passed=human_approved or not human_needed,
                observed=float(human_needed),
                limit=float(human_approved),
            ),
        ]
        if not all(r.passed for r in results):
            return self._reject(decision, results, alerts, m)
        side = Side.BUY if sign > 0 else Side.SELL
        deviation = lim.execution.max_price_deviation_bps / 1e4
        limit_price = entry * (1 + deviation) if side is Side.BUY else entry * (1 - deviation)
        unsigned = UnsignedInstruction(
            instruction_id=uuid5(_NAMESPACE, f"{cycle_id}|{decision.decision_id}"),
            idempotency_key=f"{cycle_id}:{decision.decision_id}:{decision.revision_number}",
            decision_id=decision.decision_id,
            action=decision.action,
            intent=InstructionIntent.HEDGE if decision.action is Action.HEDGE else InstructionIntent.OPEN,
            instrument=inst,
            side=side,
            quantity=qty,
            order_type=OrderType.LIMIT,
            limit_price=_limit_tick(limit_price, side),
            product=Product.CNC if inst.instrument_type is InstrumentType.EQUITY else Product.NRML,
            open_short=decision.open_short,
            algo=plan.algo,
            participation_rate=plan.participation_rate,
            horizon_minutes=plan.horizon_minutes,
            protective_stop=_tick(stop),
            targets=tuple(_tick(float(t)) for t in chart.targets),
            max_price_deviation_bps=lim.execution.max_price_deviation_bps,
            valid_until=as_of + timedelta(seconds=lim.execution.approval_token_ttl_seconds),
        )
        return self._approve(decision, unsigned, results, as_of, alerts, m)

    def _assess_close(
        self,
        decision: BossDecision,
        held_qty: int,
        plan: LiquidityPlan | None,
        portfolio: PortfolioSnapshot,
        cycle_id: UUID,
        as_of: datetime,
        alerts: list[RiskAlert],
    ) -> RiskOutcome:
        inst = decision.instrument
        if held_qty == 0:
            return self._reject(
                decision, [RuleResult(rule=RiskRule.MAX_POSITION, passed=False, observed=0, limit=0)], alerts
            )
        lot = inst.lot_size
        qty = abs(held_qty) if decision.action is Action.SELL else max(lot, (abs(held_qty) // 2 // lot) * lot)
        side = Side.SELL if held_qty > 0 else Side.BUY
        bars = self._market.bars(inst, Timeframe.D1, as_of, start=as_of - timedelta(days=10)).bars
        entry = float(bars[-1].close)
        deviation = self._limits.execution.max_price_deviation_bps / 1e4
        limit_price = entry * (1 - deviation) if side is Side.SELL else entry * (1 + deviation)
        # Risk-reducing orders stay allowed during a freeze, so positions can always be cut.
        results = [
            RuleResult(rule=RiskRule.MAX_POSITION, passed=True, observed=abs(held_qty), limit=abs(held_qty))
        ]
        # A full exit of a long is a SELL; anything else that only reduces exposure (including
        # covering a short with a buy) is a REDUCE.
        action = Action.SELL if decision.action is Action.SELL and side is Side.SELL else Action.REDUCE
        unsigned = UnsignedInstruction(
            instruction_id=uuid5(_NAMESPACE, f"{cycle_id}|{decision.decision_id}"),
            idempotency_key=f"{cycle_id}:{decision.decision_id}:{decision.revision_number}",
            decision_id=decision.decision_id,
            action=action,
            intent=InstructionIntent.CLOSE,
            instrument=inst,
            side=side,
            quantity=qty,
            order_type=OrderType.LIMIT,
            limit_price=_limit_tick(limit_price, side),
            product=Product.CNC if inst.instrument_type is InstrumentType.EQUITY else Product.NRML,
            algo=plan.algo if plan else ExecAlgo.MARKETABLE_LIMIT,
            participation_rate=plan.participation_rate
            if plan
            else self._limits.liquidity.max_participation_rate,
            horizon_minutes=plan.horizon_minutes if plan else 30,
            max_price_deviation_bps=self._limits.execution.max_price_deviation_bps,
            valid_until=as_of + timedelta(seconds=self._limits.execution.approval_token_ttl_seconds),
        )
        return self._approve(decision, unsigned, results, as_of, alerts, 1.0)

    # ------------------------------------------------------------------ helpers
    def _approve(
        self,
        decision: BossDecision,
        unsigned: UnsignedInstruction,
        results: list[RuleResult],
        as_of: datetime,
        alerts: list[RiskAlert],
        multiplier: float,
    ) -> RiskOutcome:
        token = self._signer.sign(unsigned, as_of)
        instruction = OrderInstruction(**unsigned.model_dump(), approval=token)
        approval = RiskApproval(
            decision_id=decision.decision_id,
            instrument=decision.instrument,
            approved=True,
            approved_quantity=unsigned.quantity,
            results=tuple(results),
            limit_multiplier=min(max(multiplier, 0.01), 1.0),
            token=token,
        )
        return RiskOutcome(approval, instruction, alerts)

    @staticmethod
    def _reject(
        decision: BossDecision, results: list[RuleResult], alerts: list[RiskAlert], multiplier: float = 1.0
    ) -> RiskOutcome:
        approval = RiskApproval(
            decision_id=decision.decision_id,
            instrument=decision.instrument,
            approved=False,
            approved_quantity=0,
            results=tuple(results),
            limit_multiplier=min(max(multiplier, 0.01), 1.0),
        )
        return RiskOutcome(approval, None, alerts)

    @staticmethod
    def _sector_of(inst: Instrument, portfolio: PortfolioSnapshot) -> str:
        held = portfolio.holding(inst.key)
        return (held.sector if held and held.sector else None) or "UNKNOWN"

    def _returns(self, inst: Instrument, as_of: datetime) -> dict[datetime, float]:
        bars = self._market.bars(inst, Timeframe.D1, as_of, start=as_of - timedelta(days=400)).bars[-251:]
        return {b.bar_end: float(b.close) / float(a.close) - 1 for a, b in itertools.pairwise(bars)}

    def _portfolio_risk(
        self, portfolio: PortfolioSnapshot, new: Instrument, new_value: float, as_of: datetime
    ) -> tuple[float, float, float, float]:
        """(1-day 99% historical VaR, CVaR, correlation-cluster weight, index-shock stress loss),
        all as fractions of equity, for the portfolio after the trade."""
        equity = portfolio.equity
        positions: dict[str, tuple[Instrument, float]] = {
            h.instrument.key: (h.instrument, h.value) for h in portfolio.holdings
        }
        inst_value = positions.get(new.key, (new, 0.0))[1] + new_value
        positions[new.key] = (new, inst_value)
        series = {k: self._returns(i, as_of) for k, (i, _) in positions.items()}
        common = sorted(set.intersection(*(set(s) for s in series.values()))) if series else []
        if len(common) < 60:
            return (
                0.0,
                0.0,
                abs(inst_value) / equity,
                abs(inst_value) / equity * abs(self._limits.stress.index_shock),
            )
        keys = list(positions)
        matrix = np.array([[series[k][t] for k in keys] for t in common])
        weights = np.array([positions[k][1] / equity for k in keys])
        pnl = matrix @ weights
        losses = -pnl
        var = float(np.quantile(losses, self._limits.var.confidence))
        cvar = float(losses[losses >= var].mean())
        corr = np.corrcoef(matrix.T) if len(keys) > 1 else np.ones((1, 1))
        j = keys.index(new.key)
        cluster = float(sum(abs(weights[i]) for i in range(len(keys)) if i == j or corr[i, j] > 0.7))
        index_returns = self._returns(index_instrument("NIFTY_50"), as_of)
        stress = 0.0
        if all(t in index_returns for t in common):
            m = np.array([index_returns[t] for t in common])
            var_m = float(np.var(m, ddof=1)) or 1.0
            betas = np.array([float(np.cov(matrix[:, i], m, ddof=1)[0, 1]) / var_m for i in range(len(keys))])
            stress = max(0.0, -float(self._limits.stress.index_shock * (betas * weights).sum()))
        return max(var, 0.0), max(cvar, 0.0), cluster, stress
