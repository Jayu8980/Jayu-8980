"""Risk agent payloads: approval tokens, approvals and alerts.

The approval token binds the risk decision to the exact instruction (by digest), is
single-use (token_id is consumed by the execution layer) and expires. Signature creation
and verification are deterministic code owned by the risk and execution packages.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field, model_validator

from aitrader.schemas.base import Instrument, ShortText, StrictModel, UtcDatetime
from aitrader.schemas.market import Severity

HexDigest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class ApprovalToken(StrictModel):
    token_id: UUID
    decision_id: UUID
    instruction_digest: HexDigest
    issued_at: UtcDatetime
    expires_at: UtcDatetime
    key_id: Annotated[str, Field(min_length=1, max_length=40)]
    signature: HexDigest  # HMAC-SHA256 over the canonical token body without the signature

    @model_validator(mode="after")
    def _expiry(self) -> ApprovalToken:
        if self.expires_at <= self.issued_at:
            raise ValueError("token must expire after it is issued")
        return self


class RiskRule(StrEnum):
    MAX_RISK_PER_TRADE = "max_risk_per_trade"
    MAX_POSITION = "max_position"
    GROSS_EXPOSURE = "gross_exposure"
    NET_EXPOSURE = "net_exposure"
    SECTOR_LIMIT = "sector_limit"
    CORRELATION_LIMIT = "correlation_limit"
    LEVERAGE = "leverage"
    DAILY_DRAWDOWN = "daily_drawdown"
    TOTAL_DRAWDOWN = "total_drawdown"
    LIQUIDITY_LIMIT = "liquidity_limit"
    EVENT_EXPOSURE = "event_exposure"
    VAR_LIMIT = "var_limit"
    STRESS_LIMIT = "stress_limit"
    TRADING_FROZEN = "trading_frozen"
    HUMAN_APPROVAL_PENDING = "human_approval_pending"
    STAGE_NOT_PERMITTED = "stage_not_permitted"


class RuleResult(StrictModel):
    rule: RiskRule
    passed: bool
    observed: float
    limit: float


class RiskApproval(StrictModel):
    kind: Literal["risk_approval"] = "risk_approval"
    decision_id: UUID
    instrument: Instrument
    approved: bool
    approved_quantity: Annotated[int, Field(ge=0)]
    results: Annotated[tuple[RuleResult, ...], Field(min_length=1)]
    limit_multiplier: Annotated[float, Field(gt=0.0, le=1.0)]  # < 1 when limits are tightened
    token: ApprovalToken | None = None

    @model_validator(mode="after")
    def _token_iff_approved(self) -> RiskApproval:
        if self.approved != (self.token is not None):
            raise ValueError("a token must be present exactly when approved")
        if self.approved and not all(r.passed for r in self.results):
            raise ValueError("cannot approve with a failed rule")
        if self.approved and self.approved_quantity == 0:
            raise ValueError("approved quantity must be positive")
        if not self.approved and self.approved_quantity != 0:
            raise ValueError("rejected approvals carry zero quantity")
        if self.token is not None and self.token.decision_id != self.decision_id:
            raise ValueError("token decision_id mismatch")
        return self


class AlertType(StrEnum):
    NEWS_SHOCK = "news_shock"
    LIQUIDITY_COLLAPSE = "liquidity_collapse"
    DRAWDOWN_BREACH = "drawdown_breach"
    DATA_FAILURE = "data_failure"
    RECONCILIATION_MISMATCH = "reconciliation_mismatch"
    AGENT_FAILURE = "agent_failure"
    BROKER_DISCONNECT = "broker_disconnect"
    KILL_SWITCH = "kill_switch"


class RiskAlert(StrictModel):
    kind: Literal["risk_alert"] = "risk_alert"
    alert_id: UUID
    alert_type: AlertType
    severity: Severity
    freeze_new_orders: bool
    flatten_positions: bool = False
    limit_multiplier: Annotated[float, Field(gt=0.0, le=1.0)] = 1.0
    detail: ShortText
    # Only a human may clear a freeze; the clearing message references this alert.
    clears_alert_id: UUID | None = None
