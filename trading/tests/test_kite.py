"""Kite Connect adapter against a fake transport with documented response shapes.

These pin the request mapping and response parsing; they cannot prove the live API still
behaves this way (see docs/DECISIONS.md, Phase 7 checks)."""

from __future__ import annotations

import json
import urllib.error
from datetime import date
from decimal import Decimal
from typing import Any

import pytest

from aitrader.execution.broker import (
    BrokerOrderStatus,
    BrokerUnavailableError,
    OrderRejectedError,
    OrderRequest,
    ProtectiveRequest,
    ProtectiveStatus,
)
from aitrader.execution.kite import KiteBroker, KiteInstruments
from aitrader.schemas.base import InstrumentType
from aitrader.schemas.orders import OrderType, Product, Side
from tests.execution_helpers import XYZ

INSTRUMENTS_CSV = (
    "instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,"
    "tick_size,lot_size,instrument_type,segment,exchange\n"
    """408065,1594,INFY,INFOSYS,0,,0,0.05,1,EQ,NSE,NSE
12345,48,NIFTY26OCTFUT,"NIFTY",0,2026-10-27,0,0.05,75,FUT,NFO-FUT,NFO
12346,49,NIFTY26OCT25000CE,"NIFTY",0,2026-10-27,25000,0.05,75,CE,NFO-OPT,NFO
"""
)


class Fake:
    def __init__(self, responses: dict[tuple[str, str], Any]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, str, dict[str, str]]] = []

    def __call__(self, method: str, path: str, params: dict[str, str]) -> dict[str, Any]:
        self.calls.append((method, path, params))
        value = self.responses[(method, path)]
        if isinstance(value, Exception):
            raise value
        if isinstance(value, dict) and value.get("status") == "error":
            return value
        return {"status": "success", "data": value}


def test_place_order_maps_fields_and_variety() -> None:
    fake = Fake(
        {
            ("POST", "/orders/regular"): {"order_id": "151220000000000"},
            ("POST", "/orders/amo"): {"order_id": "2"},
        }
    )
    kite = KiteBroker(fake)
    request = OrderRequest(
        tag="A1b2c3",
        instrument=XYZ,
        side=Side.BUY,
        quantity=10,
        order_type=OrderType.LIMIT,
        price=Decimal("101.05"),
        product=Product.CNC,
    )
    assert kite.place_order(request) == "151220000000000"
    _, path, params = fake.calls[0]
    assert path == "/orders/regular"
    assert params == {
        "tradingsymbol": "XYZ",
        "exchange": "NSE",
        "transaction_type": "BUY",
        "order_type": "LIMIT",
        "quantity": "10",
        "product": "CNC",
        "validity": "DAY",
        "tag": "A1b2c3",
        "price": "101.05",
    }
    kite.place_order(request.model_copy(update={"after_market": True}))
    assert fake.calls[-1][1] == "/orders/amo"


def test_error_types_map_to_rejection_or_unknown_outcome() -> None:
    request = OrderRequest(
        tag="A1",
        instrument=XYZ,
        side=Side.BUY,
        quantity=1,
        order_type=OrderType.LIMIT,
        price=Decimal("100"),
        product=Product.CNC,
    )
    for error_type in ("InputException", "OrderException", "MarginException"):
        kite = KiteBroker(
            Fake(
                {("POST", "/orders/regular"): {"status": "error", "error_type": error_type, "message": "no"}}
            )
        )
        with pytest.raises(OrderRejectedError):
            kite.place_order(request)
    for failure in (
        {"status": "error", "error_type": "NetworkException", "message": "gateway"},
        {"status": "error", "error_type": "TokenException", "message": "session expired"},
        urllib.error.URLError("timed out"),
        TimeoutError("read timed out"),
    ):
        kite = KiteBroker(Fake({("POST", "/orders/regular"): failure}))
        with pytest.raises(BrokerUnavailableError):
            kite.place_order(request)


def test_orders_trades_and_status_mapping() -> None:
    orders = [
        {
            "order_id": "1",
            "tag": "Aabc",
            "exchange": "NSE",
            "tradingsymbol": "XYZ",
            "transaction_type": "BUY",
            "quantity": 10,
            "filled_quantity": 4,
            "average_price": 100.5,
            "status": "OPEN",
            "order_type": "LIMIT",
            "price": 101,
            "order_timestamp": "2026-10-22 09:15:03",
            "variety": "regular",
        },
        {
            "order_id": "2",
            "tag": None,
            "exchange": "NSE",
            "tradingsymbol": "XYZ",
            "transaction_type": "SELL",
            "quantity": 10,
            "filled_quantity": 0,
            "average_price": 0,
            "status": "AMO REQ RECEIVED",
            "order_type": "LIMIT",
            "price": 99,
            "order_timestamp": "2026-10-22 20:00:00",
            "variety": "amo",
        },
        {
            "order_id": "3",
            "tag": "Ax",
            "exchange": "NSE",
            "tradingsymbol": "XYZ",
            "transaction_type": "BUY",
            "quantity": 5,
            "filled_quantity": 5,
            "average_price": 100,
            "status": "COMPLETE",
            "order_type": "LIMIT",
            "price": 100,
            "order_timestamp": "2026-10-22 09:15:00",
            "variety": "regular",
        },
    ]
    trades = [
        {
            "trade_id": "T1",
            "order_id": "1",
            "exchange": "NSE",
            "tradingsymbol": "XYZ",
            "transaction_type": "BUY",
            "quantity": 4,
            "average_price": 100.5,
            "fill_timestamp": "2026-10-22 09:15:04",
        }
    ]
    fake = Fake(
        {
            ("GET", "/orders"): orders,
            ("GET", "/trades"): trades,
            ("DELETE", "/orders/amo/2"): {"order_id": "2"},
        }
    )
    kite = KiteBroker(fake)
    parsed = kite.orders()
    assert [o.status for o in parsed] == [
        BrokerOrderStatus.OPEN,
        BrokerOrderStatus.OPEN,
        BrokerOrderStatus.COMPLETE,
    ]
    placed = parsed[0].placed_at
    assert parsed[0].average_price == 100.5 and placed is not None and placed.hour == 3  # 09:15 IST
    (trade,) = kite.trades()
    assert trade.tag == "Aabc" and trade.quantity == 4
    kite.cancel_order("2")
    assert fake.calls[-1][:2] == ("DELETE", "/orders/amo/2")
    with pytest.raises(OrderRejectedError):
        kite.cancel_order("404")


def test_positions_net_holdings_and_todays_trades_once() -> None:
    fake = Fake(
        {
            ("GET", "/portfolio/holdings"): [
                {
                    "exchange": "NSE",
                    "tradingsymbol": "XYZ",
                    "quantity": 100,
                    "t1_quantity": 20,
                    "average_price": 90,
                },
            ],
            ("GET", "/portfolio/positions"): {
                "net": [
                    {
                        "exchange": "NSE",
                        "tradingsymbol": "XYZ",
                        "quantity": -50,
                        "average_price": 101,
                        "product": "CNC",
                    },
                ],
                "day": [],
            },
        }
    )
    (pos,) = KiteBroker(fake).positions()
    assert pos.quantity == 70


def test_gtt_two_leg_orders_levels_by_side_and_parses_triggers() -> None:
    fake = Fake(
        {
            ("GET", "/quote"): {
                "NSE:XYZ": {"last_price": 100.0, "lower_circuit_limit": 80, "upper_circuit_limit": 120}
            },
            ("POST", "/gtt/triggers"): {"trigger_id": 77},
            ("GET", "/gtt/triggers"): [
                {
                    "id": 77,
                    "type": "two-leg",
                    "status": "triggered",
                    "condition": {"exchange": "NSE", "tradingsymbol": "XYZ", "trigger_values": [95, 110]},
                    "orders": [
                        {"transaction_type": "SELL", "quantity": 10, "price": 93.1, "result": None},
                        {
                            "transaction_type": "SELL",
                            "quantity": 10,
                            "price": 110,
                            "result": {"order_result": {"order_id": "999", "status": "success"}},
                        },
                    ],
                },
            ],
        }
    )
    kite = KiteBroker(fake)
    long_stop = ProtectiveRequest(
        tag="Ap",
        instrument=XYZ,
        side=Side.SELL,
        quantity=10,
        stop_trigger=Decimal("95"),
        stop_limit=Decimal("93.1"),
        target=Decimal("110"),
        product=Product.CNC,
    )
    assert kite.place_protective(long_stop) == "77"
    params = fake.calls[-1][2]
    assert params["type"] == "two-leg"
    assert json.loads(params["condition"])["trigger_values"] == [95.0, 110.0]
    assert [leg["price"] for leg in json.loads(params["orders"])] == [93.1, 110.0]
    short_stop = long_stop.model_copy(
        update={
            "side": Side.BUY,
            "stop_trigger": Decimal("105"),
            "stop_limit": Decimal("107"),
            "target": Decimal("90"),
        }
    )
    kite.place_protective(short_stop)
    assert json.loads(fake.calls[-1][2]["condition"])["trigger_values"] == [90.0, 105.0]
    (gtt,) = kite.protective_orders()
    assert gtt.status is ProtectiveStatus.TRIGGERED and gtt.triggered_order_id == "999"
    assert gtt.stop_trigger == Decimal("95") and gtt.target == Decimal("110") and gtt.tag is None


def test_instruments_master_maps_derivatives_both_ways() -> None:
    master = KiteInstruments.from_csv(INSTRUMENTS_CSV)
    fut = master.ours("NFO", "NIFTY26OCTFUT")
    assert (
        fut.instrument_type is InstrumentType.FUTURE
        and fut.lot_size == 75
        and fut.expiry == date(2026, 10, 27)
    )
    call = master.ours("NFO", "NIFTY26OCT25000CE")
    assert call.strike == Decimal("25000") and master.kite(call) == ("NFO", "NIFTY26OCT25000CE")
    assert master.kite(XYZ) == ("NSE", "XYZ")
