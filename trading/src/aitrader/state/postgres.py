"""Postgres-backed state store and order book (paper and live stages).

The safety invariants rest on primary keys, not on application locks: a token id or an
idempotency key can be inserted exactly once across every process and restart, and a broker
trade id can be booked exactly once. Child-order updates read the current row ``FOR UPDATE``
and apply the state-machine check inside the same transaction.

Records are stored as JSONB documents written from the pydantic models and read back through
them, so a row that no longer validates fails loudly instead of being half-read. Every table
lives in one schema (``aitrader`` by default) created on first use.
"""

from __future__ import annotations

import re
import threading
from datetime import datetime
from typing import Any
from uuid import UUID

from aitrader.execution.orderbook import (
    ChildOrder,
    InstructionRecord,
    Protection,
    check_update,
)
from aitrader.schemas.orders import Fill
from aitrader.schemas.regime import RegimeLabel
from aitrader.state.interface import FreezeStatus
from aitrader.state.memory import FreezeClearRefusedError

_SCHEMA_NAME = re.compile(r"^[a-z_][a-z0-9_]{0,40}$")

DDL = """
CREATE TABLE IF NOT EXISTS {s}.freeze (
    alert_id uuid PRIMARY KEY, since timestamptz NOT NULL);
CREATE TABLE IF NOT EXISTS {s}.consumed_tokens (
    token_id uuid PRIMARY KEY, consumed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS {s}.idempotency_keys (
    key text PRIMARY KEY, claimed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS {s}.settings (name text PRIMARY KEY, value text NOT NULL);
CREATE TABLE IF NOT EXISTS {s}.instructions (
    instruction_id uuid PRIMARY KEY, received_at timestamptz NOT NULL, body jsonb NOT NULL);
CREATE TABLE IF NOT EXISTS {s}.child_orders (
    child_order_id text PRIMARY KEY, instruction_id uuid NOT NULL, slice_index int NOT NULL,
    body jsonb NOT NULL);
CREATE TABLE IF NOT EXISTS {s}.protections (protection_id text PRIMARY KEY, body jsonb NOT NULL);
CREATE TABLE IF NOT EXISTS {s}.fills (
    seq bigserial, broker_trade_id text PRIMARY KEY, body jsonb NOT NULL);
"""


def connect(dsn: str) -> Any:
    import psycopg

    return psycopg.connect(dsn, autocommit=True)


class _Postgres:
    def __init__(self, conn: Any, schema: str = "aitrader") -> None:
        if not _SCHEMA_NAME.match(schema):
            raise ValueError(f"invalid schema name {schema!r}")
        self._conn = conn
        self._s = schema
        self._lock = threading.Lock()
        with self._lock:
            self._conn.execute(f"CREATE SCHEMA IF NOT EXISTS {schema}")
            for statement in DDL.format(s=schema).split(";"):
                if statement.strip():
                    self._conn.execute(statement)

    def _one(self, sql: str, params: tuple[Any, ...] = ()) -> Any:
        with self._lock:
            row = self._conn.execute(sql.format(s=self._s), params).fetchone()
        return row

    def _all(self, sql: str, params: tuple[Any, ...] = ()) -> list[Any]:
        with self._lock:
            rows: list[Any] = self._conn.execute(sql.format(s=self._s), params).fetchall()
        return rows


class PostgresStateStore(_Postgres):
    def freeze_status(self) -> FreezeStatus:
        rows = self._all("SELECT alert_id, since FROM {s}.freeze ORDER BY alert_id::text")
        if not rows:
            return FreezeStatus(frozen=False)
        return FreezeStatus(
            frozen=True, reason_alert_ids=tuple(r[0] for r in rows), since=min(r[1] for r in rows)
        )

    def set_frozen(self, alert_id: UUID, at: datetime) -> None:
        self._one(
            "INSERT INTO {s}.freeze (alert_id, since) VALUES (%s, %s) ON CONFLICT DO NOTHING RETURNING 1",
            (alert_id, at),
        )

    def clear_freeze(self, alert_id: UUID, *, cleared_by_human: bool) -> None:
        if not cleared_by_human:
            raise FreezeClearRefusedError("only a human can clear a trading freeze")
        self._one("DELETE FROM {s}.freeze WHERE alert_id = %s RETURNING 1", (alert_id,))

    def consume_token(self, token_id: UUID) -> bool:
        row = self._one(
            "INSERT INTO {s}.consumed_tokens (token_id) VALUES (%s) ON CONFLICT DO NOTHING RETURNING 1",
            (token_id,),
        )
        return row is not None

    def claim_idempotency_key(self, key: str) -> bool:
        row = self._one(
            "INSERT INTO {s}.idempotency_keys (key) VALUES (%s) ON CONFLICT DO NOTHING RETURNING 1", (key,)
        )
        return row is not None

    def _get(self, name: str) -> str | None:
        row = self._one("SELECT value FROM {s}.settings WHERE name = %s", (name,))
        return row[0] if row else None

    def _set(self, name: str, value: str) -> None:
        self._one(
            "INSERT INTO {s}.settings (name, value) VALUES (%s, %s) "
            "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value RETURNING 1",
            (name, value),
        )

    def limit_multiplier(self) -> float:
        value = self._get("limit_multiplier")
        return float(value) if value is not None else 1.0

    def set_limit_multiplier(self, value: float) -> None:
        if not 0 < value <= 1:
            raise ValueError("limit multiplier must be in (0, 1]")
        self._set("limit_multiplier", repr(value))

    def current_regime(self) -> RegimeLabel:
        value = self._get("regime")
        return RegimeLabel(value) if value is not None else RegimeLabel.UNKNOWN

    def set_regime(self, regime: RegimeLabel) -> None:
        self._set("regime", regime.value)


class PostgresOrderBook(_Postgres):
    def add_instruction(self, record: InstructionRecord) -> bool:
        row = self._one(
            "INSERT INTO {s}.instructions (instruction_id, received_at, body) VALUES (%s, %s, %s::jsonb) "
            "ON CONFLICT DO NOTHING RETURNING 1",
            (record.instruction.instruction_id, record.received_at, record.model_dump_json()),
        )
        return row is not None

    def instruction(self, instruction_id: UUID) -> InstructionRecord | None:
        row = self._one(
            "SELECT body::text FROM {s}.instructions WHERE instruction_id = %s", (instruction_id,)
        )
        return InstructionRecord.model_validate_json(row[0]) if row else None

    def instructions(self) -> list[InstructionRecord]:
        rows = self._all("SELECT body::text FROM {s}.instructions ORDER BY received_at, instruction_id")
        return [InstructionRecord.model_validate_json(r[0]) for r in rows]

    def add_child(self, child: ChildOrder) -> None:
        row = self._one(
            "INSERT INTO {s}.child_orders (child_order_id, instruction_id, slice_index, body) "
            "VALUES (%s, %s, %s, %s::jsonb) ON CONFLICT DO NOTHING RETURNING 1",
            (child.child_order_id, child.instruction_id, child.slice_index, child.model_dump_json()),
        )
        if row is None:
            raise ValueError(f"child {child.child_order_id} already exists")

    def update_child(self, child: ChildOrder) -> None:
        with self._lock, self._conn.transaction():
            row = self._conn.execute(
                f"SELECT body::text FROM {self._s}.child_orders WHERE child_order_id = %s FOR UPDATE",
                (child.child_order_id,),
            ).fetchone()
            if row is None:
                raise KeyError(child.child_order_id)
            check_update(ChildOrder.model_validate_json(row[0]), child)
            self._conn.execute(
                f"UPDATE {self._s}.child_orders SET body = %s::jsonb WHERE child_order_id = %s",
                (child.model_dump_json(), child.child_order_id),
            )

    def child(self, child_order_id: str) -> ChildOrder | None:
        row = self._one(
            "SELECT body::text FROM {s}.child_orders WHERE child_order_id = %s", (child_order_id,)
        )
        return ChildOrder.model_validate_json(row[0]) if row else None

    def children(self, instruction_id: UUID | None = None) -> list[ChildOrder]:
        if instruction_id is None:
            rows = self._all("SELECT body::text FROM {s}.child_orders ORDER BY instruction_id, slice_index")
        else:
            rows = self._all(
                "SELECT body::text FROM {s}.child_orders WHERE instruction_id = %s ORDER BY slice_index",
                (instruction_id,),
            )
        return [ChildOrder.model_validate_json(r[0]) for r in rows]

    def put_protection(self, protection: Protection) -> None:
        self._one(
            "INSERT INTO {s}.protections (protection_id, body) VALUES (%s, %s::jsonb) "
            "ON CONFLICT (protection_id) DO UPDATE SET body = EXCLUDED.body RETURNING 1",
            (protection.protection_id, protection.model_dump_json()),
        )

    def protections(self) -> list[Protection]:
        rows = self._all("SELECT body::text FROM {s}.protections ORDER BY protection_id")
        return [Protection.model_validate_json(r[0]) for r in rows]

    def add_fill(self, fill: Fill) -> bool:
        row = self._one(
            "INSERT INTO {s}.fills (broker_trade_id, body) VALUES (%s, %s::jsonb) "
            "ON CONFLICT DO NOTHING RETURNING 1",
            (fill.broker_trade_id, fill.model_dump_json()),
        )
        return row is not None

    def fills(self) -> list[Fill]:
        rows = self._all("SELECT body::text FROM {s}.fills ORDER BY seq")
        return [Fill.model_validate_json(r[0]) for r in rows]
