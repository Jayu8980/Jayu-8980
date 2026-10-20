(window.NOTES=window.NOTES||[]).push({k:'MEAS',n:'Electrical & Electronic Measurements',w:'≈ 5–7 marks',s:[
{h:'Errors & statistics',b:`
<ul>
<li>Accuracy: closeness to the true value; precision: repeatability. Resolution: smallest change detectable.</li>
<li>Limiting (guarantee) error is specified as a percentage of full scale: at a reading x, relative error = (limit × full scale)/x → reading near the lower end of the scale gives larger relative error.</li>
<li>Combination of limiting errors: sum or difference → add absolute errors; product, quotient → add relative (%) errors; power xⁿ → n × relative error.</li>
<li>Probable/standard errors combine as root-sum-square: for y = x₁x₂, (σ<sub>y</sub>/y)² = (σ₁/x₁)² + (σ₂/x₂)². Standard deviation of the mean = σ/√n. Probable error = 0.6745σ.</li>
</ul>`},
{h:'Indicating instruments',b:`
<ul>
<li><b>PMMC</b>: deflecting torque T = NBAI; uniform scale; DC only (reads average value). With AC input it reads zero; with a rectifier it reads the average, usually calibrated in rms of a sine (× 1.11) → error for non-sinusoids.</li>
<li><b>Moving iron</b>: T = ½I² dL/dθ → reads rms; AC and DC; non-uniform (square-law) scale.</li>
<li><b>Electrodynamometer</b>: T = I₁I₂ cos φ dM/dθ → true rms (ammeter/voltmeter) and power (wattmeter). AC/DC transfer instrument.</li>
<li>Thermocouple and electrostatic instruments read true rms; electrostatic voltmeter draws no steady current (high-voltage use).</li>
<li>Damping: eddy current (PMMC), air friction (MI). Control: spring (T ∝ θ) or gravity (T ∝ sin θ).</li>
<li><b>Ammeter shunt</b>: R<sub>sh</sub> = R<sub>m</sub>/(m − 1), m = I/I<sub>m</sub>. <b>Voltmeter multiplier</b>: R<sub>s</sub> = R<sub>m</sub>(m − 1), m = V/V<sub>m</sub>. Sensitivity = 1/I<sub>fsd</sub> (Ω/V).</li>
<li>Loading effect: voltmeter of resistance R<sub>v</sub> across a source of Thevenin resistance R<sub>th</sub> reads V·R<sub>v</sub>/(R<sub>v</sub> + R<sub>th</sub>).</li>
</ul>
<div class="trap">Trap: for a waveform with DC + AC, PMMC shows the DC (average) part, MI / dynamometer show the true rms √(V<sub>dc</sub>² + V<sub>ac,rms</sub>²), and a rectifier-type meter shows 1.11 × (rectified average).</div>`},
{h:'Power & energy measurement',b:`
<ul>
<li>Wattmeter reads V<sub>pc</sub> I<sub>cc</sub> cos(angle between them) — use the actual voltage across the pressure coil and current through the current coil.</li>
<li>Pressure coil inductance error: true power = reading × cos φ/(cos β cos(φ − β)), tan β = ωL<sub>p</sub>/R<sub>p</sub>.</li>
<li>Two-wattmeter method: P = W₁ + W₂; Q = √3 (W₁ − W₂). One wattmeter with a 90°-shifted pressure coil measures reactive power: Q<sub>total</sub> = √3 × reading (balanced load).</li>
<li>Energy meter (induction): driving torque ∝ VI cos φ; braking magnet provides speed ∝ power. Meter constant in rev/kWh. Creep: rotation on no-load (prevented by holes in the disc).</li>
<li>Percentage error of an energy meter = (recorded − true)/true × 100.</li>
</ul>`},
{h:'Bridges',b:`
<ul>
<li><b>Wheatstone</b> (medium R): balance R₁R₄ = R₂R₃. <b>Kelvin double bridge</b>: low R (&lt; 1 Ω). <b>Megger / loss-of-charge</b>: high R.</li>
<li><b>Maxwell inductance–capacitance</b>: L<sub>x</sub> = R₂R₃C₄, R<sub>x</sub> = R₂R₃/R₄; for medium-Q coils (1 &lt; Q &lt; 10); balance independent of frequency.</li>
<li><b>Hay's</b>: high-Q coils (Q &gt; 10); frequency-dependent. <b>Anderson</b>: very accurate inductance, wide range. <b>Owen</b>: inductance, frequency-independent.</li>
<li><b>Schering</b>: capacitance and dissipation factor (insulation testing, high voltage): C<sub>x</sub> = C₂R₄/R₃, D = ωC₄R₄.</li>
<li><b>Wien</b>: frequency measurement / oscillators; f = 1/(2π√(R₁R₂C₁C₂)). <b>De Sauty</b>: capacitance comparison (lossless).</li>
<li>AC bridge balance needs both magnitude and phase conditions: Z₁Z₄ = Z₂Z₃ (magnitudes multiply, angles add).</li>
</ul>`},
{h:'Instrument transformers & others',b:`
<ul>
<li><b>CT</b>: secondary must never be open-circuited (very high voltage, core saturation). Ratio error = (K<sub>n</sub>I<sub>s</sub> − I<sub>p</sub>)/I<sub>p</sub>; phase angle error from magnetising and loss components.</li>
<li><b>PT</b>: secondary must not be short-circuited.</li>
<li>Actual ratio R = n + (I<sub>e</sub> sin(δ + α))/I<sub>s</sub> (approximate CT relation, n = turns ratio).</li>
<li><b>CRO</b>: Lissajous figures: f<sub>y</sub>/f<sub>x</sub> = (horizontal tangencies)/(vertical tangencies). Phase from ellipse: sin φ = Y-intercept / Y-max. Rise time ↔ bandwidth: t<sub>r</sub> = 0.35/BW.</li>
<li><b>Digital voltmeters</b>: dual-slope integrating (high accuracy, noise rejection), successive approximation (fast), flash (fastest, 2ⁿ − 1 comparators). Resolution of an n-digit display = 1/10ⁿ; 3½ digit → 1999 counts.</li>
<li>Q-meter measures Q, inductance and self-capacitance of coils (distributed capacitance C<sub>d</sub> = (C₁ − 4C₂)/3 from two resonance tests at f and 2f).</li>
</ul>`}]});
