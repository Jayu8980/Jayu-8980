"""Tool registry and runner.

Tools are plain, pure Python functions with fully type-annotated parameters. Registration
derives a strict Pydantic input model from the signature (constraints in ``Annotated`` are
kept) and requires the return type to be a Pydantic model, so every tool has typed I/O.

Agents never call tool functions directly: they go through ``ToolRunner.call``, which
validates inputs, runs the tool, validates the output and appends a ``ToolCallRecord`` to the
audit log, including failures.
"""

from __future__ import annotations

import inspect
from collections.abc import Callable, Mapping
from datetime import datetime
from typing import Annotated, Any, TypeVar, get_type_hints
from uuid import UUID, uuid4

from pydantic import AllowInfNan, BaseModel, ConfigDict, Field, create_model

from aitrader.audit.interface import AuditLog
from aitrader.schemas.audit import ToolCallRecord
from aitrader.schemas.base import AgentId
from aitrader.schemas.canonical import canonical_json

Finite = Annotated[float, AllowInfNan(False)]


class ToolSpec(BaseModel):
    model_config = ConfigDict(frozen=True, arbitrary_types_allowed=True)

    name: Annotated[str, Field(pattern=r"^[a-z_]+\.[a-z0-9_]+$")]  # "<domain>.<function>"
    version: str
    input_model: type[BaseModel]
    output_model: type[BaseModel]
    func: Callable[..., BaseModel]
    # Tools must be pure functions of their inputs (randomised tools take an explicit seed).
    deterministic: bool = True
    # A valid input used by the registry self-test (every tool must have one).
    example: Mapping[str, Any]

    def run(self, inputs: Mapping[str, Any]) -> BaseModel:
        validated = self.input_model.model_validate(dict(inputs))
        kwargs = {name: getattr(validated, name) for name in self.input_model.model_fields}
        output = self.func(**kwargs)
        return self.output_model.model_validate(output)


class ToolError(RuntimeError):
    """Raised for invalid tool inputs or a failing tool; always logged before raising."""


_REGISTRY: dict[str, ToolSpec] = {}


def _input_model(name: str, func: Callable[..., Any]) -> type[BaseModel]:
    hints = get_type_hints(func, include_extras=True)
    fields: dict[str, Any] = {}
    for param in inspect.signature(func).parameters.values():
        if param.kind not in (param.POSITIONAL_OR_KEYWORD, param.KEYWORD_ONLY):
            raise TypeError(f"tool {name}: *args/**kwargs are not allowed")
        if param.name not in hints:
            raise TypeError(f"tool {name}: parameter {param.name} needs a type annotation")
        default = ... if param.default is inspect.Parameter.empty else param.default
        fields[param.name] = (hints[param.name], default)
    model_name = "".join(part.title() for part in name.replace(".", "_").split("_")) + "Input"
    config = ConfigDict(frozen=True, extra="forbid", allow_inf_nan=False)
    model: type[BaseModel] = create_model(model_name, __config__=config, **fields)
    return model


F = TypeVar("F", bound=Callable[..., BaseModel])


def tool(
    name: str, version: str, *, example: Mapping[str, Any], deterministic: bool = True
) -> Callable[[F], F]:
    """Register a pure function as an institutional tool (the function itself is unchanged)."""

    def register(func: F) -> F:
        if name in _REGISTRY:
            raise ValueError(f"tool {name} registered twice")
        output = get_type_hints(func).get("return")
        if not (isinstance(output, type) and issubclass(output, BaseModel)):
            raise TypeError(f"tool {name} must return a Pydantic model")
        _REGISTRY[name] = ToolSpec(
            name=name,
            version=version,
            input_model=_input_model(name, func),
            output_model=output,
            func=func,
            deterministic=deterministic,
            example=example,
        )
        return func

    return register


def registry() -> Mapping[str, ToolSpec]:
    # Importing the catalogue registers every tool module exactly once.
    from aitrader.tools import catalogue  # noqa: F401

    return dict(_REGISTRY)


class ToolRunner:
    """The only way agents invoke tools. Every call, successful or not, is audited."""

    def __init__(self, audit: AuditLog, clock: Callable[[], datetime]) -> None:
        self._audit = audit
        self._clock = clock
        self._tools = registry()

    def call(self, name: str, inputs: Mapping[str, Any], *, agent_id: AgentId, cycle_id: UUID) -> BaseModel:
        spec = self._tools.get(name)
        if spec is None:
            raise ToolError(f"unknown tool {name}")
        started = self._clock()
        output: BaseModel | None = None
        error: str | None = None
        try:
            output = spec.run(inputs)
        except Exception as exc:  # every failure must reach the audit log before re-raising
            error = f"{type(exc).__name__}: {exc}"
        record = ToolCallRecord(
            call_id=uuid4(),
            agent_id=agent_id,
            cycle_id=cycle_id,
            tool=name,
            tool_version=spec.version,
            inputs=_jsonable(inputs),
            output=output.model_dump(mode="json") if output is not None else None,
            error=error,
            started_at=started,
            finished_at=self._clock(),
        )
        self._audit.append("tool_call", canonical_json(record))
        if output is None:
            raise ToolError(f"{name} failed: {error}")
        return output


def _jsonable(inputs: Mapping[str, Any]) -> dict[str, Any]:
    import json

    result: dict[str, Any] = json.loads(json.dumps(dict(inputs), default=str))
    return result
