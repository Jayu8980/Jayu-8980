"""Running per-agent accuracy for monitoring: each published chart and smart-money signal is
scored against the instrument's realised return over the following ``horizon`` sessions,
once that return is known. Hit rate counts signals with |score| >= 0.2; the information
coefficient needs at least 10 matured signals. This is a monitoring view, not the
attribution agent: it does not shrink toward a prior or split by regime."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta

from aitrader.agents.data.agent import MarketData
from aitrader.bus.interface import MessageBus
from aitrader.evaluation.scoring import hit_rate, ic
from aitrader.schemas.base import AgentId, Instrument, Timeframe
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.signals import ChartSignal, SmartMoneySignal


@dataclass(frozen=True)
class LiveScore:
    agent_id: AgentId
    signals: int
    matured: int
    hit_rate: float | None
    information_coefficient: float | None


class LiveScorer:
    def __init__(self, market: MarketData, horizon: int = 5) -> None:
        self._market = market
        self._horizon = horizon
        self._signals: dict[AgentId, list[tuple[Instrument, datetime, float]]] = defaultdict(list)

    def attach(self, bus: MessageBus) -> None:
        async def on_signal(envelope: Envelope) -> None:
            payload = envelope.payload
            if isinstance(payload, ChartSignal | SmartMoneySignal):
                self._signals[envelope.agent_id].append(
                    (payload.instrument, envelope.available_at, payload.score)
                )

        bus.subscribe((Topic.SIGNALS_CHART, Topic.SIGNALS_SMARTMONEY), on_signal, group="live_scores")

    def _forward_return(self, instrument: Instrument, at: datetime, now: datetime) -> float | None:
        bars = self._market.bars(instrument, Timeframe.D1, now, start=at - timedelta(days=10)).bars
        before = [b for b in bars if b.bar_end <= at]
        after = [b for b in bars if b.bar_end > at]
        if not before or len(after) < self._horizon:
            return None
        return float(after[self._horizon - 1].close) / float(before[-1].close) - 1

    def scores(self, now: datetime) -> list[LiveScore]:
        out = []
        for agent in sorted(self._signals, key=lambda a: a.value):
            preds, outcomes = [], []
            for inst, at, score in self._signals[agent]:
                ret = self._forward_return(inst, at, now)
                if ret is not None:
                    preds.append(score)
                    outcomes.append(ret)
            rate, _ = hit_rate(preds, outcomes)
            out.append(
                LiveScore(
                    agent_id=agent,
                    signals=len(self._signals[agent]),
                    matured=len(preds),
                    hit_rate=rate,
                    information_coefficient=ic(preds, outcomes),
                )
            )
        return out
