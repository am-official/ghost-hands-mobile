import { firefox } from '/home/hatch/workspace/ghostcut/agent/node_modules/playwright-core/index.mjs';
try {
  const b = await firefox.launch({ executablePath: process.env.HOME + '/.cache/ms-playwright/firefox-1543/firefox/firefox', headless: true, timeout: 20000 });
  console.log('LAUNCH OK');
  await b.close();
} catch (e) { console.log('LAUNCH FAIL:', e.message.slice(0, 1200)); }
