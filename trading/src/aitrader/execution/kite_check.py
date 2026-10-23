"""Read-only check of the Kite Connect adapter against a real account.

    AITRADER_KITE_API_KEY=... AITRADER_KITE_ACCESS_TOKEN=... python -m aitrader.execution.kite_check

Calls only read endpoints (orders, trades, holdings and positions, margins, one quote, GTT
triggers), parses each response through the adapter and reports what parsed and what did
not. It never places, modifies or cancels anything: the transport it uses refuses any
method other than GET. Run it once before Phase 8 to confirm the response shapes.
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable
from typing import Any

from aitrader.backtest.strategies import equity
from aitrader.config.settings import secret
from aitrader.execution.kite import ACCESS_TOKEN_ENV, API_KEY_ENV, KiteBroker, Transport, http_transport


class WriteRefusedError(RuntimeError):
    """A write was attempted through the read-only transport."""


def read_only(transport: Transport) -> Transport:
    def guarded(method: str, path: str, params: dict[str, str]) -> dict[str, Any]:
        if method != "GET":
            raise WriteRefusedError(f"read-only check refused {method} {path}")
        return transport(method, path, params)

    return guarded


def check(broker: KiteBroker, quote_symbol: str) -> list[tuple[str, bool, str]]:
    calls: list[tuple[str, Callable[[], object]]] = [
        ("orders", broker.orders),
        ("trades", broker.trades),
        ("positions (holdings + net)", broker.positions),
        ("available margin", broker.available_margin),
        (f"quote {quote_symbol}", lambda: broker.quote(equity(quote_symbol))),
        ("GTT triggers", broker.protective_orders),
    ]
    results = []
    for name, call in calls:
        try:
            value = call()
        except Exception as exc:  # report every failure; this is a diagnostic
            results.append((name, False, f"{type(exc).__name__}: {exc}"))
            continue
        summary = f"{len(value)} parsed" if isinstance(value, list) else repr(value)[:120]
        results.append((name, True, summary))
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m aitrader.execution.kite_check")
    parser.add_argument("--quote", default="INFY", help="cash symbol to quote (default INFY)")
    args = parser.parse_args(argv)
    broker = KiteBroker(read_only(http_transport(secret(API_KEY_ENV), secret(ACCESS_TOKEN_ENV))))
    results = check(broker, args.quote)
    for name, ok, detail in results:
        print(f"[{'ok' if ok else 'FAIL'}] {name}: {detail}")
    return 0 if all(ok for _, ok, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
