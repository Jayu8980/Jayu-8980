"""Bull vs bear debate (LLM).

Each round the bull, then the bear, states its strongest case and its own strongest
counterargument, citing evidence ids from a list built from the analysts' messages. Points
citing unknown ids are discarded; a side left with no valid point ends the debate. Rounds
are capped by config (and by ``MAX_DEBATE_ROUNDS_HARD_CAP``). Without an LLM the debate is
skipped and the boss decides without it.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Annotated, Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from aitrader.llm.caller import StructuredCaller
from aitrader.llm.prompts import PromptTemplate, load_prompt
from aitrader.schemas.base import AgentId, EvidenceRef, Instrument
from aitrader.schemas.debate import (
    MAX_DEBATE_ROUNDS_HARD_CAP,
    DebateCase,
    DebateOutput,
    DebatePoint,
    DebateSide,
)


class _Point(BaseModel):
    model_config = ConfigDict(extra="forbid")
    claim: Annotated[str, Field(min_length=1, max_length=500)]
    evidence_ids: Annotated[list[str], Field(min_length=1)]


class CaseOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    points: Annotated[list[_Point], Field(min_length=1, max_length=5)]
    counterargument: _Point
    confidence: Annotated[float, Field(ge=0, le=1)]


_POINT_SCHEMA = {
    "type": "object",
    "properties": {
        "claim": {"type": "string"},
        "evidence_ids": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["claim", "evidence_ids"],
    "additionalProperties": False,
}
CASE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "points": {"type": "array", "items": _POINT_SCHEMA},
        "counterargument": _POINT_SCHEMA,
        "confidence": {"type": "number"},
    },
    "required": ["points", "counterargument", "confidence"],
    "additionalProperties": False,
}


class DebateAgent:
    def __init__(
        self, caller: StructuredCaller | None, max_rounds: int = 2, prompt: PromptTemplate | None = None
    ) -> None:
        self._caller = caller
        self._prompt = prompt or (load_prompt("debate", 1) if caller else None)
        self._rounds = min(max_rounds, MAX_DEBATE_ROUNDS_HARD_CAP)

    @property
    def available(self) -> bool:
        return self._caller is not None

    def run(
        self, instrument: Instrument, evidence: Mapping[str, tuple[EvidenceRef, str]], cycle_id: UUID
    ) -> DebateOutput | None:
        """``evidence``: id -> (reference, one-line summary). Returns None when skipped or failed."""
        if self._caller is None or self._prompt is None or not evidence:
            return None
        listing = "\n".join(f"- {k}: {summary}" for k, (_, summary) in sorted(evidence.items()))
        cases: dict[DebateSide, DebateCase] = {}
        rounds = 0
        for round_no in range(1, self._rounds + 1):
            for side in (DebateSide.BULL, DebateSide.BEAR):
                opposing = cases.get(DebateSide.BEAR if side is DebateSide.BULL else DebateSide.BULL)
                out = self._caller.call(
                    role="reasoning",
                    prompt=self._prompt,
                    values={
                        "side": side.value,
                        "round": str(round_no),
                        "max_rounds": str(self._rounds),
                        "evidence": listing,
                        "opposing": json.dumps([p.claim for p in opposing.points])
                        if opposing
                        else "(none yet)",
                    },
                    schema=CASE_SCHEMA,
                    output_model=CaseOutput,
                    agent_id=AgentId.DEBATE,
                    cycle_id=cycle_id,
                )
                case = self._case(side, out, evidence) if out else None
                if case is None:
                    return self._finish(instrument, cases, rounds, early=True)
                cases[side] = case
            rounds = round_no
        return self._finish(instrument, cases, rounds, early=False)

    @staticmethod
    def _point(p: _Point, evidence: Mapping[str, tuple[EvidenceRef, str]]) -> DebatePoint | None:
        refs = tuple(evidence[i][0] for i in p.evidence_ids if i in evidence)
        return DebatePoint(claim=p.claim, evidence=refs) if refs else None

    def _case(
        self, side: DebateSide, out: CaseOutput, evidence: Mapping[str, tuple[EvidenceRef, str]]
    ) -> DebateCase | None:
        points = [pt for pt in (self._point(p, evidence) for p in out.points) if pt is not None]
        counter = self._point(out.counterargument, evidence)
        if not points or counter is None:
            return None
        return DebateCase(
            side=side, points=tuple(points), strongest_counterargument=counter, confidence=out.confidence
        )

    @staticmethod
    def _finish(
        instrument: Instrument, cases: dict[DebateSide, DebateCase], rounds: int, early: bool
    ) -> DebateOutput | None:
        if DebateSide.BULL not in cases or DebateSide.BEAR not in cases or rounds == 0:
            return None
        bull, bear = cases[DebateSide.BULL], cases[DebateSide.BEAR]
        return DebateOutput(
            instrument=instrument,
            rounds_used=rounds,
            bull=bull,
            bear=bear,
            unresolved=(bull.strongest_counterargument.claim, bear.strongest_counterargument.claim),
            terminated_early=early,
        )
