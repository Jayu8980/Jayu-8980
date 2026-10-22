"""Order execution agent: deterministic code, never an LLM.

Executes only instructions carrying a valid, unexpired, unused risk approval token bound to
the exact instruction digest. Implementation lands in Phase 6 (paper mode first).
"""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.EXECUTION,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Verify the approval token, run pre-trade checks (margin, limits, price bands, circuit "
        "limits, freeze quantity, market hours, duplicates), slice per the liquidity plan, place "
        "protective stops and targets on fill, retry idempotently, reconcile with the broker and "
        "publish order updates and fills."
    ),
    consumes=(Topic.ORDERS_INSTRUCTIONS, Topic.RISK_ALERTS),
    publishes=(Topic.ORDERS_UPDATES, Topic.ORDERS_FILLS, Topic.RISK_ALERTS),
    tools=("broker.place", "broker.cancel", "broker.positions", "broker.orders", "micro.slicer"),
    timeout_key=None,
    failure_mode=FailureMode.FREEZE,
    failure_behaviour=(
        "Broker disconnect or reconciliation mismatch raises a freeze alert; no new orders are "
        "sent, resting protective stop orders stay at the broker, and a human clears the freeze."
    ),
    performance_metrics=("slippage_bps", "reject_rate", "reconciliation_mismatches", "fill_latency_ms"),
)
