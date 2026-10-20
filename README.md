# GATE EE Old Papers (offline app)

35 GATE Electrical papers built in (909 pages): 1991–1993, 1995–1999, 2001–2026,
with both 2017 sets. Missing: 1994, 2000.

## Use on a computer (no install)
Open `www/index.html` in Chrome or Edge. It works without internet.
(If the pages don't load when you double-click the file, open a terminal in `www` and run
`python -m http.server`, then go to http://localhost:8000)

## Features
- Read any paper page by page, with zoom. It reopens at the last page you read.
- Timed test: pick the minutes, fill in the answer sheet (Q1, Q2, ...), then Finish. Enter your marks from the answer key, and they are saved in "My practice history".
- Mark papers as Done, and filter by 1990s / 2000s / 2010s+ / In progress / Done.
- PDF button opens the original file for printing (on a computer).
- Light and dark theme.
- Missing years show as "Not added yet".

## AI mock test series (Generate mock test)
Tap **⚡ Generate mock test** on the home screen.
- First time: tap **AI settings** and paste an Anthropic API key (console.anthropic.com → API keys; paid per use).
  The key stays on your device.
- Choose: Full-length (65 Q, 100 marks, 3 h), Half-length (33 Q, 50 marks, 90 min) or Subject-wise (20 Q).
- Choose toughness: **GATE level** or **Tougher than GATE**. You can also type a focus (e.g. "more root locus").
- How the agent works:
  1. Plans the paper in GATE EE format: GA 15 marks + technical 85 marks, split by subject weightage,
     with a mix of MCQ, MSQ and NAT questions and of 1-mark and 2-mark questions.
  2. A generator writes original questions subject by subject.
  3. A second, independent solver re-solves every question. If its answer doesn't match the key, the question is
     rewritten and checked again. Anything still doubtful is labelled "Key not double-checked".
  4. Later tests avoid subtopics used in your recent tests, so the series stays varied.
- Generating needs internet (about 3–8 min for a full test). The test is then saved and works **offline**.
- Exam screen in GATE CBT style: timer (auto-submits), question palette (answered / not answered /
  not visited / marked for review), radio buttons for MCQ, checkboxes for MSQ, number keypad for NAT.
  Leave mid-test and tap Resume later.
- On submit: automatic GATE marking (MCQ −1/3 or −2/3 for wrong answers; MSQ and NAT have no negative marks
  and MSQ gives no partial marks), score, accuracy, negative marks, subject-wise strong/weak,
  time per question, and step-by-step solutions (filter by Wrong / Skipped).
- Export / Import tests as .json to back them up or share them.
- **Figures & diagrams**: choose "Like GATE" (about 35–40% of technical questions), "More figures" or "Text only".
  The app draws the figures itself (circuits with R, L, C, sources, dependent sources, switches, diodes, meters, op-amps;
  block diagrams with summing junctions; single-line diagrams; waveforms and Bode/characteristic plots; phasor diagrams;
  truth tables). They work offline and in dark mode. A figure that is broken or missing makes that question get
  rewritten, and the answer checker also sees the figure, so it catches figures that don't match the text.

## Question solver agent
- **📷 Solve a question** (home screen): add a PDF, JPG, PNG or screenshot. Choose files, take a photo, drag and drop,
  or paste a screenshot with Ctrl+V. You can add several files at once. Optionally say which question, e.g. "only Q.12".
- **🧠 Solve this page** (under every page of every old paper): sends that page to the solver.
  Hidden during timed tests.
- How it works: two independent AI solvers read the image or PDF and solve every question. If their final answers match,
  the result shows "✓ 2 solvers agree". If they differ, a referee re-solves the question and decides.
- Each answer shows: how the question was read (so you can spot a misread value), the final answer,
  step-by-step working, the concept used, a GATE shortcut when there is one, and a confidence level.
- Solutions are saved: reopening "Solve this page" shows the saved solution offline. The last few solves appear on the home screen.
- Needs internet and the same API key as the mock tests. PDFs up to 24 MB; large photos are shrunk automatically.

## My books (your own PDFs)
- **＋ Add book (PDF)** on the home screen → pick a subject → the PDF is copied into the app's storage on this device
  (IndexedDB) and opens offline. Your original file is not changed.
- Reader: smooth page rendering, zoom, go-to-page, 🔖 bookmarks, and it reopens where you stopped.
- **🧠 Solve this page** under every page sends that page to the question solver.
- Rename / change subject with ✎; ✕ removes the book from the app.
- Only add books you own. Copyrighted books (Sadiku, etc.) are not bundled with the app.
- PDF rendering uses Mozilla pdf.js 3.2.146 (Apache-2.0, bundled in lib/pdfjs, with JavaScript evaluation disabled).

## GATE notes (built in, offline)
- Original, GATE-focused revision notes for all 11 subjects (≈ 11,000 words, 67 topics): key formulas, standard results,
  shortcuts and common traps, with the usual GATE weightage.
- Search across all notes (e.g. "Thevenin", "slip", "Routh"); matches are highlighted.
- Tick each topic as revised; progress shows on the subject cards.
- **⚡ Practice test** in a subject opens the mock-test generator with that subject pre-selected.

## 📚 Study book (all GATE EE subjects)
- 11 subjects, 73 chapters, laid out from the official GATE EE syllabus (General Aptitude, Engineering Mathematics,
  Circuits, EM Fields, Signals & Systems, Machines, Power Systems, Control, Measurements, Analog & Digital, Power Electronics).
- Tap a subject → **✍️ Write** for one chapter or **Write all remaining chapters**. The AI writes each chapter once:
  theory with figures, a formula sheet, solved GATE-level examples, common traps, what GATE asks, and last-day
  revision points. A second solver checks every worked example; any mismatch is marked "⚠ verify".
- Chapters are saved on the device (IndexedDB) and readable offline. ↻ rewrites a chapter.
- **🖨 Print / PDF**: prints one chapter or the whole subject book. Choose "Save as PDF" in the print dialog
  (works on a computer; Android's built-in app view can't print).
- Each subject also lists **free, legal courses and books**: NPTEL (IIT/IISc video courses), MIT OpenCourseWare,
  All About Circuits, and LibreTexts.
- Commercial books (Made Easy, ACE, standard textbooks) are copyrighted and are not included.
- Cost estimate with Sonnet: a few cents to about 15 cents per chapter. The whole book is roughly $5–10 (rough estimate; check current API pricing).

## Get the APK (built automatically by GitHub)
Every push to the `apk-build` (or `main`) branch runs **Actions → Build Android APK**, which compiles the app
on GitHub's servers. When it finishes (≈ 5–10 min):
- **Releases** (right side of the repo page) → newest "GATE EE Papers APK" → download `GATE-EE-Papers.apk`
  on your phone and install it (allow "Install unknown apps" when Android asks), or
- **Actions** → the latest run → *Artifacts* → `GATE-EE-Papers-apk` (zip containing the APK).
You can also re-run it any time: Actions → Build Android APK → *Run workflow*.

## Build the Android APK yourself (optional, on a laptop)
Needs: Node.js 18+, Android Studio (with an Android SDK).
```
cd gate-ee-pyq
npm install
npx cap add android
npx cap sync android
npx cap open android
```
In Android Studio: Build → Build Bundle(s)/APK(s) → Build APK(s).
The APK appears in `android/app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and install it.
(The PDF button doesn't open inside the APK. Reading and timed tests work fully.)

## Add more years
1. Name the file like `EE2014.pdf` (or `EE2017-1.pdf` for a set).
2. Run `./add_paper.sh EE2014.pdf` (needs `pdftoppm` from poppler and Python with Pillow).
3. For the APK, run `npx cap sync android` again and rebuild.

For personal use only. The papers are copyright of the IITs/IISc.
