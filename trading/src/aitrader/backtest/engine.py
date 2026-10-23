"""Event-driven backtest engine.

Timeline for each trading session d:
  1. Before the open: corporate actions with ex-date d are applied to holdings (and pending
     orders); delisted or expired positions are closed at their last close.
  2. At the open: orders decided after the previous close fill at d's open, with slippage,
     a participation cap on d's volume and the cost schedule in force on d.
  3. During the session: protective stops and targets are checked against d's range. Gaps
     through a level fill at the open; a bar spanning both stop and target assumes the stop.
  4. At the close: positions are marked to d's close.
  5. After EOD data is published: the strategy decides, seeing only data available at the
     decision time (``PitView``). Its orders wait for step 2 of the next session.

The strategy never sees a bar before it is published, and no order fills at a price that was
known when it was decided. The engine itself reads session d's bar to simulate fills on d.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Protocol

import numpy as np

from aitrader.agents.data.agent import BarSeries, MarketData
from aitrader.agents.data.calendar import ist_datetime, parse_hhmm
from aitrader.agents.data.universe import Universe
from aitrader.backtest.config import BacktestConfig
from aitrader.backtest.metrics import summarise
from aitrader.backtest.models import (
    BacktestResult,
    EquityPoint,
    FillRecord,
    Intent,
    OrderIntent,
    Position,
    Rejection,
    Side,
    TradeRecord,
)
from aitrader.schemas.base import Instrument, InstrumentType, Segment, Timeframe
from aitrader.schemas.canonical import digest
from aitrader.schemas.market import Bar, CorporateAction, CorporateActionType
from aitrader.tools.costs import india_costs

_END_OF_DAY = time(23, 59)
_PRE_OPEN = time(9, 0)


class PitView:
    """Everything a strategy may read at a decision point: data published at or before
    ``as_of``. There is deliberately no way to pass a later time."""

    def __init__(self, market: MarketData, universe: Universe | None, as_of: datetime) -> None:
        self._market = market
        self._universe = universe
        self._as_of = as_of

    @property
    def as_of(self) -> datetime:
        return self._as_of

    def series(
        self, instrument: Instrument, lookback_sessions: int, timeframe: Timeframe = Timeframe.D1
    ) -> BarSeries:
        """The last ``lookback_sessions`` bars (adjusted for actions known now)."""
        calendar_days = int(lookback_sessions * 7 / 5) + 20
        series = self._market.bars(
            instrument, timeframe, self._as_of, start=self._as_of - timedelta(days=calendar_days)
        )
        return BarSeries(series.bars[-lookback_sessions:], series.unadjusted_actions)

    def bars(self, instrument: Instrument, lookback_sessions: int) -> list[Bar]:
        return self.series(instrument, lookback_sessions).bars

    def members(self, index: str) -> tuple[str, ...]:
        if self._universe is None:
            raise LookupError("no universe configured for this backtest")
        return self._universe.members(index, self._as_of)


@dataclass(frozen=True)
class PositionView:
    quantity: int
    avg_price: float
    stop: float | None
    target: float | None
    entry_date: date
    tag: str


@dataclass(frozen=True)
class DecisionContext:
    day: date
    as_of: datetime
    view: PitView
    cash: float
    equity: float
    positions: dict[str, PositionView]
    instruments: dict[str, Instrument]


class Strategy(Protocol):
    name: str

    @property
    def params(self) -> dict[str, float | int | str | bool]: ...

    def decide(self, ctx: DecisionContext) -> Sequence[OrderIntent]: ...


@dataclass
class _Pending:
    intent: OrderIntent
    slippage_bps: float
    sessions_left: int


@dataclass
class _Run:
    cash: float
    positions: dict[str, Position] = field(default_factory=dict)
    pending: list[_Pending] = field(default_factory=list)
    fills: list[FillRecord] = field(default_factory=list)
    trades: list[TradeRecord] = field(default_factory=list)
    rejections: list[Rejection] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    curve: list[EquityPoint] = field(default_factory=list)
    slippage: dict[str, float] = field(default_factory=dict)  # per position, for exits
    dividends: float = 0.0
    approximate_costs: bool = False


def _cost_segment(instrument: Instrument) -> str:
    return {
        InstrumentType.EQUITY: "equity_delivery",
        InstrumentType.FUTURE: "futures",
        InstrumentType.OPTION: "options",
    }[instrument.instrument_type]


class BacktestEngine:
    def __init__(self, market: MarketData, config: BacktestConfig, universe: Universe | None = None) -> None:
        self._market = market
        self._config = config
        self._universe = universe
        self._decision_time = parse_hhmm(config.decision_time_ist)
        self._bar_cache: dict[tuple[str, date], Bar | None] = {}

    # ----------------------------------------------------------------- public API
    def sessions(self, start: date, end: date) -> list[date]:
        days = self._market.sessions(ist_datetime(end, _END_OF_DAY), start=ist_datetime(start, time(0, 0)))
        return [d for d in days if start <= d <= end]

    def run(
        self,
        strategy: Strategy,
        start: date,
        end: date,
        *,
        initial_capital: float | None = None,
        liquidate_at_end: bool = False,
    ) -> BacktestResult:
        sessions = self.sessions(start, end)
        if len(sessions) < 2:
            raise ValueError(f"need at least two sessions between {start} and {end}")
        capital = initial_capital or self._config.initial_capital_inr
        run = _Run(cash=capital)
        instruments: dict[str, Instrument] = {}
        index_of = {d: i for i, d in enumerate(sessions)}
        for day in sessions:
            self._before_open(run, day, index_of)
            self._fill_pending(run, day, instruments, index_of)
            self._protective_exits(run, day, index_of)
            self._mark(run, day)
            if day == sessions[-1] and liquidate_at_end:
                for key in list(run.positions):
                    pos = run.positions[key]
                    self._exit(
                        run, pos, day, pos.last_close or pos.avg_price, 0.0, "end", index_of, with_costs=True
                    )
                self._mark(run, day, replace=True)
                break
            if day != sessions[-1]:
                self._decide(run, strategy, day, instruments)
        config_digest = digest({"config": self._config.model_dump(mode="json"), "params": strategy.params})
        body = {
            "strategy": strategy.name,
            "params": strategy.params,
            "config_digest": config_digest,
            "start": sessions[0],
            "end": sessions[-1],
            "equity_curve": tuple(run.curve),
            "trades": tuple(run.trades),
            "fills": tuple(run.fills),
            "rejections": tuple(run.rejections),
            "open_positions": {k: p.quantity for k, p in sorted(run.positions.items())},
            "approximate_costs_used": run.approximate_costs,
            "summary": summarise(run.curve, run.trades, run.fills, run.dividends),
            "warnings": tuple(run.warnings),
        }
        provisional = BacktestResult.model_validate({**body, "run_digest": "0" * 64})
        return provisional.model_copy(
            update={"run_digest": digest(provisional.model_dump(mode="json", exclude={"run_digest"}))}
        )

    # ----------------------------------------------------------------- data access
    def _bar(self, instrument: Instrument, day: date) -> Bar | None:
        key = (instrument.key, day)
        if key not in self._bar_cache:
            bars = self._market.raw_bars(
                instrument,
                Timeframe.D1,
                ist_datetime(day, _END_OF_DAY),
                start=ist_datetime(day, time(0, 0)),
                end=ist_datetime(day, _END_OF_DAY),
            )
            self._bar_cache[key] = bars[-1] if bars else None
        return self._bar_cache[key]

    # ----------------------------------------------------------------- step 1
    def _before_open(self, run: _Run, day: date, index_of: dict[date, int]) -> None:
        pre_open = ist_datetime(day, _PRE_OPEN)
        for key in list(run.positions):
            pos = run.positions[key]
            inst = pos.instrument
            if inst.instrument_type is InstrumentType.EQUITY:
                for action in self._market.corporate_actions(inst, pre_open):
                    if action.ex_date != day:
                        continue
                    self._apply_action(run, pos, action, day)
                if self._universe is not None:
                    security = self._universe.security(inst.symbol, pre_open)
                    if security and security.delisted_on and security.delisted_on <= day:
                        self._exit(
                            run,
                            pos,
                            day,
                            pos.last_close or pos.avg_price,
                            0.0,
                            "delisted",
                            index_of,
                            with_costs=False,
                        )
                        run.pending = [p for p in run.pending if p.intent.instrument.key != key]
                        continue
            if inst.expiry is not None and day > inst.expiry:
                self._exit(
                    run, pos, day, pos.last_close or pos.avg_price, 0.0, "expiry", index_of, with_costs=False
                )

    def _apply_action(self, run: _Run, pos: Position, action: CorporateAction, day: date) -> None:
        key = pos.instrument.key
        kind = action.action_type
        if kind in (CorporateActionType.SPLIT, CorporateActionType.BONUS):
            if action.ratio_numerator is None or action.ratio_denominator is None:
                run.warnings.append(f"{day}: {kind} for held {key} has no ratio; not applied")
                return
            n, d = float(action.ratio_numerator), float(action.ratio_denominator)
            mult = n / d if kind is CorporateActionType.SPLIT else (d + n) / d
            new_qty = pos.quantity * mult
            whole = math.floor(abs(new_qty)) * (1 if new_qty > 0 else -1)
            fraction = new_qty - whole
            pos.quantity = whole
            pos.avg_price /= mult
            pos.stop = pos.stop / mult if pos.stop else None
            pos.target = pos.target / mult if pos.target else None
            pos.initial_risk_per_unit = (
                pos.initial_risk_per_unit / mult if pos.initial_risk_per_unit else None
            )
            if pos.last_close:
                pos.last_close /= mult
                run.cash += fraction * pos.last_close  # cash in lieu of fractional entitlement
            run.pending = [
                _Pending(
                    p.intent.model_copy(
                        update={
                            "quantity": max(int(p.intent.quantity * mult), 1),
                            "stop": p.intent.stop / mult if p.intent.stop else None,
                            "target": p.intent.target / mult if p.intent.target else None,
                            "limit_price": p.intent.limit_price / mult if p.intent.limit_price else None,
                        }
                    ),
                    p.slippage_bps,
                    p.sessions_left,
                )
                if p.intent.instrument.key == key
                else p
                for p in run.pending
            ]
        elif kind is CorporateActionType.DIVIDEND and action.cash_amount is not None:
            amount = float(action.cash_amount) * pos.quantity
            run.cash += amount
            run.dividends += amount
        elif kind in (CorporateActionType.RIGHTS, CorporateActionType.MERGER, CorporateActionType.DEMERGER):
            run.warnings.append(
                f"{day}: {kind} for held {key} not simulated; results around this date are unreliable"
            )

    # ----------------------------------------------------------------- step 2
    def _fill_pending(
        self, run: _Run, day: date, instruments: dict[str, Instrument], index_of: dict[date, int]
    ) -> None:
        still_pending: list[_Pending] = []
        cfg = self._config.fills
        for order in run.pending:
            intent = order.intent
            inst = intent.instrument
            key = inst.key
            bar = self._bar(inst, day)
            if bar is None:
                self._retry_or_reject(run, order, still_pending, day, "no bar (halt or suspension)")
                continue
            lot = inst.lot_size
            cap = math.floor(bar.volume * cfg.max_participation_of_bar_volume / lot) * lot
            qty = min(intent.quantity, cap)
            pos = run.positions.get(key)
            if intent.intent is Intent.CLOSE:
                held = abs(pos.quantity) if pos else 0
                qty = min(qty, held)
                if qty == 0:
                    run.rejections.append(Rejection(day=day, instrument_key=key, reason="nothing to close"))
                    continue
            if qty == 0:
                self._retry_or_reject(run, order, still_pending, day, "volume too thin for participation cap")
                continue
            if intent.intent is Intent.OPEN and pos and (pos.quantity > 0) != (intent.side == "BUY"):
                run.rejections.append(
                    Rejection(day=day, instrument_key=key, reason="would flip an existing position")
                )
                continue
            reference = self._entry_reference(intent, bar)
            if reference is None:
                self._retry_or_reject(run, order, still_pending, day, "limit not reached")
                continue
            price = self._slip(intent.side, reference, order.slippage_bps, bar)
            if intent.intent is Intent.OPEN and intent.side == "BUY" and inst.segment is Segment.CASH:
                estimate = india_costs(_cost_segment(inst), "BUY", price, qty, day).total  # type: ignore[arg-type]
                if qty * price + estimate > run.cash:
                    affordable = math.floor((run.cash * 0.995) / (price * lot)) * lot
                    if affordable <= 0:
                        run.rejections.append(
                            Rejection(day=day, instrument_key=key, reason="insufficient cash")
                        )
                        continue
                    qty = affordable
            if qty < intent.quantity:
                run.rejections.append(
                    Rejection(day=day, instrument_key=key, reason=f"partial fill {qty}/{intent.quantity}")
                )
            instruments[key] = inst
            self._fill(run, intent, qty, price, reference, order.slippage_bps, day, "order", index_of)
        run.pending = still_pending

    def _retry_or_reject(
        self, run: _Run, order: _Pending, keep: list[_Pending], day: date, reason: str
    ) -> None:
        if order.sessions_left > 1:
            keep.append(_Pending(order.intent, order.slippage_bps, order.sessions_left - 1))
        else:
            run.rejections.append(
                Rejection(day=day, instrument_key=order.intent.instrument.key, reason=reason)
            )

    @staticmethod
    def _entry_reference(intent: OrderIntent, bar: Bar) -> float | None:
        o, lo, hi = float(bar.open), float(bar.low), float(bar.high)
        limit = intent.limit_price
        if limit is None:
            return o
        if intent.side == "BUY":
            return o if o <= limit else (limit if lo <= limit else None)
        return o if o >= limit else (limit if hi >= limit else None)

    def _slip(self, side: Side, reference: float, bps: float, bar: Bar) -> float:
        price = reference * (1 + bps / 1e4) if side == "BUY" else reference * (1 - bps / 1e4)
        if self._config.fills.clamp_to_bar_range:
            price = min(max(price, float(bar.low)), float(bar.high))
        return price

    def _fill(
        self,
        run: _Run,
        intent: OrderIntent,
        qty: int,
        price: float,
        reference: float,
        slip_bps: float,
        day: date,
        reason: str,
        index_of: dict[date, int],
    ) -> None:
        inst = intent.instrument
        key = inst.key
        costs = self._costs(run, inst, intent.side, price, qty, day)
        signed = qty if intent.side == "BUY" else -qty
        run.cash -= signed * price + costs
        run.fills.append(
            FillRecord(
                day=day,
                instrument_key=key,
                side=intent.side,
                quantity=qty,
                price=price,
                reference_price=reference,
                slippage_bps=slip_bps,
                costs=costs,
                reason=reason,
                approximate_costs=run.approximate_costs,
            )
        )
        pos = run.positions.get(key)
        if intent.intent is Intent.OPEN:
            if pos is None:
                pos = Position(instrument=inst, quantity=0, avg_price=price, entry_date=day, tag=intent.tag)
                run.positions[key] = pos
            total = pos.quantity + signed
            pos.avg_price = (pos.avg_price * pos.quantity + price * signed) / total
            pos.quantity = total
            pos.entry_costs += costs
            pos.stop = intent.stop if intent.stop is not None else pos.stop
            pos.target = intent.target if intent.target is not None else pos.target
            if pos.stop is not None:
                pos.initial_risk_per_unit = abs(pos.avg_price - pos.stop)
            pos.last_close = pos.last_close or price
            run.slippage[key] = slip_bps
        else:
            assert pos is not None  # guaranteed by _fill_pending
            self._realise(run, pos, qty, price, costs, day, "signal", index_of)

    def _costs(self, run: _Run, inst: Instrument, side: Side, price: float, qty: int, day: date) -> float:
        breakdown = india_costs(_cost_segment(inst), side, price, qty, day)  # type: ignore[arg-type]
        run.approximate_costs = run.approximate_costs or breakdown.approximate_rates
        return breakdown.total

    def _realise(
        self,
        run: _Run,
        pos: Position,
        qty: int,
        price: float,
        exit_costs: float,
        day: date,
        reason: str,
        index_of: dict[date, int],
    ) -> None:
        direction = 1 if pos.quantity > 0 else -1
        share = qty / abs(pos.quantity)
        entry_costs = pos.entry_costs * share
        gross = direction * qty * (price - pos.avg_price)
        total_costs = entry_costs + exit_costs
        net = gross - total_costs
        risk = pos.initial_risk_per_unit * qty if pos.initial_risk_per_unit else None
        run.trades.append(
            TradeRecord(
                instrument_key=pos.instrument.key,
                direction="long" if direction > 0 else "short",
                entry_date=pos.entry_date,
                exit_date=day,
                quantity=qty,
                entry_price=pos.avg_price,
                exit_price=price,
                gross_pnl=gross,
                costs=total_costs,
                net_pnl=net,
                r_multiple=net / risk if risk else None,
                exit_reason=reason,
                tag=pos.tag,
                holding_sessions=index_of.get(day, 0) - index_of.get(pos.entry_date, 0),
            )
        )
        pos.entry_costs -= entry_costs
        pos.quantity -= direction * qty
        if pos.quantity == 0:
            del run.positions[pos.instrument.key]
            run.slippage.pop(pos.instrument.key, None)

    def _exit(
        self,
        run: _Run,
        pos: Position,
        day: date,
        reference: float,
        slip_bps: float,
        reason: str,
        index_of: dict[date, int],
        *,
        with_costs: bool,
        bar: Bar | None = None,
    ) -> None:
        side: Side = "SELL" if pos.quantity > 0 else "BUY"
        qty = abs(pos.quantity)
        price = self._slip(side, reference, slip_bps, bar) if bar is not None else reference
        costs = self._costs(run, pos.instrument, side, price, qty, day) if with_costs else 0.0
        signed = qty if side == "BUY" else -qty
        run.cash -= signed * price + costs
        run.fills.append(
            FillRecord(
                day=day,
                instrument_key=pos.instrument.key,
                side=side,
                quantity=qty,
                price=price,
                reference_price=reference,
                slippage_bps=slip_bps,
                costs=costs,
                reason=reason,
                approximate_costs=run.approximate_costs,
            )
        )
        self._realise(run, pos, qty, price, costs, day, reason, index_of)

    # ----------------------------------------------------------------- step 3
    def _protective_exits(self, run: _Run, day: date, index_of: dict[date, int]) -> None:
        for key in list(run.positions):
            pos = run.positions[key]
            bar = self._bar(pos.instrument, day)
            if bar is None:
                continue
            o, lo, hi = float(bar.open), float(bar.low), float(bar.high)
            long = pos.quantity > 0
            level: float | None = None
            reason = ""
            if pos.stop is not None:
                if (long and o <= pos.stop) or (not long and o >= pos.stop):
                    level, reason = o, "stop"  # gapped through the stop
                elif (long and lo <= pos.stop) or (not long and hi >= pos.stop):
                    level, reason = pos.stop, "stop"
            if level is None and pos.target is not None:
                if (long and o >= pos.target) or (not long and o <= pos.target):
                    level, reason = o, "target"
                elif (long and hi >= pos.target) or (not long and lo <= pos.target):
                    level, reason = pos.target, "target"
            if level is not None:
                slip = run.slippage.get(key, self._config.slippage.min_bps) if reason == "stop" else 0.0
                self._exit(run, pos, day, level, slip, reason, index_of, with_costs=True, bar=bar)

    # ----------------------------------------------------------------- step 4
    def _mark(self, run: _Run, day: date, replace: bool = False) -> None:
        for pos in run.positions.values():
            bar = self._bar(pos.instrument, day)
            if bar is not None:
                pos.last_close = float(bar.close)
        values = [pos.quantity * (pos.last_close or pos.avg_price) for pos in run.positions.values()]
        equity = run.cash + sum(values)
        point = EquityPoint(
            day=day,
            equity=equity,
            cash=run.cash,
            gross_exposure=sum(abs(v) for v in values) / equity if equity > 0 else 0.0,
            net_exposure=sum(values) / equity if equity > 0 else 0.0,
        )
        if replace and run.curve and run.curve[-1].day == day:
            run.curve[-1] = point
        else:
            run.curve.append(point)

    # ----------------------------------------------------------------- step 5
    def _decide(self, run: _Run, strategy: Strategy, day: date, instruments: dict[str, Instrument]) -> None:
        as_of = ist_datetime(day, self._decision_time)
        view = PitView(self._market, self._universe, as_of)
        ctx = DecisionContext(
            day=day,
            as_of=as_of,
            view=view,
            cash=run.cash,
            equity=run.curve[-1].equity,
            positions={
                k: PositionView(p.quantity, p.avg_price, p.stop, p.target, p.entry_date, p.tag)
                for k, p in run.positions.items()
            },
            instruments={k: p.instrument for k, p in run.positions.items()},
        )
        for intent in strategy.decide(ctx):
            reason = self._validate(run, intent)
            if reason:
                run.rejections.append(Rejection(day=day, instrument_key=intent.instrument.key, reason=reason))
                continue
            slip = self._estimate_slippage(view, intent)
            if slip is None:
                run.rejections.append(
                    Rejection(
                        day=day,
                        instrument_key=intent.instrument.key,
                        reason="insufficient history for liquidity",
                    )
                )
                continue
            run.pending.append(_Pending(intent, slip, self._config.fills.order_validity_sessions))

    @staticmethod
    def _validate(run: _Run, intent: OrderIntent) -> str | None:
        inst = intent.instrument
        if inst.instrument_type is InstrumentType.INDEX:
            return "indices are not tradable"
        pos = run.positions.get(inst.key)
        if intent.intent is Intent.OPEN:
            if inst.segment is Segment.CASH and intent.side == "SELL":
                return "cash-segment shorts are intraday only"
            if pos and (pos.quantity > 0) != (intent.side == "BUY"):
                return "would flip an existing position; close it first"
        else:
            if pos is None or (pos.quantity > 0) == (intent.side == "BUY"):
                return "close order does not match an open position"
        return None

    def _estimate_slippage(self, view: PitView, intent: OrderIntent) -> float | None:
        cfg = self._config.slippage
        bars = view.bars(intent.instrument, cfg.adv_lookback_sessions + 1)
        if len(bars) < cfg.adv_lookback_sessions + 1:
            return None
        volumes = np.array([b.volume for b in bars[1:]], dtype=float)
        closes = np.array([float(b.close) for b in bars])
        adv = float(volumes.mean())
        if adv <= 0:
            return None
        sigma = float(np.std(np.diff(np.log(closes)), ddof=1))
        impact = cfg.impact_coefficient * sigma * math.sqrt(intent.quantity / adv) * 1e4
        return max(cfg.min_bps, cfg.half_spread_bps + impact)
