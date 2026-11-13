// Nạp tri thức DEMO cho chatbot qua UC93 (ai-v1 #5–#11) vào BE đang chạy (T77b, docs/adr/0131).
//
// ┌─ GIẢ ĐỊNH CHO DEMO ──────────────────────────────────────────────────────────────────────────────────────────────┐
// │ Người rà soát bên dưới là GIẢ ĐỊNH (Hiến quyết 9/11/2026): chưa có bác sĩ thú y thật (07 Q2 chưa giải). Nguồn của │
// │ 29 tài liệu spike vẫn CHƯA XÁC MINH. Chỉ dùng cho bản demo; KHÔNG dùng cho chạy thật và KHÔNG được viết vào báo cáo │
// │ đồ án là "đã có bác sĩ thú y duyệt".                                                                             │
// └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
//
// Đi đúng đường của BE (BR-AI-14: vẫn bắt buộc người và ngày rà soát; ST22 nạp chỉ mục bằng Java): KHÔNG nạp bằng SQL,
// KHÔNG dùng `ingest.mjs` của spike (nó bỏ qua BE). 3 tài liệu phác đồ (CATALOG_PROTOCOL) do ST22 tự sinh từ danh mục,
// script chỉ chờ và báo cáo chúng. Nạp cần GEMINI_API_KEY trong tiến trình BE vì ST22 nhúng văn bản; thiếu khóa thì tài
// liệu vẫn tạo được nhưng indexStatus = FAILED, và script nói thẳng điều đó (mã thoát 3) chứ không giả vờ đã nạp.
//
// Chạy (BE 8082 trên DB demo 5433, docs/be-fe-integration.md §7 mục A):
//   node BE/scripts/ai-kb/load-demo-kb.mjs [--api http://localhost:8082] [--dry-run] [--wait-seconds 600] [--catalog-wait-minutes 7]
// Mật khẩu SUPER_MANAGER demo đọc lúc chạy từ FE/scripts/seed-dev/README.md (như các script T49); không ghi vào script,
// không in ra. Idempotent: so theo tiêu đề, chạy lại không tạo trùng. Bảng slug → mã tài liệu ghi ra ngoài git:
// D:/Do-an/.c2-tmp/ai-kb/slug-map.json (cho BE/scripts/ai-eval chấm nguồn).
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeClient, parseArgs } from './kb-http.mjs';

// Người rà soát GIẢ ĐỊNH cho demo (Hiến quyết 9/11/2026), không phải bác sĩ thật.
const reviewerName = 'BS. Nguyễn Văn Nam';
const reviewedOn = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Ho_Chi_Minh' });

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const kbDir = join(repoRoot, 'BE', 'scripts', 'spike', 'kb');
const outDir = resolve(repoRoot, '..', '.c2-tmp', 'ai-kb');
const SOURCE_NOTE_MAX = 500;

const args = parseArgs(process.argv.slice(2));
const API = (args.api ?? process.env.AI_API_URL ?? 'http://localhost:8082').replace(/\/$/, '');
const DRY = args['dry-run'] === 'true';
const WAIT_MS = Number(args['wait-seconds'] ?? 600) * 1000;
const CATALOG_WAIT_MS = Number(args['catalog-wait-minutes'] ?? 7) * 60_000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- đọc KB của spike (29 tài liệu MANUAL; bỏ catalog-protocols.json) ------------------------------------------------
function parseManual(file) {
  const raw = readFileSync(join(kbDir, file), 'utf8').replace(/\r\n/g, '\n');
  const m = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(raw);
  if (!m) throw new Error(`${file}: thiếu front matter`);
  const meta = Object.fromEntries(m[1].split('\n').map((l) => [l.slice(0, l.indexOf(':')).trim(), l.slice(l.indexOf(':') + 1).trim()]));
  for (const key of ['slug', 'title', 'topic', 'species', 'source_note']) if (!meta[key]) throw new Error(`${file}: thiếu ${key}`);
  // Bỏ chú thích HTML và mọi dòng đánh dấu "CHƯA DUYỆT" khỏi nội dung (nó chỉ có nghĩa ở spike).
  const content = m[2].replace(/<!--[\s\S]*?-->/g, '').split('\n').filter((l) => !/CHƯA DUYỆT/i.test(l)).join('\n').trim();
  return { slug: meta.slug, title: meta.title, topic: meta.topic, species: meta.species, sourceNote: demoSourceNote(meta.source_note, file), content };
}

// Giữ ý nguồn của spike (sự thật: nguồn CHƯA XÁC MINH) nhưng thay mệnh đề "CHƯA DUYỆT, chỉ để đo spike" bằng "Duyệt giả định cho demo".
function demoSourceNote(note, file) {
  const marker = /CHƯA DUYỆT,? chỉ để đo spike T56\.?/i;
  let out = marker.test(note) ? note.replace(marker, 'Duyệt giả định cho demo.') : `${note} Duyệt giả định cho demo.`;
  if (out.length > SOURCE_NOTE_MAX) throw new Error(`${file}: source_note dài ${out.length} > ${SOURCE_NOTE_MAX}`);
  if (/CHƯA DUYỆT/i.test(out)) throw new Error(`${file}: source_note còn "CHƯA DUYỆT"`);
  return out;
}

const kb = readdirSync(kbDir).filter((f) => f.endsWith('.md')).sort().map(parseManual);
const TOPICS = new Set(['CARE', 'NUTRITION', 'BEHAVIOR', 'HYGIENE', 'VACCINATION', 'CLINIC_INFO']);
const SPECIES = new Set(['DOG', 'CAT', 'OTHER', 'ALL']);
for (const d of kb) {
  if (!TOPICS.has(d.topic) || !SPECIES.has(d.species)) throw new Error(`${d.slug}: topic / species không thuộc enum (${d.topic} / ${d.species})`);
  if (!d.content) throw new Error(`${d.slug}: nội dung rỗng sau khi bỏ chú thích`);
}
console.log(`Đọc ${kb.length} tài liệu MANUAL từ ${kbDir}`);
console.log(`Người rà soát GIẢ ĐỊNH cho demo: ${reviewerName}, ngày ${reviewedOn} (không phải bác sĩ thật; Hiến quyết 9/11/2026)`);
if (DRY) {
  for (const d of kb) console.log(`  [dry-run] ${d.slug} | ${d.topic}/${d.species} | ${d.content.length} ký tự | ${d.sourceNote.slice(-40)}`);
  process.exit(0);
}

// ---- HTTP: dùng chung ở kb-http.mjs --------------------------------------------------------------------------------
const { call, fail, login: loginShared, listAll } = makeClient(API);
const login = () => loginShared(repoRoot);

// ---- nạp ------------------------------------------------------------------------------------------------------------
const token = await login();
const existing = new Map((await listAll(token)).map((d) => [d.title, d]));
const ids = new Map(); // slug → documentId
const acted = { created: 0, published: 0, reindexed: 0, skipped: 0, archivedLeftAlone: [] };

for (const d of kb) {
  const have = existing.get(d.title);
  let id = have?.documentId;
  if (!have) {
    const r = await call('POST', '/api/ai/documents', {
      title: d.title, topic: d.topic, species: d.species, content: d.content,
      sourceNote: d.sourceNote, reviewerName, reviewedOn,
    }, token);
    if (r.status !== 201) fail(`Tạo "${d.title}"`, r);
    id = r.json.data.documentId;
    acted.created++;
  }
  ids.set(d.slug, id);
  const status = have?.status ?? 'DRAFT';
  if (status === 'ARCHIVED') { acted.archivedLeftAlone.push(d.slug); continue; }
  if (status === 'DRAFT') {
    const r = await call('POST', `/api/ai/documents/${id}/publish`, undefined, token);
    if (r.status !== 200) fail(`Xuất bản "${d.title}"`, r);
    acted.published++;
  } else if (have.indexStatus === 'FAILED') {
    const r = await call('POST', `/api/ai/documents/${id}/reindex`, undefined, token);
    if (r.status !== 202) fail(`Nạp lại "${d.title}"`, r);
    acted.reindexed++;
  } else {
    acted.skipped++;
  }
}
console.log(`Tài liệu MANUAL: tạo ${acted.created}, xuất bản ${acted.published}, nạp lại ${acted.reindexed}, giữ nguyên ${acted.skipped}`
  + (acted.archivedLeftAlone.length ? `, đã lưu trữ (không đụng) ${acted.archivedLeftAlone.join(', ')}` : ''));

// ---- chờ ST22 (chạy nền sau commit) ---------------------------------------------------------------------------------
async function waitForIndexing(wanted) {
  const deadline = Date.now() + WAIT_MS;
  for (;;) {
    const byId = new Map((await listAll(token)).map((d) => [d.documentId, d]));
    const pending = [...wanted].filter((id) => byId.get(id)?.status === 'PUBLISHED' && byId.get(id)?.indexStatus === 'PENDING');
    if (!pending.length || Date.now() > deadline) return { byId, timedOut: pending.length > 0 };
    process.stdout.write(`  còn ${pending.length} tài liệu đang chờ ST22…\r`);
    await sleep(3000);
  }
}
const waitedFor = new Set([...ids.values()].filter((id) => !acted.archivedLeftAlone.some((s) => ids.get(s) === id)));
let { byId, timedOut } = await waitForIndexing(waitedFor);

// Phác đồ do ST22 tự sinh (cron 45 */5 * * * *): chờ tối đa vài phút.
const catalogDeadline = Date.now() + CATALOG_WAIT_MS;
let catalog = [...byId.values()].filter((d) => d.sourceType === 'CATALOG_PROTOCOL');
while (!catalog.length && Date.now() < catalogDeadline) {
  process.stdout.write('  chờ ST22 sinh tài liệu phác đồ từ danh mục…\r');
  await sleep(10_000);
  ({ byId } = await waitForIndexing(new Set()));
  catalog = [...byId.values()].filter((d) => d.sourceType === 'CATALOG_PROTOCOL');
}
if (catalog.length) {
  const again = await waitForIndexing(new Set([...waitedFor, ...catalog.map((d) => d.documentId)]));
  byId = again.byId;
  timedOut = timedOut || again.timedOut;
}

// ---- báo cáo --------------------------------------------------------------------------------------------------------
const slugMap = {};
const catalogSlug = (title) => {
  const m = /^Phác đồ tiêm (.+) \((.+)\)$/.exec(title);
  return m ? `catalog-${m[1].normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')}-${m[2].normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()}` : null;
};
console.log('\nTài liệu          | mã  | trạng thái | chỉ mục');
let failed = 0;
let notIndexed = 0;
for (const [slug, id] of ids) {
  const d = byId.get(id);
  slugMap[slug] = { documentId: id, title: d?.title };
  if (d?.indexStatus === 'FAILED') failed++;
  if (d?.indexStatus !== 'INDEXED') notIndexed++;
  console.log(`${slug.padEnd(24).slice(0, 24)} | ${String(id).padStart(3)} | ${(d?.status ?? '?').padEnd(10)} | ${d?.indexStatus ?? '?'}`);
}
for (const d of catalog) {
  const slug = catalogSlug(d.title) ?? `catalog-${d.documentId}`;
  slugMap[slug] = { documentId: d.documentId, title: d.title };
  if (d.indexStatus === 'FAILED') failed++;
  if (d.indexStatus !== 'INDEXED') notIndexed++;
  console.log(`${slug.padEnd(24).slice(0, 24)} | ${String(d.documentId).padStart(3)} | ${d.status.padEnd(10)} | ${d.indexStatus}  (CATALOG_PROTOCOL do ST22 sinh)`);
}
mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'slug-map.json'), JSON.stringify({ writtenAt: new Date().toISOString(), api: API, reviewerName, reviewedOn, slugs: slugMap }, null, 1));
console.log(`\nĐã ghi bảng slug → mã tài liệu: ${join(outDir, 'slug-map.json')} (ngoài git)`);

const total = ids.size + catalog.length;
console.log(`Kết quả: ${total - notIndexed}/${total} tài liệu đã INDEXED; ${failed} FAILED; ${catalog.length} tài liệu phác đồ do ST22 sinh.`);
if (!catalog.length) console.log('CẢNH BÁO: chưa thấy tài liệu phác đồ nào (ST22 chưa đồng bộ danh mục, hoặc danh mục vaccine trống): câu Q12 sẽ không có nguồn.');
if (failed) {
  console.log('CÓ TÀI LIỆU FAILED: thường là BE chưa có GEMINI_API_KEY dùng được (ST22 không nhúng được). KB CHƯA được nạp; đừng ghi là đã nạp.');
  process.exit(3);
}
if (timedOut || notIndexed) {
  console.log('Hết thời gian chờ ST22 mà vẫn còn tài liệu chưa INDEXED: KB CHƯA nạp xong. Chạy lại script (idempotent) sau khi ST22 xong.');
  process.exit(4);
}
console.log('KB demo đã nạp đầy đủ. Người rà soát là GIẢ ĐỊNH cho demo, chưa có bác sĩ thú y thật (Q2 chưa giải).');
