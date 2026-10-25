import { firefox } from '/home/hatch/workspace/ghostcut/agent/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import http from 'node:http';
import { execSync } from 'node:child_process';
const LAB = '/home/hatch/workspace/ghost-hands-mobile/parity-lab';
const JDK = process.env.HOME + '/workspace/jdk17/bin/java';
const exe = process.env.HOME + '/.cache/ms-playwright/firefox-1543/firefox/firefox';
async function runScript(page, file) {
  const js = fs.readFileSync(file, 'utf8');
  const wrap = LAB + '/wrap-tmp.js';
  fs.writeFileSync(wrap, 'window.__lab = (function(){ try { return ' + js + '; } catch(e) { return "THREW: " + (e && e.message); } })();\n');
  await page.addScriptTag({ path: wrap });
  return await page.evaluate('window.__lab');
}
function java(args) { return execSync(JDK + ' -cp ' + LAB + '/classes JsDriver ' + args, { encoding: 'utf8' }); }
const html = fs.readFileSync(LAB + '/ddg-real.html', 'utf8');
const server = http.createServer((req, res) => { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(html); });
await new Promise(r => server.listen(8931, '127.0.0.1', r));
const browser = await firefox.launch({ executablePath: exe, headless: true, timeout: 30000 });
const ctx = await browser.newContext({ ignoreHTTPSErrors: true,
  viewport: { width: 412, height: 915 },
  userAgent: 'Mozilla/5.0 (Linux; Android 15; Moto G Stylus 2025) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36' });
const page = await ctx.newPage();
await page.goto('http://127.0.0.1:8931/html/?q=forecast+for+Oakdale+TN+chance+of+rain', { waitUntil: 'load', timeout: 30000 });
await page.waitForTimeout(800);
const mapJson = await runScript(page, LAB + '/js/map.js');
fs.writeFileSync(LAB + '/map-out.json', mapJson);
java('shape ' + LAB + '/map-out.json ' + LAB + '/stash.js ' + LAB + '/state.tsv');
console.log('stash:', await runScript(page, LAB + '/stash.js'));
// Simulate a re-render: the node stashed at slot 12 is REPLACED by a different
// link, and a fresh copy of the original target appears LATER in the DOM.
const mutate = `(function(){var els=window.__gh.els;var slot=els[11];var orig=slot.cloneNode(true);
var other=els[7].cloneNode(true);slot.replaceWith(other);
var res=document.querySelector('.results')||document.body;res.appendChild(orig);
return 'mutated: slot now '+other.tagName+'/'+(other.innerText||'').trim().slice(0,30);})()`;
fs.writeFileSync(LAB + '/mutate.js', mutate);
console.log(await runScript(page, LAB + '/mutate.js'));
fs.writeFileSync(LAB + '/identity-12.js', java('identity 12'));
const idOut = await runScript(page, LAB + '/identity-12.js');
fs.writeFileSync(LAB + '/identity-out-12.json', String(idOut));
console.log('identity #12 after mutation:', String(idOut).slice(0, 130));
const scanOut = await runScript(page, LAB + '/js/identityScan.js');
fs.writeFileSync(LAB + '/scan-out.json', String(scanOut));
console.log('heal #12:', java('heal ' + LAB + '/state.tsv 12 ' + LAB + '/identity-out-12.json ' + LAB + '/scan-out.json').trim().split('\n').join(' ; '));
const hv = java('heal ' + LAB + '/state.tsv 12 ' + LAB + '/identity-out-12.json ' + LAB + '/scan-out.json');
const m = hv.match(/scanIndex=(\d+)/);
if (m) {
  fs.writeFileSync(LAB + '/tapscan.js', java('tapscan ' + m[1] + ' 12'));
  console.log('tapScan result:', await runScript(page, LAB + '/tapscan.js'));
}
await browser.close(); server.close();
