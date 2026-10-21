// Figure specification: the JSON "figure" object a question may carry.
// Agents write figures in this small declarative language instead of images, so code can
// validate them, describe them in text for the solver agents, and lay them out for the app.
//
// Types: plot (graphs, waveforms, characteristics), bode, pole_zero, phasor, circuit,
// block_diagram (control systems and logic gates), bar_chart, pie_chart, table.

export const FIGURE_TYPES = ["plot", "bode", "pole_zero", "phasor", "circuit", "block_diagram", "bar_chart", "pie_chart", "table"];

export const TWO_TERMINAL = ["wire", "R", "L", "C", "V", "I", "AC", "dep_v", "dep_i", "diode", "zener", "thyristor", "switch", "meter", "lamp"];
export const MULTI_PIN = {
  // pins relative to "at"; y grows downward
  ground: { at: [0, 0] },
  opamp: { minus: [-1, -0.5], plus: [-1, 0.5], out: [1, 0] },
  transformer: { p1: [-0.5, -1], p2: [-0.5, 1], s1: [0.5, -1], s2: [0.5, 1] },
  npn: { base: [-1, 0], collector: [0, -1], emitter: [0, 1] },
  nmos: { gate: [-1, 0], drain: [0, -1], source: [0, 1] },
};
export const BLOCK_SHAPES = ["rect", "sum", "point", "AND", "OR", "NOT", "NAND", "NOR", "XOR", "XNOR"];

const isNum = (v) => typeof v === "number" && Number.isFinite(v);
const isPt = (p) => Array.isArray(p) && p.length === 2 && isNum(p[0]) && isNum(p[1]);
const key = (p) => `${round(p[0])},${round(p[1])}`;
const round = (v) => Math.round(v * 1000) / 1000;

/** Pins of a circuit element as [{name, at:[x,y]}]. */
export function elementPins(el) {
  if (TWO_TERMINAL.includes(el.kind)) return [
    { name: "from", at: el.from },
    { name: "to", at: el.to },
  ];
  const def = MULTI_PIN[el.kind];
  if (!def || !isPt(el.at)) return [];
  const flip = el.flip ? -1 : 1; // flip mirrors vertically (opamp: + on top)
  return Object.entries(def).map(([name, [dx, dy]]) => ({ name, at: [el.at[0] + dx, el.at[1] + dy * flip] }));
}

function onSegment(p, a, b) {
  const eps = 1e-6;
  if (Math.abs(a[0] - b[0]) < eps) return Math.abs(p[0] - a[0]) < eps && p[1] > Math.min(a[1], b[1]) + eps && p[1] < Math.max(a[1], b[1]) - eps;
  if (Math.abs(a[1] - b[1]) < eps) return Math.abs(p[1] - a[1]) < eps && p[0] > Math.min(a[0], b[0]) + eps && p[0] < Math.max(a[0], b[0]) - eps;
  return false;
}

/**
 * Electrical nodes of a circuit: wires merge their end points, a pin landing in the middle of a
 * wire joins it (T-junction). Returns {nodeOf(point) -> node id, nodes: Map(id -> {points, pins, label, ground})}.
 */
export function circuitNodes(fig) {
  const parent = new Map();
  const find = (k) => {
    if (!parent.has(k)) parent.set(k, k);
    let r = k;
    while (parent.get(r) !== r) r = parent.get(r);
    parent.set(k, r);
    return r;
  };
  const union = (a, b) => parent.set(find(a), find(b));
  const els = fig.elements ?? [];
  const wires = els.filter((e) => e.kind === "wire" && isPt(e.from) && isPt(e.to));
  const pins = [];
  for (const el of els) for (const p of elementPins(el)) if (isPt(p.at)) pins.push({ el, ...p });
  for (const p of pins) find(key(p.at));
  for (const w of wires) union(key(w.from), key(w.to));
  for (const p of pins) for (const w of wires) if (onSegment(p.at, w.from, w.to)) union(key(p.at), key(w.from));
  for (const n of fig.nodes ?? []) if (isPt(n.at)) for (const w of wires) if (onSegment(n.at, w.from, w.to)) union(key(n.at), key(w.from));

  const nodes = new Map();
  for (const p of pins) {
    const id = find(key(p.at));
    if (!nodes.has(id)) nodes.set(id, { id, pins: [], degree: 0, label: null, ground: false, terminal: false });
    const n = nodes.get(id);
    n.degree++; // pins and wire ends both count as connections
    if (p.el.kind !== "wire") n.pins.push(p);
    if (p.el.kind === "ground") n.ground = true;
  }
  for (const lab of fig.nodes ?? []) {
    if (!isPt(lab.at)) continue;
    const id = find(key(lab.at));
    const n = nodes.get(id);
    if (n) {
      if (lab.label) n.label = String(lab.label);
      if (lab.terminal) n.terminal = true;
    }
  }
  // stable names: ground -> 0, labelled nodes keep labels, others n1, n2, ...
  let k = 1;
  for (const n of nodes.values()) n.name = n.ground ? "0" : n.label ?? `n${k++}`;
  return { nodeOf: (p) => nodes.get(find(key(p))), nodes };
}

/** Returns a list of problems with a figure spec (empty = valid). */
export function figureErrors(fig) {
  const e = [];
  if (!fig || typeof fig !== "object") return ["figure must be an object"];
  if (!FIGURE_TYPES.includes(fig.type)) return [`unknown figure type '${fig.type}'`];
  const tooBig = JSON.stringify(fig).length > 20000;
  if (tooBig) e.push("figure spec is too large");

  switch (fig.type) {
    case "plot": {
      if (!Array.isArray(fig.series) || !fig.series.length) e.push("plot needs at least one series");
      for (const [i, s] of (fig.series ?? []).entries()) {
        if (s.points) {
          if (!Array.isArray(s.points) || s.points.length < 2 || !s.points.every(isPt)) e.push(`series ${i}: points must be [[x,y],...] with at least 2 points`);
        } else if (typeof s.expression !== "string") e.push(`series ${i}: needs points or an expression in x`);
        else {
          const doms = s.domains ?? (s.domain ? [s.domain] : null);
          if (doms && !doms.every((d) => isPt(d) && d[1] > d[0])) e.push(`series ${i}: domain must be [min,max]`);
        }
      }
      for (const ax of ["x", "y"]) {
        const a = fig[ax] ?? {};
        if (a.scale === "log" && isNum(a.min) && a.min <= 0) e.push(`${ax} log axis needs min > 0`);
        if (isNum(a.min) && isNum(a.max) && a.max <= a.min) e.push(`${ax} axis max must exceed min`);
      }
      break;
    }
    case "bode":
      if (!Array.isArray(fig.numerator) || !fig.numerator.every(isNum)) e.push("bode needs numerator coefficients (descending powers of s)");
      if (!Array.isArray(fig.denominator) || !fig.denominator.every(isNum) || fig.denominator.every((c) => c === 0)) e.push("bode needs denominator coefficients");
      break;
    case "pole_zero":
      if (![...(fig.poles ?? []), ...(fig.zeros ?? [])].length) e.push("pole_zero needs poles or zeros");
      if (![...(fig.poles ?? []), ...(fig.zeros ?? [])].every(isPt)) e.push("poles/zeros must be [re, im] pairs");
      break;
    case "phasor": {
      const ph = fig.phasors ?? [];
      if (!ph.length) e.push("phasor needs phasors");
      const labels = new Set(ph.map((p) => p.label));
      for (const p of ph) {
        if (!p.label) e.push("every phasor needs a label");
        if (!isNum(p.magnitude) || p.magnitude < 0 || !isNum(p.angle_deg)) e.push(`phasor ${p.label}: needs magnitude >= 0 and angle_deg`);
        if (p.from && !labels.has(p.from)) e.push(`phasor ${p.label}: 'from' refers to unknown phasor ${p.from}`);
      }
      break;
    }
    case "circuit": {
      const els = fig.elements ?? [];
      if (!els.length) e.push("circuit needs elements");
      for (const [i, el] of els.entries()) {
        const name = el.name ?? `${el.kind}#${i}`;
        if (TWO_TERMINAL.includes(el.kind)) {
          if (!isPt(el.from) || !isPt(el.to)) e.push(`${name}: needs from and to grid points`);
          else if (key(el.from) === key(el.to)) e.push(`${name}: from and to are the same point`);
          else if (el.from[0] !== el.to[0] && el.from[1] !== el.to[1]) e.push(`${name}: must be horizontal or vertical`);
        } else if (MULTI_PIN[el.kind]) {
          if (!isPt(el.at)) e.push(`${name}: needs 'at'`);
        } else e.push(`${name}: unknown element kind '${el.kind}'`);
        for (const p of elementPins(el)) if (isPt(p.at) && (Math.abs(p.at[0]) > 30 || Math.abs(p.at[1]) > 30)) e.push(`${name}: coordinates must stay within 30 grid units`);
      }
      if (!e.length) {
        // every pin must connect to something, unless it is a labelled terminal
        const { nodes } = circuitNodes(fig);
        for (const n of nodes.values()) {
          if (n.degree >= 2 || n.terminal || n.label) continue;
          const p = n.pins[0];
          if (p) e.push(`dangling ${p.el.name ?? p.el.kind} pin '${p.name}' at (${p.at}): connect it or mark the node as a terminal`);
          else e.push("a wire end is not connected to anything");
        }
      }
      break;
    }
    case "block_diagram": {
      const ids = new Set();
      for (const b of fig.blocks ?? []) {
        if (!b.id || ids.has(b.id)) e.push(`block ids must be unique and non-empty (${b.id})`);
        ids.add(b.id);
        if (!isPt(b.at)) e.push(`block ${b.id}: needs 'at'`);
        if (b.shape && !BLOCK_SHAPES.includes(b.shape)) e.push(`block ${b.id}: unknown shape ${b.shape}`);
      }
      if (!ids.size) e.push("block_diagram needs blocks");
      for (const l of fig.links ?? []) {
        if (!ids.has(l.from) || !ids.has(l.to)) e.push(`link ${l.from}->${l.to}: unknown block`);
        if (l.via && !l.via.every(isPt)) e.push(`link ${l.from}->${l.to}: via must be [[x,y],...]`);
      }
      for (const io of [...(fig.inputs ?? []), ...(fig.outputs ?? [])]) if (!ids.has(io.to ?? io.from)) e.push(`input/output refers to unknown block ${io.to ?? io.from}`);
      break;
    }
    case "bar_chart": {
      const n = fig.categories?.length ?? 0;
      if (!n) e.push("bar_chart needs categories");
      if (!fig.series?.length) e.push("bar_chart needs series");
      for (const s of fig.series ?? []) if (!Array.isArray(s.values) || s.values.length !== n || !s.values.every(isNum)) e.push(`bar series '${s.label}' needs ${n} numeric values`);
      break;
    }
    case "pie_chart":
      if (!fig.slices?.length || !fig.slices.every((s) => s.label && isNum(s.value) && s.value >= 0)) e.push("pie_chart needs slices [{label, value>=0}]");
      break;
    case "table":
      if (!fig.headers?.length || !fig.rows?.length) e.push("table needs headers and rows");
      else if (fig.rows.some((r) => !Array.isArray(r) || r.length !== fig.headers.length)) e.push("every table row needs one cell per header");
      break;
  }
  return e;
}
