import { test } from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import { runPipeline } from "../src/pipeline.js";
import { createMockLlm } from "../src/mockLlm.js";
import { config } from "../src/config.js";
import { loadData, createApp } from "../src/server.js";
import { createRunStore } from "../src/store.js";

const data = await loadData(new URL("../../data", import.meta.url).pathname);
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "gate-runs-"));
const cfg = { ...config, runsDir: tmp, clientToken: "" };

function ctx(llm, jobId) {
  return { jobId, llm, config: cfg, syllabus: data.syllabus, analysis: data.analysis, pyqBank: [], generatedBank: [], store: createRunStore(tmp, jobId) };
}

test("full mock: every question verified unanimously, pattern intact", async () => {
  const paper = await runPipeline({ mode: "full_mock", seed: 1 }, ctx(createMockLlm(), "t-full"));
  assert.equal(paper.status, "complete");
  assert.equal(paper.questions.length, 65);
  assert.equal(paper.paper_report.total_marks, 100);
  assert.deepEqual(paper.paper_report.pattern_issues, []);
  assert.equal(paper.verification_summary.unanimous, 65);
  for (const q of paper.questions) {
    assert.equal(q.verification_report.support, "3/3");
    if (q.type === "NAT") assert.equal(q.verification_report.code_check, "pass");
  }
  assert.ok(fs.existsSync(path.join(tmp, "t-full", "final_paper.json")));
  assert.ok(fs.readdirSync(path.join(tmp, "t-full")).some((f) => f.includes("solver_2")));
});

test("a dissenting solver sends questions to the adjudicator", async () => {
  const paper = await runPipeline({ mode: "daily", seed: 3 }, ctx(createMockLlm({ faultySolver: true }), "t-faulty"));
  assert.equal(paper.questions.length, 12);
  const adjudicated = paper.questions.filter((q) => q.verification_report.method === "adjudicated");
  assert.ok(adjudicated.length > 0);
  for (const q of adjudicated) assert.equal(q.verification_report.support, "3/4");
});

test("moderator rejection triggers regeneration of that slot", async () => {
  const paper = await runPipeline({ mode: "topic", topics: ["EM.IM"], seed: 5 }, ctx(createMockLlm({ rejectQnos: [2, 5] }), "t-reject"));
  assert.equal(paper.status, "complete");
  assert.equal(paper.questions.length, 15);
  assert.equal(paper.verification_summary.rejected_drafts, 2);
  assert.ok(paper.questions.find((q) => q.qno === 2).question.includes("regen1"));
  assert.ok(paper.questions.every((q) => q.subject_id === "EM"));
});

test("HTTP API: generate, poll, fetch paper, report", async () => {
  const app = createApp({ llm: createMockLlm(), data, cfg });
  const server = app.listen(0);
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const bad = await fetch(`${base}/api/generate`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ mode: "nope" }) });
    assert.equal(bad.status, 400);
    const r = await fetch(`${base}/api/generate`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ mode: "custom", subjects: ["PE", "CS"], question_count: 6 }) });
    assert.equal(r.status, 202);
    const { job_id } = await r.json();
    let job;
    for (let i = 0; i < 100; i++) {
      job = await (await fetch(`${base}/api/jobs/${job_id}`)).json();
      if (job.status !== "running") break;
      await new Promise((res) => setTimeout(res, 20));
    }
    assert.equal(job.status, "done");
    const paper = await (await fetch(`${base}/api/jobs/${job_id}/paper`)).json();
    assert.equal(paper.questions.length, 6);
    assert.ok(paper.questions.every((q) => ["PE", "CS"].includes(q.subject_id)));
    const rep = await fetch(`${base}/api/report-question`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ question_id: paper.questions[0].id, reason: "wrong" }) });
    assert.equal(rep.status, 200);
    const analysis = await (await fetch(`${base}/api/analysis`)).json();
    assert.equal(analysis.is_demo, true);
  } finally {
    server.close();
  }
});
