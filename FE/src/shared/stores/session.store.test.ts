import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeGoogleCredential } from '../../test/google';
import { apiClient } from '../api/axios';
import { toApiError } from '../api/api-error';
import { authApi } from '../api/auth.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { activeAccount, useSession } from './session.store';

beforeAll(() => installMockApi(apiClient));

beforeEach(() => useSession.getState().clear());

const login = (email: string, password = MOCK_PASSWORD) => useSession.getState().login({ email, password });

describe('session', () => {
  it('takes the role from LoginResponse and the staff name from GET /me', async () => {
    await login('doctor@store1.vn');

    const s = useSession.getState();
    expect(s.account).toMatchObject({ id: 5, role: 'VET', mustChangePassword: false });
    expect(s.token).toBeTruthy();
    expect(s.fullName).toBe('BS. Minh Anh');
  });

  it('takes the customer name from the customer profile and keeps the pending link flag (BR-TK-19)', async () => {
    await login('khachmoi@petcare.vn');

    const s = useSession.getState();
    expect(s.fullName).toBe('Lê Hoàng Long');
    expect(s.linkDecisionPending).toBe(true);

    s.setLinkDecisionPending(false);
    s.setFullName('Lê H. Long');
    expect(useSession.getState()).toMatchObject({ linkDecisionPending: false, fullName: 'Lê H. Long' });
  });

  it('sends the token with every request after login', async () => {
    await login('reception@store1.vn');
    const me = await apiClient.get('/api/me');
    expect(me.data.data.account.email).toBe('reception@store1.vn');
  });

  it('stays logged out after a wrong password and rejects with the BE error', async () => {
    const error = await login('doctor@store1.vn', 'Sai12345').catch((e: unknown) => e);

    expect(toApiError(error)).toMatchObject({ statusCode: 401, message: 'Email hoặc mật khẩu không đúng' });
    expect(useSession.getState().account).toBeNull();
  });

  it('ends the session when the BE answers 401 (session revoked or expired)', async () => {
    await login('doctor@store1.vn');
    useSession.setState({ token: 'mock-token-revoked', refreshToken: 'mock-refresh-revoked' }); // phiên bị hủy: cả hai token đều chết

    await apiClient.get('/api/me').catch(() => undefined);

    expect(useSession.getState().account).toBeNull();
    expect(useSession.getState().token).toBeNull();
  });

  it('marks the account as must-change-password when the BE answers BR-TK-17', async () => {
    await login('newstaff@store1.vn');
    useSession.getState().setMustChangePassword(false); // client chưa biết, BE vẫn chặn

    await apiClient.get('/api/visits').catch(() => undefined);

    expect(useSession.getState().account?.mustChangePassword).toBe(true);
  });

  it('clears the session on logout', async () => {
    await login('caretaker@store1.vn');
    await useSession.getState().logout();
    expect(useSession.getState()).toMatchObject({ account: null, token: null, fullName: null });
  });

  it('treats a session past expiresAt as logged out', async () => {
    await login('doctor@store1.vn');
    const s = useSession.getState();
    expect(activeAccount(s, Date.parse(s.expiresAt!) - 1)).not.toBeNull();
    expect(activeAccount(s, Date.parse(s.expiresAt!))).toBeNull();
  });
});

// Đăng nhập Google (identity-v1 #40): dùng chung đoạn lưu phiên với `login`; axios coi /api/auth/google là path public (không làm mới, không bật cờ BR-TK-17).
describe('session, đăng nhập Google', () => {
  beforeEach(() => {
    localStorage.clear(); // state mock (tài khoản tạo qua Google) không dây sang ca khác
    vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'test-client');
  });
  afterEach(() => vi.unstubAllEnvs());

  const newGmail = { credential: fakeGoogleCredential({ sub: 'g-lan', email: 'lan@gmail.com', name: 'Lan Anh' }), isAdult: true, termsAccepted: true };

  it('loginWithGoogleStoresSessionLikeLogin', async () => {
    const account = await useSession.getState().loginWithGoogle(newGmail);

    const s = useSession.getState();
    expect(account).toMatchObject({ email: 'lan@gmail.com', role: 'CUSTOMER', status: 'ACTIVE', mustChangePassword: false });
    expect(s).toMatchObject({ account, linkDecisionPending: false, fullName: 'Lan Anh' });
    expect(s.token).toBeTruthy();
    expect(s.refreshToken).toBeTruthy();
    expect(s.accessTokenExpiresAt).toBeTruthy();
    expect(s.expiresAt).toBeTruthy();
    expect((await apiClient.get('/api/me')).data.data.account.email).toBe('lan@gmail.com'); // token gửi kèm các request sau
  });

  it('googleBrTk17DoesNotFlagMustChangePassword', async () => {
    await login('khach@petcare.vn');
    const error = await authApi.google({ credential: fakeGoogleCredential({ sub: 'g-bs', email: 'doctor@store1.vn' }) }).catch((e: unknown) => e);

    expect(toApiError(error)).toMatchObject({ statusCode: 400, ruleId: 'BR-TK-17' });
    expect(useSession.getState().account?.mustChangePassword).toBe(false);
  });

  it('google401DoesNotEndExistingSession', async () => {
    await login('khach@petcare.vn');
    const error = await authApi.google({ credential: 'abc' }).catch((e: unknown) => e);

    expect(toApiError(error)).toMatchObject({ statusCode: 401, message: 'Đăng nhập Google không thành công. Vui lòng thử lại' });
    expect(useSession.getState().account).toMatchObject({ email: 'khach@petcare.vn' });
    expect(useSession.getState().token).toBeTruthy();
  });
});
