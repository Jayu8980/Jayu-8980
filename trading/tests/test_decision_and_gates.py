from uuid import uuid4

import pytest
from pydantic import ValidationError

from aitrader.schemas.debate import DebateOutput, DebateSide
from aitrader.schemas.decision import Action
from aitrader.schemas.envelope import Topic
from aitrader.schemas.gates import RRRejection, RRVerdict
from aitrader.schemas.regime import RegimeOutlook, Scenario
from aitrader.schemas.risk import RiskApproval, RiskRule, RuleResult
from tests.factories import DECISION_ID, EQUITY, boss_decision, debate_case, evidence, sample_payloads


def test_trading_decision_needs_invalidation() -> None:
    with pytest.raises(ValidationError, match="invalidation"):
        boss_decision(invalidation=None)


@pytest.mark.parametrize("action", [Action.WAIT, Action.HOLD])
def test_non_trading_decisions_request_no_risk(action: Action) -> None:
    with pytest.raises(ValidationError, match="must not request risk budget"):
        boss_decision(action=action)
    assert boss_decision(action=action, risk_budget_fraction=0.0, invalidation=None).action is action


def test_open_short_only_with_sell() -> None:
    with pytest.raises(ValidationError, match="open_short"):
        boss_decision(open_short=True)
    assert boss_decision(action=Action.SELL, open_short=True).open_short


def test_revision_fields_go_together() -> None:
    with pytest.raises(ValidationError, match="revision"):
        boss_decision(revision_of=uuid4())
    assert boss_decision(revision_of=uuid4(), revision_number=1).revision_number == 1


def _rr(**overrides: object) -> RRVerdict:
    base = sample_payloads()[Topic.RR_VERDICT].model_dump()
    return RRVerdict.model_validate({**base, **overrides})


def test_negative_ev_never_passes_whatever_the_ratio() -> None:
    with pytest.raises(ValidationError, match="negative-EV"):
        _rr(expected_value_per_unit=-0.1, rr_after_costs=5.0)


def test_failed_verdict_needs_reasons_and_passed_has_none() -> None:
    with pytest.raises(ValidationError, match="rejection reason"):
        _rr(passed=False)
    with pytest.raises(ValidationError, match="cannot carry"):
        _rr(rejections=(RRRejection.BELOW_MIN_RR,))
    assert not _rr(passed=False, rejections=(RRRejection.NEGATIVE_EV,), expected_value_per_unit=-1).passed


def test_risk_approval_token_iff_approved() -> None:
    approved = sample_payloads()[Topic.RISK_APPROVAL]
    assert isinstance(approved, RiskApproval)
    data = approved.model_dump()
    with pytest.raises(ValidationError, match="token must be present"):
        RiskApproval.model_validate({**data, "token": None})
    with pytest.raises(ValidationError, match="token must be present"):
        RiskApproval.model_validate({**data, "approved": False, "approved_quantity": 0})


def test_cannot_approve_with_a_failed_rule() -> None:
    data = sample_payloads()[Topic.RISK_APPROVAL].model_dump()
    failed = RuleResult(rule=RiskRule.DAILY_DRAWDOWN, passed=False, observed=0.031, limit=0.03)
    with pytest.raises(ValidationError, match="failed rule"):
        RiskApproval.model_validate({**data, "results": (failed,)})


def test_regime_probabilities_must_sum_to_one() -> None:
    with pytest.raises(ValidationError, match="sum to 1"):
        RegimeOutlook.model_validate(
            {
                **sample_payloads()[Topic.REGIME_OUTLOOK].model_dump(),
                "scenarios": (
                    Scenario(name="up", probability=0.7, evidence=(evidence(),)),
                    Scenario(name="down", probability=0.7, evidence=(evidence(),)),
                ),
            }
        )


def test_debate_round_cap_and_sides() -> None:
    with pytest.raises(ValidationError):
        DebateOutput(
            instrument=EQUITY,
            rounds_used=5,
            bull=debate_case(DebateSide.BULL),
            bear=debate_case(DebateSide.BEAR),
        )
    with pytest.raises(ValidationError, match="matching sides"):
        DebateOutput(
            instrument=EQUITY,
            rounds_used=1,
            bull=debate_case(DebateSide.BEAR),
            bear=debate_case(DebateSide.BEAR),
        )


def test_decision_id_constant_is_used() -> None:
    assert boss_decision().decision_id == DECISION_ID
