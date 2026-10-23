import asyncio
import json
import shutil
import zipfile
from datetime import UTC, date, datetime
from pathlib import Path

from aitrader.agents.data.agent import DataAgent
from aitrader.audit import InMemoryAuditLog
from aitrader.backtest.backfill import backfill, classify
from aitrader.bus import InMemoryBus
from aitrader.config import load_system_config
from aitrader.storage import PitStore

FIXTURES = Path(__file__).parent / "fixtures"


def test_classify_recognises_nse_file_names() -> None:
    assert classify(Path("BhavCopy_NSE_CM_0_0_0_20261021_F_0000.csv.zip")).source == "nse_cm_bhavcopy"  # type: ignore[union-attr]
    assert classify(Path("ind_close_all_21102026.csv")).trade_date == date(2026, 10, 21)  # type: ignore[union-attr]
    assert classify(Path("cm21OCT2019bhav.csv.zip")).trade_date == date(2019, 10, 21)  # type: ignore[union-attr]
    assert classify(Path("notes.txt")) is None


def test_backfill_ingests_audits_and_is_idempotent(tmp_path: Path) -> None:
    for name in (
        "ind_close_all_21102026.csv",
        "BhavCopy_NSE_FO_0_0_0_20261021_F_0000.csv",
        "cm21OCT2019bhav.csv",
    ):
        shutil.copy(FIXTURES / name, tmp_path / name)
    with zipfile.ZipFile(tmp_path / "BhavCopy_NSE_CM_0_0_0_20261021_F_0000.csv.zip", "w") as archive:
        archive.write(FIXTURES / "BhavCopy_NSE_CM_0_0_0_20261021_F_0000.csv", "BhavCopy.csv")
    (tmp_path / "README.txt").write_text("ignored")

    def clock() -> datetime:
        return datetime(2026, 10, 23, tzinfo=UTC)

    audit = InMemoryAuditLog(clock)
    store = PitStore()
    agent = DataAgent(store, InMemoryBus(audit), load_system_config().data, clock)
    reports = asyncio.run(backfill(agent, audit, tmp_path))
    assert [r.source for r in reports] == [
        "nse_cm_bhavcopy_legacy",
        "nse_index_close",
        "nse_cm_bhavcopy",
        "nse_fo_bhavcopy",
    ]
    assert sum(r.written for r in reports) == 2 + 2 + 3 + 4
    batches = [json.loads(r.body_json) for r in audit.records() if r.record_type == "ingest_batch"]
    assert len(batches) == 4 and all(len(b["sha256"]) == 64 for b in batches)
    assert not [r for r in audit.records() if r.record_type == "envelope" and "data.bars" in r.body_json]
    again = asyncio.run(backfill(agent, audit, tmp_path))
    assert sum(r.written for r in again) == 0
