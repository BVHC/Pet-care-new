import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AccountSummary, Role } from '../../types/auth';
import { addDays } from '../../utils/branch-schedule';
import { ruleIdOf } from '../api-error';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';
import { updateVisitRow } from './visit.mock';
import { visitClinicalRoutes } from './visit-clinical.mock';

// Giờ cố định: thứ Sáu 23/10/2026 10:40 (giờ Việt Nam), chi nhánh 1 đang mở cửa. Dữ liệu mẫu của visit.mock (docs/testcases/T36-fe.md A.3):
//  - 2002 Lu (chó) lịch hẹn #10 Khám tổng quát, IN_PROGRESS, VET #5 · 2102 Tôm (hamster) walk-in WAITING, VET #5 · 2101 Miu cấp cứu WAITING chưa gán
//  - 2103 Bim Thẩm mỹ WAITING (CARETAKER #6) · 2104 Mochi Thẩm mỹ IN_PROGRESS, người phụ trách #12 không còn
//  - COMPLETED: 61 (hôm nay, Order #2 PENDING), 58, 40, 2001 — dữ liệu mẫu bệnh án của mock lâm sàng (visit-clinical.mock.ts) cho 61, 58, 40, 2001.
// Chuẩn hành vi: BE (MedicalRecordService, VisitCompletionService; docs/adr/0066–0070). Tiêm chủng (#18–20, T28, ADR 0072–0075) và kê đơn (#16–17, T38, ADR 0076–0078) chép từ service của BE.
const NOW = new Date('2026-10-23T10:40:00+07:00');
const TODAY = '2026-10-23';

let client: AxiosInstance;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  client = axios.create();
  installMockApi(client);
});
afterEach(() => vi.useRealTimers());

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const desk = () => as('reception@store1.vn');
const vet = () => as('doctor@store1.vn');
const caretaker = () => as('caretaker@store1.vn');
const manager = () => as('manager@store1.vn');
const chain = () => as('chuoi@petcare.vn');
const admin = () => as('admin@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}
const ruleOf = (res: AxiosResponse) => ruleIdOf(res.data.message);

/** Tài khoản nhân viên không đăng nhập được trong mock (chi nhánh 2 chỉ có quản lý), nên gọi thẳng handler với người gọi giả. */
const callerOf = (role: Role, id: number, branchId: number): AccountSummary => ({ id, email: `u${id}@store${branchId}.vn`, role, status: 'ACTIVE', isLocked: false, mustChangePassword: false, branchId });
const direct = (key: string, account: AccountSummary, over: { body?: unknown; params?: Record<string, string>; query?: Record<string, string> } = {}) =>
  visitClinicalRoutes[key]({ body: over.body, account, params: over.params ?? {}, query: over.query ?? {} });

const RECORD_KEYS = ['addenda', 'diagnosis', 'examination', 'followUpDate', 'internalNote', 'lastEditedBy', 'locked', 'prescriptions', 'treatmentPlan', 'vaccinations', 'visitId'];
const EMPTY_RECORD = { examination: null, diagnosis: null, treatmentPlan: null, internalNote: null, followUpDate: null, locked: false, lastEditedBy: null, prescriptions: [], vaccinations: [], addenda: [] };

/** Dòng đơn thuốc mẫu của lượt 61: Amoxicillin x10, khớp dòng Order DRUG #22 của Order #2 (bất biến isExternalPurchase === (orderLineId == null)). */
const SEED_61_ITEM = {
  itemId: 2,
  productId: 3,
  productName: 'Amoxicillin 250mg',
  quantity: 10,
  dosageInstructions: 'Ngày 2 lần, mỗi lần 1 viên sau ăn, uống 5 ngày',
  isExternalPurchase: false,
  externalReason: null,
  orderLineId: 22,
};

const getRecord = async (visitId: number | string, who: () => Promise<object> = vet) => client.get(`/api/visits/${visitId}/medical-record`, await who());
const putRecord = async (visitId: number | string, body: unknown, who: () => Promise<object> = vet) => client.put(`/api/visits/${visitId}/medical-record`, body, await who());

// ─── Xem bệnh án (#12) ───

describe('xem bệnh án (#12, UC48, UC53)', () => {
  it('lượt Khám/Tiêm chưa ghi bệnh án trả khung rỗng; VET và CARETAKER đều xem được', async () => {
    for (const who of [vet, caretaker]) {
      const res = await getRecord(2002, who);
      expect(res.status).toBe(200);
      expect(res.data.message).toBe('Bệnh án của lượt');
      expect(Object.keys(res.data.data).sort()).toEqual(RECORD_KEYS);
      expect(res.data.data).toEqual({ visitId: 2002, ...EMPTY_RECORD });
    }
  });

  it('lượt đã hoàn tất: locked = true, kèm bản bổ sung theo thứ tự ghi và tên người ghi (dữ liệu mẫu lượt 61)', async () => {
    const record = (await getRecord(61)).data.data;
    expect(record).toMatchObject({ visitId: 61, locked: true, lastEditedBy: 5, vaccinations: [] });
    expect(record.prescriptions).toEqual([SEED_61_ITEM]);
    expect(record.diagnosis).toBeTruthy();
    expect(record.followUpDate).toBe(addDays(TODAY, 7));
    expect(record.addenda).toHaveLength(1);
    expect(record.addenda[0]).toMatchObject({ isInternal: false, createdBy: 5, createdByName: 'BS. Minh Anh' });
    expect(Object.keys(record.addenda[0]).sort()).toEqual(['addendumId', 'content', 'createdAt', 'createdBy', 'createdByName', 'isInternal']);
  });

  it('chưa hoàn tất thì chưa có bản bổ sung; bệnh án vừa ghi trả về đúng nội dung', async () => {
    await putRecord(2002, { diagnosis: 'Viêm da' });
    const record = (await getRecord(2002)).data.data;
    expect(record).toMatchObject({ diagnosis: 'Viêm da', locked: false, addenda: [] });
  });

  it('thứ tự lỗi: path sai kiểu 400 (trước cả vai trò) → vai trò 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404', async () => {
    const malformed = await failure(getRecord('abc', admin));
    expect([malformed.status, malformed.data.errorCode, malformed.data.message]).toEqual([400, 'MALFORMED_REQUEST', "Tham số 'visitId' sai kiểu dữ liệu"]);

    for (const who of [desk, manager, chain, admin]) {
      const denied = await failure(getRecord(2002, who));
      expect([denied.status, denied.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
    const missing = await failure(getRecord(99999));
    expect([missing.status, missing.data.errorCode, missing.data.message]).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy lượt tiếp nhận #99999']);

    const otherBranch = direct('GET /api/visits/:visitId/medical-record', callerOf('VET', 55, 2), { params: { visitId: '2002' } });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);

    const grooming = await failure(getRecord(2103));
    expect([grooming.status, grooming.data.message]).toEqual([404, 'Không tìm thấy bệnh án #2103']);
    expect((await failure(getRecord(2104, caretaker))).status).toBe(404);
  });
});

// ─── Ghi bệnh án (#13) ───

describe('ghi bệnh án (#13, UC48; BR-KB-01, BR-KB-06)', () => {
  it('VET được gán ghi lần đầu: 200 "Đã lưu bệnh án", bỏ khoảng trắng hai đầu, chuỗi rỗng thành null, lastEditedBy là người ghi', async () => {
    const res = await putRecord(2002, { examination: '  Sốt 39,5°C, bỏ ăn  ', diagnosis: 'Viêm dạ dày ruột', treatmentPlan: '   ', internalNote: null, followUpDate: '2026-11-05' });
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã lưu bệnh án');
    expect(Object.keys(res.data.data).sort()).toEqual(RECORD_KEYS);
    expect(res.data.data).toEqual({ visitId: 2002, examination: 'Sốt 39,5°C, bỏ ăn', diagnosis: 'Viêm dạ dày ruột', treatmentPlan: null, internalNote: null, followUpDate: '2026-11-05', locked: false, lastEditedBy: 5, prescriptions: [], vaccinations: [], addenda: [] });
    expect((await getRecord(2002)).data.data).toEqual(res.data.data);
  });

  it('PUT thay toàn bộ 5 trường: trường vắng hoặc null là xóa giá trị', async () => {
    await putRecord(2002, { examination: 'A', diagnosis: 'B', treatmentPlan: 'C', internalNote: 'D', followUpDate: '2026-11-05' });
    const res = await putRecord(2002, { diagnosis: 'B mới' });
    expect(res.data.data).toMatchObject({ examination: null, diagnosis: 'B mới', treatmentPlan: null, internalNote: null, followUpDate: null });
  });

  it('nội dung không đổi (sau khi chuẩn hóa): 200 và không đổi lastEditedBy; đổi nội dung thì người ghi mới được ghi nhận (ADR-0067 §4)', async () => {
    await putRecord(2002, { diagnosis: 'Viêm da' });
    updateVisitRow(2002, { assigneeId: 7 }); // gán lại cho VET khác (BR-TN-08)
    const colleague = callerOf('VET', 7, 1);
    const call = (body: unknown) => direct('PUT /api/visits/:visitId/medical-record', colleague, { params: { visitId: '2002' }, body });

    const unchanged = call({ diagnosis: '  Viêm da  ' });
    expect(unchanged.status).toBe(200);
    expect((unchanged.body as { data: { lastEditedBy: number } }).data.lastEditedBy).toBe(5);

    const changed = call({ diagnosis: 'Viêm da dị ứng' });
    expect((changed.body as { data: { lastEditedBy: number; diagnosis: string } }).data).toMatchObject({ lastEditedBy: 7, diagnosis: 'Viêm da dị ứng' });
  });

  it('ngày tái khám (BR-KB-06): phải sau ngày khám và không quá 180 ngày; ngày khám là ngày check-in theo giờ Việt Nam', async () => {
    const message = 'Ngày tái khám phải sau ngày khám và không quá 180 ngày (BR-KB-06)';
    for (const followUpDate of [TODAY, addDays(TODAY, -1), addDays(TODAY, 181)]) {
      const res = await failure(putRecord(2002, { diagnosis: 'x', followUpDate }));
      expect([res.status, res.data.errorCode, res.data.message, ruleOf(res)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', message, 'BR-KB-06']);
    }
    expect((await putRecord(2002, { followUpDate: addDays(TODAY, 1) })).status).toBe(200);
    expect((await putRecord(2002, { followUpDate: addDays(TODAY, 180) })).data.data.followUpDate).toBe(addDays(TODAY, 180));
    // Lỗi BR-KB-06 không ghi gì: bệnh án vẫn là lần ghi hợp lệ trước đó.
    await failure(putRecord(2002, { diagnosis: 'sẽ không lưu', followUpDate: TODAY }));
    expect((await getRecord(2002)).data.data.diagnosis).toBeNull();
  });

  it('thứ tự lỗi: body sai kiểu 400 (trước cả vai trò) → vai trò 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → không phải người phụ trách 403 → không IN_PROGRESS 409 → BR-KB-06 400', async () => {
    const malformed = await failure(putRecord(2002, { followUpDate: 'không phải ngày' }, admin));
    expect([malformed.status, malformed.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    expect((await failure(putRecord(2002, [], admin))).data.errorCode).toBe('MALFORMED_REQUEST');

    for (const who of [desk, caretaker, manager, chain, admin]) {
      const denied = await failure(putRecord(2002, { diagnosis: 'x' }, who));
      expect([denied.status, denied.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
    expect((await failure(putRecord(99999, { diagnosis: 'x' }))).status).toBe(404);
    const otherBranch = direct('PUT /api/visits/:visitId/medical-record', callerOf('VET', 55, 2), { params: { visitId: '2002' }, body: {} });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    expect((await failure(putRecord(2103, { diagnosis: 'x' }))).status).toBe(404);

    // Lượt chưa gán (2101) và lượt gán cho VET khác: 403 đứng TRƯỚC 409, nên người không phải chủ lượt không dò được trạng thái.
    const unassigned = await failure(putRecord(2101, { diagnosis: 'x' }));
    expect([unassigned.status, unassigned.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    updateVisitRow(2002, { assigneeId: 7 });
    expect((await failure(putRecord(2002, { diagnosis: 'x' }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    updateVisitRow(2002, { assigneeId: 5 });

    const waiting = await failure(putRecord(2102, { diagnosis: 'x', followUpDate: TODAY }));
    expect([waiting.status, waiting.data.errorCode, waiting.data.message]).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang IN_PROGRESS']);
    const done = await failure(putRecord(61, { diagnosis: 'sửa sau khi khóa' }));
    expect([done.status, done.data.message]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS']);
    expect((await getRecord(61)).data.data.diagnosis).not.toBe('sửa sau khi khóa');
  });
});

// ─── Hoàn tất lượt (#10) ───

const VISIT_KEYS = ['appointmentId', 'assigneeId', 'assigneeName', 'branchId', 'calledAt', 'cancelReason', 'cancelledAt', 'checkedInAt', 'code', 'completedAt', 'customerId', 'customerName', 'needsReassign', 'orderId', 'petId', 'petName', 'priorityClass', 'queueSortKey', 'serviceGroup', 'species', 'status', 'visitId'];
const complete = async (visitId: number | string, who: () => Promise<object> = vet) => client.post(`/api/visits/${visitId}/complete`, undefined, await who());
const visitOf = async (visitId: number) => (await client.get(`/api/visits/${visitId}`, await desk())).data.data;
const orderOf = async (orderId: number) => (await client.get(`/api/orders/${orderId}`, await desk())).data.data;
const appointmentOf = async (appointmentId: number) => (await client.get(`/api/appointments/${appointmentId}`, await desk())).data.data;
const message = (res: AxiosResponse) => res.data.message as string;

/** Tiếp nhận khách vãng lai, lễ tân gán, người phụ trách gọi: lượt IN_PROGRESS với Order VISIT OPEN một dòng tự sinh. */
async function startWalkIn(petId: number, serviceId: number, assigneeId: number, assignee: () => Promise<object>) {
  const visit = (await client.post('/api/visits', { petId, serviceId }, await desk())).data.data;
  await client.post(`/api/visits/${visit.visitId}/assign`, { assigneeId }, await desk());
  await client.post(`/api/visits/${visit.visitId}/call`, undefined, await assignee());
  return visitOf(visit.visitId);
}

describe('hoàn tất lượt Khám/Tiêm (#10, UC48; BR-KB-02)', () => {
  it('lượt khám có chẩn đoán: Visit#5 COMPLETED + lịch hẹn COMPLETED + Order PENDING + bệnh án khóa, gỡ cờ cần gán lại', async () => {
    await putRecord(2002, { examination: 'Sốt', diagnosis: 'Viêm da dị ứng' });
    updateVisitRow(2002, { needsReassign: true });
    const before = await visitOf(2002);
    expect(before).toMatchObject({ status: 'IN_PROGRESS', appointmentId: 10 });

    const res = await complete(2002);
    expect(res.status).toBe(200);
    expect(message(res)).toBe('Đã hoàn tất lượt');
    expect(Object.keys(res.data.data).sort()).toEqual(VISIT_KEYS);
    expect(res.data.data).toMatchObject({ visitId: 2002, status: 'COMPLETED', completedAt: '2026-10-23T03:40:00Z', needsReassign: false, assigneeId: 5 });

    expect(await visitOf(2002)).toMatchObject({ status: 'COMPLETED', completedAt: '2026-10-23T03:40:00Z' });
    expect(await orderOf(before.orderId)).toMatchObject({ status: 'PENDING', pendingAt: '2026-10-23T03:40:00Z' });
    expect((await appointmentOf(10)).status).toBe('COMPLETED');
    expect((await getRecord(2002)).data.data).toMatchObject({ locked: true, diagnosis: 'Viêm da dị ứng' });
    expect((await client.get('/api/me/queue', await vet())).data.data.map((v: { visitId: number }) => v.visitId)).not.toContain(2002);
  });

  it('thú hết bị khóa bởi lượt đã xong: đánh dấu đã mất được (BR-KH-05 chỉ chặn khi còn lượt chưa kết thúc)', async () => {
    const markDeceased = async () => client.post('/api/pets/4/mark-deceased', { deceasedOn: TODAY, confirmed: true }, await desk());
    expect((await failure(markDeceased())).data.message).toContain('BR-KH-05');
    await putRecord(2002, { diagnosis: 'Khỏe' });
    await complete(2002);
    expect((await markDeceased()).status).toBe(200);
  });

  it('khám thiếu chẩn đoán (kể cả chỉ khoảng trắng): 400 BR-KB-02 và KHÔNG ghi gì — lượt, lịch hẹn, Order, bệnh án giữ nguyên', async () => {
    const before = await visitOf(2002);
    for (const body of [undefined, { examination: 'Sốt' }, { diagnosis: '   ' }]) {
      if (body) await putRecord(2002, body);
      const res = await failure(complete(2002));
      expect([res.status, res.data.errorCode, message(res), ruleOf(res)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất (BR-KB-02)', 'BR-KB-02']);
    }
    expect(await visitOf(2002)).toMatchObject({ status: 'IN_PROGRESS', completedAt: null });
    expect((await orderOf(before.orderId)).status).toBe('OPEN');
    expect((await appointmentOf(10)).status).toBe('CHECKED_IN');
    expect((await getRecord(2002)).data.data.locked).toBe(false);
  });

  it('đọc dịch vụ từ Order lúc hoàn tất, không từ lượt: VET đổi dòng tự sinh sang Tiêm phòng → chỉ tiêm, chưa có mũi → 400 BR-KB-02 (BR-SP-06)', async () => {
    const order = await orderOf((await visitOf(2002)).orderId);
    await client.patch(`/api/orders/${order.orderId}/lines/${order.lines[0].lineId}`, { serviceId: 2 }, await vet());
    const res = await failure(complete(2002));
    expect([res.status, message(res)]).toEqual([400, 'Lượt chỉ có dịch vụ tiêm phải ghi nhận ít nhất 1 mũi tiêm (BR-KB-02)']);
    // Đổi ngược về Khám thì lại cần chẩn đoán, có chẩn đoán thì hoàn tất được.
    await client.patch(`/api/orders/${order.orderId}/lines/${order.lines[0].lineId}`, { serviceId: 1 }, await vet());
    expect(message(await failure(complete(2002)))).toBe('Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất (BR-KB-02)');
    await putRecord(2002, { diagnosis: 'Khỏe' });
    expect((await complete(2002)).status).toBe(200);
  });

  it('lượt chỉ có dịch vụ tiêm mà chưa ghi mũi nào: 400 BR-KB-02 (không cần chẩn đoán)', async () => {
    const visit = await startWalkIn(2, 2, 5, vet);
    const res = await failure(complete(visit.visitId));
    expect([res.status, message(res)]).toEqual([400, 'Lượt chỉ có dịch vụ tiêm phải ghi nhận ít nhất 1 mũi tiêm (BR-KB-02)']);
    expect((await visitOf(visit.visitId)).status).toBe('IN_PROGRESS');
  });

  it('A16: VET xóa dòng dịch vụ tự sinh → Order không còn dịch vụ Khám/Tiêm → 400 BR-KB-02', async () => {
    const order = await orderOf((await visitOf(2002)).orderId);
    await client.delete(`/api/orders/${order.orderId}/lines/${order.lines[0].lineId}`, await vet());
    await putRecord(2002, { diagnosis: 'Khỏe' });
    const res = await failure(complete(2002));
    expect([res.status, message(res)]).toEqual([400, 'Lượt khám/tiêm phải còn ít nhất 1 dịch vụ khám hoặc tiêm (BR-KB-02)']);
  });

  it('sau khi hoàn tất: ghi bệnh án và hoàn tất lần hai đều 409', async () => {
    await putRecord(2002, { diagnosis: 'Khỏe' });
    await complete(2002);
    expect(message(await failure(putRecord(2002, { diagnosis: 'sửa' })))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS');
    expect(message(await failure(complete(2002)))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang COMPLETED');
  });
});

describe('hoàn tất lượt Thẩm mỹ (#10, UC52)', () => {
  it('CARETAKER gọi rồi hoàn tất: không cần bệnh án, Order PENDING', async () => {
    await client.post('/api/visits/2103/call', undefined, await caretaker());
    const res = await complete(2103, caretaker);
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ visitId: 2103, status: 'COMPLETED', serviceGroup: 'GROOMING', completedAt: '2026-10-23T03:40:00Z' });
    expect((await orderOf(res.data.data.orderId)).status).toBe('PENDING');
    expect((await client.get('/api/me/queue', await caretaker())).data.data.map((v: { visitId: number }) => v.visitId)).not.toContain(2103);
  });

  it('lượt Thẩm mỹ không có bệnh án nên hoàn tất không kiểm BR-KB-02 dù Order không có dịch vụ Khám/Tiêm', async () => {
    await client.post('/api/visits/2103/call', undefined, await caretaker());
    const order = await orderOf((await visitOf(2103)).orderId);
    expect(order.lines.map((l: { serviceId: number }) => l.serviceId)).toEqual([3]);
    expect((await complete(2103, caretaker)).status).toBe(200);
  });
});

describe('hoàn tất lượt: thứ tự lỗi', () => {
  it('path sai kiểu 400 (trước cả vai trò) → vai trò 403 → lượt 404 → chi nhánh 403', async () => {
    const malformed = await failure(complete('abc', admin));
    expect([malformed.status, malformed.data.errorCode, message(malformed)]).toEqual([400, 'MALFORMED_REQUEST', "Tham số 'visitId' sai kiểu dữ liệu"]);
    for (const who of [desk, manager, chain, admin]) {
      const denied = await failure(complete(2002, who));
      expect([denied.status, denied.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
    expect((await failure(complete(99999))).status).toBe(404);
    const otherBranch = direct('POST /api/visits/:visitId/complete', callerOf('VET', 55, 2), { params: { visitId: '2002' } });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
  });

  it('không phải người phụ trách hiện tại 403 (kể cả lượt chưa gán, lượt gán cho người khác) → BR-TN-05 400 (đổi chức vụ) → 409', async () => {
    expect((await failure(complete(2101))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(complete(2104, caretaker))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(complete(2002, caretaker))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');

    // Lượt Thẩm mỹ đang chờ mà người phụ trách là VET (đổi chức vụ sau khi gán): 400 BR-TN-05 đứng TRƯỚC 409 trạng thái.
    updateVisitRow(2103, { assigneeId: 5 });
    const wrongRole = await failure(complete(2103));
    expect([wrongRole.status, message(wrongRole), ruleOf(wrongRole)]).toEqual([400, 'Chức vụ hiện tại không phụ trách được nhóm dịch vụ của lượt — cần gán lại lượt (BR-TN-05)', 'BR-TN-05']);

    const waiting = await failure(complete(2102));
    expect([waiting.status, waiting.data.errorCode, message(waiting)]).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang COMPLETED']);
    expect(message(await failure(complete(61)))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang COMPLETED');
  });
});

// ─── Tiêm chủng: gợi ý mũi và sản phẩm (#18, UC49; BR-KB-04) ───
// Chép từ BE T28 (VaccinationOptionService, ADR-0073). Dữ liệu mẫu: Lu (chó, sinh 2021) đang khám ở 2002; loại 1 "Dại" (chó) có phác đồ #3 (mũi 1, 365 ngày, ≥ 12 tuần);
// loại 2 "5 bệnh" (chó) có #1 (mũi 1, 21 ngày, ≥ 6 tuần) và #2 (mũi 2, 21 ngày, ≥ 9 tuần); loại 3 "Dại" là của mèo. Vaccine "Dại Rabisin" (#5) còn 15 liều lô RB2401
// và 2 liều lô RB2309 ĐÃ HẾT HẠN; "5 bệnh Vanguard" (#6) còn 4 liều lô VG2402.
const options = async (visitId: number | string, vaccineTypeId?: number | string, who: () => Promise<object> = vet) =>
  client.get(`/api/visits/${visitId}/vaccination-options`, { ...(await who()), params: vaccineTypeId === undefined ? {} : { vaccineTypeId } });

describe('gợi ý mũi tiêm và sản phẩm vaccine (#18, UC49; BR-KB-04)', () => {
  it('chưa tiêm loại này tại hệ thống: gợi ý mũi 1 của phác đồ đúng loài; chỉ liệt kê vaccine thuộc loại còn tồn khả dụng (bỏ lô đã hết hạn)', async () => {
    const res = await options(2002, 1);
    expect(res.status).toBe(200);
    expect(message(res)).toBe('Gợi ý mũi tiêm');
    expect(res.data.data).toEqual({
      vaccineTypeId: 1,
      protocol: [{ protocolId: 3, doseNumber: 1, intervalDays: 365, minAgeWeeks: 12, requiredForBoarding: true }],
      suggestedProtocolId: 3,
      lastDose: null,
      products: [{ productId: 5, name: 'Vaccine Dại Rabisin', availableQuantity: 15 }],
      underMinAge: false,
    });
    const fiveInOne = (await options(2002, 2)).data.data;
    expect(fiveInOne.protocol.map((p: { protocolId: number; doseNumber: number }) => [p.protocolId, p.doseNumber])).toEqual([[1, 1], [2, 2]]);
    expect(fiveInOne).toMatchObject({ suggestedProtocolId: 1, lastDose: null, underMinAge: false, products: [{ productId: 6, name: 'Vaccine 5 bệnh Vanguard', availableQuantity: 4 }] });
  });

  it('loại vaccine không thuộc loài của thú (hoặc thú không có phác đồ nào, như hamster): 400 BR-KB-04', async () => {
    const wrongSpecies = await failure(options(2002, 3));
    expect([wrongSpecies.status, wrongSpecies.data.errorCode, message(wrongSpecies), ruleOf(wrongSpecies)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Loại vaccine không áp dụng cho loài của thú (BR-KB-04)', 'BR-KB-04']);
    await client.post('/api/visits/2102/call', undefined, await vet());
    expect((await failure(options(2102, 1))).status).toBe(400);
  });

  it('sản phẩm theo TÊN rồi id như BE, không theo id: vaccine Dại (id 5) chuyển sang loại 5 bệnh có tên đứng sau vaccine 5 bệnh (id 6)', async () => {
    expect((await client.patch('/api/products/5', { vaccineTypeId: 2 }, await chain())).status).toBe(200);
    const { products } = (await options(2002, 2)).data.data;
    expect(products.map((p: { productId: number; name: string }) => [p.productId, p.name])).toEqual([[6, 'Vaccine 5 bệnh Vanguard'], [5, 'Vaccine Dại Rabisin']]);
  });

  it('loại vaccine đã ngừng sử dụng: 400 BR-SP-07 ở cả #18 và #19 (đứng trước kiểm mũi / sản phẩm, sau kiểm loài); không ghi gì', async () => {
    await client.patch('/api/vaccine-types/2', { isActive: false }, await chain()); // 5 bệnh (chó)
    await client.patch('/api/vaccine-types/3', { isActive: false }, await chain()); // Dại (mèo)
    for (const call of [() => options(2002, 2), () => vaccinate(2002, { vaccineTypeId: 2, protocolId: 99, productId: 99 })]) {
      const stopped = await failure(call());
      expect([stopped.status, stopped.data.errorCode, message(stopped), ruleOf(stopped)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Loại vaccine đã ngừng sử dụng (BR-SP-07)', 'BR-SP-07']);
    }
    // Loại của mèo đã ngừng mà thú là chó: sai loài đứng TRƯỚC ngừng sử dụng.
    for (const call of [() => options(2002, 3), () => vaccinate(2002, { vaccineTypeId: 3, protocolId: 99, productId: 99 })]) {
      expect(message(await failure(call()))).toBe(`${NOT_FOR_SPECIES} (BR-KB-04)`);
    }
    expect(await dosesOf(2002)).toEqual([]);
  });

  it('thứ tự lỗi: tham số 400 (trước cả vai trò) → vai trò VET 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → loại vaccine 404', async () => {
    const missing = await failure(options(2002, undefined, admin));
    expect([missing.status, missing.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    expect((await failure(options('abc', 1, admin))).data.message).toBe("Tham số 'visitId' sai kiểu dữ liệu");
    expect((await failure(options(2002, 'x', admin))).data.message).toBe("Tham số 'vaccineTypeId' sai kiểu dữ liệu");

    for (const who of [desk, caretaker, manager, chain, admin]) {
      const denied = await failure(options(2002, 1, who));
      expect([denied.status, denied.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
    expect((await failure(options(99999, 1))).status).toBe(404);
    const otherBranch = direct('GET /api/visits/:visitId/vaccination-options', callerOf('VET', 55, 2), { params: { visitId: '2002' }, query: { vaccineTypeId: '1' } });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    const grooming = await failure(options(2103, 1));
    expect([grooming.status, message(grooming)]).toEqual([404, 'Không tìm thấy lượt khám / tiêm #2103']);
    expect((await failure(options(2101, 3))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    const unknownType = await failure(options(2002, 99));
    expect([unknownType.status, message(unknownType)]).toEqual([404, 'Không tìm thấy loại vaccine #99']);
  });
});

// ─── Tiêm chủng: ghi nhận mũi (#19) và xóa mũi ghi nhầm (#20) ───
// Thứ tự kiểm chép từ BE (VaccinationRecordService): body 400 → vai trò 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → trạng thái 409 → loại vaccine 404 → loài 400 BR-KB-04
// → ngừng sử dụng 400 BR-SP-07 → mũi 400 BR-KB-04 → sản phẩm 400 BR-KB-04 → tồn 400 BR-KB-04 (id lạ của mũi / sản phẩm là 400, không phải 404). #20: người phụ trách HIỆN TẠI 403 → trạng thái 409 → mũi 404.

const vaccinate = async (visitId: number | string, body: unknown, who: () => Promise<object> = vet) => client.post(`/api/visits/${visitId}/vaccinations`, body, await who());
const removeDose = async (visitId: number | string, vaccinationId: number | string, who: () => Promise<object> = vet) =>
  client.delete(`/api/visits/${visitId}/vaccinations/${vaccinationId}`, await who());
const WRONG_DOSE = 'Mũi tiêm không thuộc phác đồ đang dùng của loại vaccine cho loài này (BR-KB-04)';
const WRONG_PRODUCT = 'Sản phẩm không phải vaccine đang kinh doanh của loại đã chọn (BR-KB-04)';
const NOT_FOR_SPECIES = 'Loại vaccine không áp dụng cho loài của thú';
const RABIES = { vaccineTypeId: 1, protocolId: 3, productId: 5 };
const FIVE_DOSE_1 = { vaccineTypeId: 2, protocolId: 1, productId: 6 };
const FIVE_DOSE_2 = { vaccineTypeId: 2, protocolId: 2, productId: 6 };
const VACCINATION_KEYS = ['administeredOn', 'branchId', 'doseNumber', 'expiryDate', 'lotNumber', 'nextDueDate', 'orderLineId', 'productId', 'productName', 'protocolId', 'recordedBy', 'superseded', 'vaccinationId', 'vaccineTypeId', 'vaccineTypeName', 'visitId'];
const stock = async (productId: number) => (await client.get(`/api/inventory/${productId}`, await vet())).data.data;
const lotsOf = async (productId: number) => (await client.get(`/api/inventory/${productId}/lots`, await manager())).data.data as { lotNumber: string | null; quantity: number; expired: boolean }[];
const linesOf = async (visitId: number) => (await orderOf((await visitOf(visitId)).orderId)).lines as { lineId: number; lineType: string }[];
const dosesOf = async (visitId: number) => (await getRecord(visitId)).data.data.vaccinations as { vaccinationId: number; doseNumber: number; superseded: boolean }[];

describe('ghi nhận mũi tiêm (#19, UC49, ST06; BR-KB-04, BR-KO-05)', () => {
  it('VET được gán ghi mũi 1 trong lượt khám: 201, trừ kho 1 liều, thêm dòng Order VACCINE (giá chụp), ngày tái chủng = hôm nay + khoảng cách của mũi', async () => {
    const before = await stock(6);
    const res = await vaccinate(2002, FIVE_DOSE_1);
    expect(res.status).toBe(201);
    expect(message(res)).toBe('Đã ghi nhận mũi tiêm');
    const { vaccination, warnings } = res.data.data;
    expect(warnings).toEqual([]);
    expect(Object.keys(vaccination).sort()).toEqual(VACCINATION_KEYS);
    expect(vaccination).toMatchObject({ visitId: 2002, branchId: 1, vaccineTypeId: 2, vaccineTypeName: '5 bệnh', protocolId: 1, doseNumber: 1, productId: 6, productName: 'Vaccine 5 bệnh Vanguard', lotNumber: null, expiryDate: null, administeredOn: TODAY, nextDueDate: addDays(TODAY, 21), superseded: false, recordedBy: 5 });
    // Mũi không mang số lô / hạn (BE chưa điền, D046) nhưng kho vẫn trừ đúng lô FEFO.
    expect((await lotsOf(6)).find((l) => l.lotNumber === 'VG2402')?.quantity).toBe(before.availableQuantity - 1);

    expect((await stock(6)).availableQuantity).toBe(before.availableQuantity - 1);
    const order = await orderOf((await visitOf(2002)).orderId);
    const line = order.lines.find((l: { lineId: number }) => l.lineId === vaccination.orderLineId);
    expect(line).toMatchObject({ lineType: 'VACCINE', productId: 6, description: 'Vaccine 5 bệnh Vanguard', quantity: 1, unitPrice: 180_000, addedBy: 5, canEdit: false, canDelete: false });
    expect((await getRecord(2002)).data.data.vaccinations).toEqual([vaccination]);
  });

  it('FEFO (BR-KO-05): trừ lô chưa hết hạn có hạn gần nhất, không bao giờ lấy lô đã hết hạn dù hạn sớm hơn', async () => {
    const res = await vaccinate(2002, RABIES);
    expect(res.data.data.vaccination).toMatchObject({ lotNumber: null, expiryDate: null });
    const lots = await lotsOf(5);
    expect(lots.find((l) => l.lotNumber === 'RB2309')).toMatchObject({ quantity: 2, expired: true });
    expect(lots.find((l) => l.lotNumber === 'RB2401')?.quantity).toBe(14);
  });

  it('hết tồn khả dụng: 400 BR-KB-04 và không ghi gì; sản phẩm hết liều biến khỏi gợi ý', async () => {
    for (let i = 0; i < 4; i += 1) expect((await vaccinate(2002, FIVE_DOSE_1)).status).toBe(201);
    expect((await options(2002, 2)).data.data.products).toEqual([]);
    const lines = (await linesOf(2002)).length;
    const res = await failure(vaccinate(2002, FIVE_DOSE_1));
    expect([res.status, message(res), ruleOf(res)]).toEqual([400, 'Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh (BR-KB-04)', 'BR-KB-04']);
    expect((await linesOf(2002)).length).toBe(lines);
    expect(await dosesOf(2002)).toHaveLength(4);
    expect((await stock(6)).availableQuantity).toBe(0);
  });

  it('sản phẩm không phải vaccine của loại, mũi không thuộc phác đồ đúng loài (kể cả id không tồn tại: vẫn 400, không 404), loại không đúng loài: 400 BR-KB-04 và không ghi gì (kho, Order, mũi tiêm)', async () => {
    const cases: [object, string][] = [
      [{ ...FIVE_DOSE_1, productId: 5 }, WRONG_PRODUCT], // vaccine Dại cho loại 5 bệnh
      [{ ...FIVE_DOSE_1, productId: 1 }, WRONG_PRODUCT], // hạt, không phải vaccine
      [{ ...FIVE_DOSE_1, productId: 99 }, WRONG_PRODUCT], // sản phẩm không tồn tại
      [{ ...FIVE_DOSE_1, protocolId: 3 }, WRONG_DOSE], // phác đồ của loại Dại
      [{ ...FIVE_DOSE_1, protocolId: 4 }, WRONG_DOSE], // phác đồ của mèo
      [{ ...FIVE_DOSE_1, protocolId: 99 }, WRONG_DOSE], // mũi không tồn tại
      [{ ...FIVE_DOSE_1, protocolId: 99, productId: 99 }, WRONG_DOSE], // mũi đứng trước sản phẩm
      [{ vaccineTypeId: 3, protocolId: 5, productId: 5 }, `${NOT_FOR_SPECIES} (BR-KB-04)`], // loại Dại của mèo cho chó
      [{ vaccineTypeId: 3, protocolId: 99, productId: 99 }, `${NOT_FOR_SPECIES} (BR-KB-04)`], // loài đứng trước mũi và sản phẩm
    ];
    const lines = (await linesOf(2002)).length;
    const available = (await stock(6)).availableQuantity;
    for (const [body, expected] of cases) {
      const res = await failure(vaccinate(2002, body));
      expect([res.status, message(res), ruleOf(res)]).toEqual([400, expected, 'BR-KB-04']);
    }
    expect((await linesOf(2002)).length).toBe(lines);
    expect((await stock(6)).availableQuantity).toBe(available);
    expect(await dosesOf(2002)).toEqual([]);
  });

  it('thú chưa đủ tuổi tối thiểu của mũi: vẫn 201 kèm cảnh báo UNDER_MIN_AGE (không chặn); gợi ý báo underMinAge', async () => {
    const puppy = (await client.post('/api/customers/1/pets', { name: 'Cún con', species: 'DOG', sex: 'MALE', birthDate: addDays(TODAY, -28) }, await desk())).data.data.pet;
    const visit = await startWalkIn(puppy.petId, 2, 5, vet);
    expect((await options(visit.visitId, 2)).data.data).toMatchObject({ suggestedProtocolId: 1, underMinAge: true });
    const res = await vaccinate(visit.visitId, FIVE_DOSE_1);
    expect(res.status).toBe(201);
    expect(res.data.data.warnings).toEqual(['UNDER_MIN_AGE']);
    expect(res.data.data.vaccination.doseNumber).toBe(1);
  });

  it('mũi đã ngừng dùng và vaccine đã ngừng kinh doanh không ghi được: 400 BR-KB-04 cùng câu với id lạ; không ghi gì', async () => {
    await client.patch('/api/vaccination-protocols/1', { isActive: false }, await chain());
    expect(message(await failure(vaccinate(2002, FIVE_DOSE_1)))).toBe(WRONG_DOSE);
    await client.patch('/api/products/6', { isActive: false }, await chain());
    expect(message(await failure(vaccinate(2002, FIVE_DOSE_2)))).toBe(WRONG_PRODUCT);
    expect((await options(2002, 2)).data.data.products).toEqual([]);
    expect(await dosesOf(2002)).toEqual([]);
  });

  it('mũi mới hơn cùng loại làm mũi cũ superseded và đổi gợi ý; hết phác đồ thì gợi ý lại MŨI LỚN NHẤT (nhắc lại, không phải null); xóa mũi mới thì mũi cũ hết superseded (BR-TB-03)', async () => {
    const first = (await vaccinate(2002, FIVE_DOSE_1)).data.data.vaccination;
    expect((await options(2002, 2)).data.data).toMatchObject({ suggestedProtocolId: 2, lastDose: { vaccinationId: first.vaccinationId, doseNumber: 1 } });
    const second = (await vaccinate(2002, FIVE_DOSE_2)).data.data.vaccination;
    expect((await dosesOf(2002)).map((d) => [d.doseNumber, d.superseded])).toEqual([[1, true], [2, false]]);
    expect((await options(2002, 2)).data.data).toMatchObject({ suggestedProtocolId: 2, lastDose: { vaccinationId: second.vaccinationId, doseNumber: 2 } });

    expect((await removeDose(2002, second.vaccinationId)).status).toBe(204);
    expect((await dosesOf(2002)).map((d) => [d.doseNumber, d.superseded])).toEqual([[1, false]]);
    expect((await options(2002, 2)).data.data).toMatchObject({ suggestedProtocolId: 2, lastDose: { vaccinationId: first.vaccinationId, doseNumber: 1 } });
  });

  it('phác đồ một mũi (Dại): sau mũi 1 vẫn gợi ý mũi 1 để nhắc lại; phác đồ rỗng (Ho cũi của chó) mới không gợi ý (null)', async () => {
    await vaccinate(2002, RABIES);
    expect((await options(2002, 1)).data.data).toMatchObject({ suggestedProtocolId: 3, lastDose: { doseNumber: 1 }, underMinAge: false });
    expect((await options(2002, 5)).data.data).toMatchObject({ protocol: [], suggestedProtocolId: null, lastDose: null, underMinAge: false });
  });

  it('lượt chỉ tiêm: có ≥ 1 mũi thì hoàn tất được, Order chờ thu có dòng dịch vụ + dòng vaccine; xóa hết mũi thì lại bị chặn (BR-KB-02)', async () => {
    const visit = await startWalkIn(2, 2, 5, vet);
    const dose = (await vaccinate(visit.visitId, RABIES)).data.data.vaccination;
    await removeDose(visit.visitId, dose.vaccinationId);
    expect((await failure(complete(visit.visitId))).status).toBe(400);
    await vaccinate(visit.visitId, RABIES);
    const done = await complete(visit.visitId);
    expect(done.status).toBe(200);
    const order = await orderOf(done.data.data.orderId);
    expect(order.status).toBe('PENDING');
    expect(order.lines.map((l: { lineType: string }) => l.lineType).sort()).toEqual(['SERVICE', 'VACCINE']);
    expect(order.totalAmount).toBe(50_000 + 150_000);
  });

  it('thứ tự lỗi: body 400 (trước cả vai trò) → VET 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → 409 → loại vaccine 404 → mũi / sản phẩm 400', async () => {
    const empty = await failure(vaccinate(2002, {}, admin));
    expect([empty.status, empty.data.errorCode]).toEqual([400, 'VALIDATION_FAILED']);
    for (const text of ['vaccineTypeId: Thiếu loại vaccine', 'protocolId: Thiếu mũi tiêm', 'productId: Thiếu sản phẩm vaccine']) expect(empty.data.message).toContain(text);
    const zero = await failure(vaccinate(2002, { vaccineTypeId: 0, protocolId: 0, productId: 0 }, admin));
    for (const text of ['vaccineTypeId: Mã loại vaccine không hợp lệ', 'protocolId: Mã mũi tiêm không hợp lệ', 'productId: Mã sản phẩm không hợp lệ']) expect(zero.data.message).toContain(text);
    expect((await failure(vaccinate(2002, { ...RABIES, productId: 'x' }, admin))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(vaccinate(2002, { ...RABIES, productId: 0 }, admin))).data.errorCode).toBe('VALIDATION_FAILED');

    for (const who of [desk, caretaker, manager, chain, admin]) expect((await failure(vaccinate(2002, RABIES, who))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(vaccinate(99999, RABIES))).status).toBe(404);
    const otherBranch = direct('POST /api/visits/:visitId/vaccinations', callerOf('VET', 55, 2), { params: { visitId: '2002' }, body: RABIES });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    const grooming = await failure(vaccinate(2103, RABIES));
    expect([grooming.status, message(grooming)]).toEqual([404, 'Không tìm thấy lượt khám / tiêm #2103']);
    expect((await failure(vaccinate(2101, RABIES))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');

    expect(message(await failure(vaccinate(2102, RABIES)))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang IN_PROGRESS');
    expect(message(await failure(vaccinate(61, RABIES)))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS');

    expect(message(await failure(vaccinate(2002, { ...RABIES, vaccineTypeId: 99 })))).toBe('Không tìm thấy loại vaccine #99');
    const unknownDose = await failure(vaccinate(2002, { ...RABIES, protocolId: 99 }));
    expect([unknownDose.status, unknownDose.data.errorCode, message(unknownDose)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', WRONG_DOSE]);
    const unknownProduct = await failure(vaccinate(2002, { ...RABIES, productId: 99 }));
    expect([unknownProduct.status, unknownProduct.data.errorCode, message(unknownProduct)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', WRONG_PRODUCT]);
  });
});

describe('xóa mũi tiêm ghi nhầm (#20, UC49; BR-KB-04, ADR-0075)', () => {
  it('khi lượt còn IN_PROGRESS: 204, hoàn kho vào ĐÚNG lô đã trừ (kể cả lô đã hết hạn lúc xóa), xóa dòng Order vaccine', async () => {
    const dose = (await vaccinate(2002, RABIES)).data.data.vaccination;
    expect((await lotsOf(5)).find((l) => l.lotNumber === 'RB2401')?.quantity).toBe(14); // mũi không mang số lô (D046) nhưng kho biết lô đã trừ
    vi.setSystemTime(new Date('2027-08-01T10:00:00+07:00')); // lô RB2401 hết hạn 10/07/2027: hoàn về lô cũ, không sang lô khác
    const res = await removeDose(2002, dose.vaccinationId);
    expect(res.status).toBe(204);
    expect(res.data).toBe('');
    const lots = await lotsOf(5);
    expect(lots.find((l) => l.lotNumber === 'RB2401')).toMatchObject({ quantity: 15, expired: true });
    expect(lots.find((l) => l.lotNumber === 'RB2309')?.quantity).toBe(2);
    expect((await linesOf(2002)).some((l) => l.lineType === 'VACCINE')).toBe(false);
    expect(await dosesOf(2002)).toEqual([]);
  });

  it('sau khi gán lại lượt (BR-TN-08): người phụ trách MỚI xóa được mũi do VET cũ ghi (204, hoàn kho), VET cũ hết là người phụ trách nên 403; không xét người ghi mũi', async () => {
    const dose = (await vaccinate(2002, FIVE_DOSE_1)).data.data.vaccination;
    expect(dose.recordedBy).toBe(5);
    updateVisitRow(2002, { assigneeId: 7 });
    const recorder = await failure(removeDose(2002, dose.vaccinationId));
    expect([recorder.status, recorder.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    expect(await dosesOf(2002)).toHaveLength(1);

    const successor = direct('DELETE /api/visits/:visitId/vaccinations/:vaccinationId', callerOf('VET', 7, 1), { params: { visitId: '2002', vaccinationId: String(dose.vaccinationId) } });
    expect(successor.status).toBe(204);
    expect(await dosesOf(2002)).toEqual([]);
    expect((await stock(6)).availableQuantity).toBe(4);
    expect((await linesOf(2002)).some((l) => l.lineType === 'VACCINE')).toBe(false);
  });

  it('thứ tự lỗi: path 400 → VET 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → lượt không IN_PROGRESS 409 → mũi 404 (kể cả mũi của lượt khác)', async () => {
    const dose = (await vaccinate(2002, FIVE_DOSE_1)).data.data.vaccination;
    expect((await failure(removeDose('abc', dose.vaccinationId, admin))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(removeDose(2002, 'x', admin))).data.message).toBe("Tham số 'vaccinationId' sai kiểu dữ liệu");
    for (const who of [desk, caretaker, manager, chain, admin]) expect((await failure(removeDose(2002, dose.vaccinationId, who))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(removeDose(99999, dose.vaccinationId))).status).toBe(404);
    const params = { visitId: '2002', vaccinationId: String(dose.vaccinationId) };
    const otherBranch = direct('DELETE /api/visits/:visitId/vaccinations/:vaccinationId', callerOf('VET', 55, 2), { params });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    const grooming = await failure(removeDose(2103, dose.vaccinationId));
    expect([grooming.status, message(grooming)]).toEqual([404, 'Không tìm thấy lượt khám / tiêm #2103']);

    // 403 người phụ trách đứng TRƯỚC 409 và 404 mũi (lượt chưa gán không dò được gì); 409 đứng TRƯỚC 404 mũi (lượt đang chờ / đã xong của chính mình, mũi không có).
    expect((await failure(removeDose(2101, 99999))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    const waiting = await failure(removeDose(2102, 99999));
    expect([waiting.status, waiting.data.errorCode]).toEqual([409, 'INVALID_STATE_TRANSITION']);
    expect(message(await failure(removeDose(61, 99999)))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS');

    // 404 mũi ở cuối: mũi không có, hoặc mũi của lượt khác (lượt đang khám khác của chính mình).
    const unknown = await failure(removeDose(2002, 99999));
    expect([unknown.status, message(unknown)]).toEqual([404, 'Không tìm thấy mũi tiêm #99999']);
    const second = await startWalkIn(2, 1, 5, vet);
    expect((await failure(removeDose(second.visitId, dose.vaccinationId))).status).toBe(404);
    expect(await dosesOf(2002)).toHaveLength(1);

    // Hoàn tất rồi thì không xóa được; sai sót ghi bằng bản bổ sung.
    await putRecord(2002, { diagnosis: 'Khỏe' });
    await complete(2002);
    const done = await failure(removeDose(2002, dose.vaccinationId));
    expect([done.status, done.data.errorCode, message(done)]).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS']);
    expect(await dosesOf(2002)).toHaveLength(1);
  });
});

// ─── Cân nặng khi khám (#14, UC48; BR-KH-04) ───

const weigh = async (visitId: number | string, body: unknown, who: () => Promise<object> = vet) => client.post(`/api/visits/${visitId}/weight`, body, await who());
const petWeights = async (petId: number) => (await client.get(`/api/pets/${petId}/weights`, await vet())).data.data as { weightKg: number; source: string; visitId: number | null; measuredAt: string }[];

describe('ghi cân nặng khi khám (#14; BR-KH-04, BR-KH-05)', () => {
  it('VET được gán ghi cân: 201 "Đã ghi cân nặng", mỗi lần gọi là một bản ghi VET gắn lượt, cân nặng hiện tại của thú đổi theo', async () => {
    const res = await weigh(2002, { weightKg: 18.4 });
    expect(res.status).toBe(201);
    expect(message(res)).toBe('Đã ghi cân nặng');
    expect(Object.keys(res.data.data).sort()).toEqual(['measuredAt', 'weightKg', 'weightRecordId']);
    expect(res.data.data).toMatchObject({ weightKg: 18.4, measuredAt: '2026-10-23T03:40:00Z' });

    const second = await weigh(2002, { weightKg: 18.5 });
    expect(second.data.data.weightRecordId).toBeGreaterThan(res.data.data.weightRecordId);
    const mine = (await petWeights(4)).filter((w) => w.visitId === 2002);
    expect(mine.map((w) => [w.weightKg, w.source]).sort()).toEqual([[18.4, 'VET'], [18.5, 'VET']]);
    expect((await client.get('/api/pets/4', await desk())).data.data.currentWeightKg).toBe(18.5);
  });

  it('làm tròn 2 chữ số thập phân; từ chối ≤ 0, quá 9999.99 kg hoặc làm tròn về 0: 400 BR-KH-04 và không ghi bản ghi nào', async () => {
    expect((await weigh(2002, { weightKg: 18.346 })).data.data.weightKg).toBe(18.35);
    expect((await weigh(2002, { weightKg: '12.5' })).data.data.weightKg).toBe(12.5);
    expect((await weigh(2002, { weightKg: 9999.99 })).data.data.weightKg).toBe(9999.99);
    const before = (await petWeights(4)).length;

    for (const weightKg of [0, -1]) {
      const res = await failure(weigh(2002, { weightKg }));
      expect([res.status, res.data.errorCode, message(res), ruleOf(res)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Cân nặng phải lớn hơn 0 (BR-KH-04)', 'BR-KH-04']);
    }
    for (const weightKg of [0.004, 10000, 9999.995]) {
      expect(message(await failure(weigh(2002, { weightKg })))).toBe('Cân nặng phải trong khoảng 0.01 đến 9999.99 kg (BR-KH-04)');
    }
    expect((await petWeights(4)).length).toBe(before);
  });

  it('thứ tự lỗi: body thiếu / sai kiểu 400 (trước cả vai trò) → VET 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → không IN_PROGRESS 409 → BR-KH-04 400 → thú đã mất 400 BR-KH-05', async () => {
    const empty = await failure(weigh(2002, {}, admin));
    expect([empty.status, empty.data.errorCode, message(empty)]).toEqual([400, 'VALIDATION_FAILED', 'weightKg: Bắt buộc nhập cân nặng']);
    expect((await failure(weigh(2002, { weightKg: 'abc' }, admin))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(weigh(2002, { weightKg: true }, admin))).data.errorCode).toBe('MALFORMED_REQUEST');

    for (const who of [desk, caretaker, manager, chain, admin]) expect((await failure(weigh(2002, { weightKg: 5 }, who))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(weigh(99999, { weightKg: 5 }))).status).toBe(404);
    const otherBranch = direct('POST /api/visits/:visitId/weight', callerOf('VET', 55, 2), { params: { visitId: '2002' }, body: { weightKg: 5 } });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    expect((await failure(weigh(2103, { weightKg: 5 }))).status).toBe(404);
    expect((await failure(weigh(2101, { weightKg: 5 }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect(message(await failure(weigh(2102, { weightKg: 5 })))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang IN_PROGRESS');
    expect(message(await failure(weigh(61, { weightKg: 5 })))).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS');

    // Cân nặng không hợp lệ được báo trước khi xét thú đã mất (PetWeightApiService: normalize rồi mới requireAlive).
    await client.get('/api/pets/4', await desk());
    const pets = JSON.parse(localStorage.getItem('petcare-mock-pets')!);
    pets.pets.find((p: { petId: number }) => p.petId === 4).deceasedOn = TODAY;
    localStorage.setItem('petcare-mock-pets', JSON.stringify(pets));
    expect(message(await failure(weigh(2002, { weightKg: 0 })))).toBe('Cân nặng phải lớn hơn 0 (BR-KH-04)');
    const deceased = await failure(weigh(2002, { weightKg: 5 }));
    expect([deceased.status, message(deceased), ruleOf(deceased)]).toEqual([400, 'Thú cưng đã mất, hồ sơ chỉ đọc (BR-KH-05)', 'BR-KH-05']);
  });
});

// ─── Bản bổ sung bệnh án (#15, UC53; BR-KB-01) ───

const addend = async (visitId: number | string, body: unknown, who: () => Promise<object> = vet) => client.post(`/api/visits/${visitId}/medical-record/addenda`, body, await who());

describe('thêm bản bổ sung bệnh án (#15, UC53; BR-KB-01, ADR-0070)', () => {
  it('mọi VET của chi nhánh ghi được (không cần là người phụ trách) khi lượt đã COMPLETED: 201, bỏ khoảng trắng, isInternal mặc định false, hiện trong bệnh án đã khóa theo thứ tự ghi', async () => {
    updateVisitRow(61, { assigneeId: 7 });
    const res = await addend(61, { content: '  Kết quả máu về sau: bạch cầu tăng  ' });
    expect(res.status).toBe(201);
    expect(message(res)).toBe('Đã thêm bản bổ sung');
    expect(Object.keys(res.data.data).sort()).toEqual(['addendumId', 'content', 'createdAt', 'createdBy', 'createdByName', 'isInternal']);
    expect(res.data.data).toMatchObject({ content: 'Kết quả máu về sau: bạch cầu tăng', isInternal: false, createdBy: 5, createdByName: 'BS. Minh Anh', createdAt: '2026-10-23T03:40:00Z' });

    const internal = await addend(61, { content: 'Ghi chú nội bộ thêm', isInternal: true });
    expect(internal.data.data.isInternal).toBe(true);
    const record = (await getRecord(61)).data.data;
    expect(record.locked).toBe(true);
    expect(record.addenda.map((a: { content: string }) => a.content)).toEqual(['Kết quả xét nghiệm nhanh về sau: FeLV/FIV âm tính', 'Kết quả máu về sau: bạch cầu tăng', 'Ghi chú nội bộ thêm']);
    expect(record.diagnosis).toBe('Viêm đường hô hấp trên');
  });

  it('lượt chỉ tiêm (không có dòng bệnh án) vẫn thêm bản bổ sung được để ghi sai sót mũi tiêm (A17)', async () => {
    const visit = await startWalkIn(2, 2, 5, vet);
    await vaccinate(visit.visitId, RABIES);
    await complete(visit.visitId);
    expect((await addend(visit.visitId, { content: 'Mũi trên ghi nhầm lô, lô đúng là RB2401' })).status).toBe(201);
    const record = (await getRecord(visit.visitId)).data.data;
    expect(record).toMatchObject({ locked: true, diagnosis: null, examination: null });
    expect(record.vaccinations).toHaveLength(1);
    expect(record.addenda).toHaveLength(1);
  });

  it('lượt chưa COMPLETED (đang chờ, đang khám, đã hủy): 409 — sửa trực tiếp bệnh án; không tạo bản bổ sung nào', async () => {
    await client.post('/api/visits/2101/cancel', undefined, await desk());
    for (const [visitId, from] of [[2002, 'IN_PROGRESS'], [2102, 'WAITING'], [2101, 'CANCELLED']] as const) {
      const res = await failure(addend(visitId, { content: 'x' }));
      expect([res.status, res.data.errorCode, message(res)]).toEqual([409, 'INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái lượt tiếp nhận từ ${from} sang COMPLETED`]);
    }
    await putRecord(2002, { diagnosis: 'Khỏe' });
    await complete(2002);
    expect((await getRecord(2002)).data.data.addenda).toEqual([]);
  });

  it('thứ tự lỗi: nội dung trống 400 (trước cả vai trò) → VET 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → 409', async () => {
    for (const body of [{}, { content: '   ' }, { content: null }]) {
      const empty = await failure(addend(61, body, admin));
      expect([empty.status, empty.data.errorCode, message(empty)]).toEqual([400, 'VALIDATION_FAILED', 'content: Bắt buộc nhập nội dung bổ sung']);
    }
    expect((await failure(addend(61, { content: 'x', isInternal: 'không' }, admin))).data.errorCode).toBe('MALFORMED_REQUEST');
    for (const who of [desk, caretaker, manager, chain, admin]) expect((await failure(addend(61, { content: 'x' }, who))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(addend(99999, { content: 'x' }))).status).toBe(404);
    const otherBranch = direct('POST /api/visits/:visitId/medical-record/addenda', callerOf('VET', 55, 2), { params: { visitId: '61' }, body: { content: 'x' } });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    expect((await failure(addend(2103, { content: 'x' }))).status).toBe(404);
    expect((await failure(addend(2102, { content: 'x' }))).status).toBe(409);
  });
});

// ─── Hồ sơ sức khỏe thú cưng (#21, UC25): đọc từ dữ liệu lâm sàng thật của mock ───

const khach = () => as('khach@petcare.vn');
const healthOf = async (petId: number, who: () => Promise<object> = vet) => (await client.get(`/api/pets/${petId}/health-record`, await who())).data.data;

describe('hồ sơ sức khỏe thú cưng phản ánh lượt khám, mũi tiêm, bản bổ sung (visit-v1 #21; BR-KH-07, BR-KB-01, BR-KB-04)', () => {
  it('lượt khám đã hoàn tất hiện trong hồ sơ kèm bệnh án, cùng các mũi tiêm tại hệ thống và ngày tái chủng sắp tới', async () => {
    const dose = (await vaccinate(2002, FIVE_DOSE_1)).data.data.vaccination;
    await putRecord(2002, { examination: 'Sốt nhẹ', diagnosis: 'Viêm da', treatmentPlan: 'Tắm y tế', internalNote: 'Chủ hay quên thuốc', followUpDate: addDays(TODAY, 10) });
    expect((await healthOf(4)).visits.map((v: { visitId: number }) => v.visitId)).not.toContain(2002); // chưa hoàn tất thì chưa vào hồ sơ
    await complete(2002);

    const health = await healthOf(4);
    const entry = health.visits.find((v: { visitId: number }) => v.visitId === 2002);
    expect(entry).toMatchObject({ visitDate: TODAY, serviceGroup: 'MEDICAL', examination: 'Sốt nhẹ', diagnosis: 'Viêm da', treatmentPlan: 'Tắm y tế', internalNote: 'Chủ hay quên thuốc', followUpDate: addDays(TODAY, 10), prescriptions: [], addenda: [] });
    expect(health.vaccinations).toContainEqual(expect.objectContaining({ vaccinationId: dose.vaccinationId, vaccineTypeName: '5 bệnh', doseNumber: 1, superseded: false }));
    expect(health.upcomingVaccinations).toContainEqual({ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', nextDueDate: addDays(TODAY, 21) });
    expect(health.upcomingFollowUpDate).toBe(addDays(TODAY, 10));
  });

  it('mũi tiêm đã bị thay thế bởi mũi mới hơn cùng loại không còn là "sắp tới"; mũi sắp tới là mũi mới nhất', async () => {
    await vaccinate(2002, FIVE_DOSE_1);
    await vaccinate(2002, FIVE_DOSE_2);
    const health = await healthOf(4);
    expect(health.upcomingVaccinations.filter((u: { vaccineTypeId: number }) => u.vaccineTypeId === 2)).toEqual([{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', nextDueDate: addDays(TODAY, 21) }]);
    expect(health.vaccinations.filter((v: { vaccineTypeId: number }) => v.vaccineTypeId === 2).map((v: { doseNumber: number; superseded: boolean }) => [v.doseNumber, v.superseded])).toEqual([[2, false], [1, true]]);
  });

  it('khách (chủ hiện tại) không nhận ghi chú nội bộ và bản bổ sung nội bộ, nhưng thấy bệnh án đã khóa và bản bổ sung công khai', async () => {
    await addend(61, { content: 'Bổ sung cho chủ xem' });
    await addend(61, { content: 'Bổ sung nội bộ', isInternal: true });

    const staffView = (await healthOf(1)).visits.find((v: { visitId: number }) => v.visitId === 61);
    expect(staffView.internalNote).toBe('Chủ lo lắng, nhắc tái khám');
    expect(staffView.addenda.map((a: { content: string }) => a.content)).toContain('Bổ sung nội bộ');

    const ownerView = (await healthOf(1, khach)).visits.find((v: { visitId: number }) => v.visitId === 61);
    expect(ownerView.diagnosis).toBe('Viêm đường hô hấp trên');
    expect(ownerView).not.toHaveProperty('internalNote');
    expect(ownerView.addenda.map((a: { content: string }) => a.content)).toEqual(['Kết quả xét nghiệm nhanh về sau: FeLV/FIV âm tính', 'Bổ sung cho chủ xem']);
  });

  it('lượt Thẩm mỹ đã hoàn tất vào hồ sơ không kèm bệnh án; lượt chưa xong hoặc đã hủy thì không vào', async () => {
    await client.post('/api/visits/2103/call', undefined, await caretaker());
    await complete(2103, caretaker);
    const bim = (await healthOf(2)).visits.find((v: { visitId: number }) => v.visitId === 2103);
    expect(bim).toMatchObject({ serviceGroup: 'GROOMING', examination: null, diagnosis: null, followUpDate: null, prescriptions: [], addenda: [] });
    expect((await healthOf(1)).visits.map((v: { visitId: number }) => v.visitId)).not.toContain(2104); // Mochi đang làm
  });
});

// ─── Kê đơn: thêm dòng (#16) và xóa dòng (#17) (UC48; BR-KB-03, BR-SP-01, BR-BH-02) ───
// Chép từ BE T38 (PrescriptionService, ADR 0076–0078). Thứ tự kiểm #16: body 400 → vai trò 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → không IN_PROGRESS 409 → thuốc 400 BR-KB-03
//  (id lạ cũng 400, BE không có 404 sản phẩm) → tồn 400 BR-KB-03. #17 cùng thứ tự, thay bước thuốc bằng "dòng đơn của lượt này 404" (đứng SAU 409, khác #20 xóa mũi tiêm có 404 mũi sau 409 cùng kiểu).
// BE khớp ASSUMPTION của T46 (docs/superpowers/plans/2026-10-26-t46-fe-prescription-public.md): A2 sản phẩm không phải thuốc kê đơn / ngừng kinh doanh → 400 BR-KB-03 · A3 REDUCE_TO_AVAILABLE tồn 0 → 400
//  BR-KB-03 · A4 gửi resolution khi tồn đã đủ → như kê thường · A6 kê đơn không trừ kho (kho trừ khi PAID, BR-BH-04). Còn chưa đối chiếu BE: A5 cùng thuốc kê hai lần → hai dòng.
// Dữ liệu mẫu: Amoxicillin #3 (thuốc kê đơn, 8.000 đ) chi nhánh 1 tồn 700 (lô 200 + 500); lượt 2002 đang khám (VET #5), Order OPEN một dòng khám.

const prescribe = async (visitId: number | string, body: unknown, who: () => Promise<object> = vet) => client.post(`/api/visits/${visitId}/prescriptions`, body, await who());
const removeRx = async (visitId: number | string, itemId: number | string, who: () => Promise<object> = vet) => client.delete(`/api/visits/${visitId}/prescriptions/${itemId}`, await who());
const AMOX = { productId: 3, quantity: 10, dosageInstructions: 'Ngày 2 lần, mỗi lần 1 viên sau ăn' };
const PRESCRIPTION_KEYS = ['dosageInstructions', 'externalReason', 'isExternalPurchase', 'itemId', 'orderLineId', 'productId', 'productName', 'quantity'];
interface Rx {
  itemId: number;
  productId: number;
  productName: string;
  quantity: number;
  dosageInstructions: string;
  isExternalPurchase: boolean;
  externalReason: string | null;
  orderLineId: number | null;
}
interface OrderLineView {
  lineId: number;
  lineType: string;
  productId: number | null;
  description: string;
  quantity: number;
  unitPrice: number;
}
const rxOf = async (visitId: number) => (await getRecord(visitId)).data.data.prescriptions as Rx[];
const orderLinesOf = async (visitId: number) => (await orderOf((await visitOf(visitId)).orderId)).lines as OrderLineView[];
const drugLinesOf = async (visitId: number) => (await orderLinesOf(visitId)).filter((l) => l.lineType === 'DRUG');
const AMOX_STOCK = 700;
// Ba câu BR-KB-03 của PrescriptionService, nguyên văn BE.
const SHORT_800 = 'Tồn khả dụng tại chi nhánh chỉ còn 700; chọn giảm số lượng hoặc mua ngoài (BR-KB-03)';
const SHORT_0 = 'Tồn khả dụng tại chi nhánh chỉ còn 0; chọn giảm số lượng hoặc mua ngoài (BR-KB-03)';
const EXHAUSTED = 'Thuốc đã hết tồn khả dụng tại chi nhánh, chỉ chọn được mua ngoài (BR-KB-03)';
const NOT_PRESCRIPTION = 'Sản phẩm không phải thuốc kê đơn đang kinh doanh (BR-KB-03)';

/** Thuốc kê đơn mới (chi nhánh 1 chưa có tồn): dựng trong test, KHÔNG đổi dữ liệu mẫu kho / danh mục (nhiều test khác đếm theo dữ liệu mẫu). */
async function newDrug(sku: string, name = 'Thuốc hết tồn') {
  const res = await client.post('/api/products', { categoryId: 2, sku, name, productType: 'DRUG', isPrescription: true, tracksExpiry: true, unit: 'viên', price: 5_000 }, await chain());
  return res.data.data.productId as number;
}

describe('kê đơn một dòng thuốc — đủ tồn (#16, UC48; BR-KB-03, BR-BH-02)', () => {
  it('đủ tồn: 201, dòng đơn thuốc sinh đúng 1 dòng Order DRUG (giá và tên chụp lúc kê), liều dùng bỏ khoảng trắng hai đầu, KHÔNG trừ kho (A6)', async () => {
    const before = (await stock(3)).availableQuantity;
    expect(before).toBe(AMOX_STOCK);
    const res = await prescribe(2002, { ...AMOX, dosageInstructions: `  ${AMOX.dosageInstructions}  ` });

    expect(res.status).toBe(201);
    expect(message(res)).toBe('Đã kê đơn');
    expect(Object.keys(res.data.data).sort()).toEqual(PRESCRIPTION_KEYS);
    expect(res.data.data).toMatchObject({ productId: 3, productName: 'Amoxicillin 250mg', quantity: 10, dosageInstructions: AMOX.dosageInstructions, isExternalPurchase: false, externalReason: null });
    expect(typeof res.data.data.orderLineId).toBe('number');

    const lines = await drugLinesOf(2002);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ lineId: res.data.data.orderLineId, lineType: 'DRUG', productId: 3, description: 'Amoxicillin 250mg', quantity: 10, unitPrice: 8_000, addedBy: 5, canEdit: false, canDelete: false });
    expect(await rxOf(2002)).toEqual([res.data.data]);
    expect((await stock(3)).availableQuantity).toBe(before);
  });

  it('tổng Order tính theo dòng thuốc (SL × giá lúc kê) và quay về khi xóa dòng đơn; hoàn tất lượt thì dòng thuốc vào Order chờ thu (BR-BH-04)', async () => {
    const orderId = (await visitOf(2002)).orderId;
    const base = (await orderOf(orderId)).totalAmount;
    const item = (await prescribe(2002, AMOX)).data.data;
    expect((await orderOf(orderId)).totalAmount).toBe(base + 10 * 8_000);
    await removeRx(2002, item.itemId);
    expect((await orderOf(orderId)).totalAmount).toBe(base);

    await prescribe(2002, { ...AMOX, quantity: 3 });
    await putRecord(2002, { diagnosis: 'Khỏe' });
    await complete(2002);
    const order = await orderOf(orderId);
    expect([order.status, order.totalAmount]).toEqual(['PENDING', base + 3 * 8_000]);
  });

  it('dòng thuốc trong Order không sửa / xóa trực tiếp được ở module bán hàng — chỉ biến mất kèm dòng đơn (BR-BH-02)', async () => {
    const item = (await prescribe(2002, AMOX)).data.data;
    const url = `/api/orders/${(await visitOf(2002)).orderId}/lines/${item.orderLineId}`;
    for (const who of [vet, desk]) {
      expect((await failure(client.delete(url, await who()))).status).toBeGreaterThanOrEqual(400);
      expect((await failure(client.patch(url, { quantity: 1 }, await who()))).status).toBeGreaterThanOrEqual(400);
    }
    expect(await rxOf(2002)).toEqual([item]);
    expect(await drugLinesOf(2002)).toHaveLength(1);
  });

  it('A5: cùng thuốc kê hai lần là hai dòng đơn và hai dòng Order riêng', async () => {
    const first = (await prescribe(2002, AMOX)).data.data;
    const second = (await prescribe(2002, { ...AMOX, quantity: 5 })).data.data;
    expect(second.itemId).not.toBe(first.itemId);
    expect(second.orderLineId).not.toBe(first.orderLineId);
    expect((await rxOf(2002)).map((r) => r.quantity)).toEqual([10, 5]);
    expect((await drugLinesOf(2002)).map((l) => l.quantity)).toEqual([10, 5]);
  });

  it('đúng bằng tồn (700) là đủ tồn; hơn 1 (701) là thiếu tồn — không dính nhau', async () => {
    const exact = await prescribe(2002, { ...AMOX, quantity: AMOX_STOCK });
    expect([exact.status, exact.data.data.quantity, exact.data.data.isExternalPurchase]).toEqual([201, AMOX_STOCK, false]);
    const over = await failure(prescribe(2002, { ...AMOX, quantity: AMOX_STOCK + 1 }));
    expect([over.status, ruleOf(over)]).toEqual([400, 'BR-KB-03']);
  });

  it('A4: gửi shortageResolution khi tồn đã đủ thì xử lý như kê thường (dòng Order, không mua ngoài, giữ số lượng)', async () => {
    for (const shortageResolution of ['REDUCE_TO_AVAILABLE', 'EXTERNAL_PURCHASE']) {
      const res = await prescribe(2002, { ...AMOX, shortageResolution });
      expect(res.status).toBe(201);
      expect(res.data.data).toMatchObject({ quantity: 10, isExternalPurchase: false, externalReason: null });
      expect(res.data.data.orderLineId).not.toBeNull();
    }
    expect(await drugLinesOf(2002)).toHaveLength(2);
  });

  it('shortageResolution vắng hoặc null là như nhau (không có cách xử lý)', async () => {
    expect((await prescribe(2002, { ...AMOX, shortageResolution: null })).status).toBe(201);
    expect(message(await failure(prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: null })))).toBe(SHORT_800);
  });
});

describe('kê đơn khi thiếu tồn (#16, UC48; BR-KB-03)', () => {
  it('chưa chọn cách xử lý: 400 BR-KB-03 kèm số tồn hiện có trong message và KHÔNG ghi gì (không dòng đơn, không dòng Order)', async () => {
    const orderLines = (await orderLinesOf(2002)).length;
    const res = await failure(prescribe(2002, { ...AMOX, quantity: 800 }));

    expect([res.status, res.data.errorCode, message(res), ruleOf(res)]).toEqual([400, 'BUSINESS_RULE_VIOLATION', SHORT_800, 'BR-KB-03']);
    expect(await rxOf(2002)).toEqual([]);
    expect(await orderLinesOf(2002)).toHaveLength(orderLines);
    expect((await stock(3)).availableQuantity).toBe(AMOX_STOCK);
  });

  it('REDUCE_TO_AVAILABLE: giảm xuống đúng tồn khả dụng, vẫn sinh dòng Order với số lượng đã giảm', async () => {
    const res = await prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: 'REDUCE_TO_AVAILABLE' });
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ quantity: AMOX_STOCK, isExternalPurchase: false, externalReason: null });
    const lines = await drugLinesOf(2002);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ lineId: res.data.data.orderLineId, quantity: AMOX_STOCK });
    expect((await stock(3)).availableQuantity).toBe(AMOX_STOCK);
  });

  it('REDUCE_TO_AVAILABLE khi tồn bằng 0 (A3): 400 BR-KB-03 bảo chọn mua ngoài, không ghi gì', async () => {
    const productId = await newDrug('RX-HET-1');
    const res = await failure(prescribe(2002, { productId, quantity: 3, dosageInstructions: 'x', shortageResolution: 'REDUCE_TO_AVAILABLE' }));
    expect([res.status, message(res), ruleOf(res)]).toEqual([400, EXHAUSTED, 'BR-KB-03']);
    expect(await rxOf(2002)).toEqual([]);
    expect(await drugLinesOf(2002)).toEqual([]);
  });

  it('thuốc chưa từng nhập kho (tồn 0) mà chưa chọn cách xử lý: báo thiếu tồn còn 0', async () => {
    const productId = await newDrug('RX-HET-2');
    const res = await failure(prescribe(2002, { productId, quantity: 3, dosageInstructions: 'x' }));
    expect([res.status, message(res)]).toEqual([400, SHORT_0]);
  });

  it('EXTERNAL_PURCHASE (A3): giữ nguyên số lượng, đánh dấu mua ngoài (OUT_OF_STOCK_AT_PRESCRIBE), không có dòng Order; dòng vẫn nằm trong đơn của bệnh án', async () => {
    const orderLines = (await orderLinesOf(2002)).length;
    const res = await prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: 'EXTERNAL_PURCHASE' });

    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ productId: 3, quantity: 800, isExternalPurchase: true, externalReason: 'OUT_OF_STOCK_AT_PRESCRIBE', orderLineId: null });
    expect(await orderLinesOf(2002)).toHaveLength(orderLines);
    expect(await drugLinesOf(2002)).toEqual([]);
    expect(await rxOf(2002)).toEqual([res.data.data]);
    expect((await stock(3)).availableQuantity).toBe(AMOX_STOCK);
  });

  it('EXTERNAL_PURCHASE cũng dùng được cho thuốc tồn 0', async () => {
    const productId = await newDrug('RX-HET-3');
    const res = await prescribe(2002, { productId, quantity: 3, dosageInstructions: 'x', shortageResolution: 'EXTERNAL_PURCHASE' });
    expect(res.data.data).toMatchObject({ quantity: 3, isExternalPurchase: true, orderLineId: null });
  });

  it('bất biến isExternalPurchase === (orderLineId == null) với mọi dòng đơn, cả dòng thường lẫn mua ngoài lẫn dòng mẫu', async () => {
    await prescribe(2002, AMOX);
    await prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: 'EXTERNAL_PURCHASE' });
    await prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: 'REDUCE_TO_AVAILABLE' });
    for (const visitId of [2002, 61]) {
      for (const item of await rxOf(visitId)) expect(item.isExternalPurchase).toBe(item.orderLineId === null);
    }
  });
});

describe('kê đơn: sản phẩm không kê được (#16; BR-KB-03, BR-SP-01)', () => {
  it('không phải thuốc kê đơn (hàng #1, Vitamin #4), thuốc đã ngừng kinh doanh hoặc id không tồn tại: CÙNG MỘT câu 400 BR-KB-03 của BE (không có 404), không ghi gì', async () => {
    const stopped = await newDrug('RX-NGUNG', 'Thuốc ngừng bán');
    await client.patch(`/api/products/${stopped}`, { isActive: false }, await chain());

    for (const productId of [1, 4, stopped, 9999]) {
      const res = await failure(prescribe(2002, { ...AMOX, productId }));
      expect([res.status, res.data.errorCode, message(res), ruleOf(res)], `thuốc #${productId}`).toEqual([400, 'BUSINESS_RULE_VIOLATION', NOT_PRESCRIPTION, 'BR-KB-03']);
    }
    expect(await rxOf(2002)).toEqual([]);
    expect(await drugLinesOf(2002)).toEqual([]);
  });

  it('ba ca BR-KB-03 cùng mã khác câu: chỉ ca thiếu tồn có "chỉ còn N" (giao diện dựa vào đó để hiện khung chọn giảm / mua ngoài)', async () => {
    const empty = await newDrug('RX-HET-4');
    const notDrug = message(await failure(prescribe(2002, { ...AMOX, productId: 1 })));
    const shortage = message(await failure(prescribe(2002, { ...AMOX, quantity: 800 })));
    const exhausted = message(await failure(prescribe(2002, { productId: empty, quantity: 3, dosageInstructions: 'x', shortageResolution: 'REDUCE_TO_AVAILABLE' })));
    expect([notDrug, shortage, exhausted]).toEqual([NOT_PRESCRIPTION, SHORT_800, EXHAUSTED]);
    expect([notDrug, shortage, exhausted].map((text) => /chỉ còn \d+/.test(text))).toEqual([false, true, false]);
  });
});

describe('kê đơn: kiểm dữ liệu vào (#16)', () => {
  it.each<[string, object, string]>([
    ['thiếu productId', { quantity: 1, dosageInstructions: 'x' }, 'productId: Thiếu thuốc cần kê'],
    ['productId bằng 0', { ...AMOX, productId: 0 }, 'productId: Mã thuốc không hợp lệ'],
    ['productId âm', { ...AMOX, productId: -3 }, 'productId: Mã thuốc không hợp lệ'],
    ['thiếu quantity', { productId: 3, dosageInstructions: 'x' }, 'quantity: Thiếu số lượng'],
    ['quantity bằng 0', { ...AMOX, quantity: 0 }, 'quantity: Số lượng phải lớn hơn 0'],
    ['quantity âm', { ...AMOX, quantity: -2 }, 'quantity: Số lượng phải lớn hơn 0'],
    ['thiếu liều dùng', { productId: 3, quantity: 1 }, 'dosageInstructions: Bắt buộc nhập liều dùng'],
    ['liều dùng rỗng', { ...AMOX, dosageInstructions: '' }, 'dosageInstructions: Bắt buộc nhập liều dùng'],
    ['liều dùng chỉ khoảng trắng', { ...AMOX, dosageInstructions: '   ' }, 'dosageInstructions: Bắt buộc nhập liều dùng'],
    ['liều dùng 501 ký tự', { ...AMOX, dosageInstructions: 'a'.repeat(501) }, 'dosageInstructions: Liều dùng tối đa 500 ký tự'],
  ])('%s: 400 VALIDATION_FAILED và không ghi gì', async (_name, body, expected) => {
    const res = await failure(prescribe(2002, body));
    expect([res.status, res.data.errorCode, message(res)]).toEqual([400, 'VALIDATION_FAILED', expected]);
    expect(await rxOf(2002)).toEqual([]);
  });

  it('liều dùng đúng 500 ký tự (sau khi bỏ khoảng trắng hai đầu) còn hợp lệ', async () => {
    const res = await prescribe(2002, { ...AMOX, dosageInstructions: ` ${'a'.repeat(500)} ` });
    expect([res.status, res.data.data.dosageInstructions.length]).toEqual([201, 500]);
  });

  it.each<[string, unknown]>([
    ['body không phải đối tượng', []],
    ['productId không nguyên', { ...AMOX, productId: 3.5 }],
    ['quantity không nguyên', { ...AMOX, quantity: 1.5 }],
    ['quantity là chuỗi chữ', { ...AMOX, quantity: 'nhiều' }],
    ['liều dùng là mảng', { ...AMOX, dosageInstructions: ['x'] }],
    ['shortageResolution ngoài enum', { ...AMOX, shortageResolution: 'GIAM_BOT' }],
    ['shortageResolution sai kiểu', { ...AMOX, shortageResolution: 1 }],
  ])('%s: 400 MALFORMED_REQUEST', async (_name, body) => {
    const res = await failure(prescribe(2002, body));
    expect([res.status, res.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    expect(await rxOf(2002)).toEqual([]);
  });
});

describe('kê đơn: thứ tự lỗi (#16, A1)', () => {
  it('path / body 400 (trước cả vai trò, kể cả ADMIN) → vai trò VET 403', async () => {
    const path = await failure(prescribe('abc', AMOX, admin));
    expect([path.status, path.data.errorCode, message(path)]).toEqual([400, 'MALFORMED_REQUEST', "Tham số 'visitId' sai kiểu dữ liệu"]);
    expect((await failure(prescribe(2002, [], admin))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(prescribe(2002, { ...AMOX, quantity: 0 }, admin))).data.errorCode).toBe('VALIDATION_FAILED');
    for (const who of [desk, caretaker, manager, chain, admin]) {
      const denied = await failure(prescribe(2002, AMOX, who));
      expect([denied.status, denied.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
  });

  it('lượt 404 → chi nhánh khác 403 → Thẩm mỹ 404 → người phụ trách 403 (chưa gán, người khác) → không IN_PROGRESS 409 → thuốc 400 BR-KB-03 → tồn 400 BR-KB-03', async () => {
    const missing = await failure(prescribe(99999, AMOX));
    expect([missing.status, message(missing)]).toEqual([404, 'Không tìm thấy lượt tiếp nhận #99999']);
    const otherBranch = direct('POST /api/visits/:visitId/prescriptions', callerOf('VET', 55, 2), { params: { visitId: '2002' }, body: AMOX });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    const grooming = await failure(prescribe(2103, AMOX));
    expect([grooming.status, message(grooming)]).toEqual([404, 'Không tìm thấy lượt khám / tiêm #2103']);

    // 403 người phụ trách đứng TRƯỚC 409 (người không phải chủ lượt không dò được trạng thái) và trước kiểm thuốc.
    expect((await failure(prescribe(2101, { ...AMOX, productId: 9999 }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    updateVisitRow(2002, { assigneeId: 7 });
    expect((await failure(prescribe(2002, AMOX))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    updateVisitRow(2002, { assigneeId: 5 });

    // 409 đứng TRƯỚC kiểm thuốc và tồn.
    const waiting = await failure(prescribe(2102, { ...AMOX, productId: 9999 }));
    expect([waiting.status, waiting.data.errorCode, message(waiting)]).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang IN_PROGRESS']);
    const done = await failure(prescribe(61, { ...AMOX, productId: 1 }));
    expect([done.status, message(done)]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS']);

    // Kiểm thuốc đứng TRƯỚC kiểm tồn: số lượng 800 vượt tồn nhưng thuốc lạ / không kê đơn báo "không phải thuốc kê đơn", không báo thiếu tồn, và không còn 404 sản phẩm.
    const unknownDrug = await failure(prescribe(2002, { ...AMOX, productId: 9999, quantity: 800 }));
    expect([unknownDrug.status, message(unknownDrug)]).toEqual([400, NOT_PRESCRIPTION]);
    expect(message(await failure(prescribe(2002, { ...AMOX, productId: 1, quantity: 800 })))).toBe(NOT_PRESCRIPTION);
  });
});

describe('xóa dòng đơn thuốc (#17, UC48; BR-KB-03, BR-BH-02)', () => {
  it('xóa dòng đơn đã sinh dòng Order: 204, dòng đơn và dòng Order DRUG cùng biến mất, các dòng khác của Order giữ nguyên, kho không đổi', async () => {
    const kept = (await prescribe(2002, { ...AMOX, quantity: 3 })).data.data;
    const removed = (await prescribe(2002, AMOX)).data.data;
    const linesBefore = await orderLinesOf(2002);

    const res = await removeRx(2002, removed.itemId);
    expect(res.status).toBe(204);
    expect(await rxOf(2002)).toEqual([kept]);
    expect((await orderLinesOf(2002)).map((l) => l.lineId)).toEqual(linesBefore.map((l) => l.lineId).filter((id) => id !== removed.orderLineId));
    expect((await drugLinesOf(2002)).map((l) => l.lineId)).toEqual([kept.orderLineId]);
    expect((await stock(3)).availableQuantity).toBe(AMOX_STOCK);
  });

  it('xóa dòng mua ngoài: 204, không đụng Order (không có dòng Order để xóa)', async () => {
    const orderBefore = await orderLinesOf(2002);
    const external = (await prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: 'EXTERNAL_PURCHASE' })).data.data;
    expect((await removeRx(2002, external.itemId)).status).toBe(204);
    expect(await rxOf(2002)).toEqual([]);
    expect(await orderLinesOf(2002)).toEqual(orderBefore);
  });

  it('dòng đơn của lượt khác hoặc không có: 404 và không xóa gì', async () => {
    const mine = (await prescribe(2002, AMOX)).data.data;
    const other = await startWalkIn(2, 1, 5, vet);
    for (const [visitId, itemId] of [[other.visitId, mine.itemId], [2002, 99999]] as const) {
      const res = await failure(removeRx(visitId, itemId));
      expect([res.status, res.data.errorCode, message(res)]).toEqual([404, 'RESOURCE_NOT_FOUND', `Không tìm thấy dòng đơn thuốc #${itemId}`]);
    }
    expect(await rxOf(2002)).toEqual([mine]);
    expect(await drugLinesOf(2002)).toHaveLength(1);
  });

  it('thứ tự lỗi: path 400 (trước cả vai trò) → VET 403 → lượt 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → không IN_PROGRESS 409 → dòng đơn 404', async () => {
    const mine = (await prescribe(2002, AMOX)).data.data;
    expect((await failure(removeRx('abc', 1, admin))).data.message).toBe("Tham số 'visitId' sai kiểu dữ liệu");
    expect((await failure(removeRx(2002, 'xyz', admin))).data.message).toBe("Tham số 'itemId' sai kiểu dữ liệu");
    for (const who of [desk, caretaker, manager, chain, admin]) expect((await failure(removeRx(2002, mine.itemId, who))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(removeRx(99999, 1))).status).toBe(404);
    const otherBranch = direct('DELETE /api/visits/:visitId/prescriptions/:itemId', callerOf('VET', 55, 2), { params: { visitId: '2002', itemId: String(mine.itemId) } });
    expect([otherBranch.status, (otherBranch.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    const grooming = await failure(removeRx(2103, 1));
    expect([grooming.status, message(grooming)]).toEqual([404, 'Không tìm thấy lượt khám / tiêm #2103']);

    expect((await failure(removeRx(2101, 99999))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    updateVisitRow(2002, { assigneeId: 7 });
    expect((await failure(removeRx(2002, mine.itemId))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    updateVisitRow(2002, { assigneeId: 5 });

    // 409 đứng TRƯỚC 404 dòng đơn: dòng đơn không có trên lượt đã hoàn tất vẫn báo 409.
    const waiting = await failure(removeRx(2102, 99999));
    expect([waiting.status, waiting.data.errorCode]).toEqual([409, 'INVALID_STATE_TRANSITION']);
    const done = await failure(removeRx(61, 99999));
    expect([done.status, message(done)]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS']);
    expect((await failure(removeRx(2002, 99999))).status).toBe(404);
    expect(await rxOf(2002)).toEqual([mine]);
  });
});

describe('đơn thuốc trong bệnh án và hồ sơ sức khỏe (#12, #21; BR-KB-01)', () => {
  it('dữ liệu mẫu: lượt 61 đã khóa có dòng đơn Amoxicillin ×10 gắn dòng Order #22 của Order #2 — khớp bất biến isExternalPurchase === (orderLineId == null)', async () => {
    const record = (await getRecord(61)).data.data;
    expect(record.locked).toBe(true);
    expect(record.prescriptions).toHaveLength(1);
    expect(record.prescriptions[0]).toMatchObject({ productId: 3, productName: 'Amoxicillin 250mg', quantity: 10, isExternalPurchase: false, externalReason: null, orderLineId: 22 });
    expect(record.prescriptions[0].dosageInstructions).toBeTruthy();
    expect(Object.keys(record.prescriptions[0]).sort()).toEqual(PRESCRIPTION_KEYS);
    const line = (await orderOf(2)).lines.find((l: OrderLineView) => l.lineId === 22);
    expect(line).toMatchObject({ lineType: 'DRUG', productId: 3, quantity: 10 });
  });

  it('sau khi hoàn tất (#10): kê thêm và xóa đều 409, bệnh án đã khóa vẫn liệt kê đơn, dòng Order DRUG nằm trong Order chờ thu', async () => {
    const item = (await prescribe(2002, AMOX)).data.data;
    await putRecord(2002, { diagnosis: 'Khỏe' });
    expect((await complete(2002)).status).toBe(200);

    for (const request of [prescribe(2002, AMOX), removeRx(2002, item.itemId)]) {
      const res = await failure(request);
      expect([res.status, message(res)]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS']);
    }
    const record = (await getRecord(2002)).data.data;
    expect(record).toMatchObject({ locked: true, prescriptions: [item] });
    expect(await drugLinesOf(2002)).toHaveLength(1);
  });

  it('hồ sơ sức khỏe của thú: lượt đã hoàn tất kèm đơn thuốc đã kê (cả dòng mua ngoài); lượt chưa hoàn tất thì chưa vào hồ sơ', async () => {
    const normal = (await prescribe(2002, AMOX)).data.data;
    const external = (await prescribe(2002, { ...AMOX, quantity: 800, shortageResolution: 'EXTERNAL_PURCHASE' })).data.data;
    expect((await healthOf(4)).visits.map((v: { visitId: number }) => v.visitId)).not.toContain(2002);
    await putRecord(2002, { diagnosis: 'Viêm da' });
    await complete(2002);

    const entry = (await healthOf(4)).visits.find((v: { visitId: number }) => v.visitId === 2002);
    expect(entry.prescriptions).toEqual([normal, external]);
    const seeded = (await healthOf(1)).visits.find((v: { visitId: number }) => v.visitId === 61);
    expect(seeded.prescriptions).toEqual([expect.objectContaining({ productId: 3, quantity: 10, orderLineId: 22 })]);
    expect((await healthOf(1, khach)).visits.find((v: { visitId: number }) => v.visitId === 61).prescriptions).toHaveLength(1);
  });

  it('trạng thái cũ trong localStorage chưa có mảng prescriptions (nạp từ bản trước T46): đọc bệnh án và kê đơn vẫn chạy', async () => {
    localStorage.setItem('petcare-mock-clinical', JSON.stringify({ seq: 1, records: [], addenda: [], vaccinations: [] }));
    expect((await getRecord(2002)).data.data.prescriptions).toEqual([]);
    expect((await prescribe(2002, AMOX)).status).toBe(201);
    expect(await rxOf(2002)).toHaveLength(1);
    expect((await healthOf(4)).visits).toBeDefined();
  });
});
