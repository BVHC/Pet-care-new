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

// ASSUMPTION (T36, docs/testcases/T36-fe.md): lễ tân chọn người gán lượt (UC45) nhưng identity-v1 #15 loại RECEPTIONIST và 06-module-contracts L115 nói
// `findAssignableStaff` chưa có endpoint. Mock mở GET /api/staff cho lễ tân với đúng phạm vi: VET / CARETAKER ACTIVE, không bị khóa, của chi nhánh mình.
// BE thật hiện trả 403 cho lễ tân (đã dò) — chờ BE-1.
describe('GET /api/staff cho lễ tân — danh sách chọn người gán lượt (ASSUMPTION chờ BE-1, BR-TN-05, BR-TN-06)', () => {
  const desk = () => as('reception@store1.vn');
  const names = (res: AxiosResponse) => res.data.data.content.map((s: { accountId: number }) => s.accountId);

  it('không lọc: chỉ VET và CARETAKER ACTIVE của chi nhánh lễ tân (#5, #21–23 bác sĩ, #6 nhân viên chăm sóc); không thấy lễ tân, quản lý, chi nhánh khác', async () => {
    const res = await client.get('/api/staff', await desk());
    expect(names(res)).toEqual([5, 6, 21, 22, 23]);
    expect(res.data.data.content[0]).toMatchObject({ role: 'VET', status: 'ACTIVE', isLocked: false, branchId: 1, online: true });
  });

  it('role=VET&status=ACTIVE&size=100 (lời gọi của hook) và role=CARETAKER chỉ ra đúng một nhóm', async () => {
    const auth = await desk();
    expect(names(await client.get('/api/staff', { params: { role: 'VET', status: 'ACTIVE', size: 100 }, ...auth }))).toEqual([5, 21, 22, 23]);
    expect(names(await client.get('/api/staff', { params: { role: 'CARETAKER', status: 'ACTIVE', size: 100 }, ...auth }))).toEqual([6]);
  });

  it('role khác VET / CARETAKER, status khác ACTIVE, chi nhánh khác → 403 (ngoài phạm vi được mở)', async () => {
    const auth = await desk();
    for (const params of [{ role: 'RECEPTIONIST' }, { role: 'BRANCH_MANAGER' }, { role: 'SUPER_MANAGER' }, { status: 'DISABLED' }, { status: 'PENDING' }, { branchId: 2 }]) {
      const res = await failure(client.get('/api/staff', { params, ...auth }));
      expect(res.status, JSON.stringify(params)).toBe(403);
    }
    expect((await client.get('/api/staff', { params: { branchId: 1 }, ...auth })).status).toBe(200);
  });

  it('nhân viên tạo lúc chạy hiện ngay; VET ở chi nhánh khác, bị khóa hoặc vô hiệu hóa thì không', async () => {
    const chain = await as('chuoi@petcare.vn');
    const created = async (body: object) => (await client.post('/api/staff', { phone: '0900000077', ...body }, chain)).data.data.accountId as number;
    const sameBranch = await created({ email: 'vet.moi@new.vn', fullName: 'BS. Mới', role: 'VET', branchId: 1 });
    const otherBranch = await created({ email: 'vet.b2@new.vn', fullName: 'BS. Chi nhánh 2', role: 'VET', branchId: 2 });
    const locked = await created({ email: 'vet.khoa@new.vn', fullName: 'BS. Bị khóa', role: 'VET', branchId: 1 });
    await client.post(`/api/accounts/${locked}/lock`, { reason: 'Thử khóa' }, await as('admin@petcare.vn'));
    const disabled = await created({ email: 'vet.nghi@new.vn', fullName: 'BS. Đã nghỉ', role: 'VET', branchId: 1 });
    await client.post(`/api/staff/${disabled}/disable`, {}, chain);
    const res = await client.get('/api/staff', { params: { role: 'VET' }, ...(await desk()) });
    expect(names(res)).toEqual([5, 21, 22, 23, sameBranch]);
    expect(names(res)).not.toContain(otherBranch);
    expect(names(res)).not.toContain(locked);
    expect(names(res)).not.toContain(disabled);
  });

  it('cờ online lấy từ bảng hiện diện (mặc định ai cũng online): setStaffOnline đặt một người offline', async () => {
    const { setStaffOnline } = await import('./identity-admin.mock');
    setStaffOnline(6, false);
    const res = await client.get('/api/staff', await desk());
    expect(res.data.data.content.map((s: { accountId: number; online: boolean }) => [s.accountId, s.online])).toEqual([[5, true], [6, false], [21, true], [22, true], [23, true]]);
    setStaffOnline(6, true);
    expect((await client.get('/api/staff', await desk())).data.data.content.every((s: { online: boolean }) => s.online)).toBe(true);
  });
});
