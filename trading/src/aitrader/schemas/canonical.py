"""Canonical serialisation and hashing.

Used for instruction digests (bound into risk approval tokens) and for the hash-chained
audit log. Canonical JSON: sorted keys, no insignificant whitespace, UTF-8.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

from pydantic import BaseModel


def canonical_json(value: BaseModel | dict[str, Any] | list[Any]) -> str:
    data = value.model_dump(mode="json") if isinstance(value, BaseModel) else value
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def digest(value: BaseModel | dict[str, Any] | list[Any]) -> str:
    return sha256_hex(canonical_json(value))
