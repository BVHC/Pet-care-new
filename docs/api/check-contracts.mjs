// Kiểm tra docs/api/openapi/*.yaml theo 00-method.md (V1, V4).
// Chạy: node docs/api/check-contracts.mjs        (cần mạng lần đầu để npx tải js-yaml)
// Thoát mã khác 0 nếu có lỗi.
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const dir = join(root, 'openapi');
const files = readdirSync(dir).filter((f) => f.endsWith('.yaml')).map((f) => join(dir, f));
const METHODS = ['get', 'post', 'put', 'patch', 'delete'];
const ENVELOPE = ['success', 'errorCode', 'message', 'statusCode', 'timestamp', 'traceId'];

let failures = 0;
const fail = (file, msg) => { failures++; console.log(`FAIL ${basename(file)}: ${msg}`); };
const walk = (node, cb) => {
  if (Array.isArray(node)) return node.forEach((v) => walk(v, cb));
  if (node && typeof node === 'object') { cb(node); Object.values(node).forEach((v) => walk(v, cb)); }
};
const resolves = (doc, ref) => ref.replace(/^#\//, '').split('/')
  .reduce((cur, p) => (cur === undefined ? undefined : cur[decodeURIComponent(p)]), doc) !== undefined;

const globalOps = new Map(); // "METHOD /path" -> file

for (const file of files) {
  let doc;
  try {
    const out = execFileSync(`npx --yes js-yaml "${file}"`, { encoding: 'utf8', shell: true, stdio: ['ignore', 'pipe', 'pipe'] });
    doc = JSON.parse(out);
  } catch (e) { fail(file, `YAML parse error: ${String(e.message).split('\n')[0]}`); continue; }

  // V1: server prefix /api (paths không tự mang /api)
  if (doc.servers?.[0]?.url !== '/api') fail(file, 'servers[0].url phải là /api');

  const opIds = new Set();
  let ops = 0;
  for (const [path, item] of Object.entries(doc.paths || {})) {
    if (path.startsWith('/api')) fail(file, `${path}: path không được có tiền tố /api`);
    for (const method of METHODS) {
      const op = item?.[method];
      if (!op) continue;
      ops++;
      const key = `${method.toUpperCase()} ${path.replace(/{[^}]+}/g, '{}')}`;
      if (globalOps.has(key)) fail(file, `trùng endpoint ${key} với ${globalOps.get(key)}`);
      globalOps.set(key, basename(file));
      if (!op.operationId) fail(file, `${method.toUpperCase()} ${path}: thiếu operationId`);
      else if (opIds.has(op.operationId)) fail(file, `trùng operationId ${op.operationId}`);
      else opIds.add(op.operationId);
      const codes = Object.keys(op.responses || {});
      if (!codes.some((c) => c.startsWith('2'))) fail(file, `${method.toUpperCase()} ${path}: không có response 2xx`);
      if (!/(UC\d+|ST\d+)/.test(op.description || '')) fail(file, `${method.toUpperCase()} ${path}: description không trỏ về UC / ST`);
      if (codes.includes('422')) fail(file, `${method.toUpperCase()} ${path}: không dùng 422 (xung đột là 409)`);
      const secured = (op.security ?? doc.security ?? []).length > 0;
      if (secured && !codes.includes('401')) fail(file, `${method.toUpperCase()} ${path}: cần bearer nhưng thiếu 401`);
      for (const p of path.match(/{[^}]+}/g) || []) {
        const name = p.slice(1, -1);
        const declared = (op.parameters || []).some((x) => {
          const real = x.$ref ? doc.components.parameters[x.$ref.split('/').pop()] : x;
          return real?.in === 'path' && real?.name === name;
        });
        if (!declared) fail(file, `${method.toUpperCase()} ${path}: thiếu khai báo path param ${name}`);
      }
    }
  }

  // V1: mọi $ref nội bộ resolve được
  walk(doc, (node) => {
    if (typeof node.$ref === 'string' && node.$ref.startsWith('#') && !resolves(doc, node.$ref)) fail(file, `$ref treo ${node.$ref}`);
  });

  // V4: envelope lỗi đúng 6 trường
  const err = doc.components?.schemas?.Error;
  if (!err) fail(file, 'thiếu components.schemas.Error');
  else for (const f of ENVELOPE) if (!(err.required || []).includes(f)) fail(file, `Error.required thiếu '${f}'`);

  // V1: bảng A của file .md cùng tên khớp với paths của yaml
  const mdFile = join(root, basename(file).replace(/\.yaml$/, '.md'));
  try {
    const md = readFileSync(mdFile, 'utf8');
    const listed = new Set([...md.matchAll(/^\| \d+ \| `(GET|POST|PUT|PATCH|DELETE) ([^`]+)`/gm)].map((m) => `${m[1]} ${m[2]}`));
    const inYaml = new Set();
    for (const [path, item] of Object.entries(doc.paths || {})) for (const m of METHODS) if (item?.[m]) inYaml.add(`${m.toUpperCase()} ${path}`);
    for (const k of inYaml) if (!listed.has(k)) fail(file, `${k} có trong yaml nhưng thiếu ở bảng A của ${basename(mdFile)}`);
    for (const k of listed) if (!inYaml.has(k)) fail(file, `${k} có ở bảng A nhưng thiếu trong yaml`);
  } catch { fail(file, `không đọc được ${basename(mdFile)}`); }

  console.log(`OK ${basename(file)}: ${ops} operations`);
}
if (failures > 0) { console.log(`${failures} lỗi`); process.exit(1); }
console.log(`ALL CONTRACTS PASS — ${files.length} file, ${globalOps.size} operations`);
