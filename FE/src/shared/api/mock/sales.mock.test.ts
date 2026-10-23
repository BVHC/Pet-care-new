import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import { localIsoDate } from '../../utils/branch-schedule';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (sales.mock.ts), chi nhánh 1: #1 bán lẻ OPEN (khách #3) · #2 Visit PENDING hôm nay (dịch vụ tự sinh, thuốc,
// Pate) · #3 Visit PENDING 2 ngày trước (quá hạn) · #4 bán lẻ PENDING hôm nay · #5 PAID · #6, #7 CANCELLED.
// Chi nhánh 2: #8 bán lẻ PENDING. Tồn chi nhánh 1 (inventory.mock.ts): Royal Canin #1 12, Pate #2 3, Vitamin #4 0.
// Danh mục: Amoxicillin #3 thuốc kê đơn, Rabisin #5 vaccine, Vòng cổ #7 ngừng kinh doanh.

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const desk = () => as('reception@store1.vn');
const manager = () => as('manager@store1.vn');
const manager2 = () => as('manager2@store2.vn');
const vet = () => as('doctor@store1.vn');

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
const ids = (res: AxiosResponse) => res.data.data.content.map((o: { orderId: number }) => o.orderId);

async function newOrder(customerId = 4) {
  return (await client.post('/api/orders', { customerId }, await desk())).data.data;
}
const addGoods = async (orderId: number, productId: number, quantity: number) =>
  client.post(`/api/orders/${orderId}/lines`, { lineType: 'GOODS', productId, quantity }, await desk());

describe('tạo Order bán lẻ (#1, Order#2)', () => {
  it('lễ tân tạo cho hồ sơ khách: OPEN, RETAIL, chi nhánh của lễ tân, chưa giao hàng', async () => {
    const res = await client.post('/api/orders', { customerId: 4 }, await desk());
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ source: 'RETAIL', status: 'OPEN', customerId: 4, customerName: 'Lê Thị Hoa', branchId: 1, totalAmount: 0, lines: [], deliveryAllowed: false });
    expect(res.data.data.code).toMatch(/^DH-\d{4}-00009$/);
  });

  it('thiếu khách → 400 theo trường; khách không tồn tại → 404 (A1)', async () => {
    const missing = await failure(client.post('/api/orders', {}, await desk()));
    expect(missing.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'customerId: Vui lòng chọn khách hàng' });
    expect((await failure(client.post('/api/orders', { customerId: 999 }, await desk()))).status).toBe(404);
  });

  it('chỉ lễ tân: quản lý, bác sĩ → 403', async () => {
    expect((await failure(client.post('/api/orders', { customerId: 4 }, await manager()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/orders', { customerId: 4 }, await vet()))).status).toBe(403);
  });
});

describe('thêm / sửa / xóa dòng (#4–6, BR-BH-02, 03, 04, BR-SP-01)', () => {
  it('giá chụp từ danh mục; thiếu tồn chỉ cảnh báo, không chặn (BR-BH-04)', async () => {
    const order = await newOrder();
    const res = await addGoods(order.orderId, 2, 5);
    expect(res.status).toBe(201);
    expect(res.data.data.stockWarning).toEqual({ productId: 2, requested: 5, available: 3 });
    expect(res.data.data.order.lines[0]).toMatchObject({ lineType: 'GOODS', description: 'Pate Whiskas 85g', unitPrice: 18_000, lineTotal: 90_000, canEdit: true, canDelete: true });
    expect(res.data.data.order.totalAmount).toBe(90_000);
  });

  it('cùng sản phẩm thêm lần nữa là dòng mới; cảnh báo tính trên tổng số lượng trong Order', async () => {
    const order = await newOrder();
    expect((await addGoods(order.orderId, 1, 10)).data.data.stockWarning).toBeNull();
    const second = await addGoods(order.orderId, 1, 3);
    expect(second.data.data.order.lines).toHaveLength(2);
    expect(second.data.data.stockWarning).toEqual({ productId: 1, requested: 13, available: 12 });
  });

  it('thuốc kê đơn → BR-SP-01; vaccine → BR-KB-04; ngừng kinh doanh → 400; không có → 404', async () => {
    const order = await newOrder();
    expect(ruleOf(await failure(addGoods(order.orderId, 3, 1)))).toBe('BR-SP-01');
    expect(ruleOf(await failure(addGoods(order.orderId, 5, 1)))).toBe('BR-KB-04');
    expect((await failure(addGoods(order.orderId, 7, 1))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(addGoods(order.orderId, 999, 1))).status).toBe(404);
    // Thuốc không kê đơn bán như hàng (Vitamin, tồn 0 → cảnh báo).
    expect((await addGoods(order.orderId, 4, 1)).data.data.stockWarning).toEqual({ productId: 4, requested: 1, available: 0 });
  });

  it('bác sĩ không thêm hàng bán lẻ (403 BR-BH-02); số lượng 0 → 400', async () => {
    const res = await failure(client.post('/api/orders/1/lines', { lineType: 'GOODS', productId: 1, quantity: 1 }, await vet()));
    expect(res.status).toBe(403);
    expect(ruleOf(res)).toBe('BR-BH-02');
    expect((await failure(addGoods(1, 1, 0))).data.message).toContain('quantity:');
  });

  it('PATCH chỉ giảm số lượng; xóa dòng tính lại tổng', async () => {
    const order = await newOrder();
    const added = (await addGoods(order.orderId, 1, 3)).data.data;
    const url = `/api/orders/${order.orderId}/lines/${added.lineId}`;
    expect((await failure(client.patch(url, { quantity: 4 }, await desk()))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await client.patch(url, { quantity: 1 }, await desk())).data.data.totalAmount).toBe(450_000);
    expect((await client.delete(url, await desk())).data.data).toMatchObject({ lines: [], totalAmount: 0 });
  });

  it('cờ canEdit / canDelete theo người xem; Order PENDING: dịch vụ khóa, hàng lễ tân vẫn sửa được', async () => {
    const lines = (await client.get('/api/orders/2', await desk())).data.data.lines;
    expect(lines.map((l: { lineType: string; canEdit: boolean; canDelete: boolean }) => [l.lineType, l.canEdit, l.canDelete])).toEqual([
      ['SERVICE', false, false],
      ['DRUG', false, false],
      ['GOODS', true, true],
    ]);
    const asManager = (await client.get('/api/orders/2', await manager())).data.data.lines;
    expect(asManager.every((l: { canEdit: boolean; canDelete: boolean }) => !l.canEdit && !l.canDelete)).toBe(true);
    expect(ruleOf(await failure(client.delete('/api/orders/2/lines/22', await desk())))).toBe('BR-BH-02');
    expect((await failure(client.patch('/api/orders/2/lines/21', { quantity: 1 }, await desk()))).status).toBe(403);
  });

  it('Order đã thanh toán / đã hủy → 409 khi thêm, sửa, xóa dòng', async () => {
    expect((await failure(addGoods(5, 1, 1))).status).toBe(409);
    expect((await failure(client.patch('/api/orders/5/lines/51', { quantity: 1 }, await desk()))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.delete('/api/orders/6/lines/61', await desk()))).status).toBe(409);
  });
});

describe('chốt / hủy Order bán lẻ (#7, #8, Order#5, #7, BR-BH-01)', () => {
  it('chốt Order chưa có dòng → BR-BH-01; có dòng → PENDING, vẫn chưa giao hàng', async () => {
    const order = await newOrder();
    expect(ruleOf(await failure(client.post(`/api/orders/${order.orderId}/close`, undefined, await desk())))).toBe('BR-BH-01');
    await addGoods(order.orderId, 2, 1);
    const closed = (await client.post(`/api/orders/${order.orderId}/close`, undefined, await desk())).data.data;
    expect(closed).toMatchObject({ status: 'PENDING', deliveryAllowed: false });
    expect(closed.pendingAt).toBeTruthy();
  });

  it('chốt / hủy Order không phải bán lẻ OPEN → 409 (kể cả khi chưa có dòng: 409 trước rule)', async () => {
    expect((await failure(client.post('/api/orders/4/close', undefined, await desk()))).status).toBe(409);
    expect((await failure(client.post('/api/orders/4/cancel', undefined, await desk()))).status).toBe(409);
    expect((await failure(client.post('/api/orders/2/close', undefined, await desk()))).status).toBe(409);
  });

  it('hủy Order OPEN: RETAIL_UNCLOSED, dòng giữ làm lịch sử; quản lý không chốt / hủy được', async () => {
    expect((await failure(client.post('/api/orders/1/cancel', undefined, await manager()))).status).toBe(403);
    const res = (await client.post('/api/orders/1/cancel', undefined, await desk())).data.data;
    expect(res).toMatchObject({ status: 'CANCELLED', cancelType: 'RETAIL_UNCLOSED', totalAmount: 450_000 });
    expect(res.lines).toHaveLength(1);
  });
});

describe('quản lý hủy Order khách không thanh toán (#10, Order#10, BR-BH-05)', () => {
  it('chỉ BRANCH_MANAGER: lễ tân → 403', async () => {
    expect((await failure(client.post('/api/orders/3/cancel-unpaid', { reason: 'Bỏ về' }, await desk()))).status).toBe(403);
  });

  it('lý do bắt buộc (chỉ khoảng trắng cũng thiếu) → BR-BH-05; quá 300 ký tự → 400', async () => {
    expect(ruleOf(await failure(client.post('/api/orders/3/cancel-unpaid', { reason: '   ' }, await manager())))).toBe('BR-BH-05');
    expect((await failure(client.post('/api/orders/3/cancel-unpaid', { reason: 'x'.repeat(301) }, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('PENDING → CANCELLED, UNPAID, lý do đã bỏ khoảng trắng; lần hai → 409; Order không PENDING → 409', async () => {
    const res = (await client.post('/api/orders/3/cancel-unpaid', { reason: '  Khám xong bỏ về  ' }, await manager())).data.data;
    expect(res).toMatchObject({ status: 'CANCELLED', cancelType: 'UNPAID', cancelReason: 'Khám xong bỏ về' });
    expect((await failure(client.post('/api/orders/3/cancel-unpaid', { reason: 'Lần hai' }, await manager()))).status).toBe(409);
    expect((await failure(client.post('/api/orders/1/cancel-unpaid', { reason: 'OPEN' }, await manager()))).status).toBe(409);
  });
});

describe('danh sách / chi tiết (#2, #3) — phạm vi chi nhánh', () => {
  it('mặc định mới nhất trước, chỉ chi nhánh mình; quản lý chi nhánh 2 chỉ thấy Order của mình', async () => {
    const all = await client.get('/api/orders', await manager());
    expect(all.data.data.totalElements).toBe(7);
    expect(ids(await client.get('/api/orders', await manager2()))).toEqual([8]);
  });

  it('lọc trạng thái, nguồn, khách, ngày tạo; chờ thu quá hạn = PENDING chốt trước hôm nay', async () => {
    const pending = await client.get('/api/orders', { params: { status: 'PENDING', source: 'VISIT' }, ...(await manager()) });
    expect(ids(pending).sort()).toEqual([2, 3]);
    expect(ids(await client.get('/api/orders', { params: { pendingOverdue: true }, ...(await manager()) }))).toEqual([3]);
    expect(ids(await client.get('/api/orders', { params: { customerId: 3 }, ...(await desk()) }))).toEqual([1, 6]);
    const today = await client.get('/api/orders', { params: { date: localIsoDate() }, ...(await desk()) });
    expect(ids(today).sort()).toEqual([1, 2, 4]);
  });

  it('chi tiết Order chi nhánh khác → 403; không có → 404; bác sĩ xem được', async () => {
    expect((await failure(client.get('/api/orders/8', await manager()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/orders/999', await manager()))).status).toBe(404);
    expect((await client.get('/api/orders/2', await vet())).data.data.code).toMatch(/^DH-/);
    expect((await failure(client.post('/api/orders/8/cancel-unpaid', { reason: 'x' }, await manager()))).status).toBe(403);
  });
});
