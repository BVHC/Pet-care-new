import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, pinClock } from '../../../test/boarding-harness';
import type { PageResponse } from '../../types';
import type { AppNotification } from '../../types/care';
import type { Complaint, ComplaintSummary } from '../../types/complaint';
import { ruleIdOf } from '../api-error';
import { registerComplaintSources } from './complaint.mock';
import { allOrderRows } from './sales.mock';
import { visitRowOf } from './visit.mock';
import { installMockApi } from './mock-api';

// Khiếu nại (complaint-v1 #1–12) — BE tầng 3 chưa cài, mock theo ASSUMPTION C1–C9 ở đầu complaint.mock.ts. Đồng hồ ghim ở thứ Sáu 23/10/2026 09:00 giờ Việt Nam.
// Dữ liệu mẫu (lúc nạp đầu, giờ tương đối): khách 1 (khach@petcare.vn) có #1 OPEN (3 giờ trước), #2 IN_PROGRESS (quản lý 3 nhận), #3 RESOLVED (48 giờ trước, mở lại được), #4 CLOSED do khách
// (giải quyết 12 ngày trước), #5 CLOSED do rút, #6 CLOSED tự đóng, #7 OPEN đã leo thang, #8 RESOLVED đã mở lại 2 lần, #9 OPEN ở chi nhánh 2, #11 OPEN không chi nhánh (OTHER);
// khách 2 (khachmoi@petcare.vn) có #10 OPEN do lễ tân 4 tạo thay và #12 IN_PROGRESS đã leo thang (quản lý 3). Mọi khiếu nại còn lại thuộc chi nhánh 1.

const EMAIL = { ...ACCOUNTS, doctor: 'doctor@store1.vn', newstaff: 'newstaff@store1.vn' } as const;
type Who = keyof typeof EMAIL;
type Page<T> = PageResponse<T>;
const CONTENT = 'Nhân viên tiếp đón chưa niềm nở khi tôi đến lấy thuốc.';
const DAY = 24 * 3_600_000;

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = axios.create();
  installMockApi(client);
});
afterEach(() => vi.useRealTimers());

const send = async (who: Who, method: string, url: string, body?: unknown, params?: object) =>
  client.request({ method, url, data: body, params, ...(await authAs(client, EMAIL[who])) });
const get = async <T>(who: Who, url: string, params?: object) => (await send(who, 'get', url, undefined, params)).data.data as T;
const post = async <T>(who: Who, url: string, body?: unknown) => (await send(who, 'post', url, body)).data.data as T;
const rejected = (who: Who, method: string, url: string, body?: unknown, params?: object) => failure(send(who, method, url, body, params));
const statusOf = async (request: Promise<AxiosResponse>) => (await request.catch((e) => e.response as AxiosResponse)).status;

function expectFail(res: AxiosResponse, status: number, errorCode: string, message?: string | RegExp) {
  expect(res.status).toBe(status);
  expect(res.data).toMatchObject({ success: false, errorCode, statusCode: status });
  expect(Object.keys(res.data).sort()).toEqual(['errorCode', 'message', 'statusCode', 'success', 'timestamp', 'traceId']);
  if (typeof message === 'string') expect(res.data.message).toBe(message);
  else if (message) expect(res.data.message).toMatch(message);
}
const ruleFailure = (res: AxiosResponse, ruleId: string, message?: string | RegExp) => {
  expectFail(res, 400, 'BUSINESS_RULE_VIOLATION', message);
  expect(ruleIdOf(res.data.message)).toBe(ruleId);
};

const notices = async (who: Who, type: string) =>
  (await get<Page<AppNotification>>(who, '/api/me/notifications', { size: 100 })).content.filter((n) => n.type === type);
const mineList = async (params: object = {}, who: Who = 'customer') => (await get<Page<ComplaintSummary>>(who, '/api/me/complaints', params)).content;
const listIds = async (who: Who, params: object = {}) => (await get<Page<ComplaintSummary>>(who, '/api/complaints', { size: 100, ...params })).content.map((c) => c.complaintId);
const mineDetail = (id: number, who: Who = 'customer') => get<Complaint>(who, `/api/me/complaints/${id}`);
const draft = (over: Record<string, unknown> = {}) => ({ topic: 'SERVICE_QUALITY', branchId: 1, content: CONTENT, ...over });
const createMine = (over: Record<string, unknown> = {}, who: Who = 'customer') => post<Complaint>(who, '/api/me/complaints', draft(over));

// ─── Khách: gửi, xem, trao đổi, đóng, mở lại (#1–6) ───

describe('gửi khiếu nại (#1, BR-DG-05)', () => {
  it('201: OPEN, chưa có người xử lý, một dòng STATUS của khách; xuất hiện đầu danh sách của khách; mã KN-yyMMdd-xxxx', async () => {
    const res = await send('customer', 'post', '/api/me/complaints', draft({ topic: 'GROOMING', content: `  ${CONTENT}  ` }));
    expect(res.status).toBe(201);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({
      customerId: 1, customerName: 'Nguyễn Thu Trang', branchId: 1, topic: 'GROOMING', relatedType: null, relatedId: null, content: CONTENT, status: 'OPEN',
      createdByStaff: false, handlerName: null, resolution: null, resolvedAt: null, closedAt: null, closedReason: null, reopenCount: 0, escalatedAt: null,
      createdAt: '2026-10-23T02:00:00.000Z', lastActivityAt: '2026-10-23T02:00:00.000Z',
    });
    expect(c.code).toMatch(/^KN-261023-\d{4}$/);
    expect(c.messages).toHaveLength(1);
    expect(c.messages[0]).toMatchObject({ authorType: 'CUSTOMER', authorName: 'Nguyễn Thu Trang', kind: 'STATUS', body: 'Khách đã gửi khiếu nại.' });
    const [first] = await mineList();
    expect(first).toMatchObject({ complaintId: c.complaintId, code: c.code, status: 'OPEN', escalated: false });
    const codes = (await mineList()).map((x) => x.code);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('quản lý ACTIVE của chi nhánh nhận thông báo COMPLAINT_CREATED; chi nhánh khác thì không; không chi nhánh → SUPER_MANAGER', async () => {
    const c = await createMine({ topic: 'PURCHASE' });
    const got = await notices('manager', 'COMPLAINT_CREATED');
    expect(got).toHaveLength(1);
    expect(got[0]).toMatchObject({ title: `Khiếu nại mới ${c.code}`, linkUrl: null, readAt: null });
    expect(got[0].body).toBe(`Khách Nguyễn Thu Trang vừa gửi khiếu nại ${c.code} về mua hàng. Vui lòng nhận xử lý.`);
    expect(await notices('manager2', 'COMPLAINT_CREATED')).toHaveLength(0);
    expect(await notices('chain', 'COMPLAINT_CREATED')).toHaveLength(0);
    const other = await createMine({ topic: 'OTHER', branchId: null });
    expect(other.branchId).toBeNull();
    expect(await notices('chain', 'COMPLAINT_CREATED')).toHaveLength(1);
    expect(await notices('manager', 'COMPLAINT_CREATED')).toHaveLength(1);
  });

  it('nội dung (đã cắt khoảng trắng) < 10 hoặc > 2 000 ký tự → 400 BR-DG-05; đúng 10 và đúng 2 000 thì được', async () => {
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ content: '123456789' })), 'BR-DG-05', /từ 10 đến 2000 ký tự/);
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ content: `   ${'x'.repeat(9)}   ` })), 'BR-DG-05');
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ content: 'x'.repeat(2001) })), 'BR-DG-05');
    expect((await send('customer', 'post', '/api/me/complaints', draft({ content: 'x'.repeat(10) }))).status).toBe(201);
    expect((await send('customer', 'post', '/api/me/complaints', draft({ content: 'x'.repeat(2000) }))).status).toBe(201);
  });

  it('thiếu chi nhánh → 400 BR-DG-05 trừ chủ đề OTHER; chi nhánh không có → 404; 404 đến trước các guard BR-DG-05', async () => {
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ branchId: undefined })), 'BR-DG-05', /chọn chi nhánh/);
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ branchId: null, topic: 'STAFF' })), 'BR-DG-05');
    expect((await createMine({ topic: 'OTHER', branchId: undefined })).branchId).toBeNull();
    expect((await createMine({ topic: 'OTHER', branchId: 2 })).branchId).toBe(2);
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ branchId: 99 })), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy chi nhánh #99');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ branchId: 99, content: 'ngắn' })), 404, 'RESOURCE_NOT_FOUND');
    // Nội dung ngắn được báo trước thiếu chi nhánh (thứ tự của yaml).
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ branchId: undefined, content: 'ngắn' })), 'BR-DG-05', /từ 10 đến 2000/);
  });

  it('đối tượng liên quan: đơn / lượt khám / đơn online / đặt chỗ lưu trú phải thuộc khách (400 BR-DG-05), không có thì 404', async () => {
    const orders = allOrderRows();
    const own = orders.find((o) => o.customerId === 1);
    const foreign = orders.find((o) => o.customerId !== 1);
    expect(own && foreign).toBeTruthy();
    const c = await createMine({ relatedType: 'ORDER', relatedId: own!.orderId });
    expect(c).toMatchObject({ relatedType: 'ORDER', relatedId: own!.orderId });
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'ORDER', relatedId: foreign!.orderId })), 'BR-DG-05', /không thuộc khách/);
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'ORDER', relatedId: 987654 })), 404, 'RESOURCE_NOT_FOUND', /đối tượng liên quan #987654/);

    const visits = Array.from({ length: 200 }, (_, i) => visitRowOf(i + 1)).filter((v) => v !== undefined);
    const ownVisit = visits.find((v) => v!.customerId === 1);
    const foreignVisit = visits.find((v) => v!.customerId !== 1);
    expect(ownVisit && foreignVisit).toBeTruthy();
    expect((await createMine({ relatedType: 'VISIT', relatedId: ownVisit!.visitId })).relatedType).toBe('VISIT');
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'VISIT', relatedId: foreignVisit!.visitId })), 'BR-DG-05');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'VISIT', relatedId: 987654 })), 404, 'RESOURCE_NOT_FOUND');

    // Đơn online và đặt chỗ lưu trú: nguồn mặc định (5001 → khách 1, 5002 → khách 2; đặt chỗ 5 → khách 1, 2 → khách 9002).
    expect((await createMine({ topic: 'PURCHASE', relatedType: 'ONLINE_ORDER', relatedId: 5001 })).relatedId).toBe(5001);
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'ONLINE_ORDER', relatedId: 5002 })), 'BR-DG-05');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'ONLINE_ORDER', relatedId: 1 })), 404, 'RESOURCE_NOT_FOUND');
    expect((await createMine({ topic: 'BOARDING', relatedType: 'BOARDING', relatedId: 5 })).relatedType).toBe('BOARDING');
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'BOARDING', relatedId: 2 })), 'BR-DG-05');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'BOARDING', relatedId: 777 })), 404, 'RESOURCE_NOT_FOUND');
  });

  it('registerComplaintSources thay nguồn tra chủ sở hữu (luồng chính nối với mock đơn online / lưu trú)', async () => {
    registerComplaintSources({ onlineOrderOwner: (id) => (id === 321 ? 1 : undefined), boardingOwner: (id) => (id === 654 ? 2 : undefined) });
    try {
      expect((await createMine({ relatedType: 'ONLINE_ORDER', relatedId: 321 })).relatedId).toBe(321);
      expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'ONLINE_ORDER', relatedId: 5001 })), 404, 'RESOURCE_NOT_FOUND');
      ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'BOARDING', relatedId: 654 })), 'BR-DG-05');
    } finally {
      registerComplaintSources({ onlineOrderOwner: (id) => ({ 5001: 1, 5002: 2 })[id as 5001 | 5002], boardingOwner: (id) => ({ 4: 1, 5: 1, 6: 1, 2: 9002 })[id as 2 | 4 | 5 | 6] });
    }
  });

  it('tối đa 10 khiếu nại OPEN / IN_PROGRESS cùng lúc: khiếu nại đã đóng không tính, rút bớt thì gửi tiếp được', async () => {
    for (let i = 0; i < 5; i++) await createMine({ topic: 'OTHER', branchId: null }); // 5 mẫu + 5 = 10
    expect((await mineList({ size: 100 })).filter((c) => c.status === 'OPEN' || c.status === 'IN_PROGRESS')).toHaveLength(10);
    ruleFailure(await rejected('customer', 'post', '/api/me/complaints', draft()), 'BR-DG-05', /tối đa 10 khiếu nại đang mở/);
    // Khách 2 có hạn mức riêng.
    expect((await send('pendingCustomer', 'post', '/api/me/complaints', draft())).status).toBe(201);
    await post('customer', '/api/me/complaints/1/close');
    expect((await send('customer', 'post', '/api/me/complaints', draft())).status).toBe(201);
  });

  it('VALIDATION_FAILED: thiếu chủ đề / nội dung trống, relatedType và relatedId lệch nhau; MALFORMED_REQUEST: chủ đề ngoài enum, kiểu sai (đến trước vai trò)', async () => {
    expectFail(await rejected('customer', 'post', '/api/me/complaints', {}), 400, 'VALIDATION_FAILED', /topic: .*content: /);
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ content: '    ' })), 400, 'VALIDATION_FAILED', /^content: /);
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'ORDER' })), 400, 'VALIDATION_FAILED', /^relatedId: /);
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedId: 5 })), 400, 'VALIDATION_FAILED', /^relatedId: /);
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ topic: 'FOO' })), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ relatedType: 'TICKET', relatedId: 1 })), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ branchId: 'một' })), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/me/complaints', draft({ content: 123 })), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/me/complaints'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'post', '/api/me/complaints', draft({ topic: 'FOO' })), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'post', '/api/me/complaints', {}), 403, 'ACCESS_DENIED');
  });
});


describe('khiếu nại của tôi (#2, #3, BR-DG-06)', () => {
  it('chỉ khiếu nại của mình, mới nhất trước; summary đủ trường; lọc status; phân trang; khách khác thấy của họ', async () => {
    expect((await mineList()).map((c) => c.complaintId)).toEqual([1, 11, 9, 7, 2, 3, 8, 5, 4, 6]);
    const [first] = await mineList();
    expect(Object.keys(first).sort()).toEqual(['branchId', 'code', 'complaintId', 'createdAt', 'escalated', 'lastActivityAt', 'status', 'topic']);
    expect(first).toMatchObject({ status: 'OPEN', topic: 'GROOMING', branchId: 1, escalated: false });
    expect((await mineList({ size: 100 })).find((c) => c.complaintId === 7)?.escalated).toBe(true);
    expect((await mineList({ status: 'CLOSED' })).map((c) => c.complaintId)).toEqual([5, 4, 6]);
    expect((await mineList({ status: 'RESOLVED' })).map((c) => c.complaintId)).toEqual([3, 8]);
    const page = await get<Page<ComplaintSummary>>('customer', '/api/me/complaints', { page: 1, size: 3 });
    expect(page).toMatchObject({ page: 1, size: 3, totalElements: 10, totalPages: 4 });
    expect(page.content.map((c) => c.complaintId)).toEqual([7, 2, 3]);
    expect(await get<Page<ComplaintSummary>>('customer', '/api/me/complaints', { size: 500, page: -2 })).toMatchObject({ page: 0, size: 100 });
    expect((await mineList({}, 'pendingCustomer')).map((c) => c.complaintId)).toEqual([10, 12]);
    expect(await mineList({ status: 'RESOLVED' }, 'pendingCustomer')).toEqual([]);
  });

  it('status ngoài enum / page sai kiểu → 400 MALFORMED_REQUEST (trước vai trò); vai trò khác khách → 403', async () => {
    expectFail(await rejected('customer', 'get', '/api/me/complaints', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST', "Tham số 'status' sai kiểu dữ liệu");
    expectFail(await rejected('customer', 'get', '/api/me/complaints', undefined, { page: 'a' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'get', '/api/me/complaints', undefined, { size: 'x' }), 400, 'MALFORMED_REQUEST');
    for (const who of ['desk', 'manager', 'chain', 'admin', 'vet', 'caretaker'] as const) expectFail(await rejected(who, 'get', '/api/me/complaints'), 403, 'ACCESS_DENIED');
  });

  it('chi tiết đủ trường, tin nhắn cũ nhất trước kèm tên tác giả; của người khác hoặc không có → 404 (ADR-0006); id sai kiểu → MALFORMED_REQUEST', async () => {
    const c = await mineDetail(2);
    expect(Object.keys(c).sort()).toEqual(
      [
        'branchId', 'closedAt', 'closedReason', 'code', 'complaintId', 'content', 'createdAt', 'createdByStaff', 'customerId', 'customerName', 'escalatedAt', 'handlerName',
        'lastActivityAt', 'messages', 'relatedId', 'relatedType', 'reopenCount', 'resolution', 'resolvedAt', 'status', 'topic',
      ].sort(),
    );
    expect(c).toMatchObject({ status: 'IN_PROGRESS', handlerName: 'Hoàng Nam', relatedType: 'BOARDING', relatedId: 5, branchId: 1, createdByStaff: false, reopenCount: 0, escalatedAt: null });
    expect(c.messages.map((m) => [m.authorType, m.authorName, m.kind])).toEqual([
      ['CUSTOMER', 'Nguyễn Thu Trang', 'STATUS'],
      ['STAFF', 'Hoàng Nam', 'STATUS'],
      ['STAFF', 'Hoàng Nam', 'MESSAGE'],
      ['CUSTOMER', 'Nguyễn Thu Trang', 'MESSAGE'],
    ]);
    const times = c.messages.map((m) => Date.parse(m.createdAt));
    expect(times).toEqual([...times].sort((a, b) => a - b));
    expect(c.messages.map((m) => m.messageId)).toEqual([...c.messages.map((m) => m.messageId)].sort((a, b) => a - b));
    expect(await mineDetail(6)).toMatchObject({ status: 'CLOSED', closedReason: 'AUTO', handlerName: 'Hoàng Nam', closedAt: expect.any(String), resolvedAt: expect.any(String) });
    expect((await mineDetail(6)).messages.at(-1)).toMatchObject({ authorType: 'SYSTEM', authorName: null });
    expect((await mineDetail(10, 'pendingCustomer')).createdByStaff).toBe(true);
    expectFail(await rejected('customer', 'get', '/api/me/complaints/10'), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy khiếu nại #10');
    expectFail(await rejected('pendingCustomer', 'get', '/api/me/complaints/1'), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('customer', 'get', '/api/me/complaints/9999'), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('customer', 'get', '/api/me/complaints/abc'), 400, 'MALFORMED_REQUEST', "Tham số 'complaintId' sai kiểu dữ liệu");
    expectFail(await rejected('manager', 'get', '/api/me/complaints/1'), 403, 'ACCESS_DENIED');
  });
});

describe('trao đổi, đóng / rút, mở lại (#4, #5, #6, BR-DG-07)', () => {
  const message = (id: number | string, who: Who = 'customer', body: unknown = { body: 'Em xin bổ sung thêm thông tin.' }) => send(who, 'post', `/api/me/complaints/${id}/messages`, body);
  const close = (id: number | string, who: Who = 'customer') => send(who, 'post', `/api/me/complaints/${id}/close`);
  const reopen = (id: number | string, who: Who = 'customer', body: unknown = { body: 'Em chưa hài lòng với kết luận này.' }) => send(who, 'post', `/api/me/complaints/${id}/reopen`, body);

  it('tin nhắn: 201 trả cả khiếu nại, thêm tin của khách, đẩy lastActivityAt, không đổi trạng thái, không gửi thông báo; được cả khi RESOLVED', async () => {
    const before = await mineDetail(2);
    const noticesBefore = await notices('manager', 'COMPLAINT_REOPENED');
    vi.setSystemTime(new Date('2026-10-23T09:30:00+07:00'));
    const res = await message(2, 'customer', { body: '  Em đã nhận được phản hồi, cảm ơn anh.  ' });
    expect(res.status).toBe(201);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({ status: 'IN_PROGRESS', handlerName: 'Hoàng Nam', lastActivityAt: '2026-10-23T02:30:00.000Z' });
    expect(c.messages).toHaveLength(before.messages.length + 1);
    expect(c.messages.at(-1)).toMatchObject({ authorType: 'CUSTOMER', authorName: 'Nguyễn Thu Trang', kind: 'MESSAGE', body: 'Em đã nhận được phản hồi, cảm ơn anh.' });
    expect(Date.parse(c.lastActivityAt)).toBeGreaterThan(Date.parse(before.lastActivityAt));
    expect(await notices('manager', 'COMPLAINT_REOPENED')).toEqual(noticesBefore);
    expect((await message(3)).data.data.status).toBe('RESOLVED');
  });

  it('khiếu nại đã CLOSED → 409; của người khác → 404; thiếu / trống / quá 2 000 ký tự → VALIDATION_FAILED (sau vai trò)', async () => {
    expectFail(await failure(message(4)), 409, 'INVALID_STATE_TRANSITION', 'Khiếu nại đã đóng, không thể trao đổi thêm');
    expectFail(await failure(message(5)), 409, 'INVALID_STATE_TRANSITION');
    expectFail(await failure(message(10)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(message(2, 'customer', {})), 400, 'VALIDATION_FAILED', /^body: Nội dung không được để trống/);
    expectFail(await failure(message(2, 'customer', { body: '   ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(message(2, 'customer', { body: 'x'.repeat(2001) })), 400, 'VALIDATION_FAILED', /2000/);
    expect((await message(2, 'customer', { body: 'x'.repeat(2000) })).status).toBe(201);
    expectFail(await failure(message(2, 'customer', { body: 5 })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(message(4, 'customer', {})), 400, 'VALIDATION_FAILED'); // validation đến trước 404 / 409
    expectFail(await failure(message(2, 'desk')), 403, 'ACCESS_DENIED');
    expectFail(await failure(message(2, 'desk', {})), 403, 'ACCESS_DENIED');
  });

  it('đóng khi RESOLVED → CLOSED (CUSTOMER); rút khi OPEN / IN_PROGRESS → CLOSED (WITHDRAWN) và từ đó không mở lại được', async () => {
    const closed = (await close(3)).data.data as Complaint;
    expect(closed).toMatchObject({ status: 'CLOSED', closedReason: 'CUSTOMER', closedAt: expect.any(String) });
    expect(closed.messages.at(-1)).toMatchObject({ authorType: 'CUSTOMER', kind: 'STATUS', body: 'Khách đã đóng khiếu nại.' });
    for (const id of [1, 2]) {
      const withdrawn = (await close(id)).data.data as Complaint;
      expect(withdrawn).toMatchObject({ status: 'CLOSED', closedReason: 'WITHDRAWN' });
      expect(withdrawn.messages.at(-1)).toMatchObject({ kind: 'STATUS', body: 'Khách đã rút khiếu nại.' });
      ruleFailure(await failure(reopen(id)), 'BR-DG-07', /đã rút/);
    }
  });

  it('đã CLOSED → 409; của người khác / không có → 404; id sai kiểu → MALFORMED_REQUEST; vai trò khác khách → 403', async () => {
    expectFail(await failure(close(4)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái khiếu nại từ CLOSED sang CLOSED');
    await close(3);
    expectFail(await failure(close(3)), 409, 'INVALID_STATE_TRANSITION');
    expectFail(await failure(close(10)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(close(999)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(close('x')), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(close(1, 'manager')), 403, 'ACCESS_DENIED');
  });

  it('mở lại từ RESOLVED: OPEN, xóa người xử lý, reopenCount + 1, giữ kết luận cũ; dòng STATUS rồi tin nhắn lý do; quản lý chi nhánh nhận thông báo COMPLAINT_REOPENED', async () => {
    const before = await mineDetail(3);
    const res = await reopen(3, 'customer', { body: '  Gói thức ăn bù vẫn chưa tới.  ' });
    expect(res.status).toBe(200);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({ status: 'OPEN', handlerName: null, reopenCount: 1, closedAt: null, closedReason: null, resolution: before.resolution, resolvedAt: before.resolvedAt });
    expect(c.messages.slice(-2).map((m) => [m.authorType, m.kind, m.body])).toEqual([
      ['CUSTOMER', 'STATUS', 'Khách đã mở lại khiếu nại (lần 1).'],
      ['CUSTOMER', 'MESSAGE', 'Gói thức ăn bù vẫn chưa tới.'],
    ]);
    const got = await notices('manager', 'COMPLAINT_REOPENED');
    expect(got).toHaveLength(1);
    expect(got[0]).toMatchObject({ title: `Khiếu nại ${c.code} được mở lại` });
    expect(got[0].body).toBe(`Khách Nguyễn Thu Trang đã mở lại khiếu nại ${c.code} (lần 1). Vui lòng nhận xử lý.`);
    expect(await notices('manager2', 'COMPLAINT_REOPENED')).toHaveLength(0);
  });

  it('mở lại sau khi khách đã đóng (CLOSED do khách, còn trong 7 ngày) cũng được (Khiếu nại#7)', async () => {
    await close(3);
    const c = (await reopen(3)).data.data as Complaint;
    expect(c).toMatchObject({ status: 'OPEN', closedReason: null, reopenCount: 1 });
  });

  it('quá 7 ngày kể từ lúc RESOLVED → 400 BR-DG-07 (đúng 7 ngày thì còn được); CLOSED do khách / tự đóng đã quá hạn; quá 2 lần mở lại → 400', async () => {
    const resolvedAt = Date.parse((await mineDetail(3)).resolvedAt!);
    vi.setSystemTime(resolvedAt + 7 * DAY + 1);
    ruleFailure(await failure(reopen(3)), 'BR-DG-07', /trong 7 ngày/);
    vi.setSystemTime(resolvedAt + 7 * DAY);
    expect((await reopen(3)).status).toBe(200);
    vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
    ruleFailure(await failure(reopen(4)), 'BR-DG-07', /trong 7 ngày/); // CLOSED do khách, giải quyết 12 ngày trước
    ruleFailure(await failure(reopen(6)), 'BR-DG-07', /trong 7 ngày/); // tự đóng sau 7 ngày kể từ RESOLVED nên luôn quá hạn
    ruleFailure(await failure(reopen(8)), 'BR-DG-07', /tối đa 2 lần/); // RESOLVED mới 20 giờ nhưng đã mở lại 2 lần
  });

  it('đang OPEN / IN_PROGRESS → 409 (guard 400 chỉ chạy khi đã RESOLVED / CLOSED); của người khác → 404; thiếu lý do → VALIDATION_FAILED', async () => {
    expectFail(await failure(reopen(1)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái khiếu nại từ OPEN sang OPEN');
    expectFail(await failure(reopen(2)), 409, 'INVALID_STATE_TRANSITION', /IN_PROGRESS/);
    expectFail(await failure(reopen(10)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(reopen(3, 'customer', {})), 400, 'VALIDATION_FAILED', /^body: /);
    expectFail(await failure(reopen(3, 'customer', { body: ' ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(reopen(3, 'desk')), 403, 'ACCESS_DENIED');
  });
});

// ─── Nhân viên: tạo thay, danh sách, chi tiết, nhận, trả lời, kết luận (#7–12) ───

describe('lễ tân tạo khiếu nại thay khách (#7, BR-DG-05, 06)', () => {
  const forCustomer = (who: Who, over: Record<string, unknown> = {}) => send(who, 'post', '/api/complaints', { customerId: 2, ...draft(), ...over });

  it('201: createdByStaff, dòng STATUS của lễ tân, khách thấy trong danh sách của mình, lễ tân thấy trong danh sách của mình, quản lý chi nhánh được thông báo', async () => {
    const res = await forCustomer('desk');
    expect(res.status).toBe(201);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({ customerId: 2, customerName: 'Lê Hoàng Long', branchId: 1, status: 'OPEN', createdByStaff: true, handlerName: null });
    expect(c.messages).toHaveLength(1);
    expect(c.messages[0]).toMatchObject({ authorType: 'STAFF', authorName: 'Lan Chi', kind: 'STATUS', body: 'Lễ tân đã tạo khiếu nại thay khách.' });
    expect((await mineList({}, 'pendingCustomer'))[0].complaintId).toBe(c.complaintId);
    expect(await listIds('desk')).toContain(c.complaintId);
    const got = await notices('manager', 'COMPLAINT_CREATED');
    expect(got[0].body).toBe(`Khách Lê Hoàng Long vừa gửi khiếu nại ${c.code} về chất lượng dịch vụ. Vui lòng nhận xử lý.`);
  });

  it('khách tại quầy chưa có tài khoản cũng được (không có thông báo cho khách); khách không có → 404', async () => {
    expect((await forCustomer('desk', { customerId: 3 })).data.data).toMatchObject({ customerId: 3, customerName: 'Lê Hoàng Long' });
    expectFail(await failure(forCustomer('desk', { customerId: 999 })), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy khách hàng #999');
  });

  it('chi nhánh gửi lên phải là chi nhánh của lễ tân (403 phạm vi, đến trước 404); thiếu chi nhánh → 400 BR-DG-05 trừ OTHER (khi đó chỉ lễ tân đó và SUPER_MANAGER thấy)', async () => {
    expectFail(await failure(forCustomer('desk', { branchId: 2 })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
    expectFail(await failure(forCustomer('desk', { branchId: 99 })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(forCustomer('desk', { branchId: 2, customerId: 999 })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    ruleFailure(await failure(forCustomer('desk', { branchId: undefined })), 'BR-DG-05', /chọn chi nhánh/);
    const other = (await forCustomer('desk', { topic: 'OTHER', branchId: undefined })).data.data as Complaint;
    expect(other.branchId).toBeNull();
    expect(await listIds('desk')).toContain(other.complaintId);
    expect(await listIds('chain')).toContain(other.complaintId);
    expect(await listIds('manager')).not.toContain(other.complaintId);
  });

  it('nội dung 10–2 000 ký tự, đối tượng liên quan phải thuộc khách được tạo thay, tối đa 10 khiếu nại đang mở của khách đó (BR-DG-05)', async () => {
    ruleFailure(await failure(forCustomer('desk', { content: 'ngắn' })), 'BR-DG-05', /từ 10 đến 2000/);
    ruleFailure(await failure(forCustomer('desk', { relatedType: 'ONLINE_ORDER', relatedId: 5001 })), 'BR-DG-05', /không thuộc khách/); // đơn của khách 1
    expect((await forCustomer('desk', { relatedType: 'ONLINE_ORDER', relatedId: 5002 })).status).toBe(201);
    expectFail(await failure(forCustomer('desk', { relatedType: 'BOARDING', relatedId: 777 })), 404, 'RESOURCE_NOT_FOUND');
    for (let i = 0; i < 7; i++) await forCustomer('desk'); // khách 2 có 2 mẫu + 1 + 7 = 10
    ruleFailure(await failure(forCustomer('desk')), 'BR-DG-05', /tối đa 10 khiếu nại đang mở/);
  });

  it('VALIDATION_FAILED thiếu khách / chủ đề / nội dung; MALFORMED_REQUEST kiểu sai; chỉ lễ tân (RECEPTIONIST) được', async () => {
    expectFail(await failure(send('desk', 'post', '/api/complaints', {})), 400, 'VALIDATION_FAILED', /customerId: .*topic: .*content: /);
    expectFail(await failure(forCustomer('desk', { customerId: 'hai' })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(forCustomer('desk', { topic: 'FOO' })), 400, 'MALFORMED_REQUEST');
    for (const who of ['manager', 'chain', 'admin', 'vet', 'caretaker', 'customer'] as const) expectFail(await failure(forCustomer(who)), 403, 'ACCESS_DENIED');
    expectFail(await failure(forCustomer('customer', { topic: 'FOO' })), 400, 'MALFORMED_REQUEST'); // sai kiểu đến trước vai trò
    expectFail(await failure(send('customer', 'post', '/api/complaints', {})), 403, 'ACCESS_DENIED'); // VALIDATION_FAILED đến sau vai trò
  });
});

describe('danh sách và chi tiết cho nhân viên (#8, #9, BR-DG-06, 08)', () => {
  it('mặc định OPEN + IN_PROGRESS, cũ nhất (theo hoạt động cuối) trước; tự lọc theo phạm vi: quản lý chi nhánh, SUPER_MANAGER, lễ tân', async () => {
    expect(await listIds('manager')).toEqual([12, 7, 10, 2, 1]);
    expect(await listIds('chain')).toEqual([12, 7, 10, 9, 2, 11, 1]);
    expect(await listIds('manager2')).toEqual([9]);
    expect(await listIds('desk')).toEqual([10]); // lễ tân chỉ thấy cái mình tạo thay khách
    const [oldest] = (await get<Page<ComplaintSummary>>('manager', '/api/complaints')).content;
    expect(oldest).toMatchObject({ complaintId: 12, status: 'IN_PROGRESS', escalated: true, branchId: 1 });
  });

  it('lọc status (kể cả RESOLVED / CLOSED), escalated (true = đã leo thang, false = chưa), phân trang', async () => {
    expect(await listIds('manager', { status: 'RESOLVED' })).toEqual([3, 8]);
    expect(await listIds('manager', { status: 'CLOSED' })).toEqual([4, 6, 5]);
    expect(await listIds('manager', { status: 'OPEN' })).toEqual([7, 10, 1]);
    expect(await listIds('manager', { escalated: true })).toEqual([12, 7]);
    expect(await listIds('manager', { escalated: false })).toEqual([10, 2, 1]);
    expect(await listIds('chain', { escalated: 'true', status: 'OPEN' })).toEqual([7]);
    expect(await listIds('manager2', { status: 'CLOSED' })).toEqual([]);
    const page = await get<Page<ComplaintSummary>>('chain', '/api/complaints', { size: 3, page: 2 });
    expect(page).toMatchObject({ page: 2, size: 3, totalElements: 7, totalPages: 3 });
    expect(page.content.map((c) => c.complaintId)).toEqual([1]);
  });

  it('status ngoài enum, escalated / page sai kiểu → 400 MALFORMED_REQUEST (trước vai trò); vai trò ngoài SUPER / quản lý / lễ tân → 403', async () => {
    expectFail(await rejected('manager', 'get', '/api/complaints', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'get', '/api/complaints', undefined, { escalated: 'sometimes' }), 400, 'MALFORMED_REQUEST', "Tham số 'escalated' sai kiểu dữ liệu");
    expectFail(await rejected('customer', 'get', '/api/complaints', undefined, { page: 'a' }), 400, 'MALFORMED_REQUEST');
    for (const who of ['admin', 'vet', 'caretaker', 'customer'] as const) expectFail(await rejected(who, 'get', '/api/complaints'), 403, 'ACCESS_DENIED');
  });

  it('chi tiết: quản lý thấy khiếu nại chi nhánh mình, SUPER_MANAGER thấy mọi khiếu nại (kể cả không chi nhánh), lễ tân chỉ cái mình tạo; ngoài phạm vi → 403, không có → 404', async () => {
    const c = await get<Complaint>('manager', '/api/complaints/2');
    expect(c).toMatchObject({ complaintId: 2, customerName: 'Nguyễn Thu Trang', handlerName: 'Hoàng Nam', status: 'IN_PROGRESS' });
    expect(c.messages).toHaveLength(4);
    for (const id of [1, 9, 11, 12]) expect((await get<Complaint>('chain', `/api/complaints/${id}`)).complaintId).toBe(id);
    expect((await get<Complaint>('manager2', '/api/complaints/9')).branchId).toBe(2);
    expect((await get<Complaint>('desk', '/api/complaints/10')).createdByStaff).toBe(true);
    for (const [who, id] of [['manager', 9], ['manager', 11], ['manager2', 1], ['desk', 1], ['desk', 11]] as const) {
      expectFail(await rejected(who, 'get', `/api/complaints/${id}`), 403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
    }
    expectFail(await rejected('manager', 'get', '/api/complaints/9999'), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('chain', 'get', '/api/complaints/abc'), 400, 'MALFORMED_REQUEST');
    for (const who of ['admin', 'vet', 'caretaker', 'customer'] as const) expectFail(await rejected(who, 'get', '/api/complaints/1'), 403, 'ACCESS_DENIED');
  });
});

describe('nhận, trả lời, kết luận (#10, #11, #12, BR-DG-07)', () => {
  const take = (id: number | string, who: Who = 'manager') => send(who, 'post', `/api/complaints/${id}/take`);
  const reply = (id: number | string, who: Who = 'manager', body: unknown = { body: 'Chi nhánh đã ghi nhận ý kiến của chị.' }) => send(who, 'post', `/api/complaints/${id}/messages`, body);
  const resolve = (id: number | string, who: Who = 'manager', body: unknown = { resolution: 'Chi nhánh xin lỗi và đã hoàn lại phí dịch vụ.' }) => send(who, 'post', `/api/complaints/${id}/resolve`, body);

  it('nhận: OPEN → IN_PROGRESS, gán người gọi, dòng STATUS, khách được thông báo COMPLAINT_TAKEN', async () => {
    const res = await take(1);
    expect(res.status).toBe(200);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({ status: 'IN_PROGRESS', handlerName: 'Hoàng Nam' });
    expect(c.messages.at(-1)).toMatchObject({ authorType: 'STAFF', authorName: 'Hoàng Nam', kind: 'STATUS', body: 'Quản lý đã nhận xử lý khiếu nại.' });
    const got = await notices('customer', 'COMPLAINT_TAKEN');
    expect(got).toHaveLength(1);
    expect(got[0]).toMatchObject({ title: `Khiếu nại ${c.code} đã được tiếp nhận`, body: `Khiếu nại ${c.code} của bạn đã được tiếp nhận và đang xử lý.`, linkUrl: null, readAt: null });
    expect(await notices('pendingCustomer', 'COMPLAINT_TAKEN')).toHaveLength(0);
    // SUPER_MANAGER nhận được cả khiếu nại của chi nhánh 2 và khiếu nại không chi nhánh.
    expect((await take(9, 'chain')).data.data).toMatchObject({ status: 'IN_PROGRESS', handlerName: 'Đỗ Thanh Hà' });
    expect((await take(11, 'chain')).data.data.status).toBe('IN_PROGRESS');
  });

  it('nhận khi không còn OPEN → 409; ngoài phạm vi → 403; không có → 404; vai trò khác SUPER / quản lý (kể cả lễ tân) → 403', async () => {
    expectFail(await failure(take(2)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái khiếu nại từ IN_PROGRESS sang IN_PROGRESS');
    expectFail(await failure(take(3)), 409, 'INVALID_STATE_TRANSITION', /RESOLVED/);
    expectFail(await failure(take(4)), 409, 'INVALID_STATE_TRANSITION');
    expectFail(await failure(take(1, 'manager2')), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(take(9, 'manager')), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(take(11, 'manager')), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(take(999)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(take('abc')), 400, 'MALFORMED_REQUEST');
    for (const who of ['desk', 'admin', 'vet', 'caretaker', 'customer'] as const) expectFail(await failure(take(10, who)), 403, 'ACCESS_DENIED');
    const customerSees = await mineDetail(1);
    expect(customerSees.status).toBe('OPEN');
  });

  it('trả lời: 201 trả cả khiếu nại, tin STAFF cuối danh sách, đẩy lastActivityAt, khách thấy ngay; không cần nhận trước; lễ tân chỉ trả lời cái mình tạo', async () => {
    vi.setSystemTime(new Date('2026-10-23T10:00:00+07:00'));
    const res = await reply(1, 'manager', { body: '  Chi nhánh đã ghi nhận.  ' });
    expect(res.status).toBe(201);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({ status: 'OPEN', handlerName: null, lastActivityAt: '2026-10-23T03:00:00.000Z' });
    expect(c.messages.at(-1)).toMatchObject({ authorType: 'STAFF', authorName: 'Hoàng Nam', kind: 'MESSAGE', body: 'Chi nhánh đã ghi nhận.' });
    expect((await mineDetail(1)).messages.at(-1)?.body).toBe('Chi nhánh đã ghi nhận.');
    expect((await reply(10, 'desk', { body: 'Đã hẹn khách lại.' })).data.data.messages.at(-1)).toMatchObject({ authorName: 'Lan Chi' });
    expect((await reply(9, 'chain')).status).toBe(201);
    expect((await reply(3)).data.data.status).toBe('RESOLVED');
  });

  it('trả lời: CLOSED → 409; ngoài phạm vi → 403 (lễ tân cái không phải mình tạo, quản lý chi nhánh khác); thiếu / quá dài → VALIDATION_FAILED; vai trò khác → 403', async () => {
    expectFail(await failure(reply(4)), 409, 'INVALID_STATE_TRANSITION', 'Khiếu nại đã đóng, không thể trao đổi thêm');
    expectFail(await failure(reply(1, 'desk')), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(reply(9)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(reply(999)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(reply(1, 'manager', {})), 400, 'VALIDATION_FAILED', /^body: /);
    expectFail(await failure(reply(1, 'manager', { body: 'y'.repeat(2001) })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(reply(9, 'manager', {})), 400, 'VALIDATION_FAILED'); // validation đến trước phạm vi
    expectFail(await failure(reply(1, 'manager', 'không phải object')), 400, 'MALFORMED_REQUEST');
    for (const who of ['admin', 'vet', 'caretaker', 'customer'] as const) expectFail(await failure(reply(1, who)), 403, 'ACCESS_DENIED');
  });

  it('kết luận: IN_PROGRESS → RESOLVED, lưu kết luận đã cắt khoảng trắng, resolvedAt, dòng STATUS, khách được thông báo COMPLAINT_RESOLVED', async () => {
    const res = await resolve(2, 'manager', { resolution: '  Đã hoàn lại phí dịch vụ.  ' });
    expect(res.status).toBe(200);
    const c = res.data.data as Complaint;
    expect(c).toMatchObject({ status: 'RESOLVED', resolution: 'Đã hoàn lại phí dịch vụ.', handlerName: 'Hoàng Nam', resolvedAt: expect.any(String), closedAt: null });
    expect(c.messages.at(-1)).toMatchObject({ authorType: 'STAFF', kind: 'STATUS', body: 'Quản lý đã giải quyết khiếu nại.' });
    const got = await notices('customer', 'COMPLAINT_RESOLVED');
    expect(got).toHaveLength(1);
    expect(got[0].body).toBe(`Khiếu nại ${c.code} đã được giải quyết: Đã hoàn lại phí dịch vụ. Bạn có thể mở lại trong 7 ngày.`);
    // SUPER_MANAGER kết luận được khiếu nại đã leo thang do quản lý chi nhánh đang giữ; người xử lý vẫn là quản lý đã nhận.
    expect((await resolve(12, 'chain')).data.data).toMatchObject({ status: 'RESOLVED', handlerName: 'Hoàng Nam', escalatedAt: expect.any(String) });
  });

  it('thiếu kết luận → 400 BR-DG-07 (không phải VALIDATION_FAILED; sau 404 / phạm vi, trước 409); quá 2 000 ký tự → VALIDATION_FAILED', async () => {
    ruleFailure(await failure(resolve(2, 'manager', {})), 'BR-DG-07', 'Vui lòng nhập kết luận (BR-DG-07)');
    ruleFailure(await failure(resolve(2, 'manager', { resolution: '   ' })), 'BR-DG-07');
    ruleFailure(await failure(resolve(1, 'manager', {})), 'BR-DG-07'); // đang OPEN nhưng guard BR đến trước 409
    expectFail(await failure(resolve(2, 'manager', { resolution: 'z'.repeat(2001) })), 400, 'VALIDATION_FAILED', /^resolution: .*2000/);
    expect((await resolve(2, 'manager', { resolution: 'z'.repeat(2000) })).status).toBe(200);
    expectFail(await failure(resolve(2, 'manager', { resolution: 5 })), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/complaints/2/resolve'), 400, 'MALFORMED_REQUEST');
  });

  it('không phải IN_PROGRESS → 409; ngoài phạm vi → 403; không có → 404; vai trò khác SUPER / quản lý → 403', async () => {
    expectFail(await failure(resolve(1)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái khiếu nại từ OPEN sang RESOLVED');
    expectFail(await failure(resolve(3)), 409, 'INVALID_STATE_TRANSITION', /RESOLVED sang RESOLVED/);
    expectFail(await failure(resolve(4)), 409, 'INVALID_STATE_TRANSITION');
    expectFail(await failure(resolve(2, 'manager2')), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(resolve(2, 'manager2', {})), 403, 'ACCESS_DENIED_SCOPE_MISMATCH'); // thiếu kết luận cũng sau phạm vi
    expectFail(await failure(resolve(999)), 404, 'RESOURCE_NOT_FOUND');
    for (const who of ['desk', 'admin', 'vet', 'caretaker', 'customer'] as const) expectFail(await failure(resolve(2, who)), 403, 'ACCESS_DENIED');
  });
});

describe('vòng đời đầy đủ (FSM #26)', () => {
  it('gửi → nhận → trao đổi → kết luận → mở lại 2 lần → lần thứ 3 bị từ chối; vết STATUS ghi đủ mỗi chuyển trạng thái', async () => {
    const c = await createMine();
    const id = c.complaintId;
    await post('manager', `/api/complaints/${id}/take`);
    await post('manager', `/api/complaints/${id}/messages`, { body: 'Chi nhánh đang kiểm tra.' });
    await post('customer', `/api/me/complaints/${id}/messages`, { body: 'Em chờ phản hồi.' });
    for (let round = 1; round <= 2; round++) {
      await post('manager', `/api/complaints/${id}/resolve`, { resolution: `Kết luận lần ${round}` });
      const reopened = await post<Complaint>('customer', `/api/me/complaints/${id}/reopen`, { body: `Chưa hài lòng lần ${round}` });
      expect(reopened).toMatchObject({ status: 'OPEN', reopenCount: round, handlerName: null });
      await post('manager', `/api/complaints/${id}/take`);
    }
    await post('manager', `/api/complaints/${id}/resolve`, { resolution: 'Kết luận lần 3' });
    ruleFailure(await rejected('customer', 'post', `/api/me/complaints/${id}/reopen`, { body: 'Lần nữa' }), 'BR-DG-07', /tối đa 2 lần/);
    const done = await post<Complaint>('customer', `/api/me/complaints/${id}/close`);
    expect(done).toMatchObject({ status: 'CLOSED', closedReason: 'CUSTOMER', reopenCount: 2, resolution: 'Kết luận lần 3' });
    ruleFailure(await rejected('customer', 'post', `/api/me/complaints/${id}/reopen`, { body: 'Lần nữa' }), 'BR-DG-07', /tối đa 2 lần/);
    expect(done.messages.filter((m) => m.kind === 'STATUS')).toHaveLength(10); // gửi, nhận ×3, kết luận ×3, mở lại ×2, đóng
    expect(done.messages.filter((m) => m.kind === 'MESSAGE')).toHaveLength(4); // 2 tin trao đổi + 2 lý do mở lại
    const ids = done.messages.map((m) => m.messageId);
    expect(ids).toEqual([...ids].sort((a, b) => a - b));
  });
});

// ─── Phân quyền 7 vai trò × 12 endpoint, 401 ───

describe('phân quyền', () => {
  const ROLES = ['admin', 'chain', 'manager', 'desk', 'vet', 'caretaker', 'customer'] as const;
  const CUSTOMER_ONLY: Who[] = ['customer'];
  const REPLYING: Who[] = ['chain', 'manager', 'desk'];
  const HANDLING: Who[] = ['chain', 'manager'];
  const endpoints: { name: string; allowed: readonly Who[]; call: (who: Who) => Promise<AxiosResponse> }[] = [
    { name: '#1 POST /me/complaints', allowed: CUSTOMER_ONLY, call: (w) => send(w, 'post', '/api/me/complaints', draft()) },
    { name: '#2 GET /me/complaints', allowed: CUSTOMER_ONLY, call: (w) => send(w, 'get', '/api/me/complaints') },
    { name: '#3 GET /me/complaints/{id}', allowed: CUSTOMER_ONLY, call: (w) => send(w, 'get', '/api/me/complaints/1') },
    { name: '#4 POST /me/complaints/{id}/messages', allowed: CUSTOMER_ONLY, call: (w) => send(w, 'post', '/api/me/complaints/2/messages', { body: 'Ma trận' }) },
    { name: '#5 POST /me/complaints/{id}/close', allowed: CUSTOMER_ONLY, call: (w) => send(w, 'post', '/api/me/complaints/1/close') },
    { name: '#6 POST /me/complaints/{id}/reopen', allowed: CUSTOMER_ONLY, call: (w) => send(w, 'post', '/api/me/complaints/3/reopen', { body: 'Ma trận' }) },
    { name: '#7 POST /complaints', allowed: ['desk'], call: (w) => send(w, 'post', '/api/complaints', { customerId: 2, ...draft() }) },
    { name: '#8 GET /complaints', allowed: REPLYING, call: (w) => send(w, 'get', '/api/complaints') },
    { name: '#9 GET /complaints/{id}', allowed: REPLYING, call: (w) => send(w, 'get', '/api/complaints/10') },
    { name: '#10 POST /complaints/{id}/take', allowed: HANDLING, call: (w) => send(w, 'post', '/api/complaints/1/take') },
    { name: '#11 POST /complaints/{id}/messages', allowed: REPLYING, call: (w) => send(w, 'post', '/api/complaints/10/messages', { body: 'Ma trận' }) },
    { name: '#12 POST /complaints/{id}/resolve', allowed: HANDLING, call: (w) => send(w, 'post', '/api/complaints/2/resolve', { resolution: 'Ma trận' }) },
  ];

  it('đủ 12 endpoint của hợp đồng', () => {
    expect(endpoints).toHaveLength(12);
  });

  for (const endpoint of endpoints) {
    it(`${endpoint.name}: chỉ ${endpoint.allowed.join(', ')} được; vai khác 403 ACCESS_DENIED`, async () => {
      for (const who of ROLES) {
        localStorage.clear(); // mỗi vai chạy trên dữ liệu mẫu nguyên vẹn để id lặp lại
        const status = await statusOf(endpoint.call(who));
        if (endpoint.allowed.includes(who)) expect(status, `${who} phải được`).toBeLessThan(300);
        else expect(status, `${who} phải bị từ chối`).toBe(403);
      }
    });
  }

  it('401 UNAUTHENTICATED cho mọi endpoint khi không có token', async () => {
    const calls: [string, string, unknown?][] = [
      ['post', '/api/me/complaints', {}], ['get', '/api/me/complaints'], ['get', '/api/me/complaints/1'], ['post', '/api/me/complaints/1/messages', {}],
      ['post', '/api/me/complaints/1/close'], ['post', '/api/me/complaints/1/reopen', {}], ['post', '/api/complaints', {}], ['get', '/api/complaints'],
      ['get', '/api/complaints/1'], ['post', '/api/complaints/1/take'], ['post', '/api/complaints/1/messages', {}], ['post', '/api/complaints/1/resolve', {}],
    ];
    expect(calls).toHaveLength(12);
    for (const [method, url, body] of calls) expectFail(await failure(client.request({ method, url, data: body })), 401, 'UNAUTHENTICATED');
  });
});

// ─── State ───

describe('state localStorage', () => {
  const KEY = 'petcare-mock-complaint';

  it('state rỗng {}, hỏng hoặc null → dùng lại dữ liệu mẫu, không nổ', async () => {
    for (const stored of ['{}', 'không phải json', 'null', '{"complaints":"không phải mảng"}']) {
      localStorage.setItem(KEY, stored);
      expect((await mineList({ size: 100 })).length).toBe(10);
    }
  });

  it('state cũ: dòng thiếu mảng messages vẫn đọc được, vẫn nhắn tin / đóng / mở lại được', async () => {
    const old = {
      complaintId: 5, code: 'KN-250101-0001', customerId: 1, branchId: 1, topic: 'GROOMING', relatedType: null, relatedId: null, content: 'Nội dung cũ đủ dài', status: 'RESOLVED',
      createdByStaffId: null, handlerId: 3, resolution: 'Đã xử lý', resolvedAt: new Date().toISOString(), closedAt: null, closedReason: null, reopenCount: 0, escalatedAt: null,
      lastActivityAt: new Date().toISOString(), createdAt: new Date().toISOString(),
    };
    localStorage.setItem(KEY, JSON.stringify({ complaints: [old] }));
    expect((await mineDetail(5)).messages).toEqual([]);
    expect((await listIds('manager', { status: 'RESOLVED' }))).toEqual([5]);
    const after = await post<Complaint>('customer', '/api/me/complaints/5/reopen', { body: 'Chưa ổn' });
    expect(after).toMatchObject({ status: 'OPEN', reopenCount: 1 });
    expect(after.messages.map((m) => m.messageId)).toEqual([1, 2]);
    const created = await createMine();
    expect(created.complaintId).toBe(6);
    expect(created.code).toMatch(/^KN-261023-0001$/);
  });
});
