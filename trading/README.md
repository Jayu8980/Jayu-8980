# aitrader: multi-agent AI trading system

An institutional-grade, multi-agent trading system for NSE cash and F&O. LLM agents research
and judge; deterministic code sizes, gates, vetoes and executes. Every decision is replayable
from a hash-chained audit log.

**Status:** Phase 5 (debate, boss, gates, full decision chain in backtest) is done and
awaiting review. Nothing in this package can place a real order yet.

- [Architecture](docs/ARCHITECTURE.md)
- [Configuration decisions (assumed, pending confirmation)](docs/DECISIONS.md)
- [Phase reports](docs/PROGRESS.md)

## Development

```bash
cd trading
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
ruff format --check . && ruff check .
mypy src tests
pytest
```

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
