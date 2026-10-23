"""Hash-chained, append-only audit log.

record_hash = sha256(prev_hash | body_sha256 | sequence | recorded_at). Altering, removing,
inserting or reordering any record breaks every later hash, which ``verify_chain`` detects.
``JsonlAuditLog`` persists one record per line, fsyncs every append and verifies the whole
chain when opened, refusing to append to a corrupted file.
"""

from __future__ import annotations

import os
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import get_args

from aitrader.schemas.audit import GENESIS_HASH, AuditRecord
from aitrader.schemas.canonical import sha256_hex

RecordType = AuditRecord.model_fields["record_type"].annotation
RECORD_TYPES = frozenset(get_args(RecordType))


class AuditChainError(RuntimeError):
    pass


def chain_hash(prev_hash: str, body_sha256: str, sequence: int, recorded_at: datetime) -> str:
    return sha256_hex(f"{prev_hash}|{body_sha256}|{sequence}|{recorded_at.isoformat()}")


def verify_records(records: list[AuditRecord]) -> bool:
    prev = GENESIS_HASH
    for expected_sequence, record in enumerate(records):
        if record.sequence != expected_sequence or record.prev_hash != prev:
            return False
        if sha256_hex(record.body_json) != record.body_sha256:
            return False
        if chain_hash(prev, record.body_sha256, record.sequence, record.recorded_at) != record.record_hash:
            return False
        prev = record.record_hash
    return True


class InMemoryAuditLog:
    def __init__(self, clock: Callable[[], datetime]) -> None:
        self._clock = clock
        self._records: list[AuditRecord] = []

    def _next(self, record_type: str, body_json: str) -> AuditRecord:
        if record_type not in RECORD_TYPES:
            raise ValueError(f"unknown audit record type {record_type!r}")
        prev = self._records[-1].record_hash if self._records else GENESIS_HASH
        sequence = len(self._records)
        recorded_at = self._clock()
        body_sha = sha256_hex(body_json)
        return AuditRecord(
            sequence=sequence,
            recorded_at=recorded_at,
            record_type=record_type,
            body_json=body_json,
            body_sha256=body_sha,
            prev_hash=prev,
            record_hash=chain_hash(prev, body_sha, sequence, recorded_at),
        )

    def append(self, record_type: str, body_json: str) -> AuditRecord:
        record = self._next(record_type, body_json)
        self._records.append(record)
        return record

    def verify_chain(self) -> bool:
        return verify_records(self._records)

    def records(self, from_sequence: int = 0) -> list[AuditRecord]:
        return self._records[from_sequence:]


class JsonlAuditLog(InMemoryAuditLog):
    def __init__(self, path: Path, clock: Callable[[], datetime]) -> None:
        super().__init__(clock)
        self._path = path
        if path.exists():
            with path.open(encoding="utf-8") as handle:
                self._records = [AuditRecord.model_validate_json(line) for line in handle if line.strip()]
            if not verify_records(self._records):
                raise AuditChainError(f"audit chain in {path} is corrupted; refusing to append")

    def append(self, record_type: str, body_json: str) -> AuditRecord:
        record = self._next(record_type, body_json)
        with self._path.open("a", encoding="utf-8") as handle:
            handle.write(record.model_dump_json() + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        self._records.append(record)
        return record
