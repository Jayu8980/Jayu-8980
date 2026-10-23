"""News impact analyser (deterministic).

For each news event: a market-model event study on daily bars (NIFTY 50 as the market),
volume and volatility shocks, peer contagion within the sector, overlapping-event flags, and
the event-reaction library (typical impact, spread, reversal rate and a stress quantile per
event type and regime). Everything is computed only from data available at ``as_of``; an
event whose windows have not completed by then reports only the windows it can see.

Intraday windows (5 minutes, 1 hour) need intraday bars, which are not ingested yet, so only
the 1-day and 5-day windows are produced.
"""

from __future__ import annotations

import itertools
from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta

import numpy as np

from aitrader.agents.data.agent import MarketData, index_instrument
from aitrader.agents.data.universe import Universe
from aitrader.schemas.base import Instrument, Timeframe
from aitrader.schemas.market import Bar
from aitrader.schemas.news import (
    AbnormalReturn,
    ContagionLink,
    ContagionOrder,
    EventType,
    NewsEvent,
    NewsImpact,
    ReactionWindow,
)
from aitrader.schemas.regime import RegimeLabel
from aitrader.tools.stats import EventStudyResult, event_study

ESTIMATION_SESSIONS = 120
GAP_SESSIONS = 5
WINDOWS = {1: ReactionWindow.D1, 5: ReactionWindow.D5}
OVERLAP_WINDOW = timedelta(days=7)  # events this close on the same name share attribution


@dataclass(frozen=True)
class Reaction:
    """One completed event study, the unit the reaction library is built from."""

    event_id: str
    event_type: EventType
    regime: RegimeLabel
    sentiment: float
    car_1d: float
    car_5d: float | None
    completed_at: datetime  # when the last window closed (the record is unknowable before)


@dataclass(frozen=True)
class LibraryEntry:
    event_type: EventType
    regime: RegimeLabel
    n: int
    mean_abs_car_1d: float
    mean_signed_car_1d: float  # in the direction of the event's sentiment
    std_car_1d: float
    reversal_rate: float | None  # share of events whose 5-day CAR reversed the 1-day sign
    car_5d_p05: float | None  # stress quantile for the risk agent


class ReactionLibrary:
    def __init__(self, reactions: Sequence[Reaction] = ()) -> None:
        self._reactions = list(reactions)

    def add(self, reaction: Reaction) -> None:
        self._reactions.append(reaction)

    def entry(
        self, event_type: EventType, regime: RegimeLabel, as_of: datetime, min_n: int = 5
    ) -> LibraryEntry | None:
        """Statistics over reactions completed by ``as_of``; falls back to all regimes when the
        regime-specific sample is too small."""
        known = [r for r in self._reactions if r.completed_at <= as_of and r.event_type is event_type]
        sample = [r for r in known if r.regime is regime]
        if len(sample) < min_n:
            sample = known
        if len(sample) < min_n:
            return None
        car1 = np.array([r.car_1d for r in sample])
        signed = np.array([r.car_1d * (1 if r.sentiment >= 0 else -1) for r in sample])
        pairs = [(r.car_1d, r.car_5d) for r in sample if r.car_5d is not None]
        reversal = float(np.mean([(c1 > 0) != (c5 > 0) for c1, c5 in pairs])) if pairs else None
        return LibraryEntry(
            event_type=event_type,
            regime=regime,
            n=len(sample),
            mean_abs_car_1d=float(np.mean(np.abs(car1))),
            mean_signed_car_1d=float(np.mean(signed)),
            std_car_1d=float(np.std(car1, ddof=1)) if len(car1) > 1 else 0.0,
            reversal_rate=reversal,
            car_5d_p05=float(np.quantile([c5 for _, c5 in pairs], 0.05)) if pairs else None,
        )

    def stress_scenarios(self, as_of: datetime, regime: RegimeLabel) -> dict[str, float]:
        """Scenario id -> 5th-percentile 5-day CAR per event type, for the risk agent."""
        out = {}
        for kind in EventType:
            entry = self.entry(kind, regime, as_of)
            if entry is not None and entry.car_5d_p05 is not None:
                out[f"news:{kind.value}:p05"] = entry.car_5d_p05
        return out


@dataclass(frozen=True)
class _Study:
    result: EventStudyResult
    event_bar_index: int
    bars: list[Bar]


def _returns(bars: Sequence[Bar]) -> dict[datetime, float]:
    closes = [(b.bar_end, float(b.close)) for b in bars]
    return {t1: c1 / c0 - 1 for (_, c0), (t1, c1) in itertools.pairwise(closes)}


class NewsImpactAnalyser:
    def __init__(
        self, market: MarketData, universe: Universe | None = None, market_index: str = "NIFTY_50"
    ) -> None:
        self._market = market
        self._universe = universe
        self._index = index_instrument(market_index)
        self.library = ReactionLibrary()

    def analyse(
        self,
        event: NewsEvent,
        as_of: datetime,
        regime: RegimeLabel = RegimeLabel.UNKNOWN,
        other_events: Sequence[NewsEvent] = (),
    ) -> NewsImpact | None:
        """Event study for the event's primary instrument. None when there is no price
        history to study (e.g. a market-wide event, or an unlisted name)."""
        if not event.instruments or event.first_published_at > as_of:
            return None
        inst = event.instruments[0]
        study = self._study(inst, event.first_published_at, as_of)
        if study is None:
            return None
        ars, event_bar_index, bars = study.result, study.event_bar_index, study.bars
        residual_std = ars.residual_std
        abnormal = [
            AbnormalReturn(
                window=WINDOWS[w.days], abnormal_return=w.car, t_stat=w.t_stat, expected_model="beta"
            )
            for w in ars.windows
            if w.days in WINDOWS
        ]
        volumes = np.array([b.volume for b in bars], dtype=float)
        base = volumes[max(0, event_bar_index - 20) : event_bar_index]
        volume_z = (
            float((volumes[event_bar_index] - base.mean()) / base.std(ddof=1))
            if len(base) > 2 and base.std(ddof=1) > 0
            else None
        )
        vol_z = float(abs(ars.abnormal_returns[0]) / residual_std) if residual_std > 0 else None
        entry = self.library.entry(event.event_type, regime, as_of)
        contagion = self._contagion(inst, event, entry, as_of)
        keys = {i.key for i in event.instruments}
        overlapping = tuple(
            sorted(
                e.event_id
                for e in other_events
                if e.event_id != event.event_id
                and keys & {i.key for i in e.instruments}
                and abs(e.first_published_at - event.first_published_at) <= OVERLAP_WINDOW
            )
        )
        return NewsImpact(
            event_id=event.event_id,
            abnormal_returns=tuple(abnormal),
            volume_shock_z=volume_z,
            volatility_shock_z=vol_z,
            absorption_minutes=None,
            overreaction_probability=entry.reversal_rate if entry else None,
            contagion=contagion,
            overlapping_event_ids=overlapping,
            library_sample_size=entry.n if entry else 0,
            stress_scenario_id=f"news:{event.event_type.value}:p05"
            if entry and entry.car_5d_p05 is not None
            else None,
        )

    def record(
        self, event: NewsEvent, impact: NewsImpact, regime: RegimeLabel, completed_at: datetime
    ) -> None:
        """Add a completed study to the library (callers pass the time its last window closed)."""
        cars = {a.window: a.abnormal_return for a in impact.abnormal_returns}
        if ReactionWindow.D1 not in cars:
            return
        self.library.add(
            Reaction(
                event_id=event.event_id,
                event_type=event.event_type,
                regime=regime,
                sentiment=event.sentiment,
                car_1d=cars[ReactionWindow.D1],
                car_5d=cars.get(ReactionWindow.D5),
                completed_at=completed_at,
            )
        )

    # ------------------------------------------------------------------ internals
    def _study(self, inst: Instrument, published: datetime, as_of: datetime) -> _Study | None:
        start = published - timedelta(days=int((ESTIMATION_SESSIONS + GAP_SESSIONS + 10) * 7 / 5) + 10)
        bars = self._market.bars(inst, Timeframe.D1, as_of, start=start).bars
        index_bars = self._market.raw_bars(self._index, Timeframe.D1, as_of, start=start)
        asset, market = _returns(bars), _returns(index_bars)
        common = sorted(set(asset) & set(market))
        # The event's first trading reaction is the first session closing after publication.
        event_positions = [i for i, t in enumerate(common) if t > published]
        if not event_positions:
            return None
        event_index = event_positions[0]
        if event_index < ESTIMATION_SESSIONS + GAP_SESSIONS:
            return None
        result = event_study(
            asset_returns=[asset[t] for t in common],
            market_returns=[market[t] for t in common],
            event_index=event_index,
            estimation_window=ESTIMATION_SESSIONS,
            gap=GAP_SESSIONS,
            windows=tuple(WINDOWS),
        )
        bar_times = [b.bar_end for b in bars]
        return _Study(result, bar_times.index(common[event_index]), list(bars))

    def _contagion(
        self, inst: Instrument, event: NewsEvent, entry: LibraryEntry | None, as_of: datetime
    ) -> tuple[ContagionLink, ...]:
        links = [
            ContagionLink(
                order=ContagionOrder.FIRST,
                target=inst.key,
                expected_impact_bps=(entry.mean_signed_car_1d * (1 if event.sentiment >= 0 else -1) * 1e4)
                if entry
                else 0.0,
                confidence=min(entry.n / 50, 1.0) if entry else 0.0,
            )
        ]
        if self._universe is None:
            return tuple(links)
        securities = self._universe.securities(as_of)
        sector = next((s.sector for s in securities if s.symbol == inst.symbol), None)
        if sector is None:
            return tuple(links)
        peers = sorted(s.symbol for s in securities if s.sector == sector and s.symbol != inst.symbol)
        by_peer: dict[str, list[float]] = defaultdict(list)
        for symbol in peers[:20]:
            peer = Instrument(
                exchange=inst.exchange,
                segment=inst.segment,
                symbol=symbol,
                instrument_type=inst.instrument_type,
            )
            study = self._study(peer, event.first_published_at, as_of)
            if study is not None and study.result.windows:
                by_peer[peer.key].append(study.result.windows[0].car)
        for key, cars in by_peer.items():
            links.append(
                ContagionLink(
                    order=ContagionOrder.SECOND,
                    target=key,
                    expected_impact_bps=float(np.mean(cars)) * 1e4,
                    confidence=0.3,
                )
            )
        return tuple(links)
