"""Smart money engine."""

from aitrader.agents.spec import AgentKind, AgentSpec, FailureMode
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import Topic

SPEC = AgentSpec(
    agent_id=AgentId.SMART_MONEY,
    kind=AgentKind.DETERMINISTIC,
    responsibility=(
        "Track FII/DII flows, block/bulk deals, insider and promoter activity, participant-wise OI, OI build-up, PCR, max pain, unusual options activity and delivery %, each timestamped at true availability; score accumulation, crowding, persistence and divergence. SMC patterns are optional and flagged unvalidated."
    ),
    consumes=(Topic.DATA_BARS,),
    publishes=(Topic.SIGNALS_SMARTMONEY,),
    tools=(
        "flows.fii_dii",
        "flows.participant_oi",
        "derivs.pcr",
        "derivs.max_pain",
        "derivs.oi_buildup",
        "flows.delivery_pct",
    ),
    timeout_key="analyst",
    failure_mode=FailureMode.SKIP,
    failure_behaviour=("Cycle proceeds without positioning data; missing-agent multiplier applies."),
    performance_metrics=("accumulation_score_ic_5d", "divergence_alert_precision"),
)
