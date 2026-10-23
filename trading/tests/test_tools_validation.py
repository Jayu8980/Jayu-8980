import itertools
import math

import numpy as np
import pytest
from scipy.stats import kurtosis, norm, skew

from aitrader.tools.validation import deflated_sharpe, probabilistic_sharpe, return_stats, trade_resampling

RNG = np.random.default_rng(99)
R = RNG.normal(0.0008, 0.01, 750)


def test_psr_matches_the_formula() -> None:
    res = probabilistic_sharpe(returns=R.tolist(), benchmark_sharpe=0.0)
    sr = R.mean() / R.std(ddof=1)
    g3, g4 = skew(R), kurtosis(R, fisher=False)
    expected = norm.cdf(sr * math.sqrt(len(R) - 1) / math.sqrt(1 - g3 * sr + (g4 - 1) / 4 * sr * sr))
    assert res.probability == pytest.approx(expected)
    assert res.sharpe == pytest.approx(sr)


def test_deflated_sharpe_penalises_more_trials() -> None:
    one = deflated_sharpe(returns=R.tolist(), n_trials=1, trial_sharpe_variance=0.001)
    assert one.probability == pytest.approx(probabilistic_sharpe(returns=R.tolist()).probability)
    probs = [
        deflated_sharpe(returns=R.tolist(), n_trials=n, trial_sharpe_variance=0.001).probability
        for n in (2, 10, 100, 1000)
    ]
    assert all(a > b for a, b in itertools.pairwise(probs))
    low_var = deflated_sharpe(returns=R.tolist(), n_trials=100, trial_sharpe_variance=1e-5)
    high_var = deflated_sharpe(returns=R.tolist(), n_trials=100, trial_sharpe_variance=1e-2)
    assert high_var.benchmark_sharpe > low_var.benchmark_sharpe


def test_trade_resampling() -> None:
    trades = RNG.normal(0.005, 0.02, 60).tolist()
    a = trade_resampling(trade_returns=trades, seed=1, n_simulations=2000)
    assert a == trade_resampling(trade_returns=trades, seed=1, n_simulations=2000)
    assert a.final_return_p05 <= a.final_return_p50 <= a.final_return_p95
    assert 0 <= a.max_drawdown_p50 <= a.max_drawdown_p95 < 1
    winners = trade_resampling(trade_returns=[0.01] * 10, seed=2, n_simulations=100)
    assert winners.probability_of_loss == 0 and winners.max_drawdown_p95 == 0


def test_return_stats() -> None:
    curve = [100 * 1.01**i for i in range(253)]
    stats = return_stats(curve)
    assert stats.cagr == pytest.approx(1.01**252 - 1)
    assert stats.max_drawdown == 0
    with pytest.raises(ValueError):
        return_stats([100, -1])
