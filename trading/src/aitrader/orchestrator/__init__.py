"""Orchestrator: runs decision cycles on schedule and on events, enforces per-agent timeouts,
loop and call limits, monitors heartbeats and routes priority interrupts first. Phase 5."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.ORCHESTRATOR,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Schedule and drive decision cycles, apply timeouts and call/loop caps, track heartbeats, "
        "proceed without failed agents and report failures so the risk agent tightens limits."
    ),
    consumes=tuple(Topic),
    publishes=(Topic.SYSTEM_HEARTBEAT, Topic.RISK_ALERTS),
    failure_mode=FailureMode.FREEZE,
    failure_behaviour=(
        "If the orchestrator dies no cycles run, so no new decisions are made; the risk agent "
        "raises a freeze on missing orchestrator heartbeats. Broker-side stops protect positions."
    ),
    performance_metrics=("cycle_latency_ms", "timeouts_by_agent", "llm_cost_per_cycle_usd"),
)
