"""Shared state store: the single source of truth for positions, regime, event calendar,
limits, trading-freeze status, consumed approval tokens and used idempotency keys."""

from aitrader.state.interface import FreezeStatus, StateStore

__all__ = ["FreezeStatus", "StateStore"]
