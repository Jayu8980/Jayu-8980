"""Builds data/sample_questions.json: 30 original GATE EE style questions showing the bank format.

Run: python3 tools/make_sample_bank.py
Every NAT carries a `verification` expression that the backend's validation layer recomputes in code.
"""
import json, os

NEG = {("MCQ", 1): 0.33, ("MCQ", 2): 0.67}
Q = []

def q(subject, topic, subtopic, typ, marks, diff, cog, question, answer, solution, concept, trap,
      options=None, answer_range=None, distractor="", importance="Medium", verification=None, figure=None):
    sec = "GA" if subject == "GA" else "Technical"
    n = len(Q) + 1
    Q.append({
        "id": f"SAMPLE-{n:03d}",
        "qno": n,
        "section": sec,
        "subject_id": subject,
        "subject": SUBJ[subject],
        "topic_id": topic[0],
        "topic": topic[1],
        "subtopic": subtopic,
        "type": typ,
        "marks": marks,
        "negative_marks": NEG.get((typ, marks), 0),
        "cognitive_level": cog,
        "difficulty": diff,
        "question": question,
        "options": options or [],
        "answer": answer,
        "answer_range": answer_range,
        "solution": solution,
        "concept": concept,
        "common_trap": trap,
        "distractor_logic": distractor,
        "related_pyq_year": "",
        "predicted_importance": importance,
        "time_estimate_sec": 90 if marks == 1 else 180,
        "verification": verification,
        "figure": figure,
        "source": "sample",
    })

SUBJ = {"GA": "General Aptitude", "MATH": "Engineering Mathematics", "EC": "Electric Circuits",
        "EMF": "Electromagnetic Fields", "SS": "Signals and Systems", "EM": "Electrical Machines",
        "PS": "Power Systems", "CS": "Control Systems", "MEAS": "Electrical and Electronic Measurements",
        "AE": "Analog and Digital Electronics", "PE": "Power Electronics"}

# ---------------- General Aptitude ----------------
q("GA", ("GA.VERBAL", "Verbal Aptitude"), "Vocabulary", "MCQ", 1, "Easy", "Recall",
  "Choose the word that best completes the sentence: The committee's decision was ______; no amount of argument afterwards could change it.",
  "B", "The second clause says nothing could change the decision, so the decision was final and unalterable: 'irrevocable'.",
  "Contextual vocabulary", "Picking 'tentative' or 'provisional' by reading only the first clause.",
  options=["tentative", "irrevocable", "ambiguous", "provisional"],
  distractor="A and D mean the opposite (open to change); C describes clarity, not finality.")

q("GA", ("GA.QUANT", "Quantitative Aptitude"), "Time, speed and work", "NAT", 2, "Easy", "Application",
  "Pipe A fills a tank in 12 hours and pipe B fills it in 15 hours. Pipe C empties the full tank in 20 hours. If all three pipes are opened together on an empty tank, the time taken to fill the tank is ______ hours (round off to the nearest integer).",
  "10", "Net rate = 1/12 + 1/15 - 1/20 = (5 + 4 - 3)/60 = 6/60 = 1/10 tank per hour, so the tank fills in 10 hours.",
  "Combined work rates with an outlet", "Adding the outlet rate instead of subtracting it (gives 5 h).",
  answer_range={"min": 10, "max": 10}, verification={"expression": "1/(1/a + 1/b - 1/c)", "variables": {"a": 12, "b": 15, "c": 20}})

q("GA", ("GA.ANALYTICAL", "Analytical Aptitude"), "Seating and arrangement", "MCQ", 2, "Medium", "Analysis",
  "Five people P, Q, R, S and T sit in a row facing north. Q sits immediately to the right of P. S sits between T and P and is adjacent to both. R sits at one of the ends and T does not sit at an end. Who sits in the middle?",
  "C", "Q is immediately right of P and S is adjacent to both T and P, so S must be left of P: T S P Q. R is at an end. If R is at the right end, T is at the left end, which is not allowed. So the order is R T S P Q and the middle seat is S.",
  "Linear arrangement with adjacency constraints", "Ignoring the condition that T is not at an end, which also allows T S P Q R (middle P).",
  options=["P", "Q", "S", "T"], distractor="A is the answer if the last condition is skipped; B and D come from placing S to the right of P.")

# ---------------- Engineering Mathematics ----------------
q("MATH", ("MATH.LA", "Linear Algebra"), "Eigenvalues and eigenvectors", "MCQ", 1, "Easy", "Application",
  "Let A = [[2, 1], [1, 2]]. The eigenvalues of the matrix B = A^2 - 2A + I, where I is the 2x2 identity matrix, are",
  "B", "The eigenvalues of A are 1 and 3. B = (A - I)^2, so its eigenvalues are (1 - 1)^2 = 0 and (3 - 1)^2 = 4.",
  "Eigenvalues of a matrix polynomial", "Reporting the eigenvalues of A^2 (1 and 9) and forgetting the other terms.",
  options=["1 and 9", "0 and 4", "-1 and 1", "0 and 2"],
  distractor="A: eigenvalues of A^2 only; C: eigenvalues of A - 2I (the A^2 term dropped); D: forgetting to square (A - I).")

q("MATH", ("MATH.CALC", "Calculus"), "Definite and improper integrals", "NAT", 1, "Medium", "Application",
  "The value of the integral of sin^2(x) cos^2(x) dx from x = 0 to x = pi/2 is ______ (round off to 3 decimal places).",
  "0.196", "sin^2 x cos^2 x = sin^2(2x)/4 = (1 - cos 4x)/8. Integrating from 0 to pi/2 gives (1/8)(pi/2) - 0 = pi/16 = 0.196.",
  "Power-reduction identities", "Writing sin x cos x = sin 2x (dropping the 1/2), which gives 4 times the correct value (pi/4).",
  answer_range={"min": 0.195, "max": 0.197}, verification={"expression": "pi/16", "variables": {}})

q("MATH", ("MATH.PROB", "Probability and Statistics"), "Discrete and continuous distributions", "MSQ", 2, "Medium", "Analysis",
  "A random variable X follows a Poisson distribution with mean 2. Which of the following statements is/are TRUE?",
  "A,B,D", "P(X = k) = e^-2 2^k / k!. (A) P(X = 0) = e^-2: true. (B) E[X^2] = Var(X) + (E[X])^2 = 2 + 4 = 6: true. (C) Var(X) = mean = 2, not 4: false. (D) P(X = 1) = 2e^-2 and P(X = 2) = 4e^-2/2 = 2e^-2: true.",
  "Poisson mean, variance and pmf", "Taking the variance as the square of the mean.",
  options=["P(X = 0) = e^-2", "E[X^2] = 6", "Var(X) = 4", "P(X = 1) = P(X = 2)"], importance="High",
  distractor="C tempts students who treat Poisson variance like the square of the mean.")

q("MATH", ("MATH.DE", "Differential Equations"), "Higher-order linear ODEs with constant coefficients", "MCQ", 2, "Medium", "Application",
  "The solution of y'' + 4y' + 4y = 0 with y(0) = 1 and y'(0) = 0 is",
  "A", "The characteristic equation m^2 + 4m + 4 = 0 has a repeated root m = -2, so y = (c1 + c2 t)e^-2t. y(0) = c1 = 1. y'(0) = c2 - 2c1 = 0 gives c2 = 2. So y = (1 + 2t)e^-2t.",
  "Repeated roots and initial conditions", "Taking y'(0) = c2 and missing the -2c1 term, which gives c2 = 0 and y = e^(-2t).",
  options=["(1 + 2t)e^(-2t)", "(1 - 2t)e^(-2t)", "(1 + t)e^(-2t)", "e^(-2t)"],
  distractor="B: sign error in the derivative; C: halves c2; D: drops the -2c1 term so c2 = 0.")

# ---------------- Electric Circuits ----------------
q("EC", ("EC.THEOREMS", "Network Theorems"), "Maximum power transfer", "NAT", 2, "Medium", "Analysis",
  "A 12 V ideal DC source in series with a 4 ohm resistor feeds node a. A 6 ohm resistor connects node a to the reference node. A 1.6 ohm resistor connects node a to terminal x, and a variable load resistor RL is connected between terminal x and the reference node. The maximum power that can be delivered to RL is ______ W (round off to 2 decimal places).",
  "3.24", "Thevenin voltage at x: Vth = 12 x 6/(4 + 6) = 7.2 V (no current in the 1.6 ohm resistor on open circuit). Rth = (4 || 6) + 1.6 = 2.4 + 1.6 = 4 ohm. Pmax = Vth^2/(4 Rth) = 51.84/16 = 3.24 W.",
  "Thevenin equivalent with a series element, then maximum power transfer", "Forgetting the 1.6 ohm series resistor in Rth (gives 5.40 W).",
  answer_range={"min": 3.22, "max": 3.26}, importance="High",
  verification={"expression": "(V*R2/(R1+R2))^2/(4*(R1*R2/(R1+R2)+Rs))", "variables": {"V": 12, "R1": 4, "R2": 6, "Rs": 1.6}})

q("EC", ("EC.TRANSIENT", "Transient Response"), "DC transients", "MCQ", 1, "Easy", "Application",
  "A 10 microfarad capacitor, initially charged to 2 V, is connected at t = 0 to a 10 V DC source through a 10 kilo-ohm resistor. The capacitor voltage at t = 0.1 s is approximately",
  "B", "tau = RC = 10e3 x 10e-6 = 0.1 s. v(t) = 10 - (10 - 2)e^(-t/tau). At t = tau, v = 10 - 8e^-1 = 10 - 2.94 = 7.06 V.",
  "First-order response with a non-zero initial condition", "Ignoring the initial 2 V and using 10(1 - e^-1) = 6.32 V.",
  options=["6.32 V", "7.06 V", "8.00 V", "2.94 V"],
  distractor="A: zero initial condition; C: initial plus 6 V without the exponential; D: the exponential term alone.")

q("EC", ("EC.THREEPHASE", "Three-Phase Circuits"), "Power measurement (two-wattmeter)", "MCQ", 1, "Easy", "Application",
  "In the two-wattmeter method of measuring power in a balanced three-phase load, the wattmeters read 2000 W and 1000 W. The power factor of the load is",
  "A", "tan(phi) = sqrt(3)(W1 - W2)/(W1 + W2) = sqrt(3) x 1000/3000 = 0.577, so phi = 30 degrees and pf = cos 30 = 0.866.",
  "Two-wattmeter method", "Dropping the sqrt(3) factor, giving tan(phi) = 1/3 and pf = 0.949.",
  options=["0.866", "0.500", "0.707", "0.949"],
  distractor="B: pf when one reading is zero; C: random 45 degrees; D: missed sqrt(3).")

# ---------------- Electromagnetic Fields ----------------
q("EMF", ("EMF.DIELECTRIC", "Dielectrics"), "Effect of dielectric medium", "MCQ", 1, "Medium", "Application",
  "An air-filled parallel-plate capacitor has capacitance C0. A dielectric slab of relative permittivity 4, with the same area as the plates and thickness equal to half the plate separation, is inserted parallel to the plates. The new capacitance is",
  "B", "The slab and the remaining air gap act as capacitors in series: 2C0 (air, d/2) and 8C0 (dielectric, d/2). C = (2C0 x 8C0)/(10C0) = 1.6 C0.",
  "Series layers of dielectric", "Treating the layers as parallel and averaging permittivities: (1 + 4)/2 = 2.5 C0.",
  options=["2.5 C0", "1.6 C0", "4 C0", "2 C0"],
  distractor="A: parallel combination; C: whole gap filled; D: only the air half doubled.")

q("EMF", ("EMF.MAGNETOSTATICS", "Magnetostatics"), "Magnetomotive force, reluctance, magnetic circuits", "NAT", 2, "Medium", "Application",
  "A toroidal core has a mean iron path length of 0.5 m, a cross-sectional area of 4 cm^2 and relative permeability 1000. It has an air gap of 1 mm and carries a 500-turn coil. Neglecting fringing and leakage, the coil current needed to produce a flux of 0.4 mWb in the gap is ______ A (round off to 2 decimal places).",
  "2.39", "B = 0.4e-3/4e-4 = 1 T. Iron mmf = B l/(mu0 mur) = 1 x 0.5/(4 pi e-7 x 1000) = 397.9 AT. Gap mmf = B lg/mu0 = 1 x 1e-3/(4 pi e-7) = 795.8 AT. Total = 1193.7 AT, I = 1193.7/500 = 2.39 A.",
  "Series magnetic circuit with an air gap", "Ignoring the air gap reluctance (gives 0.80 A) even though the gap dominates.",
  answer_range={"min": 2.37, "max": 2.41},
  verification={"expression": "(B/(mu0*mur)*lc + B/mu0*lg)/N", "variables": {"B": 1, "mu0": 1.2566370614359173e-6, "mur": 1000, "lc": 0.5, "lg": 0.001, "N": 500}})

# ---------------- Signals and Systems ----------------
q("SS", ("SS.SAMPLING", "Sampling"), "Aliasing", "MCQ", 1, "Medium", "Application",
  "The signal x(t) = cos(200 pi t) + cos(500 pi t) is sampled at 400 samples/s and passed through an ideal low-pass filter with cut-off 200 Hz. The output contains frequency components at",
  "B", "The components are 100 Hz and 250 Hz. With fs = 400 Hz, 250 Hz is above fs/2 = 200 Hz and aliases to 400 - 250 = 150 Hz. The filter passes 100 Hz and 150 Hz.",
  "Sampling theorem and aliasing", "Assuming both components are reproduced because fs exceeds the highest frequency (but not twice it).",
  options=["100 Hz and 250 Hz", "100 Hz and 150 Hz", "100 Hz only", "50 Hz and 150 Hz"],
  distractor="A: no aliasing considered; C: aliased component assumed removed; D: radian-to-Hz confusion (dividing by 4 pi).", importance="High")

q("SS", ("SS.BASICS", "Signal and System Properties"), "LTI systems and convolution", "NAT", 2, "Medium", "Analysis",
  "An LTI system has impulse response h(t) = e^(-t) u(t). The input is x(t) = u(t) - u(t - 2). The output y(t) at t = 3 s is ______ (round off to 3 decimal places).",
  "0.318", "y(3) = integral of x(tau) h(3 - tau) dtau from 0 to 2 = integral of e^-(3 - tau) dtau from 0 to 2 = e^-3 (e^2 - 1) = e^-1 - e^-3 = 0.368 - 0.050 = 0.318.",
  "Convolution with a finite pulse", "Integrating up to t = 3 instead of the pulse end at 2 (gives 0.950).",
  answer_range={"min": 0.317, "max": 0.319}, verification={"expression": "exp(-1) - exp(-3)", "variables": {}})

# ---------------- Electrical Machines ----------------
q("EM", ("EM.SPTX", "Single-Phase Transformer"), "Regulation and efficiency", "NAT", 2, "Medium", "Analysis",
  "A 20 kVA, 2000/200 V single-phase transformer has a core loss of 200 W and a full-load copper loss of 400 W. Its efficiency at the load at which efficiency is maximum, at 0.8 power factor lagging, is ______ % (round off to 2 decimal places).",
  "96.59", "Maximum efficiency occurs when copper loss = core loss, at load fraction x = sqrt(200/400) = 0.7071. Output = 0.7071 x 20000 x 0.8 = 11313.7 W. Losses = 200 + 200 = 400 W. Efficiency = 11313.7/11713.7 = 96.59%.",
  "Condition for maximum efficiency", "Computing efficiency at full load (96.39%) instead of at the maximum-efficiency load.",
  answer_range={"min": 96.55, "max": 96.62}, importance="High",
  verification={"expression": "100*sqrt(Pi/Pcu)*S*pf/(sqrt(Pi/Pcu)*S*pf + 2*Pi)", "variables": {"Pi": 200, "Pcu": 400, "S": 20000, "pf": 0.8}})

q("EM", ("EM.IM", "Three-Phase Induction Machines"), "Torque-slip characteristics", "MSQ", 2, "Medium", "Analysis",
  "A 4-pole, 50 Hz, three-phase slip-ring induction motor has rotor resistance 0.2 ohm per phase and standstill rotor reactance 1.0 ohm per phase (both referred to the stator). Neglect stator impedance. Which of the following statements is/are TRUE?",
  "A,B,D", "Slip at maximum torque sm = R2/X2 = 0.2 (A true). Speed = 1500(1 - 0.2) = 1200 rpm (B true). For maximum torque at start, R2 + Rext = X2, so Rext = 0.8 ohm, not 1.0 ohm (C false). Tmax is proportional to V^2/(2X2) and independent of R2 (D true).",
  "Maximum torque condition and effect of rotor resistance", "Setting Rext = X2 instead of R2 + Rext = X2.",
  options=["Slip at maximum torque is 0.2", "Speed at maximum torque is 1200 rpm", "An external resistance of 1.0 ohm per phase in the rotor gives maximum torque at starting", "The value of maximum torque does not depend on rotor resistance"],
  importance="High", distractor="C is the classic Rext = X2 error.")

q("EM", ("EM.DC", "DC Machines"), "Starting and speed control", "NAT", 2, "Medium", "Application",
  "A 220 V DC shunt motor has armature resistance 0.5 ohm and draws an armature current of 40 A at 1000 rpm. With field current and load torque unchanged, the external resistance to be inserted in series with the armature to reduce the speed to 800 rpm is ______ ohm (round off to 2 decimal places).",
  "1.00", "Constant torque and flux mean Ia stays at 40 A. Eb1 = 220 - 40 x 0.5 = 200 V. Eb2 = 200 x 800/1000 = 160 V. 220 - 40(0.5 + R) = 160 gives R = 1.0 ohm.",
  "Armature resistance speed control at constant torque", "Scaling the supply voltage by the speed ratio instead of the back emf.",
  answer_range={"min": 0.99, "max": 1.01},
  verification={"expression": "(V - (V - Ia*Ra)*N2/N1)/Ia - Ra", "variables": {"V": 220, "Ia": 40, "Ra": 0.5, "N1": 1000, "N2": 800}})

q("EM", ("EM.SYNC", "Synchronous Machines"), "V-curves and power factor control", "MCQ", 1, "Easy", "Recall",
  "A synchronous motor drives a constant load at unity power factor. If its field current is increased beyond this value, the armature current",
  "B", "Beyond normal excitation the motor is over-excited: it supplies reactive power to the system, the power factor becomes leading and the armature current rises along the right arm of the V-curve.",
  "V-curves of a synchronous motor", "Mixing up generator and motor conventions and choosing lagging.",
  options=["decreases and the power factor becomes lagging", "increases and the power factor becomes leading", "increases and the power factor becomes lagging", "decreases and the power factor becomes leading"],
  distractor="C applies to under-excitation; A and D wrongly assume unity pf is not the minimum-current point.")

# ---------------- Power Systems ----------------
q("PS", ("PS.DISTRIBUTION", "Distribution and Compensation"), "Per-unit quantities", "MCQ", 1, "Easy", "Application",
  "A generator reactance is 0.2 pu on a 50 MVA, 11 kV base. On a 100 MVA, 22 kV base, its reactance is",
  "A", "Z_new = Z_old (S_new/S_old)(V_old/V_new)^2 = 0.2 x 2 x (11/22)^2 = 0.2 x 2 x 0.25 = 0.1 pu.",
  "Change of per-unit base", "Inverting the voltage ratio, giving 0.2 x 2 x 4 = 1.6 pu, or the MVA ratio, giving 0.025 pu.",
  options=["0.1 pu", "0.4 pu", "0.8 pu", "0.05 pu"],
  distractor="B: MVA ratio only; C: voltage ratio not squared and inverted; D: MVA ratio inverted.")

q("PS", ("PS.FAULT", "Fault Analysis"), "Unsymmetrical fault analysis (LG, LL, LLG)", "MCQ", 2, "Medium", "Application",
  "An unloaded generator with solidly grounded neutral has E = 1.0 pu, X1 = 0.2 pu, X2 = 0.15 pu and X0 = 0.05 pu. The fault current for a bolted single line-to-ground fault at its terminals is",
  "A", "For an LG fault the three sequence networks are in series: Ia1 = E/(X1 + X2 + X0) = 1/0.4 = 2.5 pu. Fault current = 3 Ia1 = 7.5 pu.",
  "Sequence network connection for an LG fault", "Reporting the sequence current Ia1 (2.5 pu) as the fault current.",
  options=["7.5 pu", "2.5 pu", "5.0 pu", "8.57 pu"], importance="High",
  distractor="B: forgets the factor 3; C: three-phase fault current E/X1; D: drops X0 from the series sum.")

q("PS", ("PS.STABILITY", "Stability"), "Equal area criterion", "NAT", 2, "Hard", "Analysis",
  "A generator delivers 1.0 pu to an infinite bus with Pmax = 2.0 pu, so the initial load angle is 30 degrees. A three-phase fault at the generator terminals reduces the electrical output to zero and is then cleared, restoring the original network. Using the equal area criterion, the critical clearing angle is ______ degrees (round off to 2 decimal places).",
  "79.56", "delta0 = 30 deg = 0.5236 rad, delta_max = 180 - 30 = 150 deg = 2.618 rad. cos(delta_c) = (Pm/Pmax)(delta_max - delta0) + cos(delta_max) = 0.5 x 2.0944 - 0.8660 = 0.1812, so delta_c = 79.56 degrees.",
  "Equal area criterion with zero power during the fault", "Using degrees instead of radians for (delta_max - delta0) inside the formula.",
  answer_range={"min": 79.3, "max": 79.8}, importance="High",
  verification={"expression": "deg(acos(m*(pi - 2*d0) + cos(pi - d0)))", "variables": {"m": 0.5, "d0": 0.5235987755982988}})

# ---------------- Control Systems ----------------
q("CS", ("CS.STABILITY", "Stability Analysis"), "Routh-Hurwitz criterion", "MCQ", 1, "Easy", "Application",
  "The characteristic equation of a closed-loop system is s^3 + 2s^2 + Ks + 8 = 0. The system is stable for",
  "A", "Routh array: s^3: 1, K; s^2: 2, 8; s^1: (2K - 8)/2; s^0: 8. Stability needs 2K - 8 > 0, i.e. K > 4.",
  "Routh-Hurwitz criterion", "Using the product condition a1 a2 > a0 a3 incorrectly as K > 8.",
  options=["K > 4", "K > 8", "0 < K < 4", "K > 2"])

q("CS", ("CS.TIME", "Time Response"), "Second-order system specifications", "NAT", 2, "Medium", "Analysis",
  "A unity-feedback system has open-loop transfer function G(s) = K/(s(s + 4)). K is chosen so that the closed-loop damping ratio is 0.5. The resulting peak overshoot of the unit-step response is ______ % (round off to 2 decimal places).",
  "16.30", "Closed loop: s^2 + 4s + K. 2 zeta wn = 4 with zeta = 0.5 gives wn = 4 and K = 16. Mp = exp(-pi zeta/sqrt(1 - zeta^2)) = exp(-1.8138) = 0.1630, i.e. 16.30%.",
  "Damping ratio and peak overshoot", "Using exp(-pi zeta) without the sqrt(1 - zeta^2) term (gives 20.79%).",
  answer_range={"min": 16.2, "max": 16.4}, verification={"expression": "100*exp(-pi*z/sqrt(1 - z^2))", "variables": {"z": 0.5}})

q("CS", ("CS.FREQ", "Frequency Response"), "Bode plots", "MSQ", 2, "Medium", "Analysis",
  "A unity-feedback system has open-loop transfer function G(s) = 10/(s(s + 1)(s + 10)). Which of the following statements is/are TRUE?",
  "A,B,C", "Kv = lim s G(s) as s -> 0 = 10/10 = 1 (A true). It is a type-1 system, so the steady-state error to a unit step is zero (B true). The integrator gives an initial Bode slope of -20 dB/decade (C true). With three poles, the phase tends to -270 degrees, not -180 degrees (D false).",
  "System type, error constants and Bode asymptotes", "Counting only the finite poles when finding the high-frequency phase.",
  options=["The velocity error constant Kv is 1", "The steady-state error for a unit-step input is zero", "The initial slope of the Bode magnitude plot is -20 dB/decade", "The phase angle tends to -180 degrees as frequency tends to infinity"])

# ---------------- Measurements ----------------
q("MEAS", ("MEAS.INSTR", "Measuring Instruments"), "PMMC, moving iron, dynamometer instruments", "MCQ", 1, "Easy", "Application",
  "A PMMC instrument with internal resistance 5 ohm gives full-scale deflection at 10 mA. The shunt resistance needed to use it as a 0-1 A ammeter is approximately",
  "A", "Shunt current = 1 - 0.01 = 0.99 A. Rsh = Im Rm/(I - Im) = 0.01 x 5/0.99 = 0.0505 ohm.",
  "Ammeter range extension", "Using Rm/(I/Im) = 0.05 ohm, which forgets that the meter itself carries 10 mA.",
  options=["0.0505 ohm", "0.05 ohm", "495 ohm", "0.505 ohm"],
  distractor="B: multiplying factor not reduced by one; C: series multiplier formula; D: decimal slip.")

q("MEAS", ("MEAS.ERROR", "Error Analysis"), "Limiting and probable errors", "NAT", 1, "Easy", "Application",
  "Power is computed as P = I^2 R, with I = 2 A +/- 1% and R = 100 ohm +/- 0.5%. The limiting error in P is +/- ______ %.",
  "2.5", "For a product of powers, relative limiting errors add with the exponents as weights: 2(1%) + 0.5% = 2.5%.",
  "Propagation of limiting errors", "Adding 1% + 0.5% = 1.5% and ignoring the square on I.",
  answer_range={"min": 2.5, "max": 2.5}, verification={"expression": "2*eI + eR", "variables": {"eI": 1, "eR": 0.5}})

# ---------------- Analog and Digital Electronics ----------------
q("AE", ("AE.OPAMP", "Operational Amplifiers"), "Comparators and Schmitt trigger", "MSQ", 1, "Medium", "Application",
  "An inverting Schmitt trigger uses an op-amp with output saturation of +/-12 V. The output feeds back to the non-inverting input through R2 = 50 kilo-ohm, and R1 = 10 kilo-ohm connects the non-inverting input to ground. Which of the following statements is/are TRUE?",
  "A,B,C", "Feedback fraction = R1/(R1 + R2) = 10/60. UTP = +12/6 = +2 V and LTP = -2 V (A true), so the hysteresis width is 4 V (B true). In the inverting configuration, the output switches to -12 V when the input rises above UTP (C true). The thresholds depend only on the resistors and the saturation levels, not on input frequency (D false).",
  "Schmitt trigger thresholds", "Using R2/(R1 + R2) as the feedback fraction, giving thresholds of +/-10 V.",
  options=["The upper threshold voltage is +2 V", "The hysteresis width is 4 V", "The output switches to -12 V when the input rises above the upper threshold", "The threshold voltages depend on the input signal frequency"])

q("AE", ("AE.DIGITAL", "Digital Electronics"), "Minimization of Boolean functions", "MCQ", 1, "Easy", "Application",
  "The minimal sum-of-products form of F(A, B, C) = sum of minterms m(0, 2, 4, 5, 6) is",
  "A", "Minterms 0, 2, 4 and 6 all have C = 0, giving C'. Minterm 5 (101) pairs with 4 (100) to give AB'. F = C' + AB'.",
  "Karnaugh map grouping", "Grouping 5 with 7 (not a minterm) to get AC.",
  options=["C' + AB'", "C' + AB", "A'C' + AB'", "C + AB'"])

# ---------------- Power Electronics ----------------
q("PE", ("PE.DCDC", "DC-DC Converters"), "Buck converter", "NAT", 2, "Medium", "Analysis",
  "An ideal buck converter operates from 48 V at a switching frequency of 50 kHz with duty ratio 0.5. It has L = 100 microhenry and supplies a 6 ohm resistive load. The peak inductor current is ______ A (round off to 1 decimal place).",
  "5.2", "Vo = D Vin = 24 V, Io = 24/6 = 4 A. Ripple = (Vin - Vo) D/(L f) = 24 x 0.5/(100e-6 x 50e3) = 2.4 A. The minimum current 4 - 1.2 = 2.8 A > 0, so the converter is in CCM. Peak = 4 + 1.2 = 5.2 A.",
  "Inductor ripple and CCM check in a buck converter", "Adding the full ripple instead of half (gives 6.4 A).",
  answer_range={"min": 5.15, "max": 5.25}, importance="High",
  verification={"expression": "D*Vin/R + (Vin - D*Vin)*D/(2*L*f)", "variables": {"D": 0.5, "Vin": 48, "R": 6, "L": 1e-4, "f": 50000}})

q("PE", ("PE.RECT", "Rectifiers"), "Line-commutated thyristor rectifiers", "MCQ", 2, "Medium", "Application",
  "A single-phase fully controlled bridge converter is fed from 230 V (rms), 50 Hz and supplies a highly inductive load so the current is continuous. At a firing angle of 60 degrees, the average output voltage is approximately",
  "A", "Vo = (2 Vm/pi) cos(alpha) = (2 x 325.27/pi) x 0.5 = 103.5 V.",
  "Average output of a full converter in continuous conduction", "Using the semi-converter expression (Vm/pi)(1 + cos alpha), which gives 155.3 V.",
  options=["103.5 V", "155.3 V", "207.1 V", "51.8 V"], importance="High",
  distractor="B: semi-converter formula; C: alpha = 0; D: half-wave converter.")

# ---------------- Questions with figures (render + description are added by tools/build_figures.mjs) ----------------
q("MEAS", ("MEAS.INSTR", "Measuring Instruments"), "Measurement of voltage, current, power, energy and power factor", "NAT", 2, "Medium", "Application",
  "The periodic voltage waveform shown in the figure is applied across a 5 ohm resistor. The average power dissipated in the resistor is ______ W (round off to 2 decimal places).",
  "8.00", "Over one 10 ms period the voltage is 10 V for 4 ms and 0 V for 6 ms. Vrms^2 = (10^2 x 4 + 0 x 6)/10 = 40 V^2. Average power = Vrms^2/R = 40/5 = 8 W.",
  "RMS value of a pulse waveform and average power", "Using the average voltage (4 V) instead of the RMS value, which gives 3.2 W.",
  answer_range={"min": 7.95, "max": 8.05}, verification={"expression": "10^2*(4/10)/5", "variables": {}},
  figure={"type": "plot", "caption": "Periodic voltage applied to the resistor",
          "x": {"label": "t", "unit": "ms", "min": 0, "max": 20, "ticks": [0, 4, 10, 14, 20]},
          "y": {"label": "v(t)", "unit": "V", "min": 0, "max": 12, "ticks": [0, 5, 10]},
          "series": [{"label": "v(t)", "points": [[0, 0], [0, 10], [4, 10], [4, 0], [10, 0], [10, 10], [14, 10], [14, 0], [20, 0]]}]})

q("EC", ("EC.SINUSOIDAL", "Sinusoidal Steady State"), "Complex power and power factor", "MCQ", 1, "Easy", "Application",
  "The phasor diagram shows the RMS voltage V across a load and the RMS current I drawn by it. The active power and the reactive power of the load are",
  "A", "S = V I* = 230 x 10 at +36.87 deg = 2300(0.8 + j0.6). P = 1840 W and Q = +1380 var. I lags V, so the load is inductive and absorbs reactive power.",
  "Complex power from a phasor diagram", "Taking a lagging current as a load that delivers reactive power.",
  options=["1840 W, 1380 var absorbed", "1840 W, 1380 var delivered", "1380 W, 1840 var absorbed", "2300 W, 0 var"],
  distractor="B: sign convention reversed; C: sin and cos swapped; D: apparent power taken as active power.",
  figure={"type": "phasor", "caption": "V = 230 V (reference), I = 10 A (I drawn to a larger scale)", "reference_label": "reference",
          "phasors": [{"label": "V", "text": "V = 230 V", "magnitude": 230, "angle_deg": 0, "unit": "V"},
                      {"label": "I", "text": "I = 10 A", "magnitude": 10, "angle_deg": -36.87, "unit": "A", "scale": 16}],
          "angles": [{"from": "I", "to": "V", "label": "36.87°"}]})

q("EC", ("EC.ANALYSIS", "Network Solution Methods"), "Node and mesh analysis", "NAT", 1, "Easy", "Application",
  "In the circuit shown, the voltage of node a with respect to the ground is ______ V (round off to 2 decimal places).",
  "6.00", "The 6 ohm and 12 ohm resistors are both between node a and ground: 6 || 12 = 4 ohm. Voltage division: Va = 12 x 4/(4 + 4) = 6 V.",
  "Parallel combination and voltage division", "Ignoring the 12 ohm branch and computing 12 x 6/10 = 7.2 V.",
  answer_range={"min": 5.99, "max": 6.01}, verification={"expression": "12*(R2*R3/(R2+R3))/(R1 + R2*R3/(R2+R3))", "variables": {"R1": 4, "R2": 6, "R3": 12}},
  figure={"type": "circuit",
          "elements": [{"kind": "V", "from": [0, 2], "to": [0, 0], "label": "12 V"},
                       {"kind": "R", "from": [0, 0], "to": [2, 0], "label": "4 Ω"},
                       {"kind": "R", "from": [2, 0], "to": [2, 2], "label": "6 Ω"},
                       {"kind": "wire", "from": [2, 0], "to": [4, 0]},
                       {"kind": "R", "from": [4, 0], "to": [4, 2], "label": "12 Ω"},
                       {"kind": "wire", "from": [0, 2], "to": [4, 2]},
                       {"kind": "ground", "at": [0, 2]}],
          "nodes": [{"at": [2, 0], "label": "a"}]})

q("CS", ("CS.MODEL", "Mathematical Modelling"), "Block diagrams and signal flow graphs", "MCQ", 2, "Medium", "Analysis",
  "For the block diagram shown, the closed-loop transfer function C(s)/R(s) is",
  "A", "Inner loop: G2 with negative feedback H1 gives G2/(1 + G2 H1). In series with G1 the forward path is G1 G2/(1 + G2 H1). The outer unity negative feedback gives C/R = G1 G2/(1 + G2 H1 + G1 G2).",
  "Block diagram reduction with nested loops", "Treating H1 as if it fed back around G1 as well, giving 1 + G1 G2 H1 in the denominator.",
  options=["G1 G2 / (1 + G2 H1 + G1 G2)", "G1 G2 / (1 + G1 G2 H1)", "G1 G2 / (1 + G1 H1 + G1 G2)", "G1 G2 / (1 - G2 H1 + G1 G2)"],
  distractor="B: inner loop placed around both blocks; C: H1 attached to G1; D: sign of the inner loop reversed.", importance="High",
  figure={"type": "block_diagram",
          "blocks": [{"id": "S1", "shape": "sum", "at": [0, 0]}, {"id": "G1", "label": "G1(s)", "at": [1.6, 0]},
                     {"id": "S2", "shape": "sum", "at": [3.2, 0]}, {"id": "G2", "label": "G2(s)", "at": [4.8, 0]},
                     {"id": "P", "shape": "point", "at": [6.4, 0]}, {"id": "H1", "label": "H1(s)", "at": [4.8, 1.2]}],
          "links": [{"from": "S1", "to": "G1"}, {"from": "G1", "to": "S2", "sign": "+"}, {"from": "S2", "to": "G2"},
                    {"from": "G2", "to": "P"}, {"from": "P", "to": "H1", "via": [[6.4, 1.2]]},
                    {"from": "H1", "to": "S2", "via": [[3.2, 1.2]], "sign": "-"},
                    {"from": "P", "to": "S1", "via": [[6.4, 2.2], [0, 2.2]], "sign": "-"}],
          "inputs": [{"to": "S1", "label": "R(s)", "sign": "+"}], "outputs": [{"from": "P", "label": "C(s)"}]})

q("CS", ("CS.FREQ", "Frequency Response"), "Gain and phase margins", "NAT", 2, "Hard", "Analysis",
  "The asymptotic Bode magnitude plot of the minimum-phase open-loop transfer function G(s) of a unity-feedback system is shown. The phase margin of the system is ______ degrees (round off to 2 decimal places).",
  "51.83", "From the plot, the -20 dB/decade line passes 20 dB at w = 1, so K = 10, and the slope changes to -40 dB/decade at the corner w = 10: G(s) = 10/(s(1 + s/10)) = 100/(s(s + 10)). Exact gain crossover: w^2(w^2 + 100) = 10^4, so w^2 = 61.80 and w = 7.86 rad/s. PM = 180 - 90 - atan(7.86/10) = 90 - 38.17 = 51.83 degrees.",
  "Transfer function from an asymptotic Bode plot, then the exact phase margin", "Using the asymptotic crossover w = 10 rad/s, which gives 45 degrees.",
  answer_range={"min": 51.5, "max": 52.2}, importance="High",
  verification={"expression": "90 - deg(atan(sqrt((-100 + sqrt(100^2 + 4*10^4))/2)/10))", "variables": {}},
  figure={"type": "plot", "caption": "Asymptotic magnitude plot of G(jω)",
          "x": {"label": "ω", "unit": "rad/s", "scale": "log", "min": 0.1, "max": 1000},
          "y": {"label": "|G(jω)|", "unit": "dB", "min": -80, "max": 40, "ticks": [-80, -60, -40, -20, 0, 20, 40]},
          "series": [{"label": "asymptote", "points": [[0.1, 40], [10, 0], [1000, -80]]}],
          "annotations": [{"x": 1, "y": 20, "text": "−20 dB/dec"}, {"x": 100, "y": -40, "text": "−40 dB/dec"}],
          "vlines": [{"x": 10, "label": "ω = 10"}]})

q("PE", ("PE.RECT", "Rectifiers"), "Line-commutated thyristor rectifiers", "NAT", 2, "Medium", "Application",
  "A single-phase half-wave thyristor rectifier supplies a resistive load from a 230 V (rms), 50 Hz source. The supply voltage vs (dashed) and the load voltage vo are shown. The average load voltage is ______ V (round off to 2 decimal places).",
  "77.65", "The figure shows conduction from wt = 60 deg to 180 deg, so alpha = 60 deg. Vo = (Vm/(2 pi))(1 + cos alpha) = (325.27/(2 pi)) x 1.5 = 77.65 V.",
  "Average output of a half-wave controlled rectifier read from its waveform", "Using the full-wave expression (Vm/pi)(1 + cos alpha), which doubles the answer to 155.3 V.",
  answer_range={"min": 77.4, "max": 77.9}, verification={"expression": "230*sqrt(2)/(2*pi)*(1 + cosd(60))", "variables": {}},
  figure={"type": "plot",
          "x": {"label": "ωt", "unit": "deg", "min": 0, "max": 720, "ticks": [0, 60, 180, 360, 420, 540, 720]},
          "y": {"label": "v", "unit": "V", "min": -350, "max": 350, "ticks": [-325, 0, 325]},
          "series": [{"label": "vs", "expression": "325.27*sind(x)", "domain": [0, 720], "style": "dashed", "color_index": 0},
                     {"label": "vo", "expression": "325.27*sind(x)", "domains": [[60, 180], [420, 540]], "color_index": 1},
                     {"points": [[0, 0], [60, 0]], "color_index": 1}, {"points": [[180, 0], [420, 0]], "color_index": 1},
                     {"points": [[540, 0], [720, 0]], "color_index": 1}]})

q("GA", ("GA.QUANT", "Quantitative Aptitude"), "Data interpretation", "NAT", 2, "Easy", "Application",
  "The bar chart shows the annual production of a factory. The average annual production over the five years exceeds the 2019 production by ______ % (round off to 1 decimal place).",
  "32.5", "Average = (120 + 150 + 135 + 180 + 210)/5 = 795/5 = 159 thousand units. Excess over 2019 = (159 - 120)/120 x 100 = 32.5%.",
  "Averages and percentage change from a chart", "Dividing by the average instead of the 2019 value, giving 24.5%.",
  answer_range={"min": 32.45, "max": 32.55}, verification={"expression": "((120+150+135+180+210)/5 - 120)/120*100", "variables": {}},
  figure={"type": "bar_chart", "categories": ["2019", "2020", "2021", "2022", "2023"],
          "series": [{"label": "Production", "values": [120, 150, 135, 180, 210]}],
          "y": {"label": "Production", "unit": "thousand units"}, "x": {"label": "Year"}})

q("SS", ("SS.LAPLACE", "Laplace Transform"), "Laplace transform and ROC", "MSQ", 2, "Medium", "Analysis",
  "The pole-zero plot of a causal LTI system H(s) is shown. Which of the following statements is/are TRUE?",
  "A,B,C", "The poles are at -1 +/- j2 (left half plane), so the causal system is BIBO stable (A true). Complex poles with real part -1 and imaginary part 2 give an impulse response like e^(-t) cos(2t + phi): a decaying oscillation at 2 rad/s (B true). The zero at s = 0 makes H(0) = 0 (C true). A zero at the origin and a complex pole pair give a band-pass response, not low-pass (D false).",
  "Reading stability, damped frequency and filter type from a pole-zero plot", "Calling it low-pass because both poles are stable, ignoring the zero at the origin.",
  options=["The system is BIBO stable", "The impulse response is a decaying oscillation with damped frequency 2 rad/s", "The DC gain of the system is zero", "The system behaves as a low-pass filter"],
  figure={"type": "pole_zero", "poles": [[-1, 2], [-1, -2]], "zeros": [[0, 0]], "label_points": True})

out = os.path.join(os.path.dirname(__file__), "..", "data", "sample_questions.json")
with open(out, "w") as f:
    json.dump({"version": 1, "description": "Sample GATE EE question bank (38 original questions, 8 with figures) showing the bank format", "questions": Q}, f, indent=2)
print(len(Q), "questions ->", out)
from collections import Counter
print(Counter(x["type"] for x in Q), Counter(x["subject_id"] for x in Q))
