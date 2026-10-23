from datetime import date

import numpy as np
import pytest

from aitrader.agents.trend.agent import TrendAgent
from aitrader.backtest.strategies import equity
from aitrader.evaluation.calibration import calibrate_trend
from aitrader.evaluation.evaluators import evaluate_chart, evaluate_news, evaluate_trend, realised_scenario
from aitrader.evaluation.outcomes import r_outcome
from aitrader.storage import PitStore
from tests.analyst_helpers import market, regime_index, write_closes
from tests.backtest_helpers import bar_record
from tests.data_helpers import NIFTY


def test_r_outcome_assumes_stop_first() -> None:
    def bars(*ranges: tuple[float, float, float]) -> list:  # type: ignore[type-arg]
        from aitrader.schemas.market import Bar

        return [
            Bar.model_validate(bar_record(NIFTY, date(2026, 1, 5 + i), c, h, lo, c).payload)
            for i, (lo, h, c) in enumerate(ranges)
        ]

    path = bars((99, 101, 100), (98, 103, 102), (90, 120, 100))
    assert r_outcome(path, 1, 100, 95, 102.5) == (0.5, "target")
    assert r_outcome(bars((99, 101, 100), (90, 120, 100)), 1, 100, 95, 110) == (-1.0, "stop")
    open_trade = r_outcome(bars((99, 101, 100), (99, 101, 101)), 1, 100, 95, 110)
    assert open_trade is not None and open_trade[0] == pytest.approx(0.2) and open_trade[1] == "open"
    assert (
        realised_scenario(0.05, 0.03) == 0
        and realised_scenario(-0.05, 0.03) == 2
        and realised_scenario(0.0, 0.03) == 1
    )


def test_chart_evaluation_detects_skill_on_persistent_trends() -> None:
    store = PitStore()
    days = regime_index(store, 480)
    symbols = []
    rng = np.random.default_rng(11)
    for i, drift in enumerate((0.003, 0.0025, -0.003, -0.0025)):
        inst = equity(f"T{i}")
        closes = (100 + 10 * i) * np.exp(np.cumsum(rng.normal(drift, 0.012, 480)))
        write_closes(store, inst, days, closes.tolist())
        symbols.append(inst)
    report = evaluate_chart(market(store), symbols, days[300:], horizon=10, step=10)
    assert report.n > 20
    assert report.metrics["ic"] is not None and report.metrics["ic"] > 0.3
    assert report.metrics["hit_rate"] is not None and report.metrics["hit_rate"] > 0.6


def test_trend_calibrated_out_of_sample_beats_uniform() -> None:
    """Fit temperature/shrinkage on the first half, evaluate on the unseen second half."""
    store = PitStore()
    days = regime_index(
        store,
        1000,
        seed=5,
        drifts=((250, 0.002, 0.007), (250, -0.002, 0.012), (250, 0.002, 0.007), (250, -0.002, 0.012)),
    )
    m = market(store)
    fit = calibrate_trend(m, TrendAgent(m), days[230:600], step=7)
    assert fit.n >= 20 and fit.temperature >= 1.0
    report = evaluate_trend(
        m, TrendAgent(m, temperature=fit.temperature, shrink=fit.shrink), days[600:], step=7
    )
    assert report.metrics["brier"] is not None and report.baselines["brier_uniform"] is not None
    assert report.metrics["brier"] < report.baselines["brier_uniform"]


def test_uncalibrated_scores_are_overconfident() -> None:
    """Documents why calibration exists: raw softmax of the rule is worse than uniform."""
    store = PitStore()
    days = regime_index(store, 700, seed=5, drifts=((300, 0.002, 0.007), (400, -0.002, 0.012)))
    m = market(store)
    raw = evaluate_trend(m, TrendAgent(m, temperature=1.0, shrink=0.0), days[230:], step=10)
    default = evaluate_trend(m, TrendAgent(m), days[230:], step=10)
    assert raw.metrics["brier"] is not None and default.metrics["brier"] is not None
    assert default.metrics["brier"] < raw.metrics["brier"]


def test_news_evaluation_scores_sentiment() -> None:
    rng = np.random.default_rng(0)
    sentiment = rng.uniform(-1, 1, 40)
    events = [(float(s), 0.5, float(s * 0.02 + rng.normal(0, 0.005))) for s in sentiment]
    report = evaluate_news(events, date(2026, 1, 1), date(2026, 6, 30))
    assert report is not None and report.metrics["sentiment_ic"] and report.metrics["sentiment_ic"] > 0.7
    assert evaluate_news([], date(2026, 1, 1), date(2026, 6, 30)) is None
