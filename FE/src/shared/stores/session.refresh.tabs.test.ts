// D055 quy tắc 2: nhiều tab cùng mở thì chỉ một tab được làm mới tại một thời điểm.
// "Tab" ở đây = một bản module riêng (vi.resetModules) cùng chung localStorage, như hai tab cùng origin; navigator.locks giả là mutex theo tên.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const MIN = 60_000;
const ACCESS_TTL = 10 * MIN;

function installFakeLocks() {
  let tail: Promise<unknown> = Promise.resolve();
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: {
      request: <T>(_name: string, fn: () => Promise<T>) => {
        const run = tail.then(fn, fn);
        tail = run.catch(() => undefined);
        return run;
      },
    },
  });
}

async function openTab() {
  vi.resetModules();
  const { apiClient } = await import('../api/axios');
  const { authApi } = await import('../api/auth.api');
  const { installMockApi } = await import('../api/mock/mock-api');
  const { MOCK_PASSWORD, mockSessionConfig } = await import('../api/mock/identity.mock');
  const store = await import('./session.store');
  installMockApi(apiClient);
  mockSessionConfig.accessTtlMs = ACCESS_TTL;
  mockSessionConfig.sessionTtlMs = 12 * 60 * MIN;
  return { apiClient, authApi, MOCK_PASSWORD, ...store };
}

beforeEach(() => {
  localStorage.clear();
  installFakeLocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-07T08:00:00Z'));
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
  delete (navigator as { locks?: unknown }).locks;
});

describe('hai tab cùng thấy token sắp hết hạn', () => {
  it('chỉ MỘT refresh token bị tiêu; tab đến sau dùng lại token mới trong storage, cả hai phiên còn sống', async () => {
    const a = await openTab();
    await a.useSession.getState().login({ email: 'doctor@store1.vn', password: a.MOCK_PASSWORD });
    const b = await openTab(); // nạp lại từ cùng localStorage: cùng phiên
    expect(b.useSession.getState().refreshToken).toBe(a.useSession.getState().refreshToken);
    const refreshA = vi.spyOn(a.authApi, 'refresh');
    const refreshB = vi.spyOn(b.authApi, 'refresh');
    vi.setSystemTime(Date.now() + 6 * MIN);

    await Promise.all([a.refreshSession(), b.refreshSession()]);

    expect(refreshA.mock.calls.length + refreshB.mock.calls.length).toBe(1);
    expect(a.useSession.getState().account).not.toBeNull();
    expect(b.useSession.getState().account).not.toBeNull();
    expect(b.useSession.getState().token).toBe(a.useSession.getState().token);
    expect(b.useSession.getState().refreshToken).toBe(a.useSession.getState().refreshToken);
    expect((await a.apiClient.get('/api/me')).status).toBe(200);
    expect((await b.apiClient.get('/api/me')).status).toBe(200);
  });

  it('tab giữ token cũ trong bộ nhớ gặp 401 sau khi tab kia xoay token: nhận token mới từ storage, không tiêu refresh token cũ', async () => {
    const a = await openTab();
    await a.useSession.getState().login({ email: 'doctor@store1.vn', password: a.MOCK_PASSWORD });
    const b = await openTab();
    vi.setSystemTime(Date.now() + 6 * MIN);
    await b.refreshSession(); // tab B xoay token; bộ nhớ tab A còn token cũ
    const refreshA = vi.spyOn(a.authApi, 'refresh');

    expect((await a.apiClient.get('/api/me', { poll: true })).status).toBe(200);

    expect(refreshA).not.toHaveBeenCalled();
    expect(a.useSession.getState().token).toBe(b.useSession.getState().token);
  });
});
