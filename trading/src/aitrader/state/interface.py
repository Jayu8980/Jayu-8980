"""State store contract. Writes that protect safety invariants must be atomic:
consume_token() and claim_idempotency_key() succeed exactly once per value, even when called
concurrently or after a restart. That is what makes replayed instructions harmless."""

from __future__ import annotations

from typing import Protocol
from uuid import UUID

from aitrader.schemas.base import StrictModel, UtcDatetime
from aitrader.schemas.regime import RegimeLabel


class FreezeStatus(StrictModel):
    frozen: bool
    reason_alert_ids: tuple[UUID, ...] = ()
    since: UtcDatetime | None = None


class StateStore(Protocol):
    def freeze_status(self) -> FreezeStatus: ...

    def set_frozen(self, alert_id: UUID, at: UtcDatetime) -> None: ...

    def clear_freeze(self, alert_id: UUID, *, cleared_by_human: bool) -> None:
        """Refuses unless cleared_by_human is true."""
        ...

    def consume_token(self, token_id: UUID) -> bool:
        """Atomically mark an approval token used. False if it was already used."""
        ...

    def claim_idempotency_key(self, key: str) -> bool:
        """Atomically claim an instruction's idempotency key. False if already claimed."""
        ...

    def limit_multiplier(self) -> float: ...

    def set_limit_multiplier(self, value: float) -> None: ...

    def current_regime(self) -> RegimeLabel: ...

    def set_regime(self, regime: RegimeLabel) -> None: ...
