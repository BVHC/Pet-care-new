// Validates docs/api/openapi/*.yaml contracts (method 00-method.md, V1 + V4).
// Usage: node docs/api/check-contracts.mjs [yaml files...]  (default: openapi/*.yaml)
// Parses via `npx --yes js-yaml` (no local deps). Exit non-zero on any failure.
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = join(dirname(fileURLToPath(import.meta.url)), 'openapi');
const files = process.argv.length > 2 ? process.argv.slice(2) : readdirSync(dir)
  .filter((f) => f.endsWith('.yaml') || f.endsWith('.yml')).map((f) => join(dir, f));

let failures = 0;
const fail = (file, msg) => { failures++; console.log(`FAIL ${file}: ${msg}`); };
const walk = (node, cb) => {
  if (Array.isArray(node)) return node.forEach((v) => walk(v, cb));
  if (node && typeof node === 'object') { cb(node); Object.values(node).forEach((v) => walk(v, cb)); }
};
const resolve = (root, ref) => {
  const parts = ref.replace(/^#\//, '').split('/');
  let cur = root;
  for (const p of parts) { cur = cur?.[decodeURIComponent(p)]; if (cur === undefined) return false; }
  return true;
};

for (const file of files) {
  const raw = readFileSync(file, 'utf8');
  // V1: unquoted risky scalars (YAML would silently truncate or break).
  raw.split('\n').forEach((line, i) => {
    const m = line.match(/^(\s*)([a-zA-Z][a-zA-Z0-9_-]*:)(\s*)(.*)$/);
    if (!m) return;
    const rest = m[4];
    if (/^['"|>\[{]/.test(rest)) return; // quoted, block, or flow scalar: safe
    const where = `${file} line ${i + 1}`;
    if (/ #/.test(rest)) fail(where, `unquoted ' #' in scalar (quote it): ${line.trim().slice(0, 80)}`);
    if (/: /.test(rest)) fail(where, `unquoted ': ' in scalar (quote it): ${line.trim().slice(0, 80)}`);
  });
  let doc;
  try {
    const out = execFileSync(`npx --yes js-yaml "${file}"`, { encoding: 'utf8', shell: true, stdio: ['ignore', 'pipe', 'pipe'] });
    doc = JSON.parse(out);
  } catch (e) { fail(file, `YAML parse error: ${String(e.message).split('\n')[0]}`); continue; }
  // V1: paths carry operations with ids + responses.
  const opIds = new Set();
  for (const [path, item] of Object.entries(doc.paths || {})) {
    for (const [method, op] of Object.entries(item || {})) {
      if (!op || typeof op !== 'object' || !op.responses) continue;
      if (!op.operationId) fail(file, `${method.toUpperCase()} ${path}: missing operationId`);
      else if (opIds.has(op.operationId)) fail(file, `duplicate operationId ${op.operationId}`);
      else opIds.add(op.operationId);
      if (Object.keys(op.responses).length === 0) fail(file, `${method.toUpperCase()} ${path}: no responses`);
    }
  }
  // V1+V4: every local $ref resolves.
  walk(doc, (node) => {
    if (typeof node.$ref === 'string' && node.$ref.startsWith('#') && !resolve(doc, node.$ref)) {
      fail(file, `dangling $ref ${node.$ref}`);
    }
  });
  // V4: convention envelope shape on Error schema.
  const err = doc.components?.schemas?.Error;
  if (!err) fail(file, 'missing components.schemas.Error');
  else {
    const req = err.required || [];
    for (const f of ['success', 'errorCode', 'message', 'statusCode', 'timestamp']) {
      if (!req.includes(f)) fail(file, `Error.required missing '${f}' (convention envelope)`);
    }
  }
  if (failures === 0) console.log(`OK ${file}: ${Object.keys(doc.paths || {}).length} paths, ${opIds.size} operations, refs resolve`);
}
if (failures > 0) { console.log(`${failures} failure(s)`); process.exit(1); }
console.log('ALL CONTRACTS PASS');
