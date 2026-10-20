// Saves every agent call and pipeline step as JSON under runs/<jobId>/ (the audit trail).
import fs from "node:fs/promises";
import path from "node:path";

export function createRunStore(runsDir, jobId) {
  const dir = path.join(runsDir, jobId);
  let n = 0;
  const write = async (name, data) => {
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(path.join(dir, `${name}.json`), JSON.stringify(data, null, 2));
  };
  return {
    dir,
    save: write,
    saveCall: (agent, data) => write(`call_${String(++n).padStart(3, "0")}_${agent.replace(/[^a-z0-9]+/gi, "_")}`, data),
  };
}

export async function loadJson(file, fallback) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch {
    return fallback;
  }
}

/** All questions generated earlier (for the duplicate check and the "avoid" list). */
export async function loadGeneratedBank(runsDir) {
  const out = [];
  let entries = [];
  try {
    entries = await fs.readdir(runsDir, { withFileTypes: true });
  } catch {
    return out;
  }
  const blocked = new Set((await loadJson(path.join(runsDir, "reported.json"), [])).map((r) => r.question_id));
  for (const e of entries) {
    if (!e.isDirectory()) continue;
    const paper = await loadJson(path.join(runsDir, e.name, "final_paper.json"), null);
    for (const q of paper?.questions ?? []) if (!blocked.has(q.id)) out.push({ id: q.id, question: q.question });
  }
  return out;
}
