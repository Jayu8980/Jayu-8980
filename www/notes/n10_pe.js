(window.NOTES=window.NOTES||[]).push({k:'PE',n:'Power Electronics',w:'≈ 7–9 marks',s:[
{h:'Power devices',b:`
<ul>
<li><b>SCR</b>: turns on with gate pulse when forward biased; stays on once anode current exceeds latching current I<sub>L</sub>; turns off when current falls below holding current I<sub>H</sub> (I<sub>L</sub> &gt; I<sub>H</sub>). Gate pulse width must last until I<sub>A</sub> reaches I<sub>L</sub> (with inductive load: L dI/dt = V → t = L·I<sub>L</sub>/V).</li>
<li>Turn-off needs reverse bias for at least t<sub>q</sub> (circuit turn-off time t<sub>c</sub> &gt; t<sub>q</sub>). dv/dt protection: snubber RC. di/dt protection: series inductor.</li>
<li>Triac: bidirectional SCR. GTO: gate turn-off (negative gate current). Diac: triggering device.</li>
<li>BJT: current-controlled, low conduction loss, secondary breakdown. MOSFET: voltage-controlled, fastest switching, positive temperature coefficient (easy paralleling), high R<sub>DS(on)</sub> at high voltage. IGBT: MOSFET input + BJT output, medium-to-high power.</li>
<li>Series SCRs: static voltage sharing with parallel resistors; dynamic with RC. String efficiency = V<sub>string</sub>/(n × V<sub>single</sub>).</li>
</ul>`},
{h:'Controlled rectifiers',b:`
<ul>
<li>Single-phase half-wave, R load: V<sub>o</sub> = (V<sub>m</sub>/2π)(1 + cos α).</li>
<li>Single-phase full converter (bridge / mid-point), continuous current (highly inductive): V<sub>o</sub> = (2V<sub>m</sub>/π) cos α; inverts for α &gt; 90°. R load: (V<sub>m</sub>/π)(1 + cos α).</li>
<li>Single-phase semi-converter: V<sub>o</sub> = (V<sub>m</sub>/π)(1 + cos α) (freewheeling action, no inversion).</li>
<li>Three-phase full converter: V<sub>o</sub> = (3√2/π)V<sub>LL</sub> cos α = (3V<sub>ml</sub>/π)cos α (V<sub>ml</sub> = peak line voltage). Ripple frequency 6f. Three-phase half-wave: (3√3V<sub>mp</sub>/2π) cos α.</li>
<li>Three-phase semi-converter: (3V<sub>ml</sub>/2π)(1 + cos α).</li>
<li>Source inductance (overlap angle μ): voltage drop = (3ωL<sub>s</sub>/π)I<sub>d</sub> for 3-phase full bridge, (2ωL<sub>s</sub>/π)I<sub>d</sub> for 1-phase full bridge. cos α − cos(α + μ) = 2ωL<sub>s</sub>I<sub>d</sub>/V<sub>m</sub> (1-φ) or 2ωL<sub>s</sub>I<sub>d</sub>/V<sub>ml</sub> (3-φ).</li>
<li>Single-phase full converter with constant I<sub>d</sub>: supply current is a square wave; rms fundamental I<sub>s1</sub> = (2√2/π)I<sub>d</sub> = 0.9I<sub>d</sub>; displacement factor cos α; input pf = 0.9 cos α. Three-phase: I<sub>s1</sub> = (√6/π)I<sub>d</sub>, input pf = (3/π) cos α.</li>
<li>Harmonics in the supply current: orders 6k ± 1 (3-phase bridge), odd harmonics (1-phase); amplitude ∝ 1/n.</li>
<li>Dual converter: α₁ + α₂ = 180° to avoid DC circulating current.</li>
</ul>
<div class="trap">Trap: with an R-L load and discontinuous current, the conduction extends beyond π (extinction angle β), and the simple cos α formulas do not apply. With a back EMF E, the SCR can fire only when v<sub>s</sub> &gt; E.</div>`},
{h:'DC–DC converters (choppers)',b:`
<ul>
<li>Duty ratio D = T<sub>on</sub>/T. <b>Buck</b>: V<sub>o</sub> = DV<sub>s</sub>. <b>Boost</b>: V<sub>o</sub> = V<sub>s</sub>/(1 − D). <b>Buck–boost</b>: V<sub>o</sub> = −DV<sub>s</sub>/(1 − D). <b>Ćuk</b>: same magnitude as buck–boost, inverted polarity, continuous input and output currents.</li>
<li>Buck inductor ripple ΔI = V<sub>s</sub>D(1 − D)/(fL), maximum at D = 0.5: V<sub>s</sub>/(4fL). Output capacitor ripple ΔV = ΔI/(8fC).</li>
<li>Boost inductor ripple ΔI = V<sub>s</sub>D/(fL); capacitor ripple ΔV = I<sub>o</sub>D/(fC).</li>
<li>CCM/DCM boundary (buck): L<sub>crit</sub> = (1 − D)R/(2f).</li>
<li>Average inductor current: buck = I<sub>o</sub>; boost = I<sub>o</sub>/(1 − D); buck–boost = I<sub>o</sub>/(1 − D).</li>
<li>Steady state: average inductor voltage = 0 (volt-second balance); average capacitor current = 0 (charge balance).</li>
<li>Chopper quadrants: Class A (1st quadrant, motoring), Class B (2nd, regenerative), Class C (1&amp;2), Class D (1&amp;4), Class E (all four).</li>
</ul>`},
{h:'Inverters',b:`
<ul>
<li>Single-phase half-bridge square wave: peak fundamental = 4V<sub>dc</sub>/(2π) = 2V<sub>dc</sub>/π; full bridge: 4V<sub>dc</sub>/π (rms fundamental 0.9V<sub>dc</sub>).</li>
<li>Quasi-square (pulse width 2d centred): V<sub>n</sub> = (4V<sub>dc</sub>/nπ) sin(nd) (full bridge). To eliminate the n<sup>th</sup> harmonic, make sin(nd) = 0 → d = π/n, i.e. pulse width 2d = 2π/n (a 120° pulse removes the 3rd harmonic).</li>
<li>Three-phase 180° conduction: line voltage is a 120° quasi-square wave; phase voltage is six-step. V<sub>L1,rms</sub> = (√6/π)V<sub>dc</sub> = 0.78V<sub>dc</sub>; V<sub>ph1,rms</sub> = (√2/π)V<sub>dc</sub> = 0.45V<sub>dc</sub>. No triplen harmonics in line voltage.</li>
<li>Sinusoidal PWM: modulation index m<sub>a</sub> = V<sub>control</sub>/V<sub>tri</sub>. Linear range m<sub>a</sub> ≤ 1: fundamental peak (phase, half-bridge leg) = m<sub>a</sub>V<sub>dc</sub>/2. Harmonics appear near multiples of the carrier frequency (m<sub>f</sub>). Overmodulation m<sub>a</sub> &gt; 1.</li>
<li>Voltage source inverters need anti-parallel (freewheeling) diodes for reactive loads. Current source inverters need devices that block reverse voltage.</li>
</ul>`},
{h:'AC voltage controllers, cycloconverters & drives',b:`
<ul>
<li>Single-phase AC voltage controller (R load, phase control): V<sub>rms</sub> = V<sub>s</sub>√[(1/π)(π − α + sin 2α/2)]. Integral-cycle control: V<sub>rms</sub> = V<sub>s</sub>√k (k = on cycles / total cycles), pf = √k.</li>
<li>With an R-L load, control is lost for α &lt; φ (load phase angle).</li>
<li>Cycloconverter: AC to lower-frequency AC directly; output frequency typically below ⅓ of the input frequency.</li>
<li>DC drive via converter: V<sub>o</sub> = E<sub>b</sub> + I<sub>a</sub>R<sub>a</sub>, E<sub>b</sub> = Kω. Regeneration needs α &gt; 90° with reversed back EMF (full converter).</li>
<li>Induction motor V/f control: constant air-gap flux below base speed.</li>
</ul>
<div class="trap">Trap: rms of a waveform from a converter is not the same as the rms of its fundamental. Use I<sub>rms</sub> = √(I<sub>dc</sub>² + ΣI<sub>n</sub>²), and distortion factor = I<sub>1</sub>/I<sub>rms</sub>; power factor = distortion factor × displacement factor.</div>`}]});
