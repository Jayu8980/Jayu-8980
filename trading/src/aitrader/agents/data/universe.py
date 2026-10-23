"""Security master and index membership, both point-in-time, including delisted names.

Survivorship-bias guard: the universe on a past date is whatever the index snapshot in
force (and known) on that date said, so companies that later delisted stay in it, and their
stored bars remain readable.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Annotated

from pydantic import Field

from aitrader.agents.data.calendar import ist_date
from aitrader.schemas.base import StrictModel
from aitrader.storage.pit import PitReader, PitRecord

SECURITY_MASTER = "security_master"
INDEX_MEMBERSHIP = "index_membership"
Symbol = Annotated[str, Field(min_length=1, max_length=40, pattern=r"^[A-Z0-9&\-_.]+$")]


class Security(StrictModel):
    symbol: Symbol
    isin: Annotated[str, Field(pattern=r"^[A-Z]{2}[A-Z0-9]{9}[0-9]$")] | None = None
    listed_on: date
    delisted_on: date | None = None
    renamed_to: Symbol | None = None
    name: Annotated[str, Field(max_length=200)] | None = None  # for entity linking
    sector: Annotated[str, Field(max_length=60)] | None = None
    aliases: tuple[Annotated[str, Field(min_length=3, max_length=100)], ...] = ()


class IndexSnapshot(StrictModel):
    index: Annotated[str, Field(min_length=1, max_length=60)]
    effective_from: date
    constituents: Annotated[tuple[Symbol, ...], Field(min_length=1)]


def security_record(security: Security, available_at: datetime) -> PitRecord:
    # Reference data: the "event" is the publication of this version of the record; the
    # dates it describes (listing, delisting) live in the payload.
    return PitRecord(
        dataset=SECURITY_MASTER,
        entity=security.symbol,
        event_time=available_at,
        available_at=available_at,
        payload=security.model_dump(mode="json"),
    )


def index_record(snapshot: IndexSnapshot, available_at: datetime) -> PitRecord:
    # Reconstitutions are announced before they take effect: event_time is the announcement,
    # effective_from (payload) decides when the snapshot applies.
    return PitRecord(
        dataset=INDEX_MEMBERSHIP,
        entity=snapshot.index,
        event_time=available_at,
        available_at=available_at,
        payload=snapshot.model_dump(mode="json"),
    )


class Universe:
    def __init__(self, store: PitReader) -> None:
        self._store = store

    def members(self, index: str, as_of: datetime) -> tuple[str, ...]:
        """Constituents effective and known at ``as_of``."""
        today = ist_date(as_of)
        snapshots = [
            IndexSnapshot.model_validate(r.payload)
            for r in self._store.read_as_of(INDEX_MEMBERSHIP, as_of, entities=[index])
        ]
        effective = [snap for snap in snapshots if snap.effective_from <= today]
        if not effective:
            raise LookupError(f"no {index} membership known and effective as of {as_of.isoformat()}")
        # Rows are in announcement order, so a later correction of the same effective date wins.
        latest = max(snap.effective_from for snap in effective)
        return [snap for snap in effective if snap.effective_from == latest][-1].constituents

    def security(self, symbol: str, as_of: datetime) -> Security | None:
        rows = self._store.read_as_of(SECURITY_MASTER, as_of, entities=[symbol])
        return Security.model_validate(rows[-1].payload) if rows else None

    def securities(self, as_of: datetime) -> list[Security]:
        """Latest known version of every security in the master as of ``as_of``."""
        rows = self._store.read_as_of(SECURITY_MASTER, as_of)
        latest: dict[str, Security] = {}
        for row in rows:  # ordered by entity then event_time: the last row per entity wins
            latest[row.entity] = Security.model_validate(row.payload)
        return list(latest.values())

    def tradable(self, symbol: str, on: date, as_of: datetime) -> bool:
        sec = self.security(symbol, as_of)
        if sec is None or on < sec.listed_on:
            return False
        return sec.delisted_on is None or on < sec.delisted_on
