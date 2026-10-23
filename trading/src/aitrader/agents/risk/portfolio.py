"""Portfolio snapshot the risk agent assesses against."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime

from aitrader.schemas.base import Instrument


@dataclass(frozen=True)
class Holding:
    instrument: Instrument
    quantity: int  # signed
    price: float  # last close
    sector: str | None = None

    @property
    def value(self) -> float:
        return self.quantity * self.price


@dataclass(frozen=True)
class PortfolioSnapshot:
    as_of: datetime
    equity: float
    cash: float
    day_start_equity: float
    peak_equity: float
    holdings: tuple[Holding, ...] = ()

    def holding(self, key: str) -> Holding | None:
        return next((h for h in self.holdings if h.instrument.key == key), None)

    @property
    def gross(self) -> float:
        return sum(abs(h.value) for h in self.holdings)

    @property
    def net(self) -> float:
        return sum(h.value for h in self.holdings)

    def sector_exposure(self) -> dict[str, float]:
        out: dict[str, float] = defaultdict(float)
        for h in self.holdings:
            out[h.sector or "UNKNOWN"] += h.value
        return dict(out)

    @property
    def daily_drawdown(self) -> float:
        return max(0.0, 1 - self.equity / self.day_start_equity)

    @property
    def total_drawdown(self) -> float:
        return max(0.0, 1 - self.equity / self.peak_equity)
