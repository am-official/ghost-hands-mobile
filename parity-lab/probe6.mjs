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
console.log('candidates:', JSON.parse(mapJson).els.length);
const shapeOut = java('shape ' + LAB + '/map-out.json ' + LAB + '/stash.js ' + LAB + '/state.tsv');
const shapeLines = shapeOut.trim().split('\n');
console.log('shaped count:', shapeLines.length - 1);
// find the field's two targets in THIS shaping
let topN = -1, tenN = -1;
for (const ln of shapeLines) {
  const m = ln.match(/^(\d+) \| dom=(\d+) \| a \| .*name=(.*?) \| href=(.*)$/);
  if (m) {
    if (m[3] === 'top') topN = parseInt(m[1]);
    if (m[3].startsWith('10-day weather forecast')) tenN = parseInt(m[1]);
  }
}
console.log('targets: top=#' + topN + ' tenday=#' + tenN);
console.log('stash:', await runScript(page, LAB + '/stash.js'));
for (const n of [topN, tenN]) {
  if (n < 0) continue;
  fs.writeFileSync(LAB + '/identity-' + n + '.js', java('identity ' + n));
  const idOut = await runScript(page, LAB + '/identity-' + n + '.js');
  fs.writeFileSync(LAB + '/identity-out-' + n + '.json', String(idOut));
  console.log('identity #' + n + ':', String(idOut).slice(0, 160));
}
const scanOut = await runScript(page, LAB + '/js/identityScan.js');
fs.writeFileSync(LAB + '/scan-out.json', String(scanOut));
try { console.log('scan size:', JSON.parse(scanOut).els.length, 'chars:', scanOut.length); }
catch (e) { console.log('scan FAILED to parse:', String(scanOut).slice(0, 200)); }
for (const n of [topN, tenN]) {
  if (n < 0) continue;
  const idFile = LAB + '/identity-out-' + n + '.json';
  console.log('heal #' + n + ':', java('heal ' + LAB + '/state.tsv ' + n + ' ' + idFile + ' ' + LAB + '/scan-out.json').trim().split('\n').join(' ; '));
}
await browser.close(); server.close();
