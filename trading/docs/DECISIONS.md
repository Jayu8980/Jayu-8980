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

## Needed from you before Phase 4 (analyst agents)

1. **Data access** (same as above): this session cannot reach NSE; a new session with the
   allowed domains, or uploaded files, unblocks real backtests and the analysts' offline
   evaluation.
2. **LLM model ids** for the news and trend agents (`llm.models` in system.yaml), and the
   daily budget.
3. **News sources:** NSE/BSE announcement feeds are the default. Should anything paid be added?
4. **FII/DII and participant-OI history** for the smart money engine: NSE publishes both daily.
   Do you want backfill from NSE archives once access works?
