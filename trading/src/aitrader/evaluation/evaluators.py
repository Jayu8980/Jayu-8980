"""Replay evaluators for the deterministic analysts.

Each evaluator walks decision times (``step`` sessions apart), asks the agent for its signal
at that time, then scores the signal on what followed. Sampling every few sessions keeps
overlapping forward windows from inflating the sample size.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from datetime import date, time, timedelta

import numpy as np

from aitrader.agents.chart.agent import ChartAgent
from aitrader.agents.data.agent import MarketData, index_instrument
from aitrader.agents.data.calendar import ist_datetime
from aitrader.agents.smart_money.engine import SmartMoneyEngine
from aitrader.agents.trend.agent import SCENARIOS, TrendAgent
from aitrader.evaluation.outcomes import bars_after, forward_return, r_outcome
from aitrader.evaluation.report import AgentEvaluation
from aitrader.evaluation.scoring import hit_rate, ic, multiclass_brier
from aitrader.schemas.base import AgentId, Instrument, Timeframe
from aitrader.schemas.signals import SetupType

DECISION = time(20, 0)


def _decisions(sessions: Sequence[date], step: int, horizon: int) -> list[date]:
    usable = list(sessions[: len(sessions) - horizon]) if horizon else list(sessions)
    return usable[::step]


def evaluate_chart(
    market: MarketData,
    instruments: Sequence[Instrument],
    sessions: Sequence[date],
    *,
    horizon: int = 10,
    max_hold: int = 20,
    step: int = 5,
) -> AgentEvaluation:
    agent = ChartAgent(market)
    data_as_of = ist_datetime(sessions[-1], time(23, 59))
    scores, outcomes = [], []
    setups: dict[str, list[float]] = defaultdict(list)
    for day in _decisions(sessions, step, horizon):
        as_of = ist_datetime(day, DECISION)
        for inst in instruments:
            signal = agent.analyse(inst, as_of)
            if signal is None:
                continue
            fwd = forward_return(market, inst, as_of, horizon, data_as_of)
            if fwd is not None:
                scores.append(signal.score)
                outcomes.append(fwd)
            stop, low = signal.structural_stop, signal.entry_low
            if signal.setup is not SetupType.NONE and stop is not None and low is not None and signal.targets:
                path = bars_after(market, inst, as_of, max_hold, data_as_of)
                then = market.bars(inst, Timeframe.D1, as_of, start=as_of - timedelta(days=10)).bars
                if not path or not then:
                    continue
                # Levels are in the signal day's price basis; the path is adjusted as of the end
                # of the data. Rescale so a later split cannot distort the R multiple.
                basis = float(path[0].close) / float(then[-1].close)
                direction = 1 if stop < low else -1
                result = r_outcome(
                    path,
                    direction,
                    float(path[0].close),
                    float(stop) * basis,
                    float(signal.targets[0]) * basis,
                )
                if result is not None:
                    setups[signal.setup.value].append(result[0])
    hit, n_hit = hit_rate(scores, outcomes)
    up_rate = float(np.mean([o > 0 for o in outcomes])) if outcomes else None
    by_setup = {
        name: {
            "trades": float(len(rs)),
            "win_rate": float(np.mean([r > 0 for r in rs])),
            "avg_r": float(np.mean(rs)),
        }
        for name, rs in sorted(setups.items())
    }
    return AgentEvaluation(
        agent_id=AgentId.CHART,
        start=sessions[0],
        end=sessions[-1],
        n=len(scores),
        metrics={"hit_rate": hit, "ic": ic(scores, outcomes), "directional_calls": float(n_hit)},
        baselines={"always_long_hit_rate": up_rate, "ic": 0.0},
        by_group=by_setup,
        notes=(f"forward horizon {horizon} sessions, setups held up to {max_hold}, sampled every {step}",),
    )


def evaluate_smart_money(
    market: MarketData,
    engine: SmartMoneyEngine,
    instruments: Sequence[Instrument],
    sessions: Sequence[date],
    *,
    horizon: int = 5,
    step: int = 5,
) -> AgentEvaluation:
    data_as_of = ist_datetime(sessions[-1], time(23, 59))
    scores, outcomes, divergences = [], [], []
    for day in _decisions(sessions, step, horizon):
        as_of = ist_datetime(day, DECISION)
        for inst in instruments:
            signal = engine.stock_signal(inst, as_of)
            fwd = forward_return(market, inst, as_of, horizon, data_as_of) if signal else None
            if signal is None or fwd is None:
                continue
            scores.append(signal.accumulation_score)
            outcomes.append(fwd)
            if signal.divergence_alert:
                divergences.append(np.sign(fwd) == np.sign(signal.accumulation_score))
    hit, _ = hit_rate(scores, outcomes)
    return AgentEvaluation(
        agent_id=AgentId.SMART_MONEY,
        start=sessions[0],
        end=sessions[-1],
        n=len(scores),
        metrics={
            "ic": ic(scores, outcomes),
            "hit_rate": hit,
            "divergence_precision": float(np.mean(divergences)) if divergences else None,
        },
        baselines={
            "ic": 0.0,
            "always_long_hit_rate": float(np.mean([o > 0 for o in outcomes])) if outcomes else None,
        },
        notes=(f"forward horizon {horizon} sessions, sampled every {step}",),
    )


def realised_scenario(ret: float, band: float) -> int:
    return 0 if ret > band else 2 if ret < -band else 1  # index into SCENARIOS


def evaluate_trend(
    market: MarketData,
    agent: TrendAgent,
    sessions: Sequence[date],
    *,
    horizon: int = 20,
    band: float = 0.03,
    step: int = 5,
) -> AgentEvaluation:
    data_as_of = ist_datetime(sessions[-1], time(23, 59))
    index = index_instrument("NIFTY_50")
    probs, realised = [], []
    for day in _decisions(sessions, step, horizon):
        as_of = ist_datetime(day, DECISION)
        outlook, _ = agent.outlook(as_of)
        fwd = forward_return(market, index, as_of, horizon, data_as_of)
        if outlook is None or fwd is None:
            continue
        by_name = {s.name: s.probability for s in outlook.scenarios}
        probs.append([by_name[name] for name in SCENARIOS])
        realised.append(realised_scenario(fwd, band))
    brier = multiclass_brier(probs, realised)
    uniform = multiclass_brier([[1 / 3] * 3] * len(realised), realised)
    freq = [float(np.mean([r == k for r in realised])) for k in range(3)] if realised else None
    climatology = multiclass_brier([freq] * len(realised), realised) if freq else None
    return AgentEvaluation(
        agent_id=AgentId.TREND,
        start=sessions[0],
        end=sessions[-1],
        n=len(realised),
        metrics={
            "brier": brier,
            "accuracy": float(np.mean([int(np.argmax(p)) == r for p, r in zip(probs, realised, strict=True)]))
            if probs
            else None,
        },
        baselines={"brier_uniform": uniform, "brier_climatology_in_sample": climatology},
        notes=(
            f"scenarios realised from {horizon}-session NIFTY return with a +/-{band:.0%} band",
            "climatology uses the evaluation sample's own frequencies, so it is an optimistic baseline",
        ),
    )


def evaluate_news(
    events: Sequence[tuple[float, float, float]], start: date, end: date
) -> AgentEvaluation | None:
    """``events``: (sentiment, materiality, 1-day CAR) triples from completed event studies."""
    if not events:
        return None
    sentiment = [e[0] for e in events]
    materiality = [e[1] for e in events]
    car = [e[2] for e in events]
    return AgentEvaluation(
        agent_id=AgentId.NEWS,
        start=start,
        end=end,
        n=len(events),
        metrics={
            "sentiment_ic": ic(sentiment, car),
            "materiality_vs_abs_car_ic": ic(materiality, [abs(c) for c in car]),
        },
        baselines={"sentiment_ic": 0.0},
    )
