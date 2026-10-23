"""Calibration of the trend agent's scenario probabilities.

The scenario rule's raw scores are not probabilities. ``calibrate_trend`` fits a softmax
temperature and a shrinkage toward the uniform distribution by minimising the multiclass
Brier score on a training window only. Features are computed once per decision, so the grid
search is cheap. Evaluate the chosen values on a later window (walk-forward); never on the
window they were fitted on.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, time

from aitrader.agents.data.agent import MarketData, index_instrument
from aitrader.agents.data.calendar import ist_datetime
from aitrader.agents.trend.agent import SCENARIOS, TrendAgent, scenario_scores, softmax
from aitrader.evaluation.evaluators import realised_scenario
from aitrader.evaluation.outcomes import forward_return
from aitrader.evaluation.scoring import multiclass_brier

TEMPERATURES = (1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0)
SHRINKS = (0.0, 0.1, 0.2, 0.3, 0.4, 0.5)


@dataclass(frozen=True)
class TrendCalibration:
    temperature: float
    shrink: float
    brier: float
    n: int


def calibrate_trend(
    market: MarketData,
    agent: TrendAgent,
    sessions: Sequence[date],
    *,
    horizon: int = 20,
    band: float = 0.03,
    step: int = 5,
) -> TrendCalibration:
    data_as_of = ist_datetime(sessions[-1], time(23, 59))
    index = index_instrument("NIFTY_50")
    samples: list[tuple[dict[str, float], int]] = []
    for day in list(sessions[: len(sessions) - horizon])[::step]:
        as_of = ist_datetime(day, time(20, 0))
        features = agent.features(as_of)
        fwd = forward_return(market, index, as_of, horizon, data_as_of)
        if features is not None and fwd is not None:
            samples.append((scenario_scores(features), realised_scenario(fwd, band)))
    if len(samples) < 20:
        raise ValueError(f"only {len(samples)} usable decisions; need at least 20 to calibrate")
    best: TrendCalibration | None = None
    outcomes = [o for _, o in samples]
    for temperature in TEMPERATURES:
        for shrink in SHRINKS:
            probs = [[softmax(s, temperature, shrink)[k] for k in SCENARIOS] for s, _ in samples]
            brier = multiclass_brier(probs, outcomes)
            if brier is not None and (best is None or brier < best.brier):
                best = TrendCalibration(temperature, shrink, brier, len(samples))
    assert best is not None
    return best
