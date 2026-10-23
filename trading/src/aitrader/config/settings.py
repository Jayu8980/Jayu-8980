"""Typed loader for config/system.yaml and config/limits.yaml.

Limits are validated on load so an inconsistent or unsafe configuration fails fast instead
of reaching the risk agent. Secrets are never read from these files.
"""

from __future__ import annotations

import os
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal

import yaml
from pydantic import Field, model_validator

from aitrader.schemas.base import StrictModel
from aitrader.schemas.signals import SetupType

Fraction = Annotated[float, Field(gt=0.0, le=1.0)]
CONFIG_DIR = Path(__file__).resolve().parents[3] / "config"
UNSET = "UNSET"


class Stage(StrEnum):
    BACKTEST = "backtest"
    PAPER = "paper"
    LIVE_HUMAN_APPROVAL = "live_human_approval"
    LIVE_AUTO = "live_auto"

    @property
    def is_live(self) -> bool:
        return self in (Stage.LIVE_HUMAN_APPROVAL, Stage.LIVE_AUTO)


STAGE_ORDER = (Stage.BACKTEST, Stage.PAPER, Stage.LIVE_HUMAN_APPROVAL, Stage.LIVE_AUTO)


class MarketConfig(StrictModel):
    exchanges: tuple[Literal["NSE", "BSE"], ...]
    segments: tuple[Literal["cash", "fno"], ...]
    universe: str
    timezone: str
    allow_naked_option_writing: bool
    shorts_via: tuple[Literal["future", "put_option", "cash_intraday"], ...]


class DecisionCycleConfig(StrictModel):
    scheduled_ist: tuple[Annotated[str, Field(pattern=r"^\d{2}:\d{2}$")], ...]
    event_driven: bool


class TradingConfig(StrictModel):
    style: Literal["intraday", "swing", "positional"]
    decision_cycle: DecisionCycleConfig
    entry_timing_timeframe: Literal["15m", "1h"]


class DataConfig(StrictModel):
    availability_ist: dict[str, Annotated[str, Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")]]
    calendar_reference: str
    cash_series: tuple[str, ...]
    publish_option_bars: bool
    outlier_robust_z: Annotated[float, Field(gt=3)]
    stale_warning_hours: Annotated[float, Field(gt=0)]
    stale_critical_hours: Annotated[float, Field(gt=0)]
    store_path: str

    @model_validator(mode="after")
    def _ordering(self) -> DataConfig:
        if self.stale_critical_hours <= self.stale_warning_hours:
            raise ValueError("stale_critical_hours must exceed stale_warning_hours")
        for source, hhmm in self.availability_ist.items():
            if hhmm < "15:30":
                raise ValueError(f"{source}: EOD data cannot be available before the 15:30 close")
        return self


class BrokerConfig(StrictModel):
    name: str
    max_orders_per_second: Annotated[int, Field(ge=1, le=10)]
    static_ip_required: bool
    allow_market_orders: bool
    kill_switch_file: str  # creating this file engages the kill switch ("flatten" in it also flattens)


class LlmModels(StrictModel):
    reasoning: str
    extraction: str


Effort = Literal["low", "medium", "high", "xhigh", "max"]


class LlmEfforts(StrictModel):
    reasoning: Effort
    extraction: Effort


class LlmMaxTokens(StrictModel):
    reasoning: Annotated[int, Field(ge=256, le=64000)]
    extraction: Annotated[int, Field(ge=256, le=64000)]


class ModelPrice(StrictModel):
    input: Annotated[float, Field(ge=0)]
    output: Annotated[float, Field(ge=0)]


class LlmConfig(StrictModel):
    provider: Literal["anthropic"]
    models: LlmModels
    effort: LlmEfforts
    max_output_tokens: LlmMaxTokens
    pricing_usd_per_mtok: dict[str, ModelPrice]
    request_timeout_seconds: Annotated[float, Field(gt=0, le=600)]
    max_debate_rounds: Annotated[int, Field(ge=1, le=4)]
    max_llm_calls_per_cycle: Annotated[int, Field(ge=1)]
    daily_cost_budget_usd: Annotated[float, Field(gt=0)]

    def require_pinned(self) -> None:
        """Called before any LLM agent starts. Placeholders, aliases and unpriced models are
        refused (an unpriced model would make the cost budget unenforceable)."""
        for role, model in self.models.model_dump().items():
            if not model or model == UNSET or model.endswith("latest"):
                raise ValueError(f"llm.models.{role} must be an exact pinned model id, got {model!r}")
            if model not in self.pricing_usd_per_mtok:
                raise ValueError(f"llm.pricing_usd_per_mtok has no entry for {model!r}")

    @property
    def pinned(self) -> bool:
        try:
            self.require_pinned()
        except ValueError:
            return False
        return True


class TimeoutsConfig(StrictModel):
    analyst: Annotated[float, Field(gt=0)]
    debate: Annotated[float, Field(gt=0)]
    boss: Annotated[float, Field(gt=0)]
    gate: Annotated[float, Field(gt=0)]


class BusConfig(StrictModel):
    backend: Literal["memory", "redis_streams"]
    redis_url_env: str


class StateConfig(StrictModel):
    backend: Literal["memory", "postgres"]
    postgres_dsn_env: str


class NotificationsConfig(StrictModel):
    channel: Literal["telegram", "none"]
    bot_token_env: str
    chat_id_env: str


class SystemConfig(StrictModel):
    stage: Stage
    market: MarketConfig
    trading: TradingConfig
    data: DataConfig
    broker: BrokerConfig
    llm: LlmConfig
    timeouts_seconds: TimeoutsConfig
    bus: BusConfig
    state: StateConfig
    notifications: NotificationsConfig

    @model_validator(mode="after")
    def _live_needs_durable_infra(self) -> SystemConfig:
        if self.stage is not Stage.BACKTEST and "memory" in (self.bus.backend, self.state.backend):
            raise ValueError("paper and live stages need durable bus and state backends")
        return self


class PerTradeLimits(StrictModel):
    max_risk_fraction: Annotated[float, Field(gt=0.0, le=0.02)]
    max_position_fraction: Fraction
    human_approval_above_risk_fraction: Fraction


class PortfolioLimits(StrictModel):
    max_gross_exposure: Annotated[float, Field(gt=0.0, le=2.0)]
    max_net_exposure: Annotated[float, Field(ge=0.0, le=2.0)]
    max_sector_exposure: Fraction
    max_pairwise_correlation_cluster: Fraction
    max_leverage: Annotated[float, Field(ge=1.0, le=3.0)]
    max_open_positions: Annotated[int, Field(ge=1)]


class DrawdownLimits(StrictModel):
    daily_limit: Fraction
    total_limit: Fraction
    tighten_from_fraction_of_limit: Fraction


class LiquidityLimits(StrictModel):
    max_fraction_of_adv: Annotated[float, Field(gt=0.0, le=0.25)]
    max_participation_rate: Annotated[float, Field(gt=0.0, le=0.25)]
    max_days_to_liquidate: Annotated[float, Field(gt=0.0)]
    assumed_spread_bps: Annotated[float, Field(ge=0)]
    thin_adv_value_inr: Annotated[float, Field(gt=0)]
    impact_coefficient: Annotated[float, Field(gt=0, le=5)]
    adv_sessions: Annotated[int, Field(ge=5, le=250)]
    freeze_quantity: dict[str, Annotated[int, Field(ge=1)]]


class VarLimits(StrictModel):
    confidence: Annotated[float, Field(ge=0.9, lt=1.0)]
    horizon_days: Annotated[int, Field(ge=1)]
    max_var_fraction: Fraction
    max_cvar_fraction: Fraction


class RiskRewardLimits(StrictModel):
    holding_sessions: Annotated[int, Field(ge=1, le=120)]
    setup_prior_strength: Annotated[float, Field(gt=0)]
    min_rr_after_costs: dict[SetupType, Annotated[float, Field(gt=0.0)]]
    min_stop_atr_multiple: Annotated[float, Field(gt=0.0)]


class StressLimits(StrictModel):
    index_shock: Annotated[float, Field(ge=-0.5, lt=0)]
    max_stress_loss_fraction: Fraction


class DegradationLimits(StrictModel):
    missing_agent_multiplier: Fraction
    missing_trend_agent_multiplier: Fraction
    min_agents_for_new_entries: Annotated[int, Field(ge=1)]


class ExecutionLimits(StrictModel):
    max_order_value_inr: Annotated[float, Field(gt=0)]
    max_price_deviation_bps: Annotated[float, Field(gt=0, le=500)]
    approval_token_ttl_seconds: Annotated[int, Field(ge=1, le=86400)]
    reconciliation_interval_seconds: Annotated[int, Field(ge=1)]
    # Broker-held stops become limit orders when triggered; this is how far past the trigger
    # the limit sits, i.e. the slippage accepted to get out.
    protective_stop_limit_bps: Annotated[float, Field(gt=0, le=1000)]
    fno_margin_fraction: Annotated[float, Field(gt=0, le=1)]  # pre-trade estimate until SPAN data is wired


class RegimeLimits(StrictModel):
    max_exposure_change_per_cycle: Fraction


class RiskLimits(StrictModel):
    capital_inr: Annotated[float, Field(gt=0)]
    per_trade: PerTradeLimits
    portfolio: PortfolioLimits
    drawdown: DrawdownLimits
    liquidity: LiquidityLimits
    var: VarLimits
    risk_reward: RiskRewardLimits
    stress: StressLimits
    degradation: DegradationLimits
    execution: ExecutionLimits
    regime: RegimeLimits

    @model_validator(mode="after")
    def _coherent(self) -> RiskLimits:
        if self.drawdown.daily_limit >= self.drawdown.total_limit:
            raise ValueError("daily drawdown limit must be below the total limit")
        if self.portfolio.max_net_exposure > self.portfolio.max_gross_exposure:
            raise ValueError("net exposure limit cannot exceed gross")
        if self.per_trade.human_approval_above_risk_fraction > self.per_trade.max_risk_fraction:
            raise ValueError("human-approval threshold must not exceed the per-trade risk cap")
        if self.var.max_cvar_fraction < self.var.max_var_fraction:
            raise ValueError("CVaR limit must be at least the VaR limit")
        tradable = {s for s in SetupType if s is not SetupType.NONE}
        if missing := tradable - set(self.risk_reward.min_rr_after_costs):
            raise ValueError(f"min R:R missing for setups: {sorted(missing)}")
        return self


def _load_yaml(path: Path) -> dict[str, object]:
    with path.open(encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a mapping")
    return data


def load_system_config(path: Path | None = None) -> SystemConfig:
    return SystemConfig.model_validate(_load_yaml(path or CONFIG_DIR / "system.yaml"))


def load_risk_limits(path: Path | None = None) -> RiskLimits:
    return RiskLimits.model_validate(_load_yaml(path or CONFIG_DIR / "limits.yaml"))


def secret(env_name: str) -> str:
    """Read a secret from the environment; never from config files or code."""
    value = os.environ.get(env_name, "")
    if not value:
        raise KeyError(f"secret {env_name} is not set")
    return value
