"""In-memory state store for tests and backtests. A lock makes the single-use operations
atomic within a process; the Postgres store (before paper trading) does the same with
unique constraints across processes and restarts."""

from __future__ import annotations

import threading
from datetime import datetime
from uuid import UUID

from aitrader.schemas.regime import RegimeLabel
from aitrader.state.interface import FreezeStatus


class FreezeClearRefusedError(PermissionError):
    pass


class InMemoryStateStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._freeze_reasons: dict[UUID, datetime] = {}
        self._tokens: set[UUID] = set()
        self._keys: set[str] = set()
        self._multiplier = 1.0
        self._regime = RegimeLabel.UNKNOWN

    def freeze_status(self) -> FreezeStatus:
        with self._lock:
            if not self._freeze_reasons:
                return FreezeStatus(frozen=False)
            return FreezeStatus(
                frozen=True,
                reason_alert_ids=tuple(sorted(self._freeze_reasons, key=str)),
                since=min(self._freeze_reasons.values()),
            )

    def set_frozen(self, alert_id: UUID, at: datetime) -> None:
        with self._lock:
            self._freeze_reasons.setdefault(alert_id, at)

    def clear_freeze(self, alert_id: UUID, *, cleared_by_human: bool) -> None:
        if not cleared_by_human:
            raise FreezeClearRefusedError("only a human can clear a trading freeze")
        with self._lock:
            self._freeze_reasons.pop(alert_id, None)

    def consume_token(self, token_id: UUID) -> bool:
        with self._lock:
            if token_id in self._tokens:
                return False
            self._tokens.add(token_id)
            return True

    def claim_idempotency_key(self, key: str) -> bool:
        with self._lock:
            if key in self._keys:
                return False
            self._keys.add(key)
            return True

    def limit_multiplier(self) -> float:
        return self._multiplier

    def set_limit_multiplier(self, value: float) -> None:
        if not 0 < value <= 1:
            raise ValueError("limit multiplier must be in (0, 1]")
        self._multiplier = value

    def current_regime(self) -> RegimeLabel:
        return self._regime

    def set_regime(self, regime: RegimeLabel) -> None:
        self._regime = regime
