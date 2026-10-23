"""Data-quality checks. Pure functions; the data agent turns findings into alerts."""

from __future__ import annotations

from collections.abc import Collection, Sequence
from datetime import date, datetime, timedelta

import numpy as np


def missing_sessions(
    sessions: Sequence[date],
    observed: Collection[date],
    *,
    listed_on: date | None = None,
    delisted_on: date | None = None,
    suspended: Collection[date] = (),
) -> list[date]:
    """Sessions on which the instrument was listed and not suspended but has no bar."""
    return [
        d
        for d in sessions
        if d not in observed
        and d not in suspended
        and (listed_on is None or d >= listed_on)
        and (delisted_on is None or d < delisted_on)
    ]


def return_outliers(
    dates: Sequence[date],
    closes: Sequence[float],
    *,
    exempt_dates: Collection[date] = (),
    threshold: float = 8.0,
    min_history: int = 30,
) -> list[date]:
    """Dates whose log return is more than ``threshold`` robust z-scores (median/MAD) from
    the median. Corporate-action ex-dates are exempt; they are explained jumps."""
    if len(dates) != len(closes):
        raise ValueError("dates and closes differ in length")
    if len(closes) <= min_history:
        return []
    c = np.asarray(closes, dtype=float)
    rets = np.diff(np.log(c))
    med = float(np.median(rets))
    mad = float(np.median(np.abs(rets - med))) * 1.4826
    if mad == 0:
        return []
    z = np.abs(rets - med) / mad
    flagged = (int(i) + 1 for i in np.where(z > threshold)[0])
    return [dates[i] for i in flagged if dates[i] not in exempt_dates]


def is_stale(last_available_at: datetime | None, now: datetime, max_age: timedelta) -> bool:
    return last_available_at is None or now - last_available_at > max_age


def source_disagreements(
    primary: dict[date, float], secondary: dict[date, float], tolerance_bps: float = 10.0
) -> list[date]:
    """Dates on which two sources' closes differ by more than ``tolerance_bps``."""
    return sorted(
        d
        for d in primary.keys() & secondary.keys()
        if abs(primary[d] - secondary[d]) / primary[d] * 1e4 > tolerance_bps
    )
