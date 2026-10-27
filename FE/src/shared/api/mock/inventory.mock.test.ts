import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import { restoreTimezone, setTimezone, TIMEZONES } from '../../../test/timezone';
import { addDays, localIsoDate } from '../../utils/branch-schedule';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (inventory.mock.ts, ngày tương đối với hôm nay), chi nhánh 1:
// Royal Canin #1 lô mặc định 12 (min 5) · Pate #2 lô mặc định 3 (min 20, dưới ngưỡng) · Amoxicillin #3 AMX2405 (+20 ngày) 200,
// AMX2501 (+400) 500 · Rabisin #5 RB2401 (+260) 15, RB2309 (−16, hết hạn) 2 · Vanguard #6 VG2402 (+160) 4 (min 5, dưới ngưỡng).
// Chi nhánh 2: Royal Canin 7. Phiếu #1 CONFIRMED (RB2401 nhập 18), #2 DRAFT (Vanguard thiếu lô / hạn), #3 CANCELLED.
// Nhà cung cấp #1, #2 đang hợp tác và có phiếu, #3 ngừng hợp tác, #4 chưa có phiếu.

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const manager = () => as('manager@store1.vn');
const manager2 = () => as('manager2@store2.vn');
const desk = () => as('reception@store1.vn');
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
const today = localIsoDate();
const item = async (productId: number) => (await client.get(`/api/inventory/${productId}`, await manager())).data.data;
const lots = async (productId: number) => (await client.get(`/api/inventory/${productId}/lots`, await manager())).data.data;

describe('nhà cung cấp (UC73, BR-KO-02)', () => {
  it('quản lý chi nhánh xem cả nhà cung cấp ngừng hợp tác, lọc được; lễ tân 403', async () => {
    const all = await client.get('/api/suppliers', await manager());
    expect(all.data.data.map((s: { supplierId: number }) => s.supplierId)).toEqual([1, 2, 3, 4]);
    const active = await client.get('/api/suppliers', { params: { isActive: true }, ...(await manager()) });
    expect(active.data.data.map((s: { supplierId: number }) => s.supplierId)).toEqual([1, 2, 4]);
    const byName = await client.get('/api/suppliers', { params: { q: 'hanvet' }, ...(await manager()) });
    expect(byName.data.data).toHaveLength(1);
    expect((await failure(client.get('/api/suppliers', await desk()))).status).toBe(403);
  });

  it('thêm: SĐT sai mẫu → 400 theo trường; thêm được; sửa ngừng hợp tác', async () => {
    const bad = await failure(client.post('/api/suppliers', { name: 'NCC mới', phone: '12345' }, await manager()));
    expect(bad.data.errorCode).toBe('VALIDATION_FAILED');
    expect(bad.data.message).toContain('phone:');
    const created = await client.post('/api/suppliers', { name: 'NCC mới', phone: '0987654321' }, await manager());
    expect(created.status).toBe(201);
    expect(created.data.data).toMatchObject({ supplierId: 5, isActive: true });
    const res = await client.patch('/api/suppliers/2', { isActive: false }, await manager());
    expect(res.data.data.isActive).toBe(false);
  });

  it('xóa nhà cung cấp đã có phiếu nhập → BR-KO-02; chưa có → 204', async () => {
    expect(ruleOf(await failure(client.delete('/api/suppliers/1', await manager())))).toBe('BR-KO-02');
    expect((await client.delete('/api/suppliers/4', await manager())).status).toBe(204);
    expect((await failure(client.delete('/api/suppliers/4', await manager()))).status).toBe(404);
  });
});

describe('tồn kho (UC75, BR-KO-01, 07)', () => {
  it('tồn khả dụng bỏ lô hết hạn; dưới ngưỡng so trên tồn khả dụng', async () => {
    const res = await client.get('/api/inventory', { params: { q: 'rabisin' }, ...(await desk()) });
    expect(res.data.data.content[0]).toMatchObject({
      productId: 5,
      totalQuantity: 17,
      availableQuantity: 15,
      expiredQuantity: 2,
      minQuantity: 10,
      belowMin: false,
      nearestExpiryDate: addDays(today, 260),
    });
    const below = await client.get('/api/inventory', { params: { belowMin: true }, ...(await manager()) });
    expect(below.data.data.content.map((i: { productId: number }) => i.productId)).toEqual([2, 6]);
  });

  it('lọc sắp hết hạn trong 30 ngày, theo loại; chi nhánh 2 chỉ thấy tồn của mình', async () => {
    const soon = await client.get('/api/inventory', { params: { expiringWithinDays: 30 }, ...(await manager()) });
    expect(soon.data.data.content.map((i: { productId: number }) => i.productId)).toEqual([3]);
    const vaccines = await client.get('/api/inventory', { params: { productType: 'VACCINE' }, ...(await manager()) });
    expect(vaccines.data.data.content.map((i: { productId: number }) => i.productId)).toEqual([6, 5]);
    const other = await client.get('/api/inventory', await manager2());
    expect(other.data.data.content).toMatchObject([{ productId: 1, totalQuantity: 7 }]);
  });

  it('lô theo FEFO kèm cờ hết hạn; bác sĩ xem tồn nhưng không xem lô; SUPER_MANAGER không có kho', async () => {
    const res = await client.get('/api/inventory/5/lots', await desk());
    expect(res.data.data.map((l: { lotNumber: string; expired: boolean }) => [l.lotNumber, l.expired])).toEqual([
      ['RB2309', true],
      ['RB2401', false],
    ]);
    expect((await client.get('/api/inventory/5', await vet())).data.data.availableQuantity).toBe(15);
    expect((await failure(client.get('/api/inventory/5/lots', await vet()))).status).toBe(403);
    expect((await failure(client.get('/api/inventory', await as('chuoi@petcare.vn')))).status).toBe(403);
  });

  it('đặt tồn tối thiểu: sản phẩm chưa có tồn thì tạo dòng (M3); số âm 400; lễ tân 403; sản phẩm lạ 404', async () => {
    const res = await client.put('/api/inventory/4/min-quantity', { minQuantity: 6 }, await manager());
    expect(res.data.data).toMatchObject({ productId: 4, totalQuantity: 0, minQuantity: 6, belowMin: true });
    expect((await failure(client.put('/api/inventory/4/min-quantity', { minQuantity: -1 }, await manager()))).status).toBe(400);
    expect((await failure(client.put('/api/inventory/4/min-quantity', { minQuantity: 1 }, await desk()))).status).toBe(403);
    expect((await failure(client.put('/api/inventory/999/min-quantity', { minQuantity: 1 }, await manager()))).status).toBe(404);
  });
});

describe('phiếu nhập kho (UC74, 03 §9)', () => {
  it('tạo nháp có mã liên tục; nhà cung cấp ngừng hợp tác → BR-KO-02; sản phẩm lạ → 404; số lượng ≤ 0 → 400', async () => {
    const body = { supplierId: 1, receiptDate: today, lines: [{ productId: 1, quantity: 5, unitCost: 380_000 }] };
    const res = await client.post('/api/stock-receipts', body, await manager());
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ receiptId: 4, status: 'DRAFT', supplierName: 'Công ty Royal Canin Việt Nam' });
    expect(res.data.data.code).toMatch(/^PN-\d{6}-0004$/);
    expect(ruleOf(await failure(client.post('/api/stock-receipts', { ...body, supplierId: 3 }, await manager())))).toBe('BR-KO-02');
    const unknown = { ...body, lines: [{ productId: 999, quantity: 1, unitCost: 1 }] };
    expect((await failure(client.post('/api/stock-receipts', unknown, await manager()))).status).toBe(404);
    const zero = { ...body, lines: [{ productId: 1, quantity: 0, unitCost: 1 }] };
    expect((await failure(client.post('/api/stock-receipts', zero, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('xác nhận thiếu số lô / hạn dùng → BR-KO-03; bổ sung rồi xác nhận: cộng tồn, cùng lô cộng vào lô cũ', async () => {
    const missing = await failure(client.post('/api/stock-receipts/2/confirm', undefined, await manager()));
    expect(ruleOf(missing)).toBe('BR-KO-03');
    expect(missing.data.message).toContain('Vaccine 5 bệnh Vanguard');

    const body = {
      supplierId: 1,
      receiptDate: today,
      lines: [
        { productId: 1, quantity: 10, unitCost: 380_000 },
        { productId: 6, quantity: 10, unitCost: 140_000, lotNumber: 'VG2402', expiryDate: addDays(today, 160) },
      ],
    };
    await client.put('/api/stock-receipts/2', body, await manager());
    const res = await client.post('/api/stock-receipts/2/confirm', undefined, await manager());
    expect(res.data.data.status).toBe('CONFIRMED');
    expect(res.data.data.lines.every((l: { stockLotId: number | null }) => l.stockLotId !== null)).toBe(true);
    expect((await item(6)).availableQuantity).toBe(14);
    expect(await lots(6)).toHaveLength(1);
    expect((await item(1)).totalQuantity).toBe(22);
  });

  it('hạn dùng không sau ngày nhập, phiếu không có dòng → BR-KO-03; phiếu đã nhập không sửa / xác nhận lại (409)', async () => {
    const early = { supplierId: 2, receiptDate: today, lines: [{ productId: 3, quantity: 1, unitCost: 1, lotNumber: 'X1', expiryDate: today }] };
    const created = await client.post('/api/stock-receipts', early, await manager());
    expect(ruleOf(await failure(client.post(`/api/stock-receipts/${created.data.data.receiptId}/confirm`, undefined, await manager())))).toBe('BR-KO-03');
    const empty = await client.post('/api/stock-receipts', { supplierId: 2, receiptDate: today, lines: [] }, await manager());
    expect(ruleOf(await failure(client.post(`/api/stock-receipts/${empty.data.data.receiptId}/confirm`, undefined, await manager())))).toBe('BR-KO-03');

    expect((await failure(client.post('/api/stock-receipts/1/confirm', undefined, await manager()))).status).toBe(409);
    const put = await failure(client.put('/api/stock-receipts/1', { supplierId: 2, receiptDate: today, lines: [] }, await manager()));
    expect(put.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
  });

  // BE StockReceiptService.validateForConfirm: ngày nhập sau hôm nay (giờ VN) → BR-KO-03, nối với lỗi dòng bằng "; " (T49 nhóm 2, hàng 5).
  it('ngày nhập ở tương lai: lưu nháp được, xác nhận → BR-KO-03 đúng câu BE, đứng trước lỗi dòng; sửa về hôm nay thì nhập kho được', async () => {
    const future = addDays(today, 1);
    const line = { productId: 1, quantity: 5, unitCost: 380_000 };
    const created = await client.post('/api/stock-receipts', { supplierId: 1, receiptDate: future, lines: [line] }, await manager());
    expect(created.status).toBe(201);
    const id = created.data.data.receiptId;
    const refused = await failure(client.post(`/api/stock-receipts/${id}/confirm`, undefined, await manager()));
    expect(refused.status).toBe(400);
    expect(refused.data.message).toBe('ngày nhập không được ở tương lai (BR-KO-03)');
    expect((await item(1)).totalQuantity).toBe(12);

    // Phiếu #2 còn dòng Vanguard thiếu số lô / hạn dùng: lỗi ngày đứng đầu, lỗi dòng nối sau.
    await client.put('/api/stock-receipts/2', { supplierId: 1, receiptDate: future, lines: [line, { productId: 6, quantity: 10, unitCost: 140_000 }] }, await manager());
    const both = await failure(client.post('/api/stock-receipts/2/confirm', undefined, await manager()));
    expect(both.data.message).toMatch(/^ngày nhập không được ở tương lai; .*Vaccine 5 bệnh Vanguard.* \(BR-KO-03\)$/);

    await client.put(`/api/stock-receipts/${id}`, { supplierId: 1, receiptDate: today, lines: [line] }, await manager());
    const confirmed = await client.post(`/api/stock-receipts/${id}/confirm`, undefined, await manager());
    expect(confirmed.data.data.status).toBe('CONFIRMED');
    expect((await item(1)).totalQuantity).toBe(17);
  });

  // BE: 404 / 403 → 409 trạng thái → 400 rule (ADR-0065 §7), nhà cung cấp trước sản phẩm (T49 nhóm 2, hàng 12).
  it('phiếu không còn nháp báo 409 trước BR-KO-02, cả lúc xác nhận lẫn lúc sửa; nhà cung cấp ngừng hợp tác báo trước sản phẩm lạ', async () => {
    await client.patch('/api/suppliers/2', { isActive: false }, await manager()); // phiếu #1 (đã nhập) và #3 (đã hủy) thuộc nhà cung cấp này
    const confirmedAgain = await failure(client.post('/api/stock-receipts/1/confirm', undefined, await manager()));
    expect(confirmedAgain.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    const cancelled = await failure(client.post('/api/stock-receipts/3/confirm', undefined, await manager()));
    expect(cancelled.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    const edit = { supplierId: 2, receiptDate: today, lines: [{ productId: 1, quantity: 1, unitCost: 1 }] };
    const edited = await failure(client.put('/api/stock-receipts/1', edit, await manager()));
    expect(edited.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });

    const both = { supplierId: 3, receiptDate: today, lines: [{ productId: 999, quantity: 1, unitCost: 1 }] };
    expect(ruleOf(await failure(client.post('/api/stock-receipts', both, await manager())))).toBe('BR-KO-02');
  });

  it('hủy phiếu đã nhập khi lô không còn đủ → BR-KO-04, chỉ sang phiếu điều chỉnh; tồn không đổi', async () => {
    const res = await failure(client.post('/api/stock-receipts/1/cancel', {}, await manager()));
    expect(ruleOf(res)).toBe('BR-KO-04');
    expect(res.data.message).toContain('phiếu điều chỉnh');
    expect((await item(5)).totalQuantity).toBe(17);
  });

  it('hủy phiếu có hai dòng cùng lô: cộng dồn rồi so với tồn (BR-KO-04)', async () => {
    const line = { productId: 3, quantity: 300, unitCost: 5_000, lotNumber: 'AMX2501', expiryDate: addDays(today, 400) };
    const created = await client.post('/api/stock-receipts', { supplierId: 2, receiptDate: today, lines: [line, line] }, await manager());
    const id = created.data.data.receiptId;
    await client.post(`/api/stock-receipts/${id}/confirm`, undefined, await manager());
    const lot = (await lots(3)).find((l: { lotNumber: string }) => l.lotNumber === 'AMX2501');
    expect(lot.quantity).toBe(1100);
    await client.post('/api/stock-adjustments', { reason: 'COUNT_MISMATCH', lines: [{ stockLotId: lot.stockLotId, quantityDelta: -600 }] }, await manager());
    expect(ruleOf(await failure(client.post(`/api/stock-receipts/${id}/cancel`, {}, await manager())))).toBe('BR-KO-04');
  });

  it('hủy nháp không đổi tồn; hủy phiếu đã hủy → 409; hủy phiếu đã nhập đủ tồn → trừ lại', async () => {
    const draft = await client.post('/api/stock-receipts/2/cancel', { reason: 'Hàng không về' }, await manager());
    expect(draft.data.data).toMatchObject({ status: 'CANCELLED', cancelReason: 'Hàng không về' });
    expect((await item(1)).totalQuantity).toBe(12);
    expect((await failure(client.post('/api/stock-receipts/3/cancel', {}, await manager()))).status).toBe(409);

    const body = { supplierId: 1, receiptDate: today, lines: [{ productId: 2, quantity: 30, unitCost: 12_000 }] };
    const id = (await client.post('/api/stock-receipts', body, await manager())).data.data.receiptId;
    await client.post(`/api/stock-receipts/${id}/confirm`, undefined, await manager());
    expect((await item(2)).totalQuantity).toBe(33);
    await client.post(`/api/stock-receipts/${id}/cancel`, {}, await manager());
    expect((await item(2)).totalQuantity).toBe(3);
  });

  it('phạm vi chi nhánh, quyền, danh sách lọc trạng thái kèm tổng tiền', async () => {
    expect((await failure(client.get('/api/stock-receipts/1', await manager2()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/stock-receipts', await desk()))).status).toBe(403);
    const drafts = await client.get('/api/stock-receipts', { params: { status: 'DRAFT' }, ...(await manager()) });
    expect(drafts.data.data.content).toEqual([
      expect.objectContaining({ receiptId: 2, status: 'DRAFT', lineCount: 2, totalCost: 10 * 380_000 + 10 * 140_000 }),
    ]);
  });
});

describe('điều chỉnh tồn (UC76, BR-KO-06)', () => {
  it('lý do Khác bắt ghi chú; giảm quá tồn của lô bị chặn; dòng 0 hoặc không có dòng → 400', async () => {
    const lot = 15; // RB2401 còn 15
    expect(ruleOf(await failure(client.post('/api/stock-adjustments', { reason: 'OTHER', lines: [{ stockLotId: lot, quantityDelta: -1 }] }, await manager())))).toBe('BR-KO-06');
    expect(ruleOf(await failure(client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: lot, quantityDelta: -16 }] }, await manager())))).toBe('BR-KO-06');
    expect((await item(5)).totalQuantity).toBe(17);
    expect((await failure(client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: lot, quantityDelta: 0 }] }, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [] }, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('lô của chi nhánh khác coi như không có (404); lễ tân 403', async () => {
    expect((await failure(client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 18, quantityDelta: -1 }] }, await manager()))).status).toBe(404);
    expect((await failure(client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 11, quantityDelta: -1 }] }, await desk()))).status).toBe(403);
  });

  it('điều chỉnh lô hết hạn về 0 và lô mặc định tăng; phiếu mới đứng đầu danh sách', async () => {
    const res = await client.post(
      '/api/stock-adjustments',
      { reason: 'EXPIRED', lines: [{ stockLotId: 16, quantityDelta: -2 }] },
      await manager(),
    );
    expect(res.status).toBe(201);
    expect(res.data.data.code).toMatch(/^DC-\d{6}-0002$/);
    expect(res.data.data.lines[0]).toMatchObject({ productId: 5, lotNumber: 'RB2309', quantityDelta: -2, balanceAfter: 0 });
    expect((await item(5)).expiredQuantity).toBe(0);

    await client.post('/api/stock-adjustments', { reason: 'COUNT_MISMATCH', lines: [{ stockLotId: 11, quantityDelta: 3 }] }, await manager());
    expect((await item(1)).totalQuantity).toBe(15);

    const list = await client.get('/api/stock-adjustments', await manager());
    expect(list.data.data.content.map((a: { adjustmentId: number }) => a.adjustmentId)).toEqual([3, 2, 1]);
    const old = await client.get('/api/stock-adjustments', { params: { to: addDays(today, -1) }, ...(await manager()) });
    expect(old.data.data.content.map((a: { adjustmentId: number }) => a.adjustmentId)).toEqual([1]);
  });
});

// BE tính "hôm nay" theo Asia/Ho_Chi_Minh; ngày lịch của máy không được lọt vào đây (T49 nhóm 2, hàng 10).
describe.each(TIMEZONES)('ngày nhập tương lai tính theo giờ VN khi máy ở %s', (tz) => {
  beforeEach(() => {
    setTimezone(tz);
    vi.useFakeTimers({ toFake: ['Date'] });
  });
  afterEach(() => {
    vi.useRealTimers();
    restoreTimezone();
  });

  it.each([
    ['2026-10-27T23:59:00+07:00', '2026-10-27'],
    ['2026-10-28T00:01:00+07:00', '2026-10-28'],
  ])('lúc %s giờ VN: phiếu ghi ngày %s nhập kho được, phiếu ghi ngày kế tiếp bị BR-KO-03', async (instant, vnToday) => {
    vi.setSystemTime(new Date(instant));
    const line = { productId: 1, quantity: 1, unitCost: 1 };
    const create = async (receiptDate: string) =>
      (await client.post('/api/stock-receipts', { supplierId: 1, receiptDate, lines: [line] }, await manager())).data.data.receiptId;
    const todayId = await create(vnToday);
    const tomorrowId = await create(addDays(vnToday, 1));

    expect((await client.post(`/api/stock-receipts/${todayId}/confirm`, undefined, await manager())).data.data.status).toBe('CONFIRMED');
    const refused = await failure(client.post(`/api/stock-receipts/${tomorrowId}/confirm`, undefined, await manager()));
    expect(refused.data.message).toBe('ngày nhập không được ở tương lai (BR-KO-03)');
  });
});
