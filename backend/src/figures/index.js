// Figures for exam questions: circuits, waveforms and graphs, phasor diagrams, Bode and
// pole-zero plots, block and logic diagrams, and data charts.
import { figureErrors, FIGURE_TYPES } from "./spec.js";
import { describeFigure } from "./describe.js";
import { layoutFigure } from "./layout.js";

export { figureErrors, describeFigure, layoutFigure, FIGURE_TYPES };
export { toSvg } from "./svg.js";

/**
 * Validates a figure and returns it with the code-generated `description` and the display
 * list `render` attached (what the app draws). Throws if the spec is invalid.
 */
export function prepareFigure(fig) {
  const errs = figureErrors(fig);
  if (errs.length) throw new Error(`invalid figure: ${errs.join("; ")}`);
  // drop any render/description an agent supplied: both are always regenerated from the spec
  const spec = Object.fromEntries(Object.entries(fig).filter(([k]) => k !== "render" && k !== "description"));
  return { ...spec, description: describeFigure(spec), render: layoutFigure(spec) };
}
