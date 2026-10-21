// Display list -> SVG, used by the backend paper preview and by tests.
const THEMES = {
  light: { fg: "#1d2433", muted: "#5f6b7a", grid: "#e3e7ee", accent: "#b5530b", bg: "#ffffff", s0: "#2f62c9", s1: "#d4762a", s2: "#21927f", s3: "#bf3f72", s4: "#6f55c9", s5: "#5f8f2c" },
  dark: { fg: "#e8ecf3", muted: "#a2acba", grid: "#2c3340", accent: "#f0a35e", bg: "#161a22", s0: "#7aa2f2", s1: "#f0a35e", s2: "#5fc9b3", s3: "#f07fa8", s4: "#a995f0", s5: "#9ccc65" },
};

const esc = (t) => String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export function toSvg(render, theme = "light") {
  const c = THEMES[theme] ?? THEMES.light;
  const col = (k) => c[k] ?? k;
  const out = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${render.w} ${render.h}" width="${render.w}" height="${render.h}" font-family="sans-serif">`, `<rect width="100%" height="100%" fill="${c.bg}"/>`];
  for (const it of render.items) {
    if (it.k === "path") {
      const pts = [];
      for (let i = 0; i < it.p.length; i += 2) pts.push(`${it.p[i]},${it.p[i + 1]}`);
      const tag = it.close ? "polygon" : "polyline";
      out.push(`<${tag} points="${pts.join(" ")}" fill="${it.f ? col(it.f) : "none"}" stroke="${col(it.s)}" stroke-width="${it.w}" stroke-linejoin="round" stroke-linecap="round"${it.d ? ' stroke-dasharray="6 4"' : ""}/>`);
    } else if (it.k === "circle") {
      out.push(`<circle cx="${it.c[0]}" cy="${it.c[1]}" r="${it.r}" fill="${it.f ? col(it.f) : "none"}" stroke="${col(it.s)}" stroke-width="${it.w}"/>`);
    } else if (it.k === "text") {
      const anchor = { s: "start", m: "middle", e: "end" }[it.a] ?? "middle";
      out.push(`<text x="${it.p[0]}" y="${it.p[1]}" font-size="${it.z}" fill="${col(it.s)}" text-anchor="${anchor}" dominant-baseline="central"${it.b ? ' font-weight="bold"' : ""}>${esc(it.t)}</text>`);
    }
  }
  out.push("</svg>");
  return out.join("");
}
