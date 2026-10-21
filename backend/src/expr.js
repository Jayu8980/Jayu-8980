// Safe arithmetic evaluator for NAT recomputation (no eval / Function).
// Grammar: expr := term (('+'|'-') term)* ; term := factor (('*'|'/') factor)* ;
// factor := ('-'|'+') factor | power ; power := primary ('^' factor)? ;
// primary := number | ident | ident '(' args ')' | '(' expr ')'

const FUNCS = {
  sqrt: Math.sqrt,
  cbrt: Math.cbrt,
  exp: Math.exp,
  ln: Math.log,
  log: Math.log10,
  log10: Math.log10,
  log2: Math.log2,
  sin: Math.sin,
  cos: Math.cos,
  tan: Math.tan,
  asin: Math.asin,
  acos: Math.acos,
  atan: Math.atan,
  atan2: Math.atan2,
  sinh: Math.sinh,
  cosh: Math.cosh,
  tanh: Math.tanh,
  abs: Math.abs,
  min: Math.min,
  max: Math.max,
  pow: Math.pow,
  hypot: Math.hypot,
  deg: (r) => (r * 180) / Math.PI,
  rad: (d) => (d * Math.PI) / 180,
  sind: (d) => Math.sin((d * Math.PI) / 180),
  cosd: (d) => Math.cos((d * Math.PI) / 180),
  tand: (d) => Math.tan((d * Math.PI) / 180),
};
const CONSTS = { pi: Math.PI, e: Math.E, mu0: 4e-7 * Math.PI, eps0: 8.854187817e-12 };

function tokenize(src) {
  const tokens = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
    } else if (/[0-9.]/.test(c)) {
      const m = /^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/.exec(src.slice(i));
      if (!m) throw new Error(`bad number at ${i}`);
      tokens.push({ t: "num", v: parseFloat(m[0]) });
      i += m[0].length;
    } else if (/[A-Za-z_]/.test(c)) {
      const m = /^[A-Za-z_][A-Za-z_0-9]*/.exec(src.slice(i));
      tokens.push({ t: "id", v: m[0] });
      i += m[0].length;
    } else if ("+-*/^(),".includes(c)) {
      tokens.push({ t: c });
      i++;
    } else {
      throw new Error(`unexpected character '${c}' at ${i}`);
    }
  }
  return tokens;
}

export function evaluate(src, variables = {}) {
  if (typeof src !== "string" || src.length > 500) throw new Error("expression missing or too long");
  const tokens = tokenize(src);
  let p = 0;
  const peek = () => tokens[p];
  const eat = (t) => {
    if (tokens[p]?.t !== t) throw new Error(`expected '${t}'`);
    return tokens[p++];
  };

  function expr() {
    let v = term();
    while (peek()?.t === "+" || peek()?.t === "-") v = eat(peek().t).t === "+" ? v + term() : v - term();
    return v;
  }
  function term() {
    let v = factor();
    while (peek()?.t === "*" || peek()?.t === "/") v = eat(peek().t).t === "*" ? v * factor() : v / factor();
    return v;
  }
  // unary minus binds looser than '^', so -2^2 = -4 as on a scientific calculator
  function factor() {
    if (peek()?.t === "-") {
      eat("-");
      return -factor();
    }
    if (peek()?.t === "+") {
      eat("+");
      return factor();
    }
    const base = primary();
    if (peek()?.t === "^") {
      eat("^");
      return Math.pow(base, factor());
    }
    return base;
  }
  function primary() {
    const tok = peek();
    if (!tok) throw new Error("unexpected end");
    if (tok.t === "num") return eat("num").v;
    if (tok.t === "(") {
      eat("(");
      const v = expr();
      eat(")");
      return v;
    }
    if (tok.t === "id") {
      const name = eat("id").v;
      if (peek()?.t === "(") {
        const fn = FUNCS[name];
        if (!fn) throw new Error(`unknown function ${name}`);
        eat("(");
        const args = [expr()];
        while (peek()?.t === ",") {
          eat(",");
          args.push(expr());
        }
        eat(")");
        return fn(...args);
      }
      if (Object.hasOwn(variables, name)) return Number(variables[name]);
      if (Object.hasOwn(CONSTS, name)) return CONSTS[name];
      throw new Error(`unknown variable ${name}`);
    }
    throw new Error(`unexpected token ${tok.t}`);
  }

  const v = expr();
  if (p !== tokens.length) throw new Error("trailing tokens");
  if (!Number.isFinite(v)) throw new Error("result is not finite");
  return v;
}
