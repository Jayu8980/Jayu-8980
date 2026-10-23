"""Acceptance tests: no lookahead and no survivorship bias in the data layer."""

import asyncio
from datetime import date, datetime, timedelta
from decimal import Decimal

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.universe import IndexSnapshot, Security, Universe, index_record, security_record
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, Segment, Timeframe
from aitrader.schemas.market import CorporateAction, CorporateActionType
from aitrader.storage import PitStore
from aitrader.tools.technical import atr
from tests.data_helpers import RELIANCE, Harness, eod, make_bar, store_bar, weekdays

DAYS = weekdays(date(2026, 3, 2), 40)


def _series(store: PitStore) -> None:
    for i, d in enumerate(DAYS):
        store_bar(store, make_bar(RELIANCE, d, 1000 + 5 * i))


def test_future_bars_are_never_visible() -> None:
    store = PitStore()
    _series(store)
    market = MarketData(store)
    as_of = eod(DAYS[19])
    bars = market.raw_bars(RELIANCE, Timeframe.D1, as_of)
    assert len(bars) == 20 and bars[-1].bar_end < as_of
    # Same day, before publication: today's bar is not yet known.
    assert len(market.raw_bars(RELIANCE, Timeframe.D1, as_of - timedelta(minutes=1))) == 19


def test_results_are_invariant_to_future_data() -> None:
    """Deleting everything after t (here: never writing it) must not change any result at t."""
    as_of = eod(DAYS[25])
    full, truncated = PitStore(), PitStore()
    _series(full)
    for i, d in enumerate(DAYS[:26]):
        store_bar(truncated, make_bar(RELIANCE, d, 1000 + 5 * i))

    def indicator(store: PitStore) -> float:
        bars = MarketData(store).bars(RELIANCE, Timeframe.D1, as_of).bars
        return atr(
            high=[float(b.high) for b in bars],
            low=[float(b.low) for b in bars],
            close=[float(b.close) for b in bars],
        ).last

    assert indicator(full) == indicator(truncated)


def _split(announced_day: date, ex_day: date) -> tuple[CorporateAction, datetime]:
    action = CorporateAction(
        instrument=RELIANCE,
        action_type=CorporateActionType.SPLIT,
        announced_at=eod(announced_day) - timedelta(hours=3),
        ex_date=ex_day,
        ratio_numerator=Decimal(2),
        ratio_denominator=Decimal(1),
    )
    return action, action.announced_at


def test_split_adjusts_only_once_known_and_effective() -> None:
    h = Harness(eod(DAYS[-1]) + timedelta(hours=1))
    _series(h.store)
    announced, ex_day = DAYS[20], DAYS[30]
    action, available_at = _split(announced, ex_day)
    asyncio.run(h.agent.ingest_corporate_actions([(action, available_at)]))
    market = h.agent.market

    before_announcement = market.bars(RELIANCE, Timeframe.D1, eod(DAYS[19]))
    assert before_announcement.bars[0].close == Decimal("1000")

    announced_not_effective = market.bars(RELIANCE, Timeframe.D1, eod(DAYS[25]))
    assert announced_not_effective.bars[0].close == Decimal("1000")  # ex-date still ahead

    after_ex = market.bars(RELIANCE, Timeframe.D1, eod(DAYS[35])).bars
    first, pre_ex, on_ex = after_ex[0], after_ex[29], after_ex[30]
    assert first.close == Decimal("500.0000") and first.adjustment_factor == Decimal("0.5")
    assert first.volume == 200_000
    assert pre_ex.close == Decimal(str((1000 + 5 * 29) / 2)).quantize(Decimal("0.0001"))
    assert on_ex.close == Decimal(1000 + 5 * 30) and on_ex.adjustment_factor == 1


def test_dividend_uses_close_before_ex_date() -> None:
    h = Harness(eod(DAYS[-1]) + timedelta(hours=1))
    _series(h.store)
    ex_day = DAYS[10]
    action = CorporateAction(
        instrument=RELIANCE,
        action_type=CorporateActionType.DIVIDEND,
        announced_at=eod(DAYS[5]),
        ex_date=ex_day,
        cash_amount=Decimal("10.45"),
    )
    asyncio.run(h.agent.ingest_corporate_actions([(action, eod(DAYS[5]))]))
    bars = h.agent.market.bars(RELIANCE, Timeframe.D1, eod(DAYS[20])).bars
    factor = 1 - Decimal("10.45") / Decimal(1045)  # close on the day before ex-date
    assert bars[0].adjustment_factor == factor
    assert bars[10].adjustment_factor == 1


def test_unsupported_actions_are_reported_not_guessed() -> None:
    h = Harness(eod(DAYS[-1]) + timedelta(hours=1))
    _series(h.store)
    rights = CorporateAction(
        instrument=RELIANCE,
        action_type=CorporateActionType.RIGHTS,
        announced_at=eod(DAYS[2]),
        ex_date=DAYS[5],
    )
    asyncio.run(h.agent.ingest_corporate_actions([(rights, eod(DAYS[2]))]))
    series = h.agent.market.bars(RELIANCE, Timeframe.D1, eod(DAYS[10]))
    assert series.unadjusted_actions and series.bars[0].adjustment_factor == 1


def test_delisted_names_stay_in_historical_universe() -> None:
    store = PitStore()
    known = eod(date(2015, 1, 1))
    gone = Instrument(
        exchange=Exchange.NSE, segment=Segment.CASH, symbol="GONECO", instrument_type=InstrumentType.EQUITY
    )
    store.write(
        [
            security_record(Security(symbol="GONECO", listed_on=date(2005, 1, 3)), known),
            security_record(
                Security(symbol="GONECO", listed_on=date(2005, 1, 3), delisted_on=date(2019, 6, 3)),
                eod(date(2019, 5, 20)),
            ),
            security_record(Security(symbol="RELIANCE", listed_on=date(1995, 11, 29)), known),
            index_record(
                IndexSnapshot(
                    index="NIFTY_50", effective_from=date(2015, 3, 27), constituents=("GONECO", "RELIANCE")
                ),
                eod(date(2015, 2, 27)),
            ),
            index_record(
                IndexSnapshot(index="NIFTY_50", effective_from=date(2019, 3, 29), constituents=("RELIANCE",)),
                eod(date(2019, 2, 25)),
            ),
        ],
        ingested_at=eod(date(2026, 1, 1)),
    )
    store_bar(store, make_bar(gone, date(2018, 6, 1), 250))
    universe = Universe(store)
    as_2018 = eod(date(2018, 6, 1))
    assert "GONECO" in universe.members("NIFTY_50", as_2018)
    assert universe.tradable("GONECO", date(2018, 6, 1), as_2018)
    assert MarketData(store).raw_bars(gone, Timeframe.D1, as_2018)  # history survives delisting
    # Reconstitution announced in Feb 2019 is not known on 2019-01-15 and not effective until March.
    assert "GONECO" in universe.members("NIFTY_50", eod(date(2019, 1, 15)))
    assert "GONECO" in universe.members("NIFTY_50", eod(date(2019, 3, 1)))
    assert "GONECO" not in universe.members("NIFTY_50", eod(date(2019, 4, 1)))
    # The delisting is only known from its announcement.
    assert universe.security("GONECO", eod(date(2019, 5, 1))).delisted_on is None  # type: ignore[union-attr]
    assert not universe.tradable("GONECO", date(2019, 6, 3), eod(date(2019, 6, 3)))
