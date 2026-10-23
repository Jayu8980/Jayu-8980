"""NSE end-of-day sources: UDiFF bhavcopies (cash and F&O) and the daily index close file.

Parsing is separated from fetching so parsers are tested offline. A missing expected
column raises ``SourceFormatError`` (format drift must stop ingestion, not corrupt it); a bad
row is rejected with a reason and reported as a data-quality issue.

Availability: NSE publishes these files after the close. We stamp ``available_at`` with a
fixed, conservative IST publication time per source (config ``data.availability_ist``), used
identically in backtests and live trading so the two can never see data at different times.
"""

from __future__ import annotations

import csv
import io
import re
import time as time_mod
import urllib.request
import zipfile
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

from pydantic import ValidationError

from aitrader.agents.data.calendar import daily_bar_window
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, OptionType, Segment, Timeframe
from aitrader.schemas.market import Bar

UDIFF_REQUIRED = (
    "TradDt",
    "Sgmt",
    "FinInstrmTp",
    "TckrSymb",
    "SctySrs",
    "XpryDt",
    "StrkPric",
    "OptnTp",
    "OpnPric",
    "HghPric",
    "LwPric",
    "ClsPric",
    "OpnIntrst",
    "TtlTradgVol",
    "NewBrdLotQty",
)
INDEX_REQUIRED = (
    "Index Name",
    "Index Date",
    "Open Index Value",
    "High Index Value",
    "Low Index Value",
    "Closing Index Value",
    "Volume",
)
FUTURE_TYPES = {"STF", "IDF"}
OPTION_TYPES = {"STO", "IDO"}
DEFAULT_CASH_SERIES = frozenset({"EQ", "BE"})


class SourceFormatError(ValueError):
    """The file does not have the expected layout; nothing from it is ingested."""


@dataclass
class ParsedBars:
    trade_date: date | None = None
    bars: list[Bar] = field(default_factory=list)
    rejects: list[tuple[int, str]] = field(default_factory=list)  # (line number, reason)


def _decimal(value: str) -> Decimal:
    try:
        return Decimal(value.strip())
    except InvalidOperation as exc:
        raise ValueError(f"not a number: {value!r}") from exc


def _int(value: str) -> int:
    v = value.strip()
    return 0 if v in ("", "-") else int(Decimal(v))


def _reason(exc: Exception) -> str:
    if isinstance(exc, ValidationError):
        detail = "; ".join(f"{'.'.join(map(str, e['loc'])) or 'row'}: {e['msg']}" for e in exc.errors())
    else:
        detail = str(exc)
    return f"{type(exc).__name__}: {detail}"[:300]


def _reader(text: str, required: tuple[str, ...]) -> csv.DictReader[str]:
    reader = csv.DictReader(io.StringIO(text.lstrip("﻿")))
    header = [h.strip() for h in (reader.fieldnames or [])]
    missing = [col for col in required if col not in header]
    if missing:
        raise SourceFormatError(f"missing columns: {missing}")
    reader.fieldnames = header
    return reader


def parse_udiff_bhavcopy(text: str, *, cash_series: frozenset[str] = DEFAULT_CASH_SERIES) -> ParsedBars:
    """Parse a UDiFF bhavcopy (CM or FO). Cash rows are kept only for ``cash_series``."""
    result = ParsedBars()
    for line_no, row in enumerate(_reader(text, UDIFF_REQUIRED), start=2):
        try:
            row = {k: (v or "").strip() for k, v in row.items() if k is not None}
            trade_date = date.fromisoformat(row["TradDt"])
            if result.trade_date is None:
                result.trade_date = trade_date
            elif trade_date != result.trade_date:
                raise ValueError(f"mixed trade dates {result.trade_date} and {trade_date}")
            kind = row["FinInstrmTp"]
            lot = max(_int(row["NewBrdLotQty"]), 1)
            if kind == "STK":
                if row["SctySrs"] not in cash_series:
                    continue
                instrument = Instrument(
                    exchange=Exchange.NSE,
                    segment=Segment.CASH,
                    symbol=row["TckrSymb"],
                    instrument_type=InstrumentType.EQUITY,
                )
            elif kind in FUTURE_TYPES or kind in OPTION_TYPES:
                is_option = kind in OPTION_TYPES
                instrument = Instrument(
                    exchange=Exchange.NSE,
                    segment=Segment.FNO,
                    symbol=row["TckrSymb"],
                    instrument_type=InstrumentType.OPTION if is_option else InstrumentType.FUTURE,
                    expiry=date.fromisoformat(row["XpryDt"]),
                    strike=_decimal(row["StrkPric"]) if is_option else None,
                    option_type=OptionType(row["OptnTp"]) if is_option else None,
                    lot_size=lot,
                )
            else:
                continue  # other instrument types (e.g. debt) are out of scope
            start, end = daily_bar_window(trade_date)
            oi = _int(row["OpnIntrst"])
            result.bars.append(
                Bar(
                    instrument=instrument,
                    timeframe=Timeframe.D1,
                    bar_start=start,
                    bar_end=end,
                    open=_decimal(row["OpnPric"]),
                    high=_decimal(row["HghPric"]),
                    low=_decimal(row["LwPric"]),
                    close=_decimal(row["ClsPric"]),
                    volume=_int(row["TtlTradgVol"]),
                    open_interest=oi if instrument.segment is Segment.FNO else None,
                )
            )
        except (ValueError, KeyError, ValidationError) as exc:
            result.rejects.append((line_no, _reason(exc)))
    return result


def index_symbol(name: str) -> str:
    return re.sub(r"_+", "_", re.sub(r"[^A-Z0-9]", "_", name.strip().upper())).strip("_")


def parse_index_close(text: str) -> ParsedBars:
    """Parse NSE's daily all-indices close file (ind_close_all_DDMMYYYY.csv)."""
    result = ParsedBars()
    for line_no, row in enumerate(_reader(text, INDEX_REQUIRED), start=2):
        try:
            row = {k: (v or "").strip() for k, v in row.items() if k is not None}
            trade_date = datetime.strptime(row["Index Date"], "%d-%m-%Y").date()
            if result.trade_date is None:
                result.trade_date = trade_date
            elif trade_date != result.trade_date:
                raise ValueError(f"mixed trade dates {result.trade_date} and {trade_date}")
            start, end = daily_bar_window(trade_date)
            result.bars.append(
                Bar(
                    instrument=Instrument(
                        exchange=Exchange.NSE,
                        segment=Segment.INDEX,
                        symbol=index_symbol(row["Index Name"]),
                        instrument_type=InstrumentType.INDEX,
                    ),
                    timeframe=Timeframe.D1,
                    bar_start=start,
                    bar_end=end,
                    open=_decimal(row["Open Index Value"]),
                    high=_decimal(row["High Index Value"]),
                    low=_decimal(row["Low Index Value"]),
                    close=_decimal(row["Closing Index Value"]),
                    volume=_int(row["Volume"]),
                )
            )
        except (ValueError, KeyError, ValidationError) as exc:
            result.rejects.append((line_no, _reason(exc)))
    return result


ARCHIVE_URLS = {
    "nse_cm_bhavcopy": "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_{ymd}_F_0000.csv.zip",
    "nse_fo_bhavcopy": "https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{ymd}_F_0000.csv.zip",
    "nse_index_close": "https://nsearchives.nseindia.com/content/indices/ind_close_all_{dmy}.csv",
}

Transport = Callable[[str], bytes]


def _urllib_transport(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (aitrader EOD ingest)"})
    with urllib.request.urlopen(request, timeout=30) as response:
        body: bytes = response.read()
        return body


class NseArchiveFetcher:
    """Downloads one source file for a trade date, with bounded retries and backoff."""

    def __init__(
        self, transport: Transport = _urllib_transport, retries: int = 3, backoff_seconds: float = 2.0
    ) -> None:
        self._transport = transport
        self._retries = retries
        self._backoff = backoff_seconds

    def fetch_text(self, source: str, trade_date: date) -> str:
        url = ARCHIVE_URLS[source].format(
            ymd=trade_date.strftime("%Y%m%d"), dmy=trade_date.strftime("%d%m%Y")
        )
        last_error: Exception | None = None
        for attempt in range(self._retries):
            try:
                body = self._transport(url)
                break
            except OSError as exc:
                last_error = exc
                time_mod.sleep(self._backoff * 2**attempt)
        else:
            raise ConnectionError(f"failed to fetch {url}") from last_error
        if url.endswith(".zip"):
            with zipfile.ZipFile(io.BytesIO(body)) as archive:
                names = [n for n in archive.namelist() if n.lower().endswith(".csv")]
                if len(names) != 1:
                    raise SourceFormatError(f"expected one CSV in {url}, found {names}")
                return archive.read(names[0]).decode("utf-8")
        return body.decode("utf-8")
