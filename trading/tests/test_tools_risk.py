from typing import Any

import numpy as np
import pytest
from scipy.stats import norm

from aitrader.tools.risk import (
    drawdown_metrics,
    exposure_decomposition,
    stress_test,
    var_historical,
    var_monte_carlo,
    var_parametric,
)


def test_historical_var_and_cvar_on_constructed_tail() -> None:
    returns = [0.001] * 990 + [-0.05] * 10
    res = var_historical(returns=returns, confidence=0.99)
    assert res.var == pytest.approx(0.05) and res.cvar == pytest.approx(0.05)
    assert var_historical(returns=returns, confidence=0.99, horizon_days=4).var == pytest.approx(0.10)


def test_parametric_normal_matches_formula() -> None:
    rng = np.random.default_rng(0)
    r = rng.normal(0.0005, 0.01, 2000)
    res = var_parametric(returns=r.tolist(), confidence=0.99, cornish_fisher=False)
    expected = -r.mean() + norm.ppf(0.99) * r.std(ddof=1)
    assert res.var == pytest.approx(expected)
    assert res.cvar > res.var


def test_cornish_fisher_raises_var_for_fat_left_tail() -> None:
    rng = np.random.default_rng(1)
    r = np.concatenate([rng.normal(0.001, 0.008, 1900), rng.normal(-0.04, 0.01, 100)])
    plain = var_parametric(returns=r.tolist(), cornish_fisher=False).var
    adjusted = var_parametric(returns=r.tolist(), cornish_fisher=True).var
    assert adjusted > plain


def test_monte_carlo_is_seeded_and_close_to_parametric() -> None:
    args: dict[str, Any] = {
        "weights": [0.6, 0.4],
        "mean_returns": [0.0, 0.0],
        "covariance": [[0.0001, 0.00003], [0.00003, 0.0004]],
        "confidence": 0.99,
        "n_paths": 200_000,
        "student_t_dof": None,
    }
    a = var_monte_carlo(**args, seed=42)
    assert a == var_monte_carlo(**args, seed=42)
    sigma = np.sqrt(np.array([0.6, 0.4]) @ np.array(args["covariance"]) @ np.array([0.6, 0.4]))
    assert a.var == pytest.approx(norm.ppf(0.99) * sigma, rel=0.02)
    fat = var_monte_carlo(**{**args, "student_t_dof": 3.0}, seed=42)
    assert fat.cvar > a.cvar


def test_monte_carlo_rejects_bad_covariance() -> None:
    with pytest.raises(ValueError, match="positive semi-definite"):
        var_monte_carlo(weights=[1, 1], mean_returns=[0, 0], covariance=[[1, 2], [2, 1]], seed=1)


def test_drawdown_metrics() -> None:
    m = drawdown_metrics(equity=[100, 110, 99, 105, 121, 115])
    assert m.max_drawdown == pytest.approx(0.1)
    assert m.peak_index == 1 and m.trough_index == 2
    assert m.current_drawdown == pytest.approx(1 - 115 / 121)
    assert m.max_drawdown_duration == 3  # peak at 1, recovered at 4


def test_stress_test_flags_limit_breach() -> None:
    res = stress_test(
        exposures={"NIFTY": 800_000, "USDINR": -100_000},
        scenarios={"crash": {"NIFTY": -0.15, "USDINR": 0.03}, "calm": {"NIFTY": 0.01}},
        equity=1_000_000,
        loss_limit_fraction=0.1,
    )
    crash = next(r for r in res.results if r.scenario == "crash")
    assert crash.pnl == pytest.approx(-123_000)
    assert crash.breaches_limit and res.worst_scenario == "crash"


def test_exposure_decomposition() -> None:
    cov = [[0.04, 0.01, 0.0], [0.01, 0.09, 0.0], [0.0, 0.0, 0.01]]
    res = exposure_decomposition(
        weights=[0.4, -0.2, 0.3], groups=["IT", "BANK", "IT"], betas=[1.0, 1.5, 0.5], covariance=cov
    )
    assert res.gross == pytest.approx(0.9) and res.net == pytest.approx(0.5)
    assert res.by_group == pytest.approx({"IT": 0.7, "BANK": -0.2})
    assert res.beta_adjusted_net == pytest.approx(0.4 - 0.3 + 0.15)
    assert sum(res.risk_contributions or ()) == pytest.approx(1.0)
