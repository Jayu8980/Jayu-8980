// GATE EE exam pattern, weightage and the deterministic "skeleton" every paper is built on.
// The Blueprint Agent refines the skeleton (subtopics, cognitive level, difficulty) but can never
// break the counts/marks fixed here; validation.js re-checks them on the final paper.

export const SUBJECT_IDS = ["GA", "MATH", "EC", "EMF", "SS", "EM", "PS", "CS", "MEAS", "AE", "PE"];
export const TECH_SUBJECTS = SUBJECT_IDS.filter((s) => s !== "GA");

export const FULL_PATTERN = {
  questions: 65,
  marks: 100,
  durationMin: 180,
  ga: { one: 5, two: 5 }, // 15 marks
  tech: { one: 25, two: 30 }, // 85 marks
};

// Approximate technical weightage (marks) per full mock, from the master prompt.
export const WEIGHTAGE = {
  MATH: [11, 13],
  EM: [10, 12],
  PS: [9, 11],
  EC: [8, 10],
  CS: [7, 9],
  PE: [7, 9],
  AE: [7, 9],
  SS: [6, 8],
  MEAS: [4, 6],
  EMF: [3, 5],
};

export const TYPE_MIX = { MCQ: [0.55, 0.6], MSQ: [0.1, 0.15], NAT: [0.25, 0.3] };
export const DIFFICULTY_MIX = { Easy: 0.3, Medium: 0.45, Hard: 0.25 };
export const COGNITIVE_MIX = { Recall: 0.2, Application: 0.45, Analysis: 0.35 };

// Subjects whose questions are naturally numerical get NAT slots first.
const NAT_AFFINITY = { EM: 3, PS: 3, PE: 3, EC: 3, MATH: 2, CS: 2, EMF: 2, SS: 1, MEAS: 2, AE: 1 };
const MSQ_AFFINITY = { CS: 3, AE: 3, SS: 2, MATH: 2, EM: 2, PS: 2, PE: 2, MEAS: 2, EC: 1, EMF: 1 };

export const MODE_DEFAULTS = {
  full_mock: { questions: 65, durationMin: 180 },
  subject: { questions: 25, one: 10, two: 15, durationMin: 75 },
  topic: { questions: 15, one: 7, two: 8, durationMin: 45 },
  weak_area: { questions: 20, one: 9, two: 11, durationMin: 60 },
  daily: { questions: 12, one: 6, two: 6, durationMin: 30 },
  custom: { questions: 20, durationMin: 60 },
};

export function negativeMarks(type, marks) {
  if (type !== "MCQ") return 0;
  return marks === 1 ? 0.33 : 0.67;
}

// Small seeded PRNG so a given request always yields the same skeleton (reproducible runs).
export function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return ((s >>> 0) % 1_000_000) / 1_000_000;
  };
}

function shuffle(arr, rand) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

// Spread a categorical distribution over n items using largest remainder.
export function apportion(n, weights) {
  const keys = Object.keys(weights);
  const total = keys.reduce((s, k) => s + weights[k], 0);
  const raw = keys.map((k) => ({ k, v: (weights[k] / total) * n }));
  const out = Object.fromEntries(raw.map(({ k, v }) => [k, Math.floor(v)]));
  let left = n - Object.values(out).reduce((s, v) => s + v, 0);
  raw.sort((a, b) => (b.v % 1) - (a.v % 1));
  for (let i = 0; left > 0; i = (i + 1) % raw.length, left--) out[raw[i].k]++;
  return out;
}

// Technical marks per subject: midpoint of each range, then the remaining marks go to the
// subjects the PYQ predictor ranks highest (never above the range maximum).
export function allocateTechMarks(subjectPriority = {}) {
  const marks = {};
  for (const [s, [lo, hi]] of Object.entries(WEIGHTAGE)) marks[s] = Math.round((lo + hi) / 2);
  let total = Object.values(marks).reduce((a, b) => a + b, 0);
  const order = Object.keys(WEIGHTAGE).sort((a, b) => (subjectPriority[b] ?? 0) - (subjectPriority[a] ?? 0));
  while (total < 85) {
    let moved = false;
    for (const s of order) {
      if (total >= 85) break;
      if (marks[s] < WEIGHTAGE[s][1]) {
        marks[s]++;
        total++;
        moved = true;
      }
    }
    if (!moved) break;
  }
  while (total > 85) {
    for (const s of [...order].reverse()) {
      if (total <= 85) break;
      if (marks[s] > WEIGHTAGE[s][0]) {
        marks[s]--;
        total--;
      }
    }
  }
  return marks;
}

// Split each subject's marks into 1-mark and 2-mark questions with the totals fixed (25 and 30).
export function splitMarks(marks, totalOne, totalTwo) {
  const totalMarks = Object.values(marks).reduce((a, b) => a + b, 0);
  const twoShare = Object.fromEntries(Object.entries(marks).map(([s, m]) => [s, (m * totalTwo) / totalMarks]));
  const two = apportion(totalTwo, twoShare);
  const one = {};
  for (const s of Object.keys(marks)) one[s] = marks[s] - 2 * two[s];
  // Repair negatives: a subject with too many 2-markers gives one away to a subject with spare 1-markers.
  for (let guard = 0; guard < 100; guard++) {
    const neg = Object.keys(one).find((s) => one[s] < 0);
    if (!neg) break;
    const donor = Object.keys(one).sort((a, b) => one[b] - one[a]).find((s) => s !== neg && one[s] >= 2);
    if (!donor) break;
    two[neg]--;
    one[neg] += 2;
    two[donor]++;
    one[donor] -= 2;
  }
  const sumOne = Object.values(one).reduce((a, b) => a + b, 0);
  if (sumOne !== totalOne) throw new Error(`mark split failed: ${sumOne} one-markers, expected ${totalOne}`);
  return { one, two };
}

function assignTypes(slots, counts, rand) {
  // counts: {MCQ, MSQ, NAT}. Highest-affinity slots get NAT, then MSQ; rest MCQ.
  const ordered = shuffle(slots, rand);
  const byNat = [...ordered].sort((a, b) => (NAT_AFFINITY[b.subject_id] ?? 0) - (NAT_AFFINITY[a.subject_id] ?? 0));
  const natSet = new Set(byNat.slice(0, counts.NAT));
  const rest = ordered.filter((s) => !natSet.has(s));
  const byMsq = [...rest].sort((a, b) => (MSQ_AFFINITY[b.subject_id] ?? 0) - (MSQ_AFFINITY[a.subject_id] ?? 0));
  const msqSet = new Set(byMsq.slice(0, counts.MSQ));
  for (const s of slots) s.type = natSet.has(s) ? "NAT" : msqSet.has(s) ? "MSQ" : "MCQ";
}

function assignLevels(slots, rand) {
  const diff = apportion(slots.length, DIFFICULTY_MIX);
  const cog = apportion(slots.length, COGNITIVE_MIX);
  // 2-mark questions take the hard/analysis tags first; then hard questions are spread out.
  const sorted = [...slots].sort((a, b) => b.marks - a.marks || rand() - 0.5);
  const diffList = [...Array(diff.Hard).fill("Hard"), ...Array(diff.Medium).fill("Medium"), ...Array(diff.Easy).fill("Easy")];
  const cogList = [...Array(cog.Analysis).fill("Analysis"), ...Array(cog.Application).fill("Application"), ...Array(cog.Recall).fill("Recall")];
  sorted.forEach((s, i) => {
    s.difficulty = diffList[i];
    s.cognitive_level = cogList[i];
    if (s.marks === 1 && s.difficulty === "Hard") s.difficulty = "Medium";
  });
}

function pickTopics(slots, syllabus, analysis, rand) {
  const used = new Map();
  const bySubject = {};
  for (const s of slots) (bySubject[s.subject_id] ??= []).push(s);
  for (const [sid, list] of Object.entries(bySubject)) {
    const subj = syllabus.subjects.find((x) => x.id === sid);
    if (!subj) continue;
    const allowed = list[0].allowed_topics?.length ? subj.topics.filter((t) => list[0].allowed_topics.includes(t.id)) : subj.topics;
    const topics = [...allowed].sort(
      (a, b) => (analysis?.topics?.[b.id]?.predicted_score ?? rand()) - (analysis?.topics?.[a.id]?.predicted_score ?? rand()),
    );
    list.forEach((slot, i) => {
      const t = topics[i % topics.length];
      // cycle subtopics so no subtopic carries more than 2 questions
      const subs = t.subtopics.filter((st) => (used.get(st) ?? 0) < 2);
      const st = subs.length ? subs[Math.floor(rand() * subs.length)] : t.subtopics[0];
      used.set(st, (used.get(st) ?? 0) + 1);
      slot.topic_id = t.id;
      slot.topic = t.name;
      slot.subtopic = st;
      slot.subject = subj.name;
      delete slot.allowed_topics;
    });
  }
}

function typeCounts(n, typeMix) {
  if (typeMix) return apportion(n, typeMix);
  return apportion(n, { MCQ: 0.58, MSQ: 0.14, NAT: 0.28 });
}

/**
 * Build the skeleton for a request.
 * @param {object} req  {mode, subjects?, topics?, question_count?, type_mix?, difficulty?, weak_topics?, seed?}
 */
export function buildSkeleton(req, syllabus, analysis) {
  const rand = rng(req.seed ?? hash(JSON.stringify(req)));
  const mode = req.mode ?? "full_mock";
  let slots = [];

  if (mode === "full_mock") {
    const marks = allocateTechMarks(analysis?.subject_priority);
    const { one, two } = splitMarks(marks, FULL_PATTERN.tech.one, FULL_PATTERN.tech.two);
    for (const s of TECH_SUBJECTS) {
      for (let i = 0; i < one[s]; i++) slots.push({ section: "Technical", subject_id: s, marks: 1 });
      for (let i = 0; i < two[s]; i++) slots.push({ section: "Technical", subject_id: s, marks: 2 });
    }
    const ga = [];
    for (let i = 0; i < 5; i++) ga.push({ section: "GA", subject_id: "GA", marks: 1 });
    for (let i = 0; i < 5; i++) ga.push({ section: "GA", subject_id: "GA", marks: 2 });
    // GA is mostly MCQ in GATE; 2 NAT keep the overall NAT share near 28%.
    assignTypes(ga, { MCQ: 8, MSQ: 0, NAT: 2 }, rand);
    assignTypes(slots, { MCQ: 30, MSQ: 9, NAT: 16 }, rand);
    slots = [...ga, ...slots];
  } else {
    const d = MODE_DEFAULTS[mode] ?? MODE_DEFAULTS.custom;
    const n = clampInt(req.question_count ?? d.questions, 1, 65);
    const nTwo = d.two != null && n === d.questions ? d.two : Math.round(n * 0.55);
    const subjects = resolveSubjects(req, syllabus);
    const weights = Object.fromEntries(subjects.map((s) => [s, WEIGHTAGE[s] ? (WEIGHTAGE[s][0] + WEIGHTAGE[s][1]) / 2 : 6]));
    const perSubject = apportion(n, weights);
    let idx = 0;
    for (const s of subjects) {
      for (let i = 0; i < perSubject[s]; i++, idx++) {
        slots.push({
          section: s === "GA" ? "GA" : "Technical",
          subject_id: s,
          marks: 1,
          allowed_topics: topicsFor(req, s),
        });
      }
    }
    // choose which slots are 2-markers, spread across subjects
    shuffle(slots, rand)
      .slice(0, nTwo)
      .forEach((s) => (s.marks = 2));
    assignTypes(slots, typeCounts(n, req.type_mix), rand);
  }

  assignLevels(slots, rand);
  if (req.difficulty && req.difficulty !== "Mixed") {
    for (const s of slots) s.difficulty = req.difficulty;
  }
  pickTopics(slots, syllabus, analysis, rand);
  spreadHard(slots);
  return slots.map((s, i) => ({
    qno: i + 1,
    ...s,
    negative_marks: negativeMarks(s.type, s.marks),
  }));
}

// GA first, then technical; within a section avoid two Hard questions back-to-back.
function spreadHard(slots) {
  for (let i = 1; i < slots.length; i++) {
    if (slots[i].difficulty === "Hard" && slots[i - 1].difficulty === "Hard") {
      const j = slots.findIndex((s, k) => k > i && s.section === slots[i].section && s.difficulty !== "Hard");
      if (j > 0) [slots[i], slots[j]] = [slots[j], slots[i]];
    }
  }
}

function resolveSubjects(req, syllabus) {
  if (req.mode === "weak_area" && req.weak_topics?.length) {
    return [...new Set(req.weak_topics.map((t) => t.split(".")[0]))].filter((s) => SUBJECT_IDS.includes(s));
  }
  if (req.topics?.length) return [...new Set(req.topics.map((t) => t.split(".")[0]))];
  if (req.subjects?.length) return req.subjects.filter((s) => SUBJECT_IDS.includes(s));
  if (req.mode === "daily") return TECH_SUBJECTS;
  return syllabus.subjects.map((s) => s.id);
}

function topicsFor(req, subject) {
  const list = req.mode === "weak_area" ? req.weak_topics : req.topics;
  return (list ?? []).filter((t) => t.startsWith(subject + "."));
}

export function paperTotals(questions) {
  const t = { questions: questions.length, marks: 0, GA: 0, Technical: 0, MCQ: 0, MSQ: 0, NAT: 0, one: 0, two: 0 };
  for (const q of questions) {
    t.marks += q.marks;
    t[q.section] = (t[q.section] ?? 0) + 1;
    t[q.type]++;
    if (q.marks === 1) t.one++;
    else t.two++;
  }
  return t;
}

function clampInt(v, lo, hi) {
  return Math.max(lo, Math.min(hi, Math.round(Number(v) || lo)));
}

export function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}
