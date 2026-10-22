"""Every component that can publish on the bus, with its declared contract."""

from aitrader import execution, feedback, orchestrator
from aitrader.agents import (
    boss,
    chart,
    data,
    debate,
    liquidity,
    news,
    news_impact,
    risk,
    risk_reward,
    smart_money,
    trend,
)
from aitrader.agents.spec import AgentSpec
from aitrader.schemas.base import AgentId

ALL_SPECS: dict[AgentId, AgentSpec] = {
    spec.agent_id: spec
    for spec in (
        data.SPEC,
        chart.SPEC,
        news.SPEC,
        news_impact.SPEC,
        smart_money.SPEC,
        trend.SPEC,
        debate.SPEC,
        boss.SPEC,
        liquidity.SPEC,
        risk_reward.SPEC,
        risk.SPEC,
        execution.SPEC,
        orchestrator.SPEC,
        feedback.TCA_SPEC,
        feedback.ATTRIBUTION_SPEC,
    )
}
