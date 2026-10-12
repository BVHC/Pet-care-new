import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { addDays, localIsoDate, nextWeekday } from '../../utils/branch-schedule';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (branch.mock.ts): chi nhánh 1 ACTIVE (manager@store1.vn), 2 DRAFT có quản lý (manager2@store2.vn),
// 3 DRAFT chưa có quản lý. Chi nhánh 1 có lịch hẹn thứ Ba tới 09:00 (Mochi), 18:30 (Lucky), thứ Năm tới 14:00 (Miu)
// và đặt chỗ lưu trú nhận thứ Sáu tới, trả Chủ nhật kế đó (Bơ).
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const chain = () => as('chuoi@petcare.vn');
const manager = () => as('manager@store1.vn');
const manager2 = () => as('manager2@store2.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const today = localIsoDate();
const tomorrow = addDays(today, 1);
const tuesday = nextWeekday(today, 2);

type Range = { open: string; close: string };
const week = (ranges: Range[], override: Record<number, Range[]> = {}) =>
  [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, ranges: override[dayOfWeek] ?? ranges }));
const STANDARD: Range[] = [
  { open: '08:00', close: '12:00' },
  { open: '13:30', close: '19:00' },
];
const SUNDAY_MORNING = { 7: [{ open: '08:00', close: '12:00' }] };

describe('branches (UC12)', () => {
  it('lists every branch for the chain manager and only their own for a branch manager', async () => {
    const all = await client.get('/api/branches', await chain());
    expect(all.data.data.map((b: { name: string }) => b.name)).toEqual(['PetCare Cầu Giấy', 'PetCare Hà Đông', 'PetCare Long Biên']);

    const own = await client.get('/api/branches', await manager());
    expect(own.data.data.map((b: { branchId: number }) => b.branchId)).toEqual([1]);
  });

  it("keeps a branch manager out of another branch", async () => {
    const res = await failure(client.get('/api/branches/2', await manager()));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH', statusCode: 403, message: 'Không có quyền truy cập dữ liệu này' });
  });

  it('creates a DRAFT branch and refuses a duplicate name (BR-CN-01)', async () => {
    const auth = await chain();
    const body = { name: 'PetCare Tây Hồ', address: '12 Xuân Diệu, Tây Hồ', phone: '0243123456', latitude: 21.06, longitude: 105.83 };
    const created = await client.post('/api/branches', body, auth);
    expect(created.status).toBe(201);
    expect(created.data).toMatchObject({
      message: 'Đã tạo chi nhánh',
      data: { ...body, status: 'DRAFT', acceptsAfterHoursEmergency: false, activatedAt: null },
    });

    const dup = await failure(client.post('/api/branches', body, auth));
    expect(dup.data.message).toBe('Tên chi nhánh đã tồn tại (BR-CN-01)');
  });

  it('reports missing fields one by one like the BE', async () => {
    const res = await failure(client.post('/api/branches', {}, await chain()));
    expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    expect(res.data.message).toContain('name: Tên chi nhánh không được để trống');
    expect(res.data.message).toContain('latitude: Thiếu vĩ độ');
  });

  it('lets only the chain manager create a branch', async () => {
    const res = await failure(client.post('/api/branches', { name: 'X' }, await manager()));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', message: 'Không có quyền thực hiện thao tác này' });
  });

  it.each([
    [3, 'Không kích hoạt được chi nhánh: chưa có BRANCH_MANAGER, chưa cấu hình giờ mở cửa (BR-QT-04)'],
    [2, 'Không kích hoạt được chi nhánh: chưa cấu hình giờ mở cửa (BR-CN-01)'],
  ])('says what branch %i still lacks before activation', async (branchId, message) => {
    const res = await failure(client.post(`/api/branches/${branchId}/activate`, null, await chain()));
    expect(res.data.message).toBe(message);
  });

  it('refuses to activate an active branch (409)', async () => {
    const res = await failure(client.post('/api/branches/1/activate', null, await chain()));
    expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
  });

  it('activates a draft branch once its manager has set opening hours effective today', async () => {
    await client.put('/api/branches/2/opening-hours', { effectiveFrom: today, days: week(STANDARD) }, await manager2());
    const res = await client.post('/api/branches/2/activate', null, await chain());
    expect(res.data).toMatchObject({ message: 'Đã kích hoạt chi nhánh', data: { status: 'ACTIVE', activatedAt: expect.any(String) } });
  });
});

describe('opening hours (UC14)', () => {
  it('lists the version in force with all seven days', async () => {
    const res = await client.get('/api/branches/1/opening-hours', await manager());
    expect(res.data.data).toHaveLength(1);
    expect(res.data.data[0].days.map((d: { dayOfWeek: number }) => d.dayOfWeek)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(res.data.data[0].days[6].ranges).toEqual([{ open: '08:00', close: '12:00' }]);
  });

  it.each([
    [week(STANDARD, { 1: [{ open: '07:00', close: '08:00' }, ...STANDARD] }), 'Thứ 1 có quá 2 khoảng giờ mở cửa (BR-CN-02)'],
    [week(STANDARD).slice(0, 6), 'Phải khai báo giờ mở cửa đủ 7 ngày trong tuần (BR-CN-02)'],
    [week(STANDARD, { 2: [{ open: '08:00', close: '12:00' }, { open: '11:00', close: '13:00' }] }), 'Thứ 2: các khoảng giờ mở cửa không được chồng nhau (BR-CN-02)'],
    [week(STANDARD, { 3: [{ open: '12:00', close: '08:00' }] }), 'Thứ 3: giờ mở cửa phải trước giờ đóng cửa (BR-CN-02)'],
  ])('refuses invalid hours with the BE message (BR-CN-02) #%#', async (days, message) => {
    const res = await failure(client.put('/api/branches/1/opening-hours', { effectiveFrom: tomorrow, days }, await manager()));
    expect(res.data.message).toBe(message);
  });

  it('needs an effective date from tomorrow for an active branch (BR-CN-04)', async () => {
    const res = await failure(client.put('/api/branches/1/opening-hours', { effectiveFrom: today, days: week(STANDARD) }, await manager()));
    expect(res.data.message).toBe('Ngày hiệu lực của giờ mở cửa mới sớm nhất là ngày mai (BR-CN-04)');
  });

  it('lists the bookings a shorter day drops and saves only when they are cancelled (BR-CN-04)', async () => {
    const auth = await manager();
    const shorter = { effectiveFrom: tomorrow, days: week(STANDARD, { ...SUNDAY_MORNING, 2: [{ open: '08:00', close: '12:00' }, { open: '13:30', close: '18:00' }] }) };

    const impact = await client.post('/api/branches/1/opening-hours/impact', shorter, auth);
    expect(impact.data.data.appointments).toEqual([
      expect.objectContaining({ slotDate: tuesday, slotStart: '18:30', petName: 'Lucky', customerName: 'Trần Đức Huy' }),
    ]);
    expect(impact.data.data.boardingBookings).toEqual([]);

    const refused = await failure(client.put('/api/branches/1/opening-hours', shorter, auth));
    expect(refused.data.message).toBe(
      'Giờ mở cửa mới làm 1 lịch hẹn và 0 đặt chỗ lưu trú rơi ra ngoài giờ mở cửa; xem danh sách và chọn hủy hàng loạt để tiếp tục (BR-CN-04)',
    );

    const saved = await client.put('/api/branches/1/opening-hours', { ...shorter, cancelAffected: true }, auth);
    expect(saved.data.message).toBe('Đã cập nhật giờ mở cửa');
    const after = await client.post('/api/branches/1/opening-hours/impact', shorter, auth);
    expect(after.data.data).toEqual({ appointments: [], boardingBookings: [] });
  });

  it('flags a stay whose check-out day becomes a day off', async () => {
    const closedSunday = { effectiveFrom: tomorrow, days: week(STANDARD, { 7: [] }) };
    const impact = await client.post('/api/branches/1/opening-hours/impact', closedSunday, await manager());
    expect(impact.data.data.boardingBookings).toEqual([expect.objectContaining({ petName: 'Bơ' })]);
  });

  it('lets only the branch manager of that branch write the hours', async () => {
    const body = { effectiveFrom: tomorrow, days: week(STANDARD, SUNDAY_MORNING) };
    expect((await failure(client.put('/api/branches/1/opening-hours', body, await chain()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.put('/api/branches/1/opening-hours', body, await manager2()))).data.errorCode).toBe(
      'ACCESS_DENIED_SCOPE_MISMATCH',
    );
  });
});

describe('holidays (UC14)', () => {
  it('refuses a day with bookings unless they are cancelled (BR-LH-10), then lists it', async () => {
    const auth = await manager();
    const impact = await client.post('/api/branches/1/holidays/impact', { holidayDate: tuesday }, auth);
    expect(impact.data.data.appointments.map((a: { petName: string }) => a.petName)).toEqual(['Mochi', 'Lucky']);

    const refused = await failure(client.post('/api/branches/1/holidays', { holidayDate: tuesday }, auth));
    expect(refused.data.message).toBe(
      `Ngày ${tuesday} còn 2 lịch hẹn và 0 đặt chỗ lưu trú; xem danh sách và chọn hủy hàng loạt để tiếp tục (BR-LH-10)`,
    );

    const created = await client.post('/api/branches/1/holidays', { holidayDate: tuesday, reason: 'Sửa điện', cancelAffected: true }, auth);
    expect(created.status).toBe(201);
    const list = await client.get('/api/branches/1/holidays', { params: { from: today }, ...auth });
    expect(list.data.data.map((h: { holidayDate: string }) => h.holidayDate)).toContain(tuesday);
  });

  it('refuses a past day and a day that is already off (BR-CN-03)', async () => {
    const auth = await manager();
    const past = await failure(client.post('/api/branches/1/holidays', { holidayDate: addDays(today, -1) }, auth));
    expect(past.data.message).toBe('Không thêm được ngày nghỉ trong quá khứ (BR-CN-03)');

    const upcoming = (await client.get('/api/branches/1/holidays', { params: { from: today }, ...auth })).data.data[0].holidayDate;
    const dup = await failure(client.post('/api/branches/1/holidays', { holidayDate: upcoming }, auth));
    expect(dup.data.message).toBe(`Ngày ${upcoming} đã là ngày nghỉ của chi nhánh (BR-CN-03)`);
  });

  it('accepts today for an incident', async () => {
    const res = await client.post('/api/branches/1/holidays', { holidayDate: today, reason: 'Mất điện' }, await manager());
    expect(res.data.data).toMatchObject({ holidayDate: today, reason: 'Mất điện' });
  });

  it('deletes only future holidays (BR-CN-03)', async () => {
    const auth = await manager();
    const all = (await client.get('/api/branches/1/holidays', auth)).data.data as { holidayId: number; holidayDate: string }[];
    const past = all.find((h) => h.holidayDate < today)!;
    const future = all.find((h) => h.holidayDate > today)!;

    expect((await failure(client.delete(`/api/branches/1/holidays/${past.holidayId}`, auth))).data.message).toBe(
      'Chỉ xóa được ngày nghỉ trong tương lai (BR-CN-03)',
    );
    expect((await client.delete(`/api/branches/1/holidays/${future.holidayId}`, auth)).status).toBe(204);
    expect((await failure(client.delete('/api/branches/1/holidays/999', auth))).data.message).toBe('Không tìm thấy Ngày nghỉ #999');
  });
});

describe('services at the branch (UC33)', () => {
  it('lists active catalog services with their flag and turns one on', async () => {
    const auth = await manager();
    const list = (await client.get('/api/branches/1/services', auth)).data.data as { serviceId: number; enabled: boolean }[];
    expect(list).toHaveLength(6);
    expect(list.find((s) => s.serviceId === 6)?.enabled).toBe(false);

    const res = await client.put('/api/branches/1/services/6', { enabled: true }, auth);
    expect(res.data).toMatchObject({ message: 'Đã cập nhật dịch vụ tại chi nhánh', data: { serviceId: 6, group: 'BOARDING', enabled: true } });
  });

  it('hides a discontinued service and refuses to enable it (BR-LH-01)', async () => {
    await client.patch('/api/services/4', { isActive: false }, await chain());
    const auth = await manager();
    const list = (await client.get('/api/branches/1/services', auth)).data.data as { serviceId: number }[];
    expect(list.map((s) => s.serviceId)).not.toContain(4);

    const res = await failure(client.put('/api/branches/1/services/4', { enabled: true }, auth));
    expect(res.data.message).toBe('Dịch vụ đã ngừng kinh doanh, không bật được tại chi nhánh (BR-LH-01)');
    expect((await failure(client.put('/api/branches/1/services/999', { enabled: true }, auth))).data.message).toBe(
      'Không tìm thấy Dịch vụ #999',
    );
  });
});

describe('quota (UC42)', () => {
  it('lists both default quotas, null when not configured', async () => {
    const res = await client.get('/api/branches/1/quota-defaults', await manager());
    expect(res.data.data).toEqual([
      { serviceGroup: 'MEDICAL', defaultQuota: 3 },
      { serviceGroup: 'GROOMING', defaultQuota: null },
    ]);
  });

  it('sets a default quota and refuses the boarding group and out-of-range values (BR-LH-03)', async () => {
    const auth = await manager();
    expect((await client.put('/api/branches/1/quota-defaults/GROOMING', { defaultQuota: 2 }, auth)).data.data).toEqual({
      serviceGroup: 'GROOMING',
      defaultQuota: 2,
    });
    expect((await failure(client.put('/api/branches/1/quota-defaults/BOARDING', { defaultQuota: 2 }, auth))).data.message).toBe(
      'Quota chỉ có với nhóm Khám/Tiêm và Thẩm mỹ, nhóm Lưu trú không có khung giờ (BR-LH-03)',
    );
    expect((await failure(client.put('/api/branches/1/quota-defaults/MEDICAL', { defaultQuota: -1 }, auth))).data.message).toBe(
      'Quota phải từ 0 đến 32767 (BR-LH-03)',
    );
  });

  it('locks a slot with 0 and keeps the same id when it is overwritten', async () => {
    const auth = await manager();
    const slot = { serviceGroup: 'MEDICAL', slotDate: tuesday, slotStart: '09:30' };
    const locked = await client.put('/api/branches/1/slot-quotas', { ...slot, quota: 0 }, auth);
    expect(locked.data).toMatchObject({ message: 'Đã cập nhật quota của khung giờ', data: { ...slot, quota: 0 } });

    const reopened = await client.put('/api/branches/1/slot-quotas', { ...slot, quota: 2 }, auth);
    expect(reopened.data.data).toEqual({ ...slot, quota: 2, slotQuotaId: locked.data.data.slotQuotaId });
  });

  it.each(['09:15', '12:00', '19:00'])('refuses %s, which is not a slot of the day (BR-LH-02)', async (slotStart) => {
    const res = await failure(
      client.put('/api/branches/1/slot-quotas', { serviceGroup: 'MEDICAL', slotDate: tuesday, slotStart, quota: 1 }, await manager()),
    );
    expect(res.data.message).toBe(
      `Khung ${slotStart} ngày ${tuesday} không phải khung đặt lịch của chi nhánh (ngoài giờ mở cửa, ngày nghỉ, hoặc không đúng bước 30 phút từ giờ mở cửa) (BR-LH-02)`,
    );
  });

  it('lists slot quotas of a period and removes one', async () => {
    const auth = await manager();
    const period = { from: today, to: addDays(today, 30) };
    const before = (await client.get('/api/branches/1/slot-quotas', { params: period, ...auth })).data.data as { slotQuotaId: number; quota: number }[];
    const lockedSlot = before.find((q) => q.quota === 0)!;

    expect((await client.delete(`/api/branches/1/slot-quotas/${lockedSlot.slotQuotaId}`, auth)).status).toBe(204);
    const after = (await client.get('/api/branches/1/slot-quotas', { params: period, ...auth })).data.data as { slotQuotaId: number }[];
    expect(after.map((q) => q.slotQuotaId)).not.toContain(lockedSlot.slotQuotaId);

    const reversed = await failure(client.get('/api/branches/1/slot-quotas', { params: { from: tuesday, to: today }, ...auth }));
    expect(reversed.data.message).toBe('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc (BR-LH-03)');
  });

  it('keeps quota away from the chain manager', async () => {
    const res = await failure(client.get('/api/branches/1/quota-defaults', await chain()));
    expect(res.data.errorCode).toBe('ACCESS_DENIED');
  });
});
