import math
from typing import Any, Literal

import pytest
from pydantic import ValidationError

from aitrader.tools.derivatives import (
    Leg,
    barrier_hit_probability,
    binomial,
    black_scholes,
    chain_levels,
    dealer_gamma_exposure,
    implied_vol,
    iv_percentile,
    payoff,
    prob_above,
    smile_metrics,
    term_structure,
)

BASE: dict[str, Any] = {"spot": 100.0, "strike": 100.0, "t_years": 1.0, "rate": 0.05, "vol": 0.2}


def test_black_scholes_reference_values() -> None:
    call = black_scholes(**BASE, option_type="CE")
    put = black_scholes(**BASE, option_type="PE")
    assert call.price == pytest.approx(10.4506, abs=1e-4)
    assert put.price == pytest.approx(5.5735, abs=1e-4)
    assert call.delta == pytest.approx(0.6368, abs=1e-4)
    assert call.gamma == pytest.approx(put.gamma)
    assert call.vega == pytest.approx(0.3752, abs=1e-4)  # per vol point


def test_put_call_parity() -> None:
    call = black_scholes(**BASE, option_type="CE").price
    put = black_scholes(**BASE, option_type="PE").price
    assert call - put == pytest.approx(100 - 100 * math.exp(-0.05), abs=1e-10)


@pytest.mark.parametrize("kind", ["CE", "PE"])
@pytest.mark.parametrize("vol", [0.08, 0.2, 0.6])
def test_implied_vol_round_trip(kind: Literal["CE", "PE"], vol: float) -> None:
    price = black_scholes(spot=100, strike=110, t_years=0.25, rate=0.06, vol=vol, option_type=kind).price
    iv = implied_vol(price=price, spot=100, strike=110, t_years=0.25, rate=0.06, option_type=kind)
    assert iv.implied_vol == pytest.approx(vol, abs=1e-8)


def test_implied_vol_rejects_arbitrage_prices() -> None:
    with pytest.raises(ValueError, match="no-arbitrage"):
        implied_vol(price=150.0, spot=100, strike=100, t_years=1, rate=0.05, option_type="CE")


def test_binomial_converges_and_values_early_exercise() -> None:
    euro = binomial(**BASE, option_type="CE", american=False, steps=2000)
    assert euro.price == pytest.approx(10.4506, abs=5e-3)
    amer_call = binomial(**BASE, option_type="CE", american=True, steps=500)
    assert amer_call.early_exercise_premium == pytest.approx(0.0, abs=1e-9)  # no dividends
    amer_put = binomial(**BASE, option_type="PE", american=True, steps=500)
    assert amer_put.early_exercise_premium > 0.1


def test_iv_percentile_and_smile_and_term_structure() -> None:
    assert iv_percentile(history=[0.1, 0.2, 0.3, 0.4, 0.5], current=0.35).percentile == pytest.approx(0.6)
    smile = smile_metrics(strikes=[90, 95, 100, 105, 110], ivs=[0.25, 0.22, 0.2, 0.19, 0.185], spot=100)
    assert smile.atm_iv == pytest.approx(0.2) and smile.skew == pytest.approx(0.03)
    assert term_structure(t_years=[0.02, 0.25], atm_ivs=[0.3, 0.2]).inverted


def test_chain_levels_max_pain_and_walls() -> None:
    levels = chain_levels(strikes=[90, 100, 110], call_oi=[0, 10, 100], put_oi=[100, 10, 0])
    assert levels.max_pain == 100
    assert levels.call_wall == 110 and levels.put_wall == 90
    assert levels.pcr_oi == pytest.approx(1.0)


def test_dealer_gamma_sign_follows_positioning() -> None:
    args: dict[str, Any] = {
        "strikes": [24900, 25000, 25100],
        "ivs": [0.14, 0.14, 0.14],
        "spot": 25000,
        "t_years": 0.02,
        "rate": 0.065,
        "lot_size": 75,
    }
    long_gamma = dealer_gamma_exposure(call_oi=[100, 500, 100], put_oi=[0, 0, 0], **args)
    short_gamma = dealer_gamma_exposure(call_oi=[0, 0, 0], put_oi=[100, 500, 100], **args)
    assert long_gamma.total_gex_per_1pct > 0 > short_gamma.total_gex_per_1pct


def test_bull_call_spread_payoff() -> None:
    legs = [
        Leg(kind="CE", strike=100, entry_price=5, quantity=1),
        Leg(kind="CE", strike=110, entry_price=2, quantity=-1),
    ]
    profile = payoff(legs=legs, spot_low=80, spot_high=130, points=501)
    assert profile.max_loss == pytest.approx(-3)
    assert profile.max_profit == pytest.approx(7)
    assert profile.breakevens == pytest.approx((103.0,))
    assert not profile.unbounded_profit and not profile.unbounded_loss


def test_naked_short_call_is_flagged_unbounded() -> None:
    profile = payoff(
        legs=[Leg(kind="CE", strike=100, entry_price=5, quantity=-1)], spot_low=50, spot_high=150
    )
    assert profile.unbounded_loss


def test_zero_quantity_leg_is_rejected() -> None:
    with pytest.raises(ValidationError, match="non-zero"):
        Leg(kind="CE", strike=100, entry_price=5, quantity=0)


def test_barrier_probability_driftless_log_space() -> None:
    vol = 0.3
    p = barrier_hit_probability(spot=100, target=120, stop=90, annual_vol=vol, annual_drift=0.5 * vol**2)
    a, b = math.log(1.2), -math.log(0.9)
    assert p.p_target_first == pytest.approx(b / (a + b))
    short = barrier_hit_probability(spot=100, target=90, stop=120, annual_vol=vol, annual_drift=0.5 * vol**2)
    assert short.p_target_first == pytest.approx(1 - p.p_target_first)
    with pytest.raises(ValueError, match="between"):
        barrier_hit_probability(spot=100, target=95, stop=90, annual_vol=vol)


def test_prob_above_is_n_d2() -> None:
    p = prob_above(**BASE, option_type="CE").probability
    d2 = (math.log(1) + (0.05 - 0.02) * 1) / 0.2
    from scipy.stats import norm

    assert p == pytest.approx(norm.cdf(d2))
