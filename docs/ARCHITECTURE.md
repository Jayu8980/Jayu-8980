# Architecture

## Components

```
┌──────────────── Android app (offline-first) ────────────────┐        ┌───────────── Backend (Node.js) ─────────────┐
│ Compose UI ─ ViewModels ─ Repository ─ Room (SQLite)        │ HTTPS  │ Express API  (/api/generate, /api/jobs/...)  │
│                     │                                        │◄──────►│ Multi-agent pipeline (pipeline.js)          │
│ core/ (pure Kotlin): Scoring, AnalysisEngine, Calculator,    │        │ Validation layer (code)                      │
│        OfflineAssembler, TopicStrength                        │        │ LLM client (Anthropic SDK) ─ API key in env │
│ assets = /data (syllabus, banks, series, formulas)           │        │ runs/<job>/*.json  (audit trail per step)   │
└──────────────────────────────────────────────────────────────┘        └──────────────────────────────────────────────┘
```

* The APK never contains an LLM key. It knows only the backend URL and an optional shared
  `APP_CLIENT_TOKEN`, which the backend checks together with a per-client hourly rate limit.
* Everything the student uses works offline: PYQ analysis is computed on the phone from the bundled
  bank, tests and attempts live in Room, and downloaded AI papers are stored as ordinary tests.
  Only *new* AI generation needs the network.

## Backend pipeline (backend/src)

| File | Role |
|---|---|
| `pattern.js` | GATE EE constants, weightage, and `buildSkeleton()`: exact counts, marks, MCQ/MSQ/NAT mix, difficulty (30/45/25) and cognitive mix (20/45/35), at most 2 questions per subtopic, hard questions spread out |
| `analysis.js` | ANALYSE + PREDICT: per-topic frequency, marks, last asked, trend slope, gap flag, likelihood score `0.45·importance + 0.2·trend + 0.2·gap + 0.15·recency` |
| `prompts.js` | The master "Agent System Prompt" plus one role prompt per agent |
| `agents.js` | One function per agent; every call is a separate LLM request saved as JSON |
| `pipeline.js` | Orchestration, batching (5 slots per call), concurrency, regeneration rounds |
| `consensus.js` | Answer normalisation (letters, MSQ sets, NAT tolerance), voting, adjudication decision |
| `validation.js` | Schema, NAT range and width, NAT recomputation, duplicates, paper totals |
| `expr.js` | Safe arithmetic evaluator used to recompute NAT answers (no `eval`) |
| `similarity.js` | Word 3-gram Jaccard + token cosine duplicate detection |
| `llm.js` | Anthropic SDK client: streaming, adaptive thinking, effort per role, server-side refusal fallback, JSON parsing with one retry |
| `mockLlm.js` | Deterministic offline provider used by tests and when no key is set |
| `figures/` | Figure language: `spec.js` validation (incl. circuit connectivity), `describe.js` text for solvers, `layout.js` display list, `svg.js` preview renderer, `math.js` ticks / sampling / Bode |
| `server.js` | REST API, job tracking, `/preview/:id` HTML paper preview |

### Agents and how they check each other

1. **Blueprint Agent**: gets the fixed skeleton and fills in topic, subtopic, cognitive level,
   difficulty and intent for each slot, using the PYQ analysis and the student's weak topics. If it
   changes a field the code owns (count, marks or type), the change is ignored.
2. **Setter committee**: N setters (default 2: Opus 5.5 "concept-first", Sonnet 5.5
   "numerical realism") each draft *every* slot independently. Each NAT includes a `verification`
   expression so the code can recompute it.
3. **Design Review Agent**: compares the drafts for each slot, picks the best one, and re-derives it
   to fix errors and sharpen the distractors.
4. **Solver ensemble**: K solvers (default 3: Opus "direct method", Sonnet "alternate method",
   Opus "checker: back-substitution, limiting cases, option-by-option") solve without the answer key.
5. **Consensus + Adjudicator**: if every solver agrees with the setter and the code check passes,
   the answer is verified. Otherwise the **Adjudicator Agent** (effort `max`) sees every working and
   rules `setter_correct`, `corrected` or `ambiguous`. The final answer must be backed by at least
   `CONSENSUS_QUORUM` votes among the solvers plus the adjudicator. If not, the slot is regenerated.
   A failed NAT recomputation can only be cleared by the adjudicator, never by a vote alone.
6. **Moderator Agent**: approves, fixes or rejects each question and estimates the topper score,
   average and cutoff. A fix that changes the question or its answer goes back through the solver
   ensemble.
7. **Validation layer** (code): schema, totals, type mix, duplicates against the PYQ bank, earlier
   generated papers and the current paper, and figure validation. Figure slots must carry a valid
   figure. Solvers, the adjudicator and the moderator see the code-generated figure description.
   See [FIGURES.md](FIGURES.md).

Rejected slots are regenerated, up to `MAX_REGEN_ROUNDS`. The paper status is `complete` or
`completed_with_gaps` (with `missing_qnos`).

### API

| Method | Path | Body / result |
|---|---|---|
| GET | `/health` | provider, whether the PYQ data is demo |
| GET | `/api/analysis` | full PYQ analysis |
| POST | `/api/generate` | `{mode, subjects?, topics?, question_count?, difficulty?, type_mix?, weak_topics?, avoid_summaries?, title?}` → `202 {job_id}` |
| GET | `/api/jobs/:id` | `{status, stage, progress, log[]}` |
| GET | `/api/jobs/:id/paper` | final paper JSON (same schema as the master prompt, plus `verification_report`) |
| POST | `/api/report-question` | `{question_id, reason}`; the question is excluded from the generated bank |

## App (android/)

```
core/src/main/kotlin/com/gateee/core/
  Models.kt            question / paper / PYQ / syllabus / series JSON models
  Scoring.kt           GATE marking, error classification, result summary, percentile model, topic strength
  AnalysisEngine.kt    Module 1 analysis + revise-next suggestions
  Calculator.kt        scientific calculator engine (also recomputes NAT expressions)
  OfflineAssembler.kt  builds tests from the on-phone bank without network
app/src/main/kotlin/com/gateee/app/
  GateApp.kt, MainActivity.kt        app container, theme (system / light / dark)
  data/Database.kt                   Room entities + DAOs
  data/Repository.kt                 seeding from assets, tests, attempts, scoring, strengths, streak
  data/BackendClient.kt              OkHttp client for the backend
  data/SettingsStore.kt              DataStore settings
  reminders/Reminders.kt             WorkManager daily reminder
  ui/AppNav.kt                       navigation graph
  ui/components/FigureView.kt        draws question figures from their display list (zoomable)
  ui/screens/*                       Home, PYQ Analysis, PYQ Practice, Series, AI Mock, Paper Report,
                                     Test (+ViewModel, calculator), Result, Progress, Revision,
                                     Question detail, Formula sheets, Settings
```

### Exam rules implemented

* Palette states: Not visited, Not answered, Answered, Marked for review, Answered & marked. As in
  GATE, "Answered & marked" is evaluated.
* A selection counts only after **Save & Next** or **Mark for Review & Next**. Jumping away
  discards an unsaved choice.
* MCQ −1/3 (1-mark) and −2/3 (2-mark). MSQ and NAT have no negative marks and no partial credit. NAT
  is correct inside the question's `answer_range`.
* The clock keeps running if the student leaves the test, and the test auto-submits at zero.

### Error classification (per wrong answer)

* **Time pressure**: answered in the last 10% of the test, or a 2-mark question answered in under
  a quarter of its expected time.
* **Silly mistake**: a NAT answer within 10% of the key, an MSQ answer one option off, or the topic
  was already strong (≥ 70%) before the test.
* **Concept gap**: everything else.
