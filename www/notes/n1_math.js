(window.NOTES=window.NOTES||[]).push({k:'EM',n:'Engineering Mathematics',w:'≈ 11–13 marks',s:[
{h:'Linear algebra',b:`
<ul>
<li><b>Rank</b> = number of non-zero rows in row-echelon form = max number of linearly independent rows (or columns). rank(A) ≤ min(m, n).</li>
<li><b>System Ax = b</b> (n unknowns): rank(A) ≠ rank([A|b]) → no solution. rank(A) = rank([A|b]) = n → unique solution. rank(A) = rank([A|b]) = r &lt; n → infinitely many solutions with (n − r) free variables.</li>
<li><b>Homogeneous Ax = 0</b>: always consistent. A non-trivial solution exists ⇔ rank &lt; n ⇔ |A| = 0 (square A). Number of independent solutions = n − rank (nullity).</li>
<li><b>Eigenvalues</b>: roots of |A − λI| = 0. Σλ = trace(A); Πλ = |A|.</li>
<li>Triangular / diagonal matrix: eigenvalues are the diagonal entries.</li>
<li>If λ is an eigenvalue of A: A<sup>k</sup> → λ<sup>k</sup>; A<sup>−1</sup> → 1/λ; kA → kλ; A + kI → λ + k; adj A → |A|/λ. A and A<sup>T</sup> have the same eigenvalues.</li>
<li>Real symmetric matrix: real eigenvalues, orthogonal eigenvectors for distinct λ. Skew-symmetric: zero or purely imaginary eigenvalues. Orthogonal matrix: |λ| = 1, |A| = ±1.</li>
<li><b>Cayley–Hamilton</b>: every square matrix satisfies its own characteristic equation. Use it to find A<sup>−1</sup> or high powers of A.</li>
<li>Eigenvectors for distinct eigenvalues are linearly independent. A matrix is diagonalisable if it has n independent eigenvectors.</li>
<li>Algebraic multiplicity ≥ geometric multiplicity (number of independent eigenvectors for that λ).</li>
<li>Positive definite ⇔ all eigenvalues &gt; 0 ⇔ all leading principal minors &gt; 0.</li>
</ul>
<div class="trap">Trap: rank of a product ≤ min(rank A, rank B). A rank-1 n×n matrix uvᵀ has eigenvalues 0 (n−1 times) and vᵀu.</div>`},
{h:'Calculus',b:`
<ul>
<li><b>L'Hôpital</b> for 0/0 or ∞/∞ forms. Standard limits: lim<sub>x→0</sub> sin x / x = 1; lim (1 + 1/n)<sup>n</sup> = e; lim<sub>x→0</sub> (a<sup>x</sup> − 1)/x = ln a.</li>
<li><b>Mean value theorems</b>: Rolle (f(a) = f(b) ⇒ f′(c) = 0 for some c in (a, b)); Lagrange: f′(c) = [f(b) − f(a)]/(b − a).</li>
<li><b>Maxima / minima (one variable)</b>: f′ = 0; f″ &lt; 0 → maximum, f″ &gt; 0 → minimum. On a closed interval, also check the end points.</li>
<li><b>Two variables</b>: f<sub>x</sub> = f<sub>y</sub> = 0. With r = f<sub>xx</sub>, s = f<sub>xy</sub>, t = f<sub>yy</sub>: rt − s² &gt; 0 and r &lt; 0 → max; rt − s² &gt; 0 and r &gt; 0 → min; rt − s² &lt; 0 → saddle; rt − s² = 0 → no conclusion.</li>
<li><b>Integration by parts</b>: ∫u dv = uv − ∫v du (ILATE order for choosing u).</li>
<li>∫<sub>0</sub><sup>π/2</sup> sin<sup>n</sup>x dx = ∫<sub>0</sub><sup>π/2</sup> cos<sup>n</sup>x dx: Wallis formula. Example: n = 2 → π/4; n = 3 → 2/3; n = 4 → 3π/16.</li>
<li>∫<sub>−a</sub><sup>a</sup> f dx = 0 for odd f, and 2∫<sub>0</sub><sup>a</sup> f dx for even f. ∫<sub>0</sub><sup>a</sup> f(x) dx = ∫<sub>0</sub><sup>a</sup> f(a − x) dx.</li>
<li>∫<sub>0</sub><sup>∞</sup> e<sup>−ax</sup> dx = 1/a; ∫<sub>0</sub><sup>∞</sup> x<sup>n</sup>e<sup>−ax</sup> dx = n!/a<sup>n+1</sup>; ∫<sub>−∞</sub><sup>∞</sup> e<sup>−x²</sup> dx = √π.</li>
<li><b>Taylor series</b> about a: f(x) = Σ f<sup>(n)</sup>(a)(x − a)<sup>n</sup>/n!.</li>
</ul>`},
{h:'Vector calculus',b:`
<ul>
<li>Gradient ∇f: points in the direction of maximum increase; its magnitude is the maximum rate of change. Directional derivative along unit vector â: ∇f · â.</li>
<li>Divergence ∇·A (scalar); curl ∇×A (vector). Identities: ∇·(∇×A) = 0; ∇×(∇f) = 0.</li>
<li>Solenoidal: ∇·A = 0. Irrotational (conservative): ∇×A = 0 ⇒ A = ∇φ, and the line integral is independent of path.</li>
<li>For the position vector r: ∇·r = 3, ∇×r = 0, ∇r = r̂, ∇(1/r) = −r̂/r².</li>
<li><b>Gauss divergence</b>: ∯A·dS = ∭(∇·A) dV. <b>Stokes</b>: ∮A·dl = ∬(∇×A)·dS. <b>Green (plane)</b>: ∮(M dx + N dy) = ∬(∂N/∂x − ∂M/∂y) dx dy.</li>
<li>Area enclosed by a closed curve C = ½∮(x dy − y dx).</li>
</ul>`},
{h:'Differential equations',b:`
<ul>
<li><b>First-order linear</b> y′ + P y = Q: integrating factor IF = e<sup>∫P dx</sup>; y·IF = ∫Q·IF dx + C.</li>
<li><b>Exact</b> M dx + N dy = 0 if ∂M/∂y = ∂N/∂x.</li>
<li><b>Linear, constant coefficients</b>: from auxiliary equation roots m —
 distinct real m₁, m₂ → c₁e<sup>m₁x</sup> + c₂e<sup>m₂x</sup>; repeated m → (c₁ + c₂x)e<sup>mx</sup>; complex α ± jβ → e<sup>αx</sup>(c₁cos βx + c₂sin βx).</li>
<li><b>Particular integral</b>: for e<sup>ax</sup>, PI = e<sup>ax</sup>/f(a); if f(a) = 0, multiply by x and differentiate f (PI = x e<sup>ax</sup>/f′(a)). For sin ax / cos ax, replace D² by −a².</li>
<li><b>Cauchy–Euler</b> x²y″ + a x y′ + b y = 0: put x = e<sup>t</sup> (xD → θ), giving θ(θ − 1) + aθ + b = 0.</li>
<li>Wronskian ≠ 0 ⇒ the solutions are linearly independent.</li>
</ul>`},
{h:'Complex variables',b:`
<ul>
<li><b>Analytic</b> f = u + jv: Cauchy–Riemann equations u<sub>x</sub> = v<sub>y</sub>, u<sub>y</sub> = −v<sub>x</sub>. u and v are then harmonic (∇²u = 0).</li>
<li><b>Cauchy's integral theorem</b>: ∮f dz = 0 if f is analytic inside and on C.</li>
<li><b>Cauchy's integral formula</b>: ∮ f(z)/(z − a) dz = 2πj f(a); ∮ f(z)/(z − a)<sup>n+1</sup> dz = 2πj f<sup>(n)</sup>(a)/n!.</li>
<li><b>Residue theorem</b>: ∮f dz = 2πj Σ(residues of poles inside C).</li>
<li>Residue at a simple pole a: lim<sub>z→a</sub> (z − a)f(z) = N(a)/D′(a). At a pole of order m: [1/(m−1)!] lim d<sup>m−1</sup>/dz<sup>m−1</sup> [(z − a)<sup>m</sup> f(z)].</li>
<li>Laurent series: the coefficient of 1/(z − a) is the residue.</li>
</ul>
<div class="trap">Trap: check whether each pole lies inside the contour (e.g. |z| = 2 includes z = 1.5 but not z = 3). Poles exactly on the contour make the integral undefined in the ordinary sense.</div>`},
{h:'Probability & statistics',b:`
<ul>
<li>P(A ∪ B) = P(A) + P(B) − P(A ∩ B). Independent: P(A ∩ B) = P(A)P(B). Conditional: P(A|B) = P(A ∩ B)/P(B).</li>
<li><b>Bayes</b>: P(A<sub>i</sub>|B) = P(B|A<sub>i</sub>)P(A<sub>i</sub>) / Σ P(B|A<sub>k</sub>)P(A<sub>k</sub>).</li>
<li>E[aX + b] = aE[X] + b; Var(aX + b) = a²Var(X); Var(X) = E[X²] − (E[X])².</li>
<li><b>Binomial</b>(n, p): P(k) = C(n,k)p<sup>k</sup>(1−p)<sup>n−k</sup>; mean np, variance np(1−p).</li>
<li><b>Poisson</b>(λ): P(k) = e<sup>−λ</sup>λ<sup>k</sup>/k!; mean = variance = λ.</li>
<li><b>Uniform</b>(a, b): mean (a+b)/2, variance (b−a)²/12.</li>
<li><b>Exponential</b>(λ): f = λe<sup>−λx</sup>; mean 1/λ, variance 1/λ²; memoryless.</li>
<li><b>Normal</b>(μ, σ²): ≈68% within μ ± σ, 95% within ±2σ, 99.7% within ±3σ. Standardise with z = (x − μ)/σ.</li>
<li>Correlation coefficient −1 ≤ r ≤ 1. Mean / median / mode: for a moderately skewed distribution, mode ≈ 3 median − 2 mean.</li>
</ul>`},
{h:'Numerical methods',b:`
<ul>
<li><b>Newton–Raphson</b>: x<sub>n+1</sub> = x<sub>n</sub> − f(x<sub>n</sub>)/f′(x<sub>n</sub>); quadratic convergence (order 2). Bisection: linear convergence; error halves each step.</li>
<li><b>Trapezoidal rule</b>: (h/2)[y₀ + 2(y₁ + … + y<sub>n−1</sub>) + y<sub>n</sub>]; error O(h²); exact for linear functions.</li>
<li><b>Simpson's 1/3</b>: (h/3)[y₀ + 4(odd) + 2(even) + y<sub>n</sub>], n even; error O(h⁴); exact up to cubic polynomials.</li>
<li><b>Euler</b>: y<sub>n+1</sub> = y<sub>n</sub> + h f(x<sub>n</sub>, y<sub>n</sub>). Modified Euler / Heun and RK4 are more accurate (RK4 error O(h⁴) global).</li>
</ul>`}]});
