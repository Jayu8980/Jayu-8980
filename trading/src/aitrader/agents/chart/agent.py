"""Multi-day chart analysis agent (deterministic, numeric; never chart images).

Timeframes: monthly and weekly set the primary trend, daily gives setups and levels.
Monthly and weekly bars are resampled from adjusted daily bars; the period in progress is
excluded so a partial week or month is never read as complete. Intraday (1h / 15m) is used
for entry timing only, and only when intraday bars exist (not ingested yet).

Per-timeframe trend scores follow a fixed mapping from market structure. Their agreement
comes from ``ta.timeframe_alignment``, which lists conflicts instead of averaging them away.
Context: relative strength against NIFTY 50 over 20/50/200 sessions, the volume point of
control and the count of unfilled gaps (sector-relative strength needs a sector map).
Setups and levels:
- breakout:       daily breakout in the direction of the weekly trend;
- pullback:       weekly uptrend (downtrend) with price within one ATR of daily support
                  (resistance) or the 50-session anchored VWAP;
- mean_reversion: daily range with price at the range edge;
- none:           anything else (including mid-range), or conflicting directional
                  timeframes. Readings still report the range structure.
The structural stop sits beyond the last confirmed swing low (high) plus a quarter ATR;
targets are the next two opposing S/R levels, or 2R and 3R when none exist.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Literal

import numpy as np

from aitrader.agents.data.agent import MarketData, bar_entity, index_instrument
from aitrader.agents.data.calendar import ist_date
from aitrader.schemas.base import EvidenceKind, EvidenceRef, Horizon, Instrument, Timeframe
from aitrader.schemas.market import Bar
from aitrader.schemas.signals import (
    ChartSignal,
    SetupType,
    StructureState,
    TimeframeReading,
    VolatilityRegime,
)
from aitrader.tools.flows import resample
from aitrader.tools.technical import (
    anchored_vwap,
    gaps,
    market_structure,
    relative_strength,
    support_resistance,
    timeframe_alignment,
    volatility_state,
    volume_profile,
    wilder_atr,
)

DAILY_HISTORY = 420  # sessions: enough for ~19 monthly bars
STRUCTURE_SCORE = {
    "uptrend": 0.6,
    "breakout": 0.8,
    "failed_breakdown": 0.4,
    "range": 0.0,
    "failed_breakout": -0.4,
    "breakdown": -0.8,
    "downtrend": -0.6,
}
PIVOT_STRENGTH = {Timeframe.MN1: 1, Timeframe.W1: 2, Timeframe.D1: 3}
MIN_BARS = {Timeframe.MN1: 8, Timeframe.W1: 20, Timeframe.D1: 60}
_Q = Decimal("0.01")


def _price(x: float) -> Decimal:
    return Decimal(str(x)).quantize(_Q, rounding=ROUND_HALF_EVEN)


class ChartAgent:
    def __init__(
        self, market: MarketData, valid_for: timedelta = timedelta(days=1), benchmark: str = "NIFTY_50"
    ) -> None:
        self._market = market
        self._valid_for = valid_for
        self._benchmark = index_instrument(benchmark)

    def _relative_strength(self, daily: list[Bar], as_of: datetime) -> dict[int, float]:
        bench = self._market.raw_bars(self._benchmark, Timeframe.D1, as_of, start=daily[0].bar_start)
        bench_close = {b.bar_end: float(b.close) for b in bench}
        pairs = [(float(b.close), bench_close[b.bar_end]) for b in daily if b.bar_end in bench_close]
        if len(pairs) < 21:
            return {}
        rs = relative_strength(asset_close=[a for a, _ in pairs], benchmark_close=[x for _, x in pairs])
        return dict(rs.excess_returns)

    def _frames(
        self, daily: list[Bar]
    ) -> dict[Timeframe, tuple[list[float], list[float], list[float], list[float]]]:
        h = [float(b.high) for b in daily]
        lo = [float(b.low) for b in daily]
        c = [float(b.close) for b in daily]
        v = [float(b.volume) for b in daily]
        frames = {Timeframe.D1: (h, lo, c, v)}
        dates = [ist_date(b.bar_end) for b in daily]
        periods: tuple[tuple[Timeframe, Literal["week", "month"]], ...] = (
            (Timeframe.W1, "week"),
            (Timeframe.MN1, "month"),
        )
        for tf, period in periods:
            r = resample(
                dates=dates,
                open=[float(b.open) for b in daily],
                high=h,
                low=lo,
                close=c,
                volume=v,
                period=period,
            )
            done = [i for i, complete in enumerate(r.complete) if complete]
            frames[tf] = (
                [r.high[i] for i in done],
                [r.low[i] for i in done],
                [r.close[i] for i in done],
                [r.volume[i] for i in done],
            )
        return frames

    def analyse(self, instrument: Instrument, as_of: datetime) -> ChartSignal | None:
        series = self._market.bars(
            instrument, Timeframe.D1, as_of, start=as_of - timedelta(days=int(DAILY_HISTORY * 7 / 5) + 30)
        )
        daily = series.bars[-DAILY_HISTORY:]
        if len(daily) < MIN_BARS[Timeframe.D1] or series.unadjusted_actions:
            return None
        if ist_date(daily[-1].bar_end) != ist_date(as_of):
            return None  # no bar today (halt, suspension or data gap): no fresh signal
        frames = self._frames(daily)
        readings: list[TimeframeReading] = []
        structures: dict[Timeframe, str] = {}
        for tf in (Timeframe.MN1, Timeframe.W1, Timeframe.D1):
            h, lo, c, _ = frames[tf]
            if len(c) < MIN_BARS[tf]:
                continue
            ms = market_structure(
                high=h, low=lo, close=c, strength=PIVOT_STRENGTH[tf], atr_period=min(14, len(c) - 2)
            )
            structures[tf] = ms.state
            confirmed = ms.higher_highs is not None and ms.higher_lows is not None
            readings.append(
                TimeframeReading(
                    timeframe=tf,
                    structure=StructureState(ms.state),
                    trend_score=STRUCTURE_SCORE[ms.state],
                    confidence=0.7 if confirmed else 0.4,
                )
            )
        if Timeframe.D1 not in structures:
            return None
        alignment = timeframe_alignment(scores={r.timeframe.value: r.trend_score for r in readings})
        h, lo, c, v = frames[Timeframe.D1]
        hh, ll, cc = np.array(h), np.array(lo), np.array(c)
        atr = float(wilder_atr(hh, ll, cc, 14)[-1])
        vol = volatility_state(close=c[-120:])
        close = c[-1]
        daily_state = structures[Timeframe.D1]
        weekly = structures.get(Timeframe.W1, "range")
        weekly_dir = (
            1
            if weekly in ("uptrend", "breakout", "failed_breakdown")
            else -1
            if weekly in ("downtrend", "breakdown", "failed_breakout")
            else 0
        )
        levels = support_resistance(high=h, low=lo, close=c, strength=3, max_levels=10).levels
        supports = sorted((lv.price for lv in levels if lv.kind == "support"), reverse=True)
        resistances = sorted(lv.price for lv in levels if lv.kind == "resistance")
        vwap = anchored_vwap(high=h, low=lo, close=c, volume=v, anchor_index=max(0, len(c) - 50)).last
        ms_daily = market_structure(high=h, low=lo, close=c, strength=3)

        setup = SetupType.NONE
        direction = 0
        if alignment.direction == "mixed":
            setup = SetupType.NONE
        elif daily_state == "breakout" and weekly_dir >= 0:
            setup, direction = SetupType.BREAKOUT, 1
        elif daily_state == "breakdown" and weekly_dir <= 0:
            setup, direction = SetupType.BREAKOUT, -1
        elif (
            weekly_dir > 0
            and ((supports and close - supports[0] <= atr) or abs(close - vwap) <= atr)
            and daily_state != "breakdown"
        ):
            setup, direction = SetupType.PULLBACK, 1
        elif (
            weekly_dir < 0
            and ((resistances and resistances[0] - close <= atr) or abs(close - vwap) <= atr)
            and daily_state != "breakout"
        ):
            setup, direction = SetupType.PULLBACK, -1
        elif daily_state == "range" and supports and resistances:
            span = resistances[0] - supports[0]
            if span > 0 and close - supports[0] <= 0.2 * span:
                setup, direction = SetupType.MEAN_REVERSION, 1
            elif span > 0 and resistances[0] - close <= 0.2 * span:
                setup, direction = SetupType.MEAN_REVERSION, -1

        entry_low = entry_high = stop = None
        targets: tuple[Decimal, ...] = ()
        if direction != 0:
            if direction > 0:
                swing = (
                    ms_daily.last_swing_low
                    if ms_daily.last_swing_low and ms_daily.last_swing_low < close
                    else min(lo[-20:])
                )
                stop_level = swing - 0.25 * atr
                risk = close - stop_level
                opposing = [r for r in resistances if r > close + 0.5 * atr][:2]
                target_levels = opposing or [close + 2 * risk, close + 3 * risk]
            else:
                swing = (
                    ms_daily.last_swing_high
                    if ms_daily.last_swing_high and ms_daily.last_swing_high > close
                    else max(h[-20:])
                )
                stop_level = swing + 0.25 * atr
                risk = stop_level - close
                opposing = [s for s in supports if s < close - 0.5 * atr][:2]
                target_levels = opposing or [close - 2 * risk, close - 3 * risk]
            if risk > 0 and stop_level > 0 and all(t > 0 for t in target_levels):
                entry_low, entry_high = _price(close - 0.25 * atr), _price(close + 0.25 * atr)
                stop = _price(stop_level)
                targets = tuple(_price(t) for t in target_levels)
            else:
                setup, direction = SetupType.NONE, 0

        profile = volume_profile(close=c[-120:], volume=v[-120:], bins=24)
        open_gaps = gaps(open=[float(b.open) for b in daily], high=h, low=lo, close=c).unfilled
        score = float(np.clip(alignment.alignment_score * (abs(direction) if direction else 0.5), -1, 1))
        if direction and np.sign(score) != direction:
            score = 0.25 * direction  # counter-trend setup: weak, explicitly signed
        confidence = float(np.mean([r.confidence for r in readings])) * (0.5 if alignment.conflicts else 1.0)
        return ChartSignal(
            instrument=instrument,
            score=round(score, 6),
            confidence=round(confidence, 6),
            horizon=Horizon.SWING,
            valid_until=as_of + self._valid_for,
            evidence=(
                EvidenceRef(
                    kind=EvidenceKind.DATASET,
                    ref_id=f"bars:{bar_entity(instrument, Timeframe.D1)}@{daily[-1].bar_end.isoformat()}",
                    available_at=as_of,
                ),
            ),
            readings=tuple(readings),
            alignment_score=round(float(alignment.alignment_score), 6),
            conflicts=alignment.conflicts,
            setup=setup,
            volatility_regime=VolatilityRegime(vol.regime),
            atr=atr,
            entry_low=entry_low,
            entry_high=entry_high,
            structural_stop=stop,
            targets=targets,
            relative_strength=self._relative_strength(daily, as_of),
            volume_point_of_control=_price(profile.point_of_control),
            unfilled_gaps=len(open_gaps),
        )
