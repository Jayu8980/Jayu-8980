"""News impact analyser."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.NEWS_IMPACT,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Run event studies (abnormal returns vs market/sector/beta over 5m, 1h, 1d, 5d), measure shocks, absorption and reversal, map contagion, maintain the regime-conditioned event-reaction library and supply event stress scenarios."
    ),
    consumes=(Topic.NEWS_EVENTS, Topic.DATA_BARS, Topic.REGIME_OUTLOOK),
    publishes=(Topic.NEWS_IMPACT,),
    tools=("stats.event_study", "stats.rolling_beta", "risk.stress_test"),
    timeout_key="analyst",
    failure_mode=FailureMode.SKIP,
    failure_behaviour=(
        "Risk agent falls back to generic historical stress scenarios and applies the missing-agent multiplier."
    ),
    performance_metrics=("predicted_vs_realised_abnormal_return_error", "library_coverage"),
)
