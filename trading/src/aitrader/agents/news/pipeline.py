"""Deterministic news pipeline steps: clustering, entity linking, rule classification,
quote verification, novelty and scheduled-event extraction. The LLM path reuses all of
these; its output is only accepted after the same checks."""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Annotated

from pydantic import Field

from aitrader.agents.data.universe import Security
from aitrader.schemas.base import StrictModel, UtcDatetime
from aitrader.schemas.news import EventType, SourceTier

TIER_CREDIBILITY = {
    SourceTier.EXCHANGE_FILING: 0.95,
    SourceTier.CENTRAL_BANK_GOVT: 0.95,
    SourceTier.NEWSWIRE: 0.8,
    SourceTier.TRANSCRIPT: 0.85,
    SourceTier.SOCIAL: 0.3,
}

# Signal half-life by event type (how fast the information is priced in).
HALF_LIFE = {
    EventType.EARNINGS: timedelta(days=3),
    EventType.GUIDANCE: timedelta(days=5),
    EventType.M_AND_A: timedelta(days=10),
    EventType.CAPITAL_RAISE: timedelta(days=5),
    EventType.BUYBACK_DIVIDEND: timedelta(days=3),
    EventType.MANAGEMENT_CHANGE: timedelta(days=5),
    EventType.REGULATORY: timedelta(days=7),
    EventType.LEGAL: timedelta(days=7),
    EventType.RATING_CHANGE: timedelta(days=3),
    EventType.ORDER_WIN: timedelta(days=3),
    EventType.PROMOTER_ACTIVITY: timedelta(days=10),
    EventType.MONETARY_POLICY: timedelta(days=5),
    EventType.MACRO_DATA: timedelta(days=2),
    EventType.FISCAL_POLICY: timedelta(days=10),
    EventType.GEOPOLITICAL: timedelta(days=5),
    EventType.OTHER: timedelta(days=1),
}

# Prior materiality when only rules are available.
MATERIALITY_PRIOR = {
    EventType.EARNINGS: 0.6,
    EventType.GUIDANCE: 0.5,
    EventType.M_AND_A: 0.7,
    EventType.CAPITAL_RAISE: 0.4,
    EventType.BUYBACK_DIVIDEND: 0.3,
    EventType.MANAGEMENT_CHANGE: 0.4,
    EventType.REGULATORY: 0.5,
    EventType.LEGAL: 0.4,
    EventType.RATING_CHANGE: 0.4,
    EventType.ORDER_WIN: 0.35,
    EventType.PROMOTER_ACTIVITY: 0.5,
    EventType.MONETARY_POLICY: 0.6,
    EventType.MACRO_DATA: 0.4,
    EventType.FISCAL_POLICY: 0.6,
    EventType.GEOPOLITICAL: 0.5,
    EventType.OTHER: 0.1,
}

# Keyword rules, checked in order; patterns match NSE/BSE announcement subjects.
RULES: tuple[tuple[EventType, re.Pattern[str]], ...] = tuple(
    (kind, re.compile(pattern, re.IGNORECASE))
    for kind, pattern in (
        (EventType.EARNINGS, r"financial results|quarterly results|q[1-4] results|earnings"),
        (EventType.BUYBACK_DIVIDEND, r"buy-?back|dividend|record date"),
        (EventType.M_AND_A, r"acquisition|amalgamation|merger|scheme of arrangement|takeover|stake sale"),
        (
            EventType.CAPITAL_RAISE,
            r"qualified institutions placement|\bqip\b|rights issue"
            r"|preferential (issue|allotment)|fund raising",
        ),
        (EventType.RATING_CHANGE, r"credit rating|rating (upgrade|downgrade|reaffirm)"),
        (
            EventType.MANAGEMENT_CHANGE,
            r"resignation|appointment of|cessation|change in (management|directors?|key managerial)",
        ),
        (EventType.PROMOTER_ACTIVITY, r"pledge|promoter|regulation 31|sast|insider trading"),
        (
            EventType.ORDER_WIN,
            r"order (win|received|inflow)|bags? (an? )?order|letter of award|contract (win|award)",
        ),
        (EventType.LEGAL, r"litigation|court|tribunal|nclt|arbitration|penalty|show cause"),
        (EventType.REGULATORY, r"\bsebi\b|regulator|licen[cs]e|approval from|usfda|warning letter"),
        (EventType.GUIDANCE, r"guidance|outlook|investor presentation"),
        (EventType.MONETARY_POLICY, r"monetary policy|repo rate|\bmpc\b"),
        (EventType.MACRO_DATA, r"\bcpi\b|inflation|\bgdp\b|\biip\b|trade deficit"),
        (EventType.FISCAL_POLICY, r"union budget|fiscal deficit|gst council"),
    )
)


class RawDocument(StrictModel):
    """One piece of external text exactly as published. ``published_at`` is the source's
    publication timestamp, never the time we fetched it."""

    doc_id: Annotated[str, Field(min_length=1, max_length=200)]
    source: Annotated[str, Field(min_length=1, max_length=100)]
    source_tier: SourceTier
    published_at: UtcDatetime
    title: Annotated[str, Field(min_length=1, max_length=1000)]
    body: Annotated[str, Field(max_length=100_000)] = ""
    url: Annotated[str, Field(max_length=2000)] | None = None
    symbols: tuple[str, ...] = ()  # symbols stated by the source (e.g. the filer)

    @property
    def text(self) -> str:
        return f"{self.title}\n{self.body}".strip()


_TYPOGRAPHIC = str.maketrans({"\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"'})


def normalise(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = text.translate(_TYPOGRAPHIC)
    return re.sub(r"\s+", " ", text).strip().lower()


def verify_quote(source_text: str, quote: str) -> bool:
    """A quote counts only if it appears verbatim in the source (whitespace, case and
    typographic quotes normalised) and is long enough to carry meaning."""
    q = normalise(quote)
    return len(q) >= 12 and q in normalise(source_text)


def _shingles(text: str, k: int = 3) -> set[tuple[str, ...]]:
    words = re.findall(r"[a-z0-9]+", normalise(text))
    return {tuple(words[i : i + k]) for i in range(max(len(words) - k + 1, 1))}


def similarity(a: str, b: str) -> float:
    sa, sb = _shingles(a), _shingles(b)
    return len(sa & sb) / len(sa | sb) if sa and sb else 0.0


@dataclass
class Cluster:
    documents: list[RawDocument]

    @property
    def first(self) -> RawDocument:
        return min(self.documents, key=lambda d: (d.published_at, d.doc_id))


def cluster_documents(
    documents: Sequence[RawDocument], threshold: float = 0.5, window: timedelta = timedelta(hours=36)
) -> list[Cluster]:
    """Greedy single-pass clustering: a document joins the first cluster whose earliest
    document is within ``window``, shares a stated symbol (or neither states one) and has a
    similar headline. Deterministic for a given input order (sorted by time then id)."""
    clusters: list[Cluster] = []
    for doc in sorted(documents, key=lambda d: (d.published_at, d.doc_id)):
        for cluster in clusters:
            head = cluster.first
            same_symbols = bool(set(doc.symbols) & set(head.symbols)) or not (doc.symbols or head.symbols)
            if (
                doc.published_at - head.published_at <= window
                and same_symbols
                and similarity(doc.title, head.title) >= threshold
            ):
                cluster.documents.append(doc)
                break
        else:
            clusters.append(Cluster([doc]))
    return clusters


def link_entities(documents: Iterable[RawDocument], securities: Sequence[Security]) -> tuple[str, ...]:
    """Symbols stated by sources, plus securities whose name or alias appears in the text."""
    by_symbol = {s.symbol: s for s in securities}
    found: set[str] = set()
    for doc in documents:
        found.update(sym for sym in doc.symbols if sym in by_symbol)
        text = f" {normalise(doc.text)} "
        for sec in securities:
            names = [n for n in (sec.name, *sec.aliases) if n]
            if any(f" {normalise(n)} " in text for n in names):
                found.add(sec.symbol)
    return tuple(sorted(found))


def classify(text: str) -> EventType:
    for kind, pattern in RULES:
        if pattern.search(text):
            return kind
    return EventType.OTHER


_DATE_PATTERNS = (
    (re.compile(r"\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b"), "dmy_numeric"),
    (re.compile(r"\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9}),?\s+(\d{4})\b"), "d_month_y"),
    (re.compile(r"\b([A-Za-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b"), "month_d_y"),
)


def _month(name: str) -> int | None:
    for fmt in ("%B", "%b"):
        try:
            return datetime.strptime(name.title(), fmt).month
        except ValueError:
            continue
    return None


def find_dates(text: str) -> list[tuple[date, str]]:
    """Calendar dates written in the text, with the exact span that states each one."""
    out: list[tuple[date, str]] = []
    for pattern, kind in _DATE_PATTERNS:
        for m in pattern.finditer(text):
            try:
                if kind == "dmy_numeric":
                    d = date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
                elif kind == "d_month_y":
                    month = _month(m.group(2))
                    if month is None:
                        continue
                    d = date(int(m.group(3)), month, int(m.group(1)))
                else:
                    month = _month(m.group(1))
                    if month is None:
                        continue
                    d = date(int(m.group(3)), month, int(m.group(2)))
            except ValueError:
                continue
            out.append((d, m.group(0)))
    return out


SCHEDULING = re.compile(
    r"board meeting|intimation|to consider|record date|scheduled|will be held|meeting of", re.IGNORECASE
)


def novelty(title: str, prior_titles: Sequence[str]) -> float:
    """1 minus the closest similarity to earlier events on the same instruments."""
    return 1.0 - max((similarity(title, p) for p in prior_titles), default=0.0)


class PriorEvent(StrictModel):
    """Minimal memory of an earlier event, for novelty scoring."""

    event_id: str
    title: str
    symbols: tuple[str, ...]
    published_at: UtcDatetime
