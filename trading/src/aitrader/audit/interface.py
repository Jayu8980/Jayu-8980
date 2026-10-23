"""Audit log contract."""

from __future__ import annotations

from typing import Protocol

from aitrader.schemas.audit import AuditRecord


class AuditLog(Protocol):
    def append(self, record_type: str, body_json: str) -> AuditRecord:
        """Append and return the chained record. Never updates or deletes."""
        ...

    def verify_chain(self) -> bool:
        """Recompute every hash; False if any record was altered, removed or reordered."""
        ...

    def records(self, from_sequence: int = 0) -> list[AuditRecord]: ...
