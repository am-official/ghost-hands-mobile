import { firefox } from '/home/hatch/workspace/ghostcut/agent/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
const LAB = '/home/hatch/workspace/ghost-hands-mobile/parity-lab';
const URL = 'https://html.duckduckgo.com/html/?q=forecast+for+Oakdale+TN+chance+of+rain';
const exe = process.env.HOME + '/.cache/ms-playwright/firefox-1543/firefox/firefox';
async function runScript(page, file) {
  const js = fs.readFileSync(file, 'utf8');
  const wrap = LAB + '/wrap-tmp.js';
  fs.writeFileSync(wrap, 'window.__lab = ' + js + ';\n');
  await page.addScriptTag({ path: wrap });
  return await page.evaluate('window.__lab');
}
const browser = await firefox.launch({ executablePath: exe, headless: true, timeout: 30000 });
const ctx = await browser.newContext({ ignoreHTTPSErrors: true,
  viewport: { width: 412, height: 915 },
  userAgent: 'Mozilla/5.0 (Linux; Android 15; Moto G Stylus 2025) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36' });
const page = await ctx.newPage();
await page.goto(URL, { waitUntil: 'load', timeout: 45000 }).catch(e => console.log('goto note', e.message.split('\n')[0]));
await page.waitForTimeout(1200);
const mapJson = await runScript(page, LAB + '/js/map.js');
fs.writeFileSync(LAB + '/map-out.json', mapJson);
console.log('candidates:', JSON.parse(mapJson).els.length);
console.log('stash:', await runScript(page, LAB + '/stash.js'));
const probeExpr = `(function(){var els=(window.__gh&&window.__gh.els)||[];var all=(window.__gh&&window.__gh.all)||[];function d(e){if(!e)return 'NULL';return e.tagName+' name='+JSON.stringify((e.innerText||e.textContent||'').trim().slice(0,40))+' href='+(e.href||'');}return JSON.stringify({elsLen:els.length,allLen:all.length,slot6:d(els[5]),slot8:d(els[7]),slot12:d(els[11]),all0:d(all[0]),all6:d(all[6]),all10:d(all[10])});})()`;
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
await browser.close();
