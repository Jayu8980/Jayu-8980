(window.NOTES=window.NOTES||[]).push({k:'PS',n:'Power Systems',w:'≈ 11–13 marks',s:[
{h:'Per-unit system',b:`
<ul>
<li>Z<sub>base</sub> = (kV<sub>base</sub>)²/MVA<sub>base</sub> (line-to-line kV, three-phase MVA). I<sub>base</sub> = MVA<sub>base</sub>/(√3 kV<sub>base</sub>).</li>
<li>Change of base: Z<sub>pu,new</sub> = Z<sub>pu,old</sub> × (MVA<sub>new</sub>/MVA<sub>old</sub>) × (kV<sub>old</sub>/kV<sub>new</sub>)².</li>
<li>A transformer's per-unit impedance is the same on both sides; voltage bases change with the turns ratio across each transformer.</li>
<li>Advantages: transformer turns ratios disappear, machine impedances fall in narrow ranges.</li>
</ul>`},
{h:'Transmission line parameters',b:`
<ul>
<li>Inductance per phase (transposed, equilateral spacing D): L = 2 × 10⁻⁷ ln(D/r′) H/m, r′ = 0.7788 r (GMR of a solid conductor). Unsymmetrical spacing: use GMD = (D<sub>ab</sub>D<sub>bc</sub>D<sub>ca</sub>)<sup>1/3</sup>.</li>
<li>Capacitance to neutral: C = 2πε₀/ln(D/r) F/m (uses actual radius r, not GMR).</li>
<li>Bundled conductors: GMR increases → L decreases, C increases, corona and radio interference reduce, surge impedance loading increases.</li>
<li>Surge impedance Z<sub>c</sub> = √(L/C) (≈ 400 Ω for overhead lines, ≈ 40–60 Ω for cables). SIL = V<sub>L</sub>²/Z<sub>c</sub>. Velocity ≈ 1/√(LC) ≈ 3 × 10⁸ m/s for overhead lines.</li>
<li>Skin effect increases AC resistance (more at higher frequency, larger conductors). Proximity effect similar.</li>
</ul>`},
{h:'Line performance',b:`
<ul>
<li>Short line (&lt; 80 km): A = D = 1, B = Z, C = 0. Medium (nominal-π): A = D = 1 + ZY/2, B = Z, C = Y(1 + ZY/4). Nominal-T: A = D = 1 + ZY/2, B = Z(1 + ZY/4), C = Y.</li>
<li>Long line: A = D = cosh γl, B = Z<sub>c</sub> sinh γl, C = sinh(γl)/Z<sub>c</sub>; γ = √(zy).</li>
<li>Lossless line: A = cos βl, B = jZ<sub>c</sub> sin βl. Wavelength at 50 Hz ≈ 6000 km.</li>
<li><b>Ferranti effect</b>: at light load / no load, receiving-end voltage exceeds sending-end voltage: V<sub>R</sub> = V<sub>S</sub>/A (A = cos βl &lt; 1).</li>
<li>Power transfer (lossless): P = (V<sub>S</sub>V<sub>R</sub>/X) sin δ; maximum at δ = 90°. General: P<sub>R</sub> = (|V<sub>S</sub>||V<sub>R</sub>|/|B|)cos(β − δ) − (|A||V<sub>R</sub>|²/|B|)cos(β − α).</li>
<li>Reactive power flows from higher voltage to lower voltage; real power flows from leading angle to lagging angle.</li>
<li>Voltage regulation = (V<sub>R,NL</sub> − V<sub>R,FL</sub>)/V<sub>R,FL</sub>, with V<sub>R,NL</sub> = V<sub>S</sub>/|A|.</li>
<li>Series compensation reduces X (increases power transfer, stability); shunt reactors absorb the excess VAr on lightly loaded lines; shunt capacitors support voltage under heavy load.</li>
</ul>`},
{h:'Load flow',b:`
<ul>
<li>Bus types: slack (V, δ specified), PV / generator (P, |V|), PQ / load (P, Q).</li>
<li>Y<sub>bus</sub>: Y<sub>ii</sub> = sum of admittances connected to bus i (including shunt / line charging); Y<sub>ij</sub> = −(admittance between i and j). Sparse and symmetric (without phase shifters).</li>
<li>Adding a line between buses i and j changes Y<sub>ii</sub>, Y<sub>jj</sub>, Y<sub>ij</sub>, Y<sub>ji</sub> only.</li>
<li>Gauss–Seidel: simple, slow (linear convergence), uses Y<sub>bus</sub>; acceleration factor ≈ 1.6. Newton–Raphson: quadratic convergence, few iterations, needs Jacobian. Fast decoupled: uses P–δ and Q–V decoupling.</li>
<li>Size of NR Jacobian (polar) = (2n − m − 2) where n buses, m PV buses (excluding slack).</li>
</ul>`},
{h:'Faults & symmetrical components',b:`
<ul>
<li>Operator a = 1∠120°; 1 + a + a² = 0. V<sub>a0</sub> = (V<sub>a</sub> + V<sub>b</sub> + V<sub>c</sub>)/3; V<sub>a1</sub> = (V<sub>a</sub> + aV<sub>b</sub> + a²V<sub>c</sub>)/3; V<sub>a2</sub> = (V<sub>a</sub> + a²V<sub>b</sub> + aV<sub>c</sub>)/3.</li>
<li><b>3-phase (LLL) fault</b>: I<sub>f</sub> = E/Z₁. Fault MVA = base MVA/Z<sub>pu</sub>.</li>
<li><b>Single line to ground</b>: I<sub>a1</sub> = I<sub>a2</sub> = I<sub>a0</sub> = E/(Z₁ + Z₂ + Z₀ + 3Z<sub>f</sub>); I<sub>f</sub> = 3I<sub>a0</sub>. Networks in series.</li>
<li><b>Line to line</b>: I<sub>a1</sub> = −I<sub>a2</sub> = E/(Z₁ + Z₂ + Z<sub>f</sub>), I<sub>a0</sub> = 0; I<sub>f</sub> = √3 |I<sub>a1</sub>|. Positive and negative networks in parallel at the fault point.</li>
<li><b>Double line to ground</b>: I<sub>a1</sub> = E/[Z₁ + Z₂‖(Z₀ + 3Z<sub>f</sub>)]; all three networks in parallel.</li>
<li>If Z₀ &lt; Z₁ (solidly grounded generator), the LG fault current exceeds the 3-phase fault current. Neutral impedance Z<sub>n</sub> appears as 3Z<sub>n</sub> in the zero-sequence network.</li>
<li>Zero-sequence for transformers: Y-grounded/Y-grounded passes; Δ blocks zero-sequence current (Y-grounded/Δ: path to ground on the Y side, open on the Δ side). Ungrounded Y: open.</li>
<li>Generator reactances: subtransient X″<sub>d</sub> &lt; transient X′<sub>d</sub> &lt; synchronous X<sub>d</sub>. Breaker interrupting rating uses subtransient/transient values.</li>
<li>Doubling-effect: the maximum momentary current ≈ 2 × (symmetrical peak) for a fault at voltage zero (full DC offset).</li>
</ul>`},
{h:'Stability',b:`
<ul>
<li>Swing equation: (2H/ω<sub>s</sub>) d²δ/dt² = P<sub>m</sub> − P<sub>e</sub> (per unit), H = stored kinetic energy/rating (MJ/MVA). M = 2H/ω<sub>s</sub> (or H/(180f) in degrees).</li>
<li>Machines swinging together: H<sub>eq</sub> = Σ H<sub>i</sub>S<sub>i</sub>/S<sub>base</sub>.</li>
<li><b>Equal area criterion</b>: stable if accelerating area = decelerating area is achievable before δ<sub>max</sub> = π − δ₀ (for P<sub>max</sub> after clearing).</li>
<li>Critical clearing angle (complete loss of transfer during fault, same curve after): cos δ<sub>cr</sub> = (π − 2δ₀) sin δ₀ − cos δ₀. Critical clearing time t<sub>cr</sub> = √(4H(δ<sub>cr</sub> − δ₀)/(ω<sub>s</sub>P<sub>m</sub>)) (when P<sub>e</sub> = 0 during the fault, δ in radians).</li>
<li>Small-signal: natural frequency ω<sub>n</sub> = √(ω<sub>s</sub>P<sub>max</sub>cos δ₀/(2H)) rad/s, where synchronising power coefficient = P<sub>max</sub>cos δ₀.</li>
<li>Improving stability: fast fault clearing, auto-reclosing, higher voltage, series compensation, parallel lines, high-speed excitation, braking resistors.</li>
</ul>`},
{h:'Protection & switchgear',b:`
<ul>
<li>Overcurrent relay: plug setting multiplier PSM = fault current/(CT ratio × plug setting current). Operating time = TMS × time from curve at that PSM. IDMT standard inverse: t = 0.14 TMS/(PSM<sup>0.02</sup> − 1).</li>
<li>Distance relays: impedance (circle at origin, non-directional), reactance (best for short lines, unaffected by arc resistance), mho (directional, best for long lines, least affected by power swings). Zones: Z1 ≈ 80–90% of line (instantaneous), Z2 ≈ 120–150%, Z3 for backup.</li>
<li>Differential protection: for transformers and generators; percentage (biased) differential avoids maloperation from CT mismatch. Transformer: CTs connected in Δ on the Y side and in Y on the Δ side (phase compensation). Harmonic (2nd) restraint against magnetising inrush.</li>
<li>Buchholz relay: incipient faults in oil-immersed transformers (gas accumulation).</li>
<li>Circuit breakers: SF₆ (high voltage), vacuum (medium voltage), air-blast, oil. RRRV = peak restriking voltage/time; natural frequency of restriking = 1/(2π√(LC)).</li>
<li>Current chopping occurs when interrupting small inductive currents (vacuum, air-blast) → overvoltage = i√(L/C). Resistance switching damps it.</li>
</ul>`},
{h:'Economic operation, HVDC, insulators, cables',b:`
<ul>
<li><b>Economic dispatch</b> (no losses): equal incremental cost λ = dC<sub>i</sub>/dP<sub>i</sub> for all units within limits. With losses: (dC<sub>i</sub>/dP<sub>i</sub>)·L<sub>i</sub> = λ, penalty factor L<sub>i</sub> = 1/(1 − ∂P<sub>L</sub>/∂P<sub>i</sub>).</li>
<li>For C<sub>i</sub> = a<sub>i</sub>P<sub>i</sub>² + b<sub>i</sub>P<sub>i</sub> + c: dC/dP = 2a<sub>i</sub>P<sub>i</sub> + b<sub>i</sub>.</li>
<li><b>Load frequency control</b>: Δf<sub>ss</sub> = −ΔP<sub>D</sub>/(D + 1/R) (single area, R = speed regulation in Hz/MW or pu). Area frequency response characteristic β = D + 1/R. Integral control makes steady-state Δf zero.</li>
<li><b>HVDC</b>: economical beyond the break-even distance (≈ 600–800 km overhead, ≈ 50 km for cables), asynchronous interconnection, no reactive power in the line, no skin effect, no stability limit. Converters need reactive power and filters. 12-pulse converters produce harmonics of order 12k ± 1 on the AC side.</li>
<li>Line-commutated converter: V<sub>d</sub> = (3√2/π)V<sub>LL</sub>cos α − (3/π)ωL<sub>c</sub>I<sub>d</sub>.</li>
<li><b>String insulators</b>: voltage across units is unequal (highest across the unit nearest the line). String efficiency = V<sub>string</sub>/(n × V<sub>unit nearest line</sub>). With shunt capacitance ratio m = C<sub>shunt</sub>/C<sub>self</sub>, V₂ = V₁(1 + m), V₃ = V₁(1 + 3m + m²). Improve with guard ring, longer cross-arm, capacitance grading.</li>
<li><b>Cables</b>: insulation resistance R = (ρ/2πl) ln(R/r). Maximum stress g<sub>max</sub> = V/(r ln(R/r)) at the conductor surface; minimum when R/r = e (≈ 2.718). Grading: capacitance (different ε) or intersheath.</li>
<li><b>Corona</b>: disruptive critical voltage V<sub>c</sub> = m₀ g₀ δ r ln(D/r), g₀ ≈ 30 kV/cm peak (21.1 kV/cm rms); δ = air density factor. Corona loss (Peek) ∝ (f + 25)(V − V<sub>c</sub>)².</li>
<li><b>Sag</b>: equal supports: S = wl²/(8T) (l = span). Unequal supports: x₁ = l/2 − Th/(wl).</li>
</ul>`}]});
