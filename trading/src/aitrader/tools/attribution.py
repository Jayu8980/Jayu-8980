"""Attribution tools: credibility weights and the credibility-weighted consensus that the
boss agent starts from (and is scored against)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from aitrader.tools.registry import tool
from aitrader.tools.types import Score, ToolOutput, Unit


class AgentSignal(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    agent: Annotated[str, Field(min_length=1, max_length=40)]
    score: Score
    confidence: Unit


class Weights(ToolOutput):
    weights: dict[str, float]  # sums to 1


@tool(
    "attribution.credibility_weights",
    "1.0.0",
    example={"credibility": {"chart": 0.6, "smart_money": 0.5, "news": 0.4}},
)
def credibility_weights(
    credibility: Annotated[Mapping[str, Unit], Field(min_length=1)], floor: Unit = 0.05
) -> Weights:
    """Weight by credibility above a 0.4 'coin-flip' level, with a small floor so an agent
    with a poor record is down-weighted, not silenced."""
    raw = {k: max(v - 0.4, floor) for k, v in credibility.items()}
    total = sum(raw.values())
    return Weights(weights={k: v / total for k, v in raw.items()})


class Consensus(ToolOutput):
    consensus: float  # -1..1, credibility- and confidence-weighted
    agreement: float  # share of weighted conviction on the consensus side
    sources: int
    dissenters: tuple[str, ...]  # agents with a meaningful opposite view


@tool(
    "attribution.weighted_consensus",
    "1.0.0",
    example={
        "signals": [
            {"agent": "chart", "score": 0.6, "confidence": 0.7},
            {"agent": "smart_money", "score": -0.2, "confidence": 0.5},
        ],
        "weights": {"chart": 0.6, "smart_money": 0.4},
    },
)
def weighted_consensus(
    signals: Annotated[Sequence[AgentSignal], Field(min_length=1)],
    weights: Mapping[str, Annotated[float, Field(ge=0)]],
    dissent_threshold: Unit = 0.3,
) -> Consensus:
    if any(s.agent not in weights for s in signals):
        raise ValueError("every signal needs a weight")
    mass = [(s, weights[s.agent] * s.confidence) for s in signals]
    total = sum(m for _, m in mass)
    consensus = sum(m * s.score for s, m in mass) / total if total > 0 else 0.0
    conviction = sum(m * abs(s.score) for s, m in mass)
    same_side = sum(m * abs(s.score) for s, m in mass if s.score * consensus > 0)
    agreement = same_side / conviction if conviction > 0 else 0.0
    dissent = tuple(
        sorted(s.agent for s, _ in mass if s.score * consensus < 0 and abs(s.score) >= dissent_threshold)
    )
    return Consensus(consensus=consensus, agreement=agreement, sources=len(signals), dissenters=dissent)
