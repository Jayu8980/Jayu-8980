import itertools
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from aitrader.audit import AuditChainError, InMemoryAuditLog, JsonlAuditLog
from aitrader.schemas.audit import GENESIS_HASH


class Clock:
    def __init__(self) -> None:
        self.now = datetime(2026, 10, 21, 10, tzinfo=UTC)

    def __call__(self) -> datetime:
        self.now += timedelta(milliseconds=1)
        return self.now


def _filled(log: InMemoryAuditLog, n: int = 5) -> InMemoryAuditLog:
    for i in range(n):
        log.append("envelope", f'{{"i":{i}}}')
    return log


def test_chain_links_and_verifies() -> None:
    log = _filled(InMemoryAuditLog(Clock()))
    records = log.records()
    assert records[0].prev_hash == GENESIS_HASH
    assert all(b.prev_hash == a.record_hash for a, b in itertools.pairwise(records))
    assert log.verify_chain()


@pytest.mark.parametrize("tamper", ["edit", "delete", "reorder", "insert"])
def test_any_tampering_is_detected(tamper: str) -> None:
    log = _filled(InMemoryAuditLog(Clock()))
    records = log.records()
    if tamper == "edit":
        records[2] = records[2].model_copy(update={"body_json": '{"i":999}'})
    elif tamper == "delete":
        del records[2]
    elif tamper == "reorder":
        records[1], records[2] = records[2], records[1]
    else:
        records.insert(2, records[2])
    log._records = records  # simulate storage-level tampering
    assert not log.verify_chain()


def test_unknown_record_types_are_rejected() -> None:
    with pytest.raises(ValueError, match="unknown audit record type"):
        InMemoryAuditLog(Clock()).append("free_text", "{}")


def test_jsonl_persists_and_reopens(tmp_path: Path) -> None:
    path = tmp_path / "audit.jsonl"
    clock = Clock()
    log = JsonlAuditLog(path, clock)
    _filled(log, 3)
    reopened = JsonlAuditLog(path, clock)
    assert [r.record_hash for r in reopened.records()] == [r.record_hash for r in log.records()]
    reopened.append("tool_call", "{}")
    assert JsonlAuditLog(path, clock).verify_chain()


def test_corrupted_file_refuses_to_open(tmp_path: Path) -> None:
    path = tmp_path / "audit.jsonl"
    _filled(JsonlAuditLog(path, Clock()), 3)
    lines = path.read_text().splitlines()
    lines[1] = lines[1].replace('{\\"i\\":1}', '{\\"i\\":7}')
    path.write_text("\n".join(lines) + "\n")
    with pytest.raises(AuditChainError):
        JsonlAuditLog(path, Clock())
