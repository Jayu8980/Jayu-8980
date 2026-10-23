import asyncio
import time
from typing import Any
from uuid import uuid4

from aitrader.agents.chart.agent import ChartAgent
from aitrader.backtest.strategies import equity
from aitrader.orchestrator.cycle import CycleTimeouts
from aitrader.schemas.decision import Action
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import AgentHeartbeat, HealthStatus
from aitrader.schemas.risk import RiskApproval
from tests.chain_helpers import SYMBOLS, decision_time, flat_portfolio, market_store, wire

INSTRUMENTS = [equity(s) for s in SYMBOLS]


def find_day_with_instruction(cycle: Any, days: list) -> tuple[Any, Any]:  # type: ignore[type-arg]
    for day in days[-120:]:
        result = asyncio.run(cycle.run(decision_time(day), INSTRUMENTS, flat_portfolio(decision_time(day))))
        if result.instructions:
            return day, result
    raise AssertionError("no instruction in 120 sessions of a trending market")


def test_full_cycle_produces_traceable_signed_instructions() -> None:
    store, days = market_store()
    cycle, signer, state, audit = wire(store)
    _, result = find_day_with_instruction(cycle, days)
    instruction = result.instructions[0]
    by_id = {e.message_id: e for e in result.envelopes}
    order_env = next(e for e in result.envelopes if e.topic is Topic.ORDERS_INSTRUCTIONS)
    chain_topics = {by_id[c].topic for c in order_env.causation_ids}
    assert {Topic.BOSS_DECISION, Topic.LIQUIDITY_PLAN, Topic.RR_VERDICT, Topic.RISK_APPROVAL} <= chain_topics
    decision_env = next(by_id[c] for c in order_env.causation_ids if by_id[c].topic is Topic.BOSS_DECISION)
    assert any(by_id[c].topic is Topic.SIGNALS_CHART for c in decision_env.causation_ids if c in by_id)
    approval_env = next(by_id[c] for c in order_env.causation_ids if by_id[c].topic is Topic.RISK_APPROVAL)
    assert (
        isinstance(approval_env.payload, RiskApproval) and approval_env.payload.token == instruction.approval
    )
    assert instruction.action is Action.BUY and instruction.protective_stop is not None
    assert audit.verify_chain()
    signer.verify(instruction, result.as_of, state)  # verifiable by execution
    beats = [e.payload for e in result.envelopes if isinstance(e.payload, AgentHeartbeat)]
    assert {h.agent_id.value for h in beats} >= {"chart", "trend", "boss"}


def test_cycles_are_reproducible() -> None:
    store, days = market_store()
    day = days[-1]

    def run() -> list[Envelope]:
        cycle, *_ = wire(store)
        return asyncio.run(
            cycle.run(decision_time(day), INSTRUMENTS, flat_portfolio(decision_time(day)))
        ).envelopes

    a, b = run(), run()

    def decision_path(envs: list[Envelope]) -> list[tuple[object, object]]:
        # Heartbeats carry measured latency (operational telemetry), so they legitimately differ.
        return [(e.message_id, e.payload) for e in envs if e.topic is not Topic.SYSTEM_HEARTBEAT]

    assert decision_path(a) == decision_path(b)


class Failing(ChartAgent):
    def analyse(self, instrument: Any, as_of: Any) -> Any:
        raise RuntimeError("chart feed exploded")


class Slow:
    def stock_signal(self, instrument: Any, as_of: Any) -> Any:
        time.sleep(0.5)
        return None


def test_failed_and_slow_agents_are_isolated_and_tighten_limits() -> None:
    store, days = market_store()
    cycle, _, state, _ = wire(
        store, chart=Failing.__new__(Failing), smart_money=Slow(), timeouts=CycleTimeouts(analyst=0.1)
    )
    result = asyncio.run(
        cycle.run(decision_time(days[-1]), INSTRUMENTS, flat_portfolio(decision_time(days[-1])))
    )
    assert (
        result.statuses[next(a for a in result.statuses if a.value == "chart")].status is HealthStatus.FAILED
    )
    assert (
        result.statuses[next(a for a in result.statuses if a.value == "smart_money")].status
        is HealthStatus.TIMED_OUT
    )
    assert result.limit_multiplier < 0.6 and state.limit_multiplier() == result.limit_multiplier
    assert not result.instructions  # no chart, no setup, no entries
    failed = [
        e.payload
        for e in result.envelopes
        if isinstance(e.payload, AgentHeartbeat) and e.payload.status is not HealthStatus.HEALTHY
    ]
    assert len(failed) >= 2  # the trend agent's regime fit can also exceed a 0.1 s budget


class ExplodingBoss:
    def decide(self, *args: Any) -> Any:
        raise RuntimeError("boss crashed")

    def revise(self, *args: Any) -> Any:
        raise AssertionError("not reached")


def test_boss_failure_falls_back_to_rules() -> None:
    store, days = market_store()
    cycle, *_ = wire(store, boss=ExplodingBoss())
    for day in days[-120:]:
        result = asyncio.run(cycle.run(decision_time(day), INSTRUMENTS, flat_portfolio(decision_time(day))))
        if result.decisions:
            assert all(d.used_fallback for d in result.decisions)
            assert any("deterministic fallback used" in n for n in result.notes)
            return
    raise AssertionError("no decision taken")


def test_freeze_vetoes_every_entry() -> None:
    store, days = market_store()
    cycle, _, state, _ = wire(store)
    state.set_frozen(uuid4(), decision_time(days[0]))
    instructions = []
    for day in days[-120:]:
        instructions += asyncio.run(
            cycle.run(decision_time(day), INSTRUMENTS, flat_portfolio(decision_time(day)))
        ).instructions
    assert instructions == []


def test_failed_gates_return_to_the_boss() -> None:
    from aitrader.agents.risk_reward.agent import SetupStats

    store, days = market_store()
    cycle, *_ = wire(store, stats=SetupStats())  # no measured edge: R:R rejects every entry
    for day in days[-120:]:
        result = asyncio.run(cycle.run(decision_time(day), INSTRUMENTS, flat_portfolio(decision_time(day))))
        if any(d.action is Action.BUY for d in result.decisions):
            assert not result.instructions
            assert any("R:R gate" in n for n in result.notes)
            revised = [d for d in result.decisions if d.revision_number == 1]
            assert revised and revised[0].action is Action.WAIT
            return
    raise AssertionError("boss never proposed a trade")
