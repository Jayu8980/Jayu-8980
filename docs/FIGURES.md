# Figure questions

GATE EE papers lean on figures: circuits, waveforms, phasor diagrams, Bode and root-locus style
plots, block diagrams, machine characteristics and, in General Aptitude, data-interpretation charts.
Questions here carry figures the same way.

## How it works

```
setter agent writes  "figure": {spec in the figure language}
        │
        ▼  backend/src/figures
  validate (spec.js) ── rejects unknown types, diagonal or unconnected circuit elements, missing data
  describe (describe.js) ── exact text: circuit netlist, curve points, phasors, blocks, chart data
  layout  (layout.js) ── display list of path / circle / text primitives with theme colour tokens
        │
        ├─► solver agents read the DESCRIPTION. They solve from what the student will see, so a
        │   figure that disagrees with the question text shows up as a failed answer vote.
        ├─► moderator checks that the figure and text agree and that the figure is needed
        └─► question JSON stores spec + description + render; the app draws render natively
            (FigureView.kt): offline, sharp at any size, dark-mode aware, pinch-zoom
```

Agents describe figures in a small JSON language rather than producing images. That has four
benefits:

- Code can check the figure.
- The solvers see exactly what the student sees.
- The app needs no image download.
- A figure can be fixed by editing one number.

## Which questions get figures

The blueprint marks a share of the slots as figure questions and suggests a type for each:

| Request `figures` | Share of questions |
|---|---|
| `none` | 0% |
| `some` (default) | ~30% (20 of 65 in a full mock, close to recent GATE EE papers) |
| `many` | ~60% |

Suggested types per subject: Circuits → circuit / phasor / waveform; Machines → phasor diagram /
characteristic / equivalent circuit; Power Systems → phasor / circuit / plot; Control → block
diagram / Bode / plot / pole-zero; Signals → waveform / pole-zero; Power Electronics → waveform /
circuit; Analog & Digital → circuit / logic diagram / truth table; Measurements → bridge circuit /
waveform; Mathematics → graph; General Aptitude → bar chart / pie chart / table.

In the app this is the "Questions with figures" choice on the AI Mock Generator screen.

## Figure types

| Type | Use for | Key fields |
|---|---|---|
| `plot` | waveforms, characteristics (torque-slip, V-curves, B-H), graphs, asymptotic Bode sketches | `x`, `y` axes (`min`, `max`, `scale: log`, `ticks`), `series` (`points` or `expression` in x with `domain`/`domains`, `style: dashed`, `hide_expression`), `hlines`, `vlines`, `annotations` |
| `bode` | exact frequency response | `numerator`, `denominator` (descending powers of s), `gain`, `delay`, `w_min`, `w_max`, `show`, `hide_function` |
| `pole_zero` | s-plane or z-plane plots | `poles`, `zeros` as `[re, im]`, `plane`, `label_points` |
| `phasor` | phasor diagrams | `phasors` (`label`, `magnitude`, `angle_deg`, `scale`, `from` for head-to-tail, `style`), `angles` |
| `circuit` | schematics on a grid | `elements` (R L C V AC I dep_v dep_i diode zener thyristor switch meter lamp wire ground opamp transformer npn nmos), `nodes` (labels, terminals), `arrows`, `labels` |
| `block_diagram` | control loops, signal flow, logic circuits | `blocks` (`rect`, `sum`, `point`, `AND`…`XNOR`), `links` with `via` corners and `sign`, `inputs`, `outputs` |
| `bar_chart`, `pie_chart`, `table` | data interpretation | `categories` + `series`, `slices`, `headers` + `rows` |

The full grammar the agents receive is `FIGURE_GUIDE` in `backend/src/prompts.js`. Examples:
`data/sample_questions.json` (SAMPLE-031 to SAMPLE-038) and `backend/test/fixtures/figures.json`
(op-amp, RLC with a diode, switch and dependent source, transformer and BJT, logic gates, exact
Bode, torque-slip family, pie chart, truth table, z-plane, three-phase phasors).

## Circuit rules

* Grid coordinates; `y` grows downward. Two-terminal elements are horizontal or vertical.
* `V`: `from` is −, `to` is +. `I`: current flows from → to. Diodes and thyristors: anode = `from`.
* Op-amp pins sit relative to `at`: − input `[-1, -0.5]`, + input `[-1, +0.5]`, output `[+1, 0]`
  (`flip: true` swaps the inputs).
* Wires join where end points meet or where a pin lands on a wire (T-junction). Junction dots are
  drawn automatically.
* Validation rejects any pin that is not connected, unless it is a labelled node or `terminal`.

## Adding figures to the bundled bank

Write the spec in `tools/make_sample_bank.py` (or in `data/pyq_bank.json` / `data/generated/*.json`),
then run:

```bash
python3 tools/make_sample_bank.py
node tools/build_figures.mjs     # validates every figure, adds description + render
```

A test fails if a bundled figure's render is out of date with the layout code.

To review a generated paper with its figures in a browser, open
`http://<backend>/preview/<job_id>` (add `?token=<APP_CLIENT_TOKEN>` when a token is set).
