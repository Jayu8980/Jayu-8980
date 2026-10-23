"""Structured LLM calls with budgets and a complete audit trail.

Every call, including refused, failed and over-budget ones, writes an ``LlmCallRecord`` with
the full rendered prompt, model id, prompt version and hash, response, token usage and cost.
The response must parse into the requested Pydantic model; anything else is an agent failure
(returned as ``None``), never a retry-until-it-agrees loop.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any, TypeVar
from uuid import UUID, uuid4

from pydantic import BaseModel, ValidationError

from aitrader.agents.data.calendar import ist_date
from aitrader.audit.interface import AuditLog
from aitrader.config.settings import LlmConfig
from aitrader.llm.client import LlmClient, LlmError, LlmRequest, LlmResponse
from aitrader.llm.prompts import PromptTemplate
from aitrader.schemas.audit import LlmCallRecord
from aitrader.schemas.base import AgentId
from aitrader.schemas.canonical import canonical_json

T = TypeVar("T", bound=BaseModel)


@dataclass
class LlmBudget:
    """Per-cycle call cap and daily (IST) cost cap."""

    max_calls_per_cycle: int
    daily_cost_usd: float
    calls: dict[UUID, int] = field(default_factory=dict)
    spend: dict[date, float] = field(default_factory=dict)

    def check(self, cycle_id: UUID, day: date) -> str | None:
        if self.calls.get(cycle_id, 0) >= self.max_calls_per_cycle:
            return "per-cycle LLM call cap reached"
        if self.spend.get(day, 0.0) >= self.daily_cost_usd:
            return "daily LLM cost budget exhausted"
        return None

    def record(self, cycle_id: UUID, day: date, cost: float) -> None:
        self.calls[cycle_id] = self.calls.get(cycle_id, 0) + 1
        self.spend[day] = self.spend.get(day, 0.0) + cost


class StructuredCaller:
    def __init__(
        self,
        client: LlmClient,
        config: LlmConfig,
        audit: AuditLog,
        clock: Callable[[], datetime],
        budget: LlmBudget | None = None,
    ) -> None:
        config.require_pinned()
        self._client = client
        self._config = config
        self._audit = audit
        self._clock = clock
        self.budget = budget or LlmBudget(config.max_llm_calls_per_cycle, config.daily_cost_budget_usd)

    def call(
        self,
        *,
        role: str,
        prompt: PromptTemplate,
        values: dict[str, str],
        schema: dict[str, Any],
        output_model: type[T],
        agent_id: AgentId,
        cycle_id: UUID,
    ) -> T | None:
        model = getattr(self._config.models, role)
        system, user = prompt.render(**values)
        request = LlmRequest(
            model=model,
            system=system,
            user=user,
            json_schema=schema,
            max_tokens=getattr(self._config.max_output_tokens, role),
            effort=getattr(self._config.effort, role),
        )
        started = self._clock()
        day = ist_date(started)
        response: LlmResponse | None = None
        parsed: T | None = None
        error = self.budget.check(cycle_id, day)
        if error is None:
            try:
                response = self._client.complete(request)
            except LlmError as exc:
                error = str(exc)
        if response is not None:
            if response.stop_reason == "refusal":
                error = "model refused"
            elif response.stop_reason == "max_tokens":
                error = "response truncated at max_tokens"
            else:
                try:
                    parsed = output_model.model_validate(json.loads(response.text))
                except (json.JSONDecodeError, ValidationError) as exc:
                    error = f"schema violation: {str(exc).splitlines()[0][:300]}"
        cost = self._cost(model, response)
        if response is not None:
            self.budget.record(cycle_id, day, cost)
        record = LlmCallRecord(
            call_id=uuid4(),
            agent_id=agent_id,
            cycle_id=cycle_id,
            provider=self._config.provider,
            model=response.model if response else model,
            prompt_id=prompt.prompt_id,
            prompt_version=prompt.version,
            prompt_sha256=prompt.sha256,
            rendered_prompt=f"[system]\n{system}\n[user]\n{user}",
            effort=request.effort,
            response_text=response.text if response else "",
            stop_reason=response.stop_reason if response else None,
            error=error,
            input_tokens=response.input_tokens if response else 0,
            output_tokens=response.output_tokens if response else 0,
            cost_usd=cost,
            started_at=started,
            finished_at=self._clock(),
        )
        self._audit.append("llm_call", canonical_json(record))
        return parsed if error is None else None

    def _cost(self, model: str, response: LlmResponse | None) -> float:
        if response is None:
            return 0.0
        price = self._config.pricing_usd_per_mtok[model]
        return (response.input_tokens * price.input + response.output_tokens * price.output) / 1e6
