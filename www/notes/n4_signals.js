(window.NOTES=window.NOTES||[]).push({k:'SS',n:'Signals and Systems',w:'≈ 7–9 marks',s:[
{h:'Signals & system properties',b:`
<ul>
<li>Energy E = ∫|x|² dt (finite for energy signals, P = 0). Power P = lim (1/T)∫|x|² dt (finite for power signals, E = ∞). Periodic signals are power signals.</li>
<li>Periodicity: x(t) = cos(ω₁t) + cos(ω₂t) is periodic only if ω₁/ω₂ is rational; period = LCM of the individual periods. Discrete cos(Ω₀n) is periodic only if Ω₀/(2π) is rational: N = 2πk/Ω₀ (smallest integer).</li>
<li>Even part ½[x(t) + x(−t)], odd part ½[x(t) − x(−t)].</li>
<li>Time operations: x(at − b) → shift by b first, then scale by a (or scale first, then shift by b/a).</li>
<li>δ(at) = δ(t)/|a|; ∫x(t)δ(t − t₀)dt = x(t₀); x(t)δ(t − t₀) = x(t₀)δ(t − t₀).</li>
<li><b>Properties</b>: Linear (additivity + homogeneity; a non-zero constant term → nonlinear). Time-invariant (no t multiplying x, no time scaling like x(2t), no x(−t)). Causal (output depends only on present/past input). Stable (BIBO: ∫|h(t)| dt &lt; ∞; all poles in LHP / inside unit circle). Memoryless (depends on present input only).</li>
<li>Examples: y = x(t)·cos t → linear, time-variant. y = x(2t) → time-variant, non-causal. y = x(−t) → time-variant, non-causal. y(n) = n x(n) → time-variant.</li>
</ul>`},
{h:'LTI systems & convolution',b:`
<ul>
<li>y(t) = x(t) ∗ h(t) = ∫x(τ)h(t − τ)dτ; y[n] = Σx[k]h[n − k].</li>
<li>Commutative, associative, distributive. Cascade: h₁ ∗ h₂; parallel: h₁ + h₂.</li>
<li>Duration: lengths add (continuous); discrete lengths N₁ + N₂ − 1. Start time = sum of start times.</li>
<li>Area of y = (area of x)(area of h). x ∗ δ(t − t₀) = x(t − t₀). u(t) ∗ u(t) = t u(t).</li>
<li>Step response s(t) = ∫h dt; h(t) = ds/dt.</li>
<li>Eigenfunction: e<sup>st</sup> → H(s)e<sup>st</sup>; for sinusoid A cos(ω₀t + θ) the output is A|H(jω₀)| cos(ω₀t + θ + ∠H(jω₀)).</li>
<li>Rectangle of width a convolved with rectangle of width b (a ≤ b) → trapezoid of total width a + b, flat top length b − a.</li>
</ul>`},
{h:'Fourier series',b:`
<ul>
<li>x(t) = Σ c<sub>k</sub> e<sup>jkω₀t</sup>, c<sub>k</sub> = (1/T)∫x(t)e<sup>−jkω₀t</sup>dt; c₀ = average value.</li>
<li>Parseval: P = Σ|c<sub>k</sub>|².</li>
<li>Even signal → cosine terms only (real c<sub>k</sub>). Odd → sine terms only. <b>Half-wave symmetry</b> x(t ± T/2) = −x(t) → odd harmonics only.</li>
<li>Square wave (±A, 50% duty): (4A/π)Σ sin(kω₀t)/k for odd k. Coefficients decay as 1/k for jumps, 1/k² for kinks (continuous signal with discontinuous derivative).</li>
<li>Gibbs phenomenon: about 9% overshoot near a discontinuity, regardless of the number of terms; series converges to the midpoint at the jump.</li>
</ul>`},
{h:'Fourier transform',b:`
<ul>
<li>X(ω) = ∫x(t)e<sup>−jωt</sup>dt. Pairs: e<sup>−at</sup>u(t) ↔ 1/(a + jω); rect(t/τ) ↔ τ sinc(ωτ/2π) [first zero at ω = 2π/τ]; δ(t) ↔ 1; 1 ↔ 2πδ(ω); u(t) ↔ πδ(ω) + 1/(jω); e<sup>−a|t|</sup> ↔ 2a/(a² + ω²); sgn(t) ↔ 2/(jω).</li>
<li>Properties: shift x(t − t₀) ↔ e<sup>−jωt₀</sup>X; modulation x e<sup>jω₀t</sup> ↔ X(ω − ω₀); scaling x(at) ↔ (1/|a|)X(ω/a); duality; differentiation ↔ jωX; convolution ↔ product.</li>
<li>X(0) = ∫x dt; x(0) = (1/2π)∫X dω. Parseval: ∫|x|²dt = (1/2π)∫|X|²dω.</li>
<li>Real and even x → X real and even. Real x → |X| even, ∠X odd.</li>
<li><b>Ideal filters</b> are non-causal. Distortionless system: H = K e<sup>−jωt<sub>d</sub></sup> (constant magnitude, linear phase). Group delay = −dθ/dω.</li>
</ul>`},
{h:'Laplace transform',b:`
<ul>
<li>Pairs: u(t) ↔ 1/s; t<sup>n</sup>u(t) ↔ n!/s<sup>n+1</sup>; e<sup>−at</sup>u(t) ↔ 1/(s + a); sin ω₀t u(t) ↔ ω₀/(s² + ω₀²); cos ω₀t u(t) ↔ s/(s² + ω₀²); e<sup>−at</sup>sin ω₀t ↔ ω₀/((s+a)² + ω₀²).</li>
<li>Properties: e<sup>−at</sup>x ↔ X(s + a); x(t − t₀)u(t − t₀) ↔ e<sup>−st₀</sup>X; dx/dt ↔ sX − x(0⁻); ∫x ↔ X/s; t x(t) ↔ −dX/ds.</li>
<li><b>Initial value</b>: x(0⁺) = lim<sub>s→∞</sub> sX(s) (X strictly proper). <b>Final value</b>: x(∞) = lim<sub>s→0</sub> sX(s), valid only if all poles of sX(s) are in the LHP.</li>
<li>ROC: right-sided signal → right of the rightmost pole; left-sided → left of the leftmost pole; two-sided → a strip. ROC contains no poles. Stable ⇔ ROC includes the jω-axis. Causal and stable ⇔ all poles in the LHP.</li>
<li>Same X(s) with different ROC → different signals (e.g. 1/(s + a): e<sup>−at</sup>u(t) for Re s &gt; −a, −e<sup>−at</sup>u(−t) for Re s &lt; −a).</li>
</ul>`},
{h:'Sampling, DTFT & Z-transform',b:`
<ul>
<li><b>Nyquist</b>: f<sub>s</sub> ≥ 2f<sub>max</sub> (for a low-pass signal). Spectrum of the sampled signal repeats every f<sub>s</sub>. Aliasing: a tone f appears at |f − kf<sub>s</sub>|.</li>
<li>Nyquist rate of x²(t) = 2 × (Nyquist rate of x); of x₁x₂ = 2(f₁ + f₂); of x₁ + x₂ = 2 max(f₁, f₂).</li>
<li>Band-pass sampling: f<sub>s</sub> = 2f<sub>H</sub>/k where k = floor(f<sub>H</sub>/B) works for band [f<sub>L</sub>, f<sub>H</sub>] with bandwidth B.</li>
<li>DTFT X(e<sup>jΩ</sup>) is periodic with 2π. DFT of N points: X[k] = Σx[n]e<sup>−j2πkn/N</sup>; circular convolution ↔ product of DFTs. Linear convolution via DFT needs N ≥ N₁ + N₂ − 1.</li>
<li><b>Z-transform</b>: aⁿu[n] ↔ z/(z − a), |z| &gt; |a|; −aⁿu[−n−1] ↔ z/(z − a), |z| &lt; |a|; δ[n] ↔ 1; x[n − k] ↔ z<sup>−k</sup>X(z); n x[n] ↔ −z dX/dz; aⁿx[n] ↔ X(z/a).</li>
<li>Causal and stable ⇔ all poles inside the unit circle. Final value: x[∞] = lim<sub>z→1</sub>(z − 1)X(z) (poles of (z−1)X inside unit circle).</li>
<li>Mapping s → z = e<sup>sT</sup>: jω-axis → unit circle; LHP → inside.</li>
</ul>
<div class="trap">Trap: an FIR filter (finite h[n]) is always stable. A system with pole-zero cancellation on or outside the unit circle may look stable from H(z) but have unstable internal modes.</div>`}]});
