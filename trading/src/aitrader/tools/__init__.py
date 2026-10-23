"""Institutional tool layer: shared, deterministic, unit-tested functions with typed I/O.
Agents call tools through ``ToolRunner`` so every call is logged (ToolCallRecord)."""

from aitrader.tools.registry import ToolError, ToolRunner, ToolSpec, registry, tool

__all__ = ["ToolError", "ToolRunner", "ToolSpec", "registry", "tool"]
