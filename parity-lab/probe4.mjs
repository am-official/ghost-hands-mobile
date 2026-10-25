import { firefox } from '/home/hatch/workspace/ghostcut/agent/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import http from 'node:http';
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
let html = await page.content();
html = html.replace(/<head([^>]*)>/i, '<head$1><base href="https://html.duckduckgo.com/html/">');
fs.writeFileSync(LAB + '/ddg-real.html', html);
console.log('saved page html chars:', html.length);
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
});
await new Promise(r => server.listen(8931, '127.0.0.1', r));
const page2 = await ctx.newPage();
await page2.goto('http://127.0.0.1:8931/html/?q=forecast+for+Oakdale+TN+chance+of+rain', { waitUntil: 'load', timeout: 30000 });
await page2.waitForTimeout(800);
console.log('local title:', await page2.title());
const mapJson = await runScript(page2, LAB + '/js/map.js');
fs.writeFileSync(LAB + '/map-out.json', mapJson);
console.log('candidates:', JSON.parse(mapJson).els.length);
await browser.close();
server.close();
