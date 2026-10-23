from datetime import timedelta

import numpy as np
import pytest

from aitrader.agents.news_impact.analyser import NewsImpactAnalyser
from aitrader.backtest.strategies import equity
from aitrader.schemas.base import EvidenceKind, EvidenceRef
from aitrader.schemas.news import EventType, NewsClaim, NewsEvent, ReactionWindow, SourceTier
from aitrader.schemas.regime import RegimeLabel
from aitrader.storage import PitStore
from tests.analyst_helpers import market, regime_index, write_closes
from tests.data_helpers import eod

AAA = equity("AAA")


def event(published, event_id: str = "e1", kind: EventType = EventType.EARNINGS) -> NewsEvent:  # type: ignore[no-untyped-def]
    return NewsEvent(
        event_id=event_id,
        event_type=kind,
        source_tier=SourceTier.EXCHANGE_FILING,
        first_published_at=published,
        instruments=(AAA,),
        sentiment=0.5,
        novelty=1.0,
        materiality=0.6,
        credibility=0.95,
        half_life=timedelta(days=3),
        claims=(
            NewsClaim(
                summary="x",
                evidence=EvidenceRef(
                    kind=EvidenceKind.DOCUMENT, ref_id="d", available_at=published, excerpt="quote text here"
                ),
            ),
        ),
    )


def setup(jump_at: int = 200, jump: float = 0.06) -> tuple[PitStore, list]:  # type: ignore[type-arg]
    store = PitStore()
    days = regime_index(store, 260, seed=3)
    from aitrader.agents.data.agent import index_instrument
    from aitrader.schemas.base import Timeframe

    nifty = [
        float(b.close)
        for b in market(store).raw_bars(index_instrument("NIFTY_50"), Timeframe.D1, eod(days[-1]))
    ]
    m = np.diff(np.log(nifty), prepend=np.log(nifty[0]))
    rng = np.random.default_rng(9)
    r = 1.2 * m + rng.normal(0, 0.004, len(m))
    r[jump_at] += jump
    write_closes(store, AAA, days, (100 * np.exp(np.cumsum(r))).tolist())
    return store, days


def test_event_study_measures_the_planted_reaction() -> None:
    store, days = setup()
    published = eod(days[199]) - timedelta(hours=3)  # 16:00 IST, after the close: reaction is on day 200
    analyser = NewsImpactAnalyser(market(store))
    impact = analyser.analyse(event(published), eod(days[210]))
    assert impact is not None
    cars = {a.window: a.abnormal_return for a in impact.abnormal_returns}
    assert cars[ReactionWindow.D1] == pytest.approx(0.06, abs=0.015)
    assert set(cars) == {ReactionWindow.D1, ReactionWindow.D5}
    assert impact.volatility_shock_z and impact.volatility_shock_z > 5


def test_windows_not_yet_closed_are_not_reported() -> None:
    store, days = setup()
    published = eod(days[199]) - timedelta(hours=3)
    impact = NewsImpactAnalyser(market(store)).analyse(event(published), eod(days[202]))
    assert impact is not None
    assert {a.window for a in impact.abnormal_returns} == {ReactionWindow.D1}


def test_no_history_means_no_study() -> None:
    store, days = setup()
    assert NewsImpactAnalyser(market(store)).analyse(event(eod(days[50])), eod(days[60])) is None


def test_reaction_library_and_overlaps() -> None:
    store, days = setup()
    analyser = NewsImpactAnalyser(market(store))
    published = eod(days[199]) - timedelta(hours=3)
    for i in range(6):
        e = event(published, f"past-{i}")
        impact = analyser.analyse(e, eod(days[210]))
        assert impact is not None
        analyser.record(e, impact, RegimeLabel.BULL_TREND, completed_at=eod(days[206]))
    later = analyser.analyse(
        event(published, "new"),
        eod(days[210]),
        RegimeLabel.BULL_TREND,
        other_events=[event(published + timedelta(days=2), "other")],
    )
    assert later is not None and later.library_sample_size == 6
    assert later.stress_scenario_id == "news:earnings:p05" and later.overlapping_event_ids == ("other",)
    assert analyser.library.stress_scenarios(eod(days[210]), RegimeLabel.BULL_TREND)
    # Reactions completed after as_of are invisible to the library.
    assert analyser.library.entry(EventType.EARNINGS, RegimeLabel.BULL_TREND, eod(days[205])) is None
