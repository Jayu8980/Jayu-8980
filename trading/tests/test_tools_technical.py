import itertools

import numpy as np
import pytest

from aitrader.tools.technical import (
    anchored_vwap,
    atr,
    find_pivots,
    gaps,
    market_structure,
    relative_strength,
    support_resistance,
    timeframe_alignment,
    volatility_state,
    volume_profile,
)


def zigzag(points: list[float], steps: int = 5) -> tuple[list[float], list[float], list[float]]:
    close: list[float] = []
    for a, b in itertools.pairwise(points):
        close.extend(np.linspace(a, b, steps, endpoint=False).tolist())
    close.append(points[-1])
    high = [c + 0.5 for c in close]
    low = [c - 0.5 for c in close]
    return high, low, close


def test_atr_on_constant_ranges() -> None:
    close = [100.0] * 30
    res = atr(high=[101.0] * 30, low=[99.0] * 30, close=close, period=14)
    assert res.last == pytest.approx(2.0) and res.first_index == 13


def test_pivots_are_confirmed_only_after_strength_bars() -> None:
    high, low, _ = zigzag([100, 110, 104, 116, 108, 122], steps=6)
    pivots = find_pivots(np.array(high), np.array(low), strength=3)
    assert pivots, "zigzag must produce pivots"
    assert all(p.confirmed_at == p.index + 3 for p in pivots)
    assert all(p.index <= len(high) - 1 - 3 for p in pivots)


def test_structure_uptrend_downtrend_and_breakout() -> None:
    up = zigzag([100, 110, 104, 116, 108, 122, 115, 118])
    assert market_structure(high=up[0], low=up[1], close=up[2], strength=2).state == "uptrend"
    down = zigzag([122, 110, 116, 104, 110, 98, 104, 101])
    assert market_structure(high=down[0], low=down[1], close=down[2], strength=2).state == "downtrend"
    brk = zigzag([100, 110, 104, 116, 108, 122, 115, 130])
    assert market_structure(high=brk[0], low=brk[1], close=brk[2], strength=2).state == "breakout"


def test_failed_breakout() -> None:
    h, lo, c = zigzag([100, 110, 104, 116, 108, 122, 115, 128, 117])
    res = market_structure(high=h, low=lo, close=c, strength=2, failure_lookback=6)
    assert res.state == "failed_breakout"


def test_support_resistance_clusters_touches() -> None:
    h, lo, c = zigzag([100, 120, 101, 119.8, 100.4, 120.2, 110])
    levels = support_resistance(high=h, low=lo, close=c, strength=2, tolerance_atr=2.0).levels
    kinds = {lv.kind: lv for lv in levels}
    assert kinds["resistance"].touches >= 2 and kinds["support"].touches >= 2
    assert kinds["resistance"].price == pytest.approx(120.5, abs=1.0)


def test_anchored_vwap_and_volume_profile() -> None:
    res = anchored_vwap(
        high=[11, 12, 13], low=[9, 10, 11], close=[10, 11, 12], volume=[100, 100, 200], anchor_index=1
    )
    assert res.values == pytest.approx((11.0, (11 * 100 + 12 * 200) / 300))
    vp = volume_profile(close=[100, 101, 102, 101, 101], volume=[10, 500, 10, 500, 400], bins=6)
    assert vp.value_area_low <= vp.point_of_control <= vp.value_area_high
    assert abs(vp.point_of_control - 101) < 0.5


def test_relative_strength_and_volatility_state() -> None:
    asset = np.linspace(100, 200, 60).tolist()
    bench = [100.0] * 60
    rs = relative_strength(asset_close=asset, benchmark_close=bench, lookbacks=(20, 50, 200))
    assert set(rs.excess_returns) == {20, 50} and rs.rs_line_slope_20 and rs.rs_line_slope_20 > 0
    rng = np.random.default_rng(5)
    quiet_then_loud = np.concatenate([100 + rng.normal(0, 0.1, 80), 100 + rng.normal(0, 3, 25)]).tolist()
    assert volatility_state(close=quiet_then_loud).regime in ("expanded", "extreme")


def test_unfilled_gaps() -> None:
    o = [100, 106, 107, 108]
    h = [101, 107, 108, 109]
    lo = [99, 105, 106, 107]
    c = [100, 106, 107, 108]
    found = gaps(open=o, high=h, low=lo, close=c, min_gap_fraction=0.01).unfilled
    assert len(found) == 1 and found[0].direction == "up" and found[0].gap_low == 101


def test_timeframe_alignment_flags_conflicts_never_averages_them() -> None:
    aligned = timeframe_alignment(scores={"1mo": 0.5, "1w": 0.7, "1d": 0.3, "15m": -0.9})
    assert aligned.alignment_score == pytest.approx(1.0) and aligned.direction == "up"
    assert aligned.timing_score == pytest.approx(-0.9)  # intraday never sets direction
    conflict = timeframe_alignment(scores={"1w": 0.8, "1d": -0.6})
    assert conflict.direction == "mixed" and conflict.conflicts
    with pytest.raises(ValueError, match="directional"):
        timeframe_alignment(scores={"15m": 0.5})
