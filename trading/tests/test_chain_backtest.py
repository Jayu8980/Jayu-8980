from uuid import uuid4

import pytest

from aitrader.agents.data.agent import MarketData
from aitrader.agents.risk.tokens import TokenError
from aitrader.backtest.chain import ChainStrategy
from aitrader.backtest.config import load_backtest_config
from aitrader.backtest.engine import BacktestEngine
from aitrader.backtest.strategies import equity
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.storage import PitStore
from tests.chain_helpers import SYMBOLS, decision_time, market_store, wire

INSTRUMENTS = [equity(s) for s in SYMBOLS]


def run(store: PitStore, days: list, freeze: bool = False):  # type: ignore[no-untyped-def, type-arg]
    cycle, signer, state, audit = wire(store)
    if freeze:
        state.set_frozen(uuid4(), decision_time(days[0]))
    strategy = ChainStrategy(cycle=cycle, signer=signer, state=state, instruments=INSTRUMENTS)
    engine = BacktestEngine(MarketData.for_backtest(store), load_backtest_config())
    return engine.run(strategy, days[260], days[-1]), strategy, audit


@pytest.fixture(scope="module")
def baseline():  # type: ignore[no-untyped-def]
    store, days = market_store()
    return store, days, run(store, days)


def test_agent_chain_trades_in_backtest_and_every_order_traces_back(baseline) -> None:  # type: ignore[no-untyped-def]
    _, _, (result, strategy, audit) = baseline
    order_fills = [f for f in result.fills if f.reason == "order"]
    assert order_fills, "the chain never traded"
    accepted = {e.instruction.instrument.key for e in strategy.log if e.accepted}
    assert {f.instrument_key for f in order_fills} <= accepted
    envelopes = [
        Envelope.model_validate_json(r.body_json) for r in audit.records() if r.record_type == "envelope"
    ]
    by_id = {e.message_id: e for e in envelopes}
    instructions = [e for e in envelopes if e.topic is Topic.ORDERS_INSTRUCTIONS]
    assert len(instructions) == len(strategy.log)
    for env in instructions:
        causes = [by_id[c] for c in env.causation_ids]
        approval = next(c for c in causes if c.topic is Topic.RISK_APPROVAL)
        decision = next(c for c in causes if c.topic is Topic.BOSS_DECISION)
        assert approval.payload.token == env.payload.approval  # type: ignore[union-attr]
        assert decision.payload.decision_id == env.payload.decision_id  # type: ignore[union-attr]
        assert any(by_id[c].topic is Topic.SIGNALS_CHART for c in decision.causation_ids)
    assert audit.verify_chain()
    assert result.summary.trades >= 1 or result.open_positions


def test_chain_backtest_is_reproducible(baseline) -> None:  # type: ignore[no-untyped-def]
    store, days, (first, _, _) = baseline
    second, _, _ = run(store, days)
    assert first.run_digest == second.run_digest


def test_risk_veto_cannot_be_bypassed_in_backtest() -> None:
    store, days = market_store()
    result, strategy, _ = run(store, days, freeze=True)
    assert not [f for f in result.fills if f.reason == "order"]
    assert all(not e.accepted for e in strategy.log) and not strategy.log


def test_replayed_instructions_are_refused(baseline) -> None:  # type: ignore[no-untyped-def]
    _, _, (_, strategy, _) = baseline
    executed = next(e for e in strategy.log if e.accepted)
    try:
        strategy.signer.verify(executed.instruction, strategy.cycles[0].as_of, strategy.state)
    except TokenError as exc:
        assert "already used" in str(exc)
    else:
        raise AssertionError("a replayed instruction was accepted")
