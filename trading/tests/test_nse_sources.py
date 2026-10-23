import io
import zipfile
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from aitrader.agents.data.sources.nse import (
    NseArchiveFetcher,
    SourceFormatError,
    index_symbol,
    parse_index_close,
    parse_udiff_bhavcopy,
)
from aitrader.schemas.base import InstrumentType, OptionType, Segment

FIXTURES = Path(__file__).parent / "fixtures"
CM = (FIXTURES / "BhavCopy_NSE_CM_0_0_0_20261021_F_0000.csv").read_text()
FO = (FIXTURES / "BhavCopy_NSE_FO_0_0_0_20261021_F_0000.csv").read_text()
IDX = (FIXTURES / "ind_close_all_21102026.csv").read_text()


def test_cash_bhavcopy_keeps_eq_series_and_rejects_bad_rows() -> None:
    parsed = parse_udiff_bhavcopy(CM)
    assert parsed.trade_date == date(2026, 10, 21)
    assert [b.instrument.symbol for b in parsed.bars] == ["RELIANCE", "TCS", "M&M"]  # SM series skipped
    reliance = parsed.bars[0]
    assert reliance.close == Decimal("1420.30") and reliance.volume == 8_123_456
    assert reliance.instrument.segment is Segment.CASH and reliance.open_interest is None
    assert reliance.bar_end.isoformat() == "2026-10-21T10:00:00+00:00"  # 15:30 IST
    assert len(parsed.rejects) == 1 and "OHLC" in parsed.rejects[0][1]


def test_fo_bhavcopy_builds_futures_and_options() -> None:
    parsed = parse_udiff_bhavcopy(FO)
    kinds = {(b.instrument.symbol, b.instrument.instrument_type) for b in parsed.bars}
    assert ("NIFTY", InstrumentType.FUTURE) in kinds and ("NIFTY", InstrumentType.OPTION) in kinds
    option = next(
        b
        for b in parsed.bars
        if b.instrument.instrument_type is InstrumentType.OPTION and b.instrument.symbol == "NIFTY"
    )
    assert option.instrument.strike == Decimal("25000") and option.instrument.option_type is OptionType.CALL
    assert option.instrument.lot_size == 75 and option.open_interest == 9_000_000
    assert option.instrument.key == "NSE:NIFTY:2026-10-27:25000.00:CE"
    assert parsed.rejects == []


def test_index_close_file() -> None:
    parsed = parse_index_close(IDX)
    assert [b.instrument.symbol for b in parsed.bars] == ["NIFTY_50", "NIFTY_BANK"]
    assert parsed.bars[0].close == Decimal("25085.40")
    assert len(parsed.rejects) == 1  # "-" prices rejected, not guessed
    assert index_symbol("Nifty Midcap 150") == "NIFTY_MIDCAP_150"


def test_format_drift_stops_ingestion() -> None:
    drifted = CM.replace("ClsPric", "ClosePrice", 1)
    with pytest.raises(SourceFormatError, match="ClsPric"):
        parse_udiff_bhavcopy(drifted)


def test_mixed_dates_in_one_file_are_rejected() -> None:
    lines = CM.splitlines()
    lines[2] = lines[2].replace("2026-10-21,2026-10-21", "2026-10-22,2026-10-22", 1)
    parsed = parse_udiff_bhavcopy("\n".join(lines))
    assert any("mixed trade dates" in why for _, why in parsed.rejects)


def test_fetcher_unzips_and_retries() -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("BhavCopy.csv", CM)
    calls: list[str] = []

    def flaky(url: str) -> bytes:
        calls.append(url)
        if len(calls) == 1:
            raise OSError("reset")
        return buffer.getvalue()

    text = NseArchiveFetcher(flaky, retries=3, backoff_seconds=0).fetch_text(
        "nse_cm_bhavcopy", date(2026, 10, 21)
    )
    assert text == CM and len(calls) == 2
    assert calls[0].endswith("BhavCopy_NSE_CM_0_0_0_20261021_F_0000.csv.zip")

    def down(url: str) -> bytes:
        raise OSError("down")

    with pytest.raises(ConnectionError):
        NseArchiveFetcher(down, retries=2, backoff_seconds=0).fetch_text(
            "nse_index_close", date(2026, 10, 21)
        )
