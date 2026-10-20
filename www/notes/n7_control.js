(window.NOTES=window.NOTES||[]).push({k:'CS',n:'Control Systems',w:'≈ 9–11 marks',s:[
{h:'Transfer functions, block diagrams, SFG',b:`
<ul>
<li>Negative feedback: T(s) = G/(1 + GH). Positive feedback: G/(1 − GH). Unity feedback: H = 1.</li>
<li>Block reduction: moving a take-off point after a block G → add 1/G in the branch; moving a summing point before a block G → add 1/G; after G → add G.</li>
<li><b>Mason's gain formula</b>: T = (1/Δ)Σ P<sub>k</sub>Δ<sub>k</sub>; Δ = 1 − Σ(loops) + Σ(products of 2 non-touching loops) − Σ(3 non-touching) + …; Δ<sub>k</sub> = Δ with loops touching path k removed.</li>
<li>Sensitivity of T to G: S = (∂T/T)/(∂G/G) = 1/(1 + GH). Sensitivity to H: −GH/(1 + GH) (≈ −1 for large loop gain).</li>
<li>Type = number of poles at the origin of the open-loop G(s)H(s). Order = degree of the denominator.</li>
</ul>`},
{h:'Time response',b:`
<ul>
<li>First order 1/(Ts + 1): step response 1 − e<sup>−t/T</sup>; rise time (10–90%) ≈ 2.2T; settling (2%) ≈ 4T.</li>
<li>Second order ω<sub>n</sub>²/(s² + 2ζω<sub>n</sub>s + ω<sub>n</sub>²). Poles −ζω<sub>n</sub> ± jω<sub>n</sub>√(1 − ζ²); ω<sub>d</sub> = ω<sub>n</sub>√(1 − ζ²); cos θ = ζ (angle from the negative real axis).</li>
<li>Peak time t<sub>p</sub> = π/ω<sub>d</sub>. Peak overshoot M<sub>p</sub> = e<sup>−ζπ/√(1−ζ²)</sup>. Settling time t<sub>s</sub> = 4/(ζω<sub>n</sub>) (2%), 3/(ζω<sub>n</sub>) (5%). Rise time (0–100%) t<sub>r</sub> = (π − θ)/ω<sub>d</sub>.</li>
<li>Reference values: ζ = 0.5 → M<sub>p</sub> = 16.3%; ζ = 0.6 → 9.5%; ζ = 0.707 → 4.3%; ζ = 0.4 → 25.4%.</li>
<li>Number of oscillations before settling ≈ t<sub>s</sub>/T<sub>d</sub>, T<sub>d</sub> = 2π/ω<sub>d</sub>.</li>
<li>Dominant poles: poles at least 5× further left can be neglected (keep the DC gain).</li>
<li>A zero in the LHP increases overshoot and speeds the response; a RHP zero (non-minimum phase) causes initial undershoot.</li>
</ul>`},
{h:'Steady-state error',b:`
<ul>
<li>Unity feedback: e<sub>ss</sub> = lim<sub>s→0</sub> sR(s)/(1 + G(s)).</li>
<li>K<sub>p</sub> = lim G(s); K<sub>v</sub> = lim sG(s); K<sub>a</sub> = lim s²G(s).</li>
<li>Step A: e<sub>ss</sub> = A/(1 + K<sub>p</sub>). Ramp A/s²: A/K<sub>v</sub>. Parabola A/s³: A/K<sub>a</sub>.</li>
<li>Type 0: finite step error, infinite ramp error. Type 1: zero step error, finite ramp error A/K<sub>v</sub>. Type 2: zero step and ramp error, finite parabolic error.</li>
<li>Non-unity feedback H(s): convert to an equivalent unity system G<sub>eq</sub> = G/(1 + GH − G) or compute E = R − C directly.</li>
<li>Error formulas are valid only if the closed loop is stable — check first.</li>
</ul>`},
{h:'Stability: Routh–Hurwitz',b:`
<ul>
<li>Necessary condition: all coefficients of the characteristic equation present and of the same sign.</li>
<li>Number of sign changes in the first column = number of RHP roots.</li>
<li>Zero in the first column (rest of row non-zero): replace by ε. Entire row of zeros: auxiliary polynomial A(s) from the row above; use dA/ds. A row of zeros indicates symmetric roots (jω-axis pairs or ± real pairs).</li>
<li>Marginal stability (sustained oscillation): the K that makes a row zero; frequency from the auxiliary equation.</li>
<li>Example: s³ + a s² + b s + K: stable for 0 &lt; K &lt; ab.</li>
</ul>`},
{h:'Root locus',b:`
<ul>
<li>Starts at open-loop poles (K = 0), ends at zeros or infinity. Number of branches = number of poles (n). Symmetric about the real axis.</li>
<li>On the real axis: to the left of an odd number of poles + zeros.</li>
<li>Asymptote angles = (2q + 1)180°/(n − m); centroid σ = (Σ poles − Σ zeros)/(n − m).</li>
<li>Break-away/break-in points: dK/ds = 0. jω-axis crossing from Routh.</li>
<li>Angle of departure from complex pole: 180° − (Σ angles from other poles) + (Σ angles from zeros).</li>
<li>Gain at a point: K = (product of distances to poles)/(product of distances to zeros).</li>
<li>Adding a pole pushes the locus to the right (less stable); adding a zero pulls it left (more stable).</li>
</ul>`},
{h:'Frequency response: Bode & Nyquist',b:`
<ul>
<li>Bode slopes: pole at origin −20 dB/dec; each real pole −20 dB/dec after its corner; zero +20 dB/dec. Quadratic pole −40 dB/dec; resonant peak M<sub>r</sub> = 1/(2ζ√(1 − ζ²)) at ω<sub>r</sub> = ω<sub>n</sub>√(1 − 2ζ²) (only for ζ &lt; 0.707).</li>
<li>Real pole at corner ω<sub>c</sub>: actual magnitude 3 dB below the asymptote at ω<sub>c</sub>; phase −45° at ω<sub>c</sub>.</li>
<li>Low-frequency slope gives the type: −20 dB/dec line intersects 0 dB at ω = K<sub>v</sub>; −40 dB/dec at ω = √K<sub>a</sub>.</li>
<li><b>Gain margin</b> GM = −20 log|G(jω<sub>pc</sub>)| (at phase crossover, ∠G = −180°). <b>Phase margin</b> PM = 180° + ∠G(jω<sub>gc</sub>) (at gain crossover |G| = 1). Stable (minimum-phase) if both positive.</li>
<li>PM ≈ 100ζ (degrees) for ζ up to about 0.7. Bandwidth ω<sub>b</sub> = ω<sub>n</sub>√(1 − 2ζ² + √(4ζ⁴ − 4ζ² + 2)).</li>
<li><b>Nyquist</b>: Z = P + N (N = clockwise encirclements of −1 + j0 by G(s)H(s), P = open-loop RHP poles, Z = closed-loop RHP poles). Equivalently N<sub>ccw</sub> = P for stability.</li>
<li>A time delay e<sup>−sT</sup> adds phase −ωT with no change of magnitude → reduces PM.</li>
<li>Minimum-phase systems: phase uniquely determined by magnitude; all poles and zeros in the LHP.</li>
</ul>`},
{h:'Compensators & controllers',b:`
<ul>
<li><b>Lead</b> (1 + aTs)/(1 + Ts), a &gt; 1: maximum phase lead φ<sub>m</sub> = sin⁻¹((a − 1)/(a + 1)) at ω<sub>m</sub> = 1/(T√a). Improves transient response, bandwidth, PM. Acts like a high-pass / PD.</li>
<li><b>Lag</b>: improves steady-state accuracy, reduces bandwidth; acts like a low-pass / PI.</li>
<li>PI adds a pole at origin (raises type, removes step error) and a zero. PD adds a zero (more damping, faster, noise-sensitive). PID combines both.</li>
<li>Op-amp lead network: zero closer to origin than the pole.</li>
</ul>`},
{h:'State-space',b:`
<ul>
<li>ẋ = Ax + Bu, y = Cx + Du. Transfer function G(s) = C(sI − A)⁻¹B + D. Characteristic equation |sI − A| = 0 (eigenvalues of A = poles).</li>
<li>State transition matrix Φ(t) = e<sup>At</sup> = ℒ⁻¹[(sI − A)⁻¹]. Φ(0) = I; Φ⁻¹(t) = Φ(−t).</li>
<li>Solution: x(t) = Φ(t)x(0) + ∫Φ(t − τ)Bu(τ)dτ.</li>
<li><b>Controllability</b>: rank[B AB … A<sup>n−1</sup>B] = n. <b>Observability</b>: rank[Cᵀ AᵀCᵀ … (Aᵀ)<sup>n−1</sup>Cᵀ] = n.</li>
<li>Pole-zero cancellation in G(s) ⇒ the realisation is either uncontrollable or unobservable.</li>
<li>Controllable canonical (phase-variable) form for sⁿ + a<sub>n−1</sub>sⁿ⁻¹ + … + a₀: A has 1s on the super-diagonal and last row [−a₀ −a₁ … −a<sub>n−1</sub>]; B = [0 … 0 1]ᵀ.</li>
</ul>`}]});
