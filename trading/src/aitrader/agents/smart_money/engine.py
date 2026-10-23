"""Smart money engine (deterministic).

Stock level (names with futures): open-interest build-up across the whole futures chain
(long build-up, short build-up, short covering, long unwinding), its persistence, how
crowded positioning is relative to its own history, and price-versus-positioning
divergence.

Index level (NIFTY): FII net index-futures positioning from participant-wise OI, its trend
and extremity, plus PCR, max pain and OI walls from the nearest option expiry.

All inputs are read point in time. End-of-day positioning data is stamped at its
publication time by the data agent, so a decision never sees it early.

Not built (no source yet): FII/DII cash flows, block and bulk deals, insider and promoter
disclosures, and delivery percentage (UDiFF bhavcopies do not carry it). Smart Money
Concepts patterns, which the spec makes optional, are not implemented.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta

import numpy as np

from aitrader.agents.data.agent import BARS, PARTICIPANT_OI, MarketData, index_instrument
from aitrader.agents.data.calendar import ist_date
from aitrader.agents.data.sources.nse import ParticipantOI
from aitrader.schemas.base import (
    EvidenceKind,
    EvidenceRef,
    Horizon,
    Instrument,
    InstrumentType,
    OptionType,
    Timeframe,
)
from aitrader.schemas.signals import SmartMoneySignal
from aitrader.storage.pit import PitReader
from aitrader.tools.derivatives import chain_levels
from aitrader.tools.flows import oi_buildup

LOOKBACK_SESSIONS = 10  # build-up window
HISTORY_SESSIONS = 60  # for percentiles
DIVERGENCE_SESSIONS = 20


def _clip(x: float, lo: float = -1.0, hi: float = 1.0) -> float:
    return float(min(max(x, lo), hi))


class SmartMoneyEngine:
    def __init__(
        self, market: MarketData, reader: PitReader, valid_for: timedelta = timedelta(days=1)
    ) -> None:
        self._market = market
        self._reader = reader
        self._valid_for = valid_for

    # ------------------------------------------------------------------ stock level
    def futures_series(self, symbol: str, as_of: datetime) -> list[tuple[date, float, float]]:
        """(day, near-month futures close, total futures OI across expiries), oldest first."""
        start = as_of - timedelta(days=int(HISTORY_SESSIONS * 7 / 5) + 30)
        per_day: dict[date, list[tuple[date, float, int]]] = defaultdict(list)
        for inst in self._market.derivatives(symbol, as_of):
            if inst.instrument_type is not InstrumentType.FUTURE or inst.expiry is None:
                continue
            for bar in self._market.raw_bars(inst, Timeframe.D1, as_of, start=start):
                per_day[ist_date(bar.bar_end)].append((inst.expiry, float(bar.close), bar.open_interest or 0))
        out = []
        for day in sorted(per_day):
            contracts = sorted(c for c in per_day[day] if c[0] >= day)
            if contracts:
                out.append((day, contracts[0][1], float(sum(c[2] for c in contracts))))
        return out

    def stock_signal(self, underlying: Instrument, as_of: datetime) -> SmartMoneySignal | None:
        series = self.futures_series(underlying.symbol, as_of)
        if len(series) < LOOKBACK_SESSIONS + 1:
            return None
        recent = series[-(LOOKBACK_SESSIONS + 1) :]
        history = series[-HISTORY_SESSIONS:]
        build = oi_buildup(prices=[p for _, p, _ in recent], open_interest=[oi for _, _, oi in recent])
        oi_pct = (
            float(np.mean([oi < history[-1][2] for _, _, oi in history[:-1]])) if len(history) > 1 else 0.5
        )
        crowding = _clip(oi_pct * min(1.0, abs(build.score) * 1.5), 0.0, 1.0)
        prices = [p for _, p, _ in series[-DIVERGENCE_SESSIONS:]]
        at_high, at_low = prices[-1] >= max(prices), prices[-1] <= min(prices)
        divergence = (at_high and build.score < -0.2) or (at_low and build.score > 0.2)
        latest = build.labels[-1] if build.labels else "flat"
        return SmartMoneySignal(
            instrument=underlying,
            score=_clip(build.score),
            confidence=_clip(0.3 + 0.1 * build.persistence_days, 0.0, 0.8),
            horizon=Horizon.SWING,
            valid_until=as_of + self._valid_for,
            evidence=(
                EvidenceRef(
                    kind=EvidenceKind.DATASET,
                    ref_id=f"{BARS}:NSE:{underlying.symbol}:futures@{series[-1][0].isoformat()}",
                    available_at=as_of,
                ),
            ),
            accumulation_score=_clip(build.score),
            crowding=crowding,
            flow_persistence_days=build.persistence_days,
            divergence_alert=divergence,
            components=(
                f"futures OI {latest.replace('_', ' ')} (latest day)",
                f"build-up score {build.score:+.2f} over {LOOKBACK_SESSIONS} sessions",
                f"total OI at {oi_pct:.0%} of {len(history)}-session range",
            )
            + (("price at extreme against positioning",) if divergence else ()),
        )

    # ------------------------------------------------------------------ index level
    def participant_history(self, as_of: datetime, participant: str = "FII") -> list[ParticipantOI]:
        start = as_of - timedelta(days=int(HISTORY_SESSIONS * 7 / 5) + 30)
        rows = self._reader.read_as_of(PARTICIPANT_OI, as_of, entities=[participant], start=start)
        return [ParticipantOI.model_validate(r.payload) for r in rows]

    def option_chain(self, symbol: str, as_of: datetime) -> dict[str, float] | None:
        """PCR, max pain and OI walls for the nearest unexpired option expiry, from the last
        session's option bars."""
        today = ist_date(as_of)
        options = [
            i for i in self._market.derivatives(symbol, as_of) if i.instrument_type is InstrumentType.OPTION
        ]
        expiries = sorted({i.expiry for i in options if i.expiry and i.expiry >= today})
        if not expiries:
            return None
        nearest = expiries[0]
        start = as_of - timedelta(days=7)
        by_strike: dict[float, dict[str, float]] = defaultdict(lambda: {"CE": 0.0, "PE": 0.0})
        last_day: date | None = None
        for inst in (i for i in options if i.expiry == nearest):
            bars = self._market.raw_bars(inst, Timeframe.D1, as_of, start=start)
            if not bars or inst.strike is None or inst.option_type is None:
                continue
            day = ist_date(bars[-1].bar_end)
            last_day = max(last_day or day, day)
            side = "CE" if inst.option_type is OptionType.CALL else "PE"
            by_strike[float(inst.strike)][side] = float(bars[-1].open_interest or 0)
        strikes = sorted(by_strike)
        if len(strikes) < 2 or sum(by_strike[k]["CE"] for k in strikes) == 0:
            return None
        levels = chain_levels(
            strikes=strikes,
            call_oi=[by_strike[k]["CE"] for k in strikes],
            put_oi=[by_strike[k]["PE"] for k in strikes],
        )
        return {
            "pcr": levels.pcr_oi,
            "max_pain": levels.max_pain,
            "call_wall": levels.call_wall,
            "put_wall": levels.put_wall,
        }

    def index_signal(
        self, as_of: datetime, index: str = "NIFTY_50", options_symbol: str = "NIFTY"
    ) -> SmartMoneySignal | None:
        history = self.participant_history(as_of)
        if len(history) < 6:
            return None
        net = np.array(
            [
                (r.future_index_long - r.future_index_short)
                / max(r.future_index_long + r.future_index_short, 1)
                for r in history
            ]
        )
        change = float(net[-1] - net[-6])
        accumulation = _clip(float(np.tanh(change * 10)))
        diffs = np.sign(np.diff(net))
        persistence = 0
        for d in diffs[::-1]:
            if d == 0 or (persistence and d != diffs[-1]):
                break
            persistence += 1
        pct = float(np.mean(net[:-1] < net[-1]))
        crowding = abs(2 * pct - 1)
        index_bars = self._market.raw_bars(
            index_instrument(index), Timeframe.D1, as_of, start=as_of - timedelta(days=45)
        )
        closes = [float(b.close) for b in index_bars][-DIVERGENCE_SESSIONS:]
        divergence = bool(closes) and (
            (closes[-1] >= max(closes) and accumulation < -0.2)
            or (closes[-1] <= min(closes) and accumulation > 0.2)
        )
        components = [
            f"FII index futures net {net[-1]:+.2f} (long-short over gross), 5-session change {change:+.2f}",
            f"FII net positioning at {pct:.0%} of {len(net)}-session range",
        ]
        chain = self.option_chain(options_symbol, as_of)
        if chain:
            components.append(
                f"{options_symbol} nearest expiry PCR {chain['pcr']:.2f}, max pain {chain['max_pain']:.0f}, "
                f"call wall {chain['call_wall']:.0f}, put wall {chain['put_wall']:.0f}"
            )
        last = history[-1].trade_date
        return SmartMoneySignal(
            instrument=index_instrument(index),
            score=accumulation,
            confidence=_clip(0.3 + 0.1 * persistence, 0.0, 0.7),
            horizon=Horizon.SWING,
            valid_until=as_of + self._valid_for,
            evidence=(
                EvidenceRef(
                    kind=EvidenceKind.DATASET,
                    ref_id=f"{PARTICIPANT_OI}:FII@{last.isoformat()}",
                    available_at=as_of,
                ),
            ),
            accumulation_score=accumulation,
            crowding=_clip(crowding, 0.0, 1.0),
            flow_persistence_days=persistence,
            divergence_alert=divergence,
            components=tuple(components),
        )
