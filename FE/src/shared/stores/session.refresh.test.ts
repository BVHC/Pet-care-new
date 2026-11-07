// D055 / ADR-0091: FE nối refresh token. Chạy trên mock #39 (identity.mock) qua apiClient thật, đồng hồ Date giả.
import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/axios';
import { authApi } from '../api/auth.api';
import { MOCK_PASSWORD, mockSessionConfig } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { useSession } from './session.store';

const MIN = 60_000;
const ACCESS_TTL = 10 * MIN;
const defaults = { ...mockSessionConfig };

let mockAdapter: AxiosAdapter;

beforeAll(() => {
  installMockApi(apiClient);
  mockAdapter = apiClient.defaults.adapter as AxiosAdapter;
});

beforeEach(async () => {
  localStorage.clear();
  mockSessionConfig.accessTtlMs = ACCESS_TTL;
  mockSessionConfig.sessionTtlMs = 12 * 60 * MIN;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-07T08:00:00Z'));
  useSession.getState().clear();
  await useSession.getState().login({ email: 'doctor@store1.vn', password: MOCK_PASSWORD });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
  apiClient.defaults.adapter = mockAdapter;
  Object.assign(mockSessionConfig, defaults);
});

const advance = (ms: number) => vi.setSystemTime(Date.now() + ms);
const statusOf = (request: Promise<unknown>) => request.then(() => 200, (e: AxiosError) => e.response?.status ?? 0);

/** Bọc adapter của mock: chạy `intercept` trước; trả undefined thì request đi tiếp vào mock. */
function wrapAdapter(intercept: (config: Parameters<AxiosAdapter>[0]) => Promise<AxiosResponse | void>) {
  apiClient.defaults.adapter = async (config) => (await intercept(config)) ?? mockAdapter(config);
}

describe('khi nào làm mới (quy tắc 1)', () => {
  it('request của người dùng khi access token còn dưới 5 phút: làm mới trước, rồi đi với token mới', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    const before = useSession.getState();
    advance(6 * MIN);

    const res = await apiClient.get('/api/me');

    expect(res.status).toBe(200);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledWith(before.refreshToken);
    const after = useSession.getState();
    expect(after.token).not.toBe(before.token);
    expect(after.refreshToken).not.toBe(before.refreshToken);
    expect(Date.parse(after.accessTokenExpiresAt!)).toBe(Date.now() + ACCESS_TTL);
    expect(after.expiresAt).toBe(before.expiresAt);
  });

  it('còn hơn 5 phút: không làm mới', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    const before = useSession.getState().token;
    advance(4 * MIN);

    expect((await apiClient.get('/api/me')).status).toBe(200);

    expect(refresh).not.toHaveBeenCalled();
    expect(useSession.getState().token).toBe(before);
  });

  it('request poll không bao giờ làm mới, kể cả khi token còn dưới 5 phút', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    const before = useSession.getState().token;
    advance(6 * MIN);

    expect((await apiClient.get('/api/me', { poll: true })).status).toBe(200);

    expect(refresh).not.toHaveBeenCalled();
    expect(useSession.getState().token).toBe(before);
  });

  it('poll sau khi token hết hạn: nhận 401, không làm mới, KHÔNG kết thúc phiên (lệch đồng hồ không được đăng xuất người đang rảnh)', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    advance(11 * MIN);

    expect(await statusOf(apiClient.get('/api/me', { poll: true }))).toBe(401);

    expect(refresh).not.toHaveBeenCalled();
    const s = useSession.getState();
    expect(s.account).not.toBeNull();
    expect(s.refreshToken).not.toBeNull();
  });

  it('người dùng thao tác sau khi token hết hạn: làm mới được và request thành công', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    advance(11 * MIN);

    expect((await apiClient.get('/api/me')).status).toBe(200);

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('nhiều request đồng thời chỉ gửi MỘT /auth/refresh (single-flight)', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    advance(6 * MIN);

    const all = await Promise.all([apiClient.get('/api/me'), apiClient.get('/api/me'), apiClient.get('/api/me/notifications/unread-count')]);

    expect(all.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('poll đang chờ lúc có lượt làm mới thì đợi lượt đó rồi đi với token mới', async () => {
    const seen: string[] = [];
    wrapAdapter(async (config) => void seen.push(String(config.headers?.Authorization)));
    const before = useSession.getState().token;
    advance(6 * MIN);

    const [, poll] = await Promise.all([apiClient.get('/api/me'), apiClient.get('/api/me', { poll: true })]);

    expect(poll.status).toBe(200);
    expect(seen).not.toContain(`Bearer ${before}`);
  });

  it('thao tác bàn phím / chuột khi còn dưới 5 phút bắt đầu đúng một lượt làm mới; còn hơn 5 phút thì không', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    advance(4 * MIN);
    window.dispatchEvent(new Event('pointerdown'));
    await new Promise((r) => setTimeout(r, 20));
    expect(refresh).not.toHaveBeenCalled();

    advance(2 * MIN);
    const before = useSession.getState().token;
    window.dispatchEvent(new Event('pointerdown'));
    window.dispatchEvent(new Event('keydown'));

    await vi.waitFor(() => expect(useSession.getState().token).not.toBe(before));
    expect(refresh).toHaveBeenCalledTimes(1);
  });
});

describe('401 (quy tắc 3, 4)', () => {
  it('poll đang bay mang token cũ bị 401 ngay sau một lượt làm mới: thử lại đúng một lần với token mới', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    const seen: string[] = [];
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    wrapAdapter(async (config) => {
      if (config.headers?.['x-gate']) {
        seen.push(String(config.headers.Authorization));
        await gate;
      }
    });
    const old = useSession.getState().token;
    advance(6 * MIN);

    const slow = apiClient.get('/api/me', { poll: true, headers: { 'x-gate': '1' } });
    await vi.waitFor(() => expect(seen).toHaveLength(1));
    await apiClient.get('/api/me'); // người dùng thao tác: làm mới, token cũ chết
    release();

    expect((await slow).status).toBe(200);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(seen).toEqual([`Bearer ${old}`, `Bearer ${useSession.getState().token}`]);
  });

  it('request của người dùng bị 401 dù đồng hồ còn hạn: làm mới rồi thử lại, và chỉ một lần', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    useSession.setState({ token: 'mock-token-hong' });

    expect((await apiClient.get('/api/me')).status).toBe(200);

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('vẫn 401 sau khi đã thử lại: kết thúc phiên, không thử lần ba', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    let hits = 0;
    wrapAdapter(async (config) => {
      if (config.url !== '/api/always-401') return;
      hits += 1;
      throw new AxiosError('401', AxiosError.ERR_BAD_REQUEST, config, {}, { status: 401, statusText: '401', headers: {}, config, data: {} } as AxiosResponse);
    });

    expect(await statusOf(apiClient.get('/api/always-401'))).toBe(401);

    expect(hits).toBe(2);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(useSession.getState().account).toBeNull();
  });

  it('401 từ /auth/refresh kết thúc phiên và request bị từ chối', async () => {
    useSession.setState({ refreshToken: 'mock-refresh-gia' });
    advance(6 * MIN);

    expect(await statusOf(apiClient.get('/api/me'))).toBe(401);

    expect(useSession.getState()).toMatchObject({ token: null, refreshToken: null, account: null });
  });

  it('phiên bị hủy (cả access lẫn refresh đều chết): 401 rồi kết thúc phiên', async () => {
    useSession.setState({ token: 'mock-token-hong', refreshToken: 'mock-refresh-gia' });

    expect(await statusOf(apiClient.get('/api/me'))).toBe(401);

    expect(useSession.getState().account).toBeNull();
  });

  it('mất phản hồi của lượt làm mới (lỗi mạng) kết thúc phiên', async () => {
    vi.spyOn(authApi, 'refresh').mockRejectedValue(new AxiosError('Network Error', AxiosError.ERR_NETWORK));
    advance(6 * MIN);

    expect(await statusOf(apiClient.get('/api/me'))).toBe(0);

    expect(useSession.getState()).toMatchObject({ token: null, refreshToken: null, account: null });
  });

  it('phiên cũ không có refresh token (mở trước V27): không làm mới, 401 kết thúc phiên như trước', async () => {
    const refresh = vi.spyOn(authApi, 'refresh');
    useSession.setState({ token: 'mock-token-hong', refreshToken: null, accessTokenExpiresAt: null });

    expect(await statusOf(apiClient.get('/api/me'))).toBe(401);

    expect(refresh).not.toHaveBeenCalled();
    expect(useSession.getState().account).toBeNull();
  });
});

describe('lưu trữ', () => {
  it('refresh token và hạn access được lưu cùng phiên và biến mất khi đăng xuất', async () => {
    const stored = () => JSON.parse(localStorage.getItem('petcare-session') ?? '{}').state;
    expect(stored()).toMatchObject({ refreshToken: useSession.getState().refreshToken, accessTokenExpiresAt: useSession.getState().accessTokenExpiresAt });
    expect(stored().refreshToken).toEqual(expect.any(String));

    await useSession.getState().logout();

    expect(stored()).toMatchObject({ token: null, refreshToken: null, accessTokenExpiresAt: null });
  });
});
