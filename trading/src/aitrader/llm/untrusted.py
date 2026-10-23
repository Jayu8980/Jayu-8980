"""Fencing for untrusted external text (news, filings, transcripts, social posts).

Documents are placed inside <document> elements. Anything inside that could close or open a
fence is neutralised, so text in a document can never appear to the model as instructions
outside the data block. Content is otherwise kept verbatim so quotes can be verified
against the original source."""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass

_FENCE = re.compile(r"<\s*/?\s*(documents?|system|instructions?)\b", re.IGNORECASE)


@dataclass(frozen=True)
class FencedDocument:
    index: int
    source: str
    published_at: str
    text: str


def neutralise(text: str) -> str:
    return _FENCE.sub(lambda m: m.group(0).replace("<", "[").replace(">", "]"), text)


def fence(documents: Sequence[FencedDocument]) -> str:
    blocks = []
    for doc in documents:
        attributes = (
            f'index="{doc.index}" source="{neutralise(doc.source)}" published_at="{doc.published_at}"'
        )
        blocks.append(f"<document {attributes}>\n{neutralise(doc.text)}\n</document>")
    return "<documents>\n" + "\n".join(blocks) + "\n</documents>"
