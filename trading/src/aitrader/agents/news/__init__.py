"""News analysis agent."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.NEWS,
    kind=AgentKind.HYBRID,
    responsibility=(
        "Deduplicate and cluster headlines into events, link entities, classify, score sentiment/novelty/materiality/credibility and assign a half-life. External text is untrusted: the LLM only extracts schema fields and every claim must quote its source."
    ),
    consumes=(),
    publishes=(Topic.NEWS_EVENTS, Topic.NEWS_RISK_FLAGS),
    tools=(),  # deterministic pipeline: agents/news/pipeline.py; LLM via llm.StructuredCaller
    timeout_key="analyst",
    failure_mode=FailureMode.SKIP,
    failure_behaviour=(
        "Without news, scheduled event-risk flags still come from the deterministic corporate calendar; limits tighten via the missing-agent multiplier."
    ),
    performance_metrics=("sentiment_ic_1d", "materiality_calibration_brier", "dropped_claim_rate"),
)
