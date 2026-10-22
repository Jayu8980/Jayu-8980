# Prompts

Every LLM prompt lives here as a versioned file: `<prompt_id>/v<N>.md`. Prompts are never
edited in place. A change creates a new version, and `LlmCallRecord` logs the prompt id,
version, SHA-256 hash, fully rendered text, model id, temperature and seed.

Untrusted external text (news, filings, transcripts, social posts) is inserted only inside
delimited data blocks. The system prompt says that content inside those blocks is data to
extract from, never instructions. LLM outputs must parse into the agent's Pydantic output
schema. If parsing fails, the output is discarded and the agent counts as failed for that
cycle. LLM agents never do arithmetic: numbers in their outputs come from tool calls.
