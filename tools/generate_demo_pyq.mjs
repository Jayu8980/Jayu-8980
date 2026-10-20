// Generates data/pyq_tags_demo.json: SYNTHETIC PYQ tag metadata for 2010-2026 so the analysis
// screens and the predictor have something to work with out of the box.
// It is NOT real GATE data: replace it with data/pyq_bank.json built from real, tagged previous
// year papers (see docs/PYQ_DATA.md). Both files use the same record format.
//
// Run: node tools/generate_demo_pyq.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const syllabus = JSON.parse(fs.readFileSync(path.join(here, "../data/syllabus.json"), "utf8"));

let seed = 20260101;
const rand = () => {
  seed ^= seed << 13;
  seed ^= seed >>> 17;
  seed ^= seed << 5;
  return ((seed >>> 0) % 1e6) / 1e6;
};
const pickWeighted = (items, w) => {
  const total = w.reduce((a, b) => a + b, 0);
  let r = rand() * total;
  for (let i = 0; i < items.length; i++) if ((r -= w[i]) <= 0) return items[i];
  return items.at(-1);
};

const TECH_MARKS = { MATH: 12, EM: 11, PS: 10, EC: 9, CS: 8, PE: 8, AE: 8, SS: 7, MEAS: 5, EMF: 4 };
// a few deliberate drifts so the trend / gap screens show something
const DRIFT = { "PE.DCDC": 0.12, "PS.LOADFLOW": 0.08, "CS.STATE": 0.1, "AE.CONV": -0.12, "MEAS.BRIDGES": -0.1, "EM.SPIM": -0.08 };
const GAP_AFTER = { "PS.ECONOMIC": 2022, "EM.EMEC": 2021, "SS.Z": 2022 };

const records = [];
for (let year = 2010; year <= 2026; year++) {
  const make = (subject, marks) => {
    const s = syllabus.subjects.find((x) => x.id === subject);
    const topics = s.topics.filter((t) => !(GAP_AFTER[t.id] && year > GAP_AFTER[t.id]));
    const t = pickWeighted(topics, topics.map((tp) => Math.max(0.15, 1 + (DRIFT[tp.id] ?? 0) * (year - 2018))));
    const sub = t.subtopics[Math.floor(rand() * t.subtopics.length)];
    const natShare = year < 2014 ? 0 : year < 2021 ? 0.3 : 0.28;
    const msqShare = year < 2021 ? 0 : 0.13;
    const r = rand();
    const type = r < natShare ? "NAT" : r < natShare + msqShare ? "MSQ" : "MCQ";
    const d = rand();
    records.push({
      id: `DEMO-${year}-${String(records.length + 1).padStart(4, "0")}`,
      year,
      subject_id: subject,
      topic_id: t.id,
      subtopic: sub,
      type,
      marks,
      difficulty: d < 0.3 ? "Easy" : d < 0.75 ? "Medium" : "Hard",
      concept: sub,
    });
  };
  for (let i = 0; i < 5; i++) make("GA", 1);
  for (let i = 0; i < 5; i++) make("GA", 2);
  // 25 one-markers and 30 two-markers spread by approximate weightage with yearly noise
  const subjects = Object.keys(TECH_MARKS);
  const w = subjects.map((s) => TECH_MARKS[s] * (0.8 + 0.4 * rand()));
  for (let i = 0; i < 25; i++) make(pickWeighted(subjects, w), 1);
  for (let i = 0; i < 30; i++) make(pickWeighted(subjects, w), 2);
}

const out = {
  is_demo: true,
  description: "SYNTHETIC demo tags (not real GATE questions). Replace with data/pyq_bank.json built from real tagged PYQs.",
  years: [2010, 2026],
  records,
};
fs.writeFileSync(path.join(here, "../data/pyq_tags_demo.json"), JSON.stringify(out));
console.log(`${records.length} demo records written`);
