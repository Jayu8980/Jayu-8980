// Validation layer (runs in code after the agents return):
// schema, NAT recomputation + range, MSQ sanity, duplicate check, paper totals.
import { evaluate } from "./expr.js";
import { normalizeAnswer } from "./consensus.js";
import { findDuplicate } from "./similarity.js";
import { negativeMarks, paperTotals, FULL_PATTERN } from "./pattern.js";
import { figureErrors, prepareFigure } from "./figures/index.js";

const TYPES = ["MCQ", "MSQ", "NAT"];
const LEVELS = ["Recall", "Application", "Analysis"];
const DIFFS = ["Easy", "Medium", "Hard"];

/** Returns a list of problems; empty means the question passed the schema check. */
export function schemaErrors(q, slot) {
  const e = [];
  if (!q || typeof q !== "object") return ["not an object"];
  if (typeof q.question !== "string" || q.question.trim().length < 20) e.push("question text missing or too short");
  if (!TYPES.includes(q.type)) e.push(`bad type ${q.type}`);
  if (![1, 2].includes(q.marks)) e.push(`bad marks ${q.marks}`);
  if (slot) {
    if (q.type !== slot.type) e.push(`type ${q.type} does not match blueprint ${slot.type}`);
    if (q.marks !== slot.marks) e.push(`marks ${q.marks} do not match blueprint ${slot.marks}`);
  }
  if (q.type === "MCQ" || q.type === "MSQ") {
    if (!Array.isArray(q.options) || q.options.length !== 4 || q.options.some((o) => typeof o !== "string" || !o.trim())) e.push("needs exactly 4 non-empty options");
    else if (new Set(q.options.map((o) => o.trim().toLowerCase())).size !== 4) e.push("options are not distinct");
    if (q.options?.some((o) => /all of the above|none of the above/i.test(o))) e.push("'all/none of the above' is not allowed");
  }
  const ans = normalizeAnswer(q, q.answer);
  // MSQ normalisation returns null when no option letter is marked, so this also enforces "at least one correct option"
  if (ans == null) e.push(`answer '${q.answer}' is not valid for ${q.type}`);
  if (q.type === "NAT") {
    if (!q.answer_range || !Number.isFinite(Number(q.answer_range.min)) || !Number.isFinite(Number(q.answer_range.max))) e.push("NAT needs answer_range {min,max}");
    else if (ans != null && (ans < Number(q.answer_range.min) || ans > Number(q.answer_range.max))) e.push(`NAT answer ${ans} lies outside its range`);
    else if (Number(q.answer_range.max) - Number(q.answer_range.min) > Math.max(Math.abs(ans) * 0.05, 0.05)) e.push("NAT range is too wide (would accept conceptual errors)");
  }
  if (typeof q.solution !== "string" || q.solution.trim().length < 15) e.push("solution missing");
  if (slot?.figure && !q.figure) e.push(`blueprint slot needs a figure (suggested type: ${slot.figure})`);
  if (q.figure) e.push(...figureErrors(q.figure).map((m) => `figure: ${m}`));
  if (q.cognitive_level && !LEVELS.includes(q.cognitive_level)) e.push(`bad cognitive_level ${q.cognitive_level}`);
  if (q.difficulty && !DIFFS.includes(q.difficulty)) e.push(`bad difficulty ${q.difficulty}`);
  return e;
}

/** NAT: recompute the answer from the setter's verification expression where one is given. */
export function codeCheck(q) {
  if (q.type !== "NAT" || !q.verification?.expression) return { status: "not_applicable" };
  try {
    const value = evaluate(q.verification.expression, q.verification.variables ?? {});
    const lo = Number(q.answer_range?.min);
    const hi = Number(q.answer_range?.max);
    // allow the rounding the question asks for: a computed value just outside the range by
    // less than one rounding step still rounds into it
    const step = Math.max((hi - lo) / 2, 1e-9);
    const ok = value >= lo - step && value <= hi + step;
    return { status: ok ? "pass" : "fail", computed: value };
  } catch (err) {
    return { status: "error", error: err.message };
  }
}

export function duplicateCheck(q, banks, threshold) {
  for (const [name, bank] of Object.entries(banks)) {
    const hit = findDuplicate(q.question, bank, threshold);
    if (hit) return { duplicate: true, of: name, score: Math.round(hit.score * 100) / 100, match: (hit.item.id ?? hit.item.question ?? hit.item).toString().slice(0, 80) };
  }
  return { duplicate: false };
}

/** Normalise fields that the code owns, so the agents can't drift from the blueprint. */
export function finalizeQuestion(q, slot, extra = {}) {
  const out = {
    id: extra.id,
    qno: slot.qno,
    section: slot.section,
    subject_id: slot.subject_id,
    subject: q.subject || slot.subject,
    topic_id: q.topic_id || slot.topic_id,
    topic: q.topic || slot.topic,
    subtopic: q.subtopic || slot.subtopic,
    type: slot.type,
    marks: slot.marks,
    negative_marks: negativeMarks(slot.type, slot.marks),
    cognitive_level: q.cognitive_level || slot.cognitive_level,
    difficulty: q.difficulty || slot.difficulty,
    question: q.question.trim(),
    options: slot.type === "NAT" ? [] : q.options.map((o) => o.trim()),
    answer: String(normalizeAnswer(q, q.answer)),
    answer_range: slot.type === "NAT" ? { min: Number(q.answer_range.min), max: Number(q.answer_range.max) } : null,
    solution: q.solution,
    concept: q.concept ?? "",
    common_trap: q.common_trap ?? "",
    distractor_logic: q.distractor_logic ?? "",
    related_pyq_year: q.related_pyq_year ?? "",
    predicted_importance: q.predicted_importance ?? "Medium",
    time_estimate_sec: Number(q.time_estimate_sec) || (slot.marks === 1 ? 90 : 180),
    verification: q.verification ?? null,
    // the app draws `figure.render`; solvers read `figure.description`
    figure: q.figure ? prepareFigure(q.figure) : null,
    ...extra,
  };
  return out;
}

/** Paper-level checks: totals, section counts and type mix. */
export function paperChecks(questions, mode) {
  const t = paperTotals(questions);
  const issues = [];
  if (mode === "full_mock") {
    if (t.questions !== FULL_PATTERN.questions) issues.push(`${t.questions} questions, expected 65`);
    if (t.marks !== FULL_PATTERN.marks) issues.push(`${t.marks} marks, expected 100`);
    if ((t.GA ?? 0) !== 10) issues.push(`${t.GA ?? 0} GA questions, expected 10`);
    const share = (k) => t[k] / Math.max(1, t.questions);
    if (share("MCQ") < 0.5 || share("MCQ") > 0.65) issues.push(`MCQ share ${share("MCQ").toFixed(2)} outside 0.55-0.60 target`);
    if (share("MSQ") < 0.08 || share("MSQ") > 0.17) issues.push(`MSQ share ${share("MSQ").toFixed(2)} outside 0.10-0.15 target`);
    if (share("NAT") < 0.22 || share("NAT") > 0.32) issues.push(`NAT share ${share("NAT").toFixed(2)} outside 0.25-0.30 target`);
  }
  return { totals: t, issues, ok: issues.length === 0 };
}
