"""Synthetic end-of-day files in NSE's published formats (index closes, UDiFF cash
bhavcopy), so the paper service is exercised through the real parsers and file names."""

from __future__ import annotations

from collections.abc import Mapping
from datetime import date
from pathlib import Path

import numpy as np

from aitrader.agents.data.sources.nse import archive_filename
from tests.data_helpers import weekdays

Ohlcv = tuple[float, float, float, float, int]

INDEX_HEADER = (
    "Index Name,Index Date,Open Index Value,High Index Value,Low Index Value,Closing Index Value,"
    "Points Change,Change(%),Volume,Turnover (Rs. Cr.),P/E,P/B,Div Yield"
)
CM_HEADER = (
    "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,"
    "StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,UndrlygPric,"
    "SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,Rmks,"
    "Rsvd1,Rsvd2,Rsvd3,Rsvd4"
)


def index_close_text(day: date, nifty: Ohlcv) -> str:
    o, h, lo, c, v = nifty
    row = f"Nifty 50,{day:%d-%m-%Y},{o:.2f},{h:.2f},{lo:.2f},{c:.2f},0,0,{v},1000.00,22.0,3.5,1.2"
    return f"{INDEX_HEADER}\n{row}\n"


def cm_bhavcopy_text(day: date, stocks: Mapping[str, Ohlcv]) -> str:
    rows = [CM_HEADER]
    for i, (symbol, (o, h, lo, c, v)) in enumerate(sorted(stocks.items())):
        isin = f"INE{i:06d}01{i % 10}"
        rows.append(
            f"{day},{day},CM,NSE,STK,{1000 + i},{isin},{symbol},EQ,,,,,{symbol} LTD,{o:.2f},{h:.2f},{lo:.2f},"
            f"{c:.2f},{c:.2f},{o:.2f},,{c:.2f},,,{v},{v * c:.2f},1000,F1,1,,,,,"
        )
    return "\n".join(rows) + "\n"


def write_day(directory: Path, day: date, nifty: Ohlcv, stocks: Mapping[str, Ohlcv]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    index_name = archive_filename("nse_index_close", day)
    cm_name = archive_filename("nse_cm_bhavcopy", day).removesuffix(".zip")
    (directory / index_name).write_text(index_close_text(day, nifty), encoding="utf-8")
    (directory / cm_name).write_text(cm_bhavcopy_text(day, stocks), encoding="utf-8")


def synthetic_market(
    n: int, start: date = date(2024, 1, 1), seed: int = 7
) -> tuple[list[date], list[Ohlcv], dict[str, list[Ohlcv]]]:
    """NIFTY plus two trending stocks and one falling stock, as daily OHLCV."""
    days = weekdays(start, n)
    rng = np.random.default_rng(seed)

    def path(start_price: float, drift: float, vol: float) -> list[Ohlcv]:
        closes = start_price * np.exp(np.cumsum(rng.normal(drift, vol, n)))
        out, prev = [], float(closes[0])
        for c in closes:
            c = float(c)
            o = prev
            out.append((o, max(o, c) * 1.005, min(o, c) * 0.995, c, 2_000_000))
            prev = c
        return out

    nifty = path(20000, 0.0012, 0.008)
    stocks = {
        "UPA": path(100, 0.0025, 0.012),
        "UPB": path(100, 0.002, 0.012),
        "DNC": path(100, -0.002, 0.012),
    }
    return days, nifty, stocks
