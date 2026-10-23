"""Kill switch: manual, file-based and automatic.

Engaging it freezes new entries at once in the state store (so it works with the bus down)
and publishes a ``KILL_SWITCH`` interrupt that makes the execution agent cancel resting entry
orders. Protective stops and exits are left alone; positions are flattened only when the
alert asks for it, through exit instructions the risk agent signs.

The file switch is for operators without access to the process: creating the file (path in
config) engages it; text containing "flatten" also flattens. Only a human can release it,
and not while the file still exists. Automatic engagement comes from the execution agent
(broker failures, reconciliation mismatches, unfilled stops, failed authentication) and the
risk agent (drawdown breaches) raising freeze alerts.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from uuid import UUID, uuid5

from aitrader.agents.publishing import publish
from aitrader.bus.interface import MessageBus
from aitrader.schemas.base import AgentId, Priority
from aitrader.schemas.envelope import Topic
from aitrader.schemas.market import Severity
from aitrader.schemas.risk import AlertType, RiskAlert
from aitrader.state.interface import StateStore
from aitrader.state.memory import FreezeClearRefusedError

_NS = UUID("6a1e2b3c-4d5e-4f60-8172-93a4b5c6d7e8")


class KillSwitch:
    def __init__(self, bus: MessageBus, state: StateStore, path: Path | None = None) -> None:
        self._bus = bus
        self._state = state
        self._path = path
        self.engaged: RiskAlert | None = None

    async def _publish(self, alert: RiskAlert, now: datetime, agent: AgentId) -> None:
        await publish(
            self._bus,
            message_id=uuid5(_NS, str(alert.alert_id)),
            topic=Topic.RISK_ALERTS,
            agent_id=agent,
            payload=alert,
            available_at=now,
            produced_at=now,
            priority=Priority.INTERRUPT,
        )

    async def engage(
        self, reason: str, now: datetime, *, by_human: bool = True, flatten: bool = False
    ) -> RiskAlert:
        if self.engaged is not None:
            return self.engaged
        alert = RiskAlert(
            alert_id=uuid5(_NS, f"kill|{now.isoformat()}|{reason}"),
            alert_type=AlertType.KILL_SWITCH,
            severity=Severity.CRITICAL,
            freeze_new_orders=True,
            flatten_positions=flatten,
            detail=reason[:500] or "kill switch engaged",
        )
        self._state.set_frozen(alert.alert_id, now)
        self.engaged = alert
        await self._publish(alert, now, AgentId.HUMAN if by_human else AgentId.EXECUTION)
        return alert

    async def poll(self, now: datetime) -> RiskAlert | None:
        """Engage if the kill file exists. Returns the alert when this call engaged it."""
        if (
            self.engaged is not None
            and self.engaged.alert_id not in self._state.freeze_status().reason_alert_ids
        ):
            self.engaged = None  # released by a human from another process (the CLI)
        if self.engaged is not None or self._path is None or not self._path.exists():
            return None
        text = self._path.read_text(encoding="utf-8", errors="replace").strip()[:400]
        return await self.engage(
            f"kill file {self._path.name}: {text or 'present'}", now, flatten="flatten" in text.lower()
        )

    async def release(self, now: datetime, *, cleared_by_human: bool) -> None:
        if self.engaged is None:
            return
        if not cleared_by_human:
            raise FreezeClearRefusedError("only a human can release the kill switch")
        if self._path is not None and self._path.exists():
            raise FreezeClearRefusedError(f"remove {self._path} before releasing the kill switch")
        alert = self.engaged
        self._state.clear_freeze(alert.alert_id, cleared_by_human=True)
        await self._publish(
            RiskAlert(
                alert_id=uuid5(_NS, f"release|{alert.alert_id}|{now.isoformat()}"),
                alert_type=AlertType.KILL_SWITCH,
                severity=Severity.INFO,
                freeze_new_orders=False,
                detail="kill switch released by a human",
                clears_alert_id=alert.alert_id,
            ),
            now,
            AgentId.HUMAN,
        )
        self.engaged = None
