"""In-memory message bus for tests and backtests.

Implements the bus contract: audit before delivery, interrupts first, per-topic publish
order otherwise, and per-group deduplication on message_id (delivery is at-least-once, so
re-publishing the same envelope is harmless).
"""

from __future__ import annotations

import heapq
from collections import defaultdict
from collections.abc import Iterable

from aitrader.audit.interface import AuditLog
from aitrader.bus.interface import Handler
from aitrader.schemas.base import Priority
from aitrader.schemas.canonical import canonical_json
from aitrader.schemas.envelope import Envelope, Topic

_RANK = {Priority.INTERRUPT: 0, Priority.HIGH: 1, Priority.NORMAL: 2, Priority.LOW: 3}


class InMemoryBus:
    def __init__(self, audit: AuditLog) -> None:
        self._audit = audit
        self._subscribers: dict[Topic, list[tuple[str, Handler]]] = defaultdict(list)
        self._seen: dict[str, set[str]] = defaultdict(set)
        self._queue: list[tuple[int, int, Envelope]] = []
        self._counter = 0
        self._draining = False

    def subscribe(self, topics: Iterable[Topic], handler: Handler, *, group: str) -> None:
        for topic in topics:
            self._subscribers[topic].append((group, handler))

    async def publish(self, envelope: Envelope) -> None:
        # Envelopes are validated on construction; re-validate in case one was built with
        # model_construct, which skips validation.
        envelope = Envelope.model_validate(envelope.model_dump())
        self._audit.append("envelope", canonical_json(envelope))
        heapq.heappush(self._queue, (_RANK[envelope.priority], self._counter, envelope))
        self._counter += 1
        if not self._draining:
            await self._drain()

    async def _drain(self) -> None:
        self._draining = True
        try:
            while self._queue:
                _, _, envelope = heapq.heappop(self._queue)
                for group, handler in list(self._subscribers[envelope.topic]):
                    key = str(envelope.message_id)
                    if key in self._seen[group]:
                        continue
                    self._seen[group].add(key)
                    await handler(envelope)
        finally:
            self._draining = False

    async def drain(self) -> None:
        if not self._draining:
            await self._drain()

    async def replay(self, topics: Iterable[Topic], *, from_sequence: int = 0) -> list[Envelope]:
        wanted = set(topics)
        out = []
        for record in self._audit.records(from_sequence):
            if record.record_type == "envelope":
                envelope = Envelope.model_validate_json(record.body_json)
                if envelope.topic in wanted:
                    out.append(envelope)
        return out
