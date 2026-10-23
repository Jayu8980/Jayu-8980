# Configuration decisions

The configuration questions were not answered before Phase 1, so Phase 1 uses the defaults
below. All of them are config-driven and none is irreversible. **Status: assumed. The owner
must confirm or change each one before the phase that depends on it.**

| # | Decision | Assumed default | Where | Needed by |
|---|----------|-----------------|-------|-----------|
| 1 | Code location | `trading/` directory in this repo, isolated CI | repo | now |
| 2 | Market | NSE cash plus index and stock F&O | `system.yaml: market` | Phase 2 |
| 3 | Universe | NIFTY 500 plus F&O stocks, delisted names kept for backtests | `market.universe` | Phase 2 |
| 4 | Options | Long options and defined-risk spreads; no naked writing | `allow_naked_option_writing` | Phase 5 |
| 5 | Shorts | Futures or puts only, only when explicitly instructed | `shorts_via`, `open_short` | Phase 5 |
| 6 | Broker | Zerodha Kite Connect | `broker.name` | Phase 6 |
| 7 | Market data | NSE bhavcopy/archives for EOD history, Kite for live and recent intraday | — | Phase 2 |
| 8 | News sources | NSE/BSE announcements, RBI, PIB, one RSS newswire at low weight | — | Phase 4 |
| 9 | Style / horizon | Swing (days to weeks); EOD cycle at 15:45 IST plus event triggers; 15m entry timing | `trading` | Phase 3 |
| 10 | Capital / risk per trade | ₹10,00,000 placeholder; 1% risk per trade | `limits.yaml` | Phase 5 |
| 11 | Drawdown | 3% daily, 10% total; human clears freezes | `drawdown` | Phase 5 |
| 12 | Exposure | Gross 100%, net 60%, sector 25%, name 10%, ≤ 5% ADV, no extra leverage | `portfolio`, `liquidity` | Phase 5 |
| 13 | Human approval | Above 0.5% risk, new instrument types, inside event windows | `per_trade` | Phase 8 |
| 14 | LLM provider and model | Anthropic. **Exact model ids not set (`UNSET`)**; LLM agents refuse to start until pinned | `llm.models` | Phase 4 |
| 15 | LLM budget | 2 debate rounds, 60 calls per cycle, $20 per day | `llm` | Phase 4 |
| 16 | Bus / storage | Redis Streams, Postgres (state and audit), Parquet/DuckDB (PIT market data) | `bus`, `state` | Phase 2 |
| 17 | Deployment | One Linux VPS with a static IP, Docker Compose | — | Phase 6 |
| 18 | Alerts / approvals | Telegram bot plus a Streamlit dashboard | `notifications` | Phase 6 |
| 19 | Capital ownership | Personal capital only (no PMS/RIA/RA obligations assumed) | — | Phase 6 |
| 20 | Stage exit criteria | Paper ≥ 6 weeks; live slippage within tolerance of backtest; owner sign-off | — | Phase 7 |

## Compliance notes to confirm before any live stage

- SEBI's retail algo framework and the broker's implementation: order-rate threshold, algo
  registration and tagging, static IP, API access rules. The cap is set below 10 OPS in config
  (`max_orders_per_second`, hard-limited to ≤ 10 in code). Confirm the current rules with the
  broker.
- Cash-segment short selling is intraday-only, so overnight shorts use F&O.

## Phase 2 decisions taken (defaults; change via config)

| Decision | Choice | Where |
|----------|--------|-------|
| PIT storage engine | DuckDB file, single append-only bitemporal table | `data.store_path` |
| EOD availability rule | 19:00 IST for NSE bhavcopies and index closes, same in backtest and live | `data.availability_ist` |
| Trading calendar | Days the NIFTY 50 index has a bar (no hand-maintained holiday list) | `data.calendar_reference` |
| Cash series kept | EQ, BE | `data.cash_series` |
| Option bars | Stored, not broadcast on the bus | `data.publish_option_bars` |
| Feed staleness | Warning after 80 h, freeze after 128 h | `data.stale_*_hours` |
| Price adjustment | Back-adjust splits, bonuses and dividends as of read time; rights, mergers and demergers are manual | `agents/data/adjust.py` |
| Cost model | Dated schedules; refuses dates before 2023-04-01 | `config/costs.yaml` |

## Needed from you before Phase 3 (still open)

1. **Historical data source** for the backtester: NSE bhavcopy archives (free, EOD, cash and
   F&O since 2024 in UDiFF format, older files in the legacy format) or a paid vendor. Either
   way, this environment's network policy must allow the host, or data is loaded elsewhere and
   uploaded.
2. **Corporate actions, index membership history and the security master**: the source for
   each. Without membership history, backtests cannot be survivorship-free.
3. **Backtest period and universe**: the default proposal is 2016 onward on the NIFTY 500.
   Older UDiFF-incompatible files need a legacy-format parser. Cost schedules must be extended
   before 2023-04-01.
4. **Verify the cost rates** in `config/costs.yaml` against the broker's current tariff.

## Phase 3 decisions taken (defaults; change via config/backtest.yaml)

| Decision | Choice |
|----------|--------|
| Fill model | Orders decided after the close fill at the next open |
| Slippage | 5 bps half-spread + square-root impact (Y = 1.0), minimum 2 bps, 20-session ADV |
| Participation | At most 10% of the fill day's volume; the remainder is cancelled |
| Stop vs target in one bar | Stop first (pessimistic) |
| Dividends | Credited on the ex-date |
| Walk-forward objective | Sharpe on the training window; ties go to the earliest grid entry |
| Pre-2023 costs | Conservative approximate schedules, flagged in results |

## Needed from you before Phase 4 (analyst agents) (still open)

1. **Data access** (same as above): this session cannot reach NSE; a new session with the
   allowed domains, or uploaded files, unblocks real backtests and the analysts' offline
   evaluation.
2. **LLM model ids** for the news and trend agents (`llm.models` in system.yaml), and the
   daily budget.
3. **News sources:** NSE/BSE announcement feeds are the default. Should anything paid be added?
4. **FII/DII and participant-OI history** for the smart money engine: NSE publishes both daily.
   Do you want backfill from NSE archives once access works?

## Phase 4 decisions taken (defaults)

| Decision | Choice |
|----------|--------|
| LLM model ids | Left `UNSET`. LLM paths are off and deterministic rules run instead |
| LLM sampling | None sent (current models reject temperature); `effort`: high for reasoning, low for extraction |
| News acceptance | An LLM claim counts only if its quote is verbatim in the source; symbols only from linked candidates; dates only if stated in their quote |
| Trend calibration | Temperature 2.5, shrinkage 0.2 until refit on real history |
| Participant OI | Stamped available at 20:00 IST |
| Macro calendar | Empty until you add confirmed dates to `config/events.yaml` |

## Needed from you before Phase 5 (debate, boss, gates, full chain in backtest) (still open)

1. **Data:** still the blocker for every real evaluation (NSE is refused from this session).
2. **LLM model ids and prices:** the debate and boss agents are LLM agents, and Phase 5 needs
   them pinned to run end to end. Without them, the deterministic boss fallback is what gets
   backtested.
3. **Macro event dates** in `config/events.yaml` (RBI MPC, Budget, CPI and GDP releases).
4. **News source:** which feed to build a connector for (default: NSE corporate announcements).

## Phase 5 decisions taken (defaults)

| Decision | Choice |
|----------|--------|
| Boss without an LLM | Deterministic fallback: consensus ≥ 0.25, agreement ≥ 60%, budget ≤ 0.5, long-only |
| Revisions after a failed gate | At most 2; the fallback simply waits |
| No-edge prior | Zero-drift barrier model, so nothing trades until setup statistics are measured |
| Approval token TTL | 16 hours (swing: approve after close, execute at next open) |
| Freeze | Blocks every opening order; exits and reductions remain allowed |
| Assumed spread | 10 bps until order-book data exists |
| Stress test | -10% index shock, max 6% loss of equity |
| Limit orders | Marketable limit at ±1% of the last close; larger gaps at the open go unfilled |

## Needed from you before Phase 6 (execution in paper mode, reconciliation, kill switch) (still open)

1. **Broker:** confirm Zerodha Kite Connect and that you have API access. The paper
   broker comes first, but its interface follows the real one.
2. **Infrastructure for paper trading:** Redis and Postgres (defaults), or keep in-memory
   backends for a first paper run on one machine.
3. **Data access** (still the blocker for measuring setup edge): without it, paper trading
   will run but the R:R gate will (correctly) reject every entry.
4. **Alerts:** Telegram bot token and chat id for freeze alerts and approvals (or another
   channel).

## Phase 6 decisions taken (defaults)

| Decision | Choice |
|----------|--------|
| Broker | Zerodha Kite Connect adapter, plus a paper broker behind the same contract |
| Durable infrastructure | Redis Streams bus and Postgres state store and order book (both start locally; CI runs them as services) |
| Protective orders | Broker-held GTT: stop alone or stop plus first target (OCO), at the signed levels |
| Stop-limit slippage allowance | 2% past the trigger (`protective_stop_limit_bps: 200`) |
| F&O margin estimate | 25% of notional until SPAN data is wired |
| Outside market hours | Orders go in as after-market orders for the next open |
| Order tags | Deterministic 20-character hash of instruction and slice (Kite limit) |
| Unknown placement outcome | Never re-send; freeze until a sync finds or rules out the order |
| Automatic freeze | 3 consecutive broker failures, reconciliation mismatch, refused or unfilled stop, failed authentication |
| Kill file | `run/KILL`; text containing "flatten" also exits every position via risk-signed exits |
| Limit rounding | Buy limits round down and sell limits round up to the tick, so they never exceed the 1% deviation guard |

## Needed from you before Phase 7 (paper trading) (still open)

1. **Data access.** Paper trading on real prices needs the NSE archives reachable from
   wherever it runs, or daily files delivered to it. With no measured setup edge, the R:R
   gate will (correctly) reject every entry.
2. **A Kite Connect account check (read-only is enough).** Run the adapter's read
   calls (orders, trades, holdings, positions, margins, quote, GTT list) once to confirm
   the response shapes. Who performs the daily login that issues the access token?
3. **Where paper trading runs.** A machine or VM with Redis and Postgres, a static IP if
   live trading will follow (SEBI and broker rules), and a daily schedule. The runner's
   steps map to IST times (§20).
4. **Alert channel.** A Telegram bot token and chat id, or another channel, for freezes,
   kill switches and fills.
5. **Paper-to-live exit criteria.** Proposed defaults: at least 60 sessions; zero
   reconciliation mismatches not explained by a documented cause; realised slippage within
   2× the estimate; drawdown within limits; every fill traced to a token. Confirm or change.
6. **Margin and capital.** Starting capital for paper (default ₹10 lakh), and whether F&O
   is in scope for the first paper run (the default excludes it until SPAN margin is wired).

## Phase 7 decisions taken (defaults)

| Decision | Choice |
|----------|--------|
| Paper capital and scope | ₹10 lakh (`limits.capital_inr`), cash equities; F&O excluded until SPAN margin is wired |
| Where it runs | Any machine with Redis and Postgres: `deploy/docker-compose.yml`, or a venv plus the systemd timer |
| Schedule | `run-once` at 19:45, 21:00, 23:30 IST on weekdays and 09:00 Saturday, or the daemon every 15 min |
| EOD source | NSE archives (`paper.feed: nse`), or a drop folder with NSE's file names |
| Universe | Index membership of `paper.universe_index` when loaded; `paper.watchlist` until then (empty by default, so you choose) |
| Checkpoints | Postgres (`paper.checkpoint: postgres`), one row per run |
| Notifications | Telegram when the token and chat id are set, otherwise in memory only; plain text |
| Attribution | 5-session horizon (trend: 20 sessions, ±3%), calls at abs(score) ≥ 0.2, prior 0.5 with strength 20, regime-specific after 10 calls |
| Paper-to-live exit criteria | 60 sessions, 0 mismatch days, drawdown below the 10% limit, slippage ≤ 2× estimate over ≥ 10 instructions, ≥ 20 closed trades, every fill traced, not frozen |
| Instruction validity | Until the close of the next weekday session (replaces "16 h from approval", which made Friday approvals unusable) |
| Regime model refit | Once per ISO week, on returns from before that week (restart-independent) |
| Releasing a freeze | `python -m aitrader.paper release --operator NAME --confirm`; refused while the kill file exists |

## Needed from you before Phase 8 (human-approval live mode)

1. **Run paper trading for real.** It needs the NSE files reachable (or delivered to a
   folder), about 220 sessions of history backfilled, a watchlist or an index-membership
   source, and a machine to run on. Phase 8 should start only after the readiness report
   passes on that run.
2. **Kite read-only check.** Run `python -m aitrader.execution.kite_check` once with your
   API key and a day's access token, and send me the output (it contains no secrets). It
   confirms the response shapes the adapter assumes.
3. **Approval channel for live trades.** In `live_human_approval` every order needs a
   person's yes. Should that be a Telegram reply, a web page, or the CLI? And who approves,
   and how quickly must they answer before the approval lapses?
4. **Daily Kite login.** Kite issues the access token through an interactive login every
   day. Who does it, and when? It must be before the after-market order window.
5. **Static IP and compliance.** SEBI's retail algo rules and Zerodha's requirements (static
   IP, algo registration or exemption, order-rate limits). This needs your confirmation with
   the broker; see "Compliance notes".
6. **Exit criteria.** Confirm or change the paper-to-live criteria above.
