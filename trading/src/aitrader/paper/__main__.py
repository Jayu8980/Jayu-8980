"""Paper-trading operations.

    python -m aitrader.paper run-once            # do whatever is due now, then exit (timers)
    python -m aitrader.paper daemon              # keep doing it every paper.poll_minutes
    python -m aitrader.paper status              # last session, equity, freeze, kill switch
    python -m aitrader.paper readiness           # paper-to-live exit criteria
    python -m aitrader.paper kill --reason "..." [--flatten]
    python -m aitrader.paper release --operator NAME --confirm

``kill`` freezes new entries in the state store at once and leaves the kill file for the
service to act on (cancel resting entries; with --flatten, exit everything). ``release`` is
the human action that clears every active freeze; it refuses while the kill file exists and
records who released what. Neither touches the service's audit log, so both are safe to run
while the daemon is up.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import UUID, uuid5

from aitrader.config import load_risk_limits, load_system_config
from aitrader.config.settings import RiskLimits, SystemConfig, secret
from aitrader.paper.checkpoint import FileCheckpointStore, PostgresCheckpointStore
from aitrader.paper.runner import PaperCheckpoint

_KILL_NS = UUID("0d9c8b7a-6f5e-4d3c-2b1a-0f9e8d7c6b5a")


def _now() -> datetime:
    return datetime.now(UTC)


def _connection(config: SystemConfig) -> Any:
    from aitrader.state.postgres import connect

    return connect(secret(config.state.postgres_dsn_env))


def _checkpoint(config: SystemConfig) -> PaperCheckpoint | None:
    if config.paper.checkpoint == "postgres":
        return PostgresCheckpointStore(_connection(config), config.state.schema_name).load()
    return FileCheckpointStore(Path(config.paper.run_dir) / "checkpoint.json").load()


def run_once(config: SystemConfig, limits: RiskLimits) -> int:
    from aitrader.paper.app import build_paper_service

    service = build_paper_service(config, limits)
    last = service.resume()
    records = asyncio.run(service.step())
    for line in service.log:
        print(line)
    print(f"resumed after {last}; completed {[r.day.isoformat() for r in records]}")
    return 0


def daemon(config: SystemConfig, limits: RiskLimits) -> int:
    from aitrader.paper.app import build_paper_service

    service = build_paper_service(config, limits)
    service.resume()
    try:
        while True:
            for record in asyncio.run(service.step()):
                print(f"{_now().isoformat()} completed {record.day} equity {record.equity:,.0f}", flush=True)
            time.sleep(config.paper.poll_minutes * 60)
    except KeyboardInterrupt:
        return 0


def status(config: SystemConfig) -> int:
    cp = _checkpoint(config)
    if cp is None or not cp.records:
        print("no completed paper session yet")
        return 0
    last = cp.records[-1]
    print(f"last session {last.day}; equity {last.equity:,.0f}; drawdown {last.total_drawdown:.2%}")
    print(f"positions {sum(1 for q in last.positions.values() if q)}; frozen {last.frozen}")
    print(f"kill switch {'ENGAGED: ' + cp.kill_switch.detail if cp.kill_switch else 'off'}")
    return 0


def readiness(config: SystemConfig, limits: RiskLimits) -> int:
    from aitrader.paper.readiness import assess
    from aitrader.state.postgres import PostgresOrderBook
    from aitrader.system import default_signer

    cp = _checkpoint(config)
    if cp is None:
        print("no paper record yet")
        return 1
    report = assess(
        records=cp.records,
        book=PostgresOrderBook(_connection(config), config.state.schema_name),
        signer=default_signer(limits),
        tca=cp.tca.results,
        closed_trips=cp.attribution.closed if cp.attribution else (),
        criteria=config.rollout.paper_exit,
        limits=limits,
        now=_now(),
    )
    for r in report.results:
        print(f"[{'x' if r.passed else ' '}] {r.name}: {r.observed} (required {r.required})")
    print("READY for the owner's promotion decision" if report.ready else "NOT READY")
    return 0 if report.ready else 1


def kill(config: SystemConfig, reason: str, flatten: bool) -> int:
    from aitrader.state.postgres import PostgresStateStore

    path = Path(config.broker.kill_switch_file)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"{'flatten: ' if flatten else ''}{reason}\n", encoding="utf-8")
    now = _now()
    PostgresStateStore(_connection(config), config.state.schema_name).set_frozen(
        uuid5(_KILL_NS, f"cli|{now.isoformat()}"), now
    )
    print(f"new entries frozen; kill file {path} written{' (flatten requested)' if flatten else ''}")
    return 0


def release(config: SystemConfig, operator: str) -> int:
    from aitrader.state.postgres import PostgresStateStore

    path = Path(config.broker.kill_switch_file)
    if path.exists():
        print(f"remove {path} first: the kill file is still present")
        return 1
    conn = _connection(config)
    schema = config.state.schema_name
    state = PostgresStateStore(conn, schema)
    ids = state.freeze_status().reason_alert_ids
    for alert_id in ids:
        state.clear_freeze(alert_id, cleared_by_human=True)
    conn.execute(
        f"CREATE TABLE IF NOT EXISTS {schema}.human_actions ("
        "at timestamptz NOT NULL, operator text NOT NULL, action text NOT NULL, detail text NOT NULL)"
    )
    conn.execute(
        f"INSERT INTO {schema}.human_actions (at, operator, action, detail) VALUES (%s, %s, %s, %s)",
        (_now(), operator, "release_freeze", ",".join(str(i) for i in ids)),
    )
    print(f"released {len(ids)} freeze(s) as {operator}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m aitrader.paper")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("run-once")
    sub.add_parser("daemon")
    sub.add_parser("status")
    sub.add_parser("readiness")
    k = sub.add_parser("kill")
    k.add_argument("--reason", required=True)
    k.add_argument("--flatten", action="store_true")
    r = sub.add_parser("release")
    r.add_argument("--operator", required=True)
    r.add_argument("--confirm", action="store_true", required=True)
    args = parser.parse_args(argv)
    config, limits = load_system_config(), load_risk_limits()
    if args.command == "run-once":
        return run_once(config, limits)
    if args.command == "daemon":
        return daemon(config, limits)
    if args.command == "status":
        return status(config)
    if args.command == "readiness":
        return readiness(config, limits)
    if args.command == "kill":
        return kill(config, args.reason, args.flatten)
    return release(config, args.operator)


if __name__ == "__main__":
    sys.exit(main())
