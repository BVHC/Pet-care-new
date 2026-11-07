// Mock identity-v1 #39 `POST /auth/refresh` (ADR-0091): access token sống ngắn, refresh token xoay vòng và chỉ dùng một lần.
import axios, { type AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toApiError } from '../api-error';
import { MOCK_PASSWORD, mockSessionConfig } from './identity.mock';
import { installMockApi } from './mock-api';

const MIN = 60_000;
const ACCESS_TTL = 10 * MIN;
const SESSION_TTL = 12 * 60 * MIN;
const GENERIC = 'Refresh token không hợp lệ hoặc đã hết hạn';
const defaults = { ...mockSessionConfig };

let client: AxiosInstance;

beforeEach(() => {
  localStorage.clear();
  mockSessionConfig.accessTtlMs = ACCESS_TTL;
  mockSessionConfig.sessionTtlMs = SESSION_TTL;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-07T08:00:00Z'));
  client = axios.create();
  installMockApi(client);
});

afterEach(() => {
  vi.useRealTimers();
  Object.assign(mockSessionConfig, defaults);
});

const advance = (ms: number) => vi.setSystemTime(Date.now() + ms);
const login = async (email = 'doctor@store1.vn') => (await client.post('/api/auth/login', { email, password: MOCK_PASSWORD })).data.data;
const refresh = (refreshToken: unknown) => client.post('/api/auth/refresh', { refreshToken });
const me = (token: string) => client.get('/api/me', { headers: { Authorization: `Bearer ${token}` } });
const errorOf = (request: Promise<unknown>) => request.then(() => null, toApiError);

describe('mock #39: đăng nhập cấp refresh token', () => {
  it('trả refreshToken (≤ 128 ký tự), hạn access ngắn và hạn phiên tách riêng, access không vượt hạn phiên', async () => {
    const res = await login();

    expect(res.refreshToken).toEqual(expect.any(String));
    expect(res.refreshToken.length).toBeLessThanOrEqual(128);
    expect(Date.parse(res.accessTokenExpiresAt) - Date.now()).toBe(ACCESS_TTL);
    expect(Date.parse(res.expiresAt) - Date.now()).toBe(SESSION_TTL);
  });

  it('access token hết hạn đúng mốc hạn thì 401; token trần mock-token-<id> (e2e, test cũ) không bao giờ hết hạn', async () => {
    const { accessToken } = await login();
    expect((await me(accessToken)).status).toBe(200);

    advance(ACCESS_TTL);
    expect(await errorOf(me(accessToken))).toMatchObject({ statusCode: 401 });

    advance(30 * 24 * 60 * MIN);
    expect((await me('mock-token-5')).status).toBe(200);
  });
});

describe('mock #39: POST /auth/refresh', () => {
  it('xoay cả hai token: trả đúng 4 trường, access cũ chết ngay, access mới dùng được, hạn phiên không đổi', async () => {
    const first = await login();
    advance(6 * MIN);

    const next = (await refresh(first.refreshToken)).data.data;

    expect(Object.keys(next).sort()).toEqual(['accessToken', 'accessTokenExpiresAt', 'expiresAt', 'refreshToken']);
    expect(next.accessToken).not.toBe(first.accessToken);
    expect(next.refreshToken).not.toBe(first.refreshToken);
    expect(next.expiresAt).toBe(first.expiresAt);
    expect(Date.parse(next.accessTokenExpiresAt) - Date.now()).toBe(ACCESS_TTL);
    expect(await errorOf(me(first.accessToken))).toMatchObject({ statusCode: 401 });
    expect((await me(next.accessToken)).status).toBe(200);
  });

  it('hạn access không vượt hạn phiên (trần tuyệt đối)', async () => {
    const first = await login();
    advance(SESSION_TTL - 2 * MIN);

    const next = (await refresh(first.refreshToken)).data.data;

    expect(next.accessTokenExpiresAt).toBe(first.expiresAt);
  });

  it('dùng lại refresh token đã dùng: 401 một thông điệp chung và hủy cả phiên (token mới cũng chết)', async () => {
    const first = await login();
    const next = (await refresh(first.refreshToken)).data.data;

    expect(await errorOf(refresh(first.refreshToken))).toMatchObject({ statusCode: 401, message: GENERIC });

    expect(await errorOf(refresh(next.refreshToken))).toMatchObject({ statusCode: 401, message: GENERIC });
    expect(await errorOf(me(next.accessToken))).toMatchObject({ statusCode: 401 });
  });

  it('token lạ hoặc phiên đã quá hạn: cùng một 401 chung', async () => {
    const first = await login();
    expect(await errorOf(refresh('khong-co-that'))).toMatchObject({ statusCode: 401, message: GENERIC });

    advance(SESSION_TTL);
    expect(await errorOf(refresh(first.refreshToken))).toMatchObject({ statusCode: 401, message: GENERIC });
  });

  it('thiếu hoặc quá dài: 400 VALIDATION_FAILED, không đụng phiên', async () => {
    const first = await login();

    expect(await errorOf(refresh(undefined))).toMatchObject({ statusCode: 400, errorCode: 'VALIDATION_FAILED' });
    expect(await errorOf(refresh('x'.repeat(129)))).toMatchObject({ statusCode: 400, errorCode: 'VALIDATION_FAILED' });

    expect((await refresh(first.refreshToken)).status).toBe(200);
  });

  it('là path public: bearer gửi kèm bị bỏ qua (ADR-0005)', async () => {
    const first = await login();

    const res = await client.post('/api/auth/refresh', { refreshToken: first.refreshToken }, { headers: { Authorization: 'Bearer rác' } });

    expect(res.status).toBe(200);
  });
});
