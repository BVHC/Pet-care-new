import axios, { type AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse } from '../../types';
import type { InventoryItem } from '../../types/inventory';
import type { PaymentSession, PaymentTransaction, ReconciliationItem, ReconciliationSummary } from '../../types/payment';
import type { OnlineOrder } from '../../types/shop';
import { ruleIdOf } from '../api-error';
import { authFor, failure, type Auth, type Who } from './commerce.test-client';
import { GATEWAY_CANCEL_CODE, GATEWAY_SUCCESS_CODE, gatewaySignature } from './payment.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (commerce.store.ts): giao dịch #1–#7 SUCCEEDED của đơn #1–#7, #8 FAILED của đơn #8 (PENDING_PAYMENT, còn hạn, 480.000 đ gồm phí 30.000), #9 SUCCEEDED của đơn #10.
// Đối soát: #1 hôm qua MATCHED · #2 hai ngày trước MISMATCHED (mục #1 AMOUNT_DIFF, #2 MISSING_LOCAL đã xử lý, #3 STATUS_DIFF) · #3 ba ngày trước FAILED · #4 năm ngày trước MISMATCHED (mục #4).
// Kho chi nhánh 1: hạt Royal Canin 12 túi (đơn #8 đang giữ 1). Callback là cổng GIẢ LẬP: chữ ký tính bằng gatewaySignature().

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

const pay = (orderId: number, who: Who = 'owner') => data<PaymentSession>(who, 'post', `/api/me/online-orders/${orderId}/pay`);
const txn = (id: number, who: Who = 'owner') => data<PaymentTransaction>(who, 'get', `/api/me/payment-transactions/${id}`);
const orderOf = (orderId: number, who: Who = 'owner') => data<OnlineOrder>(who, 'get', `/api/me/online-orders/${orderId}`);
const stock = async (productId: number) => (await data<InventoryItem>('desk', 'get', `/api/inventory/${productId}`)).availableQuantity;
const recons = (params?: object) => data<PageResponse<ReconciliationSummary>>('chain', 'get', '/api/payment-reconciliations', undefined, params);
const items = (reconciliationId: number, params?: object) => data<PageResponse<ReconciliationItem>>('chain', 'get', `/api/payment-reconciliations/${reconciliationId}/items`, undefined, params);

/** Body callback đã ký như cổng giả lập. */
function signed(transactionCode: string, o: { amount: number; status?: string; gatewayTxnId?: string | null }) {
  const status = o.status ?? GATEWAY_SUCCESS_CODE;
  const gatewayTxnId = o.gatewayTxnId === undefined ? 'VNP14090001' : o.gatewayTxnId;
  return { transactionCode, gatewayTxnId, status, amount: o.amount, signature: gatewaySignature({ transactionCode, gatewayTxnId, status, amount: o.amount }) };
}
const callback = (body: unknown, headers?: Record<string, string>) => client.post('/api/public/payment-gateway/callback', body, { headers });
const callbackFails = (body: unknown, headers?: Record<string, string>) => failure(callback(body, headers));

function expectError(res: { status: number; data: { errorCode: string; message: string } }, status: number, errorCode: string, rule?: string) {
  expect(res.status).toBe(status);
  expect(res.data.errorCode).toBe(errorCode);
  if (rule) expect(ruleIdOf(res.data.message)).toBe(rule);
}

// ─── Quyền ───

type Endpoint = [label: string, verb: Verb, url: string, body: unknown, allowed: Who[]];
const ENDPOINTS: Endpoint[] = [
  ['#1 bắt đầu thanh toán', 'post', '/api/me/online-orders/8/pay', undefined, ['owner']],
  ['#2 trạng thái giao dịch', 'get', '/api/me/payment-transactions/8', undefined, ['owner']],
  ['#4 danh sách đối soát', 'get', '/api/payment-reconciliations', undefined, ['chain']],
  ['#5 mục lệch', 'get', '/api/payment-reconciliations/2/items', undefined, ['chain']],
  ['#6 ghi chú đã xử lý', 'post', '/api/payment-reconciliation-items/1/resolve', { note: 'Đã đối chiếu với cổng' }, ['chain']],
];
const ROLES: Who[] = ['admin', 'chain', 'manager', 'desk', 'vet', 'caretaker', 'owner'];

describe('quyền theo 7 vai trò + không token', () => {
  describe.each(ENDPOINTS)('%s', (_label, verb, url, body, allowed) => {
    it.each(ROLES.filter((r) => !allowed.includes(r)))('%s bị 403 ACCESS_DENIED', async (who) => {
      expectError(await fails(who, verb, url, body), 403, 'ACCESS_DENIED');
    });
    it.each(allowed)('%s được phép', async (who) => {
      expect((await call(who, verb, url, body)).status).toBeLessThan(300);
    });
    it('không token → 401 UNAUTHENTICATED', async () => {
      expectError(await failure(client.request({ method: verb, url, data: body })), 401, 'UNAUTHENTICATED');
    });
  });

  it('#3 callback công khai: token (đúng, sai, của nhân viên) bị bỏ qua, không bao giờ 401 / 403', async () => {
    const body = signed('KHONG-CO', { amount: 1 });
    for (const headers of [undefined, { Authorization: 'Bearer mock-token-7' }, { Authorization: 'Bearer rác' }, (await auth('chain')).headers]) {
      expectError(await callbackFails(body, headers), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-07');
    }
    const code = (await pay(8)).code;
    expect((await callback(signed(code, { amount: 480_000 }), { Authorization: 'Bearer rác' })).data.data).toEqual({ received: true });
  });
});

// ─── Bắt đầu thanh toán (#1, #2) ───

describe('bắt đầu thanh toán (UC63, BR-TG-06)', () => {
  it('201: phiên thanh toán = tiền hàng + phí vận chuyển, hạn 15 phút, địa chỉ chuyển cổng; giao dịch PENDING', async () => {
    const before = Date.now();
    const session = await pay(8);
    expect(session).toMatchObject({ amount: 480_000, code: expect.stringMatching(/^TT-\d{6}-\d{4}$/) });
    expect(session.redirectUrl).toBe(`/payment/mock-gateway?code=${session.code}&amount=480000`);
    expect(session.redirectUrl.length).toBeLessThanOrEqual(500);
    expect(Date.parse(session.expiresAt) - before).toBeGreaterThanOrEqual(15 * 60_000 - 1000);
    expect(Date.parse(session.expiresAt) - before).toBeLessThan(15 * 60_000 + 5000);
    expect(await txn(session.transactionId)).toEqual({ transactionId: session.transactionId, onlineOrderId: 8, status: 'PENDING', amount: 480_000, expiresAt: session.expiresAt, paidAt: null, failureReason: null });
  });

  it('mỗi đơn tối đa một giao dịch PENDING: gọi lại → 409; giao dịch trước FAILED (mẫu #8) không cản', async () => {
    const first = await pay(8);
    const again = await fails('owner', 'post', '/api/me/online-orders/8/pay');
    expectError(again, 409, 'INVALID_STATE_TRANSITION');
    expect(again.data.message).toContain(first.code);
  });

  it('đơn không ở PENDING_PAYMENT (PAID, CANCELLED, SHIPPED…) → 409; đơn người khác và id không có → 404; id sai kiểu → 400', async () => {
    for (const id of [5, 9, 3, 2]) expectError(await fails('owner', 'post', `/api/me/online-orders/${id}/pay`), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('other', 'post', '/api/me/online-orders/8/pay'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'post', '/api/me/online-orders/999/pay'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'post', '/api/me/online-orders/abc/pay'), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'post', '/api/me/online-orders/abc/pay'), 400, 'MALFORMED_REQUEST');
  });

  it('đơn quá hạn thanh toán mà job ST10 chưa chạy → 400 BR-TG-06; sau 15 phút đơn đã bị hủy → 409', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const placed = await data<OnlineOrder>('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 1 });
    vi.setSystemTime(Date.parse(placed.paymentDueAt) + 60_000);
    expectError(await fails('owner', 'post', `/api/me/online-orders/${placed.onlineOrderId}/pay`), 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-06');
    vi.setSystemTime(Date.parse(placed.paymentDueAt) + 16 * 60_000);
    expectError(await fails('owner', 'post', `/api/me/online-orders/${placed.onlineOrderId}/pay`), 409, 'INVALID_STATE_TRANSITION');
  });

  it('giao dịch hết hạn sau 15 phút (ST11) → EXPIRED, khách thử lại bằng giao dịch mới', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const first = await pay(8);
    vi.setSystemTime(Date.parse(first.expiresAt) + 1000);
    expect(await txn(first.transactionId)).toMatchObject({ status: 'EXPIRED', paidAt: null });
    const second = await pay(8);
    expect(second.transactionId).not.toBe(first.transactionId);
    expect(second.code).not.toBe(first.code);
    expect((await txn(second.transactionId)).status).toBe('PENDING');
  });

  describe('trạng thái giao dịch của tôi (#2)', () => {
    it('giao dịch mẫu FAILED có lý do; giao dịch của đơn người khác và id không có cùng 404', async () => {
      expect(await txn(8)).toMatchObject({ onlineOrderId: 8, status: 'FAILED', failureReason: 'Khách hủy giao dịch tại cổng', paidAt: null });
      expect(await txn(1)).toMatchObject({ status: 'SUCCEEDED', paidAt: expect.any(String), failureReason: null });
      expectError(await fails('other', 'get', '/api/me/payment-transactions/1'), 404, 'RESOURCE_NOT_FOUND');
      expectError(await fails('owner', 'get', '/api/me/payment-transactions/999'), 404, 'RESOURCE_NOT_FOUND');
      expectError(await fails('owner', 'get', '/api/me/payment-transactions/abc'), 400, 'MALFORMED_REQUEST');
      expect(await txn(4, 'other')).toMatchObject({ onlineOrderId: 4, status: 'SUCCEEDED' });
    });
  });
});

// ─── Callback của cổng (#3) ───

describe('callback của cổng (BR-TG-07, 08, 09)', () => {
  it('thành công: giao dịch SUCCEEDED, đơn PAID, phần giữ chuyển thành xuất kho FEFO', async () => {
    expect(await stock(1)).toBe(12);
    const session = await pay(8);
    const res = await callback(signed(session.code, { amount: 480_000 }));
    expect(res.status).toBe(200);
    expect(res.data).toMatchObject({ data: { received: true }, message: 'success', code: 200 });
    expect(await txn(session.transactionId)).toMatchObject({ status: 'SUCCEEDED', paidAt: expect.any(String), failureReason: null });
    const order = await orderOf(8);
    expect(order).toMatchObject({ status: 'PAID', paidAt: expect.any(String) });
    expect(Date.parse(order.paidAt!)).toBe(Date.parse((await txn(session.transactionId)).paidAt!));
    expect(await stock(1)).toBe(11); // xuất 1 túi từ lô
    // phần giữ đã tiêu thụ, không trừ hai lần: 11 túi vẫn đặt được.
    await call('other', 'put', '/api/me/cart/items/1', { quantity: 11 });
    expect(await data<unknown[]>('other', 'get', '/api/me/online-orders/fulfillment-branches')).toHaveLength(1);
    expect(await data('desk', 'get', '/api/online-orders', undefined, { status: 'PAID' })).toMatchObject({ totalElements: 2 });
  });

  it('idempotent theo mã tham chiếu: gọi lại cùng kết quả vẫn 200 và không đổi gì; kết quả khác sau khi đã SUCCEEDED cũng không đổi', async () => {
    const session = await pay(8);
    await callback(signed(session.code, { amount: 480_000 }));
    const paidAt = (await txn(session.transactionId)).paidAt;
    expect((await callback(signed(session.code, { amount: 480_000 }))).data.data).toEqual({ received: true });
    expect((await callback(signed(session.code, { amount: 480_000, status: GATEWAY_CANCEL_CODE }))).status).toBe(200);
    expect((await callback(signed(session.code, { amount: 1 }))).status).toBe(200);
    expect(await txn(session.transactionId)).toMatchObject({ status: 'SUCCEEDED', paidAt });
    expect(await stock(1)).toBe(11);
    expect((await orderOf(8)).status).toBe('PAID');
  });

  it('cổng báo thất bại / khách hủy tại cổng: giao dịch FAILED có lý do, đơn vẫn PENDING_PAYMENT, thử lại bằng giao dịch mới', async () => {
    const first = await pay(8);
    await callback(signed(first.code, { amount: 480_000, status: GATEWAY_CANCEL_CODE }));
    expect(await txn(first.transactionId)).toMatchObject({ status: 'FAILED', failureReason: 'Khách hủy giao dịch tại cổng' });
    expect((await orderOf(8)).status).toBe('PENDING_PAYMENT');
    expect(await stock(1)).toBe(12);
    const second = await pay(8);
    await callback(signed(second.code, { amount: 480_000, status: '51' }));
    expect((await txn(second.transactionId)).failureReason).toBe('Cổng báo giao dịch không thành công (mã 51)');
    const third = await pay(8);
    await callback(signed(third.code, { amount: 480_000 }));
    expect((await orderOf(8)).status).toBe('PAID');
  });

  it('số tiền lệch (BR-TG-08): giao dịch FAILED, KHÔNG đánh dấu đơn đã trả, thêm mục AMOUNT_DIFF vào đối soát; gọi lại không thêm mục', async () => {
    const session = await pay(8);
    const body = signed(session.code, { amount: 450_000 });
    expect((await callback(body)).data.data).toEqual({ received: true });
    expect(await txn(session.transactionId)).toMatchObject({ status: 'FAILED', paidAt: null, failureReason: expect.stringContaining('không khớp') });
    expect((await orderOf(8)).status).toBe('PENDING_PAYMENT');
    expect(await stock(1)).toBe(12);

    const running = (await recons({ status: 'RUNNING' })).content;
    expect(running).toHaveLength(1);
    expect(running[0]).toMatchObject({ mismatchCount: 1, finishedAt: null });
    const [item] = (await items(running[0].reconciliationId)).content;
    expect(item).toMatchObject({ kind: 'AMOUNT_DIFF', transactionId: session.transactionId, ourAmount: 480_000, gatewayAmount: 450_000, resolved: false });
    await callback(body);
    expect((await items(running[0].reconciliationId)).totalElements).toBe(1);
  });

  it('chữ ký sai → 400 BR-TG-07 và không ghi gì; mã tham chiếu không có → 400 BR-TG-07', async () => {
    const session = await pay(8);
    const good = signed(session.code, { amount: 480_000 });
    for (const bad of [{ ...good, signature: 'deadbeef' }, { ...good, amount: 1 }, { ...good, status: 'XX' }, { ...good, gatewayTxnId: 'KHAC' }]) {
      const res = await callbackFails(bad);
      expectError(res, 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-07');
      expect(res.data.message).toContain('Chữ ký');
    }
    expect((await txn(session.transactionId)).status).toBe('PENDING');
    expect((await orderOf(8)).status).toBe('PENDING_PAYMENT');
    const unknown = await callbackFails(signed('TT-000000-9999', { amount: 1 }));
    expectError(unknown, 400, 'BUSINESS_RULE_VIOLATION', 'BR-TG-07');
    expect(unknown.data.message).toContain('không tồn tại');
    // chữ ký sai với mã không có: kiểm chữ ký trước nên không lộ mã nào có thật
    expect((await callbackFails({ ...signed('TT-000000-9999', { amount: 1 }), signature: 'x' })).data.message).toContain('Chữ ký');
  });

  it('body sai: không phải object / sai kiểu → MALFORMED_REQUEST; thiếu / quá dài / âm → VALIDATION_FAILED (trước kiểm chữ ký)', async () => {
    expectError(await callbackFails('không phải JSON'), 400, 'MALFORMED_REQUEST');
    expectError(await callbackFails(null), 400, 'MALFORMED_REQUEST');
    expectError(await callbackFails({ ...signed('X', { amount: 1 }), amount: '480000' }), 400, 'MALFORMED_REQUEST');
    expectError(await callbackFails({ ...signed('X', { amount: 1 }), payload: [] }), 400, 'MALFORMED_REQUEST');
    for (const bad of [{}, { ...signed('X', { amount: 1 }), transactionCode: ' ' }, { ...signed('X', { amount: 1 }), signature: '' }, { ...signed('X', { amount: 1 }), status: undefined }, { ...signed('X', { amount: 1 }), amount: undefined }, { ...signed('X', { amount: 1 }), amount: -5 }, { ...signed('X', { amount: 1 }), amount: 1.5 }, signed('x'.repeat(41), { amount: 1 }), signed('X', { amount: 1, gatewayTxnId: 'g'.repeat(101) })]) {
      expectError(await callbackFails(bad), 400, 'VALIDATION_FAILED');
    }
    // gatewayTxnId và payload không bắt buộc
    const code = (await pay(8)).code;
    const withoutRef: Record<string, unknown> = signed(code, { amount: 480_000, gatewayTxnId: null });
    delete withoutRef.gatewayTxnId;
    expect((await callback({ ...withoutRef, payload: { vnp_BankCode: 'NCB' } })).status).toBe(200);
  });

  it('giao dịch đã EXPIRED (ST11) hoặc đơn đã bị hủy (CANCELLED): callback thành công về sau vẫn 200 nhưng không đổi gì', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const first = await pay(8);
    vi.setSystemTime(Date.parse(first.expiresAt) + 60_000);
    expect((await callback(signed(first.code, { amount: 480_000 }))).status).toBe(200);
    expect(await txn(first.transactionId)).toMatchObject({ status: 'EXPIRED', paidAt: null });
    expect((await orderOf(8)).status).toBe('PENDING_PAYMENT');

    const second = await pay(8);
    await call('owner', 'post', '/api/me/online-orders/8/cancel', { reason: 'Đổi ý' });
    expect((await txn(second.transactionId)).status).toBe('CANCELLED'); // Giao dịch#5
    expect((await callback(signed(second.code, { amount: 480_000 }))).status).toBe(200);
    expect(await orderOf(8)).toMatchObject({ status: 'CANCELLED', paidAt: null });
    expect(await stock(1)).toBe(12);
  });

  it('chuỗi trọn vẹn: đặt → thanh toán → callback → xử lý → bàn giao → đã giao → khách xác nhận', async () => {
    await data('other', 'delete', '/api/me/cart');
    await call('other', 'put', '/api/me/cart/items/1', { quantity: 1 });
    const placed = await data<OnlineOrder>('other', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 });
    const session = await pay(placed.onlineOrderId, 'other');
    expect(session.amount).toBe(480_000);
    await callback(signed(session.code, { amount: 480_000 }));
    expect(await stock(1)).toBe(11);
    expect(await data('desk', 'get', '/api/online-orders', undefined, { status: 'PAID' })).toMatchObject({ totalElements: 2 });
    await call('desk', 'post', `/api/online-orders/${placed.onlineOrderId}/start-processing`);
    await call('desk', 'post', `/api/online-orders/${placed.onlineOrderId}/ship`, { carrier: 'GHN', trackingCode: 'G1' });
    await call('desk', 'post', `/api/online-orders/${placed.onlineOrderId}/mark-delivered`);
    expect((await data<OnlineOrder>('other', 'post', `/api/me/online-orders/${placed.onlineOrderId}/confirm-received`)).status).toBe('COMPLETED');
  });

  it('hủy đơn đã thanh toán hoàn hàng về lô gốc và sinh hoàn tiền; ST21 hoàn xong sau 1 phút', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const session = await pay(8);
    await callback(signed(session.code, { amount: 480_000 }));
    expect(await stock(1)).toBe(11);
    await call('owner', 'post', '/api/me/online-orders/8/cancel', { reason: 'Đổi ý sau khi trả tiền' });
    expect(await stock(1)).toBe(12); // về đúng lô đã xuất
    const refund = (await data<PageResponse<{ onlineOrderId: number | null; status: string; amount: number }>>('desk', 'get', '/api/refunds')).content.find((r) => r.onlineOrderId === 8);
    expect(refund).toMatchObject({ status: 'REQUESTED', amount: 480_000 });
    vi.setSystemTime(Date.now() + 61_000);
    const done = (await data<PageResponse<{ onlineOrderId: number | null; status: string; attempts: number; completedAt: string | null }>>('desk', 'get', '/api/refunds', undefined, { status: 'SUCCEEDED' })).content.find((r) => r.onlineOrderId === 8);
    expect(done).toMatchObject({ status: 'SUCCEEDED', attempts: 1, completedAt: expect.any(String) });
  });
});

// ─── Đối soát (#4–#6) ───

describe('đối soát cổng (ST14, BR-TG-10)', () => {
  it('danh sách: mới nhất trước (reconDate giảm dần); lọc status; phân trang', async () => {
    const all = await recons();
    expect(all).toMatchObject({ page: 0, size: 20, totalElements: 4, totalPages: 1 });
    expect(all.content.map((r) => [r.reconciliationId, r.status])).toEqual([[1, 'MATCHED'], [2, 'MISMATCHED'], [3, 'FAILED'], [4, 'MISMATCHED']]);
    const days = all.content.map((r) => r.reconDate);
    expect(days).toEqual([...days].sort().reverse());
    expect(all.content[1]).toMatchObject({ txnCount: 9, mismatchCount: 3, finishedAt: expect.any(String) });
    expect((await recons({ status: 'MISMATCHED' })).content.map((r) => r.reconciliationId)).toEqual([2, 4]);
    expect((await recons({ status: 'RUNNING' })).totalElements).toBe(0);
    expect(await recons({ size: 3, page: 1 })).toMatchObject({ page: 1, size: 3, totalElements: 4, totalPages: 2 });
    expect(await recons({ size: 0, page: -1 })).toMatchObject({ page: 0, size: 1 });
    expectError(await fails('chain', 'get', '/api/payment-reconciliations', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('chain', 'get', '/api/payment-reconciliations', undefined, { page: 'x' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('manager', 'get', '/api/payment-reconciliations', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST'); // sai kiểu chạy trước vai trò
  });

  it('mục lệch của một phiên: lọc resolved; phiên không có → 404; sai kiểu → 400', async () => {
    const all = await items(2);
    expect(all.content.map((i) => [i.itemId, i.kind, i.resolved])).toEqual([[1, 'AMOUNT_DIFF', false], [2, 'MISSING_LOCAL', true], [3, 'STATUS_DIFF', false]]);
    expect(all.content[0]).toMatchObject({ transactionId: 3, ourAmount: 138_000, gatewayAmount: 108_000, gatewayRef: expect.any(String) });
    expect(all.content[1]).toMatchObject({ transactionId: null, gatewayRef: '14020771', ourAmount: null, gatewayAmount: 270_000, resolvedNote: expect.any(String), resolvedAt: expect.any(String) });
    expect((await items(2, { resolved: false })).content.map((i) => i.itemId)).toEqual([1, 3]);
    expect((await items(2, { resolved: true })).content.map((i) => i.itemId)).toEqual([2]);
    expect((await items(1)).totalElements).toBe(0);
    expect((await items(4)).content[0]).toMatchObject({ kind: 'MISSING_GATEWAY', gatewayAmount: null });
    expectError(await fails('chain', 'get', '/api/payment-reconciliations/999/items'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('chain', 'get', '/api/payment-reconciliations/abc/items'), 400, 'MALFORMED_REQUEST');
    expectError(await fails('chain', 'get', '/api/payment-reconciliations/2/items', undefined, { resolved: 'maybe' }), 400, 'MALFORMED_REQUEST');
  });

  it('ghi chú đã xử lý: chỉ ghi chú, không sửa giao dịch hay đơn; gọi lại → 409', async () => {
    const done = await data<ReconciliationItem>('chain', 'post', '/api/payment-reconciliation-items/1/resolve', { note: '  Cổng đã xác nhận lệch 30.000 đ phí  ' });
    expect(done).toMatchObject({ itemId: 1, resolved: true, resolvedNote: 'Cổng đã xác nhận lệch 30.000 đ phí', resolvedAt: expect.any(String) });
    expect((await items(2, { resolved: false })).content.map((i) => i.itemId)).toEqual([3]);
    expectError(await fails('chain', 'post', '/api/payment-reconciliation-items/1/resolve', { note: 'lần hai' }), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('chain', 'post', '/api/payment-reconciliation-items/2/resolve', { note: 'đã xử lý từ trước' }), 409, 'INVALID_STATE_TRANSITION');
    expect((await items(2, { resolved: true })).content.find((i) => i.itemId === 1)?.resolvedNote).toBe('Cổng đã xác nhận lệch 30.000 đ phí');
    expect(await txn(3)).toMatchObject({ status: 'SUCCEEDED' }); // vẫn là giao dịch của đơn #3
    expect((await orderOf(3)).status).toBe('SHIPPED');
  });

  it('note thiếu / trống / quá 300 ký tự → 400 VALIDATION_FAILED, sai kiểu → MALFORMED_REQUEST (đều trước vai trò); mục không có → 404', async () => {
    for (const body of [{}, { note: '' }, { note: '   ' }, { note: 'x'.repeat(301) }]) {
      expectError(await fails('chain', 'post', '/api/payment-reconciliation-items/1/resolve', body), 400, 'VALIDATION_FAILED');
      expectError(await fails('manager', 'post', '/api/payment-reconciliation-items/1/resolve', body), 400, 'VALIDATION_FAILED');
    }
    expectError(await fails('chain', 'post', '/api/payment-reconciliation-items/1/resolve', { note: 5 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('chain', 'post', '/api/payment-reconciliation-items/999/resolve', { note: 'x' }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('chain', 'post', '/api/payment-reconciliation-items/abc/resolve', { note: 'x' }), 400, 'MALFORMED_REQUEST');
    expect((await items(2, { resolved: false })).totalElements).toBe(2);
  });
});

describe('state lưu trong localStorage', () => {
  it('state cũ chỉ có {} hoặc thiếu bảng giao dịch / đối soát vẫn chạy: gộp dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-commerce', '{}');
    expect((await recons()).totalElements).toBe(4);
    localStorage.setItem('petcare-mock-commerce', JSON.stringify({ txns: [], recons: [] }));
    expect((await recons()).totalElements).toBe(0);
    expectError(await fails('owner', 'get', '/api/me/payment-transactions/8'), 404, 'RESOURCE_NOT_FOUND');
    const session = await pay(8); // bộ đếm id không lùi dù bảng giao dịch rỗng
    expect((await txn(session.transactionId)).status).toBe('PENDING');
  });
});
