import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

/** Lỗi HTTP của mock: trả về response (status + body) để so với hợp đồng. */
async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

async function tokenOf(email: string, password = MOCK_PASSWORD): Promise<string> {
  const res = await client.post('/api/auth/login', { email, password });
  return res.data.data.accessToken;
}

const bearer = (token: string) => ({ headers: { Authorization: `Bearer ${token}` } });

describe('mock API envelope (00-method §3.4–3.5)', () => {
  it('answers a login with LoginResponse inside { data, message, code }', async () => {
    const res = await client.post('/api/auth/login', { email: 'doctor@store1.vn', password: MOCK_PASSWORD });

    expect(res.status).toBe(200);
    expect(res.data).toEqual({
      data: {
        accessToken: expect.any(String),
        expiresAt: expect.any(String),
        account: {
          id: 5,
          email: 'doctor@store1.vn',
          role: 'VET',
          status: 'ACTIVE',
          isLocked: false,
          mustChangePassword: false,
        },
        linkDecisionPending: false,
      },
      message: 'success',
      code: 200,
    });
  });

  it('rejects a wrong password with the 6-field error and the same message as an unknown email (BR-TK-10)', async () => {
    const wrongPassword = await failure(client.post('/api/auth/login', { email: 'doctor@store1.vn', password: 'Sai12345' }));
    const unknownEmail = await failure(client.post('/api/auth/login', { email: 'ai@do.vn', password: MOCK_PASSWORD }));

    expect(wrongPassword.status).toBe(401);
    expect(Object.keys(wrongPassword.data).sort()).toEqual(
      ['errorCode', 'message', 'statusCode', 'success', 'timestamp', 'traceId'],
    );
    expect(wrongPassword.data).toMatchObject({ success: false, errorCode: 'UNAUTHENTICATED', statusCode: 401 });
    expect(unknownEmail.data.message).toBe(wrongPassword.data.message);
  });

  it('refuses a protected path without a token', async () => {
    const res = await failure(client.get('/api/me'));
    expect(res.status).toBe(401);
    expect(res.data.errorCode).toBe('UNAUTHENTICATED');
  });

  it('answers 404 for a path that has no mock yet', async () => {
    const token = await tokenOf('reception@store1.vn');
    const res = await failure(client.get('/api/visits', bearer(token)));
    expect(res.status).toBe(404);
    expect(res.data.errorCode).toBe('RESOURCE_NOT_FOUND');
  });
});

describe('mustChangePassword (BR-TK-17)', () => {
  it('blocks every API except GET /me, POST /me/password and logout', async () => {
    const token = await tokenOf('newstaff@store1.vn');

    const blocked = await failure(client.get('/api/visits', bearer(token)));
    expect(blocked.status).toBe(400);
    expect(blocked.data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
    expect(ruleIdOf(blocked.data.message)).toBe('BR-TK-17');

    expect((await client.get('/api/me', bearer(token))).data.data.account.mustChangePassword).toBe(true);
    expect((await client.post('/api/auth/logout', null, bearer(token))).status).toBe(204);
  });

  it('lifts the block once the password is changed', async () => {
    const token = await tokenOf('newstaff@store1.vn');

    const res = await client.post(
      '/api/me/password',
      { currentPassword: MOCK_PASSWORD, newPassword: 'MatKhauMoi1' },
      bearer(token),
    );

    expect(res.status).toBe(204);
    const again = await client.post('/api/auth/login', { email: 'newstaff@store1.vn', password: 'MatKhauMoi1' });
    expect(again.data.data.account.mustChangePassword).toBe(false);
  });
});

describe('POST /me/password', () => {
  it('refuses a wrong current password (BR-TK-14)', async () => {
    const token = await tokenOf('newstaff@store1.vn');
    const res = await failure(
      client.post('/api/me/password', { currentPassword: 'Sai12345', newPassword: 'MatKhauMoi1' }, bearer(token)),
    );
    expect(ruleIdOf(res.data.message)).toBe('BR-TK-14');
  });

  it.each([
    ['too short', 'Ab12'],
    ['letters only', 'MatKhauMoi'],
    ['digits only', '12345678'],
    ['same as the current one', MOCK_PASSWORD],
  ])('refuses a new password that is %s (BR-TK-03)', async (_case, newPassword) => {
    const token = await tokenOf('newstaff@store1.vn');
    const res = await failure(
      client.post('/api/me/password', { currentPassword: MOCK_PASSWORD, newPassword }, bearer(token)),
    );
    expect(res.status).toBe(400);
    expect(ruleIdOf(res.data.message)).toBe('BR-TK-03');
  });
});
