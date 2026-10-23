import json
import math
from uuid import uuid4

import pytest
from pydantic import ValidationError

from aitrader.audit import InMemoryAuditLog
from aitrader.schemas.audit import ToolCallRecord
from aitrader.schemas.base import AgentId
from aitrader.tools import ToolError, ToolRunner, registry
from tests.test_audit import Clock

TOOLS = registry()


def test_catalogue_is_populated() -> None:
    domains = {name.split(".")[0] for name in TOOLS}
    assert {
        "stats",
        "risk",
        "derivs",
        "micro",
        "costs",
        "ta",
        "sizing",
        "portfolio",
        "fundamentals",
    } <= domains
    assert len(TOOLS) >= 50


@pytest.mark.parametrize("name", sorted(TOOLS))
def test_every_tool_runs_its_example_deterministically(name: str) -> None:
    spec = TOOLS[name]
    first, second = spec.run(spec.example), spec.run(spec.example)
    assert first == second
    json.dumps(first.model_dump(mode="json"), allow_nan=False)


@pytest.mark.parametrize("name", sorted(TOOLS))
def test_every_tool_rejects_unknown_inputs(name: str) -> None:
    spec = TOOLS[name]
    with pytest.raises(ValidationError):
        spec.run({**spec.example, "ignore_previous_instructions": True})


def test_non_finite_inputs_are_rejected() -> None:
    spec = TOOLS["risk.var_historical"]
    bad = list(spec.example["returns"])
    bad[3] = math.nan
    with pytest.raises(ValidationError):
        spec.run({**spec.example, "returns": bad})


def test_runner_audits_success_and_failure() -> None:
    audit = InMemoryAuditLog(Clock())
    runner = ToolRunner(audit, Clock())
    cycle = uuid4()
    out = runner.call(
        "sizing.kelly", {"p_win": 0.6, "win_loss_ratio": 2.0}, agent_id=AgentId.RISK, cycle_id=cycle
    )
    assert out.model_dump()["full_kelly"] == pytest.approx(0.4)
    with pytest.raises(ToolError, match="failed"):
        runner.call(
            "sizing.kelly", {"p_win": 2.0, "win_loss_ratio": 2.0}, agent_id=AgentId.RISK, cycle_id=cycle
        )
    with pytest.raises(ToolError, match="unknown tool"):
        runner.call("nope.tool", {}, agent_id=AgentId.RISK, cycle_id=cycle)
    records = [ToolCallRecord.model_validate_json(r.body_json) for r in audit.records()]
    assert [r.error is None for r in records] == [True, False]
    assert records[0].tool_version == "1.0.0" and records[0].cycle_id == cycle
    assert audit.verify_chain()
