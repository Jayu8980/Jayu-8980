"""Bull vs bear debate layer."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.DEBATE,
    kind=AgentKind.LLM,
    responsibility=(
        "Run a bull and a bear agent over the combined analyst view for a capped number of rounds; each states its strongest evidence-cited case and the most important counterargument to itself."
    ),
    consumes=(
        Topic.SIGNALS_CHART,
        Topic.SIGNALS_SMARTMONEY,
        Topic.NEWS_EVENTS,
        Topic.NEWS_IMPACT,
        Topic.REGIME_OUTLOOK,
    ),
    publishes=(Topic.DEBATE_OUTPUT,),
    tools=(),
    timeout_key="debate",
    failure_mode=FailureMode.SKIP,
    failure_behaviour=(
        "Boss decides without debate output and records that it was missing; conviction is capped by config."
    ),
    performance_metrics=("debate_side_correctness", "rounds_used"),
)
