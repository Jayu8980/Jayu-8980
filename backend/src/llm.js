import Anthropic from "@anthropic-ai/sdk";

// Models that accept the server-side refusal fallback ("default" routing).
const FALLBACK_MODELS = new Set(["claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5"]);

export class LlmError extends Error {}

/** Pull the first JSON object out of a model reply (tolerates stray fences or prose). */
export function parseJsonLoose(text) {
  const trimmed = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, "");
  try {
    return JSON.parse(trimmed);
  } catch {
    const start = trimmed.indexOf("{");
    const end = trimmed.lastIndexOf("}");
    if (start >= 0 && end > start) return JSON.parse(trimmed.slice(start, end + 1));
    throw new LlmError("reply was not JSON");
  }
}

export function createAnthropicLlm() {
  const client = new Anthropic(); // reads ANTHROPIC_API_KEY from the server environment only

  async function completeJson({ model, system, prompt, effort = "high", maxTokens = 64000 }) {
    let lastErr;
    for (let attempt = 0; attempt < 2; attempt++) {
      const content =
        attempt === 0 ? prompt : `${prompt}\n\nYour previous reply could not be parsed as JSON (${lastErr?.message}). Reply with the JSON object only.`;
      const params = {
        model,
        max_tokens: maxTokens,
        system,
        thinking: { type: "adaptive" },
        output_config: { effort },
        messages: [{ role: "user", content }],
      };
      if (FALLBACK_MODELS.has(model)) {
        params.betas = ["server-side-fallback-2026-07-01"];
        params.fallbacks = "default";
      }
      const msg = await client.beta.messages.stream(params).finalMessage();
      if (msg.stop_reason === "refusal") {
        throw new LlmError(`model declined the request (${msg.stop_details?.category ?? "unknown"})`);
      }
      if (msg.stop_reason === "max_tokens") {
        lastErr = new LlmError("reply hit max_tokens");
        continue;
      }
      const text = msg.content
        .filter((b) => b.type === "text")
        .map((b) => b.text)
        .join("");
      try {
        return { json: parseJsonLoose(text), model: msg.model, usage: msg.usage };
      } catch (e) {
        lastErr = e;
      }
    }
    throw lastErr;
  }

  return { name: "anthropic", completeJson };
}

/**
 * Wrap any provider so failures surface with the agent name and transient API errors are retried.
 * The SDK already retries 429/5xx twice; this adds one more attempt after a short pause.
 */
export function withAgentErrors(llm) {
  return {
    name: llm.name,
    async completeJson(args) {
      try {
        return await llm.completeJson(args);
      } catch (e) {
        if (e instanceof Anthropic.RateLimitError || e instanceof Anthropic.InternalServerError || e instanceof Anthropic.APIConnectionError) {
          await new Promise((r) => setTimeout(r, 5000));
          return llm.completeJson(args);
        }
        throw new LlmError(`${args.agent ?? "agent"} failed: ${e.message}`);
      }
    },
  };
}
