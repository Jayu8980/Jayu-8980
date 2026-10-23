from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from pydantic import ValidationError

from aitrader.storage import PitRecord, PitStore

T = datetime(2026, 1, 5, 10, tzinfo=UTC)


def rec(entity: str, event: datetime, available: datetime, value: int) -> PitRecord:
    return PitRecord(
        dataset="d", entity=entity, event_time=event, available_at=available, payload={"v": value}
    )


def test_records_are_invisible_before_available_at() -> None:
    store = PitStore()
    store.write([rec("A", T, T + timedelta(hours=9), 1)], ingested_at=T)
    assert store.read_as_of("d", T + timedelta(hours=8, minutes=59)) == []
    assert [r.payload["v"] for r in store.read_as_of("d", T + timedelta(hours=9))] == [1]


def test_ingestion_time_never_affects_visibility() -> None:
    store = PitStore()
    # Backfilled years later: still visible to a backtest at its true availability time.
    store.write([rec("A", T, T + timedelta(hours=1), 1)], ingested_at=T + timedelta(days=900))
    assert len(store.read_as_of("d", T + timedelta(hours=1))) == 1


def test_corrections_are_versioned_not_overwritten() -> None:
    store = PitStore()
    store.write([rec("A", T, T + timedelta(hours=1), 100)], ingested_at=T)
    store.write([rec("A", T, T + timedelta(days=3), 105)], ingested_at=T)  # restatement
    assert store.read_as_of("d", T + timedelta(days=1))[0].payload == {"v": 100}
    assert store.read_as_of("d", T + timedelta(days=3))[0].payload == {"v": 105}
    assert store.count("d") == 2


def test_writes_are_idempotent() -> None:
    store = PitStore()
    batch = [rec("A", T, T, 1), rec("B", T, T, 2)]
    assert store.write(batch, ingested_at=T) == 2
    assert store.write(batch, ingested_at=T + timedelta(hours=1)) == 0
    assert store.count() == 2


def test_lookahead_records_are_rejected_at_construction() -> None:
    with pytest.raises(ValidationError, match="cannot precede"):
        rec("A", T, T - timedelta(seconds=1), 1)


def test_filters_and_ordering() -> None:
    store = PitStore()
    store.write(
        [rec(e, T + timedelta(days=i), T + timedelta(days=i), i) for e in ("B", "A") for i in range(5)],
        ingested_at=T,
    )
    rows = store.read_as_of(
        "d", T + timedelta(days=10), entities=["A"], start=T + timedelta(days=1), end=T + timedelta(days=3)
    )
    assert [(r.entity, r.payload["v"]) for r in rows] == [("A", 1), ("A", 2), ("A", 3)]
    assert store.entities("d", T + timedelta(days=10)) == ["A", "B"]
    assert store.read_as_of("d", T, entities=[]) == []


def test_persists_to_disk(tmp_path: Path) -> None:
    path = tmp_path / "pit.duckdb"
    store = PitStore(path)
    store.write([rec("A", T, T, 1)], ingested_at=T)
    store.close()
    reopened = PitStore(path)
    assert reopened.read_as_of("d", T)[0].payload == {"v": 1}
    assert reopened.latest_available_at("d", "A") == T


def test_cached_reader_matches_the_store_exactly() -> None:
    import random

    from aitrader.storage import CachedPitReader

    store = PitStore()
    rng = random.Random(0)
    records = []
    for entity in ("A", "B"):
        for day in range(40):
            event = T + timedelta(days=day)
            records.append(rec(entity, event, event + timedelta(hours=rng.randint(0, 30)), day))
            if rng.random() < 0.2:  # a later correction
                records.append(rec(entity, event, event + timedelta(days=rng.randint(1, 10)), 1000 + day))
    store.write(records, ingested_at=T)
    cached = CachedPitReader(store)
    for _ in range(300):
        as_of = T + timedelta(hours=rng.randint(0, 60 * 24))
        start = T + timedelta(days=rng.randint(0, 20)) if rng.random() < 0.5 else None
        end = T + timedelta(days=rng.randint(20, 45)) if rng.random() < 0.5 else None
        entities = rng.choice([["A"], ["B"], ["A", "B"]])
        expected = store.read_as_of("d", as_of, entities=entities, start=start, end=end)
        assert cached.read_as_of("d", as_of, entities=entities, start=start, end=end) == expected
        assert cached.entities("d", as_of) == store.entities("d", as_of)
