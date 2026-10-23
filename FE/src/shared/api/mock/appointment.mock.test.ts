import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Giờ cố định: thứ Sáu 23/10/2026 09:00 (giờ Việt Nam). Dữ liệu mẫu (appointment.mock.ts) của chi nhánh 1:
// khách #1 khach@petcare.vn: Mochi #1 BOOKED hôm nay 11:00 và thứ Hai 26/10 09:00 (đủ 2 lịch), Bim #2 BOOKED Tắm & sấy
// mai 10:00 (đã đổi 3 lần), Bim hủy muộn 18/10 (1 vi phạm). Khách #3 tại quầy (Lu #4): CHECKED_IN hôm nay 08:30,
// BOOKED mai 10:00, đang bị hạn chế đặt online. Quota chi nhánh 1: Khám/Tiêm 3, Thẩm mỹ chưa cấu hình (= 1);
// thứ Ba 27/10 08:00 Khám/Tiêm quota riêng 0; ngày nghỉ 12/11.
const NOW = new Date('2026-10-23T09:00:00+07:00');
const TODAY = '2026-10-23';
const TOMORROW = '2026-10-24';
const MONDAY = '2026-10-26';
const TUESDAY = '2026-10-27';
const HOLIDAY = '2026-11-12';

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
const owner = () => as('khach@petcare.vn');
const desk = () => as('reception@store1.vn');
const manager = () => as('manager@store1.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

type Slot = { slotStart: string; status: string; quota: number; booked: number };
type Appt = { appointmentId: number; petName: string; slotDate: string; slotStart: string; status: string; [k: string]: unknown };

const slots = async (auth: object, serviceId: number, date: string): Promise<Slot[]> =>
  (await client.get('/api/branches/1/available-slots', { params: { serviceId, date }, ...auth })).data.data;

const book = async (auth: object, body: Record<string, unknown>) => (await client.post('/api/appointments', { branchId: 1, ...body }, auth)).data;

async function findAppt(auth: object, petName: string, date: string): Promise<Appt> {
  const list = (await client.get('/api/appointments', { params: { date, size: 100 }, ...auth })).data.data.content as Appt[];
  return list.find((a) => a.petName === petName && a.status === 'BOOKED')!;
}

describe('dữ liệu công khai cho trang đặt lịch (branch-v1 #20, catalog-v1 #19)', () => {
  it('chỉ chi nhánh ACTIVE, kèm giờ mở cửa đang áp dụng; không cần đăng nhập', async () => {
    const branches = (await client.get('/api/public/branches')).data.data;
    expect(branches).toHaveLength(1);
    expect(branches[0]).toMatchObject({ branchId: 1, name: 'PetCare Cầu Giấy', phone: '0901234567' });
    expect(branches[0].weeklyHours).toHaveLength(7);
    expect(branches[0]).not.toHaveProperty('status');
    expect((await failure(client.get('/api/public/branches/2'))).status).toBe(404);
  });

  it('dịch vụ đang kinh doanh và bật ở chi nhánh ACTIVE, lọc theo chi nhánh / nhóm', async () => {
    const all = (await client.get('/api/public/services', { params: { branchId: 1 } })).data.data as { serviceId: number; branchIds: number[] }[];
    expect(all.map((s) => s.serviceId).sort()).toEqual([1, 2, 3, 4, 5]);
    expect(all.every((s) => s.branchIds.includes(1))).toBe(true);
    const grooming = (await client.get('/api/public/services', { params: { group: 'GROOMING' } })).data.data as { name: string }[];
    expect(grooming.map((s) => s.name)).toEqual(['Cắt tỉa lông', 'Tắm & sấy']);
    expect((await failure(client.get('/api/public/services', { params: { group: 'SPA' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('khung giờ trống (#1, BR-LH-02, 03, 04)', () => {
  it('khách chỉ thấy khung từ now + 24 giờ; khung Thẩm mỹ đã đủ quota là FULL', async () => {
    const list = await slots(await owner(), 3, TOMORROW);
    expect(list[0].slotStart).toBe('09:00');
    expect(list.find((s) => s.slotStart === '10:00')).toMatchObject({ quota: 1, booked: 1, status: 'FULL' });
    expect(list.find((s) => s.slotStart === '10:30')).toMatchObject({ status: 'AVAILABLE' });
    // Khung nằm trọn trong khoảng giờ: không có 12:00 (nghỉ trưa), khung cuối 18:30.
    expect(list.map((s) => s.slotStart)).not.toContain('12:00');
    expect(list.at(-1)?.slotStart).toBe('18:30');
  });

  it('lễ tân thấy mọi khung chưa bắt đầu, kể cả hôm nay', async () => {
    expect((await slots(await desk(), 1, TOMORROW))[0].slotStart).toBe('08:00');
    expect((await slots(await desk(), 1, TODAY))[0].slotStart).toBe('09:30');
  });

  it('ngày nghỉ, quá 30 ngày hoặc ngày đã qua trả mảng rỗng', async () => {
    const auth = await owner();
    expect(await slots(auth, 1, HOLIDAY)).toEqual([]);
    expect(await slots(auth, 1, '2026-11-23')).toEqual([]);
    expect((await slots(auth, 1, '2026-11-22')).length).toBeGreaterThan(0);
    expect(await slots(await desk(), 1, '2026-10-22')).toEqual([]);
  });

  it('quota riêng 0 khóa khung', async () => {
    expect((await slots(await desk(), 1, TUESDAY))[0]).toMatchObject({ slotStart: '08:00', quota: 0, status: 'FULL' });
  });

  it('dịch vụ lưu trú, chi nhánh nháp, sai phạm vi, sai vai trò, thiếu tham số', async () => {
    const auth = await owner();
    expect((await failure(slots(auth, 5, TOMORROW))).data.message).toBe('Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)');
    const draft = await failure(client.get('/api/branches/2/available-slots', { params: { serviceId: 1, date: TOMORROW }, ...auth }));
    expect(draft.status).toBe(404);
    const other = await failure(client.get('/api/branches/2/available-slots', { params: { serviceId: 1, date: TOMORROW }, ...(await desk()) }));
    expect(other.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(slots(await manager(), 1, TOMORROW))).data.errorCode).toBe('ACCESS_DENIED');
    const missing = await failure(client.get('/api/branches/1/available-slots', { params: { serviceId: 1 }, ...auth }));
    expect(missing.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: "Thiếu tham số 'date'" });
  });
});

describe('đặt lịch (#2)', () => {
  it('khách đặt cho thú của mình: kênh ONLINE, mã LH-yyMMdd-…, thú không còn xóa được (BR-KH-06)', async () => {
    const auth = await owner();
    const res = await book(auth, { petId: 3, serviceId: 1, slotDate: MONDAY, slotStart: '10:00', note: '  Lần đầu đến  ' });
    expect(res).toMatchObject({
      message: 'Đã đặt lịch hẹn',
      data: { petName: 'Tôm', serviceName: 'Khám tổng quát', branchName: 'PetCare Cầu Giấy', channel: 'ONLINE', status: 'BOOKED', note: 'Lần đầu đến', canReschedule: true, canCancel: true },
    });
    expect(res.data.code).toMatch(/^LH-261023-\d{4}$/);
    expect((await client.get('/api/pets/3', auth)).data.data.deletable).toBe(false);
    // Khung vừa đặt được tính vào quota.
    expect((await slots(auth, 1, MONDAY)).find((s) => s.slotStart === '10:00')?.booked).toBe(1);
  });

  it.each([
    [{ petId: 3, serviceId: 1, slotDate: TOMORROW, slotStart: '08:30' }, 'Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)'],
    [{ petId: 3, serviceId: 1, slotDate: '2026-11-23', slotStart: '09:00' }, 'Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)'],
    [{ petId: 3, serviceId: 1, slotDate: MONDAY, slotStart: '12:30' }, 'Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)'],
    [{ petId: 1, serviceId: 1, slotDate: MONDAY, slotStart: '15:00' }, 'Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)'],
    [{ petId: 2, serviceId: 4, slotDate: TOMORROW, slotStart: '15:00' }, 'Thú cưng đã có lịch Thẩm mỹ trong ngày này (BR-LH-05)'],
    [{ petId: 2, serviceId: 1, slotDate: TOMORROW, slotStart: '10:00' }, 'Thú cưng đã có lịch hẹn khác trùng khung giờ này (BR-LH-05)'],
    [{ petId: 3, serviceId: 3, slotDate: TOMORROW, slotStart: '10:00' }, 'Khung giờ đã hết chỗ (BR-LH-03)'],
    [{ petId: 3, serviceId: 5, slotDate: MONDAY, slotStart: '10:00' }, 'Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)'],
  ])('từ chối và báo lý do #%#', async (body, message) => {
    expect((await failure(book(await owner(), body))).data.message).toBe(message);
  });

  it('thú của khách khác như không tồn tại; lễ tân không đặt cho chi nhánh khác', async () => {
    const res = await failure(book(await owner(), { petId: 4, serviceId: 1, slotDate: MONDAY, slotStart: '10:00' }));
    expect(res.data).toMatchObject({ statusCode: 404, message: 'Không tìm thấy thú cưng #4' });
    const other = await failure(client.post('/api/appointments', { branchId: 2, petId: 4, serviceId: 1, slotDate: MONDAY, slotStart: '10:00' }, await desk()));
    expect(other.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
  });

  it('thú đã mất không đặt được; lịch BOOKED của thú bị phòng khám hủy (BR-KH-05)', async () => {
    const auth = await owner();
    const booked = (await book(auth, { petId: 3, serviceId: 1, slotDate: MONDAY, slotStart: '10:00' })).data;
    await client.post('/api/pets/3/mark-deceased', { deceasedOn: TODAY, confirmed: true }, auth);
    const after = (await client.get(`/api/appointments/${booked.appointmentId}`, auth)).data.data;
    expect(after).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', lateCancel: false, canCancel: false });
    const res = await failure(book(auth, { petId: 3, serviceId: 1, slotDate: MONDAY, slotStart: '10:30' }));
    expect(res.data.message).toBe('Thú cưng đã mất, không đặt được lịch (BR-KH-05)');
  });

  it('lễ tân đặt hộ (kênh COUNTER) cả khung sắp bắt đầu hôm nay; khách của lịch là chủ của thú', async () => {
    const res = await book(await desk(), { petId: 4, serviceId: 3, slotDate: TODAY, slotStart: '09:30' });
    expect(res.data).toMatchObject({ channel: 'COUNTER', customerId: 3, customerName: 'Lê Hoàng Long' });
    const started = await failure(book(await desk(), { petId: 2, serviceId: 1, slotDate: TODAY, slotStart: '08:00' }));
    expect(started.data.message).toBe('Khung giờ đã bắt đầu (BR-LH-04)');
  });

  it('thiếu trường → VALIDATION_FAILED theo trường', async () => {
    const res = await failure(client.post('/api/appointments', { branchId: 1, note: 'x'.repeat(501) }, await owner()));
    expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    expect(res.data.message).toContain('petId: Thiếu thú cưng');
    expect(res.data.message).toContain('note: Ghi chú tối đa 500 ký tự');
  });
});

describe('xem lịch hẹn (#3, #4)', () => {
  it('khách chỉ thấy lịch của mình, tăng dần theo giờ hẹn', async () => {
    const page = (await client.get('/api/appointments', { params: { size: 100 }, ...(await owner()) })).data.data;
    expect(new Set(page.content.map((a: { customerId: number }) => a.customerId))).toEqual(new Set([1]));
    const dates = page.content.map((a: Appt) => `${a.slotDate} ${a.slotStart}`);
    expect(dates).toEqual([...dates].sort());
  });

  it('nhân viên xem chi nhánh mình; quản lý không có nút đổi / hủy (ADR-0048)', async () => {
    const today = (await client.get('/api/appointments', { params: { date: TODAY }, ...(await desk()) })).data.data.content as Appt[];
    expect(today.map((a) => [a.slotStart, a.petName, a.status])).toEqual([
      ['08:30', 'Lu', 'CHECKED_IN'],
      ['11:00', 'Mochi', 'BOOKED'],
    ]);
    expect(today[1]).toMatchObject({ canReschedule: true, canCancel: true });
    const asManager = (await client.get('/api/appointments', { params: { date: TODAY }, ...(await manager()) })).data.data.content as Appt[];
    expect(asManager[1]).toMatchObject({ canReschedule: false, canCancel: false });
    expect((await failure(client.get('/api/appointments', { params: { branchId: 2 }, ...(await desk()) }))).data.errorCode).toBe(
      'ACCESS_DENIED_SCOPE_MISMATCH',
    );
  });

  it('checkInReady: chỉ lịch BOOKED hôm nay trong cửa sổ ±30 phút', async () => {
    vi.setSystemTime(new Date('2026-10-23T10:40:00+07:00'));
    const res = (await client.get('/api/appointments', { params: { checkInReady: true }, ...(await desk()) })).data.data.content as Appt[];
    expect(res.map((a) => a.petName)).toEqual(['Mochi']);
  });

  it('quá giờ hẹn 30 phút chưa check-in thì thành NO_SHOW (ST05, BR-LH-08)', async () => {
    vi.setSystemTime(new Date('2026-10-23T11:31:00+07:00'));
    const res = (await client.get('/api/appointments', { params: { date: TODAY }, ...(await owner()) })).data.data.content as Appt[];
    expect(res[0]).toMatchObject({ petName: 'Mochi', status: 'NO_SHOW', canCancel: false });
  });
});

describe('đổi khung (#5, BR-LH-06)', () => {
  it('khách đổi khi còn ≥ 12 giờ; giữ mã, tăng số lần đổi; đổi về đúng khung cũ không mất lượt', async () => {
    const auth = await owner();
    const before = await findAppt(auth, 'Mochi', MONDAY);
    const moved = (await client.post(`/api/appointments/${before.appointmentId}/reschedule`, { slotDate: MONDAY, slotStart: '09:30' }, auth)).data;
    expect(moved).toMatchObject({ message: 'Đã đổi khung giờ', data: { code: before.code, slotStart: '09:30', rescheduleCount: 1 } });
    const same = (await client.post(`/api/appointments/${before.appointmentId}/reschedule`, { slotDate: MONDAY, slotStart: '09:30' }, auth)).data;
    expect(same.data.rescheduleCount).toBe(1);
  });

  it('khách không đổi được khi còn dưới 12 giờ; lễ tân thì được tới trước giờ hẹn', async () => {
    const mochi = await findAppt(await owner(), 'Mochi', TODAY);
    const late = await failure(client.post(`/api/appointments/${mochi.appointmentId}/reschedule`, { slotDate: MONDAY, slotStart: '10:00' }, await owner()));
    expect(late.data.message).toBe('Chỉ đổi được lịch trước giờ hẹn ít nhất 12 giờ, vui lòng liên hệ chi nhánh (BR-LH-06)');
    const byDesk = await client.post(`/api/appointments/${mochi.appointmentId}/reschedule`, { slotDate: TODAY, slotStart: '11:30' }, await desk());
    expect(byDesk.data.data).toMatchObject({ slotStart: '11:30', rescheduleCount: 1 });
  });

  it('tối đa 3 lần đổi, tính chung khách và lễ tân', async () => {
    const bim = await findAppt(await owner(), 'Bim', TOMORROW);
    expect(bim).toMatchObject({ rescheduleCount: 3, canReschedule: false, canCancel: true });
    const res = await failure(client.post(`/api/appointments/${bim.appointmentId}/reschedule`, { slotDate: TOMORROW, slotStart: '15:00' }, await desk()));
    expect(res.data.message).toBe('Lịch hẹn đã đổi đủ 3 lần, muốn đổi nữa vui lòng hủy rồi đặt lại (BR-LH-06)');
  });

  it('lịch không còn BOOKED → 409', async () => {
    const lu = ((await client.get('/api/appointments', { params: { date: TODAY }, ...(await desk()) })).data.data.content as Appt[])[0];
    const res = await failure(client.post(`/api/appointments/${lu.appointmentId}/reschedule`, { slotDate: TOMORROW, slotStart: '11:00' }, await desk()));
    expect(res.data).toMatchObject({ statusCode: 409, message: 'Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang BOOKED' });
  });
});

describe('hủy lịch và hạn chế đặt online (#6–9, BR-LH-07, 09)', () => {
  it('hủy khi còn ≥ 12 giờ không tính hủy muộn', async () => {
    const auth = await owner();
    const mochi = await findAppt(auth, 'Mochi', MONDAY);
    const res = (await client.post(`/api/appointments/${mochi.appointmentId}/cancel`, {}, auth)).data;
    expect(res).toMatchObject({ message: 'Đã hủy lịch hẹn', data: { status: 'CANCELLED', cancelSource: 'CUSTOMER', lateCancel: false, cancelReason: null } });
  });

  it('3 lần hủy muộn / không đến trong 90 ngày → hạn chế 30 ngày; lễ tân gỡ sớm', async () => {
    const auth = await owner();
    const reception = await desk();
    // Vi phạm 2: khách hủy Mochi hôm nay 11:00 (còn 2 giờ).
    const mochi = await findAppt(auth, 'Mochi', TODAY);
    const late = (await client.post(`/api/appointments/${mochi.appointmentId}/cancel`, { reason: 'Kẹt xe' }, auth)).data.data;
    expect(late).toMatchObject({ lateCancel: true, cancelSource: 'CUSTOMER', cancelReason: 'Kẹt xe' });
    expect((await client.get('/api/me/booking-restriction', auth)).data.data).toEqual({ restricted: false, restriction: null });

    // Vi phạm 3: lễ tân đặt hộ rồi hủy hộ khi còn dưới 12 giờ (cũng tính hủy muộn).
    const counter = (await book(reception, { petId: 2, serviceId: 1, slotDate: TODAY, slotStart: '13:30' })).data;
    const staffCancel = (await client.post(`/api/appointments/${counter.appointmentId}/cancel`, undefined, reception)).data.data;
    expect(staffCancel).toMatchObject({ lateCancel: true, cancelSource: 'STAFF' });

    const status = (await client.get('/api/me/booking-restriction', auth)).data.data;
    expect(status).toMatchObject({ restricted: true, restriction: { customerId: 1, violationCount: 3, endsAt: '2026-11-22T02:00:00.000Z' } });
    const refused = await failure(book(auth, { petId: 3, serviceId: 1, slotDate: MONDAY, slotStart: '10:00' }));
    expect(refused.data.message).toBe(
      'Bạn đang bị hạn chế đặt lịch online đến 09:00 22/11/2026, vui lòng liên hệ chi nhánh để đặt lịch (BR-LH-09)',
    );
    // Lịch tại quầy vẫn đặt được.
    expect((await book(reception, { petId: 3, serviceId: 1, slotDate: MONDAY, slotStart: '10:00' })).data.channel).toBe('COUNTER');

    const noReason = await failure(client.post('/api/customers/1/booking-restriction/lift', { reason: '  ' }, reception));
    expect(noReason.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'reason: Vui lòng nhập lý do' });
    const lifted = (await client.post('/api/customers/1/booking-restriction/lift', { reason: 'Khách giải thích lý do' }, reception)).data;
    expect(lifted).toMatchObject({ message: 'Đã gỡ hạn chế đặt online', data: { restricted: false, restriction: null } });
    const again = await failure(client.post('/api/customers/1/booking-restriction/lift', { reason: 'Lần nữa' }, reception));
    expect(again.status).toBe(409);
    expect((await book(auth, { petId: 2, serviceId: 1, slotDate: MONDAY, slotStart: '11:00' })).data.channel).toBe('ONLINE');
  });

  it('khách #3 có hạn chế mẫu; quản lý chi nhánh cũng xem được, bác sĩ thì không', async () => {
    const status = (await client.get('/api/customers/3/booking-restriction', await manager())).data.data;
    expect(status).toMatchObject({ restricted: true, restriction: { customerId: 3, violationCount: 3 } });
    expect((await failure(client.get('/api/customers/3/booking-restriction', await as('doctor@store1.vn')))).status).toBe(403);
    expect((await failure(client.get('/api/customers/999/booking-restriction', await desk()))).data.message).toBe('Không tìm thấy khách hàng #999');
  });

  it('không hủy được lịch đã tới giờ hoặc đã tiếp nhận', async () => {
    const lu = ((await client.get('/api/appointments', { params: { date: TODAY }, ...(await desk()) })).data.data.content as Appt[])[0];
    const res = await failure(client.post(`/api/appointments/${lu.appointmentId}/cancel`, {}, await desk()));
    expect(res.data.message).toBe('Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang CANCELLED');
    vi.setSystemTime(new Date('2026-10-23T11:10:00+07:00'));
    const mochi = await findAppt(await owner(), 'Mochi', TODAY);
    expect(mochi.canCancel).toBe(false);
    const started = await failure(client.post(`/api/appointments/${mochi.appointmentId}/cancel`, {}, await owner()));
    expect(started.data.message).toBe('Đã tới giờ hẹn, không hủy được nữa (BR-LH-07)');
  });
});
