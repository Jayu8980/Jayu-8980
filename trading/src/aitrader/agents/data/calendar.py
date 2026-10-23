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
