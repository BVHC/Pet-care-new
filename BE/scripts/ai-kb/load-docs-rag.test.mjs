// Kiểm phần thuần (đọc frontmatter, ánh xạ sang UC93) của load-docs-rag.mjs; không gọi mạng.
// Chạy: node --test BE/scripts/ai-kb/load-docs-rag.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseRagDoc, toCreateRequest, TOPIC_MAP } from './load-docs-rag.mjs';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const ragDir = join(repoRoot, 'docs', 'docs_RAG');

// 21 giá trị topic có trong bộ ngày 13/11/2026 (grep "^topic:" docs/docs_RAG/0[1-6]_*/*.md)
const TOPICS_IN_REPO = ['adoption', 'basic_care', 'cat_care', 'cat_environment', 'dental_care', 'dog_behavior', 'dog_care',
  'emergency_triage', 'family_safety', 'first_aid', 'grooming_skin', 'kitten_care', 'multi_pet_household', 'nutrition',
  'parasite_prevention', 'poisoning_safety', 'puppy_care', 'senior_care', 'travel', 'vaccination', 'weight_management'];

const base = {
  documentId: 'PET-HEALTH-001', title: 'Tổng quan vaccine cho chó mèo', topic: 'vaccination', species: ['dog', 'cat'],
  riskLevel: 'HIGH', version: 1, status: 'DRAFT', approved: false, reviewerName: null, reviewedAt: null,
  sourceUrls: ['https://avma.org/resources-tools/pet-owners/petcare/vaccinations'], content: '# Tổng quan vaccine\n\nx',
};

test('frontmatter dạng inline (review: {…}) đọc được từ file thật', () => {
  const d = parseRagDoc(readFileSync(join(ragDir, '04_health_prevention', 'VACCINATION_OVERVIEW.md'), 'utf8'), 'VACCINATION_OVERVIEW.md');
  assert.equal(d.documentId, 'PET-HEALTH-001');
  assert.equal(d.riskLevel, 'HIGH');
  assert.equal(d.approved, false);
  assert.equal(d.status, 'DRAFT');
  assert.deepEqual(d.species, ['dog', 'cat']);
  assert.ok(d.content.startsWith('# Tổng quan vaccine'));
  assert.ok(!d.content.includes('document_id:'), 'frontmatter không được lọt vào nội dung');
  assert.deepEqual(d.sourceUrls, ['https://avma.org/resources-tools/pet-owners/petcare/vaccinations',
    'https://www.aaha.org/for-veterinary-professionals/aaha-guidelines/']);
});

test('frontmatter dạng khối (review: nhiều dòng) đọc được từ file thật', () => {
  const d = parseRagDoc(readFileSync(join(ragDir, '00_governance', 'KB_POLICY.md'), 'utf8'), 'KB_POLICY.md');
  assert.equal(d.documentId, 'KB-POLICY-001');
  assert.equal(d.approved, false);
  assert.equal(d.reviewedAt, null);
});

test('ánh xạ topic / species / sourceNote đúng bảng README', () => {
  const r = toCreateRequest(base);
  assert.equal(r.topic, 'VACCINATION');
  assert.equal(r.species, 'ALL');
  assert.equal(r.reviewerName, undefined);
  assert.equal(r.reviewedOn, undefined);
  assert.equal(r.title, base.title);
  assert.equal(r.content, base.content);
  assert.match(r.sourceNote, /^\[docs_RAG PET-HEALTH-001 v1 HIGH\] https:\/\/avma/);
  assert.equal(toCreateRequest({ ...base, species: ['dog'] }).species, 'DOG');
  assert.equal(toCreateRequest({ ...base, species: ['cat'] }).species, 'CAT');
});

test('topic lạ thì không đoán', () => {
  assert.throws(() => toCreateRequest({ ...base, topic: 'blockchain' }), /topic/);
});

test('21 topic của docs_RAG đều có trong TOPIC_MAP', () => {
  for (const t of TOPICS_IN_REPO) assert.ok(TOPIC_MAP[t], `thiếu ${t}`);
});

test('REJECTED / EXPIRED bị bỏ qua', () => {
  assert.ok(toCreateRequest({ ...base, status: 'REJECTED' }).skip);
  assert.ok(toCreateRequest({ ...base, status: 'EXPIRED' }).skip);
});

test('VERIFIED + approved mới có người rà soát; VERIFIED thiếu approved vẫn là DRAFT', () => {
  const r = toCreateRequest({ ...base, status: 'VERIFIED', approved: true, reviewerName: 'BS. A', reviewedAt: '2026-12-01' });
  assert.equal(r.reviewerName, 'BS. A');
  assert.equal(r.reviewedOn, '2026-12-01');
  const d = toCreateRequest({ ...base, status: 'VERIFIED', approved: false, reviewerName: 'BS. A', reviewedAt: '2026-12-01' });
  assert.equal(d.reviewerName, undefined);
});

test('sourceNote cắt 500 ký tự', () => {
  const r = toCreateRequest({ ...base, sourceUrls: Array(40).fill('https://x.y/' + 'a'.repeat(30)) });
  assert.ok(r.sourceNote.length <= 500);
});

test('nội dung quá 20 000 ký tự thì ném', () => {
  assert.throws(() => toCreateRequest({ ...base, content: 'a'.repeat(20_001) }), /20 000/);
});

// ---- --publish-demo và --archive-superseded (13/11/2026, Hiến chọn B) -------------------------------------------------
import { demoReviewPatch, supersededOldDocs, indexByTitle, KEEP_TITLES } from './load-docs-rag.mjs';

test('demoReviewPatch: thêm người rà soát giả định, nhãn "giả định" trong sourceNote, ≤ 500 ký tự', () => {
  const p = demoReviewPatch({ sourceNote: '[docs_RAG X v1 LOW] https://a.b' }, 'BS. Nguyễn Văn Nam', '2026-11-13');
  assert.equal(p.reviewerName, 'BS. Nguyễn Văn Nam');
  assert.equal(p.reviewedOn, '2026-11-13');
  assert.match(p.sourceNote, /Duyệt giả định cho demo\.$/);
  assert.ok(demoReviewPatch({ sourceNote: 'x'.repeat(495) }, 'A', '2026-11-13').sourceNote.length <= 500);
  assert.match(demoReviewPatch({ sourceNote: 'x'.repeat(495) }, 'A', '2026-11-13').sourceNote, /Duyệt giả định cho demo\.$/);
});

test('demoReviewPatch không thêm nhãn hai lần', () => {
  const once = demoReviewPatch({ sourceNote: 'a' }, 'A', '2026-11-13').sourceNote;
  assert.equal(demoReviewPatch({ sourceNote: once }, 'A', '2026-11-13').sourceNote, once);
});

test('supersededOldDocs: chỉ MANUAL PUBLISHED thuộc kho demo cũ, không thuộc docs_RAG, không thuộc KEEP_TITLES', () => {
  const docs = [
    { documentId: 1, title: 'Cũ A', status: 'PUBLISHED', sourceType: 'MANUAL' },
    { documentId: 2, title: KEEP_TITLES[0], status: 'PUBLISHED', sourceType: 'MANUAL' },
    { documentId: 3, title: 'Mới R', status: 'PUBLISHED', sourceType: 'MANUAL' },
    { documentId: 4, title: 'Phác đồ tiêm Dại (mèo)', status: 'PUBLISHED', sourceType: 'CATALOG_PROTOCOL' },
    { documentId: 5, title: 'Cũ B', status: 'ARCHIVED', sourceType: 'MANUAL' },
    { documentId: 6, title: 'Cũ C', status: 'DRAFT', sourceType: 'MANUAL' },
    { documentId: 7, title: 'Tài liệu Hiến tự nạp sau này', status: 'PUBLISHED', sourceType: 'MANUAL' },
  ];
  const oldKb = new Set(['Cũ A', 'Cũ B', 'Cũ C', KEEP_TITLES[0], 'Mới R']);
  assert.deepEqual(supersededOldDocs(docs, new Set(['Mới R']), oldKb), [1]);
});

test('supersededOldDocs không bao giờ lưu trữ tài liệu ngoài kho demo cũ (kể cả khi chạy lại sau khi người dùng nạp thêm)', () => {
  const mine = [{ documentId: 9, title: 'Của Hiến', status: 'PUBLISHED', sourceType: 'MANUAL' }];
  assert.deepEqual(supersededOldDocs(mine, new Set(), new Set(['Cũ A'])), []);
});

test('indexByTitle: trùng tiêu đề thì ưu tiên tài liệu chưa lưu trữ, bất kể thứ tự', () => {
  const rows = [
    { documentId: 59, title: 'Hành vi và huấn luyện chó', status: 'ARCHIVED' },
    { documentId: 38, title: 'Hành vi và huấn luyện chó', status: 'PUBLISHED' },
    { documentId: 70, title: 'Chỉ đã lưu trữ', status: 'ARCHIVED' },
  ];
  assert.equal(indexByTitle(rows).get('Hành vi và huấn luyện chó').documentId, 38);
  assert.equal(indexByTitle([...rows].reverse()).get('Hành vi và huấn luyện chó').documentId, 38);
  assert.equal(indexByTitle(rows).get('Chỉ đã lưu trữ').documentId, 70);
});

test('KEEP_TITLES là 4 tài liệu riêng của Pet Care', () => { assert.equal(KEEP_TITLES.length, 4); });
