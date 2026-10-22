"""Valid sample payloads for every topic, used to test schemas and envelope contracts."""

from __future__ import annotations

import hashlib
import hmac
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any
from uuid import UUID, uuid4

from aitrader.schemas.base import (
    AgentId,
    EvidenceKind,
    EvidenceRef,
    Exchange,
    Horizon,
    Instrument,
    InstrumentType,
    OptionType,
    Priority,
    Segment,
    StrictModel,
    Timeframe,
)
from aitrader.schemas.canonical import canonical_json
from aitrader.schemas.debate import DebateCase, DebateOutput, DebatePoint, DebateSide
from aitrader.schemas.decision import Action, BossDecision, Invalidation, InvalidationKind
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import AgentHeartbeat, AgentScore, HealthStatus, TcaResult
from aitrader.schemas.gates import ExecAlgo, LiquidityPlan, LiquidityRegime, RRVerdict
from aitrader.schemas.market import (
    Bar,
    CorporateAction,
    CorporateActionType,
    DataQualityAlert,
    DataQualityIssue,
    Severity,
)
from aitrader.schemas.news import (
    EventRiskFlag,
    EventType,
    NewsClaim,
    NewsEvent,
    NewsImpact,
    SourceTier,
)
from aitrader.schemas.orders import (
    Fill,
    InstructionIntent,
    OrderInstruction,
    OrderState,
    OrderType,
    OrderUpdate,
    Product,
    Side,
    UnsignedInstruction,
)
from aitrader.schemas.regime import RegimeLabel, RegimeOutlook, Scenario
from aitrader.schemas.risk import (
    AlertType,
    ApprovalToken,
    RiskAlert,
    RiskApproval,
    RiskRule,
    RuleResult,
)
from aitrader.schemas.signals import (
    ChartSignal,
    SetupType,
    SmartMoneySignal,
    StructureState,
    TimeframeReading,
    VolatilityRegime,
)

T0 = datetime(2026, 10, 21, 10, 0, tzinfo=UTC)
DECISION_ID = UUID("00000000-0000-4000-8000-000000000001")
TEST_KEY = b"test-signing-key-not-for-production"

EQUITY = Instrument(
    exchange=Exchange.NSE, segment=Segment.CASH, symbol="RELIANCE", instrument_type=InstrumentType.EQUITY
)
OPTION = Instrument(
    exchange=Exchange.NSE,
    segment=Segment.FNO,
    symbol="NIFTY",
    instrument_type=InstrumentType.OPTION,
    expiry=date(2026, 10, 27),
    strike=Decimal("25000"),
    option_type=OptionType.CALL,
    lot_size=75,
)


def evidence(kind: EvidenceKind = EvidenceKind.TOOL_CALL) -> EvidenceRef:
    excerpt = "Board approved a 1:1 bonus issue." if kind is EvidenceKind.DOCUMENT else None
    return EvidenceRef(kind=kind, ref_id="ref-1", available_at=T0, excerpt=excerpt)


def bar() -> Bar:
    return Bar(
        instrument=EQUITY,
        timeframe=Timeframe.D1,
        bar_start=T0 - timedelta(days=1),
        bar_end=T0,
        open=Decimal("100"),
        high=Decimal("105"),
        low=Decimal("99"),
        close=Decimal("104"),
        volume=1_000_000,
    )


def chart_signal() -> ChartSignal:
    return ChartSignal(
        instrument=EQUITY,
        score=0.6,
        confidence=0.7,
        horizon=Horizon.SWING,
        valid_until=T0 + timedelta(days=3),
        evidence=(evidence(),),
        readings=(
            TimeframeReading(
                timeframe=Timeframe.W1, structure=StructureState.UPTREND, trend_score=0.7, confidence=0.8
            ),
            TimeframeReading(
                timeframe=Timeframe.D1, structure=StructureState.BREAKOUT, trend_score=0.6, confidence=0.6
            ),
        ),
        alignment_score=0.8,
        setup=SetupType.BREAKOUT,
        volatility_regime=VolatilityRegime.NORMAL,
        atr=2.5,
        entry_low=Decimal("103"),
        entry_high=Decimal("105"),
        structural_stop=Decimal("98"),
        targets=(Decimal("115"), Decimal("122")),
    )


def boss_decision(**overrides: Any) -> BossDecision:
    fields: dict[str, Any] = {
        "decision_id": DECISION_ID,
        "action": Action.BUY,
        "instrument": EQUITY,
        "horizon": Horizon.SWING,
        "conviction": 0.65,
        "risk_budget_fraction": 0.5,
        "key_reasons": ("Weekly and daily structure aligned up",),
        "main_risk": "Results in 9 days",
        "invalidation": Invalidation(
            condition=InvalidationKind.PRICE_CLOSE_BELOW, level=Decimal("98"), description="Close below base"
        ),
    }
    fields.update(overrides)
    return BossDecision(**fields)


def unsigned_instruction(**overrides: Any) -> UnsignedInstruction:
    fields: dict[str, Any] = {
        "instruction_id": UUID("00000000-0000-4000-8000-000000000002"),
        "idempotency_key": "cycle-1:decision-1:leg-0",
        "decision_id": DECISION_ID,
        "action": Action.BUY,
        "intent": InstructionIntent.OPEN,
        "instrument": EQUITY,
        "side": Side.BUY,
        "quantity": 100,
        "order_type": OrderType.LIMIT,
        "limit_price": Decimal("104.50"),
        "product": Product.CNC,
        "algo": ExecAlgo.TWAP,
        "participation_rate": 0.05,
        "horizon_minutes": 30,
        "protective_stop": Decimal("98"),
        "targets": (Decimal("115"),),
        "max_price_deviation_bps": 50,
        "valid_until": T0 + timedelta(minutes=5),
    }
    fields.update(overrides)
    return UnsignedInstruction(**fields)


def sign_token(instruction: UnsignedInstruction, key: bytes = TEST_KEY) -> ApprovalToken:
    body = {
        "token_id": str(uuid4()),
        "decision_id": str(instruction.decision_id),
        "instruction_digest": instruction.digest(),
        "issued_at": T0.isoformat(),
        "expires_at": (T0 + timedelta(minutes=5)).isoformat(),
        "key_id": "k1",
    }
    signature = hmac.new(key, canonical_json(body).encode(), hashlib.sha256).hexdigest()
    return ApprovalToken.model_validate({**body, "signature": signature})


def order_instruction(**overrides: Any) -> OrderInstruction:
    unsigned = unsigned_instruction(**overrides)
    return OrderInstruction(**unsigned.model_dump(), approval=sign_token(unsigned))


def risk_approval() -> RiskApproval:
    token = sign_token(unsigned_instruction())
    return RiskApproval(
        decision_id=DECISION_ID,
        instrument=EQUITY,
        approved=True,
        approved_quantity=100,
        results=(RuleResult(rule=RiskRule.MAX_RISK_PER_TRADE, passed=True, observed=0.006, limit=0.01),),
        limit_multiplier=1.0,
        token=token,
    )


def news_event() -> NewsEvent:
    return NewsEvent(
        event_id="evt-1",
        event_type=EventType.BUYBACK_DIVIDEND,
        source_tier=SourceTier.EXCHANGE_FILING,
        first_published_at=T0,
        instruments=(EQUITY,),
        sentiment=0.4,
        novelty=0.8,
        materiality=0.5,
        credibility=0.95,
        half_life=timedelta(days=2),
        claims=(NewsClaim(summary="1:1 bonus approved", evidence=evidence(EvidenceKind.DOCUMENT)),),
    )


def debate_case(side: DebateSide) -> DebateCase:
    point = DebatePoint(claim="Breakout on 2x volume", evidence=(evidence(EvidenceKind.MESSAGE),))
    return DebateCase(side=side, points=(point,), strongest_counterargument=point, confidence=0.6)


def sample_payloads() -> dict[Topic, StrictModel]:
    return {
        Topic.DATA_BARS: bar(),
        Topic.DATA_CORPORATE_ACTIONS: CorporateAction(
            instrument=EQUITY,
            action_type=CorporateActionType.BONUS,
            announced_at=T0,
            ex_date=date(2026, 11, 10),
            ratio_numerator=Decimal(1),
            ratio_denominator=Decimal(1),
        ),
        Topic.DATA_QUALITY: DataQualityAlert(
            issue=DataQualityIssue.GAP,
            severity=Severity.WARNING,
            source="nse_bhavcopy",
            detail="Missing 2026-10-20 bar",
            detected_at=T0,
        ),
        Topic.SIGNALS_CHART: chart_signal(),
        Topic.SIGNALS_SMARTMONEY: SmartMoneySignal(
            instrument=EQUITY,
            score=0.3,
            confidence=0.5,
            horizon=Horizon.SWING,
            valid_until=T0 + timedelta(days=5),
            evidence=(evidence(EvidenceKind.DATASET),),
            accumulation_score=0.4,
            crowding=0.2,
            flow_persistence_days=4,
            divergence_alert=False,
        ),
        Topic.NEWS_EVENTS: news_event(),
        Topic.NEWS_RISK_FLAGS: EventRiskFlag(
            flag_id="flag-1",
            event_type=EventType.EARNINGS,
            instrument=EQUITY,
            window_start=T0 + timedelta(days=8),
            window_end=T0 + timedelta(days=10),
            block_new_entries=True,
            reason="Quarterly results",
        ),
        Topic.NEWS_IMPACT: NewsImpact(event_id="evt-1"),
        Topic.REGIME_OUTLOOK: RegimeOutlook(
            regime=RegimeLabel.BULL_TREND,
            confidence=0.6,
            scenarios=(
                Scenario(name="uptrend", probability=0.55, evidence=(evidence(),)),
                Scenario(name="range", probability=0.30, evidence=(evidence(),)),
                Scenario(name="correction", probability=0.15, evidence=(evidence(),)),
            ),
            exposure_multiplier=0.8,
            forecast_horizon_end=T0 + timedelta(days=20),
        ),
        Topic.DEBATE_OUTPUT: DebateOutput(
            instrument=EQUITY,
            rounds_used=2,
            bull=debate_case(DebateSide.BULL),
            bear=debate_case(DebateSide.BEAR),
        ),
        Topic.BOSS_DECISION: boss_decision(),
        Topic.LIQUIDITY_PLAN: LiquidityPlan(
            decision_id=DECISION_ID,
            instrument=EQUITY,
            liquidity_score=0.9,
            regime=LiquidityRegime.NORMAL,
            adv_shares=5_000_000,
            spread_bps=2.0,
            max_quantity=250_000,
            algo=ExecAlgo.TWAP,
            participation_rate=0.05,
            horizon_minutes=30,
            est_impact_bps=3.0,
            days_to_liquidate=0.01,
        ),
        Topic.RR_VERDICT: RRVerdict(
            decision_id=DECISION_ID,
            instrument=EQUITY,
            setup=SetupType.BREAKOUT,
            passed=True,
            p_win=0.45,
            p_win_source="historical_setup",
            reward_per_unit=11.0,
            risk_per_unit=6.5,
            costs_per_unit=0.3,
            rr_after_costs=1.65,
            expected_value_per_unit=1.0,
            stop_distance_atr=2.6,
        ),
        Topic.RISK_APPROVAL: risk_approval(),
        Topic.RISK_ALERTS: RiskAlert(
            alert_id=uuid4(),
            alert_type=AlertType.KILL_SWITCH,
            severity=Severity.CRITICAL,
            freeze_new_orders=True,
            detail="Manual kill switch",
        ),
        Topic.ORDERS_INSTRUCTIONS: order_instruction(),
        Topic.ORDERS_UPDATES: OrderUpdate(
            instruction_id=uuid4(),
            child_order_id="c1",
            state=OrderState.PLACED,
            previous_state=OrderState.PENDING,
            filled_quantity=0,
        ),
        Topic.ORDERS_FILLS: Fill(
            instruction_id=uuid4(),
            child_order_id="c1",
            broker_trade_id="t1",
            instrument=EQUITY,
            side=Side.BUY,
            quantity=100,
            price=Decimal("104.40"),
            filled_at=T0,
            arrival_price=Decimal("104.30"),
            slippage_bps=9.6,
        ),
        Topic.TCA_RESULTS: TcaResult(
            instruction_id=uuid4(),
            instrument=EQUITY,
            filled_quantity=100,
            avg_price=104.4,
            arrival_price=104.3,
            realised_slippage_bps=9.6,
            estimated_impact_bps=3.0,
            estimation_error_bps=6.6,
        ),
        Topic.FEEDBACK_SCORES: AgentScore(
            agent_id=AgentId.CHART,
            regime=RegimeLabel.BULL_TREND,
            window_start=T0 - timedelta(days=90),
            window_end=T0,
            sample_size=40,
            hit_rate=0.52,
            credibility=0.55,
        ),
        Topic.SYSTEM_HEARTBEAT: AgentHeartbeat(agent_id=AgentId.CHART, status=HealthStatus.HEALTHY),
    }


DEFAULT_PUBLISHER: dict[Topic, AgentId] = {
    Topic.DATA_BARS: AgentId.DATA,
    Topic.DATA_CORPORATE_ACTIONS: AgentId.DATA,
    Topic.DATA_QUALITY: AgentId.DATA,
    Topic.SIGNALS_CHART: AgentId.CHART,
    Topic.SIGNALS_SMARTMONEY: AgentId.SMART_MONEY,
    Topic.NEWS_EVENTS: AgentId.NEWS,
    Topic.NEWS_RISK_FLAGS: AgentId.NEWS,
    Topic.NEWS_IMPACT: AgentId.NEWS_IMPACT,
    Topic.REGIME_OUTLOOK: AgentId.TREND,
    Topic.DEBATE_OUTPUT: AgentId.DEBATE,
    Topic.BOSS_DECISION: AgentId.BOSS,
    Topic.LIQUIDITY_PLAN: AgentId.LIQUIDITY,
    Topic.RR_VERDICT: AgentId.RISK_REWARD,
    Topic.RISK_APPROVAL: AgentId.RISK,
    Topic.RISK_ALERTS: AgentId.RISK,
    Topic.ORDERS_INSTRUCTIONS: AgentId.RISK,
    Topic.ORDERS_UPDATES: AgentId.EXECUTION,
    Topic.ORDERS_FILLS: AgentId.EXECUTION,
    Topic.TCA_RESULTS: AgentId.TCA,
    Topic.FEEDBACK_SCORES: AgentId.ATTRIBUTION,
    Topic.SYSTEM_HEARTBEAT: AgentId.ORCHESTRATOR,
}


def envelope(topic: Topic, **overrides: Any) -> Envelope:
    fields: dict[str, Any] = {
        "topic": topic,
        "agent_id": DEFAULT_PUBLISHER[topic],
        "event_time": T0,
        "available_at": T0,
        "produced_at": T0 + timedelta(seconds=1),
        "priority": Priority.INTERRUPT if topic is Topic.RISK_ALERTS else Priority.NORMAL,
        "payload": sample_payloads()[topic],
    }
    fields.update(overrides)
    return Envelope(**fields)
