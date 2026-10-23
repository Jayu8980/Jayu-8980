"""Monitoring dashboard: one self-contained HTML file (no external resources).

Shows trading status (freeze, reconciliation), the headline numbers, equity, drawdown
against its limit, gross and net exposure, open positions with their broker-held stops,
agent health and latency, per-agent accuracy, LLM cost per decision, execution quality
(TCA, recent fills) and the alert log.

Every text value is HTML-escaped here, and the page script writes data into the DOM with
``textContent`` only: instrument names and alert details are data, never markup. Colours
follow the validated reference palette (two series slots plus the fixed status palette) in
light and dark themes; status never relies on colour alone (icon + label).
"""

from __future__ import annotations

import html
import json
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import datetime
from importlib import resources

from aitrader.config.settings import RiskLimits
from aitrader.execution.orderbook import OrderBook, net_positions
from aitrader.paper.readiness import ReadinessReport
from aitrader.paper.runner import DayRecord
from aitrader.schemas.feedback import AgentScore, TcaResult
from aitrader.schemas.market import Severity
from aitrader.schemas.risk import RiskAlert


@dataclass(frozen=True)
class PositionRow:
    instrument: str
    quantity: int
    price: float
    value: float
    weight: float
    stop: float | None
    target: float | None
    protected: int


@dataclass
class DashboardData:
    title: str
    stage: str
    generated_at: datetime
    records: Sequence[DayRecord]
    positions: Sequence[PositionRow]
    scores: Sequence[AgentScore]
    tca: Sequence[TcaResult]
    limits: RiskLimits
    starting_equity: float
    recent_fills: Sequence[tuple[str, str, str, int, float, float]] = field(default_factory=list)
    readiness: ReadinessReport | None = None


def collect(
    *,
    title: str,
    stage: str,
    generated_at: datetime,
    records: Sequence[DayRecord],
    book: OrderBook,
    scores: Sequence[AgentScore],
    tca: Sequence[TcaResult],
    limits: RiskLimits,
    starting_equity: float,
    readiness: ReadinessReport | None = None,
) -> DashboardData:
    last = records[-1] if records else None
    held = net_positions(book.fills())
    rows = []
    for key, qty in sorted(held.items()):
        price = last.prices.get(key, 0.0) if last else 0.0
        live = [p for p in book.protections() if p.live and p.instrument.key == key]
        rows.append(
            PositionRow(
                instrument=key,
                quantity=qty,
                price=price,
                value=qty * price,
                weight=qty * price / last.equity if last and last.equity else 0.0,
                stop=float(live[-1].stop_trigger) if live else None,
                target=float(live[-1].target) if live and live[-1].target is not None else None,
                protected=sum(p.quantity for p in live),
            )
        )
    fills = sorted(book.fills(), key=lambda f: f.filled_at)[-15:]
    recent = [
        (
            f.filled_at.strftime("%Y-%m-%d %H:%M"),
            f.instrument.key,
            f.side.value,
            f.quantity,
            float(f.price),
            f.slippage_bps,
        )
        for f in reversed(fills)
    ]
    return DashboardData(
        title=title,
        stage=stage,
        generated_at=generated_at,
        records=records,
        positions=rows,
        scores=scores,
        tca=tca,
        limits=limits,
        starting_equity=starting_equity,
        recent_fills=recent,
        readiness=readiness,
    )


def _e(value: object) -> str:
    return html.escape(str(value), quote=True)


def _inr(value: float) -> str:
    sign = "-" if value < 0 else ""
    v = abs(value)
    if v >= 1e7:
        return f"{sign}₹{v / 1e7:.2f} Cr"
    if v >= 1e5:
        return f"{sign}₹{v / 1e5:.2f} L"
    return f"{sign}₹{v:,.0f}"


def _pct(value: float, digits: int = 1) -> str:
    return f"{value * 100:.{digits}f}%"


_ICONS = {"good": "\u2713", "warning": "!", "serious": "!", "critical": "\u2715", "neutral": "\u2013"}


def _status(kind: str, label: str) -> str:
    icon = f'<span class="status-icon" aria-hidden="true">{_ICONS[kind]}</span>'
    return f'<span class="status status-{kind}">{icon}{_e(label)}</span>'


def _tile(
    label: str, value: str, sub: str = "", hero: bool = False, meter: tuple[float, float] | None = None
) -> str:
    meter_html = ""
    if meter is not None:
        used, limit = meter
        frac = 0.0 if limit <= 0 else min(max(used / limit, 0.0), 1.0)
        level = "critical" if frac >= 1 else "warning" if frac >= 0.6 else "accent"
        meter_html = (
            f'<div class="meter" role="meter" aria-valuemin="0" '
            f'aria-valuemax="{limit:.4f}" aria-valuenow="{used:.4f}">'
            f'<div class="meter-fill meter-{level}" style="width:{frac * 100:.1f}%"></div></div>'
        )
    cls = "tile hero" if hero else "tile"
    return (
        f'<div class="{cls}"><div class="tile-label">{_e(label)}</div>'
        f'<div class="tile-value">{_e(value)}</div>'
        f'{meter_html}<div class="tile-sub">{_e(sub)}</div></div>'
    )


def _table(headers: Sequence[str], rows: Sequence[Sequence[str]], numeric: Sequence[bool], empty: str) -> str:
    if not rows:
        return f'<p class="empty">{_e(empty)}</p>'
    head = "".join(
        f'<th class="{"num" if n else ""}">{_e(h)}</th>' for h, n in zip(headers, numeric, strict=True)
    )
    body = "".join(
        "<tr>"
        + "".join(f'<td class="{"num" if n else ""}">{c}</td>' for c, n in zip(row, numeric, strict=True))
        + "</tr>"
        for row in rows
    )
    return f'<div class="table-wrap"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def _severity_kind(alert: RiskAlert) -> str:
    if alert.clears_alert_id is not None:
        return "good"
    return {Severity.CRITICAL: "critical", Severity.WARNING: "warning", Severity.INFO: "neutral"}[
        alert.severity
    ]


def render(data: DashboardData) -> str:
    records = list(data.records)
    last = records[-1] if records else None
    prev = records[-2] if len(records) > 1 else None
    lim = data.limits
    if last is None:
        tiles = '<p class="empty">No sessions recorded yet.</p>'
        banner = _status("neutral", "No data")
    else:
        day_pnl = last.equity - (prev.equity if prev else data.starting_equity)
        total_ret = last.equity / data.starting_equity - 1
        decisions = sum(r.decisions for r in records)
        llm_total = sum(r.llm_cost_usd for r in records)
        tiles = "".join(
            [
                _tile("Equity", _inr(last.equity), f"{total_ret:+.2%} since start", hero=True),
                _tile("Day P&L", _inr(day_pnl), f"{day_pnl / (last.equity - day_pnl):+.2%} on the day"),
                _tile(
                    "Drawdown from peak",
                    _pct(last.total_drawdown, 2),
                    f"limit {_pct(lim.drawdown.total_limit, 0)}",
                    meter=(last.total_drawdown, lim.drawdown.total_limit),
                ),
                _tile(
                    "Gross exposure",
                    _pct(last.gross_exposure),
                    f"limit {_pct(lim.portfolio.max_gross_exposure, 0)}",
                    meter=(last.gross_exposure, lim.portfolio.max_gross_exposure),
                ),
                _tile("Open positions", str(len(data.positions)), f"max {lim.portfolio.max_open_positions}"),
                _tile(
                    "LLM cost per decision",
                    f"${llm_total / decisions:.3f}" if decisions else "$0.000",
                    f"${llm_total:.2f} over {decisions} decisions"
                    if llm_total
                    else "LLM agents off (models not pinned)",
                ),
            ]
        )
        frozen = (
            _status("critical", "Frozen: new entries blocked")
            if last.frozen
            else _status("good", "Trading normally")
        )
        rec = last.reconciliation
        reconciled = (
            _status("good", "Ledger matches broker")
            if rec and rec.matched
            else _status("critical", "Reconciliation mismatch")
            if rec
            else _status("neutral", "Not reconciled")
        )
        banner = frozen + reconciled + _status("neutral", f"Limits \u00d7 {last.limit_multiplier:.2f}")

    positions = _table(
        ["Instrument", "Qty", "Last", "Value", "Weight", "Stop", "Target", "Protected"],
        [
            [
                _e(p.instrument),
                f"{p.quantity:,}",
                f"{p.price:,.2f}",
                _e(_inr(p.value)),
                _pct(p.weight),
                f"{p.stop:,.2f}" if p.stop is not None else "\u2013",
                f"{p.target:,.2f}" if p.target is not None else "\u2013",
                (
                    _status("good", f"{p.protected:,}")
                    if p.protected == abs(p.quantity)
                    else _status("critical", f"{p.protected:,} of {abs(p.quantity):,}")
                ),
            ]
            for p in data.positions
        ],
        [False, True, True, True, True, True, True, True],
        "No open positions.",
    )

    latency: dict[str, list[float]] = {}
    last_status: dict[str, str] = {}
    unhealthy: dict[str, int] = {}
    for r in records:
        for agent, ms in r.agent_latency_ms.items():
            latency.setdefault(agent, []).append(ms)
            last_status[agent] = r.agent_status.get(agent, "")
            unhealthy[agent] = unhealthy.get(agent, 0) + (r.agent_status.get(agent) != "healthy")
    scores = {s.agent_id.value: s for s in data.scores}
    averages = {k: sum(v) / len(v) for k, v in latency.items() if v}
    max_avg = max(averages.values(), default=0.0) or 1.0
    agent_rows = []
    for name in sorted(set(latency) | set(scores)):
        lat = latency.get(name, [])
        avg = averages.get(name, 0.0)
        st = last_status.get(name, "")
        kind = "good" if st == "healthy" else "critical" if st in ("failed", "timed_out") else "neutral"
        s = scores.get(name)
        agent_rows.append(
            [
                _e(name),
                _status(kind, st.replace("_", " ") or "not run"),
                f"{lat[-1]:,.0f}" if lat else "\u2013",
                f'<span class="bar-cell"><span class="inline-bar" style="width:{avg / max_avg * 100:.1f}%">'
                f"</span>{avg:,.0f}</span>",
                str(unhealthy.get(name, 0)),
                _pct(s.hit_rate) if s and s.hit_rate is not None else "\u2013",
                f"{s.information_coefficient:+.2f}"
                if s and s.information_coefficient is not None
                else "\u2013",
                f"{s.credibility:.2f}" if s else "\u2013",
                f"{s.sample_size:,}" if s else "\u2013",
            ]
        )
    agent_table = _table(
        [
            "Agent",
            "Last status",
            "Latency (ms)",
            "Avg latency (ms)",
            "Unhealthy cycles",
            "Hit rate",
            "IC",
            "Credibility",
            "Scored calls",
        ],
        agent_rows,
        [False, False, True, True, True, True, True, True, True],
        "No agent activity yet.",
    )

    tca_rows = [
        [
            _e(t.instrument.key),
            f"{t.filled_quantity:,}",
            f"{t.avg_price:,.2f}",
            f"{t.realised_slippage_bps:+.1f}",
            f"{t.estimated_impact_bps:.1f}",
            f"{t.estimation_error_bps:+.1f}",
        ]
        for t in list(data.tca)[-12:]
    ]
    tca_table = _table(
        ["Instrument", "Filled", "Avg price", "Slippage (bps)", "Estimate (bps)", "Error (bps)"],
        tca_rows,
        [False, True, True, True, True, True],
        "No completed instructions yet.",
    )
    fills_table = _table(
        ["Time (UTC)", "Instrument", "Side", "Qty", "Price", "Slippage (bps)"],
        [[_e(t), _e(k), _e(s), f"{q:,}", f"{p:,.2f}", f"{b:+.1f}"] for t, k, s, q, p, b in data.recent_fills],
        [False, False, False, True, True, True],
        "No fills yet.",
    )
    alerts = [(r.day, a) for r in records for a in r.alerts]
    alert_rows = [
        [
            _e(d.isoformat()),
            _status(_severity_kind(a), a.alert_type.value.replace("_", " ")),
            _e(a.detail),
            "yes" if a.freeze_new_orders else "no",
        ]
        for d, a in reversed(alerts[-30:])
    ]
    alert_table = _table(
        ["Day", "Type", "Detail", "Freezes entries"], alert_rows, [False, False, False, False], "No alerts."
    )

    if data.readiness is None:
        readiness = '<p class="empty">Not assessed.</p>'
    else:
        verdict = (
            _status("good", "All exit criteria met: the owner may decide on promotion")
            if data.readiness.ready
            else _status("warning", "Not ready for live trading")
        )
        readiness = (
            '<div class="banner">'
            + verdict
            + "</div>"
            + _table(
                ["Criterion", "Status", "Observed", "Required"],
                [
                    [
                        _e(c.name),
                        _status("good", "met") if c.passed else _status("critical", "not met"),
                        _e(c.observed),
                        _e(c.required),
                    ]
                    for c in data.readiness.results
                ],
                [False, False, False, False],
                "No criteria.",
            )
        )
    series = {
        "dates": [r.day.isoformat() for r in records],
        "equity": [round(r.equity, 2) for r in records],
        "drawdown": [round(-r.total_drawdown, 6) for r in records],
        "gross": [round(r.gross_exposure, 6) for r in records],
        "net": [round(r.net_exposure, 6) for r in records],
        "ddLimit": -lim.drawdown.total_limit,
    }
    payload = json.dumps(series, separators=(",", ":")).replace("</", "<\\/")
    title = _e(data.title)
    return _asset("dashboard.html").format(
        title=title,
        stage=_e(data.stage.replace("_", " ")),
        generated=_e(data.generated_at.strftime("%Y-%m-%d %H:%M UTC")),
        sessions=len(records),
        banner=banner,
        tiles=tiles,
        positions=positions,
        agents=agent_table,
        tca=tca_table,
        fills=fills_table,
        alerts=alert_table,
        readiness=readiness,
        data=payload,
        script=_asset("dashboard.js"),
        style=_asset("dashboard.css"),
    )


def _asset(name: str) -> str:
    return (resources.files("aitrader.monitoring") / "assets" / name).read_text(encoding="utf-8")
