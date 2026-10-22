"""Message bus. Phase 1 defines the contract; the in-memory and Redis Streams backends land
with the components that need them (in-memory in Phase 3, Redis Streams before paper trading)."""

from aitrader.bus.interface import Handler, MessageBus

__all__ = ["Handler", "MessageBus"]
