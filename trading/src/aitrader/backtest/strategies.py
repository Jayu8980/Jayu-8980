"""Reference strategies. They are deterministic baselines for testing the engine and the
validation pipeline, not claims of edge. The multi-agent decision chain plugs into the same
``Strategy`` protocol in Phase 5."""

from __future__ import annotations

from collections.abc import Sequence

import numpy as np

from aitrader.backtest.engine import DecisionContext
from aitrader.backtest.models import Intent, OrderIntent
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, Segment
from aitrader.tools.sizing import fixed_fractional
from aitrader.tools.technical import wilder_atr


def equity(symbol: str) -> Instrument:
    return Instrument(
        exchange=Exchange.NSE, segment=Segment.CASH, symbol=symbol, instrument_type=InstrumentType.EQUITY
    )


class BuyAndHold:
    """Equal-weight purchase on the first decision; a wide stop satisfies the stop rule."""

    name = "buy_and_hold"

    def __init__(
        self, instruments: Sequence[Instrument], invest_fraction: float = 0.95, stop_fraction: float = 0.5
    ):
        self._instruments = list(instruments)
        self._invest = invest_fraction
        self._stop = stop_fraction
        self._done = False

    @property
    def params(self) -> dict[str, float | int | str | bool]:
        return {"invest_fraction": self._invest, "stop_fraction": self._stop, "n": len(self._instruments)}

    def decide(self, ctx: DecisionContext) -> list[OrderIntent]:
        if self._done:
            return []
        self._done = True
        budget = ctx.equity * self._invest / len(self._instruments)
        out = []
        for inst in self._instruments:
            bars = ctx.view.bars(inst, 1)
            if not bars:
                continue
            price = float(bars[-1].close)
            qty = int(budget / price / inst.lot_size) * inst.lot_size
            if qty > 0:
                out.append(
                    OrderIntent(
                        instrument=inst,
                        side="BUY",
                        quantity=qty,
                        intent=Intent.OPEN,
                        stop=price * (1 - self._stop),
                        tag="hold",
                    )
                )
        return out


class DonchianBreakout:
    """Long-only channel breakout: enter on a close above the prior N-session high with an
    ATR stop sized to a fixed fraction of equity; exit on a close below the prior M-session low."""

    name = "donchian_breakout"

    def __init__(
        self,
        instruments: Sequence[Instrument] = (),
        *,
        universe_index: str | None = None,
        entry_lookback: int = 55,
        exit_lookback: int = 20,
        atr_period: int = 20,
        stop_atr: float = 2.0,
        target_r: float | None = None,
        risk_fraction: float = 0.005,
        max_positions: int = 10,
        max_position_fraction: float = 0.1,
    ) -> None:
        if not instruments and universe_index is None:
            raise ValueError("give instruments or a universe index")
        self._instruments = list(instruments)
        self._universe_index = universe_index
        self.entry_lookback = entry_lookback
        self.exit_lookback = exit_lookback
        self.atr_period = atr_period
        self.stop_atr = stop_atr
        self.target_r = target_r
        self.risk_fraction = risk_fraction
        self.max_positions = max_positions
        self.max_position_fraction = max_position_fraction

    @property
    def params(self) -> dict[str, float | int | str | bool]:
        return {
            "entry_lookback": self.entry_lookback,
            "exit_lookback": self.exit_lookback,
            "atr_period": self.atr_period,
            "stop_atr": self.stop_atr,
            "target_r": self.target_r or 0.0,
            "risk_fraction": self.risk_fraction,
            "max_positions": self.max_positions,
        }

    def _universe(self, ctx: DecisionContext) -> list[Instrument]:
        if self._universe_index is None:
            return self._instruments
        return [equity(symbol) for symbol in ctx.view.members(self._universe_index)]

    def decide(self, ctx: DecisionContext) -> list[OrderIntent]:
        out: list[OrderIntent] = []
        for key, pos in sorted(ctx.positions.items()):
            bars = ctx.view.bars(ctx.instruments[key], self.exit_lookback + 1)
            if len(bars) <= self.exit_lookback or bars[-1].bar_end.date() < ctx.day:
                continue
            channel_low = min(float(b.low) for b in bars[:-1])
            if float(bars[-1].close) < channel_low:
                out.append(
                    OrderIntent(
                        instrument=ctx.instruments[key],
                        side="SELL",
                        quantity=pos.quantity,
                        intent=Intent.CLOSE,
                        tag="exit",
                    )
                )
        slots = self.max_positions - len(ctx.positions)
        candidates: list[tuple[float, OrderIntent]] = []
        need = max(self.entry_lookback, self.atr_period) + 1
        for inst in self._universe(ctx):
            if slots <= 0 or inst.key in ctx.positions:
                continue
            series = ctx.view.series(inst, need)
            bars = series.bars
            if len(bars) < need or series.unadjusted_actions or bars[-1].bar_end.date() < ctx.day:
                continue
            close = float(bars[-1].close)
            channel_high = max(float(b.high) for b in bars[-self.entry_lookback - 1 : -1])
            if close <= channel_high:
                continue
            h = np.array([float(b.high) for b in bars])
            lo = np.array([float(b.low) for b in bars])
            c = np.array([float(b.close) for b in bars])
            atr = float(wilder_atr(h, lo, c, self.atr_period)[-1])
            stop = close - self.stop_atr * atr
            if stop <= 0:
                continue
            size = fixed_fractional(
                equity=ctx.equity,
                risk_fraction=self.risk_fraction,
                entry=close,
                stop=stop,
                lot_size=inst.lot_size,
                max_position_value=ctx.equity * self.max_position_fraction,
            )
            if size.quantity == 0:
                continue
            target = close + self.target_r * (close - stop) if self.target_r else None
            strength = (close - channel_high) / atr
            candidates.append(
                (
                    strength,
                    OrderIntent(
                        instrument=inst,
                        side="BUY",
                        quantity=size.quantity,
                        intent=Intent.OPEN,
                        stop=stop,
                        target=target,
                        tag="breakout",
                    ),
                )
            )
        candidates.sort(key=lambda item: (-item[0], item[1].instrument.key))
        out.extend(intent for _, intent in candidates[: max(slots, 0)])
        return out
