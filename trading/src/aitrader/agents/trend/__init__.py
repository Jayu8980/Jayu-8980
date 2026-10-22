"""Upcoming market trend analysis agent."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.TREND,
    kind=AgentKind.HYBRID,
    responsibility=(
        "Detect regime from breadth, rotation, macro/event calendar, flows, sentiment extremes and global linkages; publish calibrated scenario probabilities, a regime label broadcast to all agents, sector tilts, event-risk calendar and early warnings."
    ),
    consumes=(Topic.DATA_BARS, Topic.NEWS_EVENTS, Topic.SIGNALS_SMARTMONEY),
    publishes=(Topic.REGIME_OUTLOOK, Topic.NEWS_RISK_FLAGS),
    tools=(
        "stats.regime_hmm",
        "stats.change_point",
        "breadth.compute",
        "calendar.macro_events",
        "stats.brier",
    ),
    timeout_key="analyst",
    failure_mode=FailureMode.FALLBACK,
    failure_behaviour=(
        "Regime becomes UNKNOWN and the stricter missing-trend multiplier applies; exposure targets cannot rise until it recovers."
    ),
    performance_metrics=("scenario_brier_score", "regime_label_accuracy"),
)
