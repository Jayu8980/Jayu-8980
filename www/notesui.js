/* GATE notes reader: subject cards, contents, search, per-section progress, practice links. */
(function(){
const A=window.__ai, esc=A.esc, LS=A.LS, $=id=>document.getElementById(id), N=window.NOTES||[];
const KEY={EM:'EM',EC:'EC',EMF:'EMF',SS:'SS',MAC:'MAC',PS:'PS',CS:'CS',MEAS:'MEAS',AD:'AD',PE:'PE'};
let done=LS.get('notesDone',{});
const prog=s=>{const t=s.s.length,d=s.s.filter((_,i)=>done[s.k+'#'+i]).length;return [d,t]};

const sec=document.createElement('section');
sec.innerHTML=`<div class="mockhead"><h2>GATE notes</h2><input type="search" id="nSearch" placeholder="Search all notes, e.g. Thevenin, slip, Routh" style="flex:1;min-width:200px;max-width:340px;padding:9px 12px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:var(--ink);font:inherit"></div>
 <p class="note" style="margin-top:-4px">Original GATE-focused notes: key formulas, concepts, shortcuts and common traps for every EE subject.</p>
 <div class="grid ngrid" id="nGrid"></div><div id="nHits"></div>`;
const anchor=[...document.querySelectorAll('#home h2')].find(h=>h.textContent==='Old papers');
anchor.parentNode.insertBefore(sec,anchor);

function cards(){ $('nGrid').innerHTML=N.map(s=>{const [d,t]=prog(s);return `<button class="card ncard" data-n="${s.k}"><div class="nk">${esc(s.n)}</div><div class="meta">${esc(s.w)} · ${t} topics</div>
  <div class="progress" style="margin:4px 0 0"><div style="width:${Math.round(100*d/t)}%"></div></div><div class="meta">${d}/${t} done</div></button>`}).join('')}
$('nGrid').onclick=e=>{const c=e.target.closest('[data-n]');if(c)open(c.dataset.n)};

// global search
const strip=h=>h.replace(/<[^>]+>/g,' ').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&').replace(/\s+/g,' ');
$('nSearch').oninput=()=>{const q=$('nSearch').value.trim().toLowerCase();if(q.length<2){$('nHits').innerHTML='';$('nGrid').hidden=false;return}
  const hits=[];N.forEach(s=>s.s.forEach((x,i)=>{const items=x.b.split(/<li>|<div class="trap">/).map(strip);items.forEach(t=>{const j=t.toLowerCase().indexOf(q);if(j>=0)hits.push({s,i,h:x.h,t})})}));
  $('nGrid').hidden=true;
  $('nHits').innerHTML=hits.length?`<p class="note">${hits.length} match${hits.length>1?'es':''}</p>`+hits.slice(0,60).map(h=>{const t=h.t.trim(),j=t.toLowerCase().indexOf(q),a=Math.max(0,j-90);
    return `<button class="mitem nhit" data-n="${h.s.k}" data-i="${h.i}"><div class="info"><div class="nm">${esc(h.s.n)} › ${esc(h.h)}</div><div class="meta">${a?'…':''}${esc(t.slice(a,j))}<mark>${esc(t.slice(j,j+q.length))}</mark>${esc(t.slice(j+q.length,j+q.length+140))}…</div></div></button>`}).join('')
    :'<div class="empty">No matches.</div>'};
$('nHits').onclick=e=>{const b=e.target.closest('[data-n]');if(b)open(b.dataset.n,+b.dataset.i,$('nSearch').value.trim())};

function open(k,goto,hl){
  const s=N.find(x=>x.k===k); if(!s) return;
  const o=A.overlay('nOv',esc(s.n),`<button class="iconbtn" id="nPractice">⚡ Practice test</button>`);
  o.querySelector('[data-x]').onclick=()=>{o.classList.remove('open');document.body.style.overflow='';cards()};
  const body=o.querySelector('.ovbody');
  body.innerHTML=`<div class="res notes"><p class="note">${esc(s.w)} in GATE EE. Tick each topic when you have revised it.</p>
   <div class="tabs">${s.s.map((x,i)=>`<button class="chip" data-j="${i}">${esc(x.h)}</button>`).join('')}</div>
   ${s.s.map((x,i)=>`<section class="nsec" id="ns${i}"><h3><label><input type="checkbox" data-d="${i}" ${done[k+'#'+i]?'checked':''}> ${esc(x.h)}</label></h3>${x.b}</section>`).join('')}
   <p class="note" style="margin-top:24px">These notes are a revision aid written for this app. For full theory, use your textbooks (add them under My books).</p></div>`;
  body.querySelector('.tabs').onclick=e=>{const j=e.target.dataset.j;if(j!==undefined)$('ns'+j).scrollIntoView({behavior:'smooth'})};
  body.onchange=e=>{const d=e.target.dataset.d;if(d===undefined)return;const key=k+'#'+d;if(e.target.checked)done[key]=1;else delete done[key];LS.set('notesDone',done)};
  if(hl){const re=new RegExp(hl.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'),'gi');body.querySelectorAll('.nsec li, .nsec .trap').forEach(el=>{const w=document.createTreeWalker(el,NodeFilter.SHOW_TEXT);const ns=[];while(w.nextNode())ns.push(w.currentNode);
    ns.forEach(n=>{if(!re.test(n.nodeValue))return;re.lastIndex=0;const span=document.createElement('span');span.innerHTML=esc(n.nodeValue).replace(re,m=>`<mark>${m}</mark>`);n.replaceWith(span)})})}
  if(goto!==undefined) setTimeout(()=>{const m=body.querySelector('mark')||$('ns'+goto);m&&m.scrollIntoView({block:'center'})},50);
  $('nPractice').onclick=()=>{o.classList.remove('open');$('genBtn').click();
    setTimeout(()=>{const g=$('genOv');if(!g||!g.classList.contains('open'))return;
      if(k==='GA'){return}
      const r=g.querySelector('[name=mode][value=subject]');if(r){r.checked=true;r.dispatchEvent(new Event('change'))}
      const c=g.querySelector(`#subjBox input[value="${KEY[k]}"]`);if(c)c.checked=true},50)};
}
cards();
window.NOTESUI={open};
})();
