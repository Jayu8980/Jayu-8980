import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const env = process.env;
const list = (v, d) => (v ? v.split(",").map((s) => s.trim()).filter(Boolean) : d);
const int = (v, d) => (v != null && v !== "" ? parseInt(v, 10) : d);

const DEFAULT_MODEL = "claude-opus-5-5";

// Each agent in the committee can run on a different model. Diversity (two setters on different
// models, solvers using different models and strategies) is what makes the cross-check meaningful.
export const config = {
  port: int(env.PORT, 8080),
  provider: env.LLM_PROVIDER ?? (env.ANTHROPIC_API_KEY ? "anthropic" : "mock"),
  clientToken: env.APP_CLIENT_TOKEN ?? "",
  dataDir: env.DATA_DIR ?? path.resolve(here, "../../data"),
  runsDir: env.RUNS_DIR ?? path.resolve(here, "../runs"),
  maxConcurrency: int(env.MAX_CONCURRENCY, 4),
  batchSize: int(env.BATCH_SIZE, 5),
  maxRounds: int(env.MAX_REGEN_ROUNDS, 2),
  quorum: int(env.CONSENSUS_QUORUM, 2),
  duplicateThreshold: Number(env.DUPLICATE_THRESHOLD ?? 0.6),
  rateLimitPerHour: int(env.RATE_LIMIT_PER_HOUR, 20),
  models: {
    blueprint: env.MODEL_BLUEPRINT ?? DEFAULT_MODEL,
    setters: list(env.MODEL_SETTERS, [DEFAULT_MODEL, "claude-sonnet-5-5"]),
    reviewer: env.MODEL_REVIEWER ?? DEFAULT_MODEL,
    solvers: list(env.MODEL_SOLVERS, [DEFAULT_MODEL, "claude-sonnet-5-5", DEFAULT_MODEL]),
    adjudicator: env.MODEL_ADJUDICATOR ?? DEFAULT_MODEL,
    moderator: env.MODEL_MODERATOR ?? DEFAULT_MODEL,
  },
  effort: {
    blueprint: env.EFFORT_BLUEPRINT ?? "medium",
    setter: env.EFFORT_SETTER ?? "high",
    reviewer: env.EFFORT_REVIEWER ?? "high",
    solver: env.EFFORT_SOLVER ?? "high",
    adjudicator: env.EFFORT_ADJUDICATOR ?? "max",
    moderator: env.EFFORT_MODERATOR ?? "high",
  },
};

export const SETTER_PERSONAS = [
  "Setter A - concept-first examiner: tests deep understanding, links two topics, designs distractors from real mistakes",
  "Setter B - numerical-realism examiner: reverse-engineers clean answers from realistic ratings and practical values",
  "Setter C - twist examiner: changes the configuration, the unknown or the direction of a standard problem",
];

export const SOLVER_STRATEGIES = [
  "Solver 1 - direct method: the standard textbook route, carefully applied.",
  "Solver 2 - alternate method: deliberately use a different route from the obvious one (for example per-unit vs actual values, Thevenin vs nodal, phasor vs time domain, Routh vs root locus), then compare.",
  "Solver 3 - checker: solve, then verify by back-substitution, limiting cases, dimensional analysis and order-of-magnitude estimates; for MCQ/MSQ test every option separately.",
];
