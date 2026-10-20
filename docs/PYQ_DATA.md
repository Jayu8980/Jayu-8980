# PYQ data (Module 1)

The analysis screens, the predictor and PYQ practice all read one file: **`data/pyq_bank.json`**.
Until that file exists, the app and the backend fall back to `data/pyq_tags_demo.json`, which holds
**synthetic** tags made by `tools/generate_demo_pyq.mjs` so the screens have something to show.
That fallback is not real GATE data, and the app shows a banner while it is in use.

## Record format

```json
{
  "is_demo": false,
  "records": [
    {
      "id": "EE-2024-Q37",
      "year": 2024,
      "subject_id": "EM",
      "topic_id": "EM.IM",
      "subtopic": "Torque-slip characteristics",
      "type": "NAT",
      "marks": 2,
      "difficulty": "Medium",
      "concept": "Slip at maximum torque",
      "question": "optional full text (enables PYQ practice)",
      "options": [],
      "answer": "0.2",
      "answer_range": {"min": 0.19, "max": 0.21},
      "solution": "optional step-by-step solution"
    }
  ]
}
```

* `subject_id` and `topic_id` must match `data/syllabus.json` (for example `PS.FAULT`, `CS.FREQ`).
* Tag-only records (no `question`) still drive every analysis screen and the predictor.
* Records with `question` text also appear in PYQ practice, and the AI setters avoid copying them
  through the duplicate check.

## Importing from a spreadsheet

Keep one row per question with this header, export to CSV, and run the converter:

```
id,year,subject_id,topic_id,subtopic,type,marks,difficulty,concept,question,option_a,option_b,option_c,option_d,answer,answer_min,answer_max,solution
```

```bash
node tools/csv_to_pyq_bank.mjs gate_ee_pyqs.csv    # validates topic ids, types and marks, then writes data/pyq_bank.json
```

Rebuild the APK (the `data/` folder is its asset folder) and restart the backend. Analysis, gap
topics, the likelihood scores and the AI blueprint then use the real data. Make sure you have the
right to redistribute any question text you bundle.
