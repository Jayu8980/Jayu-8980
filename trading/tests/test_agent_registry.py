import re

import pytest

from aitrader.agents.registry import ALL_SPECS
from aitrader.agents.spec import AgentKind, FailureMode
from aitrader.config import load_system_config
from aitrader.schemas.base import AgentId
from aitrader.schemas.envelope import TOPIC_PUBLISHERS, Topic

# Roles that publish through another component's spec or are external.
NO_OWN_SPEC = {AgentId.BULL, AgentId.BEAR, AgentId.BOSS_FALLBACK, AgentId.HUMAN}
GATE_AND_ORDER_TOPICS = {
    Topic.LIQUIDITY_PLAN,
    Topic.RR_VERDICT,
    Topic.RISK_APPROVAL,
    Topic.ORDERS_INSTRUCTIONS,
    Topic.ORDERS_UPDATES,
    Topic.ORDERS_FILLS,
}


def test_every_component_has_a_spec() -> None:
    assert set(ALL_SPECS) == set(AgentId) - NO_OWN_SPEC


@pytest.mark.parametrize("agent_id", list(ALL_SPECS))
def test_specs_publish_only_where_authorised(agent_id: AgentId) -> None:
    for topic in ALL_SPECS[agent_id].publishes:
        assert agent_id in TOPIC_PUBLISHERS[topic], f"{agent_id} declares {topic} without authority"


def test_every_authorised_publisher_declares_the_topic() -> None:
    for topic, publishers in TOPIC_PUBLISHERS.items():
        for agent_id in publishers - NO_OWN_SPEC:
            assert topic in ALL_SPECS[agent_id].publishes, f"{agent_id} authorised on {topic} but undeclared"


def test_llm_agents_never_touch_gates_or_orders() -> None:
    for spec in ALL_SPECS.values():
        if spec.kind is not AgentKind.DETERMINISTIC:
            assert not set(spec.publishes) & GATE_AND_ORDER_TOPICS, spec.agent_id
            assert Topic.RISK_ALERTS not in spec.publishes, spec.agent_id


@pytest.mark.parametrize(
    "agent_id",
    [AgentId.LIQUIDITY, AgentId.RISK_REWARD, AgentId.RISK, AgentId.EXECUTION, AgentId.ORCHESTRATOR],
)
def test_gates_and_execution_are_deterministic(agent_id: AgentId) -> None:
    assert ALL_SPECS[agent_id].kind is AgentKind.DETERMINISTIC


def test_safety_critical_components_freeze_on_failure() -> None:
    for agent_id in (AgentId.RISK, AgentId.EXECUTION, AgentId.DATA, AgentId.ORCHESTRATOR):
        assert ALL_SPECS[agent_id].failure_mode is FailureMode.FREEZE


def test_boss_has_a_deterministic_fallback() -> None:
    assert ALL_SPECS[AgentId.BOSS].failure_mode is FailureMode.FALLBACK
    assert AgentId.BOSS_FALLBACK in TOPIC_PUBLISHERS[Topic.BOSS_DECISION]


def test_timeout_keys_exist_in_config() -> None:
    timeouts = load_system_config().timeouts_seconds.model_dump()
    for spec in ALL_SPECS.values():
        if spec.timeout_key is not None:
            assert spec.timeout_key in timeouts, spec.agent_id


def test_tool_names_follow_registry_convention() -> None:
    pattern = re.compile(r"^[a-z_]+\.[a-z0-9_]+$")
    for spec in ALL_SPECS.values():
        for tool in spec.tools:
            assert pattern.match(tool), f"{spec.agent_id}: {tool}"


# Tools named in agent specs that later phases implement. Listing them here keeps any
# drift between specs and the registry visible: a spec cannot name a tool nobody owns.
PLANNED_TOOLS = {
    "flows.fii_dii": 4,  # needs a source (NSE publishes FII/DII cash flows via its website API)
    "flows.delivery_pct": 4,  # needs the security-wise delivery file (not in UDiFF bhavcopies)
    "micro.volume_curve": 6,  # needs intraday volume data
    "derivs.freeze_qty": 6,  # freeze quantities come from config (limits.liquidity) for now
    "broker.place": 6,
    "broker.cancel": 6,
    "broker.positions": 6,
    "broker.orders": 6,
    "micro.slicer": 6,
}


def test_spec_tools_exist_or_are_planned() -> None:
    from aitrader.tools import registry

    registered = set(registry())
    named = {tool for spec in ALL_SPECS.values() for tool in spec.tools}
    assert named - registered - set(PLANNED_TOOLS) == set()
    assert not set(PLANNED_TOOLS) & registered, "implemented tools must leave the planned list"
