import axios, { type AxiosAdapter, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { AccountSummary } from '../../types/auth';
import { installHybridMockApi } from './mock-api';
import { parseMockOnly } from './mock-scope';

// Chế độ hỗn hợp: đường dẫn trong danh sách trả bằng mock, còn lại đi tiếp tới adapter thật (ở đây là bản giả ghi lại lệnh gọi).
// Mock không biết token JWT của BE, nên tài khoản của lệnh gọi mock lấy từ phiên đăng nhập thật.

const customer: AccountSummary = {
  id: 7,
  email: 'khach@petcare.vn',
  role: 'CUSTOMER',
  status: 'ACTIVE',
  isLocked: false,
  mustChangePassword: false,
};

let client: AxiosInstance;
let reached: string[];

beforeEach(() => {
  localStorage.clear();
  reached = [];
  client = axios.create();
  const realBackend: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    reached.push(`${(config.method ?? 'get').toUpperCase()} ${config.url}`);
    return { data: { data: 'from-real-backend', message: 'success', code: 200 }, status: 200, statusText: 'OK', headers: {}, config };
  };
  client.defaults.adapter = realBackend;
});

const install = (only: string, account: AccountSummary | null = customer) =>
  installHybridMockApi(client, parseMockOnly(only), () => account);

describe('installHybridMockApi', () => {
  it('đường dẫn không nằm trong danh sách đi tới BE thật, không đụng mock', async () => {
    install('/api/me/notifications');
    const res = await client.get('/api/orders');
    expect(res.data.data).toBe('from-real-backend');
    expect(reached).toEqual(['GET /api/orders']);
  });

  it('đường dẫn trong danh sách trả bằng mock và không gọi BE thật', async () => {
    install('/api/me/notifications');
    const res = await client.get('/api/me/notifications/unread-count', { headers: { Authorization: 'Bearer jwt-that-cua-be' } });
    expect(res.data.data).toEqual({ count: expect.any(Number) });
    expect(reached).toEqual([]);
  });

  it('lệnh gọi mock lấy tài khoản từ phiên đăng nhập thật, không từ token (token JWT của BE mock không biết)', async () => {
    install('/api/me/notifications');
    const res = await client.get('/api/me/notifications', { headers: { Authorization: 'Bearer jwt-that-cua-be' } });
    // khach@petcare.vn (id 7) có 26 thông báo trong dữ liệu mẫu của mock care
    expect(res.data.data.totalElements).toBe(26);
  });

  it('chưa có phiên đăng nhập thì lệnh gọi mock bị 401, giống BE', async () => {
    install('/api/me/notifications', null);
    await expect(client.get('/api/me/notifications')).rejects.toMatchObject({ response: { status: 401 } });
    expect(reached).toEqual([]);
  });

  it('tài khoản còn phải đổi mật khẩu bị chặn BR-TK-17 ở đường dẫn mock', async () => {
    install('/api/me/notifications', { ...customer, mustChangePassword: true });
    await expect(client.get('/api/me/notifications')).rejects.toMatchObject({
      response: { status: 400, data: { message: expect.stringContaining('BR-TK-17') } },
    });
  });

  it('luật có phương thức chỉ mock đúng phương thức đó', async () => {
    install('GET /api/me/notification-settings');
    const mockedRes = await client.get('/api/me/notification-settings');
    expect(mockedRes.data.data).toHaveProperty('appointmentReminder');
    const real = await client.put('/api/me/notification-settings', {});
    expect(real.data.data).toBe('from-real-backend');
    expect(reached).toEqual(['PUT /api/me/notification-settings']);
  });

  it('không có luật nào thì mọi lệnh gọi đi tới BE thật', async () => {
    install('');
    await client.get('/api/me/notifications');
    expect(reached).toEqual(['GET /api/me/notifications']);
  });
});
