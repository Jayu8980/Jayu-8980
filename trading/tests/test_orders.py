from decimal import Decimal

import pytest
from pydantic import ValidationError

from aitrader.schemas.decision import Action
from aitrader.schemas.orders import (
    ALLOWED_TRANSITIONS,
    TERMINAL_STATES,
    InstructionIntent,
    OrderInstruction,
    OrderState,
    OrderUpdate,
    Product,
    Side,
    can_transition,
)
from tests.factories import OPTION, order_instruction, sign_token, unsigned_instruction


def test_token_covers_the_exact_instruction() -> None:
    instruction = order_instruction()
    assert instruction.approval.instruction_digest == instruction.digest()


@pytest.mark.parametrize(
    "tamper",
    [
        {"quantity": 1000},
        {"limit_price": Decimal("110")},
        {"side": Side.SELL, "action": Action.SELL, "intent": InstructionIntent.CLOSE},
        {"protective_stop": Decimal("50")},
        {"idempotency_key": "cycle-1:decision-1:leg-9"},
    ],
)
def test_tampering_after_approval_invalidates_the_instruction(tamper: dict[str, object]) -> None:
    signed = order_instruction()
    with pytest.raises(ValidationError, match="does not cover"):
        OrderInstruction.model_validate({**signed.model_dump(), **tamper})


def test_token_from_another_decision_is_rejected() -> None:
    other = unsigned_instruction(decision_id="00000000-0000-4000-8000-0000000000ff")
    with pytest.raises(ValidationError, match="different decision"):
        OrderInstruction(**unsigned_instruction().model_dump(), approval=sign_token(other))


def test_digest_is_stable_and_ignores_the_token() -> None:
    a, b = order_instruction(), order_instruction()
    assert a.digest() == b.digest() == unsigned_instruction().digest()
    assert a.approval.token_id != b.approval.token_id


@pytest.mark.parametrize("action", [Action.HOLD, Action.WAIT])
def test_hold_and_wait_never_become_orders(action: Action) -> None:
    with pytest.raises(ValidationError, match="never produce"):
        unsigned_instruction(action=action)


def test_buy_maps_to_opening_a_long() -> None:
    with pytest.raises(ValidationError, match="opening or adding a long"):
        unsigned_instruction(side=Side.SELL)


def test_sell_opens_a_short_only_when_explicit() -> None:
    with pytest.raises(ValidationError, match="open_short explicitly"):
        unsigned_instruction(action=Action.SELL, side=Side.SELL)
    short = unsigned_instruction(action=Action.SELL, side=Side.SELL, open_short=True)
    assert short.open_short
    exit_ = unsigned_instruction(action=Action.SELL, side=Side.SELL, intent=InstructionIntent.CLOSE)
    assert not exit_.open_short


def test_reduce_only_closes() -> None:
    with pytest.raises(ValidationError, match="REDUCE"):
        unsigned_instruction(action=Action.REDUCE, side=Side.SELL)


def test_opening_needs_a_protective_stop() -> None:
    with pytest.raises(ValidationError, match="protective stop"):
        unsigned_instruction(protective_stop=None)


def test_derivative_lots_and_product() -> None:
    with pytest.raises(ValidationError, match="lot size"):
        unsigned_instruction(instrument=OPTION, quantity=100, product=Product.NRML)
    with pytest.raises(ValidationError, match="CNC"):
        unsigned_instruction(instrument=OPTION, quantity=150, product=Product.CNC)
    assert unsigned_instruction(instrument=OPTION, quantity=150, product=Product.NRML).quantity == 150


def test_state_machine_terminal_states_are_final() -> None:
    for state in TERMINAL_STATES:
        assert ALLOWED_TRANSITIONS[state] == frozenset()
    assert set(ALLOWED_TRANSITIONS) == set(OrderState)


@pytest.mark.parametrize(
    ("current", "new", "legal"),
    [
        (OrderState.PENDING, OrderState.PLACED, True),
        (OrderState.PLACED, OrderState.PARTIALLY_FILLED, True),
        (OrderState.PARTIALLY_FILLED, OrderState.FILLED, True),
        (OrderState.FILLED, OrderState.PLACED, False),
        (OrderState.CANCELLED, OrderState.PLACED, False),
        (OrderState.PENDING, OrderState.FILLED, False),
        (OrderState.PARTIALLY_FILLED, OrderState.REJECTED, False),
    ],
)
def test_transitions(current: OrderState, new: OrderState, legal: bool) -> None:
    assert can_transition(current, new) is legal


def test_order_update_rejects_illegal_transition() -> None:
    with pytest.raises(ValidationError, match="illegal transition"):
        OrderUpdate(
            instruction_id="00000000-0000-4000-8000-000000000002",
            child_order_id="c1",
            state=OrderState.PLACED,
            previous_state=OrderState.FILLED,
            filled_quantity=0,
        )
