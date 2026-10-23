"""Approval tokens: HMAC-SHA256 over the canonical token body, bound to the instruction
digest, expiring and single-use.

The risk agent signs; execution verifies with ``verify`` before any broker call. A token
fails verification if its signature, key, instruction digest or decision differ, if it has
expired, or if it was already consumed. Every failure raises; nothing is retried.
"""

from __future__ import annotations

import hashlib
import hmac
from datetime import datetime, timedelta
from uuid import UUID, uuid5

from aitrader.config.settings import secret
from aitrader.schemas.canonical import canonical_json
from aitrader.schemas.orders import OrderInstruction, UnsignedInstruction
from aitrader.schemas.risk import ApprovalToken
from aitrader.state.interface import StateStore

SIGNING_KEY_ENV = "AITRADER_RISK_SIGNING_KEY"
_TOKEN_NAMESPACE = UUID("5c4a3d0e-8f3b-4a57-9f53-7b0a8b2a7e11")


class TokenError(PermissionError):
    """The instruction may not be executed."""


class TokenSigner:
    def __init__(self, key: bytes, key_id: str = "k1", ttl: timedelta = timedelta(minutes=5)) -> None:
        if len(key) < 32:
            raise ValueError("signing key must be at least 32 bytes")
        self._key = key
        self.key_id = key_id
        self._ttl = ttl

    @classmethod
    def from_env(cls, ttl: timedelta = timedelta(minutes=5)) -> TokenSigner:
        return cls(bytes.fromhex(secret(SIGNING_KEY_ENV)), ttl=ttl)

    def _signature(self, body: dict[str, object]) -> str:
        return hmac.new(self._key, canonical_json(body).encode(), hashlib.sha256).hexdigest()

    def sign(self, instruction: UnsignedInstruction, issued_at: datetime) -> ApprovalToken:
        unsigned = ApprovalToken(
            # Deterministic id per instruction, so a replayed backtest issues identical tokens.
            token_id=uuid5(_TOKEN_NAMESPACE, f"{instruction.idempotency_key}|{instruction.digest()}"),
            decision_id=instruction.decision_id,
            instruction_digest=instruction.digest(),
            issued_at=issued_at,
            expires_at=issued_at + self._ttl,
            key_id=self.key_id,
            signature="0" * 64,
        )
        # Sign the token's own canonical serialisation, exactly what verify() recomputes.
        body = unsigned.model_dump(mode="json", exclude={"signature"})
        return unsigned.model_copy(update={"signature": self._signature(body)})

    def verify(self, instruction: OrderInstruction, now: datetime, state: StateStore) -> None:
        """Check everything, then atomically consume the token and the idempotency key."""
        token = instruction.approval
        if token.key_id != self.key_id:
            raise TokenError(f"unknown signing key {token.key_id!r}")
        body = token.model_dump(mode="json", exclude={"signature"})
        if not hmac.compare_digest(self._signature(body), token.signature):
            raise TokenError("approval signature is invalid")
        if token.instruction_digest != instruction.digest() or token.decision_id != instruction.decision_id:
            raise TokenError("approval does not cover this instruction")
        if not token.issued_at <= now < token.expires_at:
            raise TokenError("approval token expired or not yet valid")
        if not state.consume_token(token.token_id):
            raise TokenError("approval token already used")
        if not state.claim_idempotency_key(instruction.idempotency_key):
            raise TokenError(f"instruction {instruction.idempotency_key} already executed")
