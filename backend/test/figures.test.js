import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { figureErrors, describeFigure, layoutFigure, prepareFigure, toSvg } from "../src/figures/index.js";
import { bodeData } from "../src/figures/math.js";
import { buildSkeleton } from "../src/pattern.js";
import { schemaErrors } from "../src/validation.js";
import { solverView } from "../src/prompts.js";
import { runPipeline } from "../src/pipeline.js";
import { createMockLlm } from "../src/mockLlm.js";
import { config } from "../src/config.js";
import { loadData } from "../src/server.js";

const fixtures = JSON.parse(fs.readFileSync(new URL("./fixtures/figures.json", import.meta.url), "utf8"));
const data = await loadData(new URL("../../data", import.meta.url).pathname);
const sampleFigures = data.sample.questions.filter((q) => q.figure);

test("every figure type validates, lays out and renders to SVG", () => {
  const types = new Set();
  for (const [name, fig] of [...Object.entries(fixtures), ...sampleFigures.map((q) => [q.id, q.figure])]) {
    if (name === "opamp_inverting") continue; // deliberately invalid
    assert.deepEqual(figureErrors(fig), [], name);
    const r = layoutFigure(fig);
    assert.ok(r.w > 0 && r.h > 0 && r.items.length > 5, name);
    for (const it of r.items) {
      const nums = it.k === "path" ? it.p : it.k === "circle" ? [...it.c, it.r] : it.p;
      assert.ok(nums.every(Number.isFinite), `${name}: non-finite coordinate in ${it.k}`);
    }
    assert.match(toSvg(r, "dark"), /^<svg[\s\S]*<\/svg>$/);
    assert.ok(describeFigure(fig).length > 20, name);
    types.add(fig.type);
  }
  assert.deepEqual([...types].sort(), ["bar_chart", "block_diagram", "bode", "circuit", "phasor", "pie_chart", "plot", "pole_zero", "table"]);
});

test("invalid figures are rejected with useful messages", () => {
  assert.match(figureErrors(fixtures.opamp_inverting).join(), /horizontal or vertical/);
  const circuit = structuredClone(sampleFigures.find((q) => q.figure.type === "circuit").figure);
  circuit.elements = circuit.elements.filter((e) => !(e.kind === "wire" && e.from[1] === 2)); // remove the bottom return wire
  assert.match(figureErrors(circuit).join(), /dangling/);
  assert.match(figureErrors({ type: "hologram" }).join(), /unknown figure type/);
  assert.match(figureErrors({ type: "phasor", phasors: [{ label: "V", magnitude: 1, angle_deg: 0, from: "X" }] }).join(), /unknown phasor/);
  assert.match(figureErrors({ type: "bar_chart", categories: ["a", "b"], series: [{ label: "s", values: [1] }] }).join(), /2 numeric values/);
  assert.match(figureErrors({ type: "block_diagram", blocks: [{ id: "A", at: [0, 0] }], links: [{ from: "A", to: "B" }] }).join(), /unknown block/);
});

test("circuit description is a correct netlist", () => {
  const d = describeFigure(sampleFigures.find((q) => q.figure.type === "circuit").figure);
  assert.match(d, /DC voltage source 12 V, \+ terminal at node n1, - terminal at node 0/);
  assert.match(d, /resistor 4 Ω between nodes n1 and a/);
  assert.match(d, /resistor 6 Ω between nodes a and 0/);
  assert.match(d, /resistor 12 Ω between nodes a and 0/);
});

test("bode data: magnitude and unwrapped phase", () => {
  const { mag, phase } = bodeData({ numerator: [100], denominator: [1, 10, 0], w_min: 0.1, w_max: 1000 });
  assert.ok(Math.abs(mag[0][1] - 40) < 0.1); // 10/(s) at w = 0.1 -> 40 dB
  assert.ok(Math.abs(phase[0][1] + 90.6) < 0.2);
  assert.ok(Math.abs(phase.at(-1)[1] + 179.4) < 0.2);
  const triple = bodeData({ numerator: [1], denominator: [1, 0, 0, 0] });
  assert.ok(Math.abs(triple.phase[0][1] + 270) < 1e-6, "1/s^3 starts at -270, not +90");
});

test("prepared bank figures are up to date with the layout code", () => {
  for (const q of sampleFigures) {
    const again = prepareFigure(q.figure);
    assert.deepEqual(again.render, q.figure.render, `${q.id}: run node tools/build_figures.mjs`);
    assert.equal(again.description, q.figure.description);
  }
});

test("blueprint marks figure slots; validation and solver view handle them", () => {
  const sk = buildSkeleton({ mode: "full_mock", seed: 3 }, data.syllabus, data.analysis);
  const figs = sk.filter((s) => s.figure);
  assert.equal(figs.length, 20);
  assert.ok(new Set(figs.map((s) => s.subject_id)).size >= 8, "figures spread over subjects");
  assert.ok(figs.filter((s) => s.subject_id === "GA").every((s) => ["bar_chart", "pie_chart", "table"].includes(s.figure)));
  assert.equal(buildSkeleton({ mode: "daily", figures: "none" }, data.syllabus, data.analysis).filter((s) => s.figure).length, 0);

  const q = structuredClone(sampleFigures[0]);
  const slot = { type: q.type, marks: q.marks, figure: "plot" };
  assert.deepEqual(schemaErrors(q, slot), []);
  assert.match(schemaErrors({ ...q, figure: null }, slot).join(), /needs a figure/);
  const view = solverView(q);
  assert.match(view.figure, /piecewise linear through/);
  assert.equal(view.answer, undefined);
  assert.ok(!JSON.stringify(view).includes('"render"'));
});

test("pipeline produces drawable figures for figure slots", async () => {
  const cfg = { ...config, runsDir: fs.mkdtempSync("/tmp/gate-fig-") };
  const paper = await runPipeline({ mode: "custom", subjects: ["CS", "EC", "PE"], question_count: 10, figures: "many", seed: 9 }, { jobId: "fig", llm: createMockLlm(), config: cfg, syllabus: data.syllabus, analysis: data.analysis });
  assert.equal(paper.status, "complete");
  const withFig = paper.questions.filter((q) => q.figure);
  assert.equal(withFig.length, 6);
  for (const q of withFig) {
    assert.ok(q.figure.render.items.length > 0);
    assert.match(q.figure.description, /netlist/);
  }
  assert.equal(paper.paper_report.figures, 6);
  assert.equal(paper.blueprint.filter((b) => b.figure).length, 6);
});
