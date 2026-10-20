// HTTP API used by the Android app. The LLM API key lives only in this server's environment.
import express from "express";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { config } from "./config.js";
import { createAnthropicLlm, withAgentErrors } from "./llm.js";
import { createMockLlm } from "./mockLlm.js";
import { analysePyqs } from "./analysis.js";
import { runPipeline } from "./pipeline.js";
import { createRunStore, loadJson, loadGeneratedBank } from "./store.js";
import { SUBJECT_IDS } from "./pattern.js";

export async function loadData(dataDir) {
  const syllabus = await loadJson(path.join(dataDir, "syllabus.json"), null);
  if (!syllabus) throw new Error(`syllabus.json not found in ${dataDir}`);
  const bank = (await loadJson(path.join(dataDir, "pyq_bank.json"), null)) ?? (await loadJson(path.join(dataDir, "pyq_tags_demo.json"), { is_demo: true, records: [] }));
  const sample = await loadJson(path.join(dataDir, "sample_questions.json"), { questions: [] });
  return { syllabus, bank, sample, analysis: analysePyqs(bank, syllabus) };
}

const MODES = ["full_mock", "subject", "topic", "weak_area", "daily", "custom"];

export function validateRequest(body) {
  const r = { ...body };
  if (!MODES.includes(r.mode)) return { error: `mode must be one of ${MODES.join(", ")}` };
  if (r.subjects && (!Array.isArray(r.subjects) || r.subjects.some((s) => !SUBJECT_IDS.includes(s)))) return { error: "unknown subject id" };
  if (r.question_count != null && !(r.question_count >= 1 && r.question_count <= 65)) return { error: "question_count must be 1-65" };
  if (r.mode === "weak_area" && !r.weak_topics?.length) return { error: "weak_area mode needs weak_topics" };
  if (r.mode === "topic" && !r.topics?.length) return { error: "topic mode needs topics" };
  if (r.mode === "subject" && !r.subjects?.length) return { error: "subject mode needs subjects" };
  for (const k of ["topics", "weak_topics", "avoid_summaries"]) if (r[k] != null && !Array.isArray(r[k])) return { error: `${k} must be an array` };
  if (r.avoid_summaries) r.avoid_summaries = r.avoid_summaries.slice(-200).map((s) => String(s).slice(0, 300));
  return { request: r };
}

export function createApp({ llm, data, cfg = config }) {
  const app = express();
  app.use(express.json({ limit: "1mb" }));
  const jobs = new Map();
  const hits = new Map();

  app.get("/health", (_req, res) => res.json({ ok: true, provider: llm.name, pyq_data_is_demo: data.analysis.is_demo }));

  // simple shared-secret auth so only your app build can spend your API budget
  app.use("/api", (req, res, next) => {
    if (cfg.clientToken && req.get("authorization") !== `Bearer ${cfg.clientToken}`) return res.status(401).json({ error: "unauthorized" });
    next();
  });

  app.get("/api/analysis", (_req, res) => res.json(data.analysis));

  app.post("/api/generate", async (req, res) => {
    const key = req.get("authorization") ?? req.ip;
    const now = Date.now();
    const recent = (hits.get(key) ?? []).filter((t) => now - t < 3600_000);
    if (recent.length >= cfg.rateLimitPerHour) return res.status(429).json({ error: "rate limit: too many papers this hour" });
    hits.set(key, [...recent, now]);

    const { request, error } = validateRequest(req.body ?? {});
    if (error) return res.status(400).json({ error });
    const jobId = `${request.mode}-${now.toString(36)}-${crypto.randomBytes(3).toString("hex")}`;
    const job = { id: jobId, status: "running", stage: "queued", progress: 0, log: [], created_at: new Date().toISOString() };
    jobs.set(jobId, job);
    res.status(202).json({ job_id: jobId });

    try {
      const generatedBank = await loadGeneratedBank(cfg.runsDir);
      const pyqText = (data.bank.records ?? []).filter((r) => r.question).map((r) => ({ id: r.id, question: r.question }));
      const paper = await runPipeline(
        request,
        {
          jobId,
          llm,
          config: cfg,
          syllabus: data.syllabus,
          analysis: data.analysis,
          pyqBank: [...pyqText, ...data.sample.questions.map((q) => ({ id: q.id, question: q.question }))],
          generatedBank,
          store: createRunStore(cfg.runsDir, jobId),
        },
        (p) => {
          if (p.stage) job.stage = p.stage;
          if (p.progress != null) job.progress = Math.round(p.progress * 100) / 100;
          if (p.log) job.log.push(p.log);
        },
      );
      job.status = paper.questions.length ? "done" : "failed";
      job.paper = paper;
      if (!paper.questions.length) job.error = "no question passed verification";
    } catch (e) {
      job.status = "failed";
      job.error = e.message;
    }
  });

  app.get("/api/jobs/:id", (req, res) => {
    const job = jobs.get(req.params.id);
    if (!job) return res.status(404).json({ error: "unknown job" });
    const { paper, ...status } = job;
    res.json({ ...status, question_count: paper?.questions.length ?? 0 });
  });

  app.get("/api/jobs/:id/paper", async (req, res) => {
    const job = jobs.get(req.params.id);
    const paper = job?.paper ?? (await loadJson(path.join(cfg.runsDir, path.basename(req.params.id), "final_paper.json"), null));
    if (!paper) return res.status(404).json({ error: "paper not ready" });
    res.json(paper);
  });

  // "Report wrong question": the question is blocked from the generated bank on the server too
  app.post("/api/report-question", async (req, res) => {
    const { question_id, reason } = req.body ?? {};
    if (!question_id) return res.status(400).json({ error: "question_id required" });
    const file = path.join(cfg.runsDir, "reported.json");
    const list = await loadJson(file, []);
    list.push({ question_id: String(question_id).slice(0, 120), reason: String(reason ?? "").slice(0, 500), at: new Date().toISOString() });
    await fs.mkdir(cfg.runsDir, { recursive: true });
    await fs.writeFile(file, JSON.stringify(list, null, 2));
    res.json({ ok: true });
  });

  return app;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const data = await loadData(config.dataDir);
  const llm = config.provider === "anthropic" ? withAgentErrors(createAnthropicLlm()) : createMockLlm();
  createApp({ llm, data }).listen(config.port, () => {
    console.log(`GATE EE backend on :${config.port} (provider: ${llm.name}${data.analysis.is_demo ? ", DEMO PYQ data" : ""})`);
    if (llm.name === "mock") console.log("No ANTHROPIC_API_KEY set: running the offline mock generator.");
  });
}
