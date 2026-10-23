"""Zerodha Kite Connect v3 adapter for the ``Broker`` contract.

Built from the published Kite Connect REST documentation and tested against recorded
response shapes only; it has not been exercised against a live account. Before live use,
verify (docs/DECISIONS.md): status strings, the holdings/positions netting, GTT payloads and
the AMO window.

- Credentials come from the environment (``AITRADER_KITE_API_KEY``,
  ``AITRADER_KITE_ACCESS_TOKEN``); the daily access token comes from Kite's login flow,
  which needs a human.
- Errors: input, order and margin exceptions are definitive refusals (``OrderRejectedError``);
  network, timeout, gateway, 5xx and token (session) errors are ``BrokerUnavailableError``,
  which the execution agent treats as "outcome unknown".
- Protective orders are GTT triggers: "single" for a stop alone, "two-leg" (OCO) for a stop
  plus a target. GTTs carry no tag, so the execution agent matches them by their levels.
- Derivative symbols need the instruments master (``GET /instruments``, CSV), loaded with
  ``KiteInstruments.from_csv``; cash equities map directly.
"""

from __future__ import annotations

import csv
import io
import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

from aitrader.agents.data.calendar import IST
from aitrader.config.settings import secret
from aitrader.execution.broker import (
    BrokerError,
    BrokerOrder,
    BrokerOrderStatus,
    BrokerPosition,
    BrokerTrade,
    BrokerUnavailableError,
    OrderRejectedError,
    OrderRequest,
    ProtectiveOrder,
    ProtectiveRequest,
    ProtectiveStatus,
    Quote,
)
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, OptionType, Segment
from aitrader.schemas.orders import OrderType, Side

API_KEY_ENV = "AITRADER_KITE_API_KEY"
ACCESS_TOKEN_ENV = "AITRADER_KITE_ACCESS_TOKEN"
BASE_URL = "https://api.kite.trade"

# (method, path, form or query params) -> parsed JSON body; raises urllib errors like urlopen.
Transport = Callable[[str, str, dict[str, str]], dict[str, Any]]

_REJECTIONS = {"InputException", "OrderException", "MarginException", "PermissionException"}
_STATUS = {
    "COMPLETE": BrokerOrderStatus.COMPLETE,
    "CANCELLED": BrokerOrderStatus.CANCELLED,
    "CANCELLED AMO": BrokerOrderStatus.CANCELLED,
    "REJECTED": BrokerOrderStatus.REJECTED,
    "TRIGGER PENDING": BrokerOrderStatus.TRIGGER_PENDING,
}
_GTT_STATUS = {"active": ProtectiveStatus.ACTIVE, "triggered": ProtectiveStatus.TRIGGERED}


@dataclass
class KiteInstruments:
    """Two-way map between instruments and Kite (exchange, tradingsymbol) pairs."""

    by_symbol: dict[tuple[str, str], Instrument] = field(default_factory=dict)
    by_key: dict[str, tuple[str, str]] = field(default_factory=dict)

    @classmethod
    def from_csv(cls, text: str) -> KiteInstruments:
        out = cls()
        for row in csv.DictReader(io.StringIO(text)):
            kind = row["instrument_type"]
            exchange = row["exchange"]
            try:
                if exchange == "NSE" and kind == "EQ":
                    inst = Instrument(
                        exchange=Exchange.NSE,
                        segment=Segment.CASH,
                        symbol=row["tradingsymbol"],
                        instrument_type=InstrumentType.EQUITY,
                    )
                elif exchange == "NFO" and kind in ("FUT", "CE", "PE"):
                    common: dict[str, Any] = {
                        "exchange": Exchange.NSE,
                        "segment": Segment.FNO,
                        "symbol": row["name"].strip('"'),
                        "expiry": date.fromisoformat(row["expiry"]),
                        "lot_size": int(row["lot_size"]),
                    }
                    if kind == "FUT":
                        inst = Instrument(**common, instrument_type=InstrumentType.FUTURE)
                    else:
                        inst = Instrument(
                            **common,
                            instrument_type=InstrumentType.OPTION,
                            strike=Decimal(row["strike"]),
                            option_type=OptionType(kind),
                        )
                else:
                    continue
            except ValueError:
                continue  # symbols outside our schema (odd characters) are not tradable here
            out.by_symbol[(exchange, row["tradingsymbol"])] = inst
            out.by_key[inst.key] = (exchange, row["tradingsymbol"])
        return out

    def kite(self, instrument: Instrument) -> tuple[str, str]:
        if instrument.key in self.by_key:
            return self.by_key[instrument.key]
        if instrument.instrument_type is InstrumentType.EQUITY:
            return "NSE", instrument.symbol
        raise BrokerError(f"{instrument.key} is not in the Kite instruments master")

    def ours(self, exchange: str, tradingsymbol: str) -> Instrument:
        if (exchange, tradingsymbol) in self.by_symbol:
            return self.by_symbol[(exchange, tradingsymbol)]
        if exchange == "NSE":
            return Instrument(
                exchange=Exchange.NSE,
                segment=Segment.CASH,
                symbol=tradingsymbol,
                instrument_type=InstrumentType.EQUITY,
            )
        raise BrokerError(f"{exchange}:{tradingsymbol} is not in the Kite instruments master")


def _ist(ts: str | None) -> datetime | None:
    if not ts:
        return None
    return datetime.fromisoformat(ts).replace(tzinfo=IST).astimezone(UTC)


def http_transport(api_key: str, access_token: str, timeout: float = 10.0) -> Transport:
    headers = {"X-Kite-Version": "3", "Authorization": f"token {api_key}:{access_token}"}

    def send(method: str, path: str, params: dict[str, str]) -> dict[str, Any]:
        query = urllib.parse.urlencode(params, doseq=True)
        url = BASE_URL + path
        data = None
        if method in ("GET", "DELETE") and query:
            url += "?" + query
        elif query:
            data = query.encode()
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body: dict[str, Any] = json.loads(response.read())
                return body
        except urllib.error.HTTPError as exc:
            try:
                body = json.loads(exc.read())
            except ValueError:
                body = {"status": "error", "error_type": "NetworkException", "message": f"HTTP {exc.code}"}
            if exc.code >= 500:
                body["error_type"] = "NetworkException"
            return body

    return send


class KiteBroker:
    name = "zerodha_kite"

    def __init__(self, transport: Transport, instruments: KiteInstruments | None = None) -> None:
        self._send = transport
        self._instruments = instruments or KiteInstruments()

    @classmethod
    def from_env(cls, instruments: KiteInstruments | None = None) -> KiteBroker:
        return cls(http_transport(secret(API_KEY_ENV), secret(ACCESS_TOKEN_ENV)), instruments)

    def _call(self, method: str, path: str, params: dict[str, str] | None = None) -> Any:
        try:
            body = self._send(method, path, params or {})
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise BrokerUnavailableError(f"{method} {path}: {exc}") from exc
        if body.get("status") == "success":
            return body.get("data")
        error_type = str(body.get("error_type", ""))
        message = f"{error_type}: {body.get('message', 'unknown error')}"
        if error_type in _REJECTIONS:
            raise OrderRejectedError(message)
        raise BrokerUnavailableError(message)

    # ------------------------------------------------------------ orders
    def place_order(self, request: OrderRequest) -> str:
        if request.order_type is OrderType.MARKET:
            raise OrderRejectedError("market orders are disabled for this system")
        exchange, symbol = self._instruments.kite(request.instrument)
        params = {
            "tradingsymbol": symbol,
            "exchange": exchange,
            "transaction_type": request.side.value,
            "order_type": request.order_type.value,
            "quantity": str(request.quantity),
            "product": request.product.value,
            "validity": "DAY",
            "tag": request.tag,
        }
        if request.price is not None:
            params["price"] = str(request.price)
        if request.trigger_price is not None:
            params["trigger_price"] = str(request.trigger_price)
        variety = "amo" if request.after_market else "regular"
        data = self._call("POST", f"/orders/{variety}", params)
        return str(data["order_id"])

    def _raw_orders(self) -> list[dict[str, Any]]:
        data = self._call("GET", "/orders")
        return list(data or [])

    def cancel_order(self, broker_order_id: str) -> None:
        variety = next(
            (
                o.get("variety", "regular")
                for o in self._raw_orders()
                if str(o["order_id"]) == broker_order_id
            ),
            None,
        )
        if variety is None:
            raise OrderRejectedError(f"unknown order {broker_order_id}")
        self._call("DELETE", f"/orders/{variety}/{broker_order_id}")

    def orders(self) -> list[BrokerOrder]:
        out = []
        for o in self._raw_orders():
            status = str(o.get("status", "")).upper()
            filled = int(o.get("filled_quantity") or 0)
            price = o.get("price")
            out.append(
                BrokerOrder(
                    broker_order_id=str(o["order_id"]),
                    tag=o.get("tag") or None,
                    instrument=self._instruments.ours(o["exchange"], o["tradingsymbol"]),
                    side=Side(o["transaction_type"]),
                    quantity=int(o["quantity"]),
                    filled_quantity=filled,
                    average_price=float(o["average_price"]) if filled and o.get("average_price") else None,
                    status=_STATUS.get(status, BrokerOrderStatus.OPEN),  # OPEN, AMO REQ RECEIVED, *PENDING
                    status_message=o.get("status_message"),
                    order_type=OrderType(o["order_type"]),
                    price=Decimal(str(price)) if price else None,
                    placed_at=_ist(o.get("order_timestamp")),
                )
            )
        return out

    def trades(self) -> list[BrokerTrade]:
        tags = {str(o["order_id"]): o.get("tag") for o in self._raw_orders()}
        out = []
        for t in self._call("GET", "/trades") or []:
            order_id = str(t["order_id"])
            out.append(
                BrokerTrade(
                    trade_id=str(t["trade_id"]),
                    broker_order_id=order_id,
                    tag=tags.get(order_id) or None,
                    instrument=self._instruments.ours(t["exchange"], t["tradingsymbol"]),
                    side=Side(t["transaction_type"]),
                    quantity=int(t["quantity"]),
                    price=float(t["average_price"]),
                    filled_at=_ist(t.get("fill_timestamp") or t.get("exchange_timestamp"))
                    or datetime.now(UTC),
                )
            )
        return out

    def positions(self) -> list[BrokerPosition]:
        """Settled and T1 holdings plus today's net positions (CNC sells today show as
        negative net positions, so a sold holding is counted once)."""
        totals: dict[str, tuple[Instrument, int, float]] = {}

        def add(exchange: str, symbol: str, quantity: int, price: float) -> None:
            inst = self._instruments.ours(exchange, symbol)
            _, q, p = totals.get(inst.key, (inst, 0, 0.0))
            totals[inst.key] = (inst, q + quantity, price if quantity else p)

        for h in self._call("GET", "/portfolio/holdings") or []:
            add(
                h["exchange"],
                h["tradingsymbol"],
                int(h.get("quantity", 0)) + int(h.get("t1_quantity", 0)),
                float(h.get("average_price", 0)),
            )
        for p in (self._call("GET", "/portfolio/positions") or {}).get("net", []):
            add(
                p["exchange"], p["tradingsymbol"], int(p.get("quantity", 0)), float(p.get("average_price", 0))
            )
        return [BrokerPosition(instrument=i, quantity=q, average_price=p) for i, q, p in totals.values() if q]

    def available_margin(self) -> float:
        data = self._call("GET", "/user/margins/equity")
        return float(data["net"])

    def quote(self, instrument: Instrument) -> Quote:
        exchange, symbol = self._instruments.kite(instrument)
        key = f"{exchange}:{symbol}"
        data = self._call("GET", "/quote", {"i": key}).get(key)
        if not data:
            raise OrderRejectedError(f"no quote for {key}")
        return Quote(
            instrument=instrument,
            last_price=float(data["last_price"]),
            lower_circuit=float(data["lower_circuit_limit"]),
            upper_circuit=float(data["upper_circuit_limit"]),
            as_of=_ist(data.get("timestamp")) or datetime.now(UTC),
        )

    # ------------------------------------------------------------ GTT protective orders
    def place_protective(self, request: ProtectiveRequest) -> str:
        exchange, symbol = self._instruments.kite(request.instrument)
        last = self.quote(request.instrument).last_price

        def leg(price: Decimal) -> dict[str, Any]:
            return {
                "exchange": exchange,
                "tradingsymbol": symbol,
                "transaction_type": request.side.value,
                "quantity": request.quantity,
                "order_type": "LIMIT",
                "product": request.product.value,
                "price": float(price),
            }

        if request.target is None:
            kind, triggers, legs = "single", [float(request.stop_trigger)], [leg(request.stop_limit)]
        elif request.side is Side.SELL:  # long position: stop below, target above
            kind = "two-leg"
            triggers = [float(request.stop_trigger), float(request.target)]
            legs = [leg(request.stop_limit), leg(request.target)]
        else:
            kind = "two-leg"
            triggers = [float(request.target), float(request.stop_trigger)]
            legs = [leg(request.target), leg(request.stop_limit)]
        condition = {
            "exchange": exchange,
            "tradingsymbol": symbol,
            "trigger_values": triggers,
            "last_price": last,
        }
        data = self._call(
            "POST",
            "/gtt/triggers",
            {"type": kind, "condition": json.dumps(condition), "orders": json.dumps(legs)},
        )
        return str(data["trigger_id"])

    def cancel_protective(self, protective_id: str) -> None:
        self._call("DELETE", f"/gtt/triggers/{protective_id}")

    def protective_orders(self) -> list[ProtectiveOrder]:
        out = []
        for g in self._call("GET", "/gtt/triggers") or []:
            condition, legs = g["condition"], g.get("orders") or []
            if not legs:
                continue
            inst = self._instruments.ours(condition["exchange"], condition["tradingsymbol"])
            side = Side(legs[0]["transaction_type"])
            triggers = [Decimal(str(v)) for v in condition["trigger_values"]]
            if len(triggers) == 1:
                stop, target = triggers[0], None
            elif side is Side.SELL:
                stop, target = triggers[0], triggers[1]
            else:
                stop, target = triggers[1], triggers[0]
            triggered = next(
                (
                    str(result["order_result"]["order_id"])
                    for o in legs
                    if (result := o.get("result")) and result.get("order_result", {}).get("order_id")
                ),
                None,
            )
            out.append(
                ProtectiveOrder(
                    protective_id=str(g["id"]),
                    instrument=inst,
                    side=side,
                    quantity=int(legs[0]["quantity"]),
                    stop_trigger=stop,
                    target=target,
                    status=_GTT_STATUS.get(str(g.get("status")), ProtectiveStatus.CANCELLED),
                    triggered_order_id=triggered,
                )
            )
        return out
