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
