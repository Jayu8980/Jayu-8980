"""Slicer, market clock, rate limiter, kill switch and flatten instructions."""

from __future__ import annotations

from datetime import date, time
from pathlib import Path
from uuid import uuid4

import pytest

from aitrader.agents.data.calendar import ist_datetime
from aitrader.agents.risk.agent import RiskAgent
from aitrader.agents.risk.portfolio import Holding, PortfolioSnapshot
from aitrader.execution.checks import MarketClock, MarketPhase, RateLimiter
from aitrader.execution.killswitch import KillSwitch
from aitrader.schemas.base import AgentId
from aitrader.schemas.market import Severity
from aitrader.schemas.orders import InstructionIntent, Side
from aitrader.schemas.risk import AlertType, RiskAlert
from aitrader.state.memory import FreezeClearRefusedError
from aitrader.tools.microstructure import slicer
from tests.execution_helpers import XYZ, rig, run


def test_slicer_respects_freeze_quantity_and_value_cap() -> None:
    plan = slicer(
        quantity=3000,
        lot_size=75,
        price=120.0,
        max_order_value=10_000_000,
        algo="marketable_limit",
        horizon_minutes=30,
        freeze_quantity=1801,
    )
    assert sum(plan.slices) == 3000 and max(plan.slices) <= 1800
    assert all(q % 75 == 0 for q in plan.slices)
    capped = slicer(
        quantity=1000,
        lot_size=1,
        price=500.0,
        max_order_value=200_000,
        algo="marketable_limit",
        horizon_minutes=30,
    )
    assert max(capped.slices) * 500 <= 200_000 and sum(capped.slices) == 1000
    twap = slicer(
        quantity=1200, lot_size=1, price=10.0, max_order_value=1e9, algo="twap", horizon_minutes=120
    )
    assert len(twap.slices) == 8 and twap.interval_seconds == 900
    with pytest.raises(ValueError, match="single lot"):
        slicer(
            quantity=75, lot_size=75, price=5000.0, max_order_value=200_000, algo="twap", horizon_minutes=30
        )


def test_market_clock_phases_and_next_open() -> None:
    sessions = [date(2026, 10, 22), date(2026, 10, 23), date(2026, 10, 26)]
    clock = MarketClock(sessions=sessions)
    assert clock.phase(ist_datetime(sessions[0], time(9, 5))) is MarketPhase.PRE_OPEN
    assert clock.phase(ist_datetime(sessions[0], time(11, 0))) is MarketPhase.OPEN
    assert clock.phase(ist_datetime(sessions[0], time(15, 30))) is MarketPhase.CLOSED
    assert clock.phase(ist_datetime(date(2026, 10, 24), time(11, 0))) is MarketPhase.CLOSED  # Saturday
    friday_evening = ist_datetime(sessions[1], time(20, 0))
    assert clock.next_open(friday_evening) == ist_datetime(sessions[2], time(9, 15))
    during = ist_datetime(sessions[0], time(10, 0))
    assert clock.next_open(during) == during
    holiday_clock = MarketClock(holidays=[date(2026, 10, 26)])
    assert holiday_clock.next_open(friday_evening) == ist_datetime(date(2026, 10, 27), time(9, 15))


def test_rate_limiter_caps_orders_per_second() -> None:
    now = [0.0]
    slept: list[float] = []

    async def sleep(seconds: float) -> None:
        slept.append(seconds)
        now[0] += seconds

    limiter = RateLimiter(8, monotonic=lambda: now[0], sleep=sleep)

    async def burst() -> None:
        for _ in range(24):
            await limiter.acquire()

    run(burst())
    assert now[0] == pytest.approx(2.0)  # 8 immediately, then 8 per second
    assert limiter.waits == 16


def test_kill_file_engages_and_only_a_human_releases(tmp_path: Path) -> None:
    r = rig()
    path = tmp_path / "KILL"
    switch = KillSwitch(r.bus, r.state, path)
    now = r.at(0, 9, 0)
    assert run(switch.poll(now)) is None
    path.write_text("flatten: suspicious fills", encoding="utf-8")
    alert = run(switch.poll(now))
    assert alert is not None and alert.flatten_positions and alert.alert_type is AlertType.KILL_SWITCH
    assert r.state.freeze_status().frozen
    published = [e for e in r.envelopes if e.payload == alert]
    assert published and published[0].agent_id is AgentId.HUMAN
    with pytest.raises(FreezeClearRefusedError):
        run(switch.release(now, cleared_by_human=False))
    with pytest.raises(FreezeClearRefusedError, match="remove"):
        run(switch.release(now, cleared_by_human=True))
    path.unlink()
    run(switch.release(now, cleared_by_human=True))
    assert not r.state.freeze_status().frozen
    assert any(
        isinstance(e.payload, RiskAlert) and e.payload.clears_alert_id == alert.alert_id for e in r.envelopes
    )


def test_engaged_kill_switch_refuses_entries_but_not_exits() -> None:
    r = rig()
    r.submit(r.instruction(0))
    r.session(1)
    run(KillSwitch(r.bus, r.state).engage("manual stop", r.at(1, 19, 0)))
    assert r.submit(r.instruction(1)).reason.startswith("trading frozen")
    exit_ = r.submit(r.instruction(1, side=Side.SELL, limit="99.00", intent=InstructionIntent.CLOSE))
    assert exit_.reason is None


def test_flatten_instructions_are_signed_exits_verifiable_once() -> None:
    r = rig()
    risk = RiskAgent(r.market, r.limits, r.state, r.signer)
    as_of = ist_datetime(r.days[3], time(20, 0))
    portfolio = PortfolioSnapshot(
        as_of=as_of,
        equity=1e6,
        cash=9e5,
        day_start_equity=1e6,
        peak_equity=1e6,
        holdings=(Holding(XYZ, 100, 100.0),),
    )
    alert = RiskAlert(
        alert_id=uuid4(),
        alert_type=AlertType.DRAWDOWN_BREACH,
        severity=Severity.CRITICAL,
        freeze_new_orders=True,
        flatten_positions=True,
        detail="total drawdown breached",
    )
    (exit_,) = risk.flatten_instructions(alert, portfolio, as_of)
    assert exit_.intent is InstructionIntent.CLOSE and exit_.side is Side.SELL and exit_.quantity == 100
    assert risk.flatten_instructions(alert, portfolio, as_of) == [exit_]  # deterministic
    r.signer.verify(exit_, as_of, r.state)
    no_flatten = alert.model_copy(update={"flatten_positions": False})
    assert risk.flatten_instructions(no_flatten, portfolio, as_of) == []
