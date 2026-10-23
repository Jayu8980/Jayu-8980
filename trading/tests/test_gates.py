from datetime import date, timedelta
from decimal import Decimal
from typing import Any
from uuid import uuid4

import pytest

from aitrader.agents.data.agent import MarketData
from aitrader.agents.liquidity.agent import LiquidityAgent
from aitrader.agents.risk.agent import RiskAgent, limit_multiplier
from aitrader.agents.risk.portfolio import Holding, PortfolioSnapshot
from aitrader.agents.risk.tokens import TokenSigner
from aitrader.agents.risk_reward.agent import RiskRewardAgent, SetupStats
from aitrader.backtest.strategies import equity
from aitrader.config import Stage, load_risk_limits
from aitrader.schemas.base import (
    EvidenceKind,
    EvidenceRef,
    Exchange,
    Horizon,
    Instrument,
    InstrumentType,
    Segment,
    Timeframe,
)
from aitrader.schemas.decision import Action, BossDecision, Invalidation, InvalidationKind
from aitrader.schemas.gates import ExecAlgo, LiquidityRegime, RRRejection
from aitrader.schemas.news import EventRiskFlag, EventType
from aitrader.schemas.risk import RiskRule
from aitrader.schemas.signals import (
    ChartSignal,
    SetupType,
    StructureState,
    TimeframeReading,
    VolatilityRegime,
)
from aitrader.state import InMemoryStateStore
from aitrader.storage import CachedPitReader, PitStore
from tests.analyst_helpers import regime_index, write_closes
from tests.chain_helpers import KEY, flat_portfolio, seeded_stats
from tests.data_helpers import eod

AAA = equity("AAA")
LIMITS = load_risk_limits()


def setup(last_flat: bool = False, volume: int = 1_000_000) -> tuple[MarketData, list]:  # type: ignore[type-arg]
    store = PitStore()
    days = regime_index(store, 120)
    closes = [100 * (1.002**i) * (1 + 0.004 * (-1) ** i) for i in range(120)]
    write_closes(store, AAA, days, closes, volume=[volume] * 120)
    if last_flat:
        write_closes(store, AAA, days[-1:], [closes[-1]], spread=0.0)  # a correction: locked at the band
    return MarketData(CachedPitReader(store)), days


def chart(
    stop: float, targets: tuple[float, ...], atr: float = 2.0, setup_type: SetupType = SetupType.BREAKOUT
) -> ChartSignal:
    return ChartSignal(
        instrument=AAA,
        score=0.6,
        confidence=0.7,
        horizon=Horizon.SWING,
        valid_until=eod(date(2030, 1, 1)),
        evidence=(EvidenceRef(kind=EvidenceKind.DATASET, ref_id="x", available_at=eod(date(2025, 1, 1))),),
        readings=(
            TimeframeReading(
                timeframe=Timeframe.D1, structure=StructureState.BREAKOUT, trend_score=0.8, confidence=0.7
            ),
        ),
        alignment_score=0.8,
        setup=setup_type,
        volatility_regime=VolatilityRegime.NORMAL,
        atr=atr,
        entry_low=Decimal("120"),
        entry_high=Decimal("130"),
        structural_stop=Decimal(str(round(stop, 2))),
        targets=tuple(Decimal(str(round(t, 2))) for t in targets),
    )


def buy(budget: float = 1.0) -> BossDecision:
    return BossDecision(
        decision_id=uuid4(),
        action=Action.BUY,
        instrument=AAA,
        horizon=Horizon.SWING,
        conviction=0.6,
        risk_budget_fraction=budget,
        key_reasons=("test",),
        main_risk="test",
        invalidation=Invalidation(
            condition=InvalidationKind.PRICE_CLOSE_BELOW, level=Decimal("100"), description="x"
        ),
    )


def test_liquidity_caps_size_at_share_of_adv() -> None:
    market, days = setup()
    plan = LiquidityAgent(market, LIMITS.liquidity).plan(uuid4(), AAA, 10_000, eod(days[-1]))
    assert plan is not None and plan.max_quantity == 50_000 and plan.regime is LiquidityRegime.NORMAL
    assert plan.algo is ExecAlgo.TWAP and plan.est_impact_bps > LIMITS.liquidity.assumed_spread_bps / 2


def test_circuit_locked_and_thin_names() -> None:
    market, days = setup(last_flat=True)
    plan = LiquidityAgent(market, LIMITS.liquidity).plan(uuid4(), AAA, 100, eod(days[-1]))
    assert plan is not None and plan.regime is LiquidityRegime.CIRCUIT_LOCKED and plan.max_quantity == 0
    thin_market, days = setup(volume=1_000)
    thin = LiquidityAgent(thin_market, LIMITS.liquidity).plan(uuid4(), AAA, 10, eod(days[-1]))
    assert thin is not None and thin.regime is LiquidityRegime.THIN and thin.liquidity_score < 0.1


def test_futures_above_freeze_quantity_are_iceberged() -> None:
    store = PitStore()
    days = regime_index(store, 60)
    fut = Instrument(
        exchange=Exchange.NSE,
        segment=Segment.FNO,
        symbol="NIFTY",
        instrument_type=InstrumentType.FUTURE,
        expiry=days[-1] + timedelta(days=20),
        lot_size=75,
    )
    write_closes(store, fut, days, [25000.0 + i for i in range(60)], oi=[1e6] * 60, volume=[5_000_000] * 60)
    limits = LIMITS.liquidity.model_copy(update={"freeze_quantity": {"NIFTY": 1800}})
    plan = LiquidityAgent(MarketData(CachedPitReader(store)), limits).plan(uuid4(), fut, 3000, eod(days[-1]))
    assert plan is not None and plan.algo is ExecAlgo.ICEBERG and plan.max_quantity % 75 == 0


def rr_agent(market: MarketData, stats: SetupStats | None = None) -> RiskRewardAgent:
    return RiskRewardAgent(
        market, LIMITS.risk_reward, LIMITS.risk_reward.min_stop_atr_multiple, stats or seeded_stats()
    )


def test_rr_passes_a_good_setup_and_rejects_bad_ones() -> None:
    market, days = setup()
    as_of = eod(days[-1])
    close = float(market.raw_bars(AAA, Timeframe.D1, as_of)[-1].close)
    plan = LiquidityAgent(market, LIMITS.liquidity).plan(uuid4(), AAA, 100, as_of)
    assert plan is not None
    good = rr_agent(market).evaluate(
        buy(), chart(close - 6, (close + 12, close + 18), atr=2), plan, 100, [], as_of
    )
    assert (
        good.passed
        and good.rr_after_costs > 2
        and good.expected_value_per_unit > 0
        and good.p_win_source == "blend"
    )
    no_edge = rr_agent(market, SetupStats()).evaluate(
        buy(), chart(close - 6, (close + 12, close + 18), atr=2), plan, 100, [], as_of
    )
    assert RRRejection.NEGATIVE_EV in no_edge.rejections  # driftless model: no measured edge, no trade
    near = rr_agent(market).evaluate(buy(), chart(close - 6, (close + 2,), atr=2), plan, 100, [], as_of)
    assert RRRejection.BELOW_MIN_RR in near.rejections
    noise = rr_agent(market).evaluate(
        buy(), chart(close - 0.5, (close + 2, close + 3), atr=2), plan, 100, [], as_of
    )
    assert RRRejection.STOP_INSIDE_NOISE in noise.rejections
    flag = EventRiskFlag(
        flag_id="f",
        event_type=EventType.EARNINGS,
        instrument=AAA,
        window_start=as_of + timedelta(days=2),
        window_end=as_of + timedelta(days=3),
        block_new_entries=True,
        reason="results",
    )
    event = rr_agent(market).evaluate(
        buy(), chart(close - 6, (close + 12, close + 18)), plan, 100, [flag], as_of
    )
    assert RRRejection.EVENT_RISK in event.rejections
    missing = rr_agent(market).evaluate(buy(), None, plan, 100, [], as_of)
    assert missing.rejections == (RRRejection.MISSING_LEVELS,)


def risk_setup(stage: Stage = Stage.BACKTEST) -> tuple[RiskAgent, InMemoryStateStore, MarketData, list]:  # type: ignore[type-arg]
    market, days = setup()
    state = InMemoryStateStore()
    return (
        RiskAgent(market, LIMITS, state, TokenSigner(KEY), stage=stage, min_analysts=2),
        state,
        market,
        days,
    )


def gated(market: MarketData, days: list, decision: BossDecision):  # type: ignore[no-untyped-def, type-arg]
    as_of = eod(days[-1])
    close = float(market.raw_bars(AAA, Timeframe.D1, as_of)[-1].close)
    c = chart(close - 6, (close + 12, close + 18), atr=2)
    plan = LiquidityAgent(market, LIMITS.liquidity).plan(decision.decision_id, AAA, 1000, as_of)
    verdict = rr_agent(market).evaluate(decision, c, plan, 1000, [], as_of)  # type: ignore[arg-type]
    return c, plan, verdict, as_of, close


def test_risk_sizes_signs_and_respects_the_budget() -> None:
    agent, _, market, days = risk_setup()
    decision = buy(budget=0.5)
    c, plan, verdict, as_of, close = gated(market, days, decision)
    outcome = agent.assess(
        decision,
        chart=c,
        plan=plan,
        verdict=verdict,
        portfolio=flat_portfolio(as_of),
        flags=[],
        live_analysts=3,
        cycle_id=uuid4(),
        as_of=as_of,
    )
    assert outcome.approval.approved and outcome.instruction is not None
    risk_used = outcome.instruction.quantity * (6 + verdict.costs_per_unit) / 1_000_000
    assert 0 < risk_used <= 0.01 * 0.5 + 1e-9  # half the 1% budget
    assert outcome.instruction.protective_stop is not None and float(
        outcome.instruction.protective_stop
    ) == pytest.approx(close - 6, abs=0.05)
    assert outcome.instruction.approval.instruction_digest == outcome.instruction.digest()


def test_frozen_trading_blocks_entries_but_not_exits() -> None:
    agent, state, market, days = risk_setup()
    state.set_frozen(uuid4(), eod(days[-1]))
    decision = buy()
    c, plan, verdict, as_of, close = gated(market, days, decision)
    entry = agent.assess(
        decision,
        chart=c,
        plan=plan,
        verdict=verdict,
        portfolio=flat_portfolio(as_of),
        flags=[],
        live_analysts=3,
        cycle_id=uuid4(),
        as_of=as_of,
    )
    assert not entry.approval.approved
    assert any(r.rule is RiskRule.TRADING_FROZEN and not r.passed for r in entry.approval.results)
    held = PortfolioSnapshot(
        as_of=as_of,
        equity=1e6,
        cash=9e5,
        day_start_equity=1e6,
        peak_equity=1e6,
        holdings=(Holding(AAA, 500, close),),
    )
    exit_ = BossDecision.model_validate(
        {**decision.model_dump(), "decision_id": uuid4(), "action": Action.SELL, "risk_budget_fraction": 0.0}
    )
    out = agent.assess(
        exit_,
        chart=None,
        plan=None,
        verdict=None,
        portfolio=held,
        flags=[],
        live_analysts=3,
        cycle_id=uuid4(),
        as_of=as_of,
    )
    assert out.approval.approved and out.instruction is not None and out.instruction.quantity == 500


def test_failed_gates_cannot_be_skipped() -> None:
    agent, _, market, days = risk_setup()
    decision = buy()
    c, plan, verdict, as_of, _ = gated(market, days, decision)
    failed = verdict.model_copy(update={"passed": False, "rejections": (RRRejection.BELOW_MIN_RR,)})
    for bad_verdict in (None, failed, verdict.model_copy(update={"decision_id": uuid4()})):
        out = agent.assess(
            decision,
            chart=c,
            plan=plan,
            verdict=bad_verdict,
            portfolio=flat_portfolio(as_of),
            flags=[],
            live_analysts=3,
            cycle_id=uuid4(),
            as_of=as_of,
        )
        assert not out.approval.approved and out.approval.results[0].rule is RiskRule.GATE_NOT_PASSED


def test_drawdown_breach_raises_alert_and_freezes() -> None:
    agent, state, market, days = risk_setup()
    decision = buy()
    c, plan, verdict, as_of, _ = gated(market, days, decision)
    hurt = PortfolioSnapshot(
        as_of=as_of, equity=960_000, cash=960_000, day_start_equity=1_000_000, peak_equity=1_000_000
    )
    out = agent.assess(
        decision,
        chart=c,
        plan=plan,
        verdict=verdict,
        portfolio=hurt,
        flags=[],
        live_analysts=3,
        cycle_id=uuid4(),
        as_of=as_of,
    )
    assert not out.approval.approved and out.alerts and out.alerts[0].freeze_new_orders
    assert state.freeze_status().frozen


def test_coverage_positions_sector_and_human_approval_rules() -> None:
    agent, _, market, days = risk_setup()
    decision = buy()
    c, plan, verdict, as_of, _close = gated(market, days, decision)

    def failed(risk: RiskAgent = agent, **kw: Any) -> set[RiskRule]:
        args: dict[str, Any] = {
            "chart": c,
            "plan": plan,
            "verdict": verdict,
            "portfolio": flat_portfolio(as_of),
            "flags": [],
            "live_analysts": 3,
            "cycle_id": uuid4(),
            "as_of": as_of,
        }
        args.update(kw)
        out = risk.assess(decision, **args)
        return {r.rule for r in out.approval.results if not r.passed}

    assert RiskRule.DEGRADED_COVERAGE in failed(live_analysts=1)
    many = tuple(Holding(equity(f"H{i}"), 10, 100.0) for i in range(LIMITS.portfolio.max_open_positions))
    assert RiskRule.MAX_OPEN_POSITIONS in failed(
        portfolio=PortfolioSnapshot(
            as_of=as_of, equity=1e6, cash=9e5, day_start_equity=1e6, peak_equity=1e6, holdings=many
        )
    )
    sector_heavy = (Holding(equity("BNK"), 2_500, 100.0, "BANK"),)
    assert RiskRule.SECTOR_LIMIT in failed(
        sector="BANK",
        portfolio=PortfolioSnapshot(
            as_of=as_of, equity=1e6, cash=7.5e5, day_start_equity=1e6, peak_equity=1e6, holdings=sector_heavy
        ),
    )
    live_agent = RiskAgent(
        market,
        LIMITS,
        InMemoryStateStore(),
        TokenSigner(KEY),
        stage=Stage.LIVE_HUMAN_APPROVAL,
        min_analysts=2,
    )
    assert RiskRule.HUMAN_APPROVAL_PENDING in failed(live_agent)
    assert not failed(live_agent, human_approved=True)


def test_limit_multiplier_tightens() -> None:
    p = flat_portfolio(eod(date(2026, 1, 5)))
    base = limit_multiplier(
        LIMITS, missing_analysts=0, trend_missing=False, exposure_multiplier=1.0, portfolio=p
    )
    one = limit_multiplier(
        LIMITS, missing_analysts=1, trend_missing=False, exposure_multiplier=1.0, portfolio=p
    )
    trendless = limit_multiplier(
        LIMITS, missing_analysts=1, trend_missing=True, exposure_multiplier=1.0, portfolio=p
    )
    hurt = PortfolioSnapshot(
        as_of=p.as_of, equity=975_000, cash=975_000, day_start_equity=1e6, peak_equity=1e6
    )
    dd = limit_multiplier(
        LIMITS, missing_analysts=0, trend_missing=False, exposure_multiplier=1.0, portfolio=hurt
    )
    assert base == 1.0 and one == pytest.approx(0.75) and trendless == pytest.approx(0.375) and dd < 1.0
