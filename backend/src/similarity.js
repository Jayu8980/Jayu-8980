// Text-similarity duplicate check against the PYQ bank and the generated bank.
// Word 3-gram Jaccard catches light rewording; token cosine catches reordered sentences.

const STOP = new Set("the a an of is are to in and for with at on by be as that this which its it from".split(" "));

export function normalizeText(s) {
  return String(s ?? "")
    .toLowerCase()
    .replace(/[0-9]+(\.[0-9]+)?/g, " # ")
    .replace(/[^a-z#\s]/g, " ")
    .split(/\s+/)
    .filter((w) => w && !STOP.has(w));
}

function shingles(words, n = 3) {
  const out = new Set();
  for (let i = 0; i + n <= words.length; i++) out.add(words.slice(i, i + n).join(" "));
  return out;
}

export function jaccard(a, b) {
  const A = shingles(normalizeText(a));
  const B = shingles(normalizeText(b));
  if (!A.size || !B.size) return 0;
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  return inter / (A.size + B.size - inter);
}

export function cosine(a, b) {
  const tf = (words) => words.reduce((m, w) => m.set(w, (m.get(w) ?? 0) + 1), new Map());
  const A = tf(normalizeText(a));
  const B = tf(normalizeText(b));
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (const [w, v] of A) {
    na += v * v;
    if (B.has(w)) dot += v * B.get(w);
  }
  for (const v of B.values()) nb += v * v;
  return na && nb ? dot / Math.sqrt(na * nb) : 0;
}

export function similarity(a, b) {
  return Math.max(jaccard(a, b), cosine(a, b) >= 0.92 ? cosine(a, b) : 0);
}

/** Returns the closest bank entry above the threshold, or null. */
export function findDuplicate(text, bank, threshold = 0.6) {
  let best = null;
  for (const item of bank) {
    const other = typeof item === "string" ? item : item.question ?? item.summary;
    if (!other) continue;
    const s = similarity(text, other);
    if (s >= threshold && (!best || s > best.score)) best = { score: s, item };
  }
  return best;
}
