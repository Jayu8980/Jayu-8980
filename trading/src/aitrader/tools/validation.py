"""Validation tools: probabilistic and deflated Sharpe ratios, Monte Carlo trade resampling
and a performance summary over an equity curve.

Sharpe ratios here are per-period (not annualised) unless stated, as the formulas require.
References: Bailey & Lopez de Prado, "The Sharpe Ratio Efficient Frontier" (2012) and
"The Deflated Sharpe Ratio" (2014).
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from typing import Annotated

import numpy as np
from pydantic import Field
from scipy import stats as sps

from aitrader.tools.registry import tool
from aitrader.tools.types import Finite, Positive, Series, ToolOutput, arr

EULER_GAMMA = 0.5772156649015329
_RNG = np.random.default_rng(17)
_EX_RET = [float(v) for v in _RNG.normal(0.0006, 0.01, 500)]
_EX_TRADES = [float(v) for v in _RNG.normal(0.004, 0.02, 80)]


class SharpeTest(ToolOutput):
    sharpe: float  # per period
    benchmark_sharpe: float  # per period
    probability: float  # P(true Sharpe > benchmark)
    n_obs: int
    skewness: float
    kurtosis: float  # raw (normal = 3)


def _psr(sr: float, sr_star: float, n: int, skew: float, kurt: float) -> float:
    denom = 1 - skew * sr + (kurt - 1) / 4 * sr * sr
    if denom <= 0:
        raise ValueError("non-positive variance term; returns too extreme for the PSR approximation")
    return float(sps.norm.cdf((sr - sr_star) * math.sqrt(n - 1) / math.sqrt(denom)))


def _moments(r: np.ndarray) -> tuple[float, float, float]:
    sd = float(r.std(ddof=1))
    if sd == 0:
        raise ValueError("returns have zero variance")
    return float(r.mean()) / sd, float(sps.skew(r)), float(sps.kurtosis(r, fisher=False))


@tool("validation.probabilistic_sharpe", "1.0.0", example={"returns": _EX_RET, "benchmark_sharpe": 0.0})
def probabilistic_sharpe(
    returns: Annotated[Series, Field(min_length=20)], benchmark_sharpe: Finite = 0.0
) -> SharpeTest:
    r = arr(returns)
    sr, skew, kurt = _moments(r)
    return SharpeTest(
        sharpe=sr,
        benchmark_sharpe=benchmark_sharpe,
        probability=_psr(sr, benchmark_sharpe, len(r), skew, kurt),
        n_obs=len(r),
        skewness=skew,
        kurtosis=kurt,
    )


@tool(
    "validation.deflated_sharpe",
    "1.0.0",
    example={"returns": _EX_RET, "n_trials": 20, "trial_sharpe_variance": 0.0004},
)
def deflated_sharpe(
    returns: Annotated[Series, Field(min_length=20)],
    n_trials: Annotated[int, Field(ge=1)],
    trial_sharpe_variance: Annotated[float, Field(ge=0)],
) -> SharpeTest:
    """PSR against the Sharpe ratio expected from the best of ``n_trials`` unskilled trials.
    ``trial_sharpe_variance`` is the variance of per-period Sharpe ratios across all trials."""
    r = arr(returns)
    sr, skew, kurt = _moments(r)
    if n_trials == 1:
        sr0 = 0.0
    else:
        z1 = sps.norm.ppf(1 - 1 / n_trials)
        z2 = sps.norm.ppf(1 - 1 / (n_trials * math.e))
        sr0 = math.sqrt(trial_sharpe_variance) * ((1 - EULER_GAMMA) * z1 + EULER_GAMMA * z2)
    return SharpeTest(
        sharpe=sr,
        benchmark_sharpe=float(sr0),
        probability=_psr(sr, float(sr0), len(r), skew, kurt),
        n_obs=len(r),
        skewness=skew,
        kurtosis=kurt,
    )


class Resampling(ToolOutput):
    n_simulations: int
    final_return_p05: float
    final_return_p50: float
    final_return_p95: float
    max_drawdown_p50: float
    max_drawdown_p95: float
    probability_of_loss: float


@tool("validation.trade_resampling", "1.0.0", example={"trade_returns": _EX_TRADES, "seed": 3})
def trade_resampling(
    trade_returns: Annotated[
        Sequence[Annotated[float, Field(gt=-1.0, allow_inf_nan=False)]], Field(min_length=5)
    ],
    seed: int,
    n_simulations: Annotated[int, Field(ge=100, le=100_000)] = 5000,
) -> Resampling:
    """Bootstrap the trade sequence (with replacement, same length) and compound it, to see how
    much of the result depends on the order and luck of trades. Returns are fractions of equity."""
    r = arr(trade_returns)
    rng = np.random.default_rng(seed)
    draws = r[rng.integers(0, len(r), size=(n_simulations, len(r)))]
    curves = np.cumprod(1 + draws, axis=1)
    peaks = np.maximum.accumulate(np.concatenate([np.ones((n_simulations, 1)), curves], axis=1), axis=1)[
        :, 1:
    ]
    drawdowns = np.max(1 - curves / peaks, axis=1)
    finals = curves[:, -1] - 1
    return Resampling(
        n_simulations=n_simulations,
        final_return_p05=float(np.quantile(finals, 0.05)),
        final_return_p50=float(np.quantile(finals, 0.5)),
        final_return_p95=float(np.quantile(finals, 0.95)),
        max_drawdown_p50=float(np.quantile(drawdowns, 0.5)),
        max_drawdown_p95=float(np.quantile(drawdowns, 0.95)),
        probability_of_loss=float(np.mean(finals < 0)),
    )


class ReturnStats(ToolOutput):
    cagr: float
    annual_vol: float
    sharpe: float  # annualised
    sortino: float  # annualised
    max_drawdown: float
    calmar: float


def return_stats(
    equity: Sequence[float], periods_per_year: int = 252, risk_free_annual: float = 0.0
) -> ReturnStats:
    curve = arr(equity)
    if len(curve) < 2 or np.any(curve <= 0):
        raise ValueError("equity curve needs at least two positive points")
    rets = curve[1:] / curve[:-1] - 1
    excess = rets - risk_free_annual / periods_per_year
    years = len(rets) / periods_per_year
    cagr = float((curve[-1] / curve[0]) ** (1 / years) - 1)
    sd = float(excess.std(ddof=1)) if len(rets) > 1 else 0.0
    downside = excess[excess < 0]
    dsd = float(np.sqrt(np.mean(downside**2))) if len(downside) else 0.0
    mdd = float(np.max(1 - curve / np.maximum.accumulate(curve)))
    ann = math.sqrt(periods_per_year)
    return ReturnStats(
        cagr=cagr,
        annual_vol=sd * ann,
        sharpe=float(excess.mean()) / sd * ann if sd > 0 else 0.0,
        sortino=float(excess.mean()) / dsd * ann if dsd > 0 else 0.0,
        max_drawdown=mdd,
        calmar=cagr / mdd if mdd > 0 else 0.0,
    )


@tool("validation.return_stats", "1.0.0", example={"equity": [100, 101, 99.5, 102, 104, 103]})
def return_stats_tool(
    equity: Annotated[Sequence[Positive], Field(min_length=2)],
    periods_per_year: Annotated[int, Field(ge=1, le=100_000)] = 252,
    risk_free_annual: Annotated[float, Field(ge=-0.1, le=0.5)] = 0.0,
) -> ReturnStats:
    return return_stats(equity, periods_per_year, risk_free_annual)
