import axios, { type AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse } from '../../types';
import type { InventoryItem } from '../../types/inventory';
import type { CashierShift } from '../../types/sales';
import type { OnlineOrder } from '../../types/shop';
import type { Refund, ReturnRequest, ReturnRequestSummary } from '../../types/returns';
import { ruleIdOf } from '../api-error';
import { authFor, failure, type Auth, type Who } from './commerce.test-client';
import { gatewaySignature } from './payment.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (commerce.store.ts + mock bán hàng): Order quầy #5 (khách #1, chi nhánh 1) PAID 3 ngày trước, dòng #51 Pate × 6 thu tiền mặt (phiếu #1); Order quầy #8 ở chi nhánh 2.
// Trả hàng chi nhánh 1: #1 PENDING (Order #5 ×2) · #2 APPROVED (×1, hoàn CASH #2 chờ xác nhận) · #3 REJECTED · #4 CANCELLED · #5 PENDING do tài khoản #8 tạo (đơn online #2, hạt ×1) ·
// #6 APPROVED (đơn online #2, pate ×1, hoàn GATEWAY #3 FAILED) · #7 REFUNDED (đơn online #1). Hoàn tiền: #1 SUCCEEDED (hủy đơn online #10), #2 CASH REQUESTED, #3 GATEWAY FAILED, #4 SUCCEEDED.
// Dòng #51 còn được trả 6 − 2 − 1 = 3. Đơn online #2 (salesOrderId 7002): dòng 700201 hạt ×1 (đã có yêu cầu #5), dòng 700202 pate ×4 (đã có #6 ×1 → còn 3).
// Kho chi nhánh 1: Royal Canin 12, Pate 3. Đồng hồ ghim ở thứ Ba 27/10/2026 10:00 giờ Việt Nam khi cần mở ca thu ngân (chi nhánh 1 mở cửa giờ đó).

const TUE_10 = '2026-10-27T10:00:00+07:00';
let client: AxiosInstance;
let tokens: Map<Who, Auth>;
beforeEach(() => {
  client = axios.create();
  installMockApi(client);
  tokens = new Map();
});
afterEach(() => {
  vi.useRealTimers();
});

type Verb = 'get' | 'post' | 'put' | 'delete';
async function auth(who: Who) {
  if (!tokens.has(who)) tokens.set(who, await authFor(client, who));
  return tokens.get(who)!;
}
async function call(who: Who, method: Verb, url: string, body?: unknown, params?: object) {
  return client.request({ method, url, data: body, params, ...(await auth(who)) });
}
const fails = (who: Who, method: Verb, url: string, body?: unknown, params?: object) => failure(call(who, method, url, body, params));
const data = async <T>(who: Who, method: Verb, url: string, body?: unknown, params?: object) => (await call(who, method, url, body, params)).data.data as T;

const create = (body: unknown, who: Who = 'desk') => data<ReturnRequest>(who, 'post', '/api/return-requests', body);
const request = (id: number, who: Who = 'desk') => data<ReturnRequest>(who, 'get', `/api/return-requests/${id}`);
const list = (params?: object, who: Who = 'desk') => data<PageResponse<ReturnRequestSummary>>(who, 'get', '/api/return-requests', undefined, params);
const refunds = (params?: object, who: Who = 'desk') => data<PageResponse<Refund>>(who, 'get', '/api/refunds', undefined, params);
const stock = async (productId: number) => (await data<InventoryItem>('desk', 'get', `/api/inventory/${productId}`)).availableQuantity;
const ids = (page: PageResponse<{ requestId?: number; refundId?: number }>) => page.content.map((x) => x.requestId ?? x.refundId);
const line = (orderLineId: number, quantity: number) => ({ orderLineId, quantity });
const NEW_RETURN = { orderId: 5, lines: [line(51, 1)], reasonCode: 'DEFECTIVE' };

function expectError(res: { status: number; data: { errorCode: string; message: string } }, status: number, errorCode: string, rule?: string) {
  expect(res.status).toBe(status);
  expect(res.data.errorCode).toBe(errorCode);
  if (rule) expect(ruleIdOf(res.data.message)).toBe(rule);
}

/** Lưu state bán hàng (mock bán hàng chỉ ghi khi có thay đổi) rồi sửa Order #2 (khám + thuốc + pate) thành PAID, thu chuyển khoản (phiếu #2). */
async function payVisitOrder() {
  await call('desk', 'post', '/api/orders', { customerId: 1 });
  const sales = JSON.parse(localStorage.getItem('petcare-mock-sales')!);
  Object.assign(sales.orders.find((o: { orderId: number }) => o.orderId === 2), { status: 'PAID', paidAt: new Date().toISOString(), paymentId: 2 });
  localStorage.setItem('petcare-mock-sales', JSON.stringify(sales));
}

// ─── Quyền ───

type Endpoint = [label: string, verb: Verb, url: string, body: unknown, allowed: Who[]];
const READ: Who[] = ['desk', 'manager'];
const ENDPOINTS: Endpoint[] = [
  ['#1 tạo yêu cầu', 'post', '/api/return-requests', NEW_RETURN, ['desk']],
  ['#2 danh sách', 'get', '/api/return-requests', undefined, READ],
  ['#3 chi tiết', 'get', '/api/return-requests/1', undefined, READ],
  ['#4 rút yêu cầu', 'post', '/api/return-requests/1/cancel', undefined, ['desk']],
  ['#5 duyệt', 'post', '/api/return-requests/1/approve', { restock: true }, ['manager']],
  ['#6 từ chối', 'post', '/api/return-requests/5/reject', { reason: 'Không đủ điều kiện' }, ['manager']],
  ['#7 danh sách hoàn tiền', 'get', '/api/refunds', undefined, READ],
  ['#8 xác nhận hoàn tiền', 'post', '/api/refunds/2/confirm', {}, ['desk']],
  ['#9 thử lại hoàn tiền', 'post', '/api/refunds/3/retry', undefined, ['manager']],
];
const ROLES: Who[] = ['admin', 'chain', 'manager', 'desk', 'vet', 'caretaker', 'owner'];

describe('quyền theo 7 vai trò + không token', () => {
  it('đủ 9 endpoint của returns-v1', () => {
    expect(ENDPOINTS).toHaveLength(9);
  });

  describe.each(ENDPOINTS)('%s', (_label, verb, url, body, allowed) => {
    it.each(ROLES.filter((r) => !allowed.includes(r)))('%s bị 403 ACCESS_DENIED', async (who) => {
      expectError(await fails(who, verb, url, body), 403, 'ACCESS_DENIED');
    });
    it.each(allowed)('%s được phép (không 401 / 403 ACCESS_DENIED)', async (who) => {
      const res = await call(who, verb, url, body).then(
        (r) => ({ status: r.status, data: r.data as { errorCode?: string } }),
        (e) => e.response as { status: number; data: { errorCode: string } },
      );
      expect(res.status).not.toBe(401);
      expect(res.data?.errorCode).not.toBe('ACCESS_DENIED');
    });
    it('không token → 401 UNAUTHENTICATED', async () => {
      expectError(await failure(client.request({ method: verb, url, data: body })), 401, 'UNAUTHENTICATED');
    });
  });
});

// ─── Tạo yêu cầu trả hàng (#1) ───

describe('tạo yêu cầu trả hàng (UC68, BR-BH-13)', () => {
  it('201 PENDING cho Order quầy: chụp tên và giá, chưa có quyết định, vào danh sách PENDING sau các yêu cầu cũ', async () => {
    const res = await call('desk', 'post', '/api/return-requests', { ...NEW_RETURN, lines: [line(51, 2)], reasonNote: '  Bao bì rách  ' });
    expect(res.status).toBe(201);
    expect(res.data.data).toEqual({
      requestId: 8,
      code: expect.stringMatching(/^TH-\d{6}-\d{4}$/),
      orderId: 5,
      branchId: 1,
      status: 'PENDING',
      reasonCode: 'DEFECTIVE',
      reasonNote: 'Bao bì rách',
      lines: [{ orderLineId: 51, productName: 'Pate Whiskas 85g', unitPrice: 18_000, quantity: 2 }],
      restock: null,
      refundAmount: null,
      decisionNote: null,
      createdAt: expect.any(String),
      decidedAt: null,
    });
    expect(ids(await list())).toEqual([1, 5, 8]);
  });

  it('đơn online: dùng salesOrderId, dòng theo orderLineId của OnlineOrderLine', async () => {
    const o2 = await data<OnlineOrder>('owner', 'get', '/api/me/online-orders/2');
    expect(o2.salesOrderId).toBe(7002);
    const made = await create({ orderId: o2.salesOrderId, lines: [line(o2.lines[1].orderLineId!, 3)], reasonCode: 'WRONG_ITEM' });
    expect(made).toMatchObject({ orderId: 7002, lines: [{ productName: 'Pate Whiskas 85g', unitPrice: 18_000, quantity: 3 }] });
    // onlineOrderId (2) không phải salesOrderId: orders.id = 2 là Order khám tại quầy, không có dòng 700202 → 404
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 2, lines: [line(o2.lines[1].orderLineId!, 1)], reasonCode: 'OTHER' }), 404, 'RESOURCE_NOT_FOUND');
  });

  it('số lượng ≤ đã mua − đã / đang trả: dòng #51 còn 3; yêu cầu bị từ chối hoặc rút nhả lại số lượng', async () => {
    const over = await fails('desk', 'post', '/api/return-requests', { ...NEW_RETURN, lines: [line(51, 4)] });
    expectError(over, 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    expect(over.data.message).toContain('còn 3');
    await create({ ...NEW_RETURN, lines: [line(51, 3)] });
    expectError(await fails('desk', 'post', '/api/return-requests', NEW_RETURN), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    await call('desk', 'post', '/api/return-requests/8/cancel'); // rút → nhả 3
    expect((await create({ ...NEW_RETURN, lines: [line(51, 3)] })).requestId).toBe(9);
    // đơn online #2: hạt đã có yêu cầu PENDING #5 nên hết; pate còn 3
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 7002, lines: [line(700201, 1)], reasonCode: 'OTHER' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    await call('manager', 'post', '/api/return-requests/5/reject', { reason: 'Không nhận' });
    expect((await create({ orderId: 7002, lines: [line(700201, 1)], reasonCode: 'OTHER' })).orderId).toBe(7002);
  });

  it('chỉ trả dòng GOODS: dịch vụ và thuốc trong Order khám đã PAID bị chặn, hàng được trả', async () => {
    await payVisitOrder(); // Order #2: dòng #21 khám, #22 Amoxicillin, #23 Pate × 2
    const service = await fails('desk', 'post', '/api/return-requests', { orderId: 2, lines: [line(21, 1)], reasonCode: 'OTHER' });
    expectError(service, 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    expect(service.data.message).toContain('Khám tổng quát');
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 2, lines: [line(22, 1)], reasonCode: 'OTHER' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    expect((await create({ orderId: 2, lines: [line(23, 2)], reasonCode: 'OTHER' })).lines).toEqual([{ orderLineId: 23, productName: 'Pate Whiskas 85g', unitPrice: 18_000, quantity: 2 }]);
  });

  it('Order chưa PAID / đã hủy, đơn online chưa giao → 400 BR-BH-13', async () => {
    for (const [orderId, orderLineId] of [[4, 41], [1, 11], [6, 61], [7003, 700301], [7008, 700801], [7009, 700901]]) {
      expectError(await fails('desk', 'post', '/api/return-requests', { orderId, lines: [line(orderLineId, 1)], reasonCode: 'OTHER' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    }
    expect((await fails('desk', 'post', '/api/return-requests', { orderId: 7003, lines: [line(700301, 1)], reasonCode: 'OTHER' })).data.message).toContain('chưa giao');
  });

  it('quá hạn 7 ngày kể từ lúc giao (online) / lúc thanh toán (quầy) → 400 BR-BH-13; đúng 7 ngày thì còn được', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(TUE_10));
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 7001, lines: [line(700102, 1)], reasonCode: 'OTHER' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13'); // giao 14 ngày trước
    await call('desk', 'post', '/api/orders', { customerId: 1 }); // lưu state bán hàng để mốc thanh toán không trôi theo đồng hồ
    vi.setSystemTime(new Date(TUE_10).getTime() + 6 * 86_400_000);
    expect((await create({ orderId: 7002, lines: [line(700202, 1)], reasonCode: 'OTHER' })).requestId).toBe(8); // giao đúng 7 ngày trước
    vi.setSystemTime(new Date(TUE_10).getTime() + 6 * 86_400_000 + 1000);
    const late = await fails('desk', 'post', '/api/return-requests', { orderId: 7002, lines: [line(700202, 1)], reasonCode: 'OTHER' });
    expectError(late, 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    expect(late.data.message).toContain('7 ngày');
    expectError(await fails('desk', 'post', '/api/return-requests', NEW_RETURN), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13'); // Order #5 thu từ 24/10: 7+ ngày
  });

  it('thiếu lý do, không có dòng, dòng trùng → 400 BR-BH-13 (sau 404 / phạm vi)', async () => {
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 5, lines: [line(51, 1)] }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    for (const lines of [[], undefined, null]) {
      expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 5, lines, reasonCode: 'OTHER' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    }
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 5, lines: [line(51, 1), line(51, 1)], reasonCode: 'OTHER' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-13');
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 999, lines: [], reasonCode: 'OTHER' }), 404, 'RESOURCE_NOT_FOUND'); // 404 đứng trước
  });

  it('404 Order / dòng; Order của chi nhánh khác → 403 phạm vi (message chung, không mã rule)', async () => {
    expectError(await fails('desk', 'post', '/api/return-requests', { ...NEW_RETURN, orderId: 999 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('desk', 'post', '/api/return-requests', { orderId: 5, lines: [line(999, 1)], reasonCode: 'OTHER' }), 404, 'RESOURCE_NOT_FOUND');
    const other = await fails('desk', 'post', '/api/return-requests', { orderId: 8, lines: [line(81, 1)], reasonCode: 'OTHER' }); // Order quầy chi nhánh 2
    expectError(other, 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expect(other.data.message).not.toMatch(/\(BR-/);
  });

  it('body sai: kiểu → MALFORMED_REQUEST; thiếu orderId, số lượng ≤ 0, ghi chú > 300 → VALIDATION_FAILED; cả hai chạy trước vai trò', async () => {
    for (const body of [null, { ...NEW_RETURN, orderId: '5' }, { ...NEW_RETURN, lines: 'x' }, { ...NEW_RETURN, lines: [5] }, { ...NEW_RETURN, lines: [{ orderLineId: 'a', quantity: 1 }] }, { ...NEW_RETURN, reasonCode: 'FOO' }, { ...NEW_RETURN, reasonNote: 7 }]) {
      expectError(await fails('desk', 'post', '/api/return-requests', body), 400, 'MALFORMED_REQUEST');
    }
    for (const body of [{ ...NEW_RETURN, orderId: undefined }, { ...NEW_RETURN, lines: [line(51, 0)] }, { ...NEW_RETURN, lines: [{ orderLineId: 51 }] }, { ...NEW_RETURN, lines: [{ quantity: 1 }] }, { ...NEW_RETURN, reasonNote: 'x'.repeat(301) }]) {
      expectError(await fails('desk', 'post', '/api/return-requests', body), 400, 'VALIDATION_FAILED');
    }
    expectError(await fails('manager', 'post', '/api/return-requests', { ...NEW_RETURN, lines: [line(51, 0)] }), 400, 'VALIDATION_FAILED');
    expectError(await fails('manager', 'post', '/api/return-requests', NEW_RETURN), 403, 'ACCESS_DENIED');
  });
});

// ─── Xem và rút (#2–#4) ───

describe('danh sách, chi tiết, rút yêu cầu', () => {
  it('danh sách mặc định = PENDING, cũ nhất trước; lọc từng trạng thái; lễ tân và quản lý cùng thấy', async () => {
    expect(ids(await list())).toEqual([1, 5]);
    expect(ids(await list({}, 'manager'))).toEqual([1, 5]);
    const expected: Record<string, number[]> = { APPROVED: [6, 2], REJECTED: [3], CANCELLED: [4], REFUNDED: [7] };
    for (const [status, want] of Object.entries(expected)) expect(ids(await list({ status }))).toEqual(want);
    expect((await list()).content[0]).toEqual({ requestId: 1, code: expect.any(String), orderId: 5, status: 'PENDING', reasonCode: 'DEFECTIVE', refundAmount: null, createdAt: expect.any(String) });
    expect((await list({ status: 'APPROVED' })).content[0]).toMatchObject({ requestId: 6, refundAmount: 18_000 });
    expect(await list({ size: 1, page: 1 })).toMatchObject({ page: 1, size: 1, totalElements: 2, totalPages: 2 });
    expectError(await fails('desk', 'get', '/api/return-requests', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('admin', 'get', '/api/return-requests', undefined, { page: 'x' }), 400, 'MALFORMED_REQUEST');
  });

  it('chỉ thấy chi nhánh của mình: quản lý chi nhánh 2 thấy danh sách rỗng, chi tiết 403 phạm vi', async () => {
    expect((await list({}, 'manager2')).totalElements).toBe(0);
    expectError(await fails('manager2', 'get', '/api/return-requests/1'), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectError(await fails('manager2', 'get', '/api/return-requests/999'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('desk', 'get', '/api/return-requests/abc'), 400, 'MALFORMED_REQUEST');
    expect(await request(6, 'manager')).toMatchObject({ requestId: 6, status: 'APPROVED', restock: false, refundAmount: 18_000, decidedAt: expect.any(String), branchId: 1 });
    expect(await request(3)).toMatchObject({ status: 'REJECTED', decisionNote: 'Hàng đã mở bao, không nhận trả' });
  });

  it('rút (#4): chỉ người tạo, chỉ khi PENDING; yêu cầu do người khác tạo → 403 phạm vi; trạng thái khác → 409', async () => {
    expect(await data<ReturnRequest>('desk', 'post', '/api/return-requests/1/cancel')).toMatchObject({ requestId: 1, status: 'CANCELLED' });
    expectError(await fails('desk', 'post', '/api/return-requests/1/cancel'), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('desk', 'post', '/api/return-requests/5/cancel'), 403, 'ACCESS_DENIED_SCOPE_MISMATCH'); // do tài khoản #8 tạo
    for (const id of [2, 3, 4, 6, 7]) expectError(await fails('desk', 'post', `/api/return-requests/${id}/cancel`), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('desk', 'post', '/api/return-requests/999/cancel'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('manager', 'post', '/api/return-requests/1/cancel'), 403, 'ACCESS_DENIED');
    expectError(await fails('manager2', 'post', '/api/return-requests/1/cancel'), 403, 'ACCESS_DENIED');
  });
});

// ─── Duyệt và từ chối (#5, #6) ───

describe('duyệt yêu cầu trả hàng (UC69, BR-BH-14, BR-TG-12)', () => {
  it('Order quầy thu tiền mặt: APPROVED, tiền hoàn = đơn giá snapshot × số lượng, sinh hoàn tiền CASH REQUESTED, nhập lại kho', async () => {
    expect(await stock(2)).toBe(3);
    const approved = await data<ReturnRequest>('manager', 'post', '/api/return-requests/1/approve', { restock: true });
    expect(approved).toMatchObject({ status: 'APPROVED', restock: true, refundAmount: 36_000, decidedAt: expect.any(String) });
    const mine = (await refunds()).content.find((r) => r.returnRequestId === 1);
    expect(mine).toMatchObject({ method: 'CASH', amount: 36_000, status: 'REQUESTED', attempts: 0, onlineOrderId: null, completedAt: null });
    expect(await stock(2)).toBe(5); // 2 gói về kho
  });

  it('restock=false: loại bỏ, kho không đổi', async () => {
    const approved = await data<ReturnRequest>('manager', 'post', '/api/return-requests/1/approve', { restock: false });
    expect(approved).toMatchObject({ status: 'APPROVED', restock: false, refundAmount: 36_000 });
    expect(await stock(2)).toBe(3);
  });

  it('hình thức hoàn = hình thức đã thu: chuyển khoản tại quầy → TRANSFER; đơn online → GATEWAY (phí vận chuyển không hoàn)', async () => {
    await payVisitOrder();
    const made = await create({ orderId: 2, lines: [line(23, 1)], reasonCode: 'OTHER' });
    await call('manager', 'post', `/api/return-requests/${made.requestId}/approve`, { restock: true });
    expect((await refunds({ method: 'TRANSFER' })).content).toMatchObject([{ returnRequestId: made.requestId, amount: 18_000, status: 'REQUESTED' }]);
    await call('manager', 'post', '/api/return-requests/5/approve', { restock: true });
    expect((await refunds({ method: 'GATEWAY' })).content.find((r) => r.returnRequestId === 5)).toMatchObject({ amount: 450_000, method: 'GATEWAY', status: 'REQUESTED' });
  });

  it('đơn online thanh toán qua cổng: nhập lại ĐÚNG lô đã xuất lúc PAID', async () => {
    await call('other', 'put', '/api/me/cart/items/1', { quantity: 1 });
    const placed = await data<OnlineOrder>('other', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 });
    const session = await data<{ code: string }>('other', 'post', `/api/me/online-orders/${placed.onlineOrderId}/pay`);
    const body = { transactionCode: session.code, gatewayTxnId: 'VNP1', status: '00', amount: 480_000 };
    await client.post('/api/public/payment-gateway/callback', { ...body, signature: gatewaySignature(body) });
    expect(await stock(1)).toBe(11);
    await call('desk', 'post', `/api/online-orders/${placed.onlineOrderId}/start-processing`);
    await call('desk', 'post', `/api/online-orders/${placed.onlineOrderId}/ship`, { carrier: 'GHN', trackingCode: 'T1' });
    await call('desk', 'post', `/api/online-orders/${placed.onlineOrderId}/mark-delivered`);
    const made = await create({ orderId: placed.salesOrderId, lines: [line(placed.lines[0].orderLineId!, 1)], reasonCode: 'DEFECTIVE' });
    await call('manager', 'post', `/api/return-requests/${made.requestId}/approve`, { restock: true });
    expect(await stock(1)).toBe(12);
  });

  it('chỉ PENDING duyệt được (409 cho APPROVED, REJECTED, CANCELLED, REFUNDED); gọi lại → 409', async () => {
    for (const id of [2, 3, 4, 6, 7]) expectError(await fails('manager', 'post', `/api/return-requests/${id}/approve`, { restock: true }), 409, 'INVALID_STATE_TRANSITION');
    await call('manager', 'post', '/api/return-requests/1/approve', { restock: true });
    expectError(await fails('manager', 'post', '/api/return-requests/1/approve', { restock: true }), 409, 'INVALID_STATE_TRANSITION');
  });

  it('restock thiếu → VALIDATION_FAILED, sai kiểu → MALFORMED_REQUEST (trước vai trò); lễ tân 403; chi nhánh khác 403 phạm vi; id không có 404', async () => {
    expectError(await fails('manager', 'post', '/api/return-requests/1/approve', {}), 400, 'VALIDATION_FAILED');
    expectError(await fails('desk', 'post', '/api/return-requests/1/approve', {}), 400, 'VALIDATION_FAILED');
    expectError(await fails('manager', 'post', '/api/return-requests/1/approve', { restock: 'yes' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'post', '/api/return-requests/1/approve', { restock: true }), 403, 'ACCESS_DENIED');
    expectError(await fails('manager2', 'post', '/api/return-requests/1/approve', { restock: true }), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectError(await fails('manager', 'post', '/api/return-requests/999/approve', { restock: true }), 404, 'RESOURCE_NOT_FOUND');
    expect((await request(1)).status).toBe('PENDING');
  });
});

describe('từ chối yêu cầu trả hàng (BR-BH-14)', () => {
  it('REJECTED kèm lý do; không sinh hoàn tiền; gọi lại → 409', async () => {
    const rejected = await data<ReturnRequest>('manager', 'post', '/api/return-requests/1/reject', { reason: '  Hàng đã qua sử dụng ' });
    expect(rejected).toMatchObject({ status: 'REJECTED', decisionNote: 'Hàng đã qua sử dụng', decidedAt: expect.any(String), restock: null, refundAmount: null });
    expect((await refunds()).content.some((r) => r.returnRequestId === 1)).toBe(false);
    expectError(await fails('manager', 'post', '/api/return-requests/1/reject', { reason: 'x' }), 409, 'INVALID_STATE_TRANSITION');
  });

  it('thiếu lý do → 400 BR-BH-14 (sau 404 / phạm vi, TRƯỚC 409); quá 300 ký tự → VALIDATION_FAILED; sai kiểu → MALFORMED', async () => {
    for (const body of [{}, { reason: '' }, { reason: '   ' }]) expectError(await fails('manager', 'post', '/api/return-requests/1/reject', body), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-14');
    expectError(await fails('manager', 'post', '/api/return-requests/2/reject', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-14'); // APPROVED nhưng thiếu lý do → 400 trước 409
    expectError(await fails('manager', 'post', '/api/return-requests/2/reject', { reason: 'x' }), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('manager', 'post', '/api/return-requests/1/reject', { reason: 'x'.repeat(301) }), 400, 'VALIDATION_FAILED');
    expectError(await fails('desk', 'post', '/api/return-requests/1/reject', { reason: 'x'.repeat(301) }), 400, 'VALIDATION_FAILED');
    expectError(await fails('manager', 'post', '/api/return-requests/1/reject', { reason: 5 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'post', '/api/return-requests/1/reject', { reason: 'ok' }), 403, 'ACCESS_DENIED');
    expectError(await fails('manager2', 'post', '/api/return-requests/1/reject', {}), 403, 'ACCESS_DENIED_SCOPE_MISMATCH'); // phạm vi đứng trước BR
    expectError(await fails('manager', 'post', '/api/return-requests/999/reject', { reason: 'x' }), 404, 'RESOURCE_NOT_FOUND');
  });
});

// ─── Hoàn tiền (#7–#9) ───

describe('danh sách hoàn tiền (#7)', () => {
  it('mặc định = REQUESTED + FAILED, cũ nhất trước; lọc status, method; chi nhánh khác rỗng', async () => {
    const all = await refunds();
    expect(ids(all)).toEqual([3, 2]);
    expect(all.content[0]).toEqual({
      refundId: 3,
      code: expect.stringMatching(/^HT-\d{6}-\d{4}$/),
      returnRequestId: 6,
      onlineOrderId: null,
      method: 'GATEWAY',
      amount: 18_000,
      status: 'FAILED',
      attempts: 3,
      failureReason: 'Cổng từ chối hoàn tiền: tài khoản nguồn tạm khóa',
      completedAt: null,
      createdAt: expect.any(String),
    });
    expect(ids(await refunds({}, 'manager'))).toEqual([3, 2]);
    expect(ids(await refunds({ status: 'SUCCEEDED' }))).toEqual([4, 1]);
    expect(ids(await refunds({ status: 'SUCCEEDED', method: 'GATEWAY' }))).toEqual([4, 1]);
    expect((await refunds({ status: 'SUCCEEDED' })).content.find((r) => r.refundId === 1)).toMatchObject({ onlineOrderId: 10, returnRequestId: null, amount: 480_000, completedAt: expect.any(String) });
    expect(ids(await refunds({ method: 'CASH' }))).toEqual([2]);
    expect(ids(await refunds({ method: 'TRANSFER' }))).toEqual([]);
    expect((await refunds({}, 'manager2')).totalElements).toBe(0);
    expectError(await fails('desk', 'get', '/api/refunds', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'get', '/api/refunds', undefined, { method: 'MOMO' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('vet', 'get', '/api/refunds', undefined, { method: 'MOMO' }), 400, 'MALFORMED_REQUEST');
  });
});

describe('xác nhận hoàn tiền tại quầy (#8, BR-TG-13)', () => {
  const openShift = async () => (await call('desk', 'post', '/api/cashier-shifts', { afterHours: false })).data.data as CashierShift;
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(TUE_10));
  });

  it('chưa có ca OPEN của chính mình → 400 BR-TG-01', async () => {
    expectError(await fails('desk', 'post', '/api/refunds/2/confirm', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-01');
    expect((await refunds()).content.find((r) => r.refundId === 2)?.status).toBe('REQUESTED');
  });

  it('có ca OPEN: REQUESTED → SUCCEEDED, gắn vào ca (đối soát ca trừ khoản hoàn), yêu cầu trả hàng → REFUNDED', async () => {
    const shift = await openShift();
    const done = await data<Refund>('desk', 'post', '/api/refunds/2/confirm', { note: 'Đã trả tiền mặt cho khách' });
    expect(done).toMatchObject({ refundId: 2, status: 'SUCCEEDED', method: 'CASH', returnRequestId: 2, completedAt: expect.any(String) });
    expect((await request(2)).status).toBe('REFUNDED');
    expect(ids(await refunds())).toEqual([3]);
    expectError(await fails('desk', 'post', '/api/refunds/2/confirm', {}), 409, 'INVALID_STATE_TRANSITION');
    const closed = (await call('desk', 'post', `/api/cashier-shifts/${shift.shiftId}/close`, { countedCash: 0 })).data.data as CashierShift;
    expect(closed).toMatchObject({ expectedCash: -18_000, expectedTransfer: 0 });
    // ca đã chốt: không còn ca OPEN nên BR-TG-01 đứng trước 409
    expectError(await fails('desk', 'post', '/api/refunds/2/confirm', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-01');
  });

  it('body bắt buộc (yaml required): vắng → 400 MALFORMED_REQUEST, {} hợp lệ; ghi chú > 300 → VALIDATION_FAILED, sai kiểu → MALFORMED_REQUEST (trước vai trò)', async () => {
    await openShift();
    expectError(await failure(client.post('/api/refunds/2/confirm', undefined, await auth('desk'))), 400, 'MALFORMED_REQUEST');
    expectError(await fails('manager', 'post', '/api/refunds/2/confirm', undefined), 400, 'MALFORMED_REQUEST'); // sai kiểu / thiếu body chạy trước vai trò
    expect((await call('desk', 'post', '/api/refunds/2/confirm', {})).status).toBe(200);
    expectError(await fails('desk', 'post', '/api/refunds/2/confirm', { note: 'x'.repeat(301) }), 400, 'VALIDATION_FAILED');
    expectError(await fails('manager', 'post', '/api/refunds/2/confirm', { note: 'x'.repeat(301) }), 400, 'VALIDATION_FAILED');
    expectError(await fails('desk', 'post', '/api/refunds/2/confirm', { note: 5 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'post', '/api/refunds/abc/confirm', {}), 400, 'MALFORMED_REQUEST');
  });

  it('hoàn tiền qua cổng không xác nhận tay (do ST21) → 400 BR-TG-14, kể cả khi chưa mở ca; chi nhánh khác 403; id không có 404; quản lý 403', async () => {
    expectError(await fails('desk', 'post', '/api/refunds/3/confirm', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-14');
    expectError(await fails('desk', 'post', '/api/refunds/999/confirm', {}), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('manager', 'post', '/api/refunds/2/confirm', {}), 403, 'ACCESS_DENIED');
    expectError(await fails('manager2', 'post', '/api/refunds/2/confirm', {}), 403, 'ACCESS_DENIED');
  });

  it('hoàn tiền không còn REQUESTED → 409 (sau BR-TG-01)', async () => {
    await openShift();
    expectError(await fails('desk', 'post', '/api/refunds/4/confirm', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-14'); // GATEWAY SUCCEEDED
    await call('desk', 'post', '/api/refunds/2/confirm', {});
    expectError(await fails('desk', 'post', '/api/refunds/2/confirm', {}), 409, 'INVALID_STATE_TRANSITION');
  });

  it('chuyển khoản tại quầy tính vào cột chuyển khoản của ca', async () => {
    await payVisitOrder();
    const made = await create({ orderId: 2, lines: [line(23, 2)], reasonCode: 'OTHER' });
    await call('manager', 'post', `/api/return-requests/${made.requestId}/approve`, { restock: false });
    const shift = await openShift();
    const transfer = (await refunds({ method: 'TRANSFER' })).content[0];
    expect((await data<Refund>('desk', 'post', `/api/refunds/${transfer.refundId}/confirm`, {})).status).toBe('SUCCEEDED');
    const closed = (await call('desk', 'post', `/api/cashier-shifts/${shift.shiftId}/close`, { countedCash: 0 })).data.data as CashierShift;
    expect(closed).toMatchObject({ expectedCash: 0, expectedTransfer: -36_000 });
  });
});

describe('thử lại hoàn tiền qua cổng (#9, BR-TG-14) và ST21', () => {
  it('FAILED → REQUESTED (attempts về 0, bỏ lý do lỗi); ST21 hoàn xong sau 1 phút và yêu cầu trả hàng → REFUNDED', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const retried = await data<Refund>('manager', 'post', '/api/refunds/3/retry');
    expect(retried).toMatchObject({ refundId: 3, status: 'REQUESTED', attempts: 0, failureReason: null, completedAt: null });
    expect((await request(6)).status).toBe('APPROVED');
    vi.setSystemTime(Date.now() + 30_000);
    expect((await refunds()).content.find((r) => r.refundId === 3)?.status).toBe('REQUESTED');
    vi.setSystemTime(Date.now() + 40_000);
    expect((await refunds({ status: 'SUCCEEDED' })).content.find((r) => r.refundId === 3)).toMatchObject({ status: 'SUCCEEDED', attempts: 1, completedAt: expect.any(String) });
    expect((await request(6)).status).toBe('REFUNDED');
  });

  it('chỉ hoàn tiền GATEWAY (400 BR-TG-14); chỉ FAILED (409); chỉ quản lý chi nhánh của Order', async () => {
    expectError(await fails('manager', 'post', '/api/refunds/2/retry'), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-14'); // CASH
    expectError(await fails('manager', 'post', '/api/refunds/1/retry'), 409, 'INVALID_STATE_TRANSITION'); // GATEWAY SUCCEEDED
    await call('manager', 'post', '/api/refunds/3/retry');
    expectError(await fails('manager', 'post', '/api/refunds/3/retry'), 409, 'INVALID_STATE_TRANSITION'); // đã REQUESTED
    expectError(await fails('manager', 'post', '/api/refunds/999/retry'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('manager', 'post', '/api/refunds/abc/retry'), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'post', '/api/refunds/3/retry'), 403, 'ACCESS_DENIED');
    expectError(await fails('manager2', 'post', '/api/refunds/3/retry'), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
  });
});

describe('state lưu trong localStorage', () => {
  it('state cũ chỉ có {} hoặc thiếu bảng trả hàng / hoàn tiền vẫn chạy: gộp dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-commerce', '{}');
    expect(ids(await list())).toEqual([1, 5]);
    localStorage.setItem('petcare-mock-commerce', JSON.stringify({ returns: [], refunds: [] }));
    expect((await list()).totalElements).toBe(0);
    expect((await refunds()).totalElements).toBe(0);
    expect((await create(NEW_RETURN)).requestId).toBe(8); // bộ đếm id không lùi
  });

  it('state bán hàng chưa có khoản hoàn tiền của ca (cashier.mock trước T57) vẫn đối soát bình thường', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(TUE_10));
    const shiftRes = await call('desk', 'post', '/api/cashier-shifts', { afterHours: false });
    const state = JSON.parse(localStorage.getItem('petcare-mock-cashier')!);
    delete state.refunds;
    localStorage.setItem('petcare-mock-cashier', JSON.stringify(state));
    const closed = (await call('desk', 'post', `/api/cashier-shifts/${shiftRes.data.data.shiftId}/close`, { countedCash: 0 })).data.data as CashierShift;
    expect(closed).toMatchObject({ expectedCash: 0, expectedTransfer: 0 });
  });
});
