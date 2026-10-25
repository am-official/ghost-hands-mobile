import { firefox } from '/home/hatch/workspace/ghostcut/agent/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';

const LAB = '/home/hatch/workspace/ghost-hands-mobile/parity-lab';
const URL = process.argv[2] || 'https://html.duckduckgo.com/html/?q=forecast+for+Oakdale+TN+chance+of+rain';
const exe = process.env.HOME + '/.cache/ms-playwright/firefox-1543/firefox/firefox';

const browser = await firefox.launch({ executablePath: exe, headless: true, timeout: 30000 }).catch(e => { console.log('LAUNCH FAIL', e.message.slice(0,800)); process.exit(2); });
const ctx = await browser.newContext({ ignoreHTTPSErrors: true,
  viewport: { width: 412, height: 915 },
  userAgent: 'Mozilla/5.0 (Linux; Android 15; Moto G Stylus 2025) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36',
});
const page = await ctx.newPage();
try {
  await page.goto(URL, { waitUntil: 'load', timeout: 45000 });
} catch (e) { console.log('goto note:', e.message.split('\n')[0]); }
await page.waitForTimeout(1500);
console.log('page url:', page.url());
console.log('title:', await page.title());
const counts = await page.evaluate(() => {
  const sel = "a,button,input,select,textarea,[role='button'],[role='link'],[onclick]";
  return {
    selectorMatches: document.querySelectorAll(sel).length,
    allElements: document.querySelectorAll('*').length,
    bodyTextStart: (document.body && document.body.innerText || '').slice(0, 120),
  };
});
console.log('counts:', JSON.stringify(counts));

const mapJs = fs.readFileSync(LAB + '/js/map.js', 'utf8');
const mapJson = await page.evaluate((js) => eval(js), mapJs);
fs.writeFileSync(LAB + '/map-out.json', mapJson);
const parsed = JSON.parse(mapJson);
console.log('MAP_JS: url=', parsed.url, 'raw candidates=', parsed.els.length);
console.log('text preview:', parsed.text.slice(0, 100));
await browser.close();
