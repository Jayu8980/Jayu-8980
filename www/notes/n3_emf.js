(window.NOTES=window.NOTES||[]).push({k:'EMF',n:'Electromagnetic Fields',w:'≈ 3–5 marks',s:[
{h:'Electrostatics',b:`
<ul>
<li>Coulomb: F = Q₁Q₂/(4πε₀r²); 1/(4πε₀) ≈ 9 × 10⁹ N·m²/C²; ε₀ = 8.854 × 10⁻¹² F/m.</li>
<li>E of a point charge = Q/(4πε₀r²); infinite line charge ρ<sub>L</sub>: E = ρ<sub>L</sub>/(2πε₀r) (radial); infinite sheet ρ<sub>s</sub>: E = ρ<sub>s</sub>/(2ε₀) (independent of distance).</li>
<li>Uniformly charged sphere (total Q, radius a): outside E = Q/(4πε₀r²); inside E = Qr/(4πε₀a³). Conducting sphere: E = 0 inside.</li>
<li><b>Gauss's law</b>: ∯D·dS = Q<sub>enc</sub>; point form ∇·D = ρ<sub>v</sub>. D = εE = ε₀ε<sub>r</sub>E.</li>
<li>E = −∇V; V of a point charge = Q/(4πε₀r). ∮E·dl = 0 (electrostatic field is conservative).</li>
<li>Poisson: ∇²V = −ρ<sub>v</sub>/ε; Laplace (charge-free): ∇²V = 0.</li>
<li>Energy: W = ½∫εE² dV = ½CV². Energy density = ½εE².</li>
<li><b>Boundary conditions</b>: E<sub>t1</sub> = E<sub>t2</sub>; D<sub>n1</sub> − D<sub>n2</sub> = ρ<sub>s</sub> (D<sub>n</sub> continuous if no free surface charge). At a conductor: E<sub>t</sub> = 0, D<sub>n</sub> = ρ<sub>s</sub>. Refraction: tan θ₁/tan θ₂ = ε<sub>r1</sub>/ε<sub>r2</sub>.</li>
<li>Method of images: point charge Q at height h above a grounded plane → image −Q at depth h.</li>
</ul>`},
{h:'Capacitance',b:`
<ul>
<li>Parallel plate: C = εA/d. Two dielectrics in series layers (thicknesses d₁, d₂): C = ε₀A/(d₁/ε<sub>r1</sub> + d₂/ε<sub>r2</sub>). Side by side (areas A₁, A₂): capacitances add.</li>
<li>Coaxial cable (inner a, outer b): C/length = 2πε/ln(b/a).</li>
<li>Isolated sphere radius a: C = 4πεa. Concentric spheres a &lt; b: C = 4πε ab/(b − a).</li>
<li>Two-wire line (radius a, spacing d ≫ a): C/length = πε/ln(d/a).</li>
<li>Inserting a slab of thickness t (dielectric ε<sub>r</sub>) in a plate gap d: C = ε₀A/(d − t + t/ε<sub>r</sub>). A metal slab: C = ε₀A/(d − t).</li>
</ul>
<div class="trap">Trap: with the battery connected, V stays constant (Q changes). After disconnecting, Q stays constant (V and energy change).</div>`},
{h:'Magnetostatics',b:`
<ul>
<li>Biot–Savart: dH = I dl × â<sub>R</sub>/(4πR²). Ampère: ∮H·dl = I<sub>enc</sub>; point form ∇×H = J. B = μH; μ₀ = 4π × 10⁻⁷ H/m. ∇·B = 0 (no magnetic monopoles).</li>
<li>Infinite straight wire: H = I/(2πr). Finite wire: H = (I/4πρ)(cos α₂ − cos α₁) (angles measured from the wire to the end points), or (I/4πρ)(sin β₁ + sin β₂) with β from the perpendicular.</li>
<li>Centre of a circular loop (radius a, N turns): H = NI/(2a). On the axis at distance z: H = Ia²/[2(a² + z²)<sup>3/2</sup>].</li>
<li>Long solenoid: H = nI inside (n turns per metre), ≈ 0 outside. Toroid: H = NI/(2πr) inside, 0 outside.</li>
<li>Infinite current sheet K: H = ½ K × â<sub>n</sub> (uniform magnitude K/2).</li>
<li>Force: F = q(E + v × B) (Lorentz). On a conductor: F = I L × B. Between parallel wires: F/length = μ₀I₁I₂/(2πd), attractive for currents in the same direction.</li>
<li>Boundary: B<sub>n1</sub> = B<sub>n2</sub>; H<sub>t1</sub> − H<sub>t2</sub> = K (surface current).</li>
<li>Inductance: L = Nφ/I. Solenoid L = μN²A/l. Coax: L/length = (μ/2π) ln(b/a). Energy ½LI² = ½∫μH² dV.</li>
<li>Magnetic circuit: reluctance ℛ = l/(μA); flux φ = NI/ℛ (mmf/reluctance); reluctances in series add.</li>
</ul>`},
{h:'Time-varying fields',b:`
<ul>
<li>Faraday: emf = −dλ/dt = −N dφ/dt; ∇×E = −∂B/∂t. Motional emf = ∫(v × B)·dl (= Blv for a rod).</li>
<li>Displacement current density J<sub>d</sub> = ∂D/∂t; ∇×H = J + ∂D/∂t. Ratio of conduction to displacement current = σ/(ωε) (loss tangent).</li>
<li>Continuity: ∇·J = −∂ρ<sub>v</sub>/∂t.</li>
<li><b>Maxwell's equations</b> (point form): ∇·D = ρ<sub>v</sub>; ∇·B = 0; ∇×E = −∂B/∂t; ∇×H = J + ∂D/∂t.</li>
<li>Plane wave in free space: c = 1/√(μ₀ε₀) = 3 × 10⁸ m/s; intrinsic impedance η₀ = √(μ₀/ε₀) ≈ 377 Ω (120π). In a lossless dielectric: v = c/√(μ<sub>r</sub>ε<sub>r</sub>), η = η₀√(μ<sub>r</sub>/ε<sub>r</sub>).</li>
<li>Good conductor: skin depth δ = 1/√(πfμσ) = √(2/(ωμσ)).</li>
<li>Poynting vector S = E × H; average power density = |E|²/(2η) for peak amplitude E.</li>
<li>Normal incidence reflection coefficient Γ = (η₂ − η₁)/(η₂ + η₁); transmission τ = 1 + Γ. VSWR = (1 + |Γ|)/(1 − |Γ|).</li>
</ul>`}]});
