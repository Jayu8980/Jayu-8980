"""Walk-forward validation: choose parameters on a training window only, evaluate them on
the following unseen window, roll forward, and judge the stitched out-of-sample record with
the deflated Sharpe ratio (which accounts for how many parameter sets were tried)."""

from __future__ import annotations

import itertools
import json
import math
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date

import numpy as np

from aitrader.backtest.engine import BacktestEngine, Strategy
from aitrader.backtest.models import PerformanceSummary
from aitrader.schemas.base import StrictModel
from aitrader.tools.validation import ReturnStats, SharpeTest, deflated_sharpe, return_stats

Params = Mapping[str, float | int | str | bool]


@dataclass(frozen=True)
class Fold:
    index: int
    train_start: date
    train_end: date
    test_start: date
    test_end: date


def make_folds(
    sessions: Sequence[date],
    train_sessions: int,
    test_sessions: int,
    *,
    step_sessions: int | None = None,
    anchored: bool = False,
    embargo_sessions: int = 0,
) -> list[Fold]:
    """Rolling (or anchored) folds. ``embargo_sessions`` are skipped between each training
    window and its test window so positions and signals from training cannot leak into it."""
    if train_sessions < 2 or test_sessions < 2 or embargo_sessions < 0:
        raise ValueError("windows must cover at least two sessions and embargo must be >= 0")
    step = step_sessions or test_sessions
    folds: list[Fold] = []
    start = 0
    while True:
        train_lo = 0 if anchored else start
        train_hi = start + train_sessions - 1
        test_lo = train_hi + 1 + embargo_sessions
        test_hi = test_lo + test_sessions - 1
        if test_hi >= len(sessions):
            break
        folds.append(
            Fold(len(folds), sessions[train_lo], sessions[train_hi], sessions[test_lo], sessions[test_hi])
        )
        start += step
    if not folds:
        raise ValueError("not enough sessions for a single fold")
    return folds


class FoldResult(StrictModel):
    index: int
    train_start: date
    train_end: date
    test_start: date
    test_end: date
    chosen_params: dict[str, float | int | str | bool]
    train_scores: tuple[tuple[str, float], ...]  # (params as JSON, objective)
    test_summary: PerformanceSummary
    test_run_digest: str


class WalkForwardResult(StrictModel):
    folds: tuple[FoldResult, ...]
    n_trials: int
    oos_equity: tuple[float, ...]  # stitched, starting at 1.0
    oos_stats: ReturnStats
    deflated_sharpe: SharpeTest | None
    mean_train_sharpe: float
    mean_test_sharpe: float


def walk_forward(
    engine: BacktestEngine,
    factory: Callable[[Params], Strategy],
    grid: Sequence[Params],
    folds: Sequence[Fold],
    *,
    objective: Callable[[PerformanceSummary], float] = lambda s: s.sharpe,
) -> WalkForwardResult:
    if not grid:
        raise ValueError("parameter grid is empty")
    results: list[FoldResult] = []
    oos_returns: list[float] = []
    trial_sharpes: list[list[float]] = []
    for fold in folds:
        scores: list[tuple[str, float]] = []
        per_period: list[float] = []
        for params in grid:
            try:
                summary = engine.run(factory(params), fold.train_start, fold.train_end).summary
                score = objective(summary)
                per_period.append(summary.sharpe / math.sqrt(252))
            except ValueError:
                score = float("-inf")
            scores.append((json.dumps(dict(params), sort_keys=True), score))
        trial_sharpes.append(per_period)
        best = max(range(len(grid)), key=lambda i: (scores[i][1], -i))  # ties: earliest in grid
        chosen = dict(grid[best])
        test = engine.run(factory(chosen), fold.test_start, fold.test_end)
        curve = [p.equity for p in test.equity_curve]
        oos_returns.extend(float(b / a - 1) for a, b in itertools.pairwise(curve))
        finite = tuple((p, s if math.isfinite(s) else -1e9) for p, s in scores)
        results.append(
            FoldResult(
                index=fold.index,
                train_start=fold.train_start,
                train_end=fold.train_end,
                test_start=fold.test_start,
                test_end=fold.test_end,
                chosen_params=chosen,
                train_scores=finite,
                test_summary=test.summary,
                test_run_digest=test.run_digest,
            )
        )
    oos_curve = np.cumprod(np.concatenate([[1.0], 1 + np.array(oos_returns)]))
    variances = [float(np.var(s, ddof=1)) for s in trial_sharpes if len(s) > 1]
    dsr: SharpeTest | None = None
    if len(oos_returns) >= 20 and np.std(oos_returns) > 0:
        try:
            dsr = deflated_sharpe(
                returns=oos_returns,
                n_trials=len(grid),
                trial_sharpe_variance=float(np.mean(variances)) if variances else 0.0,
            )
        except ValueError:
            dsr = None
    chosen_train = [max(s for _, s in r.train_scores) for r in results]
    return WalkForwardResult(
        folds=tuple(results),
        n_trials=len(grid),
        oos_equity=tuple(float(v) for v in oos_curve),
        oos_stats=return_stats(oos_curve.tolist()),
        deflated_sharpe=dsr,
        mean_train_sharpe=float(np.mean(chosen_train)),
        mean_test_sharpe=float(np.mean([r.test_summary.sharpe for r in results])),
    )
