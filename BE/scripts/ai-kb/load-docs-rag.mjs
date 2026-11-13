// Nạp kho tri thức nguồn docs/docs_RAG vào BE đang chạy qua UC93 (ai-v1 #5, #6), theo bảng ánh xạ ở docs/docs_RAG/README.md
// mục "Ánh xạ vào UC93". Mọi tài liệu DRAFT / PENDING_REVIEW của bộ thành DRAFT trong DB (không người rà soát, KHÔNG xuất bản);
// chỉ file VERIFIED + review.approved: true mới mang reviewer_name / reviewed_at (hiện bộ chưa có file nào như vậy, Q2 chưa giải).
// Không SQL, không ST22 (DRAFT không nạp chỉ mục). Idempotent theo tiêu đề. Giá trị frontmatter không có trong bảng → dừng, mã 2.
//
// Chạy (BE 8082 trên DB demo 5433):
//   node BE/scripts/ai-kb/load-docs-rag.mjs [--api http://localhost:8082] [--dry-run] [--publish-demo] [--archive-superseded]
// --publish-demo: PATCH người rà soát GIẢ ĐỊNH rồi xuất bản (cho demo, Q2 mở). --archive-superseded: lưu trữ tài liệu MANUAL cũ đang PUBLISHED không thuộc docs_RAG (trừ KEEP_TITLES).
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

// ---- xuất bản demo và lưu trữ kho cũ (13/11/2026, Hiến chọn "B": xuất bản cả 21 với người rà soát GIẢ ĐỊNH, bỏ kho cũ) -----------
export const DEMO_REVIEWER = 'BS. Nguyễn Văn Nam';   // GIẢ ĐỊNH cho demo (Hiến quyết 9/11/2026), không phải bác sĩ thật; Q2 vẫn mở
const DEMO_NOTE = 'Duyệt giả định cho demo.';

// Bốn tài liệu riêng của Pet Care (lưu trú, cấp cứu ngoài giờ, dịch vụ): docs_RAG không có, nên giữ khi lưu trữ kho cũ.
export const KEEP_TITLES = [
  'Chăm sóc thú cưng sau khi lưu trú trở về',
  'Chuẩn bị khi đưa thú cưng đi lưu trú',
  'Cấp cứu ngoài giờ tại Pet Care',
  'Dịch vụ của Pet Care và cách đặt lịch hẹn',
];

/** Thân PATCH để một tài liệu DRAFT đủ hai lớp BR-AI-14 (nguồn + người rà soát giả định); nhãn "giả định" luôn nằm cuối sourceNote. */
export function demoReviewPatch(doc, reviewerName, reviewedOn) {
  const note = String(doc.sourceNote ?? '');
  const sourceNote = note.endsWith(DEMO_NOTE) ? note
    : `${note.slice(0, SOURCE_NOTE_MAX - DEMO_NOTE.length - 1)} ${DEMO_NOTE}`;
  return { sourceNote, reviewerName, reviewedOn };
}

/**
 * Id tài liệu cũ cần lưu trữ: MANUAL đang PUBLISHED, tiêu đề thuộc **kho demo cũ** (`oldKbTitles`: `title:` của BE/scripts/spike/kb),
 * không thuộc docs_RAG, không thuộc KEEP_TITLES. Giới hạn theo kho cũ để chạy lại sau này không lưu trữ nhầm tài liệu người dùng tự nạp.
 */
export function supersededOldDocs(existing, ragTitles, oldKbTitles) {
  return existing.filter((d) => d.sourceType === 'MANUAL' && d.status === 'PUBLISHED' && oldKbTitles.has(d.title)
    && !ragTitles.has(d.title) && !KEEP_TITLES.includes(d.title)).map((d) => d.documentId);
}

/** Tiêu đề trùng (bản đã lưu trữ và bản đang dùng): ưu tiên tài liệu chưa lưu trữ, bất kể thứ tự danh sách. */
export function indexByTitle(documents) {
  const byTitle = new Map();
  for (const d of documents) {
    const seen = byTitle.get(d.title);
    if (!seen || (seen.status === 'ARCHIVED' && d.status !== 'ARCHIVED')) byTitle.set(d.title, d);
  }
  return byTitle;
}

/** `title:` của 29 file kho demo cũ (BE/scripts/spike/kb), cùng nguồn với load-demo-kb.mjs. */
function oldDemoKbTitles(spikeKbDir) {
  return new Set(readdirSync(spikeKbDir).filter((f) => f.endsWith('.md')).map((f) => {
    const m = /^title:\s*(.+)$/m.exec(readFileSync(join(spikeKbDir, f), 'utf8').replace(/\r\n/g, '\n'));
    return m ? m[1].trim() : null;
  }).filter(Boolean));
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
  const existing = indexByTitle(await api.listAll(token));
  const map = {}; let created = 0; let kept = 0;
  for (const [d, r] of plan) {
    if (r.skip) continue;
    const have = existing.get(r.title);
    if (have) { map[d.documentId] = have.documentId; kept++; continue; }
    const res = await api.call('POST', '/api/ai/documents', r, token);
    if (res.status !== 201) { console.error(`Tạo "${r.title}": HTTP ${res.status} ${res.json?.errorCode ?? ''} ${res.json?.message ?? ''}`); process.exit(3); }
    map[d.documentId] = res.json.data.documentId; created++;
  }
  if (args['publish-demo'] === 'true') {
    const reviewedOn = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Ho_Chi_Minh' });
    console.log(`Xuất bản demo với người rà soát GIẢ ĐỊNH ${DEMO_REVIEWER}, ngày ${reviewedOn} (không phải bác sĩ thật; Q2 mở)`);
    let published = 0; let already = 0;
    for (const [d, r] of plan) {
      if (r.skip) continue;
      const id = map[d.documentId];
      const cur = await api.call('GET', `/api/ai/documents/${id}`, undefined, token);
      if (cur.status !== 200) { console.error(`Đọc #${id}: HTTP ${cur.status}`); process.exit(3); }
      if (cur.json.data.status !== 'DRAFT') { already++; continue; }
      const patch = await api.call('PATCH', `/api/ai/documents/${id}`, demoReviewPatch(cur.json.data, DEMO_REVIEWER, reviewedOn), token);
      if (patch.status !== 200) { console.error(`Sửa #${id}: HTTP ${patch.status} ${patch.json?.errorCode ?? ''} ${patch.json?.message ?? ''}`); process.exit(3); }
      const pub = await api.call('POST', `/api/ai/documents/${id}/publish`, undefined, token);
      if (pub.status !== 200) { console.error(`Xuất bản #${id}: HTTP ${pub.status} ${pub.json?.errorCode ?? ''} ${pub.json?.message ?? ''}`); process.exit(3); }
      published++;
    }
    console.log(`Xuất bản: ${published} tài liệu; đã xuất bản từ trước (bỏ qua): ${already}. ST22 sẽ nạp chỉ mục nền (vài phút).`);
  }
  if (args['archive-superseded'] === 'true') {
    const ragTitles = new Set(plan.filter(([, r]) => !r.skip).map(([, r]) => r.title));
    const old = supersededOldDocs(await api.listAll(token), ragTitles, oldDemoKbTitles(join(repoRoot, 'BE', 'scripts', 'spike', 'kb')));
    for (const id of old) {
      const res = await api.call('POST', `/api/ai/documents/${id}/archive`, undefined, token);
      if (res.status !== 200) { console.error(`Lưu trữ #${id}: HTTP ${res.status} ${res.json?.errorCode ?? ''}`); process.exit(3); }
    }
    console.log(`Lưu trữ kho cũ: ${old.length} tài liệu (giữ ${KEEP_TITLES.length} tài liệu riêng của Pet Care và phác đồ tiêm). Hoàn tác: xuất bản lại ở /admin/ai.`);
  }
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, 'docs-rag-map.json'), JSON.stringify({ writtenAt: new Date().toISOString(), api: api.API, ids: map }, null, 1));
  console.log(`Xong: tạo mới ${created}, đã có ${kept} (không tạo trùng). ${args['publish-demo'] === 'true' ? 'Đã xuất bản (người rà soát giả định).' : 'Tài liệu tạo mới ở DRAFT (chưa xuất bản); tài liệu đã có giữ nguyên trạng thái.'} Bản đồ: ${join(outDir, 'docs-rag-map.json')}`);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((e) => { console.error(e.message); process.exit(3); });
}
