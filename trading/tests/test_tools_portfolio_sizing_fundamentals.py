import numpy as np
import pytest

from aitrader.tools.fundamentals import Statement, beneish, dcf, piotroski
from aitrader.tools.portfolio import black_litterman, hrp, mean_variance, risk_parity
from aitrader.tools.sizing import fixed_fractional, kelly, vol_target

COV = [
    [0.040, 0.012, 0.010, 0.002],
    [0.012, 0.090, 0.015, 0.003],
    [0.010, 0.015, 0.060, 0.004],
    [0.002, 0.003, 0.004, 0.010],
]


def test_risk_parity_equalises_contributions() -> None:
    res = risk_parity(covariance=COV)
    assert sum(res.weights) == pytest.approx(1.0)
    assert res.risk_contributions == pytest.approx((0.25,) * 4, abs=1e-5)
    budgeted = risk_parity(covariance=COV, risk_budget=[0.4, 0.2, 0.2, 0.2])
    assert budgeted.risk_contributions[0] == pytest.approx(0.4, abs=1e-5)


def test_hrp_weights_are_valid_and_favour_low_vol() -> None:
    res = hrp(covariance=COV)
    w = np.array(res.weights)
    assert w.sum() == pytest.approx(1.0) and (w > 0).all()
    assert w[3] == w.max()  # lowest-variance asset gets the most weight


def test_mean_variance_respects_caps() -> None:
    res = mean_variance(
        expected_returns=[0.12, 0.20, 0.10, 0.06],
        covariance=COV,
        risk_aversion=1.0,
        max_weight=0.4,
        groups=["IT", "BANK", "IT", "FMCG"],
        group_caps={"BANK": 0.3},
    )
    w = res.weights
    assert sum(w) == pytest.approx(1.0) and max(w) <= 0.4 + 1e-6 and w[1] <= 0.3 + 1e-6
    with pytest.raises(ValueError, match="fully invested"):
        mean_variance(expected_returns=[0.1] * 4, covariance=COV, max_weight=0.2)


def test_mean_variance_turnover_limit() -> None:
    current = [0.25, 0.25, 0.25, 0.25]
    res = mean_variance(
        expected_returns=[0.3, 0.0, 0.0, 0.0],
        covariance=COV,
        risk_aversion=0.5,
        current_weights=current,
        max_turnover=0.2,
    )
    assert np.abs(np.array(res.weights) - current).sum() <= 0.2 + 1e-6


def test_black_litterman() -> None:
    wm = [0.3, 0.3, 0.2, 0.2]
    base = black_litterman(covariance=COV, market_weights=wm)
    assert base.posterior_returns == pytest.approx(base.equilibrium_returns)
    viewed = black_litterman(
        covariance=COV,
        market_weights=wm,
        view_matrix=[[1, -1, 0, 0]],
        view_returns=[0.05],
        view_confidences=[0.9],
    )
    spread_before = base.equilibrium_returns[0] - base.equilibrium_returns[1]
    spread_after = viewed.posterior_returns[0] - viewed.posterior_returns[1]
    assert spread_after > spread_before
    assert spread_after < 0.05  # pulled toward, not onto, the view


def test_fixed_fractional_rounds_down_to_lots() -> None:
    res = fixed_fractional(equity=1_000_000, risk_fraction=0.01, entry=1500, stop=1440)
    assert res.quantity == 166 and res.risk_amount <= 10_000
    lots = fixed_fractional(equity=1_000_000, risk_fraction=0.01, entry=1500, stop=1440, lot_size=25)
    assert lots.quantity == 150
    capped = fixed_fractional(
        equity=1_000_000, risk_fraction=0.01, entry=1500, stop=1440, max_position_value=60_000
    )
    assert capped.quantity == 40 and capped.binding_constraint == "position_value"
    with_costs = fixed_fractional(
        equity=1_000_000, risk_fraction=0.01, entry=1500, stop=1440, cost_per_unit=40
    )
    assert with_costs.quantity == 100


def test_vol_target_and_kelly() -> None:
    assert vol_target(equity=1e6, target_annual_vol=0.01, asset_annual_vol=0.25, price=400).quantity == 100
    k = kelly(p_win=0.5, win_loss_ratio=2.0, fraction=0.25, cap=0.02)
    assert k.full_kelly == pytest.approx(0.25) and k.applied_fraction == pytest.approx(0.02)
    assert kelly(p_win=0.3, win_loss_ratio=1.0).applied_fraction == 0.0


BASE = {
    "revenue": 1000,
    "cogs": 600,
    "sga": 100,
    "net_income": 50,
    "operating_cash_flow": 50,
    "total_assets": 1000,
    "current_assets": 400,
    "current_liabilities": 200,
    "long_term_debt": 200,
    "receivables": 100,
    "ppe_net": 300,
    "securities": 0,
    "depreciation": 30,
    "shares_outstanding": 100,
}


def test_beneish_neutral_statements_score_constant() -> None:
    s = Statement(**BASE)
    res = beneish(current=s, prior=s)
    assert all(v == pytest.approx(1.0) for k, v in res.indices.items() if k != "TATA")
    assert res.m_score == pytest.approx(-4.84 + 0.92 + 0.528 + 0.404 + 0.892 + 0.115 - 0.172 - 0.327)
    assert not res.likely_manipulator


def test_piotroski_improving_company_scores_high() -> None:
    prior = Statement(**BASE)
    current = Statement(
        **{
            **BASE,
            "revenue": 1200,
            "cogs": 680,
            "net_income": 90,
            "operating_cash_flow": 120,
            "long_term_debt": 150,
            "current_assets": 480,
        }
    )
    res = piotroski(current=current, prior=prior)
    assert res.score == 9 and all(res.components.values())


def test_dcf_by_hand() -> None:
    res = dcf(fcff=[100, 100], wacc=0.10, terminal_growth=0.0, net_debt=100, shares=10)
    pv = 100 / 1.1 + 100 / 1.21
    tv = 1000 / 1.21
    assert res.enterprise_value == pytest.approx(pv + tv)
    assert res.value_per_share == pytest.approx((pv + tv - 100) / 10)
    with pytest.raises(ValueError, match="below WACC"):
        dcf(fcff=[100], wacc=0.05, terminal_growth=0.06, net_debt=0, shares=1)
