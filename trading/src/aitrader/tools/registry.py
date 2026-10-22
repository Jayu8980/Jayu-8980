"""Tool registration contract."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class ToolSpec(BaseModel):
    model_config = ConfigDict(frozen=True, arbitrary_types_allowed=True)

    name: Annotated[str, Field(pattern=r"^[a-z_]+\.[a-z0-9_]+$")]  # "<domain>.<function>"
    version: str
    input_model: type[BaseModel]
    output_model: type[BaseModel]
    func: Callable[[Any], BaseModel]
    # Tools must be pure functions of their inputs (randomised tools take an explicit seed).
    deterministic: bool = True
