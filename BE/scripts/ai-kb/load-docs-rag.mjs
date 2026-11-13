// Nạp kho tri thức nguồn docs/docs_RAG vào BE đang chạy qua UC93 (ai-v1 #5, #6), theo bảng ánh xạ ở docs/docs_RAG/README.md
// mục "Ánh xạ vào UC93". Mọi tài liệu DRAFT / PENDING_REVIEW của bộ thành DRAFT trong DB (không người rà soát, KHÔNG xuất bản);
// chỉ file VERIFIED + review.approved: true mới mang reviewer_name / reviewed_at (hiện bộ chưa có file nào như vậy, Q2 chưa giải).
// Không SQL, không ST22 (DRAFT không nạp chỉ mục). Idempotent theo tiêu đề. Giá trị frontmatter không có trong bảng → dừng, mã 2.
//
// Chạy (BE 8082 trên DB demo 5433):
//   node BE/scripts/ai-kb/load-docs-rag.mjs [--api http://localhost:8082] [--dry-run]
// Mã thoát: 0 xong · 2 dữ liệu lệch (topic lạ, trùng tiêu đề / document_id, quá 20 000 ký tự) · 3 API lỗi.
// Ghi D:/Do-an/.c2-tmp/ai-kb/docs-rag-map.json (document_id → documentId) cho bộ đo.
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { makeClient, parseArgs } from './kb-http.mjs';

const CONTENT_MAX = 20_000;
const SOURCE_NOTE_MAX = 500;

// Bảng ánh xạ: phải khớp docs/docs_RAG/README.md. Thiếu khóa → toCreateRequest ném (không đoán).
export const TOPIC_MAP = {
  basic_care: 'CARE', dog_care: 'CARE', cat_care: 'CARE', puppy_care: 'CARE', kitten_care: 'CARE', senior_care: 'CARE',
  adoption: 'CARE', travel: 'CARE', multi_pet_household: 'CARE', family_safety: 'CARE', cat_environment: 'CARE',
  emergency_triage: 'CARE', first_aid: 'CARE', poisoning_safety: 'CARE',
  nutrition: 'NUTRITION', weight_management: 'NUTRITION',
  dog_behavior: 'BEHAVIOR',
  grooming_skin: 'HYGIENE', dental_care: 'HYGIENE',
  vaccination: 'VACCINATION', parasite_prevention: 'VACCINATION',
};
const SPECIES_MAP = { dog: 'DOG', cat: 'CAT', 'dog,cat': 'ALL', 'cat,dog': 'ALL' };

// ---- YAML frontmatter tối thiểu (đủ cho FRONTMATTER_SCHEMA.md: vô hướng, [a, b], {k: v}, khối "- x" và khối "k: v") ----
function scalar(s) {
  s = s.trim();
  if (s === '' || s === 'null' || s === '~') return null;
  if (s === 'true') return true;
  if (s === 'false') return false;
  if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
  return s.replace(/^["']|["']$/g, '');
}
function inlineValue(s) {
  s = s.trim();
  if (s.startsWith('[') && s.endsWith(']')) return s.slice(1, -1).split(',').map(scalar).filter((x) => x !== null);
  if (s.startsWith('{') && s.endsWith('}')) {
    return Object.fromEntries(s.slice(1, -1).split(',').map((kv) => {
      const i = kv.indexOf(':');
      return [kv.slice(0, i).trim(), scalar(kv.slice(i + 1))];
    }));
  }
  return scalar(s);
}
function parseFrontmatter(text) {
  const lines = text.split('\n');
  const out = {};
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || /^\s/.test(line)) continue;
    const c = line.indexOf(':');
    const key = line.slice(0, c).trim();
    const rest = line.slice(c + 1);
    if (rest.trim() !== '') { out[key] = inlineValue(rest); continue; }
    const block = [];
    while (i + 1 < lines.length && /^\s+\S/.test(lines[i + 1])) block.push(lines[++i].trim());
    out[key] = block.every((b) => b.startsWith('- '))
      ? block.map((b) => scalar(b.slice(2)))
      : Object.fromEntries(block.map((b) => { const j = b.indexOf(':'); return [b.slice(0, j).trim(), inlineValue(b.slice(j + 1))]; }));
  }
  return out;
}

export function parseRagDoc(raw, file) {
  const text = raw.replace(/\r\n/g, '\n');
  const m = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(text);
  if (!m) throw new Error(`${file}: thiếu frontmatter`);
  const fm = parseFrontmatter(m[1]);
  for (const k of ['document_id', 'title', 'species', 'topic', 'status']) if (fm[k] == null) throw new Error(`${file}: thiếu ${k}`);
  const review = fm.review ?? {};
  return {
    documentId: String(fm.document_id), title: String(fm.title), topic: String(fm.topic),
    species: Array.isArray(fm.species) ? fm.species.map(String) : [String(fm.species)],
    riskLevel: fm.risk_level == null ? 'UNSET' : String(fm.risk_level), version: fm.version ?? 1, status: String(fm.status),
    approved: review.approved === true, reviewerName: review.reviewer_name ?? null, reviewedAt: review.reviewed_at ?? null,
    sourceUrls: Array.isArray(fm.source_urls) ? fm.source_urls.map(String) : [], content: m[2].trim(),
  };
}

/** Thân POST /api/ai/documents, hoặc {skip} cho REJECTED / EXPIRED. Ném khi lệch bảng ánh xạ hay quá cỡ. */
export function toCreateRequest(d) {
  if (d.status === 'REJECTED' || d.status === 'EXPIRED') return { skip: `${d.documentId}: status ${d.status}` };
  const topic = TOPIC_MAP[d.topic];
  if (!topic) throw new Error(`${d.documentId}: topic "${d.topic}" không có trong bảng ánh xạ (README docs_RAG)`);
  const species = SPECIES_MAP[d.species.join(',')];
  if (!species) throw new Error(`${d.documentId}: species [${d.species}] không có trong bảng ánh xạ`);
  if (d.content.length > CONTENT_MAX) throw new Error(`${d.documentId}: nội dung ${d.content.length} ký tự > 20 000 (BR-AI-14); tách tay theo "##"`);
  const sourceNote = `[docs_RAG ${d.documentId} v${d.version} ${d.riskLevel}] ${d.sourceUrls.join(' ; ')}`.slice(0, SOURCE_NOTE_MAX);
  const verified = d.status === 'VERIFIED' && d.approved && d.reviewerName && d.reviewedAt;
  return {
    title: d.title, topic, species, content: d.content, sourceNote,
    ...(verified ? { reviewerName: String(d.reviewerName), reviewedOn: String(d.reviewedAt) } : {}),
  };
}

// ---- CLI ------------------------------------------------------------------------------------------------------------
async function main() {
  const here = dirname(fileURLToPath(import.meta.url));
  const repoRoot = resolve(here, '..', '..', '..');
  const ragDir = join(repoRoot, 'docs', 'docs_RAG');
  const outDir = resolve(repoRoot, '..', '.c2-tmp', 'ai-kb');
  const args = parseArgs(process.argv.slice(2));
  const DRY = args['dry-run'] === 'true';

  const files = readdirSync(ragDir).filter((d) => /^0[1-6]_/.test(d) && statSync(join(ragDir, d)).isDirectory())
    .flatMap((d) => readdirSync(join(ragDir, d)).filter((f) => f.endsWith('.md')).sort().map((f) => join(d, f)));
  const docs = files.map((f) => parseRagDoc(readFileSync(join(ragDir, f), 'utf8'), f));

  const problems = [];
  const seenId = new Set(); const seenTitle = new Set();
  const plan = [];
  for (const d of docs) {
    if (seenId.has(d.documentId)) problems.push(`${d.documentId}: document_id trùng`);
    if (seenTitle.has(d.title)) problems.push(`${d.documentId}: title trùng "${d.title}"`);
    seenId.add(d.documentId); seenTitle.add(d.title);
    try { plan.push([d, toCreateRequest(d)]); } catch (e) { problems.push(e.message); }
  }
  if (problems.length) { console.error('Dữ liệu lệch, không gọi API:\n  ' + problems.join('\n  ')); process.exit(2); }

  for (const [d, r] of plan) {
    console.log(r.skip ? `  [bỏ qua] ${r.skip}`
      : `  ${DRY ? '[dry-run] ' : ''}${d.documentId} | ${d.topic} → ${r.topic} | [${d.species}] → ${r.species} | ${d.riskLevel} | ${r.content.length} ký tự | ${r.reviewerName ? 'PUBLISHED (có người rà soát)' : 'DRAFT'}`);
  }
  console.log(`${plan.filter(([, r]) => !r.skip).length} tài liệu sẽ nạp, ${plan.filter(([, r]) => r.skip).length} bỏ qua`);
  if (DRY) return;

  const api = makeClient(args.api ?? process.env.AI_API_URL ?? 'http://localhost:8082');
  let token;
  try { token = await api.login(repoRoot); } catch (e) { console.error(e.message); process.exit(3); }
  const existing = new Map((await api.listAll(token)).map((x) => [x.title, x]));
  const map = {}; let created = 0; let kept = 0;
  for (const [d, r] of plan) {
    if (r.skip) continue;
    const have = existing.get(r.title);
    if (have) { map[d.documentId] = have.documentId; kept++; continue; }
    const res = await api.call('POST', '/api/ai/documents', r, token);
    if (res.status !== 201) { console.error(`Tạo "${r.title}": HTTP ${res.status} ${res.json?.errorCode ?? ''} ${res.json?.message ?? ''}`); process.exit(3); }
    map[d.documentId] = res.json.data.documentId; created++;
  }
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, 'docs-rag-map.json'), JSON.stringify({ writtenAt: new Date().toISOString(), api: api.API, ids: map }, null, 1));
  console.log(`Xong: tạo mới ${created}, đã có ${kept} (không tạo trùng). Tất cả ở DRAFT, chưa xuất bản. Bản đồ: ${join(outDir, 'docs-rag-map.json')}`);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((e) => { console.error(e.message); process.exit(3); });
}
