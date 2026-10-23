"""Data agent."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.DATA,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Ingest OHLCV, corporate actions, fundamentals, filings, FII/DII and participant OI, macro releases and news; validate, adjust for corporate actions, and store point-in-time keyed by publication time, including delisted instruments."
    ),
    consumes=(),
    publishes=(Topic.DATA_BARS, Topic.DATA_CORPORATE_ACTIONS, Topic.DATA_QUALITY, Topic.RISK_ALERTS),
    tools=(),  # deterministic pipeline: storage/pit.py, agents/data/{adjust,quality}.py
    timeout_key=None,
    failure_mode=FailureMode.FREEZE,
    failure_behaviour=(
        "A stale or failed primary feed raises a DATA_FAILURE risk alert, which freezes new entries; existing protective stops stay with the broker."
    ),
    performance_metrics=("feed_latency_seconds", "gap_count", "outlier_count", "source_disagreement_count"),
)
