"""Portfolio construction tools: risk parity, hierarchical risk parity, constrained
mean-variance and Black-Litterman. All return weights summing to 1 (long-only) unless noted.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Annotated

import numpy as np
from numpy.typing import NDArray
from pydantic import Field
from scipy.cluster.hierarchy import leaves_list, linkage
from scipy.optimize import minimize
from scipy.spatial.distance import squareform

from aitrader.tools.registry import tool
from aitrader.tools.types import Finite, Matrix, Positive, ToolOutput, Unit, arr, mat

_COV = [
    [0.040, 0.012, 0.010, 0.002],
    [0.012, 0.090, 0.015, 0.003],
    [0.010, 0.015, 0.060, 0.004],
    [0.002, 0.003, 0.004, 0.010],
]


class Weights(ToolOutput):
    weights: tuple[float, ...]
    risk_contributions: tuple[float, ...]  # fraction of portfolio variance
    expected_vol: float


def _check_cov(cov: NDArray[np.float64]) -> None:
    if cov.shape[0] != cov.shape[1]:
        raise ValueError("covariance must be square")
    if not np.allclose(cov, cov.T, atol=1e-12):
        raise ValueError("covariance must be symmetric")
    if np.min(np.linalg.eigvalsh(cov)) <= 0:
        raise ValueError("covariance must be positive definite")


def _weights(w: NDArray[np.float64], cov: NDArray[np.float64]) -> Weights:
    var = float(w @ cov @ w)
    rc = w * (cov @ w) / var
    return Weights(
        weights=tuple(w.tolist()), risk_contributions=tuple(rc.tolist()), expected_vol=float(np.sqrt(var))
    )


@tool("portfolio.risk_parity", "1.0.0", example={"covariance": _COV})
def risk_parity(covariance: Matrix, risk_budget: Sequence[Positive] | None = None) -> Weights:
    """Equal (or budgeted) risk contributions via the convex log-barrier formulation."""
    cov = mat(covariance)
    _check_cov(cov)
    n = len(cov)
    b = arr(risk_budget) if risk_budget is not None else np.full(n, 1.0 / n)
    if len(b) != n:
        raise ValueError("risk budget length mismatch")
    b = b / b.sum()

    def objective(y: NDArray[np.float64]) -> float:
        return float(0.5 * y @ cov @ y - b @ np.log(y))

    def gradient(y: NDArray[np.float64]) -> NDArray[np.float64]:
        return np.asarray(cov @ y - b / y)

    x0 = 1 / np.sqrt(np.diag(cov))
    res = minimize(
        objective,
        x0,
        jac=gradient,
        bounds=[(1e-12, None)] * n,
        method="L-BFGS-B",
        options={"ftol": 1e-15, "gtol": 1e-12, "maxiter": 10_000},
    )
    if not res.success:
        raise ArithmeticError(f"risk parity did not converge: {res.message}")
    w = res.x / res.x.sum()
    return _weights(w, cov)


@tool("portfolio.hrp", "1.0.0", example={"covariance": _COV})
def hrp(covariance: Matrix) -> Weights:
    """Hierarchical risk parity (Lopez de Prado, 2016): single-linkage on correlation
    distance, quasi-diagonalisation, recursive inverse-variance bisection."""
    cov = mat(covariance)
    _check_cov(cov)
    std = np.sqrt(np.diag(cov))
    corr = np.clip(cov / np.outer(std, std), -1, 1)
    dist = np.sqrt(np.clip((1 - corr) / 2, 0, None))
    np.fill_diagonal(dist, 0)
    order = list(leaves_list(linkage(squareform(dist, checks=False), method="single")))
    w = np.ones(len(cov))
    clusters = [order]
    while clusters:
        clusters = [
            c[i:j] for c in clusters for i, j in ((0, len(c) // 2), (len(c) // 2, len(c))) if len(c) > 1
        ]
        for k in range(0, len(clusters), 2):
            left, right = clusters[k], clusters[k + 1]

            def cluster_var(idx: list[int]) -> float:
                sub = cov[np.ix_(idx, idx)]
                ivp = 1 / np.diag(sub)
                ivp /= ivp.sum()
                return float(ivp @ sub @ ivp)

            vl, vr = cluster_var(left), cluster_var(right)
            alpha = 1 - vl / (vl + vr)
            w[left] *= alpha
            w[right] *= 1 - alpha
    return _weights(w / w.sum(), cov)


@tool(
    "portfolio.mean_variance",
    "1.0.0",
    example={
        "expected_returns": [0.12, 0.15, 0.10, 0.06],
        "covariance": _COV,
        "risk_aversion": 3.0,
        "max_weight": 0.5,
        "groups": ["IT", "BANK", "IT", "FMCG"],
        "group_caps": {"IT": 0.6},
    },
)
def mean_variance(
    expected_returns: Annotated[Sequence[Finite], Field(min_length=2)],
    covariance: Matrix,
    risk_aversion: Positive = 3.0,
    max_weight: Unit = 1.0,
    groups: Sequence[str] | None = None,
    group_caps: Mapping[str, Unit] | None = None,
    current_weights: Sequence[Finite] | None = None,
    max_turnover: Positive | None = None,
) -> Weights:
    """Long-only, fully invested: maximise mu'w - (lambda/2) w'Sw subject to per-name caps,
    group (sector) caps and an optional turnover limit sum|w - w0| <= max_turnover."""
    mu, cov = arr(expected_returns), mat(covariance)
    _check_cov(cov)
    n = len(mu)
    if cov.shape != (n, n):
        raise ValueError("dimension mismatch")
    if max_weight * n < 1 - 1e-9:
        raise ValueError("max_weight too small to be fully invested")
    group_masks: list[tuple[NDArray[np.float64], float]] = []
    if group_caps:
        if groups is None or len(groups) != n:
            raise ValueError("group caps need one group label per asset")
        group_masks = [
            (np.array([g == name for g in groups], dtype=float), cap) for name, cap in group_caps.items()
        ]

    def utility(w: NDArray[np.float64]) -> float:
        return float(-(mu @ w) + 0.5 * risk_aversion * (w @ cov @ w))

    if max_turnover is None:
        constraints: list[dict[str, object]] = [{"type": "eq", "fun": lambda w: w.sum() - 1.0}]
        constraints += [{"type": "ineq", "fun": lambda w, m=m, c=c: c - m @ w} for m, c in group_masks]
        res = minimize(
            utility,
            np.full(n, 1.0 / n),
            bounds=[(0.0, max_weight)] * n,
            constraints=constraints,
            method="SLSQP",
            options={"ftol": 1e-12, "maxiter": 1000},
        )
        x = res.x
    else:
        # Smooth reformulation of sum|w - w0| <= T: w = w0 + buys - sells, buys, sells >= 0.
        if current_weights is None:
            raise ValueError("turnover limit needs current weights")
        w0 = arr(current_weights)
        if len(w0) != n:
            raise ValueError("current weights length mismatch")

        def weights_of(z: NDArray[np.float64]) -> NDArray[np.float64]:
            return np.asarray(w0 + z[:n] - z[n:])

        constraints = [
            {"type": "eq", "fun": lambda z: weights_of(z).sum() - 1.0},
            {"type": "ineq", "fun": lambda z: max_turnover - z.sum()},
            {"type": "ineq", "fun": lambda z: weights_of(z)},
            {"type": "ineq", "fun": lambda z: max_weight - weights_of(z)},
        ]
        constraints += [
            {"type": "ineq", "fun": lambda z, m=m, c=c: c - m @ weights_of(z)} for m, c in group_masks
        ]
        res = minimize(
            lambda z: utility(weights_of(z)),
            np.zeros(2 * n),
            bounds=[(0.0, None)] * (2 * n),
            constraints=constraints,
            method="SLSQP",
            options={"ftol": 1e-12, "maxiter": 1000},
        )
        x = weights_of(res.x)
    if not res.success:
        raise ArithmeticError(f"optimiser failed: {res.message}")
    w = np.clip(x, 0, None)
    return _weights(w / w.sum(), cov)


class BlackLittermanResult(ToolOutput):
    equilibrium_returns: tuple[float, ...]
    posterior_returns: tuple[float, ...]
    posterior_covariance: tuple[tuple[float, ...], ...]


@tool(
    "portfolio.black_litterman",
    "1.0.0",
    example={
        "covariance": _COV,
        "market_weights": [0.3, 0.3, 0.2, 0.2],
        "risk_aversion": 2.5,
        "view_matrix": [[1, -1, 0, 0]],
        "view_returns": [0.02],
        "view_confidences": [0.5],
    },
)
def black_litterman(
    covariance: Matrix,
    market_weights: Annotated[Sequence[Finite], Field(min_length=2)],
    risk_aversion: Positive = 2.5,
    tau: Annotated[float, Field(gt=0, le=1)] = 0.05,
    view_matrix: Matrix | None = None,
    view_returns: Sequence[Finite] | None = None,
    view_confidences: Sequence[Annotated[float, Field(gt=0, lt=1)]] | None = None,
) -> BlackLittermanResult:
    """He-Litterman posterior; view uncertainty from Idzorek-style confidences:
    omega_k = (1 - c_k) / c_k * p_k' (tau S) p_k."""
    cov, wm = mat(covariance), arr(market_weights)
    _check_cov(cov)
    pi = risk_aversion * cov @ wm
    if view_matrix is None:
        return BlackLittermanResult(
            equilibrium_returns=tuple(pi.tolist()),
            posterior_returns=tuple(pi.tolist()),
            posterior_covariance=tuple(tuple(r) for r in (cov * (1 + tau)).tolist()),
        )
    p = mat(view_matrix)
    if view_returns is None or view_confidences is None:
        raise ValueError("views need returns and confidences")
    q, conf = arr(view_returns), arr(view_confidences)
    if p.shape[1] != len(wm) or not (len(q) == len(conf) == p.shape[0]):
        raise ValueError("view dimensions mismatch")
    ts = tau * cov
    omega = np.diag([(1 - c) / c * float(row @ ts @ row) for row, c in zip(p, conf, strict=True)])
    middle = np.linalg.inv(np.linalg.inv(ts) + p.T @ np.linalg.inv(omega) @ p)
    posterior = middle @ (np.linalg.inv(ts) @ pi + p.T @ np.linalg.inv(omega) @ q)
    return BlackLittermanResult(
        equilibrium_returns=tuple(pi.tolist()),
        posterior_returns=tuple(posterior.tolist()),
        posterior_covariance=tuple(tuple(r) for r in (cov + middle).tolist()),
    )
