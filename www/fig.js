/* Figure renderer for AI-generated questions.
   The AI returns a JSON drawing spec; this file validates it and draws SVG itself
   (no model-written markup is ever inserted). Kinds: circuit, graph, phasor, table. */
(function(){
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const num=v=>typeof v==='number'&&isFinite(v);
const pt=p=>Array.isArray(p)&&p.length===2&&num(p[0])&&num(p[1])&&Math.abs(p[0])<=40&&Math.abs(p[1])<=40;
const f1=n=>Math.round(n*10)/10;

// SVG text with <sub>/<sup> support → tspans
function svgText(x,y,s,anchor,size,extra){
  size=size||13; const parts=String(s??'').split(/(<\/?(?:sub|sup)>)/); let out='', mode='';
  for(const p of parts){ if(p==='<sub>'){mode='sub';continue} if(p==='<sup>'){mode='sup';continue} if(/^<\//.test(p)){mode='';continue} if(!p)continue;
    const t=esc(p.replace(/<[^>]*>/g,''));
    out+= mode?`<tspan baseline-shift="${mode==='sub'?'-25%':'40%'}" font-size="${Math.round(size*.72)}">${t}</tspan>`:`<tspan>${t}</tspan>`}
  return `<text x="${f1(x)}" y="${f1(y)}" font-size="${size}" text-anchor="${anchor||'middle'}" dominant-baseline="middle" fill="currentColor" stroke="none" ${extra||''}>${out}</text>`}

/* ---------------- safe expression evaluator ---------------- */
const FN={sin:Math.sin,cos:Math.cos,tan:Math.tan,asin:Math.asin,acos:Math.acos,atan:Math.atan,sinh:Math.sinh,cosh:Math.cosh,tanh:Math.tanh,
  exp:Math.exp,ln:Math.log,log:Math.log,log10:Math.log10,sqrt:Math.sqrt,abs:Math.abs,sign:Math.sign,sgn:Math.sign,floor:Math.floor,ceil:Math.ceil,
  u:x=>x>=0?1:0,step:x=>x>=0?1:0,rect:x=>Math.abs(x)<=0.5?1:0,tri:x=>Math.max(0,1-Math.abs(x)),
  min:Math.min,max:Math.max,pow:Math.pow,mod:(a,b)=>((a%b)+b)%b,atan2:Math.atan2};
const CONST={pi:Math.PI,e:Math.E};
function compile(src){
  const s=String(src).replace(/\*\*/g,'^').replace(/π/g,'pi').replace(/ω/g,'w').replace(/−/g,'-');
  if(s.length>300) throw Error('expr too long');
  const tok=s.match(/\d*\.?\d+(?:e[+-]?\d+)?|[A-Za-z_][A-Za-z_0-9]*|[-+*/^(),]|\S/g)||[]; let i=0;
  const peek=()=>tok[i], next=()=>tok[i++];
  function prim(){const t=next(); if(t===undefined) throw Error('end');
    if(/^\d|^\./.test(t)){const v=parseFloat(t);return ()=>v}
    if(t==='('){const e=add();if(next()!==')')throw Error(')');return e}
    if(t==='-'){const e=pw();return x=>-e(x)} if(t==='+') return pw();
    if(/^[A-Za-z_]/.test(t)){const k=t.toLowerCase();
      if(peek()==='('){ if(!FN[k]) throw Error('fn '+t); next(); const args=[]; if(peek()!==')'){args.push(add());while(peek()===','){next();args.push(add())}}
        if(next()!==')')throw Error(')'); const f=FN[k]; return x=>f(...args.map(a=>a(x)))}
      if(k in CONST){const v=CONST[k];return ()=>v}
      if(['x','t','w','f','s','theta','v','i','n'].includes(k)) return x=>x;
      throw Error('name '+t)}
    throw Error('token '+t)}
  function pw(){const b=prim(); if(peek()==='^'){next();const e=unary();return x=>Math.pow(b(x),e(x))} return b}
  function unary(){if(peek()==='-'){next();const e=unary();return x=>-e(x)} if(peek()==='+'){next();return unary()} return pw()}
  function mul(){let a=unary(); while(peek()==='*'||peek()==='/'||(peek()&&/^[A-Za-z(\d]/.test(peek()))){const op=/[*\/]/.test(peek())?next():'*';const b=unary(),l=a;a=op==='*'?x=>l(x)*b(x):x=>l(x)/b(x)} return a}
  function add(){let a=mul(); while(peek()==='+'||peek()==='-'){const op=next(),b=mul(),l=a;a=op==='+'?x=>l(x)+b(x):x=>l(x)-b(x)} return a}
  const e=add(); if(i<tok.length) throw Error('extra '+tok[i]); return e}

/* ---------------- circuit / block diagram ---------------- */
const U=56;
const plainLen=t=>String(t||'').replace(/<[^>]*>/g,'').length;
const TWO=new Set(['R','L','C','V','I','Vac','Vdep','Idep','Z','D','sw','meter','xfmr','cb','wire','bus','arrow','gen']);
const POINT=new Set(['dot','terminal','ground','text','opamp','block','sum','load']);
function circuitSVG(sp){ let FS=15; const T=(x,y,t,a)=>svgText(x,y,t,a,FS);
  const els=sp.elements, P=p=>[p[0]*U,p[1]*U];
  let xs=[],ys=[]; const add=(x,y)=>{xs.push(x);ys.push(y)};
  els.forEach(e=>{ if(e.points) e.points.forEach(p=>add(...p)); if(e.from){add(...e.from);add(...e.to)}
    if(e.at){const w=e.type==='opamp'?1.5:(e.w||1.6)/2, h=e.type==='opamp'?0.75:(e.h||0.8)/2; add(e.at[0]-(e.type==='opamp'?0:w),e.at[1]-h);add(e.at[0]+w,e.at[1]+h)}});
  const pad=0.9, px=els.some(e=>e.label||e.text)?1.5:pad, minx=Math.min(...xs)-px, maxx=Math.max(...xs)+px, miny=Math.min(...ys)-pad, maxy=Math.max(...ys)+pad+0.2;
  const VW=(maxx-minx)*U; FS=Math.max(15,Math.min(22,VW/27)); const SW=Math.max(1.6,Math.min(2.6,VW/300));
  let g='';
  const sign=(x,y,c)=>{const s=4.5;return c==='+'?`<path d="M${f1(x-s)} ${f1(y)}h${2*s}M${f1(x)} ${f1(y-s)}v${2*s}"/>`:`<path d="M${f1(x-s)} ${f1(y)}h${2*s}"/>`};
  for(const e of els){
    const t=e.type;
    if(t==='wire'&&e.points){g+=`<polyline fill="none" points="${e.points.map(p=>P(p).map(f1).join(',')).join(' ')}"${e.dashed?' stroke-dasharray="5 4"':''}/>`;continue}
    if(TWO.has(t)){
      const [x1,y1]=P(e.from),[x2,y2]=P(e.to),dx=x2-x1,dy=y2-y1,len=Math.hypot(dx,dy); if(len<1) continue;
      const ang=Math.atan2(dy,dx)*180/Math.PI, mx=(x1+x2)/2, my=(y1+y2)/2, ux=dx/len, uy=dy/len;
      const G=(lx,ly)=>[mx+lx*ux-ly*uy, my+lx*uy+ly*ux];
      let h=0, sym='';
      const r=14;
      switch(t){
        case 'wire': h=0; break;
        case 'bus': g+=`<line x1="${f1(x1)}" y1="${f1(y1)}" x2="${f1(x2)}" y2="${f1(y2)}" stroke-width="5"/>`; h=-1; break;
        case 'arrow': g+=`<line x1="${f1(x1)}" y1="${f1(y1)}" x2="${f1(x2)}" y2="${f1(y2)}" marker-end="url(#ah)"/>`; h=-1; break;
        case 'R': h=Math.min(22,len/2-2); {let d=`M${-h} 0`;const n=6,st=2*h/n;for(let k=0;k<n;k++)d+=` L${f1(-h+st*(k+.5))} ${k%2?7:-7}`;sym=`<path fill="none" d="${d} L${h} 0"/>`} break;
        case 'L': h=Math.min(22,len/2-2); {const n=4,w=2*h/n;let d=`M${-h} 0`;for(let k=0;k<n;k++)d+=` a${f1(w/2)} ${f1(w/2)} 0 0 1 ${f1(w)} 0`;sym=`<path fill="none" d="${d}"/>`} break;
        case 'C': h=4; sym=`<path d="M-4 -12V12M4 -12V12" stroke-width="2.2"/>`; break;
        case 'V': case 'gen': case 'Vac': case 'meter': case 'I': h=r; sym=`<circle r="${r}" fill="var(--fig-bg,none)"/>`;
          if(t==='I') sym+=`<path d="M-8 0H6" marker-end="url(#ah)"/>`;
          if(t==='Vac'||t==='gen') sym+=`<path fill="none" d="M-8 0q4 -10 8 0t8 0"/>`; break;
        case 'Vdep': case 'Idep': h=r; sym=`<path d="M-14 0L0 -14L14 0L0 14Z" fill="var(--fig-bg,none)"/>`; if(t==='Idep') sym+=`<path d="M-7 0H5" marker-end="url(#ah)"/>`; break;
        case 'Z': h=Math.min(18,len/2-2); sym=`<rect x="${-h}" y="-7" width="${2*h}" height="14" fill="var(--fig-bg,none)"/>`; break;
        case 'D': h=9; sym=`<path d="M-9 -9L-9 9L8 0Z" fill="currentColor"/><path d="M9 -9V9" stroke-width="2"/>`; break;
        case 'sw': h=12; sym=`<circle cx="-12" r="2.5" fill="currentColor"/><circle cx="12" r="2.5" fill="currentColor"/><path d="M-12 0L9 -12"/>`; break;
        case 'xfmr': h=16; sym=`<circle cx="-6" r="10" fill="none"/><circle cx="6" r="10" fill="none"/>`; break;
        case 'cb': h=7; sym=`<rect x="-7" y="-7" width="14" height="14" fill="var(--fig-bg,none)"/>`; break;
      }
      if(h>=0){
        if(h===0) g+=`<line x1="${f1(x1)}" y1="${f1(y1)}" x2="${f1(x2)}" y2="${f1(y2)}"${e.dashed?' stroke-dasharray="5 4"':''}/>`;
        else{const [ax,ay]=G(-h,0),[bx,by]=G(h,0);
          g+=`<line x1="${f1(x1)}" y1="${f1(y1)}" x2="${f1(ax)}" y2="${f1(ay)}"/><line x1="${f1(bx)}" y1="${f1(by)}" x2="${f1(x2)}" y2="${f1(y2)}"/>`;
          g+=`<g transform="translate(${f1(mx)} ${f1(my)}) rotate(${f1(ang)})">${sym}</g>`}
      }
      if(t==='V'||t==='Vdep'){const [px,py]=G(6.5,0),[nx,ny]=G(-6.5,0);g+=sign(px,py,'+')+sign(nx,ny,'-')}
      if(t==='meter') g+=svgText(mx,my+1,e.meter||'V','middle',13,'font-weight="700"');
      if(e.label){ // label beside the element
        const horiz=Math.abs(dx)>=Math.abs(dy); let side=e.side==='b'?-1:1;
        const off=(['wire','arrow','bus'].includes(t)?FS*0.8:(h>12?h+FS*0.8:FS*1.3));
        if(horiz){g+=T(mx,my-side*off,e.label,'middle')}
        else{g+=T(mx+side*(off-4),my,e.label,side>0?'start':'end')}
      }
      continue}
    if(!e.at) continue;
    const [x,y]=P(e.at);
    switch(t){
      case 'dot': g+=`<circle cx="${f1(x)}" cy="${f1(y)}" r="3.2" fill="currentColor"/>`; break;
      case 'terminal': g+=`<circle cx="${f1(x)}" cy="${f1(y)}" r="3.6" fill="var(--fig-bg,#fff)"/>`; break;
      case 'ground': g+=`<path d="M${f1(x)} ${f1(y)}v10M${f1(x-11)} ${f1(y+10)}h22M${f1(x-7)} ${f1(y+15)}h14M${f1(x-3)} ${f1(y+20)}h6"/>`; break;
      case 'load': g+=`<path d="M${f1(x)} ${f1(y)}v22" marker-end="url(#ah)"/>`; break;
      case 'text': break;
      case 'opamp': g+=`<path d="M${f1(x)} ${f1(y-.75*U)}L${f1(x)} ${f1(y+.75*U)}L${f1(x+1.5*U)} ${f1(y)}Z" fill="var(--fig-bg,none)"/>`+sign(x+9,y-.5*U,'-')+sign(x+9,y+.5*U,'+'); break;
      case 'block':{const w=(e.w||1.6)*U,hh=(e.h||0.8)*U,bfs=Math.max(10,Math.min(FS,(w-10)/(Math.max(1,plainLen(e.text||e.label))*0.58)));g+=`<rect x="${f1(x-w/2)}" y="${f1(y-hh/2)}" width="${f1(w)}" height="${f1(hh)}" fill="var(--fig-bg,none)"/>`;g+=svgText(x,y,e.text||e.label||'','middle',bfs);break}
      case 'sum':{g+=`<circle cx="${f1(x)}" cy="${f1(y)}" r="11" fill="var(--fig-bg,none)"/>`;if(e.x)g+=`<path d="M${f1(x-7.8)} ${f1(y-7.8)}l15.6 15.6M${f1(x+7.8)} ${f1(y-7.8)}l-15.6 15.6"/>`;
        const D={w:[-17,-9],e:[17,-9],n:[9,-17],s:[9,17]};Object.entries(e.signs||{}).forEach(([k,v])=>{const d=D[k];if(d)g+=sign(x+d[0],y+d[1],/^[-−]/.test(v)?'-':'+')});break}
    }
    if(t==='text') g+=svgText(x,y,e.text||e.label||'',e.anchor||'middle',FS);
    else if(e.label&&t!=='block') g+=svgText(x+(t==='ground'||t==='load'?0:8),y+(t==='ground'?32:t==='load'?34:-12),e.label,t==='ground'||t==='load'?'middle':'start',FS);
  }
  const vb=[minx*U,miny*U,(maxx-minx)*U,(maxy-miny)*U].map(f1);
  return `<svg viewBox="${vb.join(' ')}" width="${f1(Math.min(640,VW))}" style="max-width:100%;height:auto" stroke="currentColor" stroke-width="${f1(SW)}" stroke-linecap="round" stroke-linejoin="round" fill="none" role="img" aria-label="Circuit figure">
  <defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="currentColor" stroke="none"/></marker></defs>${g}</svg>`}

/* ---------------- graph ---------------- */
function niceTicks(a,b,n){const span=b-a, raw=span/(n||5), mag=Math.pow(10,Math.floor(Math.log10(raw))), st=[1,2,2.5,5,10].map(k=>k*mag).find(s=>s>=raw)||raw;
  const out=[];for(let v=Math.ceil(a/st)*st;v<=b+st*1e-9;v+=st)out.push(+v.toFixed(10));return out}
const fmtN=v=>{if(v===0)return '0';const a=Math.abs(v);if(a>=1e4||a<1e-3)return v.toExponential(0).replace('e+','e');return String(+v.toPrecision(4))};
function graphSVG(sp){
  const W=420,H=266,ml=54,mr=18,mt=26,mb=44,pw=W-ml-mr,ph=H-mt-mb, X=sp.x, Y=sp.y, lg=!!(X.log||sp.log);
  const tx=v=>lg?Math.log10(v):v, x0=tx(X.min), x1=tx(X.max);
  const sx=v=>ml+(tx(v)-x0)/(x1-x0)*pw, sy=v=>mt+(Y.max-v)/(Y.max-Y.min)*ph;
  let g='';
  const ticks=(ax,isX)=>{ if(Array.isArray(ax.ticks)&&ax.ticks.length) return ax.ticks.map(t=>Array.isArray(t)?[+t[0],String(t[1])]:[+t,fmtN(+t)]).filter(t=>num(t[0]));
    if(isX&&lg){const o=[];for(let k=Math.ceil(Math.log10(ax.min));k<=Math.floor(Math.log10(ax.max));k++)o.push([Math.pow(10,k),fmtN(Math.pow(10,k))]);return o}
    return niceTicks(ax.min,ax.max,isX?6:5).map(v=>[v,fmtN(v)])};
  // grid & ticks
  ticks(X,true).forEach(([v,l])=>{if(v<X.min||v>X.max)return;const px=sx(v);g+=`<line x1="${f1(px)}" y1="${mt}" x2="${f1(px)}" y2="${mt+ph}" stroke-opacity=".14"/>`+svgText(px,mt+ph+13,l,'middle',12)});
  ticks(Y,false).forEach(([v,l])=>{if(v<Y.min||v>Y.max)return;const py=sy(v);g+=`<line x1="${ml}" y1="${f1(py)}" x2="${ml+pw}" y2="${f1(py)}" stroke-opacity=".14"/>`+svgText(ml-6,py,l,'end',12)});
  // axes: through zero when in range
  const ax0=Y.min<=0&&Y.max>=0?sy(0):mt+ph, ay0=!lg&&X.min<=0&&X.max>=0?sx(0):ml;
  g+=`<line x1="${ml}" y1="${f1(ax0)}" x2="${ml+pw+8}" y2="${f1(ax0)}" stroke-width="1.4" marker-end="url(#gah)"/><line x1="${f1(ay0)}" y1="${mt+ph}" x2="${f1(ay0)}" y2="${mt-8}" stroke-width="1.4" marker-end="url(#gah)"/>`;
  g+=svgText(ml+pw,H-8,X.label||'','end',12)+svgText(ml-48,9,Y.label||'','start',12);
  // series
  let body='';
  (sp.series||[]).forEach((s,si)=>{ let pts=[];
    if(s.expr){const f=compile(s.expr); const N=600; let seg=[];
      for(let k=0;k<=N;k++){const xv=lg?Math.pow(10,x0+(x1-x0)*k/N):X.min+(X.max-X.min)*k/N; let yv; try{yv=f(xv)}catch(e){yv=NaN}
        if(!isFinite(yv)||Math.abs(yv)>1e6){if(seg.length)pts.push(seg);seg=[];continue}
        const prev=seg[seg.length-1]; if(prev&&Math.abs(yv-prev[1])>(Y.max-Y.min)*1.5){pts.push(seg);seg=[]}
        seg.push([xv,yv])} if(seg.length)pts.push(seg)}
    else pts=[s.points.filter(p=>pt2(p))];
    const dash=s.dashed?' stroke-dasharray="6 4"':'';
    pts.forEach(seg=>{if(seg.length>1)body+=`<polyline points="${seg.map(p=>f1(sx(p[0]))+','+f1(sy(p[1]))).join(' ')}" stroke-width="2"${dash}/>`;});
    if(s.label){const last=(pts[pts.length-1]||[]).slice(-1)[0]; if(last) body+=svgText(Math.min(sx(last[0])+4,W-4),sy(last[1])-10,s.label,'end',12)}
  });
  (sp.marks||[]).forEach(m=>{if(!num(m.x)||!num(m.y))return;const px=sx(m.x),py=sy(m.y);
    body+=`<circle cx="${f1(px)}" cy="${f1(py)}" r="2.8" fill="currentColor"/>`;if(m.text)body+=svgText(px+5,py-10,m.text,'start',12)});
  (sp.vlines||[]).forEach(v=>{if(num(+v))body+=`<line x1="${f1(sx(+v))}" y1="${mt}" x2="${f1(sx(+v))}" y2="${mt+ph}" stroke-dasharray="4 4" stroke-opacity=".7"/>`});
  (sp.hlines||[]).forEach(v=>{if(num(+v))body+=`<line x1="${ml}" y1="${f1(sy(+v))}" x2="${ml+pw}" y2="${f1(sy(+v))}" stroke-dasharray="4 4" stroke-opacity=".7"/>`});
  const id='c'+Math.random().toString(36).slice(2,8);
  return `<svg viewBox="0 0 ${W} ${H}" width="${W}" style="max-width:100%;min-width:${Math.round(W*.8)}px;height:auto" stroke="currentColor" fill="none" stroke-width="1" role="img" aria-label="Graph figure">
  <defs><marker id="gah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0 0L10 5L0 10z" fill="currentColor" stroke="none"/></marker>
  <clipPath id="${id}"><rect x="${ml}" y="${mt-4}" width="${pw+4}" height="${ph+8}"/></clipPath></defs>${g}<g clip-path="url(#${id})">${body}</g></svg>`}
const pt2=p=>Array.isArray(p)&&p.length===2&&num(p[0])&&num(p[1]);

/* ---------------- phasor ---------------- */
function phasorSVG(sp){
  const V=sp.vectors, tips=[]; V.forEach((v,i)=>{const a=v.ang*Math.PI/180;const base=num(v.from)&&v.from<i?tips[v.from][1]:[0,0];
    tips.push([base,[base[0]+v.mag*Math.cos(a),base[1]+v.mag*Math.sin(a)]])});
  const all=tips.flat(); let mx=Math.max(...all.map(p=>Math.abs(p[0])),...all.map(p=>Math.abs(p[1])),1e-9);
  const minX=Math.min(0,...all.map(p=>p[0])),maxX=Math.max(0,...all.map(p=>p[0])),minY=Math.min(0,...all.map(p=>p[1])),maxY=Math.max(0,...all.map(p=>p[1]));
  const S=200/Math.max(maxX-minX,maxY-minY,mx*0.3), pad=40, W=(maxX-minX)*S+2*pad+40, H=(maxY-minY)*S+2*pad;
  const X=x=>pad+(x-minX)*S, Yv=y=>pad+(maxY-y)*S; let g=`<line x1="${f1(X(minX)-10)}" y1="${f1(Yv(0))}" x2="${f1(X(maxX)+30)}" y2="${f1(Yv(0))}" stroke-dasharray="4 4" stroke-opacity=".5"/>`;
  tips.forEach(([a,b],i)=>{const v=V[i];g+=`<line x1="${f1(X(a[0]))}" y1="${f1(Yv(a[1]))}" x2="${f1(X(b[0]))}" y2="${f1(Yv(b[1]))}" stroke-width="2" marker-end="url(#pah)"${v.dashed?' stroke-dasharray="6 4"':''}/>`;
    if(v.label){const ang=Math.atan2(b[1]-a[1],b[0]-a[0]);g+=svgText(X(b[0])+Math.cos(ang)*14-Math.sin(ang)*9,Yv(b[1])-Math.sin(ang)*14-Math.cos(ang)*9,v.label,'middle',13)}});
  (sp.angles||[]).forEach(an=>{if(!num(an.from)||!num(an.to))return;const r=34,a1=an.from*Math.PI/180,a2=an.to*Math.PI/180;
    g+=`<path d="M${f1(X(0)+r*Math.cos(a1))} ${f1(Yv(0)-r*Math.sin(a1))}A${r} ${r} 0 0 ${a2>a1?0:1} ${f1(X(0)+r*Math.cos(a2))} ${f1(Yv(0)-r*Math.sin(a2))}"/>`;
    if(an.label){const m=(a1+a2)/2;g+=svgText(X(0)+(r+12)*Math.cos(m),Yv(0)-(r+12)*Math.sin(m),an.label,'middle',12)}});
  return `<svg viewBox="0 0 ${f1(W)} ${f1(H)}" width="${f1(Math.min(W,460))}" style="max-width:100%;height:auto" stroke="currentColor" fill="none" stroke-width="1.4" role="img" aria-label="Phasor diagram">
  <defs><marker id="pah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="currentColor" stroke="none"/></marker></defs>${g}</svg>`}

/* ---------------- table ---------------- */
const cell=s=>esc(s).replace(/&lt;(\/?)(sub|sup|b)&gt;/g,'<$1$2>');
function tableHTML(sp){return `<table class="ftab"><tr>${sp.headers.map(h=>`<th>${cell(h)}</th>`).join('')}</tr>${sp.rows.map(r=>`<tr>${r.map(c=>`<td>${cell(c)}</td>`).join('')}</tr>`).join('')}</table>`}

/* ---------------- validation ---------------- */
function validate(sp){
  try{
    if(!sp||typeof sp!=='object') return 'not an object';
    if(sp.kind==='circuit'){
      if(!Array.isArray(sp.elements)||!sp.elements.length||sp.elements.length>90) return 'elements';
      for(const e of sp.elements){
        if(!e||typeof e.type!=='string') return 'element type';
        if(e.type==='wire'&&e.points){if(!Array.isArray(e.points)||e.points.length<2||e.points.length>30||!e.points.every(pt))return 'wire points';continue}
        if(TWO.has(e.type)){if(!pt(e.from)||!pt(e.to))return e.type+' needs from/to';continue}
        if(POINT.has(e.type)){if(!pt(e.at))return e.type+' needs at';continue}
        return 'unknown element '+e.type}
      if(!sp.elements.some(e=>e.type!=='wire'&&e.type!=='text')) return 'only wires';
      circuitSVG(sp); return ''}
    if(sp.kind==='graph'){
      const X=sp.x,Y=sp.y; if(!X||!Y||!num(X.min)||!num(X.max)||!num(Y.min)||!num(Y.max)||X.min>=X.max||Y.min>=Y.max) return 'axis ranges';
      if((X.log||sp.log)&&X.min<=0) return 'log axis needs min>0';
      if(!Array.isArray(sp.series)||!sp.series.length||sp.series.length>8) return 'series';
      for(const s of sp.series){ if(s.expr){const f=compile(s.expr);let ok=0;for(let k=0;k<=50;k++){const v=f(X.min+(X.max-X.min)*k/50);if(isFinite(v))ok++}if(!ok)return 'expr gives no values'}
        else if(!Array.isArray(s.points)||s.points.length<2||s.points.length>400||!s.points.every(pt2)) return 'points'}
      graphSVG(sp); return ''}
    if(sp.kind==='phasor'){if(!Array.isArray(sp.vectors)||!sp.vectors.length||sp.vectors.length>12||!sp.vectors.every(v=>num(v.mag)&&num(v.ang)&&v.mag>=0))return 'vectors';phasorSVG(sp);return ''}
    if(sp.kind==='table'){if(!Array.isArray(sp.headers)||!Array.isArray(sp.rows)||sp.rows.length>40||!sp.rows.every(r=>Array.isArray(r)&&r.length===sp.headers.length))return 'table shape';return ''}
    return 'unknown kind'
  }catch(e){return 'render error: '+e.message}}

function html(sp){
  if(!sp) return '';
  if(validate(sp)) return '<div class="fig"><p class="note">[Figure could not be drawn]</p></div>';
  const body=sp.kind==='circuit'?circuitSVG(sp):sp.kind==='graph'?graphSVG(sp):sp.kind==='phasor'?phasorSVG(sp):tableHTML(sp);
  return `<figure class="fig">${body}${sp.caption?`<figcaption>${cell(sp.caption)}</figcaption>`:''}</figure>`}

window.FIG={html,validate,compile};
})();
