# GATE EE Prep: Android app + multi-agent AI paper setter

An Android app (Kotlin + Jetpack Compose) for GATE Electrical Engineering preparation, built from
`GATE_EE_App_Master_Prompt.pdf`. It has PYQ analysis, a GATE-pattern mock test series, an AI live mock
generator, and an AI paper setter. The paper setter is a **committee of AI agents**: several agents
design each question and several agents solve it independently, so an answer reaches the student
only after they agree on it.

```
android/            Android app (Kotlin, Compose, Room) + pure-Kotlin core logic module
  core/             scoring, PYQ analysis, calculator, offline test assembly (unit-tested on the JVM)
  app/              screens, Room database, backend client, reminders
backend/            Node.js server: holds the LLM API key and runs the multi-agent pipeline
  src/pipeline.js   Blueprint -> Setter committee -> Review -> Solver ensemble -> Consensus/Adjudicator -> Moderator -> Validation
data/               shared JSON used by both the app (as assets) and the backend
  syllabus.json             official GATE EE syllabus: subjects -> topics -> subtopics
  sample_questions.json     38 original GATE EE questions showing the bank format (MCQ/MSQ/NAT, all subjects, 8 with figures)
  pyq_tags_demo.json        SYNTHETIC PYQ tags 2010-2026 (placeholder: replace with real tagged PYQs)
  mock_series.json          fixed series: 20 topic-wise, 10 subject-wise, 10 full-length tests
  formula_sheets.json       formula sheet for every subject
tools/              data generators and the CSV -> pyq_bank.json importer
docs/               architecture, database schema, APK build steps, PYQ data format
```

## What the master prompt asked for, and where it is

| Requirement | Implementation |
|---|---|
| Module 1 – PYQ analysis (subject/topic marks per year, 5/10-year averages, repeated concepts, rising/falling trends, MCQ/MSQ/NAT split, difficulty distribution, High-Priority = marks × frequency, Gap topics) | `core/AnalysisEngine.kt` (in the app, works offline), `backend/src/analysis.js`; screen `AnalysisScreen.kt` |
| Filterable PYQ practice with full solutions | `PyqPracticeScreen.kt`, `QuestionDetailScreen.kt` |
| Module 2 – mock series, real pattern (65 Q / 100 marks / 180 min, GA 10 Q, Technical 55 Q, negative marking, weightage, type mix, difficulty) | `backend/src/pattern.js` (skeleton), `data/mock_series.json`, `SeriesScreen.kt` |
| Each question: step-by-step solution, concept, common trap, related PYQ | question schema in `data/sample_questions.json`; shown in `QuestionDetailScreen.kt` |
| Module 3 – live mock agent: ANALYSE → PREDICT → GENERATE; modes Full / Subject / Topic / Weak-Area / Daily / Custom; personalisation; offline save; "Report wrong question" | `backend/src/analysis.js` + `pipeline.js`; `AiMockScreen.kt`; topic-strength map in `Repository.submit` |
| Module 4 – paper-setter pipeline (Blueprint, Setter, Solver, Moderator, Final Paper, Paper Report), JSON per step | `backend/src/pipeline.js`, `agents.js`, `prompts.js`; every call saved in `backend/runs/<job>/`; `PaperReportScreen.kt` |
| Agent system prompt sent with every call | `MASTER_PROMPT` in `backend/src/prompts.js` |
| Validation layer (schema, solver vs setter, NAT recomputed in code, MSQ ≥ 1 correct, duplicates, totals, regenerate missing slots) | `backend/src/validation.js`, `consensus.js`, `expr.js`, `similarity.js` |
| GATE test interface (palette states, mark for review, scientific calculator, countdown, sections, auto-submit, NAT keypad, MSQ checkboxes) | `TestScreen.kt`, `TestViewModel.kt`, `CalculatorDialog.kt`, `core/Calculator.kt` |
| Results: negative marking, rank/percentile estimate, accuracy, time per question, subject/topic strengths, silly vs concept vs time-pressure errors, revise-next vs PYQ weightage, progress graph | `core/Scoring.kt`, `ResultScreen.kt`, `ProgressScreen.kt` |
| Offline mode, bookmarks, notes, wrong-answer list, formula sheets, streak + reminders, dark mode | Room DB, `RevisionScreen.kt`, `FormulaScreen.kt`, `reminders/Reminders.kt`, `SettingsScreen.kt` |
| **Figure questions**: circuit diagrams, waveforms and graphs, phasor diagrams, Bode / pole-zero plots, block and logic diagrams, bar / pie charts and tables | `backend/src/figures/` (validate, describe, lay out), `FIGURE_GUIDE` in `prompts.js`, `FigureView.kt`; see [docs/FIGURES.md](docs/FIGURES.md) |
| API key never in the APK | the key lives only in the backend's environment; the app sends an optional `APP_CLIENT_TOKEN` |

## Multi-agent question design and answer verification

```
           PYQ bank ──► ANALYSE + PREDICT (code: topic map, trend, gap, likelihood score)
                                  │
                       code skeleton (exact GATE counts / marks / type mix)
                                  ▼
                        1. Blueprint Agent  ── plans topic, subtopic, level, difficulty per slot
                                  ▼
          ┌────────── 2. Setter committee (N agents, different models and personas) ──────────┐
          │   Setter A: concept-first      Setter B: numerical realism     (Setter C: twists)  │
          └───────────────────────────────┬───────────────────────────────────────────────────┘
                     every draft → schema check + duplicate check (code)
                                  ▼
                        3. Design Review Agent ── picks the best draft per slot, re-derives it, sharpens distractors
                                  ▼
          ┌──────── 4. Solver ensemble (K agents, no answer key, different models and methods) ┐
          │   Solver 1: direct method   Solver 2: alternate method   Solver 3: back-substitution check │
          └───────────────────────────────┬────────────────────────────────────────────────────┘
                                  ▼
                        5. Consensus (code)
                           all solvers = setter, NAT recomputed in code  → verified (unanimous)
                           any disagreement / code mismatch → Adjudicator Agent re-solves
                              final answer needs ≥ quorum votes (solvers + adjudicator) → verified / corrected
                              otherwise → rejected, slot regenerated
                                  ▼
                        6. Moderator Agent ── approve / fix / reject; a fix is re-solved by the ensemble
                                  ▼
                        7. Validation layer (code) ── schema, totals, type mix, duplicates
                                  ▼
                        Final paper + Paper Report + per-question verification report
```

Every question in a generated paper carries a `verification_report`: which setter wrote it, each
solver's answer and whether it agreed, the adjudicator's ruling, and the result of the code check.
The app shows this on the Paper Report and on each question ("Verified: unanimous (3/3)").

The models are configurable per role (`backend/.env.example`). The defaults use Claude Opus 5.5 and
Claude Sonnet 5.5 side by side, so the setters and solvers are not all the same model. The
provider interface (`completeJson`) in `backend/src/llm.js` is small, so a second vendor can be added
as another committee member.

## Questions with figures

The setters can attach a **figure** to a question: a circuit, a waveform or characteristic graph, a
phasor diagram, a Bode or pole-zero plot, a block or logic diagram, or a chart or table. About 30%
of a full mock has figures by default (`figures: none | some | many`). Agents write figures as
structured JSON, not images. The backend validates each figure: for example, a circuit with an
unconnected pin is rejected. It then turns the figure into exact text for the solver agents (a
netlist, curve points, phasor values) and into drawing primitives that the app renders natively,
offline and in dark mode. Because solvers work from that description, a figure that contradicts the
question text fails the answer vote. Details and the figure language: [docs/FIGURES.md](docs/FIGURES.md).

## Quick start

**Backend** (needs Node 20+):

```bash
cd backend
npm install
cp .env.example .env          # put ANTHROPIC_API_KEY and APP_CLIENT_TOKEN in .env
npm start                     # http://localhost:8080
npm test                      # 20 tests: pattern, consensus, adjudication, regeneration, figures, API
```

If no `ANTHROPIC_API_KEY` is set, the server runs an offline **mock** generator. It produces
placeholder questions, so you can try the app and the pipeline without spending API credit.

Pre-generate the fixed series so it ships inside the APK:

```bash
npm run build-series                       # all 40 tests -> data/generated/*.json
npm run build-series -- --only TT01,FM01   # just some
```

**App:** open `android/` in Android Studio and press Run. Full steps, including how to build a
signed APK, are in [docs/BUILD_APK.md](docs/BUILD_APK.md). In the app, go to Settings → AI backend
and enter your backend URL. The emulator reaches your computer at `http://10.0.2.2:8080`.

**CI:** `.github/workflows/build.yml` runs the backend tests and the core unit tests on every push,
builds the debug and release APKs, and uploads them as the `gate-ee-apk` artifact.

## Important notes

- **PYQ data:** `data/pyq_tags_demo.json` is synthetic and is not real GATE data. It is only there so
  the analysis screens work out of the box, and the app shows a "demo data" banner while it is in
  use. To get real analysis, put tagged GATE EE PYQs (2010–2026) in `data/pyq_bank.json`. A
  spreadsheet can be converted with `node tools/csv_to_pyq_bank.mjs file.csv`. See
  [docs/PYQ_DATA.md](docs/PYQ_DATA.md).
- **Mock series content:** the 40 series tests are filled by the AI pipeline (`npm run build-series`
  or "Generate with AI" in the app). Until then, "Offline" builds a test from the questions already
  on the phone.
- **Rank and percentile** are estimates from a model of the GATE EE score distribution, not official
  figures.
- Rename the app in `android/app/src/main/res/values/strings.xml`.
