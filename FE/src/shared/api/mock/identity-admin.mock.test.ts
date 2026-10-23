import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

let client: AxiosInstance;

beforeEach(() => {
  localStorage.clear();
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const newStaff = (role: string, extra: object = {}) => ({
  email: `${role.toLowerCase()}@new.vn`,
  phone: '0900000077',
  fullName: 'Nhân viên mới',
  role,
  ...extra,
});

describe('POST /api/staff — phân cấp tạo tài khoản (BR-QT-01)', () => {
  it('ADMIN chỉ tạo được SUPER_MANAGER', async () => {
    const auth = await as('admin@petcare.vn');
    const ok = await client.post('/api/staff', newStaff('SUPER_MANAGER'), auth);
    expect(ok.status).toBe(201);
    for (const role of ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER']) {
      const res = await failure(client.post('/api/staff', newStaff(role, { branchId: 1 }), auth));
      expect(res.status, role).toBe(403);
    }
  });

  it('SUPER_MANAGER tạo A05–A08 nhưng không tạo được SUPER_MANAGER', async () => {
    const auth = await as('chuoi@petcare.vn');
    expect((await failure(client.post('/api/staff', newStaff('SUPER_MANAGER'), auth))).status).toBe(403);
    expect((await client.post('/api/staff', newStaff('BRANCH_MANAGER', { branchId: 1 }), auth)).status).toBe(201);
  });

  it('BRANCH_MANAGER chỉ tạo A06–A08', async () => {
    const auth = await as('manager@store1.vn');
    expect((await failure(client.post('/api/staff', newStaff('BRANCH_MANAGER'), auth))).status).toBe(403);
    expect((await client.post('/api/staff', newStaff('VET'), auth)).status).toBe(201);
  });
});

describe('PUT /api/notification-templates/{code} — subject bắt buộc mọi kênh (identity-v1 #34)', () => {
  const url = '/api/notification-templates/BRANCH_MANAGER_LOCKED_APP';
  const body = async (auth: object) => {
    const list = (await client.get('/api/notification-templates', auth)).data.data as { code: string; body: string }[];
    return list.find((t) => t.code === 'BRANCH_MANAGER_LOCKED_APP')!.body;
  };

  it('IN_APP: thiếu / null / rỗng / quá 255 ký tự → 400 VALIDATION_FAILED', async () => {
    const auth = await as('admin@petcare.vn');
    const text = await body(auth);
    for (const subject of [undefined, null, '', '   ', 'x'.repeat(256)]) {
      const res = await failure(client.put(url, { subject, body: text }, auth));
      expect(res.status, String(subject)).toBe(400);
      expect(res.data.errorCode).toBe('VALIDATION_FAILED');
      expect(res.data.message).toContain('subject');
    }
  });

  it('IN_APP: có subject thì lưu được và trả lại đúng tiêu đề', async () => {
    const auth = await as('admin@petcare.vn');
    const res = await client.put(url, { subject: 'Tiêu đề mới', body: await body(auth) }, auth);
    expect(res.data.data.subject).toBe('Tiêu đề mới');
  });
});

describe('GET /api/audit-logs — from / to phải kèm offset (identity-v1 #36)', () => {
  it('thiếu offset → 400 MALFORMED_REQUEST; có offset (+07:00 hoặc Z) → 200', async () => {
    const auth = await as('admin@petcare.vn');
    for (const key of ['from', 'to']) {
      const res = await failure(client.get('/api/audit-logs', { params: { [key]: '2026-10-23T08:00:00' }, ...auth }));
      expect(res.status, key).toBe(400);
      expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
    }
    expect((await client.get('/api/audit-logs', { params: { from: '2020-01-01T08:00:00+07:00' }, ...auth })).status).toBe(200);
    expect((await client.get('/api/audit-logs', { params: { to: '2099-01-01T00:00:00Z' }, ...auth })).status).toBe(200);
  });
});
