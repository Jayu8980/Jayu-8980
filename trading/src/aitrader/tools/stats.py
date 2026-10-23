"""Quant and statistics tools: regression, rolling beta/correlation, cointegration, GARCH,
ARIMA, regime detection, change points, event studies and calibration metrics.

Point-in-time note: every tool uses only the data it is given. Callers pass data available
as of the decision time; tools never smooth with future observations (the HMM tool reports
filtered, not smoothed, probabilities for that reason).
"""

from __future__ import annotations

import itertools
import warnings
from collections.abc import Sequence
from typing import Annotated

import numpy as np
from pydantic import Field
from scipy import stats as sps

from aitrader.tools.registry import tool
from aitrader.tools.types import Finite, Series, ToolOutput, Unit, arr, same_length

_RNG = np.random.default_rng(7)
_EX_Y = [float(v) for v in np.cumsum(_RNG.normal(0, 1, 120))]
_EX_X = [float(v) for v in np.cumsum(_RNG.normal(0, 1, 120))]
_EX_RET = [float(v) for v in _RNG.normal(0.0005, 0.01, 250)]
_EX_MKT = [float(v) for v in _RNG.normal(0.0004, 0.009, 250)]
_EX_REGIME = [
    float(v) for v in np.concatenate([_RNG.normal(0.001, 0.007, 150), _RNG.normal(-0.002, 0.025, 80)])
]


class OlsResult(ToolOutput):
    coefficients: tuple[float, ...]  # intercept first when included
    std_errors: tuple[float, ...]
    t_stats: tuple[float, ...]
    r_squared: float
    residual_std: float
    n_obs: int


@tool("stats.ols", "1.0.0", example={"y": _EX_Y[:50], "regressors": [_EX_X[:50]]})
def ols(
    y: Series,
    regressors: Annotated[Sequence[Series], Field(min_length=1)],
    add_intercept: bool = True,
) -> OlsResult:
    yv = arr(y)
    columns = [arr(r) for r in regressors]
    same_length(y, *regressors)
    if add_intercept:
        columns.insert(0, np.ones_like(yv))
    x = np.column_stack(columns)
    n, k = x.shape
    if n <= k:
        raise ValueError("need more observations than regressors")
    beta, *_ = np.linalg.lstsq(x, yv, rcond=None)
    resid = yv - x @ beta
    dof = n - k
    sigma2 = float(resid @ resid) / dof
    cov = sigma2 * np.linalg.pinv(x.T @ x)
    se = np.sqrt(np.clip(np.diag(cov), 0.0, None))
    t = np.divide(beta, se, out=np.zeros_like(beta), where=se > 0)
    tss = float(((yv - yv.mean()) ** 2).sum())
    r2 = 1.0 - float(resid @ resid) / tss if tss > 0 else 0.0
    return OlsResult(
        coefficients=tuple(beta.tolist()),
        std_errors=tuple(se.tolist()),
        t_stats=tuple(t.tolist()),
        r_squared=r2,
        residual_std=float(np.sqrt(sigma2)),
        n_obs=n,
    )


class RollingSeries(ToolOutput):
    window: int
    values: tuple[float, ...]  # values[i] uses observations [i, i + window)


@tool("stats.rolling_beta", "1.0.0", example={"asset": _EX_RET, "market": _EX_MKT, "window": 60})
def rolling_beta(asset: Series, market: Series, window: Annotated[int, Field(ge=10)] = 60) -> RollingSeries:
    n = same_length(asset, market)
    a, m = arr(asset), arr(market)
    if n < window:
        raise ValueError("series shorter than window")
    out = []
    for i in range(n - window + 1):
        ms, as_ = m[i : i + window], a[i : i + window]
        var = float(np.var(ms, ddof=1))
        out.append(float(np.cov(as_, ms, ddof=1)[0, 1]) / var if var > 0 else 0.0)
    return RollingSeries(window=window, values=tuple(out))


@tool("stats.rolling_correlation", "1.0.0", example={"a": _EX_RET, "b": _EX_MKT, "window": 60})
def rolling_correlation(a: Series, b: Series, window: Annotated[int, Field(ge=10)] = 60) -> RollingSeries:
    n = same_length(a, b)
    av, bv = arr(a), arr(b)
    if n < window:
        raise ValueError("series shorter than window")
    out = []
    for i in range(n - window + 1):
        x, y = av[i : i + window], bv[i : i + window]
        if np.std(x) == 0 or np.std(y) == 0:
            out.append(0.0)
        else:
            out.append(float(np.corrcoef(x, y)[0, 1]))
    return RollingSeries(window=window, values=tuple(out))


class CointegrationResult(ToolOutput):
    t_stat: float
    p_value: float
    hedge_ratio: float  # y ~ hedge_ratio * x + c
    spread_half_life: float | None  # in observations; None if the spread does not mean-revert
    cointegrated_at_5pct: bool


@tool("stats.cointegration", "1.0.0", example={"y": _EX_Y, "x": _EX_X})
def cointegration(y: Series, x: Series) -> CointegrationResult:
    from statsmodels.tsa.stattools import coint

    same_length(y, x)
    yv, xv = arr(y), arr(x)
    if len(yv) < 30:
        raise ValueError("cointegration needs at least 30 observations")
    t_stat, p_value, _ = coint(yv, xv)
    slope, intercept = np.polyfit(xv, yv, 1)
    spread = yv - (slope * xv + intercept)
    lagged, delta = spread[:-1], np.diff(spread)
    phi = float(np.polyfit(lagged, delta, 1)[0])
    half_life = float(-np.log(2) / np.log1p(phi)) if -1 < phi < 0 else None
    return CointegrationResult(
        t_stat=float(t_stat),
        p_value=float(p_value),
        hedge_ratio=float(slope),
        spread_half_life=half_life,
        cointegrated_at_5pct=bool(p_value < 0.05),
    )


class GarchResult(ToolOutput):
    omega: float
    alpha: float
    beta: float
    persistence: float
    daily_vol_forecast: tuple[float, ...]  # fractional daily vol for horizons 1..h
    annualised_vol_next: float


@tool("stats.garch11", "1.0.0", example={"returns": _EX_RET, "horizon": 5})
def garch11(
    returns: Annotated[Series, Field(min_length=100)],
    horizon: Annotated[int, Field(ge=1, le=60)] = 5,
    trading_days: Annotated[int, Field(ge=200, le=366)] = 252,
) -> GarchResult:
    from arch import arch_model

    scaled = arr(returns) * 100.0  # arch is numerically happier in percent
    model = arch_model(scaled, mean="Constant", vol="GARCH", p=1, q=1, dist="normal", rescale=False)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        fit = model.fit(disp="off", show_warning=False)
    params = fit.params
    forecast = fit.forecast(horizon=horizon, reindex=False).variance.to_numpy()[-1]
    daily = np.sqrt(forecast) / 100.0
    return GarchResult(
        omega=float(params["omega"]) / 1e4,
        alpha=float(params["alpha[1]"]),
        beta=float(params["beta[1]"]),
        persistence=float(params["alpha[1]"] + params["beta[1]"]),
        daily_vol_forecast=tuple(float(v) for v in daily),
        annualised_vol_next=float(daily[0] * np.sqrt(trading_days)),
    )


class ArimaForecast(ToolOutput):
    order: tuple[int, int, int]
    mean: tuple[float, ...]
    lower_95: tuple[float, ...]
    upper_95: tuple[float, ...]
    aic: float


@tool("stats.arima_forecast", "1.0.0", example={"series": _EX_RET[:120], "p": 1, "d": 0, "q": 0, "steps": 3})
def arima_forecast(
    series: Annotated[Series, Field(min_length=30)],
    p: Annotated[int, Field(ge=0, le=5)] = 1,
    d: Annotated[int, Field(ge=0, le=2)] = 0,
    q: Annotated[int, Field(ge=0, le=5)] = 0,
    steps: Annotated[int, Field(ge=1, le=60)] = 5,
) -> ArimaForecast:
    from statsmodels.tsa.arima.model import ARIMA

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        fit = ARIMA(arr(series), order=(p, d, q)).fit()
        forecast = fit.get_forecast(steps=steps)
    interval = np.asarray(forecast.conf_int(alpha=0.05))
    return ArimaForecast(
        order=(p, d, q),
        mean=tuple(float(v) for v in np.asarray(forecast.predicted_mean)),
        lower_95=tuple(float(v) for v in interval[:, 0]),
        upper_95=tuple(float(v) for v in interval[:, 1]),
        aic=float(fit.aic),
    )


class RegimeResult(ToolOutput):
    n_regimes: int
    # Regimes are relabelled by ascending volatility: 0 = calmest.
    regime_means: tuple[float, ...]
    regime_vols: tuple[float, ...]
    current_probabilities: tuple[float, ...]  # filtered (no lookahead), last observation
    current_regime: int
    filtered_high_vol_probability: tuple[float, ...]  # P(highest-vol regime) per observation
    expected_durations: tuple[float, ...]


@tool("stats.regime_hmm", "1.0.0", example={"returns": _EX_REGIME, "n_regimes": 2})
def regime_hmm(
    returns: Annotated[Series, Field(min_length=100)], n_regimes: Annotated[int, Field(ge=2, le=3)] = 2
) -> RegimeResult:
    """Markov-switching mean/variance model. Uses statsmodels' deterministic start values
    (no random restarts) so identical inputs give identical outputs."""
    from statsmodels.tsa.regime_switching.markov_regression import MarkovRegression

    data = arr(returns)
    scale = float(np.std(data)) or 1.0
    model = MarkovRegression(data / scale, k_regimes=n_regimes, trend="c", switching_variance=True)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            fit = model.fit(disp=False, em_iter=50)
        except (RuntimeError, np.linalg.LinAlgError) as exc:
            raise ArithmeticError(f"regime model failed to fit: {exc}") from exc
    names = list(fit.model.param_names)
    params = np.asarray(fit.params)
    means = np.array([params[names.index(f"const[{i}]")] for i in range(n_regimes)]) * scale
    vols = np.sqrt([params[names.index(f"sigma2[{i}]")] for i in range(n_regimes)]) * scale
    order = np.argsort(vols)
    filtered = np.asarray(fit.filtered_marginal_probabilities)[:, order]
    durations = np.asarray(fit.expected_durations)[order]
    current = filtered[-1]
    return RegimeResult(
        n_regimes=n_regimes,
        regime_means=tuple(float(v) for v in means[order]),
        regime_vols=tuple(float(v) for v in vols[order]),
        current_probabilities=tuple(float(v) for v in current),
        current_regime=int(np.argmax(current)),
        filtered_high_vol_probability=tuple(float(v) for v in filtered[:, -1]),
        expected_durations=tuple(float(min(v, 1e9)) for v in durations),
    )


class ChangePoints(ToolOutput):
    indices: tuple[int, ...]  # first index of each new segment
    segment_means: tuple[float, ...]


@tool("stats.change_points", "1.0.0", example={"series": _EX_RET, "min_size": 20})
def change_points(
    series: Annotated[Series, Field(min_length=10)],
    min_size: Annotated[int, Field(ge=2)] = 20,
    penalty_multiplier: Annotated[float, Field(gt=0)] = 1.0,
) -> ChangePoints:
    """Binary segmentation on the mean with a BIC-style penalty (noise from MAD of diffs)."""
    data = arr(series)
    n = len(data)
    noise = float(np.median(np.abs(np.diff(data) - np.median(np.diff(data))))) * 1.4826 / np.sqrt(2)
    sigma2 = noise**2 if noise > 0 else float(np.var(data)) or 1.0
    penalty = penalty_multiplier * 2.0 * sigma2 * np.log(n)

    def sse(lo: int, hi: int) -> float:
        seg = data[lo:hi]
        return float(((seg - seg.mean()) ** 2).sum())

    points: list[int] = []
    stack = [(0, n)]
    while stack:
        lo, hi = stack.pop()
        if hi - lo < 2 * min_size:
            continue
        base = sse(lo, hi)
        best_gain, best_k = 0.0, -1
        for k in range(lo + min_size, hi - min_size + 1):
            gain = base - sse(lo, k) - sse(k, hi)
            if gain > best_gain:
                best_gain, best_k = gain, k
        if best_k > 0 and best_gain > penalty:
            points.append(best_k)
            stack.extend([(lo, best_k), (best_k, hi)])
    points.sort()
    bounds = [0, *points, n]
    means = [float(data[a:b].mean()) for a, b in itertools.pairwise(bounds)]
    return ChangePoints(indices=tuple(points), segment_means=tuple(means))


class EventWindowResult(ToolOutput):
    days: int
    car: float  # cumulative abnormal return over [event, event + days)
    t_stat: float


class EventStudyResult(ToolOutput):
    alpha: float
    beta: float
    residual_std: float
    abnormal_returns: tuple[float, ...]  # from the event day to the end of the data
    windows: tuple[EventWindowResult, ...]  # only windows fully covered by the data
    incomplete_windows: tuple[int, ...]


@tool(
    "stats.event_study",
    "1.0.0",
    example={"asset_returns": _EX_RET, "market_returns": _EX_MKT, "event_index": 200},
)
def event_study(
    asset_returns: Series,
    market_returns: Series,
    event_index: Annotated[int, Field(ge=1)],
    estimation_window: Annotated[int, Field(ge=20)] = 120,
    gap: Annotated[int, Field(ge=0)] = 5,
    windows: tuple[int, ...] = (1, 2, 5),
) -> EventStudyResult:
    """Market-model event study. Estimation uses only data ending ``gap`` days before the event."""
    n = same_length(asset_returns, market_returns)
    a, m = arr(asset_returns), arr(market_returns)
    est_end = event_index - gap
    est_start = est_end - estimation_window
    if est_start < 0 or event_index >= n:
        raise ValueError("not enough data before the event or event outside the series")
    beta, alpha = np.polyfit(m[est_start:est_end], a[est_start:est_end], 1)
    resid = a[est_start:est_end] - (alpha + beta * m[est_start:est_end])
    sigma = float(np.std(resid, ddof=2))
    ar = a[event_index:] - (alpha + beta * m[event_index:])
    done, incomplete = [], []
    for days in sorted(set(windows)):
        if days < 1:
            raise ValueError("windows are positive day counts")
        if days > len(ar):
            incomplete.append(days)
            continue
        car = float(ar[:days].sum())
        t = car / (sigma * np.sqrt(days)) if sigma > 0 else 0.0
        done.append(EventWindowResult(days=days, car=car, t_stat=float(t)))
    return EventStudyResult(
        alpha=float(alpha),
        beta=float(beta),
        residual_std=sigma,
        abnormal_returns=tuple(float(v) for v in ar),
        windows=tuple(done),
        incomplete_windows=tuple(incomplete),
    )


class ScoreResult(ToolOutput):
    value: float
    n: int


@tool("stats.brier", "1.0.0", example={"probabilities": [0.8, 0.3, 0.6], "outcomes": [1, 0, 0]})
def brier(
    probabilities: Annotated[Sequence[Unit], Field(min_length=1)],
    outcomes: Annotated[Sequence[Annotated[int, Field(ge=0, le=1)]], Field(min_length=1)],
) -> ScoreResult:
    same_length(probabilities, outcomes)
    p, o = np.asarray(probabilities, float), np.asarray(outcomes, float)
    return ScoreResult(value=float(np.mean((p - o) ** 2)), n=len(p))


@tool(
    "stats.brier_multiclass",
    "1.0.0",
    example={"probabilities": [[0.6, 0.3, 0.1], [0.2, 0.5, 0.3]], "outcomes": [0, 2]},
)
def brier_multiclass(
    probabilities: Annotated[Sequence[Annotated[Sequence[Unit], Field(min_length=2)]], Field(min_length=1)],
    outcomes: Annotated[Sequence[Annotated[int, Field(ge=0)]], Field(min_length=1)],
) -> ScoreResult:
    """Mean over forecasts of sum_k (p_k - o_k)^2 / K, scaled to [0, 1]."""
    same_length(probabilities, outcomes)
    p = np.asarray(probabilities, float)
    if not np.allclose(p.sum(axis=1), 1.0, atol=1e-6):
        raise ValueError("each probability row must sum to 1")
    onehot = np.zeros_like(p)
    for row, outcome in enumerate(outcomes):
        if outcome >= p.shape[1]:
            raise ValueError("outcome index out of range")
        onehot[row, outcome] = 1.0
    return ScoreResult(value=float(np.mean(((p - onehot) ** 2).sum(axis=1) / 2.0)), n=len(p))


@tool(
    "stats.information_coefficient",
    "1.0.0",
    example={"predictions": [0.1, -0.2, 0.3, 0.05], "outcomes": [0.02, -0.01, 0.03, -0.005]},
)
def information_coefficient(
    predictions: Annotated[Sequence[Finite], Field(min_length=3)],
    outcomes: Annotated[Sequence[Finite], Field(min_length=3)],
) -> ScoreResult:
    """Spearman rank correlation between predictions and realised outcomes."""
    same_length(predictions, outcomes)
    p, o = arr(predictions), arr(outcomes)
    if np.ptp(p) == 0 or np.ptp(o) == 0:
        return ScoreResult(value=0.0, n=len(p))
    rho = sps.spearmanr(p, o).statistic
    return ScoreResult(value=float(rho), n=len(p))


class Credibility(ToolOutput):
    credibility: float
    raw_rate: float | None
    effective_sample: float


@tool(
    "attribution.shrink_credibility",
    "1.0.0",
    example={"successes": 12, "trials": 20, "prior_mean": 0.5, "prior_strength": 20},
)
def shrink_credibility(
    successes: Annotated[int, Field(ge=0)],
    trials: Annotated[int, Field(ge=0)],
    prior_mean: Unit = 0.5,
    prior_strength: Annotated[float, Field(gt=0)] = 20.0,
) -> Credibility:
    """Beta-binomial posterior mean: small samples stay near the prior."""
    if successes > trials:
        raise ValueError("successes cannot exceed trials")
    a = prior_mean * prior_strength + successes
    b = (1 - prior_mean) * prior_strength + (trials - successes)
    return Credibility(
        credibility=float(a / (a + b)),
        raw_rate=successes / trials if trials else None,
        effective_sample=float(prior_strength + trials),
    )
