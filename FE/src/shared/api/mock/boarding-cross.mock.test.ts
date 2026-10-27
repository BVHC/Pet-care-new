import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';
import { recordDoseForMock } from './visit-clinical.mock';

// Tác vụ hệ thống chạy lười (ST05 NO_SHOW, ST15 OVERDUE, hủy do phòng khám) và liên thông với các mock khác (chi nhánh, thú cưng, thông báo).
// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). Dữ liệu mẫu: xem boarding.mock.test.ts.

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
type Booking = { bookingId: number; status: string; cancelSource: string | null; cancelReason: string | null; lateCancel: boolean; overdueSince: string | null; checkInDate: string; kennelCode: string | null; [k: string]: unknown };
const get = async (id: number, auth?: object): Promise<Booking> => (await client.get(`/api/boarding-bookings/${id}`, auth ?? (await desk()))).data.data;
const unread = async (email: string) => (await client.get('/api/me/notifications/unread-count', await as(email))).data.data.count as number;
const notifications = async (email: string) => (await client.get('/api/me/notifications', { params: { size: 100 }, ...(await as(email)) })).data.data.content as { type: string; title: string; body: string; linkUrl: string | null }[];

/** Sửa thẳng state lưu trong localStorage (dựng tình huống mà API không tạo ra được). */
function patchState(edit: (s: { bookings: Record<string, unknown>[] }) => void) {
  const s = JSON.parse(localStorage.getItem('petcare-mock-boarding') ?? 'null');
  edit(s);
  localStorage.setItem('petcare-mock-boarding', JSON.stringify(s));
}

describe('ST05 — BOOKED quá giờ làm việc ngày nhận thành NO_SHOW (BR-LT-06)', () => {
  it('mốc = giờ đóng cửa muộn nhất của ngày nhận: 18:59:59 vẫn BOOKED, 19:00:00 là NO_SHOW (thứ Sáu 30/10)', async () => {
    await get(1);
    at('2026-10-30T18:59:59+07:00');
    expect((await get(1)).status).toBe('BOOKED');
    at('2026-10-30T19:00:00+07:00');
    expect(await get(1)).toMatchObject({ status: 'NO_SHOW', cancelSource: null, lateCancel: false });
  });

  it('Chủ nhật chỉ mở buổi sáng: mốc 12:00', async () => {
    await get(1);
    patchState((s) => {
      s.bookings[0].checkInDate = '2026-10-25';
      s.bookings[0].checkOutDate = '2026-10-26';
    });
    at('2026-10-25T11:59:59+07:00');
    expect((await get(1)).status).toBe('BOOKED');
    at('2026-10-25T12:00:00+07:00');
    expect((await get(1)).status).toBe('NO_SHOW');
  });

  it('ngày nghỉ (không có khung giờ): mốc là 00:00 ngày kế tiếp', async () => {
    await get(1);
    // mock chi nhánh chỉ lưu state khi có thay đổi, nên ghi một lần để ngày nghỉ mẫu (12/11) đứng yên khi đồng hồ chạy tiếp
    await client.put('/api/branches/1/services/1', { enabled: true }, await manager());
    patchState((s) => {
      s.bookings[0].checkInDate = '2026-11-12';
      s.bookings[0].checkOutDate = '2026-11-13';
    });
    at('2026-11-12T23:59:59+07:00');
    expect((await get(1)).status).toBe('BOOKED');
    at('2026-11-13T00:00:00+07:00');
    expect((await get(1)).status).toBe('NO_SHOW');
  });

  it('chỉ chuyển BOOKED chưa nhận: đã CHECKED_IN hoặc đã hủy thì giữ nguyên; NO_SHOW giải phóng chỗ', async () => {
    await get(4);
    at('2026-10-26T19:00:00+07:00');
    expect((await get(4)).status).toBe('NO_SHOW');
    expect((await get(2)).status).toBe('CHECKED_IN');
    expect((await get(6)).status).toBe('CANCELLED');
    const free = await client.get('/api/branches/1/boarding-availability', { params: { kennelTypeId: 5, checkInDate: '2026-10-26', checkOutDate: '2026-10-27' }, ...(await as(ACCOUNTS.desk)) });
    expect(free.data.data.nights[0]).toMatchObject({ occupied: 2, remaining: 1 });
  });

  it('ST05 không đẩy thông báo cho khách (03 #6 "—")', async () => {
    const before = { unread: await unread(ACCOUNTS.customer), all: (await notifications(ACCOUNTS.customer)).length };
    await get(4);
    at('2026-10-26T19:00:00+07:00');
    expect((await get(4)).status).toBe('NO_SHOW');
    expect(await unread(ACCOUNTS.customer)).toBe(before.unread);
    expect(await notifications(ACCOUNTS.customer)).toHaveLength(before.all);
  });
});

describe('ST15 — CHECKED_IN quá 12:00 ngày trả dự kiến thành OVERDUE (BR-LT-10, giả định của T48: BE chưa có)', () => {
  it('đúng 12:00:00 vẫn CHECKED_IN, 12:00:01 là OVERDUE kèm overdueSince = 12:00 ngày trả; chuồng vẫn OCCUPIED', async () => {
    await get(2);
    at('2026-10-31T12:00:00+07:00');
    expect(await get(2)).toMatchObject({ status: 'CHECKED_IN', overdueSince: null });
    at('2026-10-31T12:00:01+07:00');
    expect(await get(2)).toMatchObject({ status: 'OVERDUE', overdueSince: '2026-10-31T05:00:00.000Z', kennelCode: 'C-01' });
    const kennels = (await client.get('/api/kennels', await desk())).data.data as { kennelId: number; status: string; currentBookingId: number | null }[];
    expect(kennels.find((k) => k.kennelId === 1)).toMatchObject({ status: 'OCCUPIED', currentBookingId: 2 });
    const stay = (await client.get('/api/boarding-bookings', { params: { inStay: true }, ...(await desk()) })).data.data.content.map((b: { bookingId: number }) => b.bookingId);
    expect(stay).toEqual([3, 2]);
  });

  it('gia hạn trước ngày trả đẩy mốc OVERDUE ra sau', async () => {
    await get(2);
    at('2026-10-30T10:00:00+07:00');
    await client.post('/api/boarding-bookings/2/extend', { checkOutDate: '2026-11-02' }, await desk());
    at('2026-10-31T13:00:00+07:00');
    expect((await get(2)).status).toBe('CHECKED_IN');
    at('2026-11-02T12:00:01+07:00');
    expect(await get(2)).toMatchObject({ status: 'OVERDUE', overdueSince: '2026-11-02T05:00:00.000Z' });
  });

  it('ST15 không đẩy thông báo (Care Task / thông báo hằng ngày thuộc T43)', async () => {
    const before = await unread(ACCOUNTS.desk);
    await get(2);
    at('2026-10-31T13:00:00+07:00');
    await get(2);
    expect(await unread(ACCOUNTS.desk)).toBe(before);
  });
});

describe('hủy do phòng khám: thú chuyển chủ / đã mất (Đặt chỗ#5, BR-KH-05, BR-KH-08)', () => {
  it('chuyển chủ: đặt chỗ BOOKED bị phòng khám hủy, không tính hủy muộn, báo chủ lúc đặt một lần; đặt chỗ đã kết thúc giữ nguyên', async () => {
    await get(4);
    const before = (await notifications(ACCOUNTS.customer)).length;
    const moved = await client.post('/api/pets/2/transfer-owner', { newCustomerId: 4, previousOwnerConfirmed: true }, await desk());
    expect(moved.status).toBe(200);
    expect(await get(4)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', lateCancel: false, cancelReason: 'Thú cưng đã được chuyển sang chủ mới' });
    expect((await get(5)).status).toBe('CHECKED_OUT');
    expect((await get(6)).cancelSource).toBe('CUSTOMER');
    const mine = await notifications(ACCOUNTS.customer);
    expect(mine).toHaveLength(before + 1);
    expect(mine[0]).toMatchObject({ type: 'BOARDING_CANCELLED_BY_CLINIC', title: 'Phòng khám đã hủy đặt chỗ lưu trú của Bim', linkUrl: '/boarding-bookings/4' });
    expect(mine[0].body).toContain('Thú cưng đã được chuyển sang chủ mới');
    await get(4);
    expect(await notifications(ACCOUNTS.customer)).toHaveLength(before + 1);
    expect((await client.get('/api/me/booking-restriction', await as(ACCOUNTS.customer))).data.data.restricted).toBe(false);
  });

  it('đánh dấu đã mất: BOOKED tương lai bị hủy với lý do thú đã mất', async () => {
    await get(4);
    await client.post('/api/pets/2/mark-deceased', { deceasedOn: '2026-10-22', confirmed: true }, await as(ACCOUNTS.customer));
    expect(await get(4)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', cancelReason: 'Thú cưng đã được ghi nhận là đã mất' });
  });

  it('thú đã rời chuồng thì chuyển chủ lại được; đặt chỗ BOOKED khác của thú đó bị hủy do chuyển chủ', async () => {
    const rex = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
    recordDoseForMock(rex, 1, '2026-10-10');
    recordDoseForMock(rex, 2, '2026-10-10');
    const book = async (checkInDate: string, checkOutDate: string) =>
      (await client.post('/api/boarding-bookings', { petId: rex, branchId: 1, kennelTypeId: 5, checkInDate, checkOutDate }, await desk())).data.data.booking.bookingId as number;
    const stay = await book('2026-10-23', '2026-10-24');
    const later = await book('2026-10-28', '2026-10-29');
    await client.post(`/api/boarding-bookings/${stay}/check-in`, { kennelId: 3, weightKg: 6.2, healthCondition: 'Khỏe', emergencyPhone: '0987000111' }, await desk());
    const refused = await failure(client.post(`/api/pets/${rex}/transfer-owner`, { newCustomerId: 4, previousOwnerConfirmed: true }, await desk()));
    expect(refused.data.message).toContain('(BR-KH-08)');
    expect((await get(later)).status).toBe('BOOKED');
    await client.post(`/api/boarding-bookings/${stay}/end-deceased`, { note: 'Thú mất' }, await desk());
    expect((await client.post(`/api/pets/${rex}/transfer-owner`, { newCustomerId: 4, previousOwnerConfirmed: true }, await desk())).status).toBe(200);
    expect(await get(later)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC' });
    expect((await get(stay)).status).toBe('CHECKED_OUT');
  });
});

describe('chi nhánh đổi lịch: đặt chỗ lưu trú BOOKED bị ảnh hưởng (BR-LH-10, BR-CN-03, BR-CN-04)', () => {
  it('xem trước ngày nghỉ rơi vào ngày nhận: liệt kê đặt chỗ thật của mock lưu trú', async () => {
    const impact = await client.post('/api/branches/1/holidays/impact', { holidayDate: '2026-10-26' }, await manager());
    expect(impact.data.data.boardingBookings).toEqual([
      { bookingId: 4, code: expect.stringMatching(/^LT-2610-\d{5}$/), checkInDate: '2026-10-26', checkOutDate: '2026-10-28', petName: 'Bim', customerName: 'Nguyễn Thu Trang', customerPhone: '0912345678' },
    ]);
    const byCheckOut = await client.post('/api/branches/1/holidays/impact', { holidayDate: '2026-11-01' }, await manager());
    expect(byCheckOut.data.data.boardingBookings.map((b: { petName: string }) => b.petName)).toEqual(['Bơ']);
    const none = await client.post('/api/branches/1/holidays/impact', { holidayDate: '2026-10-27' }, await manager());
    expect(none.data.data.boardingBookings).toEqual([]);
  });

  it('thêm ngày nghỉ có đặt chỗ phải chọn hủy hàng loạt; hủy xong đặt chỗ thành CANCELLED do phòng khám và khách được báo', async () => {
    const refused = await failure(client.post('/api/branches/1/holidays', { holidayDate: '2026-10-26' }, await manager()));
    expect(refused.data.message).toBe('Ngày 2026-10-26 còn 0 lịch hẹn và 1 đặt chỗ lưu trú; xem danh sách và chọn hủy hàng loạt để tiếp tục (BR-LH-10)');
    expect((await get(4)).status).toBe('BOOKED');
    const before = (await notifications(ACCOUNTS.customer)).length;
    const created = await client.post('/api/branches/1/holidays', { holidayDate: '2026-10-26', reason: 'Sửa điện', cancelAffected: true }, await manager());
    expect(created.status).toBe(201);
    expect(await get(4)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', lateCancel: false, cancelReason: 'Chi nhánh nghỉ vào ngày nhận hoặc ngày trả của đặt chỗ' });
    const mine = await notifications(ACCOUNTS.customer);
    expect(mine).toHaveLength(before + 1);
    expect(mine[0]).toMatchObject({ type: 'BOARDING_CANCELLED_BY_CLINIC', linkUrl: '/boarding-bookings/4' });
    const impact = await client.post('/api/branches/1/holidays/impact', { holidayDate: '2026-10-26' }, await manager());
    expect(impact.data.data.boardingBookings).toEqual([]);
  });

  it('giờ mở cửa mới đóng cửa Chủ nhật: đặt chỗ trả vào Chủ nhật bị hủy với lý do giờ mở cửa', async () => {
    const days = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
      dayOfWeek,
      ranges: dayOfWeek === 7 ? [] : [{ open: '08:00', close: '12:00' }, { open: '13:30', close: '19:00' }],
    }));
    const body = { effectiveFrom: '2026-10-24', days };
    const impact = await client.post('/api/branches/1/opening-hours/impact', body, await manager());
    expect(impact.data.data.boardingBookings.map((b: { petName: string }) => b.petName)).toEqual(['Bơ']);
    await client.put('/api/branches/1/opening-hours', { ...body, cancelAffected: true }, await manager());
    expect(await get(1)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', cancelReason: 'Chi nhánh thay đổi giờ mở cửa, ngày nhận hoặc ngày trả nằm ngoài giờ mở cửa mới' });
  });

  it('đặt chỗ đã NO_SHOW / CANCELLED / đang lưu trú không nằm trong danh sách bị ảnh hưởng', async () => {
    await get(4);
    at('2026-10-30T19:00:00+07:00');
    const impact = await client.post('/api/branches/1/holidays/impact', { holidayDate: '2026-11-01' }, await manager());
    expect(impact.data.data.boardingBookings).toEqual([]);
  });
});

describe('chuồng của chi nhánh khác', () => {
  it('nhận thú vào chuồng của chi nhánh khác → 404 chuồng, như không tồn tại', async () => {
    await get(4);
    patchState((s) => {
      (s as unknown as { kennels: Record<string, unknown>[] }).kennels.push({ kennelId: 99, branchId: 2, kennelTypeId: 5, code: 'X-01', note: null, status: 'AVAILABLE' });
    });
    const res = await failure(client.post('/api/boarding-bookings/1/check-in', { kennelId: 99, weightKg: 6.2, healthCondition: 'Khỏe', emergencyPhone: '0987000111' }, await desk()));
    expect(res.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy chuồng #99' });
  });
});

describe('mock khác trống hoặc thiếu không làm mock lưu trú throw', () => {
  it('kho thú / danh mục rỗng: đặt chỗ mẫu hiện tên đã lưu', async () => {
    localStorage.setItem('petcare-mock-pets', JSON.stringify({ nextPetId: 1, nextWeightId: 1, pets: [], weights: [] }));
    localStorage.setItem('petcare-mock-catalog', JSON.stringify({ seq: 100, categories: [], products: [], services: [], vaccineTypes: [], protocols: [] }));
    const res = await client.get('/api/boarding-bookings', await desk());
    const names = res.data.data.content.map((b: { petName: string }) => b.petName);
    expect(names).toContain('Bim');
    expect(names).toContain('Bơ');
    expect(res.data.data.content[0]).toMatchObject({ kennelTypeName: 'Loại chuồng #5' });
    expect((await client.get('/api/kennels', await desk())).status).toBe(200);
    expect((await client.post('/api/branches/1/holidays/impact', { holidayDate: '2026-10-26' }, await manager())).status).toBe(200);
  });
});
