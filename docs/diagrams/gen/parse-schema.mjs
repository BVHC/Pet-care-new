// Áp lần lượt migration Flyway (V1..V64) → mô hình bảng/cột/khóa. Chỉ đọc, không chạm DB.
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const MIG = join(dirname(fileURLToPath(import.meta.url)), '../../../BE/src/main/resources/db/migration');

// Tách câu lệnh theo ';' ngoài chuỗi/comment/dollar-quote; bỏ comment.
function statements(sql) {
  const out = [];
  let cur = '';
  for (let i = 0; i < sql.length; ) {
    const c = sql[i];
    if (c === '-' && sql[i + 1] === '-') { while (i < sql.length && sql[i] !== '\n') i++; continue; }
    if (c === '/' && sql[i + 1] === '*') { i = sql.indexOf('*/', i + 2) + 2; continue; }
    if (c === "'") { let j = i + 1; while (j < sql.length) { if (sql[j] === "'" && sql[j + 1] === "'") j += 2; else if (sql[j] === "'") break; else j++; } cur += sql.slice(i, j + 1); i = j + 1; continue; }
    if (c === '$') { const m = /^\$[A-Za-z_]*\$/.exec(sql.slice(i)); if (m) { const e = sql.indexOf(m[0], i + m[0].length); cur += sql.slice(i, e + m[0].length); i = e + m[0].length; continue; } }
    if (c === ';') { if (cur.trim()) out.push(cur.trim()); cur = ''; i++; continue; }
    cur += c; i++;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

// Tách theo dấu phẩy ở độ sâu ngoặc 0.
function splitTop(s) {
  const parts = []; let d = 0, cur = '', q = false;
  for (const c of s) {
    if (c === "'") q = !q;
    if (!q) { if (c === '(') d++; else if (c === ')') d--; else if (c === ',' && d === 0) { parts.push(cur.trim()); cur = ''; continue; } }
    cur += c;
  }
  if (cur.trim()) parts.push(cur.trim());
  return parts;
}

const body = s => { const a = s.indexOf('('); let d = 0; for (let i = a; i < s.length; i++) { if (s[i] === '(') d++; else if (s[i] === ')' && --d === 0) return s.slice(a + 1, i); } return ''; };
const cols = s => s.split(',').map(x => x.trim().replace(/"/g, ''));
const ACT = { RESTRICT: 'RESTRICT', CASCADE: 'CASCADE', 'SET NULL': 'SET NULL', 'NO ACTION': 'NO ACTION' };

function parseColumn(item) {
  const m = /^"?(\w+)"?\s+(.*)$/s.exec(item);
  const name = m[1], rest = m[2];
  const tm = /^([A-Za-z_]+(?:\s+(?:WITH|WITHOUT)\s+TIME\s+ZONE)?(?:\s*\([^)]*\))?(?:\[\])?)/i.exec(rest);
  const type = tm[1].replace(/\s+/g, ' ').replace(/\(\s*/, '(').replace(/\s*,\s*/, ',').replace(/\s+\)/, ')').toLowerCase();
  const col = { name, type, pk: /PRIMARY KEY/i.test(rest), nn: /NOT NULL/i.test(rest) || /PRIMARY KEY/i.test(rest), uq: /\bUNIQUE\b/i.test(rest), fk: null };
  const r = /REFERENCES\s+(\w+)\s*\((\w+)\)(?:\s+ON DELETE\s+(RESTRICT|CASCADE|SET NULL|NO ACTION))?/i.exec(rest);
  if (r) col.fk = { table: r[1], column: r[2], onDelete: (r[3] || 'NO ACTION').toUpperCase() };
  return col;
}

function tableConstraint(t, item) {
  const s = item.replace(/^CONSTRAINT\s+\w+\s+/i, '');
  let m;
  if ((m = /^PRIMARY KEY\s*\(([^)]*)\)/i.exec(s))) for (const c of cols(m[1])) { const x = t.cols.find(k => k.name === c); if (x) { x.pk = true; x.nn = true; } }
  else if ((m = /^UNIQUE\s*\(([^)]*)\)/i.exec(s))) t.uniques.push(cols(m[1]));
  else if ((m = /^FOREIGN KEY\s*\(([^)]*)\)\s*REFERENCES\s+(\w+)\s*\((\w+)\)(?:\s+ON DELETE\s+(RESTRICT|CASCADE|SET NULL|NO ACTION))?/i.exec(s))) {
    const x = t.cols.find(k => k.name === cols(m[1])[0]); if (x) x.fk = { table: m[2], column: m[3], onDelete: (m[4] || 'NO ACTION').toUpperCase() };
  }
}

export function parseSchema() {
  const files = readdirSync(MIG).filter(f => /^V\d+__.*\.sql$/.test(f)).sort((a, b) => parseInt(a.slice(1)) - parseInt(b.slice(1)));
  const T = new Map(); const dropped = [];
  for (const f of files) {
    const ver = parseInt(f.slice(1));
    for (const st of statements(readFileSync(join(MIG, f), 'utf8'))) {
      let m;
      if ((m = /^CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)\s*\(/i.exec(st))) {
        const t = { name: m[1], cols: [], uniques: [], indexes: [], since: ver };
        for (const item of splitTop(body(st))) {
          if (/^(CONSTRAINT|PRIMARY KEY|UNIQUE|FOREIGN KEY|CHECK|EXCLUDE)\b/i.test(item)) tableConstraint(t, item);
          else t.cols.push(parseColumn(item));
        }
        T.set(t.name, t);
      } else if ((m = /^DROP TABLE\s+(?:IF EXISTS\s+)?(\w+)/i.exec(st))) { T.delete(m[1]); dropped.push(m[1]); }
      else if ((m = /^ALTER TABLE\s+(?:ONLY\s+)?(\w+)\s+(.*)$/is.exec(st))) {
        const t = T.get(m[1]); if (!t) continue;
        for (const a of splitTop(m[2])) {
          let k;
          if ((k = /^ADD COLUMN\s+(?:IF NOT EXISTS\s+)?(.*)$/is.exec(a))) t.cols.push(parseColumn(k[1]));
          else if ((k = /^DROP COLUMN\s+(?:IF EXISTS\s+)?"?(\w+)"?/i.exec(a))) t.cols = t.cols.filter(c => c.name !== k[1]);
          else if ((k = /^ALTER COLUMN\s+(\w+)\s+SET NOT NULL/i.exec(a))) { const c = t.cols.find(x => x.name === k[1]); if (c) c.nn = true; }
          else if ((k = /^ADD CONSTRAINT\s+\w+\s+(.*)$/is.exec(a))) tableConstraint(t, k[1]);
        }
      } else if ((m = /^CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF NOT EXISTS\s+)?(\w+)\s+ON\s+(\w+)\s*(?:USING\s+\w+\s*)?\(/i.exec(st))) {
        const t = T.get(m[3]); if (!t) continue;
        t.indexes.push({ name: m[2], unique: !!m[1], cols: cols(body(st.slice(st.indexOf(m[3])))), partial: /\bWHERE\b/i.test(st) });
      }
    }
  }
  // cột UNIQUE đơn (uq_… ở mức bảng) → gắn cờ lên cột
  for (const t of T.values()) for (const u of t.uniques) if (u.length === 1) { const c = t.cols.find(x => x.name === u[0]); if (c) c.uq = true; }
  return { tables: [...T.values()], dropped, migrations: files.length };
}

if (process.argv[1] && process.argv[1].endsWith('parse-schema.mjs')) {
  const s = parseSchema();
  const fks = s.tables.flatMap(t => t.cols.filter(c => c.fk).map(c => ({ ...c.fk, from: t.name, col: c.name })));
  const acts = {}; for (const f of fks) acts[f.onDelete] = (acts[f.onDelete] || 0) + 1;
  const missing = fks.filter(f => !s.tables.find(t => t.name === f.table)).map(f => `${f.from}.${f.col}→${f.table}`);
  console.log('migrations', s.migrations, 'tables', s.tables.length, 'dropped', s.dropped, 'FK', fks.length, acts, 'FK→bảng không có:', missing);
  console.log(s.tables.map(t => `${t.name}(${t.cols.length})`).join(' '));
}
