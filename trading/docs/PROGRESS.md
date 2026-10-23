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

**Known limitations:** ARCHITECTURE.md §18–19.

## Phase 3: event-driven backtester and walk-forward validation (done)

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

**Known limitations:** ARCHITECTURE.md §18–19.

## Phase 4: analyst agents with offline evaluation (done)

**Built** (details in ARCHITECTURE.md §16)
- The LLM layer: client contract, Anthropic adapter, versioned prompts, untrusted-text
  fencing, and a budgeted, fully audited structured caller. Prompt `news_extraction/v1`.
- Chart agent, news agent (rules plus optional LLM, with verification), news impact
  analyser with its reaction library, smart money engine, and trend agent.
- A participant-OI parser and ingestion; F&O chain lookup on `MarketData`.
- New tools: `derivs.oi_buildup`, `breadth.compute`, `ta.resample` (66 in total).
- The offline evaluation harness and trend calibration, with a CLI.
- Config: LLM `effort` and pricing replace `temperature` (current models reject sampling
  parameters); `config/events.yaml` for macro events.

**Tests:** 435 passing on Python 3.11 and 3.13; ruff and strict mypy clean. Highlights:
- prompt injection inside a document stays data;
- hallucinated quotes, symbols and dates are dropped;
- an extra output field discards the LLM output;
- budget caps stop calls before they are sent;
- the Anthropic adapter sends structured output and no sampling parameters;
- the event study recovers a planted 6% reaction and never reports an unclosed window;
- the reaction library ignores reactions completed after as-of;
- OI build-up is classified correctly;
- participant OI is not used before publication;
- the calibrated trend forecast beats uniform out of sample.

**Defects found and fixed while testing**
- The trend agent's raw probabilities were overconfident (Brier 0.42 vs 0.33 uniform).
  Calibration was added.
- The regime model reported "high volatility" in single-regime data. It is now gated on the
  fitted regimes having distinct volatilities.
- Regime labels relied on a fixed 50% threshold that calibrated probabilities rarely cross.
  Labels now follow the top scenario.
- The chart evaluator compared levels in different price bases across splits.

## Phase 5: debate, boss, gates and the full chain in backtest (done)

**Built** (details in ARCHITECTURE.md §17)
- In-memory state store with an atomic token and idempotency-key ledger, and a freeze that
  only a human can clear.
- HMAC approval tokens (`agents/risk/tokens.py`): digest-bound, expiring, single-use.
- Liquidity agent, R:R agent (blended targets, no-edge prior, seedable setup statistics) and
  risk agent (deterministic sizing, 17 rules, VaR/CVaR, stress, correlation, signed
  instructions, drawdown alerts).
- Boss: deterministic fallback rule set, validated LLM path, discretion tracker. Debate agent
  with capped rounds and enforced citations. Prompts `boss/v1` and `debate/v1`.
- Attribution tools (`attribution.credibility_weights`, `attribution.weighted_consensus`).
- Orchestrator cycle with timeouts, failure isolation, revision loop, causation-linked and
  deterministic messages, and heartbeats.
- `backtest/chain.py` runs the whole chain inside the event-driven backtester, verifying
  every token first. `system.py` wires the chain from config.

**Tests:** 475 passing on Python 3.11 and 3.13; ruff and strict mypy clean. New tests cover:
- forged, expired, replayed and re-approved tokens;
- liquidity regimes and freeze-quantity icebergs;
- every R:R gate, including "no measured edge → no trade";
- risk sizing within budget, freeze blocking entries but not exits, unskippable gates,
  drawdown freezes, and the coverage, position, sector and human-approval rules;
- boss fallback and LLM validation, the discretion tracker, debate citations;
- full-cycle traceability and reproducibility;
- a failing agent, a slow agent and a crashing boss;
- the chain trading in a backtest with every order traced to its approval, memo and chart
  signal;
- a frozen backtest that never trades, and refused replays.

**Defects found and fixed while testing**
- `BossDecision` required a positive risk budget for every SELL, so a plain exit was
  impossible. Only opening trades now need one.
- The R:R no-edge prior used a log-driftless model, under which wide targets showed positive
  EV with no edge. It is now a price martingale, which gives EV of exactly minus costs.
- R:R used only the first target, so breakouts with 2R/3R targets could never meet their own
  2.0 minimum. It now uses blended targets, as the spec says.
- Token signatures were computed over a different timestamp serialisation from the one
  verified.
- Random message ids made replays differ; ids are now derived from the cycle and payload.
- The trend agent refitted its regime model every cycle (0.36 s). It now refits weekly and
  filters in between (6 ms).

## Phase 6: execution in paper mode, reconciliation, kill switch (done, awaiting review)

**Built** (details in ARCHITECTURE.md §20)
- **Execution agent:**
  - token verification with typed replay and forgery errors;
  - pre-trade checks: band, deviation, margin, freeze, hours, position, cash shorts;
  - slicing (`micro.slicer` tool) and idempotent tagged placement under an OPS rate limiter;
  - broker-held protective stops and targets at the signed levels, with exits that cancel
    protection first;
  - sync, fill booking, reconciliation and freeze alerts.
- **Paper broker:** a daily-bar exchange simulator with costs, slippage, participation caps,
  broker-style refusals and fault injection. **Kite Connect adapter** (orders, AMO, GTT, positions,
  quotes, instrument master), unverified against a live account.
- **Kill switch** (manual, file, automatic) and risk-signed flatten instructions.
- **Postgres** state store and order book, and a **Redis Streams** bus (interrupt stream,
  consumer groups, dead-lettering). `system.backends()` selects them from config.
- **Paper-trading runner, TCA agent, live per-agent scores and the monitoring dashboard**
  (self-contained HTML, light and dark, table view).
- A static test that nothing outside `execution/` places or cancels orders. CI now runs
  Postgres 16 and Redis 7 services.

**Tests:** 528 passing on Python 3.11 and 3.13 against real Postgres and Redis (the backend
tests skip without them); ruff and strict mypy clean. New tests cover:
- duplicates: replayed instructions and envelopes never create a second order, and trades are
  booked once however often sync runs;
- broker faults: a lost acknowledgement is adopted by tag, a pre-send failure is retried, and
  an unknown outcome freezes instead of re-sending;
- refused instructions: forged and foreign tokens (refused with an alert), expired tokens,
  and every pre-trade check;
- freezes: entries are blocked and resting entries cancelled while stops and exits stay;
- protection: exits cancel stops first, partial exits re-protect, stops and targets execute,
  and a gap through the stop limit alerts and re-arms;
- reconciliation catching a manual trade; illegal state transitions; value-cap slicing;
- the slicer, market clock, rate limiter, kill file and human-only release, and flatten
  signing;
- the Kite request mapping and parsing;
- state-store and order-book contracts on memory and Postgres, 8 connections racing for one
  token, persistence across restart;
- the Redis bus: once per group, interrupts first, redelivery and dead-lettering, invalid
  envelopes never written;
- paper trading end to end: reconciled every day, every fill traced to a verified token,
  every position covered by a stop, reproducible, identical on the durable stack;
- the kill file halting entries mid-run, and a flatten kill exiting every position;
- dashboard escaping and self-containment.

**Defects found and fixed while testing**
- Rounding a buy limit to the tick could push it just past the 1% deviation guard, so some
  approved instructions were refused at execution. The risk agent now rounds limits
  conservatively (buys down, sells up).
- The market clock raised when a trade was decided after the last known session. Beyond the
  known sessions it now falls back to weekdays.
- Replay and forgery were told apart by error text. They are now typed exceptions.
- A broker error that was neither a refusal nor a transport failure (e.g. a symbol missing
  from the Kite master) escaped the placement path. It is now a definitive refusal.

**Known limitations:** ARCHITECTURE.md §21. In short: the paper run is a replay on stored
daily bars, the Kite adapter is unverified live, margin is approximated, corporate actions on
held positions are not simulated, and notifications are not sent yet.

**Decisions needed before Phase 7:** DECISIONS.md, "Needed from you before Phase 7".
