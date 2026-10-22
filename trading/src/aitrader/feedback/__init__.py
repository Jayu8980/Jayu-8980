"""Feedback layer: transaction cost analysis, per-agent attribution and calibration. Phase 5-7."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

TCA_SPEC = AgentSpec(
    agent_id=AgentId.TCA,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Compare realised fills against arrival price, VWAP and the liquidity agent's impact "
        "estimate; publish estimation errors used to recalibrate impact models."
    ),
    consumes=(Topic.ORDERS_FILLS, Topic.LIQUIDITY_PLAN, Topic.DATA_BARS),
    publishes=(Topic.TCA_RESULTS,),
    tools=("micro.tca", "micro.vwap_benchmark"),
    failure_mode=FailureMode.SKIP,
    failure_behaviour="Impact models keep their last calibration; results are backfilled later.",
    performance_metrics=("tca_backlog",),
)

ATTRIBUTION_SPEC = AgentSpec(
    agent_id=AgentId.ATTRIBUTION,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Score every agent's signals against realised outcomes by regime (hit rate, IC, Brier), "
        "shrink credibility toward a prior on small samples, and score the boss against the "
        "credibility-weighted consensus to set its discretion budget."
    ),
    consumes=(
        Topic.SIGNALS_CHART,
        Topic.SIGNALS_SMARTMONEY,
        Topic.NEWS_EVENTS,
        Topic.NEWS_IMPACT,
        Topic.REGIME_OUTLOOK,
        Topic.DEBATE_OUTPUT,
        Topic.BOSS_DECISION,
        Topic.ORDERS_FILLS,
        Topic.DATA_BARS,
    ),
    publishes=(Topic.FEEDBACK_SCORES,),
    tools=("stats.brier", "stats.information_coefficient", "attribution.shrink_credibility"),
    failure_mode=FailureMode.SKIP,
    failure_behaviour="Boss keeps the last published credibility scores, decayed toward the prior.",
    performance_metrics=("scores_published",),
)
