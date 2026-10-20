// Attaches the code-generated `description` and `render` display list to every question
// figure in the bundled banks, so the app can draw figures offline.
// Run after editing figures: node tools/build_figures.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { prepareFigure } from "../backend/src/figures/index.js";

const data = path.join(path.dirname(fileURLToPath(import.meta.url)), "../data");
const files = ["sample_questions.json", "pyq_bank.json", ...(fs.existsSync(path.join(data, "generated")) ? fs.readdirSync(path.join(data, "generated")).map((f) => `generated/${f}`) : [])];
let total = 0;
let failed = 0;
for (const f of files) {
  const file = path.join(data, f);
  if (!fs.existsSync(file) || !f.endsWith(".json")) continue;
  const json = JSON.parse(fs.readFileSync(file, "utf8"));
  const list = json.questions ?? json.records ?? [];
  let n = 0;
  for (const q of list) {
    if (!q.figure) continue;
    try {
      q.figure = prepareFigure(q.figure);
      n++;
    } catch (e) {
      failed++;
      console.error(`${f} ${q.id}: ${e.message}`);
    }
  }
  if (n) fs.writeFileSync(file, JSON.stringify(json, null, 2));
  total += n;
  if (n) console.log(`${f}: ${n} figure(s)`);
}
console.log(`${total} figures prepared${failed ? `, ${failed} FAILED` : ""}`);
process.exit(failed ? 1 : 0);
