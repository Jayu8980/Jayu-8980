import itertools
from datetime import date
from typing import Any

import pytest

from aitrader.backtest.engine import DecisionContext
from aitrader.backtest.models import Intent, OrderIntent
from aitrader.backtest.strategies import DonchianBreakout, equity
from aitrader.backtest.walkforward import make_folds, walk_forward
from aitrader.storage import PitStore
from tests.backtest_helpers import bar_record, calendar, engine, random_walk
from tests.data_helpers import eod, weekdays

AAA, BBB = equity("AAA"), equity("BBB")


def test_folds_are_ordered_disjoint_and_embargoed() -> None:
    sessions = weekdays(date(2024, 1, 1), 300)
    folds = make_folds(sessions, train_sessions=100, test_sessions=40, embargo_sessions=5)
    assert len(folds) == 4
    for f in folds:
        assert f.train_end < f.test_start
        assert sessions.index(f.test_start) - sessions.index(f.train_end) == 6  # 5 skipped
    for a, b in itertools.pairwise(folds):
        assert a.test_end < b.test_start
    anchored = make_folds(sessions, train_sessions=100, test_sessions=40, anchored=True)
    assert all(f.train_start == sessions[0] for f in anchored)
    with pytest.raises(ValueError, match="not enough"):
        make_folds(sessions[:50], train_sessions=100, test_sessions=40)


class Holder:
    """Buys one symbol on the first decision and holds it."""

    name = "holder"

    def __init__(self, symbol: str) -> None:
        self.inst = equity(symbol)
        self.done = False

    @property
    def params(self) -> dict[str, Any]:
        return {"symbol": self.inst.symbol}

    def decide(self, ctx: DecisionContext) -> list[OrderIntent]:
        if self.done:
            return []
        self.done = True
        price = float(ctx.view.bars(self.inst, 1)[-1].close)
        qty = int(ctx.equity * 0.9 / price)
        return [
            OrderIntent(instrument=self.inst, side="BUY", quantity=qty, intent=Intent.OPEN, stop=price * 0.2)
        ]


def test_parameters_are_chosen_on_training_data_only() -> None:
    """AAA wins in training and loses in testing; BBB the reverse. Walk-forward must pick AAA:
    choosing BBB would mean the test window leaked into the selection."""
    store = PitStore()
    days = calendar(store, 120)
    rows = []
    for i, d in enumerate(days):
        a = 100 * (1.004**i if i < 80 else 1.004**80 * 0.996 ** (i - 80))
        b = 100 * (0.999**i if i < 80 else 0.999**80 * 1.006 ** (i - 80))
        wiggle = 1 + 0.003 * (-1) ** i
        for inst, c in ((AAA, a * wiggle), (BBB, b * wiggle)):
            rows.append(bar_record(inst, d, c, c * 1.004, c * 0.996, c))
    store.write(rows, ingested_at=eod(days[-1]))
    folds = make_folds(days[25:], train_sessions=55, test_sessions=35)
    result = walk_forward(
        engine(store), lambda p: Holder(str(p["symbol"])), [{"symbol": "AAA"}, {"symbol": "BBB"}], folds[:1]
    )
    fold = result.folds[0]
    assert fold.chosen_params == {"symbol": "AAA"}
    assert fold.test_summary.total_return < 0 < result.mean_train_sharpe
    assert result.mean_test_sharpe < result.mean_train_sharpe  # the classic in/out-of-sample gap, reported


def test_walk_forward_end_to_end_with_deflated_sharpe() -> None:
    store = PitStore()
    days = calendar(store, 400)
    for i, sym in enumerate(("AAA", "BBB", "CCC")):
        random_walk(store, equity(sym), days, seed=40 + i, drift=0.0008)
    universe = [equity(s) for s in ("AAA", "BBB", "CCC")]
    grid = [{"entry_lookback": n, "stop_atr": k} for n in (20, 40) for k in (2.0, 3.0)]
    folds = make_folds(days[60:], train_sessions=150, test_sessions=60, embargo_sessions=2)

    def factory(p: Any) -> DonchianBreakout:
        return DonchianBreakout(
            universe, entry_lookback=int(p["entry_lookback"]), exit_lookback=10, stop_atr=float(p["stop_atr"])
        )

    result = walk_forward(engine(store), factory, grid, folds)
    assert result.n_trials == 4 and len(result.folds) == len(folds)
    assert result.oos_equity[0] == 1.0 and len(result.oos_equity) == 1 + sum(
        f.test_summary.sessions - 1 for f in result.folds
    )
    assert result.deflated_sharpe is not None and 0 <= result.deflated_sharpe.probability <= 1
    assert result.deflated_sharpe.benchmark_sharpe > 0  # four trials raise the bar above zero
    again = walk_forward(engine(store), factory, grid, folds)
    assert [f.test_run_digest for f in again.folds] == [f.test_run_digest for f in result.folds]
