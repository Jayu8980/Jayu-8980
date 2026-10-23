"""Performance summary for a backtest run (pure functions over the run's records)."""

from __future__ import annotations

from collections.abc import Sequence

import numpy as np

from aitrader.backtest.models import EquityPoint, FillRecord, PerformanceSummary, TradeRecord
from aitrader.tools.validation import return_stats

TRADING_DAYS = 252


def summarise(
    curve: Sequence[EquityPoint],
    trades: Sequence[TradeRecord],
    fills: Sequence[FillRecord],
    dividends: float,
) -> PerformanceSummary:
    equity = [p.equity for p in curve]
    if min(equity) <= 0:
        raise ValueError("equity went non-positive; the run is invalid as configured")
    stats = return_stats(equity, TRADING_DAYS)
    years = max((len(curve) - 1) / TRADING_DAYS, 1 / TRADING_DAYS)
    notional = sum(f.price * f.quantity for f in fills)
    wins = [t for t in trades if t.net_pnl > 0]
    losses = [t for t in trades if t.net_pnl <= 0]
    gross_loss = -sum(t.net_pnl for t in losses)
    r_values = [t.r_multiple for t in trades if t.r_multiple is not None]
    return PerformanceSummary(
        start=curve[0].day,
        end=curve[-1].day,
        sessions=len(curve),
        initial_equity=equity[0],
        final_equity=equity[-1],
        total_return=equity[-1] / equity[0] - 1,
        cagr=stats.cagr,
        annual_vol=stats.annual_vol,
        sharpe=stats.sharpe,
        sortino=stats.sortino,
        max_drawdown=stats.max_drawdown,
        calmar=stats.calmar,
        avg_gross_exposure=float(np.mean([p.gross_exposure for p in curve])),
        annual_turnover=notional / float(np.mean(equity)) / years,
        trades=len(trades),
        win_rate=len(wins) / len(trades) if trades else None,
        avg_r_multiple=float(np.mean(r_values)) if r_values else None,
        profit_factor=sum(t.net_pnl for t in wins) / gross_loss if trades and gross_loss > 0 else None,
        avg_holding_sessions=float(np.mean([t.holding_sessions for t in trades])) if trades else None,
        total_costs=sum(f.costs for f in fills),
        total_dividends=dividends,
    )
