"""Deterministic boss: the rule set used when no LLM is configured, when the LLM boss fails
or times out, and as the baseline the LLM boss is scored against.

- Flat, with a long chart setup: BUY when the credibility-weighted consensus is at least
  ``min_consensus``, agreement at least ``min_agreement`` and no entry-blocking event is
  active. Risk budget = consensus x agreement, capped at 0.5 (fallback decisions never use
  more than half the normal budget).
- Long position: SELL (exit) when consensus is at most ``-min_consensus`` with the same
  agreement; REDUCE when it is merely negative beyond ``reduce_below``; otherwise HOLD.
- Everything else: WAIT. The fallback never opens shorts.
"""

from __future__ import annotations

from datetime import timedelta
from uuid import UUID

from aitrader.agents.boss.inputs import BossInput
from aitrader.agents.risk_reward.agent import blocking_flags
from aitrader.schemas.base import AgentId, Horizon
from aitrader.schemas.decision import Action, AgentWeight, BossDecision, Invalidation, InvalidationKind
from aitrader.schemas.signals import SetupType
from aitrader.tools.attribution import credibility_weights, weighted_consensus

MIN_CONSENSUS = 0.25
MIN_AGREEMENT = 0.6
REDUCE_BELOW = -0.15
MAX_FALLBACK_BUDGET = 0.5


def consensus_of(inp: BossInput) -> tuple[float, float, tuple[str, ...], dict[str, float]]:
    signals = inp.signals()
    if not signals:
        return 0.0, 0.0, (), {}
    weights = credibility_weights(credibility=inp.weights()).weights
    c = weighted_consensus(signals=signals, weights=weights)
    return c.consensus, c.agreement, c.dissenters, weights


def fallback_decision(inp: BossInput, decision_id: UUID) -> BossDecision:
    consensus, agreement, dissenters, weights = consensus_of(inp)
    agent_weights = tuple(
        AgentWeight(agent_id=AgentId(a), weight=round(w, 6), signal_message_id=inp.message_ids.get(a))
        for a, w in sorted(weights.items())
    )
    reasons = [f"credibility-weighted consensus {consensus:+.2f}, agreement {agreement:.0%}"]
    if inp.chart is not None:
        reasons.append(f"chart {inp.chart.setup.value} setup, alignment {inp.chart.alignment_score:+.2f}")
    if inp.smart_money is not None:
        reasons.append(f"smart money accumulation {inp.smart_money.accumulation_score:+.2f}")
    main_risk = f"dissent from {', '.join(dissenters)}" if dissenters else "signals may already be priced in"
    blocked = blocking_flags(inp.flags, inp.instrument.key, inp.as_of, inp.as_of + timedelta(days=14))
    common = {
        "decision_id": decision_id,
        "instrument": inp.instrument,
        "horizon": Horizon.SWING,
        "conviction": round(min(abs(consensus) * agreement, 1.0), 6),
        "key_reasons": tuple(reasons[:5]),
        "main_risk": main_risk,
        "critical_disagreement": main_risk if dissenters else None,
        "agent_weights": agent_weights,
        "used_fallback": True,
    }
    held = inp.holding.quantity if inp.holding else 0
    chart = inp.chart
    if held > 0:
        if consensus <= -MIN_CONSENSUS and agreement >= MIN_AGREEMENT:
            return BossDecision(
                action=Action.SELL, risk_budget_fraction=0.0, invalidation=_hold_invalidation(inp), **common
            )
        if consensus <= REDUCE_BELOW:
            return BossDecision(
                action=Action.REDUCE, risk_budget_fraction=0.0, invalidation=_hold_invalidation(inp), **common
            )
        return BossDecision(action=Action.HOLD, risk_budget_fraction=0.0, **common)
    long_setup = (
        chart is not None
        and chart.setup is not SetupType.NONE
        and chart.structural_stop is not None
        and chart.entry_low is not None
        and chart.structural_stop < chart.entry_low
    )
    if held == 0 and long_setup and consensus >= MIN_CONSENSUS and agreement >= MIN_AGREEMENT and not blocked:
        assert chart is not None and chart.structural_stop is not None
        budget = min(MAX_FALLBACK_BUDGET, consensus * agreement, inp.discretion_cap)
        return BossDecision(
            action=Action.BUY,
            risk_budget_fraction=round(max(budget, 0.01), 6),
            invalidation=Invalidation(
                condition=InvalidationKind.PRICE_CLOSE_BELOW,
                level=chart.structural_stop,
                description="close below the chart's structural stop",
            ),
            **common,
        )
    return BossDecision(action=Action.WAIT if held == 0 else Action.HOLD, risk_budget_fraction=0.0, **common)


def _hold_invalidation(inp: BossInput) -> Invalidation:
    return Invalidation(
        condition=InvalidationKind.REGIME_CHANGE,
        description="consensus turns back positive",
    )
