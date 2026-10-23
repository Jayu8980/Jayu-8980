"""News analysis agent (hybrid).

For each cluster of documents reporting the same story:
1. Entity linking, classification, credibility, novelty and half-life are deterministic.
2. If an LLM caller is configured (pinned models), it extracts event type, sentiment,
   materiality, claims and any scheduled date. Its output is accepted only after checks:
   every claim's quote must appear verbatim in the cited document, affected symbols must
   come from the candidate list, and a scheduled date must be stated in its quote.
3. Otherwise, or if nothing survives verification, the rules path produces the event.

Documents published after ``as_of`` are ignored, so the agent can be replayed point in time.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Annotated, Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from aitrader.agents.data.calendar import ist_date, ist_datetime
from aitrader.agents.data.universe import Universe
from aitrader.agents.news.pipeline import (
    HALF_LIFE,
    MATERIALITY_PRIOR,
    SCHEDULING,
    TIER_CREDIBILITY,
    Cluster,
    PriorEvent,
    RawDocument,
    classify,
    cluster_documents,
    find_dates,
    link_entities,
    novelty,
    verify_quote,
)
from aitrader.bus.interface import MessageBus
from aitrader.llm.caller import StructuredCaller
from aitrader.llm.prompts import PromptTemplate, load_prompt
from aitrader.llm.untrusted import FencedDocument, fence
from aitrader.schemas.base import (
    AgentId,
    EvidenceKind,
    EvidenceRef,
    Exchange,
    Instrument,
    InstrumentType,
    Segment,
)
from aitrader.schemas.canonical import sha256_hex
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.news import EventRiskFlag, EventType, NewsClaim, NewsEvent

SCHEDULED_BLOCKING = {
    EventType.EARNINGS,
    EventType.M_AND_A,
    EventType.MONETARY_POLICY,
    EventType.FISCAL_POLICY,
}


class _Extracted(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ExtractedClaim(_Extracted):
    summary: Annotated[str, Field(min_length=1, max_length=500)]
    quote: Annotated[str, Field(min_length=1, max_length=600)]
    document_index: Annotated[int, Field(ge=0)]


class ScheduledEvent(_Extracted):
    description: Annotated[str, Field(min_length=1, max_length=300)]
    date: date
    quote: Annotated[str, Field(min_length=1, max_length=600)]


class NewsExtraction(_Extracted):
    event_type: EventType
    sentiment: Annotated[float, Field(ge=-1, le=1)]
    materiality: Annotated[float, Field(ge=0, le=1)]
    affected_symbols: list[str]
    claims: Annotated[list[ExtractedClaim], Field(max_length=5)]
    scheduled_event: ScheduledEvent | None


EXTRACTION_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "event_type": {"type": "string", "enum": [e.value for e in EventType]},
        "sentiment": {"type": "number"},
        "materiality": {"type": "number"},
        "affected_symbols": {"type": "array", "items": {"type": "string"}},
        "claims": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "quote": {"type": "string"},
                    "document_index": {"type": "integer"},
                },
                "required": ["summary", "quote", "document_index"],
                "additionalProperties": False,
            },
        },
        "scheduled_event": {
            "anyOf": [
                {
                    "type": "object",
                    "properties": {
                        "description": {"type": "string"},
                        "date": {"type": "string", "format": "date"},
                        "quote": {"type": "string"},
                    },
                    "required": ["description", "date", "quote"],
                    "additionalProperties": False,
                },
                {"type": "null"},
            ]
        },
    },
    "required": ["event_type", "sentiment", "materiality", "affected_symbols", "claims", "scheduled_event"],
    "additionalProperties": False,
}


@dataclass
class NewsResult:
    events: list[NewsEvent] = field(default_factory=list)
    flags: list[EventRiskFlag] = field(default_factory=list)
    available_at: dict[str, datetime] = field(default_factory=dict)  # event/flag id -> availability
    notes: list[str] = field(default_factory=list)  # dropped claims, fallbacks, skipped clusters


def _equity(symbol: str) -> Instrument:
    return Instrument(
        exchange=Exchange.NSE, segment=Segment.CASH, symbol=symbol, instrument_type=InstrumentType.EQUITY
    )


class NewsAgent:
    def __init__(
        self,
        clock: Any,
        universe: Universe | None = None,
        caller: StructuredCaller | None = None,
        prompt: PromptTemplate | None = None,
        novelty_lookback: timedelta = timedelta(days=30),
    ) -> None:
        self._clock = clock
        self._universe = universe
        self._caller = caller
        self._prompt = prompt or (load_prompt("news_extraction", 1) if caller else None)
        self._lookback = novelty_lookback

    def process(
        self,
        documents: Sequence[RawDocument],
        as_of: datetime,
        cycle_id: UUID,
        prior: Sequence[PriorEvent] = (),
    ) -> NewsResult:
        result = NewsResult()
        visible = [d for d in documents if d.published_at <= as_of]
        securities = self._universe.securities(as_of) if self._universe else []
        for cluster in cluster_documents(visible):
            symbols = link_entities(cluster.documents, securities) or tuple(
                sorted({s for d in cluster.documents for s in d.symbols})
            )
            event, flag = self._build(cluster, symbols, cycle_id, prior, result.notes)
            if event is None:
                continue
            available = max(d.published_at for d in cluster.documents)
            result.events.append(event)
            result.available_at[event.event_id] = available
            if flag is not None:
                result.flags.append(flag)
                result.available_at[flag.flag_id] = available
        return result

    # ------------------------------------------------------------------ building
    def _build(
        self,
        cluster: Cluster,
        symbols: tuple[str, ...],
        cycle_id: UUID,
        prior: Sequence[PriorEvent],
        notes: list[str],
    ) -> tuple[NewsEvent | None, EventRiskFlag | None]:
        docs = sorted(cluster.documents, key=lambda d: (d.published_at, d.doc_id))
        head = docs[0]
        extraction = self._extract(docs, symbols, cycle_id, notes) if self._caller else None
        claims: list[NewsClaim] = []
        scheduled: tuple[date, str] | None = None
        if extraction is not None:
            for claim in extraction.claims:
                if claim.document_index >= len(docs) or not verify_quote(
                    docs[claim.document_index].text, claim.quote
                ):
                    notes.append(f"{head.doc_id}: dropped unverifiable claim {claim.summary[:60]!r}")
                    continue
                doc = docs[claim.document_index]
                claims.append(NewsClaim(summary=claim.summary, evidence=self._evidence(doc, claim.quote)))
            unknown = set(extraction.affected_symbols) - set(symbols)
            if unknown:
                notes.append(f"{head.doc_id}: ignored symbols not in the candidate list {sorted(unknown)}")
            symbols = tuple(s for s in symbols if s in set(extraction.affected_symbols)) or symbols
            sched = extraction.scheduled_event
            if sched is not None:
                if any(verify_quote(d.text, sched.quote) for d in docs) and sched.date in {
                    d for d, _ in find_dates(sched.quote)
                }:
                    scheduled = (sched.date, sched.quote)
                else:
                    notes.append(f"{head.doc_id}: dropped scheduled date not stated in its quote")
            if not claims:
                notes.append(f"{head.doc_id}: no verifiable LLM claims; using rules")
                extraction = None
        kind = extraction.event_type if extraction else classify(head.text)
        if extraction is None:
            claims = [NewsClaim(summary=head.title[:500], evidence=self._evidence(head, head.title[:600]))]
            scheduled = self._rule_schedule(docs)
        related_prior = [
            p.title
            for p in prior
            if head.published_at - self._lookback <= p.published_at < head.published_at
            and (set(p.symbols) & set(symbols) or not (p.symbols or symbols))
        ]
        event_id = "news-" + sha256_hex("|".join(sorted(d.doc_id for d in docs)))[:20]
        event = NewsEvent(
            event_id=event_id,
            event_type=kind,
            source_tier=min((d.source_tier for d in docs), key=lambda t: -TIER_CREDIBILITY[t]),
            first_published_at=head.published_at,
            instruments=tuple(_equity(s) for s in symbols),
            sentiment=extraction.sentiment if extraction else 0.0,
            novelty=novelty(head.title, related_prior),
            materiality=extraction.materiality if extraction else MATERIALITY_PRIOR[kind],
            credibility=max(TIER_CREDIBILITY[d.source_tier] for d in docs),
            half_life=HALF_LIFE[kind],
            claims=tuple(claims),
            duplicate_document_ids=tuple(d.doc_id for d in docs[1:]),
            extraction="llm" if extraction else "rules",
        )
        flag = None
        if scheduled is not None and scheduled[0] >= ist_date(head.published_at):
            day = scheduled[0]
            flag = EventRiskFlag(
                flag_id=f"{event_id}-sched",
                event_type=kind,
                instrument=_equity(symbols[0]) if len(symbols) == 1 else None,
                window_start=ist_datetime(day - timedelta(days=1), time(0, 0)),
                window_end=ist_datetime(day + timedelta(days=1), time(23, 59)),
                block_new_entries=kind in SCHEDULED_BLOCKING,
                exposure_multiplier=0.5 if kind in SCHEDULED_BLOCKING else 1.0,
                reason=f"scheduled: {scheduled[1][:200]}",
            )
        return event, flag

    def _extract(
        self, docs: list[RawDocument], symbols: tuple[str, ...], cycle_id: UUID, notes: list[str]
    ) -> NewsExtraction | None:
        if self._caller is None or self._prompt is None:
            return None
        fenced = fence(
            [FencedDocument(i, d.source, d.published_at.isoformat(), d.text) for i, d in enumerate(docs)]
        )
        out = self._caller.call(
            role="extraction",
            prompt=self._prompt,
            values={
                "symbols": ", ".join(symbols) or "(none identified)",
                "event_types": ", ".join(e.value for e in EventType),
                "documents": fenced,
            },
            schema=EXTRACTION_SCHEMA,
            output_model=NewsExtraction,
            agent_id=AgentId.NEWS,
            cycle_id=cycle_id,
        )
        if out is None:
            notes.append(f"{docs[0].doc_id}: LLM extraction unavailable; using rules")
        return out

    @staticmethod
    def _rule_schedule(docs: list[RawDocument]) -> tuple[date, str] | None:
        for doc in docs:
            if not SCHEDULING.search(doc.text):
                continue
            published = ist_date(doc.published_at)
            future = sorted((d, span) for d, span in find_dates(doc.text) if d >= published)
            if future:
                return future[0]
        return None

    @staticmethod
    def _evidence(doc: RawDocument, quote: str) -> EvidenceRef:
        return EvidenceRef(
            kind=EvidenceKind.DOCUMENT,
            ref_id=doc.doc_id,
            available_at=doc.published_at,
            uri=doc.url,
            excerpt=quote,
        )

    # ------------------------------------------------------------------ publishing
    async def publish(self, bus: MessageBus, result: NewsResult, cycle_id: UUID) -> list[Envelope]:
        now = self._clock()
        out = []
        items: list[tuple[Topic, NewsEvent | EventRiskFlag, datetime, str]] = [
            (Topic.NEWS_EVENTS, e, e.first_published_at, e.event_id) for e in result.events
        ] + [(Topic.NEWS_RISK_FLAGS, f, result.available_at[f.flag_id], f.flag_id) for f in result.flags]
        for topic, payload, event_time, key in items:
            envelope = Envelope(
                topic=topic,
                agent_id=AgentId.NEWS,
                cycle_id=cycle_id,
                event_time=event_time,
                available_at=result.available_at[key],
                produced_at=now,
                payload=payload,
            )
            await bus.publish(envelope)
            out.append(envelope)
        return out
