# Phase reports

## Phase 1: architecture, schemas, skeleton (done)

Architecture document, typed schemas for every bus topic, config with limit validation, agent
contracts, CI. 119 tests.

## Phase 2: data agent, point-in-time storage, tool layer (done)

**Built**
- `audit/chain.py`: hash-chained audit log, in memory or as an append-only JSONL file that is
  fsynced and verified on open.
- `bus/memory.py`: in-memory bus. It audits before delivery, sends interrupts first,
  deduplicates per consumer group and supports replay.
- `storage/pit.py`: bitemporal DuckDB store. It is append-only and idempotent, keeps
  versioned corrections, and reads as of `available_at`.
- `agents/data/`:
  - NSE UDiFF bhavcopy (cash and F&O) and index-close parsers that fail closed on format drift
    and reject bad rows with reasons;
  - an archive fetcher with retries;
  - IST calendar helpers;
  - corporate-action adjustment as of a point in time;
  - point-in-time security master and index membership, including delisted names;
  - gap, outlier, staleness and source-disagreement checks;
  - the `DataAgent` (ingest, store, publish, alert, freeze on critical failures) and the
    `MarketData` read API.
- `tools/`: 59 deterministic, typed, audited tools (see ARCHITECTURE.md §14), including a
  dated Indian cost model.

**Tests:** 344 passing on Python 3.11 and 3.13; ruff and strict mypy are clean. They include
explicit acceptance tests in `tests/test_lookahead_survivorship.py`:
- future bars are invisible;
- results are unchanged by future data;
- a split adjusts prices only once it is announced *and* effective;
- dividends use the pre-ex close;
- unsupported actions are reported rather than guessed;
- delisted names stay in the historical universe;
- index reconstitutions apply only once announced and effective.

**Defects found and fixed while testing**
- Payoff breakevens were double-counted when they landed on a grid point.
- Failed-breakout detection used the failed attempt's own peak as the reference level.
- Index snapshots violated `available_at >= event_time`, because announcements come before
  effective dates.
- The HMM regime tool was non-deterministic: statsmodels used global randomness in its random
  restarts.
- `Field(ne=0)` silently did nothing in Pydantic v2; deprecation warnings now fail the tests.
- The tool decorator erased function signatures, which hid type errors.
- The mean-variance turnover constraint was non-smooth and the optimizer failed on it.

**Known limitations:** ARCHITECTURE.md §16.

## Phase 3: event-driven backtester and walk-forward validation (done, awaiting review)

**Built**
- `backtest/engine.py`: the event-driven engine (timeline in ARCHITECTURE.md §15).
  Strategies get a `PitView` that cannot see past the decision time. The engine provides:
  - next-open fills with square-root slippage, a participation cap and a cash check;
  - dated costs;
  - stops, targets and gap handling;
  - splits, bonuses and dividends while holding;
  - delisting and futures expiry;
  - a reproducible `run_digest`.
- `backtest/walkforward.py`: rolling or anchored folds with an embargo, selection on
  training windows only, and a stitched out-of-sample curve judged by the deflated Sharpe ratio.
- `tools/validation.py`: probabilistic and deflated Sharpe ratios, Monte Carlo trade
  resampling and return statistics (registered tools).
- `backtest/strategies.py`: buy-and-hold and Donchian-breakout baselines. These exist to
  exercise the engine; they are not alpha claims.
- `backtest/backfill.py`: a directory loader for NSE files that audits each file and is
  idempotent; a legacy (pre-July-2024) cash bhavcopy parser.
- `config/backtest.yaml` holds every simulation assumption.
- Cost schedules now reach back to 2016. Pre-2023 rates are conservative approximations and
  flagged in results.
- `storage.CachedPitReader` and batched writes took the engine from 16 s to 0.6 s per run
  and cut test time about tenfold.

**Tests:** 384 passing on Python 3.11 and 3.13, with ruff and strict mypy clean. New tests:
- future-data invariance of a whole backtest, by `run_digest`;
- determinism;
- the accounting identity;
- next-open fills;
- that the strategy sees no unpublished bar;
- gap-through stops and stop-before-target;
- split equity continuity, dividends and delisting;
- the participation cap;
- invalid orders;
- that walk-forward cannot select on the test window;
- the PSR formula and DSR monotonicity;
- equivalence of the cached reader and SQL on 300 random queries.

**Defects found and fixed while testing**
- Result digests hashed Python dates instead of their JSON form.
- My first invariance test compared two different random markets. My helper drew random
  numbers in a different order for different lengths; the engine was fine once the data
  matched.

**Known limitations:** ARCHITECTURE.md §16.
