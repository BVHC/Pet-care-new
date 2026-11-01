// Tách dạng RESPONSE khỏi ngữ cảnh REQUEST nội bộ của mock (T49). BE `AccountSummary` chỉ có 6 trường, KHÔNG có `branchId`
// (nó chỉ ở `GET /me` → staffProfile.branchId); mock từng trả thêm `branchId` ở login / me.account nên che lỗi FE đọc account.branchId.
// Ngược lại các mock khác (inventory, care, sales, cashier…) vẫn đọc `req.account.branchId` làm ngữ cảnh request: phần đó phải giữ nguyên.
import axios, { type AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
