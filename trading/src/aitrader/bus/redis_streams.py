"""Redis Streams bus (paper and live stages).

Implements the bus contract across processes:
- ``publish`` validates the envelope and writes it to the audit log before adding it to a
  stream; an envelope that fails validation never reaches Redis.
- one stream per topic, plus one interrupt stream that every consumer group reads first,
  so a risk alert overtakes queued normal messages;
- each subscriber group is a Redis consumer group: a message is acknowledged only after
  its handler succeeds, so a crash mid-handler means redelivery (at-least-once). Handlers
  still see each message id once per group: ids already handled are kept in a per-group set.
  A message whose handler keeps failing goes to a dead-letter stream after three attempts.
- ``replay`` reads the audit log, the system of record, not Redis.

Delivery happens in ``drain`` (everything currently queued) or ``run`` (until stopped),
not inside ``publish``.
"""

from __future__ import annotations

import asyncio
from collections import defaultdict
from collections.abc import Iterable
from typing import Any

from aitrader.audit.interface import AuditLog
from aitrader.bus.interface import Handler
from aitrader.schemas.base import Priority
from aitrader.schemas.canonical import canonical_json
from aitrader.schemas.envelope import Envelope, Topic


class RedisStreamsBus:
    def __init__(
        self,
        redis: Any,  # redis.asyncio.Redis
        audit: AuditLog,
        *,
        prefix: str = "aitrader",
        consumer: str = "worker-1",
        max_stream_length: int = 1_000_000,
    ) -> None:
        self._redis = redis
        self._audit = audit
        self._prefix = prefix
        self._consumer = consumer
        self._maxlen = max_stream_length
        self._groups: dict[str, list[tuple[frozenset[Topic], Handler]]] = defaultdict(list)
        self._ready: set[tuple[str, str]] = set()
        self._stopping = False
        self._attempts: dict[tuple[str, str], int] = defaultdict(int)
        self.max_attempts = 3
        self.dead_lettered: list[str] = []

    @classmethod
    def from_url(cls, url: str, audit: AuditLog, **kw: Any) -> RedisStreamsBus:
        import redis.asyncio

        return cls(redis.asyncio.Redis.from_url(url), audit, **kw)

    def _stream(self, topic: Topic | None) -> str:
        return f"{self._prefix}:interrupts" if topic is None else f"{self._prefix}:topic:{topic.value}"

    def subscribe(self, topics: Iterable[Topic], handler: Handler, *, group: str) -> None:
        self._groups[group].append((frozenset(topics), handler))

    async def publish(self, envelope: Envelope) -> None:
        envelope = Envelope.model_validate(envelope.model_dump())
        self._audit.append("envelope", canonical_json(envelope))
        interrupt = envelope.priority is Priority.INTERRUPT
        await self._redis.xadd(
            self._stream(None if interrupt else envelope.topic),
            {"message_id": str(envelope.message_id), "body": envelope.model_dump_json()},
            maxlen=self._maxlen,
            approximate=True,
        )

    async def _ensure_group(self, stream: str, group: str) -> None:
        if (stream, group) in self._ready:
            return
        try:
            await self._redis.xgroup_create(stream, group, id="0", mkstream=True)
        except Exception as exc:  # BUSYGROUP: created earlier, possibly by another process
            if "BUSYGROUP" not in str(exc):
                raise
        self._ready.add((stream, group))

    async def _read(
        self, group: str, stream: str, *, pending: bool, count: int
    ) -> list[tuple[bytes, dict[bytes, bytes]]]:
        await self._ensure_group(stream, group)
        response = await self._redis.xreadgroup(
            group, self._consumer, {stream: "0" if pending else ">"}, count=count
        )
        return [entry for _, entries in response or [] for entry in entries if entry[1]]

    async def _deliver(self, group: str, stream: str, entry: tuple[bytes, dict[bytes, bytes]]) -> None:
        entry_id, fields = entry
        message_id = fields[b"message_id"].decode()
        seen_key = f"{self._prefix}:seen:{group}"
        if not await self._redis.sismember(seen_key, message_id):
            envelope = Envelope.model_validate_json(fields[b"body"])
            try:
                for topics, handler in list(self._groups[group]):
                    if envelope.topic in topics:
                        await handler(envelope)
            except Exception as exc:
                # Left unacknowledged, the entry is redelivered on the next poll; after
                # max_attempts it moves to the group's dead-letter stream for a human.
                key = (group, message_id)
                self._attempts[key] += 1
                if self._attempts[key] < self.max_attempts:
                    return
                await self._redis.xadd(
                    f"{self._prefix}:dead:{group}",
                    {
                        "message_id": message_id,
                        "body": fields[b"body"],
                        "error": f"{type(exc).__name__}: {exc}",
                    },
                )
                self.dead_lettered.append(message_id)
            else:
                await self._redis.sadd(seen_key, message_id)
        await self._redis.xack(stream, group, entry_id)

    async def poll(self, count: int = 100) -> int:
        """Deliver one batch per group, interrupts first. Returns the number of entries handled."""
        handled = 0
        for group, subscriptions in list(self._groups.items()):
            topics = sorted({t for ts, _ in subscriptions for t in ts}, key=lambda t: t.value)
            streams = [self._stream(None), *(self._stream(t) for t in topics)]
            for stream in streams:
                for pending in (True, False):  # retry our own unacknowledged entries first
                    for entry in await self._read(group, stream, pending=pending, count=count):
                        await self._deliver(group, stream, entry)
                        handled += 1
                if stream == streams[0] and handled:
                    return handled  # interrupts were handled: start again before normal traffic
        return handled

    async def drain(self) -> None:
        while await self.poll():
            pass

    async def run(self, idle_sleep: float = 0.05) -> None:
        self._stopping = False
        while not self._stopping:
            if not await self.poll():
                await asyncio.sleep(idle_sleep)

    def stop(self) -> None:
        self._stopping = True

    async def replay(self, topics: Iterable[Topic], *, from_sequence: int = 0) -> list[Envelope]:
        wanted = set(topics)
        out = []
        for record in self._audit.records(from_sequence):
            if record.record_type == "envelope":
                envelope = Envelope.model_validate_json(record.body_json)
                if envelope.topic in wanted:
                    out.append(envelope)
        return out
