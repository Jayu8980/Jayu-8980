// GATE Paper Setter pipeline with a multi-agent committee:
//
//   ANALYSE -> PREDICT (code)            topic map + likelihood scores from the PYQ bank
//   1. Blueprint Agent                   plans every slot on a fixed code-built skeleton
//   2. Setter committee (N agents)       each setter drafts every slot independently
//   3. Design Review Agent               picks + improves the best draft per slot
//   4. Solver ensemble (K agents)        solve blind (no answer key), different models/methods
//   5. Consensus (code) + Adjudicator    vote; disputes and code-check failures are adjudicated
//   6. Moderator Agent                   approve / fix / reject; fixes are re-verified
//   7. Validation layer (code)           schema, NAT recompute, duplicates, totals
//   -> rejected slots are regenerated (up to MAX_REGEN_ROUNDS), then the Final Paper is built.
import { buildSkeleton, paperTotals, MODE_DEFAULTS } from "./pattern.js";
import { analysisForPrompt } from "./analysis.js";
import { makeAgents } from "./agents.js";
import { tally, decide, normalizeAnswer, natTolerance } from "./consensus.js";
import { schemaErrors, codeCheck, duplicateCheck, finalizeQuestion, paperChecks } from "./validation.js";

export async function runPipeline(request, ctx, onProgress = () => {}) {
  const { llm, config, syllabus, analysis, pyqBank = [], generatedBank = [], store } = ctx;
  const agents = makeAgents({ llm, config, store });
  const quorum = Math.min(config.quorum, config.models.solvers.length);
  const avoid = (request.avoid_summaries ?? []).slice(-200);
  const log = [];
  const note = (m) => {
    log.push({ t: new Date().toISOString(), m });
    onProgress({ log: m });
  };

  // ---- ANALYSE + PREDICT ----
  onProgress({ stage: "analyse", progress: 0.02 });
  const compact = analysisForPrompt(analysis);
  await store?.save("01_analysis", compact);

  // ---- 1. BLUEPRINT ----
  onProgress({ stage: "blueprint", progress: 0.05 });
  const skeleton = buildSkeleton(request, syllabus, analysis);
  let blueprint = skeleton;
  try {
    const planned = await agents.blueprint({ skeleton, request, analysis: compact, avoid });
    blueprint = mergeBlueprint(skeleton, planned, syllabus);
  } catch (e) {
    note(`blueprint agent failed, using the code skeleton: ${e.message}`);
  }
  await store?.save("02_blueprint", blueprint);

  // ---- 2..7 per round ----
  const accepted = new Map();
  const rejectedLog = [];
  let pending = blueprint;
  const totalSlots = blueprint.length;
  for (let round = 0; round <= config.maxRounds && pending.length; round++) {
    note(`round ${round}: producing ${pending.length} question(s)`);
    const batches = chunk(pending, config.batchSize);
    const failed = [];
    await mapLimit(batches, config.maxConcurrency, async (batch) => {
      const res = await produceBatch(batch, { round, request, agents, config, quorum, avoid, pyqBank, generatedBank, accepted, note });
      for (const q of res.accepted) accepted.set(q.qno, q);
      failed.push(...res.rejected);
      onProgress({ stage: `round ${round}: setting and verifying`, progress: 0.1 + 0.75 * (accepted.size / totalSlots) });
    });

    // ---- 6. MODERATION of this round's accepted questions ----
    const fresh = [...accepted.values()].filter((q) => q._round === round);
    if (fresh.length) {
      onProgress({ stage: `round ${round}: moderation`, progress: 0.1 + 0.75 * (accepted.size / totalSlots) });
      const verdicts = await moderateAndReverify(fresh, { request, agents, config, quorum, avoid, store, note });
      for (const v of verdicts.rejected) {
        accepted.delete(v.qno);
        failed.push({ slot: blueprint.find((s) => s.qno === v.qno), reason: `moderator: ${v.reason}` });
      }
      if (round === 0) ctx.moderatorEstimates = verdicts.estimates;
    }
    rejectedLog.push(...failed.map((f) => ({ round, qno: f.slot.qno, reason: f.reason })));
    pending = failed.map((f) => f.slot);
  }

  // ---- FINAL PAPER ----
  onProgress({ stage: "final paper", progress: 0.95 });
  const questions = [...accepted.values()].sort((a, b) => a.qno - b.qno).map(({ _round, ...q }) => q);
  const checks = paperChecks(questions, request.mode);
  const missing = blueprint.filter((s) => !accepted.has(s.qno)).map((s) => s.qno);
  const paper = {
    id: ctx.jobId,
    title: request.title ?? defaultTitle(request),
    mode: request.mode,
    created_at: new Date().toISOString(),
    duration_min: request.duration_min ?? durationFor(request),
    status: missing.length ? "completed_with_gaps" : "complete",
    missing_qnos: missing,
    blueprint: blueprint.map(({ qno, section, subject_id, topic_id, subtopic, type, marks, cognitive_level, difficulty }) => ({
      qno,
      section,
      subject_id,
      topic_id,
      subtopic,
      type,
      marks,
      cognitive_level,
      difficulty,
    })),
    questions,
    paper_report: paperReport(questions, ctx.moderatorEstimates, checks),
    verification_summary: verificationSummary(questions, rejectedLog),
    agents: { provider: llm.name, ...config.models, quorum },
    log,
  };
  await store?.save("final_paper", paper);
  onProgress({ stage: "done", progress: 1 });
  return paper;
}

async function produceBatch(slots, { round, request, agents, config, quorum, avoid, pyqBank, generatedBank, accepted, note }) {
  const bySlot = new Map(slots.map((s) => [s.qno, s]));
  const rejected = [];
  const reject = (slot, reason) => rejected.push({ slot, reason });
  const banks = { pyq: pyqBank, generated: generatedBank, this_paper: [...accepted.values()] };

  // 2. setter committee
  const drafts = await agents.setters({ slots, request, round, avoid });
  for (const d of drafts) if (d.error) note(`${d.setter} (${d.model}) failed: ${d.error}`);
  const candidates = new Map(slots.map((s) => [s.qno, []]));
  for (const d of drafts) {
    for (const q of d.questions) {
      const slot = bySlot.get(q?.qno);
      if (!slot) continue;
      const errs = schemaErrors(q, slot);
      const dup = errs.length ? null : duplicateCheck(q, banks, config.duplicateThreshold);
      if (!errs.length && !dup.duplicate) candidates.get(slot.qno).push({ setter: d.setter, model: d.model, question: q });
    }
  }

  // 3. design review
  const reviewable = slots.filter((s) => candidates.get(s.qno).length);
  for (const s of slots) if (!candidates.get(s.qno).length) reject(s, "no setter produced a valid, original draft");
  let reviewed = new Map();
  if (reviewable.length) {
    try {
      const out = await agents.review({
        slotsWithCandidates: reviewable.map((s) => ({ ...s, candidates: candidates.get(s.qno).map((c) => ({ setter: c.setter, question: c.question })) })),
      });
      reviewed = new Map(out.filter((q) => q?.qno != null).map((q) => [q.qno, q]));
    } catch (e) {
      note(`reviewer failed, using first valid draft: ${e.message}`);
    }
  }
  const drafted = [];
  for (const s of reviewable) {
    const r = reviewed.get(s.qno);
    const first = candidates.get(s.qno)[0];
    const pick = r && !schemaErrors(r, s).length && !duplicateCheck(r, banks, config.duplicateThreshold).duplicate ? r : first.question;
    const chosen = r?.chosen_from ? candidates.get(s.qno).find((c) => c.setter === r.chosen_from) ?? first : first;
    drafted.push({ slot: s, q: pick, meta: { chosen_from: chosen.setter, setter_model: chosen.model, candidates: candidates.get(s.qno).length, review_notes: r?.review_notes ?? "" } });
  }
  if (!drafted.length) return { accepted: [], rejected };

  // 4. blind solver ensemble
  const solverRuns = await agents.solvers({ questions: drafted.map((d) => ({ ...d.q, qno: d.slot.qno })) });
  for (const s of solverRuns) if (s.error) note(`${s.agent} (${s.model}) failed: ${s.error}`);

  // 5. consensus + adjudication
  const tallies = new Map();
  const disputes = [];
  for (const d of drafted) {
    const votes = solverRuns.map((run) => {
      const a = run.answers.find((x) => x?.qno === d.slot.qno);
      return { agent: run.agent, model: run.model, answer: a?.answer ?? null, confidence: a?.confidence ?? null, working: a?.working ?? "", issues: a?.issues ?? "" };
    });
    const t = tally(d.q, votes, quorum);
    const cc = codeCheck(d.q);
    tallies.set(d.slot.qno, { t, cc });
    if (t.status !== "unanimous" || cc.status === "fail") {
      disputes.push({
        qno: d.slot.qno,
        type: d.q.type,
        question: d.q.question,
        options: d.q.options,
        setter_answer: t.setter_answer,
        setter_solution: d.q.solution,
        answer_range: d.q.answer_range,
        code_recomputed_value: cc.computed ?? null,
        solvers: votes.map((v) => ({ agent: v.agent, answer: v.answer, working: v.working, issues: v.issues })),
      });
    }
  }
  let verdicts = new Map();
  if (disputes.length) {
    try {
      const out = await agents.adjudicate({ disputes });
      verdicts = new Map(out.map((v) => [v.qno, v]));
    } catch (e) {
      note(`adjudicator failed: ${e.message}`);
    }
  }

  const acceptedNow = [];
  for (const d of drafted) {
    const { t, cc } = tallies.get(d.slot.qno);
    const verdict = verdicts.get(d.slot.qno);
    // a failed code recomputation can only be cleared by the adjudicator, never by a vote alone
    const decision = cc.status === "fail" && t.status === "unanimous" ? decide(d.q, { ...t, status: "disputed" }, verdict, quorum) : decide(d.q, t, verdict, quorum);
    if (!decision.accepted) {
      reject(d.slot, decision.reason);
      continue;
    }
    let q = d.q;
    if (decision.method === "corrected_by_adjudicator") {
      q = {
        ...q,
        answer: String(decision.final_answer),
        solution: verdict.corrected_solution || q.solution,
        answer_range: q.type === "NAT" ? verdict.answer_range ?? natTolerance(q, Number(decision.final_answer)) : null,
      };
    }
    const finalCc = codeCheck(q);
    if (finalCc.status === "fail" && decision.method !== "corrected_by_adjudicator") {
      reject(d.slot, `NAT recomputation gave ${finalCc.computed}, outside the answer range`);
      continue;
    }
    const errs = schemaErrors(q, d.slot);
    if (errs.length) {
      reject(d.slot, `validation: ${errs.join("; ")}`);
      continue;
    }
    const finalAns = normalizeAnswer(q, q.answer);
    acceptedNow.push(
      finalizeQuestion(q, d.slot, {
        id: `${request.mode}-${Date.now().toString(36)}-${d.slot.qno}`,
        _round: round,
        source: "ai_generated",
        verification_report: {
          method: decision.method,
          support: `${decision.support}/${decision.of}`,
          setter: d.meta.chosen_from,
          setter_model: d.meta.setter_model,
          candidates_considered: d.meta.candidates,
          review_notes: d.meta.review_notes,
          solver_votes: t.votes.map((v) => ({ agent: v.agent, model: v.model, answer: v.answer, agrees: v.normalized != null && tallyMatch(q, finalAns, v.normalized) })),
          adjudicator: verdict ? { verdict: verdict.verdict, reason: verdict.reason } : null,
          code_check: finalCc.status,
          code_value: finalCc.computed ?? null,
        },
      }),
    );
  }
  return { accepted: acceptedNow, rejected };
}

function tallyMatch(q, a, b) {
  if (q.type !== "NAT") return a === b;
  const r = natTolerance(q, a);
  return b >= r.min - 1e-9 && b <= r.max + 1e-9;
}

async function moderateAndReverify(questions, { request, agents, config, quorum, avoid, note }) {
  const rejected = [];
  let estimates = null;
  for (const group of chunk(questions, 20)) {
    let out;
    try {
      out = await agents.moderate({ questions: group.map(({ verification_report, _round, ...q }) => q), request, avoid });
    } catch (e) {
      note(`moderator failed, keeping verified questions: ${e.message}`);
      continue;
    }
    estimates ??= {
      expected_average_score: out.expected_average_score,
      expected_topper_score: out.expected_topper_score,
      expected_cutoff: out.expected_cutoff,
      balance_notes: out.balance_notes,
    };
    const toReverify = [];
    for (const dec of out.decisions ?? []) {
      const q = group.find((x) => x.qno === dec.qno);
      if (!q) continue;
      q.verification_report.moderator = dec.action;
      if (dec.difficulty_actual) q.difficulty = dec.difficulty_actual;
      if (dec.action === "reject") rejected.push({ qno: q.qno, reason: dec.reasons || "rejected" });
      else if (dec.action === "fix" && dec.fixed_question) {
        const fixed = { ...dec.fixed_question, type: q.type, marks: q.marks, qno: q.qno };
        if (schemaErrors(fixed, q).length) {
          note(`moderator fix for Q${q.qno} failed validation; keeping the verified version`);
          continue;
        }
        if (dec.answer_changed || fixed.question !== q.question || String(normalizeAnswer(fixed, fixed.answer)) !== q.answer) toReverify.push({ q, fixed });
        else Object.assign(q, { solution: fixed.solution ?? q.solution, distractor_logic: fixed.distractor_logic ?? q.distractor_logic, common_trap: fixed.common_trap ?? q.common_trap });
      }
    }
    // a moderator edit that touches the question or answer must pass the solver ensemble again
    if (toReverify.length) {
      const runs = await agents.solvers({ questions: toReverify.map((x) => x.fixed) });
      for (const { q, fixed } of toReverify) {
        const votes = runs.map((r) => ({ agent: r.agent, model: r.model, answer: r.answers.find((a) => a?.qno === q.qno)?.answer ?? null }));
        const t = tally(fixed, votes, quorum);
        const cc = codeCheck(fixed);
        if (t.agree >= quorum && cc.status !== "fail") {
          Object.assign(q, finalizeQuestion(fixed, q, { id: q.id, _round: q._round, source: q.source, verification_report: { ...q.verification_report, moderator: "fix", reverified: `${t.agree}/${t.total}` } }));
        } else {
          note(`moderator fix for Q${q.qno} did not re-verify (${t.agree}/${t.total}); keeping the verified version`);
        }
      }
    }
  }
  return { rejected, estimates };
}

function mergeBlueprint(skeleton, planned, syllabus) {
  const byQno = new Map((planned ?? []).map((p) => [p.qno, p]));
  return skeleton.map((s) => {
    const p = byQno.get(s.qno);
    if (!p) return s;
    const subj = syllabus.subjects.find((x) => x.id === s.subject_id);
    const topic = subj?.topics.find((t) => t.id === p.topic_id);
    return {
      ...s,
      ...(topic ? { topic_id: topic.id, topic: topic.name, subtopic: topic.subtopics.includes(p.subtopic) ? p.subtopic : p.subtopic || topic.subtopics[0] } : {}),
      cognitive_level: ["Recall", "Application", "Analysis"].includes(p.cognitive_level) ? p.cognitive_level : s.cognitive_level,
      difficulty: ["Easy", "Medium", "Hard"].includes(p.difficulty) ? p.difficulty : s.difficulty,
      intent: p.intent ?? "",
    };
  });
}

function paperReport(questions, est, checks) {
  const marksBySubject = {};
  const typeMix = { MCQ: 0, MSQ: 0, NAT: 0 };
  const difficultyMix = { Easy: 0, Medium: 0, Hard: 0 };
  for (const q of questions) {
    marksBySubject[q.subject_id] = (marksBySubject[q.subject_id] ?? 0) + q.marks;
    typeMix[q.type]++;
    difficultyMix[q.difficulty] = (difficultyMix[q.difficulty] ?? 0) + 1;
  }
  const total = questions.reduce((s, q) => s + q.marks, 0);
  // fallback estimate: share of marks a topper / average candidate gets by difficulty
  const share = (p) => Math.round(questions.reduce((s, q) => s + q.marks * p[q.difficulty], 0));
  return {
    total_marks: total,
    totals: paperTotals(questions),
    marks_by_subject: marksBySubject,
    type_mix: typeMix,
    difficulty_mix: difficultyMix,
    difficulty_curve: questions.map((q) => ({ qno: q.qno, difficulty: q.difficulty })),
    expected_topper_score: num(est?.expected_topper_score) ?? share({ Easy: 0.97, Medium: 0.88, Hard: 0.7 }),
    expected_average_score: num(est?.expected_average_score) ?? share({ Easy: 0.5, Medium: 0.3, Hard: 0.12 }),
    expected_cutoff: num(est?.expected_cutoff) ?? Math.round(total * 0.27),
    balance_notes: est?.balance_notes ?? "",
    pattern_issues: checks.issues,
  };
}

function verificationSummary(questions, rejectedLog) {
  const c = { unanimous: 0, adjudicated: 0, corrected_by_adjudicator: 0 };
  for (const q of questions) c[q.verification_report.method] = (c[q.verification_report.method] ?? 0) + 1;
  return { ...c, code_checked: questions.filter((q) => q.verification_report.code_check === "pass").length, rejected_drafts: rejectedLog.length, rejections: rejectedLog };
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function defaultTitle(r) {
  const names = { full_mock: "AI Full Mock", subject: "AI Subject Mock", topic: "AI Topic Mock", weak_area: "Weak-Area Mock", daily: "Daily Live Practice", custom: "Custom Mock" };
  return `${names[r.mode] ?? "AI Mock"} - ${new Date().toISOString().slice(0, 10)}`;
}

function durationFor(r) {
  if (r.mode === "full_mock" || r.question_count == null) return MODE_DEFAULTS[r.mode]?.durationMin ?? 60;
  return Math.max(15, Math.round(r.question_count * 2.8));
}

export function chunk(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

export async function mapLimit(items, limit, fn) {
  let i = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (i < items.length) {
      const idx = i++;
      await fn(items[idx], idx);
    }
  });
  await Promise.all(workers);
}
