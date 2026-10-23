"""Anthropic Messages API adapter.

Uses structured outputs (``output_config.format``) so the response is a single JSON object
matching the request schema; the caller still validates it with Pydantic. Sampling
parameters are not sent (current models reject them); ``effort`` controls depth and spend.
"""

from __future__ import annotations

import anthropic

from aitrader.config.settings import secret
from aitrader.llm.client import LlmError, LlmRequest, LlmResponse

API_KEY_ENV = "AITRADER_ANTHROPIC_API_KEY"


class AnthropicLlmClient:
    def __init__(
        self, timeout_seconds: float = 60.0, max_retries: int = 2, api_key: str | None = None
    ) -> None:
        self._client = anthropic.Anthropic(
            api_key=api_key or secret(API_KEY_ENV), timeout=timeout_seconds, max_retries=max_retries
        )

    def complete(self, request: LlmRequest) -> LlmResponse:
        try:
            response = self._client.messages.create(
                model=request.model,
                max_tokens=request.max_tokens,
                system=request.system,
                messages=[{"role": "user", "content": request.user}],
                output_config={
                    "effort": request.effort,
                    "format": {"type": "json_schema", "schema": request.json_schema},
                },
            )
        except anthropic.BadRequestError as exc:
            raise LlmError(f"bad request: {exc.message}") from exc
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError) as exc:
            raise LlmError(f"credentials rejected: {exc.message}") from exc
        except anthropic.NotFoundError as exc:
            raise LlmError(f"unknown model or endpoint: {exc.message}") from exc
        except anthropic.RateLimitError as exc:
            raise LlmError("rate limited after retries") from exc
        except anthropic.APIStatusError as exc:
            raise LlmError(f"API error {exc.status_code}: {exc.message}") from exc
        except anthropic.APIConnectionError as exc:  # includes timeouts
            raise LlmError(f"connection failed: {exc}") from exc
        text = "".join(block.text for block in response.content if block.type == "text")
        return LlmResponse(
            text=text,
            model=response.model,
            stop_reason=response.stop_reason,
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            request_id=getattr(response, "_request_id", None),
        )
