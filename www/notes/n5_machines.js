(window.NOTES=window.NOTES||[]).push({k:'MAC',n:'Electrical Machines',w:'≈ 12–14 marks',s:[
{h:'Single-phase transformer',b:`
<ul>
<li>EMF equation: E = 4.44 f N φ<sub>m</sub> = 4.44 f N B<sub>m</sub>A. Turns ratio a = N₁/N₂ = V₁/V₂ = I₂/I₁.</li>
<li>Referring to primary: R′₂ = a²R₂, X′₂ = a²X₂. Equivalent R<sub>eq1</sub> = R₁ + a²R₂.</li>
<li>No-load current: I₀ = I<sub>c</sub> + I<sub>m</sub>; core-loss component I<sub>c</sub> = I₀cos φ₀, magnetising I<sub>m</sub> = I₀ sin φ₀.</li>
<li><b>OC test</b> (LV side, HV open): gives core loss P<sub>i</sub> and R<sub>c</sub>, X<sub>m</sub>. <b>SC test</b> (HV side, LV shorted, reduced voltage): gives full-load copper loss and R<sub>eq</sub>, X<sub>eq</sub>.</li>
<li>Core loss: hysteresis P<sub>h</sub> ∝ f B<sub>m</sub><sup>x</sup> (Steinmetz x ≈ 1.6–2), eddy P<sub>e</sub> ∝ f²B<sub>m</sub>²t². With V/f constant, B<sub>m</sub> is constant → P<sub>h</sub> ∝ f, P<sub>e</sub> ∝ f². With f constant, B<sub>m</sub> ∝ V.</li>
<li><b>Regulation</b> (approx.) = (I R<sub>eq</sub>cos φ ± I X<sub>eq</sub>sin φ)/V × 100% (+ lagging, − leading). In per-unit: ε<sub>r</sub>cos φ ± ε<sub>x</sub>sin φ. Zero regulation at leading pf with tan φ = R/X. Maximum regulation when φ = θ<sub>z</sub> (tan φ = X/R, lagging): value = Z<sub>pu</sub>.</li>
<li><b>Efficiency</b> η = xS cos φ / (xS cos φ + P<sub>i</sub> + x²P<sub>cu,fl</sub>), x = fraction of full load. Maximum efficiency when copper loss = core loss: x = √(P<sub>i</sub>/P<sub>cu,fl</sub>); occurs at unity pf for a given load.</li>
<li><b>All-day efficiency</b> = energy output / energy input over 24 h (distribution transformers are designed with low core loss).</li>
<li><b>Autotransformer</b> (a = V<sub>H</sub>/V<sub>L</sub> &gt; 1): copper used = (1 − 1/a) of an equivalent two-winding transformer, so the saving is 1/a. Power transferred conductively = S/a; inductively = S(1 − 1/a). A two-winding transformer of rating S reconnected as an autotransformer (windings in series, additive) can handle S·a/(a − 1).</li>
<li><b>Parallel operation</b>: same voltage ratio, polarity, phase sequence, phase shift. Load sharing inversely proportional to per-unit impedances (on a common base): S₁ = S·Z₂/(Z₁ + Z₂).</li>
</ul>
<div class="trap">Trap: OC test reading of current is very small and the wattmeter reads core loss only; SC test wattmeter reads copper loss at the test current (scale by (I<sub>rated</sub>/I<sub>test</sub>)²).</div>`},
{h:'Three-phase transformers',b:`
<ul>
<li>Vector groups: Yy0, Dd0 (0°); Yd1, Dy1 (−30°, secondary lags); Yd11, Dy11 (+30°, secondary leads); Yy6, Dd6 (180°).</li>
<li>Line-voltage ratio: Y primary / Δ secondary: V<sub>L2</sub>/V<sub>L1</sub> = (N₂/N₁)/√3. Δ primary / Y secondary: V<sub>L2</sub>/V<sub>L1</sub> = √3(N₂/N₁).</li>
<li>Open-delta (V–V) with two identical transformers: capacity = 57.7% (1/√3) of the original Δ–Δ bank, or 86.6% of the rating of two transformers.</li>
<li>Scott connection: 3-phase to 2-phase; teaser has 0.866 of the main transformer turns, main transformer has a centre tap.</li>
<li>Third-harmonic magnetising currents need a delta winding (or neutral) to avoid distorted, peaky flux and voltage in Y–Y banks.</li>
</ul>`},
{h:'DC machines',b:`
<ul>
<li>EMF: E = φZNP/(60A) = K<sub>a</sub>φω. A = P for lap winding, A = 2 for wave winding.</li>
<li>Torque: T = (PZ/2πA)φI<sub>a</sub> = K<sub>a</sub>φI<sub>a</sub>. Same constant: E·I<sub>a</sub> = T·ω.</li>
<li>Generator: E = V + I<sub>a</sub>R<sub>a</sub>. Motor: V = E + I<sub>a</sub>R<sub>a</sub>; speed N ∝ (V − I<sub>a</sub>R<sub>a</sub>)/φ.</li>
<li><b>Shunt motor</b>: nearly constant speed, T ∝ I<sub>a</sub>. <b>Series motor</b>: φ ∝ I<sub>a</sub> (unsaturated) → T ∝ I<sub>a</sub>², N ∝ 1/I<sub>a</sub>; never start on no load (dangerous speed). Best for traction.</li>
<li>Speed control: armature voltage / armature resistance → below base speed (constant torque); field weakening → above base speed (constant power).</li>
<li>Starting current would be V/R<sub>a</sub> (very large) → starter resistance. Back EMF is zero at start.</li>
<li>Losses: constant losses (core + mechanical + shunt field) and variable armature copper loss I<sub>a</sub>²R<sub>a</sub>. Maximum efficiency when variable loss = constant loss.</li>
<li><b>Swinburne's test</b>: no-load test to find constant losses (not valid for series motors). <b>Hopkinson's test</b>: back-to-back, two identical machines.</li>
<li>Armature reaction: cross-magnetising + demagnetising (with brush shift). Remedied by interpoles (commutation) and compensating windings.</li>
<li>Voltage build-up of a self-excited generator needs residual flux, correct field connection, and field resistance below the critical resistance.</li>
</ul>`},
{h:'Three-phase induction motor',b:`
<ul>
<li>Synchronous speed N<sub>s</sub> = 120f/P. Slip s = (N<sub>s</sub> − N)/N<sub>s</sub>. Rotor current frequency f<sub>r</sub> = s f. Rotor EMF at slip s: sE₂.</li>
<li>Power flow: air-gap power P<sub>g</sub> : rotor copper loss : mechanical power = 1 : s : (1 − s). P<sub>cu2</sub> = sP<sub>g</sub>, P<sub>m</sub> = (1 − s)P<sub>g</sub>. Torque T = P<sub>g</sub>/ω<sub>s</sub>.</li>
<li>Torque (neglecting stator impedance): T = (3/ω<sub>s</sub>)·sE₂²R₂/(R₂² + s²X₂²). Low slip: T ∝ s/R₂ (linear). High slip: T ∝ 1/s.</li>
<li><b>Maximum torque</b> at s<sub>m</sub> = R₂/X₂; T<sub>max</sub> = 3E₂²/(2ω<sub>s</sub>X₂), independent of R₂. Adding rotor resistance shifts s<sub>m</sub> (max starting torque when R₂ = X₂).</li>
<li>T/T<sub>max</sub> = 2/(s/s<sub>m</sub> + s<sub>m</sub>/s). T ∝ V² at a given slip.</li>
<li>Equivalent circuit load resistance: R₂(1 − s)/s.</li>
<li><b>Starting</b>: DOL: I<sub>st</sub> = I<sub>sc</sub>. Star–delta: starting current and torque reduced to 1/3 of DOL. Autotransformer (tapping x): current from supply and torque reduced to x² of DOL. Stator resistance/reactor: torque reduced by (I<sub>st</sub>/I<sub>sc</sub>)².</li>
<li>T<sub>st</sub>/T<sub>fl</sub> = (I<sub>st</sub>/I<sub>fl</sub>)² s<sub>fl</sub>.</li>
<li>Speed control: V/f (constant flux, constant torque below base), pole changing, rotor resistance (slip-ring), slip-power recovery.</li>
<li>Tests: no-load (gives core + friction losses, magnetising branch), blocked-rotor (gives equivalent impedance, copper loss). Circle diagram from these tests.</li>
<li>Crawling (sub-synchronous running at ≈ 1/7 N<sub>s</sub>, due to the 7th harmonic) and cogging (failure to start, slots).</li>
<li>Generator action when N &gt; N<sub>s</sub> (negative slip); braking (plugging) when 1 &lt; s &lt; 2.</li>
</ul>
<div class="trap">Trap: with V/f constant, the maximum torque stays (approximately) constant; with V constant and f reduced, flux and core saturation rise.</div>`},
{h:'Single-phase induction motor',b:`
<ul>
<li>Double revolving field theory: a pulsating field = two half-amplitude fields rotating in opposite directions; net starting torque is zero.</li>
<li>Forward slip s, backward slip 2 − s.</li>
<li>Starting methods: split-phase (resistance), capacitor-start (high starting torque), capacitor-start capacitor-run, permanent split capacitor, shaded pole (lowest efficiency, smallest).</li>
<li>For maximum starting torque in a capacitor-start motor, the auxiliary winding current should lead the main winding current by close to 90°.</li>
</ul>`},
{h:'Synchronous machines',b:`
<ul>
<li>EMF per phase: E = 4.44 f φ T<sub>ph</sub> K<sub>p</sub>K<sub>d</sub>. Pitch factor K<sub>p</sub> = cos(α/2) (α = short-pitch angle). Distribution factor K<sub>d</sub> = sin(mβ/2)/(m sin(β/2)) (m slots/pole/phase, β slot angle). For the n<sup>th</sup> harmonic, use nα and nβ.</li>
<li>To eliminate the n<sup>th</sup> harmonic by short pitching: α = 180°/n.</li>
<li>Generator: E = V + I(R<sub>a</sub> + jX<sub>s</sub>). Motor: V = E + I(R<sub>a</sub> + jX<sub>s</sub>).</li>
<li><b>Power</b> (cylindrical rotor, R<sub>a</sub> ≈ 0): P = (3EV/X<sub>s</sub>) sin δ; maximum at δ = 90° (steady-state limit). Q = (3V/X<sub>s</sub>)(E cos δ − V).</li>
<li>Salient pole: P = (EV/X<sub>d</sub>) sin δ + (V²/2)(1/X<sub>q</sub> − 1/X<sub>d</sub>) sin 2δ (per phase); reluctance power exists even with E = 0. Slip test gives X<sub>d</sub> = V<sub>max</sub>/I<sub>min</sub>, X<sub>q</sub> = V<sub>min</sub>/I<sub>max</sub>.</li>
<li><b>Excitation</b>: over-excited (E cos δ &gt; V) → supplies reactive power: generator at lagging pf / motor at leading pf (synchronous condenser). Under-excited → absorbs reactive power.</li>
<li>V-curves: armature current vs field current at constant power; minimum at unity pf. Inverted V-curves: pf vs field current.</li>
<li>Short-circuit ratio SCR = I<sub>f</sub>(rated OC voltage)/I<sub>f</sub>(rated SC current) ≈ 1/X<sub>s,pu</sub> (saturated). High SCR → better stability, larger machine.</li>
<li>Regulation methods: EMF (synchronous impedance, pessimistic), MMF (ampere-turn, optimistic), ZPF/Potier (accurate). Z<sub>s</sub> = V<sub>OC</sub>/I<sub>SC</sub> at the same field current.</li>
<li><b>Synchronisation</b> conditions: equal voltage, frequency, phase sequence, phase angle.</li>
<li>Synchronous motor is not self-starting (damper windings / pony motor). Hunting reduced by damper windings.</li>
</ul>`}]});
