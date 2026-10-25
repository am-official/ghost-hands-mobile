// Final v2.8 lab: run the PRODUCTION JS strings (dumped from the
// compiled Eyes class into prod-js/) against the fixtures in
// jsdom. The pick rule mirrored here is Eyes.pickMainContent's
// integer rule; GovernorTest pins the Java original on the same
// measured metrics.
import { JSDOM } from 'jsdom';
import fs from 'fs';

const J = (n) => fs.readFileSync(new URL('./prod-js/' + n, import.meta.url), 'utf8');
const MAP_JS = J('map.js');
const READ_JS = J('read.js');               // readTextJs(0, 2400)
const CANDS_JS = J('read-candidates.js');
const PROBE_JS = J('probe.js');
const READ_AT = (idx, start, len) => J('read-at-cand.js')
  .replace('var idx=2;', `var idx=${idx};`)
  .replace('var s=Math.max(0,0);', `var s=Math.max(0,${start});`)
  .replace('s+2400', `s+${len}`);
const PREVIEW_AT = (idx) => J('preview-at.js').replace('cands[2]', `cands[${idx}]`);

function pickMainContent(cands, legacy) {
  const clears = (c) => c && !c.chrome && c.len >= 600 && c.para * 2 >= c.len && c.link * 5 <= c.len * 2;
  if (legacy >= 0 && legacy < cands.length && clears(cands[legacy])) return legacy;
  const score = (c) => Math.floor(c.para * (c.len - c.link) / Math.max(1, c.len));
  let best = -1, bestScore = -1;
  cands.forEach((c, i) => { if (clears(c) && score(c) > bestScore) { bestScore = score(c); best = i; } });
  if (best < 0) return -1;
  let pick = best;
  cands.forEach((c, i) => { if (clears(c) && score(c) * 10 >= bestScore * 9 && c.len < cands[pick].len) pick = i; });
  return pick;
}

let failures = 0;
function expect(name, cond) {
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name);
  if (!cond) failures++;
}

function load(path) {
  const dom = new JSDOM(fs.readFileSync(path, 'utf8'), { runScripts: 'outside-only' });
  return dom.window;
}
function fullText(w, script, idx) {
  // Extract the whole text by reading the total then one big slice.
  const probe = JSON.parse(w.eval(script(idx, 0, 1)));
  return JSON.parse(w.eval(script(idx, 0, Math.max(1, probe.total)))).text;
}

// ---- DDG lite mirror: fallback, byte-identical ------------------
{
  const w = load('/home/hatch/workspace/ghost-hands-mobile/parity-lab/ddg-real.html');
  const rc = JSON.parse(w.eval(CANDS_JS));
  const pick = pickMainContent(rc.cands, rc.legacy);
  expect('ddg: no candidate clears -> fallback', pick === -1);
  const legacy = fullText(w, (i, s, l) => READ_JS.replace('Math.max(0,0)', `Math.max(0,${s})`).replace('s+2400', `s+${l}`), 0);
  const fb = fullText(w, (i, s, l) => READ_AT(-1, s, l), 0);
  expect('ddg: full-page tier text == legacy text (byte-identical)', fb === legacy);
  expect('ddg: results content present in the read',
    fb.includes('10-Day Weather') || fb.includes('Oakdale'));
}

// ---- Wikipedia Clinch: legacy <main> wins, byte-identical -------
{
  const w = load('/home/hatch/workspace/ghost-hands-mobile/read-lab/clinch-real.html');
  const rc = JSON.parse(w.eval(CANDS_JS));
  const pick = pickMainContent(rc.cands, rc.legacy);
  expect('clinch: the pick is the legacy root', pick === rc.legacy && pick >= 0);
  expect('clinch: the legacy root is <main>', rc.cands[pick].tag === 'main');
  const legacy = fullText(w, (i, s, l) => READ_JS.replace('Math.max(0,0)', `Math.max(0,${s})`).replace('s+2400', `s+${l}`), 0);
  const main = fullText(w, (i, s, l) => READ_AT(pick, s, l), 0);
  expect('clinch: main-content text == legacy text (byte-identical)', main === legacy);
  expect('clinch: word count identical',
    main.split(/\s+/).length === legacy.split(/\s+/).length);
  const chunk1 = JSON.parse(w.eval(READ_AT(pick, 0, 2400))).text;
  expect('clinch: markers in chunk 1 (337 / Norris Dam)',
    chunk1.includes('337') && chunk1.includes('Norris Dam'));
}

// ---- Weather-shaped: failure reproduced, fix proven -------------
{
  const w = load('/home/hatch/workspace/ghost-hands-mobile/read-lab/weather-shaped.html');
  const legacyChunk1 = JSON.parse(w.eval(READ_JS)).text;
  expect('weather-shaped: legacy chunk 1 has NO forecast (chrome wall)',
    !legacyChunk1.includes('Chance of rain') && legacyChunk1.includes('WeatherShaped'));
  const rc = JSON.parse(w.eval(CANDS_JS));
  const pick = pickMainContent(rc.cands, rc.legacy);
  expect('weather-shaped: a candidate is picked', pick >= 0);
  expect('weather-shaped: the pick is the forecast section',
    rc.cands[pick].tag === 'section');
  const chunk1 = JSON.parse(w.eval(READ_AT(pick, 0, 2400))).text;
  expect('weather-shaped: forecast values in the NEW chunk 1',
    chunk1.includes('low near 43') && chunk1.includes('Chance of rain 10 percent'));
  // Preview preference via the production MAP_JS candidate pass.
  const mapOut = JSON.parse(w.eval(MAP_JS));
  const mapPick = pickMainContent(mapOut.cands, mapOut.legacy);
  expect('weather-shaped: MAP light pass also picks a candidate', mapPick >= 0);
  const preview = JSON.parse(w.eval(PREVIEW_AT(mapPick))).text;
  expect('weather-shaped: preview leads with the forecast, not the wall',
    preview.includes('Oakdale, TN Weather Forecast') && !preview.includes('Sign In'));
  // Settle probe: a number, and it grows when the page hydrates.
  const before = JSON.parse(w.eval(PROBE_JS)).len;
  w.document.querySelector('section.forecast-card')
    .insertAdjacentHTML('beforeend', '<p>Late update: frost advisory issued for the valley counties overnight.</p>');
  const after = JSON.parse(w.eval(PROBE_JS)).len;
  expect('settle probe: length grows after hydration', after > before);
}

// ---- weather.gov homepage shell: no catastrophic pick ----------
{
  const w = load('/home/hatch/workspace/downloads/weather-gov-home.html');
  const rc = JSON.parse(w.eval(CANDS_JS));
  const pick = pickMainContent(rc.cands, rc.legacy);
  const navWrapper = rc.cands.findIndex(c => c.len === 3047);
  expect('weather-gov: the 0.48-density nav wrapper is not picked',
    pick !== navWrapper);
  console.log('  weather-gov pick:', pick, pick >= 0 ? JSON.stringify(rc.cands[pick]) : '(fallback)');
}

console.log(failures === 0 ? '\nFINAL LAB: all pass' : `\nFINAL LAB: ${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
