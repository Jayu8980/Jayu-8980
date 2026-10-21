// Module 1 + Agent steps ANALYSE and PREDICT: build the topic map from the tagged PYQ bank and
// score every topic for "likely in the upcoming GATE". The Android app runs the same maths in
// core/AnalysisEngine.kt for its offline screens.

export function analysePyqs(bank, syllabus, { currentYear = latestYear(bank) + 1 } = {}) {
  const records = bank.records ?? [];
  const years = [...new Set(records.map((r) => r.year))].sort((a, b) => a - b);
  const topicName = {};
  const topicSubject = {};
  for (const s of syllabus.subjects)
    for (const t of s.topics) {
      topicName[t.id] = t.name;
      topicSubject[t.id] = s.id;
    }

  const subjects = {};
  const topics = {};
  const typeSplit = {};
  const difficulty = {};
  const concepts = {};

  for (const r of records) {
    const s = (subjects[r.subject_id] ??= { marks_by_year: {}, questions: 0, marks: 0 });
    s.marks_by_year[r.year] = (s.marks_by_year[r.year] ?? 0) + r.marks;
    s.questions++;
    s.marks += r.marks;

    const t = (topics[r.topic_id] ??= {
      topic_id: r.topic_id,
      name: topicName[r.topic_id] ?? r.topic_id,
      subject_id: r.subject_id,
      frequency: 0,
      marks: 0,
      marks_by_year: {},
      types: { MCQ: 0, MSQ: 0, NAT: 0 },
      difficulty_sum: 0,
      last_asked: 0,
    });
    t.frequency++;
    t.marks += r.marks;
    t.marks_by_year[r.year] = (t.marks_by_year[r.year] ?? 0) + r.marks;
    t.types[r.type] = (t.types[r.type] ?? 0) + 1;
    t.difficulty_sum += { Easy: 1, Medium: 2, Hard: 3 }[r.difficulty] ?? 2;
    t.last_asked = Math.max(t.last_asked, r.year);

    const ts = (typeSplit[r.year] ??= { MCQ: 0, MSQ: 0, NAT: 0 });
    ts[r.type] = (ts[r.type] ?? 0) + 1;
    const ds = (difficulty[r.year] ??= { Easy: 0, Medium: 0, Hard: 0 });
    ds[r.difficulty] = (ds[r.difficulty] ?? 0) + 1;
    if (r.concept) {
      const c = (concepts[r.concept] ??= { concept: r.concept, topic_id: r.topic_id, count: 0, years: [] });
      c.count++;
      c.years.push(r.year);
    }
  }

  const last5 = years.slice(-5);
  const last10 = years.slice(-10);
  for (const s of Object.values(subjects)) {
    s.avg_5yr = avg(last5.map((y) => s.marks_by_year[y] ?? 0));
    s.avg_10yr = avg(last10.map((y) => s.marks_by_year[y] ?? 0));
  }

  // importance is relative to the strongest topic of the same subject
  const maxBySubject = {};
  for (const t of Object.values(topics)) maxBySubject[t.subject_id] = Math.max(maxBySubject[t.subject_id] ?? 1, t.marks);
  for (const t of Object.values(topics)) {
    const maxImportance = maxBySubject[t.subject_id];
    const recent = years.slice(-8);
    t.trend_slope = slope(recent, recent.map((y) => t.marks_by_year[y] ?? 0));
    t.trend = t.trend_slope > 0.15 ? "rising" : t.trend_slope < -0.15 ? "falling" : "stable";
    t.avg_difficulty = t.difficulty_sum / t.frequency;
    delete t.difficulty_sum;
    t.priority = t.marks * t.frequency; // "High-Priority Topics" = marks x frequency
    const gapYears = currentYear - t.last_asked;
    t.is_gap = t.marks / maxImportance >= 0.35 && gapYears >= 3;
    // PREDICT: weighted blend of importance, trend, gap and recency
    const importance = t.marks / maxImportance;
    const trend = clamp(0.5 + t.trend_slope / 2, 0, 1);
    const gap = t.is_gap ? 1 : 0;
    const recency = clamp(1 - (gapYears - 1) / 8, 0, 1);
    t.predicted_score = round(0.45 * importance + 0.2 * trend + 0.2 * gap + 0.15 * recency, 3);
  }

  // topics in the syllabus that never appear still exist in the map with zero history
  for (const [id, name] of Object.entries(topicName)) {
    topics[id] ??= {
      topic_id: id,
      name,
      subject_id: topicSubject[id],
      frequency: 0,
      marks: 0,
      marks_by_year: {},
      types: { MCQ: 0, MSQ: 0, NAT: 0 },
      last_asked: null,
      trend: "new",
      trend_slope: 0,
      priority: 0,
      is_gap: false,
      predicted_score: 0.1,
    };
  }

  const subject_priority = {};
  for (const t of Object.values(topics)) subject_priority[t.subject_id] = (subject_priority[t.subject_id] ?? 0) + t.predicted_score;

  // GA is excluded from the revision-priority lists: it has few, always-present topics
  const ranked = Object.values(topics)
    .filter((t) => t.subject_id !== "GA")
    .sort((a, b) => b.priority - a.priority);
  return {
    is_demo: !!bank.is_demo,
    years,
    subjects,
    topics,
    type_split_by_year: typeSplit,
    difficulty_by_year: difficulty,
    most_repeated_concepts: Object.values(concepts)
      .sort((a, b) => b.count - a.count)
      .slice(0, 25),
    high_priority_topics: ranked.slice(0, 20).map(pick),
    gap_topics: ranked.filter((t) => t.is_gap).map(pick),
    rising_topics: ranked.filter((t) => t.trend === "rising").map(pick),
    falling_topics: ranked.filter((t) => t.trend === "falling").map(pick),
    subject_priority,
  };
}

/** Compact version sent to the LLM agents (keeps prompts small). */
export function analysisForPrompt(a) {
  const topics = Object.values(a.topics)
    .sort((x, y) => y.predicted_score - x.predicted_score)
    .map((t) => ({
      topic: t.topic_id,
      freq: t.frequency,
      marks: t.marks,
      trend: t.trend,
      last_asked: t.last_asked,
      gap: t.is_gap,
      score: t.predicted_score,
    }));
  return { data_is_demo: a.is_demo, topics, gap_topics: a.gap_topics.map((t) => t.topic_id), rising: a.rising_topics.map((t) => t.topic_id) };
}

function pick(t) {
  return {
    topic_id: t.topic_id,
    name: t.name,
    subject_id: t.subject_id,
    frequency: t.frequency,
    marks: t.marks,
    priority: t.priority,
    trend: t.trend,
    last_asked: t.last_asked,
    predicted_score: t.predicted_score,
  };
}

function latestYear(bank) {
  return Math.max(2025, ...(bank.records ?? []).map((r) => r.year));
}
function avg(xs) {
  return xs.length ? round(xs.reduce((a, b) => a + b, 0) / xs.length, 2) : 0;
}
function slope(xs, ys) {
  const n = xs.length;
  if (n < 2) return 0;
  const mx = avg(xs);
  const my = ys.reduce((a, b) => a + b, 0) / n;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i++) {
    num += (xs[i] - mx) * (ys[i] - my);
    den += (xs[i] - mx) ** 2;
  }
  return den ? num / den : 0;
}
function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}
function round(v, d) {
  const f = 10 ** d;
  return Math.round(v * f) / f;
}
