// Multi-agent answer verification: normalise every agent's answer, then decide by vote.
//
// Rules (K = number of solver agents, Q = quorum, default 2 of 3):
//   * all K solvers agree with the setter            -> "verified" (unanimous)
//   * otherwise the Adjudicator Agent rules; the final answer must be backed by at least Q
//     votes among (solvers + adjudicator)             -> "verified" (adjudicated / corrected)
//   * anything else                                    -> "rejected" (slot is regenerated)

const LETTERS = ["A", "B", "C", "D"];

export function normalizeAnswer(q, raw) {
  if (raw == null) return null;
  if (q.type === "NAT") {
    const m = String(raw).replace(/,/g, "").match(/-?\d+(\.\d+)?([eE][+-]?\d+)?/);
    return m ? Number(m[0]) : null;
  }
  const str = String(Array.isArray(raw) ? raw.join(",") : raw).toUpperCase();
  let letters = [...new Set(str.match(/\b[A-D]\b/g) ?? [])];
  if (!letters.length && q.options?.length) {
    // agent replied with option text instead of a letter
    const idx = q.options.findIndex((o) => String(o).trim().toUpperCase() === str.trim());
    if (idx >= 0) letters = [LETTERS[idx]];
  }
  letters.sort();
  if (q.type === "MCQ") return letters.length === 1 ? letters[0] : null;
  return letters.length ? letters.join(",") : null;
}

export function natTolerance(q, ref) {
  const r = q.answer_range;
  // the setter's range only applies when it is the range around this reference value
  if (r && Number.isFinite(r.min) && Number.isFinite(r.max) && ref >= r.min && ref <= r.max) {
    return { min: r.min, max: r.max };
  }
  const tol = Math.max(Math.abs(ref) * 0.01, 0.005);
  return { min: ref - tol, max: ref + tol };
}

export function answersMatch(q, a, b) {
  if (a == null || b == null) return false;
  if (q.type !== "NAT") return a === b;
  // b is a solver's value; accept if inside the setter's acceptable range, or within 1% of a
  const r = natTolerance(q, a);
  const lo = Math.min(r.min, a - Math.abs(a) * 0.01);
  const hi = Math.max(r.max, a + Math.abs(a) * 0.01);
  return b >= lo - 1e-9 && b <= hi + 1e-9;
}

/**
 * @param q        question (with setter answer)
 * @param solverAnswers [{agent, model, answer, confidence}]
 */
export function tally(q, solverAnswers, quorum) {
  const setter = normalizeAnswer(q, q.answer);
  const votes = solverAnswers.map((s) => ({ ...s, normalized: normalizeAnswer(q, s.answer) }));
  const agree = votes.filter((v) => answersMatch(q, setter, v.normalized)).length;
  const k = votes.length;
  const status = setter == null ? "invalid_key" : agree === k ? "unanimous" : agree >= quorum ? "majority" : "disputed";
  return { qno: q.qno, setter_answer: setter, votes, agree, total: k, status };
}

/** Combine the tally with an adjudicator verdict and return the final decision. */
export function decide(q, t, verdict, quorum) {
  if (t.status === "unanimous") {
    return { accepted: true, final_answer: t.setter_answer, method: "unanimous", support: t.agree, of: t.total };
  }
  if (!verdict) {
    return { accepted: false, reason: `no adjudication for ${t.status} answer (${t.agree}/${t.total})` };
  }
  if (verdict.verdict === "ambiguous") return { accepted: false, reason: `adjudicator: ambiguous - ${verdict.reason ?? ""}` };
  const finalAns = normalizeAnswer(q, verdict.final_answer);
  if (finalAns == null) return { accepted: false, reason: "adjudicator gave no usable answer" };
  const solverSupport = t.votes.filter((v) => answersMatch(q, finalAns, v.normalized)).length;
  const support = solverSupport + 1; // adjudicator's own vote
  if (support < quorum) {
    return { accepted: false, reason: `final answer backed by ${support} votes, quorum ${quorum}` };
  }
  const corrected = !answersMatch(q, t.setter_answer, finalAns);
  return {
    accepted: true,
    final_answer: finalAns,
    method: corrected ? "corrected_by_adjudicator" : "adjudicated",
    support,
    of: t.total + 1,
  };
}

export function formatAnswer(q, normalized) {
  if (q.type === "NAT") return String(normalized);
  return normalized;
}
