"""The bus envelope, topic registry and publishing authority.

Every bus message is an Envelope. The envelope enforces:
- the payload type matches the topic (no free-text or mistyped messages),
- only the authorised agent(s) may publish on each topic (authority hierarchy),
- point-in-time ordering: event_time <= available_at <= produced_at,
- alerts always travel as priority interrupts.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Union
from uuid import UUID, uuid4

from pydantic import Field, model_validator

from aitrader.schemas.base import SCHEMA_VERSION, AgentId, Priority, StrictModel, UtcDatetime
from aitrader.schemas.debate import DebateOutput
from aitrader.schemas.decision import BossDecision
from aitrader.schemas.feedback import AgentHeartbeat, AgentScore, TcaResult
from aitrader.schemas.gates import LiquidityPlan, RRVerdict
from aitrader.schemas.market import Bar, CorporateAction, DataQualityAlert
from aitrader.schemas.news import EventRiskFlag, NewsEvent, NewsImpact
from aitrader.schemas.orders import Fill, OrderInstruction, OrderUpdate
from aitrader.schemas.regime import RegimeOutlook
from aitrader.schemas.risk import RiskAlert, RiskApproval
from aitrader.schemas.signals import ChartSignal, SmartMoneySignal


class Topic(StrEnum):
    DATA_BARS = "data.bars"
    DATA_CORPORATE_ACTIONS = "data.corporate_actions"
    DATA_QUALITY = "data.quality"
    SIGNALS_CHART = "signals.chart"
    SIGNALS_SMARTMONEY = "signals.smartmoney"
    NEWS_EVENTS = "news.events"
    NEWS_RISK_FLAGS = "news.risk_flags"
    NEWS_IMPACT = "news.impact"
    REGIME_OUTLOOK = "regime.outlook"
    DEBATE_OUTPUT = "debate.output"
    BOSS_DECISION = "boss.decision"
    LIQUIDITY_PLAN = "liquidity.plan"
    RR_VERDICT = "rr.verdict"
    RISK_APPROVAL = "risk.approval"
    RISK_ALERTS = "risk.alerts"
    ORDERS_INSTRUCTIONS = "orders.instructions"
    ORDERS_UPDATES = "orders.updates"
    ORDERS_FILLS = "orders.fills"
    TCA_RESULTS = "tca.results"
    FEEDBACK_SCORES = "feedback.scores"
    SYSTEM_HEARTBEAT = "system.heartbeat"


Payload = Annotated[
    Union[
        Bar,
        CorporateAction,
        DataQualityAlert,
        ChartSignal,
        SmartMoneySignal,
        NewsEvent,
        EventRiskFlag,
        NewsImpact,
        RegimeOutlook,
        DebateOutput,
        BossDecision,
        LiquidityPlan,
        RRVerdict,
        RiskApproval,
        RiskAlert,
        OrderInstruction,
        OrderUpdate,
        Fill,
        TcaResult,
        AgentScore,
        AgentHeartbeat,
    ],
    Field(discriminator="kind"),
]

TOPIC_PAYLOAD: dict[Topic, type[StrictModel]] = {
    Topic.DATA_BARS: Bar,
    Topic.DATA_CORPORATE_ACTIONS: CorporateAction,
    Topic.DATA_QUALITY: DataQualityAlert,
    Topic.SIGNALS_CHART: ChartSignal,
    Topic.SIGNALS_SMARTMONEY: SmartMoneySignal,
    Topic.NEWS_EVENTS: NewsEvent,
    Topic.NEWS_RISK_FLAGS: EventRiskFlag,
    Topic.NEWS_IMPACT: NewsImpact,
    Topic.REGIME_OUTLOOK: RegimeOutlook,
    Topic.DEBATE_OUTPUT: DebateOutput,
    Topic.BOSS_DECISION: BossDecision,
    Topic.LIQUIDITY_PLAN: LiquidityPlan,
    Topic.RR_VERDICT: RRVerdict,
    Topic.RISK_APPROVAL: RiskApproval,
    Topic.RISK_ALERTS: RiskAlert,
    Topic.ORDERS_INSTRUCTIONS: OrderInstruction,
    Topic.ORDERS_UPDATES: OrderUpdate,
    Topic.ORDERS_FILLS: Fill,
    Topic.TCA_RESULTS: TcaResult,
    Topic.FEEDBACK_SCORES: AgentScore,
    Topic.SYSTEM_HEARTBEAT: AgentHeartbeat,
}

# Authority hierarchy: analysts advise -> boss decides -> gates check -> risk vetoes ->
# execution acts. Nobody else can publish on these topics.
TOPIC_PUBLISHERS: dict[Topic, frozenset[AgentId]] = {
    Topic.DATA_BARS: frozenset({AgentId.DATA}),
    Topic.DATA_CORPORATE_ACTIONS: frozenset({AgentId.DATA}),
    Topic.DATA_QUALITY: frozenset({AgentId.DATA}),
    Topic.SIGNALS_CHART: frozenset({AgentId.CHART}),
    Topic.SIGNALS_SMARTMONEY: frozenset({AgentId.SMART_MONEY}),
    Topic.NEWS_EVENTS: frozenset({AgentId.NEWS}),
    Topic.NEWS_RISK_FLAGS: frozenset({AgentId.NEWS, AgentId.TREND}),
    Topic.NEWS_IMPACT: frozenset({AgentId.NEWS_IMPACT}),
    Topic.REGIME_OUTLOOK: frozenset({AgentId.TREND}),
    Topic.DEBATE_OUTPUT: frozenset({AgentId.DEBATE}),
    Topic.BOSS_DECISION: frozenset({AgentId.BOSS, AgentId.BOSS_FALLBACK}),
    Topic.LIQUIDITY_PLAN: frozenset({AgentId.LIQUIDITY}),
    Topic.RR_VERDICT: frozenset({AgentId.RISK_REWARD}),
    Topic.RISK_APPROVAL: frozenset({AgentId.RISK}),
    Topic.RISK_ALERTS: frozenset(
        {AgentId.RISK, AgentId.DATA, AgentId.EXECUTION, AgentId.ORCHESTRATOR, AgentId.HUMAN}
    ),
    Topic.ORDERS_INSTRUCTIONS: frozenset({AgentId.RISK}),
    Topic.ORDERS_UPDATES: frozenset({AgentId.EXECUTION}),
    Topic.ORDERS_FILLS: frozenset({AgentId.EXECUTION}),
    Topic.TCA_RESULTS: frozenset({AgentId.TCA}),
    Topic.FEEDBACK_SCORES: frozenset({AgentId.ATTRIBUTION}),
    Topic.SYSTEM_HEARTBEAT: frozenset({AgentId.ORCHESTRATOR}),
}

INTERRUPT_TOPICS = frozenset({Topic.RISK_ALERTS})


class Envelope(StrictModel):
    message_id: UUID = Field(default_factory=uuid4)
    schema_version: str = SCHEMA_VERSION
    topic: Topic
    agent_id: AgentId
    cycle_id: UUID | None = None  # decision cycle this message belongs to
    causation_ids: tuple[UUID, ...] = ()  # messages this one was derived from
    event_time: UtcDatetime  # when the fact happened / was published at the source
    available_at: UtcDatetime  # earliest time the system could have known it (PIT key)
    produced_at: UtcDatetime  # when this message was produced (sim time in backtests)
    priority: Priority = Priority.NORMAL
    payload: Payload

    @model_validator(mode="after")
    def _enforce_contract(self) -> Envelope:
        expected = TOPIC_PAYLOAD[self.topic]
        if not isinstance(self.payload, expected):
            raise ValueError(f"topic {self.topic} carries {expected.__name__}")
        if self.agent_id not in TOPIC_PUBLISHERS[self.topic]:
            raise ValueError(f"agent {self.agent_id} may not publish on {self.topic}")
        if not (self.event_time <= self.available_at <= self.produced_at):
            raise ValueError("timestamps must satisfy event_time <= available_at <= produced_at")
        if self.topic in INTERRUPT_TOPICS and self.priority is not Priority.INTERRUPT:
            raise ValueError(f"{self.topic} messages must be priority interrupts")
        if self.schema_version.split(".")[0] != SCHEMA_VERSION.split(".")[0]:
            raise ValueError(f"incompatible schema major version {self.schema_version}")
        if self.message_id in self.causation_ids:
            raise ValueError("a message cannot cause itself")
        return self
