# Operations: running paper trading

This is the runbook for the `paper` stage. Nothing here can send a real order. The only
broker in the paper service is the paper broker, and live stages need the owner's sign-off
(DECISIONS.md).

## 1. What runs, and when

The service does one thing per session, after NSE publishes the day's files:

1. Fetch the EOD files: index closes and the cash bhavcopy are required, F&O and
   participant OI are used when present. They come from the NSE archives (`paper.feed: nse`)
   or from a folder another process fills (`paper.feed: folder`, the same file names NSE
   publishes).
2. Replay that session through the paper broker. Orders queued last evening trade at the
   open, broker-held stops and targets are checked, and day orders expire.
3. Reconcile the ledger with the broker. TCA runs, and the attribution agent scores the
   analysts.
4. Run the decision cycle. Approved instructions go to execution as after-market orders for
   the next open.
5. Save a checkpoint, write `run/dashboard.html` and send the daily summary.

A weekday without files is treated as a holiday once a later session has arrived. If the
feed stays silent, the data agent raises staleness alerts: a warning at 80 hours and a
critical alert at 128.

## 2. Setting up

```bash
cd trading
python -m venv .venv && . .venv/bin/activate
pip install -e ".[infra]"
cp .env.example .env    # fill in the secrets; never commit .env
```

In `config/system.yaml`:

```yaml
stage: paper
bus:   {backend: redis_streams, ...}
state: {backend: postgres, ...}
paper: {feed: nse, watchlist: [...], start_date: 2026-11-02, ...}
```

Required secrets:
- `AITRADER_RISK_SIGNING_KEY`: 32+ random bytes as hex, e.g. `python -c "import secrets; print(secrets.token_hex(32))"`.
- `AITRADER_POSTGRES_DSN` and `AITRADER_REDIS_URL`.

Optional secrets:
- `AITRADER_ANTHROPIC_API_KEY`: used only when model ids and prices are pinned.
- `AITRADER_TELEGRAM_BOT_TOKEN` and `AITRADER_TELEGRAM_CHAT_ID`: without them, notifications
  are only kept in memory.

**Universe.** Until index-membership history is loaded, set `paper.watchlist` to the
symbols you want analysed. Membership of `paper.universe_index`, as of each decision, is
added once it is loaded.

**History.** The trend agent needs about 220 sessions before the first decision. Backfill
first:

```bash
python -m aitrader.backtest.backfill /path/to/nse/files --store data/pit.duckdb
```

## 3. Running

Choose one of these.

- **Timer:**
  ```bash
  python -m aitrader.paper run-once
  ```
  Run it after the files are out. `deploy/aitrader-paper.timer` runs it at 19:45, 21:00 and
  23:30 IST on weekdays and at 09:00 on Saturday. A run with nothing due does nothing.
- **Daemon:**
  ```bash
  python -m aitrader.paper daemon
  ```
  It checks every `paper.poll_minutes`.
- **Docker:**
  ```bash
  docker compose -f deploy/docker-compose.yml up -d --build
  ```
  This runs Redis, Postgres and the daemon. Mount a config directory set to the paper stage
  via `AITRADER_CONFIG_DIR`. The image has not been built in CI yet.

A restart resumes from the last checkpoint and reproduces an uninterrupted run exactly. A
crash in the middle of a session replays that session from the checkpoint: fills are booked
once by trade id, but protection records can disagree with the broker, which reconciliation
reports and freezes on. A human then checks before releasing.

## 4. Watching

- `run/dashboard.html` is rewritten after every session. It shows status, equity, drawdown,
  exposure, positions with their stops, agent health and accuracy, TCA, fills, alerts and
  paper-to-live readiness.
- `python -m aitrader.paper status` prints the last session, equity, freeze and kill switch.
- Telegram receives every freeze or critical alert as it happens, a release when a human
  clears one, and a summary after each session.
- `run/audit.jsonl` is the hash-chained record of every message, broker call and LLM call.

## 5. Stopping and releasing

```bash
python -m aitrader.paper kill --reason "unexpected fills"            # freeze new entries now
python -m aitrader.paper kill --reason "exit everything" --flatten   # also exit all positions
```

`kill` freezes new entries in the state store at once. It also writes the kill file, which
the service acts on at its next step: resting entry orders are cancelled, and with
`--flatten` every position exits through risk-signed instructions. Stops and exits are never
cancelled by a freeze.

To resume:

```bash
rm run/KILL
python -m aitrader.paper release --operator <your name> --confirm
```

`release` refuses while the kill file exists. It clears every active freeze, including the
automatic ones (broker failures, reconciliation mismatches, unfilled stops, drawdown), and
records who released what in Postgres (`human_actions`). Investigate the cause first: the
alert detail is on the dashboard and in Telegram.

## 6. Before live trading

```bash
python -m aitrader.paper readiness
```

This checks `rollout.paper_exit`:
- at least 60 sessions;
- no reconciliation mismatch;
- drawdown within the limit;
- realised slippage within 2× the estimate;
- at least 20 closed trades;
- every fill traced to a verified approval token;
- not frozen.

Passing is evidence for the owner's decision. It does not change the stage.

Check the Kite adapter against your account, read only:

```bash
AITRADER_KITE_API_KEY=... AITRADER_KITE_ACCESS_TOKEN=... python -m aitrader.execution.kite_check
```

It calls only read endpoints, and its transport refuses anything but GET.
