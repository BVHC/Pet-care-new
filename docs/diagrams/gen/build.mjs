// Sinh docs/diagrams/petcare-system-diagrams.html từ: migration (schema thật), entity Java, docs/05 (loại bảng), docs/01 (use case).
// Chạy: node docs/diagrams/gen/build.mjs   (chỉ đọc nguồn, chỉ ghi file HTML đầu ra)
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseSchema } from './parse-schema.mjs';
import { USE_CASES, ACTORS, MODULES_UC, ucLinks, layoutUC } from './usecases.mjs';
import { DETAIL } from './ucdetail.mjs';
import { parseApi } from './parse-api.mjs';
import { parseSequences } from './sequence.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '../../..');
const JAVA = join(ROOT, 'BE/src/main/java');

// ---------- nhóm bảng (theo module BE / mục docs/05), tổng đúng 88 bảng ----------
const GROUPS = [
  ['identity', 'Định danh & quản trị', 'accounts staff_profiles sessions refresh_tokens otp_tokens audit_logs system_configs notification_templates document_counters'],
  ['branch', 'Chi nhánh', 'branches opening_hours holidays branch_services branch_quota_defaults slot_quotas'],
  ['customer', 'Khách hàng & thú cưng', 'customers addresses pets weight_records customer_merges'],
  ['catalog', 'Sản phẩm & dịch vụ', 'product_categories vaccine_types products services kennel_types vaccination_protocols shipping_fee_configs service_material_norms branch_home_visit_fees'],
  ['appointment', 'Lịch hẹn', 'appointments booking_restrictions'],
  ['visit', 'Tiếp nhận & khám', 'visits visit_assignments medical_records medical_record_addenda prescription_items vaccinations visit_material_usages surgeries'],
  ['boarding', 'Lưu trú', 'kennels boarding_bookings boarding_check_ins care_logs care_log_addenda'],
  ['inpatient', 'Nội trú', 'admissions admission_orders admission_observations'],
  ['sales', 'Bán hàng & thu ngân', 'orders order_lines payments cashier_shifts'],
  ['shop', 'Mua hàng online', 'carts cart_items online_orders stock_reservations'],
  ['payment', 'Thanh toán online & đối soát', 'payment_transactions payment_reconciliations reconciliation_items'],
  ['returns', 'Trả hàng & hoàn tiền', 'return_requests return_request_lines refunds'],
  ['inventory', 'Kho', 'suppliers inventory_items stock_lots stock_receipts stock_receipt_lines stock_adjustments stock_adjustment_lines stock_movements stock_transfers stock_transfer_lines stock_counts stock_count_lines'],
  ['content', 'Nội dung & feedback', 'article_categories articles article_comments page_contents feedbacks'],
  ['care', 'Chăm sóc khách & thông báo', 'care_tasks notifications notification_outbox'],
  ['workforce', 'Nhân sự', 'shift_definitions shift_assignments shift_requests'],
  ['ai', 'Trí tuệ nhân tạo', 'ai_documents ai_chunks ai_chat_logs ai_item_profiles'],
].map(([id, title, t]) => ({ id, title, tables: t.split(' ') }));
const LEGACY = new Set(['surgeries', 'branch_home_visit_fees']); // 00-so-lieu: giữ trong DB, không dùng (ADR-0124, ADR-0125)

// ---------- schema ----------
const schema = parseSchema();
const byName = new Map(schema.tables.map(t => [t.name, t]));
const groupOf = {};
for (const g of GROUPS) for (const t of g.tables) { if (!byName.has(t)) throw new Error('bảng không có trong schema: ' + t); groupOf[t] = g.id; }
const orphan = schema.tables.filter(t => !groupOf[t.name]).map(t => t.name);
if (orphan.length) throw new Error('bảng chưa vào nhóm: ' + orphan);

// ---------- loại bảng + bảng cha (docs/05) ----------
const snake2pascal = s => s.split('_').map(w => w[0].toUpperCase() + w.slice(1)).join('');
function singular(w) {
  if (w === 'addenda') return 'addendum';
  if (/ies$/.test(w)) return w.slice(0, -3) + 'y';
  if (/(ss|us)$/.test(w)) return w;
  if (/(sses|xes|ches|shes)$/.test(w)) return w.slice(0, -2);
  if (/ses$/.test(w) && !/(cases|bases)$/.test(w)) return w.slice(0, -2);
  return w.replace(/s$/, '');
}
const pascalOfTable = t => { const p = t.split('_'); p[p.length - 1] = singular(p[p.length - 1]); return snake2pascal(p.join('_')); };

const erd = readFileSync(join(ROOT, 'docs/05-erd.md'), 'utf8');
const kind = {}, parentName = {};
for (const m of erd.matchAll(/^### `(\w+)` — (ROOT|PART|REF|LOG)(?: · SM #\d+)?(?: của (\w+)| \(thuộc (\w+)\))?/gm)) { kind[m[1]] = m[2]; if (m[3] || m[4]) parentName[m[1]] = m[3] || m[4]; }
for (const m of erd.matchAll(/^\| `(\w+)` \| (ROOT|PART|REF|LOG) \|/gm)) kind[m[1]] ??= m[2];
Object.assign(kind, { refresh_tokens: 'PART', document_counters: 'REF', surgeries: 'ROOT', branch_home_visit_fees: 'REF' });
const TIER3_PARENT = { ai_chunks: 'ai_documents', cart_items: 'carts', stock_reservations: 'online_orders', reconciliation_items: 'payment_reconciliations', return_request_lines: 'return_requests', stock_transfer_lines: 'stock_transfers', stock_count_lines: 'stock_counts' };
const tableOfPascal = new Map(schema.tables.map(t => [pascalOfTable(t.name), t.name]));
const parentOf = t => TIER3_PARENT[t] || (parentName[t] && tableOfPascal.get(parentName[t])) || null;
for (const t of schema.tables) if (!kind[t.name]) throw new Error('thiếu loại bảng: ' + t.name);

// ---------- entity Java ----------
function walk(d) { return readdirSync(d).flatMap(f => { const p = join(d, f); return statSync(p).isDirectory() ? walk(p) : p.endsWith('.java') ? [p] : []; }); }
const camel = s => s.replace(/_(\w)/g, (_, c) => c.toUpperCase());
const snake = s => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
const stripGen = t => t.replace(/<.*>/, '');

function blank(src) { // bỏ comment + nội dung chuỗi để đếm ngoặc an toàn
  return src.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' ')).replace(/\/\/.*$/gm, '').replace(/"(?:\\.|[^"\\])*"/g, s => '"' + ' '.repeat(s.length - 2) + '"');
}
function parseEntity(file) {
  const raw = readFileSync(file, 'utf8');
  if (!/^@Entity\s*$/m.test(raw)) return null;
  const src = blank(raw);
  const cm = /public\s+(?:abstract\s+)?class\s+(\w+)/.exec(src); if (!cm) return null;
  const tm = /@Table\(\s*name\s*=\s*"(\w+)"/.exec(raw);
  const table = tm ? tm[1] : snake(cm[1]);
  const open = src.indexOf('{', cm.index), fields = [], methods = [];
  let depth = 0, buf = '';
  const header = h => {
    const noAnn = h.replace(/@\w+(\([^)]*\))?/g, ' ').replace(/\s+/g, ' ').trim();
    let m;
    if ((m = /^private\s+(?!static)(?:final\s+)?([\w<>\[\],.? ]+?)\s+(\w+)\s*(?:=.*)?$/.exec(noAnn)) && !noAnn.includes('(')) {
      const ann = h.match(/@Column\(\s*name\s*=\s*"(\w+)"/) || h.match(/@JoinColumn\(\s*name\s*=\s*"(\w+)"/);
      fields.push({ name: m[2], type: m[1].replace(/\s+/g, ' '), col: ann ? ann[1] : snake(m[2]) });
    } else if ((m = /^public\s+(static\s+)?(?:<[^>]+>\s+)?([\w<>\[\],.? ]+?)\s+(\w+)\s*\(/.exec(noAnn))) {
      if (!/^(equals|hashCode|toString)$/.test(m[3]) && !/^(get|set)[A-Z]/.test(m[3])) methods.push({ n: m[3], r: stripGen(m[2]).replace(/\s+/g, ' '), st: !!m[1] });
    }
  };
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === '{') { if (depth === 1) { header(buf); buf = ''; } depth++; continue; }
    if (c === '}') { depth--; if (depth === 0) break; if (depth === 1) buf = ''; continue; }
    if (depth === 1) { if (c === ';') { header(buf); buf = ''; } else buf += c; }
  }
  return { cls: cm[1], table, fields, methods };
}
const entities = new Map();
for (const f of walk(JAVA)) { const e = parseEntity(f); if (e) entities.set(e.table, e); }

// ---------- kiểu SQL → Java ----------
function javaType(sql) {
  if (/^(varchar|text|char)/.test(sql)) return /\[\]$/.test(sql) ? 'String[]' : 'String';
  if (/^bigint\[\]/.test(sql)) return 'Long[]';
  if (sql === 'bigint') return 'Long'; if (sql === 'int') return 'Integer'; if (sql === 'smallint') return 'Short';
  if (sql === 'boolean') return 'boolean'; if (sql === 'timestamptz') return 'Instant'; if (sql === 'date') return 'LocalDate'; if (sql === 'time') return 'LocalTime';
  if (/^numeric/.test(sql)) return 'BigDecimal'; if (sql === 'jsonb') return 'JsonNode'; if (/^vector/.test(sql)) return 'float[]';
  return sql;
}

// ---------- dữ liệu DB ----------
const dbTables = schema.tables.map(t => ({
  name: t.name, group: groupOf[t.name], kind: kind[t.name], parent: parentOf(t.name), legacy: LEGACY.has(t.name),
  cols: t.cols.map(c => ({ n: c.name, t: c.type, pk: c.pk || undefined, nn: c.nn || undefined, uq: c.uq || undefined, fk: c.fk ? { t: c.fk.table, c: c.fk.column } : undefined })),
}));

// ---------- dữ liệu lớp ----------
const MAX_ATTR = 8, MAX_OP = 5;
const classes = [], rels = [];
for (const t of schema.tables) {
  if (LEGACY.has(t.name)) continue;
  const e = entities.get(t.name);
  const f = new Map((e?.fields || []).map(x => [x.col, x]));
  const attrs = [];
  for (const c of t.cols) {
    if ((c.pk && c.name === 'id') || c.name === 'created_at' || c.name === 'updated_at') continue;
    if (c.fk && c.pk) continue; // PK là FK: thể hiện bằng quan hệ
    if (c.fk && c.fk.table === t.name) { attrs.push({ n: camel(c.name.replace(/_id$/, '')), t: pascalOfTable(t.name) }); continue; }
    if (c.fk) continue;
    const jf = f.get(c.name);
    attrs.push({ n: jf ? jf.name : camel(c.name), t: jf ? jf.type : javaType(c.type) });
  }
  const ops = (e?.methods || []).filter((m, i, a) => a.findIndex(x => x.n === m.n) === i);
  classes.push({
    id: t.name, name: e ? e.cls : pascalOfTable(t.name), kind: kind[t.name], parent: parentOf(t.name), group: groupOf[t.name], java: !!e,
    attrs: attrs.slice(0, MAX_ATTR), attrMore: Math.max(0, attrs.length - MAX_ATTR),
    ops: ops.slice(0, MAX_OP), opMore: Math.max(0, ops.length - MAX_OP),
  });
}
const clsIds = new Set(classes.map(c => c.id));
for (const t of schema.tables) {
  if (!clsIds.has(t.name)) continue;
  const byParent = new Map();
  for (const c of t.cols) if (c.fk && c.fk.table !== t.name && clsIds.has(c.fk.table)) { if (!byParent.has(c.fk.table)) byParent.set(c.fk.table, []); byParent.get(c.fk.table).push(c); }
  for (const [p, cs] of byParent) {
    const one = cs.some(c => c.pk || c.uq) || t.uniques.some(u => u.length === 1 && cs.some(c => c.name === u[0]));
    rels.push({
      from: t.name, to: p, roles: cs.map(c => camel(c.name.replace(/_id$/, ''))),
      mFrom: one ? '0..1' : '0..*', mTo: cs.every(c => c.nn) ? '1' : '0..1', comp: parentOf(t.name) === p,
    });
  }
}

// ---------- ghép vào template ----------
// ---------- 30 sơ đồ UC chi tiết: chức năng con = thao tác API thật ----------
const api = parseApi(), allOps = Object.values(api).flat();
const ucName = Object.fromEntries(USE_CASES.map(u => [u.c, u.n])), ucMod = Object.fromEntries(USE_CASES.map(u => [u.c, u.m]));
const collapseActors = s => { // A02–A08 → USER, A05–A08 → STAFF (cùng quy ước với sơ đồ tổng quát)
  const a = new Set(s), has = l => l.every(x => a.has(x));
  if (has(['A02', 'A03', 'A04', 'A05', 'A06', 'A07', 'A08'])) { ['A02', 'A03', 'A04', 'A05', 'A06', 'A07', 'A08'].forEach(x => a.delete(x)); a.add('USER'); }
  else if (has(['A05', 'A06', 'A07', 'A08'])) { ['A05', 'A06', 'A07', 'A08'].forEach(x => a.delete(x)); a.add('STAFF'); }
  return [...a];
};
const expandRules = txt => { const m = new Map();
  for (const r of txt.matchAll(/BR-([A-Z]{2})-(\d+)((?:\s*,\s*\d+)*)/g)) { const set = m.get(r[1]) ?? m.set(r[1], new Set()).get(r[1]); set.add(r[2]); for (const n of r[3].matchAll(/\d+/g)) set.add(n[0]); }
  return m; };
const ucd = DETAIL.map(d => {
  const rules = new Map();
  const subs = d.subs.map(s => {
    const ops = s.ops.map(name => (api[d.uc] || []).find(o => o.op === name) || allOps.find(o => o.op === name));
    ops.forEach((o, i) => { if (!o) throw new Error(`${d.uc}: không thấy thao tác API "${s.ops[i]}"`); });
    const actors = collapseActors(s.a || [...new Set(ops.flatMap(o => o.actors))]);
    for (const o of ops) for (const [k, v] of expandRules(o.rules)) { const set = rules.get(k) ?? rules.set(k, new Set()).get(k); v.forEach(x => set.add(x)); }
    return { n: s.n, ep: ops.map(o => o.endpoint), a: actors };
  });
  const ext = [...new Set(d.links.flatMap(l => l.slice(0, 2)).filter(x => typeof x === 'string' && x.startsWith('x:')))].map(x => ({ id: x, code: x.slice(2), name: ucName[x.slice(2)] }));
  ext.forEach(e => { if (!e.name) throw new Error(d.uc + ': thiếu use case liên quan ' + e.code); });
  const ruleTxt = [...rules].sort().map(([k, v]) => `BR-${k}: ${[...v].sort().join(', ')}`);
  return { uc: d.uc, title: d.title, status: d.status, module: ucMod[d.uc], subs, ext, links: d.links, rules: ruleTxt };
});
if (ucd.length !== 30) throw new Error('cần đúng 30 use case chi tiết, đang có ' + ucd.length);

const data = {
  meta: { builtAt: (d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`)(new Date()), migrations: schema.migrations, tables: dbTables.length, fks: dbTables.reduce((n, t) => n + t.cols.filter(c => c.fk).length, 0), classes: classes.length, javaEntities: entities.size },
  groups: GROUPS.map(g => ({ id: g.id, title: g.title })),
  db: dbTables, classes, rels,
  uc: { actors: ACTORS, modules: MODULES_UC, cases: USE_CASES, links: ucLinks(), pos: layoutUC(ucLinks()) },
  ucd,
  seq: parseSequences(ROOT),
};
const tpl = readFileSync(join(HERE, 'template.html'), 'utf8');
const out = join(ROOT, 'docs/diagrams/petcare-system-diagrams.html');
writeFileSync(out, tpl.replace('/*__DATA__*/null', JSON.stringify(data)));
console.log('OK', out, JSON.stringify(data.meta));
console.log('lớp có Java:', classes.filter(c => c.java).length, '/', classes.length, '| quan hệ:', rels.length, '(composition', rels.filter(r => r.comp).length + ')');
console.log('entity Java không khớp bảng nào:', [...entities.keys()].filter(k => !byName.has(k)));
