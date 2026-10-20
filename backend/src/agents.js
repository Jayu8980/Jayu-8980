// One function per agent. Each call is a separate LLM request; inputs/outputs are saved as JSON.
import { MASTER_PROMPT, ROLE_PROMPTS, solverView } from "./prompts.js";
import { SETTER_PERSONAS, SOLVER_STRATEGIES } from "./config.js";

export function makeAgents({ llm, config, store }) {
  async function call(agent, rolePrompt, model, effort, task, input) {
    const prompt = `${task}\n\nINPUT:\n${JSON.stringify(input)}`;
    const started = Date.now();
    const res = await llm.completeJson({ agent, model, effort, system: `${MASTER_PROMPT}\n\n${rolePrompt}`, prompt });
    await store?.saveCall(agent, { model: res.model ?? model, ms: Date.now() - started, input, output: res.json, usage: res.usage });
    return res.json;
  }

  return {
    async blueprint({ skeleton, request, analysis, avoid }) {
      const out = await call(
        "blueprint",
        ROLE_PROMPTS.blueprint,
        config.models.blueprint,
        config.effort.blueprint,
        `Plan the paper for mode "${request.mode}". Keep every slot's qno, section, subject_id, marks and type.`,
        { request, skeleton, pyq_analysis: analysis, user_weak_topics: request.weak_topics ?? [], previously_generated: avoid },
      );
      return out.blueprint ?? [];
    },

    /** Every setter writes the whole batch independently; returns [{setter, model, questions}]. */
    async setters({ slots, request, round, avoid }) {
      const models = config.models.setters;
      return Promise.all(
        models.map(async (model, i) => {
          const name = `setter:${i}`;
          try {
            const out = await call(
              name,
              ROLE_PROMPTS.setter(SETTER_PERSONAS[i % SETTER_PERSONAS.length]),
              model,
              config.effort.setter,
              `Write one question for each of the ${slots.length} blueprint slots.`,
              { mode: request.mode, round, slots, avoid_similar_to: avoid },
            );
            return { setter: name, model, questions: out.questions ?? [] };
          } catch (e) {
            return { setter: name, model, questions: [], error: e.message };
          }
        }),
      );
    },

    async review({ slotsWithCandidates }) {
      const out = await call(
        "reviewer",
        ROLE_PROMPTS.reviewer,
        config.models.reviewer,
        config.effort.reviewer,
        `Choose and improve the best candidate for each of the ${slotsWithCandidates.length} slots.`,
        { slots: slotsWithCandidates },
      );
      return out.questions ?? [];
    },

    /** K independent solvers with different models/strategies; none sees the answer key. */
    async solvers({ questions }) {
      const view = questions.map(solverView);
      return Promise.all(
        config.models.solvers.map(async (model, i) => {
          const name = `solver:${i}`;
          try {
            const out = await call(name, ROLE_PROMPTS.solver(SOLVER_STRATEGIES[i % SOLVER_STRATEGIES.length]), model, config.effort.solver, `Solve all ${view.length} questions.`, {
              questions: view,
            });
            return { agent: name, model, answers: out.answers ?? [] };
          } catch (e) {
            return { agent: name, model, answers: [], error: e.message };
          }
        }),
      );
    },

    async adjudicate({ disputes }) {
      const out = await call(
        "adjudicator",
        ROLE_PROMPTS.adjudicator,
        config.models.adjudicator,
        config.effort.adjudicator,
        `Resolve the answer disputes for ${disputes.length} questions.`,
        { questions: disputes },
      );
      return out.verdicts ?? [];
    },

    async moderate({ questions, request, avoid }) {
      return call("moderator", ROLE_PROMPTS.moderator, config.models.moderator, config.effort.moderator, `Moderate these ${questions.length} questions for mode "${request.mode}".`, {
        questions,
        avoid_similar_to: avoid,
      });
    },
  };
}
