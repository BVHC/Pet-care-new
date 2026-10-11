import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import { MOCK_OTP_CODE, MOCK_PASSWORD } from './identity.mock';
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

describe('registration (UC01, UC02)', () => {
  const NEW = { email: 'moi@petcare.vn', password: 'MatKhau1', fullName: 'Khách Mới', isAdult: true, termsAccepted: true };
  const register = (patch: Record<string, unknown> = {}) => client.post('/api/auth/register', { ...NEW, ...patch });
  const verify = (code: string, email = NEW.email) => client.post('/api/auth/register/verify', { email, code });

  afterEach(() => vi.useRealTimers());

  it('creates a PENDING account and says when the code can be resent', async () => {
    const res = await register();
    expect(res.status).toBe(201);
    expect(res.data.data).toEqual({
      accountId: expect.any(Number),
      email: NEW.email,
      status: 'PENDING',
      otpResendAvailableAt: expect.any(String),
    });
  });

  it('refuses a registration without the 18+ and terms confirmation (BR-TK-02)', async () => {
    const res = await failure(register({ isAdult: false }));
    expect(ruleIdOf(res.data.message)).toBe('BR-TK-02');
  });

  it('refuses an email that is already used (BR-TK-01)', async () => {
    const res = await failure(register({ email: 'doctor@store1.vn' }));
    expect(ruleIdOf(res.data.message)).toBe('BR-TK-01');
  });

  it('reports a payload off the contract field by field, like the BE', async () => {
    const res = await failure(client.post('/api/auth/register', { email: NEW.email, password: NEW.password, name: 'Tên cũ', phone: '12345' }));
    expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    expect(res.data.message).toContain('fullName: ');
    expect(res.data.message).toContain('phone: ');
    expect(res.data.message).toContain('isAdult: ');
  });

  it('activates the account with the code, after which the customer can log in', async () => {
    await register();
    const pending = await failure(client.post('/api/auth/login', { email: NEW.email, password: NEW.password }));
    expect(ruleIdOf(pending.data.message)).toBe('BR-TK-08');

    const verified = await verify(MOCK_OTP_CODE);
    expect(verified.data.data).toEqual({ accountId: expect.any(Number), status: 'ACTIVE', linkDecisionPending: false });

    const login = await client.post('/api/auth/login', { email: NEW.email, password: NEW.password });
    expect(login.data.data.account).toMatchObject({ email: NEW.email, role: 'CUSTOMER', status: 'ACTIVE' });
  });

  it('counts wrong codes and cancels the code on the 5th (BR-TK-05, BR-TK-06)', async () => {
    await register();
    for (let i = 0; i < 4; i++) expect(ruleIdOf((await failure(verify('000000'))).data.message)).toBe('BR-TK-05');
    expect(ruleIdOf((await failure(verify('000000'))).data.message)).toBe('BR-TK-06');
    expect(ruleIdOf((await failure(verify(MOCK_OTP_CODE))).data.message)).toBe('BR-TK-05');
  });

  it('refuses a resend within 60 seconds and allows it afterwards (BR-TK-07)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-09T10:00:00+07:00'));
    await register();

    const early = await failure(client.post('/api/auth/register/resend-otp', { email: NEW.email }));
    expect(ruleIdOf(early.data.message)).toBe('BR-TK-07');

    vi.setSystemTime(Date.parse('2026-10-09T10:01:00+07:00'));
    const res = await client.post('/api/auth/register/resend-otp', { email: NEW.email });
    expect(Date.parse(res.data.data.resendAvailableAt)).toBe(Date.parse('2026-10-09T10:02:00+07:00'));
  });

  it('answers 404 when no account waits for verification with that email', async () => {
    const res = await failure(verify(MOCK_OTP_CODE, 'ai@do.vn'));
    expect(res.status).toBe(404);
  });
});

describe('forgot / reset password (UC04)', () => {
  const forgot = (email: string) => client.post('/api/auth/password/forgot', { email });
  const reset = (email: string, code: string, newPassword: string) =>
    client.post('/api/auth/password/reset', { email, code, newPassword });

  it('answers 202 with the same body whether the email exists or not (BR-TK-10)', async () => {
    const known = await forgot('doctor@store1.vn');
    const unknown = await forgot('ai@do.vn');
    expect([known.status, unknown.status]).toEqual([202, 202]);
    expect(known.data.data).toEqual({ resendAvailableAt: expect.any(String), maskedEmail: null });
    expect(unknown.data.data).toEqual({ resendAvailableAt: expect.any(String), maskedEmail: null });
  });

  it('sets the new password with the code; the old one stops working', async () => {
    await forgot('doctor@store1.vn');
    expect((await reset('doctor@store1.vn', MOCK_OTP_CODE, 'MatKhauMoi1')).status).toBe(204);

    expect((await failure(client.post('/api/auth/login', { email: 'doctor@store1.vn', password: MOCK_PASSWORD }))).status).toBe(401);
    expect((await client.post('/api/auth/login', { email: 'doctor@store1.vn', password: 'MatKhauMoi1' })).status).toBe(200);
  });

  it('gives the same BR-TK-05 to a wrong code and to an email that never asked for one', async () => {
    await forgot('doctor@store1.vn');
    const wrong = await failure(reset('doctor@store1.vn', '000000', 'MatKhauMoi1'));
    const never = await failure(reset('ai@do.vn', MOCK_OTP_CODE, 'MatKhauMoi1'));
    expect(wrong.data.message).toBe(never.data.message);
    expect(ruleIdOf(wrong.data.message)).toBe('BR-TK-05');
  });

  it('checks the new password before the code (BR-TK-03)', async () => {
    await forgot('doctor@store1.vn');
    const res = await failure(reset('doctor@store1.vn', MOCK_OTP_CODE, 'abc'));
    expect(ruleIdOf(res.data.message)).toBe('BR-TK-03');
  });
});
