"""End-of-day files for live paper trading.

``fetch`` returns a file's text, or None when it is not published (yet, or ever: holidays).
``NseFeed`` downloads from the NSE archives. ``FolderFeed`` reads the same file names from a
directory, for a machine that cannot reach NSE but receives the files some other way.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path
from typing import Protocol

from aitrader.agents.data.sources.nse import NseArchiveFetcher, archive_filename, read_archive

# The calendar reference (index closes) defines sessions; the cash bhavcopy carries the bars
# traded. F&O and participant OI enrich the analysts when present.
REQUIRED_SOURCES = ("nse_index_close", "nse_cm_bhavcopy")
OPTIONAL_SOURCES = ("nse_fo_bhavcopy", "nse_participant_oi")


class EodFeed(Protocol):
    def fetch(self, source: str, day: date) -> str | None: ...


class NseFeed:
    def __init__(self, fetcher: NseArchiveFetcher | None = None) -> None:
        self._fetcher = fetcher or NseArchiveFetcher()

    def fetch(self, source: str, day: date) -> str | None:
        try:
            return self._fetcher.fetch_text(source, day)
        except ConnectionError:  # not published, or unreachable: retried on the next step
            return None


class FolderFeed:
    def __init__(self, directory: Path) -> None:
        self._dir = directory

    def fetch(self, source: str, day: date) -> str | None:
        name = archive_filename(source, day)
        for candidate in (name, name.removesuffix(".zip")):
            path = self._dir / candidate
            if path.exists():
                return read_archive(candidate, path.read_bytes())
        return None
