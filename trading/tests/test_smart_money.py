import asyncio
from datetime import UTC, datetime, timedelta
from pathlib import Path

from aitrader.agents.data.agent import PARTICIPANT_OI, DataAgent
from aitrader.agents.data.sources.nse import parse_participant_oi
from aitrader.agents.smart_money.engine import SmartMoneyEngine
from aitrader.audit import InMemoryAuditLog
from aitrader.backtest.strategies import equity
from aitrader.bus import InMemoryBus
from aitrader.config import load_system_config
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, OptionType, Segment
from aitrader.storage import CachedPitReader, PitRecord, PitStore
from tests.analyst_helpers import market, regime_index, write_closes
from tests.data_helpers import eod

FIXTURES = Path(__file__).parent / "fixtures"
AAA = equity("AAA")


def future(symbol: str, expiry, lot: int = 100) -> Instrument:  # type: ignore[no-untyped-def]
    return Instrument(
        exchange=Exchange.NSE,
        segment=Segment.FNO,
        symbol=symbol,
        instrument_type=InstrumentType.FUTURE,
        expiry=expiry,
        lot_size=lot,
    )


def option(symbol: str, expiry, strike: float, kind: OptionType) -> Instrument:  # type: ignore[no-untyped-def]
    return Instrument(
        exchange=Exchange.NSE,
        segment=Segment.FNO,
        symbol=symbol,
        instrument_type=InstrumentType.OPTION,
        expiry=expiry,
        strike=strike,
        option_type=kind,
        lot_size=75,
    )


def test_participant_oi_parses_and_ingests() -> None:
    from datetime import date

    text = (FIXTURES / "fao_participant_oi_21102026.csv").read_text()
    rows = parse_participant_oi(text, date(2026, 10, 21))
    fii = next(r for r in rows if r.participant == "FII")
    assert (fii.future_index_long, fii.future_index_short) == (110_000, 160_000)
    clock = lambda: datetime(2026, 10, 21, 15, tzinfo=UTC)  # noqa: E731 - 20:30 IST
    store = PitStore()
    agent = DataAgent(store, InMemoryBus(InMemoryAuditLog(clock)), load_system_config().data, clock)
    assert asyncio.run(agent.ingest_participant_oi(text, date(2026, 10, 21))) == 5
    assert store.count(PARTICIPANT_OI) == 5


def test_long_and_short_buildup_are_scored() -> None:
    store = PitStore()
    days = regime_index(store, 80)
    expiry = days[-1] + timedelta(days=20)
    prices = [100 + 0.0 * i for i in range(60)] + [100 + 1.0 * i for i in range(20)]
    oi = [1_000_000.0] * 60 + [1_000_000 + 50_000 * i for i in range(20)]
    write_closes(store, future("AAA", expiry), days, prices, oi=oi)
    engine = SmartMoneyEngine(market(store), CachedPitReader(store))
    signal = engine.stock_signal(AAA, eod(days[-1]))
    assert signal is not None and signal.accumulation_score > 0.8 and signal.flow_persistence_days >= 5
    assert "long buildup" in signal.components[0] and signal.crowding > 0.5

    short_store = PitStore()
    days = regime_index(short_store, 80)
    falling = [100.0] * 60 + [100 - 1.0 * i for i in range(20)]
    write_closes(short_store, future("AAA", days[-1] + timedelta(days=20)), days, falling, oi=oi)
    short = SmartMoneyEngine(market(short_store), CachedPitReader(short_store)).stock_signal(
        AAA, eod(days[-1])
    )
    assert short is not None and short.accumulation_score < -0.8


def test_oi_is_summed_across_expiries_and_near_month_sets_price() -> None:
    store = PitStore()
    days = regime_index(store, 30)
    near, far = days[-1] + timedelta(days=5), days[-1] + timedelta(days=40)
    write_closes(store, future("AAA", near), days, [100.0] * 30, oi=[500.0] * 30)
    write_closes(store, future("AAA", far), days, [101.0] * 30, oi=[300.0] * 30)
    series = SmartMoneyEngine(market(store), CachedPitReader(store)).futures_series("AAA", eod(days[-1]))
    assert series[-1][1] == 100.0 and series[-1][2] == 800.0


def test_index_signal_from_participant_positioning_and_option_chain() -> None:
    store = PitStore()
    days = regime_index(store, 40)
    rows = []
    for i, d in enumerate(days):
        long_, short = 100_000 + 3_000 * i, 150_000
        payload = {
            "trade_date": d.isoformat(),
            "participant": "FII",
            "future_index_long": long_,
            "future_index_short": short,
            "future_stock_long": 0,
            "future_stock_short": 0,
            "option_index_call_long": 0,
            "option_index_put_long": 0,
            "option_index_call_short": 0,
            "option_index_put_short": 0,
            "option_stock_call_long": 0,
            "option_stock_put_long": 0,
            "option_stock_call_short": 0,
            "option_stock_put_short": 0,
            "total_long": long_,
            "total_short": short,
        }
        rows.append(
            PitRecord(
                dataset=PARTICIPANT_OI,
                entity="FII",
                event_time=eod(d) - timedelta(hours=3, minutes=30),
                available_at=eod(d) + timedelta(hours=1),
                payload=payload,
            )
        )
    store.write(rows, ingested_at=eod(days[-1]))
    expiry = days[-1] + timedelta(days=3)
    for strike, call_oi, put_oi in ((24500, 100.0, 900.0), (25000, 500.0, 500.0), (25500, 1200.0, 50.0)):
        write_closes(
            store, option("NIFTY", expiry, strike, OptionType.CALL), days[-3:], [100.0] * 3, oi=[call_oi] * 3
        )
        write_closes(
            store, option("NIFTY", expiry, strike, OptionType.PUT), days[-3:], [100.0] * 3, oi=[put_oi] * 3
        )
    engine = SmartMoneyEngine(market(store), CachedPitReader(store))
    signal = engine.index_signal(eod(days[-1]) + timedelta(hours=1))
    assert signal is not None and signal.accumulation_score > 0.3 and signal.instrument.symbol == "NIFTY_50"
    assert any("max pain 25000" in c and "call wall 25500" in c for c in signal.components)
    # Before the day's participant file is published, the engine uses the previous day's.
    earlier = engine.participant_history(eod(days[-1]))
    assert earlier[-1].trade_date == days[-2]
