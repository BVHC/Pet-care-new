import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { AppNotification, NotificationSettings } from '../../types/care';
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
