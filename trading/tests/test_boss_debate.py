from datetime import timedelta
from decimal import Decimal
from typing import Any
from uuid import uuid4

import pytest

from aitrader.agents.boss.agent import BossAgent, DiscretionTracker, allowed_actions
from aitrader.agents.boss.fallback import fallback_decision
from aitrader.agents.boss.inputs import BossInput
from aitrader.agents.debate.agent import DebateAgent
from aitrader.agents.risk.portfolio import Holding
from aitrader.audit import InMemoryAuditLog
from aitrader.llm import StructuredCaller
from aitrader.schemas.base import EvidenceKind, EvidenceRef
from aitrader.schemas.decision import Action
from aitrader.schemas.envelope import Topic
from aitrader.schemas.news import EventRiskFlag, EventType
from aitrader.schemas.signals import SetupType
from aitrader.tools.attribution import AgentSignal, credibility_weights, weighted_consensus
from tests.factories import EQUITY, T0, chart_signal, sample_payloads
from tests.llm_helpers import FakeClient, pinned_config


def inputs(score: float = 0.6, holding: int = 0, setup: bool = True, **kw: Any) -> BossInput:
    chart = chart_signal().model_copy(update={"score": score})
    if not setup:
        chart = chart.model_copy(
            update={
                "setup": SetupType.NONE,
                "entry_low": None,
                "entry_high": None,
                "structural_stop": None,
                "targets": (),
            }
        )
    regime = sample_payloads()[Topic.REGIME_OUTLOOK]
    return BossInput(
        instrument=EQUITY,
        as_of=T0,
        chart=chart,
        regime=regime,  # type: ignore[arg-type]
        holding=Holding(EQUITY, holding, 104.0) if holding else None,
        **kw,
    )


def test_attribution_tools() -> None:
    w = credibility_weights(credibility={"chart": 0.7, "news": 0.4}).weights
    assert sum(w.values()) == pytest.approx(1.0) and w["chart"] > w["news"] > 0
    c = weighted_consensus(
        signals=[
            AgentSignal(agent="chart", score=0.8, confidence=1.0),
            AgentSignal(agent="news", score=-0.5, confidence=1.0),
        ],
        weights={"chart": 0.8, "news": 0.2},
    )
    assert c.consensus == pytest.approx(0.54) and c.dissenters == ("news",) and 0.5 < c.agreement < 1


def test_fallback_buys_aligned_setups_and_waits_otherwise() -> None:
    buy = fallback_decision(inputs(0.7), uuid4())
    assert buy.action is Action.BUY and buy.used_fallback and 0 < buy.risk_budget_fraction <= 0.5
    assert buy.invalidation is not None and buy.invalidation.level == Decimal("98")
    assert fallback_decision(inputs(0.7, setup=False), uuid4()).action is Action.WAIT
    assert fallback_decision(inputs(-0.7), uuid4()).action is Action.WAIT  # never opens shorts
    flag = EventRiskFlag(
        flag_id="x",
        event_type=EventType.EARNINGS,
        instrument=EQUITY,
        window_start=T0,
        window_end=T0 + timedelta(days=2),
        block_new_entries=True,
        reason="results",
    )
    assert fallback_decision(inputs(0.7, flags=[flag]), uuid4()).action is Action.WAIT


def test_fallback_manages_holdings() -> None:
    assert fallback_decision(inputs(-0.9, holding=100, setup=False), uuid4()).action is Action.SELL
    assert fallback_decision(inputs(0.5, holding=100), uuid4()).action is Action.HOLD


BOSS_OK = {
    "action": "BUY",
    "conviction": 0.7,
    "risk_budget_fraction": 0.6,
    "key_reasons": ["aligned trend and breakout"],
    "main_risk": "results next week",
    "critical_disagreement": None,
    "invalidation_reference": "chart_stop",
}


def llm_boss(script: Any, cap: float = 1.0) -> BossAgent:
    caller = StructuredCaller(FakeClient(script), pinned_config(), InMemoryAuditLog(lambda: T0), lambda: T0)
    return BossAgent(caller, discretion=DiscretionTracker(cap=cap))


def test_llm_boss_is_validated_and_falls_back() -> None:
    accepted = llm_boss([BOSS_OK]).decide(inputs(0.2), uuid4(), uuid4())
    assert (
        accepted.action is Action.BUY and not accepted.used_fallback and accepted.risk_budget_fraction == 0.6
    )
    over_cap = llm_boss([BOSS_OK], cap=0.4).decide(inputs(0.7), uuid4(), uuid4())
    assert over_cap.used_fallback and over_cap.risk_budget_fraction <= 0.4
    not_allowed = llm_boss([{**BOSS_OK, "action": "SELL"}]).decide(inputs(0.7), uuid4(), uuid4())
    assert not_allowed.used_fallback
    no_stop = llm_boss([{**BOSS_OK, "invalidation_reference": "none"}]).decide(inputs(0.7), uuid4(), uuid4())
    assert no_stop.used_fallback
    broken = llm_boss(["not json"]).decide(inputs(0.7), uuid4(), uuid4())
    assert broken.used_fallback
    assert allowed_actions(inputs(holding=10)) == ["HOLD", "REDUCE", "SELL"]


def test_fallback_revision_waits() -> None:
    boss = BossAgent(None)
    first = boss.decide(inputs(0.7), uuid4(), uuid4())
    revised = boss.revise(inputs(0.7), first, "R:R gate: below_min_rr", uuid4())
    assert (
        revised.action is Action.WAIT
        and revised.revision_number == 1
        and revised.revision_of == first.decision_id
    )


def test_discretion_shrinks_after_persistent_underperformance() -> None:
    tracker = DiscretionTracker()
    for i in range(20):
        tracker.record(-0.01 + 0.001 * (i % 2), 0.0)
    assert tracker.cap < 1.0 and tracker.cap >= tracker.floor
    low = tracker.cap
    for i in range(30):
        tracker.record(0.01 + 0.001 * (i % 2), 0.0)
    assert tracker.cap > low


EVIDENCE = {
    "chart:1": (EvidenceRef(kind=EvidenceKind.MESSAGE, ref_id="1", available_at=T0), "chart breakout"),
    "smart:2": (EvidenceRef(kind=EvidenceKind.MESSAGE, ref_id="2", available_at=T0), "long build-up"),
}


def case(ids: list[str]) -> dict[str, Any]:
    return {
        "points": [{"claim": "trend up", "evidence_ids": ids}],
        "counterargument": {"claim": "crowded", "evidence_ids": ["smart:2"]},
        "confidence": 0.6,
    }


def debate(script: Any, rounds: int = 2) -> DebateAgent:
    caller = StructuredCaller(FakeClient(script), pinned_config(), InMemoryAuditLog(lambda: T0), lambda: T0)
    return DebateAgent(caller, max_rounds=rounds)


def test_debate_runs_capped_rounds_with_cited_evidence() -> None:
    out = debate([case(["chart:1"])] * 4).run(EQUITY, EVIDENCE, uuid4())
    assert out is not None and out.rounds_used == 2 and not out.terminated_early
    assert out.bull.points[0].evidence[0].ref_id == "1" and out.unresolved


def test_debate_drops_uncited_points_and_stops() -> None:
    assert debate([case(["made-up-id"])] * 4).run(EQUITY, EVIDENCE, uuid4()) is None
    partial = debate([case(["chart:1"]), case(["chart:1"]), case(["nope"]), case(["chart:1"])]).run(
        EQUITY, EVIDENCE, uuid4()
    )
    assert partial is not None and partial.rounds_used == 1 and partial.terminated_early
    assert DebateAgent(None).run(EQUITY, EVIDENCE, uuid4()) is None and not DebateAgent(None).available
