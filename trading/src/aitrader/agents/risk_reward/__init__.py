"""Risk-to-reward agent."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.RISK_REWARD,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Compute R:R after all costs, expected value, blended multi-target R:R, time-adjusted return and gap risk from chart levels, hit probabilities and liquidity costs; reject negative EV, sub-minimum R:R and stops inside volatility noise; track realised R-multiples."
    ),
    consumes=(
        Topic.BOSS_DECISION,
        Topic.SIGNALS_CHART,
        Topic.LIQUIDITY_PLAN,
        Topic.NEWS_RISK_FLAGS,
        Topic.ORDERS_FILLS,
    ),
    publishes=(Topic.RR_VERDICT,),
    tools=(
        "costs.india",
        "costs.round_trip",
        "derivs.barrier_hit_probability",
        "derivs.prob_above",
    ),
    timeout_key="gate",
    failure_mode=FailureMode.BLOCK_TRADE,
    failure_behaviour=("No verdict means the trade is dropped."),
    performance_metrics=("predicted_vs_realised_ev", "realised_r_multiple", "setup_win_rate_drift"),
)
