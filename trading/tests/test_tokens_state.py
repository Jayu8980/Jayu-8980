from datetime import timedelta
from uuid import uuid4

import pytest

from aitrader.agents.risk.tokens import TokenError, TokenSigner
from aitrader.schemas.orders import OrderInstruction
from aitrader.state import FreezeClearRefusedError, InMemoryStateStore
from tests.factories import T0, unsigned_instruction

KEY = b"k" * 32


def signed(signer: TokenSigner, **overrides: object) -> OrderInstruction:
    u = unsigned_instruction(**overrides)
    return OrderInstruction(**u.model_dump(), approval=signer.sign(u, T0))


def test_valid_token_verifies_once() -> None:
    signer, state = TokenSigner(KEY), InMemoryStateStore()
    instruction = signed(signer)
    signer.verify(instruction, T0 + timedelta(seconds=1), state)
    with pytest.raises(TokenError, match="already used"):
        signer.verify(instruction, T0 + timedelta(seconds=2), state)


def test_tokens_are_deterministic_per_instruction() -> None:
    signer = TokenSigner(KEY)
    assert signed(signer).approval == signed(signer).approval


def test_forged_or_foreign_tokens_are_rejected() -> None:
    state = InMemoryStateStore()
    forged = signed(TokenSigner(b"x" * 32))
    with pytest.raises(TokenError, match="signature"):
        TokenSigner(KEY).verify(forged, T0, state)
    other_key_id = signed(TokenSigner(KEY, key_id="k2"))
    with pytest.raises(TokenError, match="unknown signing key"):
        TokenSigner(KEY).verify(other_key_id, T0, state)
    good = signed(TokenSigner(KEY))
    tampered = good.approval.model_copy(update={"expires_at": good.approval.expires_at + timedelta(days=1)})
    with pytest.raises(TokenError, match="signature"):
        TokenSigner(KEY).verify(
            OrderInstruction.model_construct(**{**good.__dict__, "approval": tampered}), T0, state
        )


def test_expired_tokens_are_rejected() -> None:
    signer = TokenSigner(KEY, ttl=timedelta(minutes=5))
    with pytest.raises(TokenError, match="expired"):
        signer.verify(signed(signer), T0 + timedelta(minutes=6), InMemoryStateStore())


def test_a_new_token_cannot_re_execute_the_same_instruction() -> None:
    signer, state = TokenSigner(KEY), InMemoryStateStore()
    first = signed(signer)
    signer.verify(first, T0, state)
    later = signer.sign(
        unsigned_instruction(), T0 + timedelta(seconds=30)
    )  # re-approval, same idempotency key
    state2_token = OrderInstruction(**unsigned_instruction().model_dump(), approval=later)
    with pytest.raises(TokenError):
        signer.verify(state2_token, T0 + timedelta(seconds=31), state)


def test_short_keys_are_refused() -> None:
    with pytest.raises(ValueError, match="32 bytes"):
        TokenSigner(b"short")


def test_freeze_needs_a_human_to_clear() -> None:
    state = InMemoryStateStore()
    alert = uuid4()
    state.set_frozen(alert, T0)
    assert state.freeze_status().frozen
    with pytest.raises(FreezeClearRefusedError):
        state.clear_freeze(alert, cleared_by_human=False)
    state.clear_freeze(alert, cleared_by_human=True)
    assert not state.freeze_status().frozen
    with pytest.raises(ValueError):
        state.set_limit_multiplier(0)
