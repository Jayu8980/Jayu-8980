"""A small synthetic market and a fully wired decision chain for Phase 5 tests."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import numpy as np

from aitrader.agents.boss.agent import BossAgent
from aitrader.agents.chart.agent import ChartAgent
from aitrader.agents.data.agent import MarketData
from aitrader.agents.debate.agent import DebateAgent
from aitrader.agents.liquidity.agent import LiquidityAgent
from aitrader.agents.risk.agent import RiskAgent
from aitrader.agents.risk.portfolio import PortfolioSnapshot
from aitrader.agents.risk.tokens import TokenSigner
from aitrader.agents.risk_reward.agent import RiskRewardAgent, SetupStats
from aitrader.agents.smart_money.engine import SmartMoneyEngine
from aitrader.agents.trend.agent import TrendAgent
from aitrader.audit import InMemoryAuditLog
from aitrader.backtest.strategies import equity
from aitrader.bus import InMemoryBus
from aitrader.config import load_risk_limits
from aitrader.config.settings import RiskLimits
from aitrader.orchestrator.cycle import CycleTimeouts, DecisionCycle
from aitrader.schemas.signals import SetupType
from aitrader.state import InMemoryStateStore, StateStore
from aitrader.storage import CachedPitReader, PitStore
from tests.analyst_helpers import regime_index, write_closes
from tests.data_helpers import eod

KEY = b"phase5-test-signing-key-32-bytes!!"
SYMBOLS = ("UPA", "UPB", "DNC")


def seeded_stats() -> SetupStats:
    """A measured edge, as an offline evaluation would provide: 50% wins per setup."""
    stats = SetupStats()
    for setup in (SetupType.BREAKOUT, SetupType.PULLBACK, SetupType.MEAN_REVERSION):
        for i in range(100):
            stats.record(setup, 1.0 if i % 2 == 0 else -1.0)
    return stats


def market_store(n: int = 420, seed: int = 21) -> tuple[PitStore, list[date]]:
    store = PitStore()
    days = regime_index(store, n, seed=seed, drifts=((n, 0.0012, 0.008),))
    rng = np.random.default_rng(seed)
    for sym, drift in zip(SYMBOLS, (0.0025, 0.002, -0.002), strict=True):
        closes = 100 * np.exp(np.cumsum(rng.normal(drift, 0.012, n)))
        write_closes(store, equity(sym), days, closes.tolist(), volume=[2_000_000] * n)
    return store, days


def wire(
    store: PitStore,
    *,
    limits: RiskLimits | None = None,
    stats: SetupStats | None = None,
    timeouts: CycleTimeouts | None = None,
    reader: CachedPitReader | None = None,
    state: StateStore | None = None,
    **overrides: object,
) -> tuple[DecisionCycle, TokenSigner, StateStore, InMemoryAuditLog]:
    limits = limits or load_risk_limits()
    reader = reader or CachedPitReader(store)
    market = MarketData(reader)
    state = state or InMemoryStateStore()
    audit = InMemoryAuditLog(lambda: datetime(2030, 1, 1, tzinfo=UTC))
    signer = TokenSigner(KEY, ttl=timedelta(seconds=limits.execution.approval_token_ttl_seconds))
    parts: dict[str, object] = {
        "bus": InMemoryBus(audit),
        "state": state,
        "limits": limits,
        "chart": ChartAgent(market),
        "smart_money": SmartMoneyEngine(market, reader),
        "trend": TrendAgent(market),
        "news": None,
        "debate": DebateAgent(None),
        "boss": BossAgent(None),
        "liquidity": LiquidityAgent(market, limits.liquidity),
        "risk_reward": RiskRewardAgent(
            market, limits.risk_reward, limits.risk_reward.min_stop_atr_multiple, stats or seeded_stats()
        ),
        "risk": RiskAgent(market, limits, state, signer, min_analysts=2),
        "timeouts": timeouts,
    }
    parts.update(overrides)
    return DecisionCycle(**parts), signer, state, audit  # type: ignore[arg-type]


def flat_portfolio(as_of: datetime, equity_value: float = 1_000_000) -> PortfolioSnapshot:
    return PortfolioSnapshot(
        as_of=as_of,
        equity=equity_value,
        cash=equity_value,
        day_start_equity=equity_value,
        peak_equity=equity_value,
    )


def decision_time(day: date) -> datetime:
    return eod(day) + timedelta(hours=1)
