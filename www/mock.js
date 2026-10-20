/* GATE EE Mock Test Agent
   Generator agent writes questions → Checker agent solves them independently →
   mismatches are regenerated once → test saved offline → CBT exam → auto grading. */
(function(){
const LS={get(k,d){try{const v=localStorage.getItem('gee_'+k);return v?JSON.parse(v):d}catch(e){return d}},
          set(k,v){try{localStorage.setItem('gee_'+k,JSON.stringify(v));return true}catch(e){return false}}};
const $=id=>document.getElementById(id);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
// allow only a few formatting tags from the model
const rich=s=>esc(s).replace(/&lt;(\/?)(sub|sup|b|i|u)&gt;/g,'<$1$2>').replace(/&lt;br\s*\/?&gt;/g,'<br>').replace(/\n/g,'<br>');

/* ---------- syllabus & blueprint ---------- */
const SUBJ=[
 {k:'EM',n:'Engineering Mathematics',w:11,t:'linear algebra, calculus, differential equations, complex variables, probability & statistics, numerical methods, vector calculus'},
 {k:'EC',n:'Electric Circuits',w:10,t:'network theorems, transient & steady-state, AC circuits, resonance, two-port networks, three-phase circuits, coupled circuits'},
 {k:'EMF',n:'Electromagnetic Fields',w:4,t:'Coulomb/Gauss law, electric field & potential, capacitance, Biot-Savart, Ampere, Faraday, inductance, magnetic circuits'},
 {k:'SS',n:'Signals and Systems',w:8,t:'LTI systems, convolution, Fourier series/transform, Laplace, Z-transform, sampling, DTFT'},
 {k:'MAC',n:'Electrical Machines',w:12,t:'single-phase & three-phase transformers, DC machines, induction motors, synchronous machines, efficiency & regulation'},
 {k:'PS',n:'Power Systems',w:11,t:'transmission line parameters & performance, per-unit, load flow, fault analysis, symmetrical components, stability, protection, economic dispatch, HVDC'},
 {k:'CS',n:'Control Systems',w:9,t:'transfer function, block diagrams, time response, Routh, root locus, Bode, Nyquist, compensators, state-space'},
 {k:'MEAS',n:'Electrical & Electronic Measurements',w:5,t:'bridges, instruments, power & energy measurement, instrument transformers, errors, oscilloscope'},
 {k:'AD',n:'Analog & Digital Electronics',w:7,t:'diodes, BJT/MOSFET amplifiers, op-amps, oscillators, filters, Boolean algebra, combinational & sequential circuits, ADC/DAC'},
 {k:'PE',n:'Power Electronics',w:8,t:'power devices, choppers, DC-DC converters, rectifiers, inverters, PWM, AC voltage controllers'}];
const GA={k:'GA',n:'General Aptitude',t:'verbal ability (grammar, vocabulary, reading), quantitative aptitude, analytical & spatial reasoning, data interpretation'};
const ALL=[GA,...SUBJ], NAME=Object.fromEntries(ALL.map(s=>[s.k,s.n]));
const MODES={
 full:{label:'Full-length mock',desc:'65 Q · 100 marks · 180 min · same pattern as GATE EE',mins:180,ga:[5,5],tech:[25,30]},
 half:{label:'Half-length mock',desc:'33 Q · 50 marks · 90 min',mins:90,ga:[3,2],tech:[13,15]},
 subject:{label:'Subject-wise test',desc:'20 Q · 30 marks · 60 min · subjects you pick',mins:60,ga:[0,0],tech:[10,10]}};

function alloc(total,weights){ // largest-remainder apportionment
  const W=weights.reduce((a,b)=>a+b,0)||1, raw=weights.map(w=>total*w/W), out=raw.map(Math.floor);
  let left=total-out.reduce((a,b)=>a+b,0);
  raw.map((r,i)=>[r-Math.floor(r),i]).sort((a,b)=>b[0]-a[0]).forEach(([,i])=>{if(left>0){out[i]++;left--}});
  return out}
function shuffle(a){for(let i=a.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[a[i],a[j]]=[a[j],a[i]]}return a}
function typeMix(n,isGA){ // GATE-like ratio of MCQ/MSQ/NAT
  const c=isGA?alloc(n,[80,0,20]):alloc(n,[50,12,38]);
  return shuffle([...Array(c[0]).fill('MCQ'),...Array(c[1]).fill('MSQ'),...Array(c[2]).fill('NAT')])}
function blueprint(mode,subjKeys){
  const M=MODES[mode], slots=[];
  const push=(k,marks,types)=>types.forEach(t=>slots.push({subject:k,marks,type:t}));
  if(M.ga[0]+M.ga[1]){push('GA',1,typeMix(M.ga[0],true));push('GA',2,typeMix(M.ga[1],true))}
  const subs=mode==='subject'?SUBJ.filter(s=>subjKeys.includes(s.k)):SUBJ;
  const w=subs.map(s=>mode==='subject'?1:s.w);
  [1,2].forEach((m,idx)=>{const per=alloc(M.tech[idx],w), types=typeMix(M.tech[idx],false); let p=0; // type mix spread over the whole section
    subs.forEach((s,i)=>{if(per[i]){push(s.k,m,types.slice(p,p+per[i]));p+=per[i]}})});
  return slots}

/* ---------- Claude API ---------- */
const MODELS=[['claude-sonnet-5-5','Claude Sonnet 5.5 (fast, recommended)'],['claude-opus-5-5','Claude Opus 5.5 (strongest, slower, costs more)']];
let cfg=Object.assign({key:'',model:MODELS[0][0],verify:true},LS.get('ai',{}));
async function claude(system,user,maxTokens){
  for(let attempt=0;attempt<4;attempt++){
    let r;
    try{
      r=await fetch('https://api.anthropic.com/v1/messages',{method:'POST',headers:{
        'content-type':'application/json','x-api-key':cfg.key,'anthropic-version':'2023-06-01',
        'anthropic-dangerous-direct-browser-access':'true'},
        body:JSON.stringify({model:cfg.model,max_tokens:maxTokens||16000,system,messages:[{role:'user',content:user}]})});
    }catch(e){ if(attempt===3) throw new Error('No internet connection (generating a test needs internet; taking it does not).'); await sleep(2000*(attempt+1)); continue }
    if(r.ok){const j=await r.json();return (j.content||[]).filter(b=>b.type==='text').map(b=>b.text).join('')}
    let msg=''; try{msg=(await r.json()).error?.message||''}catch(e){}
    if(r.status===401) throw new Error('API key is not valid. Check it in AI settings.');
    if(r.status===400||r.status===403||r.status===404) throw new Error(`API error ${r.status}: ${msg}`);
    if(attempt===3) throw new Error(`API busy (${r.status}). Try again in a few minutes.`);
    await sleep(4000*(attempt+1));
  }}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function parseJSON(txt){
  const a=txt.indexOf('{'), b=txt.lastIndexOf('}');
  if(a<0||b<a) throw new Error('Model did not return JSON');
  return JSON.parse(txt.slice(a,b+1))}

const GEN_SYS=`You are a senior GATE Electrical Engineering (EE) paper setter from the IITs. You write ORIGINAL questions that match real GATE EE questions in style, depth and difficulty.
Rules:
- Every question must be fully solvable from its text plus its figure (if any).
- Do not copy any past GATE question; change the concept combination, numbers and framing.
- Write maths in plain text with Unicode (√, π, ω, θ, ∫, Σ, ≤, ≥, °, Ω, μ, ∠) and the HTML tags <sub> </sub> <sup> </sup> only. No LaTeX, no Markdown.
- MCQ: exactly 4 options, exactly ONE correct. Distractors must come from common mistakes.
- MSQ: exactly 4 options, ONE OR MORE correct (vary it: sometimes 1, sometimes 2-3, rarely all 4). Each option must be independently true/false.
- NAT: no options. Give the exact answer and an acceptance range (GATE style, about ±1% or the stated rounding). Say in the question how to round, e.g. "(round off to 2 decimal places)".
- 1-mark questions: single concept, 1-3 steps. 2-mark questions: multi-step, combine 2 concepts, need careful calculation.
- Before writing each answer, solve the question fully yourself and make sure the key is correct and unique.
- solution: clear step-by-step working (4-10 short lines), ending with the final answer.
Return ONLY a JSON object, no commentary:
{"questions":[{"slot":<slot number>,"type":"MCQ|MSQ|NAT","marks":1|2,"subject":"<code>","topic":"<topic>","subtopic":"<subtopic>","question":"...","options":["...","...","...","..."],"answer":"B" or "AC" or <number>,"range":[min,max],"solution":"..."}]}
(options only for MCQ/MSQ; range only for NAT; answer letters A-D.) Add "figure":{...} when the question has a figure.

FIGURES — like real GATE papers, use a figure whenever a circuit, waveform, plot, block diagram, single-line diagram, phasor diagram or truth table is the natural way to give the data. The question text must refer to "the figure", and every value needed must appear in the figure labels or the text, consistently. Never give a figure that reveals the answer. Allowed figure kinds (JSON):
1) {"kind":"circuit","elements":[...],"caption":"optional"} — also used for block diagrams and single-line diagrams.
 Grid coordinates [x,y]: x to the right, y DOWNWARD, integers or .5, between 0 and 14. Leave 2 units for each R/L/C/source and at least 1 unit between parallel branches.
 Two-terminal elements: {"type":T,"from":[x,y],"to":[x,y],"label":"R<sub>1</sub> = 2 Ω"} with T one of:
  R, L, C, V (DC source, + terminal at "to"), I (current source, arrow points from→to), Vac (AC source / generator), Vdep (dependent voltage source, + at "to"; label like "2v<sub>x</sub>"), Idep (dependent current source, arrow from→to), Z (impedance box), D (diode, anode at "from", cathode at "to"), sw (switch; label e.g. "t = 0"), meter (add "meter":"V" | "A" | "W"), xfmr (transformer in single-line diagrams), cb (circuit breaker), wire, bus (thick bus bar), arrow (signal-flow arrow, head at "to").
  "wire" may instead use "points":[[x,y],[x,y],...]. Optional "side":"b" moves a label to the other side; "dashed":true for wires.
 Point elements: {"type":"dot"|"terminal"|"ground"|"load","at":[x,y]}; {"type":"text","at":[x,y],"text":"a","anchor":"start|middle|end"};
  {"type":"opamp","at":[x,y]} → inverting (−) input pin at [x,y-0.5], non-inverting (+) at [x,y+0.5], output at [x+1.5,y] (wire exactly to these pins);
  {"type":"block","at":[x,y],"text":"G(s) = 10/(s+1)","w":2,"h":0.8} (centre, size in grid units; make w wide enough for the text: about 0.12 per character);
  {"type":"sum","at":[x,y],"signs":{"w":"+","s":"-"}} (summing junction, radius 0.2; signs at sides w/n/e/s; connect arrows to its edge, e.g. head at [x-0.2,y]).
 Every connection must meet exactly at the same grid point. Mark node names with "text" elements. Before answering, mentally trace the netlist to be sure the drawing matches the question.
2) {"kind":"graph","x":{"label":"t (ms)","min":0,"max":4},"y":{"label":"v(t) (V)","min":-12,"max":12},"series":[{"points":[[0,10],[1,10],[1,-10],[2,-10]]},{"expr":"5*exp(-x)","dashed":true,"label":"i(t)"}],"marks":[{"x":1,"y":10,"text":"10 V"}],"vlines":[2],"hlines":[5]}
 Use "points" for piecewise waveforms (repeat x for vertical edges) and "expr" for smooth curves. expr uses variable x with + - * / ^ ( ), sin cos tan exp ln log10 sqrt abs u (unit step) rect tri min max pi e. Set "log":true inside "x" for Bode plots (x.min > 0). Optional "ticks":[0,1,2] or [[3.1416,"π"]] on an axis.
3) {"kind":"phasor","vectors":[{"mag":1,"ang":0,"label":"V"},{"mag":0.8,"ang":-30,"label":"I"},{"mag":0.3,"ang":60,"from":0,"label":"IX","dashed":true}],"angles":[{"from":-30,"to":0,"label":"φ"}]} — angles in degrees; "from":k starts a vector at the tip of vector k.
4) {"kind":"table","headers":["A","B","Y"],"rows":[["0","0","1"]]} — truth tables, K-maps, data tables.`;

const LEVEL={gate:'Real GATE EE level: about 30% easy, 50% moderate, 20% tough, like a recent GATE paper.',
             tough:'Tougher than average GATE: about 10% easy, 45% moderate, 45% tough. Use the hardest recent GATE EE questions as the benchmark, with traps and multi-concept 2-mark problems. Keep everything solvable in about 3 minutes with a scientific calculator.'};

const FIGQ={gate:'About 35-40% of the technical questions should have a figure (as in recent GATE EE papers): mostly circuits, op-amp/diode circuits, block diagrams, waveforms, Bode/characteristic plots, single-line diagrams. General Aptitude may occasionally use a table or graph.',
            more:'About 60% of the questions should have a figure where it makes sense (circuits, block diagrams, waveforms, plots, phasors, tables).',
            none:'Do NOT use figures; describe everything in words.'};
function genPrompt(batch,level,focus,avoid,figs){
  return `Write ${batch.length} new questions for a GATE EE mock test.
Difficulty: ${LEVEL[level]}
Figures: ${FIGQ[figs||'gate']}
${focus?`Student's focus request: ${focus}\n`:''}${avoid.length?`Avoid repeating these subtopics already used in this student's earlier mock tests: ${avoid.join('; ')}\n`:''}
Slots (write exactly one question for each, keep slot numbers, subject codes, type and marks exactly):
${batch.map(s=>`- slot ${s.slot}: subject ${s.subject} (${NAME[s.subject]}; syllabus: ${(ALL.find(x=>x.k===s.subject)||{}).t}), ${s.type}, ${s.marks} mark${s.marks>1?'s':''}`).join('\n')}
Spread the questions over different topics of each subject.`}

const VER_SYS=`You are an expert GATE Electrical Engineering solver. Solve each question independently and carefully. Show no working.
Return ONLY JSON: {"answers":[{"slot":<n>,"answer":"<letter(s) for MCQ/MSQ, e.g. B or ACD>","value":<number for NAT or null>,"ambiguous":true|false}]}
Set ambiguous=true if the question is flawed, has no correct option, or more than one defensible answer (for MCQ).`;
function verPrompt(qs){return 'Solve these questions. A figure, when present, is given as a JSON drawing spec (circuit elements on a grid with y downward, graphs, phasors or tables); read it as the student would see the drawing. If the figure contradicts the text or is missing data, set ambiguous=true.\n\n'+qs.map(q=>`[slot ${q.slot}] (${q.type}, ${q.marks} mark) ${q.question}${q.figure?'\nFIGURE: '+JSON.stringify(q.figure):''}${q.options?'\n'+q.options.map((o,i)=>`(${'ABCD'[i]}) ${o}`).join('\n'):''}`).join('\n\n')}

function normLetters(s){return [...new Set(String(s||'').toUpperCase().replace(/[^A-D]/g,''))].sort().join('')}
function agree(q,v){
  if(!v||v.ambiguous) return false;
  if(q.type==='NAT'){const x=parseFloat(v.value??v.answer);if(!isFinite(x))return false;
    const [lo,hi]=q.range, mid=(lo+hi)/2, tol=Math.max(Math.abs(mid)*0.02,0.011);
    return (x>=lo&&x<=hi)||Math.abs(x-mid)<=tol}
  return normLetters(v.answer)===normLetters(q.answer)}
function validQ(q,slot){
  if(!q||typeof q.question!=='string'||q.question.length<15) return false;
  q.type=slot.type; q.marks=slot.marks; q.subject=slot.subject;
  if(q.figure!=null){ const err=window.FIG.validate(q.figure); if(err){ q._figErr=err; return false } }
  else if(/\bfigure\b|shown below|given below/i.test(q.question)&&!/table below/i.test(q.question)){ q._figErr='mentions a figure but has none'; return false }
  if(q.type==='NAT'){const a=parseFloat(q.answer); if(!isFinite(a)) return false; q.answer=a;
    let r=Array.isArray(q.range)?q.range.map(Number):[]; if(r.length!==2||!r.every(isFinite)) r=[a-Math.max(Math.abs(a)*0.01,0.01),a+Math.max(Math.abs(a)*0.01,0.01)];
    q.range=[Math.min(...r,a),Math.max(...r,a)]; delete q.options; return true}
  if(!Array.isArray(q.options)||q.options.length!==4) return false;
  q.answer=normLetters(q.answer); if(!q.answer) return false;
  if(q.type==='MCQ'&&q.answer.length!==1) return false;
  q.options=q.options.map(o=>String(o).replace(/^\s*\(?[A-Da-d][\).:]\s+/,'')); return true}

async function pool(items,n,fn){let i=0;const run=async()=>{while(i<items.length){const k=i++;await fn(items[k],k)}};await Promise.all(Array.from({length:Math.min(n,items.length)},run))}

async function runAgent(opts,ui){
  const slots=blueprint(opts.mode,opts.subjects).map((s,i)=>({...s,slot:i+1}));
  const avoid=[...new Set(LS.get('tests',[]).slice(-4).flatMap(t=>t.questions.map(q=>q.subtopic).filter(Boolean)))].slice(0,60);
  const done={}, verified={};
  const total=slots.length*(cfg.verify?2:1); let prog=0; const tick=n=>{prog+=n;ui.progress(Math.min(1,prog/total))};
  ui.log(`Plan: ${slots.length} questions — ${countBy(slots,'type')} — subjects: ${[...new Set(slots.map(s=>s.subject))].join(', ')}`);

  async function genBatch(batch,label){
    ui.log(`✍️  Writing ${label}: ${batch.length} Q (${NAME[batch[0].subject]})…`);
    let out;
    try{ out=parseJSON(await claude(GEN_SYS,genPrompt(batch,opts.level,opts.focus,avoid,opts.figs),Math.min(32000,3400*batch.length+2000))).questions||[] }
    catch(e){ if(/API key|API error|internet/.test(e.message)) throw e; ui.log('   ⚠ '+e.message+' — retrying once');
      try{out=parseJSON(await claude(GEN_SYS,genPrompt(batch,opts.level,opts.focus,avoid,opts.figs),Math.min(32000,3400*batch.length+2000))).questions||[]}
      catch(e2){if(/API key|API error|internet/.test(e2.message))throw e2;ui.log('   ⚠ batch failed again: '+e2.message);out=[]} }
    let ok=0,figs=0,bad=[]; for(const s of batch){const q=out.find(x=>+x.slot===s.slot); if(q&&validQ(q,s)){q.slot=s.slot;done[s.slot]=q;ok++;if(q.figure)figs++} else if(q&&q._figErr) bad.push(q._figErr)}
    tick(batch.length); ui.log(`   ✓ ${ok}/${batch.length} written${figs?` (${figs} with figure)`:''}${bad.length?` — figure problems: ${bad.join('; ')}`:''}`);
  }
  async function verBatch(batch){
    const qs=batch.map(s=>done[s.slot]).filter(Boolean); if(!qs.length) return;
    ui.log(`🔍 Checking ${qs.length} Q (${NAME[qs[0].subject]}) with an independent solver…`);
    let ans=[]; try{ans=parseJSON(await claude(VER_SYS,verPrompt(qs),6000)).answers||[]}catch(e){if(/API key|API error|internet/.test(e.message))throw e;ui.log('   ⚠ checker failed: '+e.message)}
    let ok=0; for(const q of qs){const v=ans.find(a=>+a.slot===q.slot); if(agree(q,v)){verified[q.slot]=true;ok++}}
    tick(batch.length); ui.log(`   ${ok===qs.length?'✓':'⚠'} ${ok}/${qs.length} answers confirmed`);
  }
  const batches=chunkBySubject(slots,6);
  await pool(batches,3,(b,i)=>genBatch(b,`batch ${i+1}/${batches.length}`));
  // fill any slot the generator missed
  let missing=slots.filter(s=>!done[s.slot]);
  if(missing.length){ ui.log(`Rewriting ${missing.length} question(s) that came back malformed…`); prog-=missing.length;
    await pool(chunkBySubject(missing,6),3,b=>genBatch(b,'retry')) }
  if(cfg.verify){
    await pool(chunkBySubject(slots.filter(s=>done[s.slot]),8),3,verBatch);
    const bad=slots.filter(s=>done[s.slot]&&!verified[s.slot]);
    if(bad.length){
      ui.log(`♻️  ${bad.length} question(s) had a doubtful answer key — replacing them…`);
      const before={}; bad.forEach(s=>{before[s.slot]=done[s.slot];delete done[s.slot]});
      prog-=bad.length*2;
      await pool(chunkBySubject(bad,6),3,b=>genBatch(b,'replacement'));
      await pool(chunkBySubject(bad.filter(s=>done[s.slot]),8),3,verBatch);
      bad.forEach(s=>{if(!done[s.slot]) done[s.slot]=before[s.slot]});
    }
  }
  const questions=slots.map(s=>done[s.slot]).filter(Boolean).map(q=>({...q,unverified:cfg.verify?!verified[q.slot]:undefined}));
  if(questions.length<slots.length*0.8) throw new Error(`Only ${questions.length}/${slots.length} questions could be generated. Please try again.`);
  // GATE order: GA first, then 1-mark, then 2-mark
  questions.sort((a,b)=>(a.subject==='GA'?0:1)-(b.subject==='GA'?0:1)||a.marks-b.marks);
  const unv=questions.filter(q=>q.unverified).length;
  const nf=questions.filter(q=>q.figure).length;
  ui.log(`Done: ${questions.length} questions (${nf} with figures)${cfg.verify?`, ${questions.length-unv} double-checked`+(unv?`, ${unv} flagged`:''):''}.`);
  return questions}
function chunkBySubject(slots,max){const by={};slots.forEach(s=>(by[s.subject]=by[s.subject]||[]).push(s));
  const out=[];Object.values(by).forEach(a=>{const k=Math.ceil(a.length/max),sz=Math.ceil(a.length/k);for(let i=0;i<a.length;i+=sz)out.push(a.slice(i,i+sz))});return out}
function countBy(a,k){const c={};a.forEach(x=>c[x[k]]=(c[x[k]]||0)+1);return Object.entries(c).map(([t,n])=>`${n} ${t}`).join(', ')}

/* ---------- home section ---------- */
const home=$('home'), grid=$('grid');
const sec=document.createElement('section');
sec.innerHTML=`<div class="mockhead"><h2>AI mock test series</h2><div style="display:flex;gap:8px;flex-wrap:wrap">
 <button class="iconbtn" id="aiSet">AI settings</button><button class="iconbtn" id="impBtn">Import</button>
 <button class="genbtn" id="genBtn">⚡ Generate mock test</button></div></div>
 <div class="mlist" id="mlist"></div><input type="file" id="impFile" accept=".json,application/json" hidden>
 <h2>Old papers</h2>`;
home.insertBefore(sec,$('filters'));
function tests(){return LS.get('tests',[])}
function saveTests(t){if(!LS.set('tests',t))alert('Phone storage is full. Delete an old mock test first.')}
function renderList(){
  const t=tests();
  $('mlist').innerHTML=t.length?t.slice().reverse().map(x=>{const a=x.attempts||[],last=a[a.length-1];
    return `<div class="mitem"><div class="info"><div class="nm">${esc(x.name)}</div><div class="meta">${x.questions.length} Q · ${x.maxMarks} marks · ${x.mins} min · ${esc(x.levelLabel)}${last?` · Last score <b>${last.score}</b>/${x.maxMarks}`:''}${x.progress?' · <span class="badge prog">In progress</span>':''}</div></div>
    <button class="btn pri" data-start="${x.id}">${x.progress?'Resume':a.length?'Retake':'Start'}</button>${last?`<button class="btn" data-review="${x.id}">Solutions</button>`:''}
    <button class="btn" data-exp="${x.id}" title="Export">⤓</button><button class="btn" data-del="${x.id}" title="Delete">✕</button></div>`}).join('')
    :`<div class="empty">No mock tests yet. Tap <b>Generate mock test</b>. It needs internet and an API key once; the test is then saved and works offline.</div>`}
$('mlist').onclick=e=>{const d=e.target.closest('button')?.dataset||{};
  if(d.start)startExam(d.start); if(d.review){const t=tests().find(x=>x.id===d.review);showResult(t,t.attempts[t.attempts.length-1])}
  if(d.del&&confirm('Delete this mock test and its scores?')){saveTests(tests().filter(x=>x.id!==d.del));renderList()}
  if(d.exp){const t=tests().find(x=>x.id===d.exp);const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(t,null,1)],{type:'application/json'}));a.download=t.name.replace(/\W+/g,'_')+'.json';a.click()}};
$('impBtn').onclick=()=>$('impFile').click();
$('impFile').onchange=async e=>{try{const t=JSON.parse(await e.target.files[0].text());if(!Array.isArray(t.questions))throw 0;
  t.id='t'+Date.now();t.attempts=t.attempts||[];delete t.progress;t.name=t.name||'Imported test';t.mins=t.mins||Math.round(t.questions.length*2.8);
  t.maxMarks=t.questions.reduce((a,q)=>a+q.marks,0);t.levelLabel=t.levelLabel||'Imported';const all=tests();all.push(t);saveTests(all);renderList()}catch(err){alert('Not a valid mock test file.')} e.target.value=''};

/* ---------- overlays ---------- */
function overlay(id,title,right){let o=$(id);if(!o){o=document.createElement('div');o.id=id;o.className='ov';document.body.appendChild(o)}
  o.innerHTML=`<div class="ovbar"><button class="iconbtn" data-x>←</button><div class="t">${title}</div>${right||''}</div><div class="ovbody"></div>`;
  o.classList.add('open');document.body.style.overflow='hidden';return o}
function closeOv(o){o.classList.remove('open');document.body.style.overflow='';renderList()}

function settings(){
  const o=overlay('aiOv','AI settings');const b=o.querySelector('.ovbody');
  b.innerHTML=`<div class="gen">
  <fieldset><legend>Anthropic API key</legend><input type="password" id="kIn" placeholder="sk-ant-..." value="${esc(cfg.key)}" autocomplete="off">
  <p class="note">Get one at console.anthropic.com → API keys (paid; one full-length test usually costs well under a dollar with Sonnet). The key is stored only on this device and sent only to api.anthropic.com.</p></fieldset>
  <fieldset><legend>Model</legend>${MODELS.map(([v,l])=>`<label class="opt"><input type="radio" name="mdl" value="${v}" ${cfg.model===v?'checked':''}><span>${l}</span></label>`).join('')}
  <label class="opt"><span style="flex:1">Custom model ID</span></label><input type="text" id="mCustom" placeholder="leave empty to use the choice above" value="${MODELS.some(m=>m[0]===cfg.model)?'':esc(cfg.model)}"></fieldset>
  <fieldset><legend>Answer checking</legend><label class="opt"><input type="checkbox" id="vIn" ${cfg.verify?'checked':''}><span>Double-check every answer with a second, independent solver<small>Questions whose answers don't match are rewritten. About 30% slower, much more reliable keys.</small></span></label></fieldset>
  <button class="genbtn" id="kSave" style="width:100%">Save</button></div>`;
  o.querySelector('[data-x]').onclick=()=>closeOv(o);
  $('kSave').onclick=()=>{cfg.key=$('kIn').value.trim();cfg.model=$('mCustom').value.trim()||o.querySelector('[name=mdl]:checked').value;cfg.verify=$('vIn').checked;LS.set('ai',cfg);closeOv(o)}}
$('aiSet').onclick=settings;

$('genBtn').onclick=()=>{
  if(!cfg.key){settings();return}
  const o=overlay('genOv','Generate mock test');const b=o.querySelector('.ovbody');
  b.innerHTML=`<div class="gen">
  <fieldset><legend>Test type</legend>${Object.entries(MODES).map(([k,m],i)=>`<label class="opt"><input type="radio" name="mode" value="${k}" ${i?'':'checked'}><span>${m.label}<small>${m.desc}</small></span></label>`).join('')}
   <div id="subjBox" hidden><div class="palsec">Pick subjects</div><div class="subjs">${SUBJ.map(s=>`<label class="opt"><input type="checkbox" value="${s.k}"><span>${s.n}</span></label>`).join('')}</div></div></fieldset>
  <fieldset><legend>Toughness</legend>
   <label class="opt"><input type="radio" name="lvl" value="gate" checked><span>GATE level<small>Same mix as a real recent GATE EE paper</small></span></label>
   <label class="opt"><input type="radio" name="lvl" value="tough"><span>Tougher than GATE<small>For final revision; more multi-concept 2-mark problems</small></span></label></fieldset>
  <fieldset><legend>Figures &amp; diagrams</legend>
   <label class="opt"><input type="radio" name="figs" value="gate" checked><span>Like GATE<small>Circuits, block diagrams, waveforms and plots in about 35–40% of technical questions</small></span></label>
   <label class="opt"><input type="radio" name="figs" value="more"><span>More figures<small>Extra practice reading circuits and graphs</small></span></label>
   <label class="opt"><input type="radio" name="figs" value="none"><span>Text only</span></label></fieldset>
  <fieldset><legend>Focus (optional)</legend><textarea id="focus" rows="2" placeholder="e.g. more questions on root locus and synchronous machines"></textarea></fieldset>
  <button class="genbtn" id="go" style="width:100%">⚡ Generate</button>
  <p class="note">Uses ${esc(cfg.model)}${cfg.verify?' with answer double-checking':''}. A full-length test takes about 3–8 minutes; keep the app open.</p>
  <div id="genRun" hidden><div class="progress"><div id="bar"></div></div><div class="log" id="log"></div></div></div>`;
  let busy=false;
  o.querySelector('[data-x]').onclick=()=>{if(busy&&!confirm('Stop generating?'))return;busy=false;closeOv(o)};
  b.querySelectorAll('[name=mode]').forEach(r=>r.onchange=()=>$('subjBox').hidden=r.value!=='subject'||!r.checked);
  $('go').onclick=async()=>{
    const mode=b.querySelector('[name=mode]:checked').value, level=b.querySelector('[name=lvl]:checked').value;
    const subjects=[...b.querySelectorAll('#subjBox input:checked')].map(i=>i.value);
    if(mode==='subject'&&!subjects.length){alert('Pick at least one subject.');return}
    busy=true;$('go').disabled=true;$('go').textContent='Generating…';$('genRun').hidden=false;
    const logEl=$('log'),ui={log:m=>{logEl.textContent+=m+'\n';logEl.scrollTop=1e9},progress:p=>$('bar').style.width=(p*100).toFixed(0)+'%'};
    try{
      const qs=await runAgent({mode,level,subjects,focus:$('focus').value.trim(),figs:b.querySelector('[name=figs]:checked').value},ui);
      if(!busy) return;
      const all=tests(), n=all.filter(t=>t.mode===mode).length+1, M=MODES[mode];
      const name=mode==='subject'?`Subject test ${n}: ${subjects.map(k=>NAME[k]).join(', ')}`:`${M.label} ${n}`;
      const maxMarks=qs.reduce((a,q)=>a+q.marks,0);
      const t={id:'t'+Date.now(),name,mode,level,levelLabel:level==='tough'?'Tougher than GATE':'GATE level',mins:mode==='full'?180:Math.round(M.mins*qs.length/(M.ga[0]+M.ga[1]+M.tech[0]+M.tech[1])),maxMarks,model:cfg.model,created:new Date().toISOString(),questions:qs,attempts:[]};
      all.push(t);saveTests(all);ui.progress(1);ui.log('\nSaved to your test series. Starting is optional — it is stored offline.');
      $('go').disabled=false;$('go').textContent='▶ Start this test now';$('go').onclick=()=>{closeOv(o);startExam(t.id)};busy=false;
    }catch(e){ui.log('\n✕ '+e.message);logEl.classList.add('err');$('go').disabled=false;$('go').textContent='⚡ Try again';busy=false}
  }};

/* ---------- CBT exam ---------- */
function startExam(id){
  const all=tests(), t=all.find(x=>x.id===id); if(!t) return;
  let st=t.progress||{end:Date.now()+t.mins*60000,cur:0,resp:{},status:{},time:{}};
  if(t.progress&&!confirm('Resume your unfinished attempt?')) st={end:Date.now()+t.mins*60000,cur:0,resp:{},status:{},time:{}};
  const Q=t.questions; let enter=Date.now(), timer=null, natBuf='';
  const o=overlay('exOv',esc(t.name),`<span class="timer" id="exT"></span><button class="iconbtn" id="palToggle">Palette</button><button class="iconbtn" id="exSub" style="background:var(--accent);color:var(--accent-ink);border-color:var(--accent)">Submit</button>`);
  const body=o.querySelector('.ovbody'); body.style.display='flex';body.style.flexDirection='column';
  body.innerHTML=`<div class="exam" style="flex:1;min-height:0"><div class="qpane" id="qp"></div><aside class="palette" id="pal"></aside></div>
   <div class="qfoot"><button class="btn" id="bPrev">← Previous</button><button class="btn" id="bClr">Clear response</button><button class="btn" id="bRev">Mark for review &amp; next</button><button class="btn pri" id="bNext">Save &amp; next →</button></div>`;
  const save=()=>{st.time[st.cur]=(st.time[st.cur]||0)+(Date.now()-enter);enter=Date.now();t.progress=st;const a=tests();const i=a.findIndex(x=>x.id===t.id);a[i]=t;saveTests(a)};
  const has=i=>st.resp[i]!==undefined&&st.resp[i]!=='';
  function cls(i){const s=st.status[i];if(s==='rev')return has(i)?'s-rev s-revans':'s-rev';if(!s)return '';return has(i)?'s-ans':'s-nans'}
  function palette(){
    const groups=[['General Aptitude',Q.map((q,i)=>[q,i]).filter(([q])=>q.subject==='GA')],['Technical',Q.map((q,i)=>[q,i]).filter(([q])=>q.subject!=='GA')]].filter(g=>g[1].length);
    const cnt=k=>Q.filter((_,i)=>k(i)).length;
    $('pal').innerHTML=`<div class="legend"><span><i class="s-ans"></i>Answered ${cnt(i=>st.status[i]&&st.status[i]!=='rev'&&has(i))}</span><span><i class="s-nans"></i>Not answered ${cnt(i=>st.status[i]&&st.status[i]!=='rev'&&!has(i))}</span><span><i></i>Not visited ${cnt(i=>!st.status[i])}</span><span><i class="s-rev"></i>Marked ${cnt(i=>st.status[i]==='rev')}</span></div>`+
      groups.map(([n,arr])=>`<div class="palsec">${n}</div><div class="pal">${arr.map(([q,i])=>`<button data-q="${i}" class="${cls(i)} ${i===st.cur?'cur':''}">${i+1}</button>`).join('')}</div>`).join('')}
  function show(i){
    save(); st.cur=i; if(!st.status[i]) st.status[i]='seen'; const q=Q[i], r=st.resp[i];
    let inp='';
    if(q.type==='NAT'){natBuf=r??'';inp=`<div class="natbox"><input id="nat" inputmode="decimal" autocomplete="off" value="${esc(natBuf)}" placeholder="Enter a number"><div class="keypad">${['7','8','9','⌫','4','5','6','−','1','2','3','.','0','C'].map(k=>`<button data-k="${k}">${k}</button>`).join('')}</div></div>`}
    else inp=`<div class="opts">${q.options.map((op,j)=>{const L='ABCD'[j],on=(r||'').includes(L);return `<label class="${on?'sel':''}"><input type="${q.type==='MCQ'?'radio':'checkbox'}" name="op" value="${L}" ${on?'checked':''}><span><b>(${L})</b> ${rich(op)}</span></label>`}).join('')}</div>`;
    $('qp').innerHTML=`<div class="qhead"><b>Question ${i+1}</b><span>${q.type}</span><span>${q.marks} mark${q.marks>1?'s':''}</span><span>${q.type==='MCQ'?'Negative: −'+(q.marks===1?'1/3':'2/3'):'No negative marking'}</span><span>${esc(NAME[q.subject]||q.subject)}</span></div>
      ${q.type==='MSQ'?'<p class="note">One or more options may be correct. Select all correct options.</p>':''}<div class="qtext">${rich(q.question)}</div>${q.figure?window.FIG.html(q.figure):''}${inp}`;
    $('qp').scrollTop=0; palette();
    if(q.type==='NAT'){const n=$('nat');n.oninput=()=>{n.value=n.value.replace(/[^0-9.\-]/g,'');st.resp[i]=n.value};
      $('qp').querySelector('.keypad').onclick=e=>{const k=e.target.dataset.k;if(!k)return;let v=n.value;
        if(k==='⌫')v=v.slice(0,-1);else if(k==='C')v='';else if(k==='−')v=v.startsWith('-')?v.slice(1):'-'+v;else if(k==='.'){if(!v.includes('.'))v+=v===''||v==='-'?'0.':'.'}else v+=k;
        n.value=v;st.resp[i]=v}}
    else $('qp').querySelectorAll('input[name=op]').forEach(x=>x.onchange=()=>{
      const sel=[...$('qp').querySelectorAll('input[name=op]:checked')].map(y=>y.value).join('');st.resp[i]=sel;
      $('qp').querySelectorAll('.opts label').forEach(l=>l.classList.toggle('sel',l.querySelector('input').checked))});
  }
  $('pal').onclick=e=>{const q=e.target.closest('[data-q]');if(q){show(+q.dataset.q);$('pal').classList.remove('open')}};
  $('palToggle').onclick=()=>$('pal').classList.toggle('open');
  $('bNext').onclick=()=>{st.status[st.cur]='seen';if(st.cur<Q.length-1)show(st.cur+1);else{save();palette()}};
  $('bPrev').onclick=()=>{if(st.cur>0)show(st.cur-1)};
  $('bRev').onclick=()=>{st.status[st.cur]='rev';if(st.cur<Q.length-1)show(st.cur+1);else{save();palette()}};
  $('bClr').onclick=()=>{delete st.resp[st.cur];if(st.status[st.cur]==='rev')st.status[st.cur]='seen';show(st.cur)};
  const fmt=ms=>{ms=Math.max(0,ms);const s=Math.floor(ms/1000);return `${Math.floor(s/3600)}:${String(Math.floor(s/60)%60).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`};
  const tickF=()=>{const left=st.end-Date.now();$('exT').textContent=fmt(left);$('exT').classList.toggle('low',left<600000);if(left<=0)submit(true)};
  function submit(auto){
    if(!auto){const un=Q.filter((_,i)=>!has(i)).length;if(!confirm(`Submit the test?${un?`\n${un} question(s) not answered.`:''}`))return}
    clearInterval(timer);save();
    const at=grade(t,st);delete t.progress;t.attempts.push(at);const a=tests();a[a.findIndex(x=>x.id===t.id)]=t;saveTests(a);
    o.classList.remove('open');showResult(t,at,auto)}
  $('exSub').onclick=()=>submit(false);
  o.querySelector('[data-x]').onclick=()=>{save();clearInterval(timer);alert('Your attempt is saved. Tap Resume to continue. The timer keeps running.');closeOv(o)};
  timer=setInterval(tickF,1000);tickF();show(st.cur);
}

function correctOf(q,r){
  if(r===undefined||r==='') return null;
  if(q.type==='NAT'){const x=parseFloat(r);return isFinite(x)&&x>=q.range[0]-1e-9&&x<=q.range[1]+1e-9}
  return normLetters(r)===normLetters(q.answer)}
function grade(t,st){
  let score=0,neg=0,c=0,w=0,s=0;const per={};
  const items=t.questions.map((q,i)=>{const r=st.resp[i],ok=correctOf(q,r);let m=0;
    if(ok===null)s++;else if(ok){m=q.marks;c++}else{w++;if(q.type==='MCQ'){m=-q.marks/3;neg+=q.marks/3}}
    score+=m;const p=per[q.subject]=per[q.subject]||{max:0,got:0,att:0,cor:0,n:0};p.max+=q.marks;p.got+=m;p.n++;if(ok!==null)p.att++;if(ok)p.cor++;
    return {r:r??'',ok,m:+m.toFixed(2),time:Math.round((st.time[i]||0)/1000)}});
  const used=Math.min(Date.now(),st.end)-(st.end-t.mins*60000);
  return {date:new Date().toLocaleString(),score:+score.toFixed(2),neg:+neg.toFixed(2),correct:c,wrong:w,skipped:s,usedMs:used,per,items}}

/* ---------- results ---------- */
function showResult(t,at,auto){
  const o=overlay('resOv','Result · '+esc(t.name));const b=o.querySelector('.ovbody');o.querySelector('[data-x]').onclick=()=>closeOv(o);
  const acc=at.correct+at.wrong?Math.round(100*at.correct/(at.correct+at.wrong)):0;
  const mins=Math.round(at.usedMs/60000);
  const rows=Object.entries(at.per).map(([k,p])=>{const pct=p.max?p.got/p.max:0;return {k,p,pct}}).sort((a,b)=>a.pct-b.pct);
  const weak=rows.filter(r=>r.p.att&&r.pct<0.4).map(r=>NAME[r.k]);
  b.innerHTML=`<div class="res">${auto?'<p class="note">Time was up. Your test was submitted automatically.</p>':''}
  <div class="score"><div><b>${at.score} / ${t.maxMarks}</b><span>Marks</span></div><div><b>${at.correct}</b><span>Correct</span></div><div><b>${at.wrong}</b><span>Wrong</span></div><div><b>${at.skipped}</b><span>Skipped</span></div><div><b>−${at.neg}</b><span>Negative marks</span></div><div><b>${acc}%</b><span>Accuracy</span></div><div><b>${mins} min</b><span>Time used</span></div></div>
  <h2>Subject-wise</h2><table><tr><th>Subject</th><th>Marks</th><th>Correct / Attempted / Total</th><th></th></tr>
  ${rows.map(({k,p,pct})=>`<tr><td>${esc(NAME[k]||k)}</td><td>${+p.got.toFixed(2)} / ${p.max}</td><td>${p.cor} / ${p.att} / ${p.n}</td><td class="${!p.att?'':pct<0.4?'weak':pct>=0.7?'strong':''}">${!p.att?'Not attempted':pct<0.4?'Weak':pct>=0.7?'Strong':'Average'}</td></tr>`).join('')}</table>
  ${weak.length?`<p><b>Revise first:</b> ${weak.map(esc).join(', ')}. Use the Old papers section to practise these subjects' previous-year questions.</p>`:''}
  ${t.attempts.length>1?`<p class="note">Previous scores: ${t.attempts.slice(0,-1).map(a=>a.score).join(', ')}</p>`:''}
  <h2>Solutions</h2><div class="tabs" id="rf">${['All','Wrong','Skipped','Correct'].map((f,i)=>`<button class="chip" data-f="${f}" aria-pressed="${!i}">${f}</button>`).join('')}</div><div id="rlist"></div></div>`;
  const draw=f=>{$('rlist').innerHTML=t.questions.map((q,i)=>{const it=at.items[i];
    if(f==='Wrong'&&it.ok!==false||f==='Skipped'&&it.ok!==null||f==='Correct'&&it.ok!==true) return '';
    const tag=it.ok===null?'<span class="tag s">Skipped</span>':it.ok?'<span class="tag c">Correct</span>':'<span class="tag w">Wrong</span>';
    const key=q.type==='NAT'?`${q.answer} (accepted ${q.range[0]} to ${q.range[1]})`:q.answer;
    const opts=q.options?`<div class="opts">${q.options.map((op,j)=>{const L='ABCD'[j];return `<div class="${q.answer.includes(L)?'key':''} ${(it.r||'').includes(L)?'mine':''}">(${L}) ${rich(op)}</div>`}).join('')}</div>`:'';
    return `<div class="rq"><div class="top"><b style="color:var(--ink)">Q${i+1}</b>${tag}<span>${q.type} · ${q.marks}M</span><span>${it.m>0?'+':''}${it.m}</span><span>${esc(NAME[q.subject]||q.subject)}${q.topic?' · '+esc(q.topic):''}</span><span>${it.time}s</span>${q.unverified?'<span class="tag u" title="The checker solver got a different answer. Verify this key yourself.">Key not double-checked</span>':''}</div>
      <div class="qtext" style="font-size:15px">${rich(q.question)}</div>${q.figure?window.FIG.html(q.figure):''}${opts}
      <p style="margin:6px 0;font-size:14px">Your answer: <b>${esc(it.r||'—')}</b> · Correct: <b style="color:var(--ans)">${esc(key)}</b></p>
      <details ${it.ok===true?'':'open'}><summary>Solution</summary><div class="sol">${rich(q.solution)}</div></details></div>`}).join('')||'<div class="empty">None.</div>'};
  $('rf').onclick=e=>{const f=e.target.dataset.f;if(!f)return;[...$('rf').children].forEach(c=>c.setAttribute('aria-pressed',c.dataset.f===f));draw(f)};
  draw('All');
}
renderList();
window.__mock={blueprint,alloc,grade,agree,validQ};
window.__ai={claude,parseJSON,rich,esc,overlay,closeOv,settings,cfg:()=>cfg,LS};
})();
