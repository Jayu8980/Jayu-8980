"""Bull vs bear deliberation payloads."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from aitrader.schemas.base import EvidenceRef, Instrument, Probability, ShortText, StrictModel

MAX_DEBATE_ROUNDS_HARD_CAP = 4  # config may lower this, never raise it


class DebateSide(StrEnum):
    BULL = "bull"
    BEAR = "bear"


class DebatePoint(StrictModel):
    claim: ShortText
    # Points must cite analyst messages or tool calls; uncited points are discarded.
    evidence: Annotated[tuple[EvidenceRef, ...], Field(min_length=1)]


class DebateCase(StrictModel):
    side: DebateSide
    points: Annotated[tuple[DebatePoint, ...], Field(min_length=1, max_length=5)]
    strongest_counterargument: DebatePoint  # the best point against this side's own case
    confidence: Probability


class DebateOutput(StrictModel):
    kind: Literal["debate_output"] = "debate_output"
    instrument: Instrument
    rounds_used: Annotated[int, Field(ge=1, le=MAX_DEBATE_ROUNDS_HARD_CAP)]
    bull: DebateCase
    bear: DebateCase
    unresolved: tuple[ShortText, ...] = ()
    terminated_early: bool = False  # e.g. timeout or round cap reached

    @model_validator(mode="after")
    def _sides(self) -> DebateOutput:
        if self.bull.side is not DebateSide.BULL or self.bear.side is not DebateSide.BEAR:
            raise ValueError("bull and bear cases must carry matching sides")
        return self
