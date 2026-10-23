# aitrader: multi-agent AI trading system

An institutional-grade, multi-agent trading system for NSE cash and F&O. LLM agents research
and judge; deterministic code sizes, gates, vetoes and executes. Every decision is replayable
from a hash-chained audit log.

**Status:** Phase 6 is done and awaiting review. It covers execution against a paper broker,
reconciliation, the kill switch, the Redis and Postgres backends and the monitoring dashboard.
A Kite Connect adapter exists but has never been run against a live account, and no stage
that sends real orders is enabled.

- [Architecture](docs/ARCHITECTURE.md)
- [Configuration decisions (assumed, pending confirmation)](docs/DECISIONS.md)
- [Phase reports](docs/PROGRESS.md)

## Development

```bash
cd trading
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev,infra]"
ruff format --check . && ruff check .
mypy src tests
pytest
```

The Postgres and Redis tests run when these point at disposable servers (they skip
otherwise):

```bash
export AITRADER_TEST_POSTGRES_DSN="host=localhost port=5432 user=postgres dbname=postgres"
export AITRADER_TEST_REDIS_URL="redis://localhost:6379/0"
```

Kill switch: create the file named by `broker.kill_switch_file` (default `run/KILL`) to
freeze new entries. Text containing "flatten" also exits every position. Only a human can
release it, and only after the file is removed.

Load NSE files into the point-in-time store (idempotent, audited):

```bash
python -m aitrader.backtest.backfill /path/to/nse/files --store data/pit.duckdb
```

Evaluate the analysts on stored history:

```bash
python -m aitrader.evaluation.run --store data/pit.duckdb --start 2019-01-01 --end 2025-12-31
```

Secrets come only from the environment (see `.env.example`). Limits and parameters live in
`config/`.
