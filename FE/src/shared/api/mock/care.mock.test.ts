import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse } from '../../types';
import type { AppNotification, CareTask, NotificationSettings } from '../../types/care';
import { DEFAULT_SETTINGS } from './care.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (care.mock.ts, thời gian tương đối với lúc chạy): khach@petcare.vn 26 thông báo, 6 chưa đọc (1 ở
// trang 2); hai thông báo hủy lịch trùng giờ. manager@store1.vn 4 (2 chưa đọc), reception@store1.vn 3 (2 chưa đọc).

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const customer = () => as('khach@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const list = async (auth: object, params: object = {}) =>
  (await client.get('/api/me/notifications', { params, ...auth })).data.data as {
    content: AppNotification[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
const unreadCount = async (auth: object) => (await client.get('/api/me/notifications/unread-count', auth)).data.data.count as number;

describe('thông báo của tôi (care-v1 #5, #6)', () => {
  it('mới nhất trước; trùng giờ thì id lớn trước', async () => {
    const page = await list(await customer());
    expect(page).toMatchObject({ page: 0, size: 20, totalElements: 26, totalPages: 2 });
    const times = page.content.map((n) => Date.parse(n.createdAt));
    expect(times).toEqual([...times].sort((a, b) => b - a));
    const tie = page.content.filter((n) => n.type === 'APPOINTMENT_CANCELLED_BY_CLINIC');
    expect(tie).toHaveLength(2);
    expect(tie[0].createdAt).toBe(tie[1].createdAt);
    expect(tie[0].notificationId).toBeGreaterThan(tie[1].notificationId);
    expect(page.content[0]).not.toHaveProperty('accountId');
  });

  it('chỉ trả thông báo của chủ token', async () => {
    const mine = await list(await as('reception@store1.vn'));
    expect(mine.totalElements).toBe(3);
    expect((await list(await as('doctor@store1.vn'))).totalElements).toBe(0);
    // ADMIN cũng gọi được (UC88 A02–A08, BE isAuthenticated()).
    expect((await list(await as('admin@petcare.vn'))).totalElements).toBe(0);
  });

  it('unreadOnly lọc chưa đọc, kể cả thông báo cũ ở trang 2; rỗng = false; sai kiểu → 400', async () => {
    const auth = await customer();
    const unread = await list(auth, { unreadOnly: true });
    expect(unread.totalElements).toBe(6);
    expect(unread.content.every((n) => !n.readAt)).toBe(true);
    expect(await unreadCount(auth)).toBe(6);
    expect((await client.get('/api/me/notifications?unreadOnly=', auth)).data.data.totalElements).toBe(26);
    const bad = await failure(client.get('/api/me/notifications', { params: { unreadOnly: 'yes' }, ...auth }));
    expect(bad.status).toBe(400);
    expect(bad.data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('kẹp page < 0 về 0 và size vào 1–100', async () => {
    const auth = await customer();
    expect(await list(auth, { page: -3, size: 0 })).toMatchObject({ page: 0, size: 1, totalPages: 26 });
    const all = await list(auth, { size: 500 });
    expect(all).toMatchObject({ size: 100, totalPages: 1 });
    expect(all.content).toHaveLength(26);
    const second = await list(auth, { page: 1, size: 20 });
    expect(second.content).toHaveLength(6);
    expect((await failure(client.get('/api/me/notifications', { params: { page: 'x' }, ...auth }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('đánh dấu đã đọc (care-v1 #7, #8)', () => {
  it('đọc một thông báo: 204, giảm số chưa đọc; gọi lại vẫn 204 và giữ readAt lần đầu', async () => {
    const auth = await customer();
    const [first] = (await list(auth, { unreadOnly: true })).content;
    const res = await client.post(`/api/me/notifications/${first.notificationId}/read`, null, auth);
    expect(res.status).toBe(204);
    expect(await unreadCount(auth)).toBe(5);
    const readAt = (await list(auth)).content.find((n) => n.notificationId === first.notificationId)!.readAt;
    expect(readAt).toBeTruthy();

    await new Promise((r) => setTimeout(r, 5));
    expect((await client.post(`/api/me/notifications/${first.notificationId}/read`, null, auth)).status).toBe(204);
    expect((await list(auth)).content.find((n) => n.notificationId === first.notificationId)!.readAt).toBe(readAt);
  });

  it('thông báo của người khác và id không tồn tại cùng 404; id sai kiểu → 400', async () => {
    const [others] = (await list(await as('reception@store1.vn'))).content;
    const auth = await customer();
    const foreign = await failure(client.post(`/api/me/notifications/${others.notificationId}/read`, null, auth));
    expect(foreign.status).toBe(404);
    expect(foreign.data.message).toBe(`Không tìm thấy thông báo #${others.notificationId}`);
    expect((await failure(client.post('/api/me/notifications/99999/read', null, auth))).status).toBe(404);
    expect((await failure(client.post('/api/me/notifications/abc/read', null, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    // Không đổi thông báo của lễ tân.
    expect((await list(await as('reception@store1.vn'))).content[0].readAt).toBe(others.readAt);
  });

  it('đọc tất cả: chỉ của tôi, thông báo đã đọc giữ readAt cũ', async () => {
    const auth = await customer();
    const before = (await list(auth, { size: 100 })).content.filter((n) => n.readAt);
    expect((await client.post('/api/me/notifications/read-all', null, auth)).status).toBe(204);
    expect(await unreadCount(auth)).toBe(0);
    const after = (await list(auth, { size: 100 })).content;
    for (const n of before) expect(after.find((a) => a.notificationId === n.notificationId)!.readAt).toBe(n.readAt);
    expect(await unreadCount(await as('reception@store1.vn'))).toBe(2);
  });
});

describe('cài đặt nhận thông báo (care-v1 #9, #10)', () => {
  const custom: NotificationSettings = {
    appointmentReminder: { email: false, inApp: true },
    vaccineReminder: { email: true, inApp: false },
    followUpReminder: { email: true, inApp: true },
  };

  it('khách: mặc định bật hết; lưu đủ 3 loại × 2 kênh rồi đọc lại', async () => {
    const auth = await customer();
    expect((await client.get('/api/me/notification-settings', auth)).data.data).toEqual(DEFAULT_SETTINGS);
    const saved = await client.put('/api/me/notification-settings', { ...custom, extra: 1 }, auth);
    expect(saved.data.data).toEqual(custom);
    expect((await client.get('/api/me/notification-settings', auth)).data.data).toEqual(custom);
    // Khách khác không bị ảnh hưởng.
    expect((await client.get('/api/me/notification-settings', await as('khachmoi@petcare.vn'))).data.data).toEqual(DEFAULT_SETTINGS);
  });

  it('thiếu loại nhắc hoặc giá trị không phải boolean → 400 VALIDATION_FAILED theo trường', async () => {
    const auth = await customer();
    const bad = await failure(
      client.put('/api/me/notification-settings', { appointmentReminder: { email: 'yes', inApp: true }, vaccineReminder: { email: true } }, auth),
    );
    expect(bad.status).toBe(400);
    expect(bad.data.errorCode).toBe('VALIDATION_FAILED');
    expect(bad.data.message).toContain('appointmentReminder.email:');
    expect(bad.data.message).toContain('vaccineReminder.inApp:');
    expect(bad.data.message).toContain('followUpReminder:');
    expect((await client.get('/api/me/notification-settings', auth)).data.data).toEqual(DEFAULT_SETTINGS);
  });

  it('nhân viên (kể cả ADMIN) → 403', async () => {
    for (const email of ['reception@store1.vn', 'manager@store1.vn', 'admin@petcare.vn']) {
      const auth = await as(email);
      expect((await failure(client.get('/api/me/notification-settings', auth))).status).toBe(403);
      expect((await failure(client.put('/api/me/notification-settings', custom, auth))).status).toBe(403);
    }
  });
});

// ─── Việc gọi điện của lễ tân (Care Task, care-v1 #1–4, UC87) ───
// Dữ liệu mẫu tính theo giờ Việt Nam so với lúc nạp; các test ghim Date ở thứ Hai 26/10/2026 09:00 (giờ VN) nên hạn là
// chuỗi literal. Lễ tân reception@store1.vn (id 4, chi nhánh 1). Mảng mẫu xếp id giảm dần để lộ lỗi sắp xếp.
//   Đang chờ (hạn tăng dần): #5 Lu quá hạn tái chủng 23/10 · #6 Tôm quá hạn đón 25/10 (không có SĐT) · #7 Bim quá hạn
//     đón 25/10 (cùng hạn với #6) · #8 Miu tái khám 26/10 · #9 Miu tái chủng 28/10
//   Đã xong: #2 Miu tái chủng 26/09 (liên hệ được) · #1 Bim quá hạn tái chủng 20/10 (không liên lạc được)
//   Đã hủy: #3 Lu quá hạn đón 21/10 (lễ tân hủy) · #4 Mochi quá hạn tái chủng 22/10 (hệ thống hủy, BR-TB-03)

const CARE_KEY = 'petcare-mock-care';
const pinClock = (iso = '2026-10-26T09:00:00+07:00') => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
};

const desk = () => as('reception@store1.vn');
const careList = async (auth: object, params: object = {}) =>
  (await client.get('/api/care-tasks', { params, ...auth })).data.data as PageResponse<CareTask>;
const idsOf = (page: PageResponse<CareTask>) => page.content.map((t) => t.careTaskId);
/** Cả ba trạng thái, theo id: để so trước / sau một thao tác phải bị từ chối. */
async function everyTask(auth: object) {
  const lists = await Promise.all((['OPEN', 'DONE', 'CANCELLED'] as const).map((status) => careList(auth, { status, size: 100 })));
  return lists.flatMap((p) => p.content).sort((a, b) => a.careTaskId - b.careTaskId);
}
const getTask = (auth: object, id: number | string) => client.get(`/api/care-tasks/${id}`, auth);
const complete = (auth: object, id: number | string, body: unknown) => client.post(`/api/care-tasks/${id}/complete`, body, auth);
const cancel = (auth: object, id: number | string, body: unknown) => client.post(`/api/care-tasks/${id}/cancel`, body, auth);
const done = { result: 'REACHED', note: 'Khách sẽ ghé chiều nay' };

type RawState = { careTasks: Array<Record<string, unknown>> };
const rawState = () => JSON.parse(localStorage.getItem(CARE_KEY)!) as RawState;
/** Mock không seed chi nhánh 2 (chưa có lễ tân): chép task `ofId` thành task mới của chi nhánh 2. Gọi sau lần đọc đầu. */
async function addBranch2Task(ofId: number, careTaskId: number) {
  await careList(await desk());
  const s = rawState();
  s.careTasks.push({ ...s.careTasks.find((t) => t.careTaskId === ofId)!, careTaskId, branchId: 2 });
  localStorage.setItem(CARE_KEY, JSON.stringify(s));
}

describe('Care Task', () => {
  beforeEach(() => pinClock());
  afterEach(() => vi.useRealTimers());

  describe('dữ liệu mẫu', () => {
    it('đủ 4 loại và 3 trạng thái; mọi dòng thỏa CHECK của 05-erd care_tasks', async () => {
      const all = await everyTask(await desk());
      expect(all.map((t) => t.status).sort()).toEqual(['CANCELLED', 'CANCELLED', 'DONE', 'DONE', 'OPEN', 'OPEN', 'OPEN', 'OPEN', 'OPEN']);
      expect(new Set(all.map((t) => t.type))).toEqual(new Set(['VACCINE_DUE', 'VACCINE_OVERDUE', 'FOLLOW_UP_DUE', 'PICKUP_OVERDUE']));

      const linkOf = { VACCINE_DUE: 'vaccinationId', VACCINE_OVERDUE: 'vaccinationId', FOLLOW_UP_DUE: 'medicalRecordVisitId', PICKUP_OVERDUE: 'boardingBookingId' } as const;
      for (const t of all) {
        // Đúng một khóa liên kết, đúng khóa của loại.
        const set = (['vaccinationId', 'medicalRecordVisitId', 'boardingBookingId'] as const).filter((k) => t[k] != null);
        expect(set, `#${t.careTaskId} ${t.type}`).toEqual([linkOf[t.type]]);
        // status = DONE ⇔ có result.
        expect(t.status === 'DONE', `#${t.careTaskId} DONE ⇔ result`).toBe(t.result != null);
        if (t.status === 'DONE') {
          expect(t.note).toBeTruthy();
          expect(t.handledBy).toBe(4);
          expect(t.handledAt).toBeTruthy();
        }
        if (t.status === 'CANCELLED') expect(t.cancelReason).toBeTruthy();
        if (t.status === 'OPEN') expect([t.result, t.note, t.cancelReason, t.handledBy, t.handledAt]).toEqual([null, null, null, null, null]);
        expect(t.branchId).toBe(1);
        if (t.phoneToCall != null) expect(t.phoneToCall).toMatch(/^0[0-9]{9,10}$/);
      }
      // UNIQUE (vaccination_id) WHERE VACCINE_OVERDUE; UNIQUE (boarding_booking_id) WHERE PICKUP_OVERDUE.
      const overdueVaccines = all.filter((t) => t.type === 'VACCINE_OVERDUE').map((t) => t.vaccinationId);
      expect(new Set(overdueVaccines).size).toBe(overdueVaccines.length);
      const pickups = all.filter((t) => t.type === 'PICKUP_OVERDUE').map((t) => t.boardingBookingId);
      expect(new Set(pickups).size).toBe(pickups.length);
    });

    it('có một task chưa có SĐT, một task bị hệ thống hủy, và các task đang chờ đã quá hạn', async () => {
      const auth = await desk();
      const all = await everyTask(auth);
      expect(all.filter((t) => t.phoneToCall == null).map((t) => t.careTaskId)).toEqual([6]);
      const bySystem = all.filter((t) => t.status === 'CANCELLED' && t.handledBy == null);
      expect(bySystem).toHaveLength(1);
      expect(bySystem[0]).toMatchObject({ careTaskId: 4, cancelReason: 'Đã tiêm lại (BR-TB-03)', handledAt: null });
      expect(all.filter((t) => t.status === 'OPEN' && t.dueDate < '2026-10-26').map((t) => t.careTaskId)).toEqual([5, 6, 7]);
    });

    it('hạn tính theo ngày Việt Nam: 20:30Z ngày 25 đã là ngày 26 ở Việt Nam', async () => {
      pinClock('2026-10-25T20:30:00Z');
      const open = await careList(await desk());
      expect(open.content.map((t) => t.dueDate)).toEqual(['2026-10-23', '2026-10-25', '2026-10-25', '2026-10-26', '2026-10-28']);
    });

    it('tên khách, tên thú và SĐT đọc từ hồ sơ khách / thú; đón thú quá hạn dùng SĐT khẩn thay vì SĐT hồ sơ', async () => {
      const open = (await careList(await desk())).content;
      const byId = (id: number) => open.find((t) => t.careTaskId === id)!;
      expect(byId(9)).toMatchObject({ customerId: 4, customerName: 'Lê Thị Hoa', petId: 5, petName: 'Miu', phoneToCall: '0905111222' });
      expect(byId(5)).toMatchObject({ customerName: 'Lê Hoàng Long', petName: 'Lu', phoneToCall: '0905111222' });
      // #7: hồ sơ khách 0912345678, SĐT khẩn ghi lúc nhận thú 0987000111.
      expect(byId(7)).toMatchObject({ customerName: 'Nguyễn Thu Trang', petName: 'Bim', phoneToCall: '0987000111' });
      expect(byId(6)).toMatchObject({ customerName: 'Nguyễn Thu Trang', petName: 'Tôm', phoneToCall: null });
    });

    it('context nói rõ ngày hạn hoặc mã đặt chỗ; không lộ trường nội bộ của mock', async () => {
      const open = (await careList(await desk())).content;
      const byId = (id: number) => open.find((t) => t.careTaskId === id)!;
      expect(byId(9).context).toContain('28/10/2026');
      expect(byId(8).context).toContain('26/10/2026');
      expect(byId(7).context).toContain('LT-2610-');
      expect(byId(5).context).toContain('16/10/2026');
      for (const t of open) expect(t).not.toHaveProperty('emergencyPhone');
    });
  });

  describe('danh sách (#1)', () => {
    it('mặc định đang chờ, hạn tăng dần; cùng hạn thì id tăng dần', async () => {
      const page = await careList(await desk());
      expect(idsOf(page)).toEqual([5, 6, 7, 8, 9]);
      expect(page).toMatchObject({ page: 0, size: 20, totalElements: 5, totalPages: 1 });
      expect(page.content.every((t) => t.status === 'OPEN')).toBe(true);
    });

    it('status lọc đã xong và đã hủy, vẫn theo hạn tăng dần', async () => {
      const auth = await desk();
      expect(idsOf(await careList(auth, { status: 'DONE' }))).toEqual([2, 1]);
      expect(idsOf(await careList(auth, { status: 'CANCELLED' }))).toEqual([3, 4]);
      expect(idsOf(await careList(auth, { status: 'OPEN' }))).toEqual([5, 6, 7, 8, 9]);
    });

    it('type lọc theo loại, kết hợp với status', async () => {
      const auth = await desk();
      expect(idsOf(await careList(auth, { type: 'PICKUP_OVERDUE' }))).toEqual([6, 7]);
      expect(idsOf(await careList(auth, { type: 'PICKUP_OVERDUE', status: 'CANCELLED' }))).toEqual([3]);
      expect(idsOf(await careList(auth, { type: 'VACCINE_DUE' }))).toEqual([9]);
      expect(idsOf(await careList(auth, { type: 'FOLLOW_UP_DUE', status: 'DONE' }))).toEqual([]);
    });

    it.each([
      ['dueFrom và dueTo cùng ngày: lấy đúng ngày đó', { dueFrom: '2026-10-25', dueTo: '2026-10-25' }, [6, 7]],
      ['dueTo gồm cả ngày biên', { dueTo: '2026-10-25' }, [5, 6, 7]],
      ['dueFrom gồm cả ngày biên', { dueFrom: '2026-10-26' }, [8, 9]],
      ['khoảng một ngày ở giữa', { dueFrom: '2026-10-26', dueTo: '2026-10-26' }, [8]],
      ['dueFrom sau dueTo: rỗng, không báo lỗi', { dueFrom: '2026-10-27', dueTo: '2026-10-25' }, []],
    ])('%s', async (_name, params, expected) => {
      const page = await careList(await desk(), params);
      expect(idsOf(page)).toEqual(expected);
      expect(page.totalPages).toBe(expected.length === 0 ? 0 : 1);
    });

    it('page / size: kẹp size vào 1–100, page âm về 0, trang quá cuối rỗng', async () => {
      const auth = await desk();
      expect(await careList(auth, { size: 2 })).toMatchObject({ page: 0, size: 2, totalElements: 5, totalPages: 3 });
      expect(idsOf(await careList(auth, { size: 2, page: 2 }))).toEqual([9]);
      expect(await careList(auth, { size: 0 })).toMatchObject({ size: 1, totalPages: 5 });
      expect(await careList(auth, { size: 500 })).toMatchObject({ size: 100, totalPages: 1 });
      expect(await careList(auth, { page: -1 })).toMatchObject({ page: 0 });
      const past = await careList(auth, { size: 2, page: 7 });
      expect(past.content).toEqual([]);
      expect(past.totalElements).toBe(5);
    });

    it.each([
      ['status', { status: 'ALL' }],
      ['status', { status: 'open' }],
      ['type', { type: 'VACCINE' }],
      ['dueFrom', { dueFrom: '26/10/2026' }],
      ['dueFrom', { dueFrom: '2026-13-01' }],
      ['dueTo', { dueTo: '2026-02-30' }],
      ['page', { page: 'x' }],
      ['size', { size: '1.5' }],
    ])('tham số %s sai kiểu → 400 MALFORMED_REQUEST', async (name, params) => {
      const res = await failure(client.get('/api/care-tasks', { params, ...(await desk()) }));
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
      expect(res.data.message).toBe(`Tham số '${name}' sai kiểu dữ liệu`);
    });
  });

  describe('chi tiết (#2)', () => {
    it('trả đủ trường của CareTask, task đã xử lý có kết quả và người xử lý', async () => {
      const auth = await desk();
      const one = await getTask(auth, 1);
      expect(one.data.data).toEqual({
        careTaskId: 1,
        type: 'VACCINE_OVERDUE',
        status: 'DONE',
        branchId: 1,
        customerId: 1,
        customerName: 'Nguyễn Thu Trang',
        phoneToCall: '0912345678',
        petId: 2,
        petName: 'Bim',
        vaccinationId: expect.any(Number),
        medicalRecordVisitId: null,
        boardingBookingId: null,
        dueDate: '2026-10-20',
        context: expect.any(String),
        result: 'UNREACHABLE',
        note: expect.any(String),
        cancelReason: null,
        handledBy: 4,
        handledAt: '2026-10-20T07:40:00.000Z',
        createdAt: '2026-10-19T23:00:00.000Z',
      });
      const open = (await getTask(auth, 7)).data.data;
      expect(open).toMatchObject({ status: 'OPEN', result: null, handledBy: null, handledAt: null, createdAt: '2026-10-24T23:00:00.000Z' });
    });

    it('id lạ → 404 RESOURCE_NOT_FOUND; id sai kiểu → 400 MALFORMED_REQUEST', async () => {
      const auth = await desk();
      const missing = await failure(getTask(auth, 999));
      expect(missing.status).toBe(404);
      expect(missing.data.errorCode).toBe('RESOURCE_NOT_FOUND');
      expect(missing.data.message).toBe('Không tìm thấy việc gọi điện #999');
      expect((await failure(getTask(auth, 0))).status).toBe(404);
      const bad = await failure(getTask(auth, 'abc'));
      expect(bad.status).toBe(400);
      expect(bad.data.errorCode).toBe('MALFORMED_REQUEST');
    });
  });

  describe('quyền và phạm vi (BR-TB-05)', () => {
    it.each([
      ['quản lý chi nhánh', 'manager@store1.vn'],
      ['quản lý chi nhánh khác', 'manager2@store2.vn'],
      ['quản lý chuỗi', 'chuoi@petcare.vn'],
      ['quản trị hệ thống', 'admin@petcare.vn'],
      ['bác sĩ', 'doctor@store1.vn'],
      ['nhân viên chăm sóc', 'caretaker@store1.vn'],
      ['khách hàng', 'khach@petcare.vn'],
    ])('%s không dùng được Care Task: 403 ACCESS_DENIED ở cả 4 endpoint, dữ liệu không đổi', async (_who, email) => {
      const before = await everyTask(await desk());
      const auth = await as(email);
      const calls = [
        () => client.get('/api/care-tasks', auth),
        () => getTask(auth, 5),
        () => complete(auth, 5, done),
        () => cancel(auth, 5, { reason: 'Không cần gọi nữa' }),
      ];
      for (const call of calls) {
        const res = await failure(call());
        expect(res.status).toBe(403);
        expect(res.data.errorCode).toBe('ACCESS_DENIED');
      }
      expect(await everyTask(await desk())).toEqual(before);
    });

    it('lễ tân chỉ thấy task của chi nhánh mình; task chi nhánh khác → 403 ACCESS_DENIED_SCOPE_MISMATCH và không bị đổi', async () => {
      await addBranch2Task(5, 20);
      const auth = await desk();
      expect(idsOf(await careList(auth))).toEqual([5, 6, 7, 8, 9]);
      expect(idsOf(await careList(auth, { size: 100, dueFrom: '2026-10-23', dueTo: '2026-10-23' }))).toEqual([5]);

      for (const call of [() => getTask(auth, 20), () => complete(auth, 20, done), () => cancel(auth, 20, { reason: 'x' })]) {
        const res = await failure(call());
        expect(res.status).toBe(403);
        expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
      }
      expect(rawState().careTasks.find((t) => t.careTaskId === 20)).toMatchObject({ status: 'OPEN', result: null, handledBy: null });
    });
  });

  describe('thứ tự lỗi: 400 body / path → 403 vai trò → 404 / 403 phạm vi → 400 BR-TB-05 → 409', () => {
    it('body sai đến trước vai trò', async () => {
      const res = await failure(complete(await as('manager@store1.vn'), 5, { result: 'MAYBE', note: 'x' }));
      expect([res.status, res.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    });

    it('id sai kiểu đến trước vai trò', async () => {
      const res = await failure(getTask(await as('manager@store1.vn'), 'abc'));
      expect([res.status, res.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    });

    it('vai trò đến trước 404', async () => {
      const res = await failure(complete(await as('manager@store1.vn'), 999, done));
      expect([res.status, res.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    });

    it('404 đến trước BR-TB-05', async () => {
      const res = await failure(complete(await desk(), 999, { result: 'REACHED', note: '   ' }));
      expect([res.status, res.data.errorCode, res.data.message]).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy việc gọi điện #999']);
    });

    it('phạm vi chi nhánh đến trước BR-TB-05 và 409', async () => {
      await addBranch2Task(1, 21); // chi nhánh 2, đã DONE
      const auth = await desk();
      expect((await failure(complete(auth, 21, { result: 'REACHED', note: '   ' }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
      expect((await failure(complete(auth, 21, done))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
      expect((await failure(cancel(auth, 21, { reason: 'x' }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    });

    it('BR-TB-05 đến trước 409: ghi chú trống trên task đã xong vẫn báo thiếu ghi chú', async () => {
      const res = await failure(complete(await desk(), 1, { result: 'REACHED', note: ' ' }));
      expect([res.status, res.data.errorCode]).toEqual([400, 'BUSINESS_RULE_VIOLATION']);
      expect((await failure(complete(await desk(), 1, done))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    });
  });

  describe('hoàn tất (#3, BR-TB-05)', () => {
    it('OPEN → DONE: ghi kết quả, ghi chú đã strip, người xử lý và giờ xử lý; rời danh sách đang chờ', async () => {
      const auth = await desk();
      const res = await complete(auth, 7, { result: 'REACHED', note: '  Khách sẽ đến đón chiều nay  ' });
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({
        careTaskId: 7,
        type: 'PICKUP_OVERDUE',
        status: 'DONE',
        result: 'REACHED',
        note: 'Khách sẽ đến đón chiều nay',
        cancelReason: null,
        handledBy: 4,
        handledAt: '2026-10-26T02:00:00.000Z',
        dueDate: '2026-10-25',
        phoneToCall: '0987000111',
        customerName: 'Nguyễn Thu Trang',
        petName: 'Bim',
      });
      expect(idsOf(await careList(auth))).toEqual([5, 6, 8, 9]);
      expect(idsOf(await careList(auth, { status: 'DONE' }))).toEqual([2, 1, 7]);
    });

    it('không liên lạc được cũng đóng task và không sinh task gọi lại', async () => {
      const auth = await desk();
      const before = await everyTask(auth);
      const res = await complete(auth, 5, { result: 'UNREACHABLE', note: 'Gọi 2 lần không nghe máy' });
      expect(res.data.data).toMatchObject({ status: 'DONE', result: 'UNREACHABLE' });
      const after = await everyTask(auth);
      expect(after).toHaveLength(before.length);
      expect(after.filter((t) => t.status === 'OPEN').map((t) => t.careTaskId)).toEqual([6, 7, 8, 9]);
    });

    it.each([
      ['thiếu cả result và note', {}, 'VALIDATION_FAILED', ['result:', 'note:']],
      ['thiếu result', { note: 'x' }, 'VALIDATION_FAILED', ['result:']],
      ['thiếu note', { result: 'REACHED' }, 'VALIDATION_FAILED', ['note:']],
      ['note dài 501 ký tự', { result: 'REACHED', note: 'x'.repeat(501) }, 'VALIDATION_FAILED', ['note:']],
      ['result ngoài enum', { result: 'MAYBE', note: 'x' }, 'MALFORMED_REQUEST', []],
      ['note không phải chuỗi', { result: 'REACHED', note: 5 }, 'MALFORMED_REQUEST', []],
    ])('%s → 400 %s, không ghi gì', async (_name, body, code, fields) => {
      const auth = await desk();
      const before = await everyTask(auth);
      const res = await failure(complete(auth, 5, body));
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe(code);
      for (const f of fields) expect(res.data.message).toContain(f);
      expect(await everyTask(auth)).toEqual(before);
    });

    it('ghi chú dài đúng 500 ký tự được chấp nhận', async () => {
      const res = await complete(await desk(), 5, { result: 'REACHED', note: 'x'.repeat(500) });
      expect(res.status).toBe(200);
      expect(res.data.data.note).toHaveLength(500);
    });

    it('ghi chú toàn khoảng trắng → 400 BUSINESS_RULE_VIOLATION (BR-TB-05), task vẫn đang chờ', async () => {
      const auth = await desk();
      const before = await everyTask(auth);
      const res = await failure(complete(auth, 5, { result: 'REACHED', note: ' \n\t ' }));
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
      expect(res.data.message).toMatch(/\(BR-TB-05\)$/);
      expect(await everyTask(auth)).toEqual(before);
    });

    it.each([
      ['đã xong', 1, 'DONE'],
      ['đã hủy', 3, 'CANCELLED'],
    ])('task %s không hoàn tất lại được: 409 INVALID_STATE_TRANSITION, dữ liệu cũ giữ nguyên', async (_name, id, from) => {
      const auth = await desk();
      const before = await everyTask(auth);
      const res = await failure(complete(auth, id, done));
      expect(res.status).toBe(409);
      expect(res.data.errorCode).toBe('INVALID_STATE_TRANSITION');
      expect(res.data.message).toBe(`Không thể chuyển trạng thái CareTask từ ${from} sang DONE`);
      expect(await everyTask(auth)).toEqual(before);
    });

    it('bấm hai lần: lần hai bị 409 và không ghi đè kết quả lần đầu', async () => {
      const auth = await desk();
      await complete(auth, 8, { result: 'REACHED', note: 'Lần đầu' });
      const second = await failure(complete(auth, 8, { result: 'UNREACHABLE', note: 'Lần hai' }));
      expect(second.status).toBe(409);
      expect((await getTask(auth, 8)).data.data).toMatchObject({ result: 'REACHED', note: 'Lần đầu' });
    });
  });

  describe('hủy (#4)', () => {
    it('OPEN → CANCELLED: ghi lý do đã strip, người hủy và giờ hủy; không có kết quả gọi', async () => {
      const auth = await desk();
      const res = await cancel(auth, 8, { reason: '  Khách báo đã tái khám ở nơi khác  ' });
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({
        careTaskId: 8,
        status: 'CANCELLED',
        cancelReason: 'Khách báo đã tái khám ở nơi khác',
        handledBy: 4,
        handledAt: '2026-10-26T02:00:00.000Z',
        result: null,
        note: null,
      });
      expect(idsOf(await careList(auth))).toEqual([5, 6, 7, 9]);
      expect(idsOf(await careList(auth, { status: 'CANCELLED' }))).toEqual([3, 4, 8]);
    });

    it.each([
      ['thiếu reason', {}, 'VALIDATION_FAILED'],
      ['reason rỗng', { reason: '' }, 'VALIDATION_FAILED'],
      ['reason toàn khoảng trắng', { reason: '   ' }, 'VALIDATION_FAILED'],
      ['reason dài 301 ký tự', { reason: 'x'.repeat(301) }, 'VALIDATION_FAILED'],
      ['reason không phải chuỗi', { reason: 5 }, 'MALFORMED_REQUEST'],
    ])('%s → 400 %s, không ghi gì', async (_name, body, code) => {
      const auth = await desk();
      const before = await everyTask(auth);
      const res = await failure(cancel(auth, 5, body));
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe(code);
      if (code === 'VALIDATION_FAILED') expect(res.data.message).toContain('reason:');
      expect(await everyTask(auth)).toEqual(before);
    });

    it('reason dài đúng 300 ký tự được chấp nhận', async () => {
      const res = await cancel(await desk(), 5, { reason: 'x'.repeat(300) });
      expect(res.status).toBe(200);
      expect(res.data.data.cancelReason).toHaveLength(300);
    });

    it.each([
      ['đã xong', 1, 'DONE'],
      ['đã hủy', 4, 'CANCELLED'],
    ])('task %s không hủy được: 409 INVALID_STATE_TRANSITION, dữ liệu cũ giữ nguyên', async (_name, id, from) => {
      const auth = await desk();
      const before = await everyTask(auth);
      const res = await failure(cancel(auth, id, { reason: 'Hủy nhầm' }));
      expect(res.status).toBe(409);
      expect(res.data.errorCode).toBe('INVALID_STATE_TRANSITION');
      expect(res.data.message).toBe(`Không thể chuyển trạng thái CareTask từ ${from} sang CANCELLED`);
      expect(await everyTask(auth)).toEqual(before);
    });
  });

  describe('state cũ trong trình duyệt', () => {
    it('state lưu từ trước T47 (chỉ notifications, settings) vẫn chạy: thêm Care Task mẫu, giữ nguyên thông báo cũ', async () => {
      const oldNotification = {
        notificationId: 1,
        accountId: 4,
        type: 'CARE_LOG_ABNORMAL',
        title: 'Thông báo cũ của lễ tân',
        body: 'Lu bỏ ăn',
        linkUrl: null,
        createdAt: '2026-10-20T01:00:00.000Z',
        readAt: null,
      };
      const oldSettings: NotificationSettings = { ...DEFAULT_SETTINGS, vaccineReminder: { email: false, inApp: true } };
      localStorage.setItem(CARE_KEY, JSON.stringify({ notifications: [oldNotification], settings: { 7: oldSettings } }));

      const auth = await desk();
      expect(idsOf(await careList(auth))).toEqual([5, 6, 7, 8, 9]);
      const mine = (await client.get('/api/me/notifications', auth)).data.data.content as AppNotification[];
      expect(mine.map((n) => n.title)).toEqual(['Thông báo cũ của lễ tân']);
      expect((await client.get('/api/me/notification-settings', await customer())).data.data).toEqual(oldSettings);
      // Đã ghi lại: thao tác sau đọc đúng phần vừa thêm, không seed lại.
      expect(rawState().careTasks).toHaveLength(9);
      await complete(auth, 7, done);
      expect(idsOf(await careList(auth))).toEqual([5, 6, 8, 9]);
    });
  });
});
