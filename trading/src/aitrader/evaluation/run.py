"""Offline evaluation of the analyst agents over a stored history.

Usage: python -m aitrader.evaluation.run --store data/pit.duckdb --start 2019-01-01 --end 2025-12-31
       [--symbols RELIANCE,TCS,...] [--universe-index NIFTY_500] [--out report.json]
"""

from __future__ import annotations

import argparse
import json
from datetime import date, time
from pathlib import Path

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import ist_datetime
from aitrader.agents.data.universe import Universe
from aitrader.agents.smart_money.engine import SmartMoneyEngine
from aitrader.agents.trend.agent import TrendAgent, load_events
from aitrader.backtest.strategies import equity
from aitrader.config import load_risk_limits
from aitrader.evaluation.evaluators import evaluate_chart, evaluate_smart_money, evaluate_trend
from aitrader.storage import CachedPitReader, PitStore


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, default=Path("data/pit.duckdb"))
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    parser.add_argument("--symbols", default="")
    parser.add_argument("--universe-index", default="NIFTY_500")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()
    store = PitStore(args.store)
    reader = CachedPitReader(store)
    market = MarketData(reader)
    universe = Universe(reader)
    sessions = [
        d for d in market.sessions(ist_datetime(args.end, time(23, 59))) if args.start <= d <= args.end
    ]
    symbols = [s for s in args.symbols.split(",") if s] or list(
        universe.members(args.universe_index, ist_datetime(args.start, time(20, 0)))
    )
    instruments = [equity(s) for s in symbols]
    trend = TrendAgent(
        market,
        universe,
        universe_index=args.universe_index,
        events=load_events(),
        max_exposure_change=load_risk_limits().regime.max_exposure_change_per_cycle,
    )
    reports = [
        evaluate_chart(market, instruments, sessions),
        evaluate_smart_money(market, SmartMoneyEngine(market, reader), instruments, sessions),
        evaluate_trend(market, trend, sessions),
    ]
    payload = [r.model_dump(mode="json") for r in reports]
    text = json.dumps(payload, indent=2)
    if args.out:
        args.out.write_text(text)
    print(text)


if __name__ == "__main__":
    main()
