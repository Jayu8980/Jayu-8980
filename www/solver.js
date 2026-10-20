/* Question Solver Agent
   Input: PDF / JPG / PNG / screenshot (upload, paste, camera) or an old-paper page.
   Two independent solvers work on the same input; if their final answers differ,
   a referee pass decides. Solutions are cached on the device for offline viewing. */
(function(){
const A=window.__ai, $=id=>document.getElementById(id), esc=A.esc, rich=A.rich, LS=A.LS;
const MAX_PDF=24*1024*1024;

const SOLVE_SYS=`You are an expert GATE Electrical Engineering tutor and solver (also strong in Engineering Mathematics and General Aptitude).
You receive one or more images or PDF pages containing exam questions (often scanned old GATE papers, photos or screenshots; options may be labelled (a)-(d) or (A)-(D)).
Read every question carefully, including figures, circuit diagrams, graphs and tables. Then solve it fully and correctly.
- If the student says which question(s) to solve, solve only those; otherwise solve every complete question visible.
- Skip instruction text, headers and cut-off fragments (mention them in "issue" only if the student asked for them).
- Re-check each answer by a second method or a sanity check (units, limiting case, option elimination) before finalising.
- If the image is unclear or a value is unreadable, state your assumption in "issue" and lower "confidence".
- Write maths in plain text with Unicode (√, π, ω, θ, ∫, Σ, ≤, ≥, °, Ω, μ, ∠) and the tags <sub> </sub> <sup> </sup> only. No LaTeX, no Markdown.
Return ONLY JSON:
{"questions":[{"label":"Q.12 (or 1.5, or Q1 if unnumbered)","read_as":"short restatement of the question with all given data, so the student can check you read it correctly (describe any figure in one line)","type":"MCQ|MSQ|NAT|Descriptive","final":"normalised final answer: option letter(s) like B or AC, or a number like 12.5 (no units), or a short expression","answer":"final answer in words with units, e.g. (B) 2.5 A","steps":"numbered step-by-step working, 4-15 short lines","concepts":"key concept / formula used","shortcut":"a faster GATE trick if one exists, else empty","confidence":"high|medium|low","issue":"empty, or what was unclear"}]}`;

const REF_SYS=`You are the senior examiner. Two solvers solved the same GATE question(s) from the attached image(s) and disagree.
Re-solve each disputed question yourself from the image, carefully and independently, then decide the correct answer. Use the same JSON format as the solvers:
{"questions":[{"label":"...","read_as":"...","type":"...","final":"...","answer":"...","steps":"...","concepts":"...","shortcut":"...","confidence":"high|medium|low","issue":"...","why":"one line: which solver was wrong and why"}]}
Return ONLY JSON.`;

/* ---------- input handling ---------- */
function fileToB64(file){return new Promise((res,rej)=>{const r=new FileReader();r.onload=()=>res(String(r.result).split(',')[1]);r.onerror=rej;r.readAsDataURL(file)})}
function imgToJpeg(src,maxSide){ // src: object URL or path → base64 jpeg, downscaled
  return new Promise((res,rej)=>{const im=new Image();im.onload=()=>{const k=Math.min(1,(maxSide||1800)/Math.max(im.naturalWidth,im.naturalHeight));
    const c=document.createElement('canvas');c.width=Math.round(im.naturalWidth*k);c.height=Math.round(im.naturalHeight*k);const x=c.getContext('2d');
    x.fillStyle='#fff';x.fillRect(0,0,c.width,c.height);x.drawImage(im,0,0,c.width,c.height);res({b64:c.toDataURL('image/jpeg',0.88).split(',')[1],thumb:c.toDataURL('image/jpeg',0.5)})};
    im.onerror=()=>rej(new Error('Could not read this image'));im.src=src})}
async function toPart(file){
  if(file.type==='application/pdf'||/\.pdf$/i.test(file.name)){
    if(file.size>MAX_PDF) throw new Error(`${file.name} is larger than 24 MB. Split it or screenshot the page instead.`);
    return {name:file.name,kind:'pdf',block:{type:'document',source:{type:'base64',media_type:'application/pdf',data:await fileToB64(file)}}}}
  if(/^image\//.test(file.type)||/\.(jpe?g|png|webp|gif|heic|bmp)$/i.test(file.name)){
    const url=URL.createObjectURL(file); try{const j=await imgToJpeg(url);return {name:file.name||'screenshot',kind:'img',thumb:j.thumb,block:{type:'image',source:{type:'base64',media_type:'image/jpeg',data:j.b64}}}} finally{URL.revokeObjectURL(url)}}
  throw new Error(`${file.name}: only PDF, JPG, PNG or screenshots are supported`)}

/* ---------- agent ---------- */
function norm(f){const s=String(f??'').trim();if(/^[\(\[]?[A-Da-d]([,\s&]*[A-Da-d])*[\)\]]?$/.test(s.replace(/\s*(and|,)\s*/g,'')))return [...new Set(s.toUpperCase().replace(/[^A-D]/g,''))].sort().join('');
  const n=parseFloat(s.replace(/[^\d.eE+\-]/g,''));return /^[^A-Za-z]*-?\d/.test(s)&&isFinite(n)?n:s.toLowerCase().replace(/\s+/g,'')}
function same(a,b){const x=norm(a),y=norm(b);if(typeof x==='number'&&typeof y==='number')return Math.abs(x-y)<=Math.max(Math.abs(x)*0.02,0.011);return x===y}
const key=l=>String(l||'').toLowerCase().replace(/[^0-9a-z.]/g,'').replace(/^q\.?/,'');

async function solve(parts,note,log){
  const cfg=A.cfg(); if(!cfg.key) throw new Error('Add your API key in AI settings first.');
  const content=[...parts.map(p=>p.block),{type:'text',text:(note?`Student's request: ${note}\n`:'')+'Solve the question(s) in the attached file(s).'}];
  const ask=()=>A.claude(SOLVE_SYS,content,20000).then(t=>A.parseJSON(t).questions||[]);
  if(!cfg.verify){log('🧠 Reading and solving…');const q=await ask();q.forEach(x=>x.check='single');return q}
  log('🧠 Two independent solvers are reading and solving…');
  const [s1,s2]=await Promise.all([ask(),ask().catch(()=>null)]);
  if(!s2){log('⚠ Second solver failed; showing the first solution only.');s1.forEach(x=>x.check='single');return s1}
  const m2={};s2.forEach(q=>m2[key(q.label)]=q);
  const disputed=[];
  s1.forEach(q=>{const o=m2[key(q.label)];if(!o){q.check='single';return} if(same(q.final,o.final)){q.check='agree'} else {q.check='dispute';q.alt=o;disputed.push(q)}});
  log(`   ${s1.length-disputed.length}/${s1.length} answers matched${disputed.length?`; ${disputed.length} disagree → asking a referee`:''}`);
  if(disputed.length){
    const msg=[...parts.map(p=>p.block),{type:'text',text:(note?`Student's request: ${note}\n`:'')+'Disputed questions:\n'+disputed.map(q=>`${q.label}\nSolver 1: ${JSON.stringify({final:q.final,answer:q.answer,steps:q.steps})}\nSolver 2: ${JSON.stringify({final:q.alt.final,answer:q.alt.answer,steps:q.alt.steps})}`).join('\n\n')}];
    try{const r=A.parseJSON(await A.claude(REF_SYS,msg,16000)).questions||[];
      r.forEach(x=>{const i=s1.findIndex(q=>key(q.label)===key(x.label));if(i>=0){const agreeWith=same(x.final,s1[i].final)?1:same(x.final,s1[i].alt.final)?2:0;
        s1[i]=Object.assign(x,{check:'referee',agreeWith,alt:s1[i].alt,first:{final:s1[i].final,answer:s1[i].answer}})}});
      log('   ⚖ Referee decided the disputed answers.')}
    catch(e){if(/API key|internet/.test(e.message))throw e;log('   ⚠ Referee failed: '+e.message)}
  }
  return s1}

/* ---------- rendering ---------- */
const CHECK={agree:['c','✓ 2 solvers agree'],referee:['u','⚖ Solvers disagreed — referee decided'],dispute:['w','⚠ Solvers disagree — check carefully'],single:['s','Single solution']};
function card(q){const ck=CHECK[q.check]||CHECK.single;
  return `<div class="rq"><div class="top"><b style="color:var(--ink);font-size:15px">${esc(q.label||'Question')}</b>${q.type?`<span>${esc(q.type)}</span>`:''}<span class="tag ${ck[0]}">${ck[1]}</span>${q.confidence?`<span>Confidence: ${esc(q.confidence)}</span>`:''}</div>
  ${q.read_as?`<p class="note" style="margin:4px 0 8px">Read as: ${rich(q.read_as)}</p>`:''}
  <div class="ans">Answer: ${rich(q.answer||q.final)}</div>
  ${q.check==='dispute'&&q.alt?`<p class="note">Other solver got: ${rich(q.alt.answer||q.alt.final)}</p>`:''}
  ${q.check==='referee'&&q.why?`<p class="note">${rich(q.why)}</p>`:''}
  <div class="sol">${rich(q.steps)}</div>
  ${q.concepts?`<p style="margin:8px 0 0;font-size:14px"><b>Concept:</b> ${rich(q.concepts)}</p>`:''}
  ${q.shortcut?`<p style="margin:6px 0 0;font-size:14px"><b>GATE shortcut:</b> ${rich(q.shortcut)}</p>`:''}
  ${q.issue?`<p class="note" style="color:var(--warn)">Note: ${rich(q.issue)}</p>`:''}</div>`}

/* ---------- UI ---------- */
function openSolver(pre){ // pre: {parts,title,cacheKey,note}
  const o=A.overlay('solOv',pre?.title?esc(pre.title):'Question solver');const b=o.querySelector('.ovbody');
  o.querySelector('[data-x]').onclick=()=>{busy=false;o.classList.remove('open');if(!$('viewer').classList.contains('open'))document.body.style.overflow=''};
  let parts=pre?.parts?[...pre.parts]:[], busy=false;
  b.innerHTML=`<div class="gen">
   ${pre?'<div class="thumbs" id="thumbs" style="margin:0 0 12px"></div>':`<fieldset><legend>Your question</legend>
     <div class="drop" id="drop">Drop a PDF / JPG / PNG here, or paste a screenshot (Ctrl+V)
     <div class="srow"><label class="btn pri">📁 Choose files<input type="file" id="sFile" accept="image/*,application/pdf" multiple hidden></label>
     <label class="btn">📷 Take photo<input type="file" id="sCam" accept="image/*" capture="environment" hidden></label></div></div>
     <div class="thumbs" id="thumbs"></div></fieldset>`}
   <fieldset><legend>Which question? (optional)</legend><input type="text" id="sNote" placeholder="e.g. only Q.12   ·   page 3, Q.5 to Q.8   ·   explain option C" value="${esc(pre?.note||'')}"></fieldset>
   <button class="genbtn" id="sGo" style="width:100%">🧠 Solve</button>
   <p class="note">Uses ${esc(A.cfg().model)}${A.cfg().verify?'. Two independent solvers check each other, and a referee settles any disagreement':''}. Needs internet; solutions are saved for offline viewing.</p>
   <div id="sLog" class="log" hidden></div><div id="sRes"></div></div>`;
  const showThumbs=()=>{const t=$('thumbs');if(t)t.innerHTML=parts.map((p,i)=>`<div class="th">${p.thumb?`<img src="${p.thumb}" alt="">`:'<div class="pdf">PDF</div>'}<span>${esc(p.name)}</span>${pre?'':`<button data-rm="${i}" aria-label="Remove">✕</button>`}</div>`).join('')};
  async function addFiles(files){for(const f of files){try{parts.push(await toPart(f))}catch(e){alert(e.message)}}showThumbs()}
  if(!pre){$('sFile').onchange=e=>{addFiles([...e.target.files]);e.target.value=''};$('sCam').onchange=e=>{addFiles([...e.target.files]);e.target.value=''};
    $('thumbs').onclick=e=>{const i=e.target.dataset.rm;if(i!==undefined){parts.splice(+i,1);showThumbs()}};
    const d=$('drop');d.ondragover=e=>{e.preventDefault();d.classList.add('over')};d.ondragleave=()=>d.classList.remove('over');
    d.ondrop=e=>{e.preventDefault();d.classList.remove('over');addFiles([...e.dataTransfer.files])}}
  o.onpaste=e=>{if(pre)return;const fs=[...(e.clipboardData?.items||[])].filter(i=>i.kind==='file').map(i=>i.getAsFile()).filter(Boolean);if(fs.length){e.preventDefault();addFiles(fs)}};
  window.__solPaste=o.onpaste;
  const cacheKey=()=>pre?.cacheKey?pre.cacheKey+'|'+$('sNote').value.trim().toLowerCase():null;
  const cached=cacheKey()&&LS.get('solved',{})[cacheKey()];
  if(cached){$('sRes').innerHTML=`<p class="note">Saved solution (${esc(cached.date)}). <button class="iconbtn" id="reSolve">Solve again</button></p>`+cached.qs.map(card).join('');$('reSolve').onclick=()=>run(true)}
  async function run(){
    if(!A.cfg().key){A.settings();return}
    if(!parts.length){alert('Add a PDF, image or screenshot first.');return}
    busy=true;$('sGo').disabled=true;$('sGo').textContent='Solving…';const L=$('sLog');L.hidden=false;L.textContent='';L.classList.remove('err');$('sRes').innerHTML='';
    const log=m=>{L.textContent+=m+'\n'};
    try{const qs=await solve(parts,$('sNote').value.trim(),log); if(!busy)return;
      if(!qs.length) throw new Error('No complete question found. Try a clearer or closer image, or say which question to solve.');
      log(`Done: ${qs.length} question(s) solved.`);
      $('sRes').innerHTML=qs.map(card).join('');
      const k=cacheKey(); if(k){const all=LS.get('solved',{});all[k]={date:new Date().toLocaleString(),qs};const ks=Object.keys(all);if(ks.length>150)delete all[ks[0]];LS.set('solved',all)}
      const h=LS.get('solveHist',[]);h.push({date:new Date().toLocaleString(),title:pre?.title||parts.map(p=>p.name).join(', '),qs});while(h.length>40)h.shift();
      if(!LS.set('solveHist',h)){h.splice(0,10);LS.set('solveHist',h)}
      renderHist();
    }catch(e){log('✕ '+e.message);L.classList.add('err')}
    busy=false;$('sGo').disabled=false;$('sGo').textContent='🧠 Solve again'}
  $('sGo').onclick=()=>run();
  if(pre&&!cached&&pre.auto) run();
  showThumbs();
}

/* old-paper page → solver */
async function solvePage(paper,page){
  try{const j=await imgToJpeg(`pages/${paper.id}/${page}.jpg`,2000);
    openSolver({title:`${paper.title} · page ${page}`,cacheKey:`${paper.id}#${page}`,parts:[{name:`page ${page}`,kind:'img',thumb:j.thumb,block:{type:'image',source:{type:'base64',media_type:'image/jpeg',data:j.b64}}}]})}
  catch(e){alert(e.message)}}
document.getElementById('pages').addEventListener('click',e=>{const b=e.target.closest('.solvep');if(!b)return;
  const title=$('vt').textContent, paper=(window.PAPERS||[]).find(p=>p.title===title); if(paper) solvePage(paper,+b.dataset.sp)});

/* home section */
const sec=document.createElement('section');
sec.innerHTML=`<div class="mockhead"><h2>Question solver</h2><button class="genbtn" id="solveBtn">📷 Solve a question</button></div>
 <p class="note" style="margin-top:-4px">Upload a PDF, photo or screenshot of any question, or tap <b>🧠 Solve this page</b> on any old-paper page.</p><div class="mlist" id="sHist"></div>`;
const anchor=[...document.querySelectorAll('#home h2')].find(h=>h.textContent==='Old papers');
anchor.parentNode.insertBefore(sec,anchor);
$('solveBtn').onclick=()=>openSolver();
function renderHist(){const h=LS.get('solveHist',[]);
  $('sHist').innerHTML=h.slice(-5).reverse().map((x,i)=>`<div class="mitem"><div class="info"><div class="nm">${esc(x.title)}</div><div class="meta">${esc(x.date)} · ${x.qs.length} question(s) · ${x.qs.map(q=>esc(q.label)+': '+esc(String(q.final??'')).slice(0,14)).join(' · ')}</div></div><button class="btn" data-sh="${h.length-1-i}">Open</button></div>`).join('')}
$('sHist').onclick=e=>{const i=e.target.dataset.sh;if(i===undefined)return;const x=LS.get('solveHist',[])[+i];if(!x)return;
  const o=A.overlay('solOv',esc(x.title));o.querySelector('[data-x]').onclick=()=>{o.classList.remove('open');document.body.style.overflow=''};
  o.querySelector('.ovbody').innerHTML=`<div class="gen"><p class="note">${esc(x.date)}</p>${x.qs.map(card).join('')}</div>`};
renderHist();
document.addEventListener('paste',e=>{const o=$('solOv');if(o&&o.classList.contains('open')&&window.__solPaste)window.__solPaste(e)});
window.SOLVER={openSolver,solve,same,norm};
})();
