import asyncio
from datetime import timedelta

import pytest

from aitrader.agents.data.agent import index_instrument
from aitrader.agents.publishing import publish
from aitrader.agents.trend.agent import MacroEvent, TrendAgent, TrendFeatures, scenario_scores, softmax
from aitrader.audit import InMemoryAuditLog
from aitrader.bus import InMemoryBus
from aitrader.schemas.base import AgentId, Timeframe
from aitrader.schemas.envelope import Topic
from aitrader.schemas.news import EventType
from aitrader.schemas.regime import RegimeLabel
from aitrader.storage import PitStore
from tests.analyst_helpers import market, regime_index, write_closes
from tests.data_helpers import NIFTY, eod


def features(**kw: object) -> TrendFeatures:
    base: dict[str, object] = {
        "index_return_20": 0.0,
        "above_50dma": True,
        "above_200dma": True,
        "dma50_slope": 0.0,
        "high_vol_probability": 0.1,
        "pct_above_50dma": 0.5,
        "pct_above_200dma": 0.5,
        "new_high_minus_low": 0.0,
        "vix_percentile": 0.5,
    }
    base.update(kw)
    return TrendFeatures.model_validate(base)


def test_scenario_rule_moves_the_right_way() -> None:
    bull = softmax(scenario_scores(features(index_return_20=0.05, dma50_slope=0.02, pct_above_50dma=0.8)))
    bear = softmax(
        scenario_scores(
            features(
                index_return_20=-0.06,
                above_50dma=False,
                above_200dma=False,
                dma50_slope=-0.02,
                pct_above_50dma=0.2,
                high_vol_probability=0.9,
            )
        )
    )
    assert bull["uptrend"] > 0.5 > bear["uptrend"] and bear["correction"] > 0.5
    assert sum(bull.values()) == pytest.approx(1.0, abs=1e-9)


def test_uptrend_market_gives_bullish_outlook_with_rate_limited_exposure() -> None:
    store = PitStore()
    days = regime_index(store, 300, seed=1, drifts=((300, 0.0015, 0.006),))
    agent = TrendAgent(market(store), max_exposure_change=0.2)
    agent.previous_exposure = 0.3
    outlook, f = agent.outlook(eod(days[-1]))
    assert outlook is not None and f is not None
    assert outlook.regime is RegimeLabel.BULL_TREND
    probs = {s.name: s.probability for s in outlook.scenarios}
    assert probs["uptrend"] > probs["correction"]
    assert outlook.exposure_multiplier == pytest.approx(0.5)  # 0.3 + at most 0.2


def test_selloff_market_gives_bearish_outlook() -> None:
    store = PitStore()
    days = regime_index(store, 300, seed=2, drifts=((240, 0.0008, 0.007), (60, -0.004, 0.02)))
    outlook, _ = TrendAgent(market(store)).outlook(eod(days[-1]))
    assert outlook is not None and outlook.regime in (RegimeLabel.BEAR_TREND, RegimeLabel.HIGH_VOL_STRESS)
    assert {s.name: s.probability for s in outlook.scenarios}["correction"] > 0.4


def test_short_history_yields_no_outlook() -> None:
    store = PitStore()
    days = regime_index(store, 100)
    assert TrendAgent(market(store)).outlook(eod(days[-1])) == (None, None)


def test_sector_tilts_and_macro_flags() -> None:
    store = PitStore()
    days = regime_index(store, 300, seed=4)
    nifty = [float(b.close) for b in market(store).raw_bars(NIFTY, Timeframe.D1, eod(days[-1]))]
    write_closes(
        store, index_instrument("NIFTY_IT"), days, [c * (1 + 0.002 * i) for i, c in enumerate(nifty)]
    )
    write_closes(
        store, index_instrument("NIFTY_BANK"), days, [c * (1 - 0.002 * i) for i, c in enumerate(nifty)]
    )
    events = [
        MacroEvent(
            name="Policy decision",
            event_type=EventType.MONETARY_POLICY,
            date=days[-1] + timedelta(days=5),
            block_new_entries=True,
            exposure_multiplier=0.5,
        ),
        MacroEvent(name="Long past", event_type=EventType.MACRO_DATA, date=days[0], block_new_entries=False),
    ]
    agent = TrendAgent(market(store), events=events)
    outlook, _ = agent.outlook(eod(days[-1]))
    assert outlook is not None
    tilts = {t.sector: t.tilt for t in outlook.sector_tilts}
    assert tilts["NIFTY_IT"] > 0 > tilts["NIFTY_BANK"]
    [flag] = agent.event_flags(eod(days[-1]))
    assert flag.block_new_entries and flag.instrument is None and flag.reason == "Policy decision"
    bus = InMemoryBus(InMemoryAuditLog(lambda: eod(days[-1])))
    env = asyncio.run(
        publish(
            bus,
            topic=Topic.REGIME_OUTLOOK,
            agent_id=AgentId.TREND,
            payload=outlook,
            available_at=eod(days[-1]),
            produced_at=eod(days[-1]) + timedelta(minutes=5),
        )
    )
    assert env.topic is Topic.REGIME_OUTLOOK
