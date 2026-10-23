"""Corporate-action price adjustment, applied as of a point in time.

Ratio conventions on ``CorporateAction`` (numerator:denominator):
- SPLIT:  new shares : old shares (face value 10 -> 2 is 5:1); price factor = D / N.
- BONUS:  bonus shares : shares held (1:1 doubles the count);   price factor = D / (D + N).
- DIVIDEND: price factor = 1 - cash / close before the ex-date (total-return adjustment).
Bars traded before the ex-date are multiplied by every factor whose ex-date is after the bar
and on or before the as-of date, and only for actions announced (available) by then.
RIGHTS, mergers and demergers need terms-specific handling and are reported, not adjusted.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

from aitrader.agents.data.calendar import ist_date
from aitrader.schemas.market import Bar, CorporateAction, CorporateActionType

PRICE_QUANTUM = Decimal("0.0001")


@dataclass(frozen=True)
class AdjustmentFactor:
    ex_date: date
    price_factor: Decimal  # multiply earlier prices by this
    volume_factor: Decimal  # multiply earlier volumes by this
    action: CorporateActionType


class UnsupportedAdjustmentError(ValueError):
    pass


def factor_for(
    action: CorporateAction, close_before_ex: Callable[[date], Decimal | None]
) -> AdjustmentFactor | None:
    """Return the adjustment for an action, None if it does not change prices."""
    n, d = action.ratio_numerator, action.ratio_denominator
    kind = action.action_type
    if kind in (CorporateActionType.SPLIT, CorporateActionType.BONUS):
        if n is None or d is None:
            raise UnsupportedAdjustmentError(f"{kind} on {action.ex_date} is missing its ratio")
        shares_multiplier = n / d if kind is CorporateActionType.SPLIT else (d + n) / d
        return AdjustmentFactor(action.ex_date, 1 / shares_multiplier, shares_multiplier, kind)
    if kind is CorporateActionType.DIVIDEND:
        if action.cash_amount is None:
            raise UnsupportedAdjustmentError(f"dividend on {action.ex_date} has no amount")
        close = close_before_ex(action.ex_date)
        if close is None or close <= action.cash_amount:
            raise UnsupportedAdjustmentError(f"no usable close before dividend ex-date {action.ex_date}")
        return AdjustmentFactor(action.ex_date, 1 - action.cash_amount / close, Decimal(1), kind)
    if kind in (CorporateActionType.RIGHTS, CorporateActionType.MERGER, CorporateActionType.DEMERGER):
        raise UnsupportedAdjustmentError(f"{kind} on {action.ex_date} needs manual adjustment terms")
    return None  # symbol change, delisting: no price effect


def apply_factors(bars: Sequence[Bar], factors: Sequence[AdjustmentFactor], as_of_date: date) -> list[Bar]:
    """Back-adjust bars. Only factors with ex_date <= as_of_date apply."""
    active = [f for f in factors if f.ex_date <= as_of_date]
    out = []
    for bar in bars:
        trade_date = ist_date(bar.bar_end)
        price_mult, volume_mult = Decimal(1), Decimal(1)
        for f in active:
            if trade_date < f.ex_date:
                price_mult *= f.price_factor
                volume_mult *= f.volume_factor
        if price_mult == 1 and volume_mult == 1:
            out.append(bar)
            continue

        def scale(p: Decimal, m: Decimal = price_mult) -> Decimal:
            return (p * m).quantize(PRICE_QUANTUM, rounding=ROUND_HALF_EVEN)

        out.append(  # re-validated so adjusted bars obey the same schema as raw ones
            Bar.model_validate(
                {
                    **bar.model_dump(),
                    "open": scale(bar.open),
                    "high": scale(bar.high),
                    "low": scale(bar.low),
                    "close": scale(bar.close),
                    "volume": int((bar.volume * volume_mult).to_integral_value(rounding=ROUND_HALF_EVEN)),
                    "adjustment_factor": bar.adjustment_factor * price_mult,
                }
            )
        )
    return out
