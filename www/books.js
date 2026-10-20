/* My Books: import PDFs you own, stored on this device (IndexedDB), read offline with pdf.js.
   Any page can be sent to the Question Solver. */
(function(){
const A=window.__ai, esc=A.esc, $=id=>document.getElementById(id);
const SUBJECTS=['General','General Aptitude','Engineering Mathematics','Electric Circuits','Electromagnetic Fields','Signals and Systems','Electrical Machines','Power Systems','Control Systems','Measurements','Analog & Digital Electronics','Power Electronics'];

/* ---------- pdf.js loader ---------- */
let pdfReady=null;
function loadScript(src){return new Promise((res,rej)=>{const s=document.createElement('script');s.src=src;s.onload=res;s.onerror=()=>rej(new Error('Could not load '+src));document.head.appendChild(s)})}
function pdfjs(){ if(pdfReady) return pdfReady;
  pdfReady=(async()=>{ await loadScript('lib/pdfjs/pdf.min.js'); const L=window.pdfjsLib;
    if(location.protocol==='file:'){ await loadScript('lib/pdfjs/pdf.worker.min.js') } // no workers on file:// → main-thread parsing
    else L.GlobalWorkerOptions.workerSrc='lib/pdfjs/pdf.worker.min.js';
    return L})(); return pdfReady}
const openDoc=async data=>(await pdfjs()).getDocument({data,isEvalSupported:false,standardFontDataUrl:'lib/pdfjs/standard_fonts/'}).promise;

/* ---------- IndexedDB ---------- */
let dbP=null;
function db(){ if(dbP) return dbP; dbP=new Promise((res,rej)=>{const r=indexedDB.open('gee_books',1);
  r.onupgradeneeded=()=>{const d=r.result;d.createObjectStore('meta',{keyPath:'id'});d.createObjectStore('files')};
  r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error||new Error('Storage is not available'))}); return dbP}
async function tx(store,mode,fn){const d=await db();return new Promise((res,rej)=>{const t=d.transaction(store,mode),s=t.objectStore(store);let out;const r=fn(s);if(r)r.onsuccess=()=>out=r.result;t.oncomplete=()=>res(out);t.onerror=t.onabort=()=>rej(t.error||new Error('Storage error'))})}
const allMeta=()=>tx('meta','readonly',s=>s.getAll());
const putMeta=m=>tx('meta','readwrite',s=>s.put(m));
const getFile=id=>tx('files','readonly',s=>s.get(id));

/* ---------- home section ---------- */
const sec=document.createElement('section');
sec.innerHTML=`<div class="mockhead"><h2>My books</h2><label class="genbtn" style="cursor:pointer">＋ Add book (PDF)<input type="file" id="bkFile" accept="application/pdf,.pdf" multiple hidden></label></div>
 <p class="note" style="margin-top:-4px">Add PDFs of books or notes you own. They are stored only on this device and open offline.</p>
 <div class="tabs" id="bkTabs"></div><div class="mlist" id="bkList"></div>`;
const anchor=[...document.querySelectorAll('#home h2')].find(h=>h.textContent==='Old papers');
anchor.parentNode.insertBefore(sec,anchor);
let filt='All';
async function render(){
  let list=[]; try{list=await allMeta()}catch(e){$('bkList').innerHTML=`<div class="empty err">${esc(e.message)}</div>`;return}
  const subs=['All',...SUBJECTS.filter(s=>list.some(b=>b.subject===s))];
  $('bkTabs').innerHTML=list.length?subs.map(s=>`<button class="chip" data-f="${esc(s)}" aria-pressed="${s===filt}">${esc(s)}</button>`).join(''):'';
  list.sort((a,b)=>(b.opened||b.added)-(a.opened||a.added));
  const shown=list.filter(b=>filt==='All'||b.subject===filt);
  $('bkList').innerHTML=shown.length?shown.map(b=>`<div class="mitem"><div class="info"><div class="nm">${esc(b.name)}</div><div class="meta">${esc(b.subject)} · ${b.pages} pages · ${(b.size/1048576).toFixed(1)} MB${b.last>1?` · <span class="badge prog">Page ${b.last}</span>`:''}${(b.marks||[]).length?` · 🔖 ${b.marks.length}`:''}</div></div>
     <button class="btn pri" data-bo="${b.id}">${b.last>1?'Continue':'Open'}</button><button class="btn" data-be="${b.id}" title="Change subject / rename">✎</button><button class="btn" data-bd="${b.id}" title="Delete">✕</button></div>`).join('')
    :'<div class="empty">No books yet. Tap <b>＋ Add book</b> to import a PDF.</div>'}
$('bkTabs').onclick=e=>{const f=e.target.dataset.f;if(f){filt=f;render()}};
$('bkFile').onchange=async e=>{const files=[...e.target.files];e.target.value='';
  if(!files.length) return; const subject=await pickSubject(files); if(subject===null) return;
  $('bkList').insertAdjacentHTML('afterbegin','<div class="empty" id="bkBusy">Adding book… large PDFs take a few seconds.</div>');
  for(const f of files){ try{
    const buf=await f.arrayBuffer(); let pages=0;
    try{const d=await openDoc(buf.slice(0));pages=d.numPages;d.destroy()}catch(err){alert(`${f.name} could not be opened as a PDF.`);continue}
    const id='b'+Date.now()+Math.random().toString(36).slice(2,6);
    await tx('files','readwrite',s=>s.put(new Blob([buf],{type:'application/pdf'}),id));
    await putMeta({id,name:f.name.replace(/\.pdf$/i,''),subject:SUBJECTS[subject]||'General',pages,size:f.size,added:Date.now(),last:1,marks:[]});
    try{navigator.storage&&navigator.storage.persist&&navigator.storage.persist()}catch(_){}
  }catch(err){alert(/quota/i.test(err.message||err.name)?'Not enough storage space on this device for that book.':'Could not add '+f.name+': '+(err.message||err))} }
  render()};
$('bkList').onclick=async e=>{const d=e.target.closest('button')?.dataset||{};
  if(d.bo) openBook(d.bo);
  if(d.bd&&confirm('Delete this book from the app? (Your original file is not affected.)')){await tx('meta','readwrite',s=>s.delete(d.bd));await tx('files','readwrite',s=>s.delete(d.bd));render()}
  if(d.be){const m=(await allMeta()).find(x=>x.id===d.be);const n=prompt('Book name:',m.name);if(n===null)return;
    const sj=prompt(`Subject:\n${SUBJECTS.map((s,i)=>`${i}. ${s}`).join('\n')}`,String(Math.max(0,SUBJECTS.indexOf(m.subject))));
    m.name=n.trim()||m.name;if(sj!==null&&SUBJECTS[+sj])m.subject=SUBJECTS[+sj];await putMeta(m);render()}};

function pickSubject(files){return new Promise(res=>{const d=document.createElement('dialog');
  d.innerHTML=`<form method="dialog"><strong>Add ${files.length>1?files.length+' books':'book'}</strong><p class="note" style="word-break:break-word">${files.map(f=>esc(f.name)).join('<br>')}</p>
   <label for="bkSub">Subject</label><select id="bkSub" style="width:100%;padding:8px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--ink);font:inherit">${SUBJECTS.map((s,i)=>`<option value="${i}">${esc(s)}</option>`).join('')}</select>
   <div class="drow"><button class="btn" value="cancel">Cancel</button><button class="btn pri" value="ok">Add</button></div></form>`;
  document.body.appendChild(d);d.onclose=()=>{const v=d.returnValue==='ok'?+d.querySelector('select').value:null;d.remove();res(v)};d.showModal()})}

/* ---------- reader ---------- */
let doc=null, meta=null, zoom=1, io=null, renderIO=null;
async function openBook(id){
  meta=(await allMeta()).find(x=>x.id===id); const blob=await getFile(id); if(!meta||!blob){alert('Book file is missing.');return}
  const o=A.overlay('bkOv',esc(meta.name),`<span class="pg" id="bkPg"></span><input id="bkGo" type="number" min="1" placeholder="Go to" style="width:76px;padding:6px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--ink)">
    <button class="iconbtn" id="bkZo">−</button><button class="iconbtn" id="bkZi">+</button><button class="iconbtn" id="bkMark">🔖</button><button class="iconbtn" id="bkMarks">Bookmarks</button>`);
  o.querySelector('[data-x]').onclick=()=>{io&&io.disconnect();renderIO&&renderIO.disconnect();doc&&doc.destroy();doc=null;o.classList.remove('open');document.body.style.overflow='';render()};
  const body=o.querySelector('.ovbody'); body.innerHTML='<p class="note" style="padding:16px">Opening…</p>';
  try{doc=await openDoc(await blob.arrayBuffer())}catch(e){body.innerHTML=`<p class="note err" style="padding:16px">Could not open this PDF: ${esc(e.message)}</p>`;return}
  meta.opened=Date.now(); putMeta(meta);
  zoom=A.LS.get('bkZoom',1);
  const first=await doc.getPage(1), v=first.getViewport({scale:1}), ratio=v.height/v.width;
  let h=''; for(let i=1;i<=doc.numPages;i++) h+=`<div class="bkp" data-p="${i}"><canvas></canvas></div><div class="pnum">${i} / ${doc.numPages} <button class="solvep" data-bs="${i}">🧠 Solve this page</button></div>`;
  body.innerHTML=`<div id="bkPages" style="padding:12px">${h}</div>`;
  const size=()=>{const w=Math.round((Math.min(body.clientWidth,900)-24)*zoom);body.querySelectorAll('.bkp').forEach(d=>{d.style.width=w+'px';d.style.height=Math.round(w*ratio)+'px';d.dataset.r=''})};
  size();
  const draw=async div=>{ if(div.dataset.r==='1'||!doc) return; div.dataset.r='1';
    try{const pg=await doc.getPage(+div.dataset.p), w=div.clientWidth, vp0=pg.getViewport({scale:1}), dpr=Math.min(2,window.devicePixelRatio||1);
      const vp=pg.getViewport({scale:w/vp0.width*dpr}), c=div.querySelector('canvas'); c.width=vp.width;c.height=vp.height;c.style.width='100%';c.style.height='auto';
      div.style.height=Math.round(w*vp0.height/vp0.width)+'px';
      await pg.render({canvasContext:c.getContext('2d'),viewport:vp}).promise}catch(e){div.dataset.r=''}};
  const start=meta.last||1; let ready=false;
  renderIO=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting)draw(e.target)}),{root:body,rootMargin:'600px 0px'});
  io=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting&&ready){const p=+e.target.dataset.p;$('bkPg').textContent=`${p}/${doc.numPages}`;if(meta.last!==p){meta.last=p;clearTimeout(io.t);io.t=setTimeout(()=>putMeta(meta),800)}}}),{root:body,threshold:.4});
  body.querySelectorAll('.bkp').forEach(d=>{renderIO.observe(d);io.observe(d)});
  const go=p=>{const el=body.querySelector(`.bkp[data-p="${Math.max(1,Math.min(doc.numPages,p))}"]`);el&&el.scrollIntoView()};
  go(start); setTimeout(()=>{go(start);ready=true;$('bkPg').textContent=`${start}/${doc.numPages}`},120);
  const rezoom=z=>{const p=meta.last;zoom=z;A.LS.set('bkZoom',z);size();body.querySelectorAll('.bkp').forEach(d=>{renderIO.unobserve(d);renderIO.observe(d)});setTimeout(()=>go(p),30)};
  $('bkZi').onclick=()=>rezoom(Math.min(3,zoom+.25)); $('bkZo').onclick=()=>rezoom(Math.max(.5,zoom-.25));
  $('bkGo').onchange=e=>{go(+e.target.value);e.target.value=''};
  $('bkMark').onclick=()=>{const p=meta.last;meta.marks=meta.marks||[];const i=meta.marks.indexOf(p);if(i>=0)meta.marks.splice(i,1);else meta.marks.push(p);meta.marks.sort((a,b)=>a-b);putMeta(meta);
    $('bkMark').textContent=i>=0?'🔖':'🔖✓';setTimeout(()=>$('bkMark').textContent='🔖',1200)};
  $('bkMarks').onclick=()=>{const m=meta.marks||[];if(!m.length){alert('No bookmarks yet. Tap 🔖 on a page to bookmark it.');return}
    const p=prompt('Bookmarked pages: '+m.join(', ')+'\nType a page to open:',m[0]);if(p)go(+p)};
  body.onclick=async e=>{const b=e.target.closest('[data-bs]');if(!b)return;const p=+b.dataset.bs;
    b.disabled=true;b.textContent='Preparing…';
    try{const pg=await doc.getPage(p),vp0=pg.getViewport({scale:1}),sc=Math.min(2.5,2000/Math.max(vp0.width,vp0.height)),vp=pg.getViewport({scale:sc});
      const c=document.createElement('canvas');c.width=vp.width;c.height=vp.height;const x=c.getContext('2d');x.fillStyle='#fff';x.fillRect(0,0,c.width,c.height);
      await pg.render({canvasContext:x,viewport:vp}).promise;
      const url=c.toDataURL('image/jpeg',0.88),thumb=c.toDataURL('image/jpeg',0.4);
      window.SOLVER.openSolver({title:`${meta.name} · page ${p}`,cacheKey:`book:${meta.id}#${p}`,parts:[{name:`page ${p}`,kind:'img',thumb,block:{type:'image',source:{type:'base64',media_type:'image/jpeg',data:url.split(',')[1]}}}]})}
    catch(err){alert('Could not prepare this page: '+err.message)}
    b.disabled=false;b.textContent='🧠 Solve this page'};
}
render();
window.BOOKS={render,openBook};
})();
