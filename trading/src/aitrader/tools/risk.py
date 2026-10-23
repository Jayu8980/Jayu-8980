"""Risk tools: VaR/CVaR (historical, parametric, Monte Carlo), drawdown and tail metrics,
stress testing and exposure decomposition.

Conventions: returns are simple fractional returns; VaR and CVaR are reported as positive
loss fractions of portfolio value at the given confidence over the given horizon.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Annotated

import numpy as np
from pydantic import Field
from scipy import stats as sps

from aitrader.tools.registry import tool
from aitrader.tools.types import (
    Confidence,
    Finite,
    Matrix,
    Positive,
    Series,
    ToolOutput,
    arr,
    mat,
    same_length,
)

_RNG = np.random.default_rng(11)
_EX_RET = [float(v) for v in _RNG.normal(0.0004, 0.012, 500)]


class VarResult(ToolOutput):
    method: str
    confidence: float
    horizon_days: int
    var: float
    cvar: float


def _scale(horizon_days: int) -> float:
    return float(np.sqrt(horizon_days))


@tool("risk.var_historical", "1.0.0", example={"returns": _EX_RET, "confidence": 0.99})
def var_historical(
    returns: Annotated[Series, Field(min_length=50)],
    confidence: Confidence = 0.99,
    horizon_days: Annotated[int, Field(ge=1, le=20)] = 1,
) -> VarResult:
    """Empirical quantile of losses; multi-day by square-root-of-time scaling."""
    losses = -arr(returns)
    var = float(np.quantile(losses, confidence, method="higher"))
    tail = losses[losses >= var]
    cvar = float(tail.mean())
    k = _scale(horizon_days)
    return VarResult(
        method="historical",
        confidence=confidence,
        horizon_days=horizon_days,
        var=max(var * k, 0.0),
        cvar=max(cvar * k, 0.0),
    )


@tool("risk.var_parametric", "1.0.0", example={"returns": _EX_RET, "confidence": 0.99})
def var_parametric(
    returns: Annotated[Series, Field(min_length=20)],
    confidence: Confidence = 0.99,
    horizon_days: Annotated[int, Field(ge=1, le=20)] = 1,
    cornish_fisher: bool = True,
) -> VarResult:
    """Normal (optionally Cornish-Fisher skew/kurtosis-adjusted) VaR; CVaR from the normal."""
    r = arr(returns)
    mu, sigma = float(r.mean()), float(r.std(ddof=1))
    z = float(sps.norm.ppf(confidence))
    if cornish_fisher:
        s, k = float(sps.skew(r)), float(sps.kurtosis(r))  # excess kurtosis
        z_left = -z
        z_left = (
            z_left
            + (z_left**2 - 1) * s / 6
            + (z_left**3 - 3 * z_left) * k / 24
            - (2 * z_left**3 - 5 * z_left) * s**2 / 36
        )
        z = -z_left
    h = horizon_days
    var = -(mu * h) + z * sigma * _scale(h)
    cvar = -(mu * h) + sigma * _scale(h) * float(sps.norm.pdf(sps.norm.ppf(confidence))) / (1 - confidence)
    return VarResult(
        method="parametric_cf" if cornish_fisher else "parametric_normal",
        confidence=confidence,
        horizon_days=h,
        var=max(var, 0.0),
        cvar=max(cvar, var, 0.0),
    )


@tool(
    "risk.var_monte_carlo",
    "1.0.0",
    example={
        "weights": [0.5, 0.5],
        "mean_returns": [0.0004, 0.0003],
        "covariance": [[0.0001, 0.00004], [0.00004, 0.0002]],
        "seed": 1,
    },
)
def var_monte_carlo(
    weights: Annotated[Sequence[Finite], Field(min_length=1)],
    mean_returns: Annotated[Sequence[Finite], Field(min_length=1)],
    covariance: Matrix,
    seed: int,
    confidence: Confidence = 0.99,
    horizon_days: Annotated[int, Field(ge=1, le=20)] = 1,
    n_paths: Annotated[int, Field(ge=1000, le=1_000_000)] = 50_000,
    student_t_dof: Annotated[float, Field(gt=2)] | None = 5.0,
) -> VarResult:
    """Simulated portfolio returns (multivariate normal or fat-tailed Student-t), fixed seed."""
    w, mu, cov = arr(weights), arr(mean_returns), mat(covariance)
    if cov.shape != (len(w), len(w)) or len(mu) != len(w):
        raise ValueError("weights, means and covariance dimensions differ")
    if not np.allclose(cov, cov.T) or np.min(np.linalg.eigvalsh(cov)) < -1e-12:
        raise ValueError("covariance must be symmetric positive semi-definite")
    rng = np.random.default_rng(seed)
    chol = np.linalg.cholesky(cov + np.eye(len(w)) * 1e-14)
    z = rng.standard_normal((n_paths, len(w)))
    if student_t_dof is not None:
        chi = rng.chisquare(student_t_dof, size=(n_paths, 1))
        z = z * np.sqrt((student_t_dof - 2) / chi)  # unit-variance Student-t
    port = (mu + z @ chol.T) @ w
    mu_p = float(mu @ w)
    # Drift scales with time, the random part with the square root of time.
    losses = -(mu_p * horizon_days + (port - mu_p) * _scale(horizon_days))
    var = float(np.quantile(losses, confidence))
    cvar = float(losses[losses >= var].mean())
    return VarResult(
        method="monte_carlo_t" if student_t_dof else "monte_carlo_normal",
        confidence=confidence,
        horizon_days=horizon_days,
        var=max(var, 0.0),
        cvar=max(cvar, 0.0),
    )


class DrawdownMetrics(ToolOutput):
    max_drawdown: float  # positive fraction
    current_drawdown: float
    max_drawdown_duration: int  # observations from peak to recovery (or to the end)
    peak_index: int
    trough_index: int
    total_return: float
    worst_period_return: float
    skewness: float
    excess_kurtosis: float


@tool("risk.drawdown_metrics", "1.0.0", example={"equity": [100, 104, 101, 97, 99, 106, 103]})
def drawdown_metrics(equity: Annotated[Sequence[Positive], Field(min_length=2)]) -> DrawdownMetrics:
    curve = arr(equity)
    running_peak = np.maximum.accumulate(curve)
    dd = 1.0 - curve / running_peak
    trough = int(np.argmax(dd))
    peak = int(np.argmax(curve[: trough + 1]))
    longest, start = 0, None
    for i, value in enumerate(dd):
        if value > 0 and start is None:
            start = i - 1
        elif value == 0 and start is not None:
            longest, start = max(longest, i - start), None
    if start is not None:
        longest = max(longest, len(dd) - 1 - start)
    rets = curve[1:] / curve[:-1] - 1.0
    many = len(rets) > 3
    return DrawdownMetrics(
        max_drawdown=float(np.max(dd)),
        current_drawdown=float(dd[-1]),
        max_drawdown_duration=longest,
        peak_index=peak,
        trough_index=trough,
        total_return=float(curve[-1] / curve[0] - 1.0),
        worst_period_return=float(np.min(rets)),
        skewness=float(sps.skew(rets)) if many else 0.0,
        excess_kurtosis=float(sps.kurtosis(rets)) if many else 0.0,
    )


class ScenarioPnl(ToolOutput):
    scenario: str
    pnl: float  # in currency
    pnl_fraction: float  # of equity
    breaches_limit: bool


class StressResult(ToolOutput):
    results: tuple[ScenarioPnl, ...]
    worst_scenario: str
    worst_pnl_fraction: float


@tool(
    "risk.stress_test",
    "1.0.0",
    example={
        "exposures": {"NIFTY": 600000, "BANKNIFTY": 200000},
        "scenarios": {"crash_2020": {"NIFTY": -0.13, "BANKNIFTY": -0.18}, "rally": {"NIFTY": 0.05}},
        "equity": 1000000,
        "loss_limit_fraction": 0.1,
    },
)
def stress_test(
    exposures: Mapping[str, Finite],
    scenarios: Annotated[Mapping[str, Mapping[str, Finite]], Field(min_length=1)],
    equity: Positive,
    loss_limit_fraction: Annotated[float, Field(gt=0, le=1)],
) -> StressResult:
    """Linear shock P&L: sum over factors of exposure x shock (factors not shocked move 0)."""
    results = []
    for name, shocks in scenarios.items():
        pnl = sum(float(exposures.get(factor, 0.0)) * float(shock) for factor, shock in shocks.items())
        frac = pnl / equity
        results.append(
            ScenarioPnl(scenario=name, pnl=pnl, pnl_fraction=frac, breaches_limit=-frac > loss_limit_fraction)
        )
    worst = min(results, key=lambda r: r.pnl)
    return StressResult(
        results=tuple(results), worst_scenario=worst.scenario, worst_pnl_fraction=worst.pnl_fraction
    )


class ExposureBreakdown(ToolOutput):
    gross: float
    net: float
    long: float
    short: float
    beta_adjusted_net: float
    by_group: dict[str, float]  # net exposure per sector/group
    risk_contributions: tuple[float, ...] | None  # fraction of portfolio variance per position
    portfolio_vol: float | None


@tool(
    "risk.exposure_decomposition",
    "1.0.0",
    example={
        "weights": [0.3, -0.1, 0.2],
        "groups": ["IT", "BANK", "IT"],
        "betas": [0.9, 1.2, 1.0],
        "covariance": [[0.0002, 0.00005, 0.0001], [0.00005, 0.0003, 0.00005], [0.0001, 0.00005, 0.00025]],
    },
)
def exposure_decomposition(
    weights: Annotated[Sequence[Finite], Field(min_length=1)],
    groups: Annotated[Sequence[str], Field(min_length=1)],
    betas: Annotated[Sequence[Finite], Field(min_length=1)] | None = None,
    covariance: Matrix | None = None,
) -> ExposureBreakdown:
    n = same_length(weights, groups, *([betas] if betas is not None else []))
    w = arr(weights)
    by_group: dict[str, float] = {}
    for group, weight in zip(groups, w, strict=True):
        by_group[group] = by_group.get(group, 0.0) + float(weight)
    b = arr(betas) if betas is not None else np.ones(n)
    contributions, vol = None, None
    if covariance is not None:
        cov = mat(covariance)
        if cov.shape != (n, n):
            raise ValueError("covariance dimension mismatch")
        variance = float(w @ cov @ w)
        if variance > 0:
            contributions = tuple(float(v) for v in w * (cov @ w) / variance)
            vol = float(np.sqrt(variance))
    return ExposureBreakdown(
        gross=float(np.abs(w).sum()),
        net=float(w.sum()),
        long=float(w[w > 0].sum()),
        short=float(-w[w < 0].sum()),
        beta_adjusted_net=float((w * b).sum()),
        by_group=by_group,
        risk_contributions=contributions,
        portfolio_vol=vol,
    )
