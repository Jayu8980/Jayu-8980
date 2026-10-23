"""Shared annotated types for tool inputs and the base class for tool outputs."""

from __future__ import annotations

from collections.abc import Sequence, Sized
from typing import Annotated

import numpy as np
from numpy.typing import NDArray
from pydantic import AllowInfNan, BaseModel, ConfigDict, Field

Finite = Annotated[float, AllowInfNan(False)]
Positive = Annotated[float, AllowInfNan(False), Field(gt=0)]
NonNegative = Annotated[float, AllowInfNan(False), Field(ge=0)]
Unit = Annotated[float, Field(ge=0.0, le=1.0)]
Score = Annotated[float, Field(ge=-1.0, le=1.0)]
Confidence = Annotated[float, Field(gt=0.5, lt=1.0)]
Series = Annotated[Sequence[Finite], Field(min_length=2)]
PositiveSeries = Annotated[Sequence[Positive], Field(min_length=2)]
Matrix = Annotated[Sequence[Annotated[Sequence[Finite], Field(min_length=1)]], Field(min_length=1)]


class ToolOutput(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", allow_inf_nan=False)


def arr(values: Sequence[float]) -> NDArray[np.float64]:
    out = np.asarray(values, dtype=np.float64)
    if not np.all(np.isfinite(out)):
        raise ValueError("inputs must be finite")
    return out


def mat(values: Sequence[Sequence[float]]) -> NDArray[np.float64]:
    out = np.asarray(values, dtype=np.float64)
    if out.ndim != 2:
        raise ValueError("expected a rectangular matrix")
    if not np.all(np.isfinite(out)):
        raise ValueError("inputs must be finite")
    return out


def same_length(*series: Sized) -> int:
    lengths = {len(s) for s in series}
    if len(lengths) != 1:
        raise ValueError(f"series lengths differ: {sorted(lengths)}")
    return lengths.pop()
