import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { applyLifecycle } from './branch.mock';
import { MOCK_OTP_CODE, MOCK_PASSWORD } from './identity.mock';
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
  const url = '/api/notification-templates/LAST_BRANCH_MANAGER_LOCKED_APP';
  const body = async (auth: object) => {
    const list = (await client.get('/api/notification-templates', auth)).data.data as { code: string; body: string }[];
    return list.find((t) => t.code === 'LAST_BRANCH_MANAGER_LOCKED_APP')!.body;
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

// identity-v1 #38 `GET /api/staff/assignable` (ADR-0089, docs/testcases/T36-fe.md A.7): người gán lượt cho lễ tân (UC45) và quản lý (gán lại, BR-TN-08). Thay cho việc mở
// `GET /api/staff` cho lễ tân: endpoint cũ vẫn đóng (xem describe ngay dưới). Cờ `online` lấy từ bảng hiện diện của mock (BR-TN-06).
describe('GET /api/staff/assignable — danh sách chọn người gán lượt (identity-v1 #38, BR-TN-05, BR-TN-06)', () => {
  const URL = '/api/staff/assignable';
  const desk = () => as('reception@store1.vn');
  type Row = { accountId: number; fullName: string; role: string; online: boolean };
  const rows = (res: AxiosResponse) => res.data.data as Row[];
  const accountIds = (res: AxiosResponse) => rows(res).map((s) => s.accountId);
  const ask = (params: object, auth: object) => client.get(URL, { params, ...auth });

  it('lễ tân, bỏ branchId = chi nhánh mình: mảng (không phân trang) chỉ có 4 trường, đúng chức vụ, người online xếp trước rồi họ tên', async () => {
    const auth = await desk();
    const vets = await ask({ role: 'VET' }, auth);
    expect(Array.isArray(vets.data.data)).toBe(true);
    // Họ tên theo tiếng Việt: BS. Minh Anh (5), BS. Ngọc Lan (22), BS. Quốc Bảo (21), BS. Thanh Thảo (23).
    expect(accountIds(vets)).toEqual([5, 22, 21, 23]);
    expect(rows(vets)[0]).toEqual({ accountId: 5, fullName: 'BS. Minh Anh', role: 'VET', online: true });
    for (const row of rows(vets)) expect(Object.keys(row).sort()).toEqual(['accountId', 'fullName', 'online', 'role']);
    const caretakers = await ask({ role: 'CARETAKER' }, auth);
    expect(rows(caretakers)).toEqual([{ accountId: 6, fullName: 'Thu Hà', role: 'CARETAKER', online: true }]);
  });

  it('branchId của chính mình thì được; chi nhánh khác → 403 ACCESS_DENIED_SCOPE_MISMATCH', async () => {
    const auth = await desk();
    expect(accountIds(await ask({ role: 'VET', branchId: 1 }, auth))).toEqual([5, 22, 21, 23]);
    const res = await failure(ask({ role: 'VET', branchId: 2 }, auth));
    expect(res.status).toBe(403);
    expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
  });

  it('role thiếu hoặc không phải VET / CARETAKER → 400 MALFORMED_REQUEST', async () => {
    const auth = await desk();
    for (const params of [{}, { role: 'RECEPTIONIST' }, { role: 'BRANCH_MANAGER' }, { role: 'ADMIN' }, { role: 'vet' }, { branchId: 1 }]) {
      const res = await failure(ask(params, auth));
      expect(res.status, JSON.stringify(params)).toBe(400);
      expect(res.data.errorCode, JSON.stringify(params)).toBe('MALFORMED_REQUEST');
    }
  });

  it('branchId sai kiểu → 400 MALFORMED_REQUEST; không phải số dương (0, âm) → 400 VALIDATION_FAILED (@Positive)', async () => {
    const auth = await desk();
    const bad = await failure(ask({ role: 'VET', branchId: 'abc' }, auth));
    expect([bad.status, bad.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    for (const branchId of [0, -1]) {
      const res = await failure(ask({ role: 'VET', branchId }, auth));
      expect([res.status, res.data.errorCode], String(branchId)).toEqual([400, 'VALIDATION_FAILED']);
    }
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
    const wrongRole = await created({ email: 'cs.moi@new.vn', fullName: 'Chăm sóc mới', role: 'CARETAKER', branchId: 1 });
    const res = await ask({ role: 'VET' }, await desk());
    expect(accountIds(res)).toEqual([5, sameBranch, 22, 21, 23]);
    for (const id of [otherBranch, locked, disabled, wrongRole]) expect(accountIds(res)).not.toContain(id);
    // Quản lý chi nhánh 2 chỉ thấy VET của chi nhánh mình.
    expect(accountIds(await ask({ role: 'VET' }, await as('manager2@store2.vn')))).toEqual([otherBranch]);
  });

  it('cờ online lấy từ bảng hiện diện (mặc định ai cũng online): người offline xếp sau nhưng VẪN có trong danh sách (BR-TN-06 chỉ gợi ý)', async () => {
    const { setStaffOnline } = await import('./identity-admin.mock');
    const auth = await desk();
    setStaffOnline(5, false);
    const res = await ask({ role: 'VET' }, auth);
    expect(rows(res).map((s) => [s.accountId, s.online])).toEqual([[22, true], [21, true], [23, true], [5, false]]);
    setStaffOnline(5, true);
    expect(accountIds(await ask({ role: 'VET' }, auth))).toEqual([5, 22, 21, 23]);
    expect(rows(await ask({ role: 'VET' }, auth)).every((s) => s.online)).toBe(true);
  });

  it('BRANCH_MANAGER (gán lại): chi nhánh mình bỏ trống hoặc gửi đúng thì được; chi nhánh khác → 403 ACCESS_DENIED_SCOPE_MISMATCH; chi nhánh chưa có VET → mảng rỗng', async () => {
    const bm = await as('manager@store1.vn');
    expect(accountIds(await ask({ role: 'VET' }, bm))).toEqual([5, 22, 21, 23]);
    expect(accountIds(await ask({ role: 'CARETAKER', branchId: 1 }, bm))).toEqual([6]);
    const res = await failure(ask({ role: 'VET', branchId: 2 }, bm));
    expect(res.status).toBe(403);
    expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await ask({ role: 'VET' }, await as('manager2@store2.vn'))).data.data).toEqual([]);
  });

  it('SUPER_MANAGER (cấp chuỗi): không gửi branchId → 400 VALIDATION_FAILED; có branchId thì được; chi nhánh không tồn tại → mảng rỗng', async () => {
    const chain = await as('chuoi@petcare.vn');
    const res = await failure(ask({ role: 'VET' }, chain));
    expect(res.status).toBe(400);
    expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    expect(accountIds(await ask({ role: 'VET', branchId: 1 }, chain))).toEqual([5, 22, 21, 23]);
    expect((await ask({ role: 'VET', branchId: 999 }, chain)).data.data).toEqual([]);
  });

  it('ADMIN, VET, CARETAKER, CUSTOMER → 403 ACCESS_DENIED dù lời gọi hợp lệ; chưa đăng nhập → 401', async () => {
    for (const email of ['admin@petcare.vn', 'doctor@store1.vn', 'caretaker@store1.vn', 'khach@petcare.vn']) {
      const res = await failure(ask({ role: 'VET', branchId: 1 }, await as(email)));
      expect(res.status, email).toBe(403);
      expect(res.data.errorCode, email).toBe('ACCESS_DENIED');
    }
    expect((await failure(client.get(URL, { params: { role: 'VET' } }))).status).toBe(401);
  });

  it('route "/api/staff/assignable" không bị "/api/staff/:id" ăn (khai báo trước :id): 200 với mảng, không phải 400/403/404 của getStaffOne', async () => {
    const res = await client.get(URL, { params: { role: 'VET' }, validateStatus: () => true, ...(await desk()) });
    expect(res.status).toBe(200);
    expect(Array.isArray(res.data.data)).toBe(true);
  });
});

describe('GET /api/staff và GET /api/staff/{id} — vẫn đóng với lễ tân (ADR-0089: không mở endpoint cũ, StaffController chỉ dành cho quản trị nhân sự)', () => {
  it('lễ tân gọi GET /api/staff (không lọc, đúng lời gọi cũ của hộp gán, lọc chi nhánh mình) → 403 ACCESS_DENIED', async () => {
    const auth = await as('reception@store1.vn');
    for (const params of [{}, { role: 'VET', status: 'ACTIVE', size: 100 }, { branchId: 1 }]) {
      const res = await failure(client.get('/api/staff', { params, ...auth }));
      expect(res.status, JSON.stringify(params)).toBe(403);
      expect(res.data.errorCode, JSON.stringify(params)).toBe('ACCESS_DENIED');
    }
  });

  it('GET /api/staff/{id}: lễ tân, bác sĩ, nhân viên chăm sóc → 403; SUPER_MANAGER và BRANCH_MANAGER chi nhánh mình vẫn đọc được', async () => {
    for (const email of ['reception@store1.vn', 'doctor@store1.vn', 'caretaker@store1.vn']) {
      const res = await failure(client.get('/api/staff/5', await as(email)));
      expect(res.status, email).toBe(403);
      expect(res.data.errorCode, email).toBe('ACCESS_DENIED');
    }
    expect((await client.get('/api/staff/5', await as('chuoi@petcare.vn'))).data.data.accountId).toBe(5);
    expect((await client.get('/api/staff/5', await as('manager@store1.vn'))).data.data.accountId).toBe(5);
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

// ─── Cổng vai + phân cấp cho 5 route thao tác lên MỘT nhân viên khác: disable, reactivate, resend-initial-password, email-change, email-change/confirm ───
// BE: `@PreAuthorize(STAFF_ADMINS)` (ADMIN | SUPER_MANAGER | BRANCH_MANAGER; vai khác 403 ACCESS_DENIED, không audit), rồi `StaffHierarchyPolicy.manages` sau khi tìm thấy đích
// (ngoài phân cấp hoặc chính mình: 403 ACCESS_DENIED_SCOPE_MISMATCH + audit STAFF_ACTION_DENIED). Đích là nhân viên TẠO LÚC CHẠY (id ≥ 200, reset mỗi test) để test dương tính
// không làm DISABLED / đổi email nhân viên seed mà test khác còn cần. Nhân viên tạo lúc chạy không đăng nhập được trong mock nên không có ca "người gọi tạo lúc chạy".

/** BE `OtpService.INVALID_OTP_MESSAGE` + mã rule: không có mã mở, mã bị hủy hoặc mã sai đều ra đúng một câu (BR-TK-05). */
const OTP_REJECTED = 'OTP không đúng hoặc đã hết hạn (BR-TK-05)';

interface Op {
  name: string;
  /** `afterData.action` của dòng audit từ chối (BE `StaffAction`). */
  audit: string;
  path: string;
  body: object;
  /** Kết quả khi phân cấp CHO QUA với đích tạo lúc chạy, đang ACTIVE, chưa đổi mật khẩu lần đầu, chưa có mã OTP (không route nào đổi state seed). */
  reached: { status: number; errorCode?: string; message?: string };
}

// DISABLE để cuối: nếu một bản mock hỏng cho qua, các route còn lại của cùng đích vẫn chạy trên đích ACTIVE.
const OPS: Op[] = [
  { name: 'reactivate', audit: 'REACTIVATE', path: 'reactivate', body: {}, reached: { status: 409, errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái tài khoản từ ACTIVE sang ACTIVE' } },
  { name: 'resend-initial-password', audit: 'RESEND_INITIAL_PASSWORD', path: 'resend-initial-password', body: {}, reached: { status: 204 } },
  { name: 'email-change', audit: 'CHANGE_EMAIL', path: 'email-change', body: { newEmail: 'moi@new.vn' }, reached: { status: 200 } },
  { name: 'email-change/confirm', audit: 'CHANGE_EMAIL', path: 'email-change/confirm', body: { code: '000000' }, reached: { status: 400, errorCode: 'BUSINESS_RULE_VIOLATION', message: OTP_REJECTED } },
  { name: 'disable', audit: 'DISABLE', path: 'disable', body: {}, reached: { status: 200 } },
];
const opNamed = (name: string) => OPS.find((o) => o.name === name)!;

const CALLERS = {
  admin: { email: 'admin@petcare.vn', id: 1, role: 'ADMIN', branchId: null },
  chuoi: { email: 'chuoi@petcare.vn', id: 2, role: 'SUPER_MANAGER', branchId: null },
  manager: { email: 'manager@store1.vn', id: 3, role: 'BRANCH_MANAGER', branchId: 1 },
  manager2: { email: 'manager2@store2.vn', id: 9, role: 'BRANCH_MANAGER', branchId: 2 },
} as const;
type CallerName = keyof typeof CALLERS;

/** Gọi route và trả response dù thành công hay lỗi (adapter mock ném AxiosError cho mọi status ≥ 400, bất kể validateStatus). */
async function send(op: Op, id: number, auth: object, body: object = op.body): Promise<AxiosResponse> {
  try {
    return await client.post(`/api/staff/${id}/${op.path}`, body, auth);
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
}

let spawned = 0;
/** Nhân viên tạo lúc chạy: SUPER_MANAGER do ADMIN tạo, còn lại do SUPER_MANAGER tạo (bắt buộc chi nhánh). */
async function spawnStaff(role: string, branchId?: number): Promise<number> {
  const creator = await as(role === 'SUPER_MANAGER' ? CALLERS.admin.email : CALLERS.chuoi.email);
  const body = { ...newStaff(role, branchId === undefined ? {} : { branchId }), email: `rt${++spawned}@new.vn` };
  return (await client.post('/api/staff', body, creator)).data.data.accountId as number;
}

/** Đích: 'ADMIN' (seed id 1), 'SELF' (chính người gọi), 'SUPER_MANAGER', hoặc '<VAI>@<chi nhánh>' tạo lúc chạy. */
const resolveTarget = async (spec: string, who: CallerName): Promise<number> => {
  if (spec === 'ADMIN') return 1;
  if (spec === 'SELF') return CALLERS[who].id;
  const [role, branch] = spec.split('@');
  return spawnStaff(role, branch === undefined ? undefined : Number(branch));
};
const targetFacts = (spec: string, who: CallerName): { role: string; branchId: number | null } => {
  if (spec === 'ADMIN') return { role: 'ADMIN', branchId: null };
  if (spec === 'SELF') return { role: CALLERS[who].role, branchId: CALLERS[who].branchId };
  const [role, branch] = spec.split('@');
  return { role, branchId: branch === undefined ? null : Number(branch) };
};

interface AuditRow {
  action: string;
  entityType?: string | null;
  entityId?: number | null;
  actorAccountId?: number | null;
  actorEmail?: string | null;
  reason?: string | null;
  afterData?: Record<string, unknown> | null;
}
/** Các dòng STAFF_ACTION_DENIED (mới nhất trước), của một đích hoặc của tất cả. */
const denials = async (entityId?: number): Promise<AuditRow[]> => {
  const params = entityId === undefined ? { size: 100 } : { size: 100, entityType: 'accounts', entityId };
  const res = await client.get('/api/audit-logs', { params, ...(await as(CALLERS.admin.email)) });
  return (res.data.data.content as AuditRow[]).filter((r) => r.action === 'STAFF_ACTION_DENIED');
};
const staffOf = async (id: number) => (await client.get(`/api/staff/${id}`, await as(CALLERS.admin.email))).data.data;

function expectReached(res: AxiosResponse, op: Op, label: string) {
  expect(res.status, label).toBe(op.reached.status);
  if (op.reached.errorCode) expect(res.data.errorCode, label).toBe(op.reached.errorCode);
  if (op.reached.message) expect(res.data.message, label).toBe(op.reached.message);
}
const SCOPE_MISMATCH = [403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này'];

describe('cổng vai @PreAuthorize(STAFF_ADMINS) của disable / reactivate / resend-initial-password / email-change', () => {
  it.each(['reception@store1.vn', 'doctor@store1.vn', 'caretaker@store1.vn', 'khach@petcare.vn'])(
    '%s → 403 ACCESS_DENIED ở cả 5 route, không ghi audit từ chối, đích nguyên trạng',
    async (email) => {
      const target = await spawnStaff('VET', 1);
      const before = await staffOf(target);
      const auth = await as(email);
      for (const op of OPS) {
        const res = await send(op, target, auth);
        expect([res.status, res.data.errorCode, res.data.message], `${email} ${op.name}`).toEqual([403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này']);
      }
      expect(await denials()).toEqual([]);
      expect(await staffOf(target)).toEqual(before);
    },
  );

  it.each(OPS)('$name: cổng vai đứng TRƯỚC 404 — lễ tân / khách gọi với đích là khách (#7) vẫn nhận 403 ACCESS_DENIED', async (op) => {
    for (const email of ['reception@store1.vn', 'khach@petcare.vn']) {
      const res = await send(op, 7, await as(email));
      expect([res.status, res.data.errorCode], `${email} ${op.name}`).toEqual([403, 'ACCESS_DENIED']);
    }
  });

  it('validation body (400) đứng TRƯỚC cổng vai (Bean Validation chạy trước @PreAuthorize): khách / lễ tân gửi body sai vẫn nhận 400', async () => {
    const target = await spawnStaff('VET', 1);
    const cases: [string, object, string][] = [
      ['email-change', { newEmail: 'khong-hop-le' }, 'VALIDATION_FAILED'],
      ['email-change', {}, 'VALIDATION_FAILED'],
      ['email-change/confirm', { code: 'abc' }, 'VALIDATION_FAILED'],
      ['email-change/confirm', {}, 'VALIDATION_FAILED'],
      ['reactivate', { branchId: 'abc' }, 'MALFORMED_REQUEST'],
      ['reactivate', { branchId: 0 }, 'VALIDATION_FAILED'],
    ];
    for (const email of ['khach@petcare.vn', 'reception@store1.vn']) {
      const auth = await as(email);
      for (const [name, body, errorCode] of cases) {
        const res = await send(opNamed(name), target, auth, body);
        expect([res.status, res.data.errorCode], `${email} ${name} ${JSON.stringify(body)}`).toEqual([400, errorCode]);
      }
    }
    // Cũng đứng trước phân cấp: BRANCH_MANAGER gửi body sai cho đích chi nhánh khác nhận 400, không audit từ chối.
    const other = await spawnStaff('VET', 2);
    const res = await send(opNamed('reactivate'), other, await as(CALLERS.manager.email), { branchId: 'abc' });
    expect([res.status, res.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    expect(await denials()).toEqual([]);
  });
});

describe('disable / reactivate / resend-initial-password / email-change — ma trận ai quản lý ai (BE StaffHierarchyPolicy.manages)', () => {
  // [người gọi, đích, được quản lý?]. ADMIN chỉ quản SUPER_MANAGER; SUPER_MANAGER quản A05–A08 mọi chi nhánh; BRANCH_MANAGER chỉ A06–A08 cùng chi nhánh
  // (không quản BRANCH_MANAGER khác kể cả cùng chi nhánh); không ai tác động lên chính mình.
  const MATRIX: [CallerName, string, boolean][] = [
    ['admin', 'SUPER_MANAGER', true],
    ['admin', 'BRANCH_MANAGER@1', false],
    ['admin', 'RECEPTIONIST@1', false],
    ['admin', 'VET@1', false],
    ['admin', 'CARETAKER@2', false],
    ['admin', 'SELF', false],
    ['chuoi', 'BRANCH_MANAGER@1', true],
    ['chuoi', 'BRANCH_MANAGER@2', true],
    ['chuoi', 'RECEPTIONIST@1', true],
    ['chuoi', 'VET@2', true],
    ['chuoi', 'CARETAKER@1', true],
    ['chuoi', 'SUPER_MANAGER', false],
    ['chuoi', 'ADMIN', false],
    ['chuoi', 'SELF', false],
    ['manager', 'RECEPTIONIST@1', true],
    ['manager', 'VET@1', true],
    ['manager', 'CARETAKER@1', true],
    ['manager', 'RECEPTIONIST@2', false],
    ['manager', 'VET@2', false],
    ['manager', 'CARETAKER@2', false],
    ['manager', 'BRANCH_MANAGER@1', false],
    ['manager', 'BRANCH_MANAGER@2', false],
    ['manager', 'SUPER_MANAGER', false],
    ['manager', 'ADMIN', false],
    ['manager', 'SELF', false],
    ['manager2', 'VET@2', true],
    ['manager2', 'VET@1', false],
  ];

  it.each(MATRIX.filter(([, , allowed]) => allowed))('%s → %s: phân cấp cho qua cả 5 route (đi tiếp tới guard sau phân cấp)', async (who, spec) => {
    const auth = await as(CALLERS[who].email);
    for (const op of OPS) {
      const target = await resolveTarget(spec, who); // đích mới cho từng route vì disable đổi trạng thái
      expectReached(await send(op, target, auth), op, `${who} → ${spec} ${op.name}`);
    }
    expect(await denials()).toEqual([]);
  });

  it.each(MATRIX.filter(([, , allowed]) => !allowed))(
    '%s → %s: 403 ACCESS_DENIED_SCOPE_MISMATCH + audit STAFF_ACTION_DENIED ở cả 5 route, đích nguyên trạng',
    async (who, spec) => {
      const caller = CALLERS[who];
      const auth = await as(caller.email);
      const target = await resolveTarget(spec, who);
      const facts = targetFacts(spec, who);
      const before = await staffOf(target);
      let count = 0;
      for (const op of OPS) {
        const label = `${who} → ${spec} ${op.name}`;
        const res = await send(op, target, auth);
        expect([res.status, res.data.errorCode, res.data.message], label).toEqual(SCOPE_MISMATCH);
        const rows = await denials(target);
        expect(rows, label).toHaveLength(++count);
        expect(rows[0], label).toMatchObject({ action: 'STAFF_ACTION_DENIED', entityType: 'accounts', entityId: target, actorAccountId: caller.id, actorEmail: caller.email });
        expect(rows[0].reason ?? null, label).toBeNull(); // BE reason = null
        expect(rows[0].afterData, label).toEqual({ action: op.audit, targetRole: facts.role, targetBranchId: facts.branchId, requestedRole: null, requestedBranchId: null });
      }
      expect(await staffOf(target)).toEqual(before);
    },
  );

  it.each(OPS)('$name: đích là tài khoản khách (#7) hoặc không tồn tại → 404 "Không tìm thấy nhân viên #id" (trước phân cấp, không audit)', async (op) => {
    for (const who of ['admin', 'chuoi', 'manager'] as const) {
      const auth = await as(CALLERS[who].email);
      for (const id of [7, 9999]) {
        const res = await send(op, id, auth);
        expect([res.status, res.data.errorCode, res.data.message], `${who} ${op.name} #${id}`).toEqual([404, 'RESOURCE_NOT_FOUND', `Không tìm thấy nhân viên #${id}`]);
      }
    }
    expect(await denials()).toEqual([]);
  });
});

describe('POST /api/staff/{id}/reactivate — thứ tự BE: phân cấp → khóa (BR-QT-10) → BR-QT-03 → 409 đang ACTIVE', () => {
  const reactivate = opNamed('reactivate');
  const disable = opNamed('disable');
  const lock = async (id: number) => client.post(`/api/accounts/${id}/lock`, { reason: 'Thử khóa' }, await as(CALLERS.admin.email));

  it('phân cấp đứng TRƯỚC khóa: BRANCH_MANAGER với đích chi nhánh khác đang bị khóa → 403 SCOPE_MISMATCH (không phải 400 BR-QT-10)', async () => {
    const target = await spawnStaff('VET', 2);
    await lock(target);
    const res = await send(reactivate, target, await as(CALLERS.manager.email));
    expect([res.status, res.data.errorCode, res.data.message]).toEqual(SCOPE_MISMATCH);
    expect(await denials(target)).toHaveLength(1);
  });

  it('khóa (BR-QT-10) đứng TRƯỚC 409: người được phép, đích ACTIVE nhưng đang bị khóa → 400 BUSINESS_RULE_VIOLATION (BR-QT-10)', async () => {
    const target = await spawnStaff('VET', 1);
    await lock(target);
    const res = await send(reactivate, target, await as(CALLERS.chuoi.email));
    expect([res.status, res.data.errorCode]).toEqual([400, 'BUSINESS_RULE_VIOLATION']);
    expect(res.data.message).toBe('Tài khoản đang bị khóa, cần mở khóa trước (BR-QT-10)');
  });

  it('BR-QT-03 (SUPER_MANAGER kèm branchId) đứng TRƯỚC 409: đích ACTIVE → 400 (BR-QT-03); không kèm branchId mới ra 409', async () => {
    const admin = await as(CALLERS.admin.email);
    const sm = await spawnStaff('SUPER_MANAGER');
    const withBranch = await send(reactivate, sm, admin, { branchId: 1 });
    expect([withBranch.status, withBranch.data.errorCode]).toEqual([400, 'BUSINESS_RULE_VIOLATION']);
    expect(withBranch.data.message).toBe('Quản lý cấp chuỗi không thuộc chi nhánh nào (BR-QT-03)');
    const plain = await send(reactivate, sm, admin);
    expect([plain.status, plain.data.errorCode]).toEqual([409, 'INVALID_STATE_TRANSITION']);
  });

  it('BRANCH_MANAGER gửi branchId khác chi nhánh mình → 403 + audit requestedBranchId; không gửi hoặc gửi đúng chi nhánh mình thì kích hoạt lại được', async () => {
    const bm = await as(CALLERS.manager.email);
    const target = await spawnStaff('VET', 1);
    expect((await send(disable, target, bm)).status).toBe(200);

    const res = await send(reactivate, target, bm, { branchId: 2 });
    expect([res.status, res.data.errorCode, res.data.message]).toEqual(SCOPE_MISMATCH);
    const rows = await denials(target);
    expect(rows).toHaveLength(1);
    expect(rows[0].afterData).toEqual({ action: 'REACTIVATE', targetRole: 'VET', targetBranchId: 1, requestedRole: null, requestedBranchId: 2 });
    expect(await staffOf(target)).toMatchObject({ status: 'DISABLED', branchId: 1 });

    const same = await send(reactivate, target, bm, { branchId: 1 });
    expect([same.status, same.data.data.status, same.data.data.branchId]).toEqual([200, 'ACTIVE', 1]);
    expect(await denials(target)).toHaveLength(1);

    const again = await spawnStaff('VET', 1);
    await send(disable, again, bm);
    expect((await send(reactivate, again, bm)).status).toBe(200);
  });

  it('SUPER_MANAGER vẫn gán lại chi nhánh được khi kích hoạt lại', async () => {
    const chain = await as(CALLERS.chuoi.email);
    const target = await spawnStaff('VET', 1);
    await send(disable, target, chain);
    const res = await send(reactivate, target, chain, { branchId: 2 });
    expect([res.status, res.data.data.status, res.data.data.branchId]).toEqual([200, 'ACTIVE', 2]);
    expect(await denials()).toEqual([]);
  });
});

describe('POST /api/staff/{id}/email-change/confirm — phân cấp kiểm TRƯỚC khi tra mã OTP (BE StaffEmailChangeService.findEmailChangeCode)', () => {
  const sendCode = opNamed('email-change');
  const confirm = opNamed('email-change/confirm');

  it('người ngoài phân cấp không đổi được email và không đốt bộ đếm OTP: BRANCH_MANAGER chi nhánh 1 nhập sai / đúng mã cho VET chi nhánh 2 đều 403; SUPER_MANAGER xác nhận sau đó vẫn thành công', async () => {
    const target = await spawnStaff('VET', 2);
    const before = await staffOf(target);
    const chain = await as(CALLERS.chuoi.email);
    expect((await send(sendCode, target, chain)).status).toBe(200);

    const bm = await as(CALLERS.manager.email);
    const codes = ['000000', '000000', '000000', '000000', '000000', '000000', MOCK_OTP_CODE]; // 6 mã sai (> MAX_FAILED_OTP) rồi mã đúng
    for (const code of codes) {
      const res = await send(confirm, target, bm, { code });
      expect([res.status, res.data.errorCode, res.data.message], code).toEqual(SCOPE_MISMATCH);
    }
    expect(await denials(target)).toHaveLength(codes.length);
    expect((await staffOf(target)).email).toBe(before.email);

    const done = await send(confirm, target, chain, { code: MOCK_OTP_CODE });
    expect([done.status, done.data.data.email]).toEqual([200, 'moi@new.vn']);
  });

  it('đúng phân cấp thì đổi được: BRANCH_MANAGER gửi OTP rồi xác nhận cho VET cùng chi nhánh → email đổi; mã sai → 400 (BR-TK-05)', async () => {
    const target = await spawnStaff('VET', 1);
    const bm = await as(CALLERS.manager.email);
    expect((await send(sendCode, target, bm)).status).toBe(200);
    const wrong = await send(confirm, target, bm, { code: '000000' });
    expect([wrong.status, wrong.data.errorCode]).toEqual([400, 'BUSINESS_RULE_VIOLATION']);
    expect(wrong.data.message).toBe(OTP_REJECTED);
    const done = await send(confirm, target, bm, { code: MOCK_OTP_CODE });
    expect([done.status, done.data.data.email]).toEqual([200, 'moi@new.vn']);
    expect(await denials()).toEqual([]);
  });
});

// ─── Bước 2: message nguyên văn của BE cho các guard (so cả `message`, không chỉ hậu tố rule), disable giữ mustChangePassword, OTP nhân viên không trả email che ───

const triple = (res: AxiosResponse) => [res.status, res.data.errorCode, res.data.message];
const ruleViolation = (message: string) => [400, 'BUSINESS_RULE_VIOLATION', message];
const stateConflict = (message: string) => [409, 'INVALID_STATE_TRANSITION', message];

describe('disable / resend-initial-password / email-change/confirm — message nguyên văn của BE (StaffAccountService, InvalidStateTransitionException, OtpService)', () => {
  const disable = opNamed('disable');
  const lock = async (id: number) => client.post(`/api/accounts/${id}/lock`, { reason: 'Thử khóa' }, await as(CALLERS.admin.email));

  it('disable: đang bị khóa → 400 (BR-QT-12); đã DISABLED → 409 "Không thể chuyển trạng thái tài khoản từ DISABLED sang DISABLED"', async () => {
    const chain = await as(CALLERS.chuoi.email);
    const locked = await spawnStaff('VET', 1);
    await lock(locked);
    expect(triple(await send(disable, locked, chain))).toEqual(ruleViolation('Tài khoản đang bị khóa, cần mở khóa trước khi vô hiệu hóa (BR-QT-12)'));
    const target = await spawnStaff('VET', 1);
    expect((await send(disable, target, chain)).status).toBe(200);
    expect(triple(await send(disable, target, chain))).toEqual(stateConflict('Không thể chuyển trạng thái tài khoản từ DISABLED sang DISABLED'));
  });

  it('disable: quản lý cuối cùng của chi nhánh → 400 câu riêng của disable (BR-QT-04); đổi chức vụ / điều chuyển giữ nguyên câu chung cũ', async () => {
    const chain = await as(CALLERS.chuoi.email);
    const lastManager = CALLERS.manager.id; // chi nhánh 1 (ACTIVE duy nhất trong seed) chỉ có đúng một quản lý; chi nhánh DRAFT không chặn, xem describe BR-QT-04 bên dưới
    expect(triple(await send(disable, lastManager, chain))).toEqual(
      ruleViolation('Không vô hiệu hóa được: đây là quản lý cuối cùng của chi nhánh đang hoạt động (BR-QT-04)'),
    );
    const generic = ruleViolation('Chi nhánh phải có ít nhất một quản lý đang hoạt động (BR-QT-04)');
    expect(triple(await failure(client.post(`/api/staff/${lastManager}/change-role`, { role: 'VET' }, chain)))).toEqual(generic);
    expect(triple(await failure(client.post(`/api/staff/${lastManager}/transfer`, { branchId: 4 }, chain)))).toEqual(generic);
  });

  it('resend-initial-password: đích DISABLED → 409; đã đổi mật khẩu lần đầu → 409 (hai câu riêng của BE)', async () => {
    const resend = opNamed('resend-initial-password');
    const target = await spawnStaff('VET', 1);
    await send(disable, target, await as(CALLERS.chuoi.email));
    expect(triple(await send(resend, target, await as(CALLERS.chuoi.email)))).toEqual(
      stateConflict('Không thể chuyển trạng thái tài khoản nhân viên từ DISABLED sang gửi lại mật khẩu ban đầu'),
    );
    // SUPER_MANAGER seed #2 đã đổi mật khẩu lần đầu; ADMIN quản được và 409 không đổi state.
    expect(triple(await send(resend, CALLERS.chuoi.id, await as(CALLERS.admin.email)))).toEqual(
      stateConflict('Không thể chuyển trạng thái tài khoản nhân viên từ đã đổi mật khẩu lần đầu sang gửi lại mật khẩu ban đầu'),
    );
  });

  it('email-change/confirm: không có mã mở, mã sai và mã đã bị hủy đều ra MỘT câu "OTP không đúng hoặc đã hết hạn (BR-TK-05)"; lần sai thứ 5 giữ câu BR-TK-06', async () => {
    const chain = await as(CALLERS.chuoi.email);
    const confirm = opNamed('email-change/confirm');
    const target = await spawnStaff('VET', 1);
    expect(triple(await send(confirm, target, chain, { code: MOCK_OTP_CODE }))).toEqual(ruleViolation(OTP_REJECTED)); // chưa có mã mở
    expect((await send(opNamed('email-change'), target, chain)).status).toBe(200);
    for (let attempt = 1; attempt <= 4; attempt++) {
      expect(triple(await send(confirm, target, chain, { code: '000000' })), `lần sai ${attempt}`).toEqual(ruleViolation(OTP_REJECTED));
    }
    expect(triple(await send(confirm, target, chain, { code: '000000' }))).toEqual(
      ruleViolation('Nhập sai mã OTP quá 5 lần, mã đã bị hủy. Vui lòng yêu cầu mã mới (BR-TK-06)'),
    );
    expect(triple(await send(confirm, target, chain, { code: MOCK_OTP_CODE }))).toEqual(ruleViolation(OTP_REJECTED)); // mã đã bị hủy, đúng mã cũng không dùng được
  });
});

// ─── BR-QT-04 chỉ áp cho chi nhánh ACTIVE (02 BR-QT-04 "chi nhánh đang hoạt động", 08 BR-CN-10; BE StaffAccountService.checkNotLastBranchManager) ───
// BE chặn khi: người bị tác động là BRANCH_MANAGER ACTIVE, chi nhánh ACTIVE và họ là người cuối. Chi nhánh 3 (DRAFT, chưa có quản lý) dùng làm sân thử: quản lý tạo lúc chạy là người duy nhất,
// `applyLifecycle` của branch.mock đặt trạng thái chi nhánh. Không đụng nhân viên seed: vô hiệu hóa / điều chuyển chúng đổi biến module của mock, test sau bị lệch.
describe('BR-QT-04 chỉ chặn quản lý cuối của chi nhánh ACTIVE — DRAFT / SUSPENDED / CLOSED không chặn (disable, change-role, transfer)', () => {
  const GENERIC = 'Chi nhánh phải có ít nhất một quản lý đang hoạt động';
  const ACTIONS = {
    disable: { path: 'disable', body: {}, blocked: 'Không vô hiệu hóa được: đây là quản lý cuối cùng của chi nhánh đang hoạt động' },
    'change-role': { path: 'change-role', body: { role: 'VET' }, blocked: GENERIC },
    transfer: { path: 'transfer', body: { branchId: 2 }, blocked: GENERIC },
  } as const;
  type ActionName = keyof typeof ACTIONS;
  const names = Object.keys(ACTIONS) as ActionName[];
  const blockedBy = (name: ActionName) => ruleViolation(`${ACTIONS[name].blocked} (BR-QT-04)`);
  const run = async (name: ActionName, id: number) => {
    try {
      return await client.post(`/api/staff/${id}/${ACTIONS[name].path}`, ACTIONS[name].body, await as(CALLERS.chuoi.email));
    } catch (e) {
      if (axios.isAxiosError(e) && e.response) return e.response;
      throw e;
    }
  };
  const suspend = () => applyLifecycle(3, { to: 'SUSPENDED', reason: 'Sửa chữa', expectedResumeOn: null });
  /** Quản lý duy nhất của chi nhánh 3 khi chi nhánh đang ở `state` (tạo quản lý trước, vì chi nhánh tạm ngừng / đóng không nhận nhân viên mới). */
  async function soleManager(state: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED') {
    const id = await spawnStaff('BRANCH_MANAGER', 3);
    if (state !== 'DRAFT') applyLifecycle(3, { to: 'ACTIVE' });
    if (state === 'SUSPENDED') suspend();
    if (state === 'CLOSED') applyLifecycle(3, { to: 'CLOSED', reason: 'Dừng kinh doanh' });
    return id;
  }

  it.each(names)('%s: chi nhánh ACTIVE, quản lý duy nhất → 400 BR-QT-04, nguyên trạng', async (name) => {
    const id = await soleManager('ACTIVE');
    expect(triple(await run(name, id))).toEqual(blockedBy(name));
    expect(await staffOf(id)).toMatchObject({ status: 'ACTIVE', role: 'BRANCH_MANAGER', branchId: 3 });
  });

  it('chi nhánh ACTIVE có quản lý thứ hai: gỡ người đầu được; người còn lại thành quản lý cuối và bị chặn cả ba thao tác', async () => {
    const first = await soleManager('ACTIVE');
    const second = await spawnStaff('BRANCH_MANAGER', 3);
    expect((await run('disable', first)).status).toBe(200);
    for (const name of names) expect(triple(await run(name, second)), name).toEqual(blockedBy(name));
  });

  it.each(names)('%s: chi nhánh DRAFT dù chỉ có một quản lý → 200 (BE chỉ đếm khi chi nhánh ACTIVE)', async (name) => {
    expect((await run(name, await soleManager('DRAFT'))).status).toBe(200);
  });

  it.each(['SUSPENDED', 'CLOSED'] as const)('chi nhánh %s: gỡ quản lý duy nhất → 200 cả ba thao tác (bug cũ: chi nhánh có nhân viên không tạm ngừng / đóng cửa được)', async (state) => {
    for (const name of names) {
      localStorage.clear(); // mỗi thao tác một state sạch (chi nhánh 3 trở lại DRAFT, quản lý tạo lại)
      const res = await run(name, await soleManager(state));
      expect(res.status, `${state} ${name}: ${JSON.stringify(res.data)}`).toBe(200);
    }
  });

  it('tạm ngừng → vô hiệu hóa quản lý cuối được → mở lại bị chặn BR-QT-04 khi chưa có quản lý ACTIVE (branchlife #3)', async () => {
    const id = await soleManager('SUSPENDED');
    expect((await run('disable', id)).status).toBe(200);
    const blocked = await failure(client.post('/api/branches/3/resume', {}, await as(CALLERS.chuoi.email)));
    expect([blocked.status, blocked.data.errorCode]).toEqual([400, 'BUSINESS_RULE_VIOLATION']);
    expect(blocked.data.message).toMatch(/BR-QT-04\)$/);
  });

  it.each(['change-role', 'transfer'] as const)('%s: người bị tác động đã DISABLED không bị chặn (BE chỉ chặn BRANCH_MANAGER ACTIVE) dù chi nhánh ACTIVE không còn quản lý ACTIVE → 200', async (name) => {
    const id = await soleManager('SUSPENDED');
    expect((await run('disable', id)).status).toBe(200);
    applyLifecycle(3, { to: 'ACTIVE' }); // ép mở lại: chi nhánh ACTIVE, không còn quản lý ACTIVE nào
    expect(await staffOf(id)).toMatchObject({ status: 'DISABLED' });
    expect((await run(name, id)).status).toBe(200);
  });
});

describe('POST /api/staff/{id}/disable — giữ nguyên mustChangePassword (BE Account.disable chỉ đặt status = DISABLED)', () => {
  it('đích chưa đổi mật khẩu lần đầu bị vô hiệu hóa vẫn có mustChangePassword = true: trong response, GET /api/staff/{id} và danh sách', async () => {
    const chain = await as(CALLERS.chuoi.email);
    const target = await spawnStaff('VET', 1);
    expect((await staffOf(target)).mustChangePassword).toBe(true);
    const res = await send(opNamed('disable'), target, chain);
    expect([res.status, res.data.data.status, res.data.data.mustChangePassword]).toEqual([200, 'DISABLED', true]);
    expect(await staffOf(target)).toMatchObject({ status: 'DISABLED', mustChangePassword: true });
    const list = await client.get('/api/staff', { params: { status: 'DISABLED', size: 100 }, ...chain });
    expect(list.data.data.content.find((s: { accountId: number }) => s.accountId === target)).toMatchObject({ status: 'DISABLED', mustChangePassword: true });
  });
});

describe('POST /api/staff/{id}/email-change — OTP của nhân viên luôn trả maskedEmail = null (BE StaffAdminMapper; chỉ luồng liên kết hồ sơ mới có email che) và codeLength', () => {
  it('200 với đúng ba khóa: resendAvailableAt là chuỗi, maskedEmail là null (không phải email đã che, không thiếu khóa), codeLength 6 (BE OtpSentResponse dùng chung, StaffEmailChangeIT)', async () => {
    const target = await spawnStaff('VET', 1);
    const res = await send(opNamed('email-change'), target, await as(CALLERS.chuoi.email));
    expect(res.status).toBe(200);
    expect(Object.keys(res.data.data).sort()).toEqual(['codeLength', 'maskedEmail', 'resendAvailableAt']);
    expect(res.data.data.maskedEmail).toBeNull();
    expect(res.data.data.codeLength).toBe(6);
    expect(typeof res.data.data.resendAvailableAt).toBe('string');
  });
});

// BE StaffController#listAssignableStaff: "Tham số sai là 400 trước @PreAuthorize" (AssignableStaffIT#badParametersAre400BeforeTheRoleCheckAndValidOnesAre403,
// #anAuthorizedCallerWithBadParametersGets400): 400 tham số → 403 vai trò → 403 phạm vi → 400 thiếu chi nhánh. Message chép từ GlobalExceptionHandler.
describe('GET /api/staff/assignable — tham số sai là 400 TRƯỚC cổng vai (identity-v1 #38)', () => {
  const BAD: [label: string, params: object, errorCode: string, message: string][] = [
    ['thiếu role', {}, 'MALFORMED_REQUEST', "Thiếu tham số 'role'"],
    ['role ngoài VET / CARETAKER', { role: 'RECEPTIONIST' }, 'MALFORMED_REQUEST', "Tham số 'role' sai kiểu dữ liệu"],
    ['role đúng chữ nhưng sai hoa thường', { role: 'vet' }, 'MALFORMED_REQUEST', "Tham số 'role' sai kiểu dữ liệu"],
    ['branchId = 0', { role: 'VET', branchId: 0 }, 'VALIDATION_FAILED', 'branchId: branchId phải là số dương'],
    ['branchId âm', { role: 'CARETAKER', branchId: -5 }, 'VALIDATION_FAILED', 'branchId: branchId phải là số dương'],
    ['branchId sai kiểu', { role: 'VET', branchId: 'abc' }, 'MALFORMED_REQUEST', "Tham số 'branchId' sai kiểu dữ liệu"],
    ['role sai đứng trước branchId sai', { role: 'XYZ', branchId: 0 }, 'MALFORMED_REQUEST', "Tham số 'role' sai kiểu dữ liệu"],
  ];

  it.each([
    'reception@store1.vn',
    'manager@store1.vn',
    'chuoi@petcare.vn',
    'doctor@store1.vn',
    'caretaker@store1.vn',
    'khach@petcare.vn',
    'admin@petcare.vn',
  ])('%s: mọi tham số sai đều 400 với message nguyên văn của BE (kể cả vai ngoài nhóm gán lượt)', async (email) => {
    const auth = await as(email);
    for (const [label, params, errorCode, message] of BAD) {
      const res = await failure(client.get('/api/staff/assignable', { params, ...auth }));
      expect([res.status, res.data.errorCode, res.data.message], `${email} ${label}`).toEqual([400, errorCode, message]);
    }
  });

  it.each(['doctor@store1.vn', 'caretaker@store1.vn', 'khach@petcare.vn', 'admin@petcare.vn'])('%s: tham số đúng thì vẫn 403 ACCESS_DENIED như cũ', async (email) => {
    const res = await failure(client.get('/api/staff/assignable', { params: { role: 'VET', branchId: 1 }, ...(await as(email)) }));
    expect([res.status, res.data.errorCode, res.data.message]).toEqual([403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này']);
  });
});

describe('GET /api/audit-logs — tên bảng, mã hành động thật, so khớp đúng, action không còn giới hạn khoảng (BE AuditLogFilterValidator, ADR-0098)', () => {
  const auditOf = async (params: object = {}) => (await client.get('/api/audit-logs', { params, ...(await as('admin@petcare.vn')) })).data.data;
  const failed = async (params: object) => failure(client.get('/api/audit-logs', { params, ...(await as('admin@petcare.vn')) }));

  const doAdminThings = async () => {
    const admin = await as('admin@petcare.vn');
    await client.post('/api/accounts/4/lock', { reason: 'Thử khóa' }, admin);
    await client.put('/api/system-configs/vet.bio_max_length', { value: '300' }, admin); // BE: 100–500, mặc định 500 (giá trị trùng thì BE không ghi, không audit)
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
    expect((await auditOf({ action: 'ACCOUNT_LOCKED' })).totalElements).toBe(1);
    expect((await auditOf({ action: 'ACCOUNT_LOCK' })).totalElements).toBe(0);
    expect((await auditOf({ action: 'account_locked' })).totalElements).toBe(0);
  });

  it('R3 đã bỏ (V31, ADR-0098): lọc action một mình, thiếu from / to hay cách nhau hơn 31 ngày đều hợp lệ (200)', async () => {
    await doAdminThings();
    for (const params of [
      { action: 'ACCOUNT_LOCKED' },
      { action: 'ACCOUNT_LOCKED', from: '2026-10-01T00:00:00+07:00' },
      { action: 'ACCOUNT_LOCKED', to: '2026-10-01T00:00:00+07:00' },
      { action: 'ACCOUNT_LOCKED', from: '2025-01-01T00:00:00+07:00', to: '2026-12-31T00:00:00+07:00' }, // gần hai năm
      { action: 'ACCOUNT_LOCKED', entityType: 'accounts' },
      { action: 'ACCOUNT_LOCKED', actorAccountId: 1 },
      { action: 'ACCOUNT_LOCKED', entityType: 'accounts', entityId: 4 },
    ]) {
      const res = await client.get('/api/audit-logs', { params, ...(await as('admin@petcare.vn')) });
      expect(res.status, JSON.stringify(params)).toBe(200);
    }
    expect((await auditOf({ action: 'ACCOUNT_LOCKED' })).content.map((r: { action: string }) => r.action)).toEqual(['ACCOUNT_LOCKED']);
  });

  it('R1 from > to và R2 entityId không kèm entityType: báo đủ các luật vi phạm, theo thứ tự to → entityType, nối "; "', async () => {
    const r1r2 = await failed({ action: 'X', entityId: 4, from: '2026-10-05T00:00:00+07:00', to: '2026-10-01T00:00:00+07:00' });
    expect(r1r2.status).toBe(400);
    expect(r1r2.data.message).toBe('to: Thời điểm kết thúc không được trước thời điểm bắt đầu; entityType: Bắt buộc khi lọc theo entityId');
    const r2 = await failed({ action: 'X', entityId: 4 });
    expect(r2.data.message).toBe('entityType: Bắt buộc khi lọc theo entityId');
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

// ─── UC10 — tham số [CFG] và mẫu thông báo: dữ liệu seed khớp BE, validate như BE (BR-QT-13, BR-QT-14) ───
// Nguồn thật là migration của BE (V2 + V27 + V35 + V40 + V44…V46 + V48 tham số, V27 xóa staff.online_window_minutes và thêm session.access_ttl_minutes; V3…V26 + V40 mẫu, V4 sửa OTP_REGISTER). Không có thư mục BE bên cạnh (chỉ checkout FE) thì các ca đối chiếu SQL
// được bỏ qua; ca gõ tay và ca hành vi vẫn chạy.

const MIGRATIONS = resolve(__dirname, '../../../../../BE/src/main/resources/db/migration');
const hasMigrations = existsSync(resolve(MIGRATIONS, 'V2__seed_system_configs.sql'));

type Cell = string | string[] | null;
type SqlRow = Record<string, Cell>;

const skipSpace = (sql: string, from: number) => {
  let i = from;
  while (i < sql.length && /\s/.test(sql[i])) i++;
  return i;
};

/** Literal chuỗi SQL tại `start`: `'…'` (`''` = một dấu '), hoặc `E'…'` (\n \t \r \\ \'). `end` là vị trí ngay sau dấu đóng. */
function readString(sql: string, start: number): { text: string; end: number } {
  let i = start;
  const escapes = sql[i] === 'E';
  if (escapes) i++;
  if (sql[i] !== "'") throw new Error(`Không phải literal SQL tại ${start}: ${sql.slice(start, start + 40)}`);
  let text = '';
  for (i++; ; i++) {
    const c = sql[i];
    if (c === undefined) throw new Error(`Literal SQL không đóng, bắt đầu tại ${start}`);
    if (c === "'") {
      if (sql[i + 1] !== "'") break;
      text += "'";
      i++;
    } else if (escapes && c === '\\') {
      const next = sql[++i];
      text += next === 'n' ? '\n' : next === 't' ? '\t' : next === 'r' ? '\r' : next;
    } else {
      text += c;
    }
  }
  return { text, end: i + 1 };
}

/** Bỏ chú thích `-- …` nằm ngoài literal (chú thích V2 có dấu phẩy và chữ INSERT; literal giữ nguyên). */
function stripComments(sql: string): string {
  let out = '';
  for (let i = 0; i < sql.length; ) {
    const c = sql[i];
    if (c === '-' && sql[i + 1] === '-') {
      while (i < sql.length && sql[i] !== '\n') i++;
    } else if (c === "'" || (c === 'E' && sql[i + 1] === "'" && !/\w/.test(sql[i - 1] ?? ' '))) {
      const { end } = readString(sql, i);
      out += sql.slice(i, end);
      i = end;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Mọi dòng của `INSERT INTO <table> (cột…) VALUES (…), (…);` theo tên cột; `'[…]'::jsonb` thành mảng chuỗi. Gặp INSERT không đọc được thì ném lỗi. */
function insertedRows(sql: string, table: string): SqlRow[] {
  const rows: SqlRow[] = [];
  const mentions = [...sql.matchAll(new RegExp(`INSERT\\s+INTO\\s+${table}\\b`, 'gi'))].length;
  const statements = [...sql.matchAll(new RegExp(`INSERT\\s+INTO\\s+${table}\\s*\\(([^)]*)\\)\\s*VALUES`, 'gi'))];
  if (statements.length !== mentions) throw new Error(`INSERT INTO ${table} không có dạng "(cột…) VALUES": ${mentions} câu, đọc được ${statements.length}`);
  for (const statement of statements) {
    const columns = statement[1].split(',').map((c) => c.trim());
    let i = statement.index! + statement[0].length;
    for (;;) {
      i = skipSpace(sql, i);
      if (sql[i] === ';') break;
      if (sql[i] === ',') {
        i++;
        continue;
      }
      if (sql[i] !== '(') throw new Error(`INSERT INTO ${table}: cần "(" tại ${sql.slice(i, i + 40)}`);
      i++;
      const row: SqlRow = {};
      for (const [n, column] of columns.entries()) {
        i = skipSpace(sql, i);
        if (/^NULL\b/i.test(sql.slice(i, i + 5))) {
          row[column] = null;
          i += 4;
        } else {
          const { text, end } = readString(sql, i);
          i = end;
          if (sql.startsWith('::jsonb', i)) {
            row[column] = JSON.parse(text) as string[];
            i += '::jsonb'.length;
          } else {
            row[column] = text;
          }
        }
        i = skipSpace(sql, i);
        const expected = n === columns.length - 1 ? ')' : ',';
        if (sql[i] !== expected) throw new Error(`INSERT INTO ${table}: cần "${expected}" tại ${sql.slice(i, i + 40)}`);
        i++;
      }
      rows.push(row);
    }
  }
  return rows;
}

/** `UPDATE notification_templates SET cột = 'literal', …, updated_at = now() WHERE code = '…';` (V4). Dạng khác thì ném lỗi, không đoán. */
function applyTemplateUpdates(sql: string, templates: Map<string, SqlRow>) {
  const mentions = [...sql.matchAll(/UPDATE\s+notification_templates\b/gi)].length;
  const statements = [...sql.matchAll(/UPDATE\s+notification_templates\s+SET\s+/gi)];
  if (statements.length !== mentions) throw new Error('UPDATE notification_templates không có dạng "SET …"');
  for (const statement of statements) {
    let i = statement.index! + statement[0].length;
    const set: SqlRow = {};
    for (;;) {
      const column = /^\w+/.exec(sql.slice(i))?.[0];
      if (!column) throw new Error(`UPDATE notification_templates: không đọc được cột tại ${sql.slice(i, i + 40)}`);
      i = skipSpace(sql, i + column.length);
      if (sql[i] !== '=') throw new Error(`UPDATE notification_templates: cần "=" sau ${column}`);
      i = skipSpace(sql, i + 1);
      if (sql[i] === "'" || sql[i] === 'E') {
        const { text, end } = readString(sql, i);
        set[column] = text;
        i = end;
      } else {
        const now = /^now\(\)/i.exec(sql.slice(i));
        if (!now) throw new Error(`UPDATE notification_templates: giá trị của ${column} không phải literal hay now()`);
        i += now[0].length;
      }
      i = skipSpace(sql, i);
      if (sql[i] !== ',') break;
      i = skipSpace(sql, i + 1);
    }
    const where = /^WHERE\s+code\s*=\s*/i.exec(sql.slice(i));
    if (!where) throw new Error('UPDATE notification_templates: thiếu WHERE code = …');
    const { text: code, end } = readString(sql, i + where[0].length);
    if (!/^\s*;/.test(sql.slice(end))) throw new Error(`UPDATE notification_templates: sau WHERE code = '${code}' còn điều kiện khác`);
    const row = templates.get(code);
    if (!row) throw new Error(`UPDATE notification_templates: chưa có mẫu ${code}`);
    Object.assign(row, set);
  }
}

/** `DELETE FROM system_configs WHERE key = '…';` (V27) ra danh sách khóa bị xóa. DELETE không điều kiện hay điều kiện khác thì ném lỗi, không đoán. */
function deletedConfigKeys(sql: string): string[] {
  const mentions = [...sql.matchAll(/DELETE\s+FROM\s+system_configs\b/gi)].length;
  const statements = [...sql.matchAll(/DELETE\s+FROM\s+system_configs\s+WHERE\s+key\s*=\s*/gi)];
  if (statements.length !== mentions) throw new Error('DELETE FROM system_configs không có dạng "WHERE key = …;"');
  return statements.map((statement) => {
    const { text: key, end } = readString(sql, statement.index! + statement[0].length);
    if (!/^\s*;/.test(sql.slice(end))) throw new Error(`DELETE FROM system_configs WHERE key = '${key}': còn điều kiện khác`);
    return key;
  });
}

/** Cách làm đổi dữ liệu hai bảng mà bộ đọc không hiểu. DELETE trên system_configs không nằm ở đây: `deletedConfigKeys` đọc dạng `WHERE key =` và ném lỗi với dạng khác. */
const UNREADABLE_SQL = /TRUNCATE(\s+TABLE)?\s+(system_configs|notification_templates)\b|DELETE\s+FROM\s+notification_templates\b|UPDATE\s+system_configs\b/i;

const byText = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0); // thứ tự code-unit như `String.compareTo` của Java (BE IT khẳng định isSorted)
const versionOf = (file: string) => Number(/^V(\d+)__/.exec(file)![1]);

/** Trạng thái sau khi chạy mọi migration theo số phiên bản: V10 sau V9, không theo thứ tự chữ cái. */
function sqlSeed() {
  const configs: SqlRow[] = [];
  const templates = new Map<string, SqlRow>();
  const files = readdirSync(MIGRATIONS).filter((f) => /^V\d+__.*\.sql$/.test(f)).sort((a, b) => versionOf(a) - versionOf(b));
  for (const file of files) {
    const raw = readFileSync(resolve(MIGRATIONS, file), 'utf8');
    if (!/system_configs|notification_templates/.test(raw)) continue;
    const sql = stripComments(raw);
    // Mọi cách khác làm đổi dữ liệu hai bảng này mà bộ đọc không hiểu: dừng, đừng đoán.
    expect(sql, file).not.toMatch(UNREADABLE_SQL);
    // Xóa trước, thêm sau (V27 xóa khóa cũ rồi thêm khóa mới): khóa chưa có để xóa thì dừng, đừng đoán.
    for (const key of deletedConfigKeys(sql)) {
      const at = configs.findIndex((r) => r.key === key);
      if (at < 0) throw new Error(`${file}: DELETE khóa ${key} nhưng chưa có khóa này`);
      configs.splice(at, 1);
    }
    configs.push(...insertedRows(sql, 'system_configs'));
    for (const row of insertedRows(sql, 'notification_templates')) {
      if (templates.has(row.code as string)) throw new Error(`${file}: mẫu ${row.code} đã có`);
      templates.set(row.code as string, row);
    }
    applyTemplateUpdates(sql, templates);
  }
  // Mock không seed `complaint.*` và `COMPLAINT_*` (ADR-0122), nhưng V39 / V40 của BE còn INSERT chúng cho tới khi có V51 (DROP + DELETE);
  // lọc ra để so đúng phần mock có. Khi V51 đến thì hai bộ lọc dưới thành no-op: xóa chúng.
  return {
    configs: configs.filter((r) => !String(r.key).startsWith('complaint.')),
    templates: new Map([...templates].filter(([code]) => !code.startsWith('COMPLAINT_'))),
  };
}

const CONFIG_FIELDS = ['key', 'value', 'valueType', 'minValue', 'maxValue', 'unit', 'description'];
const TEMPLATE_FIELDS = ['code', 'channel', 'subject', 'body', 'allowedVars', 'requiredVars', 'isDefault'];

const sqlConfigs = () =>
  sqlSeed()
    .configs.map((r) => ({ key: r.key, value: r.value, valueType: r.value_type, minValue: r.min_value, maxValue: r.max_value, unit: r.unit, description: r.description }) as Record<string, unknown>)
    .sort((a, b) => byText(a.key as string, b.key as string));

const sqlTemplates = () =>
  [...sqlSeed().templates.values()].sort((a, b) => byText(a.code as string, b.code as string));

const admin = () => as('admin@petcare.vn');
const getConfigs = async () => (await client.get('/api/system-configs', await admin())).data.data as Record<string, string>[];
const getTemplates = async () => (await client.get('/api/notification-templates', await admin())).data.data as Record<string, unknown>[];
const putConfig = async (key: string, value: unknown) => client.put(`/api/system-configs/${key}`, { value }, await admin());
const putTemplate = async (code: string, body: object) => client.put(`/api/notification-templates/${code}`, body, await admin());
const auditActions = async () =>
  ((await client.get('/api/audit-logs', { params: { actorAccountId: 1 }, ...(await admin()) })).data.data.content as { action: string }[]).map((r) => r.action);

describe('bộ đọc SQL của ca đối chiếu seed — DELETE trên system_configs (V27)', () => {
  it("đọc `DELETE FROM system_configs WHERE key = '…';` ra khóa bị xóa; không có DELETE thì rỗng", () => {
    expect(deletedConfigKeys("DELETE FROM system_configs WHERE key = 'staff.online_window_minutes';\nINSERT INTO t (a) VALUES ('x');")).toEqual(['staff.online_window_minutes']);
    expect(deletedConfigKeys('SELECT 1;')).toEqual([]);
  });

  it.each([
    ['không điều kiện', 'DELETE FROM system_configs;'],
    ['điều kiện không phải key =', "DELETE FROM system_configs WHERE value = '10';"],
    ['còn điều kiện sau key =', "DELETE FROM system_configs WHERE key = 'a.b' OR key = 'c.d';"],
  ])('từ chối DELETE %s: dừng, không đoán', (_, sql) => {
    expect(() => deletedConfigKeys(sql)).toThrow(/DELETE FROM system_configs/);
  });

  it.each([
    'TRUNCATE system_configs;',
    'TRUNCATE TABLE notification_templates;',
    'DELETE FROM notification_templates;',
    "DELETE FROM notification_templates WHERE code = 'X';",
    "UPDATE system_configs SET value = '1';",
  ])('lưới chặn vẫn cấm: %s', (sql) => {
    expect(sql).toMatch(UNREADABLE_SQL);
  });

  it('lưới chặn không còn cấm DELETE có `WHERE key =` trên system_configs', () => {
    expect("DELETE FROM system_configs WHERE key = 'a.b';").not.toMatch(UNREADABLE_SQL);
  });
});

describe('UC10 — tham số [CFG] seed khớp BE (V2, V27, V35, V40, V44, V45, V46, V48, V52)', () => {
  it.skipIf(!hasMigrations)('đúng 80 khóa, từng dòng giống hệt SQL (key, value, valueType, minValue, maxValue, unit, description), sắp theo key', async () => {
    const expected = sqlConfigs();
    expect(expected).toHaveLength(80); // = 86 hằng số ConfigKey của BE (52 của V2 + V27, 19 ai.* của V35 + V52, 8 của V40, 7 của V44…V48) trừ 6 complaint.* mà mock không seed (xem sqlSeed)
    const rows = await getConfigs();
    expect(rows.map((r) => r.key)).toEqual(expected.map((r) => r.key));
    for (const [i, row] of rows.entries()) {
      expect(Object.keys(row).sort(), row.key).toEqual([...CONFIG_FIELDS].sort());
      expect(row, row.key).toEqual(expected[i]);
    }
  });

  it('dòng gõ tay theo V2 + V27 (+ V35, V40, V44…V48): khóa thật của BE có mặt, khóa bịa của bản mock cũ và khóa V27 đã xóa không còn', async () => {
    const rows = await getConfigs();
    const by = Object.fromEntries(rows.map((r) => [r.key, r]));
    expect(rows).toHaveLength(80);
    expect(by['otp.ttl_minutes']).toEqual({ key: 'otp.ttl_minutes', value: '5', valueType: 'INT', minValue: '1', maxValue: '15', unit: 'phút', description: 'Thời gian hiệu lực của mã OTP (BR-TK-05)' });
    expect(by['boarding.checkout_time']).toMatchObject({ value: '12:00', valueType: 'TIME', minValue: '06:00', maxValue: '22:00', unit: 'giờ' });
    expect(by['address.max_per_customer']).toMatchObject({ value: '5', minValue: '1', maxValue: '20', unit: 'địa chỉ' });
    expect(by['otp.resend_interval_seconds']).toMatchObject({ value: '60', minValue: '30', maxValue: '300', unit: 'giây' });
    expect(by['session.ttl_hours']).toMatchObject({ value: '12', minValue: '1', maxValue: '72', unit: 'giờ' });
    expect(by['session.access_ttl_minutes']).toEqual({ key: 'session.access_ttl_minutes', value: '10', valueType: 'INT', minValue: '5', maxValue: '720', unit: 'phút', description: 'Hạn access token; nhân viên có phiên với access token còn hạn được coi là online (BR-TN-06, docs/adr/0091)' }); // V27
    expect(by['vet.bio_max_length']).toMatchObject({ value: '500', minValue: '100', maxValue: '500', unit: 'ký tự' });
    for (const old of ['customer.address_book_max', 'auth.otp_resend_seconds', 'auth.otp_max_per_hour', 'auth.session_ttl_hours', 'staff.online_window_minutes']) expect(by[old], old).toBeUndefined(); // staff.online_window_minutes: V27 xóa
  });

  it('28 khóa tầng 3 gõ tay theo V35 / V40 / V44–V48 / V52: đúng số lượng từng nhóm, 4 khóa DECIMAL đều ở ai.*', async () => {
    const rows = await getConfigs();
    const by = Object.fromEntries(rows.map((r) => [r.key, r]));
    const prefixCount = (prefix: string) => rows.filter((r) => r.key.startsWith(prefix)).length;
    expect([prefixCount('ai.'), prefixCount('complaint.'), prefixCount('workforce.'), prefixCount('shop.'), prefixCount('payment.'), prefixCount('returns.'), prefixCount('content.')]).toEqual([19, 0, 2, 3, 2, 1, 1]);
    expect(rows.filter((r) => r.valueType === 'DECIMAL').map((r) => r.key)).toEqual(['ai.reco.weight_attr', 'ai.reco.weight_history', 'ai.reco.weight_popularity', 'ai.retrieval.min_similarity']);
    expect(rows.filter((r) => r.valueType !== 'INT' && r.valueType !== 'DECIMAL').map((r) => r.key)).toEqual(['ai.chat.general_knowledge_enabled', 'boarding.checkout_time']); // BOOL duy nhất là công tắc V52 (T92); tầng 3 không thêm TIME
    expect(by['ai.retrieval.min_similarity']).toEqual({ key: 'ai.retrieval.min_similarity', value: '0.6', valueType: 'DECIMAL', minValue: '0.3', maxValue: '0.9', unit: 'cosine', description: 'Ngưỡng độ tương đồng cosine tối thiểu để một đoạn được dùng; dưới ngưỡng thì ngoài phạm vi (BR-AI-04)' });
    expect(by['ai.chat.general_knowledge_enabled']).toMatchObject({ value: 'false', valueType: 'BOOL', minValue: 'false', maxValue: 'true', unit: 'bật/tắt' }); // mặc định tắt (BR-AI-33)
    expect(by['ai.reco.weight_attr']).toMatchObject({ value: '0.5', minValue: '0', maxValue: '1', unit: 'trọng số' });
    expect(by['ai.chat.max_question_chars']).toMatchObject({ value: '500', valueType: 'INT', minValue: '100', maxValue: '2000', unit: 'ký tự' });
    expect(by['ai.reco.senior_age_years_cat']).toMatchObject({ value: '10', minValue: '5', maxValue: '15', unit: 'năm' });
    expect(by['workforce.request_lead_hours']).toMatchObject({ value: '24', minValue: '0', maxValue: '168', unit: 'giờ' });
    expect(by['shop.payment_window_hours']).toMatchObject({ value: '24', minValue: '1', maxValue: '168', unit: 'giờ' });
    expect(by['payment.refund_max_attempts']).toMatchObject({ value: '3', minValue: '1', maxValue: '10', unit: 'lần' });
    expect(by['returns.window_days']).toMatchObject({ value: '7', minValue: '1', maxValue: '30', unit: 'ngày' });
    expect(by['content.comment_per_hour']).toMatchObject({ value: '10', minValue: '1', maxValue: '100', unit: 'bình luận' });
  });
});

describe('PUT /api/system-configs/{key} — validate như BE SystemConfigEditService (BR-QT-13)', () => {
  const rule = 'BUSINESS_RULE_VIOLATION';

  it.each(['0', '16', '-3', '1.5', 'abc', '5 phút', '99999999999'])('otp.ttl_minutes = %s (INT, 1–15) → 400 BR-QT-13 kèm khoảng cho phép', async (value) => {
    const res = await failure(putConfig('otp.ttl_minutes', value));
    expect([res.status, res.data.errorCode, res.data.message]).toEqual([400, rule, 'Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút) (BR-QT-13)']);
  });

  it('số / boolean JSON được ép thành chuỗi rồi mới kiểm kiểu và khoảng (Jackson)', async () => {
    expect((await failure(putConfig('otp.ttl_minutes', true))).data.errorCode).toBe(rule);
    expect((await failure(putConfig('otp.ttl_minutes', 1.5))).data.errorCode).toBe(rule);
    expect((await failure(putConfig('otp.ttl_minutes', 16))).data.errorCode).toBe(rule);
    const res = await putConfig('otp.ttl_minutes', 7);
    expect([res.status, res.data.data.value, res.data.message]).toEqual([200, '7', 'Đã cập nhật tham số']);
  });

  it.each(['1', '15', '07', '+9'])('otp.ttl_minutes = %s nằm trong khoảng → 200, lưu dạng chuẩn hóa', async (value) => {
    const res = await putConfig('otp.ttl_minutes', value);
    expect(res.status).toBe(200);
    expect(res.data.data.value).toBe(String(Number(value)));
    expect((await getConfigs()).find((c) => c.key === 'otp.ttl_minutes')!.value).toBe(String(Number(value)));
  });

  it('khoảng lấy từ chính dòng dữ liệu: vet.bio_max_length dưới 100, address.max_per_customer trên 20 đều bị từ chối với đúng khoảng của dòng đó', async () => {
    expect((await failure(putConfig('vet.bio_max_length', '99'))).data.message).toMatch(/^Giá trị của vet\.bio_max_length phải là số nguyên từ 100 đến \d+ \(ký tự\) \(BR-QT-13\)$/);
    expect((await failure(putConfig('address.max_per_customer', '21'))).data.message).toBe('Giá trị của address.max_per_customer phải là số nguyên từ 1 đến 20 (địa chỉ) (BR-QT-13)');
  });

  it('TIME: chỉ HH:mm trong [06:00, 22:00]; không có đơn vị trong câu báo lỗi (đúng BE ConfigValues.describeAllowed)', async () => {
    const message = 'Giá trị của boarding.checkout_time phải là giờ dạng HH:mm từ 06:00 đến 22:00 (BR-QT-13)';
    for (const value of ['05:59', '22:01', '9:00', '12:60', '24:00', '12h', '12:00:00']) {
      const res = await failure(putConfig('boarding.checkout_time', value));
      expect([res.status, res.data.message], value).toEqual([400, message]);
    }
    const res = await putConfig('boarding.checkout_time', '21:30');
    expect([res.status, res.data.data.value, res.data.data.valueType]).toEqual([200, '21:30', 'TIME']);
  });

  it('DECIMAL (V35, ai.retrieval.min_similarity 0.3–0.9): chỉ số thập phân thường, khoảng và đơn vị trong câu báo lỗi, lưu dạng chuẩn BigDecimal.stripTrailingZeros', async () => {
    const message = 'Giá trị của ai.retrieval.min_similarity phải là số thập phân từ 0.3 đến 0.9 (cosine) (BR-QT-13)';
    for (const value of ['0.29', '0.91', '1', '-0.5', '1e-1', '0,6', '.6', '0.', 'abc', '0.6 cosine']) {
      const res = await failure(putConfig('ai.retrieval.min_similarity', value));
      expect([res.status, res.data.errorCode, res.data.message], value).toEqual([400, 'BUSINESS_RULE_VIOLATION', message]);
    }
    for (const [sent, stored] of [['0.30', '0.3'], ['+0.90', '0.9'], ['0.75', '0.75'], ['00.700', '0.7'], [0.8, '0.8']] as const) {
      const res = await putConfig('ai.retrieval.min_similarity', sent);
      expect([res.status, res.data.data.value, res.data.data.valueType], String(sent)).toEqual([200, stored, 'DECIMAL']);
      expect((await getConfigs()).find((c) => c.key === 'ai.retrieval.min_similarity')!.value).toBe(stored);
    }
    // Khoảng 0–1 của trọng số: "1.0" → "1"; số rất dài không đi qua Number (không ra dạng mũ). Tổng ba trọng số = 1 KHÔNG được kiểm (V35, ConfigConstraints chỉ hỗ trợ cặp INT).
    expect((await putConfig('ai.reco.weight_attr', '1.0')).data.data.value).toBe('1');
    expect((await putConfig('ai.reco.weight_history', '0.0000001')).data.data.value).toBe('0.0000001');
    expect((await putConfig('ai.reco.weight_popularity', '0.0')).data.data.value).toBe('0');
    expect((await failure(putConfig('ai.reco.weight_attr', '1.01'))).data.message).toBe('Giá trị của ai.reco.weight_attr phải là số thập phân từ 0 đến 1 (trọng số) (BR-QT-13)');
  });

  it('khóa INT mới của tầng 3 dùng khoảng của chính dòng: ai.chat.max_question_chars, workforce.request_lead_hours (0–168), content.comment_per_hour', async () => {
    expect((await failure(putConfig('ai.chat.max_question_chars', '2001'))).data.message).toBe('Giá trị của ai.chat.max_question_chars phải là số nguyên từ 100 đến 2000 (ký tự) (BR-QT-13)');
    expect((await failure(putConfig('workforce.request_lead_hours', '169'))).data.message).toBe('Giá trị của workforce.request_lead_hours phải là số nguyên từ 0 đến 168 (giờ) (BR-QT-13)');
    expect((await failure(putConfig('content.comment_per_hour', '0'))).data.message).toBe('Giá trị của content.comment_per_hour phải là số nguyên từ 1 đến 100 (bình luận) (BR-QT-13)');
    expect((await failure(putConfig('ai.retrieval.top_k', '1.5'))).data.errorCode).toBe('BUSINESS_RULE_VIOLATION'); // INT không nhận số thập phân
    const res = await putConfig('workforce.request_lead_hours', '0');
    expect([res.status, res.data.data.value]).toEqual([200, '0']);
  });

  it('ràng buộc chéo (ADR-0036): khoảng thu hẹp theo giá trị hiện hành của khóa kia, kèm lý do', async () => {
    const message = async (key: string, value: string) => (await failure(putConfig(key, value))).data.message;
    expect(await message('visit.late_priority_minutes', '30')).toBe('Giá trị của visit.late_priority_minutes phải là số nguyên từ 0 đến 29 (phút): phải nhỏ hơn appointment.no_show_after_minutes đang là 30 (BR-QT-13)');
    expect(await message('appointment.no_show_after_minutes', '15')).toBe('Giá trị của appointment.no_show_after_minutes phải là số nguyên từ 16 đến 120 (phút): phải lớn hơn visit.late_priority_minutes đang là 15 (BR-QT-13)');
    expect(await message('appointment.late_cancel_hours', '24')).toBe('Giá trị của appointment.late_cancel_hours phải là số nguyên từ 0 đến 23 (giờ): phải nhỏ hơn appointment.reminder_hours_before đang là 24 (BR-QT-13)');
    expect(await message('appointment.reminder_hours_before', '12')).toBe('Giá trị của appointment.reminder_hours_before phải là số nguyên từ 13 đến 72 (giờ): phải lớn hơn appointment.late_cancel_hours đang là 12 (BR-QT-13)');
    // boarding.min_nights ≤ boarding.max_nights: khoảng tĩnh [1, 7] chưa bị thu hẹp (max_nights đang là 30) nên chưa có lý do; hạ max_nights xuống 5 thì có.
    expect(await message('boarding.min_nights', '8')).toBe('Giá trị của boarding.min_nights phải là số nguyên từ 1 đến 7 (đêm) (BR-QT-13)');
    expect((await putConfig('boarding.max_nights', '5')).status).toBe(200);
    expect(await message('boarding.min_nights', '6')).toBe('Giá trị của boarding.min_nights phải là số nguyên từ 1 đến 5 (đêm): không được lớn hơn boarding.max_nights đang là 5 (BR-QT-13)');
    expect((await putConfig('boarding.min_nights', '5')).status).toBe(200); // bằng nhau được (≤)
    // Cặp nới ra rồi thì khoảng của khóa kia nới theo.
    expect((await putConfig('appointment.no_show_after_minutes', '60')).status).toBe(200);
    expect((await putConfig('visit.late_priority_minutes', '30')).status).toBe(200);
  });

  it('giá trị trùng hiện tại sau chuẩn hóa ("05" = "5") → 200, không ghi, không audit; đổi thật thì audit {key, value} trước / sau', async () => {
    const same = await putConfig('otp.ttl_minutes', '05');
    expect([same.status, same.data.data.value, same.data.message]).toEqual([200, '5', 'Đã cập nhật tham số']);
    expect(await auditActions()).toEqual([]);
    expect((await putConfig('otp.ttl_minutes', '9')).status).toBe(200);
    const rows = (await client.get('/api/audit-logs', { params: { actorAccountId: 1 }, ...(await admin()) })).data.data.content;
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ action: 'SYSTEM_CONFIG_UPDATED', entityType: 'system_configs', beforeData: { key: 'otp.ttl_minutes', value: '5' }, afterData: { key: 'otp.ttl_minutes', value: '9' } });
    expect((await putConfig('otp.ttl_minutes', '09')).status).toBe(200); // lần hai cũng trùng
    expect(await auditActions()).toHaveLength(1);
  });

  it('hình thức (Bean Validation) → 404 khóa lạ → rule; hình thức chạy trước cổng vai như BE', async () => {
    for (const body of [{}, { value: '' }, { value: '   ' }, { value: null }]) {
      const res = await failure(client.put('/api/system-configs/otp.ttl_minutes', body, await admin()));
      expect([res.status, res.data.errorCode, res.data.message], JSON.stringify(body)).toEqual([400, 'VALIDATION_FAILED', 'value: Vui lòng nhập giá trị']);
    }
    const tooLong = await failure(putConfig('otp.ttl_minutes', '1'.repeat(256)));
    expect([tooLong.status, tooLong.data.message]).toEqual([400, 'value: Giá trị tối đa 255 ký tự']);
    const unknown = await failure(putConfig('customer.address_book_max', '5'));
    expect([unknown.status, unknown.data.errorCode, unknown.data.message]).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy tham số #customer.address_book_max']);
    const notAdmin = await as('chuoi@petcare.vn');
    expect((await failure(client.put('/api/system-configs/otp.ttl_minutes', { value: '7' }, notAdmin))).status).toBe(403);
    expect((await failure(client.put('/api/system-configs/otp.ttl_minutes', { value: '' }, notAdmin))).status).toBe(400);
  });
});

describe('UC10 — mẫu thông báo seed khớp BE (V3…V26, V40)', () => {
  it.skipIf(!hasMigrations)('đúng 63 mã, từng mẫu giống hệt SQL (kênh, tiêu đề, nội dung, allowedVars, requiredVars, isDefault), sắp theo code', async () => {
    const expected = sqlTemplates().map((r) => ({
      code: r.code,
      channel: r.channel,
      subject: r.subject,
      body: r.body,
      allowedVars: r.allowed_vars,
      requiredVars: r.required_vars,
      isDefault: r.subject === r.default_subject && r.body === r.default_body,
    }));
    expect(expected).toHaveLength(63);
    const rows = await getTemplates();
    expect(rows.map((r) => r.code)).toEqual(expected.map((r) => r.code));
    for (const [i, row] of rows.entries()) {
      expect(Object.keys(row).sort(), String(row.code)).toEqual([...TEMPLATE_FIELDS].sort());
      expect(row, String(row.code)).toEqual(expected[i]);
    }
  });

  it('mọi mẫu seed hợp lệ theo chính luật BE: requiredVars ⊆ allowedVars, mọi biến dùng nằm trong allowedVars, biến bắt buộc có mặt trong tiêu đề ∪ nội dung', async () => {
    const used = (text: string) => [...text.matchAll(/\{([^{}]*)\}/g)].map((m) => m[1]);
    for (const t of (await getTemplates()) as { code: string; subject: string; body: string; allowedVars: string[]; requiredVars: string[] }[]) {
      expect(t.allowedVars, t.code).toEqual(expect.arrayContaining(t.requiredVars));
      const names = new Set([...used(t.subject), ...used(t.body)]);
      for (const name of names) expect(t.allowedVars, `${t.code} {${name}}`).toContain(name);
      for (const name of t.requiredVars) expect([...names], `${t.code} thiếu {${name}}`).toContain(name);
      expect(`${t.subject}${t.body}`.replace(/\{[^{}]*\}/g, ''), `${t.code}: ngoặc nhọn lẻ`).not.toMatch(/[{}]/);
    }
  });

  it('dòng gõ tay theo SQL: mã thật của BE có mặt, mã bịa của bản mock cũ không còn, OTP_REGISTER là bản V4', async () => {
    const rows = await getTemplates();
    const by = Object.fromEntries(rows.map((r) => [r.code as string, r]));
    expect(rows).toHaveLength(63);
    expect(by['LAST_BRANCH_MANAGER_LOCKED_APP']).toMatchObject({
      channel: 'IN_APP',
      subject: 'Chi nhánh không còn quản lý đang hoạt động',
      allowedVars: ['ho_ten', 'email_tai_khoan', 'vai_tro', 'ly_do', 'thoi_diem', 'viec_do_dang', 'ten_chi_nhanh'],
      requiredVars: ['email_tai_khoan', 'ly_do', 'thoi_diem', 'viec_do_dang', 'ten_chi_nhanh'],
      isDefault: true,
    });
    expect(by['STAFF_TEMP_PASSWORD']).toMatchObject({ channel: 'EMAIL', allowedVars: ['ho_ten', 'email_dang_nhap', 'mat_khau_tam'], requiredVars: ['ho_ten', 'email_dang_nhap', 'mat_khau_tam'] });
    expect(by['EMAIL_CHANGED_NOTICE']).toMatchObject({ allowedVars: ['email_cu', 'email_moi', 'thoi_diem'] });
    expect(by['OTP_REGISTER']).toMatchObject({
      channel: 'EMAIL',
      subject: 'Mã xác thực đăng ký tài khoản Pet Care',
      allowedVars: ['ten_khach', 'ma_otp', 'thoi_han_phut'],
      requiredVars: ['ma_otp'],
    });
    expect(by['OTP_REGISTER'].body).toBe(
      'Xin chào,\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.\n\nPet Care',
    );
    expect(by['OTP_PROFILE_LINK']).toMatchObject({ requiredVars: ['ma_otp', 'email_tai_khoan'] });
    expect(by['BRANCH_MANAGER_LOCKED_APP']).toBeUndefined();
  });
});

describe('PUT /api/notification-templates/{code} và restore-default — validate như BE NotificationTemplateAdminService (BR-QT-14)', () => {
  const rule = 'BUSINESS_RULE_VIOLATION';
  const otp = '/api/notification-templates/OTP_REGISTER';
  const current = async (code: string) => (await getTemplates()).find((t) => t.code === code) as { subject: string; body: string; isDefault: boolean };

  it('biến lạ ở tiêu đề / nội dung → 400 BR-QT-14 nêu đủ biến lạ rồi danh sách biến dùng được', async () => {
    const res = await failure(putTemplate('OTP_REGISTER', { subject: 'Tiêu đề {abc}', body: 'Mã {ma_otp} {bien_1}' }));
    expect([res.status, res.data.errorCode, res.data.message]).toEqual([
      400,
      rule,
      'Tiêu đề dùng biến không có: {abc}; Nội dung dùng biến không có: {bien_1}; Biến dùng được: {ten_khach}, {ma_otp}, {thoi_han_phut} (BR-QT-14)',
    ]);
  });

  it('biến camelCase của bản mock cũ ({fullName}) là biến lạ; biến phân biệt hoa thường', async () => {
    const old = await failure(putTemplate('STAFF_TEMP_PASSWORD', { subject: 'T', body: '{fullName} {tempPassword}' }));
    expect(old.data.message).toBe(
      'Nội dung dùng biến không có: {fullName}, {tempPassword}; Biến dùng được: {ho_ten}, {email_dang_nhap}, {mat_khau_tam}; Mẫu thiếu biến bắt buộc: {ho_ten}, {email_dang_nhap}, {mat_khau_tam} (BR-QT-14)',
    );
    expect((await failure(putTemplate('OTP_REGISTER', { subject: 'T', body: '{MA_OTP}' }))).data.message).toContain('Nội dung dùng biến không có: {MA_OTP}');
  });

  it('thiếu biến bắt buộc (tính trên tiêu đề ∪ nội dung) → 400; biến bắt buộc nằm ở tiêu đề là đủ', async () => {
    const missing = await failure(putTemplate('OTP_REGISTER', { subject: 'Tiêu đề', body: 'Không có biến nào' }));
    expect([missing.status, missing.data.message]).toEqual([400, 'Mẫu thiếu biến bắt buộc: {ma_otp} (BR-QT-14)']);
    const inSubject = await putTemplate('OTP_REGISTER', { subject: 'Mã {ma_otp}', body: 'Không có biến nào' });
    expect(inSubject.status).toBe(200);
  });

  it('dấu { hoặc } lẻ → 400 BR-QT-14 (ngữ pháp chặt: ngoặc nhọn chỉ để viết biến)', async () => {
    for (const body of ['{ma_otp} {', '{ma_otp} }', '{ma_otp} {abc{ma_otp}']) {
      const res = await failure(putTemplate('OTP_REGISTER', { subject: 'T', body }));
      expect(res.data.message, body).toContain('Nội dung có dấu { hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien}');
    }
    const subject = await failure(putTemplate('OTP_REGISTER', { subject: 'T }', body: '{ma_otp}' }));
    expect(subject.data.message).toBe('Tiêu đề có dấu { hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien} (BR-QT-14)');
  });

  it('hình thức: tiêu đề / nội dung rỗng, tiêu đề quá 255 → 400 VALIDATION_FAILED đúng câu BE; mã lạ 404', async () => {
    const check = async (body: object, message: string) => {
      const res = await failure(client.put(otp, body, await admin()));
      expect([res.status, res.data.errorCode, res.data.message], JSON.stringify(body)).toEqual([400, 'VALIDATION_FAILED', message]);
    };
    await check({ body: '{ma_otp}' }, 'subject: Vui lòng nhập tiêu đề');
    await check({ subject: '   ', body: '{ma_otp}' }, 'subject: Vui lòng nhập tiêu đề');
    await check({ subject: 'x'.repeat(256), body: '{ma_otp}' }, 'subject: Tiêu đề tối đa 255 ký tự');
    await check({ subject: 'T' }, 'body: Vui lòng nhập nội dung');
    await check({ subject: 'T', body: ' \n ' }, 'body: Vui lòng nhập nội dung');
    const unknown = await failure(putTemplate('BRANCH_MANAGER_LOCKED_APP', { subject: 'T', body: 'B' }));
    expect([unknown.status, unknown.data.errorCode, unknown.data.message]).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy mẫu thông báo #BRANCH_MANAGER_LOCKED_APP']);
  });

  it('sửa hợp lệ → 200 lưu nguyên văn, isDefault=false, audit {code, subject, body} trước / sau; khôi phục → về đúng bản V4 và isDefault=true', async () => {
    const before = await current('OTP_REGISTER');
    expect(before.isDefault).toBe(true);
    const edited = { subject: ' Mã {ma_otp} ', body: 'Mã của bạn: {ma_otp}\r\n({thoi_han_phut} phút)  ' };
    const res = await putTemplate('OTP_REGISTER', edited);
    expect([res.status, res.data.message]).toEqual([200, 'Đã cập nhật mẫu thông báo']);
    expect(res.data.data).toMatchObject({ code: 'OTP_REGISTER', channel: 'EMAIL', ...edited, isDefault: false });
    expect(await current('OTP_REGISTER')).toMatchObject({ ...edited, isDefault: false });

    const audit = (await client.get('/api/audit-logs', { params: { actorAccountId: 1 }, ...(await admin()) })).data.data.content;
    expect(audit[0]).toMatchObject({
      action: 'NOTIFICATION_TEMPLATE_UPDATED',
      entityType: 'notification_templates',
      beforeData: { code: 'OTP_REGISTER', subject: before.subject, body: before.body },
      afterData: { code: 'OTP_REGISTER', ...edited },
    });

    const restored = await client.post(`${otp}/restore-default`, {}, await admin());
    expect([restored.status, restored.data.message]).toEqual([200, 'Đã khôi phục mẫu mặc định']);
    expect(restored.data.data).toEqual((await getTemplates()).find((t) => t.code === 'OTP_REGISTER'));
    expect(await current('OTP_REGISTER')).toEqual(before);
  });

  it.skipIf(!hasMigrations)('khôi phục về default_body của SQL (không chỉ về bản mock): đọc V3/V4 rồi so từng ký tự', async () => {
    await putTemplate('OTP_REGISTER', { subject: 'Mã {ma_otp}', body: 'Chỉ {ma_otp}' });
    const restored = (await client.post(`${otp}/restore-default`, {}, await admin())).data.data;
    const row = sqlSeed().templates.get('OTP_REGISTER')!;
    expect([restored.subject, restored.body, restored.isDefault]).toEqual([row.default_subject, row.default_body, true]);
  });

  it('sửa thành đúng bản mặc định → isDefault=true trở lại; khôi phục bản đã mặc định → 200 và vẫn audit (BR-QT-15 "mọi thao tác")', async () => {
    const original = await current('OTP_REGISTER');
    await putTemplate('OTP_REGISTER', { subject: 'Mã {ma_otp}', body: 'Chỉ {ma_otp}' });
    expect((await current('OTP_REGISTER')).isDefault).toBe(false);
    const back = await putTemplate('OTP_REGISTER', { subject: original.subject, body: original.body });
    expect(back.data.data.isDefault).toBe(true);
    expect((await current('OTP_REGISTER')).isDefault).toBe(true);

    const before = (await auditActions()).length;
    const again = await client.post(`${otp}/restore-default`, {}, await admin());
    expect([again.status, again.data.data.isDefault]).toEqual([200, true]);
    const same = await putTemplate('OTP_REGISTER', { subject: original.subject, body: original.body });
    expect(same.status).toBe(200);
    const actions = await auditActions();
    expect(actions.slice(0, actions.length - before)).toEqual(['NOTIFICATION_TEMPLATE_UPDATED', 'NOTIFICATION_TEMPLATE_RESTORED']);
  });

  it('khôi phục mã lạ → 404; vai khác ADMIN → 403', async () => {
    expect((await failure(client.post('/api/notification-templates/NOPE/restore-default', {}, await admin()))).status).toBe(404);
    expect((await failure(client.post(`${otp}/restore-default`, {}, await as('chuoi@petcare.vn')))).status).toBe(403);
    expect((await failure(client.put(otp, { subject: 'T', body: '{ma_otp}' }, await as('chuoi@petcare.vn')))).status).toBe(403);
  });
});
