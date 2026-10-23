"""Paper-to-live readiness: the exit criteria in ``rollout.paper_exit``, checked on the record.

Passing does not promote anything. Promotion to ``live_human_approval`` stays a config
change the owner makes and signs off; this report is the evidence for that decision.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

from aitrader.agents.risk.tokens import TokenError, TokenSigner
from aitrader.config.settings import PaperExitCriteria, RiskLimits
from aitrader.execution.orderbook import OrderBook
from aitrader.feedback.attribution import ClosedTrip
from aitrader.paper.runner import DayRecord
from aitrader.schemas.base import StrictModel, UtcDatetime
from aitrader.schemas.feedback import TcaResult
from aitrader.state.memory import InMemoryStateStore


class CriterionResult(StrictModel):
    name: str
    passed: bool
    observed: str
    required: str


class ReadinessReport(StrictModel):
    generated_at: UtcDatetime
    ready: bool
    results: tuple[CriterionResult, ...]


def untraced_fills(book: OrderBook, signer: TokenSigner) -> list[str]:
    """Fills whose instruction is missing from the book or whose approval token does not
    verify (signature, key, digest), checked offline without consuming anything."""
    problems = []
    records = {r.instruction.instruction_id: r.instruction for r in book.instructions()}
    for fill in book.fills():
        instruction = records.get(fill.instruction_id)
        if instruction is None:
            problems.append(f"{fill.broker_trade_id}: no instruction")
            continue
        try:
            signer.verify(instruction, instruction.approval.issued_at, InMemoryStateStore())
        except TokenError as exc:
            problems.append(f"{fill.broker_trade_id}: {exc}")
    return problems


def assess(
    *,
    records: Sequence[DayRecord],
    book: OrderBook,
    signer: TokenSigner,
    tca: Sequence[TcaResult],
    closed_trips: Sequence[ClosedTrip],
    criteria: PaperExitCriteria,
    limits: RiskLimits,
    now: datetime,
) -> ReadinessReport:
    results = []

    def add(name: str, passed: bool, observed: str, required: str) -> None:
        results.append(CriterionResult(name=name, passed=passed, observed=observed, required=required))

    add(
        "Sessions traded",
        len(records) >= criteria.min_sessions,
        str(len(records)),
        f">= {criteria.min_sessions}",
    )
    mismatches = sum(1 for r in records if r.reconciliation is None or not r.reconciliation.matched)
    add(
        "Days with a reconciliation mismatch",
        mismatches <= criteria.max_reconciliation_mismatch_days,
        str(mismatches),
        f"<= {criteria.max_reconciliation_mismatch_days}",
    )
    worst = max((r.total_drawdown for r in records), default=0.0)
    add(
        "Worst drawdown from peak",
        worst < limits.drawdown.total_limit,
        f"{worst:.2%}",
        f"< {limits.drawdown.total_limit:.0%}",
    )
    slippage_required = (
        f"<= {criteria.max_slippage_to_estimate_ratio:.1f}x over >= {criteria.min_tca_samples} instructions"
    )
    if len(tca) >= criteria.min_tca_samples:
        realised = sum(t.realised_slippage_bps for t in tca) / len(tca)
        estimated = sum(t.estimated_impact_bps for t in tca) / len(tca)
        ratio = realised / estimated if estimated > 0 else float("inf")
        add(
            "Realised slippage vs estimate",
            ratio <= criteria.max_slippage_to_estimate_ratio,
            f"{realised:.1f} vs {estimated:.1f} bps ({ratio:.2f}x)",
            slippage_required,
        )
    else:
        add(
            "Realised slippage vs estimate",
            False,
            f"{len(tca)} instructions analysed",
            slippage_required,
        )
    add(
        "Closed trades (measured edge)",
        len(closed_trips) >= criteria.min_closed_trades,
        str(len(closed_trips)),
        f">= {criteria.min_closed_trades}",
    )
    problems = untraced_fills(book, signer)
    add(
        "Fills traced to a verified approval token",
        not problems,
        f"{len(book.fills()) - len(problems)} of {len(book.fills())}"
        + (f"; first: {problems[0]}" if problems else ""),
        "all",
    )
    frozen = bool(records) and records[-1].frozen
    add("Trading not frozen now", not frozen and bool(records), "frozen" if frozen else "trading", "trading")
    return ReadinessReport(generated_at=now, ready=all(r.passed for r in results), results=tuple(results))
