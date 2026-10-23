"""Bus contract.

Guarantees every backend must provide:
- publish() validates the envelope (topic/payload/publisher/timestamps) before delivery and
  writes it to the audit log first; an envelope that fails validation is never delivered.
- delivery is at-least-once, so consumers deduplicate on message_id.
- INTERRUPT messages are delivered ahead of any queued lower-priority message.
- per-topic ordering is by publish order; there is no cross-topic ordering guarantee.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Iterable
from typing import Protocol

from aitrader.schemas.envelope import Envelope, Topic

Handler = Callable[[Envelope], Awaitable[None]]


class MessageBus(Protocol):
    async def publish(self, envelope: Envelope) -> None: ...

    def subscribe(self, topics: Iterable[Topic], handler: Handler, *, group: str) -> None: ...

    async def drain(self) -> None:
        """Deliver everything published so far (immediate for the in-memory bus)."""
        ...

    async def replay(self, topics: Iterable[Topic], *, from_sequence: int = 0) -> list[Envelope]:
        """Return recorded envelopes in audit order, for deterministic replay."""
        ...
