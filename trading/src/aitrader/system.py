"""Wiring: build the full decision chain from configuration.

LLM agents (debate, boss, news extraction) are enabled only when model ids and prices are
pinned in ``system.yaml`` and an API key is present; otherwise the debate is skipped and the
boss and news agents run their deterministic paths.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

from aitrader.agents.boss.agent import BossAgent
from aitrader.agents.chart.agent import ChartAgent
from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.universe import Universe
from aitrader.agents.debate.agent import DebateAgent
from aitrader.agents.liquidity.agent import LiquidityAgent
from aitrader.agents.news.agent import NewsAgent
from aitrader.agents.risk.agent import RiskAgent
from aitrader.agents.risk.tokens import TokenSigner
from aitrader.agents.risk_reward.agent import RiskRewardAgent
from aitrader.agents.smart_money.engine import SmartMoneyEngine
from aitrader.agents.trend.agent import TrendAgent, load_events
from aitrader.audit.interface import AuditLog
from aitrader.bus.memory import InMemoryBus
from aitrader.config.settings import RiskLimits, SystemConfig
from aitrader.llm.caller import StructuredCaller
from aitrader.llm.client import LlmClient
from aitrader.orchestrator.cycle import CycleTimeouts, DecisionCycle
from aitrader.state.memory import InMemoryStateStore
from aitrader.storage.pit import PitReader


@dataclass
class System:
    cycle: DecisionCycle
    signer: TokenSigner
    state: InMemoryStateStore
    bus: InMemoryBus
    llm_enabled: bool


def build_system(
    *,
    reader: PitReader,
    config: SystemConfig,
    limits: RiskLimits,
    audit: AuditLog,
    signer: TokenSigner,
    clock: Callable[[], datetime],
    llm_client: LlmClient | None = None,
    universe_index: str = "NIFTY_500",
) -> System:
    market = MarketData(reader, config.data.calendar_reference)
    universe = Universe(reader)
    state = InMemoryStateStore()
    bus = InMemoryBus(audit)
    caller = None
    if llm_client is not None and config.llm.pinned:
        caller = StructuredCaller(llm_client, config.llm, audit, clock)
    risk = RiskAgent(market, limits, state, signer, stage=config.stage)
    t = config.timeouts_seconds
    cycle = DecisionCycle(
        bus=bus,
        state=state,
        limits=limits,
        chart=ChartAgent(market),
        smart_money=SmartMoneyEngine(market, reader),
        trend=TrendAgent(
            market,
            universe,
            universe_index=universe_index,
            events=load_events(),
            max_exposure_change=limits.regime.max_exposure_change_per_cycle,
        ),
        news=NewsAgent(clock, universe, caller),
        debate=DebateAgent(caller, config.llm.max_debate_rounds),
        boss=BossAgent(caller),
        liquidity=LiquidityAgent(market, limits.liquidity),
        risk_reward=RiskRewardAgent(market, limits.risk_reward, limits.risk_reward.min_stop_atr_multiple),
        risk=risk,
        timeouts=CycleTimeouts(analyst=t.analyst, debate=t.debate, boss=t.boss),
        max_revisions=2,
    )
    return System(cycle=cycle, signer=signer, state=state, bus=bus, llm_enabled=caller is not None)


def default_signer(limits: RiskLimits) -> TokenSigner:
    return TokenSigner.from_env(ttl=timedelta(seconds=limits.execution.approval_token_ttl_seconds))
