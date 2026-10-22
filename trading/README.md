# aitrader: multi-agent AI trading system

An institutional-grade, multi-agent trading system for NSE cash and F&O. LLM agents research
and judge; deterministic code sizes, gates, vetoes and executes. Every decision is replayable
from a hash-chained audit log.

**Status:** Phase 1 (architecture, schemas, skeleton) is done and awaiting review. Nothing in
this package can place an order yet.

- [Architecture](docs/ARCHITECTURE.md)
- [Configuration decisions (assumed, pending confirmation)](docs/DECISIONS.md)

## Development

```bash
cd trading
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
ruff format --check . && ruff check .
mypy src tests
pytest
```

Secrets come only from the environment (see `.env.example`). Limits and parameters live in
`config/`.
