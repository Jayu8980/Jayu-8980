"""Liquidity agent."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.LIQUIDITY,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Measure depth, spread, resiliency, ADV and volume curves, impact (square-root, Almgren-Chriss), liquidity regime and concentration; output a liquidity score, a hard maximum quantity and an execution plan, plus portfolio time-to-liquidate."
    ),
    consumes=(Topic.BOSS_DECISION, Topic.DATA_BARS, Topic.TCA_RESULTS),
    publishes=(Topic.LIQUIDITY_PLAN,),
    tools=(
        "micro.liquidation",
        "micro.spread_depth",
        "micro.impact_sqrt",
        "micro.almgren_chriss",
        "micro.volume_curve",
        "derivs.freeze_qty",
    ),
    timeout_key="gate",
    failure_mode=FailureMode.BLOCK_TRADE,
    failure_behaviour=(
        "No plan means no maximum quantity, so the trade cannot reach the risk agent and is dropped."
    ),
    performance_metrics=("impact_estimation_error_bps", "max_qty_utilisation"),
)
