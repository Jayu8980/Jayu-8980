"""Notification agent: forwards risk alerts as they happen and sends a daily summary.

Every freezing or critical alert is sent once (by alert id); warnings and info are batched
into the daily summary. Human releases of a freeze are sent too, so the operator sees both
ends. The summary is built from the day's record, never from free text an agent produced.
"""

from __future__ import annotations

from aitrader.bus.interface import MessageBus
from aitrader.notify.channels import Notifier
from aitrader.paper.runner import DayRecord
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.market import Severity
from aitrader.schemas.risk import RiskAlert


def format_alert(alert: RiskAlert) -> str:
    if alert.clears_alert_id is not None:
        return f"RELEASED: {alert.alert_type.value.replace('_', ' ')}. {alert.detail}"
    action = "New entries frozen." if alert.freeze_new_orders else "No freeze."
    if alert.flatten_positions:
        action += " Flattening all positions."
    return (
        f"{alert.severity.value.upper()} {alert.alert_type.value.replace('_', ' ')}: {alert.detail}\n{action}"
    )


def format_summary(record: DayRecord, starting_equity: float) -> str:
    lines = [
        f"Paper trading {record.day.isoformat()}",
        f"Equity {record.equity:,.0f} ({record.equity / starting_equity - 1:+.2%} since start)",
        f"Drawdown {record.total_drawdown:.2%}, gross exposure {record.gross_exposure:.1%}",
        f"Positions {sum(1 for q in record.positions.values() if q)}, fills today {len(record.fills)}",
        f"Instructions accepted {record.instructions}, rejected {record.rejected_instructions}",
        "Reconciled"
        if record.reconciliation and record.reconciliation.matched
        else "RECONCILIATION MISMATCH",
    ]
    if record.frozen:
        lines.append("TRADING FROZEN: new entries blocked until a human releases it")
    if record.alerts:
        lines.append(f"Alerts today: {len(record.alerts)}")
    return "\n".join(lines)


class NotificationAgent:
    def __init__(self, notifier: Notifier) -> None:
        self._notifier = notifier
        self._sent: set[str] = set()

    def attach(self, bus: MessageBus) -> None:
        async def on_alert(envelope: Envelope) -> None:
            if isinstance(envelope.payload, RiskAlert):
                self.alert(envelope.payload)

        bus.subscribe((Topic.RISK_ALERTS,), on_alert, group="notifications")

    def alert(self, alert: RiskAlert) -> None:
        urgent = alert.freeze_new_orders or alert.severity is Severity.CRITICAL or alert.clears_alert_id
        key = str(alert.alert_id)
        if urgent and key not in self._sent:
            self._sent.add(key)
            self._notifier.send(format_alert(alert))

    def daily(self, record: DayRecord, starting_equity: float) -> None:
        self._notifier.send(format_summary(record, starting_equity))
