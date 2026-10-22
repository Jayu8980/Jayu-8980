"""Boss agent (CIO)."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.BOSS,
    kind=AgentKind.LLM,
    responsibility=(
        "Weigh analyst signals by tracked credibility in the current regime, check cross-source agreement, identify the critical disagreement, and choose BUY/SELL/HOLD/REDUCE/HEDGE/WAIT with a memo: size as a risk-budget fraction, conviction, reasons, main risk and invalidation. Cannot override hard limits."
    ),
    consumes=(
        Topic.SIGNALS_CHART,
        Topic.SIGNALS_SMARTMONEY,
        Topic.NEWS_EVENTS,
        Topic.NEWS_IMPACT,
        Topic.REGIME_OUTLOOK,
        Topic.NEWS_RISK_FLAGS,
        Topic.DEBATE_OUTPUT,
        Topic.FEEDBACK_SCORES,
        Topic.LIQUIDITY_PLAN,
        Topic.RR_VERDICT,
        Topic.RISK_APPROVAL,
    ),
    publishes=(Topic.BOSS_DECISION,),
    tools=("attribution.credibility_weights", "attribution.weighted_consensus"),
    timeout_key="boss",
    failure_mode=FailureMode.FALLBACK,
    failure_behaviour=(
        "The deterministic fallback rule set (credibility-weighted consensus with conservative thresholds) publishes as boss_fallback; fallback decisions never exceed half the normal risk budget."
    ),
    performance_metrics=("decision_pnl_vs_weighted_consensus", "discretion_budget", "wait_rate"),
)
