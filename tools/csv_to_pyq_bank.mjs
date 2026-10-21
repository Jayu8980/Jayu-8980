// Converts a spreadsheet of tagged GATE EE previous-year questions into data/pyq_bank.json.
//
// CSV header (one row per question; question/options/answer/solution may be empty for tag-only rows):
//   id,year,subject_id,topic_id,subtopic,type,marks,difficulty,concept,question,option_a,option_b,option_c,option_d,answer,answer_min,answer_max,solution
//
// Run: node tools/csv_to_pyq_bank.mjs my_pyqs.csv
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const file = process.argv[2];
if (!file) {
  console.error("usage: node tools/csv_to_pyq_bank.mjs <file.csv>");
  process.exit(1);
}

// RFC 4180 CSV parser (quoted fields, embedded commas, quotes and newlines)
function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += c;
  }
  if (field || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => r.some((f) => f.trim()));
}

const syllabus = JSON.parse(fs.readFileSync(path.join(here, "../data/syllabus.json"), "utf8"));
const topicIds = new Set(syllabus.subjects.flatMap((s) => s.topics.map((t) => t.id)));
const [header, ...rows] = parseCsv(fs.readFileSync(file, "utf8"));
const col = Object.fromEntries(header.map((h, i) => [h.trim(), i]));
const get = (r, k) => (col[k] != null ? (r[col[k]] ?? "").trim() : "");

const errors = [];
const records = rows.map((r, n) => {
  const rec = {
    id: get(r, "id") || `PYQ-${get(r, "year")}-${String(n + 1).padStart(3, "0")}`,
    year: Number(get(r, "year")),
    subject_id: get(r, "subject_id"),
    topic_id: get(r, "topic_id"),
    subtopic: get(r, "subtopic"),
    type: get(r, "type").toUpperCase(),
    marks: Number(get(r, "marks")),
    difficulty: get(r, "difficulty") || "Medium",
    concept: get(r, "concept"),
  };
  if (!topicIds.has(rec.topic_id)) errors.push(`row ${n + 2}: unknown topic_id '${rec.topic_id}'`);
  if (!["MCQ", "MSQ", "NAT"].includes(rec.type)) errors.push(`row ${n + 2}: type must be MCQ/MSQ/NAT`);
  if (![1, 2].includes(rec.marks)) errors.push(`row ${n + 2}: marks must be 1 or 2`);
  const q = get(r, "question");
  if (q) {
    rec.question = q;
    rec.options = ["option_a", "option_b", "option_c", "option_d"].map((k) => get(r, k)).filter(Boolean);
    rec.answer = get(r, "answer");
    if (rec.type === "NAT" && get(r, "answer_min")) rec.answer_range = { min: Number(get(r, "answer_min")), max: Number(get(r, "answer_max")) };
    rec.solution = get(r, "solution");
  }
  return rec;
});

if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
const out = path.join(here, "../data/pyq_bank.json");
fs.writeFileSync(out, JSON.stringify({ is_demo: false, description: "Tagged GATE EE previous year questions", records }, null, 1));
console.log(`${records.length} records -> ${out} (${records.filter((r) => r.question).length} with full text)`);
