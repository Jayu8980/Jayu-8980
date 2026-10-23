"""Scoring helpers shared by the evaluators."""

from __future__ import annotations

from collections.abc import Sequence

import numpy as np

from aitrader.tools.stats import brier_multiclass, information_coefficient


def ic(predictions: Sequence[float], outcomes: Sequence[float]) -> float | None:
    if len(predictions) < 10:
        return None
    return information_coefficient(predictions=list(predictions), outcomes=list(outcomes)).value


def hit_rate(
    predictions: Sequence[float], outcomes: Sequence[float], threshold: float = 0.2
) -> tuple[float | None, int]:
    pairs = [(p, o) for p, o in zip(predictions, outcomes, strict=True) if abs(p) >= threshold and o != 0]
    if not pairs:
        return None, 0
    return float(np.mean([np.sign(p) == np.sign(o) for p, o in pairs])), len(pairs)


def multiclass_brier(probabilities: Sequence[Sequence[float]], outcomes: Sequence[int]) -> float | None:
    if not probabilities:
        return None
    return brier_multiclass(probabilities=[list(p) for p in probabilities], outcomes=list(outcomes)).value
