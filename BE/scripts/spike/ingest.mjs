// Nạp KB vào container spike (docs/07 §5.3, ST22 rút gọn): đọc kb/*.md + dựng tài liệu CATALOG_PROTOCOL,
// cắt đoạn, nhúng `gemini-embedding-001` 768 chiều, ghi `ai_documents` / `ai_chunks` trong MỘT transaction cho mỗi tài liệu
// (BR-AI-16: ghi đoạn mới rồi mới đặt indexed_version).
//
// Tài liệu thử được đặt PUBLISHED CHỈ trong container spike để đo (chưa có người rà soát, 07 Q2); không bao giờ nạp vào DB dev/demo.
// Chạy: node ingest.mjs            (cần Docker + container spike, và GEMINI_API_KEY nếu có đoạn chưa có trong bộ nhớ đệm)
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chunkDoc } from './chunk.mjs';
import { lit, openDb, vec } from './db.mjs';
import { createGemini } from './gemini.mjs';
import { strip } from './guards.mjs';

const dir = fileURLToPath(new URL('.', import.meta.url));
const cacheDir = process.env.SPIKE_CACHE_DIR ?? join(tmpdir(), 'petcare-spike-cache');
const cacheFile = join(cacheDir, 'embeddings.json');
const MODEL = 'gemini-embedding-001';
const DIM = 768;
const sha = (s) => createHash('sha256').update(s).digest('hex');

function parseManual(file) {
  const raw = readFileSync(join(dir, 'kb', file), 'utf8').replace(/\r\n/g, '\n');
  const m = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(raw);
  if (!m) throw new Error(`${file}: thiếu front-matter`);
  const meta = Object.fromEntries(m[1].split('\n').map((l) => [l.slice(0, l.indexOf(':')).trim(), l.slice(l.indexOf(':') + 1).trim()]));
  const content = m[2].replace(/<!--[\s\S]*?-->/g, '').trim();
  return { sourceType: 'MANUAL', slug: meta.slug, title: meta.title, topic: meta.topic, species: meta.species, sourceNote: meta.source_note, content };
}

// Dựng tài liệu CATALOG_PROTOCOL từ phác đồ (07 §5.3 bước 2): tên loại vaccine, loài, từng mũi.
function catalogDocs() {
  const { rows } = JSON.parse(readFileSync(join(dir, 'kb', 'catalog-protocols.json'), 'utf8'));
  const groups = new Map();
  for (const r of rows) {
    if (!groups.has(r.vaccineTypeId)) groups.set(r.vaccineTypeId, []);
    groups.get(r.vaccineTypeId).push(r);
  }
  return [...groups.entries()].map(([vaccineTypeId, doses]) => {
    const { name, species } = doses[0];
    const loai = species === 'DOG' ? 'chó' : 'mèo';
    const lines = doses.sort((a, b) => a.dose - b.dose).map((d) =>
      `- Mũi ${d.dose}: từ ${d.minAgeWeeks} tuần tuổi trở lên; khoảng cách tới mũi kế: ${d.intervalDays} ngày; ${d.requiredForBoarding ? 'bắt buộc' : 'không bắt buộc'} khi lưu trú.`);
    const content = `# Phác đồ tiêm vaccine ${name} cho ${loai}\n\n## Các mũi trong phác đồ\nPhác đồ tiêm vaccine ${name} dành cho ${loai} trong danh mục của hệ thống Pet Care gồm ${doses.length} mũi:\n${lines.join('\n')}`;
    return {
      sourceType: 'CATALOG_PROTOCOL', vaccineTypeId, slug: `catalog-${strip(name).replace(/\s+/g, '-')}-${species === 'DOG' ? 'cho' : 'meo'}`,
      title: `Phác đồ tiêm vaccine ${name} cho ${loai}`, topic: 'VACCINATION', species, sourceNote: null, content, contentHash: sha(content),
    };
  });
}

const docs = [
  ...readdirSync(join(dir, 'kb')).filter((f) => f.endsWith('.md')).sort().map(parseManual),
  ...catalogDocs(),
];
for (const d of docs) d.chunks = chunkDoc(d);

// Nhúng: dùng bộ nhớ đệm (khóa = mô hình + số chiều + loại tác vụ + nội dung) để phiên sau không tốn hạn mức.
mkdirSync(cacheDir, { recursive: true });
const cache = existsSync(cacheFile) ? JSON.parse(readFileSync(cacheFile, 'utf8')) : {};
const keyOf = (t) => sha(`${MODEL}|${DIM}|RETRIEVAL_DOCUMENT|${t}`);
const missing = [...new Set(docs.flatMap((d) => d.chunks.map((c) => c.embedText)).filter((t) => !cache[keyOf(t)]))];
const total = docs.reduce((n, d) => n + d.chunks.length, 0);
console.log(`KB: ${docs.length} tài liệu (${docs.filter((d) => d.sourceType === 'MANUAL').length} MANUAL + ${docs.filter((d) => d.sourceType !== 'MANUAL').length} CATALOG_PROTOCOL), ${total} đoạn; cần nhúng ${missing.length}, đã có trong bộ nhớ đệm ${total - missing.length}`);
let embedMs = 0;
if (missing.length) {
  const gemini = createGemini();
  const t0 = performance.now();
  const vectors = await gemini.embedDocuments(missing);
  embedMs = performance.now() - t0;
  missing.forEach((t, i) => { cache[keyOf(t)] = vectors[i]; });
  writeFileSync(cacheFile, JSON.stringify(cache));
  console.log(`Nhúng ${missing.length} đoạn mất ${(embedMs / 1000).toFixed(1)} s (gồm thời gian giãn nhịp); ${JSON.stringify(gemini.stats.embed)}`);
}

const db = openDb();
await db.query('TRUNCATE ai_chunks, ai_documents RESTART IDENTITY CASCADE');
for (const d of docs) {
  const rows = d.chunks.map((c) => `(${lit(d.slug)}, 1, ${c.ordinal}, ${lit(c.heading)}, ${lit(c.text)}, ${vec(cache[keyOf(c.embedText)])}, ${lit(MODEL)})`);
  await db.query(`BEGIN;
INSERT INTO ai_documents (slug, source_type, vaccine_type_id, title, topic, species, content, source_note, content_hash, status, version, published_at)
VALUES (${lit(d.slug)}, ${lit(d.sourceType)}, ${d.vaccineTypeId ?? 'NULL'}, ${lit(d.title)}, ${lit(d.topic)}, ${lit(d.species)}, ${lit(d.content)}, ${lit(d.sourceNote)}, ${lit(d.contentHash ?? null)}, 'PUBLISHED', 1, now());
INSERT INTO ai_chunks (document_id, doc_version, ordinal, heading, text, embedding, embedding_model)
SELECT d.id, v.ver, v.ord, v.head, v.txt, v.emb, v.model FROM ai_documents d
JOIN (VALUES ${rows.join(',\n')}) AS v(slug, ver, ord, head, txt, emb, model) ON v.slug = d.slug;
UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE slug = ${lit(d.slug)};
COMMIT`);
}
const stat = await db.json(`SELECT json_build_object('docs', (SELECT count(*) FROM ai_documents), 'chunks', (SELECT count(*) FROM ai_chunks),
  'avgChunkChars', (SELECT round(avg(char_length(text))) FROM ai_chunks), 'minChunkChars', (SELECT min(char_length(text)) FROM ai_chunks),
  'maxChunkChars', (SELECT max(char_length(text)) FROM ai_chunks), 'dims', (SELECT array_agg(DISTINCT vector_dims(embedding)) FROM ai_chunks),
  'indexed', (SELECT count(*) FROM ai_documents WHERE index_status = 'INDEXED' AND indexed_version = version))`);
console.log('Đã nạp:', JSON.stringify(stat));
db.close();
