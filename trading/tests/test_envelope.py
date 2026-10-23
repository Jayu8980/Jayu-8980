from datetime import timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from aitrader.schemas.base import AgentId, Priority
from aitrader.schemas.envelope import TOPIC_PAYLOAD, TOPIC_PUBLISHERS, Envelope, Topic
from tests.factories import DEFAULT_PUBLISHER, T0, envelope, sample_payloads


def test_every_topic_has_payload_publishers_and_a_sample() -> None:
    assert set(TOPIC_PAYLOAD) == set(Topic)
    assert set(TOPIC_PUBLISHERS) == set(Topic)
    assert set(sample_payloads()) == set(Topic)


@pytest.mark.parametrize("topic", list(Topic))
def test_json_round_trip_for_every_topic(topic: Topic) -> None:
    original = envelope(topic)
    restored = Envelope.model_validate_json(original.model_dump_json())
    assert restored == original
    assert type(restored.payload) is TOPIC_PAYLOAD[topic]


def test_payload_must_match_topic() -> None:
    with pytest.raises(ValidationError, match="carries ChartSignal"):
        envelope(Topic.SIGNALS_CHART, payload=sample_payloads()[Topic.SIGNALS_SMARTMONEY])


@pytest.mark.parametrize(
    ("topic", "intruder"),
    [
        (Topic.ORDERS_INSTRUCTIONS, AgentId.BOSS),
        (Topic.RISK_APPROVAL, AgentId.BOSS),
        (Topic.RISK_APPROVAL, AgentId.DEBATE),
        (Topic.BOSS_DECISION, AgentId.CHART),
        (Topic.ORDERS_FILLS, AgentId.RISK),
        (Topic.LIQUIDITY_PLAN, AgentId.BOSS),
    ],
)
def test_unauthorised_publishers_are_rejected(topic: Topic, intruder: AgentId) -> None:
    with pytest.raises(ValidationError, match="may not publish"):
        envelope(topic, agent_id=intruder)


def test_lookahead_timestamps_are_rejected() -> None:
    # Knowing a fact before it happened.
    with pytest.raises(ValidationError, match="event_time <= available_at"):
        envelope(Topic.DATA_BARS, available_at=T0 - timedelta(seconds=1))
    # Producing a message from data that was not yet available.
    with pytest.raises(ValidationError, match="event_time <= available_at"):
        envelope(Topic.DATA_BARS, produced_at=T0 - timedelta(seconds=1))


def test_alerts_must_be_interrupts() -> None:
    with pytest.raises(ValidationError, match="priority interrupts"):
        envelope(Topic.RISK_ALERTS, priority=Priority.NORMAL)


def test_incompatible_schema_major_version_is_rejected() -> None:
    with pytest.raises(ValidationError, match="schema major version"):
        envelope(Topic.DATA_BARS, schema_version="2.0.0")


def test_message_cannot_cause_itself() -> None:
    message_id = uuid4()
    with pytest.raises(ValidationError, match="cannot cause itself"):
        envelope(Topic.DATA_BARS, message_id=message_id, causation_ids=(message_id,))


def test_free_text_payload_is_rejected() -> None:
    raw = envelope(Topic.SIGNALS_CHART).model_dump(mode="json")
    raw["payload"] = {"kind": "chart_signal", "text": "BUY RELIANCE NOW"}
    with pytest.raises(ValidationError):
        Envelope.model_validate(raw)


def test_default_publishers_are_authorised() -> None:
    for topic, agent in DEFAULT_PUBLISHER.items():
        assert agent in TOPIC_PUBLISHERS[topic]
