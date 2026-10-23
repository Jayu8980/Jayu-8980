"""Builds the paper-trading service from configuration (``stage: paper`` in system.yaml).

Secrets come from the environment: the risk signing key, the Postgres DSN and Redis URL,
optionally the Anthropic key (LLM agents run only when model ids are pinned) and the
Telegram bot token and chat id (without them, notifications are kept in memory and logged).
"""

from __future__ import annotations

import os
from collections.abc import Callable, Sequence
from datetime import UTC, datetime
from pathlib import Path

from aitrader.agents.data.agent import DataAgent, MarketData
from aitrader.agents.data.universe import Universe
from aitrader.audit.chain import JsonlAuditLog
from aitrader.backtest.strategies import equity
from aitrader.config.settings import RiskLimits, Stage, SystemConfig
from aitrader.execution.agent import ExecutionAgent
from aitrader.execution.broker import AuditedBroker
from aitrader.execution.checks import MarketClock
from aitrader.execution.killswitch import KillSwitch
from aitrader.execution.paper import PaperBroker, PaperConfig
from aitrader.feedback.attribution import AttributionAgent
from aitrader.feedback.tca import TcaAgent
from aitrader.llm.client import LlmClient
from aitrader.notify.agent import NotificationAgent
from aitrader.notify.channels import MemoryNotifier, Notifier, TelegramNotifier
from aitrader.paper.checkpoint import CheckpointStore, FileCheckpointStore, PostgresCheckpointStore
from aitrader.paper.feed import EodFeed, FolderFeed, NseFeed
from aitrader.paper.runner import PaperTrader, SimClock
from aitrader.paper.service import PaperService
from aitrader.schemas.base import Instrument
from aitrader.storage.pit import CachedPitReader, PitStore
from aitrader.system import build_system, default_signer


def _llm_client(config: SystemConfig) -> LlmClient | None:
    from aitrader.llm.anthropic_client import API_KEY_ENV, AnthropicLlmClient

    if not config.llm.pinned or not os.environ.get(API_KEY_ENV):
        return None
    return AnthropicLlmClient(timeout_seconds=config.llm.request_timeout_seconds)


def _notifier(config: SystemConfig) -> Notifier:
    n = config.notifications
    if n.channel == "telegram" and os.environ.get(n.bot_token_env) and os.environ.get(n.chat_id_env):
        return TelegramNotifier.from_env(n.bot_token_env, n.chat_id_env)
    return MemoryNotifier()


def _checkpoints(config: SystemConfig, run_dir: Path) -> CheckpointStore:
    if config.paper.checkpoint == "postgres":
        from aitrader.config.settings import secret
        from aitrader.state.postgres import connect

        return PostgresCheckpointStore(
            connect(secret(config.state.postgres_dsn_env)), config.state.schema_name
        )
    return FileCheckpointStore(run_dir / "checkpoint.json")


def build_paper_service(
    config: SystemConfig,
    limits: RiskLimits,
    *,
    wall_clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    feed: EodFeed | None = None,
    notifier: Notifier | None = None,
) -> PaperService:
    if config.stage is not Stage.PAPER:
        raise ValueError(f"the paper service runs only with stage: paper (config has {config.stage.value})")
    run_dir = Path(config.paper.run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    audit = JsonlAuditLog(run_dir / "audit.jsonl", wall_clock)
    store = PitStore(config.data.store_path)
    reader = CachedPitReader(store)
    signer = default_signer(limits)
    system = build_system(
        reader=reader,
        config=config,
        limits=limits,
        audit=audit,
        signer=signer,
        clock=wall_clock,
        llm_client=_llm_client(config),
        universe_index=config.paper.universe_index,
    )
    market = MarketData(reader, config.data.calendar_reference)
    universe = Universe(reader)
    sim = SimClock(wall_clock())
    broker = PaperBroker(
        market,
        sim,
        cash=limits.capital_inr,
        config=PaperConfig(
            half_spread_bps=limits.liquidity.assumed_spread_bps / 2,
            fno_margin_fraction=limits.execution.fno_margin_fraction,
        ),
    )
    execution = ExecutionAgent(
        broker=AuditedBroker(broker, audit, wall_clock),
        signer=signer,
        state=system.state,
        book=system.book,
        bus=system.bus,
        limits=limits,
        broker_config=config.broker,
        market_clock=MarketClock(),
    )
    watchlist = [equity(s) for s in config.paper.watchlist]

    def members(as_of: datetime) -> Sequence[Instrument]:
        try:
            return [equity(s) for s in universe.members(config.paper.universe_index, as_of)]
        except LookupError:
            return []  # no membership history loaded: the watchlist alone

    sectors = {s.symbol: s.sector for s in universe.securities(wall_clock()) if s.sector}
    system.cycle.sectors.update(sectors)
    attribution = AttributionAgent(market, system.cycle.rr.stats, system.cycle.credibility)
    trader = PaperTrader(
        cycle=system.cycle,
        risk=system.cycle.risk,
        execution=execution,
        broker=broker,
        kill_switch=KillSwitch(system.bus, system.state, Path(config.broker.kill_switch_file)),
        tca=TcaAgent(system.bus, system.book, limits.liquidity.assumed_spread_bps),
        bus=system.bus,
        state=system.state,
        market=market,
        audit=audit,
        clock=sim,
        instruments=watchlist,
        universe=members,
        sectors=sectors,
        attribution=attribution,
    )
    notifications = NotificationAgent(notifier or _notifier(config))
    notifications.attach(system.bus)
    data = DataAgent(store, system.bus, config.data, wall_clock)
    return PaperService(
        trader=trader,
        data=data,
        feed=feed or (FolderFeed(Path(config.paper.folder)) if config.paper.feed == "folder" else NseFeed()),
        reader=reader,
        checkpoints=_checkpoints(config, run_dir),
        signer=signer,
        limits=limits,
        criteria=config.rollout.paper_exit,
        clock=wall_clock,
        start_date=config.paper.start_date,
        dashboard_path=run_dir / "dashboard.html",
        notifications=notifications,
    )
