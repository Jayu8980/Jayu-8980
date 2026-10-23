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

import bisect
import json
from collections.abc import Iterable, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Any, Protocol

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


_CHUNK = 500  # rows per multi-row INSERT (7 parameters each)


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
            for i in range(0, len(rows), _CHUNK):
                chunk = rows[i : i + _CHUNK]
                values = ", ".join(["(?, ?, ?, ?, ?, ?, ?)"] * len(chunk))
                params = [value for row in chunk for value in row]
                # Duplicates inside one batch are collapsed first; ON CONFLICT skips existing rows.
                self._con.execute(
                    f"""
                    INSERT INTO pit_records
                    SELECT nextval('pit_seq'), * FROM (
                        SELECT DISTINCT * FROM (VALUES {values})
                            AS v(dataset, entity, event_time, available_at, ingested_at, payload_sha, payload)
                    )
                    ON CONFLICT DO NOTHING
                    """,
                    params,
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


class PitReader(Protocol):
    def read_as_of(
        self,
        dataset: str,
        as_of: datetime,
        *,
        entities: Sequence[str] | None = None,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> list[PitRow]: ...

    def latest_available_at(self, dataset: str, entity: str | None = None) -> datetime | None: ...

    def entities(self, dataset: str, as_of: datetime) -> list[str]: ...


def _versions(store: PitStore, dataset: str, entity: str) -> list[PitRow]:
    rows = store._con.execute(
        """
        SELECT dataset, entity, event_time, available_at, payload FROM pit_records
        WHERE dataset = ? AND entity = ? ORDER BY event_time, available_at, seq
        """,
        [dataset, entity],
    ).fetchall()
    return [
        PitRow(dataset=ds, entity=e, event_time=_aware(et), available_at=_aware(av), payload=json.loads(p))
        for ds, e, et, av, p in rows
    ]


class CachedPitReader:
    """Read-only, in-memory view of a ``PitStore`` for backtests, with exactly the same as-of
    semantics as ``PitStore.read_as_of``. Each (dataset, entity) history is loaded once; a
    caller that writes to the store afterwards (the daily paper service) must ``refresh()``."""

    def __init__(self, store: PitStore) -> None:
        self._store = store
        self._cache: dict[tuple[str, str], tuple[list[datetime], list[PitRow]]] = {}
        self._first_seen: dict[str, list[tuple[str, datetime]]] = {}

    def refresh(self) -> None:
        """Forget cached histories, so the next reads see rows written since."""
        self._cache.clear()
        self._first_seen.clear()

    def _history(self, dataset: str, entity: str) -> tuple[list[datetime], list[PitRow]]:
        key = (dataset, entity)
        if key not in self._cache:
            rows = _versions(self._store, dataset, entity)
            self._cache[key] = ([r.event_time for r in rows], rows)
        return self._cache[key]

    def read_as_of(
        self,
        dataset: str,
        as_of: datetime,
        *,
        entities: Sequence[str] | None = None,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> list[PitRow]:
        if entities is None:
            return self._store.read_as_of(dataset, as_of, start=start, end=end)
        out: list[PitRow] = []
        for entity in sorted(set(entities)):
            times, rows = self._history(dataset, entity)
            lo = bisect.bisect_left(times, start) if start is not None else 0
            hi = bisect.bisect_right(times, end) if end is not None else len(times)
            latest: dict[datetime, PitRow] = {}
            for row in rows[lo:hi]:
                if row.available_at <= as_of:
                    latest[row.event_time] = row  # rows are ordered, so the last visible version wins
            out.extend(latest[t] for t in sorted(latest))
        return out

    def latest_available_at(self, dataset: str, entity: str | None = None) -> datetime | None:
        return self._store.latest_available_at(dataset, entity)

    def entities(self, dataset: str, as_of: datetime) -> list[str]:
        """Entities with at least one record available at ``as_of`` (same as the store)."""
        if dataset not in self._first_seen:
            rows = self._store._con.execute(
                "SELECT entity, min(available_at) FROM pit_records"
                " WHERE dataset = ? GROUP BY entity ORDER BY entity",
                [dataset],
            ).fetchall()
            self._first_seen[dataset] = [(entity, _aware(first)) for entity, first in rows]
        return [entity for entity, first in self._first_seen[dataset] if first <= as_of]
