"""Position sizing tools: fixed-fractional (stop-based), volatility-targeted and Kelly.

All quantities round down to whole lots, so sizing can only ever undershoot a risk budget.
"""

from __future__ import annotations

import math
from typing import Annotated

from pydantic import Field

from aitrader.tools.registry import tool
from aitrader.tools.types import NonNegative, Positive, ToolOutput, Unit


class SizeResult(ToolOutput):
    quantity: int  # multiple of lot_size, possibly 0
    risk_amount: float  # loss at the stop incl. per-unit costs, for the rounded quantity
    position_value: float
    binding_constraint: str


@tool(
    "sizing.fixed_fractional",
    "1.0.0",
    example={"equity": 1000000, "risk_fraction": 0.01, "entry": 1500, "stop": 1440, "lot_size": 1},
)
def fixed_fractional(
    equity: Positive,
    risk_fraction: Annotated[float, Field(gt=0, le=0.02)],
    entry: Positive,
    stop: Positive,
    lot_size: Annotated[int, Field(ge=1)] = 1,
    cost_per_unit: NonNegative = 0.0,
    max_position_value: Positive | None = None,
) -> SizeResult:
    """Quantity such that (|entry - stop| + costs) x quantity <= equity x risk_fraction."""
    if entry == stop:
        raise ValueError("stop must differ from entry")
    per_unit = abs(entry - stop) + cost_per_unit
    budget = equity * risk_fraction
    lots_by_risk = math.floor(budget / (per_unit * lot_size))
    lots, binding = lots_by_risk, "risk"
    if max_position_value is not None:
        lots_by_value = math.floor(max_position_value / (entry * lot_size))
        if lots_by_value < lots:
            lots, binding = lots_by_value, "position_value"
    lots = max(lots, 0)
    qty = lots * lot_size
    return SizeResult(
        quantity=qty, risk_amount=qty * per_unit, position_value=qty * entry, binding_constraint=binding
    )


@tool(
    "sizing.vol_target",
    "1.0.0",
    example={
        "equity": 1000000,
        "target_annual_vol": 0.01,
        "asset_annual_vol": 0.3,
        "price": 1500,
        "lot_size": 1,
    },
)
def vol_target(
    equity: Positive,
    target_annual_vol: Annotated[float, Field(gt=0, le=0.5)],  # contribution of this position
    asset_annual_vol: Annotated[float, Field(gt=0, le=5)],
    price: Positive,
    lot_size: Annotated[int, Field(ge=1)] = 1,
) -> SizeResult:
    notional = equity * target_annual_vol / asset_annual_vol
    lots = max(math.floor(notional / (price * lot_size)), 0)
    qty = lots * lot_size
    return SizeResult(
        quantity=qty,
        risk_amount=qty * price * asset_annual_vol,
        position_value=qty * price,
        binding_constraint="volatility",
    )


class KellyResult(ToolOutput):
    full_kelly: float  # may be negative: no edge
    applied_fraction: float  # fractional Kelly, floored at 0 and capped


@tool("sizing.kelly", "1.0.0", example={"p_win": 0.45, "win_loss_ratio": 2.0, "fraction": 0.25, "cap": 0.02})
def kelly(
    p_win: Unit,
    win_loss_ratio: Positive,
    fraction: Annotated[float, Field(gt=0, le=1)] = 0.25,
    cap: Annotated[float, Field(gt=0, le=1)] = 0.02,
) -> KellyResult:
    full = p_win - (1 - p_win) / win_loss_ratio
    return KellyResult(full_kelly=full, applied_fraction=min(max(full * fraction, 0.0), cap))
