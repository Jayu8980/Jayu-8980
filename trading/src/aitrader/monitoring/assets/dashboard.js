(function(){
const D=JSON.parse(document.getElementById('dash-data').textContent);
const css=n=>getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const NS='http://www.w3.org/2000/svg';
const el=(t,a)=>{const e=document.createElementNS(NS,t);for(const k in a)e.setAttribute(k,a[k]);return e;};
const inr=v=>{const a=Math.abs(v),s=v<0?'-':'';return a>=1e7?s+'₹'+(a/1e7).toFixed(2)+' Cr':a>=1e5?s+'₹'+(a/1e5).toFixed(2)+' L':s+'₹'+Math.round(a).toLocaleString('en-IN');};
const pct=v=>(v*100).toFixed(1)+'%';
function ticks(lo,hi,n){const span=hi-lo||Math.abs(hi)||1;const raw=span/n;const mag=Math.pow(10,Math.floor(Math.log10(raw)));
 const step=[1,2,2.5,5,10].map(m=>m*mag).find(s=>s>=raw);const out=[];for(let v=Math.ceil(lo/step)*step;v<=hi+1e-12;v+=step)out.push(+v.toFixed(10));return out;}
let range=0;
const charts=[
 {id:'chart-equity',series:[{name:'Equity',key:'equity',color:'--s1'}],fmt:inr,area:true},
 {id:'chart-drawdown',series:[{name:'Drawdown',key:'drawdown',color:'--s2'}],fmt:pct,ref:{value:D.ddLimit,label:'Limit '+pct(-D.ddLimit)},yMax:0},
 {id:'chart-exposure',series:[{name:'Gross',key:'gross',color:'--s1'},{name:'Net',key:'net',color:'--s2'}],fmt:pct}
];
function draw(c){
 const box=document.getElementById(c.id);box.textContent='';
 const n=D.dates.length, start=range&&n>range?n-range:0, dates=D.dates.slice(start);
 const ser=c.series.map(s=>({...s,vals:D[s.key].slice(start)}));
 if(!dates.length){const p=document.createElement('p');p.className='empty';p.textContent='No data yet.';box.appendChild(p);return;}
 const W=box.clientWidth||600,H=box.clientHeight||220,m={l:64,r:ser.length>1?64:80,t:10,b:26};
 let lo=Math.min(...ser.flatMap(s=>s.vals)),hi=Math.max(...ser.flatMap(s=>s.vals));
 if(c.ref){lo=Math.min(lo,c.ref.value);hi=Math.max(hi,c.ref.value);}
 if(c.yMax!==undefined)hi=Math.max(hi,c.yMax);
 if(c.series.length>1){lo=Math.min(lo,0);}
 const pad=(hi-lo)*0.08||Math.abs(hi)*0.02||1;lo-=pad;hi+=c.yMax===0?0:pad;
 const tk=ticks(lo,hi,4);lo=Math.min(lo,tk[0]);hi=Math.max(hi,tk[tk.length-1]);
 const x=i=>m.l+(dates.length<2?0:i*(W-m.l-m.r)/(dates.length-1)), y=v=>m.t+(hi-v)*(H-m.t-m.b)/(hi-lo||1);
 const svg=el('svg',{viewBox:`0 0 ${W} ${H}`,role:'img','aria-label':box.getAttribute('aria-label')||''});
 for(const v of tk){svg.appendChild(el('line',{x1:m.l,x2:W-m.r,y1:y(v),y2:y(v),stroke:css('--grid'),'stroke-width':1}));
  const t=el('text',{x:m.l-8,y:y(v)+4,'text-anchor':'end','font-size':11,fill:css('--muted')});t.textContent=c.fmt(v);svg.appendChild(t);}
 const xt=(W-m.l-m.r<300?[0,dates.length-1]:[0,Math.floor((dates.length-1)/2),dates.length-1]).filter((v,i,a)=>a.indexOf(v)===i);
 for(const i of xt){const t=el('text',{x:x(i),y:H-6,'text-anchor':i===0?'start':i===dates.length-1?'end':'middle','font-size':11,fill:css('--muted')});t.textContent=dates[i];svg.appendChild(t);}
 svg.appendChild(el('line',{x1:m.l,x2:W-m.r,y1:H-m.b,y2:H-m.b,stroke:css('--axis'),'stroke-width':1}));
 if(c.ref){const ry=y(c.ref.value);svg.appendChild(el('line',{x1:m.l,x2:W-m.r,y1:ry,y2:ry,stroke:css('--muted'),'stroke-width':1}));
  const t=el('text',{x:W-m.r+6,y:ry+4,'font-size':11,fill:css('--ink-2')});t.textContent=c.ref.label;svg.appendChild(t);}
 for(const s of ser){const col=css(s.color);const d=s.vals.map((v,i)=>(i?'L':'M')+x(i).toFixed(1)+','+y(v).toFixed(1)).join('');
  if(c.area){svg.appendChild(el('path',{d:d+`L${x(s.vals.length-1)},${H-m.b}L${x(0)},${H-m.b}Z`,fill:col,'fill-opacity':0.1}));}
  svg.appendChild(el('path',{d,fill:'none',stroke:col,'stroke-width':2,'stroke-linejoin':'round','stroke-linecap':'round'}));
  const li=s.vals.length-1;svg.appendChild(el('circle',{cx:x(li),cy:y(s.vals[li]),r:4,fill:col,stroke:css('--surface'),'stroke-width':2}));}
 // End labels only when they cannot collide; otherwise the legend and tooltip carry identity.
 const ends=ser.map(s=>y(s.vals[s.vals.length-1])).sort((a,b)=>a-b);
 const apart=ends.every((v,i)=>!i||v-ends[i-1]>=14);
 if(!c.ref&&apart){for(const s of ser){const li=s.vals.length-1;const t=el('text',{x:x(li)+8,y:y(s.vals[li])+4,'font-size':11,fill:css('--ink-2')});
  t.textContent=(ser.length>1?s.name+' ':'')+c.fmt(s.vals[li]);svg.appendChild(t);}}
 const cross=el('line',{y1:m.t,y2:H-m.b,stroke:css('--axis'),'stroke-width':1,visibility:'hidden'});svg.appendChild(cross);
 const dots=ser.map(s=>{const d=el('circle',{r:4,fill:css(s.color),stroke:css('--surface'),'stroke-width':2,visibility:'hidden'});svg.appendChild(d);return d;});
 const hit=el('rect',{x:m.l,y:m.t,width:Math.max(W-m.l-m.r,1),height:H-m.t-m.b,fill:'transparent',tabindex:0,class:'hit'});svg.appendChild(hit);
 box.appendChild(svg);
 const tip=document.createElement('div');tip.className='tip';box.appendChild(tip);let cur=dates.length-1;
 function show(i){cur=Math.max(0,Math.min(dates.length-1,i));const px=x(cur);cross.setAttribute('x1',px);cross.setAttribute('x2',px);cross.setAttribute('visibility','visible');
  ser.forEach((s,k)=>{dots[k].setAttribute('cx',px);dots[k].setAttribute('cy',y(s.vals[cur]));dots[k].setAttribute('visibility','visible');});
  tip.textContent='';const h=document.createElement('div');h.className='name';h.textContent=dates[cur];tip.appendChild(h);
  for(const s of ser){const r=document.createElement('div');r.className='row';const k=document.createElement('span');k.className='key';k.style.background=css(s.color);
   const v=document.createElement('strong');v.textContent=c.fmt(s.vals[cur]);const nm=document.createElement('span');nm.className='name';nm.textContent=s.name;r.append(k,v,nm);tip.appendChild(r);}
  tip.style.display='block';const tw=tip.offsetWidth;tip.style.left=Math.min(Math.max(px+12,0),W-tw)+'px';tip.style.top='8px';}
 function hide(){cross.setAttribute('visibility','hidden');dots.forEach(d=>d.setAttribute('visibility','hidden'));tip.style.display='none';}
 hit.addEventListener('pointermove',e=>{const r=svg.getBoundingClientRect();const px=(e.clientX-r.left)*W/r.width;show(Math.round((px-m.l)/((W-m.l-m.r)/Math.max(dates.length-1,1))));});
 hit.addEventListener('pointerleave',hide);hit.addEventListener('focus',()=>show(cur));hit.addEventListener('blur',hide);
 hit.addEventListener('keydown',e=>{if(e.key==='ArrowLeft'){show(cur-1);e.preventDefault();}if(e.key==='ArrowRight'){show(cur+1);e.preventDefault();}});
}
function drawAll(){charts.forEach(draw);}
document.querySelectorAll('[data-range]').forEach(b=>b.addEventListener('click',()=>{range=+b.dataset.range;
 document.querySelectorAll('[data-range]').forEach(o=>o.setAttribute('aria-pressed',String(o===b)));drawAll();}));
let t;window.addEventListener('resize',()=>{clearTimeout(t);t=setTimeout(drawAll,100);});
if(window.matchMedia)window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',drawAll);
drawAll();
})();
(function(){const D=JSON.parse(document.getElementById('dash-data').textContent);const w=document.getElementById('data-table');
const t=document.createElement('table');const hr=t.createTHead().insertRow();['Session','Equity','Drawdown','Gross','Net'].forEach(h=>{const th=document.createElement('th');th.textContent=h;hr.appendChild(th);});
const b=t.createTBody();D.dates.forEach((d,i)=>{const r=b.insertRow();[d,Math.round(D.equity[i]).toLocaleString('en-IN'),(D.drawdown[i]*100).toFixed(2)+'%',(D.gross[i]*100).toFixed(1)+'%',(D.net[i]*100).toFixed(1)+'%'].forEach((v,k)=>{const c=r.insertCell();c.textContent=v;if(k)c.className='num';});});
w.appendChild(t);})();
