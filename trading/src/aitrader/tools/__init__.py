"""Institutional tool layer: shared, deterministic, unit-tested functions with typed I/O.
Agents call tools through the registry so every call is logged (ToolCallRecord).
Implementations arrive in Phase 2; see docs/ARCHITECTURE.md for the catalogue."""

from aitrader.tools.registry import ToolSpec

__all__ = ["ToolSpec"]
