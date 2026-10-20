// Numeric helpers for figures: axis ticks, sampling expression series, frequency response.
import { evaluate } from "../expr.js";

export function niceStep(range, target = 6) {
  const raw = range / Math.max(1, target);
  const mag = 10 ** Math.floor(Math.log10(raw));
  const n = raw / mag;
  return (n < 1.5 ? 1 : n < 3 ? 2 : n < 7 ? 5 : 10) * mag;
}

/** "Nice" axis bounds and ticks around [lo, hi]. */
export function linearTicks(lo, hi, target = 6) {
  if (!(hi > lo)) {
    const d = Math.abs(lo) || 1;
    lo -= d / 2;
    hi += d / 2;
  }
  const step = niceStep(hi - lo, target);
  const min = Math.floor(lo / step + 1e-9) * step;
  const max = Math.ceil(hi / step - 1e-9) * step;
  const ticks = [];
  for (let v = min; v <= max + step / 2; v += step) ticks.push(Math.abs(v) < step * 1e-9 ? 0 : v);
  return { min, max, ticks };
}

export function logTicks(lo, hi) {
  const a = Math.floor(Math.log10(lo) + 1e-9);
  const b = Math.ceil(Math.log10(hi) - 1e-9);
  const ticks = [];
  for (let k = a; k <= b; k++) ticks.push(10 ** k);
  return { min: 10 ** a, max: 10 ** b, ticks };
}

export function fmt(v) {
  if (v === 0) return "0";
  const a = Math.abs(v);
  if (a >= 1e5 || a < 1e-3) {
    const exp = Math.floor(Math.log10(a));
    const m = v / 10 ** exp;
    return Math.abs(m - 1) < 1e-9 ? `1e${exp}` : `${+m.toFixed(2)}e${exp}`;
  }
  return String(+v.toPrecision(4));
}

/** Points of a plot series: explicit points, or an expression in x sampled over its domain(s). */
export function seriesPoints(s, xRange, logX = false) {
  if (s.points) return [s.points.map(([x, y]) => [x, y])];
  const doms = s.domains ?? [s.domain ?? xRange];
  const n = Math.min(Math.max(s.samples ?? 240, 20), 1000);
  return doms.map(([a, b]) => {
    const pts = [];
    for (let i = 0; i <= n; i++) {
      const x = logX ? a * (b / a) ** (i / n) : a + ((b - a) * i) / n;
      let y;
      try {
        y = evaluate(s.expression, { x, ...(s.variables ?? {}) });
      } catch {
        y = NaN;
      }
      pts.push([x, y]);
    }
    return pts;
  });
}

// complex polynomial evaluation at s = j*w (coefficients in descending powers)
function polyAtJw(coeffs, w) {
  let re = 0;
  let im = 0;
  for (const c of coeffs) {
    // (re + j im) * (j w) + c
    const nre = -im * w + c;
    const nim = re * w;
    re = nre;
    im = nim;
  }
  return [re, im];
}

/** Magnitude (dB) and unwrapped phase (deg) of N(s)/D(s) * K e^(-sT) on log-spaced w. */
export function bodeData({ numerator, denominator, gain = 1, delay = 0, w_min = 0.01, w_max = 1000, samples = 300 }) {
  const mag = [];
  const phase = [];
  let prev = null;
  let offset = 0;
  for (let i = 0; i <= samples; i++) {
    const w = w_min * (w_max / w_min) ** (i / samples);
    const [nr, ni] = polyAtJw(numerator, w);
    const [dr, di] = polyAtJw(denominator, w);
    const den = dr * dr + di * di;
    const gr = (gain * (nr * dr + ni * di)) / den;
    const gi = (gain * (ni * dr - nr * di)) / den;
    mag.push([w, 20 * Math.log10(Math.hypot(gr, gi))]);
    let ph = (Math.atan2(gi, gr) * 180) / Math.PI - (delay * w * 180) / Math.PI;
    if (prev != null) {
      while (ph + offset - prev > 180) offset -= 360;
      while (ph + offset - prev < -180) offset += 360;
    }
    prev = ph + offset;
    phase.push([w, prev]);
  }
  // atan2 only knows the phase modulo 360: start the curve where the textbook does,
  // at -90 deg per net integrator (s factors in the denominator minus those in the numerator)
  const zerosAtOrigin = (c) => {
    let k = 0;
    for (let i = c.length - 1; i >= 0 && c[i] === 0; i--) k++;
    return k;
  };
  const expected = -90 * (zerosAtOrigin(denominator) - zerosAtOrigin(numerator)) + (gain < 0 ? -180 : 0);
  const shift = Math.round((expected - phase[0][1]) / 360) * 360;
  if (shift) for (const p of phase) p[1] += shift;
  return { mag, phase };
}
