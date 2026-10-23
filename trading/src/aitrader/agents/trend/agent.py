"""Upcoming market trend analysis agent.

Deterministic core (runs without an LLM):
- regime evidence: Markov-switching volatility regime on NIFTY 50 returns (filtered, no
  lookahead), trend versus 50/200-session averages, 20-session momentum;
- breadth across the universe: share above 50/200-session averages, advance/decline, new
  highs and lows;
- sentiment: India VIX percentile when the index is ingested;
- sector tilts from sector-index relative strength;
- an owner-maintained macro event calendar turned into event-risk flags.

Scenario probabilities come from a transparent scoring rule (documented below) passed
through a softmax. They are probabilistic forecasts and are scored for calibration (Brier)
by ``aitrader.evaluation``. Target exposure moves toward the new view by at most
``max_exposure_change_per_cycle`` per cycle.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Annotated

import numpy as np
import yaml
from pydantic import Field

from aitrader.agents.data.agent import MarketData, index_instrument
from aitrader.agents.data.calendar import ist_date, ist_datetime
from aitrader.agents.data.universe import Universe
from aitrader.config.settings import CONFIG_DIR
from aitrader.schemas.base import (
    EvidenceKind,
    EvidenceRef,
    Exchange,
    Instrument,
    InstrumentType,
    Segment,
    StrictModel,
    Timeframe,
)
from aitrader.schemas.news import EventRiskFlag, EventType
from aitrader.schemas.regime import RegimeLabel, RegimeOutlook, Scenario, SectorTilt
from aitrader.tools.flows import Breadth, breadth
from aitrader.tools.stats import RegimeResult, regime_hmm

SCENARIOS = ("uptrend", "range", "correction")
SECTOR_INDICES = (
    "NIFTY_BANK",
    "NIFTY_IT",
    "NIFTY_FMCG",
    "NIFTY_PHARMA",
    "NIFTY_AUTO",
    "NIFTY_METAL",
    "NIFTY_ENERGY",
    "NIFTY_REALTY",
)
MIN_HISTORY = 220
HMM_MIN_VOL_RATIO = 1.5
HMM_REFIT_DAYS = 7
# Uncalibrated defaults: soften the rule's scores and shrink toward 1/3 each. Refit on real
# history with aitrader.evaluation.calibration.calibrate_trend before relying on them.
DEFAULT_TEMPERATURE = 2.5
DEFAULT_SHRINK = 0.2


class MacroEvent(StrictModel):
    name: Annotated[str, Field(min_length=1, max_length=120)]
    event_type: EventType
    date: date
    block_new_entries: bool
    exposure_multiplier: Annotated[float, Field(ge=0, le=1)] = 1.0
    days_before: Annotated[int, Field(ge=0, le=10)] = 1
    days_after: Annotated[int, Field(ge=0, le=10)] = 0


def load_events(path: Path | None = None) -> list[MacroEvent]:
    data = yaml.safe_load((path or CONFIG_DIR / "events.yaml").read_text(encoding="utf-8")) or {}
    return [MacroEvent.model_validate(e) for e in data.get("events") or []]


class TrendFeatures(StrictModel):
    """Every input to the scenario rule, published alongside the outlook for audit."""

    index_return_20: float
    above_50dma: bool
    above_200dma: bool
    dma50_slope: float  # 20-session change of the 50-session average, fractional
    high_vol_probability: float | None
    pct_above_50dma: float | None
    pct_above_200dma: float | None
    new_high_minus_low: float | None  # (highs - lows) / members
    vix_percentile: float | None


def scenario_scores(f: TrendFeatures) -> dict[str, float]:
    """Transparent rule: positive trend, momentum and breadth favour 'uptrend'; their
    negatives favour 'correction'; high volatility and VIX favour 'correction'; weak or
    mixed signals favour 'range'. Scores are log-odds-like and go through a softmax."""
    trend = (
        (0.6 if f.above_200dma else -0.6)
        + (0.4 if f.above_50dma else -0.4)
        + float(np.tanh(f.dma50_slope * 50))
    )
    momentum = float(np.tanh(f.index_return_20 * 15))
    breadth_term = 0.0
    if f.pct_above_50dma is not None:
        breadth_term += (f.pct_above_50dma - 0.5) * 2
    if f.new_high_minus_low is not None:
        breadth_term += float(np.tanh(f.new_high_minus_low * 10))
    stress = 0.0
    if f.high_vol_probability is not None:
        stress += f.high_vol_probability * 1.5
    if f.vix_percentile is not None:
        stress += max(f.vix_percentile - 0.7, 0) * 4
    directional = trend + momentum + breadth_term
    return {
        "uptrend": directional - 0.5 * stress,
        "range": 0.8 - 0.5 * abs(directional),
        "correction": -directional + stress,
    }


def softmax(scores: dict[str, float], temperature: float = 1.0, shrink: float = 0.0) -> dict[str, float]:
    """Softmax of ``scores / temperature``, mixed with the uniform distribution by ``shrink``."""
    if temperature <= 0 or not 0 <= shrink < 1:
        raise ValueError("temperature must be > 0 and shrink in [0, 1)")
    peak = max(scores.values())
    exps = {k: math.exp((v - peak) / temperature) for k, v in scores.items()}
    total = sum(exps.values())
    probs = {k: (1 - shrink) * v / total + shrink / len(scores) for k, v in exps.items()}
    # Round to 6 dp and put the rounding residue on the largest so the sum is exactly 1.
    rounded = {k: round(v, 6) for k, v in probs.items()}
    top = max(rounded, key=lambda k: rounded[k])
    rounded[top] = round(1 - sum(v for k, v in rounded.items() if k != top), 6)
    return rounded


class TrendAgent:
    def __init__(
        self,
        market: MarketData,
        universe: Universe | None = None,
        *,
        universe_index: str = "NIFTY_500",
        events: Sequence[MacroEvent] = (),
        max_exposure_change: float = 0.2,
        horizon: timedelta = timedelta(days=28),
        temperature: float = DEFAULT_TEMPERATURE,
        shrink: float = DEFAULT_SHRINK,
    ) -> None:
        self.temperature = temperature
        self.shrink = shrink
        self._hmm_cache: tuple[date, tuple[float, ...], float] | None = None
        self._market = market
        self._universe = universe
        self._universe_index = universe_index
        self._events = list(events)
        self._max_change = max_exposure_change
        self._horizon = horizon
        self.previous_exposure: float | None = None

    def features(self, as_of: datetime) -> TrendFeatures | None:
        start = as_of - timedelta(days=int(MIN_HISTORY * 7 / 5) + 120)
        bars = self._market.raw_bars(index_instrument("NIFTY_50"), Timeframe.D1, as_of, start=start)
        if len(bars) < MIN_HISTORY:
            return None
        closes = np.array([float(b.close) for b in bars])
        rets = np.diff(np.log(closes))
        dma50, dma200 = closes[-50:].mean(), closes[-200:].mean()
        dma50_prev = closes[-70:-20].mean()
        try:
            hmm = self._regime(rets[-500:], ist_date(as_of))
            # With a single regime in the window the "high-vol" state is arbitrary; only trust
            # it when the fitted regimes genuinely differ.
            distinct = hmm.regime_vols[-1] >= HMM_MIN_VOL_RATIO * hmm.regime_vols[0]
            high_vol = hmm.current_probabilities[-1] if distinct else 0.0
        except (ArithmeticError, ValueError):
            high_vol = None
        b = self._breadth(as_of)
        vix = self._market.raw_bars(index_instrument("INDIA_VIX"), Timeframe.D1, as_of, start=start)
        vix_pct = None
        if len(vix) >= 60:
            levels = np.array([float(v.close) for v in vix[-250:]])
            vix_pct = float(np.mean(levels[:-1] < levels[-1]))
        return TrendFeatures(
            index_return_20=float(closes[-1] / closes[-21] - 1),
            above_50dma=bool(closes[-1] > dma50),
            above_200dma=bool(closes[-1] > dma200),
            dma50_slope=float(dma50 / dma50_prev - 1),
            high_vol_probability=high_vol,
            pct_above_50dma=b.pct_above_50dma if b else None,
            pct_above_200dma=b.pct_above_200dma if b else None,
            new_high_minus_low=(b.new_highs - b.new_lows) / b.members if b and b.members else None,
            vix_percentile=vix_pct,
        )

    def _regime(self, rets: np.ndarray, today: date) -> RegimeResult:
        """Re-estimate the regime model at most every ``HMM_REFIT_DAYS`` calendar days; in
        between, filter the new data through the last parameters (fitted on earlier data
        only, so this stays point in time)."""
        cache = self._hmm_cache
        if cache is not None and 0 <= (today - cache[0]).days < HMM_REFIT_DAYS:
            try:
                return regime_hmm(returns=rets.tolist(), fixed_params=cache[1], fixed_scale=cache[2])
            except ArithmeticError:
                pass
        fit = regime_hmm(returns=rets.tolist())
        self._hmm_cache = (today, fit.params, fit.scale)
        return fit

    def _breadth(self, as_of: datetime) -> Breadth | None:
        if self._universe is None:
            return None
        try:
            members = self._universe.members(self._universe_index, as_of)
        except LookupError:
            return None
        series = []
        start = as_of - timedelta(days=400)
        for symbol in members:
            inst = Instrument(
                exchange=Exchange.NSE,
                segment=Segment.CASH,
                symbol=symbol,
                instrument_type=InstrumentType.EQUITY,
            )
            bars = self._market.bars(inst, Timeframe.D1, as_of, start=start).bars
            if len(bars) >= 2 and ist_date(bars[-1].bar_end) == ist_date(as_of):
                series.append([float(x.close) for x in bars])
        return breadth(closes=series) if series else None

    def sector_tilts(self, as_of: datetime) -> tuple[SectorTilt, ...]:
        start = as_of - timedelta(days=120)
        bench = self._market.raw_bars(index_instrument("NIFTY_50"), Timeframe.D1, as_of, start=start)
        if len(bench) < 51:
            return ()
        bench_ret = float(bench[-1].close) / float(bench[-51].close) - 1
        tilts = []
        for name in SECTOR_INDICES:
            bars = self._market.raw_bars(index_instrument(name), Timeframe.D1, as_of, start=start)
            if len(bars) >= 51:
                excess = float(bars[-1].close) / float(bars[-51].close) - 1 - bench_ret
                tilts.append(SectorTilt(sector=name, tilt=float(np.tanh(excess * 10))))
        return tuple(tilts)

    def event_flags(self, as_of: datetime) -> list[EventRiskFlag]:
        today = ist_date(as_of)
        flags = []
        for e in self._events:
            if not (today <= e.date + timedelta(days=e.days_after) and e.date - today <= self._horizon):
                continue
            flags.append(
                EventRiskFlag(
                    flag_id=f"macro-{e.event_type.value}-{e.date.isoformat()}",
                    event_type=e.event_type,
                    instrument=None,
                    window_start=ist_datetime(e.date - timedelta(days=e.days_before), time(0, 0)),
                    window_end=ist_datetime(e.date + timedelta(days=e.days_after), time(23, 59)),
                    block_new_entries=e.block_new_entries,
                    exposure_multiplier=e.exposure_multiplier,
                    reason=e.name,
                )
            )
        return flags

    def outlook(self, as_of: datetime) -> tuple[RegimeOutlook | None, TrendFeatures | None]:
        f = self.features(as_of)
        if f is None:
            return None, None
        probs = softmax(scenario_scores(f), self.temperature, self.shrink)
        evidence = (
            EvidenceRef(
                kind=EvidenceKind.DATASET,
                ref_id=f"bars:NSE:NIFTY_50|1d@{as_of.isoformat()}",
                available_at=as_of,
            ),
        )
        stressed = (f.high_vol_probability or 0) > 0.7 or (f.vix_percentile or 0) > 0.9
        top = max(probs, key=lambda k: probs[k])
        if stressed:
            regime = RegimeLabel.HIGH_VOL_STRESS
        elif top == "uptrend":
            regime = RegimeLabel.BULL_TREND  # labels follow the most likely scenario: calibrated
        elif top == "correction":  # probabilities are deliberately soft, so no fixed threshold
            regime = RegimeLabel.BEAR_TREND
        else:
            regime = RegimeLabel.RANGE
        target = min(max(probs["uptrend"] + 0.5 * probs["range"], 0.2), 1.0)
        if self.previous_exposure is not None:
            step = max(min(target - self.previous_exposure, self._max_change), -self._max_change)
            target = self.previous_exposure + step
        self.previous_exposure = target
        warnings = []
        if f.above_50dma and f.pct_above_50dma is not None and f.pct_above_50dma < 0.4:
            warnings.append("index above its 50-session average while most members are below theirs")
        if (f.vix_percentile or 0) > 0.9:
            warnings.append(f"India VIX at the {f.vix_percentile:.0%} percentile of the past year")
        if (f.high_vol_probability or 0) > 0.5:
            warnings.append(f"high-volatility regime probability {f.high_vol_probability:.0%}")
        outlook = RegimeOutlook(
            regime=regime,
            confidence=probs[top],
            scenarios=tuple(Scenario(name=k, probability=probs[k], evidence=evidence) for k in SCENARIOS),
            sector_tilts=self.sector_tilts(as_of),
            early_warnings=tuple(warnings),
            exposure_multiplier=round(target, 6),
            forecast_horizon_end=as_of + self._horizon,
        )
        return outlook, f
