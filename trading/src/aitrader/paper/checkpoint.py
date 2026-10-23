"""Where paper-run checkpoints live.

A checkpoint is written after every completed session. A file store replaces its file
atomically (write, fsync, rename), so a crash leaves either the old or the new checkpoint,
never a torn one. The Postgres store keeps one row per run name in the same database as the
state store and order book.
"""

from __future__ import annotations

import os
import re
import threading
from pathlib import Path
from typing import Any, Protocol

from aitrader.paper.runner import PaperCheckpoint

_SCHEMA_NAME = re.compile(r"^[a-z_][a-z0-9_]{0,40}$")


class CheckpointStore(Protocol):
    def load(self) -> PaperCheckpoint | None: ...

    def save(self, checkpoint: PaperCheckpoint) -> None: ...


class FileCheckpointStore:
    def __init__(self, path: Path) -> None:
        self._path = path

    def load(self) -> PaperCheckpoint | None:
        if not self._path.exists():
            return None
        return PaperCheckpoint.model_validate_json(self._path.read_text(encoding="utf-8"))

    def save(self, checkpoint: PaperCheckpoint) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(self._path.suffix + ".tmp")
        with tmp.open("w", encoding="utf-8") as handle:
            handle.write(checkpoint.model_dump_json())
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, self._path)


class PostgresCheckpointStore:
    def __init__(self, conn: Any, schema: str = "aitrader", name: str = "paper") -> None:
        if not _SCHEMA_NAME.match(schema):
            raise ValueError(f"invalid schema name {schema!r}")
        self._conn = conn
        self._s = schema
        self._name = name
        self._lock = threading.Lock()
        with self._lock:
            conn.execute(f"CREATE SCHEMA IF NOT EXISTS {schema}")
            conn.execute(
                f"CREATE TABLE IF NOT EXISTS {schema}.paper_checkpoints ("
                "name text PRIMARY KEY, saved_at timestamptz NOT NULL, body jsonb NOT NULL)"
            )

    def load(self) -> PaperCheckpoint | None:
        with self._lock:
            row = self._conn.execute(
                f"SELECT body::text FROM {self._s}.paper_checkpoints WHERE name = %s", (self._name,)
            ).fetchone()
        return PaperCheckpoint.model_validate_json(row[0]) if row else None

    def save(self, checkpoint: PaperCheckpoint) -> None:
        with self._lock:
            self._conn.execute(
                f"INSERT INTO {self._s}.paper_checkpoints (name, saved_at, body) VALUES (%s, %s, %s::jsonb) "
                "ON CONFLICT (name) DO UPDATE SET saved_at = EXCLUDED.saved_at, body = EXCLUDED.body",
                (self._name, checkpoint.saved_at, checkpoint.model_dump_json()),
            )
