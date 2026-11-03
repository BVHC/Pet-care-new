// Tách dạng RESPONSE khỏi ngữ cảnh REQUEST nội bộ của mock (T49). BE `AccountSummary` chỉ có 6 trường, KHÔNG có `branchId`
// (nó chỉ ở `GET /me` → staffProfile.branchId); mock từng trả thêm `branchId` ở login / me.account nên che lỗi FE đọc account.branchId.
// Ngược lại các mock khác (inventory, care, sales, cashier…) vẫn đọc `req.account.branchId` làm ngữ cảnh request: phần đó phải giữ nguyên.
import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import { customerOfAccount, findCustomer, markHasData, updateCustomerFields } from './customer.mock';
import { MOCK_PASSWORD, accountForToken } from './identity.mock';
import { installMockApi } from './mock-api';

let client: AxiosInstance;

beforeEach(() => {
  localStorage.clear();
  client = axios.create();
  installMockApi(client);
});

const ACCOUNT_KEYS = ['email', 'id', 'isLocked', 'mustChangePassword', 'role', 'status'];
const EMAILS = [
  'admin@petcare.vn',
  'chuoi@petcare.vn',
  'manager@store1.vn',
  'manager2@store2.vn',
  'reception@store1.vn',
  'doctor@store1.vn',
  'caretaker@store1.vn',
  'khach@petcare.vn',
];

const login = (email: string) => client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
async function as(email: string) {
  const res = await login(email);
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
/** `toEqual` bỏ qua khóa có giá trị undefined, nên so danh sách khóa. */
const keysOf = (value: object) => Object.keys(value).sort();

describe('response khớp BE: account chỉ có đúng 6 trường, không có branchId', () => {
  it.each(EMAILS)('POST /api/auth/login (%s)', async (email) => {
    const res = await login(email);
    expect(keysOf(res.data.data.account)).toEqual(ACCOUNT_KEYS);
  });

  it.each(EMAILS)('GET /api/me (%s)', async (email) => {
    const res = await client.get('/api/me', await as(email));
    expect(keysOf(res.data.data.account)).toEqual(ACCOUNT_KEYS);
  });

  it('quản lý chi nhánh 1: giá trị 6 trường ở cả login lẫn /me', async () => {
    const expected = { id: 3, email: 'manager@store1.vn', role: 'BRANCH_MANAGER', status: 'ACTIVE', isLocked: false, mustChangePassword: false };
    expect((await login('manager@store1.vn')).data.data.account).toEqual(expected);
    expect((await client.get('/api/me', await as('manager@store1.vn'))).data.data.account).toEqual(expected);
  });
});

describe('GET /api/me vẫn trả chi nhánh ở staffProfile.branchId', () => {
  it.each([
    ['manager@store1.vn', 1],
    ['manager2@store2.vn', 2],
    ['reception@store1.vn', 1],
    ['admin@petcare.vn', null],
    ['chuoi@petcare.vn', null],
  ])('%s → %s', async (email, branchId) => {
    const res = await client.get('/api/me', await as(email));
    expect(res.data.data.staffProfile.branchId).toBe(branchId);
  });

  it('khách không có hồ sơ nhân viên', async () => {
    const res = await client.get('/api/me', await as('khach@petcare.vn'));
    expect(res.data.data.staffProfile).toBeNull();
  });
});

describe('ngữ cảnh request nội bộ (req.account) vẫn mang branchId cho các mock khác', () => {
  it('accountForToken: nhân viên có chi nhánh thì mang branchId, ADMIN / SUPER_MANAGER / khách thì không', async () => {
    const tokenOf = async (email: string) => (await login(email)).data.data.accessToken as string;
    expect(accountForToken(await tokenOf('manager@store1.vn'))).toMatchObject({ id: 3, role: 'BRANCH_MANAGER', branchId: 1 });
    expect(accountForToken(await tokenOf('manager2@store2.vn'))).toMatchObject({ id: 9, branchId: 2 });
    for (const email of ['admin@petcare.vn', 'chuoi@petcare.vn', 'khach@petcare.vn']) {
      expect(accountForToken(await tokenOf(email))?.branchId, email).toBeUndefined();
    }
  });

  // Ba ca dưới đây dùng route đọc req.account.branchId KHÔNG có dự phòng branchIdOf (inventory.mock.ts:52, care.mock.ts:452, sales.mock.ts:341/374/405):
  // mất branchId thì danh sách rỗng, nên là khẳng định dương.
  it('kho (inventory.mock): quản lý chi nhánh 2 chỉ thấy tồn của chi nhánh 2, chi nhánh 1 thấy nhiều mặt hàng hơn', async () => {
    const other = await client.get('/api/inventory', await as('manager2@store2.vn'));
    expect(other.data.data.content).toMatchObject([{ productId: 1, totalQuantity: 7 }]);
    const own = await client.get('/api/inventory', await as('manager@store1.vn'));
    expect(own.data.data.content.length).toBeGreaterThan(1);
  });

  it('bán hàng (sales.mock): quản lý chi nhánh 2 thấy Order #8 của mình, chi nhánh 1 thì không', async () => {
    const ids = async (email: string) =>
      ((await client.get('/api/orders', await as(email))).data.data.content as { orderId: number }[]).map((o) => o.orderId);
    expect(await ids('manager2@store2.vn')).toEqual([8]);
    expect(await ids('manager@store1.vn')).not.toContain(8);
    expect((await client.get('/api/orders/8', await as('manager2@store2.vn'))).data.data.orderId).toBe(8);
  });

  it('việc gọi điện (care.mock): lễ tân chi nhánh 1 thấy việc đang chờ của chi nhánh 1', async () => {
    const res = await client.get('/api/care-tasks', { params: { status: 'OPEN' }, ...(await as('reception@store1.vn')) });
    expect(res.data.data.content.length).toBeGreaterThan(0);
  });
});

// Response gửi mã OTP mang độ dài mã để FE dựng ô nhập đúng cỡ khi BE `otp.code_length` khác 6:
// `codeLength` ở OtpSentResponse (quên mật khẩu, gửi lại mã đăng ký, liên kết hồ sơ, UC22 #25), `otpCodeLength` ở RegistrationResponse.
// Mock giữ mặc định 6 (MOCK_OTP_CODE). `toEqual` bỏ qua khóa undefined nên so danh sách khóa.
describe('response gửi mã OTP mang độ dài mã (mock: 6)', () => {
  const NEW = { email: 'moi@petcare.vn', password: 'MatKhau1', fullName: 'Khách Mới', isAdult: true, termsAccepted: true };
  const CHANGE = { newEmail: 'trang.moi@gmail.com', verificationMethod: 'ID_CARD_IN_PERSON' };
  const SENT_KEYS = ['codeLength', 'maskedEmail', 'resendAvailableAt'];

  afterEach(() => vi.useRealTimers());

  it('đăng ký (#1): otpCodeLength cạnh otpResendAvailableAt', async () => {
    const res = await client.post('/api/auth/register', NEW);
    expect(keysOf(res.data.data)).toEqual(['accountId', 'email', 'otpCodeLength', 'otpResendAvailableAt', 'status']);
    expect(res.data.data.otpCodeLength).toBe(6);
  });

  it('gửi lại mã đăng ký: codeLength', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-10-09T10:00:00+07:00'));
    await client.post('/api/auth/register', NEW);
    vi.setSystemTime(Date.parse('2026-10-09T10:01:00+07:00'));
    const res = await client.post('/api/auth/register/resend-otp', { email: NEW.email });
    expect(keysOf(res.data.data)).toEqual(SENT_KEYS);
    expect(res.data.data.codeLength).toBe(6);
  });

  it('quên mật khẩu: codeLength, cùng một nội dung cho email có và không có tài khoản (BR-TK-10)', async () => {
    const known = await client.post('/api/auth/password/forgot', { email: 'doctor@store1.vn' });
    const unknown = await client.post('/api/auth/password/forgot', { email: 'ai@do.vn' });
    for (const res of [known, unknown]) {
      expect(keysOf(res.data.data)).toEqual(SENT_KEYS);
      expect(res.data.data.codeLength).toBe(6);
      expect(res.data.data.maskedEmail).toBeNull();
    }
  });

  it('liên kết hồ sơ (POST /api/me/link/otp): codeLength', async () => {
    const res = await client.post('/api/me/link/otp', { customerId: 3 }, await as('khachmoi@petcare.vn'));
    expect(keysOf(res.data.data)).toEqual(SENT_KEYS);
    expect(res.data.data.codeLength).toBe(6);
  });

  it('sửa email tài khoản khách tại quầy (UC22 #25): codeLength', async () => {
    const res = await client.post('/api/customers/1/account-email-change', CHANGE, await as('reception@store1.vn'));
    expect(keysOf(res.data.data)).toEqual(SENT_KEYS);
    expect(res.data.data.codeLength).toBe(6);
  });
});

// UC22 #25–26 theo BE: CustomerAccountController, CustomerAccountService, CustomerEmailChangeService, StaffEmailChangeService (MSG_*), CustomerEmailChangeRequest,
// GlobalExceptionHandler; các ca khớp CustomerAccountEmailChangeIT. Câu báo lỗi chép nguyên văn. Mock không tách được hồ sơ trỏ vào tài khoản nhân viên (BE: 404).
describe('UC22 #25–26 — lễ tân sửa email tài khoản khách, khớp BE', () => {
  const NEW = { email: 'moi@petcare.vn', password: 'MatKhau1', fullName: 'Khách Mới', isAdult: true, termsAccepted: true };
  const METHOD = { verificationMethod: 'ID_CARD_IN_PERSON' } as const;
  const NOT_LINKED = 'Hồ sơ chưa gắn tài khoản online nên không có email tài khoản để sửa';
  const PENDING = 'Tài khoản chưa xác thực email nên chưa sửa được email';
  const MALFORMED = 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu';
  const RECEPTION = 'reception@store1.vn';
  const VET = 'doctor@store1.vn';

  /** Mock ném lỗi như axios khi status >= 400: lấy lại phản hồi để so status, errorCode và message. */
  async function reply(request: Promise<AxiosResponse>): Promise<AxiosResponse> {
    try {
      return await request;
    } catch (e) {
      if (axios.isAxiosError(e) && e.response) return e.response;
      throw e;
    }
  }
  const path = (customerId: number) => `/api/customers/${customerId}/account-email-change`;
  const start = async (customerId: number, body: object, who = RECEPTION) => reply(client.post(path(customerId), body, await as(who)));
  const confirm = async (customerId: number, code: string, who = RECEPTION) => reply(client.post(`${path(customerId)}/confirm`, { code }, await as(who)));
  const errorOf = (res: AxiosResponse) => [res.status, res.data.errorCode, res.data.message];
  const RULE = 'BUSINESS_RULE_VIOLATION';

  /** Đăng ký tài khoản khách mới (id 100, PENDING) và trả mã hồ sơ online của nó. */
  async function registerPending() {
    await client.post('/api/auth/register', NEW);
    return customerOfAccount(100)!.customerId;
  }
  const verifyRegistered = () => client.post('/api/auth/register/verify', { email: NEW.email, code: '123456' });

  afterEach(() => vi.useRealTimers());

  it('#25 thành công: message của BE, data đúng 3 khóa, maskedEmail luôn null (BE không che email mới), codeLength 6', async () => {
    const res = await start(1, { newEmail: 'Trang.Moi@Gmail.com', ...METHOD });
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã gửi mã xác thực tới email mới');
    expect(keysOf(res.data.data)).toEqual(['codeLength', 'maskedEmail', 'resendAvailableAt']);
    expect(res.data.data).toMatchObject({ maskedEmail: null, codeLength: 6 });
    expect(Number.isNaN(Date.parse(res.data.data.resendAvailableAt))).toBe(false);
  });

  it('hồ sơ chưa gắn tài khoản (#3 tại quầy): 400 VALIDATION_FAILED đúng câu BE, KHÔNG đuôi (BR-…), ở cả hai endpoint', async () => {
    expect(errorOf(await start(3, { newEmail: 'a@b.vn', ...METHOD }))).toEqual([400, 'VALIDATION_FAILED', NOT_LINKED]);
    expect(errorOf(await confirm(3, '123456'))).toEqual([400, 'VALIDATION_FAILED', NOT_LINKED]);
  });

  it('tài khoản PENDING: 400 VALIDATION_FAILED đúng câu BE, KHÔNG đuôi (BR-…), ở cả hai endpoint; chưa có mã nào được gửi', async () => {
    const customerId = await registerPending();
    expect(errorOf(await start(customerId, { newEmail: 'a@b.vn', ...METHOD }))).toEqual([400, 'VALIDATION_FAILED', PENDING]);
    expect(errorOf(await confirm(customerId, '123456'))).toEqual([400, 'VALIDATION_FAILED', PENDING]);

    // Chưa có mã nào được sinh: xác thực email xong thì xác nhận #26 là "không còn mã đang mở" (BR-TK-05).
    await verifyRegistered();
    expect(errorOf(await confirm(customerId, '123456'))).toEqual([400, RULE, 'OTP không đúng hoặc đã hết hạn (BR-TK-05)']);
  });

  it('tài khoản đang bị khóa vẫn sửa được (khôi phục tài khoản, BR-TK-16)', async () => {
    const customerId = await registerPending();
    await verifyRegistered();
    await client.post('/api/accounts/100/lock', { reason: 'Khách báo mất email' }, await as('admin@petcare.vn'));

    expect((await start(customerId, { newEmail: 'khoi.phuc@gmail.com', ...METHOD })).status).toBe(200);
    expect((await confirm(customerId, '123456')).status).toBe(204);
  });

  it('Bean Validation chép từ DTO của BE, chạy trước kiểm vai (token bác sĩ + body sai vẫn là 400)', async () => {
    const tooLong = `${'a'.repeat(64)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(60)}.vn`;
    const empty = await start(1, {}, VET);
    expect([empty.status, empty.data.errorCode]).toEqual([400, 'VALIDATION_FAILED']);
    expect(empty.data.message).toContain('newEmail: Email mới không được để trống');
    expect(empty.data.message).toContain('verificationMethod: Phương thức xác minh không được để trống');

    const cases: [label: string, body: object, message: string][] = [
      ['email rỗng', { newEmail: '', ...METHOD }, 'newEmail: Email mới không được để trống'],
      ['email sai định dạng', { newEmail: 'khong-hop-le', ...METHOD }, 'newEmail: Email mới không đúng định dạng'],
      ['email còn khoảng trắng hai đầu (BE không cắt trước @Email)', { newEmail: ' a@b.vn ', ...METHOD }, 'newEmail: Email mới không đúng định dạng'],
      ['email dài 256 ký tự', { newEmail: tooLong, ...METHOD }, 'newEmail: Email mới tối đa 255 ký tự'],
      ['thiếu verificationMethod', { newEmail: 'a@b.vn' }, 'verificationMethod: Phương thức xác minh không được để trống'],
      ['verificationMethod null', { newEmail: 'a@b.vn', verificationMethod: null }, 'verificationMethod: Phương thức xác minh không được để trống'],
    ];
    for (const [label, body, message] of cases) {
      const res = await start(1, body, VET);
      expect([label, res.status, res.data.errorCode]).toEqual([label, 400, 'VALIDATION_FAILED']);
      expect(res.data.message, label).toContain(message);
    }
    expect((await confirm(1, '12a456', VET)).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('verificationMethod ngoài ID_CARD_IN_PERSON là MALFORMED_REQUEST (lỗi đọc JSON, đứng trước cả validation lẫn kiểm vai)', async () => {
    expect(errorOf(await start(1, { newEmail: 'a@b.vn', verificationMethod: 'PASSPORT' }))).toEqual([400, 'MALFORMED_REQUEST', MALFORMED]);
    expect(errorOf(await start(1, { newEmail: '', verificationMethod: 'PASSPORT' }, VET))).toEqual([400, 'MALFORMED_REQUEST', MALFORMED]);
  });

  it('thứ tự sau validation: vai (403) rồi hồ sơ không có (404 "Không tìm thấy hồ sơ khách #id") ở cả hai endpoint', async () => {
    expect((await start(999, { newEmail: 'a@b.vn', ...METHOD }, VET)).status).toBe(403);
    expect((await confirm(999, '123456', VET)).status).toBe(403);
    expect(errorOf(await start(999, { newEmail: 'a@b.vn', ...METHOD }))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy hồ sơ khách #999']);
    expect(errorOf(await confirm(999, '123456'))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy hồ sơ khách #999']);
  });

  it('BR-TK-01: trùng email hiện tại (không phân biệt hoa thường) và email của tài khoản khác (nhân viên, tài khoản PENDING)', async () => {
    await registerPending();
    expect(errorOf(await start(1, { newEmail: 'KHACH@petcare.vn', ...METHOD }))).toEqual([400, RULE, 'Email mới trùng email hiện tại (BR-TK-01)']);
    for (const taken of [RECEPTION, NEW.email]) {
      expect(errorOf(await start(1, { newEmail: taken, ...METHOD }))).toEqual([400, RULE, 'Email đã được sử dụng (BR-TK-01)']);
    }
  });

  it('BR-TK-07 tính theo email nhận mã: đổi sang email khác gửi được ngay, gửi lại tới cùng email thì phải chờ', async () => {
    expect((await start(1, { newEmail: 'ban.dau@gmail.com', ...METHOD })).status).toBe(200);
    expect((await start(1, { newEmail: 'sua.lai@gmail.com', ...METHOD })).status).toBe(200);
    const again = await start(1, { newEmail: 'sua.lai@gmail.com', ...METHOD });
    expect([again.status, again.data.errorCode, ruleIdOf(again.data.message)]).toEqual([400, RULE, 'BR-TK-07']);
  });

  it('#26: 204, đổi email TÀI KHOẢN (đăng nhập, /me), email cũ như email lạ, mật khẩu giữ nguyên, không hủy phiên của khách', async () => {
    const customer = await as('khach@petcare.vn');
    await start(1, { newEmail: 'Trang.Moi@Gmail.com', ...METHOD });
    const done = await confirm(1, '123456');
    expect([done.status, done.data]).toEqual([204, '']);

    expect((await client.get('/api/me', customer)).data.data.account.email).toBe('trang.moi@gmail.com');
    expect((await login('trang.moi@gmail.com')).status).toBe(200);
    expect((await reply(login('khach@petcare.vn'))).status).toBe(401);
  });

  it('#26: không có mã là BR-TK-05; sai mã vẫn tính, sai lần 5 là BR-TK-06 và hủy mã; email bị lấy sau khi gửi mã là BR-TK-01 và mã còn dùng lại được', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.parse('2026-11-02T10:00:00+07:00'));
    const invalidOtp = [400, RULE, 'OTP không đúng hoặc đã hết hạn (BR-TK-05)'];
    expect(errorOf(await confirm(1, '123456'))).toEqual(invalidOtp);

    await start(1, { newEmail: 'moi@petcare.vn', ...METHOD });
    for (let attempt = 1; attempt <= 4; attempt++) expect(errorOf(await confirm(1, '000000'))).toEqual(invalidOtp);
    expect(ruleIdOf((await confirm(1, '000000')).data.message)).toBe('BR-TK-06');
    expect(errorOf(await confirm(1, '123456'))).toEqual(invalidOtp);

    vi.setSystemTime(Date.parse('2026-11-02T10:01:01+07:00'));
    await start(1, { newEmail: 'moi@petcare.vn', ...METHOD });
    await client.post('/api/auth/register', NEW);
    for (let tries = 0; tries < 2; tries++) {
      expect(errorOf(await confirm(1, '123456'))).toEqual([400, RULE, 'Email đã được sử dụng (BR-TK-01)']);
    }
  });
});

// UC22 #27 theo BE: CustomerAccountController.linkAccount, CounterLinkService, CounterLinkStepService (MSG_*), LinkAccountNotFoundException, CounterLinkRequest, LoginAttemptService.MSG_LOCKED,
// GlobalExceptionHandler; các ca khớp CustomerAccountLinkIT. Câu báo lỗi chép nguyên văn. Dữ liệu mẫu: #1 online của khach@ (có dữ liệu), #2 online của khachmoi@ (chưa có dữ liệu),
// #3 tại quầy có email, #4 tại quầy KHÔNG email. Mock không gồm: hồ sơ trỏ nhầm vào nhân viên, tài khoản khách không có hồ sơ (BE: 404), đồng thời (BR-TK-19 "vừa được liên kết"), audit.
describe('UC22 #27 — lễ tân liên kết hộ tài khoản online với hồ sơ tại quầy, khớp BE', () => {
  const METHOD = { verificationMethod: 'ID_CARD_IN_PERSON' } as const;
  const RECEPTION = 'reception@store1.vn';
  const RULE = 'BUSINESS_RULE_VIOLATION';
  const NEW = { email: 'moi@petcare.vn', password: 'MatKhau1', fullName: 'Khách Mới', isAdult: true, termsAccepted: true };
  const MALFORMED = 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu';
  const ACCOUNT_NOT_FOUND = 'Không tìm thấy tài khoản khách hàng đã kích hoạt với email này';
  const PROFILE_HAS_ACCOUNT = 'Hồ sơ này đã gắn tài khoản (hồ sơ online hoặc đã liên kết) (BR-TK-19)';
  const PROFILE_NO_EMAIL = 'Hồ sơ tại quầy chưa có email. Hãy bổ sung email vào hồ sơ (sửa hồ sơ khách) rồi liên kết lại (BR-TK-19)';
  const ACCOUNT_LINKED = 'Tài khoản đã được liên kết với một hồ sơ tại quầy khác (BR-TK-19)';
  const ONLINE_HAS_DATA = 'Hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không liên kết được (BR-TK-19)';
  const ACCOUNT_LOCKED = 'Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ (BR-TK-11)';

  /** Mock ném lỗi như axios khi status >= 400: lấy lại phản hồi để so status, errorCode và message. */
  async function reply(request: Promise<AxiosResponse>): Promise<AxiosResponse> {
    try {
      return await request;
    } catch (e) {
      if (axios.isAxiosError(e) && e.response) return e.response;
      throw e;
    }
  }
  const link = async (customerId: number, accountEmail: unknown, who = RECEPTION, extra: object = METHOD) =>
    reply(client.post(`/api/customers/${customerId}/link-account`, { accountEmail, ...extra }, await as(who)));
  const errorOf = (res: AxiosResponse) => [res.status, res.data.errorCode, res.data.message];
  const rule = (message: string) => [400, RULE, message];
  const notFoundAccount = [404, 'RESOURCE_NOT_FOUND', ACCOUNT_NOT_FOUND];
  const patchEmail = async (customerId: number, email: string) =>
    reply(client.patch(`/api/customers/${customerId}`, { email }, await as(RECEPTION)));
  /** Đăng ký tài khoản khách mới (id 100, PENDING) rồi xác thực email cho ACTIVE. */
  async function registerActive() {
    await client.post('/api/auth/register', NEW);
    await client.post('/api/auth/register/verify', { email: NEW.email, code: '123456' });
  }

  it('thành công: message và data của BE, email viết hoa vẫn tìm được, hồ sơ online + sổ địa chỉ bị xóa, hồ sơ quầy gắn tài khoản, SĐT giữ nguyên, không đụng phiên', async () => {
    const khachmoi = await as('khachmoi@petcare.vn');
    const res = await link(3, 'KHACHMOI@PETCARE.VN');
    expect([res.status, res.data.message]).toEqual([200, 'Đã liên kết hồ sơ với tài khoản']);
    expect(res.data.data).toEqual({ customerId: 3 });

    expect(findCustomer(2), 'hồ sơ online bị xóa').toBeUndefined();
    expect(findCustomer(3)).toMatchObject({ accountId: 10, email: 'khachmoi@petcare.vn', phone: '0905111222', createdChannel: 'COUNTER', linkDecisionPending: false });
    expect((await client.get('/api/me/addresses', khachmoi)).data.data, 'sổ địa chỉ online xóa cùng hồ sơ').toEqual([]);
    // Token cấp trước khi liên kết vẫn dùng được (không hủy phiên) và GET /me thấy hồ sơ quầy.
    expect((await client.get('/api/me', khachmoi)).data.data).toMatchObject({ customerId: 3, linkDecisionPending: false });
  });

  it('hồ sơ chưa có email: 400 BR-TK-19 với hướng dẫn nguyên văn (kể cả khi email tài khoản lạ), không đổi gì; bổ sung email qua PATCH rồi liên kết thì thành công', async () => {
    expect(errorOf(await link(4, 'khachmoi@petcare.vn'))).toEqual(rule(PROFILE_NO_EMAIL));
    expect(errorOf(await link(4, 'chua-co@gmail.com'))).toEqual(rule(PROFILE_NO_EMAIL));
    expect(findCustomer(4)).toMatchObject({ accountId: null, email: null });
    expect(findCustomer(2), 'hồ sơ online còn').toBeDefined();

    expect((await patchEmail(4, 'tam@petcare.vn')).status).toBe(200);
    expect((await link(4, 'khachmoi@petcare.vn')).data.data).toEqual({ customerId: 4 });
    expect(findCustomer(4)).toMatchObject({ accountId: 10 });
  });

  it('email hồ sơ toàn dấu cách cũng coi như chưa có email (PATCH đã đổi dấu cách thành null nên đặt thẳng vào dữ liệu hồ sơ)', async () => {
    updateCustomerFields(4, { email: '   ' });
    expect(findCustomer(4)!.email).toBe('   ');

    expect(errorOf(await link(4, 'khachmoi@petcare.vn'))).toEqual(rule(PROFILE_NO_EMAIL));
    expect(findCustomer(4)!.accountId).toBeNull();
  });

  it('email tạm của hồ sơ KHÁC email tài khoản vẫn liên kết được và bị ghi đè bằng email tài khoản (đọc thẳng dữ liệu hồ sơ, không qua GET vì GET lấy email tài khoản)', async () => {
    await patchEmail(4, 'tam@petcare.vn');
    expect(findCustomer(4)!.email).toBe('tam@petcare.vn');

    expect((await link(4, 'khachmoi@petcare.vn')).status).toBe(200);
    expect(findCustomer(4)!.email).toBe('khachmoi@petcare.vn');
  });

  it('hồ sơ đã gắn tài khoản (hồ sơ online, hoặc hồ sơ quầy đã liên kết): MỘT câu BR-TK-19 chung, kiểm TRƯỚC khi tìm tài khoản (email lạ vẫn 400, không phải 404)', async () => {
    for (const profile of [1, 2]) {
      expect(errorOf(await link(profile, 'khach@petcare.vn'))).toEqual(rule(PROFILE_HAS_ACCOUNT));
      expect(errorOf(await link(profile, 'chua-co@gmail.com'))).toEqual(rule(PROFILE_HAS_ACCOUNT));
    }

    expect((await link(3, 'khachmoi@petcare.vn')).status).toBe(200);
    expect(errorOf(await link(3, 'khachmoi@petcare.vn'))).toEqual(rule(PROFILE_HAS_ACCOUNT));
    expect(errorOf(await link(3, 'khach@petcare.vn'))).toEqual(rule(PROFILE_HAS_ACCOUNT));
    expect(errorOf(await link(3, 'chua-co@gmail.com'))).toEqual(rule(PROFILE_HAS_ACCOUNT));
    expect(findCustomer(1)!.accountId).toBe(7);
  });

  it('404 MỘT câu cố định cho email lạ, tài khoản nhân viên và tài khoản PENDING; không nhắc lại email đã gõ; hồ sơ quầy vẫn chưa gắn', async () => {
    await client.post('/api/auth/register', NEW);
    for (const typed of ['chua-co@gmail.com', 'doctor@store1.vn', 'admin@petcare.vn', NEW.email]) {
      const res = await link(3, typed);
      expect(errorOf(res), typed).toEqual(notFoundAccount);
      expect(res.data.message).not.toContain(typed);
    }
    expect(findCustomer(3)).toMatchObject({ accountId: null, email: 'long.lh@gmail.com' });

    // Xác thực email xong thì cùng email ấy liên kết được.
    await client.post('/api/auth/register/verify', { email: NEW.email, code: '123456' });
    expect((await link(3, NEW.email)).status).toBe(200);
  });

  it('tài khoản đang bị khóa: 400 BR-TK-11 đúng câu BE, kiểm trước dữ liệu hồ sơ online (khóa + có dữ liệu vẫn là BR-TK-11); mở khóa thì quay lại kiểm dữ liệu hồ sơ online', async () => {
    await registerActive();
    const admin = await as('admin@petcare.vn');
    await client.post('/api/accounts/100/lock', { reason: 'Khách báo mất email' }, admin);

    expect(errorOf(await link(3, NEW.email))).toEqual(rule(ACCOUNT_LOCKED));
    markHasData(customerOfAccount(100)!.customerId);
    expect(errorOf(await link(3, NEW.email))).toEqual(rule(ACCOUNT_LOCKED));
    expect(findCustomer(3)!.accountId).toBeNull();
    expect(customerOfAccount(100), 'hồ sơ online của tài khoản bị khóa còn').toBeDefined();

    await client.post('/api/accounts/100/unlock', { reason: 'Đã xác minh' }, admin);
    expect(errorOf(await link(3, NEW.email))).toEqual(rule(ONLINE_HAS_DATA));
  });

  it('hồ sơ online của tài khoản đã có dữ liệu (BR-TK-19) và tài khoản đã liên kết một hồ sơ quầy khác (BR-TK-19): đúng câu BE, không xóa gì', async () => {
    expect(errorOf(await link(3, 'khach@petcare.vn'))).toEqual(rule(ONLINE_HAS_DATA));
    expect(findCustomer(1), 'hồ sơ online có dữ liệu còn').toBeDefined();

    expect((await link(3, 'khachmoi@petcare.vn')).status).toBe(200);
    const created = await client.post('/api/customers', { fullName: 'Trần Văn Nam', phone: '0905111222', email: 'nam@petcare.vn' }, await as(RECEPTION));
    const other = created.data.data.customer.customerId as number;
    expect(errorOf(await link(other, 'khachmoi@petcare.vn'))).toEqual(rule(ACCOUNT_LINKED));
    expect(findCustomer(other)!.accountId).toBeNull();
  });

  it('Bean Validation chép từ DTO của BE, chạy trước kiểm vai và trước tra hồ sơ (token bác sĩ + hồ sơ không có + body sai vẫn là 400)', async () => {
    const tooLong = `${'a'.repeat(64)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(60)}.vn`;
    const empty = await link(987654321, undefined, 'doctor@store1.vn', {});
    expect([empty.status, empty.data.errorCode]).toEqual([400, 'VALIDATION_FAILED']);
    expect(empty.data.message).toContain('accountEmail: Email tài khoản không được để trống');
    expect(empty.data.message).toContain('verificationMethod: Phương thức xác minh không được để trống');

    const cases: [label: string, accountEmail: unknown, extra: object, message: string][] = [
      ['email rỗng', '', METHOD, 'accountEmail: Email tài khoản không được để trống'],
      ['email sai định dạng', 'khong-hop-le', METHOD, 'accountEmail: Email tài khoản không đúng định dạng'],
      ['email còn khoảng trắng hai đầu (BE không cắt trước @Email)', ' a@b.vn ', METHOD, 'accountEmail: Email tài khoản không đúng định dạng'],
      ['email dài 256 ký tự', tooLong, METHOD, 'accountEmail: Email tài khoản tối đa 255 ký tự'],
      ['thiếu verificationMethod', 'a@b.vn', {}, 'verificationMethod: Phương thức xác minh không được để trống'],
      ['verificationMethod null', 'a@b.vn', { verificationMethod: null }, 'verificationMethod: Phương thức xác minh không được để trống'],
    ];
    for (const [label, accountEmail, extra, message] of cases) {
      const res = await link(987654321, accountEmail, 'doctor@store1.vn', extra);
      expect([label, res.status, res.data.errorCode]).toEqual([label, 400, 'VALIDATION_FAILED']);
      expect(res.data.message, label).toContain(message);
    }
  });

  it('verificationMethod ngoài ID_CARD_IN_PERSON là MALFORMED_REQUEST (lỗi đọc JSON, đứng trước cả validation lẫn kiểm vai)', async () => {
    expect(errorOf(await link(3, 'khachmoi@petcare.vn', RECEPTION, { verificationMethod: 'PASSPORT' }))).toEqual([400, 'MALFORMED_REQUEST', MALFORMED]);
    expect(errorOf(await link(3, '', 'doctor@store1.vn', { verificationMethod: 'PASSPORT' }))).toEqual([400, 'MALFORMED_REQUEST', MALFORMED]);
    expect(findCustomer(3)!.accountId).toBeNull();
  });

  it('chỉ lễ tân (403) và 403 đứng trước 404 hồ sơ; hồ sơ không có là 404 "Không tìm thấy hồ sơ khách #id" kể cả khi email tài khoản lạ', async () => {
    for (const who of ['doctor@store1.vn', 'manager@store1.vn', 'admin@petcare.vn', 'khachmoi@petcare.vn']) {
      expect((await link(3, 'khachmoi@petcare.vn', who)).status, who).toBe(403);
      expect((await link(987654321, 'khachmoi@petcare.vn', who)).status, who).toBe(403);
    }
    expect(errorOf(await link(987654321, 'chua-co@gmail.com'))).toEqual([404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy hồ sơ khách #987654321']);
    expect(findCustomer(3)!.accountId).toBeNull();
  });
});
