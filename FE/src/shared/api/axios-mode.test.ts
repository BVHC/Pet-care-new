import type { AxiosError } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AccountSummary } from '../types/auth';

// axios.ts đọc biến môi trường lúc nạp mô-đun: mỗi test đặt biến rồi nạp lại. BE "thật" ở đây là cổng 1 (luôn từ chối kết nối),
// nên lệnh gọi tới BE thật luôn lỗi mạng và không bao giờ nhầm với trả lời của mock.

const customer: AccountSummary = {
  id: 7,
  email: 'khach@petcare.vn',
  role: 'CUSTOMER',
  status: 'ACTIVE',
  isLocked: false,
  mustChangePassword: false,
};

/** Lệnh gọi tới BE thật (cổng 1) luôn lỗi mạng: không có `response`. */
async function networkError(request: Promise<unknown>): Promise<AxiosError> {
  try {
    await request;
  } catch (e) {
    return e as AxiosError;
  }
  throw new Error('expected a network error');
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

async function load(env: Record<string, string>) {
  vi.resetModules();
  vi.stubEnv('VITE_API_URL', 'http://127.0.0.1:1');
  for (const [k, v] of Object.entries(env)) vi.stubEnv(k, v);
  const { apiClient } = await import('./axios');
  const { useSession } = await import('../stores/session.store');
  useSession.setState({ token: 'jwt-that-cua-be', expiresAt: null, account: customer });
  return apiClient;
}

describe('chế độ API của axios.ts', () => {
  it('VITE_API_MOCK=false, không có danh sách mock: mọi lệnh gọi đi tới BE thật', async () => {
    const client = await load({ VITE_API_MOCK: 'false', VITE_API_MOCK_ONLY: '' });
    const error = await networkError(client.get('/api/me/notifications/unread-count'));
    expect(error.response).toBeUndefined();
    expect(error.message).toBe('Network Error');
  });

  it('VITE_API_MOCK=true: mock toàn bộ, bỏ qua danh sách', async () => {
    const client = await load({ VITE_API_MOCK: 'true', VITE_API_MOCK_ONLY: '/api/orders' });
    const { useSession } = await import('../stores/session.store');
    await useSession.getState().login({ email: 'khach@petcare.vn', password: 'Petcare123' });
    const res = await client.get('/api/me/notifications/unread-count');
    expect(res.data.data).toEqual({ count: expect.any(Number) });
  });

  it('VITE_API_MOCK=false kèm danh sách: đường dẫn trong danh sách trả bằng mock theo tài khoản của phiên, còn lại tới BE thật', async () => {
    const client = await load({ VITE_API_MOCK: 'false', VITE_API_MOCK_ONLY: '/api/me/notifications' });
    const mocked = await client.get('/api/me/notifications', { headers: { Authorization: 'Bearer jwt-that-cua-be' } });
    expect(mocked.data.data.totalElements).toBe(26);
    expect((await networkError(client.get('/api/orders'))).response).toBeUndefined();
  });
});
