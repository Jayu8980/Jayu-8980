"""Multi-day chart analysis agent."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.CHART,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Compute market structure, multi-day support/resistance, gaps, volume profile, anchored VWAP, rule-coded patterns, relative strength and volatility regime numerically across monthly/weekly/daily (direction) and 1h/15m (entry timing only)."
    ),
    consumes=(Topic.DATA_BARS, Topic.REGIME_OUTLOOK),
    publishes=(Topic.SIGNALS_CHART,),
    tools=(
        "ta.market_structure",
        "ta.support_resistance",
        "ta.volume_profile",
        "ta.anchored_vwap",
        "ta.relative_strength",
        "ta.atr",
        "ta.timeframe_alignment",
    ),
    timeout_key="analyst",
    failure_mode=FailureMode.SKIP,
    failure_behaviour=(
        "Cycle proceeds without chart levels; with no structural stop the R:R gate cannot pass, so no new entries are possible for that instrument."
    ),
    performance_metrics=("hit_rate_by_setup", "realised_r_multiple_by_setup", "alignment_score_ic"),
)
