import { JSDOM } from 'jsdom';
import fs from 'fs';
const SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1};
const PARA={P:1,LI:1,TD:1,TH:1,BLOCKQUOTE:1,DD:1,DT:1,PRE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1};
function metrics(root){
  let len=0,linkLen=0,paraLen=0;
  (function walk(n,inA,inPara){
    for(const k of n.childNodes){
      if(k.nodeType===3){const L=k.data.length;len+=L;if(inA)linkLen+=L;if(inPara)paraLen+=L;}
      else if(k.nodeType===1){const t=k.tagName;if(SKIP[t])continue;walk(k,inA||t==='A',inPara||!!PARA[t]);}
    }})(root,false,false);
  return {len,linkLen,paraLen,ld:len?(linkLen/len).toFixed(2):0,ps:len?(paraLen/len).toFixed(2):0};
}
for (const f of process.argv.slice(2)) {
  const dom = new JSDOM(fs.readFileSync(f,'utf8'));
  const d = dom.window.document;
  console.log('=====', f.split('/').pop());
  for (const sel of ['main','article','header','nav','footer','aside',
      '[role="navigation"]','[role="banner"]','[role="contentinfo"]','#mw-content-text','#front_page_content','#content']) {
    d.querySelectorAll(sel).forEach((e,i)=>{ if(i<3) console.log(' ', sel, e.tagName, JSON.stringify(metrics(e))); });
  }
}
