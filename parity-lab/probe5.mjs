import { firefox } from '/home/hatch/workspace/ghostcut/agent/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import http from 'node:http';
const LAB = '/home/hatch/workspace/ghost-hands-mobile/parity-lab';
const exe = process.env.HOME + '/.cache/ms-playwright/firefox-1543/firefox/firefox';
async function runScript(page, file) {
  const js = fs.readFileSync(file, 'utf8');
  const wrap = LAB + '/wrap-tmp.js';
  fs.writeFileSync(wrap, 'window.__lab = ' + js + ';\n');
  await page.addScriptTag({ path: wrap });
  return await page.evaluate('window.__lab');
}
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
console.log('stash:', await runScript(page, LAB + '/stash.js'));
const probeExpr = `(function(){var els=(window.__gh&&window.__gh.els)||[];var all=(window.__gh&&window.__gh.all)||[];function d(e){if(!e)return 'NULL';return e.tagName+' name='+JSON.stringify((e.innerText||e.textContent||'').trim().slice(0,44))+' href='+(e.href||'');}return JSON.stringify({elsLen:els.length,allLen:all.length,slot6:d(els[5]),slot12:d(els[11])});})()`;
fs.writeFileSync(LAB + '/probe-expr.js', probeExpr);
console.log('probe:', await runScript(page, LAB + '/probe-expr.js'));
for (const n of [6, 12]) {
  const out = await runScript(page, LAB + '/identity-' + n + '.js');
  fs.writeFileSync(LAB + '/identity-out-' + n + '.json', out);
  console.log('identity #' + n + ':', String(out).slice(0, 200));
}
const scanOut = await runScript(page, LAB + '/js/identityScan.js');
fs.writeFileSync(LAB + '/scan-out.json', scanOut);
console.log('scan size:', JSON.parse(scanOut).els.length, 'chars:', scanOut.length);
await browser.close(); server.close();
