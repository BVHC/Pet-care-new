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

// ─── T49: mock khớp BE thật cho GET /api/staff, GET /api/accounts, GET /api/audit-logs và POST /api/staff (branchId) ───

const ids = (res: AxiosResponse) => res.data.data.content.map((x: { accountId?: number; id?: number }) => x.accountId ?? x.id);
const pageInfo = (res: AxiosResponse) => {
  const { page, size, totalElements, totalPages } = res.data.data;
  return { page, size, totalElements, totalPages };
};

describe('GET /api/staff — phạm vi xem theo vai trò (BE StaffQueryService.visibleRoles)', () => {
  it('ADMIN chỉ thấy SUPER_MANAGER; lọc role ngoài tập, status PENDING hay branchId đều ra trang rỗng (không lỗi)', async () => {
    const auth = await as('admin@petcare.vn');
    expect(ids(await client.get('/api/staff', auth))).toEqual([2]);
    for (const params of [{ role: 'VET' }, { role: 'ADMIN' }, { status: 'PENDING' }, { branchId: 1 }]) {
      const res = await client.get('/api/staff', { params, ...auth });
      expect(res.data.data.content, JSON.stringify(params)).toEqual([]);
      expect(res.data.data.totalPages).toBe(0);
    }
  });

  it('SUPER_MANAGER thấy A05–A08 mọi chi nhánh, không thấy ADMIN / SUPER_MANAGER; BRANCH_MANAGER thấy A06–A08 chi nhánh mình, không thấy chính mình', async () => {
    const chain = await as('chuoi@petcare.vn');
    // Sắp theo họ tên: BS. Minh Anh (5), BS. Ngọc Lan (22), BS. Quốc Bảo (21), BS. Thanh Thảo (23), Hoàng Nam (3), Lan Chi (4), Lễ tân mới (8), Phạm Minh Đức (9), Thu Hà (6)
    const all = await client.get('/api/staff', chain);
    expect(ids(all).sort((a: number, b: number) => a - b)).toEqual([3, 4, 5, 6, 8, 9, 21, 22, 23]);
    expect(all.data.data.content.map((s: { role: string }) => s.role)).not.toContain('SUPER_MANAGER');
    const bm = await as('manager@store1.vn');
    expect(ids(await client.get('/api/staff', bm)).sort((a: number, b: number) => a - b)).toEqual([4, 5, 6, 8, 21, 22, 23]);
  });

  it('BRANCH_MANAGER lọc chi nhánh khác → 403 ACCESS_DENIED_SCOPE_MISMATCH; chi nhánh mình → 200; VET / CARETAKER → 403', async () => {
    const bm = await as('manager@store1.vn');
    const res = await failure(client.get('/api/staff', { params: { branchId: 2 }, ...bm }));
    expect(res.status).toBe(403);
    expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await client.get('/api/staff', { params: { branchId: 1 }, ...bm })).status).toBe(200);
    for (const email of ['doctor@store1.vn', 'caretaker@store1.vn']) {
      expect((await failure(client.get('/api/staff', await as(email)))).status, email).toBe(403);
    }
  });
});

describe('GET /api/staff, /api/accounts, /api/audit-logs — phân trang như BE (mặc định size 20, kẹp 1–100, page âm = 0)', () => {
  const createReceptionists = async (count: number) => {
    const chain = await as('chuoi@petcare.vn');
    for (let i = 1; i <= count; i++) {
      await client.post('/api/staff', { email: `lt${i}@new.vn`, phone: '0900000077', fullName: `Lễ tân ${String(i).padStart(2, '0')}`, role: 'RECEPTIONIST', branchId: 1 }, chain);
    }
    return chain;
  };

  it('staff: mặc định page 0 / size 20, trang 2 còn lại, sắp theo họ tên rồi id', async () => {
    const chain = await createReceptionists(14); // 9 + 14 = 23
    const first = await client.get('/api/staff', chain);
    expect(pageInfo(first)).toEqual({ page: 0, size: 20, totalElements: 23, totalPages: 2 });
    expect(first.data.data.content).toHaveLength(20);
    const second = await client.get('/api/staff', { params: { page: 1 }, ...chain });
    expect(pageInfo(second)).toEqual({ page: 1, size: 20, totalElements: 23, totalPages: 2 });
    expect(second.data.data.content).toHaveLength(3);
    const names = [...first.data.data.content, ...second.data.data.content].map((s: { fullName: string }) => s.fullName);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'vi')));
    expect((await client.get('/api/staff', { params: { page: 2 }, ...chain })).data.data).toMatchObject({ content: [], totalElements: 23, totalPages: 2 });
  });

  it('kẹp size 1–100, page âm = 0, danh sách rỗng có 0 trang, page / size không phải số → 400 MALFORMED_REQUEST', async () => {
    const chain = await createReceptionists(14);
    expect(pageInfo(await client.get('/api/staff', { params: { size: 0 }, ...chain }))).toEqual({ page: 0, size: 1, totalElements: 23, totalPages: 23 });
    expect(pageInfo(await client.get('/api/staff', { params: { size: 1000 }, ...chain }))).toMatchObject({ size: 100, totalPages: 1 });
    expect(pageInfo(await client.get('/api/staff', { params: { page: -3 }, ...chain }))).toMatchObject({ page: 0 });
    expect(pageInfo(await client.get('/api/staff', { params: { q: 'khong-co-ai' }, ...chain }))).toMatchObject({ totalElements: 0, totalPages: 0 });
    for (const params of [{ page: 'abc' }, { size: '1.5' }]) {
      const res = await failure(client.get('/api/staff', { params, ...chain }));
      expect(res.status, JSON.stringify(params)).toBe(400);
      expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
    }
  });

  it('accounts: sắp theo id, q chỉ tìm trong email (không khớp họ tên), phân trang', async () => {
    const admin = await as('admin@petcare.vn');
    const first = await client.get('/api/accounts', admin);
    expect(ids(first)).toEqual([...ids(first)].sort((a: number, b: number) => a - b));
    expect(first.data.data.size).toBe(20);
    const byName = await client.get('/api/accounts', { params: { q: 'Hoàng Nam' }, ...admin }); // họ tên của manager@store1.vn
    expect(byName.data.data.content).toEqual([]);
    const byEmail = await client.get('/api/accounts', { params: { q: 'MANAGER@STORE1' }, ...admin });
    expect(ids(byEmail)).toEqual([3]);
    expect((await client.get('/api/accounts', { params: { size: 5, page: 1 }, ...admin })).data.data).toMatchObject({ page: 1, size: 5, content: expect.any(Array) });
    expect((await failure(client.get('/api/accounts', await as('chuoi@petcare.vn')))).status).toBe(403);
  });
});

describe('POST /api/staff — branchId @Positive (BE CreateStaffRequest)', () => {
  it('branchId = 0 hoặc âm → 400 VALIDATION_FAILED "branchId: branchId phải là số dương" với MỌI vai trò, kể cả BRANCH_MANAGER', async () => {
    for (const [email, role] of [['manager@store1.vn', 'VET'], ['chuoi@petcare.vn', 'VET']] as const) {
      const auth = await as(email);
      for (const branchId of [0, -1]) {
        const res = await failure(client.post('/api/staff', newStaff(role, { branchId }), auth));
        expect(res.status, `${email} ${branchId}`).toBe(400);
        expect(res.data.errorCode).toBe('VALIDATION_FAILED');
        expect(res.data.message).toBe('branchId: branchId phải là số dương');
      }
    }
  });

  it('BRANCH_MANAGER không gửi branchId → tạo ở chi nhánh của mình', async () => {
    const res = await client.post('/api/staff', newStaff('VET'), await as('manager@store1.vn'));
    expect(res.status).toBe(201);
    expect(res.data.data.branchId).toBe(1);
  });
});

describe('GET /api/audit-logs — tên bảng, mã hành động thật, so khớp đúng, luật 31 ngày (BE AuditLogFilterValidator)', () => {
  const auditOf = async (params: object = {}) => (await client.get('/api/audit-logs', { params, ...(await as('admin@petcare.vn')) })).data.data;
  const failed = async (params: object) => failure(client.get('/api/audit-logs', { params, ...(await as('admin@petcare.vn')) }));

  const doAdminThings = async () => {
    const admin = await as('admin@petcare.vn');
    await client.post('/api/accounts/4/lock', { reason: 'Thử khóa' }, admin);
    await client.put('/api/system-configs/vet.bio_max_length', { value: '600' }, admin);
    const bm = await as('manager@store1.vn');
    await failure(client.post('/api/staff', newStaff('BRANCH_MANAGER'), bm)); // 403 BR-QT-01 → STAFF_ACTION_DENIED
  };

  it('dòng mock ghi tên bảng (accounts, system_configs) và mã hành động của BE; thao tác tạo bị từ chối không có đối tượng', async () => {
    await doAdminThings();
    const rows: { action: string; entityType?: string; entityId?: number }[] = (await auditOf()).content;
    expect(rows.map((r) => [r.action, r.entityType ?? null, r.entityId ?? null])).toEqual([
      ['STAFF_ACTION_DENIED', null, null],
      ['SYSTEM_CONFIG_UPDATED', 'system_configs', null],
      ['ACCOUNT_LOCKED', 'accounts', 4],
    ]);
  });

  it('entityType so khớp đúng tên bảng; tên kiểu cũ ("Account") không ra dòng nào', async () => {
    await doAdminThings();
    expect((await auditOf({ entityType: 'accounts' })).content.map((r: { action: string }) => r.action)).toEqual(['ACCOUNT_LOCKED']);
    expect((await auditOf({ entityType: 'Account' })).content).toEqual([]);
    expect((await auditOf({ entityType: '  ' })).totalElements).toBe(3); // toàn khoảng trắng = không lọc
  });

  it('action so khớp ĐÚNG (không phải tiền tố, không phân biệt hoa thường là sai)', async () => {
    await doAdminThings();
    const admin = { actorAccountId: 1 }; // có người thực hiện nên không cần from / to
    expect((await auditOf({ ...admin, action: 'ACCOUNT_LOCKED' })).totalElements).toBe(1);
    expect((await auditOf({ ...admin, action: 'ACCOUNT_LOCK' })).totalElements).toBe(0);
    expect((await auditOf({ ...admin, action: 'account_locked' })).totalElements).toBe(0);
  });

  it('R3: lọc action mà không có người thực hiện / (loại + id đối tượng) → cần from + to ≤ 31 ngày, nếu không 400 VALIDATION_FAILED đúng câu BE', async () => {
    const message = 'from: Lọc theo hành động cần from và to cách nhau tối đa 31 ngày, hoặc kèm actorAccountId hay entityType + entityId';
    for (const params of [
      { action: 'ACCOUNT_LOCKED' },
      { action: 'ACCOUNT_LOCKED', from: '2026-10-01T00:00:00+07:00' },
      { action: 'ACCOUNT_LOCKED', to: '2026-10-01T00:00:00+07:00' },
      { action: 'ACCOUNT_LOCKED', from: '2026-09-01T00:00:00+07:00', to: '2026-10-02T00:00:01+07:00' }, // 31 ngày + 1 giây
      { action: 'ACCOUNT_LOCKED', entityType: 'accounts' }, // loại mà không có id không là mốc chọn lọc
    ]) {
      const res = await failed(params);
      expect(res.status, JSON.stringify(params)).toBe(400);
      expect(res.data.errorCode).toBe('VALIDATION_FAILED');
      expect(res.data.message).toBe(message);
    }
    // Hợp lệ: đúng 31 ngày; có người thực hiện; có loại + id đối tượng.
    for (const params of [
      { action: 'ACCOUNT_LOCKED', from: '2026-09-01T00:00:00+07:00', to: '2026-10-02T00:00:00+07:00' },
      { action: 'ACCOUNT_LOCKED', actorAccountId: 1 },
      { action: 'ACCOUNT_LOCKED', entityType: 'accounts', entityId: 4 },
    ]) {
      expect((await auditOf(params)).content, JSON.stringify(params)).toEqual([]);
    }
  });

  it('R1 from > to và R2 entityId không kèm entityType: báo đủ các luật vi phạm, theo thứ tự to → entityType → from, nối "; "', async () => {
    // from > to: khoảng âm vẫn ≤ 31 ngày nên BE (Duration.compareTo) không báo thêm R3.
    const r1r2 = await failed({ action: 'X', entityId: 4, from: '2026-10-05T00:00:00+07:00', to: '2026-10-01T00:00:00+07:00' });
    expect(r1r2.status).toBe(400);
    expect(r1r2.data.message).toBe('to: Thời điểm kết thúc không được trước thời điểm bắt đầu; entityType: Bắt buộc khi lọc theo entityId');
    const r2r3 = await failed({ action: 'X', entityId: 4 });
    expect(r2r3.data.message).toBe(
      'entityType: Bắt buộc khi lọc theo entityId; ' +
        'from: Lọc theo hành động cần from và to cách nhau tối đa 31 ngày, hoặc kèm actorAccountId hay entityType + entityId',
    );
  });

  it('phân trang: mặc định size 20, kẹp 1–100, mới nhất trước', async () => {
    const admin = await as('admin@petcare.vn');
    for (let i = 0; i < 25; i++) await client.post(`/api/accounts/4/${i % 2 ? 'unlock' : 'lock'}`, { reason: `Lần ${i}` }, admin); // 25 dòng audit
    const first = await auditOf();
    expect(first).toMatchObject({ page: 0, size: 20, totalElements: 25, totalPages: 2 });
    expect(first.content).toHaveLength(20);
    expect(first.content[0].reason).toBe('Lần 24'); // mới nhất trước
    expect((await auditOf({ page: 1 })).content).toHaveLength(5);
    expect(await auditOf({ size: 500 })).toMatchObject({ size: 100, totalPages: 1 });
  });
});
