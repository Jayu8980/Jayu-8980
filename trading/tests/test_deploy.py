"""Deployment files stay consistent with the code they run."""

from __future__ import annotations

import importlib
from pathlib import Path

import yaml

DEPLOY = Path(__file__).resolve().parents[1] / "deploy"


def test_compose_runs_the_paper_daemon_with_durable_services() -> None:
    compose = yaml.safe_load((DEPLOY / "docker-compose.yml").read_text(encoding="utf-8"))
    services = compose["services"]
    assert {"redis", "postgres", "paper"} <= set(services)
    env = services["paper"]["environment"]
    assert (
        env["AITRADER_REDIS_URL"].startswith("redis://redis") and "@postgres/" in env["AITRADER_POSTGRES_DSN"]
    )
    assert "POSTGRES_PASSWORD" not in yaml.safe_dump(services["paper"].get("env_file", []))


def test_entry_points_exist() -> None:
    dockerfile = (DEPLOY / "Dockerfile").read_text(encoding="utf-8")
    assert '"aitrader.paper", "daemon"' in dockerfile
    unit = (DEPLOY / "aitrader-paper.service").read_text(encoding="utf-8")
    assert "-m aitrader.paper run-once" in unit
    for module in ("aitrader.paper.__main__", "aitrader.execution.kite_check"):
        assert hasattr(importlib.import_module(module), "main")
