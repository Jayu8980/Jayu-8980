"""Load a directory of NSE end-of-day files into the point-in-time store.

Files are recognised by NSE's own names. Each file is audited as one ``ingest_batch`` record
(source, trade date, file SHA-256, rows written); historical bars are not broadcast on the bus.

Usage: python -m aitrader.backtest.backfill <directory> [--store data/pit.duckdb] [--audit data/audit.jsonl]
"""

from __future__ import annotations

import argparse
import asyncio
import io
import json
import re
import zipfile
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path

from aitrader.agents.data.agent import DataAgent, IngestReport
from aitrader.audit import JsonlAuditLog
from aitrader.audit.interface import AuditLog
from aitrader.bus import InMemoryBus
from aitrader.config import load_system_config
from aitrader.schemas.canonical import sha256_hex
from aitrader.storage import PitStore

PATTERNS: tuple[tuple[str, re.Pattern[str], str], ...] = (
    ("nse_cm_bhavcopy", re.compile(r"BhavCopy_NSE_CM_0_0_0_(\d{8})_F_0000\.csv(\.zip)?$"), "%Y%m%d"),
    ("nse_fo_bhavcopy", re.compile(r"BhavCopy_NSE_FO_0_0_0_(\d{8})_F_0000\.csv(\.zip)?$"), "%Y%m%d"),
    ("nse_index_close", re.compile(r"ind_close_all_(\d{8})\.csv$"), "%d%m%Y"),
    (
        "nse_cm_bhavcopy_legacy",
        re.compile(r"cm(\d{2}[A-Z]{3}\d{4})bhav\.csv(\.zip)?$", re.IGNORECASE),
        "%d%b%Y",
    ),
)


@dataclass(frozen=True)
class SourceFile:
    path: Path
    source: str
    trade_date: date


def classify(path: Path) -> SourceFile | None:
    for source, pattern, fmt in PATTERNS:
        match = pattern.search(path.name)
        if match:
            return SourceFile(path, source, datetime.strptime(match.group(1).title(), fmt).date())
    return None


def read_text(path: Path) -> str:
    raw = path.read_bytes()
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = [n for n in archive.namelist() if n.lower().endswith(".csv")]
            if len(names) != 1:
                raise ValueError(f"{path.name}: expected one CSV inside, found {names}")
            return archive.read(names[0]).decode("utf-8")
    return raw.decode("utf-8")


async def backfill(agent: DataAgent, audit: AuditLog, directory: Path) -> list[IngestReport]:
    """Ingest every recognised file, index files first (they define the trading calendar)."""
    files = [f for f in (classify(p) for p in sorted(directory.rglob("*")) if p.is_file()) if f is not None]
    files.sort(key=lambda f: (f.trade_date, f.source != "nse_index_close", f.source))
    reports = []
    for f in files:
        text = read_text(f.path)
        report = await agent.ingest_eod(f.source, text, publish=False)
        audit.append(
            "ingest_batch",
            json.dumps(
                {
                    "file": f.path.name,
                    "source": f.source,
                    "trade_date": f.trade_date.isoformat(),
                    "sha256": sha256_hex(text),
                    "parsed": report.parsed,
                    "written": report.written,
                    "rejected": len(report.rejects),
                },
                sort_keys=True,
            ),
        )
        reports.append(report)
    return reports


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--store", type=Path, default=Path("data/pit.duckdb"))
    parser.add_argument("--audit", type=Path, default=Path("data/audit.jsonl"))
    args = parser.parse_args()
    args.store.parent.mkdir(parents=True, exist_ok=True)

    def clock() -> datetime:
        return datetime.now(UTC)

    audit = JsonlAuditLog(args.audit, clock)
    agent = DataAgent(PitStore(args.store), InMemoryBus(audit), load_system_config().data, clock)
    reports = asyncio.run(backfill(agent, audit, args.directory))
    written = sum(r.written for r in reports)
    rejected = sum(len(r.rejects) for r in reports)
    print(f"{len(reports)} files, {written} bars written, {rejected} rows rejected")


if __name__ == "__main__":
    main()
