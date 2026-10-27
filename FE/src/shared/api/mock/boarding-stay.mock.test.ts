import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';
import { recordDoseForMock } from './visit-clinical.mock';

// Nhận thú (#13, #14), hủy vì hết chuồng (#15), trả thú (#16–18) và hủy phiên trả thú (sales #9) — BE chưa có, mock theo ASSUMPTION A1–A6, A10 của T48.
// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). Xem dữ liệu mẫu ở boarding.mock.test.ts: Cốm (#2) đã nhận 21/10, trả dự kiến 31/10, chuồng C-01;
// Bim (#4) BOOKED 26/10→28/10 và chưa tiêm 5 bệnh; Bơ (#1) BOOKED 30/10→01/11 (thú / khách không còn trong mock thú nên không có hồ sơ tiêm → không thiếu mũi).

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = makeClient();
});
afterEach(() => vi.useRealTimers());

const as = (email: string) => authAs(client, email);
const desk = () => as(ACCOUNTS.desk);
const manager = () => as(ACCOUNTS.manager);
const at = (iso: string) => vi.setSystemTime(new Date(iso));

type Booking = { bookingId: number; status: string; checkInDate: string; checkOutDate: string; kennelId: number | null; kennelCode: string | null; activeOrderId: number | null; [k: string]: unknown };
const get = async (id: number, auth?: object): Promise<Booking> => (await client.get(`/api/boarding-bookings/${id}`, auth ?? (await desk()))).data.data;
const kennelOf = async (id: number) => ((await client.get('/api/kennels', await desk())).data.data as { kennelId: number; status: string; currentBookingId: number | null }[]).find((k) => k.kennelId === id)!;

const body = (extra: object = {}) => ({ kennelId: 3, weightKg: 6.2, healthCondition: 'Khỏe, hơi nhút nhát', emergencyPhone: '0987000111', ...extra });
const readiness = async (id: number, auth?: object) => client.get(`/api/boarding-bookings/${id}/check-in-readiness`, auth ?? (await desk()));
const checkIn = async (id: number, extra: object = {}, auth?: object) => client.post(`/api/boarding-bookings/${id}/check-in`, body(extra), auth ?? (await desk()));
const checkInFail = async (id: number, extra: object = {}, auth?: object) => failure(checkIn(id, extra, auth));
const post = async (path: string, data?: object, auth?: object) => client.post(path, data, auth ?? (await desk()));
const startCheckout = (id: number, auth?: object) => post(`/api/boarding-bookings/${id}/start-checkout`, undefined, auth);
const handOver = (id: number, auth?: object) => post(`/api/boarding-bookings/${id}/hand-over`, undefined, auth);
const order = async (id: number) => (await client.get(`/api/orders/${id}`, await desk())).data.data;

/** Rex (chó của khách #1, không có lượt khám nào) đã tiêm đủ, đặt hôm nay và đã nhận vào C-03. Bim không dùng được cho các ca khóa thú: mock lượt khám mẫu cho Bim một lượt thẩm mỹ đang mở. */
async function rexInStay(): Promise<{ petId: number; bookingId: number }> {
  const petId = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
  recordDoseForMock(petId, 1, '2026-10-10');
  recordDoseForMock(petId, 2, '2026-10-10');
  const booked = await client.post('/api/boarding-bookings', { petId, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }, await desk());
  const bookingId = booked.data.data.booking.bookingId;
  expect((await checkIn(bookingId)).data.data.status).toBe('CHECKED_IN');
  return { petId, bookingId };
}

describe('GET /boarding-bookings/:id/check-in-readiness (#13)', () => {
  it('Bim chưa tiêm 5 bệnh: thiếu mũi, còn chuồng C-03, trong giờ mở cửa → không nhận được', async () => {
    const res = await readiness(4);
    expect(res.data).toMatchObject({ message: 'Điều kiện nhận thú' });
    expect(res.data.data).toEqual({
      vaccineGaps: [{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', lastAdministeredOn: null, nextDueDate: null }],
      availableKennels: [{ kennelId: 3, branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-03', status: 'AVAILABLE', note: null, currentBookingId: null }],
      withinOpeningHours: true,
      canCheckIn: false,
    });
  });

  it('tiêm đủ và còn hạn (hạn tái chủng đúng hôm nay vẫn qua) → nhận được; quá hạn một ngày vẫn là thiếu', async () => {
    await readiness(4); // nạp dữ liệu mẫu
    recordDoseForMock(2, 2, '2026-10-02'); // mũi 1: tái chủng sau 21 ngày = 23/10 = hôm nay
    expect((await readiness(4)).data.data).toMatchObject({ vaccineGaps: [], canCheckIn: true });
    recordDoseForMock(2, 2, '2026-10-01'); // mũi mới nhất theo ngày tiêm vẫn là 02/10 → vẫn đủ
    expect((await readiness(4)).data.data.vaccineGaps).toEqual([]);
  });

  it('mũi gần nhất quá ngày tái chủng → thiếu, kèm ngày tiêm và hạn của mũi đó', async () => {
    await readiness(4);
    recordDoseForMock(2, 2, '2026-10-01'); // hạn 22/10 < hôm nay 23/10
    expect((await readiness(4)).data.data.vaccineGaps).toEqual([{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', lastAdministeredOn: '2026-10-01', nextDueDate: '2026-10-22' }]);
  });

  it('chỉ tính mũi bắt buộc của loài: thú không có phác đồ bắt buộc không thiếu gì', async () => {
    // Bơ là thú mẫu không có hồ sơ trong mock thú → không có phác đồ để so.
    expect((await readiness(1)).data.data).toMatchObject({ vaccineGaps: [], canCheckIn: true });
  });

  it('ngoài giờ mở cửa (nghỉ trưa, trước 08:00, từ 19:00) → withinOpeningHours false, không nhận được', async () => {
    await readiness(1);
    for (const [time, open] of [
      ['07:59', false],
      ['08:00', true],
      ['11:59', true],
      ['12:00', false],
      ['13:29', false],
      ['13:30', true],
      ['18:59', true],
      ['19:00', false],
    ] as const) {
      at(`2026-10-23T${time}:00+07:00`);
      const data = (await readiness(1)).data.data;
      expect([time, data.withinOpeningHours, data.canCheckIn]).toEqual([time, open, open]);
    }
  });

  it('không còn chuồng AVAILABLE đúng loại → availableKennels rỗng, không nhận được', async () => {
    await client.post('/api/kennels/3/start-maintenance', undefined, await manager());
    expect((await readiness(1)).data.data).toMatchObject({ availableKennels: [], canCheckIn: false });
  });

  it('409 khi không còn BOOKED; 404; CARETAKER được xem, VET / quản lý / khách thì không', async () => {
    const res = await failure(readiness(2));
    expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CHECKED_IN' });
    expect((await failure(readiness(999))).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
    expect((await readiness(4, await as(ACCOUNTS.caretaker))).status).toBe(200);
    for (const email of [ACCOUNTS.vet, ACCOUNTS.manager, ACCOUNTS.customer]) expect((await failure(readiness(4, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('POST /boarding-bookings/:id/check-in (#14)', () => {
  it('Bơ nhận đúng ngày: CHECKED_IN, chuồng OCCUPIED, ngày nhận giữ nguyên', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    const res = await checkIn(1);
    expect(res.data).toMatchObject({ message: 'Đã nhận thú lưu trú' });
    expect(res.data.data).toMatchObject({
      bookingId: 1,
      status: 'CHECKED_IN',
      kennelId: 3,
      kennelCode: 'C-03',
      checkInDate: '2026-10-30',
      checkOutDate: '2026-11-01',
      actualCheckInAt: '2026-10-30T02:00:00.000Z',
      missingCareLogToday: true,
    });
    expect(await kennelOf(3)).toMatchObject({ status: 'OCCUPIED', currentBookingId: 1 });
  });

  it('Bim nhận sớm 3 ngày sau khi tiêm đủ: ngày nhận về hôm nay, cân nặng INTAKE vào lịch sử, chuồng OCCUPIED, chiếm các đêm thực tế', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    const res = await checkIn(4, { weightKg: 6.25, belongings: 'Đệm, 1 gói pate', dietInstructions: 'Ăn hạt mềm' });
    expect(res.data.data).toMatchObject({ status: 'CHECKED_IN', checkInDate: '2026-10-23', checkOutDate: '2026-10-28', kennelCode: 'C-03', actualCheckInAt: '2026-10-23T02:00:00.000Z' });
    const owner = await as(ACCOUNTS.customer);
    const weights = (await client.get('/api/pets/2/weights', owner)).data.data;
    expect(weights[0]).toMatchObject({ weightKg: 6.25, source: 'INTAKE', boardingBookingId: 4 });
    expect((await client.get('/api/pets/2', owner)).data.data.currentWeightKg).toBe(6.25);
    // Đặt chỗ nhận sớm chiếm các đêm thực tế: 23/10 đã có Cốm + Mực + Bim.
    const nights = (await client.get('/api/branches/1/boarding-availability', { params: { kennelTypeId: 5, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }, ...owner })).data.data.nights;
    expect(nights[0]).toMatchObject({ occupied: 3, remaining: 0 });
  });

  it('thú đang lưu trú thì khóa đánh dấu mất (BR-KH-05), chuyển chủ (BR-KH-08) và xóa (BR-KH-06); rời chuồng thì mở lại', async () => {
    const { petId, bookingId } = await rexInStay();
    const owner = await as(ACCOUNTS.customer);
    const dead = await failure(client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: '2026-10-23', confirmed: true }, owner));
    expect(dead.data.message).toBe('Thú đang lưu trú, hãy kết thúc lưu trú trước (BR-LT-12) (BR-KH-05)');
    const moved = await failure(client.post(`/api/pets/${petId}/transfer-owner`, { newCustomerId: 4, previousOwnerConfirmed: true }, await desk()));
    expect(moved.data.message).toBe('Thú đang lưu trú, chưa chuyển chủ được (BR-KH-08)');
    expect((await failure(client.delete(`/api/pets/${petId}`, owner))).data.message).toContain('(BR-KH-06)');
    await post(`/api/boarding-bookings/${bookingId}/end-deceased`, { note: 'Thú mất do bệnh tim' });
    expect((await client.post(`/api/pets/${petId}/transfer-owner`, { newCustomerId: 4, previousOwnerConfirmed: true }, await desk())).status).toBe(200);
  });

  it('body: thiếu trường báo theo thứ tự; cân nặng > 0, tối đa 9999,99 và 2 chữ số thập phân; SĐT khẩn dạng 0xxxxxxxxx (10–11 số)', async () => {
    const empty = await failure(client.post('/api/boarding-bookings/4/check-in', {}, await as(ACCOUNTS.vet)));
    expect(empty.data).toMatchObject({
      errorCode: 'VALIDATION_FAILED',
      message:
        'kennelId: Vui lòng chọn chuồng; weightKg: Vui lòng nhập cân nặng; healthCondition: Vui lòng nhập tình trạng sức khỏe; emergencyPhone: Vui lòng nhập số điện thoại liên hệ khẩn',
    });
    expect((await checkInFail(4, { weightKg: 0 })).data.message).toBe('weightKg: Cân nặng phải lớn hơn 0');
    expect((await checkInFail(4, { weightKg: -1 })).data.message).toBe('weightKg: Cân nặng phải lớn hơn 0');
    expect((await checkInFail(4, { weightKg: 10000 })).data.message).toBe('weightKg: Cân nặng tối đa 9999.99 kg và 2 chữ số thập phân');
    expect((await checkInFail(4, { weightKg: 6.123 })).data.message).toBe('weightKg: Cân nặng tối đa 9999.99 kg và 2 chữ số thập phân');
    expect((await checkInFail(4, { healthCondition: '   ' })).data.message).toBe('healthCondition: Vui lòng nhập tình trạng sức khỏe');
    for (const phone of ['123456789', '012345678', '012345678901', '09a7000111', '']) {
      expect((await checkInFail(4, { emergencyPhone: phone })).data.message).toMatch(/^emergencyPhone: /);
    }
    expect((await checkInFail(4, { emergencyPhone: '0987000111' })).data.errorCode).not.toBe('VALIDATION_FAILED'); // 10 số hợp lệ
    expect((await checkInFail(4, { emergencyPhone: '09870001112' })).data.errorCode).not.toBe('VALIDATION_FAILED'); // 11 số hợp lệ
    expect((await checkInFail(4, { kennelId: 'x' })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await checkInFail(4, { weightKg: '6' })).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('vai trò: lễ tân và CARETAKER nhận được; quản lý, VET, khách bị 403; 404 đặt chỗ → 404 chuồng → 409', async () => {
    for (const email of [ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await checkInFail(4, {}, await as(email))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await checkInFail(999)).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
    expect((await checkInFail(4, { kennelId: 999 })).data.message).toBe('Không tìm thấy chuồng #999');
    // 404 chuồng đứng trước 409: đặt chỗ đã CHECKED_IN mà chuồng không có vẫn báo 404.
    expect((await checkInFail(2, { kennelId: 999 })).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    const stale = await checkInFail(2);
    expect(stale.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CHECKED_IN' });
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    expect((await checkIn(1, {}, await as(ACCOUNTS.caretaker))).data.data.status).toBe('CHECKED_IN');
  });

  it('thứ tự 400: ngoài giờ mở cửa (BR-LT-04) → chuồng (BR-LT-08) → mũi tiêm (BR-LT-05) → hết chỗ nhận sớm (BR-LT-03)', async () => {
    await get(4);
    at('2026-10-23T12:30:00+07:00');
    const closed = await checkInFail(4, { kennelId: 1 }); // chuồng đang có thú, thiếu mũi — vẫn báo giờ mở cửa trước
    expect(closed.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chỉ nhận thú trong giờ mở cửa của chi nhánh (BR-LT-04)' });
    at('2026-10-23T09:00:00+07:00');
    expect((await checkInFail(4, { kennelId: 1 })).data.message).toBe('Chuồng không ở trạng thái trống (BR-LT-08)');
    expect((await checkInFail(4, { kennelId: 4 })).data.message).toBe('Chuồng không ở trạng thái trống (BR-LT-08)');
    expect((await checkInFail(4)).data.message).toBe('Thú cưng thiếu hoặc quá hạn mũi tiêm bắt buộc khi lưu trú: 5 bệnh. Hãy tiếp nhận thú như walk-in để tiêm trước (BR-LT-05)');
    recordDoseForMock(2, 2, '2026-10-10');
    // Đêm 24/10 kín bởi Cốm + Mực + một đặt chỗ khác → nhận sớm 3 ngày bị BR-LT-03.
    const other = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
    await client.post('/api/boarding-bookings', { petId: other, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' }, await desk());
    const full = await checkInFail(4);
    expect(full.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Hết chỗ các đêm: 24/10 (BR-LT-03)' });
    expect((await get(4)).status).toBe('BOOKED');
    expect((await kennelOf(3)).status).toBe('AVAILABLE');
  });

  it('chuồng khác loại với đặt chỗ → BR-LT-08', async () => {
    await client.put('/api/branches/1/services/6', { enabled: true }, await manager());
    const cat = (await client.post('/api/kennels', { kennelTypeId: 6, code: 'M-01' }, await manager())).data.data;
    const res = await checkInFail(1, { kennelId: cat.kennelId });
    expect(res.data.message).toBe('Chuồng không đúng loại chuồng đã đặt (BR-LT-08)');
  });

  it('nhận xong chuồng không còn trống: đặt chỗ BOOKED khác cùng loại hết chuồng để nhận', async () => {
    const rex = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
    recordDoseForMock(rex, 1, '2026-10-10');
    recordDoseForMock(rex, 2, '2026-10-10');
    const today = (await client.post('/api/boarding-bookings', { petId: rex, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }, await desk())).data.data.booking;
    expect((await checkIn(today.bookingId)).data.data).toMatchObject({ status: 'CHECKED_IN', kennelCode: 'C-03' });
    expect((await readiness(4)).data.data).toMatchObject({ availableKennels: [], canCheckIn: false });
  });

  it('thú nhận xong xuất hiện ở danh sách đang lưu trú và đủ cờ chưa có nhật ký hôm nay', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    await checkIn(1);
    const rows = (await client.get('/api/boarding-bookings', { params: { missingCareLogToday: true }, ...(await desk()) })).data.data.content as { bookingId: number }[];
    expect(rows.map((r) => r.bookingId)).toContain(1);
  });
});

describe('POST /boarding-bookings/:id/cancel-no-kennel (#15)', () => {
  const cancelNoKennel = (id: number, auth?: object) => post(`/api/boarding-bookings/${id}/cancel-no-kennel`, undefined, auth);
  const notifications = async (email: string) => (await client.get('/api/me/notifications', { params: { size: 100 }, ...(await as(email)) })).data.data.content as { type: string; title: string; body: string; linkUrl: string | null; readAt: string | null }[];

  it('còn chuồng trống đúng loại → 400 BR-LT-08, hãy nhận thú', async () => {
    const res = await failure(cancelNoKennel(4));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Vẫn còn chuồng trống đúng loại, hãy nhận thú (BR-LT-08)' });
    expect((await get(4)).status).toBe('BOOKED');
  });

  it('hết chuồng: phòng khám hủy, không tính hủy muộn, báo khách (link tới đặt chỗ) và quản lý chi nhánh (link tới /admin/boarding)', async () => {
    await client.post('/api/kennels/3/start-maintenance', undefined, await manager());
    const customerBefore = (await notifications(ACCOUNTS.customer)).length;
    const managerBefore = (await notifications(ACCOUNTS.manager)).length;
    const res = await cancelNoKennel(4);
    expect(res.data).toMatchObject({ message: 'Đã hủy đặt chỗ vì không còn chuồng trống' });
    expect(res.data.data).toMatchObject({
      status: 'CANCELLED',
      cancelSource: 'CLINIC',
      lateCancel: false,
      cancelReason: 'Chi nhánh không còn chuồng trống đúng loại vào ngày nhận',
    });
    const mine = await notifications(ACCOUNTS.customer);
    expect(mine).toHaveLength(customerBefore + 1);
    expect(mine[0]).toMatchObject({ type: 'BOARDING_CANCELLED_BY_CLINIC', title: 'Phòng khám đã hủy đặt chỗ lưu trú của Bim', linkUrl: '/boarding-bookings/4', readAt: null });
    expect(mine[0].body).toContain('Lần hủy này không tính là hủy muộn.');
    const boss = await notifications(ACCOUNTS.manager);
    expect(boss).toHaveLength(managerBefore + 1);
    expect(boss[0]).toMatchObject({ type: 'BOARDING_CANCELLED_BY_CLINIC', linkUrl: '/admin/boarding' });
    // Không tính hủy muộn nên không thêm vi phạm cho khách.
    expect((await client.get('/api/me/booking-restriction', await as(ACCOUNTS.customer))).data.data.restricted).toBe(false);
  });

  it('409 khi không còn BOOKED; 404; chỉ lễ tân', async () => {
    expect((await failure(cancelNoKennel(2))).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CANCELLED');
    expect((await failure(cancelNoKennel(999))).status).toBe(404);
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await failure(cancelNoKennel(4, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('POST /boarding-bookings/:id/start-checkout (#16)', () => {
  it('Cốm ở từ 21/10, hôm nay 23/10 trước 12:00: 2 đêm × 200.000 đ, Order BOARDING PENDING 1 dòng, activeOrderId trỏ tới Order', async () => {
    const res = await startCheckout(2);
    expect(res.data).toMatchObject({ message: 'Đã bắt đầu trả thú' });
    const { booking, orderId, nights, amount } = res.data.data;
    expect([nights, amount]).toEqual([2, 400000]);
    expect(booking).toMatchObject({ bookingId: 2, status: 'CHECKED_IN', activeOrderId: orderId });
    const o = await order(orderId);
    expect(o).toMatchObject({ source: 'BOARDING', status: 'PENDING', boardingBookingId: 2, branchId: 1, customerId: 9002, totalAmount: 400000 });
    expect(o.lines).toEqual([expect.objectContaining({ lineType: 'BOARDING', description: 'Lưu trú Chuồng chó nhỏ × 2 đêm', quantity: 2, unitPrice: 200000, lineTotal: 400000, canEdit: false, canDelete: false })]);
    expect((await get(2)).activeOrderId).toBe(orderId);
  });

  it('trả sau 12:00 (giờ làm việc buổi chiều) tính thêm 1 đêm', async () => {
    await get(2);
    at('2026-10-23T13:30:00+07:00');
    const res = await startCheckout(2);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([3, 600000]);
  });

  it('số đêm tối thiểu 1: nhận và trả trong cùng ngày vẫn tính 1 đêm', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    await checkIn(1);
    const res = await startCheckout(1);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([1, 200000]);
  });

  it('đã có Order PENDING thì dùng lại và tính lại số đêm, không sinh Order thứ hai', async () => {
    const first = (await startCheckout(2)).data.data;
    at('2026-10-24T09:00:00+07:00');
    const second = (await startCheckout(2)).data.data;
    expect(second.orderId).toBe(first.orderId);
    expect([second.nights, second.amount]).toEqual([3, 600000]);
    const orders = (await client.get('/api/orders', { params: { boardingBookingId: 2 }, ...(await desk()) })).data.data.content;
    expect(orders).toHaveLength(1);
    expect((await order(first.orderId)).lines).toHaveLength(1);
  });

  it('OVERDUE vẫn trả được và tính tiếp theo đêm', async () => {
    await get(2);
    at('2026-10-31T13:30:00+07:00');
    expect((await get(2)).status).toBe('OVERDUE');
    const res = await startCheckout(2);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([11, 2200000]); // 21/10→31/10 = 10 đêm, quá 12:00 +1
    expect(res.data.data.booking.status).toBe('OVERDUE');
  });

  it('ngoài giờ mở cửa → 400 BR-LT-04; thú không còn trong chuồng → 409 (kiểm trước giờ mở cửa)', async () => {
    await get(2);
    at('2026-10-23T12:30:00+07:00');
    expect((await failure(startCheckout(2))).data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chỉ trả thú trong giờ mở cửa của chi nhánh (BR-LT-04)' });
    const gone = await failure(startCheckout(4));
    expect(gone.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Thú cưng không còn trong chuồng' });
    expect((await failure(startCheckout(5))).status).toBe(409);
    expect((await failure(startCheckout(6))).status).toBe(409);
  });

  it('chỉ lễ tân; 404', async () => {
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await failure(startCheckout(2, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(startCheckout(999))).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
  });
});

describe('vòng trả thú: bắt đầu → thu tiền → giao thú; hủy phiên (Review Focus #4)', () => {
  const openShift = async () => client.post('/api/cashier-shifts', { afterHours: false }, await desk());
  const pay = async (orderIds: number[], amount: number) => client.post('/api/payments', { orderIds, method: 'CASH', amount }, await desk());
  const abort = (orderId: number, reason?: unknown, auth?: object) => post(`/api/orders/${orderId}/abort-checkout`, reason === undefined ? {} : { reason }, auth);

  it('giao thú khi Order chưa PAID → 400 BR-LT-09, chưa có Order cũng vậy', async () => {
    const noOrder = await failure(handOver(2));
    expect(noOrder.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Order lưu trú chưa thanh toán (BR-LT-09)' });
    await startCheckout(2);
    expect((await failure(handOver(2))).data.message).toBe('Order lưu trú chưa thanh toán (BR-LT-09)');
    expect((await get(2)).status).toBe('CHECKED_IN');
    expect((await kennelOf(1)).status).toBe('OCCUPIED');
  });

  it('thu tiền ở máy thu ngân (ca mở) → Order PAID → vẫn thấy activeOrderId, start-checkout lại trả Order cũ, giao thú được', async () => {
    const { orderId, amount } = (await startCheckout(2)).data.data;
    await openShift();
    const paid = await pay([orderId], amount);
    expect(paid.status).toBe(201);
    expect((await order(orderId)).status).toBe('PAID');
    expect((await get(2)).activeOrderId).toBe(orderId);
    // Order đã PAID không tính lại số đêm dù sang ngày mới (BR-LT-09: mỗi đặt chỗ tối đa 1 Order chưa kết thúc).
    at('2026-10-24T09:00:00+07:00');
    const nextDay = (await startCheckout(2)).data.data;
    expect([nextDay.orderId, nextDay.nights, nextDay.amount]).toEqual([orderId, 2, amount]);
    at('2026-10-23T09:00:00+07:00');
    const again = (await startCheckout(2)).data.data;
    expect(again.orderId).toBe(orderId);
    const res = await handOver(2);
    expect(res.data).toMatchObject({ message: 'Đã giao thú' });
    expect(res.data.data).toMatchObject({ status: 'CHECKED_OUT', endReason: 'RETURNED', actualCheckOutAt: '2026-10-23T02:00:00.000Z', activeOrderId: null });
    expect(await kennelOf(1)).toMatchObject({ status: 'AVAILABLE', currentBookingId: null });
    expect((await get(2)).activeOrderId).toBeNull();
    const inStay = (await client.get('/api/boarding-bookings', { params: { inStay: true }, ...(await desk()) })).data.data.content.map((b: { bookingId: number }) => b.bookingId);
    expect(inStay).toEqual([3]);
    expect((await failure(handOver(2))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('khách không trả: hủy phiên (lý do bắt buộc) → Order CANCELLED CHECKOUT_ABORTED, đặt chỗ giữ CHECKED_IN, bắt đầu lại sinh Order mới', async () => {
    const first = (await startCheckout(2)).data.data;
    const noReason = await failure(abort(first.orderId, '   '));
    expect(noReason.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Vui lòng nhập lý do hủy phiên trả thú (BR-LT-09)' });
    expect((await failure(abort(first.orderId))).data.message).toBe('Vui lòng nhập lý do hủy phiên trả thú (BR-LT-09)');
    expect((await failure(abort(first.orderId, 'x'.repeat(301)))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'reason: Lý do tối đa 300 ký tự' });

    const res = await abort(first.orderId, '  Khách chưa mang đủ tiền  ');
    expect(res.data).toMatchObject({ message: 'Đã hủy phiên trả thú' });
    expect(res.data.data).toMatchObject({ orderId: first.orderId, status: 'CANCELLED', cancelType: 'CHECKOUT_ABORTED', cancelReason: 'Khách chưa mang đủ tiền', source: 'BOARDING' });
    const booking = await get(2);
    expect(booking).toMatchObject({ status: 'CHECKED_IN', activeOrderId: null });
    expect((await kennelOf(1)).status).toBe('OCCUPIED');

    const second = (await startCheckout(2)).data.data;
    expect(second.orderId).not.toBe(first.orderId);
    expect(second.booking.activeOrderId).toBe(second.orderId);
    expect((await get(2)).activeOrderId).toBe(second.orderId);
    expect((await order(first.orderId)).status).toBe('CANCELLED');
  });

  it('hủy phiên: 409 khi không phải Order BOARDING đang PENDING (đã PAID, đã hủy, Order bán lẻ); kiểm trước lý do', async () => {
    const { orderId, amount } = (await startCheckout(2)).data.data;
    await abort(orderId, 'Khách chưa trả');
    const cancelled = await failure(abort(orderId, 'Lần hai'));
    expect(cancelled.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    const retail = await failure(abort(4)); // Order bán lẻ PENDING mẫu — không lý do vẫn là 409
    expect(retail.data.errorCode).toBe('INVALID_STATE_TRANSITION');
    const next = (await startCheckout(2)).data.data;
    await openShift();
    await pay([next.orderId], amount);
    expect((await failure(abort(next.orderId, 'Đã trả rồi'))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('hủy phiên: chỉ lễ tân; 404; Order chi nhánh khác → 403 phạm vi (sau 404)', async () => {
    const { orderId } = (await startCheckout(2)).data.data;
    for (const email of [ACCOUNTS.manager, ACCOUNTS.caretaker, ACCOUNTS.vet]) expect((await failure(abort(orderId, 'x', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(abort(999, 'x'))).data.message).toBe('Không tìm thấy đơn hàng #999');
    expect((await failure(abort(8, 'x'))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
  });

  it('thú đã rời chuồng: không hủy phiên được (400 BR-LT-09, dùng hủy Order khách không thanh toán); quản lý hủy Order được', async () => {
    const { orderId } = (await post('/api/boarding-bookings/2/end-deceased', { note: 'Thú lên cơn co giật lúc 8h sáng' })).data.data;
    const res = await failure(abort(orderId, 'Hủy'));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Thú cưng không còn trong chuồng, hãy hủy Order theo luồng khách không thanh toán (BR-LT-09)' });
    const cancelled = await client.post(`/api/orders/${orderId}/cancel-unpaid`, { reason: 'Khách bỏ về, không liên lạc được' }, await manager());
    expect(cancelled.data.data).toMatchObject({ status: 'CANCELLED', cancelType: 'UNPAID' });
  });

  it('quản lý không hủy được Order lưu trú khi thú còn trong chuồng (xử lý theo BR-LT-09)', async () => {
    const { orderId } = (await startCheckout(2)).data.data;
    const res = await failure(client.post(`/api/orders/${orderId}/cancel-unpaid`, { reason: 'Muốn hủy' }, await manager()));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Order lưu trú khi thú còn trong chuồng phải hủy phiên trả thú (BR-LT-09)' });
    expect((await order(orderId)).status).toBe('PENDING');
  });

  it('Order BOARDING PENDING hiện trong "Order chờ thu" của khách (bàn Lễ tân) và thu được bằng POST /payments (A11)', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    await checkIn(4);
    const { orderId, amount } = (await startCheckout(4)).data.data;
    const payable = (await client.get('/api/customers/1/payable-orders', await desk())).data.data as { orderId: number; source: string; totalAmount: number }[];
    expect(payable).toEqual(expect.arrayContaining([expect.objectContaining({ orderId, source: 'BOARDING', totalAmount: amount })]));
    await openShift();
    expect((await pay([orderId], amount)).status).toBe(201);
    expect(((await client.get('/api/customers/1/payable-orders', await desk())).data.data as { orderId: number }[]).map((o) => o.orderId)).not.toContain(orderId);
    expect((await handOver(4)).data.data.status).toBe('CHECKED_OUT');
  });
});

describe('POST /boarding-bookings/:id/end-deceased (#18)', () => {
  const deceased = (id: number, note?: unknown, auth?: object) => post(`/api/boarding-bookings/${id}/end-deceased`, note === undefined ? {} : { note }, auth);

  it('kết thúc do thú mất: CHECKED_OUT / PET_DECEASED, chuồng về AVAILABLE, Order PENDING tính đến hôm nay, trả CheckoutStarted', async () => {
    const res = await deceased(2, '  Thú lên cơn co giật lúc 8h sáng ');
    expect(res.data).toMatchObject({ message: 'Đã kết thúc lưu trú do thú mất' });
    const { booking, orderId, nights, amount } = res.data.data;
    expect([nights, amount]).toEqual([2, 400000]);
    expect(booking).toMatchObject({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED', actualCheckOutAt: '2026-10-23T02:00:00.000Z', activeOrderId: null });
    expect(await kennelOf(1)).toMatchObject({ status: 'AVAILABLE', currentBookingId: null });
    expect(await order(orderId)).toMatchObject({ source: 'BOARDING', status: 'PENDING', boardingBookingId: 2 });
  });

  it('đã bắt đầu trả thú trước đó thì dùng lại Order PENDING (BR-LT-09)', async () => {
    const started = (await startCheckout(2)).data.data;
    const res = await deceased(2, 'Thú mất');
    expect(res.data.data.orderId).toBe(started.orderId);
  });

  it('thú mất xong mới đánh dấu đã mất được (BR-LT-12): đang lưu trú thì bị chặn', async () => {
    const { petId, bookingId } = await rexInStay();
    const owner = await as(ACCOUNTS.customer);
    expect((await failure(client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: '2026-10-23', confirmed: true }, owner))).data.message).toContain('hãy kết thúc lưu trú trước');
    const res = await deceased(bookingId, 'Thú mất do bệnh tim');
    expect([res.data.data.nights, res.data.data.amount]).toEqual([1, 200000]); // nhận và mất cùng ngày vẫn tính tối thiểu 1 đêm
    expect((await client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: '2026-10-23', confirmed: true }, owner)).status).toBe(200);
  });

  it('ghi chú bắt buộc; sai kiểu; thú không còn trong chuồng → 409; chỉ lễ tân; 404', async () => {
    expect((await failure(deceased(2))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'note: Vui lòng ghi rõ diễn biến' });
    expect((await failure(deceased(2, '   '))).data.message).toBe('note: Vui lòng ghi rõ diễn biến');
    expect((await failure(deceased(2, 5))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(deceased(4, 'Không ở trong chuồng'))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Thú cưng không còn trong chuồng' });
    expect((await failure(deceased(999, 'x'))).status).toBe(404);
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet]) expect((await failure(deceased(2, 'x', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(deceased(2, undefined, await as(ACCOUNTS.vet)))).data.errorCode).toBe('VALIDATION_FAILED');
  });
});

describe('hand-over (#17) — quyền', () => {
  it('chỉ lễ tân; 404; thú không còn trong chuồng → 409', async () => {
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await failure(handOver(2, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(handOver(999))).status).toBe(404);
    expect((await failure(handOver(4))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Thú cưng không còn trong chuồng' });
  });
});

describe('đặt lại sau khi đặt chỗ cũ đã CHECKED_OUT (Review Focus #1)', () => {
  it('đặt chỗ đã CHECKED_OUT không còn chặn đặt chồng ngày của cùng thú; đang CHECKED_IN thì còn chặn', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    await checkIn(4); // 23/10→28/10, CHECKED_IN
    const again = { petId: 2, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' };
    const blocked = await failure(client.post('/api/boarding-bookings', again, await desk()));
    expect(blocked.data.message).toBe('Thú cưng đã có đặt chỗ lưu trú trùng ngày (BR-LT-02)');
    const { orderId, amount } = (await startCheckout(4)).data.data;
    await client.post('/api/cashier-shifts', { afterHours: false }, await desk());
    await client.post('/api/payments', { orderIds: [orderId], method: 'CASH', amount }, await desk());
    await handOver(4);
    expect((await client.post('/api/boarding-bookings', again, await desk())).status).toBe(201);
  });
});
