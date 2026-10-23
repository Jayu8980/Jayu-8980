"""Envelope construction shared by analyst agents."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from aitrader.bus.interface import MessageBus
from aitrader.schemas.base import AgentId, Priority, StrictModel
from aitrader.schemas.envelope import Envelope, Topic


async def publish(
    bus: MessageBus,
    *,
    topic: Topic,
    agent_id: AgentId,
    payload: StrictModel,
    available_at: datetime,
    produced_at: datetime,
    cycle_id: UUID | None = None,
    event_time: datetime | None = None,
    causation_ids: tuple[UUID, ...] = (),
    priority: Priority = Priority.NORMAL,
    message_id: UUID | None = None,
) -> Envelope:
    """Publish one payload. ``available_at`` is when the newest input to it became known;
    the envelope rejects anything produced before that."""
    envelope = Envelope.model_validate(
        {
            **({"message_id": message_id} if message_id else {}),
            "topic": topic,
            "agent_id": agent_id,
            "cycle_id": cycle_id,
            "causation_ids": causation_ids,
            "event_time": event_time or available_at,
            "available_at": available_at,
            "produced_at": produced_at,
            "priority": priority,
            "payload": payload,
        }
    )
    await bus.publish(envelope)
    return envelope
