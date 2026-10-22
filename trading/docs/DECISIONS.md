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
