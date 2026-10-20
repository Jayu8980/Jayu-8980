# Database schema (Room / SQLite, `gate_ee.db`)

| Table | Key | Columns | Purpose |
|---|---|---|---|
| `questions` | `id` | subjectId, topicId, subtopic, type, marks, difficulty, source (`sample` / `pyq` / `series` / `ai_generated`), year, **json** (full question), reported, createdAt | The question bank. `json` holds the complete question in the bank format, so new fields need no migration. `reported = 1` removes a question from new tests. |
| `tests` | `id` | title, kind (`topic` / `subject` / `full` / `daily` / `weak_area` / `custom`), seriesId, questionIdsJson, durationMin, totalMarks, paperReportJson, verificationJson, blueprintJson, createdAt | A paper: a series test, a downloaded AI paper, or an offline-assembled test |
| `attempts` | `id` (auto) | testId, startedAt, submittedAt, score, maxScore, correct, wrong, unattempted, timeTakenSec, percentile, estimatedRank, autoSubmitted, currentIndex | One sitting. `submittedAt = NULL` means in progress, so the test can be resumed. |
| `responses` | (`attemptId`, `questionId`) | answer, status (palette state), timeSpentSec, answeredAtSec, isCorrect, marksAwarded, errorType | Per-question answer, timing and evaluation |
| `bookmarks` | `questionId` | createdAt | Bookmarked questions |
| `notes` | `questionId` | text, updatedAt | Personal notes |
| `topic_strength` | `topicId` | subjectId, strength (0–1, EMA α = 0.35), attempts, correct, updatedAt | Personalisation map. It feeds Weak-Area mocks and the AI generator. |
| `study_days` | `date` (yyyy-MM-dd) | questions | Daily streak and reminder target |

The wrong-answer revision list is a query: `responses.isCorrect = 0` joined with submitted attempts.

## Bank JSON format (data/*.json)

The question format is the same everywhere: the sample bank, AI papers and the backend output.
`data/sample_questions.json` has 30 examples.

```json
{
  "id": "SAMPLE-015", "qno": 15, "section": "Technical",
  "subject_id": "EM", "subject": "Electrical Machines", "topic_id": "EM.SPTX", "topic": "Single-Phase Transformer",
  "subtopic": "Regulation and efficiency", "type": "NAT", "marks": 2, "negative_marks": 0,
  "cognitive_level": "Analysis", "difficulty": "Medium",
  "question": "A 20 kVA, 2000/200 V ...", "options": [],
  "answer": "96.59", "answer_range": {"min": 96.55, "max": 96.62},
  "solution": "step-by-step ...", "concept": "...", "common_trap": "...", "distractor_logic": "...",
  "related_pyq_year": "", "predicted_importance": "High", "time_estimate_sec": 180,
  "verification": {"expression": "100*sqrt(Pi/Pcu)*S*pf/(sqrt(Pi/Pcu)*S*pf + 2*Pi)", "variables": {"Pi": 200, "Pcu": 400, "S": 20000, "pf": 0.8}},
  "verification_report": {"method": "unanimous", "support": "3/3", "solver_votes": [...], "code_check": "pass"}
}
```

Answer encoding: MCQ `"B"`, MSQ `"A,C"`, NAT `"12.5"` with `answer_range`.

Questions with a figure also carry `"figure": {"type": "circuit", ...spec, "description": "...", "render": {"w": 480, "h": 300, "items": [...]}}`.
The app draws `render`; see [FIGURES.md](FIGURES.md).

To add more questions later, append them to `data/sample_questions.json`, or drop generated papers
into `data/generated/`. Then bump `SEED_VERSION` in `Repository.kt` so existing installs re-import.
