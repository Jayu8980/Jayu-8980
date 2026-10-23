"""Backtest configuration (config/backtest.yaml)."""

from __future__ import annotations

from datetime import date
from pathlib import Path
from typing import Annotated, Literal

import yaml
from pydantic import Field

from aitrader.config.settings import CONFIG_DIR
from aitrader.schemas.base import StrictModel


class FillConfig(StrictModel):
    model: Literal["next_open"]
    max_participation_of_bar_volume: Annotated[float, Field(gt=0, le=1)]
    order_validity_sessions: Annotated[int, Field(ge=1, le=5)]
    stop_and_target_same_bar: Literal["stop_first"]
    clamp_to_bar_range: bool


class SlippageConfig(StrictModel):
    half_spread_bps: Annotated[float, Field(ge=0)]
    impact_coefficient: Annotated[float, Field(ge=0, le=5)]
    min_bps: Annotated[float, Field(ge=0)]
    adv_lookback_sessions: Annotated[int, Field(ge=5, le=250)]


class CorporateActionConfig(StrictModel):
    dividends: Literal["credit_on_ex_date"]


class BacktestConfig(StrictModel):
    initial_capital_inr: Annotated[float, Field(gt=0)]
    decision_time_ist: Annotated[str, Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")]
    benchmark: str
    start: date
    universe_index: str
    fills: FillConfig
    slippage: SlippageConfig
    corporate_actions: CorporateActionConfig


def load_backtest_config(path: Path | None = None) -> BacktestConfig:
    data = yaml.safe_load((path or CONFIG_DIR / "backtest.yaml").read_text(encoding="utf-8"))
    return BacktestConfig.model_validate(data)
