/* Study Book agent: writes a GATE EE study book chapter by chapter (official syllabus),
   checks worked-example answers with an independent solver, stores chapters offline (IndexedDB),
   shows a reader, prints to PDF, and lists free legal courses/books per subject. */
(function(){
const A=window.__ai, $=id=>document.getElementById(id), esc=A.esc, rich=A.rich;

/* ---------- syllabus (GATE EE) ---------- */
const BOOK=[
 {k:'GA',n:'General Aptitude',ch:['Verbal Ability: grammar, vocabulary, sentence completion, reading comprehension','Quantitative Aptitude: numbers, ratios, percentages, averages, time-work-distance, data interpretation','Analytical Aptitude: logic, deduction, induction, analogy, numerical relations','Spatial Aptitude: transformation of shapes, rotation, mirror images, paper folding, 2D/3D patterns']},
 {k:'EM',n:'Engineering Mathematics',ch:['Linear Algebra: matrices, rank, systems of equations, eigenvalues & eigenvectors','Calculus: limits, mean value theorems, integrals, maxima & minima, partial derivatives','Vector Calculus: gradient, divergence, curl, line/surface/volume integrals, Gauss, Stokes, Green theorems','Differential Equations: first-order, higher-order linear ODEs, Cauchy-Euler, variation of parameters, PDEs (separation of variables)','Complex Variables: analytic functions, Cauchy integral theorem & formula, Taylor & Laurent series, residue theorem','Probability & Statistics: distributions (binomial, Poisson, normal, exponential, uniform), mean, variance, correlation & regression']},
 {k:'EC',n:'Electric Circuits',ch:['Network elements, ideal & dependent sources, KCL/KVL, nodal & mesh analysis','Network theorems: Thevenin, Norton, superposition, maximum power transfer, reciprocity','Transient response of RL, RC and RLC circuits (dc and ac excitation)','Sinusoidal steady state: phasors, impedance, complex power, power factor','Resonance: series and parallel, quality factor, bandwidth','Two-port networks: Z, Y, h, ABCD parameters and interconnections','Three-phase circuits: star/delta, balanced & unbalanced loads, power measurement (two-wattmeter)']},
 {k:'EMF',n:'Electromagnetic Fields',ch:['Electrostatics: Coulomb\'s law, electric field, flux density, Gauss\'s law, divergence','Electric potential, fields of point/line/plane/spherical charges, dielectrics and capacitance','Magnetostatics: Biot-Savart law, Ampere\'s law, curl, magnetic fields of standard configurations','Faraday\'s law, Lorentz force, self & mutual inductance, magnetic circuits (mmf, reluctance)']},
 {k:'SS',n:'Signals and Systems',ch:['Signals: continuous & discrete, classification, operations, impulse and step, RMS and average values','LTI systems: properties (linearity, causality, stability, time invariance), convolution','Fourier series of periodic signals and its properties','Fourier transform, DTFT and their properties','Sampling theorem, aliasing and reconstruction','Laplace transform: ROC, properties, inverse, system analysis','Z-transform: ROC, properties, inverse, discrete system analysis']},
 {k:'MAC',n:'Electrical Machines',ch:['Single-phase transformer: equivalent circuit, phasor diagram, OC/SC tests, regulation, efficiency','Three-phase transformers: connections, vector groups, parallel operation, auto-transformer','DC machines: windings, generator characteristics, armature reaction, commutation, motor speed control & starting','Three-phase induction motor: principle, equivalent circuit, torque-slip, power flow, tests','Induction motor starting, speed control, and single-phase induction motors','Synchronous machines: cylindrical & salient pole, regulation, V-curves, power angle, parallel operation','Losses and efficiency of electrical machines']},
 {k:'PS',n:'Power Systems',ch:['Power generation concepts; ac and dc transmission concepts','Transmission line parameters and models (short, medium, long), ABCD constants, Ferranti effect','Cables, insulators, electric field distribution and distribution systems','Per-unit system and bus admittance matrix','Load flow: Gauss-Seidel and Newton-Raphson methods','Economic load dispatch with and without transmission losses','Voltage and frequency control, series & shunt compensation, power factor correction','Symmetrical components; symmetrical and unsymmetrical fault analysis','Protection: over-current, differential, directional, distance relays; circuit breakers','Power system stability, swing equation and equal area criterion']},
 {k:'CS',n:'Control Systems',ch:['Mathematical modelling, transfer functions, block diagram reduction and signal flow graphs (Mason\'s gain)','Time response: first & second order systems, specifications, steady-state error and error constants','Routh-Hurwitz stability criterion','Root locus','Frequency response and Bode plots, gain & phase margins','Polar and Nyquist plots, Nyquist stability criterion','Compensators (lead, lag, lead-lag) and P, PI, PID controllers','State-space representation, solution, controllability and observability']},
 {k:'MEAS',n:'Electrical & Electronic Measurements',ch:['Bridges (Wheatstone, Kelvin, Maxwell, Hay, Schering, Wien) and potentiometers','Measuring instruments: PMMC, moving iron, electrodynamometer; extension of range','Measurement of voltage, current, power, energy and power factor','Instrument transformers (CT, PT) and their errors','Digital voltmeters, multimeters, oscilloscopes, phase/time/frequency measurement','Error analysis: types of error, limiting error, statistical analysis']},
 {k:'AD',n:'Analog & Digital Electronics',ch:['Diode circuits: rectifiers, clippers, clampers, Zener regulators','Transistor amplifiers (BJT, MOSFET): biasing and small-signal analysis','Feedback amplifiers and oscillators (Barkhausen criterion, RC & LC oscillators)','Operational amplifiers: characteristics and applications (amplifiers, integrator, differentiator, comparators, Schmitt trigger)','Active filters, timers (555), VCO and sample-and-hold circuits','Combinational logic: Boolean algebra, K-maps, multiplexers, demultiplexers, decoders','Sequential logic: flip-flops, counters, shift registers','A/D and D/A converters']},
 {k:'PE',n:'Power Electronics',ch:['Power semiconductor devices: diode, thyristor, MOSFET, IGBT — characteristics, ratings, gate drive','DC-DC converters: buck, boost, buck-boost (steady-state analysis, ripple)','Single- and three-phase uncontrolled rectifiers','Thyristor-controlled (phase-controlled) rectifiers','Harmonics, displacement factor, distortion factor and power factor of rectifiers','Voltage source inverters: single- and three-phase, square-wave and sinusoidal PWM']}];

/* ---------- free legal resources (verified links) ---------- */
const NP=(id,t,by)=>({t,by:'NPTEL · '+by,u:'https://nptel.ac.in/courses/'+id});
const OCW=(slug,t)=>({t,by:'MIT OpenCourseWare',u:'https://ocw.mit.edu/courses/'+slug+'/'});
const AAC={t:'Lessons in Electric Circuits (free online textbook)',by:'All About Circuits',u:'https://www.allaboutcircuits.com/textbook/'};
const LIB={
 EM:[OCW('18-06-linear-algebra-spring-2010','18.06 Linear Algebra (Gilbert Strang)'),OCW('18-03-differential-equations-spring-2010','18.03 Differential Equations')],
 EC:[NP(108106172,'Basic Electrical Circuits','IIT Madras'),NP(108104139,'Basic Electric Circuits','IIT Kanpur'),OCW('6-002-circuits-and-electronics-spring-2007','6.002 Circuits and Electronics'),AAC],
 EMF:[NP(108106073,'Electromagnetic Fields','IIT Madras'),NP(108102119,'Engineering Electromagnetics','IIT Delhi')],
 SS:[NP(108104100,'Principles of Signals and Systems','IIT Kanpur'),NP(108106163,'Signals and Systems','NPTEL'),OCW('6-003-signals-and-systems-fall-2011','6.003 Signals and Systems')],
 MAC:[NP(108102146,'Electrical Machines','IIT Delhi'),NP(108105155,'Electrical Machines - I','IIT Kharagpur'),NP(108105131,'Electrical Machines - II','IIT Kharagpur'),OCW('6-685-electric-machines-fall-2013','6.685 Electric Machines')],
 PS:[NP(117105140,'Power System Analysis','IIT Kharagpur'),NP(108105104,'Power System Engineering','IIT Kharagpur'),NP(108105167,'Power System Protection','IIT Kharagpur'),OCW('6-061-introduction-to-electric-power-systems-spring-2011','6.061 Introduction to Electric Power Systems (with free chapter PDFs)')],
 CS:[NP(108106098,'Control Engineering','IIT Madras'),NP(107106081,'Control Systems','IIT Madras')],
 MEAS:[NP(108105153,'Electrical Measurement and Electronic Instruments','IIT Kharagpur'),NP(108103862,'Measurement and Instrumentation','IIT Guwahati')],
 AD:[NP(108105158,'Analog Electronic Circuits','IIT Kharagpur'),NP(108105132,'Digital Electronic Circuits','IIT Kharagpur'),AAC],
 PE:[NP(108102145,'Power Electronics','IIT Delhi'),NP(108101126,'Fundamentals of Power Electronics','IISc Bangalore'),NP(108101038,'Power Electronics','IIT Bombay')],
 GA:[]};
const LIB_ALL={t:'Electrical Engineering open textbooks',by:'LibreTexts',u:'https://eng.libretexts.org/Bookshelves/Electrical_Engineering'};

/* ---------- IndexedDB storage ---------- */
let dbp=null;
function db(){return dbp||(dbp=new Promise((res,rej)=>{const r=indexedDB.open('geebook',1);r.onupgradeneeded=()=>r.result.createObjectStore('ch');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)}))}
async function dget(k){try{const d=await db();return await new Promise(r=>{const q=d.transaction('ch').objectStore('ch').get(k);q.onsuccess=()=>r(q.result||null);q.onerror=()=>r(null)})}catch(e){return null}}
async function dput(k,v){const d=await db();return new Promise((r,j)=>{const t=d.transaction('ch','readwrite');t.objectStore('ch').put(v,k);t.oncomplete=()=>r();t.onerror=()=>j(t.error)})}
async function ddel(k){const d=await db();return new Promise(r=>{const t=d.transaction('ch','readwrite');t.objectStore('ch').delete(k);t.oncomplete=()=>r()})}
async function dkeys(){try{const d=await db();return await new Promise(r=>{const q=d.transaction('ch').objectStore('ch').getAllKeys();q.onsuccess=()=>r(q.result||[]);q.onerror=()=>r([])})}catch(e){return []}}
const ck=(s,i)=>s+'|'+i;

/* ---------- agent ---------- */
const BOOK_SYS=`You are an expert GATE Electrical Engineering teacher writing a concise, exam-focused study book (like the best GATE coaching notes, but original).
Write ONE chapter. Be accurate, clear and dense: theory a student must know for GATE, every important formula, standard results, and solved GATE-level examples.
Formatting: plain text with Unicode maths (√, π, ω, θ, ∫, Σ, ≤, ≥, °, Ω, μ, ∠, →, ∞) and the tags <sub> </sub> <sup> </sup> <b> </b> only. No LaTeX, no Markdown. Start bullet lines with "• ". Use "\\n" for new lines.
Figures: add a "figure" to a section or example whenever a diagram helps (circuit, phasor diagram, characteristic curve, waveform, Bode plot, block diagram, truth table). Use exactly this JSON drawing format:
- {"kind":"circuit","elements":[...]} grid coordinates [x,y] (x right, y DOWN, integers or .5, 0-14). Two-terminal: {"type":T,"from":[x,y],"to":[x,y],"label":"..."} with T = R, L, C, V (+ at "to"), I (arrow from→to), Vac, Vdep, Idep, Z, D (anode at "from"), sw, meter ("meter":"V|A|W"), xfmr, cb, wire, bus, arrow. "wire" may use "points":[[x,y],...]. Points: {"type":"dot|terminal|ground|load","at":[x,y]}, {"type":"text","at":[x,y],"text":"..."}, {"type":"opamp","at":[x,y]} (− input [x,y-0.5], + input [x,y+0.5], output [x+1.5,y]), {"type":"block","at":[x,y],"text":"G(s)","w":2,"h":0.8}, {"type":"sum","at":[x,y],"signs":{"w":"+","s":"-"}}.
- {"kind":"graph","x":{"label":"","min":0,"max":1,"log":false},"y":{"label":"","min":0,"max":1},"series":[{"points":[[x,y],...]} or {"expr":"10*exp(-x)","dashed":false,"label":""}],"marks":[{"x":0,"y":0,"text":""}]} (expr in variable x with + - * / ^ ( ) sin cos tan exp ln log10 sqrt abs u rect tri min max pi e)
- {"kind":"phasor","vectors":[{"mag":1,"ang":0,"label":"V"},{"mag":0.5,"ang":-30,"label":"I","from":0}]}
- {"kind":"table","headers":[...],"rows":[[...]]}
Every figure must be correct and consistent with the text. Use 2-5 figures per chapter where natural.
Return ONLY JSON:
{"title":"...","intro":"2-3 lines: what this chapter covers and why it matters for GATE",
"sections":[{"heading":"...","text":"theory with bullets, definitions, derivation outlines, standard results","figure":{...} (optional)}],
"formulas":[{"name":"...","formula":"...","note":"when to use / conditions"}],
"examples":[{"level":"1-mark|2-mark","q":"GATE-style question (with options (A)-(D) inside the text if MCQ)","figure":{...} (optional),"solution":"step-by-step","answer":"final answer"}],
"traps":["common mistakes students make"],
"pyq_trend":"what GATE usually asks from this chapter and roughly how often",
"revision":["one-line points for last-day revision"]}
Aim for 5-8 sections, 12-25 formulas, 5-7 solved examples (mix of 1-mark and 2-mark, at least two numerical), 4-8 traps, 8-12 revision points.`;
const CHK_SYS=`You are an expert GATE EE solver. Solve each question independently. Return ONLY JSON {"answers":[{"i":<index>,"final":"<option letter(s) or number or short expression>"}]}.`;

function cleanFig(o){if(o&&o.figure!=null&&window.FIG.validate(o.figure))delete o.figure}
async function writeChapter(subj,idx,log){
  const S=BOOK.find(b=>b.k===subj), topic=S.ch[idx];
  log(`✍️  Writing: ${S.n} → ${topic.split(':')[0]}…`);
  const user=`Subject: ${S.n} (GATE Electrical Engineering).\nChapter ${idx+1} of ${S.ch.length}: ${topic}\nOther chapters in this subject (do not cover them in depth): ${S.ch.filter((_,i)=>i!==idx).map(c=>c.split(':')[0]).join('; ')}.`;
  let ch;
  for(let a=0;a<2&&!ch;a++){try{ch=A.parseJSON(await A.claude(BOOK_SYS,user,24000));if(!Array.isArray(ch.sections)||!ch.sections.length)throw Error('chapter came back empty')}catch(e){if(/API key|API error|internet/.test(e.message))throw e;log('   ⚠ '+e.message+(a?'':' — retrying'));ch=null}}
  if(!ch) throw new Error('Could not write this chapter. Try again.');
  (ch.sections||[]).forEach(cleanFig); (ch.examples||[]).forEach(cleanFig);
  ch.examples=(ch.examples||[]).filter(e=>e&&e.q&&e.solution);
  if(A.cfg().verify&&ch.examples.length){
    log('   🔍 Checking worked examples with an independent solver…');
    try{const r=A.parseJSON(await A.claude(CHK_SYS,'Questions:\n\n'+ch.examples.map((e,i)=>`[${i}] ${e.q}${e.figure?'\nFIGURE (JSON drawing spec, y downward): '+JSON.stringify(e.figure):''}`).join('\n\n'),4000)).answers||[];
      let ok=0; ch.examples.forEach((e,i)=>{const v=r.find(x=>+x.i===i); const fin=String(e.answer||'').match(/\(?([A-D])\)/)?.[1]||e.answer;
        e.checked=!!(v&&window.SOLVER.same(fin,v.final)); if(e.checked)ok++; else if(v) e.alt=String(v.final)});
      log(`   ${ok===ch.examples.length?'✓':'⚠'} ${ok}/${ch.examples.length} example answers confirmed`)}
    catch(e){if(/API key|internet/.test(e.message))throw e;log('   ⚠ example check skipped: '+e.message)}}
  ch.subj=subj; ch.idx=idx; ch.date=new Date().toLocaleDateString(); ch.model=A.cfg().model;
  await dput(ck(subj,idx),ch); log(`   ✓ Saved (${ch.sections.length} sections, ${(ch.formulas||[]).length} formulas, ${ch.examples.length} examples)`);
  return ch}

/* ---------- rendering ---------- */
const FIG=f=>f?window.FIG.html(f):'';
function chapterHTML(ch,num){
  return `<article class="chap"><div class="chnum">Chapter ${num}</div><h1>${rich(ch.title)}</h1><p class="intro">${rich(ch.intro)}</p>
  ${(ch.sections||[]).map((s,i)=>`<h2>${num}.${i+1} ${rich(s.heading)}</h2><div class="btxt">${rich(s.text)}</div>${FIG(s.figure)}`).join('')}
  ${(ch.formulas||[]).length?`<h2>Formula sheet</h2><table class="fsheet">${ch.formulas.map(f=>`<tr><td>${rich(f.name)}</td><td class="fm">${rich(f.formula)}</td><td>${rich(f.note||'')}</td></tr>`).join('')}</table>`:''}
  ${(ch.examples||[]).length?`<h2>Solved GATE-level examples</h2>${ch.examples.map((e,i)=>`<div class="ex"><div class="exh"><b>Example ${i+1}</b>${e.level?` <span class="badge">${esc(e.level)}</span>`:''}${e.checked===true?' <span class="tag c">✓ answer double-checked</span>':e.checked===false?` <span class="tag u">⚠ checker got ${esc(e.alt||'a different answer')} — verify</span>`:''}</div>
     <div>${rich(e.q)}</div>${FIG(e.figure)}<details><summary>Solution</summary><div class="sol">${rich(e.solution)}</div><p><b>Answer:</b> ${rich(e.answer)}</p></details></div>`).join('')}`:''}
  ${(ch.traps||[]).length?`<h2>Common traps</h2><ul>${ch.traps.map(t=>`<li>${rich(t)}</li>`).join('')}</ul>`:''}
  ${ch.pyq_trend?`<h2>What GATE asks</h2><p>${rich(ch.pyq_trend)}</p>`:''}
  ${(ch.revision||[]).length?`<h2>Last-day revision</h2><ul class="rev">${ch.revision.map(t=>`<li>${rich(t)}</li>`).join('')}</ul>`:''}
  <p class="note">AI-written (${esc(ch.model||'')}, ${esc(ch.date||'')}). Cross-check important results with your textbook.</p></article>`}

/* ---------- UI ---------- */
const sec=document.createElement('section');
sec.innerHTML=`<div class="mockhead"><h2>📚 Study book</h2><a class="iconbtn" href="${LIB_ALL.u}" target="_blank" rel="noopener" style="text-decoration:none;color:var(--ink)">Free textbooks ↗</a></div>
<p class="note" style="margin-top:-4px">All 11 GATE EE subjects, chapter-wise from the official syllabus. Each chapter is written by AI once, then saved for offline reading.</p>
<div class="bgrid" id="bgrid"></div>`;
const anchor=[...document.querySelectorAll('#home h2')].find(h=>h.textContent==='Old papers');
anchor.parentNode.insertBefore(sec,anchor);
async function renderGrid(){const keys=new Set(await dkeys());
  $('bgrid').innerHTML=BOOK.map(S=>{const n=S.ch.filter((_,i)=>keys.has(ck(S.k,i))).length;
    return `<button class="bcard" data-bs="${S.k}"><b>${esc(S.n)}</b><span>${n}/${S.ch.length} chapters ready</span><i style="width:${Math.round(100*n/S.ch.length)}%"></i></button>`}).join('')}
$('bgrid').onclick=e=>{const b=e.target.closest('[data-bs]');if(b)openSubject(b.dataset.bs)};

let running=false;
async function openSubject(k){
  const S=BOOK.find(b=>b.k===k), o=A.overlay('bookOv',esc(S.n),`<button class="iconbtn" id="bkPrint">🖨 Print / PDF</button>`), body=o.querySelector('.ovbody');
  const close=()=>{o.classList.remove('open');document.body.style.overflow='';renderGrid()};
  o.querySelector('[data-x]').onclick=()=>{if(running&&!confirm('Chapters are still being written. Stop?'))return;running=false;close()};
  const keys=new Set(await dkeys()); const have=i=>keys.has(ck(k,i));
  body.innerHTML=`<div class="gen">
   <div class="chlist">${S.ch.map((c,i)=>{const [t,d]=c.split(/:(.+)/);return `<div class="chrow"><div class="info"><b>${i+1}. ${esc(t)}</b>${d?`<span>${esc(d.trim())}</span>`:''}</div>
     ${have(i)?`<button class="btn pri" data-read="${i}">Read</button><button class="btn" data-rw="${i}" title="Rewrite">↻</button>`:`<button class="btn" data-w="${i}">✍️ Write</button>`}</div>`}).join('')}</div>
   <button class="genbtn" id="bkAll" style="width:100%;margin-top:12px">✍️ Write all remaining chapters (${S.ch.filter((_,i)=>!have(i)).length})</button>
   <p class="note">Needs internet and your API key. About 1–2 minutes per chapter${A.cfg().verify?' including an answer check of the worked examples':''}. Chapters are then readable offline.</p>
   <div class="log" id="bkLog" hidden></div>
   ${(LIB[k]||[]).length?`<h2>Free courses &amp; books for ${esc(S.n)}</h2><div class="libl">${LIB[k].map(r=>`<a href="${r.u}" target="_blank" rel="noopener"><b>${esc(r.t)}</b><span>${esc(r.by)} ↗</span></a>`).join('')}</div>`:''}
  </div>`;
  const L=$('bkLog'), log=m=>{L.hidden=false;L.textContent+=m+'\n';L.scrollTop=1e9};
  async function write(list){
    if(!A.cfg().key){A.settings();return} if(running)return; running=true; $('bkAll').disabled=true;
    let i=0; const worker=async()=>{while(running&&i<list.length){const j=list[i++];try{await writeChapter(k,j,log)}catch(e){log('✕ '+e.message);if(/API key|internet|API error/.test(e.message)){running=false}}}};
    await Promise.all([worker(),worker()]);
    const wasRunning=running; running=false; if(wasRunning){log('Done.');openSubject(k)}}
  body.onclick=async e=>{const d=e.target.closest('button')?.dataset||{};
    if(d.read!==undefined) readChapter(k,+d.read);
    if(d.w!==undefined) write([+d.w]);
    if(d.rw!==undefined&&confirm('Rewrite this chapter? The saved version will be replaced.')) write([+d.rw])};
  $('bkAll').onclick=()=>write(S.ch.map((_,i)=>i).filter(i=>!have(i)));
  $('bkPrint').onclick=async()=>{const chs=[];for(let i=0;i<S.ch.length;i++){const c=await dget(ck(k,i));if(c)chs.push([c,i+1])}
    if(!chs.length){alert('Write at least one chapter first.');return} printBook(S,chs)};
}
async function readChapter(k,i){
  const S=BOOK.find(b=>b.k===k), ch=await dget(ck(k,i)); if(!ch) return;
  const o=A.overlay('readOv',esc(S.n)+' · Ch '+(i+1),`<button class="iconbtn" id="rdPrev" ${i?'':'disabled'}>‹</button><button class="iconbtn" id="rdNext" ${i<S.ch.length-1?'':'disabled'}>›</button><button class="iconbtn" id="rdPrint">🖨</button>`);
  o.querySelector('[data-x]').onclick=()=>{o.classList.remove('open')};
  o.querySelector('.ovbody').innerHTML=`<div class="reader">${chapterHTML(ch,i+1)}</div>`; o.querySelector('.ovbody').scrollTop=0;
  $('rdPrev').onclick=()=>readChapter(k,i-1);
  $('rdNext').onclick=async()=>{if(await dget(ck(k,i+1)))readChapter(k,i+1);else alert('Next chapter is not written yet.')};
  $('rdPrint').onclick=()=>printBook(S,[[ch,i+1]]);
}
function printBook(S,chs){
  let p=$('printArea'); if(!p){p=document.createElement('div');p.id='printArea';document.body.appendChild(p)}
  p.innerHTML=`<div class="ptitle"><h1>GATE Electrical Engineering</h1><h2>${esc(S.n)}</h2><p>Study notes · ${chs.length} chapter(s)</p></div>${chs.map(([c,n])=>chapterHTML(c,n)).join('')}`;
  p.querySelectorAll('details').forEach(d=>d.open=true);
  document.body.classList.add('printing'); setTimeout(()=>{window.print();setTimeout(()=>document.body.classList.remove('printing'),500)},100)}
window.addEventListener('afterprint',()=>document.body.classList.remove('printing'));
renderGrid();
window.BOOK={BOOK,LIB,writeChapter,chapterHTML,dget,dkeys};
})();
