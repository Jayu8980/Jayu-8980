(window.NOTES=window.NOTES||[]).push({k:'EC',n:'Electric Circuits',w:'≈ 10–12 marks',s:[
{h:'Basics & network theorems',b:`
<ul>
<li>KCL: Σ currents leaving a node = 0. KVL: Σ voltages around a loop = 0. Nodal analysis needs (nodes − 1) equations; mesh analysis needs (b − n + 1) equations.</li>
<li>Series R: R<sub>eq</sub> = ΣR. Parallel: 1/R<sub>eq</sub> = Σ1/R. Two in parallel: R₁R₂/(R₁+R₂). Capacitors combine like conductances; inductors (uncoupled) like resistors.</li>
<li>Voltage divider: V<sub>R1</sub> = V·R₁/(R₁+R₂). Current divider: I<sub>R1</sub> = I·R₂/(R₁+R₂).</li>
<li><b>Star–delta</b>: R<sub>Y</sub> = (product of the two adjacent Δ resistors)/(sum of all three). Δ from Y: R<sub>Δ</sub> = (sum of pairwise products)/(opposite Y resistor). Balanced: R<sub>Y</sub> = R<sub>Δ</sub>/3.</li>
<li><b>Thevenin</b>: V<sub>th</sub> = open-circuit voltage; R<sub>th</sub> = V<sub>oc</sub>/I<sub>sc</sub>. With dependent sources, deactivate only the independent sources and apply a test source: R<sub>th</sub> = V<sub>test</sub>/I<sub>test</sub>. R<sub>th</sub> can be negative with dependent sources.</li>
<li><b>Norton</b>: I<sub>N</sub> = I<sub>sc</sub>, R<sub>N</sub> = R<sub>th</sub>.</li>
<li><b>Maximum power transfer</b>: resistive load R<sub>L</sub> = R<sub>th</sub>, P<sub>max</sub> = V<sub>th</sub>²/(4R<sub>th</sub>) (efficiency 50%). Complex load: Z<sub>L</sub> = Z<sub>th</sub>*; P<sub>max</sub> = |V<sub>th</sub>|²/(4R<sub>th</sub>) using the rms value. If only |Z<sub>L</sub>| can vary (pure resistance with complex Z<sub>th</sub>): R<sub>L</sub> = |Z<sub>th</sub>|.</li>
<li><b>Superposition</b>: one independent source at a time (V-source shorted, I-source opened); dependent sources stay. Not valid for power.</li>
<li><b>Reciprocity</b> holds for linear, bilateral networks without dependent sources. <b>Tellegen</b>: Σ v<sub>k</sub>i<sub>k</sub> = 0 for any network that satisfies KCL and KVL.</li>
<li><b>Millman</b>: V = Σ(V<sub>k</sub>/R<sub>k</sub>)/Σ(1/R<sub>k</sub>) for parallel branches with sources.</li>
</ul>
<div class="trap">Trap: an ideal voltage source in parallel with anything → the element in parallel does not change the voltage seen by the rest of the circuit. An ideal current source in series with anything → that element does not affect the rest of the circuit (but it does affect the power of the source).</div>`},
{h:'Transients (first & second order)',b:`
<ul>
<li>Inductor current and capacitor voltage cannot change instantaneously (finite sources): i<sub>L</sub>(0⁺) = i<sub>L</sub>(0⁻), v<sub>C</sub>(0⁺) = v<sub>C</sub>(0⁻).</li>
<li>At t = 0⁺: an uncharged C acts as a short circuit; an L with no current acts as an open circuit. In DC steady state: C → open, L → short.</li>
<li><b>First order</b>: x(t) = x(∞) + [x(0⁺) − x(∞)]e<sup>−t/τ</sup>; τ = RC or L/R, with R = Thevenin resistance seen by the C or L.</li>
<li>After 5τ the response is within 1% of final value. At t = τ: 63.2% of the change is complete.</li>
<li>Energy: ½CV² in a capacitor, ½LI² in an inductor.</li>
<li><b>Series RLC</b>: s² + (R/L)s + 1/(LC) = 0; α = R/(2L), ω₀ = 1/√(LC), ζ = (R/2)√(C/L).</li>
<li><b>Parallel RLC</b>: α = 1/(2RC), ζ = (1/(2R))√(L/C).</li>
<li>α &gt; ω₀ overdamped, α = ω₀ critically damped, α &lt; ω₀ underdamped (ω<sub>d</sub> = √(ω₀² − α²)).</li>
<li>Laplace models: L → sL in series with a voltage source L·i(0⁻) (polarity that keeps the initial current flowing); C → 1/(sC) with source v(0⁻)/s in series.</li>
</ul>
<div class="trap">Trap: when a switch creates a loop of capacitors only (or a cutset of inductors only), charge (or flux) conservation is used instead, and impulsive currents appear.</div>`},
{h:'Sinusoidal steady state & power',b:`
<ul>
<li>Z<sub>L</sub> = jωL, Z<sub>C</sub> = 1/(jωC) = −j/(ωC). Admittance Y = 1/Z = G + jB.</li>
<li>RMS of a sinusoid = V<sub>m</sub>/√2; average of a full sine = 0, of a half-wave rectified sine = V<sub>m</sub>/π, full-wave = 2V<sub>m</sub>/π.</li>
<li>RMS of a sum of different frequencies: √(V₀² + V₁²<sub>rms</sub> + V₂²<sub>rms</sub> + …). Square wave ±V: rms = V. Triangular/sawtooth of peak V<sub>m</sub>: rms = V<sub>m</sub>/√3.</li>
<li>Form factor = rms/average (sine 1.11); peak factor = peak/rms (sine 1.414).</li>
<li>Complex power S = V I* = P + jQ; |S| = V<sub>rms</sub>I<sub>rms</sub> (VA); P = VI cos φ; Q = VI sin φ; pf = cos φ (lagging for inductive loads).</li>
<li>Power factor correction capacitor: Q<sub>C</sub> = P(tan φ₁ − tan φ₂); C = Q<sub>C</sub>/(ωV²).</li>
</ul>`},
{h:'Resonance',b:`
<ul>
<li><b>Series RLC</b>: ω₀ = 1/√(LC); Z = R (minimum), current maximum. Q = ω₀L/R = 1/(ω₀CR) = (1/R)√(L/C). Voltage across L or C at resonance = Q·V.</li>
<li>Bandwidth BW = R/L rad/s = ω₀/Q; half-power frequencies ω₁ω₂ = ω₀².</li>
<li><b>Parallel RLC</b>: Z = R (maximum), Q = R/(ω₀L) = ω₀CR = R√(C/L), BW = 1/(RC). Circulating current = Q·I.</li>
<li>Practical parallel tank (R in series with L, C in parallel): ω₀ = √(1/(LC) − R²/L²); dynamic resistance Z<sub>D</sub> = L/(CR).</li>
<li>Below resonance: series RLC is capacitive, parallel RLC is inductive.</li>
</ul>`},
{h:'Three-phase circuits',b:`
<ul>
<li>Star: V<sub>L</sub> = √3 V<sub>ph</sub> (V<sub>L</sub> leads V<sub>ph</sub> by 30° for ABC sequence), I<sub>L</sub> = I<sub>ph</sub>. Delta: I<sub>L</sub> = √3 I<sub>ph</sub>, V<sub>L</sub> = V<sub>ph</sub>.</li>
<li>P = √3 V<sub>L</sub>I<sub>L</sub> cos φ (φ = angle of the phase impedance); Q = √3 V<sub>L</sub>I<sub>L</sub> sin φ.</li>
<li>A balanced Δ load of Z is equivalent to a Y load of Z/3. Power drawn by a Δ-connected load is 3 times that of the same impedances connected in Y on the same supply.</li>
<li><b>Two-wattmeter method</b>: P = W₁ + W₂; tan φ = √3 (W₁ − W₂)/(W₁ + W₂). pf = 1 → W₁ = W₂; pf = 0.5 → one reading is zero; pf &lt; 0.5 → one reading is negative.</li>
<li>Unbalanced star without neutral: find the neutral shift voltage V<sub>N</sub> = Σ(V<sub>k</sub>Y<sub>k</sub>)/ΣY<sub>k</sub> (Millman).</li>
</ul>`},
{h:'Two-port networks',b:`
<ul>
<li><b>Z</b>: V₁ = z₁₁I₁ + z₁₂I₂, V₂ = z₂₁I₁ + z₂₂I₂ (open-circuit parameters). <b>Y</b>: short-circuit parameters. <b>h</b>: V₁ = h₁₁I₁ + h₁₂V₂, I₂ = h₂₁I₁ + h₂₂V₂. <b>ABCD</b>: V₁ = AV₂ − BI₂, I₁ = CV₂ − DI₂.</li>
<li>Reciprocal: z₁₂ = z₂₁, y₁₂ = y₂₁, h₁₂ = −h₂₁, AD − BC = 1. Symmetric: z₁₁ = z₂₂, y₁₁ = y₂₂, A = D, Δh = 1.</li>
<li>Interconnections: series → add Z; parallel → add Y; cascade → multiply ABCD; series-parallel → add h.</li>
<li>Y = Z<sup>−1</sup>. For a T-network (Z₁, Z₂ series arms, Z₃ shunt): z₁₁ = Z₁ + Z₃, z₂₂ = Z₂ + Z₃, z₁₂ = z₂₁ = Z₃.</li>
<li>Input impedance with load Z<sub>L</sub>: Z<sub>in</sub> = (AZ<sub>L</sub> + B)/(CZ<sub>L</sub> + D).</li>
</ul>
<div class="trap">Trap: an ideal transformer has no Z or Y parameters (they are infinite); use ABCD or h parameters.</div>`},
{h:'Coupled circuits & graph theory',b:`
<ul>
<li>Mutual inductance M = k√(L₁L₂), 0 ≤ k ≤ 1. Series aiding: L = L₁ + L₂ + 2M; opposing: L₁ + L₂ − 2M.</li>
<li>Parallel aiding: L = (L₁L₂ − M²)/(L₁ + L₂ − 2M); opposing: (L₁L₂ − M²)/(L₁ + L₂ + 2M).</li>
<li>Dot convention: current entering a dotted terminal induces a voltage that is positive at the dotted terminal of the other coil.</li>
<li>Ideal transformer: V₂/V₁ = N₂/N₁, I₂/I₁ = N₁/N₂, impedance reflected to primary = Z<sub>L</sub>(N₁/N₂)².</li>
<li><b>Graph</b>: n nodes, b branches. Tree branches = n − 1; links = b − n + 1 (number of independent loops / fundamental loops). Fundamental cutsets = n − 1.</li>
<li>Number of trees of a complete graph K<sub>n</sub> = n<sup>n−2</sup>; in general = det(reduced incidence matrix × its transpose).</li>
</ul>`}]});
