import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PageResponse } from '../../types';
import type { AiDocument, AiDocumentSummary, ChatResponse, ItemProfileRow, RecommendationResponse, VaccinationSummary } from '../../types/ai';
import { vnDate } from '../../utils/vn-time';
import {
  AI_DAILY_LLM_LIMIT,
  aiProviderLog,
  attributeFit,
  detectPersonalIntent,
  hasPhrase,
  maskPersonal,
  mentionsPet,
  recoScore,
  vaccinationItemOf,
} from './ai.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { recordDoseForMock } from './visit-clinical.mock';
import { installMockApi } from './mock-api';

// Mock ai.mock.ts, qua axios như care.mock.test.ts. Dữ liệu mẫu (ai.seed.ts): 12 tài liệu MANUAL (id 1–12: PUBLISHED 1–8 và 10, trong đó #8 FAILED v2 đang phục vụ bản v1; DRAFT 9 và 12; ARCHIVED 11)
// cộng 4 tài liệu CATALOG_PROTOCOL (id 13–16) sinh từ phác đồ của catalog.mock; hồ sơ gợi ý của dịch vụ 4 và sản phẩm 1, 2 (dịch vụ 3 chưa có).

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const manager = () => as('chuoi@petcare.vn');
const customer = () => as('khach@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}
const errorOf = async (request: Promise<unknown>) => {
  const res = await failure(request);
  return [res.status, res.data.errorCode, res.data.message] as const;
};

const ROLES = {
  ADMIN: 'admin@petcare.vn',
  SUPER_MANAGER: 'chuoi@petcare.vn',
  BRANCH_MANAGER: 'manager@store1.vn',
  RECEPTIONIST: 'reception@store1.vn',
  VET: 'doctor@store1.vn',
  CARETAKER: 'caretaker@store1.vn',
  CUSTOMER: 'khach@petcare.vn',
} as const;

const STATE_KEY = 'petcare-mock-ai';
const storedState = () => JSON.parse(localStorage.getItem(STATE_KEY) ?? '{}');

// ─── Phân quyền: 7 vai trò × mọi endpoint + 401 không token ───

type Call = (auth?: object) => Promise<unknown>;
const KNOWLEDGE_CALLS: Record<string, Call> = {
  '#5 GET /ai/documents': (a) => client.get('/api/ai/documents', a),
  '#6 POST /ai/documents': (a) => client.post('/api/ai/documents', { title: 'T', topic: 'CARE', species: 'ALL', content: 'x' }, a),
  '#7 GET /ai/documents/{id}': (a) => client.get('/api/ai/documents/1', a),
  '#8 PATCH /ai/documents/{id}': (a) => client.patch('/api/ai/documents/9', { topic: 'CARE' }, a),
  '#9 POST /ai/documents/{id}/publish': (a) => client.post('/api/ai/documents/11/publish', {}, a),
  '#10 POST /ai/documents/{id}/archive': (a) => client.post('/api/ai/documents/9/archive', {}, a),
  '#11 POST /ai/documents/{id}/reindex': (a) => client.post('/api/ai/documents/1/reindex', {}, a),
  '#12 GET /ai/item-profiles': (a) => client.get('/api/ai/item-profiles', a),
  '#13 PUT /ai/item-profiles/{type}/{id}': (a) => client.put('/api/ai/item-profiles/SERVICE/3', { breedKeywords: [] }, a),
};

describe('phân quyền #5–#13: chỉ SUPER_MANAGER (BR-AI-17)', () => {
  it.each(Object.keys(KNOWLEDGE_CALLS))('%s: 7 vai trò + không token', async (name) => {
    const call = KNOWLEDGE_CALLS[name];
    for (const [role, email] of Object.entries(ROLES)) {
      if (role === 'SUPER_MANAGER') continue;
      expect(await errorOf(call(await as(email))), role).toEqual([403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này']);
    }
    expect((await failure(call())).status).toBe(401);
    const ok = await call(await manager()); // gọi sau cùng: các ca ghi đổi state
    expect((ok as AxiosResponse).status).toBeLessThan(300);
  });

  // Đo trên BE thật 11/11/2026: `@Valid` chạy trước `@PreAuthorize` (VET gửi thân thiếu trường vẫn nhận 400, không phải 403); BR-AI-14 chạy trong service nên đứng SAU vai trò.
  it('thứ tự đo trên BE: 400 sai kiểu → 400 VALIDATION_FAILED → 403 vai trò → BR (id sai kiểu vẫn 400)', async () => {
    const vet = await as(ROLES.VET);
    const complete = { topic: 'CARE', species: 'ALL', content: 'x' };
    expect((await failure(client.post('/api/ai/documents', { title: '' }, vet))).data.errorCode).toBe('VALIDATION_FAILED'); // thiếu topic / species / content: VALIDATION trước vai trò
    expect((await failure(client.post('/api/ai/documents', { ...complete, title: '' }, vet))).status).toBe(403); // tiêu đề rỗng là BR-AI-14 (service): vai trò chặn trước
    expect((await failure(client.post('/api/ai/documents', { ...complete, title: 'T' }, vet))).status).toBe(403);
    expect(await errorOf(client.get('/api/ai/documents/abc', vet))).toEqual([400, 'MALFORMED_REQUEST', "Tham số 'documentId' sai kiểu dữ liệu"]);
    expect((await failure(client.get('/api/ai/documents', { params: { status: 'NOPE' }, ...vet }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.patch('/api/ai/documents/9', { title: 5 }, vet))).data.errorCode).toBe('MALFORMED_REQUEST'); // sai kiểu thân đến trước
    expect((await failure(client.patch('/api/ai/documents/9', { sourceNote: 'x'.repeat(501) }, vet))).data.errorCode).toBe('VALIDATION_FAILED'); // @Size trước vai trò
    expect((await failure(client.patch('/api/ai/documents/9', { topic: 'CARE' }, vet))).status).toBe(403);
    expect((await failure(client.put('/api/ai/item-profiles/KENNEL/3', { breedKeywords: [] }, vet))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.put('/api/ai/item-profiles/SERVICE/3', { breedKeywords: [], minAgeWeeks: 9, maxAgeWeeks: 1 }, vet))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.put('/api/ai/item-profiles/SERVICE/3', {}, vet))).data.errorCode).toBe('VALIDATION_FAILED'); // thiếu breedKeywords
    expect((await failure(client.put('/api/ai/item-profiles/SERVICE/3', { breedKeywords: [] }, vet))).status).toBe(403);
  });
});

// ─── Tài liệu tri thức (#5–#11) ───

const listDocs = async (params: object = {}) => (await client.get('/api/ai/documents', { params, ...(await manager()) })).data.data as PageResponse<AiDocumentSummary>;
const getDoc = async (id: number) => (await client.get(`/api/ai/documents/${id}`, await manager())).data.data as AiDocument;
const postDoc = async (path: string, body: object = {}) => client.post(`/api/ai/documents${path}`, body, await manager());
const patchDoc = async (id: number, body: object) => client.patch(`/api/ai/documents/${id}`, body, await manager());
const reviewed = { sourceNote: 'Tài liệu tự soạn', reviewerName: 'BS. Kiểm thử', reviewedOn: '2026-11-05' };

describe('GET /ai/documents (#5)', () => {
  it('mới sửa trước, trùng thì id giảm dần; 16 tài liệu mẫu gồm 4 CATALOG_PROTOCOL sinh từ phác đồ', async () => {
    const page = await listDocs({ size: 100 });
    expect(page).toMatchObject({ page: 0, size: 100, totalElements: 16, totalPages: 1 });
    const times = page.content.map((d) => Date.parse(d.updatedAt));
    expect(times).toEqual([...times].sort((a, b) => b - a));
    expect(page.content[0]).not.toHaveProperty('content'); // Summary: không có nội dung
    const catalog = page.content.filter((d) => d.sourceType === 'CATALOG_PROTOCOL');
    expect(catalog.map((d) => d.title).sort()).toEqual(['Lịch tiêm 4 bệnh cho mèo', 'Lịch tiêm 5 bệnh cho chó', 'Lịch tiêm Dại cho chó', 'Lịch tiêm Dại cho mèo']);
    expect(catalog.every((d) => d.status === 'PUBLISHED' && d.topic === 'VACCINATION')).toBe(true);
  });

  it('lọc status / topic / species / q (q theo tiêu đề, không phân biệt dấu); phân trang kẹp như các danh sách khác', async () => {
    expect((await listDocs({ status: 'DRAFT' })).content.map((d) => d.documentId).sort((a, b) => a - b)).toEqual([9, 12]);
    expect((await listDocs({ status: 'ARCHIVED' })).content.map((d) => d.documentId)).toEqual([11]);
    expect((await listDocs({ topic: 'HYGIENE' })).totalElements).toBe(2);
    expect((await listDocs({ species: 'CAT', status: 'PUBLISHED' })).content.map((d) => d.documentId)).toEqual(expect.arrayContaining([2]));
    expect((await listDocs({ q: 'thuc pham doc' })).content.map((d) => d.documentId)).toEqual([4]);
    expect(await listDocs({ q: 'không có tài liệu này' })).toMatchObject({ totalElements: 0, content: [] });
    expect(await listDocs({ page: 1, size: 5 })).toMatchObject({ page: 1, size: 5, totalElements: 16, totalPages: 4 });
    expect(await listDocs({ size: 500 })).toMatchObject({ size: 100 });
    expect(await listDocs({ page: -2, size: 0 })).toMatchObject({ page: 0, size: 1 });
    expect((await failure(client.get('/api/ai/documents', { params: { page: 'x' }, ...(await manager()) }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('POST /ai/documents (#6) và GET /ai/documents/{id} (#7)', () => {
  it('tạo → 201 DRAFT, version 1, chưa nạp, thiếu nguồn vẫn lưu nháp được; GET trả đủ trường, không lộ đoạn đang phục vụ', async () => {
    const res = await client.post('/api/ai/documents', { title: '  Bản nháp mới  ', topic: 'BEHAVIOR', species: 'DOG', content: '' }, await manager());
    expect(res.status).toBe(201);
    expect(res.data.code).toBe(201);
    const doc = res.data.data as AiDocument;
    expect(doc).toMatchObject({
      sourceType: 'MANUAL', title: 'Bản nháp mới', topic: 'BEHAVIOR', species: 'DOG', content: '', sourceNote: null, reviewerName: null, reviewedOn: null,
      status: 'DRAFT', version: 1, indexedVersion: 0, indexStatus: 'PENDING', indexError: null, chunkCount: 0, publishedAt: null,
    });
    expect(doc.documentId).toBe(17);
    expect(doc).not.toHaveProperty('served');
    expect(await getDoc(17)).toEqual(doc);
    expect((await listDocs()).content[0].documentId).toBe(17); // mới sửa trước
  });

  it('BR-AI-14: tiêu đề rỗng / quá 200 ký tự, nội dung quá 20 000 ký tự; ngay 200 và 20 000 thì được', async () => {
    const valid = { topic: 'CARE', species: 'ALL', content: 'x' };
    const post = async (body: object) => client.post('/api/ai/documents', body, await manager());
    expect(await errorOf(post({ ...valid, title: '   ' }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Tiêu đề không được rỗng (BR-AI-14)']);
    expect((await errorOf(post(valid)))[2]).toBe('Tiêu đề không được rỗng (BR-AI-14)');
    expect(await errorOf(post({ ...valid, title: 'T'.repeat(201) }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Tiêu đề tối đa 200 ký tự (BR-AI-14)']);
    expect(await errorOf(post({ ...valid, title: 'T', content: 'x'.repeat(20_001) }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Nội dung tối đa 20 000 ký tự (BR-AI-14)']);
    expect((await post({ ...valid, title: 'T'.repeat(200), content: 'x'.repeat(20_000) })).status).toBe(201);
  });

  it('VALIDATION_FAILED: thiếu topic / species / content, sourceNote > 500, reviewerName > 100 (trước BR-AI-14); enum / ngày / kiểu sai là MALFORMED_REQUEST', async () => {
    const post = async (body: unknown) => client.post('/api/ai/documents', body, await manager());
    expect(await errorOf(post({ title: '' }))).toEqual([400, 'VALIDATION_FAILED', 'species: Loài áp dụng không được để trống; topic: Chủ đề không được để trống; content: Nội dung không được để trống']);
    expect((await errorOf(post({ title: 'T', topic: 'CARE', species: 'ALL', content: 'x', sourceNote: 'x'.repeat(501), reviewerName: 'x'.repeat(101) })))[2]).toBe(
      'sourceNote: Ghi chú nguồn tối đa 500 ký tự; reviewerName: Tên người rà soát tối đa 100 ký tự',
    );
    for (const bad of [{ topic: 'PETS' }, { species: 'BIRD' }, { title: 7 }, { reviewedOn: '05/11/2026' }, { reviewedOn: '2026-02-30' }, { sourceNote: 3 }]) {
      expect((await failure(post({ title: 'T', topic: 'CARE', species: 'ALL', content: 'x', ...bad }))).data.errorCode, JSON.stringify(bad)).toBe('MALFORMED_REQUEST');
    }
    expect((await failure(post('chuỗi'))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(post([]))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('GET: id không có → 404, id sai kiểu → 400, tài liệu CATALOG_PROTOCOL không có nguồn / người rà soát', async () => {
    expect(await errorOf(client.get('/api/ai/documents/999', await manager()))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy tài liệu tri thức #999']);
    expect((await failure(client.get('/api/ai/documents/x', await manager()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect(await getDoc(13)).toMatchObject({ sourceType: 'CATALOG_PROTOCOL', sourceNote: null, reviewerName: null, reviewedOn: null, status: 'PUBLISHED', indexStatus: 'INDEXED', indexedVersion: 1 });
    expect((await getDoc(13)).chunkCount).toBeGreaterThan(0);
  });
});

describe('PATCH /ai/documents/{id} (#8)', () => {
  it('sửa nháp tự do: không tăng version, không nạp; PATCH rỗng hoặc không đổi gì thì trả nguyên văn', async () => {
    const before = await getDoc(9);
    const res = await patchDoc(9, { title: 'Chó sủa nhiều (sửa)', topic: 'CARE', content: 'Nội dung mới', sourceNote: '  Nguồn  ' });
    expect(res.data.data).toMatchObject({ documentId: 9, title: 'Chó sủa nhiều (sửa)', topic: 'CARE', species: 'DOG', content: 'Nội dung mới', sourceNote: 'Nguồn', status: 'DRAFT', version: 1, indexedVersion: 0 });
    expect(Date.parse(res.data.data.updatedAt)).toBeGreaterThanOrEqual(Date.parse(before.updatedAt));
    const same = await patchDoc(9, {});
    expect(same.data.data).toEqual(res.data.data);
    expect((await patchDoc(9, { title: 'Chó sủa nhiều (sửa)' })).data.data).toEqual(res.data.data);
    expect((await patchDoc(9, { sourceNote: '' })).data.data.sourceNote).toBeNull(); // chuỗi rỗng xóa nguồn của bản nháp
  });

  it('PUBLISHED: sửa tiêu đề tăng version, PENDING tới khi ST22 nạp (lần đọc sau); sửa topic không tăng version', async () => {
    const res = await patchDoc(5, { title: 'Tắm và chải lông (bản 2)' });
    expect(res.data.data).toMatchObject({ status: 'PUBLISHED', version: 2, indexedVersion: 1, indexStatus: 'PENDING' });
    expect(await getDoc(5)).toMatchObject({ version: 2, indexedVersion: 2, indexStatus: 'INDEXED', indexError: null });
    const topicOnly = await patchDoc(5, { topic: 'CARE' });
    expect(topicOnly.data.data).toMatchObject({ topic: 'CARE', version: 2, indexStatus: 'INDEXED' });
  });

  it('PUBLISHED: sửa NỘI DUNG phải kèm người và ngày rà soát mới (BR-AI-14); không xóa được nguồn / người rà soát', async () => {
    const message = 'Sửa nội dung tài liệu đã xuất bản phải cập nhật lại người và ngày rà soát (BR-AI-14)';
    expect(await errorOf(patchDoc(6, { content: 'Nội dung khác' }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', message]);
    expect((await errorOf(patchDoc(6, { content: 'Nội dung khác', reviewerName: 'BS. A' })))[2]).toBe(message); // thiếu ngày
    const ok = await patchDoc(6, { content: 'Nội dung khác', reviewerName: 'BS. A', reviewedOn: '2026-11-06' });
    expect(ok.data.data).toMatchObject({ version: 2, reviewerName: 'BS. A', reviewedOn: '2026-11-06', indexStatus: 'PENDING' });
    expect((await errorOf(patchDoc(6, { sourceNote: '' })))[2]).toBe('Tài liệu đã xuất bản phải có ghi chú nguồn (lớp 1) (BR-AI-14)');
    expect((await errorOf(patchDoc(6, { reviewerName: ' ' })))[2]).toBe('Tài liệu đã xuất bản phải có người và ngày rà soát (lớp 2) (BR-AI-14)');
    expect((await errorOf(patchDoc(6, { content: '', reviewerName: 'BS. A', reviewedOn: '2026-11-06' })))[2]).toBe('Tài liệu đã xuất bản không được để trống nội dung (BR-AI-14)');
  });

  it('CATALOG_PROTOCOL → 400 BR-AI-13 (không sửa tay); ARCHIVED → 409; tiêu đề rỗng / nội dung quá dài → 400 BR-AI-14 (trước 409)', async () => {
    expect(await errorOf(patchDoc(13, { title: 'Đổi tên' }))).toEqual([
      400, 'BUSINESS_RULE_VIOLATION', 'Tài liệu sinh từ phác đồ tiêm do hệ thống quản lý, không sửa tay (BR-AI-13)',
    ]);
    expect(await errorOf(patchDoc(11, { title: 'Đổi tên' }))).toEqual([409, 'INVALID_STATE_TRANSITION', 'Tài liệu đã lưu trữ (ARCHIVED) không sửa được']);
    expect((await errorOf(patchDoc(11, { title: ' ' })))[2]).toBe('Tiêu đề không được để trống (BR-AI-14)');
    expect((await errorOf(patchDoc(9, { content: 'x'.repeat(20_001) })))[2]).toBe('Nội dung tối đa 20 000 ký tự (BR-AI-14)');
    expect((await errorOf(patchDoc(999, { title: 'x' })))[0]).toBe(404);
  });
});

describe('FSM #13: publish / archive / reindex (#9–#11) và chỉ mục (ST22)', () => {
  it('DRAFT → PUBLISHED: thiếu nguồn, người / ngày rà soát, nội dung thì liệt kê đủ; đủ thì PENDING rồi INDEXED ở lần đọc sau, có đoạn', async () => {
    expect(await errorOf(postDoc('/12/publish'))).toEqual([
      400, 'BUSINESS_RULE_VIOLATION', 'Không thể xuất bản, còn thiếu: người rà soát và ngày rà soát (lớp 2) (BR-AI-14)',
    ]);
    expect((await errorOf(postDoc('/9/publish')))[2]).toBe('Không thể xuất bản, còn thiếu: ghi chú nguồn (lớp 1); người rà soát và ngày rà soát (lớp 2) (BR-AI-14)');
    await patchDoc(9, { ...reviewed, content: '' });
    expect((await errorOf(postDoc('/9/publish')))[2]).toBe('Không thể xuất bản, còn thiếu: nội dung (BR-AI-14)');
    await patchDoc(9, { content: '## Mục\nNội dung đủ dài để có đoạn.' });

    const res = await postDoc('/9/publish');
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ status: 'PUBLISHED', version: 1, indexedVersion: 0, indexStatus: 'PENDING', chunkCount: 0 });
    expect(res.data.data.publishedAt).toEqual(expect.any(String));
    const indexed = await getDoc(9);
    expect(indexed).toMatchObject({ indexedVersion: 1, indexStatus: 'INDEXED', indexError: null });
    expect(indexed.chunkCount).toBeGreaterThan(0);
  });

  it('trạng thái sai → 409 INVALID_STATE_TRANSITION: xuất bản PUBLISHED, lưu trữ ARCHIVED, nạp lại tài liệu không PUBLISHED', async () => {
    expect(await errorOf(postDoc('/1/publish'))).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái tài liệu tri thức từ PUBLISHED sang PUBLISHED']);
    expect(await errorOf(postDoc('/11/archive'))).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái tài liệu tri thức từ ARCHIVED sang ARCHIVED']);
    expect(await errorOf(postDoc('/11/reindex'))).toEqual([409, 'INVALID_STATE_TRANSITION', 'Tài liệu ARCHIVED không có chỉ mục để nạp lại']);
    expect((await errorOf(postDoc('/9/reindex')))[0]).toBe(409); // DRAFT
    for (const action of ['publish', 'archive', 'reindex']) expect((await errorOf(postDoc(`/999/${action}`)))[0]).toBe(404);
  });

  it('lưu trữ: PUBLISHED hoặc DRAFT → ARCHIVED (#4, #6); xuất bản lại ARCHIVED → PUBLISHED (#5) giữ version', async () => {
    expect((await postDoc('/1/archive')).data.data).toMatchObject({ status: 'ARCHIVED', version: 1 });
    expect((await postDoc('/9/archive')).data.data.status).toBe('ARCHIVED');
    const again = await postDoc('/1/publish');
    expect(again.data.data).toMatchObject({ status: 'PUBLISHED', version: 1, indexStatus: 'PENDING' });
    expect(await getDoc(1)).toMatchObject({ indexStatus: 'INDEXED', indexedVersion: 1 });
  });

  it('CATALOG_PROTOCOL lưu trữ được (#4) nhưng xuất bản lại bằng tay → 400 BR-AI-13', async () => {
    // BE (đo 11/11/2026): xuất bản tay tài liệu phác đồ luôn 400 BR-AI-13, kể cả khi đang PUBLISHED (không phải 409 PUBLISHED → PUBLISHED).
    expect(await errorOf(postDoc('/13/publish'))).toEqual([
      400, 'BUSINESS_RULE_VIOLATION', 'Tài liệu sinh từ phác đồ tiêm do hệ thống quản lý, không xuất bản tay (BR-AI-13)',
    ]);
    expect((await postDoc('/13/archive')).data.data.status).toBe('ARCHIVED');
    expect(await errorOf(postDoc('/13/publish'))).toEqual([
      400, 'BUSINESS_RULE_VIOLATION', 'Tài liệu sinh từ phác đồ tiêm do hệ thống quản lý, không xuất bản tay (BR-AI-13)',
    ]);
  });

  it('reindex: 202 + PENDING, idempotent, gỡ lỗi nạp; tài liệu #8 đang FAILED (v2 chờ nạp, v1 vẫn phục vụ) nạp lại được', async () => {
    expect(await getDoc(8)).toMatchObject({ status: 'PUBLISHED', version: 2, indexedVersion: 1, indexStatus: 'FAILED' });
    expect((await getDoc(8)).indexError).toContain('429');
    const res = await postDoc('/8/reindex');
    expect(res.status).toBe(202);
    expect(res.data.code).toBe(202);
    expect(res.data.data).toMatchObject({ indexStatus: 'PENDING', indexError: null, indexedVersion: 1 });
    expect((await postDoc('/8/reindex')).data.data).toMatchObject({ indexStatus: 'PENDING' }); // gọi lại: vẫn 202
    expect(await getDoc(8)).toMatchObject({ indexStatus: 'INDEXED', indexedVersion: 2, indexError: null });
  });
});

// ─── Hồ sơ gợi ý (#12, #13) ───

const listProfiles = async (params: object = {}) => (await client.get('/api/ai/item-profiles', { params, ...(await manager()) })).data.data as PageResponse<ItemProfileRow>;
const putProfile = async (path: string, body: unknown) => client.put(`/api/ai/item-profiles/${path}`, body, await manager());

describe('GET /ai/item-profiles (#12)', () => {
  it('chỉ dịch vụ thẩm mỹ và sản phẩm GOODS không kê đơn đang kinh doanh; dịch vụ trước, mỗi loại theo tên rồi id (BE: Sort name, id)', async () => {
    const page = await listProfiles();
    expect(page.content.map((r) => `${r.itemType}:${r.itemId}`)).toEqual(['SERVICE:4', 'SERVICE:3', 'PRODUCT:1', 'PRODUCT:2']); // "Cắt tỉa lông" trước "Tắm & sấy"
    // khám / tiêm (1, 2), loại chuồng (5, 6), thuốc (3, 4), vaccine (5, 6), sản phẩm ngừng bán (7) đều không có mặt
    expect(page.content[1]).toEqual({ itemType: 'SERVICE', itemId: 3, name: 'Tắm & sấy', group: 'GROOMING', hasProfile: false });
    expect(page.content[0]).toMatchObject({ hasProfile: true, profile: { species: 'DOG', minAgeWeeks: 12, minWeightKg: 5, breedKeywords: ['poodle', 'phoc soc', 'shih tzu'], repeatIntervalDays: 45 } });
    expect(page.content[2]).toMatchObject({ group: 'PRODUCT', name: 'Hạt Royal Canin Mini Adult 2kg' });
  });

  it('lọc itemType, missingOnly, q (không phân biệt dấu); sai kiểu → 400', async () => {
    expect((await listProfiles({ itemType: 'PRODUCT' })).totalElements).toBe(2);
    expect((await listProfiles({ missingOnly: true })).content.map((r) => r.itemId)).toEqual([3]);
    expect((await listProfiles({ missingOnly: false })).totalElements).toBe(4);
    expect((await listProfiles({ q: 'tam' })).content.map((r) => r.itemId)).toEqual([3]);
    expect((await listProfiles({ size: 2, page: 1 })).content.map((r) => r.itemId)).toEqual([1, 2]);
    expect((await failure(client.get('/api/ai/item-profiles', { params: { itemType: 'KENNEL' }, ...(await manager()) }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/ai/item-profiles', { params: { missingOnly: 'yes' }, ...(await manager()) }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('PUT /ai/item-profiles/{itemType}/{itemId} (#13)', () => {
  const profile = { species: 'DOG', minAgeWeeks: 8, maxAgeWeeks: 520, minWeightKg: 1, maxWeightKg: 8.5, breedKeywords: ['  Poodle ', 'POODLE', 'Phốc Sóc', ''], repeatIntervalDays: 30 };

  it('ghi đè toàn bộ, chuẩn hóa từ khóa giống (thường, không dấu, bỏ trùng); GET thấy ngay; ghi lại cùng nội dung vẫn 200', async () => {
    const res = await putProfile('SERVICE/3', profile);
    expect(res.status).toBe(200);
    expect(res.data.data).toEqual({
      itemType: 'SERVICE', itemId: 3, name: 'Tắm & sấy', group: 'GROOMING', hasProfile: true,
      profile: { species: 'DOG', minAgeWeeks: 8, maxAgeWeeks: 520, minWeightKg: 1, maxWeightKg: 8.5, breedKeywords: ['poodle', 'phoc soc'], repeatIntervalDays: 30 },
    });
    expect((await listProfiles({ missingOnly: true })).content).toEqual([]);
    expect((await putProfile('SERVICE/3', profile)).data.data).toEqual(res.data.data);
    const replaced = await putProfile('SERVICE/3', { breedKeywords: ['corgi'] });
    expect(replaced.data.data.profile).toEqual({ minAgeWeeks: null, maxAgeWeeks: null, minWeightKg: null, maxWeightKg: null, breedKeywords: ['corgi'], repeatIntervalDays: null }); // không còn species cũ
  });

  it('mọi trường rỗng = xóa hồ sơ (hasProfile false, không có `profile`); mục chưa có hồ sơ gửi rỗng vẫn 200', async () => {
    const res = await putProfile('PRODUCT/1', { breedKeywords: [], species: null, minAgeWeeks: null });
    expect(res.data.data).toEqual({ itemType: 'PRODUCT', itemId: 1, name: 'Hạt Royal Canin Mini Adult 2kg', group: 'PRODUCT', hasProfile: false });
    expect((await listProfiles({ missingOnly: true })).content.map((r) => r.itemId).sort()).toEqual([1, 3]);
    expect((await putProfile('SERVICE/3', { breedKeywords: [] })).data.data.hasProfile).toBe(false);
  });

  it('VALIDATION_FAILED: thiếu breedKeywords, min > max (tuổi, cân nặng), chu kỳ < 1, âm, từ khóa quá 100 ký tự', async () => {
    expect((await errorOf(putProfile('SERVICE/3', {})))[1]).toBe('VALIDATION_FAILED');
    expect(await errorOf(putProfile('SERVICE/3', { breedKeywords: [], minAgeWeeks: 10, maxAgeWeeks: 5, minWeightKg: 9, maxWeightKg: 2 }))).toEqual([
      400, 'VALIDATION_FAILED', 'minAgeWeeks: Tuổi tối thiểu không được lớn hơn tuổi tối đa; minWeightKg: Cân nặng tối thiểu không được lớn hơn cân nặng tối đa',
    ]);
    expect((await errorOf(putProfile('SERVICE/3', { breedKeywords: [], repeatIntervalDays: 0 })))[2]).toBe('repeatIntervalDays: Chu kỳ dùng lại phải từ 1 đến 32767 ngày');
    expect((await errorOf(putProfile('SERVICE/3', { breedKeywords: [], minWeightKg: -1 })))[1]).toBe('VALIDATION_FAILED');
    expect((await errorOf(putProfile('SERVICE/3', { breedKeywords: ['k'.repeat(101)] })))[1]).toBe('VALIDATION_FAILED');
    expect((await putProfile('SERVICE/3', { breedKeywords: [], minAgeWeeks: 5, maxAgeWeeks: 5, minWeightKg: 2, maxWeightKg: 2 })).status).toBe(200); // bằng nhau được
  });

  it('MALFORMED_REQUEST: itemType / itemId / species sai, kiểu số / mảng sai; 404 mục không có; BR-AI-18 khám, tiêm, loại chuồng, thuốc, vaccine, kê đơn', async () => {
    expect((await errorOf(putProfile('KENNEL/5', { breedKeywords: [] })))[1]).toBe('MALFORMED_REQUEST');
    expect((await errorOf(putProfile('SERVICE/abc', { breedKeywords: [] })))[1]).toBe('MALFORMED_REQUEST');
    for (const bad of [{ species: 'BIRD' }, { minAgeWeeks: 1.5 }, { minWeightKg: '3' }, { breedKeywords: 'poodle' }, { breedKeywords: [1] }, { repeatIntervalDays: 'x' }]) {
      expect((await errorOf(putProfile('SERVICE/3', { breedKeywords: [], ...bad })))[1], JSON.stringify(bad)).toBe('MALFORMED_REQUEST');
    }
    expect((await failure(putProfile('SERVICE/3', 'chuỗi'))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect(await errorOf(putProfile('SERVICE/99', { breedKeywords: [] }))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy dịch vụ #99']);
    expect(await errorOf(putProfile('PRODUCT/99', { breedKeywords: [] }))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy sản phẩm #99']);
    const rule = 'Chỉ lập hồ sơ gợi ý cho dịch vụ thẩm mỹ và sản phẩm bán lẻ không kê đơn (BR-AI-18)';
    for (const item of ['SERVICE/1', 'SERVICE/2', 'SERVICE/5', 'SERVICE/6', 'PRODUCT/3', 'PRODUCT/4', 'PRODUCT/5', 'PRODUCT/6']) {
      expect(await errorOf(putProfile(item, { breedKeywords: [] })), item).toEqual([400, 'BUSINESS_RULE_VIOLATION', rule]);
    }
    expect((await putProfile('PRODUCT/7', { breedKeywords: ['x'] })).status).toBe(200); // sản phẩm ngừng bán vẫn có hồ sơ được (A11)
  });
});

// ─── State lưu từ bản cũ / thiếu trường ───

describe('state localStorage cũ hoặc thiếu trường không làm nổ route', () => {
  it.each([
    ['rỗng {}', '{}'],
    ['chỉ có nextDocumentId', '{"nextDocumentId":3}'],
    ['JSON hỏng', '{khong-phai-json'],
    ['không phải object', '[1,2]'],
  ])('%s → vẫn có 16 tài liệu mẫu và hồ sơ mẫu', async (_name, raw) => {
    localStorage.setItem(STATE_KEY, raw);
    expect((await listDocs()).totalElements).toBe(16);
    expect((await listProfiles()).totalElements).toBe(4);
    expect((await postDoc('/1/reindex')).status).toBe(202);
  });

  it('dòng tài liệu thiếu trường được bù mặc định; id mới không trùng id đã có', async () => {
    localStorage.setItem(STATE_KEY, JSON.stringify({ documents: [{ documentId: 40, title: 'Cũ', status: 'PUBLISHED' }, { title: 'Không id' }] }));
    const page = await listDocs();
    expect(page.totalElements).toBe(2);
    const old = await getDoc(40);
    expect(old).toMatchObject({ sourceType: 'MANUAL', topic: 'CARE', species: 'ALL', content: '', version: 1, status: 'PUBLISHED', indexStatus: 'INDEXED', indexedVersion: 1 });
    const created = await client.post('/api/ai/documents', { title: 'Mới', topic: 'CARE', species: 'ALL', content: '' }, await manager());
    expect(created.data.data.documentId).toBe(41);
    expect(storedState().documents).toHaveLength(3);
  });
});

// ─── PUBLIC_PATH ───

describe('đường public', () => {
  it('POST /api/public/ai/chat không cần token: adapter thật không trả 401 (PUBLIC_PATH đã khớp `public/**`); path /me và /ai vẫn 401', async () => {
    const plain = axios.create();
    const { installMockApi } = await import('./mock-api');
    installMockApi(plain); // adapter thật: chưa đăng ký aiRoutes thì 404 "Chưa có mock", đã đăng ký thì 200; cả hai nghĩa là đã qua cổng đăng nhập
    const status = await plain.post('/api/public/ai/chat', { question: 'x' }).then(
      (res) => res.status,
      (e: unknown) => (axios.isAxiosError(e) && e.response ? e.response.status : -1),
    );
    expect(status).not.toBe(401);
    expect(status).not.toBe(-1);
    expect((await failure(plain.post('/api/me/ai/chat', { question: 'x' }))).status).toBe(401);
    expect((await failure(plain.get('/api/ai/documents'))).status).toBe(401);
    expect((await failure(client.post('/api/me/ai/chat', { question: 'x' }))).status).toBe(401);
  });
});

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
// Chatbot (#1, #2), lịch tiêm (#3), gợi ý (#4)
// ═══════════════════════════════════════════════════════════════════════════════════════════════════

const today = () => vnDate();
const DAY_MS = 86_400_000;
const isoDay = (offset: number) => vnDate(Date.now() + offset * DAY_MS);
const mergeState = (patch: object) => localStorage.setItem(STATE_KEY, JSON.stringify({ ...storedState(), ...patch }));
const askPublic = async (question: string, extra: object = {}, auth?: object) => (await client.post('/api/public/ai/chat', { question, ...extra }, auth)).data.data as ChatResponse;
const askMe = async (question: string, extra: object = {}, auth?: object) => (await client.post('/api/me/ai/chat', { question, ...extra }, auth ?? (await customer()))).data.data as ChatResponse;
const llmCalls = () => (storedState().llm?.count ?? 0) as number;
const putConfig = async (key: string, value: string) => client.put(`/api/system-configs/${key}`, { value }, await as(ROLES.ADMIN));

const KEYS = ['answer', 'bookingHint', 'disclaimer', 'emergencyBranches', 'pets', 'result', 'sources'];
/** Bất biến của ChatResponse (một nơi dựng): đủ khóa; sources / disclaimer chỉ ANSWERED; emergencyBranches chỉ EMERGENCY; pets chỉ NEED_PET_CHOICE. */
function expectInvariants(res: ChatResponse, label = res.result) {
  expect(Object.keys(res).sort(), label).toEqual(KEYS);
  expect(typeof res.answer, label).toBe('string');
  expect(res.answer.length, label).toBeGreaterThan(0);
  expect(res.sources.length > 0, label).toBe(res.result === 'ANSWERED');
  expect(res.sources.length, label).toBeLessThanOrEqual(3);
  expect(res.disclaimer, label).toBe(res.result === 'ANSWERED' ? 'Thông tin tham khảo, không thay thế khám thú y.' : null);
  expect(res.emergencyBranches.length > 0, label).toBe(res.result === 'EMERGENCY');
  expect(res.pets.length > 0, label).toBe(res.result === 'NEED_PET_CHOICE');
  if (['OUT_OF_SCOPE', 'UNAVAILABLE'].includes(res.result)) expect(res.bookingHint, label).toBe(true);
  if (['EMERGENCY', 'NEED_LOGIN', 'NEED_PET_CHOICE', 'ANSWERED'].includes(res.result)) expect(res.bookingHint, label).toBe(false);
}

describe('phân quyền #1–#4', () => {
  it('#1 public: không token vẫn 200; token của bất kỳ vai trò nào bị bỏ qua (không bao giờ PERSONAL), kể cả nhân viên và khách', async () => {
    const question = 'Sắp tới tôi có lịch hẹn không?';
    expect((await askPublic(question)).result).toBe('NEED_LOGIN');
    for (const [role, email] of Object.entries(ROLES)) {
      expect((await askPublic(question, {}, await as(email))).result, role).toBe('NEED_LOGIN'); // cả 7 vai trò, token bị bỏ qua (ADR-0005)
    }
  });

  it('#2 /me/ai/chat: chỉ CUSTOMER (6 vai trò khác 403), không token 401', async () => {
    for (const [role, email] of Object.entries(ROLES)) {
      if (role === 'CUSTOMER') continue;
      expect(await errorOf(client.post('/api/me/ai/chat', { question: 'Chó ăn gì?' }, await as(email))), role).toEqual([403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này']);
    }
    expect((await failure(client.post('/api/me/ai/chat', { question: 'x' }))).status).toBe(401);
    expect((await askMe('Chó ăn gì?')).result).toBeDefined();
  });

  it.each([
    ['#3 vaccination-summary', (a?: object) => client.get('/api/me/ai/pets/1/vaccination-summary', a)],
    ['#4 recommendations', (a?: object) => client.get('/api/me/ai/pets/1/recommendations', a)],
  ])('%s: chỉ CUSTOMER, không token 401; thứ tự 400 sai kiểu → 403 vai trò → 400 VALIDATION → 404', async (name, call) => {
    for (const [role, email] of Object.entries(ROLES)) {
      if (role === 'CUSTOMER') continue;
      expect((await errorOf(call(await as(email))))[0], `${name} ${role}`).toBe(403);
    }
    expect((await failure(call())).status).toBe(401);
    expect(((await call(await customer())) as AxiosResponse).status).toBe(200);
    const vet = await as(ROLES.VET);
    const path = name.startsWith('#3') ? 'vaccination-summary' : 'recommendations';
    expect(await errorOf(client.get(`/api/me/ai/pets/abc/${path}`, vet))).toEqual([400, 'MALFORMED_REQUEST', "Tham số 'petId' sai kiểu dữ liệu"]); // sai kiểu đến trước vai trò
    expect((await errorOf(client.get(`/api/me/ai/pets/999/${path}`, vet)))[0]).toBe(403); // 404 sau vai trò
  });

  it('#4 thứ tự (đo trên BE): group / limit sai kiểu 400 MALFORMED, limit ngoài 1–12 là VALIDATION_FAILED, đều TRƯỚC vai trò (VET vẫn 400) và trước 404', async () => {
    const vet = await as(ROLES.VET);
    expect((await errorOf(client.get('/api/me/ai/pets/1/recommendations', { params: { limit: 99 }, ...vet })))[1]).toBe('VALIDATION_FAILED');
    expect((await errorOf(client.get('/api/me/ai/pets/1/recommendations', { ...vet })))[0]).toBe(403);
    expect((await errorOf(client.get('/api/me/ai/pets/1/recommendations', { params: { limit: 'abc' }, ...vet })))[1]).toBe('MALFORMED_REQUEST');
    expect((await errorOf(client.get('/api/me/ai/pets/1/recommendations', { params: { group: 'NOPE' }, ...vet })))[1]).toBe('MALFORMED_REQUEST');
    const mine = await customer();
    for (const limit of [0, 13, -1]) {
      expect(await errorOf(client.get('/api/me/ai/pets/1/recommendations', { params: { limit }, ...mine })), String(limit)).toEqual([400, 'VALIDATION_FAILED', 'limit: limit phải từ 1 tới 12']);
    }
    expect((await errorOf(client.get('/api/me/ai/pets/999/recommendations', { params: { limit: 99 }, ...mine })))[1]).toBe('VALIDATION_FAILED'); // VALIDATION trước 404
  });
});

describe('chatbot: nhánh kết quả và bất biến ChatResponse', () => {
  it('public: ANSWERED / OUT_OF_SCOPE / MEDICAL_REFUSAL / EMERGENCY / NEED_LOGIN đều 200 và đúng bất biến', async () => {
    const answered = await askPublic('Chó con tiêm phòng dại lúc mấy tuần?');
    const outOfScope = await askPublic('Giá vàng hôm nay là bao nhiêu?');
    const medical = await askPublic('Chó 10 kg uống bao nhiêu mg paracetamol?');
    const emergency = await askPublic('Mèo nhà mình bị co giật liên tục');
    const needLogin = await askPublic('Sắp tới tôi có lịch hẹn không?');
    expect([answered.result, outOfScope.result, medical.result, emergency.result, needLogin.result]).toEqual(['ANSWERED', 'OUT_OF_SCOPE', 'MEDICAL_REFUSAL', 'EMERGENCY', 'NEED_LOGIN']);
    for (const res of [answered, outOfScope, medical, emergency, needLogin]) expectInvariants(res);
    expect(answered.sources.map((s) => s.title)).toContain('Lịch tiêm Dại cho chó');
    expect(answered.answer).toContain('tuần tuổi');
    expect(medical.answer).toContain('không thể chẩn đoán');
    expect(emergency.emergencyBranches[0]).toEqual({ branchId: 1, name: expect.any(String), address: expect.any(String), phone: expect.any(String) });
    expect(needLogin.answer).toBe('Vui lòng đăng nhập để xem lịch hẹn / lịch tiêm của bạn.');
  });

  it('/me: PERSONAL / NEED_PET_CHOICE đúng bất biến; UNAVAILABLE khi hết hạn mức ngày', async () => {
    const personal = await askMe('Mochi tiêm khi nào?');
    const choice = await askMe('Lịch tiêm của bé nhà tôi');
    mergeState({ llm: { date: today(), count: AI_DAILY_LLM_LIMIT } });
    const unavailable = await askMe('Chó con tiêm phòng dại lúc mấy tuần?');
    expect([personal.result, choice.result, unavailable.result]).toEqual(['PERSONAL', 'NEED_PET_CHOICE', 'UNAVAILABLE']);
    for (const res of [personal, choice, unavailable]) expectInvariants(res);
    expect(choice.pets).toEqual([{ petId: 1, name: 'Mochi' }, { petId: 2, name: 'Bim' }, { petId: 3, name: 'Tôm' }]);
  });

  it('ANSWERED: tốn đúng 1 lệnh sinh và ghi nhà cung cấp; tối đa 3 nguồn, cao nhất trước; đổi ngưỡng τ ở màn tham số có hiệu lực ngay', async () => {
    const res = await askPublic('Chó con tiêm phòng dại lúc mấy tuần?');
    expect(res.result).toBe('ANSWERED');
    expect(llmCalls()).toBe(1);
    expect(aiProviderLog()).toEqual([{ question: 'Chó con tiêm phòng dại lúc mấy tuần?', previousQuestions: [], llm: true }]);
    expect(res.sources[0].documentId).toBe(13); // 'Lịch tiêm Dại cho chó' sinh từ phác đồ
    // τ = 0,9 (tối đa) → cùng câu hỏi dưới ngưỡng: không còn tốn lệnh sinh.
    expect((await putConfig('ai.retrieval.min_similarity', '0.9')).status).toBe(200);
    expect((await askPublic('Chó con tiêm phòng dại lúc mấy tuần?')).result).toBe('OUT_OF_SCOPE');
    expect(llmCalls()).toBe(1);
  });

  it('câu ngoài phạm vi: lạc đề dưới τ (không tốn lệnh), gần chủ đề (giá, mua thuốc) qua τ rồi inScope=false (tốn 1 lệnh)', async () => {
    expect((await askPublic('Giá vàng hôm nay là bao nhiêu?')).result).toBe('OUT_OF_SCOPE');
    expect(llmCalls()).toBe(0);
    expect(aiProviderLog()).toHaveLength(1); // đã nhúng nên nhà cung cấp có nhận câu hỏi, nhưng chưa tới bước sinh
    expect(aiProviderLog()[0].llm).toBe(false);
    for (const question of ['Giá tiêm vaccine dại cho chó bao nhiêu?', 'Mua thuốc tẩy giun cho chó ở đâu?']) {
      const res = await askPublic(question);
      expect(res, question).toMatchObject({ result: 'OUT_OF_SCOPE', bookingHint: true, answer: 'Mình chưa có thông tin cho câu hỏi này. Bạn có thể đặt lịch để bác sĩ tư vấn trực tiếp.' });
    }
    expect(llmCalls()).toBe(2);
  });

  it('y khoa: liều / chẩn đoán → MEDICAL_REFUSAL; ml nước cho chó KHÔNG bị chặn nhầm (BR-AI-06)', async () => {
    for (const question of ['Chó 10 kg uống bao nhiêu mg paracetamol?', 'Chó nôn vàng uống thuốc gì?', 'Mèo tôi bị bệnh gì nếu nôn và bỏ ăn?']) {
      expect((await askPublic(question)).result, question).toBe('MEDICAL_REFUSAL');
    }
    expect(llmCalls()).toBe(3);
    const water = await askPublic('Chó 10 kg cần uống bao nhiêu ml nước mỗi ngày?');
    expect(water.result).toBe('ANSWERED');
    expect(water.sources.map((s) => s.documentId)).toContain(3);
  });

  it('cấp cứu: không nhúng, không LLM, danh sách chi nhánh nhận cấp cứu ngoài giờ; "Liệt kê…" không phải cấp cứu (ranh giới từ), cụm 2 âm tiết bỏ dấu vẫn nhận', async () => {
    for (const question of ['Chó ăn nhầm thuốc chuột', 'Mèo co giật liên tục', 'Chó khó thở thở dốc', 'Chó bị ngất', 'Chó kho tho']) {
      expect((await askPublic(question)).result, question).toBe('EMERGENCY');
    }
    expect(llmCalls()).toBe(0);
    expect(aiProviderLog()).toEqual([]);
    expect((await askPublic('Liệt kê giúp tôi các mũi tiêm cho mèo con')).result).not.toBe('EMERGENCY');
    expect((await askPublic('Chó ngat')).result).not.toBe('EMERGENCY'); // một âm tiết không dấu thì không khớp
  });

  it('hết hạn mức ngày (20): chỉ câu tới bước LLM bị UNAVAILABLE; cấp cứu, NEED_LOGIN, PERSONAL, dưới τ vẫn trả lời; sang ngày mới thì đếm lại', async () => {
    mergeState({ llm: { date: today(), count: AI_DAILY_LLM_LIMIT - 1 } });
    expect((await askPublic('Chó con tiêm phòng dại lúc mấy tuần?')).result).toBe('ANSWERED'); // lệnh thứ 20
    expect(llmCalls()).toBe(AI_DAILY_LLM_LIMIT);
    const blocked = await askPublic('Chó con tiêm phòng dại lúc mấy tuần?');
    expect(blocked).toMatchObject({ result: 'UNAVAILABLE', bookingHint: true, sources: [] });
    expect(llmCalls()).toBe(AI_DAILY_LLM_LIMIT); // không tăng thêm
    expect((await askPublic('Mèo co giật')).result).toBe('EMERGENCY');
    expect((await askPublic('Sắp tới tôi có lịch hẹn không?')).result).toBe('NEED_LOGIN');
    expect((await askPublic('Giá vàng hôm nay là bao nhiêu?')).result).toBe('OUT_OF_SCOPE');
    expect((await askMe('Mochi tiêm khi nào?')).result).toBe('PERSONAL');
    mergeState({ llm: { date: '2000-01-01', count: AI_DAILY_LLM_LIMIT } });
    expect((await askPublic('Chó con tiêm phòng dại lúc mấy tuần?')).result).toBe('ANSWERED');
    expect(llmCalls()).toBe(1);
  });
});

describe('chatbot: giới hạn tần suất theo IP (BR-AI-09)', () => {
  it('10 câu / phút dùng chung cho hai endpoint; câu thứ 11 → 429 TOO_MANY_REQUESTS với số giây chờ trong message', async () => {
    for (let i = 0; i < 5; i += 1) expect((await askPublic('Giá vàng hôm nay là bao nhiêu?')).result).toBe('OUT_OF_SCOPE');
    const auth = await customer();
    for (let i = 0; i < 5; i += 1) expect((await askMe('Giá vàng hôm nay là bao nhiêu?', {}, auth)).result).toBe('OUT_OF_SCOPE');
    const res = await failure(client.post('/api/public/ai/chat', { question: 'x' }));
    expect(res.status).toBe(429);
    expect(res.data).toMatchObject({ success: false, errorCode: 'TOO_MANY_REQUESTS', statusCode: 429 });
    const seconds = Number(/thử lại sau (\d+) giây$/.exec(res.data.message)?.[1]);
    expect(res.data.message).toMatch(/^Bạn thao tác quá nhanh, vui lòng thử lại sau \d+ giây$/);
    expect(seconds).toBeGreaterThanOrEqual(1);
    expect(seconds).toBeLessThanOrEqual(60);
    expect((await failure(client.post('/api/me/ai/chat', { question: 'x' }, auth))).status).toBe(429); // lần bị từ chối vẫn được tính
    expect(aiProviderLog()).toHaveLength(10); // bị chặn trước khi nhúng: nhà cung cấp không nhận gì thêm
  });

  it('cửa sổ phút hết hạn thì đếm lại; cửa sổ giờ 60 câu; bị chặn ở phút thì không tiêu hao hạn mức giờ', async () => {
    const now = Date.now();
    mergeState({ limiter: { minute: { start: now - 61_000, count: 10 }, hour: { start: now, count: 0 } } });
    expect((await askPublic('Giá vàng hôm nay là bao nhiêu?')).result).toBe('OUT_OF_SCOPE'); // cửa sổ phút cũ đã hết hạn
    expect(storedState().limiter.minute.count).toBe(1);
    expect(storedState().limiter.hour.count).toBe(1);

    mergeState({ limiter: { minute: { start: now, count: 10 }, hour: { start: now, count: 5 } } });
    expect((await failure(client.post('/api/public/ai/chat', { question: 'x' }))).status).toBe(429);
    expect(storedState().limiter.hour.count).toBe(5); // dừng ở cửa sổ phút, không đụng cửa sổ giờ

    mergeState({ limiter: { minute: { start: now, count: 0 }, hour: { start: now - 600_000, count: 60 } } });
    const hour = await failure(client.post('/api/public/ai/chat', { question: 'x' }));
    expect(hour.status).toBe(429);
    expect(Number(/(\d+) giây$/.exec(hour.data.message)![1])).toBeGreaterThan(60); // còn ~50 phút
    expect(storedState().limiter.minute.count).toBe(1); // đã qua cửa sổ phút rồi mới bị chặn ở giờ
  });

  it('429 đến trước cả vai trò và kiểm đầu vào: VET gọi /me hay gửi thân sai vẫn tốn hạn mức và bị 429 khi hết', async () => {
    const vet = await as(ROLES.VET);
    for (let i = 0; i < 10; i += 1) expect((await failure(client.post('/api/me/ai/chat', { question: 'x' }, vet))).status).toBe(403);
    expect((await failure(client.post('/api/me/ai/chat', { question: 'x' }, vet))).status).toBe(429);
    expect((await failure(client.post('/api/public/ai/chat', { question: 7 }))).status).toBe(429);
  });
});

describe('chatbot: kiểm đầu vào (BR-AI-08)', () => {
  it('VALIDATION_FAILED: câu hỏi rỗng / quá 500 ký tự, quá 3 câu hỏi trước, một câu hỏi trước quá 500 ký tự; đúng 500 thì được', async () => {
    const post = (body: unknown) => client.post('/api/public/ai/chat', body);
    expect(await errorOf(post({ question: '   ' }))).toEqual([400, 'VALIDATION_FAILED', 'question: Câu hỏi không được để trống']);
    expect((await errorOf(post({})))[2]).toBe('question: Câu hỏi không được để trống');
    expect(await errorOf(post({ question: 'a'.repeat(501) }))).toEqual([400, 'VALIDATION_FAILED', 'question: Câu hỏi tối đa 500 ký tự']);
    expect(await errorOf(post({ question: 'Chó ăn gì?', previousQuestions: ['a', 'b', 'c', 'd'] }))).toEqual([400, 'VALIDATION_FAILED', 'previousQuestions: Tối đa 3 câu hỏi trước']);
    expect((await errorOf(post({ question: 'Chó ăn gì?', previousQuestions: ['a'.repeat(501)] })))[2]).toBe('previousQuestions: Mỗi câu hỏi trước tối đa 500 ký tự');
    expect((await post({ question: `${'a'.repeat(499)}  `, previousQuestions: ['a'.repeat(500), 'b', 'c'] })).status).toBe(200); // khoảng trắng hai đầu được cắt
    expect((await post({ question: 'Chó ăn gì?', previousQuestions: null })).status).toBe(200);
  });

  it('giới hạn đọc từ [CFG]: hạ ai.chat.max_question_chars và max_previous_questions ở màn tham số có hiệu lực ngay', async () => {
    expect((await putConfig('ai.chat.max_question_chars', '100')).status).toBe(200);
    expect((await putConfig('ai.chat.max_previous_questions', '1')).status).toBe(200);
    expect((await errorOf(client.post('/api/public/ai/chat', { question: 'a'.repeat(101) })))[2]).toBe('question: Câu hỏi tối đa 100 ký tự');
    expect((await errorOf(client.post('/api/public/ai/chat', { question: 'Chó ăn gì?', previousQuestions: ['a', 'b'] })))[2]).toBe('previousQuestions: Tối đa 1 câu hỏi trước');
    expect((await client.post('/api/public/ai/chat', { question: 'a'.repeat(100), previousQuestions: ['a'] })).status).toBe(200);
  });

  it('MALFORMED_REQUEST: thân không phải object, question không phải chuỗi, previousQuestions không phải mảng chuỗi; đến trước vai trò', async () => {
    for (const body of ['chuỗi', [], { question: 7 }, { question: 'x', previousQuestions: 'y' }, { question: 'x', previousQuestions: [1] }, { question: 'x', previousQuestions: [{ role: 'assistant', text: 'y' }] }]) {
      expect((await errorOf(client.post('/api/public/ai/chat', body)))[1], JSON.stringify(body)).toBe('MALFORMED_REQUEST');
    }
    const vet = await as(ROLES.VET);
    expect((await errorOf(client.post('/api/me/ai/chat', { question: 7 }, vet)))[1]).toBe('MALFORMED_REQUEST');
    expect((await errorOf(client.post('/api/me/ai/chat', { question: '' }, vet)))[1]).toBe('VALIDATION_FAILED'); // @NotBlank trước vai trò (đo trên BE 11/11/2026: VET + `{}` → 400)
    expect((await errorOf(client.post('/api/me/ai/chat', { question: 'x' }, vet)))[0]).toBe(403);
  });
});

describe('chatbot: che dữ liệu cá nhân (BR-AI-12)', () => {
  it('email, SĐT (0… và +84…), dãy 9 / 12 chữ số bị che trước khi nhúng, cả câu hỏi trước; chuỗi nhà cung cấp nhận là bản đã che', async () => {
    await askPublic('Chó con tiêm phòng dại lúc mấy tuần? Liên hệ a.b+c@x.com hoặc 0912345678, +84 912 345 678, số 012345678901 và 123456789.', {
      previousQuestions: ['SĐT 0987-654-321 và email lan@x.vn', 'Mã 1234567'],
    });
    expect(aiProviderLog()).toEqual([
      {
        question: 'Chó con tiêm phòng dại lúc mấy tuần? Liên hệ [email] hoặc [sđt], [sđt], số [số] và [số].',
        previousQuestions: ['SĐT [sđt] và email [email]', 'Mã 1234567'], // 7 chữ số không phải giấy tờ / SĐT
        llm: true,
      },
    ]);
    expect(maskPersonal('0912345678901')).toBe('0912345678901'); // 13 chữ số: không phải SĐT, 9 hay 12
  });

  it('/me: tên thú còn sống → [thú] SAU khi định tuyến ý định; endpoint công khai không che được tên thú', async () => {
    await askMe('Mochi bị ngứa thì chăm sóc thế nào?');
    await askPublic('Mochi bị ngứa thì chăm sóc thế nào?');
    expect(aiProviderLog().map((c) => c.question)).toEqual(['[thú] bị ngứa thì chăm sóc thế nào?', 'Mochi bị ngứa thì chăm sóc thế nào?']);
    const log = aiProviderLog().length;
    // Ý định cá nhân cần tên thú ("Mochi tiêm khi nào?"): nhận ý định xong mới che, và câu cá nhân không tới nhà cung cấp.
    expect((await askMe('Mochi tiêm khi nào?')).result).toBe('PERSONAL');
    expect(aiProviderLog()).toHaveLength(log);
    await askMe('Chăm sóc bé thế nào?', { previousQuestions: ['Bim ăn gì', 'Còn Tôm?'] });
    expect(aiProviderLog().at(-1)).toMatchObject({ question: 'Chăm sóc bé thế nào?', previousQuestions: ['[thú] ăn gì', 'Còn [thú]?'] });
  });

  it('kết quả không tới nhà cung cấp thì nhà cung cấp không ghi gì: cấp cứu, bệnh án, NEED_LOGIN, PERSONAL, NEED_PET_CHOICE', async () => {
    await askPublic('Mèo co giật liên tục');
    await askPublic('Đơn thuốc của tôi hôm qua là gì?');
    await askPublic('Sắp tới tôi có lịch hẹn không?');
    await askMe('Lịch tiêm của bé nhà tôi');
    await askMe('Mochi tiêm khi nào?');
    await askMe('Sắp tới tôi có lịch hẹn không?');
    await askMe('Đơn thuốc của Mochi hôm qua là gì?');
    expect(aiProviderLog()).toEqual([]);
    expect(llmCalls()).toBe(0);
  });
});

describe('chatbot: ý định cá nhân (BR-AI-26…28)', () => {
  it('/me lịch hẹn: PERSONAL, chỉ lịch BOOKED còn tới của thú còn sống, tối đa ai.personal.appointments_limit, đúng thứ tự', async () => {
    const list = (await client.get('/api/appointments', { params: { status: 'BOOKED', size: 100 }, ...(await customer()) })).data.data.content as { petName: string; slotDate: string; slotStart: string; code: string }[];
    const res = await askMe('Sắp tới tôi có lịch hẹn không?');
    expect(res.result).toBe('PERSONAL');
    if (list.length === 0) {
      expect(res).toMatchObject({ answer: 'Bạn chưa có lịch hẹn sắp tới.', bookingHint: true });
    } else {
      const upcoming = list.filter((a) => Date.parse(`${a.slotDate}T${a.slotStart}:00+07:00`) >= Date.now());
      const lines = res.answer.split('\n');
      expect(lines[0]).toMatch(/^Bạn có \d+ lịch hẹn sắp tới:$/);
      expect(lines.length - 1).toBe(Math.min(upcoming.length, 5));
      for (const a of upcoming.slice(0, 5)) expect(res.answer).toContain(`(${a.code})`);
    }
    expect((await putConfig('ai.personal.appointments_limit', '1')).status).toBe(200);
    const one = await askMe('Sắp tới tôi có lịch hẹn không?');
    expect(one.answer.split('\n').length).toBeLessThanOrEqual(2);
  });

  it('/me lịch tiêm: nêu tên → PERSONAL của thú đó; không nêu tên và nhiều thú → NEED_PET_CHOICE; sau đó gọi #3 cho thú đã chọn', async () => {
    const named = await askMe('Mochi tiêm khi nào?');
    expect(named.result).toBe('PERSONAL');
    expect(named.answer.split('\n')[0]).toBe('Lịch tiêm của Mochi (chỉ tính các mũi ghi nhận tại hệ thống):');
    const choice = await askMe('Lịch tiêm sắp tới của bé già nhà tôi');
    expect(choice.result).toBe('NEED_PET_CHOICE');
    expect(choice.sources).toEqual([]);
    const picked = (await client.get(`/api/me/ai/pets/${choice.pets[1].petId}/vaccination-summary`, await customer())).data.data as VaccinationSummary;
    expect(picked.petName).toBe('Bim');
    const both = await askMe('Mochi và Bim tiêm khi nào?');
    expect(both.result).toBe('NEED_PET_CHOICE');
    expect(both.pets.map((p) => p.name)).toEqual(['Mochi', 'Bim']);
  });

  it('/me chỉ còn một thú sống → dùng thú đó; không có thú (khách chờ liên kết hồ sơ) → câu hướng dẫn thêm thú', async () => {
    const reception = await as(ROLES.RECEPTIONIST);
    for (const petId of [2, 3]) expect((await client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: isoDay(-1), confirmed: true }, reception)).status).toBe(200);
    const single = await askMe('Mũi tiêm tiếp theo của tôi là khi nào?');
    expect(single.result).toBe('PERSONAL');
    expect(single.answer.split('\n')[0]).toBe('Lịch tiêm của Mochi (chỉ tính các mũi ghi nhận tại hệ thống):');
    const none = await askMe('Mũi tiêm tiếp theo của tôi là khi nào?', {}, await as('khachmoi@petcare.vn'));
    expect(none).toMatchObject({ result: 'PERSONAL', answer: 'Bạn chưa có thú cưng nào còn sống trong hồ sơ. Hãy thêm thú cưng để xem lịch tiêm.' });
  });

  it('câu kiến thức chung và câu phủ định đi RAG, không nhận nhầm là cá nhân (/me)', async () => {
    for (const question of [
      'Chó con tiêm phòng dại lúc mấy tuần?',
      'Mũi tiêm tiếp theo của chó con là khi nào?',
      'Quá hạn tiêm vaccine dại có sao không?',
      'Giá tiêm vaccine dại bao nhiêu?',
      'Liệt kê giúp tôi các mũi tiêm cho mèo con',
    ]) {
      const res = await askMe(question);
      expect(['PERSONAL', 'NEED_PET_CHOICE', 'NEED_LOGIN'], question).not.toContain(res.result);
    }
  });

  it('bệnh án / đơn thuốc kèm dấu hiệu sở hữu hoặc tên thú → MEDICAL_REFUSAL chỉ đường tới Hồ sơ sức khỏe, với mọi người dùng, không LLM', async () => {
    const expected = 'Mình không xem được hồ sơ bệnh án. Bạn xem ở Hồ sơ sức khỏe của thú (UC25) hoặc hỏi bác sĩ.';
    for (const res of [await askPublic('Đơn thuốc của tôi hôm qua là gì?'), await askMe('Đơn thuốc của Mochi hôm qua là gì?'), await askMe('Kết quả xét nghiệm nhà mình đâu?')]) {
      expect(res).toMatchObject({ result: 'MEDICAL_REFUSAL', answer: expected });
      expectInvariants(res);
    }
    expect(llmCalls()).toBe(0);
    expect((await askPublic('Đơn thuốc là gì?')).answer).not.toBe(expected); // không có dấu hiệu sở hữu thì là câu hỏi kiến thức
  });
});

describe('bộ định tuyến ý định, khớp cụm từ, che dữ liệu (hàm thuần)', () => {
  it.each([
    ['Sắp tới tôi có lịch hẹn không?', [], 'APPOINTMENT_UPCOMING'],
    ['Lịch hẹn của tôi', [], 'APPOINTMENT_UPCOMING'],
    ['Lịch hẹn khám hôm nay', [], 'APPOINTMENT_UPCOMING'], // dấu hiệu yếu đủ với lịch hẹn
    ['Mũi tiêm tiếp theo của tôi là khi nào?', [], 'VACCINATION_SCHEDULE'],
    ['Mochi tiêm khi nào?', ['Mochi'], 'VACCINATION_SCHEDULE'],
    ['Lịch tiêm sắp tới của bé già nhà tôi', [], 'VACCINATION_SCHEDULE'], // "già" không bị loại như "giá"
    ['Mũi tiêm tiếp theo của chó con là khi nào?', [], null], // dấu hiệu yếu không đủ với tiêm chủng
    ['Quá hạn tiêm vaccine dại có sao không?', [], null],
    ['Chó con tiêm phòng dại lúc mấy tuần?', [], null],
    ['Giá tiêm vaccine của tôi bao nhiêu?', [], null], // cụm chỉ giá chặn
    ['Chi phí tiêm của bé nhà mình', [], null],
    ['Giá vàng hôm nay là bao nhiêu?', ['Vàng'], null], // tên trùng từ thông dụng đứng một mình thì không là dấu hiệu
    ['Con Vàng tiêm khi nào?', ['Vàng'], 'VACCINATION_SCHEDULE'],
    ['Bé Vàng tiêm khi nào?', ['Vàng'], 'VACCINATION_SCHEDULE'],
    ['Vàng tiêm khi nào?', ['Vàng'], null],
    ['Tiem phong cho nha toi', [], null], // "tiem" không dấu một âm tiết không khớp
    ['Lich hen cua toi sap toi', [], 'APPOINTMENT_UPCOMING'], // cụm ≥ 2 âm tiết được bỏ dấu
    ['Tiêm ở tiệm thú y của tôi', [], 'VACCINATION_SCHEDULE'],
  ] as const)('%s', (question, names, expected) => {
    expect(detectPersonalIntent(question, names)).toBe(expected);
  });

  it('hasPhrase: ranh giới từ và bỏ dấu chỉ cho cụm ≥ 2 âm tiết; mentionsPet theo dạng viết của tên', () => {
    expect(hasPhrase('Giá vaccine', 'giá')).toBe(true);
    expect(hasPhrase('Chó già lắm', 'giá')).toBe(false);
    expect(hasPhrase('gia dinh', 'giá')).toBe(false);
    expect(hasPhrase('Liệt kê các mũi tiêm', 'bị liệt')).toBe(false);
    expect(hasPhrase('Chó khó thở', 'khó thở')).toBe(true);
    expect(hasPhrase('Cho kho tho', 'khó thở')).toBe(true);
    expect(hasPhrase('Cho khotho', 'khó thở')).toBe(false);
    expect(hasPhrase('Nhà TÔI  có lịch', 'nhà tôi')).toBe(true);
    expect(mentionsPet('mochi ăn gì', 'Mochi')).toBe(true);
    expect(mentionsPet('Mochis ăn gì', 'Mochi')).toBe(false);
    expect(mentionsPet('Tôm ăn gì', 'Tôm')).toBe(true);
    expect(mentionsPet('em Mít ăn gì', 'Mít')).toBe(true);
    expect(mentionsPet('bánh mít', 'Mít')).toBe(false);
  });
});

describe('liên kết tài liệu ↔ chatbot (BR-AI-16)', () => {
  it('lưu trữ tài liệu có hiệu lực ngay: câu hỏi hết dẫn nguồn tài liệu đó; xuất bản lại thì dẫn lại', async () => {
    const question = 'Chó con tiêm phòng dại lúc mấy tuần?';
    expect((await askPublic(question)).sources.map((s) => s.documentId)).toContain(13);
    expect((await postDoc('/13/archive')).data.data.status).toBe('ARCHIVED');
    expect((await askPublic(question)).sources.map((s) => s.documentId)).not.toContain(13);
  });

  it('tài liệu mới: nháp không được truy hồi; xuất bản đủ hai lớp xong thì trả lời và dẫn nguồn; DRAFT / ARCHIVED không lọt vào', async () => {
    const question = 'Nhím Ethiopia ngủ lúc nào?';
    expect((await askPublic(question)).result).toBe('OUT_OF_SCOPE');
    const created = (await client.post('/api/ai/documents', { title: 'Nuôi nhím Ethiopia', topic: 'CARE', species: 'OTHER', content: '## Nghỉ ngơi\nNhím Ethiopia hoạt động về đêm và ngủ ban ngày trong tổ ấm.', ...reviewed }, await manager())).data.data as AiDocument;
    expect((await askPublic(question)).result).toBe('OUT_OF_SCOPE'); // còn DRAFT
    expect((await postDoc(`/${created.documentId}/publish`)).data.data.indexStatus).toBe('PENDING');
    const res = await askPublic(question);
    expect(res.result).toBe('ANSWERED');
    expect(res.sources).toEqual([{ documentId: created.documentId, title: 'Nuôi nhím Ethiopia' }]);
    expect(res.answer).toContain('ngủ ban ngày');
  });

  it('câu hỏi nói rõ một loài thì không dẫn tài liệu dành riêng cho loài kia; lạc đề chỉ trùng vài từ phổ thông vẫn OUT_OF_SCOPE; khách chờ liên kết hồ sơ vẫn hỏi đáp được', async () => {
    const cat = await askPublic('Mèo con ăn mấy bữa một ngày?');
    expect(cat.result).toBe('ANSWERED');
    expect(cat.sources[0].documentId).toBe(2);
    expect(cat.sources.map((s) => s.documentId)).not.toContain(1); // tài liệu chó con
    const dog = await askPublic('Chó ăn chocolate có sao không?');
    expect(dog.sources[0].documentId).toBe(4);
    expect((await askPublic('Hamster ăn gì?')).result).toBe('OUT_OF_SCOPE');
    expect((await askMe('Chó con tiêm phòng dại lúc mấy tuần?', {}, await as('khachmoi@petcare.vn'))).result).toBe('ANSWERED');
  });

  it('tài liệu #8 (FAILED: v2 chưa nạp, bản v1 vẫn phục vụ): trả lời theo v1 cho tới khi nạp lại, rồi theo v2', async () => {
    const question = 'Liên lạc với chi nhánh để cập nhật tình hình lưu trú';
    const before = await askPublic(question);
    expect(before.sources.map((s) => s.documentId)).not.toContain(8); // bản v1 đang phục vụ chưa có câu về liên lạc, cập nhật
    await postDoc('/8/reindex');
    const after = await askPublic(question);
    expect(after.sources[0].documentId).toBe(8);
    expect(after.answer).toContain('giữ liên lạc');
  });
});

// ─── #3 Lịch tiêm ───

const summaryOf = async (petId: number, auth?: object) => (await client.get(`/api/me/ai/pets/${petId}/vaccination-summary`, auth ?? (await customer()))).data.data as VaccinationSummary;

describe('GET /me/ai/pets/{petId}/vaccination-summary (#3, BR-AI-11)', () => {
  it('Bim (chó, đã tiêm Dại): theo phác đồ của loài, mỗi loại vaccine một dòng, Dại UP_TO_DATE, 5 bệnh NOT_STARTED kèm tuổi mũi 1; Ho cũi (không có phác đồ) không có mặt', async () => {
    const res = await summaryOf(2);
    expect(res).toMatchObject({ petId: 2, petName: 'Bim', disclaimer: 'Thông tin tham khảo, không thay thế khám thú y.' });
    expect(res.items).toEqual([
      { vaccineTypeId: 1, vaccineName: 'Dại', status: 'UP_TO_DATE', lastDoseNumber: 1, lastAdministeredOn: '2026-08-01', nextDoseNumber: 1, nextDueDate: '2027-08-01', firstDoseFromAgeWeeks: null },
      { vaccineTypeId: 2, vaccineName: '5 bệnh', status: 'NOT_STARTED', lastDoseNumber: null, lastAdministeredOn: null, nextDoseNumber: 1, nextDueDate: null, firstDoseFromAgeWeeks: 6 },
    ]);
    expect(res.message).toBe(
      'Lịch tiêm của Bim (chỉ tính các mũi ghi nhận tại hệ thống):\n- Dại: còn hạn, đã tiêm mũi 1 ngày 01/08/2026; mũi tiếp theo hạn 01/08/2027.\n- 5 bệnh: chưa tiêm tại hệ thống, mũi đầu tiên từ 6 tuần tuổi.',
    );
  });

  it('Mochi (mèo, chưa có mũi nào) NOT_STARTED cả hai; Tôm (loài khác) không có phác đồ; thú chưa đủ tuổi NOT_YET_ELIGIBLE', async () => {
    expect((await summaryOf(1)).items.map((i) => [i.vaccineName, i.status])).toEqual([['Dại', 'NOT_STARTED'], ['4 bệnh', 'NOT_STARTED']]);
    expect(await summaryOf(3)).toMatchObject({ items: [], message: 'Chưa có phác đồ tiêm cho loài của Tôm trong danh mục.' });
    const auth = await customer();
    const puppy = (await client.post('/api/me/pets', { name: 'Cún', species: 'DOG', sex: 'MALE', birthDate: isoDay(-49) }, auth)).data.data.pet; // 7 tuần
    const res = await summaryOf(puppy.petId, auth);
    expect(res.items.map((i) => [i.vaccineName, i.status, i.firstDoseFromAgeWeeks])).toEqual([['Dại', 'NOT_YET_ELIGIBLE', 12], ['5 bệnh', 'NOT_STARTED', 6]]);
  });

  it('mũi do mock lâm sàng ghi (recordDoseForMock) đổi kết quả: OVERDUE khi quá hạn, và DUE_SOON / UP_TO_DATE đổi ranh giới theo reminder.vaccine_days_before', async () => {
    recordDoseForMock(2, 2, isoDay(-30)); // 5 bệnh mũi 1, khoảng cách 21 ngày → hạn tái chủng cách đây 9 ngày
    let five = (await summaryOf(2)).items.find((i) => i.vaccineTypeId === 2)!;
    expect(five).toMatchObject({ status: 'OVERDUE', lastDoseNumber: 1, nextDoseNumber: 2, nextDueDate: isoDay(-9) });

    recordDoseForMock(2, 2, today()); // mũi 2 hôm nay → hạn sau 21 ngày; mũi mới nhất thay thế mũi cũ
    const status = async () => (await summaryOf(2)).items.find((i) => i.vaccineTypeId === 2)!;
    five = await status();
    expect(five).toMatchObject({ status: 'UP_TO_DATE', lastDoseNumber: 2, lastAdministeredOn: today(), nextDueDate: isoDay(21), nextDoseNumber: 2 });
    expect((await putConfig('reminder.vaccine_days_before', '21')).status).toBe(200);
    expect((await status()).status).toBe('DUE_SOON'); // đúng ranh giới N ngày tới
    expect((await putConfig('reminder.vaccine_days_before', '20')).status).toBe(200);
    expect((await status()).status).toBe('UP_TO_DATE');
  });

  it('vaccinationItemOf: ranh giới OVERDUE (< hôm nay) / DUE_SOON (≤ hôm nay + N) / UP_TO_DATE; tuổi chưa đủ hoặc chưa biết ngày sinh', () => {
    const base = { type: { vaccineTypeId: 9, name: 'X' }, protocols: [{ doseNumber: 1, minAgeWeeks: 8 }, { doseNumber: 2, minAgeWeeks: 11 }], today: '2026-11-06', dueSoonDays: 7 };
    const dose = (nextDueDate: string) => ({ doseNumber: 1, administeredOn: '2026-10-01', nextDueDate });
    const status = (due: string) => vaccinationItemOf({ ...base, last: dose(due), ageWeeks: 20 }).status;
    expect([status('2026-11-05'), status('2026-11-06'), status('2026-11-13'), status('2026-11-14')]).toEqual(['OVERDUE', 'DUE_SOON', 'DUE_SOON', 'UP_TO_DATE']);
    expect(vaccinationItemOf({ ...base, last: dose('2026-11-20'), ageWeeks: 20 })).toMatchObject({ nextDoseNumber: 2 });
    expect(vaccinationItemOf({ ...base, last: { doseNumber: 2, administeredOn: '2026-10-01', nextDueDate: '2027-10-01' }, ageWeeks: 20 })).toMatchObject({ nextDoseNumber: 2, lastDoseNumber: 2 }); // hết phác đồ: nhắc lại mũi cuối
    expect(vaccinationItemOf({ ...base, last: null, ageWeeks: 7 })).toMatchObject({ status: 'NOT_YET_ELIGIBLE', firstDoseFromAgeWeeks: 8 });
    expect(vaccinationItemOf({ ...base, last: null, ageWeeks: 8 }).status).toBe('NOT_STARTED');
    expect(vaccinationItemOf({ ...base, last: null, ageWeeks: null }).status).toBe('NOT_STARTED');
  });

  it('thú của người khác / chủ cũ / không tồn tại → 404 cùng một câu; thú đã mất → 400 BR-KH-05; khách chờ liên kết hồ sơ không có thú nào', async () => {
    const missing = await errorOf(client.get('/api/me/ai/pets/999/vaccination-summary', await customer()));
    expect(missing).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy thú cưng #999']);
    expect((await errorOf(client.get('/api/me/ai/pets/4/vaccination-summary', await customer())))[0]).toBe(404); // Lu của hồ sơ tại quầy #3
    expect(await errorOf(client.get('/api/me/ai/pets/1/vaccination-summary', await as('khachmoi@petcare.vn')))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy thú cưng #1']);
    await client.post('/api/pets/3/mark-deceased', { deceasedOn: isoDay(-1), confirmed: true }, await as(ROLES.RECEPTIONIST));
    expect(await errorOf(client.get('/api/me/ai/pets/3/vaccination-summary', await customer()))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Thú cưng đã mất, không tra được lịch tiêm (BR-KH-05)']);
  });
});

// ─── #4 Gợi ý ───

const recosOf = async (petId: number, params: object = {}, auth?: object) =>
  (await client.get(`/api/me/ai/pets/${petId}/recommendations`, { params, ...(auth ?? (await customer())) })).data.data as RecommendationResponse;
const idsOf = (res: RecommendationResponse, group: string) => res.items.filter((i) => i.group === group).map((i) => i.itemId);

describe('GET /me/ai/pets/{petId}/recommendations (#4, BR-AI-18…23)', () => {
  const weights = { attr: 0.5, history: 0.3, popularity: 0.2 };
  const mochi = { species: 'DOG', breed: 'Poodle', birthDate: isoDay(-140), weightKg: 3.2 } as const; // 20 tuần, 3,2 kg (ví dụ 07 §7.3)

  it('ví dụ tính tay 07 §7.3: 92,0 (khớp 4/4, H = 1, P = 0,6), loại (chó lớn ≥ 15 kg), 37,0 (không hồ sơ: A = 0,3, H = 0,4, P = 0,5)', () => {
    const bath = attributeFit(mochi, { species: 'DOG', minAgeWeeks: 12, minWeightKg: 1, maxWeightKg: 8, breedKeywords: ['poodle'] }, today());
    expect(bath).toMatchObject({ excluded: false, fit: 1 });
    expect(bath.reasons).toEqual(['Phù hợp chó', 'Phù hợp độ tuổi 20 tuần', 'Phù hợp cân nặng 3.2 kg', 'Phù hợp giống Poodle']);
    expect(recoScore(bath.fit, 1, 0.6, weights)).toBe(92);
    expect(attributeFit(mochi, { species: 'DOG', minWeightKg: 15 }, today()).excluded).toBe(true);
    const spa = attributeFit(mochi, null, today());
    expect(spa).toEqual({ excluded: false, fit: 0.3, reasons: ['Phù hợp chung'] });
    expect(recoScore(spa.fit, 0.4, 0.5, weights)).toBe(37);
  });

  it('A: không đánh giá tiêu chí thiếu dữ liệu của thú; giống không khớp không loại; loài / tuổi khác thì loại; trọng số lấy từ tham số', () => {
    const noData = { species: 'CAT', breed: null, birthDate: null, weightKg: null } as const;
    expect(attributeFit(noData, { species: 'CAT', minAgeWeeks: 8, maxWeightKg: 5, breedKeywords: ['anh'] }, today())).toMatchObject({ excluded: false, fit: 1 }); // chỉ loài đánh giá được
    expect(attributeFit({ ...noData, breed: 'Mèo Anh lông ngắn' }, { breedKeywords: ['ba tu'] }, today())).toMatchObject({ excluded: false, fit: 0 }); // giống không khớp: không loại, không điểm
    expect(attributeFit({ ...noData, breed: 'Mèo Anh lông ngắn' }, { breedKeywords: ['anh'] }, today())).toMatchObject({ fit: 1 });
    expect(attributeFit(mochi, { species: 'CAT' }, today()).excluded).toBe(true);
    expect(attributeFit(mochi, { maxAgeWeeks: 10 }, today()).excluded).toBe(true);
    expect(attributeFit(mochi, { minAgeWeeks: 21 }, today()).excluded).toBe(true);
    expect(attributeFit(mochi, { minAgeWeeks: 20, maxAgeWeeks: 20, minWeightKg: 3.2, maxWeightKg: 3.2 }, today()).excluded).toBe(false); // biên inclusive
    expect(recoScore(1, 0, 0, { attr: 1, history: 0, popularity: 0 })).toBe(100);
    expect(recoScore(0.3, 0.4, 0.5, { attr: 0.2, history: 0.3, popularity: 0.5 })).toBe(43); // 100 × (0,06 + 0,12 + 0,25)
  });

  it('Bim (chó poodle 6,5 kg): đúng ứng viên, sắp theo nhóm rồi điểm giảm dần, giá theo đêm cho lưu trú; không có dịch vụ khám / tiêm, thuốc, vaccine, hàng ngừng bán, thú loài khác', async () => {
    const res = await recosOf(2);
    expect(res.petId).toBe(2);
    expect(Date.parse(res.generatedAt)).not.toBeNaN();
    expect(idsOf(res, 'GROOMING')).toEqual(expect.arrayContaining([3, 4]));
    expect(idsOf(res, 'BOARDING')).toEqual([5]);
    expect(idsOf(res, 'PRODUCT')).toEqual([1]); // Pate Whiskas (mèo) bị loại theo loài
    for (const item of res.items) {
      expect(item.score).toBeGreaterThanOrEqual(0);
      expect(item.score).toBeLessThanOrEqual(100);
      expect(item.reasons.length).toBeGreaterThan(0);
      expect(['SERVICE', 'PRODUCT']).toContain(item.itemType);
    }
    const order = ['GROOMING', 'BOARDING', 'PRODUCT'];
    expect(res.items.map((i) => order.indexOf(i.group))).toEqual([...res.items.map((i) => order.indexOf(i.group))].sort((a, b) => a - b));
    for (const group of order) {
      const scores = res.items.filter((i) => i.group === group).map((i) => i.score);
      expect(scores).toEqual([...scores].sort((a, b) => b - a));
    }
    expect(res.items.find((i) => i.group === 'BOARDING')).toMatchObject({ itemType: 'SERVICE', itemId: 5, name: 'Chuồng chó nhỏ', price: 200_000, priceUnit: 'đêm', priceIsFrom: false });
    expect(res.items.find((i) => i.itemId === 4)).toMatchObject({ priceIsFrom: true, priceUnit: null, reasons: expect.arrayContaining(['Phù hợp giống Poodle']) });
    expect(res.items.find((i) => i.group === 'PRODUCT')).toMatchObject({ itemType: 'PRODUCT', name: 'Hạt Royal Canin Mini Adult 2kg', imageUrl: '/imgs/prod1.jpg' });
    const ids = res.items.map((i) => `${i.itemType}:${i.itemId}`);
    for (const never of ['SERVICE:1', 'SERVICE:2', 'SERVICE:6', 'PRODUCT:3', 'PRODUCT:4', 'PRODUCT:5', 'PRODUCT:6', 'PRODUCT:7']) expect(ids, never).not.toContain(never);
  });

  it('Mochi (mèo): loại dịch vụ chỉ dành cho chó, không có loại chuồng mèo nào được bật ở chi nhánh; sản phẩm chỉ dành cho mèo', async () => {
    const res = await recosOf(1);
    expect(idsOf(res, 'GROOMING')).toEqual([3]);
    expect(idsOf(res, 'BOARDING')).toEqual([]);
    expect(idsOf(res, 'PRODUCT')).toEqual([2]);
    expect(res.items.find((i) => i.itemId === 2)!.reasons).toEqual(expect.arrayContaining(['Phù hợp mèo']));
  });

  it('lọc group, limit (1–12) và mặc định ai.reco.max_per_group đọc từ tham số', async () => {
    expect((await recosOf(2, { group: 'PRODUCT' })).items.every((i) => i.group === 'PRODUCT')).toBe(true);
    const one = await recosOf(2, { limit: 1 });
    for (const group of ['GROOMING', 'BOARDING', 'PRODUCT']) expect(idsOf(one, group).length).toBeLessThanOrEqual(1);
    expect(idsOf(await recosOf(2, { limit: 12 }), 'GROOMING')).toHaveLength(2);
    expect((await putConfig('ai.reco.max_per_group', '1')).status).toBe(200);
    expect(idsOf(await recosOf(2), 'GROOMING')).toHaveLength(1);
    expect(idsOf(await recosOf(2, { limit: 2 }), 'GROOMING')).toHaveLength(2); // limit tường minh thắng mặc định
  });

  it('đổi dữ liệu thú thì gợi ý đổi ngay (T78): Bim lên 12 kg → mất loại chuồng ≤ 10 kg và sản phẩm ≤ 10 kg', async () => {
    const auth = await customer();
    expect(idsOf(await recosOf(2, {}, auth), 'BOARDING')).toEqual([5]);
    expect((await client.post('/api/pets/2/weights', { weightKg: 12 }, auth)).status).toBe(201);
    const after = await recosOf(2, {}, auth);
    expect(idsOf(after, 'BOARDING')).toEqual([]);
    expect(idsOf(after, 'PRODUCT')).toEqual([]);
    expect(idsOf(after, 'GROOMING')).toEqual(expect.arrayContaining([3, 4])); // dịch vụ cắt tỉa chỉ cần ≥ 5 kg
  });

  it('hồ sơ UC94 đổi gợi ý ngay; xóa hồ sơ về điểm trung tính 0,3; trọng số [CFG] đổi điểm; ngừng kinh doanh hoặc hết hàng thì biến mất', async () => {
    const auth = await customer();
    const scoreOf = async (id: number) => (await recosOf(2, { group: 'GROOMING' }, auth)).items.find((i) => i.itemId === id)?.score;
    const before = await scoreOf(4);
    await client.put('/api/ai/item-profiles/SERVICE/4', { species: 'CAT', breedKeywords: [] }, await manager());
    expect(await scoreOf(4)).toBeUndefined(); // hồ sơ nay chỉ dành cho mèo → loại
    await client.put('/api/ai/item-profiles/SERVICE/4', { breedKeywords: [] }, await manager());
    const neutral = await scoreOf(4);
    expect(neutral).toBeLessThan(before!);
    expect((await putConfig('ai.reco.weight_attr', '1')).status).toBe(200);
    expect((await putConfig('ai.reco.weight_history', '0')).status).toBe(200);
    expect((await putConfig('ai.reco.weight_popularity', '0')).status).toBe(200);
    expect(await scoreOf(4)).toBe(30); // A = 0,3 trung tính, H = 0, P = 0
    const sm = await manager();
    expect((await client.patch('/api/services/4', { isActive: false }, sm)).status).toBe(200);
    expect(await scoreOf(4)).toBeUndefined(); // ngừng kinh doanh
    const created = (await client.post('/api/products', { categoryId: 4, sku: 'MOI-1', name: 'Hàng chưa có tồn', productType: 'GOODS', isPrescription: false, tracksExpiry: false, unit: 'cái', price: 10_000 }, sm)).data.data;
    await client.put(`/api/ai/item-profiles/PRODUCT/${created.productId}`, { breedKeywords: [] }, sm);
    expect(idsOf(await recosOf(2, {}, auth), 'PRODUCT')).not.toContain(created.productId); // tồn khả dụng = 0 ở mọi chi nhánh
  });

  it('thú của người khác / chủ cũ → 404 như không tồn tại; thú đã mất → 400 BR-KH-05', async () => {
    expect(await errorOf(client.get('/api/me/ai/pets/4/recommendations', await customer()))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy thú cưng #4']);
    await client.post('/api/pets/3/mark-deceased', { deceasedOn: isoDay(-1), confirmed: true }, await as(ROLES.RECEPTIONIST));
    expect(await errorOf(client.get('/api/me/ai/pets/3/recommendations', await customer()))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Thú cưng đã mất, không gợi ý được (BR-KH-05)']);
  });
});

// ─── State cũ với chatbot ───

describe('state localStorage cũ không làm nổ chatbot', () => {
  it.each([
    ['{}', {}],
    ['llm thiếu count, giới hạn thiếu', { llm: { date: '2000-01-01' }, limiter: {} }],
    ['limiter hỏng', { limiter: { minute: 'x', hour: null } }],
    ['provider không phải mảng', { provider: 'x' }],
  ])('%s', async (_name, stored) => {
    localStorage.setItem(STATE_KEY, JSON.stringify(stored));
    expect((await askPublic('Chó con tiêm phòng dại lúc mấy tuần?')).result).toBe('ANSWERED');
    expect((await askMe('Mochi tiêm khi nào?')).result).toBe('PERSONAL');
    expect(llmCalls()).toBe(1);
  });
});
