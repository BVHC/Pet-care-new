import axios, { type AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse } from '../../types';
import type { Cart, OnlineOrder, OnlineOrderSummary } from '../../types/shop';
import { ruleIdOf } from '../api-error';
import { authFor, failure, type Auth, type Who } from './commerce.test-client';
import { onlineRevenueRows, openOnlineOrderCount, registerShippingFeeSource } from './shop.mock';
import { pendingRefundCount } from './returns.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (commerce.store.ts, tương đối với lúc chạy): khach@ (khách #1) có 8 đơn online #1,2,3,5,6,8,9,10; khachmoi@ (khách #2) có #4, #7.
//  #1 COMPLETED 21 ngày trước · #2 DELIVERED hôm qua · #3 SHIPPED · #4 PROCESSING · #5 PAID · #6 DELIVERY_FAILED đã giao lại 2 lần · #7 DELIVERY_FAILED 0 lần ·
//  #8 PENDING_PAYMENT còn hạn (giữ 1 túi hạt) · #9 CANCELLED chưa trả tiền · #10 CANCELLED sau PAID. Giỏ khach@ = 1 túi hạt (450.000) + 2 gói pate (18.000) = 486.000.
//  Kho chi nhánh 1: hạt Royal Canin 12 túi (đơn #8 đang giữ 1), Pate Whiskas 3 gói. Chi nhánh 1 là ACTIVE duy nhất.
// Ba bảng route thương mại chưa nằm trong ROUTES của mock-api.ts (luồng chính đăng ký sau), nên test cài qua installCommerceMockApi.

let client: AxiosInstance;
let tokens: Map<Who, Auth>;
beforeEach(() => {
  client = axios.create();
  installMockApi(client);
  tokens = new Map();
});
afterEach(() => {
  vi.useRealTimers();
  registerShippingFeeSource(() => ({ baseFee: 30_000, freeThreshold: 500_000 }));
});

type Verb = 'get' | 'post' | 'put' | 'patch' | 'delete';
async function auth(who: Who) {
  if (!tokens.has(who)) tokens.set(who, await authFor(client, who));
  return tokens.get(who)!;
}
async function call(who: Who, method: Verb, url: string, body?: unknown, params?: object) {
  return client.request({ method, url, data: body, params, ...(await auth(who)) });
}
const fails = (who: Who, method: Verb, url: string, body?: unknown, params?: object) => failure(call(who, method, url, body, params));
const data = async <T>(who: Who, method: Verb, url: string, body?: unknown, params?: object) => (await call(who, method, url, body, params)).data.data as T;
const orderOf = (who: Who, onlineOrderId: number) => data<OnlineOrder>(who, 'get', `/api/me/online-orders/${onlineOrderId}`);
const staffOrder = (who: Who, onlineOrderId: number) => data<OnlineOrder>(who, 'get', `/api/online-orders/${onlineOrderId}`);
const ids = (page: PageResponse<OnlineOrderSummary>) => page.content.map((o) => o.onlineOrderId);
const SHIP = { carrier: 'GHN', trackingCode: 'GHN123456' };

function expectError(res: { status: number; data: { errorCode: string; message: string } }, status: number, errorCode: string, rule?: string) {
  expect(res.status).toBe(status);
  expect(res.data.errorCode).toBe(errorCode);
  if (rule) expect(ruleIdOf(res.data.message)).toBe(rule);
}

/** Thêm n sản phẩm GOODS mới vào danh mục (SUPER_MANAGER) để giỏ có đủ 30 dòng; trả mã sản phẩm. */
async function addGoods(n: number, from = 0): Promise<number[]> {
  const created: number[] = [];
  for (let i = from; i < from + n; i++) {
    const product = await data<{ productId: number }>('chain', 'post', '/api/products', {
      categoryId: 4,
      sku: `TEST-GOODS-${i}`,
      name: `Phụ kiện thử ${i}`,
      productType: 'GOODS',
      isPrescription: false,
      tracksExpiry: false,
      unit: 'cái',
      price: 10_000,
    });
    created.push(product.productId);
  }
  return created;
}

// ─── Quyền: mọi endpoint × mọi vai trò (dòng "Quyền:" của yaml) ───

type Endpoint = [label: string, verb: Verb, url: string, body: unknown, allowed: Who[]];
const OWNER: Who[] = ['owner'];
const READ: Who[] = ['desk', 'manager'];
const WRITE: Who[] = ['desk'];
const ENDPOINTS: Endpoint[] = [
  ['#1 xem giỏ', 'get', '/api/me/cart', undefined, OWNER],
  ['#2 đặt số lượng', 'put', '/api/me/cart/items/1', { quantity: 1 }, OWNER],
  ['#3 xóa sản phẩm khỏi giỏ', 'delete', '/api/me/cart/items/1', undefined, OWNER],
  ['#4 xóa giỏ', 'delete', '/api/me/cart', undefined, OWNER],
  ['#5 chi nhánh xử lý được', 'get', '/api/me/online-orders/fulfillment-branches', undefined, OWNER],
  ['#6 báo giá', 'get', '/api/me/online-orders/quote?branchId=1&addressId=1', undefined, OWNER],
  ['#7 đặt hàng', 'post', '/api/me/online-orders', { branchId: 1, addressId: 1 }, OWNER],
  ['#8 đơn của tôi', 'get', '/api/me/online-orders', undefined, OWNER],
  ['#9 chi tiết đơn của tôi', 'get', '/api/me/online-orders/1', undefined, OWNER],
  ['#10 hủy đơn', 'post', '/api/me/online-orders/8/cancel', { reason: 'Đổi ý' }, OWNER],
  ['#11 xác nhận đã nhận', 'post', '/api/me/online-orders/2/confirm-received', undefined, OWNER],
  ['#12 danh sách của chi nhánh', 'get', '/api/online-orders', undefined, READ],
  ['#13 chi tiết (nhân viên)', 'get', '/api/online-orders/5', undefined, READ],
  ['#14 bắt đầu xử lý', 'post', '/api/online-orders/5/start-processing', undefined, WRITE],
  ['#15 bàn giao', 'post', '/api/online-orders/4/ship', SHIP, WRITE],
  ['#16 đã giao', 'post', '/api/online-orders/3/mark-delivered', undefined, WRITE],
  ['#17 giao thất bại', 'post', '/api/online-orders/3/fail-delivery', { reason: 'Vắng nhà' }, WRITE],
  ['#18 giao lại', 'post', '/api/online-orders/7/redeliver', SHIP, WRITE],
  ['#19 hủy sau thất bại', 'post', '/api/online-orders/7/cancel-failed', { reason: 'Khách không nhận' }, WRITE],
];
const ROLES: Who[] = ['admin', 'chain', 'manager', 'desk', 'vet', 'caretaker', 'owner'];

describe('quyền theo 7 vai trò + không token', () => {
  it('đủ 19 endpoint của shop-v1', () => {
    expect(ENDPOINTS).toHaveLength(19);
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

// ─── Giỏ hàng (#1–4) ───

describe('giỏ hàng (UC61, BR-BH-07)', () => {
  it('giỏ mẫu của khach@: tên, đơn vị, giá hiện tại, còn hàng, tạm tính', async () => {
    const cart = await data<Cart>('owner', 'get', '/api/me/cart');
    expect(cart.subtotal).toBe(486_000);
    expect(cart.items).toEqual([
      { productId: 1, name: 'Hạt Royal Canin Mini Adult 2kg', unit: 'túi', unitPrice: 450_000, quantity: 1, imageUrl: '/imgs/prod1.jpg', available: true },
      { productId: 2, name: 'Pate Whiskas 85g', unit: 'gói', unitPrice: 18_000, quantity: 2, imageUrl: '/imgs/prod6.jpg', available: true },
    ]);
  });

  it('mỗi khách một giỏ riêng: khachmoi@ giỏ rỗng', async () => {
    expect(await data<Cart>('other', 'get', '/api/me/cart')).toEqual({ items: [], subtotal: 0 });
  });

  it('PUT thêm mới rồi đặt lại số lượng (idempotent, không thêm dòng)', async () => {
    const added = await data<Cart>('other', 'put', '/api/me/cart/items/2', { quantity: 3 });
    expect(added.items).toHaveLength(1);
    expect(added.subtotal).toBe(54_000);
    const again = await data<Cart>('other', 'put', '/api/me/cart/items/2', { quantity: 3 });
    expect(again).toEqual(added);
    expect((await data<Cart>('other', 'put', '/api/me/cart/items/2', { quantity: 99 })).items).toMatchObject([{ productId: 2, quantity: 99 }]);
  });

  it('số lượng ngoài 1–99 → 400 BR-BH-07; thiếu → VALIDATION_FAILED; sai kiểu → MALFORMED_REQUEST', async () => {
    for (const quantity of [0, 100, -1]) expectError(await fails('owner', 'put', '/api/me/cart/items/2', { quantity }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-07');
    expectError(await fails('owner', 'put', '/api/me/cart/items/2', {}), 400, 'VALIDATION_FAILED');
    expectError(await fails('owner', 'put', '/api/me/cart/items/2', { quantity: null }), 400, 'VALIDATION_FAILED');
    for (const quantity of [1.5, '2', true]) expectError(await fails('owner', 'put', '/api/me/cart/items/2', { quantity }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('owner', 'put', '/api/me/cart/items/abc', { quantity: 1 }), 400, 'MALFORMED_REQUEST');
    expect((await data<Cart>('owner', 'get', '/api/me/cart')).items[1].quantity).toBe(2);
  });

  it('chỉ nhận GOODS không kê đơn đang kinh doanh: thuốc kê đơn, thuốc thường, vaccine → 400 BR-BH-07; ngừng kinh doanh / không có → 404', async () => {
    for (const productId of [3, 4, 5, 6]) expectError(await fails('owner', 'put', `/api/me/cart/items/${productId}`, { quantity: 1 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-07');
    expect((await fails('owner', 'put', '/api/me/cart/items/3', { quantity: 1 })).data.message).toContain('kê đơn');
    expect((await fails('owner', 'put', '/api/me/cart/items/5', { quantity: 1 })).data.message).toContain('vaccine');
    expectError(await fails('owner', 'put', '/api/me/cart/items/7', { quantity: 1 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'put', '/api/me/cart/items/999', { quantity: 1 }), 404, 'RESOURCE_NOT_FOUND');
    // 404 sản phẩm đứng trước 400 số lượng (tồn tại → guard nghiệp vụ).
    expectError(await fails('owner', 'put', '/api/me/cart/items/999', { quantity: 0 }), 404, 'RESOURCE_NOT_FOUND');
  });

  it('tối đa 30 dòng: dòng thứ 31 → 400 BR-BH-07, nhưng sửa số lượng dòng có sẵn vẫn được', async () => {
    const goods = await addGoods(28); // giỏ mẫu đã có 2 dòng
    for (const productId of goods) await call('owner', 'put', `/api/me/cart/items/${productId}`, { quantity: 1 });
    expect((await data<Cart>('owner', 'get', '/api/me/cart')).items).toHaveLength(30);
    const [thirtyFirst] = await addGoods(1, 100);
    expectError(await fails('owner', 'put', `/api/me/cart/items/${thirtyFirst}`, { quantity: 1 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-07');
    expect((await data<Cart>('owner', 'put', `/api/me/cart/items/${goods[0]}`, { quantity: 5 })).items).toHaveLength(30);
    expect((await data<Cart>('owner', 'delete', `/api/me/cart/items/${goods[1]}`)).items).toHaveLength(29);
    expect((await data<Cart>('owner', 'put', `/api/me/cart/items/${thirtyFirst}`, { quantity: 1 })).items).toHaveLength(30);
  });

  it('xem giỏ tự bỏ sản phẩm đã ngừng kinh doanh (dòng vẫn nằm trong bảng, bật lại thì hiện lại)', async () => {
    await call('chain', 'patch', '/api/products/2', { isActive: false });
    const cart = await data<Cart>('owner', 'get', '/api/me/cart');
    expect(cart.items.map((i) => i.productId)).toEqual([1]);
    expect(cart.subtotal).toBe(450_000);
    await call('chain', 'patch', '/api/products/2', { isActive: true });
    expect((await data<Cart>('owner', 'get', '/api/me/cart')).items).toHaveLength(2);
  });

  it('available=false khi sản phẩm hết hàng toàn chuỗi (chưa có dòng tồn nào)', async () => {
    const [fresh] = await addGoods(1);
    const cart = await data<Cart>('owner', 'put', `/api/me/cart/items/${fresh}`, { quantity: 1 });
    expect(cart.items.map((i) => [i.productId, i.available])).toEqual([[1, true], [2, true], [fresh, false]]);
    expect(await data('owner', 'get', '/api/me/online-orders/fulfillment-branches')).toEqual([]);
  });

  it('DELETE sản phẩm: xóa dòng, không có trong giỏ vẫn trả giỏ hiện tại; DELETE giỏ → 204', async () => {
    expect((await data<Cart>('owner', 'delete', '/api/me/cart/items/1')).items.map((i) => i.productId)).toEqual([2]);
    expect((await data<Cart>('owner', 'delete', '/api/me/cart/items/1')).items.map((i) => i.productId)).toEqual([2]);
    expect((await data<Cart>('owner', 'delete', '/api/me/cart/items/999')).items).toHaveLength(1);
    expectError(await fails('owner', 'delete', '/api/me/cart/items/abc'), 400, 'MALFORMED_REQUEST');
    const cleared = await call('owner', 'delete', '/api/me/cart');
    expect(cleared.status).toBe(204);
    expect(await data<Cart>('owner', 'get', '/api/me/cart')).toEqual({ items: [], subtotal: 0 });
    expect((await call('owner', 'delete', '/api/me/cart')).status).toBe(204);
  });
});

// ─── Chi nhánh xử lý, báo giá, đặt hàng (#5–7) ───

describe('chi nhánh xử lý được (#5)', () => {
  it('chỉ chi nhánh ACTIVE còn đủ hàng cho mọi dòng; giỏ rỗng → mảng rỗng', async () => {
    const branches = await data<{ branchId: number; name: string; address: string }[]>('owner', 'get', '/api/me/online-orders/fulfillment-branches');
    expect(branches).toHaveLength(1);
    expect(branches[0]).toMatchObject({ branchId: 1, name: expect.any(String), address: expect.any(String) });
    expect(await data('other', 'get', '/api/me/online-orders/fulfillment-branches')).toEqual([]);
  });

  it('tồn khả dụng đã trừ phần giữ (đơn #8 giữ 1 túi hạt): 11 túi đủ, 12 túi không', async () => {
    await call('owner', 'put', '/api/me/cart/items/1', { quantity: 11 });
    expect(await data<unknown[]>('owner', 'get', '/api/me/online-orders/fulfillment-branches')).toHaveLength(1);
    await call('owner', 'put', '/api/me/cart/items/1', { quantity: 12 });
    expect(await data('owner', 'get', '/api/me/online-orders/fulfillment-branches')).toEqual([]);
  });

  it('thiếu hàng một dòng thì chi nhánh bị loại (pate chỉ còn 3)', async () => {
    await call('owner', 'put', '/api/me/cart/items/2', { quantity: 4 });
    expect(await data('owner', 'get', '/api/me/online-orders/fulfillment-branches')).toEqual([]);
    expect((await data<Cart>('owner', 'get', '/api/me/cart')).items[1].available).toBe(true);
  });
});

describe('báo giá (#6, ST09)', () => {
  const quote = (who: Who, params: object) => data<Record<string, unknown>>(who, 'get', '/api/me/online-orders/quote', undefined, params);

  it('tiền hàng + phí cố định 30.000 khi dưới ngưỡng 500.000', async () => {
    expect(await quote('owner', { branchId: 1, addressId: 1 })).toEqual({ branchId: 1, addressId: 1, subtotal: 486_000, shippingFee: 30_000, freeShippingApplied: false, total: 516_000 });
  });

  it('miễn phí vận chuyển khi tiền hàng ≥ ngưỡng', async () => {
    await call('owner', 'put', '/api/me/cart/items/1', { quantity: 2 }); // 900.000 + 36.000
    expect(await quote('owner', { branchId: 1, addressId: 1 })).toMatchObject({ subtotal: 936_000, shippingFee: 0, freeShippingApplied: true, total: 936_000 });
    await call('owner', 'put', '/api/me/cart/items/1', { quantity: 1 });
    await call('owner', 'put', '/api/me/cart/items/2', { quantity: 3 }); // 450.000 + 54.000 = 504.000, đúng ngưỡng trở lên
    expect(await quote('owner', { branchId: 1, addressId: 1 })).toMatchObject({ subtotal: 504_000, shippingFee: 0, freeShippingApplied: true });
  });

  it('nguồn phí đăng ký (fees.mock) thay mặc định; ngưỡng 0 = không miễn phí', async () => {
    registerShippingFeeSource(() => ({ baseFee: 15_000, freeThreshold: 0 }));
    await call('owner', 'put', '/api/me/cart/items/1', { quantity: 5 });
    expect(await quote('owner', { branchId: 1, addressId: 1 })).toMatchObject({ subtotal: 2_286_000, shippingFee: 15_000, freeShippingApplied: false });
  });

  it('không ghi gì và không giữ hàng', async () => {
    await quote('owner', { branchId: 1, addressId: 1 });
    expect((await data<PageResponse<OnlineOrderSummary>>('owner', 'get', '/api/me/online-orders')).totalElements).toBe(8);
    expect((await data<Cart>('owner', 'get', '/api/me/cart')).items).toHaveLength(2);
  });

  it('thiếu / sai kiểu tham số → 400 MALFORMED_REQUEST (trước cả vai trò)', async () => {
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { addressId: 1 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 'x', addressId: 1 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'get', '/api/me/online-orders/quote', undefined, { branchId: 'x', addressId: 1 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1, addressId: 1 }), 403, 'ACCESS_DENIED');
  });

  it('404 địa chỉ / chi nhánh không có; 400 BR-BH-08 địa chỉ của khách khác, giỏ rỗng, chi nhánh không ACTIVE, thiếu hàng', async () => {
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1, addressId: 999 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 99, addressId: 1 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1, addressId: 3 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expectError(await fails('other', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1, addressId: 3 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expect((await fails('other', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1, addressId: 3 })).data.message).toContain('trống');
    // 404 chi nhánh đứng trước 400 giỏ rỗng.
    expectError(await fails('other', 'get', '/api/me/online-orders/quote', undefined, { branchId: 99, addressId: 3 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 2, addressId: 1 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    await call('owner', 'put', '/api/me/cart/items/2', { quantity: 4 });
    const short = await fails('owner', 'get', '/api/me/online-orders/quote', undefined, { branchId: 1, addressId: 1 });
    expectError(short, 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expect(short.data.message).toContain('Pate Whiskas 85g');
  });
});

describe('đặt hàng online (#7, Đơn hàng online#1)', () => {
  it('201 PENDING_PAYMENT: chụp giá, người nhận, địa chỉ; phí chốt; hạn thanh toán 24 giờ; dọn giỏ', async () => {
    const res = await call('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 1 });
    const order = res.data.data as OnlineOrder;
    expect(res.status).toBe(201);
    expect(order).toMatchObject({
      status: 'PENDING_PAYMENT',
      branchId: 1,
      receiverName: 'Nguyễn Thu Trang',
      receiverPhone: '0912345678',
      shipAddress: '12 Trần Thái Tông, Dịch Vọng Hậu, Hà Nội',
      subtotal: 486_000,
      shippingFee: 30_000,
      total: 516_000,
      redeliveryCount: 0,
      paidAt: null,
    });
    expect(order.code).toMatch(/^DH-\d{6}-\d{4}$/);
    expect(order.salesOrderId).toBe(7000 + order.onlineOrderId);
    expect(order.lines).toEqual([
      expect.objectContaining({ productId: 1, name: 'Hạt Royal Canin Mini Adult 2kg', unitPrice: 450_000, quantity: 1, lineTotal: 450_000 }),
      expect.objectContaining({ productId: 2, name: 'Pate Whiskas 85g', unitPrice: 18_000, quantity: 2, lineTotal: 36_000 }),
    ]);
    expect(Date.parse(order.paymentDueAt) - Date.parse(order.createdAt)).toBe(24 * 3_600_000);
    expect(await data('owner', 'get', '/api/me/cart')).toEqual({ items: [], subtotal: 0 });
    const list = await data<PageResponse<OnlineOrderSummary>>('owner', 'get', '/api/me/online-orders');
    expect(list.totalElements).toBe(9);
    expect(list.content[0]).toMatchObject({ onlineOrderId: order.onlineOrderId, itemCount: 2, total: 516_000, status: 'PENDING_PAYMENT' });
  });

  it('giữ hàng: sau khi đặt, tồn khả dụng của người khác giảm theo phần giữ', async () => {
    await call('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 1 }); // giữ 2 pate → còn 1
    await call('other', 'put', '/api/me/cart/items/2', { quantity: 2 });
    expect(await data('other', 'get', '/api/me/online-orders/fulfillment-branches')).toEqual([]);
    const short = await fails('other', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 });
    expectError(short, 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expect(short.data.message).toContain('còn 1');
    await call('other', 'put', '/api/me/cart/items/2', { quantity: 1 });
    expect((await call('other', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 })).status).toBe(201);
  });

  it('khách khác đặt bằng địa chỉ của mình: người nhận theo sổ địa chỉ của họ', async () => {
    await call('other', 'put', '/api/me/cart/items/1', { quantity: 2 });
    const order = await data<OnlineOrder>('other', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 });
    expect(order).toMatchObject({ receiverName: 'Lê Hoàng Long', shipAddress: '8 Nguyễn Chí Thanh, Láng Thượng, Hà Nội', subtotal: 900_000, shippingFee: 0 });
  });

  it('lỗi: body sai → 400 trước vai trò; 404 địa chỉ / chi nhánh; 400 BR-BH-08', async () => {
    expectError(await fails('owner', 'post', '/api/me/online-orders', {}), 400, 'VALIDATION_FAILED');
    expectError(await fails('owner', 'post', '/api/me/online-orders', { branchId: 1 }), 400, 'VALIDATION_FAILED');
    expectError(await fails('owner', 'post', '/api/me/online-orders', { branchId: '1', addressId: 1 }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'post', '/api/me/online-orders', {}), 400, 'VALIDATION_FAILED');
    expectError(await fails('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 999 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'post', '/api/me/online-orders', { branchId: 99, addressId: 1 }), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expectError(await fails('owner', 'post', '/api/me/online-orders', { branchId: 2, addressId: 1 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expectError(await fails('other', 'post', '/api/me/online-orders', { branchId: 1, addressId: 3 }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-08');
    expect((await data<PageResponse<OnlineOrderSummary>>('owner', 'get', '/api/me/online-orders')).totalElements).toBe(8);
  });
});

// ─── Đơn của tôi (#8–11) ───

describe('đơn của tôi (UC64)', () => {
  it('mới nhất trước; chỉ đơn của khách', async () => {
    const mine = await data<PageResponse<OnlineOrderSummary>>('owner', 'get', '/api/me/online-orders');
    expect(mine).toMatchObject({ page: 0, size: 20, totalElements: 8, totalPages: 1 });
    expect(ids(mine)).toEqual([8, 5, 3, 2, 6, 9, 10, 1]);
    expect(ids(await data('other', 'get', '/api/me/online-orders'))).toEqual([4, 7]);
    expect(mine.content[0]).toMatchObject({ salesOrderId: 7008, status: 'PENDING_PAYMENT', itemCount: 1, total: 480_000 });
  });

  it('lọc status, phân trang, kẹp page / size; sai kiểu → 400', async () => {
    expect(ids(await data('owner', 'get', '/api/me/online-orders', undefined, { status: 'CANCELLED' }))).toEqual([9, 10]);
    const second = await data<PageResponse<OnlineOrderSummary>>('owner', 'get', '/api/me/online-orders', undefined, { size: 3, page: 1 });
    expect(second).toMatchObject({ page: 1, size: 3, totalElements: 8, totalPages: 3 });
    expect(ids(second)).toEqual([2, 6, 9]);
    expect(await data('owner', 'get', '/api/me/online-orders', undefined, { size: 500, page: -2 })).toMatchObject({ page: 0, size: 100 });
    expectError(await fails('owner', 'get', '/api/me/online-orders', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('owner', 'get', '/api/me/online-orders', undefined, { page: 'x' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('desk', 'get', '/api/me/online-orders', undefined, { size: 'x' }), 400, 'MALFORMED_REQUEST');
  });

  it('chi tiết: đủ trường; đơn người khác và id không có cùng 404 (ADR-0006)', async () => {
    const order = await orderOf('owner', 3);
    expect(order).toMatchObject({ status: 'SHIPPED', carrier: 'Viettel Post', trackingCode: 'VTP2611055', branchName: expect.any(String), total: 138_000 });
    expect(order).not.toHaveProperty('failReason');
    expect(order).not.toHaveProperty('customerId');
    const foreign = await fails('other', 'get', '/api/me/online-orders/3');
    expectError(foreign, 404, 'RESOURCE_NOT_FOUND');
    expect(foreign.data.message).toBe((await fails('other', 'get', '/api/me/online-orders/999')).data.message.replace('999', '3'));
    expectError(await fails('owner', 'get', '/api/me/online-orders/abc'), 400, 'MALFORMED_REQUEST');
  });

  describe('hủy (#10)', () => {
    it('PENDING_PAYMENT: CANCELLED, nhả giữ hàng, lý do được cắt khoảng trắng', async () => {
      const order = await data<OnlineOrder>('owner', 'post', '/api/me/online-orders/8/cancel', { reason: '  Đổi ý  ' });
      expect(order).toMatchObject({ status: 'CANCELLED', cancelReason: 'Đổi ý', paidAt: null });
      expect(order.cancelledAt).toBeTruthy();
      await call('owner', 'put', '/api/me/cart/items/1', { quantity: 12 }); // 12 túi đủ vì phần giữ đã nhả
      expect(await data<unknown[]>('owner', 'get', '/api/me/online-orders/fulfillment-branches')).toHaveLength(1);
    });

    it('PAID: CANCELLED và sinh hoàn tiền GATEWAY toàn bộ gồm phí vận chuyển (BR-TG-12)', async () => {
      expect((await data<OnlineOrder>('owner', 'post', '/api/me/online-orders/5/cancel', { reason: 'Không cần nữa' })).status).toBe('CANCELLED');
      const refunds = await data<PageResponse<{ onlineOrderId: number | null; method: string; amount: number; status: string }>>('desk', 'get', '/api/refunds', undefined, { method: 'GATEWAY' });
      expect(refunds.content.find((r) => r.onlineOrderId === 5)).toMatchObject({ method: 'GATEWAY', amount: 498_000, status: 'REQUESTED' });
    });

    it('PROCESSING hủy được; SHIPPED, DELIVERY_FAILED, DELIVERED, COMPLETED, CANCELLED → 409', async () => {
      expect((await data<OnlineOrder>('other', 'post', '/api/me/online-orders/4/cancel', { reason: 'Hủy' })).status).toBe('CANCELLED');
      for (const [who, id] of [['owner', 3], ['owner', 6], ['owner', 2], ['owner', 1], ['owner', 9], ['other', 4]] as [Who, number][]) {
        expectError(await fails(who, 'post', `/api/me/online-orders/${id}/cancel`, { reason: 'Hủy' }), 409, 'INVALID_STATE_TRANSITION');
      }
    });

    it('thiếu / trống / quá 300 ký tự → 400 VALIDATION_FAILED; đơn người khác 404; body sai chạy trước vai trò', async () => {
      for (const body of [{}, { reason: '   ' }, { reason: 'x'.repeat(301) }]) expectError(await fails('owner', 'post', '/api/me/online-orders/8/cancel', body), 400, 'VALIDATION_FAILED');
      expectError(await fails('owner', 'post', '/api/me/online-orders/8/cancel', { reason: 7 }), 400, 'MALFORMED_REQUEST');
      expectError(await fails('desk', 'post', '/api/me/online-orders/8/cancel', {}), 400, 'VALIDATION_FAILED');
      expectError(await fails('other', 'post', '/api/me/online-orders/8/cancel', { reason: 'Hủy' }), 404, 'RESOURCE_NOT_FOUND');
      expect((await orderOf('owner', 8)).status).toBe('PENDING_PAYMENT');
    });
  });

  describe('xác nhận đã nhận (#11, Đơn hàng online#11)', () => {
    it('DELIVERED → COMPLETED, ghi completedAt; gọi lại → 409', async () => {
      const done = await data<OnlineOrder>('owner', 'post', '/api/me/online-orders/2/confirm-received');
      expect(done.status).toBe('COMPLETED');
      expect(done.completedAt).toBeTruthy();
      expectError(await fails('owner', 'post', '/api/me/online-orders/2/confirm-received'), 409, 'INVALID_STATE_TRANSITION');
    });
    it('trạng thái khác → 409; đơn người khác → 404', async () => {
      for (const id of [3, 5, 8, 9]) expectError(await fails('owner', 'post', `/api/me/online-orders/${id}/confirm-received`), 409, 'INVALID_STATE_TRANSITION');
      expectError(await fails('other', 'post', '/api/me/online-orders/2/confirm-received'), 404, 'RESOURCE_NOT_FOUND');
    });
  });
});

// ─── Xử lý và giao đơn của chi nhánh (#12–19) ───

describe('danh sách và chi tiết cho nhân viên (#12, #13)', () => {
  it('mặc định = PAID + PROCESSING + DELIVERY_FAILED, cũ nhất trước; lễ tân và quản lý cùng thấy', async () => {
    expect(ids(await data('desk', 'get', '/api/online-orders'))).toEqual([6, 7, 4, 5]);
    expect(ids(await data('manager', 'get', '/api/online-orders'))).toEqual([6, 7, 4, 5]);
  });
  it('lọc theo status (mọi trạng thái), phân trang', async () => {
    expect(ids(await data('desk', 'get', '/api/online-orders', undefined, { status: 'SHIPPED' }))).toEqual([3]);
    expect(ids(await data('desk', 'get', '/api/online-orders', undefined, { status: 'PENDING_PAYMENT' }))).toEqual([8]);
    expect(ids(await data('desk', 'get', '/api/online-orders', undefined, { status: 'CANCELLED' }))).toEqual([10, 9]); // cũ nhất trước
    expect(await data('desk', 'get', '/api/online-orders', undefined, { size: 2, page: 1 })).toMatchObject({ page: 1, size: 2, totalElements: 4, totalPages: 2 });
    expectError(await fails('desk', 'get', '/api/online-orders', undefined, { status: 'X' }), 400, 'MALFORMED_REQUEST');
    expectError(await fails('admin', 'get', '/api/online-orders', undefined, { status: 'X' }), 400, 'MALFORMED_REQUEST');
  });
  it('quản lý chi nhánh khác không thấy đơn của chi nhánh 1: danh sách rỗng, chi tiết 403 phạm vi, id không có 404', async () => {
    expect(ids(await data('manager2', 'get', '/api/online-orders'))).toEqual([]);
    expectError(await fails('manager2', 'get', '/api/online-orders/5'), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await fails('manager2', 'get', '/api/online-orders/5')).data.message).not.toMatch(/\(BR-/);
    expectError(await fails('manager2', 'get', '/api/online-orders/999'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('desk', 'get', '/api/online-orders/abc'), 400, 'MALFORMED_REQUEST');
    expect(await staffOrder('manager', 5)).toMatchObject({ onlineOrderId: 5, status: 'PAID' });
  });
});

describe('xử lý và giao đơn (UC65, BR-BH-11)', () => {
  it('chuỗi trọn vẹn PAID → PROCESSING → SHIPPED → DELIVERED, mốc thời gian lần lượt', async () => {
    expect((await data<OnlineOrder>('desk', 'post', '/api/online-orders/5/start-processing')).status).toBe('PROCESSING');
    const shipped = await data<OnlineOrder>('desk', 'post', '/api/online-orders/5/ship', { carrier: '  GHN ', trackingCode: ' ABC123 ' });
    expect(shipped).toMatchObject({ status: 'SHIPPED', carrier: 'GHN', trackingCode: 'ABC123' });
    expect(shipped.shippedAt).toBeTruthy();
    const delivered = await data<OnlineOrder>('desk', 'post', '/api/online-orders/5/mark-delivered');
    expect(delivered.status).toBe('DELIVERED');
    expect(delivered.deliveredAt).toBeTruthy();
    expect((await orderOf('owner', 5)).status).toBe('DELIVERED');
  });

  it('chuyển sai trạng thái → 409 (mỗi lệnh chỉ từ một trạng thái)', async () => {
    expectError(await fails('desk', 'post', '/api/online-orders/4/start-processing'), 409, 'INVALID_STATE_TRANSITION'); // PROCESSING
    expectError(await fails('desk', 'post', '/api/online-orders/5/ship', SHIP), 409, 'INVALID_STATE_TRANSITION'); // PAID
    expectError(await fails('desk', 'post', '/api/online-orders/5/mark-delivered'), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('desk', 'post', '/api/online-orders/4/fail-delivery', { reason: 'x' }), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('desk', 'post', '/api/online-orders/3/redeliver', SHIP), 409, 'INVALID_STATE_TRANSITION'); // SHIPPED, chưa thất bại
    expectError(await fails('desk', 'post', '/api/online-orders/3/cancel-failed', { reason: 'x' }), 409, 'INVALID_STATE_TRANSITION');
    expectError(await fails('desk', 'post', '/api/online-orders/8/start-processing'), 409, 'INVALID_STATE_TRANSITION'); // PENDING_PAYMENT
    expectError(await fails('desk', 'post', '/api/online-orders/9/mark-delivered'), 409, 'INVALID_STATE_TRANSITION'); // CANCELLED
  });

  it('chỉ lễ tân ghi: quản lý chi nhánh 403 ACCESS_DENIED; lễ tân... chi nhánh khác 403 phạm vi; id không có 404', async () => {
    expectError(await fails('manager', 'post', '/api/online-orders/5/start-processing'), 403, 'ACCESS_DENIED');
    expectError(await fails('manager2', 'post', '/api/online-orders/5/start-processing'), 403, 'ACCESS_DENIED');
    expectError(await fails('desk', 'post', '/api/online-orders/999/start-processing'), 404, 'RESOURCE_NOT_FOUND');
    expectError(await fails('desk', 'post', '/api/online-orders/abc/start-processing'), 400, 'MALFORMED_REQUEST');
    expect((await staffOrder('desk', 5)).status).toBe('PAID');
  });

  describe('bàn giao (#15)', () => {
    it('thiếu đơn vị vận chuyển hoặc mã vận đơn → 400 BR-BH-11, đứng trước 409', async () => {
      for (const body of [{}, { carrier: 'GHN' }, { trackingCode: 'X' }, { carrier: ' ', trackingCode: 'X' }, { carrier: 'GHN', trackingCode: '' }]) {
        expectError(await fails('desk', 'post', '/api/online-orders/4/ship', body), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11');
      }
      expectError(await fails('desk', 'post', '/api/online-orders/5/ship', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11'); // PAID, thiếu → 400 trước 409
      expect((await staffOrder('desk', 4)).status).toBe('PROCESSING');
    });
    it('quá 100 ký tự → VALIDATION_FAILED, sai kiểu → MALFORMED_REQUEST, cả hai chạy trước vai trò', async () => {
      expectError(await fails('manager', 'post', '/api/online-orders/4/ship', { carrier: 'x'.repeat(101), trackingCode: 'X' }), 400, 'VALIDATION_FAILED');
      expectError(await fails('desk', 'post', '/api/online-orders/4/ship', { carrier: 'GHN', trackingCode: 'x'.repeat(101) }), 400, 'VALIDATION_FAILED');
      expectError(await fails('desk', 'post', '/api/online-orders/4/ship', { carrier: 5, trackingCode: 'X' }), 400, 'MALFORMED_REQUEST');
    });
  });

  describe('giao thất bại và giao lại (#17, #18)', () => {
    it('SHIPPED → DELIVERY_FAILED cần lý do (BR-BH-11); thiếu → 400', async () => {
      for (const body of [{}, { reason: '  ' }]) expectError(await fails('desk', 'post', '/api/online-orders/3/fail-delivery', body), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11');
      expectError(await fails('desk', 'post', '/api/online-orders/3/fail-delivery', { reason: 'x'.repeat(301) }), 400, 'VALIDATION_FAILED');
      expect((await data<OnlineOrder>('desk', 'post', '/api/online-orders/3/fail-delivery', { reason: 'Khách vắng nhà' })).status).toBe('DELIVERY_FAILED');
      expect(ids(await data('desk', 'get', '/api/online-orders'))).toContain(3);
    });
    it('giao lại: SHIPPED, redeliveryCount + 1, cập nhật mã vận đơn', async () => {
      const again = await data<OnlineOrder>('desk', 'post', '/api/online-orders/7/redeliver', { carrier: 'Viettel Post', trackingCode: 'VTP999' });
      expect(again).toMatchObject({ status: 'SHIPPED', redeliveryCount: 1, carrier: 'Viettel Post', trackingCode: 'VTP999' });
    });
    it('quá số lần giao lại tối đa [CFG] 2 → 400 BR-BH-11; thiếu mã vận đơn cũng 400', async () => {
      expectError(await fails('desk', 'post', '/api/online-orders/6/redeliver', SHIP), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11');
      expect((await fails('desk', 'post', '/api/online-orders/6/redeliver', SHIP)).data.message).toContain('tối đa 2 lần');
      expectError(await fails('desk', 'post', '/api/online-orders/7/redeliver', { carrier: 'GHN' }), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11');
      // vòng đủ 2 lần rồi lần thứ 3 bị chặn
      await call('desk', 'post', '/api/online-orders/7/redeliver', SHIP);
      await call('desk', 'post', '/api/online-orders/7/fail-delivery', { reason: 'Lần 2 thất bại' });
      await call('desk', 'post', '/api/online-orders/7/redeliver', SHIP);
      await call('desk', 'post', '/api/online-orders/7/fail-delivery', { reason: 'Lần 3 thất bại' });
      expect((await staffOrder('desk', 7)).redeliveryCount).toBe(2);
      expectError(await fails('desk', 'post', '/api/online-orders/7/redeliver', SHIP), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11');
    });
  });

  describe('hủy sau giao thất bại (#19, Đơn hàng online#10)', () => {
    it('thiếu lý do → 400 BR-BH-11; thành công: CANCELLED + hoàn tiền GATEWAY toàn bộ (gồm phí vận chuyển)', async () => {
      expectError(await fails('desk', 'post', '/api/online-orders/7/cancel-failed', {}), 400, 'BUSINESS_RULE_VIOLATION', 'BR-BH-11');
      const done = await data<OnlineOrder>('desk', 'post', '/api/online-orders/7/cancel-failed', { reason: 'Khách từ chối nhận' });
      expect(done).toMatchObject({ status: 'CANCELLED', cancelReason: 'Khách từ chối nhận' });
      const refund = (await data<PageResponse<{ onlineOrderId: number | null; amount: number; method: string }>>('desk', 'get', '/api/refunds')).content.find((r) => r.onlineOrderId === 7);
      expect(refund).toMatchObject({ method: 'GATEWAY', amount: 480_000 });
      expect((await orderOf('other', 7)).status).toBe('CANCELLED');
    });
  });
});

// ─── Job hệ thống chạy lười (ST10, ST12) ───

describe('job hệ thống', () => {
  const at = (offsetMs: number) => vi.setSystemTime(Date.now() + offsetMs);

  it('ST10: đơn PENDING_PAYMENT quá hạn 24 giờ + 15 phút bị hệ thống hủy, nhả giữ hàng', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const placed = await data<OnlineOrder>('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 1 });
    at(24 * 3_600_000 + 14 * 60_000);
    expect((await orderOf('owner', placed.onlineOrderId)).status).toBe('PENDING_PAYMENT'); // job chạy mỗi 15 phút
    at(2 * 60_000);
    const cancelled = await orderOf('owner', placed.onlineOrderId);
    expect(cancelled).toMatchObject({ status: 'CANCELLED', cancelReason: expect.stringContaining('hệ thống') });
    expect(Date.parse(cancelled.cancelledAt!)).toBe(Date.parse(placed.paymentDueAt) + 15 * 60_000);
    // đơn #8 cũng quá hạn → hủy; phần giữ của cả hai đã nhả: 12 túi đủ.
    await call('owner', 'put', '/api/me/cart/items/1', { quantity: 12 });
    expect(await data<unknown[]>('owner', 'get', '/api/me/online-orders/fulfillment-branches')).toHaveLength(1);
  });

  it('ST12: đơn DELIVERED quá 7 ngày không có yêu cầu trả hàng đang mở → COMPLETED; có yêu cầu mở thì giữ nguyên', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const delivered = await data<OnlineOrder>('desk', 'post', '/api/online-orders/3/mark-delivered');
    at(7 * 86_400_000 - 60_000);
    expect((await orderOf('owner', 3)).status).toBe('DELIVERED');
    at(2 * 60_000);
    const done = await orderOf('owner', 3);
    expect(done.status).toBe('COMPLETED');
    expect(Date.parse(done.completedAt!)).toBe(Date.parse(delivered.deliveredAt!) + 7 * 86_400_000);
    // Đơn #2 (DELIVERED hôm qua) có hai yêu cầu trả hàng đang mở (PENDING, APPROVED) → không tự xác nhận.
    expect((await orderOf('owner', 2)).status).toBe('DELIVERED');
  });
});

// ─── Đọc chéo cho luồng chính (branchlife, báo cáo) ───

describe('đọc chéo cho luồng chính', () => {
  it('đếm đơn online chưa xong và hoàn tiền chờ xử lý theo chi nhánh (registerClosureSources của branchlife)', async () => {
    expect(openOnlineOrderCount(1)).toBe(7); // #2 DELIVERED, #3, #4, #5, #6, #7, #8; #1 COMPLETED, #9, #10 CANCELLED không tính
    expect(openOnlineOrderCount(2)).toBe(0);
    expect(pendingRefundCount(1)).toBe(2); // hoàn tiền #2 CASH REQUESTED, #3 GATEWAY FAILED
    expect(pendingRefundCount(2)).toBe(0);
    await call('owner', 'post', '/api/me/online-orders/8/cancel', { reason: 'Đổi ý' });
    expect(openOnlineOrderCount(1)).toBe(6);
    await call('owner', 'post', '/api/me/online-orders/5/cancel', { reason: 'Đổi ý' }); // đã thanh toán → sinh hoàn tiền
    expect(openOnlineOrderCount(1)).toBe(5);
    expect(pendingRefundCount(1)).toBe(3);
  });

  it('doanh thu đơn online đã thanh toán: tiền hàng tách phí vận chuyển, trừ hoàn tiền đã xong (hủy đơn và trả hàng)', () => {
    const rows = onlineRevenueRows();
    expect(rows.map((r) => r.orderId)).toEqual([7001, 7002, 7003, 7004, 7005, 7006, 7007, 7010]);
    expect(rows.find((r) => r.orderId === 7001)).toMatchObject({ goodsAmount: 486_000, shippingFee: 30_000, refunded: 18_000, branchId: 1 }); // trả hàng #7 REFUNDED
    expect(rows.find((r) => r.orderId === 7010)).toMatchObject({ goodsAmount: 450_000, shippingFee: 30_000, refunded: 480_000 }); // hủy sau PAID, hoàn toàn bộ
    expect(rows.find((r) => r.orderId === 7002)).toMatchObject({ goodsAmount: 522_000, shippingFee: 0, refunded: 0 }); // hoàn tiền #3 FAILED chưa tính
    expect(rows.every((r) => typeof r.paidAt === 'string')).toBe(true);
  });
});

// ─── State ───

describe('state lưu trong localStorage', () => {
  it('state cũ chỉ có {} hoặc thiếu bảng vẫn chạy: gộp dữ liệu mẫu, không nổ', async () => {
    localStorage.setItem('petcare-mock-commerce', '{}');
    expect(ids(await data('owner', 'get', '/api/me/online-orders'))).toHaveLength(8);
    localStorage.setItem('petcare-mock-commerce', JSON.stringify({ orders: [], carts: { '1': [{ productId: 2, quantity: 1 }] } }));
    expect(await data('owner', 'get', '/api/me/online-orders')).toMatchObject({ totalElements: 0 });
    expect((await data<Cart>('owner', 'get', '/api/me/cart')).items).toHaveLength(1);
    expect(await data('desk', 'get', '/api/refunds')).toMatchObject({ totalElements: 2 });
    localStorage.setItem('petcare-mock-commerce', 'không phải JSON');
    expect(ids(await data('owner', 'get', '/api/me/online-orders'))).toHaveLength(8);
  });

  it('state thiếu bảng đếm (counters) vẫn không trùng id lẫn mã đơn khi đặt đơn mới', async () => {
  await data('owner', 'get', '/api/me/cart'); // nạp và ghi dữ liệu mẫu
    const stored = JSON.parse(localStorage.getItem('petcare-mock-commerce')!);
    delete stored.counters;
    localStorage.setItem('petcare-mock-commerce', JSON.stringify(stored));
    const placed = await data<OnlineOrder>('owner', 'post', '/api/me/online-orders', { branchId: 1, addressId: 1 });
    expect(placed.onlineOrderId).toBe(11);
    const all = (await data<PageResponse<OnlineOrderSummary>>('owner', 'get', '/api/me/online-orders')).content.map((o) => o.code);
    expect(new Set(all).size).toBe(all.length);
  });
});
