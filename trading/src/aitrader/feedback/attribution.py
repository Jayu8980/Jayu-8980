"""Attribution agent (deterministic): scores the analysts and closes the setup-edge loop.

Signals are scored once their outcome is known, never before:
- chart and smart money: the sign of the score against the instrument's return over the
  next ``horizon`` sessions (signals with |score| < 0.2 do not count as calls);
- trend: the most likely scenario against the realised one (index return over
  ``trend_horizon`` sessions, +/-``band``), plus the multiclass Brier score.

Each agent's credibility is the beta-binomial posterior of its hit rate
(``attribution.shrink_credibility``), from the regime it was in when the signal was issued;
when that regime has fewer than ``min_regime_sample`` scored calls, from all regimes. It is
published as an ``AgentScore`` and written into the decision cycle's credibility map, which the
boss's consensus weights read.

Round trips are reconstructed from fills: when a position opened by a boss decision returns
to flat, its realised R multiple (profit per unit over the initial risk per unit, at the
signed stop) is recorded in the R:R agent's ``SetupStats`` under the setup that triggered it.
That is how paper trading supplies the measured edge the R:R gate requires.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta
from typing import Annotated
from uuid import UUID, uuid5

import numpy as np
from pydantic import Field

from aitrader.agents.data.agent import MarketData, index_instrument
from aitrader.agents.publishing import publish
from aitrader.agents.risk_reward.agent import SetupStats
from aitrader.agents.trend.agent import SCENARIOS
from aitrader.bus.interface import MessageBus
from aitrader.evaluation.evaluators import realised_scenario
from aitrader.evaluation.scoring import ic
from aitrader.schemas.base import AgentId, Instrument, StrictModel, Timeframe, UtcDatetime
from aitrader.schemas.decision import BossDecision
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import AgentScore
from aitrader.schemas.orders import Fill, OrderInstruction, Side
from aitrader.schemas.regime import RegimeLabel, RegimeOutlook
from aitrader.schemas.signals import ChartSignal, SetupType, SmartMoneySignal
from aitrader.tools.stats import shrink_credibility

_NS = UUID("c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f")
CALL_THRESHOLD = 0.2


class SignalRecord(StrictModel):
    agent_id: AgentId
    instrument: Instrument
    at: UtcDatetime
    score: float
    regime: RegimeLabel


class OutlookRecord(StrictModel):
    at: UtcDatetime
    probabilities: tuple[float, ...]  # in SCENARIOS order
    regime: RegimeLabel


class OpenTrip(StrictModel):
    instrument: Instrument
    setup: SetupType | None
    direction: int  # +1 long, -1 short
    quantity: int
    entry_notional: float
    exit_quantity: int = 0
    exit_notional: float = 0.0
    risk_per_unit: Annotated[float, Field(gt=0)] | None = None


class ClosedTrip(StrictModel):
    instrument: Instrument
    setup: SetupType | None
    r_multiple: float | None
    pnl: float
    closed_at: UtcDatetime


class ScoredSignal(StrictModel):
    signal: SignalRecord
    forward_return: float


class ScoredOutlook(StrictModel):
    outlook: OutlookRecord
    realised: int  # index into SCENARIOS


class AttributionState(StrictModel):
    pending: tuple[SignalRecord, ...] = ()
    scored: tuple[ScoredSignal, ...] = ()
    pending_outlooks: tuple[OutlookRecord, ...] = ()
    scored_outlooks: tuple[ScoredOutlook, ...] = ()
    decision_setups: dict[str, SetupType] = Field(default_factory=dict)
    instructions: dict[str, tuple[str, float | None]] = Field(default_factory=dict)  # -> (decision, stop)
    trips: dict[str, OpenTrip] = Field(default_factory=dict)
    closed: tuple[ClosedTrip, ...] = ()
    seen_fills: tuple[str, ...] = ()
    regime: RegimeLabel = RegimeLabel.UNKNOWN


class AttributionAgent:
    def __init__(
        self,
        market: MarketData,
        stats: SetupStats,
        credibility: dict[AgentId, float],
        *,
        horizon: int = 5,
        trend_horizon: int = 20,
        band: float = 0.03,
        prior_strength: float = 20.0,
        min_regime_sample: int = 10,
    ) -> None:
        self._market = market
        self._stats = stats
        self._credibility = credibility  # the decision cycle's map, updated in place
        self._horizon = horizon
        self._trend_horizon = trend_horizon
        self._band = band
        self._prior_strength = prior_strength
        self._min_regime_sample = min_regime_sample
        self._chart_setups: dict[tuple[UUID | None, str], SetupType] = {}
        self.scores: list[AgentScore] = []
        self.restore(AttributionState(), record_stats=False)

    # ------------------------------------------------------------ state
    def state(self) -> AttributionState:
        return AttributionState(
            pending=tuple(self._pending),
            scored=tuple(self._scored),
            pending_outlooks=tuple(self._pending_outlooks),
            scored_outlooks=tuple(self._scored_outlooks),
            decision_setups=dict(self._decision_setups),
            instructions=dict(self._instructions),
            trips=dict(self._trips),
            closed=tuple(self._closed),
            seen_fills=tuple(self._seen_fills),
            regime=self._regime,
        )

    def restore(self, state: AttributionState, *, record_stats: bool = True) -> None:
        """Load a checkpoint. Realised R multiples of closed trips are replayed into the
        setup statistics, which are not checkpointed separately."""
        self._pending = list(state.pending)
        self._scored = list(state.scored)
        self._pending_outlooks = list(state.pending_outlooks)
        self._scored_outlooks = list(state.scored_outlooks)
        self._decision_setups = dict(state.decision_setups)
        self._instructions = dict(state.instructions)
        self._trips = dict(state.trips)
        self._closed = list(state.closed)
        self._seen_fills = set(state.seen_fills)
        self._regime = state.regime
        if record_stats:
            for trip in self._closed:
                if trip.setup is not None and trip.r_multiple is not None:
                    self._stats.record(trip.setup, trip.r_multiple)

    @property
    def closed_trips(self) -> tuple[ClosedTrip, ...]:
        return tuple(self._closed)

    # ------------------------------------------------------------ bus
    def attach(self, bus: MessageBus) -> None:
        async def on_message(envelope: Envelope) -> None:
            self.observe(envelope)

        bus.subscribe(
            (
                Topic.REGIME_OUTLOOK,
                Topic.SIGNALS_CHART,
                Topic.SIGNALS_SMARTMONEY,
                Topic.BOSS_DECISION,
                Topic.ORDERS_INSTRUCTIONS,
                Topic.ORDERS_FILLS,
            ),
            on_message,
            group="attribution",
        )

    def observe(self, envelope: Envelope) -> None:
        p = envelope.payload
        if isinstance(p, RegimeOutlook):
            self._regime = p.regime
            by_name = {sc.name: sc.probability for sc in p.scenarios}
            if all(name in by_name for name in SCENARIOS):
                self._pending_outlooks.append(
                    OutlookRecord(
                        at=envelope.available_at,
                        probabilities=tuple(by_name[n] for n in SCENARIOS),
                        regime=p.regime,
                    )
                )
        elif isinstance(p, ChartSignal | SmartMoneySignal):
            self._pending.append(
                SignalRecord(
                    agent_id=envelope.agent_id,
                    instrument=p.instrument,
                    at=envelope.available_at,
                    score=p.score,
                    regime=self._regime,
                )
            )
            if isinstance(p, ChartSignal):
                self._chart_setups[(envelope.cycle_id, p.instrument.key)] = p.setup
        elif isinstance(p, BossDecision):
            setup = self._chart_setups.get((envelope.cycle_id, p.instrument.key))
            if setup is not None and setup is not SetupType.NONE:
                self._decision_setups[str(p.decision_id)] = setup
        elif isinstance(p, OrderInstruction):
            stop = float(p.protective_stop) if p.protective_stop is not None else None
            self._instructions[str(p.instruction_id)] = (str(p.decision_id), stop)
        elif isinstance(p, Fill):
            self._on_fill(p)

    def _on_fill(self, fill: Fill) -> None:
        if fill.broker_trade_id in self._seen_fills:
            return
        self._seen_fills.add(fill.broker_trade_id)
        key = fill.instrument.key
        signed = fill.quantity if fill.side is Side.BUY else -fill.quantity
        price = float(fill.price)
        trip = self._trips.get(key)
        if trip is None:
            decision_id, stop = self._instructions.get(str(fill.instruction_id), ("", None))
            risk = abs(price - stop) if stop is not None and abs(price - stop) > 0 else None
            self._trips[key] = OpenTrip(
                instrument=fill.instrument,
                setup=self._decision_setups.get(decision_id),
                direction=1 if signed > 0 else -1,
                quantity=abs(signed),
                entry_notional=abs(signed) * price,
                risk_per_unit=risk,
            )
            return
        if (signed > 0) == (trip.direction > 0):  # adding to the position
            self._trips[key] = trip.model_copy(
                update={
                    "quantity": trip.quantity + abs(signed),
                    "entry_notional": trip.entry_notional + abs(signed) * price,
                }
            )
            return
        trip = trip.model_copy(
            update={
                "exit_quantity": trip.exit_quantity + abs(signed),
                "exit_notional": trip.exit_notional + abs(signed) * price,
            }
        )
        self._trips[key] = trip
        if trip.exit_quantity < trip.quantity:
            return
        entry = trip.entry_notional / trip.quantity
        exit_ = trip.exit_notional / trip.exit_quantity
        per_unit = (exit_ - entry) * trip.direction
        r = per_unit / trip.risk_per_unit if trip.risk_per_unit else None
        self._closed.append(
            ClosedTrip(
                instrument=trip.instrument,
                setup=trip.setup,
                r_multiple=r,
                pnl=per_unit * trip.quantity,
                closed_at=fill.filled_at,
            )
        )
        if r is not None and trip.setup is not None:
            self._stats.record(trip.setup, r)
        del self._trips[key]

    # ------------------------------------------------------------ scoring
    def _forward_return(
        self, instrument: Instrument, at: datetime, now: datetime, horizon: int
    ) -> float | None:
        bars = self._market.bars(instrument, Timeframe.D1, now, start=at - timedelta(days=10)).bars
        before = [b for b in bars if b.bar_end <= at]
        after = [b for b in bars if b.bar_end > at]
        if not before or len(after) < horizon:
            return None
        return float(after[horizon - 1].close) / float(before[-1].close) - 1

    def _mature(self, now: datetime) -> None:
        """Move signals and outlooks whose outcome is now known to the scored lists. Each is
        scored exactly once; outcomes are never revised."""
        still = []
        for sig in self._pending:
            ret = self._forward_return(sig.instrument, sig.at, now, self._horizon)
            if ret is None:
                still.append(sig)
            else:
                self._scored.append(ScoredSignal(signal=sig, forward_return=ret))
        self._pending = still
        index = index_instrument("NIFTY_50")
        waiting = []
        for o in self._pending_outlooks:
            ret = self._forward_return(index, o.at, now, self._trend_horizon)
            if ret is None:
                waiting.append(o)
            else:
                self._scored_outlooks.append(
                    ScoredOutlook(outlook=o, realised=realised_scenario(ret, self._band))
                )
        self._pending_outlooks = waiting

    def _credibility_of(
        self, calls: list[tuple[RegimeLabel, bool]], regime: RegimeLabel
    ) -> tuple[float, int, int]:
        in_regime = [hit for r, hit in calls if r is regime]
        sample = in_regime if len(in_regime) >= self._min_regime_sample else [hit for _, hit in calls]
        cred = shrink_credibility(
            successes=sum(sample), trials=len(sample), prior_strength=self._prior_strength
        ).credibility
        return cred, sum(sample), len(sample)

    async def score(self, bus: MessageBus, now: datetime) -> list[AgentScore]:
        """Score every agent on the signals whose outcome is known at ``now``, publish the
        scores and update the boss's credibility map."""
        self._mature(now)
        regime = self._regime
        out: list[AgentScore] = []
        by_agent: dict[AgentId, list[ScoredSignal]] = defaultdict(list)
        for scored in self._scored:
            by_agent[scored.signal.agent_id].append(scored)
        for agent in sorted(by_agent, key=lambda a: a.value):
            matured = by_agent[agent]
            calls = [
                (m.signal.regime, (m.signal.score > 0) == (m.forward_return > 0))
                for m in matured
                if abs(m.signal.score) >= CALL_THRESHOLD and m.forward_return != 0
            ]
            if not calls:
                continue
            cred, hits, trials = self._credibility_of(calls, regime)
            out.append(
                AgentScore(
                    agent_id=agent,
                    regime=regime,
                    window_start=matured[0].signal.at,
                    window_end=matured[-1].signal.at,
                    sample_size=trials,
                    hit_rate=hits / trials,
                    information_coefficient=ic(
                        [m.signal.score for m in matured], [m.forward_return for m in matured]
                    ),
                    credibility=cred,
                )
            )
        trend = self._score_trend(regime)
        if trend is not None:
            out.append(trend)
        for score in out:
            self._credibility[score.agent_id] = score.credibility
            await publish(
                bus,
                message_id=uuid5(_NS, f"{score.agent_id.value}|{now.isoformat()}|{score.sample_size}"),
                topic=Topic.FEEDBACK_SCORES,
                agent_id=AgentId.ATTRIBUTION,
                payload=score,
                available_at=now,
                produced_at=now,
            )
        self.scores = out
        return out

    def _score_trend(self, regime: RegimeLabel) -> AgentScore | None:
        scored = self._scored_outlooks
        if not scored:
            return None
        calls = [(s.outlook.regime, int(np.argmax(s.outlook.probabilities)) == s.realised) for s in scored]
        cred, hits, trials = self._credibility_of(calls, regime)
        probs = np.array([s.outlook.probabilities for s in scored])
        onehot = np.eye(len(SCENARIOS))[[s.realised for s in scored]]
        brier = float(np.mean(np.sum((probs - onehot) ** 2, axis=1))) / 2  # multiclass Brier, scaled to 0..1
        return AgentScore(
            agent_id=AgentId.TREND,
            regime=regime,
            window_start=scored[0].outlook.at,
            window_end=scored[-1].outlook.at,
            sample_size=trials,
            hit_rate=hits / trials,
            brier_score=min(max(brier, 0.0), 1.0),
            credibility=cred,
        )
