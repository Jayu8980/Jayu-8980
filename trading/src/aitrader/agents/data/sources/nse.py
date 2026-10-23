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
from typing import Annotated, Literal

from pydantic import Field, ValidationError

from aitrader.agents.data.calendar import daily_bar_window
from aitrader.schemas.base import (
    Exchange,
    Instrument,
    InstrumentType,
    OptionType,
    Segment,
    StrictModel,
    Timeframe,
)
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


LEGACY_CM_REQUIRED = ("SYMBOL", "SERIES", "OPEN", "HIGH", "LOW", "CLOSE", "TOTTRDQTY", "TIMESTAMP")


def parse_legacy_cm_bhavcopy(text: str, *, cash_series: frozenset[str] = DEFAULT_CASH_SERIES) -> ParsedBars:
    """Parse the pre-July-2024 cash bhavcopy (cmDDMONYYYYbhav.csv)."""
    result = ParsedBars()
    for line_no, row in enumerate(_reader(text, LEGACY_CM_REQUIRED), start=2):
        try:
            row = {k: (v or "").strip() for k, v in row.items() if k}
            if row["SERIES"] not in cash_series:
                continue
            trade_date = datetime.strptime(row["TIMESTAMP"].title(), "%d-%b-%Y").date()
            if result.trade_date is None:
                result.trade_date = trade_date
            elif trade_date != result.trade_date:
                raise ValueError(f"mixed trade dates {result.trade_date} and {trade_date}")
            start, end = daily_bar_window(trade_date)
            result.bars.append(
                Bar(
                    instrument=Instrument(
                        exchange=Exchange.NSE,
                        segment=Segment.CASH,
                        symbol=row["SYMBOL"],
                        instrument_type=InstrumentType.EQUITY,
                    ),
                    timeframe=Timeframe.D1,
                    bar_start=start,
                    bar_end=end,
                    open=_decimal(row["OPEN"]),
                    high=_decimal(row["HIGH"]),
                    low=_decimal(row["LOW"]),
                    close=_decimal(row["CLOSE"]),
                    volume=_int(row["TOTTRDQTY"]),
                )
            )
        except (ValueError, KeyError, ValidationError) as exc:
            result.rejects.append((line_no, _reason(exc)))
    return result


PARTICIPANT_COLUMNS = (
    "Client Type",
    "Future Index Long",
    "Future Index Short",
    "Future Stock Long",
    "Future Stock Short",
    "Option Index Call Long",
    "Option Index Put Long",
    "Option Index Call Short",
    "Option Index Put Short",
    "Option Stock Call Long",
    "Option Stock Put Long",
    "Option Stock Call Short",
    "Option Stock Put Short",
    "Total Long Contracts",
    "Total Short Contracts",
)
PARTICIPANTS = ("Client", "DII", "FII", "Pro", "TOTAL")


class ParticipantOI(StrictModel):
    """Participant-wise open interest (contracts) for one participant class on one day."""

    trade_date: date
    participant: Literal["Client", "DII", "FII", "Pro", "TOTAL"]
    future_index_long: Annotated[int, Field(ge=0)]
    future_index_short: Annotated[int, Field(ge=0)]
    future_stock_long: Annotated[int, Field(ge=0)]
    future_stock_short: Annotated[int, Field(ge=0)]
    option_index_call_long: Annotated[int, Field(ge=0)]
    option_index_put_long: Annotated[int, Field(ge=0)]
    option_index_call_short: Annotated[int, Field(ge=0)]
    option_index_put_short: Annotated[int, Field(ge=0)]
    option_stock_call_long: Annotated[int, Field(ge=0)]
    option_stock_put_long: Annotated[int, Field(ge=0)]
    option_stock_call_short: Annotated[int, Field(ge=0)]
    option_stock_put_short: Annotated[int, Field(ge=0)]
    total_long: Annotated[int, Field(ge=0)]
    total_short: Annotated[int, Field(ge=0)]


def parse_participant_oi(text: str, trade_date: date) -> list[ParticipantOI]:
    """Parse fao_participant_oi_DDMMYYYY.csv. The first line is a title ("... as on Oct 21,
    2026"); if it states a date it must match the date in the file name."""
    lines = [line for line in text.lstrip("\ufeff").splitlines() if line.strip()]
    if not lines:
        raise SourceFormatError("empty participant OI file")
    title = lines[0]
    if not title.strip().strip('"').startswith("Client Type"):
        match = re.search(r"as on\s+([A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4})", title)
        if match:
            stated = datetime.strptime(match.group(1).replace(",", ""), "%b %d %Y").date()
            if stated != trade_date:
                raise SourceFormatError(f"title date {stated} does not match file date {trade_date}")
        lines = lines[1:]
    reader = _reader("\n".join(lines), PARTICIPANT_COLUMNS)
    rows: list[ParticipantOI] = []
    for row in reader:
        clean = {k: (v or "").strip() for k, v in row.items() if k}
        if clean["Client Type"] not in PARTICIPANTS:
            continue
        values = [_int(clean[c]) for c in PARTICIPANT_COLUMNS[1:]]
        fields = [f for f in ParticipantOI.model_fields if f not in ("trade_date", "participant")]
        rows.append(
            ParticipantOI(
                trade_date=trade_date,
                participant=clean["Client Type"],
                **dict(zip(fields, values, strict=True)),
            )
        )
    if {r.participant for r in rows} != set(PARTICIPANTS):
        raise SourceFormatError(f"expected rows for {PARTICIPANTS}, got {[r.participant for r in rows]}")
    return rows


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
    "nse_participant_oi": "https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_{dmy}.csv",
    "nse_cm_bhavcopy_legacy": (
        "https://nsearchives.nseindia.com/content/historical/EQUITIES/{yyyy}/{mon}/cm{dd}{mon}{yyyy}bhav.csv.zip"
    ),
}

Transport = Callable[[str], bytes]


def archive_url(source: str, trade_date: date) -> str:
    return ARCHIVE_URLS[source].format(
        ymd=trade_date.strftime("%Y%m%d"),
        dmy=trade_date.strftime("%d%m%Y"),
        dd=trade_date.strftime("%d"),
        mon=trade_date.strftime("%b").upper(),
        yyyy=trade_date.strftime("%Y"),
    )


def archive_filename(source: str, trade_date: date) -> str:
    """The file name NSE publishes, e.g. for a drop folder fed by another process."""
    return archive_url(source, trade_date).rsplit("/", 1)[1]


def read_archive(name: str, body: bytes) -> str:
    """CSV text from a published file, unzipping when needed."""
    if name.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            names = [n for n in archive.namelist() if n.lower().endswith(".csv")]
            if len(names) != 1:
                raise SourceFormatError(f"expected one CSV in {name}, found {names}")
            return archive.read(names[0]).decode("utf-8")
    return body.decode("utf-8")


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
        url = archive_url(source, trade_date)
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
        return read_archive(url, body)
