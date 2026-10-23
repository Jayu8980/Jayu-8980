"""Append-only, bitemporal point-in-time store (DuckDB).

Every record carries:
- event_time:   when the fact happened or was published at the source,
- available_at: the earliest time the system could have known it (the PIT key),
- ingested_at:  when we stored it (kept for operations; never used to decide visibility).

``read_as_of(t)`` only sees records with available_at <= t. Corrections are new versions of
the same (dataset, entity, event_time) with a later available_at; an as-of read returns the
latest version visible at t, so a backtest at t never sees a correction published after t.

There is no update or delete API. Writing the same record twice is a no-op, so ingestion is
idempotent and safe to retry.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Any

import duckdb
from pydantic import Field, model_validator

from aitrader.schemas.base import StrictModel, UtcDatetime
from aitrader.schemas.canonical import sha256_hex

Name = Annotated[str, Field(min_length=1, max_length=200)]


class PitRecord(StrictModel):
    dataset: Name
    entity: Name
    event_time: UtcDatetime
    available_at: UtcDatetime
    payload: dict[str, Any]

    @model_validator(mode="after")
    def _no_lookahead(self) -> PitRecord:
        if self.available_at < self.event_time:
            raise ValueError("available_at cannot precede event_time")
        return self

    @property
    def payload_json(self) -> str:
        return json.dumps(self.payload, sort_keys=True, separators=(",", ":"), default=str)


class PitRow(StrictModel):
    dataset: str
    entity: str
    event_time: UtcDatetime
    available_at: UtcDatetime
    payload: dict[str, Any]


_SCHEMA = """
CREATE TABLE IF NOT EXISTS pit_records (
    seq          BIGINT PRIMARY KEY,
    dataset      VARCHAR NOT NULL,
    entity       VARCHAR NOT NULL,
    event_time   TIMESTAMP NOT NULL,   -- naive UTC
    available_at TIMESTAMP NOT NULL,   -- naive UTC
    ingested_at  TIMESTAMP NOT NULL,   -- naive UTC
    payload_sha  VARCHAR NOT NULL,
    payload      VARCHAR NOT NULL,
    UNIQUE (dataset, entity, event_time, available_at, payload_sha)
);
CREATE SEQUENCE IF NOT EXISTS pit_seq START 1;
"""


def _naive(ts: datetime) -> datetime:
    return ts.astimezone(UTC).replace(tzinfo=None)


def _aware(ts: datetime) -> datetime:
    return ts.replace(tzinfo=UTC)


class PitStore:
    def __init__(self, path: Path | str = ":memory:") -> None:
        self._con = duckdb.connect(str(path))
        self._con.execute(_SCHEMA)

    def close(self) -> None:
        self._con.close()

    def write(self, records: Iterable[PitRecord], ingested_at: datetime) -> int:
        """Append records; returns how many were new (exact duplicates are skipped)."""
        rows = [
            (
                r.dataset,
                r.entity,
                _naive(r.event_time),
                _naive(r.available_at),
                _naive(ingested_at),
                sha256_hex(r.payload_json),
                r.payload_json,
            )
            for r in records
        ]
        if not rows:
            return 0
        before = self.count()
        self._con.execute("BEGIN TRANSACTION")
        try:
            self._con.executemany(
                """
                INSERT INTO pit_records
                SELECT nextval('pit_seq'), ?, ?, ?, ?, ?, ?, ?
                ON CONFLICT DO NOTHING
                """,
                rows,
            )
            self._con.execute("COMMIT")
        except Exception:
            self._con.execute("ROLLBACK")
            raise
        return self.count() - before

    def count(self, dataset: str | None = None) -> int:
        if dataset is None:
            result = self._con.execute("SELECT count(*) FROM pit_records").fetchone()
        else:
            result = self._con.execute(
                "SELECT count(*) FROM pit_records WHERE dataset = ?", [dataset]
            ).fetchone()
        return int(result[0]) if result else 0

    def read_as_of(
        self,
        dataset: str,
        as_of: datetime,
        *,
        entities: Sequence[str] | None = None,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> list[PitRow]:
        """Latest visible version of each (entity, event_time) with event_time in [start, end],
        ordered by entity then event_time."""
        clauses = ["dataset = ?", "available_at <= ?"]
        params: list[Any] = [dataset, _naive(as_of)]
        if entities is not None:
            if not entities:
                return []
            clauses.append(f"entity IN ({', '.join('?' * len(entities))})")
            params.extend(entities)
        if start is not None:
            clauses.append("event_time >= ?")
            params.append(_naive(start))
        if end is not None:
            clauses.append("event_time <= ?")
            params.append(_naive(end))
        query = f"""
            SELECT dataset, entity, event_time, available_at, payload
            FROM pit_records
            WHERE {" AND ".join(clauses)}
            QUALIFY row_number() OVER (
                PARTITION BY entity, event_time ORDER BY available_at DESC, seq DESC
            ) = 1
            ORDER BY entity, event_time
        """
        return [
            PitRow(
                dataset=ds,
                entity=entity,
                event_time=_aware(event_time),
                available_at=_aware(available_at),
                payload=json.loads(payload),
            )
            for ds, entity, event_time, available_at, payload in self._con.execute(query, params).fetchall()
        ]

    def latest_available_at(self, dataset: str, entity: str | None = None) -> datetime | None:
        if entity is None:
            result = self._con.execute(
                "SELECT max(available_at) FROM pit_records WHERE dataset = ?", [dataset]
            ).fetchone()
        else:
            result = self._con.execute(
                "SELECT max(available_at) FROM pit_records WHERE dataset = ? AND entity = ?",
                [dataset, entity],
            ).fetchone()
        return _aware(result[0]) if result and result[0] is not None else None

    def entities(self, dataset: str, as_of: datetime) -> list[str]:
        rows = self._con.execute(
            "SELECT DISTINCT entity FROM pit_records WHERE dataset = ? AND available_at <= ? ORDER BY entity",
            [dataset, _naive(as_of)],
        ).fetchall()
        return [r[0] for r in rows]
