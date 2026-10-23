"""Everything the boss sees for one instrument in one cycle."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from uuid import UUID

from aitrader.agents.risk.portfolio import Holding
from aitrader.schemas.base import AgentId, Instrument
from aitrader.schemas.debate import DebateOutput
from aitrader.schemas.news import EventRiskFlag, NewsEvent
from aitrader.schemas.regime import RegimeOutlook
from aitrader.schemas.signals import ChartSignal, SmartMoneySignal
from aitrader.tools.attribution import AgentSignal


@dataclass(frozen=True)
class BossInput:
    instrument: Instrument
    as_of: datetime
    chart: ChartSignal | None = None
    smart_money: SmartMoneySignal | None = None
    news: Sequence[NewsEvent] = ()
    regime: RegimeOutlook | None = None
    flags: Sequence[EventRiskFlag] = ()
    holding: Holding | None = None
    debate: DebateOutput | None = None
    credibility: Mapping[AgentId, float] = field(default_factory=dict)
    discretion_cap: float = 1.0
    message_ids: Mapping[str, UUID] = field(default_factory=dict)  # agent -> source message id

    def signals(self) -> list[AgentSignal]:
        """Analyst views as comparable (score, confidence) pairs. News decays with its
        half-life; the regime contributes its up-minus-down scenario probability."""
        out = []
        if self.chart is not None:
            out.append(
                AgentSignal(
                    agent=AgentId.CHART.value, score=self.chart.score, confidence=self.chart.confidence
                )
            )
        if self.smart_money is not None:
            out.append(
                AgentSignal(
                    agent=AgentId.SMART_MONEY.value,
                    score=self.smart_money.score,
                    confidence=self.smart_money.confidence,
                )
            )
        if self.news:
            weighted, mass = 0.0, 0.0
            for e in self.news:
                age = max((self.as_of - e.first_published_at).total_seconds(), 0.0)
                decay = 0.5 ** (age / e.half_life.total_seconds())
                w = e.materiality * e.credibility * decay
                weighted += w * e.sentiment
                mass += w
            if mass > 0:
                out.append(
                    AgentSignal(
                        agent=AgentId.NEWS.value,
                        score=max(-1.0, min(1.0, weighted / mass)),
                        confidence=min(mass, 1.0),
                    )
                )
        if self.regime is not None:
            p = {s.name: s.probability for s in self.regime.scenarios}
            score = p.get("uptrend", 0.0) - p.get("correction", 0.0)
            out.append(
                AgentSignal(
                    agent=AgentId.TREND.value,
                    score=max(-1.0, min(1.0, score)),
                    confidence=self.regime.confidence,
                )
            )
        return out

    def weights(self) -> dict[str, float]:
        return {s.agent: self.credibility.get(AgentId(s.agent), 0.5) for s in self.signals()}


def finite(x: float) -> float:
    return x if math.isfinite(x) else 0.0
