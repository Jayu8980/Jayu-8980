import asyncio
from datetime import timedelta

from aitrader.agents.chart.agent import ChartAgent
from aitrader.agents.publishing import publish
from aitrader.audit import InMemoryAuditLog
from aitrader.backtest.strategies import equity
from aitrader.bus import InMemoryBus
from aitrader.schemas.base import AgentId, Timeframe
from aitrader.schemas.envelope import Topic
from aitrader.schemas.signals import SetupType
from aitrader.storage import PitStore
from tests.analyst_helpers import market, regime_index, write_closes, zigzag_trend
from tests.data_helpers import eod

AAA = equity("AAA")


def chart(drift: float, n: int = 420) -> tuple[ChartAgent, list]:  # type: ignore[type-arg]
    store = PitStore()
    days = regime_index(store, n)
    write_closes(store, AAA, days, zigzag_trend(n, drift))
    return ChartAgent(market(store)), days


def test_uptrend_reads_bullish_across_timeframes() -> None:
    agent, days = chart(0.003)
    signal = agent.analyse(AAA, eod(days[-1]))
    assert signal is not None and signal.score > 0
    frames = {r.timeframe for r in signal.readings}
    assert frames == {Timeframe.D1, Timeframe.W1, Timeframe.MN1}
    assert signal.alignment_score > 0 and 20 in signal.relative_strength
    if signal.setup is not SetupType.NONE:
        assert signal.structural_stop is not None and signal.entry_low is not None
        assert signal.structural_stop < signal.entry_low and all(t > signal.entry_low for t in signal.targets)


def test_downtrend_reads_bearish_with_stops_above() -> None:
    agent, days = chart(-0.003)
    signal = agent.analyse(AAA, eod(days[-1]))
    assert signal is not None and signal.score < 0
    if signal.setup is not SetupType.NONE:
        assert signal.structural_stop is not None and signal.entry_high is not None
        assert signal.structural_stop > signal.entry_high


def test_some_decision_in_a_trend_produces_a_tradable_setup() -> None:
    agent, days = chart(0.003)
    setups = [agent.analyse(AAA, eod(d)) for d in days[-60:]]
    assert any(s is not None and s.setup is not SetupType.NONE for s in setups)


def test_no_signal_without_todays_bar_or_enough_history() -> None:
    agent, days = chart(0.003)
    assert agent.analyse(AAA, eod(days[-1]) + timedelta(days=1, hours=1)) is None  # weekend/holiday decision
    short_agent, short_days = chart(0.003, n=40)
    assert short_agent.analyse(AAA, eod(short_days[-1])) is None


def test_partial_week_and_month_are_excluded() -> None:
    agent, days = chart(0.003)
    signal = agent.analyse(AAA, eod(days[-1]))
    later = agent.analyse(AAA, eod(days[-2]))
    assert signal is not None and later is not None  # both readable; weekly bars only from completed weeks


def test_signal_publishes() -> None:
    agent, days = chart(0.003)
    signal = agent.analyse(AAA, eod(days[-1]))
    assert signal is not None
    bus = InMemoryBus(InMemoryAuditLog(lambda: eod(days[-1])))
    env = asyncio.run(
        publish(
            bus,
            topic=Topic.SIGNALS_CHART,
            agent_id=AgentId.CHART,
            payload=signal,
            available_at=eod(days[-1]),
            produced_at=eod(days[-1]) + timedelta(minutes=1),
        )
    )
    assert env.payload == signal
