// Deterministic offline stand-in for the LLM, used by tests and `npm run demo` when no API key
// is configured. It produces simple parametric questions so the whole pipeline (committee,
// consensus, adjudication, moderation, validation) can run end-to-end without network access.

const truth = new Map(); // question text -> correct answer (what a perfect solver would find)

function input(prompt) {
  const i = prompt.indexOf("INPUT:\n");
  return JSON.parse(prompt.slice(i + 7));
}

const WORDS = "amber basalt cedar delta ember fjord granite harbor indigo juniper kestrel lagoon meadow nectar onyx prairie quartz raven sierra tundra umber violet willow xenon yarrow zephyr anchor beacon canyon dune estuary falcon glacier heron island jasper krypton lantern mesa nebula orchid pebble quill ridge summit thistle upland valley wharf yonder zenith".split(" ");

// unique filler so mock questions are not flagged as duplicates of each other
function scenario(seed) {
  let x = seed * 2654435761;
  const out = [];
  for (let i = 0; i < 12; i++) {
    x = (x * 1103515245 + 12345) >>> 0;
    out.push(WORDS[x % WORDS.length]);
  }
  return out.join(" ");
}

function makeQuestion(slot, setterIdx, round = 0) {
  const tag = `[${slot.subject_id} #${slot.qno}/${setterIdx}${round ? " regen" + round : ""}] Scenario ${scenario(slot.qno * 31 + setterIdx * 7 + round * 1009)}.`;
  const k = slot.qno + 3 * setterIdx;
  const V = 100 + 10 * k;
  const R = 5 + (k % 7);
  const base = {
    ...slot,
    subject: slot.subject ?? slot.subject_id,
    topic: slot.topic ?? slot.topic_id,
    concept: "Ohm's law and power",
    common_trap: "Using V/R^2 instead of V^2/R",
    distractor_logic: "Each wrong option applies one common slip",
    related_pyq_year: "",
    predicted_importance: "Medium",
    time_estimate_sec: slot.marks === 1 ? 90 : 180,
    verification: null,
    answer_range: null,
  };
  const p = (V * V) / R;
  // figure slots get a schematic of the same source-and-resistor circuit
  if (slot.figure) {
    base.figure = {
      type: "circuit",
      caption: `Circuit for question ${slot.qno}`,
      elements: [
        { kind: "V", from: [0, 2], to: [0, 0], label: `${V} V` },
        { kind: "wire", from: [0, 0], to: [2, 0] },
        { kind: "R", from: [2, 0], to: [2, 2], label: `${R} Ω` },
        { kind: "wire", from: [0, 2], to: [2, 2] },
        { kind: "ground", at: [0, 2] },
      ],
    };
  }
  if (slot.type === "NAT") {
    const q = `${tag} A ${R} ohm resistor is connected across an ideal ${V} V DC source. The power dissipated is ______ W (round off to 1 decimal place).`;
    const ans = Math.round(p * 10) / 10;
    truth.set(q, String(ans));
    return { ...base, question: q, options: [], answer: String(ans), answer_range: { min: ans - 0.1, max: ans + 0.1 }, solution: `P = V^2/R = ${V}^2/${R} = ${ans} W.`, verification: { expression: "V^2/R", variables: { V, R } } };
  }
  if (slot.type === "MSQ") {
    const q = `${tag} A ${R} ohm resistor is connected across an ideal ${V} V DC source. Which of the following is/are TRUE?`;
    truth.set(q, "A,C");
    return { ...base, question: q, options: [`The current is ${(V / R).toFixed(2)} A`, `The power is ${(p / 2).toFixed(1)} W`, `The conductance is ${(1 / R).toFixed(3)} S`, `The current is ${(V * R).toFixed(0)} A`], answer: "A,C", solution: `I = V/R = ${(V / R).toFixed(2)} A, P = ${p.toFixed(1)} W, G = 1/R.` };
  }
  const q = `${tag} A ${R} ohm resistor is connected across an ideal ${V} V DC source. The power dissipated is`;
  truth.set(q, "B");
  return { ...base, question: q, options: [`${(V / R).toFixed(1)} W`, `${p.toFixed(1)} W`, `${(V / (R * R)).toFixed(1)} W`, `${(p / 2).toFixed(1)} W`], answer: "B", solution: `P = V^2/R = ${p.toFixed(1)} W.` };
}

export function createMockLlm({ faultySolver = false, rejectQnos = [] } = {}) {
  async function completeJson({ agent = "", prompt }) {
    const data = input(prompt);
    const role = agent.split(":")[0];
    switch (role) {
      case "blueprint":
        return { json: { blueprint: data.skeleton.map((s) => ({ ...s, intent: "test the concept" })) }, model: "mock" };
      case "setter": {
        const idx = Number(agent.split(":")[1] ?? 0);
        return { json: { questions: data.slots.map((s) => makeQuestion(s, idx, data.round ?? 0)) }, model: "mock" };
      }
      case "reviewer":
        return {
          json: { questions: data.slots.map((s) => ({ ...s.candidates[0].question, chosen_from: s.candidates[0].setter, review_notes: "mock review" })) },
          model: "mock",
        };
      case "solver": {
        const idx = Number(agent.split(":")[1] ?? 0);
        return {
          json: {
            answers: data.questions.map((q) => {
              let a = truth.get(q.question) ?? "A";
              if (faultySolver && idx === 1 && q.qno % 4 === 0) a = q.type === "NAT" ? String(Number(a) * 2) : q.type === "MCQ" ? "C" : "A";
              return { qno: q.qno, answer: a, confidence: 0.9, working: "mock", issues: "" };
            }),
          },
          model: "mock",
        };
      }
      case "adjudicator":
        return {
          json: { verdicts: data.questions.map((q) => ({ qno: q.qno, verdict: "setter_correct", final_answer: truth.get(q.question) ?? q.setter_answer, reason: "mock re-solve" })) },
          model: "mock",
        };
      case "moderator":
        return {
          json: {
            decisions: data.questions.map((q) => ({ qno: q.qno, action: rejectQnos.includes(q.qno) && !q.question.includes("regen") ? "reject" : "approve", reasons: "mock", difficulty_actual: q.difficulty })),
            balance_notes: "mock balance",
            expected_average_score: 35,
            expected_topper_score: 85,
            expected_cutoff: 28,
          },
          model: "mock",
        };
      default:
        throw new Error(`mock llm: unknown agent ${agent}`);
    }
  }
  return { name: "mock", completeJson };
}
