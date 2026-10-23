from datetime import timedelta
from typing import Any, ClassVar

import pytest

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import ist_date
from aitrader.agents.data.universe import Security, Universe, security_record
from aitrader.backtest.config import load_backtest_config
from aitrader.backtest.engine import BacktestEngine, DecisionContext
from aitrader.backtest.models import Intent, OrderIntent
from aitrader.backtest.strategies import BuyAndHold, DonchianBreakout, equity
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, Segment
from aitrader.schemas.market import CorporateAction, CorporateActionType
from aitrader.storage import PitStore
from aitrader.tools.costs import india_costs
from tests.backtest_helpers import Scripted, add_action, calendar, engine, flat, put, random_walk
from tests.data_helpers import NIFTY, eod

AAA = equity("AAA")
BBB = equity("BBB")


def buy(qty: int = 100, stop: float = 90.0, target: float | None = None, **kw: Any) -> Any:
    return lambda ctx: [
        OrderIntent(
            instrument=AAA, side="BUY", quantity=qty, intent=Intent.OPEN, stop=stop, target=target, **kw
        )
    ]


def setup(n: int = 40) -> tuple[PitStore, list[Any]]:
    store = PitStore()
    days = calendar(store, n)
    flat(store, AAA, days)
    return store, days


def test_orders_fill_at_next_open_never_at_the_signal_close() -> None:
    store, days = setup()
    put(store, AAA, days[30], 101, 103, 100, 102)  # distinctive fill-day bar
    result = engine(store).run(Scripted({days[29]: buy()}), days[25], days[35])
    [fill] = result.fills
    assert fill.day == days[30] and fill.reference_price == 101
    assert fill.price > 101 and fill.slippage_bps >= 2.0  # adverse slippage on a buy


def test_strategy_never_sees_unpublished_bars() -> None:
    store, days = setup()
    seen: list[tuple[Any, Any]] = []

    class Peeker:
        name = "peeker"
        params: ClassVar[dict[str, Any]] = {}

        def decide(self, ctx: DecisionContext) -> list[OrderIntent]:
            bars = ctx.view.bars(AAA, 500)
            seen.append((ctx.as_of, bars[-1].bar_end))
            return []

    engine(store).run(Peeker(), days[25], days[35])
    assert seen and all(last_bar <= as_of for as_of, last_bar in seen)
    assert all(
        ist_date(last_bar) == ist_date(as_of) for as_of, last_bar in seen
    )  # today's bar, nothing later


def test_results_are_identical_with_or_without_future_data() -> None:
    full, truncated = PitStore(), PitStore()
    days = calendar(full, 200)
    calendar(truncated, 150)
    for store, keep in ((full, None), (truncated, 150)):
        random_walk(store, AAA, days, seed=1, keep=keep)
        random_walk(store, BBB, days, seed=2, keep=keep)
    end = days[149]
    strategy_args: dict[str, Any] = {"instruments": [AAA, BBB], "entry_lookback": 20, "exit_lookback": 10}
    a = engine(full).run(DonchianBreakout(**strategy_args), days[40], end)
    b = engine(truncated).run(DonchianBreakout(**strategy_args), days[40], end)
    assert a.run_digest == b.run_digest and a.trades


def test_runs_are_deterministic() -> None:
    store = PitStore()
    days = calendar(store, 160)
    random_walk(store, AAA, days, seed=3)
    args: dict[str, Any] = {"instruments": [AAA], "entry_lookback": 20, "exit_lookback": 10}
    first = engine(store).run(DonchianBreakout(**args), days[40], days[-1])
    second = engine(store).run(DonchianBreakout(**args), days[40], days[-1])
    assert first.run_digest == second.run_digest


def test_costs_match_the_dated_schedule() -> None:
    store, days = setup()
    result = engine(store).run(Scripted({days[29]: buy()}), days[25], days[35])
    [fill] = result.fills
    expected = india_costs(
        segment="equity_delivery", side="BUY", price=fill.price, quantity=100, trade_date=fill.day
    )
    assert fill.costs == pytest.approx(expected.total)
    assert not result.approximate_costs_used


def test_accounting_identity() -> None:
    store = PitStore()
    days = calendar(store, 200)
    random_walk(store, AAA, days, seed=5)
    random_walk(store, BBB, days, seed=6)
    result = engine(store).run(
        DonchianBreakout([AAA, BBB], entry_lookback=20, exit_lookback=10, target_r=3.0),
        days[40],
        days[-1],
        liquidate_at_end=True,
    )
    s = result.summary
    assert result.trades and not result.open_positions
    assert s.final_equity == pytest.approx(
        s.initial_equity + sum(t.net_pnl for t in result.trades) + s.total_dividends
    )
    assert sum(t.costs for t in result.trades) == pytest.approx(s.total_costs)


def test_participation_cap_partially_fills() -> None:
    store, days = setup()
    put(store, AAA, days[30], 100, 101, 99, 100, v=5_000)
    result = engine(store).run(Scripted({days[29]: buy(qty=2_000)}), days[25], days[35])
    [fill] = result.fills
    assert fill.quantity == 500  # 10% of the fill-day volume
    assert any("partial fill 500/2000" in r.reason for r in result.rejections)


def test_stop_gap_fills_at_the_open_not_the_stop() -> None:
    store, days = setup()
    put(store, AAA, days[32], 85, 86, 84, 85.5)  # gaps below the 95 stop
    result = engine(store).run(Scripted({days[29]: buy(stop=95.0)}), days[25], days[35])
    exit_fill = result.fills[-1]
    assert exit_fill.reason == "stop" and exit_fill.reference_price == 85
    assert 84 <= exit_fill.price <= 85
    assert (
        result.trades[0].r_multiple is not None and result.trades[0].r_multiple < -1
    )  # gap made it worse than -1R


def test_bar_spanning_stop_and_target_assumes_the_stop() -> None:
    store, days = setup()
    put(store, AAA, days[31], 100, 120, 80, 100)
    result = engine(store).run(Scripted({days[29]: buy(stop=95.0, target=110.0)}), days[25], days[35])
    assert result.trades[0].exit_reason == "stop" and result.trades[0].exit_price <= 95


def test_target_fills_at_target() -> None:
    store, days = setup()
    put(store, AAA, days[31], 100.5, 112, 100, 111)
    result = engine(store).run(Scripted({days[29]: buy(stop=95.0, target=110.0)}), days[25], days[35])
    assert result.trades[0].exit_reason == "target" and result.trades[0].exit_price == pytest.approx(110)


def test_split_while_holding_preserves_equity() -> None:
    store = PitStore()
    days = calendar(store, 40)
    for i, d in enumerate(days):
        price = 100.0 if i < 33 else 50.0  # 2:1 split effective on days[33]
        put(store, AAA, d, price, price * 1.002, price * 0.998, price)
    add_action(
        store,
        CorporateAction(
            instrument=AAA,
            action_type=CorporateActionType.SPLIT,
            announced_at=eod(days[28]) - timedelta(hours=2),
            ex_date=days[33],
            ratio_numerator=2,
            ratio_denominator=1,
        ),
    )
    result = engine(store).run(Scripted({days[29]: buy(stop=80.0)}), days[25], days[38])
    curve = {p.day: p.equity for p in result.equity_curve}
    assert curve[days[33]] == pytest.approx(curve[days[32]], rel=1e-9)
    assert result.open_positions == {AAA.key: 200}


def test_dividend_is_credited() -> None:
    store, days = setup()
    add_action(
        store,
        CorporateAction(
            instrument=AAA,
            action_type=CorporateActionType.DIVIDEND,
            announced_at=eod(days[20]),
            ex_date=days[33],
            cash_amount=5,
        ),
    )
    result = engine(store).run(Scripted({days[29]: buy(stop=80.0)}), days[25], days[38])
    assert result.summary.total_dividends == pytest.approx(500)


def test_delisted_position_is_closed_at_last_close() -> None:
    store, days = setup()
    store.write(
        [security_record(Security(symbol="AAA", listed_on=days[0], delisted_on=days[33]), eod(days[31]))],
        ingested_at=eod(days[31]),
    )
    eng = BacktestEngine(MarketData(store), load_backtest_config(), Universe(store))
    result = eng.run(Scripted({days[29]: buy(stop=80.0)}), days[25], days[38])
    assert result.trades[0].exit_reason == "delisted" and result.trades[0].exit_date == days[33]
    assert not result.open_positions


@pytest.mark.parametrize(
    ("intent", "reason"),
    [
        (
            OrderIntent(instrument=AAA, side="SELL", quantity=10, intent=Intent.OPEN, stop=110.0),
            "intraday only",
        ),
        (OrderIntent(instrument=AAA, side="SELL", quantity=10, intent=Intent.CLOSE), "does not match"),
        (
            OrderIntent(
                instrument=Instrument(
                    exchange=Exchange.NSE,
                    segment=Segment.INDEX,
                    symbol="NIFTY_50",
                    instrument_type=InstrumentType.INDEX,
                ),
                side="BUY",
                quantity=1,
                intent=Intent.OPEN,
                stop=1.0,
            ),
            "not tradable",
        ),
    ],
)
def test_invalid_orders_are_rejected(intent: OrderIntent, reason: str) -> None:
    store, days = setup()
    result = engine(store).run(Scripted({days[29]: lambda ctx: [intent]}), days[25], days[35])
    assert not result.fills and any(reason in r.reason for r in result.rejections)


def test_unreached_limit_is_cancelled() -> None:
    store, days = setup()
    result = engine(store).run(Scripted({days[29]: buy(limit_price=50.0)}), days[25], days[35])
    assert not result.fills and any("limit not reached" in r.reason for r in result.rejections)


def test_buys_are_scaled_to_available_cash() -> None:
    store, days = setup()
    result = engine(store).run(
        Scripted({days[29]: buy(qty=50_000)}), days[25], days[35], initial_capital=100_000
    )
    [fill] = result.fills
    assert fill.quantity * fill.price + fill.costs <= 100_000


def test_reference_strategies_produce_valid_summaries() -> None:
    store = PitStore()
    days = calendar(store, 300)
    for i, sym in enumerate("ABCDE"):
        random_walk(store, equity(sym * 3), days, seed=10 + i, drift=0.001)
    universe = [equity(s * 3) for s in "ABCDE"]
    hold = engine(store).run(BuyAndHold(universe), days[30], days[-1])
    assert hold.summary.avg_gross_exposure > 0.8 and len(hold.fills) == 5
    trend = engine(store).run(
        DonchianBreakout(universe, entry_lookback=30, exit_lookback=15), days[60], days[-1]
    )
    s = trend.summary
    assert s.trades > 0 and 0 <= s.max_drawdown < 1 and s.total_costs > 0
    assert NIFTY.key not in trend.open_positions
