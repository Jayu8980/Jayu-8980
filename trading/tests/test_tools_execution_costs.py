from datetime import date
from typing import Any

import pytest

from aitrader.tools.costs import india_costs, round_trip, schedule_for
from aitrader.tools.microstructure import (
    almgren_chriss,
    impact_sqrt,
    liquidation,
    spread_depth,
    tca,
    twap,
    vwap,
)


def test_equity_delivery_buy_costs_by_hand() -> None:
    c = india_costs(
        segment="equity_delivery", side="BUY", price=1500, quantity=100, trade_date=date(2026, 10, 21)
    )
    assert c.turnover == 150_000
    assert c.brokerage == 0
    assert c.stt == pytest.approx(150.0)
    assert c.exchange_txn == pytest.approx(4.455)
    assert c.sebi_fee == pytest.approx(0.15)
    assert c.stamp_duty == pytest.approx(22.5)
    assert c.gst == pytest.approx((4.455 + 0.15) * 0.18)
    assert c.total == pytest.approx(150 + 4.455 + 0.15 + 22.5 + (4.455 + 0.15) * 0.18)


def test_fno_stt_follows_the_dated_schedule() -> None:
    def stt(d: date) -> float:
        return india_costs(segment="options", side="SELL", price=100, quantity=750, trade_date=d).stt

    assert stt(date(2024, 9, 30)) == pytest.approx(75_000 * 0.000625)
    assert stt(date(2024, 10, 1)) == pytest.approx(75_000 * 0.001)
    assert stt(date(2026, 4, 1)) == pytest.approx(75_000 * 0.0015)
    fut = india_costs(
        segment="futures", side="SELL", price=25_000, quantity=75, trade_date=date(2026, 10, 21)
    )
    assert fut.stt == pytest.approx(25_000 * 75 * 0.0005)
    assert fut.brokerage == 20.0  # 0.03% capped at Rs 20


def test_buy_side_options_pay_no_stt_but_stamp() -> None:
    c = india_costs(segment="options", side="BUY", price=100, quantity=75, trade_date=date(2026, 10, 21))
    assert c.stt == 0 and c.stamp_duty > 0 and c.brokerage == 20.0


def test_dates_before_first_schedule_refuse_to_guess() -> None:
    with pytest.raises(ValueError, match="no cost schedule"):
        schedule_for(date(2020, 1, 1))


def test_round_trip_and_cash_short_rule() -> None:
    rt = round_trip(
        segment="equity_delivery",
        direction="LONG",
        entry_price=100,
        exit_price=110,
        quantity=1000,
        entry_date=date(2026, 10, 1),
        exit_date=date(2026, 10, 20),
        slippage_bps_each_side=5,
    )
    assert rt.slippage == pytest.approx((100 + 110) * 1000 * 0.0005)
    assert rt.per_unit == pytest.approx(rt.total / 1000)
    with pytest.raises(ValueError, match="intraday only"):
        round_trip(
            segment="equity_delivery",
            direction="SHORT",
            entry_price=100,
            exit_price=90,
            quantity=10,
            entry_date=date(2026, 10, 1),
            exit_date=date(2026, 10, 2),
        )


def test_square_root_impact() -> None:
    res = impact_sqrt(quantity=20_000, adv=2_000_000, daily_vol=0.02, spread_bps=4)
    assert res.impact_bps == pytest.approx(20.0)
    assert res.total_bps == pytest.approx(22.0)


def test_almgren_chriss_limits() -> None:
    args: dict[str, Any] = {
        "total_quantity": 1000,
        "n_intervals": 10,
        "interval_days": 0.1,
        "price_vol_per_day": 1.0,
        "temporary_impact": 1e-3,
        "permanent_impact": 1e-5,
    }
    neutral = almgren_chriss(**args, risk_aversion=0)
    assert neutral.trades == pytest.approx((100.0,) * 10)
    averse = almgren_chriss(**args, risk_aversion=1e-3)
    assert averse.holdings[0] == 1000 and averse.holdings[-1] == pytest.approx(0, abs=1e-9)
    assert averse.trades[0] > averse.trades[-1]  # front-loaded
    assert averse.cost_variance < neutral.cost_variance and averse.expected_cost > neutral.expected_cost


def test_book_benchmarks_tca_and_liquidation() -> None:
    book = spread_depth(
        bid_prices=[99.9, 99.8],
        bid_quantities=[300, 500],
        ask_prices=[100.1, 100.2],
        ask_quantities=[100, 100],
    )
    assert book.mid == pytest.approx(100.0) and book.spread_bps == pytest.approx(20.0)
    assert book.imbalance > 0
    with pytest.raises(ValueError, match="crossed"):
        spread_depth(bid_prices=[100.2], bid_quantities=[1], ask_prices=[100.1], ask_quantities=[1])
    assert vwap(prices=[100, 102], volumes=[3, 1]).value == pytest.approx(100.5)
    assert twap(prices=[100, 102]).value == pytest.approx(101)
    buy = tca(side="BUY", fill_prices=[100.1], fill_quantities=[10], arrival_price=100, estimated_cost_bps=4)
    sell = tca(side="SELL", fill_prices=[99.9], fill_quantities=[10], arrival_price=100)
    assert buy.slippage_vs_arrival_bps == pytest.approx(10) and sell.slippage_vs_arrival_bps == pytest.approx(
        10
    )
    assert buy.estimation_error_bps == pytest.approx(6)
    liq = liquidation(position_quantity=300_000, adv=1_000_000, max_participation=0.1, max_days=1)
    assert liq.days_to_liquidate == pytest.approx(3) and liq.exceeds_limit
