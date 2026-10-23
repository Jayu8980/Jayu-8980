"""Indian market time helpers. IST has no daylight saving, so a fixed offset is exact."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30), "IST")
SESSION_OPEN = time(9, 15)
SESSION_CLOSE = time(15, 30)


def ist_datetime(day: date, at: time) -> datetime:
    return datetime.combine(day, at, tzinfo=IST).astimezone(UTC)


def ist_date(ts: datetime) -> date:
    return ts.astimezone(IST).date()


def parse_hhmm(value: str) -> time:
    hours, minutes = value.split(":")
    return time(int(hours), int(minutes))


def daily_bar_window(day: date) -> tuple[datetime, datetime]:
    return ist_datetime(day, SESSION_OPEN), ist_datetime(day, SESSION_CLOSE)


def next_session_close(as_of: datetime) -> datetime:
    """Close of the session an order decided at ``as_of`` trades in: today's while the market
    is still open, otherwise the next weekday's. Exchange holidays are not known here; an
    instruction that falls on one expires untraded, which is the safe outcome."""
    day = ist_date(as_of)
    if day.weekday() < 5 and as_of < ist_datetime(day, SESSION_CLOSE):
        return ist_datetime(day, SESSION_CLOSE)
    day += timedelta(days=1)
    while day.weekday() >= 5:
        day += timedelta(days=1)
    return ist_datetime(day, SESSION_CLOSE)
