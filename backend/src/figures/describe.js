// Code-generated text description of a figure. Solver agents solve from this text (plus the
// spec), not from the setter's prose, so a figure that contradicts the question is caught by
// the answer vote.
import { circuitNodes, elementPins, TWO_TERMINAL } from "./spec.js";
import { fmt, bodeData, seriesPoints } from "./math.js";

const ELEMENT_NAMES = {
  R: "resistor",
  L: "inductor",
  C: "capacitor",
  V: "DC voltage source",
  AC: "AC voltage source",
  I: "current source",
  dep_v: "dependent voltage source",
  dep_i: "dependent current source",
  diode: "diode",
  zener: "Zener diode",
  thyristor: "thyristor (SCR)",
  switch: "switch",
  meter: "meter",
  lamp: "lamp",
};

export function describeFigure(fig) {
  const head = [fig.title, fig.caption].filter(Boolean).join(" - ");
  const body = (() => {
    switch (fig.type) {
      case "plot":
        return describePlot(fig);
      case "bode":
        return describeBode(fig);
      case "pole_zero":
        return describePoleZero(fig);
      case "phasor":
        return describePhasor(fig);
      case "circuit":
        return describeCircuit(fig);
      case "block_diagram":
        return describeBlocks(fig);
      case "bar_chart":
        return describeBar(fig);
      case "pie_chart":
        return describePie(fig);
      case "table":
        return [fig.headers.join(" | "), ...fig.rows.map((r) => r.join(" | "))].join("\n");
      default:
        return "";
    }
  })();
  return `[Figure: ${fig.type}${head ? ` - ${head}` : ""}]\n${body}`.trim();
}

function axis(a = {}, name) {
  const unit = a.unit ? ` (${a.unit})` : "";
  return `${name}-axis: ${a.label ?? name}${unit}${a.scale === "log" ? ", logarithmic" : ""}`;
}

function describePlot(fig) {
  const lines = [axis(fig.x, "x"), axis(fig.y, "y")];
  for (const s of fig.series) {
    const style = s.style === "dashed" ? " (dashed)" : "";
    if (!s.points && s.hide_expression) {
      // the student only sees the curve, so the solvers get read-off points, not the formula
      const pts = seriesPoints({ ...s, samples: 24 }, [fig.x?.min ?? 0, fig.x?.max ?? 1], fig.x?.scale === "log").flat().filter(([, y]) => Number.isFinite(y));
      lines.push(`Curve "${s.label ?? "y"}"${style} passes through approximately: ${pts.map(([x, y]) => `(${fmt(x)}, ${fmt(y)})`).join(", ")}`);
    } else if (s.points) lines.push(`Curve "${s.label ?? "y"}"${style} is piecewise linear through (x, y): ${s.points.map(([x, y]) => `(${fmt(x)}, ${fmt(y)})`).join(", ")}`);
    else {
      const doms = s.domains ?? (s.domain ? [s.domain] : [[fig.x?.min, fig.x?.max]]);
      const vars = s.variables ? `, with ${Object.entries(s.variables).map(([k, v]) => `${k} = ${v}`).join(", ")}` : "";
      lines.push(`Curve "${s.label ?? "y"}"${style}: y = ${s.expression}${vars}, drawn for x in ${doms.map(([a, b]) => `[${fmt(a)}, ${fmt(b)}]`).join(" and ")}; elsewhere not drawn`);
    }
  }
  for (const h of fig.hlines ?? []) lines.push(`Horizontal reference line at y = ${fmt(h.y)}${h.label ? ` labelled "${h.label}"` : ""}`);
  for (const v of fig.vlines ?? []) lines.push(`Vertical reference line at x = ${fmt(v.x)}${v.label ? ` labelled "${v.label}"` : ""}`);
  for (const a of fig.annotations ?? []) lines.push(`Label "${a.text}" at (${fmt(a.x)}, ${fmt(a.y)})`);
  return lines.join("\n");
}

function describeBode(fig) {
  const n = fig.numerator.join(", ");
  const d = fig.denominator.join(", ");
  const { mag, phase } = bodeData(fig);
  const at = (arr, w) => arr.reduce((best, p) => (Math.abs(Math.log(p[0] / w)) < Math.abs(Math.log(best[0] / w)) ? p : best));
  const decades = [];
  for (let w = 10 ** Math.ceil(Math.log10(fig.w_min ?? 0.01)); w <= (fig.w_max ?? 1000); w *= 10) decades.push(w);
  const show = fig.show ?? "both";
  const lines = [`Exact Bode ${show === "both" ? "magnitude and phase" : show} plot of G(s) = ${fig.gain ?? 1} x N(s)/D(s), N coefficients [${n}], D coefficients [${d}] (descending powers of s)${fig.delay ? `, delay e^(-${fig.delay}s)` : ""}`];
  if (fig.hide_function) lines[0] = `Bode ${show} plot of an unknown G(s)`;
  lines.push("Read-off values: " + decades.map((w) => `w=${fmt(w)}: ${at(mag, w)[1].toFixed(1)} dB, ${at(phase, w)[1].toFixed(1)} deg`).join("; "));
  return lines.join("\n");
}

function describePoleZero(fig) {
  const c = ([re, im]) => (im === 0 ? fmt(re) : `${fmt(re)} ${im < 0 ? "-" : "+"} j${fmt(Math.abs(im))}`);
  const plane = fig.plane === "z" ? "z-plane" : "s-plane";
  const lines = [`${plane} pole-zero plot`];
  if (fig.poles?.length) lines.push(`Poles (x): ${fig.poles.map(c).join(", ")}`);
  if (fig.zeros?.length) lines.push(`Zeros (o): ${fig.zeros.map(c).join(", ")}`);
  if (fig.gain != null) lines.push(`Gain constant: ${fig.gain}`);
  if (fig.unit_circle || fig.plane === "z") lines.push("The unit circle is drawn");
  return lines.join("\n");
}

function describePhasor(fig) {
  const lines = ["Phasors (magnitude at angle, angles measured counter-clockwise from the reference axis):"];
  for (const p of fig.phasors) lines.push(`${p.label} = ${fmt(p.magnitude)}${p.unit ? ` ${p.unit}` : ""} at ${fmt(p.angle_deg)} deg${p.from ? ` (drawn from the tip of ${p.from})` : ""}${p.scale && p.scale !== 1 ? ` (drawn ${p.scale}x to scale)` : ""}${p.style === "dashed" ? " (dashed)" : ""}`);
  for (const a of fig.angles ?? []) lines.push(`Angle "${a.label}" marked between ${a.from} and ${a.to}`);
  return lines.join("\n");
}

function describeCircuit(fig) {
  const { nodeOf } = circuitNodes(fig);
  const N = (p) => nodeOf(p)?.name ?? "?";
  const lines = ["Circuit netlist (node 0 = ground/reference):"];
  const k = {};
  for (const el of fig.elements) {
    if (el.kind === "wire") continue;
    k[el.kind] = (k[el.kind] ?? 0) + 1;
    const name = el.name ?? `${el.kind}${k[el.kind]}`;
    const val = el.label ? ` ${el.label}` : "";
    if (TWO_TERMINAL.includes(el.kind)) {
      const a = N(el.from);
      const b = N(el.to);
      const what = ELEMENT_NAMES[el.kind] ?? el.kind;
      if (el.kind === "V" || el.kind === "dep_v") lines.push(`${name}: ${what}${val}, + terminal at node ${b}, - terminal at node ${a}`);
      else if (el.kind === "AC") lines.push(`${name}: ${what}${val} between nodes ${a} and ${b} (reference + at ${b})`);
      else if (el.kind === "I" || el.kind === "dep_i") lines.push(`${name}: ${what}${val}, current flows out of node ${a}, through the source, into node ${b}`);
      else if (["diode", "zener", "thyristor"].includes(el.kind)) lines.push(`${name}: ${what}${val}, anode at node ${a}, cathode at node ${b}`);
      else if (el.kind === "switch") lines.push(`${name}: switch between nodes ${a} and ${b}, ${el.closed ? "closed" : "open"}${val}`);
      else if (el.kind === "meter") lines.push(`${name}: ${el.meter ?? "meter"} between nodes ${a} and ${b}${val}`);
      else lines.push(`${name}: ${what}${val} between nodes ${a} and ${b}`);
    } else if (el.kind === "ground") continue;
    else {
      const pins = elementPins(el)
        .map((p) => `${p.name} -> node ${N(p.at)}`)
        .join(", ");
      lines.push(`${name}: ${el.kind}${val} (${pins})`);
    }
  }
  for (const n of fig.nodes ?? []) if (n.terminal) lines.push(`Terminal ${n.label ?? N(n.at)} is an open external terminal`);
  for (const a of fig.arrows ?? []) lines.push(`Arrow "${a.label}" pointing ${a.dir} near (${a.at})`);
  for (const t of fig.labels ?? []) lines.push(`Label "${t.text}" near (${t.at})`);
  return lines.join("\n");
}

function describeBlocks(fig) {
  const byId = Object.fromEntries(fig.blocks.map((b) => [b.id, b]));
  const name = (id) => {
    const b = byId[id];
    if (!b) return id;
    if (b.shape === "sum") return `summing junction ${id}`;
    if (b.shape === "point") return `take-off point ${id}`;
    if (b.shape && b.shape !== "rect") return `${b.shape} gate ${id}`;
    return `block ${id} [${b.label ?? id}]`;
  };
  const lines = ["Signal flow:"];
  for (const i of fig.inputs ?? []) lines.push(`Input ${i.label ?? ""} enters ${name(i.to)}${i.sign ? ` with sign ${i.sign}` : ""}`);
  for (const l of fig.links ?? []) lines.push(`${name(l.from)} -> ${name(l.to)}${l.sign ? ` (sign ${l.sign} at the junction)` : ""}${l.label ? ` [signal ${l.label}]` : ""}`);
  for (const o of fig.outputs ?? []) lines.push(`Output ${o.label ?? ""} taken from ${name(o.from)}`);
  return lines.join("\n");
}

function describeBar(fig) {
  const unit = fig.y?.unit ? ` (${fig.y.unit})` : "";
  return [`Bar chart${fig.y?.label ? ` of ${fig.y.label}${unit}` : ""}:`, ...fig.categories.map((c, i) => `${c}: ${fig.series.map((s) => `${s.label ? s.label + " = " : ""}${s.values[i]}`).join(", ")}`)].join("\n");
}

function describePie(fig) {
  const total = fig.slices.reduce((a, s) => a + s.value, 0);
  return ["Pie chart:", ...fig.slices.map((s) => `${s.label}: ${s.value} (${((s.value / total) * 100).toFixed(1)}%)`)].join("\n");
}
