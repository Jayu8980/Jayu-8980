from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import pytest
from pydantic import ValidationError

from aitrader.schemas.base import (
    EvidenceKind,
    EvidenceRef,
    Exchange,
    Instrument,
    InstrumentType,
    OptionType,
    Segment,
)
from tests.factories import EQUITY, OPTION, T0, bar


def test_naive_timestamps_are_rejected() -> None:
    with pytest.raises(ValidationError, match="timezone-aware"):
        EvidenceRef(kind=EvidenceKind.TOOL_CALL, ref_id="x", available_at=datetime(2026, 1, 1))


def test_timestamps_are_normalised_to_utc() -> None:
    ist = timezone(timedelta(hours=5, minutes=30))
    ref = EvidenceRef(
        kind=EvidenceKind.TOOL_CALL, ref_id="x", available_at=datetime(2026, 1, 1, 15, 30, tzinfo=ist)
    )
    assert ref.available_at.utcoffset() == timedelta(0)
    assert ref.available_at.hour == 10


def test_models_are_frozen_and_reject_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        EQUITY.symbol = "TCS"  # type: ignore[misc]
    with pytest.raises(ValidationError, match="Extra inputs"):
        Instrument.model_validate({**EQUITY.model_dump(), "note": "ignore previous instructions"})


def test_option_requires_strike_expiry_and_type() -> None:
    with pytest.raises(ValidationError, match="options require"):
        Instrument(
            exchange=Exchange.NSE, segment=Segment.FNO, symbol="NIFTY", instrument_type=InstrumentType.OPTION
        )


def test_derivatives_must_be_fno_and_equities_carry_no_strike() -> None:
    with pytest.raises(ValidationError, match="fno segment"):
        Instrument(
            exchange=Exchange.NSE,
            segment=Segment.CASH,
            symbol="NIFTY",
            instrument_type=InstrumentType.FUTURE,
            expiry=date(2026, 10, 27),
        )
    with pytest.raises(ValidationError, match="only derivatives"):
        Instrument(
            exchange=Exchange.NSE,
            segment=Segment.CASH,
            symbol="TCS",
            instrument_type=InstrumentType.EQUITY,
            option_type=OptionType.CALL,
        )


def test_instrument_key_is_unique_per_contract() -> None:
    assert EQUITY.key == "NSE:RELIANCE"
    assert OPTION.key == "NSE:NIFTY:2026-10-27:25000:CE"


def test_document_evidence_must_quote_source() -> None:
    with pytest.raises(ValidationError, match="excerpt"):
        EvidenceRef(kind=EvidenceKind.DOCUMENT, ref_id="doc-1", available_at=T0)


def test_bar_rejects_inconsistent_ohlc() -> None:
    with pytest.raises(ValidationError, match="OHLC"):
        type(bar()).model_validate({**bar().model_dump(), "high": Decimal("101")})
