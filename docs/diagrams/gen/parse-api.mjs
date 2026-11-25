// Đọc docs/api/*-v1.md: bảng A (endpoint → thao tác → UC → rule) ghép bảng B (thao tác → tác nhân, chuyển trạng thái).
// Kết quả: { UC01: [{ op, actors:[...], fsm, rules:[...], endpoint }] } — nguồn cho sơ đồ UC chi tiết.
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = join(dirname(fileURLToPath(import.meta.url)), '../../api');

function table(md, heading) {
  const i = md.search(new RegExp('^## ' + heading, 'm'));
  if (i < 0) return [];
  const rows = [];
  for (const line of md.slice(i).split('\n').slice(1)) {
    if (/^## /.test(line)) break;
    if (!line.startsWith('|') || /^\|[-\s|]+\|$/.test(line)) continue;
    rows.push(line.split('|').slice(1, -1).map(c => c.trim()));
  }
  return rows.slice(1); // bỏ dòng tiêu đề
}
const clean = s => s.replace(/`/g, '').replace(/\*\*/g, '').trim();
function actorsOf(s) {
  const out = new Set();
  for (const m of s.matchAll(/\b([AS])(\d\d)(?:\s*[–-]\s*\1?(\d\d))?/g)) {
    const a = +m[2], b = m[3] ? +m[3] : a;
    for (let i = a; i <= b; i++) out.add(m[1] + String(i).padStart(2, '0'));
  }
  return [...out];
}

export function parseApi() {
  const byUc = {};
  for (const f of readdirSync(DIR).filter(f => /-v1\.md$/.test(f))) {
    const md = readFileSync(join(DIR, f), 'utf8');
    const A = table(md, 'A\\.'), B = table(md, 'B\\.');
    const meta = new Map(B.map(r => [clean(r[0]), { actors: actorsOf(r[1] || ''), fsm: r[3] && r[3] !== '—' ? clean(r[3]) : '' }]));
    for (const r of A) {
      if (r.length < 5) continue;
      const op = clean(r[2]), endpoint = clean(r[1]);
      const m = meta.get(op) || { actors: [], fsm: '' };
      const rules = [...r[4].matchAll(/BR-[A-Z]{2}-\d+(?:\s*[–-]\s*\d+)?|BR-[A-Z]{2}-\d+(?:,\s*\d+)*/g)].map(x => x[0]);
      for (const uc of r[3].match(/UC\d\d/g) || []) (byUc[uc] ??= []).push({ op, endpoint, actors: m.actors, fsm: m.fsm, rules: r[4], file: f });
    }
  }
  return byUc;
}

if (process.argv[1] && process.argv[1].endsWith('parse-api.mjs')) {
  const by = parseApi();
  const want = process.argv.slice(2);
  const list = want.length ? want : Object.keys(by).sort();
  for (const uc of list) {
    console.log(`\n== ${uc} (${(by[uc] || []).length} thao tác)`);
    for (const o of by[uc] || []) console.log(`  ${o.op}  [${o.actors.join(',') || '?'}]  ${o.endpoint}`);
  }
}
