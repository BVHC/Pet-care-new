import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { toApiError } from '../api/api-error';
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
    useSession.setState({ token: 'mock-token-revoked' });

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
