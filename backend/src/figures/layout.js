// Layout: turns a figure spec into a display list of four primitives that any renderer can draw
// (the Android app with Canvas, the backend preview with SVG):
//   {k:"path",   p:[x0,y0,x1,y1,...], s:color, w:width, d:dashed, f:fill, close:closed}
//   {k:"circle", c:[x,y], r, s:color, f:fill, w:width}
//   {k:"text",   p:[x,y], t:text, a:"s"|"m"|"e" (anchor), z:size, s:color, b:bold}  (y = vertical centre)
// Colours are theme tokens ("fg", "muted", "grid", "accent", "bg", "s0".."s5") so the app can
// draw the same figure in light and dark mode.
import { linearTicks, logTicks, fmt, seriesPoints, bodeData } from "./math.js";
import { elementPins } from "./spec.js";

const W = 480;

class Canvas {
  constructor(w, h) {
    this.w = w;
    this.h = h;
    this.items = [];
  }
  path(pts, o = {}) {
    if (pts.length < 2) return;
    this.items.push({ k: "path", p: pts.flat().map(r1), s: o.s ?? "fg", w: o.w ?? 1.5, ...(o.d ? { d: 1 } : {}), ...(o.f ? { f: o.f } : {}), ...(o.z ? { close: 1 } : {}) });
  }
  line(a, b, o) {
    this.path([a, b], o);
  }
  circle(c, r, o = {}) {
    this.items.push({ k: "circle", c: c.map(r1), r: r1(r), s: o.s ?? "fg", w: o.w ?? 1.5, ...(o.f ? { f: o.f } : {}) });
  }
  text(p, t, o = {}) {
    if (t == null || t === "") return;
    this.items.push({ k: "text", p: p.map(r1), t: String(t), a: o.a ?? "m", z: o.z ?? 12, s: o.s ?? "fg", ...(o.b ? { b: 1 } : {}) });
  }
  arrowHead(tip, from, o = {}) {
    const ang = Math.atan2(tip[1] - from[1], tip[0] - from[0]);
    const L = o.size ?? 9;
    const a1 = ang + Math.PI - 0.38;
    const a2 = ang + Math.PI + 0.38;
    this.path([tip, [tip[0] + L * Math.cos(a1), tip[1] + L * Math.sin(a1)], [tip[0] + L * Math.cos(a2), tip[1] + L * Math.sin(a2)]], { s: o.s ?? "fg", f: o.s ?? "fg", z: true, w: 1 });
  }
  arrow(a, b, o = {}) {
    this.line(a, b, o);
    this.arrowHead(b, a, o);
  }
  result() {
    return { w: this.w, h: Math.round(this.h), items: this.items };
  }
}

const r1 = (v) => Math.round(v * 10) / 10;
const SERIES = ["s0", "s1", "s2", "s3", "s4", "s5"];

export function layoutFigure(fig) {
  switch (fig.type) {
    case "plot":
      return layoutPlot(fig);
    case "bode":
      return layoutBode(fig);
    case "pole_zero":
      return layoutPoleZero(fig);
    case "phasor":
      return layoutPhasor(fig);
    case "circuit":
      return layoutCircuit(fig);
    case "block_diagram":
      return layoutBlocks(fig);
    case "bar_chart":
      return layoutBar(fig);
    case "pie_chart":
      return layoutPie(fig);
    case "table":
      return layoutTable(fig);
    default:
      throw new Error(`cannot lay out figure type ${fig.type}`);
  }
}

function caption(cv, fig, y) {
  if (fig.caption) cv.text([cv.w / 2, y], fig.caption, { z: 11, s: "muted" });
}

// ---------------------------------------------------------------- plots

/** Draws axes, grid and series in box {x,y,w,h}; returns the data->pixel mappers. */
function plotPanel(cv, box, spec) {
  const xs = spec.x ?? {};
  const ys = spec.y ?? {};
  const logX = xs.scale === "log";
  const logY = ys.scale === "log";
  const rawX = spec.series.flatMap((s) => (s.points ? s.points.map((p) => p[0]) : (s.domains ?? [s.domain ?? [xs.min ?? 0, xs.max ?? 1]]).flat()));
  const xr = { lo: xs.min ?? Math.min(...rawX), hi: xs.max ?? Math.max(...rawX) };
  const curves = spec.series.map((s) => seriesPoints(s, [xr.lo, xr.hi], logX));
  const allY = curves.flat(2).map((p) => p[1]).filter(Number.isFinite); // curves: series -> segments -> [x, y]
  for (const h of spec.hlines ?? []) allY.push(h.y);
  const yr = { lo: ys.min ?? Math.min(...allY), hi: ys.max ?? Math.max(...allY) };
  if (ys.min == null && yr.lo > 0 && !logY) yr.lo = 0; // anchor positive data at zero
  const tx = xs.ticks ? { min: xr.lo, max: xr.hi, ticks: xs.ticks } : logX ? logTicks(xr.lo, xr.hi) : xs.min != null && xs.max != null ? { min: xr.lo, max: xr.hi, ticks: linearTicks(xr.lo, xr.hi).ticks.filter((t) => t >= xr.lo - 1e-9 && t <= xr.hi + 1e-9) } : linearTicks(xr.lo, xr.hi);
  const ty = ys.ticks ? { min: yr.lo, max: yr.hi, ticks: ys.ticks } : logY ? logTicks(yr.lo, yr.hi) : ys.min != null && ys.max != null ? { min: yr.lo, max: yr.hi, ticks: linearTicks(yr.lo, yr.hi, 5).ticks.filter((t) => t >= yr.lo - 1e-9 && t <= yr.hi + 1e-9) } : linearTicks(yr.lo, yr.hi, 5);
  const fx = (x) => box.x + (logX ? (Math.log10(x) - Math.log10(tx.min)) / (Math.log10(tx.max) - Math.log10(tx.min)) : (x - tx.min) / (tx.max - tx.min)) * box.w;
  const fy = (y) => box.y + box.h - (logY ? (Math.log10(y) - Math.log10(ty.min)) / (Math.log10(ty.max) - Math.log10(ty.min)) : (y - ty.min) / (ty.max - ty.min)) * box.h;

  // grid + ticks
  for (const t of tx.ticks) {
    const X = fx(t);
    if (spec.grid !== false) cv.line([X, box.y], [X, box.y + box.h], { s: "grid", w: 0.8 });
    cv.text([X, box.y + box.h + 12], xs.tick_labels?.[tx.ticks.indexOf(t)] ?? fmt(t), { z: 10, s: "muted" });
  }
  for (const t of ty.ticks) {
    const Y = fy(t);
    if (spec.grid !== false) cv.line([box.x, Y], [box.x + box.w, Y], { s: "grid", w: 0.8 });
    cv.text([box.x - 5, Y], fmt(t), { z: 10, s: "muted", a: "e" });
  }
  // axes: through zero when zero is inside the range, otherwise on the box edge
  const y0 = !logY && ty.min < 0 && ty.max > 0 ? fy(0) : box.y + box.h;
  const x0 = !logX && tx.min < 0 && tx.max > 0 ? fx(0) : box.x;
  cv.line([box.x, y0], [box.x + box.w, y0], { w: 1.2 });
  cv.line([x0, box.y], [x0, box.y + box.h], { w: 1.2 });
  const unit = (a) => (a.unit ? ` (${a.unit})` : "");
  cv.text([box.x + box.w, box.y + box.h + 28], `${xs.label ?? "x"}${unit(xs)}`, { a: "e", z: 11 });
  cv.text([box.x - 4, box.y - 10], `${ys.label ?? "y"}${unit(ys)}`, { a: "s", z: 11 });

  const clip = (pts) => clipPolyline(pts, box);
  for (const h of spec.hlines ?? []) {
    if (h.y < ty.min || h.y > ty.max) continue;
    cv.line([box.x, fy(h.y)], [box.x + box.w, fy(h.y)], { s: "muted", d: true, w: 1 });
    if (h.label) cv.text([box.x + box.w - 2, fy(h.y) - 8], h.label, { a: "e", z: 10, s: "muted" });
  }
  for (const v of spec.vlines ?? []) {
    if (v.x < tx.min || v.x > tx.max) continue;
    cv.line([fx(v.x), box.y], [fx(v.x), box.y + box.h], { s: "muted", d: true, w: 1 });
    if (v.label) cv.text([fx(v.x) + 3, box.y + 8], v.label, { a: "s", z: 10, s: "muted" });
  }
  curves.forEach((segs, i) => {
    const s = spec.series[i];
    const color = SERIES[(s.color_index ?? i) % SERIES.length];
    for (const seg of segs) {
      // break the curve where the expression is undefined
      let run = [];
      for (const [x, y] of seg) {
        if (Number.isFinite(y) && (!logY || y > 0) && (!logX || x > 0)) run.push([fx(x), fy(y)]);
        else {
          for (const part of clip(run)) cv.path(part, { s: color, w: 2, d: s.style === "dashed" });
          run = [];
        }
      }
      for (const part of clip(run)) cv.path(part, { s: color, w: 2, d: s.style === "dashed" });
    }
  });
  // legend for labelled multi-series plots
  const labelled = spec.series.map((s, i) => [s, i]).filter(([s]) => s.label);
  if (labelled.length > 1 || spec.legend) {
    labelled.forEach(([s, i], k) => {
      const ly = box.y + 10 + k * 16;
      const color = SERIES[(s.color_index ?? i) % SERIES.length];
      cv.line([box.x + box.w - 120, ly], [box.x + box.w - 100, ly], { s: color, w: 2, d: s.style === "dashed" });
      cv.text([box.x + box.w - 96, ly], s.label, { a: "s", z: 10 });
    });
  }
  for (const a of spec.annotations ?? []) {
    const p = [fx(a.x), fy(a.y)];
    if (a.marker !== false) cv.circle(p, 2.5, { f: "accent", s: "accent" });
    cv.text([p[0] + (a.dx ?? 6), p[1] + (a.dy ?? -10)], a.text, { a: a.anchor ?? "s", z: 11, s: "accent" });
  }
  return { fx, fy };
}

function layoutPlot(fig) {
  const h = fig.height ?? 300;
  const top = fig.title ? 40 : 26;
  const cv = new Canvas(W, h + (fig.caption ? 18 : 0));
  if (fig.title) cv.text([W / 2, 12], fig.title, { b: true, z: 12 });
  plotPanel(cv, { x: 58, y: top, w: W - 76, h: h - top - 40 }, fig);
  caption(cv, fig, h + 6);
  return cv.result();
}

function layoutBode(fig) {
  const { mag, phase } = bodeData(fig);
  const show = fig.show ?? "both";
  const panels = show === "both" ? ["magnitude", "phase"] : [show];
  const ph = 190;
  const cv = new Canvas(W, panels.length * ph + 16 + (fig.title ? 18 : 0) + (fig.caption ? 18 : 0));
  let y = fig.title ? 22 : 4;
  if (fig.title) cv.text([W / 2, 10], fig.title, { b: true });
  const x = { label: "ω", unit: "rad/s", scale: "log", min: fig.w_min ?? 0.01, max: fig.w_max ?? 1000 };
  for (const p of panels) {
    const data = p === "magnitude" ? mag : phase;
    plotPanel(cv, { x: 58, y: y + 22, w: W - 76, h: ph - 62 }, {
      x,
      y: p === "magnitude" ? { label: "|G(jω)|", unit: "dB" } : { label: "∠G(jω)", unit: "deg", ticks: phaseTicks(data) },
      series: [{ points: data }],
      hlines: p === "magnitude" ? [{ y: 0 }] : [{ y: -180 }],
    });
    y += ph;
  }
  caption(cv, fig, cv.h - 10);
  return cv.result();
}

function phaseTicks(data) {
  const ys = data.map((p) => p[1]);
  const lo = Math.floor(Math.min(...ys) / 45) * 45;
  const hi = Math.ceil(Math.max(...ys) / 45) * 45;
  const step = hi - lo > 270 ? 90 : 45;
  const t = [];
  for (let v = lo; v <= hi; v += step) t.push(v);
  if (t.length < 2) t.push(lo + step);
  return t;
}

function layoutPoleZero(fig) {
  const pts = [...(fig.poles ?? []), ...(fig.zeros ?? [])];
  const z = fig.plane === "z" || fig.unit_circle;
  const reach = Math.max(1, ...pts.map(([a, b]) => Math.max(Math.abs(a), Math.abs(b)))) * 1.25;
  const lim = fig.limit ?? (z ? Math.max(1.5, reach) : reach);
  const size = 300;
  const cv = new Canvas(W, size + 30 + (fig.caption ? 18 : 0));
  const cx = W / 2;
  const cy = 14 + size / 2;
  const sc = size / 2 / lim;
  const P = ([re, im]) => [cx + re * sc, cy - im * sc];
  const t = linearTicks(-lim, lim, 6).ticks.filter((v) => Math.abs(v) <= lim);
  for (const v of t) {
    cv.line(P([v, -lim]), P([v, lim]), { s: "grid", w: 0.8 });
    cv.line(P([-lim, v]), P([lim, v]), { s: "grid", w: 0.8 });
    if (v !== 0) {
      cv.text([P([v, 0])[0], cy + 12], fmt(v), { z: 10, s: "muted" });
      cv.text([cx - 5, P([0, v])[1]], `${fmt(v)}j`, { z: 10, s: "muted", a: "e" });
    }
  }
  cv.arrow(P([-lim, 0]), P([lim, 0]), { w: 1.2, size: 7 });
  cv.arrow(P([0, -lim]), P([0, lim]), { w: 1.2, size: 7 });
  cv.text([P([lim, 0])[0], cy - 12], z ? "Re(z)" : "σ", { a: "e", z: 11 });
  cv.text([cx + 8, P([0, lim])[1] + 4], z ? "Im(z)" : "jω", { a: "s", z: 11 });
  if (z) cv.circle([cx, cy], sc, { s: "muted", d: true, w: 1 });
  for (const p of fig.poles ?? []) {
    const [x, y] = P(p);
    cv.line([x - 6, y - 6], [x + 6, y + 6], { s: "s0", w: 2.2 });
    cv.line([x - 6, y + 6], [x + 6, y - 6], { s: "s0", w: 2.2 });
    if (fig.label_points) cv.text([x + 9, y - 10], pzLabel(p), { a: "s", z: 10, s: "s0" });
  }
  for (const p of fig.zeros ?? []) {
    const [x, y] = P(p);
    cv.circle([x, y], 6, { s: "s1", w: 2.2, f: "bg" });
    if (fig.label_points) cv.text([x + 9, y + 12], pzLabel(p), { a: "s", z: 10, s: "s1" });
  }
  caption(cv, fig, cv.h - 10);
  return cv.result();
}

const pzLabel = ([re, im]) => (im === 0 ? fmt(re) : `${fmt(re)}${im < 0 ? "−" : "+"}j${fmt(Math.abs(im))}`);

// ---------------------------------------------------------------- phasors

function layoutPhasor(fig) {
  const byLabel = Object.fromEntries(fig.phasors.map((p) => [p.label, p]));
  const tip = {};
  const tail = {};
  const resolve = (p, depth = 0) => {
    if (tip[p.label]) return tip[p.label];
    const start = p.from && depth < 10 ? resolve(byLabel[p.from], depth + 1) : [0, 0];
    const a = (p.angle_deg * Math.PI) / 180;
    tail[p.label] = start;
    const len = p.magnitude * (p.scale ?? 1); // per-phasor drawing scale when units differ (V and I)
    tip[p.label] = [start[0] + len * Math.cos(a), start[1] + len * Math.sin(a)];
    return tip[p.label];
  };
  fig.phasors.forEach((p) => resolve(p));
  const all = [[0, 0], ...Object.values(tip)];
  const minX = Math.min(...all.map((p) => p[0]));
  const maxX = Math.max(...all.map((p) => p[0]));
  const minY = Math.min(...all.map((p) => p[1]));
  const maxY = Math.max(...all.map((p) => p[1]));
  // leave room on both sides for tip labels
  const span = Math.max(maxX - minX, (maxY - minY) * 1.3, 1e-9);
  const sc = (W - 190) / span;
  const h = Math.max(180, (maxY - minY) * sc + 90);
  const cv = new Canvas(W, h + (fig.caption ? 18 : 0));
  const ox = 95 - minX * sc;
  const oy = 45 + maxY * sc;
  const P = ([x, y]) => [ox + x * sc, oy - y * sc];
  cv.line([20, oy], [W - 20, oy], { s: "grid", d: true, w: 1 });
  cv.text([22, oy - 10], fig.reference_label ?? "reference", { a: "s", z: 10, s: "muted" });
  fig.phasors.forEach((p, i) => {
    const a = P(tail[p.label]);
    const b = P(tip[p.label]);
    const color = SERIES[(p.color_index ?? i) % SERIES.length];
    cv.arrow(a, b, { s: color, w: 2.2, d: p.style === "dashed" });
    // label just past the tip, anchored away from the arrow so it never overlaps it
    const ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
    const cos = Math.cos(ang);
    const anchor = cos > 0.3 ? "s" : cos < -0.3 ? "e" : "m";
    cv.text([b[0] + 8 * cos, b[1] + 14 * Math.sin(ang) + (anchor === "m" ? 0 : -2)], p.text ?? p.label, { s: color, b: true, z: 12, a: anchor });
  });
  cv.circle(P([0, 0]), 2.5, { f: "fg" });
  for (const an of fig.angles ?? []) {
    const p1 = byLabel[an.from];
    const p2 = byLabel[an.to];
    if (!p1 || !p2) continue;
    const r = 34 + 10 * (fig.angles.indexOf(an) % 3);
    const a1 = p1.angle_deg;
    const a2 = p2.angle_deg;
    const pts = [];
    for (let k = 0; k <= 24; k++) {
      const a = ((a1 + ((a2 - a1) * k) / 24) * Math.PI) / 180;
      pts.push([ox + r * Math.cos(a), oy - r * Math.sin(a)]);
    }
    cv.path(pts, { s: "accent", w: 1.2 });
    const mid = (((a1 + a2) / 2) * Math.PI) / 180;
    cv.text([ox + (r + 10) * Math.cos(mid) + 4, oy - (r + 10) * Math.sin(mid)], an.label, { s: "accent", z: 11, a: "s" });
  }
  caption(cv, fig, cv.h - 10);
  return cv.result();
}

// ---------------------------------------------------------------- circuits

const G = 70; // pixels per grid unit

function layoutCircuit(fig) {
  const pts = [];
  for (const el of fig.elements) for (const p of elementPins(el)) pts.push(p.at);
  for (const el of fig.elements) if (el.kind === "opamp" || el.kind === "transformer" || el.kind === "npn" || el.kind === "nmos") pts.push(el.at);
  const minX = Math.min(...pts.map((p) => p[0]));
  const maxX = Math.max(...pts.map((p) => p[0]));
  const minY = Math.min(...pts.map((p) => p[1]));
  const maxY = Math.max(...pts.map((p) => p[1]));
  const g = Math.min(G, (W - 110) / Math.max(1, maxX - minX));
  const w = W;
  const h = (maxY - minY) * g + 100 + (fig.caption ? 18 : 0);
  const cv = new Canvas(w, h);
  const ox = (w - (maxX - minX) * g) / 2 - minX * g;
  const oy = 50 - minY * g;
  const P = ([x, y]) => [ox + x * g, oy + y * g];

  for (const el of fig.elements) drawElement(cv, el, P, g);
  // junction dots where three or more connections meet
  const count = new Map();
  for (const el of fig.elements) for (const p of elementPins(el)) count.set(String(p.at), (count.get(String(p.at)) ?? 0) + 1);
  for (const el of fig.elements.filter((e) => e.kind === "wire")) for (const other of fig.elements) for (const p of elementPins(other)) if (between(p.at, el.from, el.to)) count.set(String(p.at), (count.get(String(p.at)) ?? 0) + 2);
  for (const [k, n] of count) if (n >= 3) cv.circle(P(k.split(",").map(Number)), 3.5, { f: "fg" });
  for (const n of fig.nodes ?? []) {
    const p = P(n.at);
    if (n.terminal) cv.circle(p, 4.5, { f: "bg", w: 1.5 });
    else if (n.dot) cv.circle(p, 3.5, { f: "fg" });
    if (n.label) cv.text([p[0] + (n.dx ?? -8), p[1] + (n.dy ?? -12)], n.label, { b: true, z: 12, s: "accent", a: "e" });
  }
  for (const a of fig.arrows ?? []) {
    const p = P(a.at);
    const d = { right: [1, 0], left: [-1, 0], up: [0, -1], down: [0, 1] }[a.dir] ?? [1, 0];
    cv.arrow([p[0] - d[0] * 12, p[1] - d[1] * 12], [p[0] + d[0] * 12, p[1] + d[1] * 12], { s: "accent", w: 1.6, size: 8 });
    cv.text([p[0] + (d[1] ? 12 : 0), p[1] + (d[0] ? -12 : 0)], a.label, { s: "accent", z: 11, a: d[1] ? "s" : "m" });
  }
  for (const t of fig.labels ?? []) cv.text(P(t.at), t.text, { z: t.size ?? 12, s: t.color ?? "fg" });
  caption(cv, fig, h - 10);
  return cv.result();
}

function between(p, a, b) {
  if (a[0] === b[0]) return p[0] === a[0] && p[1] > Math.min(a[1], b[1]) && p[1] < Math.max(a[1], b[1]);
  if (a[1] === b[1]) return p[1] === a[1] && p[0] > Math.min(a[0], b[0]) && p[0] < Math.max(a[0], b[0]);
  return false;
}

function drawElement(cv, el, P, g) {
  const k = el.kind;
  if (k === "ground") return drawGround(cv, P(el.at));
  if (k === "opamp") return drawOpamp(cv, el, P, g);
  if (k === "transformer") return drawTransformer(cv, el, P, g);
  if (k === "npn" || k === "nmos") return drawTransistor(cv, el, P, g);
  const a = P(el.from);
  const b = P(el.to);
  if (k === "wire") return cv.line(a, b);
  // local frame: u along the element, v perpendicular (to the left of travel)
  const len = Math.hypot(b[0] - a[0], b[1] - a[1]);
  const u = [(b[0] - a[0]) / len, (b[1] - a[1]) / len];
  const v = [-u[1], u[0]];
  const m = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const L = (s, t) => [m[0] + u[0] * s + v[0] * t, m[1] + u[1] * s + v[1] * t];
  const body = Math.min(k === "R" || k === "L" ? 22 : 16, len / 2 - 4);
  cv.line(a, L(-body, 0));
  cv.line(L(body, 0), b);
  const horizontal = Math.abs(u[1]) < 0.5;
  switch (k) {
    case "R": {
      const zz = [L(-body, 0)];
      for (let i = 0; i < 6; i++) zz.push(L(-body + (2 * body * (i + 0.5)) / 6, i % 2 ? 6 : -6));
      zz.push(L(body, 0));
      cv.path(zz);
      break;
    }
    case "L": {
      const pts = [];
      for (let i = 0; i < 4; i++) {
        const c0 = -body + (2 * body * (i + 0.5)) / 4;
        for (let s = 0; s <= 8; s++) {
          const ang = Math.PI - (Math.PI * s) / 8;
          pts.push(L(c0 + (body / 4) * Math.cos(ang), -(body / 4) * Math.sin(ang) * 1.3));
        }
      }
      cv.path(pts);
      break;
    }
    case "C":
      cv.line(L(-body, 0), L(-4, 0));
      cv.line(L(4, 0), L(body, 0));
      cv.line(L(-4, -12), L(-4, 12), { w: 2.4 });
      cv.line(L(4, -12), L(4, 12), { w: 2.4 });
      break;
    case "V":
    case "AC":
    case "I":
    case "meter":
    case "lamp": {
      cv.line(L(-body, 0), L(-14, 0));
      cv.line(L(14, 0), L(body, 0));
      cv.circle(m, 14, { f: "bg" });
      if (k === "V") {
        cv.text(L(7, 0), "+", { z: 13, b: true });
        cv.text(L(-7, 0), "−", { z: 13, b: true });
      } else if (k === "AC") {
        const sw = [];
        for (let s = 0; s <= 16; s++) sw.push([m[0] - 8 + s, m[1] - 5 * Math.sin((s / 16) * 2 * Math.PI)]);
        cv.path(sw, { w: 1.3 });
      } else if (k === "I") cv.arrow(L(-8, 0), L(8, 0), { w: 1.6, size: 6 });
      else if (k === "meter") cv.text(m, el.meter?.[0]?.toUpperCase() ?? "M", { b: true, z: 13 });
      else {
        cv.line([m[0] - 9, m[1] - 9], [m[0] + 9, m[1] + 9], { w: 1.2 });
        cv.line([m[0] - 9, m[1] + 9], [m[0] + 9, m[1] - 9], { w: 1.2 });
      }
      break;
    }
    case "dep_v":
    case "dep_i": {
      cv.line(L(-body, 0), L(-14, 0));
      cv.line(L(14, 0), L(body, 0));
      cv.path([L(-14, 0), L(0, 14), L(14, 0), L(0, -14)], { z: true, f: "bg" });
      if (k === "dep_v") {
        cv.text(L(6, 0), "+", { z: 12, b: true });
        cv.text(L(-6, 0), "−", { z: 12, b: true });
      } else cv.arrow(L(-7, 0), L(7, 0), { w: 1.5, size: 5 });
      break;
    }
    case "diode":
    case "zener":
    case "thyristor":
      cv.line(L(-body, 0), L(-8, 0));
      cv.line(L(8, 0), L(body, 0));
      cv.path([L(-8, -9), L(-8, 9), L(8, 0)], { z: true, f: "fg" });
      cv.line(L(8, -10), L(8, 10), { w: 2 });
      if (k === "zener") {
        cv.line(L(8, -10), L(4, -13), { w: 2 });
        cv.line(L(8, 10), L(12, 13), { w: 2 });
      }
      if (k === "thyristor") {
        cv.line(L(6, 6), L(14, 18), { w: 1.5 });
        cv.text(L(18, 24), "G", { z: 10 });
      }
      break;
    case "switch": {
      cv.line(L(-body, 0), L(-10, 0));
      cv.line(L(10, 0), L(body, 0));
      cv.circle(L(-10, 0), 2.5, { f: "fg" });
      cv.circle(L(10, 0), 2.5, { f: "fg" });
      cv.line(L(-10, 0), el.closed ? L(10, 0) : L(8, -12), { w: 1.8 });
      break;
    }
  }
  // value label beside the element (above horizontal elements, right of vertical ones)
  const label = [el.name, el.label].filter(Boolean).join(" ");
  if (label) {
    if (horizontal) cv.text([m[0], m[1] - 22], label, { z: 11 });
    else cv.text([m[0] + 22, m[1]], label, { z: 11, a: "s" });
  }
}

function drawGround(cv, p) {
  cv.line(p, [p[0], p[1] + 8]);
  cv.line([p[0] - 12, p[1] + 8], [p[0] + 12, p[1] + 8], { w: 1.8 });
  cv.line([p[0] - 7, p[1] + 13], [p[0] + 7, p[1] + 13], { w: 1.8 });
  cv.line([p[0] - 2.5, p[1] + 18], [p[0] + 2.5, p[1] + 18], { w: 1.8 });
}

function drawOpamp(cv, el, P, g) {
  const [cx, cy] = P(el.at);
  const f = el.flip ? -1 : 1;
  const hw = g * 0.7;
  cv.path([[cx - hw, cy - hw], [cx - hw, cy + hw], [cx + hw, cy]], { z: true, f: "bg" });
  const pins = Object.fromEntries(elementPins(el).map((p) => [p.name, P(p.at)]));
  cv.line(pins.minus, [cx - hw, pins.minus[1]]);
  cv.line(pins.plus, [cx - hw, pins.plus[1]]);
  cv.line([cx + hw, cy], pins.out);
  cv.text([cx - hw + 10, cy - (g / 2) * f], "−", { b: true, z: 14 });
  cv.text([cx - hw + 10, cy + (g / 2) * f], "+", { b: true, z: 14 });
  if (el.label) cv.text([cx, cy - hw - 10], el.label, { z: 11 });
}

function drawTransformer(cv, el, P, g) {
  const pins = Object.fromEntries(elementPins(el).map((p) => [p.name, P(p.at)]));
  const coil = (top, bottom, dir) => {
    const pts = [];
    const n = 4;
    const H = bottom[1] - top[1];
    for (let i = 0; i < n; i++)
      for (let s = 0; s <= 8; s++) {
        const ang = -Math.PI / 2 + (Math.PI * s) / 8;
        const cy = top[1] + (H * (i + 0.5)) / n;
        pts.push([top[0] + dir * (H / n / 2) * Math.cos(ang) * 1.2, cy + (H / n / 2) * Math.sin(ang)]);
      }
    cv.path(pts);
  };
  coil(pins.p1, pins.p2, 1);
  coil(pins.s1, pins.s2, -1);
  const [cx] = P(el.at);
  cv.line([cx - 3, pins.p1[1]], [cx - 3, pins.p2[1]], { w: 1.4 });
  cv.line([cx + 3, pins.p1[1]], [cx + 3, pins.p2[1]], { w: 1.4 });
  if (el.label) cv.text([cx, pins.p1[1] - 12], el.label, { z: 11 });
  if (el.dots) {
    cv.circle([pins.p1[0] + 10, pins.p1[1] + 8], 2.5, { f: "fg" });
    cv.circle([pins.s1[0] - 10, pins.s1[1] + 8], 2.5, { f: "fg" });
  }
  void g;
}

function drawTransistor(cv, el, P, g) {
  const pins = Object.fromEntries(elementPins(el).map((p) => [p.name, P(p.at)]));
  const [cx, cy] = P(el.at);
  const r = g * 0.42;
  cv.circle([cx, cy], r, { s: "muted", w: 1 });
  if (el.kind === "npn") {
    cv.line(pins.base, [cx - r * 0.35, cy]);
    cv.line([cx - r * 0.35, cy - r * 0.55], [cx - r * 0.35, cy + r * 0.55], { w: 2.4 });
    cv.line([cx - r * 0.35, cy - r * 0.2], [cx, cy - r]);
    cv.line([cx, cy - r], pins.collector);
    cv.arrow([cx - r * 0.35, cy + r * 0.2], [cx, cy + r * 0.9], { size: 6 });
    cv.line([cx, cy + r * 0.9], pins.emitter);
  } else {
    cv.line(pins.gate, [cx - r * 0.5, cy]);
    cv.line([cx - r * 0.5, cy - r * 0.55], [cx - r * 0.5, cy + r * 0.55], { w: 2 });
    cv.line([cx - r * 0.25, cy - r * 0.6], [cx - r * 0.25, cy + r * 0.6], { w: 2 });
    cv.line([cx - r * 0.25, cy - r * 0.5], [cx, cy - r * 0.5]);
    cv.line([cx, cy - r * 0.5], pins.drain);
    cv.line([cx - r * 0.25, cy + r * 0.5], [cx, cy + r * 0.5]);
    cv.line([cx, cy + r * 0.5], pins.source);
  }
  if (el.label) cv.text([cx + r + 6, cy], el.label, { a: "s", z: 11 });
}

// ---------------------------------------------------------------- block diagrams

function layoutBlocks(fig) {
  const gx = fig.unit_x ?? 62;
  const gy = fig.unit_y ?? 56;
  const pts = fig.blocks.map((b) => b.at);
  for (const l of fig.links ?? []) for (const v of l.via ?? []) pts.push(v);
  const minX = Math.min(...pts.map((p) => p[0])) - (fig.inputs?.length ? 1.3 : 0.6);
  const maxX = Math.max(...pts.map((p) => p[0])) + (fig.outputs?.length ? 1.4 : 0.6);
  const minY = Math.min(...pts.map((p) => p[1]));
  const maxY = Math.max(...pts.map((p) => p[1]));
  const sx = Math.min(gx, (W - 20) / Math.max(1, maxX - minX));
  const cv = new Canvas(W, (maxY - minY) * gy + 90 + (fig.caption ? 18 : 0));
  const ox = (W - (maxX - minX) * sx) / 2 - minX * sx;
  const oy = 46 - minY * gy;
  const P = ([x, y]) => [ox + x * sx, oy + y * gy];
  const byId = Object.fromEntries(fig.blocks.map((b) => [b.id, b]));
  const size = (b) => {
    if (b.shape === "sum") return { w: 22, h: 22, round: true };
    if (b.shape === "point") return { w: 0, h: 0, round: true };
    if (b.shape && b.shape !== "rect") return { w: 44, h: 34 };
    return { w: Math.max(54, 7.5 * String(b.label ?? b.id).length + 16), h: 34 };
  };
  // where a line from `towards` meets the edge of block b
  const edge = (b, towards) => {
    const c = P(b.at);
    const s = size(b);
    const dx = towards[0] - c[0];
    const dy = towards[1] - c[1];
    if (!dx && !dy) return c;
    if (s.round) {
      const d = Math.hypot(dx, dy);
      return [c[0] + (dx / d) * (s.w / 2), c[1] + (dy / d) * (s.h / 2)];
    }
    const tx = dx ? s.w / 2 / Math.abs(dx) : Infinity;
    const ty = dy ? s.h / 2 / Math.abs(dy) : Infinity;
    const t = Math.min(tx, ty);
    return [c[0] + dx * t, c[1] + dy * t];
  };
  const signAt = [];
  const drawLink = (fromPt, toBlock, via, o = {}) => {
    const route = [fromPt, ...via.map(P)];
    const end = edge(toBlock, route.at(-1));
    route.push(end);
    cv.path(route, { w: 1.5 });
    cv.arrowHead(end, route.at(-2), { size: 8 });
    if (o.sign && toBlock.shape === "sum") signAt.push([end, route.at(-2), o.sign]);
    if (o.label) {
      // label the longest segment
      let best = 0;
      for (let i = 1; i < route.length; i++) if (Math.hypot(route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1]) > Math.hypot(route[best + 1][0] - route[best][0], route[best + 1][1] - route[best][1])) best = i - 1;
      const a = route[best];
      const b = route[best + 1];
      const vertical = Math.abs(a[0] - b[0]) < 1;
      cv.text(vertical ? [a[0] + 6, (a[1] + b[1]) / 2] : [(a[0] + b[0]) / 2, a[1] - 10], o.label, { z: 11, s: "accent", a: vertical ? "s" : "m" });
    }
  };
  for (const l of fig.links ?? []) {
    const from = byId[l.from];
    const to = byId[l.to];
    const first = l.via?.length ? P(l.via[0]) : P(to.at);
    drawLink(edge(from, first), to, l.via ?? [], l);
  }
  for (const i of fig.inputs ?? []) {
    const to = byId[i.to];
    const c = P(to.at);
    const start = [c[0] - sx * (i.length ?? 1.1), c[1]];
    drawLink(start, to, [], { sign: i.sign });
    cv.text([start[0], start[1] - 11], i.label, { z: 12, a: "s" });
  }
  for (const o of fig.outputs ?? []) {
    const from = byId[o.from];
    const c = P(from.at);
    const end = [c[0] + sx * (o.length ?? 1.2), c[1]];
    const s = from.shape === "point" ? c : edge(from, end);
    cv.arrow(s, end, { size: 8 });
    cv.text([end[0], end[1] - 11], o.label, { z: 12, a: "e" });
  }
  for (const b of fig.blocks) drawBlock(cv, b, P(b.at), size(b));
  for (const [end, prev, sign] of signAt) {
    const dx = end[0] - prev[0];
    const dy = end[1] - prev[1];
    const d = Math.hypot(dx, dy) || 1;
    // sign sits just outside the circle, beside the incoming arrow (above it, or left of a vertical one)
    const [ux, uy] = [dx / d, dy / d];
    cv.text([end[0] - ux * 10 + uy * 10, end[1] - uy * 10 - ux * 10], sign === "-" ? "−" : "+", { z: 13, b: true });
  }
  caption(cv, fig, cv.h - 10);
  return cv.result();
}

function drawBlock(cv, b, c, s) {
  const shape = b.shape ?? "rect";
  if (shape === "point") return cv.circle(c, 3.2, { f: "fg" });
  if (shape === "sum") {
    cv.circle(c, 11, { f: "bg" });
    const d = 7.8;
    cv.line([c[0] - d, c[1] - d], [c[0] + d, c[1] + d], { w: 1 });
    cv.line([c[0] - d, c[1] + d], [c[0] + d, c[1] - d], { w: 1 });
    return;
  }
  if (shape === "rect") {
    const [x, y] = [c[0] - s.w / 2, c[1] - s.h / 2];
    cv.path([[x, y], [x + s.w, y], [x + s.w, y + s.h], [x, y + s.h]], { z: true, f: "bg" });
    cv.text(c, b.label ?? b.id, { z: 12 });
    return;
  }
  // logic gates, input side on the left
  const hw = s.w / 2;
  const hh = s.h / 2;
  const [x0, x1, y0, y1] = [c[0] - hw, c[0] + hw, c[1] - hh, c[1] + hh];
  const bubble = ["NAND", "NOR", "XNOR", "NOT"].includes(shape);
  const bodyEnd = bubble ? x1 - 7 : x1;
  const pts = [];
  if (shape === "NOT") pts.push([x0, y0], [x0, y1], [bodyEnd, c[1]]);
  else if (shape === "AND" || shape === "NAND") {
    pts.push([x0, y0], [c[0], y0]);
    for (let k = 0; k <= 12; k++) {
      const a = -Math.PI / 2 + (Math.PI * k) / 12;
      pts.push([c[0] + (bodyEnd - c[0]) * Math.cos(a), c[1] + hh * Math.sin(a)]);
    }
    pts.push([x0, y1]);
  } else {
    // OR family: concave back, pointed front
    for (let k = 0; k <= 12; k++) {
      const t = k / 12;
      pts.push([x0 + (bodyEnd - x0) * t, y0 + (c[1] - y0) * t * t]);
    }
    for (let k = 12; k >= 0; k--) {
      const t = k / 12;
      pts.push([x0 + (bodyEnd - x0) * t, y1 - (y1 - c[1]) * t * t]);
    }
    for (let k = 0; k <= 8; k++) pts.push([x0 + 8 * Math.sin((Math.PI * k) / 8), y1 - (y1 - y0) * (k / 8)]);
    if (shape === "XOR" || shape === "XNOR") {
      const back = [];
      for (let k = 0; k <= 8; k++) back.push([x0 - 6 + 8 * Math.sin((Math.PI * k) / 8), y1 - (y1 - y0) * (k / 8)]);
      cv.path(back);
    }
  }
  cv.path(pts, { z: true, f: "bg" });
  if (bubble) cv.circle([x1 - 3.5, c[1]], 3.5, { f: "bg" });
  if (b.label) cv.text([c[0], y1 + 10], b.label, { z: 10, s: "muted" });
}

// ---------------------------------------------------------------- charts

function layoutBar(fig) {
  const h = 280;
  const top = fig.title ? 40 : 24;
  const cv = new Canvas(W, h + (fig.caption ? 18 : 0) + (fig.series.length > 1 ? 18 : 0));
  if (fig.title) cv.text([W / 2, 12], fig.title, { b: true });
  const box = { x: 58, y: top, w: W - 76, h: h - top - 46 };
  const vals = fig.series.flatMap((s) => s.values);
  const t = linearTicks(Math.min(0, ...vals), Math.max(...vals) * 1.08, 5);
  const fy = (v) => box.y + box.h - ((v - t.min) / (t.max - t.min)) * box.h;
  for (const v of t.ticks) {
    cv.line([box.x, fy(v)], [box.x + box.w, fy(v)], { s: "grid", w: 0.8 });
    cv.text([box.x - 5, fy(v)], fmt(v), { a: "e", z: 10, s: "muted" });
  }
  const n = fig.categories.length;
  const groupW = box.w / n;
  const barW = Math.min(36, (groupW * 0.75) / fig.series.length);
  fig.categories.forEach((cat, i) => {
    const gx = box.x + groupW * i + groupW / 2 - (barW * fig.series.length) / 2;
    fig.series.forEach((s, j) => {
      const x = gx + j * barW;
      const y = fy(s.values[i]);
      const y0 = fy(Math.max(0, t.min));
      cv.path([[x + 1, y0], [x + 1, y], [x + barW - 1, y], [x + barW - 1, y0]], { z: true, f: SERIES[j % SERIES.length], s: SERIES[j % SERIES.length], w: 1 });
      if (fig.value_labels !== false) cv.text([x + barW / 2, y - 8], fmt(s.values[i]), { z: 9 });
    });
    cv.text([box.x + groupW * i + groupW / 2, box.y + box.h + 13], cat, { z: 10 });
  });
  cv.line([box.x, fy(Math.max(0, t.min))], [box.x + box.w, fy(Math.max(0, t.min))], { w: 1.2 });
  cv.line([box.x, box.y], [box.x, box.y + box.h], { w: 1.2 });
  const yl = fig.y ?? {};
  cv.text([box.x - 4, box.y - 12], `${yl.label ?? ""}${yl.unit ? ` (${yl.unit})` : ""}`, { a: "s", z: 11 });
  if (fig.x?.label) cv.text([box.x + box.w, box.y + box.h + 30], fig.x.label, { a: "e", z: 11 });
  if (fig.series.length > 1)
    fig.series.forEach((s, j) => {
      const lx = box.x + j * 110;
      cv.path([[lx, h - 4], [lx + 12, h - 4], [lx + 12, h + 6], [lx, h + 6]], { z: true, f: SERIES[j], s: SERIES[j], w: 1 });
      cv.text([lx + 16, h + 1], s.label, { a: "s", z: 10 });
    });
  caption(cv, fig, cv.h - 10);
  return cv.result();
}

function layoutPie(fig) {
  const cv = new Canvas(W, 260 + (fig.caption ? 18 : 0));
  const c = [W / 2 - 60, 130];
  const r = 95;
  const total = fig.slices.reduce((a, s) => a + s.value, 0) || 1;
  let a0 = -Math.PI / 2;
  fig.slices.forEach((s, i) => {
    const a1 = a0 + (2 * Math.PI * s.value) / total;
    const pts = [c];
    const steps = Math.max(2, Math.ceil(((a1 - a0) / (2 * Math.PI)) * 60));
    for (let k = 0; k <= steps; k++) {
      const a = a0 + ((a1 - a0) * k) / steps;
      pts.push([c[0] + r * Math.cos(a), c[1] + r * Math.sin(a)]);
    }
    cv.path(pts, { z: true, f: SERIES[i % SERIES.length], s: "bg", w: 1.5 });
    const mid = (a0 + a1) / 2;
    cv.text([c[0] + r * 0.65 * Math.cos(mid), c[1] + r * 0.65 * Math.sin(mid)], `${Math.round((s.value / total) * 1000) / 10}%`, { z: 10, s: "bg", b: true });
    cv.path([[W - 150, 40 + i * 22 - 6], [W - 138, 40 + i * 22 - 6], [W - 138, 40 + i * 22 + 6], [W - 150, 40 + i * 22 + 6]], { z: true, f: SERIES[i % SERIES.length], s: SERIES[i % SERIES.length], w: 1 });
    cv.text([W - 132, 40 + i * 22], `${s.label} (${fmt(s.value)})`, { a: "s", z: 11 });
    a0 = a1;
  });
  if (fig.title) cv.text([c[0], 14], fig.title, { b: true });
  caption(cv, fig, cv.h - 10);
  return cv.result();
}

function layoutTable(fig) {
  const cols = fig.headers.length;
  const rowH = 26;
  const h = (fig.rows.length + 1) * rowH + 20 + (fig.title ? 20 : 0) + (fig.caption ? 18 : 0);
  const cv = new Canvas(W, h);
  const top = fig.title ? 26 : 10;
  if (fig.title) cv.text([W / 2, 12], fig.title, { b: true });
  const x0 = 20;
  const cw = (W - 40) / cols;
  for (let r = 0; r <= fig.rows.length + 1; r++) cv.line([x0, top + r * rowH], [x0 + cw * cols, top + r * rowH], { w: r === 1 ? 1.6 : 1, s: r === 1 || r === 0 || r === fig.rows.length + 1 ? "fg" : "grid" });
  for (let c = 0; c <= cols; c++) cv.line([x0 + c * cw, top], [x0 + c * cw, top + (fig.rows.length + 1) * rowH], { w: 1, s: c === 0 || c === cols ? "fg" : "grid" });
  fig.headers.forEach((t, c) => cv.text([x0 + c * cw + cw / 2, top + rowH / 2], t, { b: true, z: 11 }));
  fig.rows.forEach((row, r) => row.forEach((t, c) => cv.text([x0 + c * cw + cw / 2, top + (r + 1.5) * rowH], String(t), { z: 11 })));
  caption(cv, fig, h - 10);
  return cv.result();
}

// ---------------------------------------------------------------- clipping

/** Clip a pixel polyline to box; returns the visible pieces. */
function clipPolyline(pts, box) {
  const out = [];
  let cur = [];
  const x0 = box.x - 1;
  const x1 = box.x + box.w + 1;
  const y0 = box.y - 1;
  const y1 = box.y + box.h + 1;
  for (let i = 1; i < pts.length; i++) {
    const seg = clipSegment(pts[i - 1], pts[i], x0, y0, x1, y1);
    if (!seg) {
      if (cur.length > 1) out.push(cur);
      cur = [];
      continue;
    }
    const [a, b] = seg;
    if (!cur.length || cur.at(-1)[0] !== a[0] || cur.at(-1)[1] !== a[1]) {
      if (cur.length > 1) out.push(cur);
      cur = [a];
    }
    cur.push(b);
  }
  if (cur.length > 1) out.push(cur);
  return out;
}

// Liang-Barsky
function clipSegment(a, b, xmin, ymin, xmax, ymax) {
  let t0 = 0;
  let t1 = 1;
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const checks = [
    [-dx, a[0] - xmin],
    [dx, xmax - a[0]],
    [-dy, a[1] - ymin],
    [dy, ymax - a[1]],
  ];
  for (const [p, q] of checks) {
    if (p === 0) {
      if (q < 0) return null;
    } else {
      const t = q / p;
      if (p < 0) t0 = Math.max(t0, t);
      else t1 = Math.min(t1, t);
      if (t0 > t1) return null;
    }
  }
  return [
    [a[0] + t0 * dx, a[1] + t0 * dy],
    [a[0] + t1 * dx, a[1] + t1 * dy],
  ];
}
