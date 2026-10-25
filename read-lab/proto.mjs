// Prototype: v2.8 read-sense JS + pick rule, run against fixtures.
// The JS snippets below are the DRAFT production text for Eyes.java.
import { JSDOM } from 'jsdom';
import fs from 'fs';

export const READ_SENSE_JS = `
var GH_SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1};
var GH_PARA={P:1,LI:1,TD:1,TH:1,BLOCKQUOTE:1,DD:1,DT:1,PRE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1};
function ghMetrics(root){
  var len=0,para=0,link=0;
  function walk(n,inA,inP){
    var kids=n.childNodes;
    for(var i=0;i<kids.length;i++){
      var k=kids[i];
      if(k.nodeType===3){
        var L=k.data.replace(/\\s+/g,' ').length;
        len+=L; if(inA)link+=L; if(inP)para+=L;
      } else if(k.nodeType===1){
        var t=k.tagName;
        if(GH_SKIP[t])continue;
        walk(k,inA||t==='A',inP||!!GH_PARA[t]);
      }
    }
  }
  walk(root,false,false);
  return {len:len,para:para,link:link};
}
function ghChrome(el){
  var n=el;
  while(n&&n.nodeType===1){
    var t=n.tagName;
    if(t==='HEADER'||t==='NAV'||t==='FOOTER'||t==='ASIDE')return 1;
    var r=n.getAttribute?n.getAttribute('role'):null;
    if(r==='navigation'||r==='banner'||r==='contentinfo')return 1;
    n=n.parentElement;
  }
  return 0;
}
function ghCollectCands(limit){
  var nodes=document.querySelectorAll('article,main,section,div');
  var list=[],stash=[];
  var legEl=document.querySelector('main')||document.querySelector('article');
  var legacy=-1;
  for(var i=0;i<nodes.length&&list.length<limit;i++){
    var el=nodes[i];
    if(((el.textContent||'').length)<400)continue;
    var m=ghMetrics(el);
    if(el===legEl)legacy=list.length;
    list.push({tag:el.tagName.toLowerCase(),len:m.len,para:m.para,link:m.link,chrome:ghChrome(el)});
    stash.push(el);
  }
  window.__gh=window.__gh||{};
  window.__gh.readCands=stash;
  return {list:list,legacy:legacy};
}`;

export const WALK_JS = `
var GH_BLOCK={ADDRESS:1,ARTICLE:1,ASIDE:1,BLOCKQUOTE:1,DD:1,DIV:1,DL:1,DT:1,FIELDSET:1,FIGCAPTION:1,FIGURE:1,FOOTER:1,FORM:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,HEADER:1,HR:1,LI:1,MAIN:1,NAV:1,OL:1,P:1,PRE:1,SECTION:1,TABLE:1,TBODY:1,TD:1,TFOOT:1,TH:1,THEAD:1,TR:1,UL:1};
var GH_CHROME={HEADER:1,NAV:1,FOOTER:1,ASIDE:1};
function ghWalkText(root,supp){
  var parts=[];
  function walk(n){
    var kids=n.childNodes;
    for(var i=0;i<kids.length;i++){
      var k=kids[i];
      if(k.nodeType===3){parts.push(k.data);}
      else if(k.nodeType===1){
        var tag=k.tagName;
        if(GH_SKIP[tag])continue;
        if(supp&&(GH_CHROME[tag]||(function(){var r=k.getAttribute?k.getAttribute('role'):null;return r==='navigation'||r==='banner'||r==='contentinfo';})()))continue;
        if(tag==='BR'){parts.push('\\n');continue;}
        walk(k);
        if(GH_BLOCK[tag])parts.push('\\n');
      }
    }
  }
  if(root)walk(root);
  var t=parts.join('');
  t=t.replace(/[ \\t]+/g,' ').replace(/\\n[ \\t]+/g,'\\n').replace(/\\n{3,}/g,'\\n\\n').trim();
  if(t.length>60000)t=t.slice(0,60000);
  return t;
}`;

// Legacy readTextJs walk (root = main||article||body, no suppression) —
// mirrors the shipped v1.8 extractor for comparison.
export const LEGACY_JS = READ_SENSE_JS + WALK_JS + `
(function(){
  var root=document.querySelector('main')||document.querySelector('article')||document.body;
  return ghWalkText(root,false);
})()`;

export const NEWAT_JS = (idx) => READ_SENSE_JS + WALK_JS + `
(function(){
  var idx=${idx};
  var root=null,supp=false;
  if(idx>=0){
    var cands=(window.__gh&&window.__gh.readCands)||[];
    root=cands[idx];
    if(!root||root.isConnected===false)return JSON.stringify({total:-1});
  } else {
    root=document.querySelector('main')||document.querySelector('article')||document.body;
    supp=true;
  }
  return ghWalkText(root,supp);
})()`;

// The pick rule (mirror of the Java Eyes.pickMainContent).
export function pickMainContent(cands, legacy) {
  const clears = (c) => c && !c.chrome && c.len >= 600 && c.para * 2 >= c.len && c.link * 5 <= c.len * 2;
  if (legacy >= 0 && legacy < cands.length && clears(cands[legacy])) return legacy;
  let best = -1, bestScore = -1;
  const score = (c) => Math.floor(c.para * (c.len - c.link) / Math.max(1, c.len));
  for (let i = 0; i < cands.length; i++) {
    if (!clears(cands[i])) continue;
    const s = score(cands[i]);
    if (s > bestScore) { bestScore = s; best = i; }
  }
  if (best < 0) return -1;
  let pick = best;
  for (let i = 0; i < cands.length; i++) {
    if (!clears(cands[i])) continue;
    if (score(cands[i]) * 10 >= bestScore * 9 && cands[i].len < cands[pick].len) pick = i;
  }
  return pick;
}

const fixtures = [
  ['ddg', '/home/hatch/workspace/ghost-hands-mobile/parity-lab/ddg-real.html', ['10-Day Weather', 'Oakdale']],
  ['clinch', '/home/hatch/workspace/ghost-hands-mobile/read-lab/clinch-real.html', ['337', 'Norris Dam', 'Melton Hill']],
  ['weather-gov', '/home/hatch/workspace/downloads/weather-gov-home.html', ['National Weather Service']],
  ['weather-shaped', '/home/hatch/workspace/ghost-hands-mobile/read-lab/weather-shaped.html', ['Chance of rain 10 percent', 'low near 43']],
];
for (const [name, path, markers] of fixtures) {
  const dom = new JSDOM(fs.readFileSync(path, 'utf8'), { runScripts: 'outside-only' });
  const w = dom.window;
  const legacyText = w.eval(LEGACY_JS);
  const rc = w.eval(`(function(){ ${READ_SENSE_JS} ; return ghCollectCands(48); })()`);
  const pick = pickMainContent(rc.list, rc.legacy);
  const newText = w.eval(NEWAT_JS(pick));
  const fbText = w.eval(NEWAT_JS(-1));
  console.log('=====', name, 'legacyIdx:', rc.legacy, 'cands:', rc.list.length, 'pick:', pick,
    pick >= 0 ? JSON.stringify(rc.list[pick]) : '(fallback)');
  console.log('  legacy total:', legacyText.length, '| new total:', newText.length,
    '| fallback(-1) total:', fbText.length, '| fallback==legacy:', fbText === legacyText);
  console.log('  legacy words:', legacyText.split(/\s+/).length, '| new words:', newText.split(/\s+/).length);
  for (const m of markers) {
    console.log(`  marker ${JSON.stringify(m)}: legacy@${legacyText.indexOf(m)} new@${newText.indexOf(m)} legacyChunk1=${legacyText.slice(0,2400).includes(m)} newChunk1=${newText.slice(0,2400).includes(m)}`);
  }
  console.log('  top cands:', rc.list.slice(0, 6).map(c => `${c.tag}:${c.len}/p${c.para}/l${c.link}${c.chrome ? '/CHROME' : ''}`).join(' '));
  console.log('  legacy first 160:', JSON.stringify(legacyText.slice(0, 160)));
  console.log('  new    first 160:', JSON.stringify(newText.slice(0, 160)));
}
