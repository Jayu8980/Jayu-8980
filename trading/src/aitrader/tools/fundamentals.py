"""Fundamental tools: Piotroski F-score, Beneish M-score and a two-stage FCFF DCF.

Statements must be point-in-time: the caller passes figures as filed and available at the
decision time (restated numbers carry their own, later, availability).
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from aitrader.tools.registry import tool
from aitrader.tools.types import Finite, Positive, ToolOutput


class Statement(BaseModel):
    """Annual figures in one currency unit."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    revenue: Positive
    cogs: Annotated[float, Field(ge=0)]
    sga: Annotated[float, Field(ge=0)] = 0.0
    net_income: Finite
    operating_cash_flow: Finite
    total_assets: Positive
    current_assets: Annotated[float, Field(ge=0)]
    current_liabilities: Annotated[float, Field(ge=0)]
    long_term_debt: Annotated[float, Field(ge=0)]
    receivables: Annotated[float, Field(ge=0)] = 0.0
    ppe_net: Annotated[float, Field(ge=0)] = 0.0
    securities: Annotated[float, Field(ge=0)] = 0.0
    depreciation: Annotated[float, Field(ge=0)] = 0.0
    shares_outstanding: Positive


_PRIOR = {
    "revenue": 1000,
    "cogs": 650,
    "sga": 120,
    "net_income": 60,
    "operating_cash_flow": 80,
    "total_assets": 1200,
    "current_assets": 500,
    "current_liabilities": 300,
    "long_term_debt": 300,
    "receivables": 150,
    "ppe_net": 400,
    "securities": 50,
    "depreciation": 40,
    "shares_outstanding": 100,
}
_CURRENT = {
    **_PRIOR,
    "revenue": 1150,
    "cogs": 720,
    "net_income": 85,
    "operating_cash_flow": 110,
    "total_assets": 1250,
    "current_assets": 560,
    "long_term_debt": 260,
    "receivables": 160,
    "sga": 130,
    "ppe_net": 420,
    "depreciation": 45,
}


class FScore(ToolOutput):
    score: int
    components: dict[str, bool]


@tool("fundamentals.piotroski", "1.0.0", example={"current": _CURRENT, "prior": _PRIOR})
def piotroski(current: Statement, prior: Statement) -> FScore:
    c, p = current, prior
    roa_c, roa_p = c.net_income / c.total_assets, p.net_income / p.total_assets
    lev_c, lev_p = c.long_term_debt / c.total_assets, p.long_term_debt / p.total_assets
    cr_c = c.current_assets / c.current_liabilities if c.current_liabilities else float("inf")
    cr_p = p.current_assets / p.current_liabilities if p.current_liabilities else float("inf")
    gm_c, gm_p = (c.revenue - c.cogs) / c.revenue, (p.revenue - p.cogs) / p.revenue
    at_c, at_p = c.revenue / c.total_assets, p.revenue / p.total_assets
    parts = {
        "positive_roa": roa_c > 0,
        "positive_cfo": c.operating_cash_flow > 0,
        "improving_roa": roa_c > roa_p,
        "accruals_cfo_exceeds_ni": c.operating_cash_flow / c.total_assets > roa_c,
        "lower_leverage": lev_c < lev_p,
        "higher_current_ratio": cr_c > cr_p,
        "no_dilution": c.shares_outstanding <= p.shares_outstanding,
        "higher_gross_margin": gm_c > gm_p,
        "higher_asset_turnover": at_c > at_p,
    }
    return FScore(score=sum(parts.values()), components=parts)


class MScore(ToolOutput):
    m_score: float
    likely_manipulator: bool  # above -1.78 (8-variable model threshold)
    indices: dict[str, float]


@tool("fundamentals.beneish", "1.0.0", example={"current": _CURRENT, "prior": _PRIOR})
def beneish(current: Statement, prior: Statement) -> MScore:
    c, p = current, prior

    def ratio(num: float, den: float) -> float:
        if den == 0:
            raise ValueError("division by zero in Beneish index; statement field missing")
        return num / den

    dsri = ratio(c.receivables / c.revenue, p.receivables / p.revenue)
    gmi = ratio((p.revenue - p.cogs) / p.revenue, (c.revenue - c.cogs) / c.revenue)
    aqi = ratio(
        1 - (c.current_assets + c.ppe_net + c.securities) / c.total_assets,
        1 - (p.current_assets + p.ppe_net + p.securities) / p.total_assets,
    )
    sgi = c.revenue / p.revenue
    depi = ratio(p.depreciation / (p.depreciation + p.ppe_net), c.depreciation / (c.depreciation + c.ppe_net))
    sgai = ratio(c.sga / c.revenue, p.sga / p.revenue)
    tata = (c.net_income - c.operating_cash_flow) / c.total_assets
    lvgi = ratio(
        (c.current_liabilities + c.long_term_debt) / c.total_assets,
        (p.current_liabilities + p.long_term_debt) / p.total_assets,
    )
    m = (
        -4.84
        + 0.920 * dsri
        + 0.528 * gmi
        + 0.404 * aqi
        + 0.892 * sgi
        + 0.115 * depi
        - 0.172 * sgai
        + 4.679 * tata
        - 0.327 * lvgi
    )
    indices = {
        "DSRI": dsri,
        "GMI": gmi,
        "AQI": aqi,
        "SGI": sgi,
        "DEPI": depi,
        "SGAI": sgai,
        "TATA": tata,
        "LVGI": lvgi,
    }
    return MScore(m_score=m, likely_manipulator=m > -1.78, indices=indices)


class DcfResult(ToolOutput):
    enterprise_value: float
    equity_value: float
    value_per_share: float
    pv_explicit: float
    pv_terminal: float
    terminal_share: float


@tool(
    "fundamentals.dcf",
    "1.0.0",
    example={
        "fcff": [100, 110, 121, 133, 146],
        "wacc": 0.12,
        "terminal_growth": 0.05,
        "net_debt": 200,
        "shares": 100,
    },
)
def dcf(
    fcff: Annotated[Sequence[Finite], Field(min_length=1, max_length=30)],
    wacc: Annotated[float, Field(gt=0, lt=1)],
    terminal_growth: Annotated[float, Field(gt=-0.05, lt=0.2)],
    net_debt: Finite,
    shares: Positive,
) -> DcfResult:
    """Explicit FCFF forecasts (year 1..N, end-of-year discounting) plus Gordon terminal value."""
    if terminal_growth >= wacc:
        raise ValueError("terminal growth must be below WACC")
    pv = sum(cf / (1 + wacc) ** (t + 1) for t, cf in enumerate(fcff))
    n = len(fcff)
    terminal = fcff[-1] * (1 + terminal_growth) / (wacc - terminal_growth)
    pv_terminal = terminal / (1 + wacc) ** n
    ev = pv + pv_terminal
    equity = ev - net_debt
    return DcfResult(
        enterprise_value=ev,
        equity_value=equity,
        value_per_share=equity / shares,
        pv_explicit=pv,
        pv_terminal=pv_terminal,
        terminal_share=pv_terminal / ev if ev else 0.0,
    )
