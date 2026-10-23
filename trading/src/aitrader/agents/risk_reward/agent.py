"""Risk-to-reward agent (deterministic).

For an opening trade it computes, per unit:
- risk = |entry - structural stop|; reward = the blended distance to the targets (equal
  scale-out at each);
- costs = round-trip Indian costs at the plan's size + estimated impact on both sides;
- P(win) = probability of touching the target before the stop under a zero-drift GBM with
  the instrument's recent volatility, blended with the realised win rate of the setup once
  trades exist (``setup_prior_strength`` pseudo-trades of the model);
- R:R after costs = (reward - costs) / (risk + costs);
- EV = P(win) x reward - (1 - P(win)) x risk - costs.

Hard gates (any one rejects):
- negative EV, whatever the ratio;
- R:R below the setup's minimum;
- a stop tighter than ``min_stop_atr_multiple`` ATRs;
- an entry-blocking event inside the holding horizon;
- gap risk: the 95th-percentile overnight gap is at least the stop distance;
- missing levels.

Realised R multiples are recorded per setup, so the win rate it uses tracks reality.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from datetime import datetime, timedelta

import numpy as np

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import ist_date
from aitrader.config.settings import RiskRewardLimits
from aitrader.schemas.base import InstrumentType, Timeframe
from aitrader.schemas.decision import BossDecision
from aitrader.schemas.gates import LiquidityPlan, RRRejection, RRVerdict
from aitrader.schemas.news import EventRiskFlag
from aitrader.schemas.signals import ChartSignal, SetupType
from aitrader.tools.costs import round_trip
from aitrader.tools.derivatives import barrier_hit_probability

_SEGMENT = {
    InstrumentType.EQUITY: "equity_delivery",
    InstrumentType.FUTURE: "futures",
    InstrumentType.OPTION: "options",
}


def blocking_flags(
    flags: Sequence[EventRiskFlag], instrument_key: str, start: datetime, end: datetime
) -> list[EventRiskFlag]:
    """Entry-blocking flags for the instrument (or market-wide) overlapping [start, end]."""
    return [
        f
        for f in flags
        if f.block_new_entries
        and (f.instrument is None or f.instrument.key == instrument_key)
        and f.window_start <= end
        and f.window_end >= start
    ]


class SetupStats:
    """Realised R multiples per setup type (recorded when trades close).

    With no history, P(win) comes from a driftless barrier model, which by construction gives
    every trade an EV of minus its costs: no measured edge, no trade. Seed the stats from an
    offline evaluation on an earlier window (``from_evaluation``) or a paper-trading record."""

    def __init__(self) -> None:
        self._r: dict[SetupType, list[float]] = defaultdict(list)

    @classmethod
    def from_evaluation(cls, by_setup: dict[str, dict[str, float | None]]) -> SetupStats:
        """From ``AgentEvaluation.by_group`` of the chart evaluator (trades, win_rate, avg_r)."""
        stats = cls()
        for name, row in by_setup.items():
            trades, win_rate = row.get("trades"), row.get("win_rate")
            if not trades or win_rate is None:
                continue
            wins = round(trades * win_rate)
            for i in range(int(trades)):  # only the win rate is used, so +/-1R stand-ins suffice
                stats.record(SetupType(name), 1.0 if i < wins else -1.0)
        return stats

    def record(self, setup: SetupType, r_multiple: float) -> None:
        self._r[setup].append(r_multiple)

    def win_rate(self, setup: SetupType) -> tuple[float | None, int]:
        rs = self._r.get(setup, [])
        return (float(np.mean([r > 0 for r in rs])) if rs else None), len(rs)


class RiskRewardAgent:
    def __init__(
        self,
        market: MarketData,
        limits: RiskRewardLimits,
        min_stop_atr: float,
        stats: SetupStats | None = None,
    ):
        self._market = market
        self._limits = limits
        self._min_stop_atr = min_stop_atr
        self.stats = stats or SetupStats()

    def evaluate(
        self,
        decision: BossDecision,
        chart: ChartSignal | None,
        plan: LiquidityPlan,
        quantity: int,
        flags: Sequence[EventRiskFlag],
        as_of: datetime,
    ) -> RRVerdict:
        inst = decision.instrument
        setup = chart.setup if chart else SetupType.NONE
        missing = chart is None or chart.structural_stop is None or not chart.targets
        bars = self._market.bars(inst, Timeframe.D1, as_of, start=as_of - timedelta(days=400)).bars
        entry = float(bars[-1].close) if bars else 0.0
        if missing or entry <= 0 or chart is None or chart.structural_stop is None:
            return self._reject(decision, setup, [RRRejection.MISSING_LEVELS])
        stop = float(chart.structural_stop)
        direction = 1 if stop < entry else -1
        risk = (entry - stop) * direction
        # Blended reward: equal scale-out at each target; P(win) is for reaching that level.
        reward = float(np.mean([(float(t) - entry) * direction for t in chart.targets]))
        target = entry + direction * reward
        if risk <= 0 or reward <= 0:
            return self._reject(decision, setup, [RRRejection.MISSING_LEVELS])
        qty = max(quantity, inst.lot_size)
        day = ist_date(as_of)
        rt = round_trip(
            segment=_SEGMENT[inst.instrument_type],  # type: ignore[arg-type]
            direction="LONG" if direction > 0 else "SHORT",
            entry_price=entry,
            exit_price=target,
            quantity=qty,
            entry_date=day,
            exit_date=day,
            slippage_bps_each_side=plan.est_impact_bps,
        )
        costs = rt.per_unit
        closes = np.array([float(b.close) for b in bars[-61:]])
        daily_vol = float(np.std(np.diff(np.log(closes)), ddof=1)) if len(closes) > 2 else 0.02
        annual_vol = min(max(daily_vol * np.sqrt(252), 0.01), 5.0)
        # No-edge prior: price is a martingale (zero drift), so by optional stopping a trade with
        # no measured edge has EV of exactly minus its costs.
        model_p = barrier_hit_probability(
            spot=entry, target=target, stop=stop, annual_vol=annual_vol, annual_drift=0.0
        ).p_target_first
        hist, n = self.stats.win_rate(setup)
        k = self._limits.setup_prior_strength
        p_win = (n * hist + k * model_p) / (n + k) if hist is not None else model_p
        source = "blend" if hist is not None else "barrier_model"
        ev = p_win * reward - (1 - p_win) * risk - costs
        rr = (reward - costs) / (risk + costs)
        atr = chart.atr
        rejections: list[RRRejection] = []
        if ev <= 0:
            rejections.append(RRRejection.NEGATIVE_EV)
        minimum = self._limits.min_rr_after_costs.get(setup)
        if minimum is not None and rr < minimum:
            rejections.append(RRRejection.BELOW_MIN_RR)
        if risk / atr < self._min_stop_atr:
            rejections.append(RRRejection.STOP_INSIDE_NOISE)
        horizon_end = as_of + timedelta(days=int(self._limits.holding_sessions * 7 / 5) + 1)
        if blocking_flags(flags, inst.key, as_of, horizon_end):
            rejections.append(RRRejection.EVENT_RISK)
        opens = np.array([float(b.open) for b in bars[1:]])
        prev_closes = np.array([float(b.close) for b in bars[:-1]])
        if len(opens) >= 20:
            gap_p95 = float(np.quantile(np.abs(opens / prev_closes - 1), 0.95))
            if gap_p95 * entry >= risk:
                rejections.append(RRRejection.GAP_RISK)
        return RRVerdict(
            decision_id=decision.decision_id,
            instrument=inst,
            setup=setup,
            passed=not rejections,
            p_win=round(p_win, 6),
            p_win_source=source,
            reward_per_unit=reward,
            risk_per_unit=risk,
            costs_per_unit=costs,
            rr_after_costs=rr,
            expected_value_per_unit=ev,
            stop_distance_atr=risk / atr,
            rejections=tuple(rejections),
        )

    @staticmethod
    def _reject(decision: BossDecision, setup: SetupType, reasons: list[RRRejection]) -> RRVerdict:
        return RRVerdict(
            decision_id=decision.decision_id,
            instrument=decision.instrument,
            setup=setup,
            passed=False,
            p_win=0.0,
            p_win_source="barrier_model",
            reward_per_unit=0.0,
            risk_per_unit=1e-9,
            costs_per_unit=0.0,
            rr_after_costs=0.0,
            expected_value_per_unit=0.0,
            stop_distance_atr=0.0,
            rejections=tuple(reasons),
        )
