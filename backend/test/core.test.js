import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { evaluate } from "../src/expr.js";
import { normalizeAnswer, tally, decide } from "../src/consensus.js";
import { schemaErrors, codeCheck, paperChecks } from "../src/validation.js";
import { buildSkeleton, paperTotals, splitMarks } from "../src/pattern.js";
import { jaccard, findDuplicate } from "../src/similarity.js";
import { loadData } from "../src/server.js";

const data = await loadData(new URL("../../data", import.meta.url).pathname);

test("expression evaluator", () => {
  assert.equal(evaluate("2 + 3 * 4"), 14);
  assert.equal(evaluate("2^3^2"), 512);
  assert.equal(evaluate("-2^2"), -4);
  assert.ok(Math.abs(evaluate("deg(acos(0.5))") - 60) < 1e-9);
  assert.equal(evaluate("V^2/R", { V: 10, R: 4 }), 25);
  assert.throws(() => evaluate("process.exit()"));
  assert.throws(() => evaluate("1/0"));
});

test("answer normalisation", () => {
  assert.equal(normalizeAnswer({ type: "MCQ" }, "(b)"), "B");
  assert.equal(normalizeAnswer({ type: "MCQ" }, "A, C"), null);
  assert.equal(normalizeAnswer({ type: "MSQ" }, "C; A"), "A,C");
  assert.equal(normalizeAnswer({ type: "MSQ" }, ["D", "B"]), "B,D");
  assert.equal(normalizeAnswer({ type: "NAT" }, "approx 3.24 W"), 3.24);
  assert.equal(normalizeAnswer({ type: "MCQ", options: ["x", "0.866", "y", "z"] }, "0.866"), "B");
});

test("consensus: unanimous, disputed, corrected", () => {
  const q = { qno: 1, type: "NAT", answer: "5.2", answer_range: { min: 5.15, max: 5.25 } };
  const ok = tally(q, [{ answer: "5.2" }, { answer: "5.19" }, { answer: "5.21" }], 2);
  assert.equal(ok.status, "unanimous");
  assert.equal(decide(q, ok, null, 2).accepted, true);

  const split = tally(q, [{ answer: "5.2" }, { answer: "6.4" }, { answer: "6.4" }], 2);
  assert.equal(split.status, "disputed");
  assert.equal(decide(q, split, null, 2).accepted, false, "no adjudicator -> rejected");
  const corrected = decide(q, split, { verdict: "corrected", final_answer: "6.4" }, 2);
  assert.equal(corrected.accepted, true);
  assert.equal(corrected.method, "corrected_by_adjudicator");
  assert.equal(corrected.support, 3);
  assert.equal(decide(q, split, { verdict: "ambiguous" }, 2).accepted, false);
  // adjudicator answer nobody else supports does not reach quorum 3
  assert.equal(decide(q, split, { verdict: "corrected", final_answer: "9" }, 2).accepted, false);
});

test("sample bank: all 30 questions pass schema and NAT recomputation", () => {
  const qs = data.sample.questions;
  assert.equal(qs.length, 30);
  for (const q of qs) {
    assert.deepEqual(schemaErrors(q), [], `${q.id}`);
    const cc = codeCheck(q);
    if (q.type === "NAT") assert.equal(cc.status, "pass", `${q.id} computed ${cc.computed}`);
  }
  const types = paperTotals(qs);
  assert.ok(types.MCQ && types.MSQ && types.NAT);
});

test("full mock skeleton matches the GATE EE pattern", () => {
  const sk = buildSkeleton({ mode: "full_mock", seed: 7 }, data.syllabus, data.analysis);
  const t = paperTotals(sk);
  assert.equal(t.questions, 65);
  assert.equal(t.marks, 100);
  assert.equal(t.GA, 10);
  assert.equal(sk.filter((s) => s.section === "GA" && s.marks === 1).length, 5);
  assert.equal(sk.filter((s) => s.section === "Technical" && s.marks === 2).length, 30);
  assert.deepEqual(paperChecks(sk, "full_mock").issues, []);
  const subtopicCount = {};
  for (const s of sk) subtopicCount[s.subtopic] = (subtopicCount[s.subtopic] ?? 0) + 1;
  assert.ok(Object.values(subtopicCount).every((n) => n <= 2), "no subtopic more than twice");
  for (const s of sk) if (s.type === "MCQ") assert.equal(s.negative_marks, s.marks === 1 ? 0.33 : 0.67);
  for (const s of sk) if (s.type !== "MCQ") assert.equal(s.negative_marks, 0);
});

test("mark split keeps totals for any allocation", () => {
  const { one, two } = splitMarks({ A: 3, B: 20, C: 62 }, 25, 30);
  assert.equal(Object.values(one).reduce((a, b) => a + b), 25);
  assert.equal(Object.values(two).reduce((a, b) => a + b), 30);
  assert.ok(Object.values(one).every((v) => v >= 0));
});

test("duplicate detection catches light rewording", () => {
  const a = data.sample.questions.find((q) => q.subtopic === "Regulation and efficiency").question;
  // same question with new numbers and small wording edits
  const b = a.replace("20 kVA", "25 kVA").replace("200 W", "250 W").replace("400 W", "500 W").replace("single-phase", "single phase").replace("Its efficiency", "The efficiency");
  assert.ok(jaccard(a, b) > 0.6);
  assert.ok(findDuplicate(b, data.sample.questions));
  assert.equal(findDuplicate("Find the eigenvalues of a 3x3 rotation matrix about the z axis.", data.sample.questions), null);
});

test("PYQ analysis produces all Module 1 views", () => {
  const a = data.analysis;
  assert.ok(a.years.includes(2010) && a.years.includes(2026));
  for (const k of ["subjects", "topics", "type_split_by_year", "difficulty_by_year", "most_repeated_concepts", "high_priority_topics", "gap_topics"]) assert.ok(a[k], k);
  assert.ok(a.subjects.EM.avg_5yr > 0 && a.subjects.EM.avg_10yr > 0);
  assert.ok(Object.values(a.topics).every((t) => t.predicted_score >= 0 && t.predicted_score <= 1));
});

test("data files are valid JSON", () => {
  for (const f of ["syllabus.json", "sample_questions.json", "pyq_tags_demo.json", "mock_series.json", "formula_sheets.json"]) {
    JSON.parse(fs.readFileSync(new URL(`../../data/${f}`, import.meta.url), "utf8"));
  }
});
