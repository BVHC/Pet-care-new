// Xuất 30 sơ đồ UC chi tiết ra docs/diagrams/uc-detail/ (PNG ×2 và SVG), qua đúng nút tải của trang HTML.
// Cần: node, Playwright của FE (FE/node_modules) và một Chrome. Đặt CHROME_PATH nếu không tự tìm thấy.
// Chạy: node docs/diagrams/gen/build.mjs && node docs/diagrams/gen/export-ucd.mjs
import { createRequire } from 'node:module';
import { mkdirSync, existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url)), ROOT = join(HERE, '../../..');
const { chromium } = createRequire(join(ROOT, 'FE/package.json'))('playwright');
const OUT = join(HERE, '../uc-detail');
mkdirSync(OUT, { recursive: true });

const pw = join(process.env.LOCALAPPDATA || '', 'ms-playwright');
const found = existsSync(pw) ? readdirSync(pw).filter(d => /^chromium-\d+$/.test(d)).map(d => join(pw, d, 'chrome-win64/chrome.exe')).filter(existsSync) : [];
const exe = process.env.CHROME_PATH || found.at(-1);
const browser = await chromium.launch(exe ? { executablePath: exe } : {});
const page = await (await browser.newContext({ viewport: { width: 1600, height: 900 }, acceptDownloads: true })).newPage();
const errs = [];
page.on('pageerror', e => errs.push(e.message));
await page.goto(pathToFileURL(join(HERE, '../petcare-system-diagrams.html')).href);
await page.click('.tabs button[data-tab=ucd]');
await page.selectOption('#scale', '2');
const ids = await page.evaluate(() => window.__pc.DATA.ucd.map(d => d.uc));
for (const id of ids) {
  await page.evaluate(id => { window.__pc.S.sel.ucd = id; window.__pc.rebuild(); }, id);
  for (const k of ['png', 'svg']) {
    const [d] = await Promise.all([page.waitForEvent('download'), page.click(`[data-exp=${k}]`)]);
    await d.saveAs(join(OUT, d.suggestedFilename()));
  }
}
const files = readdirSync(OUT);
const svgBad = files.filter(f => f.endsWith('.svg') && /var\(/.test(readFileSync(join(OUT, f), 'utf8'))).length;
console.log(`${files.length} file (${(files.reduce((s, f) => s + statSync(join(OUT, f)).size, 0) / 1e6).toFixed(1)} MB), SVG còn var(: ${svgBad}`);
console.log(errs.length ? errs : 'không lỗi JS');
await browser.close();
