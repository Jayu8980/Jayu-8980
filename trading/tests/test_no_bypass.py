"""Static guarantee: only the execution package talks to a broker.

Outside ``aitrader/execution/`` no module may import the live broker adapter or the order
request types, or call a broker's order-placing methods. The paper runner may drive the
simulated exchange (open and run sessions, read positions) but never places orders itself.
"""

from __future__ import annotations

import ast
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "src" / "aitrader"
EXECUTION = SRC / "execution"
FORBIDDEN_MODULES = {"aitrader.execution.kite"}
FORBIDDEN_NAMES = {"OrderRequest", "ProtectiveRequest", "KiteBroker", "AuditedBroker"}
FORBIDDEN_CALLS = {"place_order", "place_protective", "cancel_order", "cancel_protective"}


def violations(source: str, name: str) -> list[str]:
    found = []
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.ImportFrom) and node.module:
            if node.module in FORBIDDEN_MODULES:
                found.append(f"{name}: imports {node.module}")
            if node.module.startswith("aitrader.execution"):
                found += [f"{name}: imports {a.name}" for a in node.names if a.name in FORBIDDEN_NAMES]
        elif isinstance(node, ast.Import):
            found += [f"{name}: imports {a.name}" for a in node.names if a.name in FORBIDDEN_MODULES]
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if node.func.attr in FORBIDDEN_CALLS:
                found.append(f"{name}:{node.lineno}: calls {node.func.attr}")
    return found


def test_only_execution_places_or_cancels_orders() -> None:
    offenders = [
        v
        for path in SRC.rglob("*.py")
        if EXECUTION not in path.parents
        for v in violations(path.read_text(encoding="utf-8"), str(path.relative_to(SRC)))
    ]
    assert offenders == []


def test_the_check_detects_a_violation() -> None:
    rogue = "from aitrader.execution.kite import KiteBroker\nbroker.place_order(request)\n"
    assert len(violations(rogue, "rogue.py")) == 3
