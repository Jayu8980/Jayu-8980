// Pre-generates the fixed mock series (20 topic + 10 subject + 10 full tests) with the full
// multi-agent pipeline and writes one JSON paper per test. Copy the output into data/generated/
// before building the APK and the tests ship inside the app, usable offline.
//
//   ANTHROPIC_API_KEY=... node scripts/build-series.js                 # all 40 tests
//   node scripts/build-series.js --only TT01,FM01 --out ../data/generated
import fs from "node:fs/promises";
import path from "node:path";
import { config } from "../src/config.js";
import { loadData } from "../src/server.js";
import { runPipeline } from "../src/pipeline.js";
import { createAnthropicLlm, withAgentErrors } from "../src/llm.js";
import { createMockLlm } from "../src/mockLlm.js";
import { createRunStore, loadGeneratedBank } from "../src/store.js";

const args = process.argv.slice(2);
const opt = (name, d) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : d;
};
const only = opt("only", "")?.split(",").filter(Boolean);
const outDir = path.resolve(opt("out", path.join(config.dataDir, "generated")));

const data = await loadData(config.dataDir);
const series = JSON.parse(await fs.readFile(path.join(config.dataDir, "mock_series.json"), "utf8"));
const llm = config.provider === "anthropic" ? withAgentErrors(createAnthropicLlm()) : createMockLlm();
if (llm.name === "mock") console.warn("No ANTHROPIC_API_KEY: generating MOCK placeholder papers (for testing the app only).");
await fs.mkdir(outDir, { recursive: true });

for (const test of series.tests) {
  if (only?.length && !only.includes(test.id)) continue;
  const target = path.join(outDir, `${test.id}.json`);
  if (!args.includes("--force") && (await fs.stat(target).catch(() => null))) {
    console.log(`${test.id}: exists, skipping (use --force)`);
    continue;
  }
  const jobId = `series-${test.id}-${Date.now().toString(36)}`;
  const generatedBank = await loadGeneratedBank(config.runsDir);
  console.log(`${test.id}: generating "${test.title}"...`);
  const paper = await runPipeline(
    { ...test.request, duration_min: test.duration_min },
    { jobId, llm, config, syllabus: data.syllabus, analysis: data.analysis, pyqBank: data.sample.questions, generatedBank, store: createRunStore(config.runsDir, jobId) },
    (p) => p.stage && process.stdout.write(`  ${p.stage} ${Math.round((p.progress ?? 0) * 100)}%\r`),
  );
  paper.id = test.id;
  paper.series_id = test.id;
  for (const q of paper.questions) q.id = `${test.id}-Q${String(q.qno).padStart(2, "0")}`;
  await fs.writeFile(target, JSON.stringify(paper, null, 2));
  const v = paper.verification_summary;
  console.log(`\n  ${paper.questions.length} questions (${paper.status}); unanimous ${v.unanimous}, adjudicated ${v.adjudicated ?? 0}, corrected ${v.corrected_by_adjudicator ?? 0}, regenerated ${v.rejected_drafts}`);
}
