"""Liquidity agent (deterministic).

Measures average daily volume, volatility, an assumed spread (until order-book data is
ingested) and square-root impact; classifies the liquidity regime; and returns a hard
maximum quantity and an execution plan. Nothing downstream may exceed ``max_quantity``.
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta
from uuid import UUID

import numpy as np

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import ist_date
from aitrader.config.settings import LiquidityLimits
from aitrader.schemas.base import Instrument, Timeframe
from aitrader.schemas.gates import ExecAlgo, LiquidityPlan, LiquidityRegime
from aitrader.tools.microstructure import impact_sqrt

SESSION_MINUTES = 375  # 09:15-15:30 IST
REGIME_FACTOR = {
    LiquidityRegime.NORMAL: 1.0,
    LiquidityRegime.EXPIRY_DAY: 0.7,
    LiquidityRegime.THIN: 0.3,
    LiquidityRegime.STRESSED: 0.5,
    LiquidityRegime.CIRCUIT_LOCKED: 0.0,
}


class LiquidityAgent:
    def __init__(self, market: MarketData, limits: LiquidityLimits) -> None:
        self._market = market
        self._limits = limits

    def stats(
        self, instrument: Instrument, as_of: datetime
    ) -> tuple[float, float, float, LiquidityRegime] | None:
        """(ADV in units, daily volatility, last close, regime) from data known at ``as_of``."""
        n = self._limits.adv_sessions
        bars = self._market.bars(instrument, Timeframe.D1, as_of, start=as_of - timedelta(days=150)).bars[
            -61:
        ]
        if len(bars) < n + 1:
            return None
        adv = float(np.mean([b.volume for b in bars[-n:]]))
        closes = np.array([float(b.close) for b in bars])
        rets = np.diff(np.log(closes))
        vol = float(np.std(rets[-n:], ddof=1))
        long_vol = float(np.std(rets, ddof=1))
        last = bars[-1]
        close = float(last.close)
        if last.high == last.low and ist_date(last.bar_end) == ist_date(as_of):
            regime = LiquidityRegime.CIRCUIT_LOCKED
        elif instrument.expiry is not None and instrument.expiry == ist_date(as_of):
            regime = LiquidityRegime.EXPIRY_DAY
        elif adv * close < self._limits.thin_adv_value_inr:
            regime = LiquidityRegime.THIN
        elif long_vol > 0 and float(np.std(rets[-5:], ddof=1)) > 2 * long_vol:
            regime = LiquidityRegime.STRESSED
        else:
            regime = LiquidityRegime.NORMAL
        return adv, vol, close, regime

    def plan(
        self, decision_id: UUID, instrument: Instrument, quantity: int, as_of: datetime
    ) -> LiquidityPlan | None:
        stats = self.stats(instrument, as_of)
        if stats is None:
            return None
        adv, vol, close, regime = stats
        lot = instrument.lot_size
        max_qty = (
            0
            if regime is LiquidityRegime.CIRCUIT_LOCKED
            else math.floor(self._limits.max_fraction_of_adv * adv / lot) * lot
        )
        qty = max(min(quantity, max_qty), 0)
        participation_of_adv = qty / adv if adv > 0 else 1.0
        freeze = self._limits.freeze_quantity.get(instrument.symbol) if instrument.expiry else None
        rate = self._limits.max_participation_rate
        if freeze is not None and qty > freeze:
            algo = ExecAlgo.ICEBERG
        elif participation_of_adv < 0.01:
            algo, rate = ExecAlgo.MARKETABLE_LIMIT, min(rate, 0.25)
        elif participation_of_adv < 0.03:
            algo = ExecAlgo.TWAP
        else:
            algo = ExecAlgo.POV
        horizon = max(1, math.ceil(qty / (adv * rate) * SESSION_MINUTES)) if adv > 0 and qty > 0 else 1
        impact = impact_sqrt(
            quantity=max(qty, 1),
            adv=adv,
            daily_vol=max(min(vol, 0.99), 1e-6),
            spread_bps=self._limits.assumed_spread_bps,
            coefficient=self._limits.impact_coefficient,
        )
        adv_value = adv * close
        score = min(1.0, adv_value / (10 * self._limits.thin_adv_value_inr)) * REGIME_FACTOR[regime]
        return LiquidityPlan(
            decision_id=decision_id,
            instrument=instrument,
            liquidity_score=round(score, 6),
            regime=regime,
            adv_shares=adv,
            spread_bps=self._limits.assumed_spread_bps,
            max_quantity=max_qty,
            freeze_quantity=freeze,
            algo=algo,
            participation_rate=rate,
            horizon_minutes=min(horizon, SESSION_MINUTES * 5),
            est_impact_bps=impact.total_bps,
            days_to_liquidate=qty / (adv * self._limits.max_participation_rate) if adv > 0 else float("inf"),
        )
