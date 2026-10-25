import { JSDOM } from 'jsdom';
import fs from 'fs';
function walkText(root){
  const SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1};
  let len=0, linkLen=0, paraLen=0;
  const PARA={P:1,LI:1,TD:1,TH:1,BLOCKQUOTE:1,DD:1,DT:1,PRE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1};
  function walk(n, inA, inPara){
    for(const k of n.childNodes){
      if(k.nodeType===3){ const L=k.data.length; len+=L; if(inA)linkLen+=L; if(inPara)paraLen+=L; }
      else if(k.nodeType===1){ const t=k.tagName; if(SKIP[t])continue;
        walk(k, inA||t==='A', inPara||!!PARA[t]); }
    }
  }
  walk(root,false,false); return {len,linkLen,paraLen};
}
for (const f of process.argv.slice(2)) {
  const html = fs.readFileSync(f,'utf8');
  const dom = new JSDOM(html);
  const d = dom.window.document;
  console.log('=====', f, 'title:', d.title);
  const body = walkText(d.body);
  console.log('BODY total', body);
  const els = d.querySelectorAll('article,main,section,div');
  const rows=[];
  els.forEach(e=>{
    const m = walkText(e);
    if (m.len>=300) rows.push({tag:e.tagName, cls:(e.getAttribute('class')||'').slice(0,30), id:e.id||'', ...m,
      ld:(m.linkLen/m.len).toFixed(2), ps:(m.paraLen/m.len).toFixed(2)});
  });
  rows.sort((a,b)=>b.len-a.len);
  for (const r of rows.slice(0,14)) console.log(r);
  console.log('candidates>=300:', rows.length);
}
