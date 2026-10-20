(window.NOTES=window.NOTES||[]).push({k:'AD',n:'Analog & Digital Electronics',w:'≈ 7–9 marks',s:[
{h:'Diodes & diode circuits',b:`
<ul>
<li>Diode equation: I = I<sub>s</sub>(e<sup>V/(ηV<sub>T</sub>)</sup> − 1), V<sub>T</sub> ≈ 26 mV at 300 K. Dynamic resistance r<sub>d</sub> = ηV<sub>T</sub>/I. I<sub>s</sub> roughly doubles every 10 °C; V<sub>D</sub> drops ≈ 2 mV/°C.</li>
<li>Half-wave rectifier: V<sub>dc</sub> = V<sub>m</sub>/π, ripple factor 1.21, efficiency 40.6%, PIV = V<sub>m</sub>, ripple frequency f.</li>
<li>Full-wave (centre-tap): V<sub>dc</sub> = 2V<sub>m</sub>/π, ripple factor 0.482, efficiency 81.2%, PIV = 2V<sub>m</sub>. Bridge: PIV = V<sub>m</sub>, two diode drops. Ripple frequency 2f.</li>
<li>Capacitor filter: ripple V<sub>r(p-p)</sub> ≈ I<sub>dc</sub>/(fC) (half-wave), I<sub>dc</sub>/(2fC) (full-wave).</li>
<li>Clippers limit the output; clampers shift the DC level (peak-to-peak unchanged). Voltage doubler output ≈ 2V<sub>m</sub>.</li>
<li>Zener regulator: I<sub>Z</sub> = (V<sub>in</sub> − V<sub>Z</sub>)/R − I<sub>L</sub>; ensure I<sub>Z,min</sub> ≤ I<sub>Z</sub> ≤ I<sub>Z,max</sub> over the range of V<sub>in</sub> and load.</li>
</ul>
<div class="trap">Trap: in diode circuits, assume each diode's state (ON/OFF), solve, then check consistency (ON → I &gt; 0; OFF → V &lt; V<sub>γ</sub>).</div>`},
{h:'BJT & MOSFET',b:`
<ul>
<li>BJT: I<sub>C</sub> = βI<sub>B</sub>, I<sub>E</sub> = (β + 1)I<sub>B</sub>, α = β/(β + 1). Active: BE forward, BC reverse. Saturation: both forward (V<sub>CE,sat</sub> ≈ 0.2 V), β<sub>forced</sub> &lt; β.</li>
<li>Small signal: g<sub>m</sub> = I<sub>C</sub>/V<sub>T</sub>; r<sub>π</sub> = β/g<sub>m</sub>; r<sub>o</sub> = V<sub>A</sub>/I<sub>C</sub>.</li>
<li>CE amplifier gain ≈ −g<sub>m</sub>(R<sub>C</sub>‖R<sub>L</sub>); with un-bypassed R<sub>E</sub>: ≈ −R<sub>C</sub>/R<sub>E</sub>. Input resistance with R<sub>E</sub>: r<sub>π</sub> + (β + 1)R<sub>E</sub>.</li>
<li>CC (emitter follower): gain ≈ 1, high R<sub>in</sub>, low R<sub>out</sub> ≈ (R<sub>s</sub> + r<sub>π</sub>)/(β + 1). CB: gain ≈ g<sub>m</sub>R<sub>C</sub>, low R<sub>in</sub> ≈ 1/g<sub>m</sub>, wide bandwidth.</li>
<li>Bias stability: voltage-divider bias is most stable; stability factor S = (1 + β)/(1 + β R<sub>E</sub>/(R<sub>E</sub> + R<sub>B</sub>)).</li>
<li>MOSFET saturation (V<sub>DS</sub> ≥ V<sub>GS</sub> − V<sub>T</sub>): I<sub>D</sub> = ½k′(W/L)(V<sub>GS</sub> − V<sub>T</sub>)². Triode: I<sub>D</sub> = k′(W/L)[(V<sub>GS</sub> − V<sub>T</sub>)V<sub>DS</sub> − V<sub>DS</sub>²/2]. g<sub>m</sub> = 2I<sub>D</sub>/(V<sub>GS</sub> − V<sub>T</sub>) = √(2k′(W/L)I<sub>D</sub>).</li>
<li>Miller effect: a capacitance C between input and output of an inverting amplifier with gain −A appears at the input as C(1 + A).</li>
<li>Differential amplifier: CMRR = A<sub>d</sub>/A<sub>cm</sub>; higher tail resistance → higher CMRR.</li>
</ul>`},
{h:'Op-amps',b:`
<ul>
<li>Ideal: infinite gain, infinite R<sub>in</sub>, zero R<sub>out</sub>; with negative feedback, virtual short V₊ = V₋ and no input current.</li>
<li>Inverting gain = −R<sub>f</sub>/R<sub>1</sub>; non-inverting = 1 + R<sub>f</sub>/R<sub>1</sub>; follower = 1. Summing: V<sub>o</sub> = −R<sub>f</sub>Σ(V<sub>k</sub>/R<sub>k</sub>).</li>
<li>Difference amplifier: with R₂/R₁ = R₄/R₃, V<sub>o</sub> = (R₂/R₁)(V₂ − V₁).</li>
<li>Integrator: V<sub>o</sub> = −(1/RC)∫V<sub>in</sub>dt. Differentiator: V<sub>o</sub> = −RC dV<sub>in</sub>/dt.</li>
<li>Gain–bandwidth product constant: closed-loop BW = GBW/(noise gain), noise gain = 1 + R<sub>f</sub>/R<sub>1</sub> (also for the inverting configuration).</li>
<li>Slew rate SR: full-power bandwidth f<sub>max</sub> = SR/(2πV<sub>m</sub>).</li>
<li>Schmitt trigger (inverting, β = R₁/(R₁ + R₂)): thresholds ±βV<sub>sat</sub>; hysteresis = 2βV<sub>sat</sub>.</li>
<li>Precision rectifier, log/antilog amplifiers (diode/BJT in feedback), instrumentation amplifier gain = (1 + 2R/R<sub>G</sub>)(R₃/R₂).</li>
<li>Negative feedback (series-shunt / voltage-series): R<sub>in</sub> × (1 + Aβ), R<sub>out</sub> ÷ (1 + Aβ), bandwidth × (1 + Aβ), gain ÷ (1 + Aβ).</li>
</ul>`},
{h:'Oscillators, filters, timers',b:`
<ul>
<li>Barkhausen: loop gain |Aβ| = 1, phase 0° (360°).</li>
<li>RC phase-shift (3 RC sections): f = 1/(2πRC√6), needs gain ≥ 29. Wien bridge: f = 1/(2πRC), needs gain ≥ 3 (β = 1/3).</li>
<li>Colpitts: f = 1/(2π√(LC<sub>eq</sub>)), C<sub>eq</sub> = C₁C₂/(C₁ + C₂). Hartley: L<sub>eq</sub> = L₁ + L₂ + 2M. Crystal: very stable (high Q).</li>
<li>First-order RC low-pass: f<sub>c</sub> = 1/(2πRC). Butterworth: maximally flat; −20n dB/decade roll-off.</li>
<li>555 astable: t<sub>H</sub> = 0.693(R<sub>A</sub> + R<sub>B</sub>)C, t<sub>L</sub> = 0.693R<sub>B</sub>C, f = 1.44/((R<sub>A</sub> + 2R<sub>B</sub>)C). Monostable: T = 1.1RC.</li>
</ul>`},
{h:'Number systems & Boolean algebra',b:`
<ul>
<li>2's complement of an n-bit number: invert bits and add 1; range −2<sup>n−1</sup> to 2<sup>n−1</sup> − 1. Overflow when adding two numbers of the same sign gives a result of the opposite sign.</li>
<li>De Morgan: (A + B)′ = A′B′; (AB)′ = A′ + B′. Absorption: A + AB = A; A + A′B = A + B. Consensus: AB + A′C + BC = AB + A′C.</li>
<li>NAND and NOR are universal gates. XOR: A ⊕ B = AB′ + A′B; odd-parity function.</li>
<li>K-map: group 1s in powers of 2 (adjacent cells, wrap-around); don't-cares can be used as 1 or 0.</li>
<li>Number of Boolean functions of n variables = 2<sup>2ⁿ</sup>. Self-dual functions of n variables: 2<sup>2<sup>n−1</sup></sup>.</li>
<li>Minimum number of 2-input NAND gates: XOR needs 4; full adder 9.</li>
</ul>`},
{h:'Combinational & sequential circuits',b:`
<ul>
<li>Half adder: S = A ⊕ B, C = AB. Full adder: S = A ⊕ B ⊕ C<sub>in</sub>, C<sub>out</sub> = AB + C<sub>in</sub>(A ⊕ B).</li>
<li>Ripple-carry adder delay ∝ n; carry look-ahead is faster. A 2ⁿ:1 MUX can implement any function of n + 1 variables (with inverters).</li>
<li>Decoder n:2ⁿ with an OR gate implements any sum of minterms.</li>
<li>Flip-flops: SR (S = R = 1 forbidden), JK (J = K = 1 toggles), D (Q⁺ = D), T (Q⁺ = T ⊕ Q). Characteristic of JK: Q⁺ = JQ′ + K′Q.</li>
<li>Race-around in level-triggered JK when J = K = 1 and the pulse width &gt; propagation delay → use master–slave or edge triggering.</li>
<li>Counters: n-bit ripple counter has 2ⁿ states (max frequency limited by n·t<sub>pd</sub>). Ring counter: n states (n flip-flops). Johnson (twisted ring): 2n states. MOD-N needs ⌈log₂N⌉ flip-flops.</li>
<li>Shift register: n-bit, serial-in data takes n clock pulses to load.</li>
</ul>`},
{h:'ADC/DAC & microprocessor basics',b:`
<ul>
<li>n-bit DAC resolution = V<sub>FS</sub>/(2ⁿ − 1) (step size); % resolution = 100/(2ⁿ − 1). R-2R ladder uses only two resistor values.</li>
<li>ADC: quantisation error ±½ LSB; SQNR ≈ 6.02n + 1.76 dB. Successive approximation: n clock cycles; flash: 2ⁿ − 1 comparators; dual-slope: slow, accurate; counter-type: up to 2ⁿ clocks.</li>
<li>8085: 8-bit data bus, 16-bit address bus (64 KB memory), 5 hardware interrupts (TRAP non-maskable, highest priority), flags S Z AC P CY.</li>
<li>Memory size: address lines n, data lines m → 2ⁿ × m bits.</li>
</ul>`}]});
