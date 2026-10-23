"""Boss agent (CIO).

With a configured LLM, the boss reads a structured summary of the analysts, the debate, the
regime, event flags, credibility weights and the current holding, and returns a decision.
The output is accepted only if:
- the action is allowed for the current holding;
- the risk budget is within the discretion cap;
- a BUY uses the chart's structural stop as its invalidation.

Anything else, or any failure, falls back to the deterministic rule set. The boss never sizes
an order, never sees a quantity, and cannot override a gate: it only proposes.

``DiscretionTracker`` scores the boss against the credibility-weighted consensus baseline.
When the boss persistently underperforms, the discretion cap (the maximum risk budget the
LLM may use) shrinks, down to 0.25, and recovers slowly when it stops underperforming.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Annotated, Any, Literal
from uuid import UUID

import numpy as np
from pydantic import BaseModel, ConfigDict, Field

from aitrader.agents.boss.fallback import consensus_of, fallback_decision
from aitrader.agents.boss.inputs import BossInput
from aitrader.llm.caller import StructuredCaller
from aitrader.llm.prompts import PromptTemplate, load_prompt
from aitrader.schemas.base import AgentId, Horizon
from aitrader.schemas.decision import Action, AgentWeight, BossDecision, Invalidation, InvalidationKind


class BossOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: Literal["BUY", "SELL", "HOLD", "REDUCE", "WAIT"]
    conviction: Annotated[float, Field(ge=0, le=1)]
    risk_budget_fraction: Annotated[float, Field(ge=0, le=1)]
    key_reasons: Annotated[
        list[Annotated[str, Field(min_length=1, max_length=500)]], Field(min_length=1, max_length=5)
    ]
    main_risk: Annotated[str, Field(min_length=1, max_length=500)]
    critical_disagreement: Annotated[str, Field(max_length=500)] | None
    invalidation_reference: Literal["chart_stop", "none"]


BOSS_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": ["BUY", "SELL", "HOLD", "REDUCE", "WAIT"]},
        "conviction": {"type": "number"},
        "risk_budget_fraction": {"type": "number"},
        "key_reasons": {"type": "array", "items": {"type": "string"}},
        "main_risk": {"type": "string"},
        "critical_disagreement": {"anyOf": [{"type": "string"}, {"type": "null"}]},
        "invalidation_reference": {"type": "string", "enum": ["chart_stop", "none"]},
    },
    "required": [
        "action",
        "conviction",
        "risk_budget_fraction",
        "key_reasons",
        "main_risk",
        "critical_disagreement",
        "invalidation_reference",
    ],
    "additionalProperties": False,
}


@dataclass
class DiscretionTracker:
    """Per closed decision: the boss's realised return vs what the consensus baseline would
    have earned on the same opportunity (fractions of equity)."""

    cap: float = 1.0
    window: int = 30
    floor: float = 0.25
    diffs: list[float] = field(default_factory=list)

    def record(self, boss_return: float, baseline_return: float) -> None:
        self.diffs.append(boss_return - baseline_return)
        recent = np.array(self.diffs[-self.window :])
        if len(recent) < 10:
            return
        t = recent.mean() / (recent.std(ddof=1) / np.sqrt(len(recent))) if recent.std(ddof=1) > 0 else 0.0
        if t < -1.0:
            self.cap = max(self.floor, self.cap - 0.1)
        elif t > 0:
            self.cap = min(1.0, self.cap + 0.05)


def allowed_actions(inp: BossInput) -> list[str]:
    held = inp.holding.quantity if inp.holding else 0
    if held > 0:
        return ["HOLD", "REDUCE", "SELL"]
    if held < 0:
        return ["HOLD", "REDUCE"]
    chart = inp.chart
    long_setup = (
        chart is not None
        and chart.structural_stop is not None
        and chart.entry_low is not None
        and chart.structural_stop < chart.entry_low
    )
    return ["WAIT", "BUY"] if long_setup else ["WAIT"]


def decision_input(inp: BossInput, rejection: str | None = None) -> str:
    consensus, agreement, dissenters, weights = consensus_of(inp)
    chart = inp.chart
    payload: dict[str, Any] = {
        "instrument": inp.instrument.key,
        "allowed_actions": allowed_actions(inp),
        "discretion_cap": round(inp.discretion_cap, 4),
        "holding_quantity_sign": (inp.holding.quantity > 0) - (inp.holding.quantity < 0)
        if inp.holding
        else 0,
        "credibility_weights": {k: round(v, 4) for k, v in weights.items()},
        "consensus": {
            "score": round(consensus, 4),
            "agreement": round(agreement, 4),
            "dissenters": list(dissenters),
        },
        "signals": [s.model_dump() for s in inp.signals()],
        "chart": None
        if chart is None
        else {
            "setup": chart.setup.value,
            "alignment": chart.alignment_score,
            "conflicts": list(chart.conflicts),
            "structure": {r.timeframe.value: r.structure.value for r in chart.readings},
            "has_structural_stop": chart.structural_stop is not None,
        },
        "smart_money_components": list(inp.smart_money.components) if inp.smart_money else [],
        "news": [
            {
                "type": e.event_type.value,
                "sentiment": e.sentiment,
                "materiality": e.materiality,
                "claims": [c.summary for c in e.claims],
            }
            for e in inp.news
        ],
        "regime": None
        if inp.regime is None
        else {
            "label": inp.regime.regime.value,
            "scenarios": {s.name: s.probability for s in inp.regime.scenarios},
        },
        "event_flags": [
            {"type": f.event_type.value, "blocks_entries": f.block_new_entries, "reason": f.reason}
            for f in inp.flags
        ],
        "debate": None
        if inp.debate is None
        else {
            "bull": [p.claim for p in inp.debate.bull.points],
            "bear": [p.claim for p in inp.debate.bear.points],
            "unresolved": list(inp.debate.unresolved),
        },
    }
    if rejection:
        payload["previous_decision_rejected_by_gates"] = rejection
    return json.dumps(payload, indent=1, sort_keys=True, default=str)


class BossAgent:
    def __init__(
        self,
        caller: StructuredCaller | None = None,
        prompt: PromptTemplate | None = None,
        discretion: DiscretionTracker | None = None,
        on_fallback: Callable[[str], None] | None = None,
    ) -> None:
        self._caller = caller
        self._prompt = prompt or (load_prompt("boss", 1) if caller else None)
        self.discretion = discretion or DiscretionTracker()
        self._on_fallback = on_fallback or (lambda reason: None)

    def decide(
        self, inp: BossInput, decision_id: UUID, cycle_id: UUID, rejection: str | None = None
    ) -> BossDecision:
        inp = BossInput(**{**inp.__dict__, "discretion_cap": min(inp.discretion_cap, self.discretion.cap)})
        if self._caller is None or self._prompt is None:
            return fallback_decision(inp, decision_id)
        out = self._caller.call(
            role="reasoning",
            prompt=self._prompt,
            values={"decision_input": decision_input(inp, rejection)},
            schema=BOSS_SCHEMA,
            output_model=BossOutput,
            agent_id=AgentId.BOSS,
            cycle_id=cycle_id,
        )
        if out is None:
            self._on_fallback("LLM boss unavailable")
            return fallback_decision(inp, decision_id)
        problem = self._validate(inp, out)
        if problem:
            self._on_fallback(problem)
            return fallback_decision(inp, decision_id)
        return self._to_decision(inp, out, decision_id)

    def revise(self, inp: BossInput, previous: BossDecision, rejection: str, cycle_id: UUID) -> BossDecision:
        """After a failed gate: the LLM may revise once per call; the fallback simply waits."""
        revision = {"revision_of": previous.decision_id, "revision_number": previous.revision_number + 1}
        if self._caller is None:
            wait = fallback_decision(BossInput(**{**inp.__dict__, "chart": None}), previous.decision_id)
            return wait.model_copy(
                update={
                    **revision,
                    "key_reasons": (*wait.key_reasons[:4], f"gates rejected: {rejection}"[:500]),
                }
            )
        revised = self.decide(inp, previous.decision_id, cycle_id, rejection=rejection)
        return BossDecision.model_validate({**revised.model_dump(), **revision})

    @staticmethod
    def _validate(inp: BossInput, out: BossOutput) -> str | None:
        if out.action not in allowed_actions(inp):
            return f"action {out.action} not allowed here"
        if out.action == "BUY":
            if out.invalidation_reference != "chart_stop":
                return "BUY without the chart stop as invalidation"
            if not 0 < out.risk_budget_fraction <= inp.discretion_cap:
                return f"risk budget {out.risk_budget_fraction} outside (0, {inp.discretion_cap}]"
        return None

    @staticmethod
    def _to_decision(inp: BossInput, out: BossOutput, decision_id: UUID) -> BossDecision:
        _, _, _, weights = consensus_of(inp)
        action = Action(out.action)
        invalidation = None
        if action is Action.BUY and inp.chart is not None and inp.chart.structural_stop is not None:
            invalidation = Invalidation(
                condition=InvalidationKind.PRICE_CLOSE_BELOW,
                level=inp.chart.structural_stop,
                description="close below the chart's structural stop",
            )
        elif action in (Action.SELL, Action.REDUCE):
            invalidation = Invalidation(
                condition=InvalidationKind.REGIME_CHANGE, description="thesis for exiting reverses"
            )
        return BossDecision(
            decision_id=decision_id,
            action=action,
            instrument=inp.instrument,
            horizon=Horizon.SWING,
            conviction=out.conviction,
            risk_budget_fraction=out.risk_budget_fraction if action is Action.BUY else 0.0,
            key_reasons=tuple(out.key_reasons),
            main_risk=out.main_risk,
            critical_disagreement=out.critical_disagreement or None,
            invalidation=invalidation,
            agent_weights=tuple(
                AgentWeight(agent_id=AgentId(a), weight=round(w, 6), signal_message_id=inp.message_ids.get(a))
                for a, w in sorted(weights.items())
            ),
        )
