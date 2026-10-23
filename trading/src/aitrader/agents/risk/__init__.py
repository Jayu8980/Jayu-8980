"""Risk agent (absolute veto)."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.RISK,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Enforce every hard limit (per-trade risk, position, gross/net, sector, correlation, leverage, drawdown, liquidity, event cuts, VaR/CVaR, stress); size the order deterministically; issue signed single-use approval tokens bound to the exact instruction; freeze trading on any interrupt."
    ),
    consumes=(
        Topic.BOSS_DECISION,
        Topic.LIQUIDITY_PLAN,
        Topic.RR_VERDICT,
        Topic.NEWS_RISK_FLAGS,
        Topic.NEWS_IMPACT,
        Topic.REGIME_OUTLOOK,
        Topic.RISK_ALERTS,
        Topic.ORDERS_FILLS,
        Topic.DATA_QUALITY,
        Topic.SYSTEM_HEARTBEAT,
    ),
    publishes=(Topic.RISK_APPROVAL, Topic.ORDERS_INSTRUCTIONS, Topic.RISK_ALERTS),
    tools=(
        "risk.var_historical",
        "risk.var_parametric",
        "risk.var_monte_carlo",
        "risk.stress_test",
        "risk.exposure_decomposition",
        "sizing.fixed_fractional",
        "sizing.vol_target",
    ),
    timeout_key="gate",
    failure_mode=FailureMode.FREEZE,
    failure_behaviour=(
        "If the risk agent is unavailable nothing can be approved, which by construction means no new orders; a heartbeat loss also raises a freeze alert."
    ),
    performance_metrics=("rejections_by_rule", "realised_vs_var_breaches", "limit_utilisation"),
)
