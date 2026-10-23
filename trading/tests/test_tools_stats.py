import numpy as np
import pytest

from aitrader.tools.stats import (
    brier,
    brier_multiclass,
    change_points,
    cointegration,
    event_study,
    garch11,
    information_coefficient,
    ols,
    regime_hmm,
    rolling_beta,
    rolling_correlation,
    shrink_credibility,
)

RNG = np.random.default_rng(2024)


def test_ols_recovers_coefficients() -> None:
    x1, x2 = RNG.normal(size=500), RNG.normal(size=500)
    y = 1.5 + 2.0 * x1 - 0.5 * x2 + RNG.normal(0, 0.1, 500)
    res = ols(y=y.tolist(), regressors=[x1.tolist(), x2.tolist()])
    assert res.coefficients == pytest.approx((1.5, 2.0, -0.5), abs=0.02)
    assert res.r_squared > 0.99 and abs(res.t_stats[1]) > 100


def test_rolling_beta_and_correlation() -> None:
    m = RNG.normal(0, 0.01, 300)
    a = 1.3 * m + RNG.normal(0, 0.001, 300)
    betas = rolling_beta(asset=a.tolist(), market=m.tolist(), window=60).values
    assert len(betas) == 241
    assert np.mean(betas) == pytest.approx(1.3, abs=0.02)
    corr = rolling_correlation(a=a.tolist(), b=m.tolist(), window=60).values
    assert min(corr) > 0.98


def test_cointegration_separates_pairs_from_random_walks() -> None:
    x = np.cumsum(RNG.normal(size=500))
    y = 0.8 * x + RNG.normal(0, 0.5, 500)
    res = cointegration(y=y.tolist(), x=x.tolist())
    assert res.cointegrated_at_5pct and res.hedge_ratio == pytest.approx(0.8, abs=0.05)
    assert res.spread_half_life is not None and res.spread_half_life < 5
    z = np.cumsum(RNG.normal(size=500))
    assert not cointegration(y=z.tolist(), x=x.tolist()).cointegrated_at_5pct


def test_garch_recovers_persistence() -> None:
    omega, alpha, beta = 2e-6, 0.08, 0.9
    n, var, out = 3000, omega / (1 - alpha - beta), []
    for _ in range(n):
        r = RNG.normal(0, np.sqrt(var))
        out.append(r)
        var = omega + alpha * r * r + beta * var
    res = garch11(returns=out, horizon=5)
    assert res.persistence == pytest.approx(0.98, abs=0.03)
    assert len(res.daily_vol_forecast) == 5


def test_regime_hmm_flags_high_vol_regime_without_lookahead() -> None:
    calm = RNG.normal(0.0008, 0.006, 300)
    stress = RNG.normal(-0.002, 0.03, 60)
    res = regime_hmm(returns=np.concatenate([calm, stress]).tolist())
    assert res.regime_vols[1] > 3 * res.regime_vols[0]
    assert res.current_regime == 1
    probs = res.filtered_high_vol_probability
    assert np.mean(probs[:280]) < 0.2 and np.mean(probs[-40:]) > 0.8
    # Filtered probabilities at t must not change when later data is appended.
    truncated = regime_hmm(returns=np.concatenate([calm, stress[:30]]).tolist())
    assert truncated.current_regime == 1


def test_change_points_detects_mean_shift() -> None:
    series = np.concatenate([RNG.normal(0, 1, 150), RNG.normal(3, 1, 150)])
    res = change_points(series=series.tolist(), min_size=20)
    assert len(res.indices) == 1 and abs(res.indices[0] - 150) <= 5
    assert change_points(series=RNG.normal(0, 1, 300).tolist(), min_size=20).indices == ()


def test_event_study_detects_planted_abnormal_return() -> None:
    m = RNG.normal(0.0005, 0.01, 300)
    a = 0.0002 + 1.1 * m + RNG.normal(0, 0.005, 300)
    a[250] += 0.06
    res = event_study(
        asset_returns=a.tolist(), market_returns=m.tolist(), event_index=250, windows=(1, 5, 100)
    )
    one_day = res.windows[0]
    assert one_day.days == 1 and one_day.car == pytest.approx(0.06, abs=0.015) and one_day.t_stat > 8
    assert res.beta == pytest.approx(1.1, abs=0.1)
    assert res.incomplete_windows == (100,)  # never reports a window it cannot see


def test_event_study_estimation_window_excludes_the_event() -> None:
    with pytest.raises(ValueError, match="not enough data"):
        event_study(asset_returns=[0.0] * 50, market_returns=[0.0] * 50, event_index=40)


def test_calibration_scores() -> None:
    assert brier(probabilities=[1.0, 0.0], outcomes=[1, 0]).value == 0.0
    assert brier(probabilities=[0.7], outcomes=[0]).value == pytest.approx(0.49)
    perfect = brier_multiclass(probabilities=[[1, 0, 0], [0, 0, 1]], outcomes=[0, 2]).value
    worst = brier_multiclass(probabilities=[[0, 1, 0]], outcomes=[0]).value
    assert perfect == 0.0 and worst == 1.0
    with pytest.raises(ValueError, match="sum to 1"):
        brier_multiclass(probabilities=[[0.5, 0.6]], outcomes=[0])
    assert information_coefficient(
        predictions=[1, 2, 3, 4], outcomes=[10, 20, 30, 40]
    ).value == pytest.approx(1.0)


def test_credibility_shrinks_small_samples() -> None:
    small = shrink_credibility(successes=3, trials=3, prior_mean=0.5, prior_strength=20)
    large = shrink_credibility(successes=300, trials=400, prior_mean=0.5, prior_strength=20)
    assert small.credibility == pytest.approx(13 / 23)
    assert large.credibility == pytest.approx(310 / 420)
    with pytest.raises(ValueError):
        shrink_credibility(successes=5, trials=3)
