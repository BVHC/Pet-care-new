// Mock module identity (docs/api/identity-v1.md): đăng ký + OTP, đăng nhập, đăng xuất,
// quên / đặt lại mật khẩu, GET /me, đổi mật khẩu. Câu báo lỗi chép từ BE.
import type { AccountSummary, LoginResponse, Role } from '../../types/auth';
import { passwordPolicyError } from '../../utils/password';
import { fail, invalid, isText, noContent, ok, ruleFail, type Check, type MockHandler, type MockReply } from './envelope';

/** Mật khẩu chung của mọi tài khoản demo (BR-TK-03: ≥ 8 ký tự, có chữ và số). */
export const MOCK_PASSWORD = 'Petcare123';
/** Mock không gửi email: mọi mã OTP (đăng ký, quên mật khẩu) đều là mã này. */
export const MOCK_OTP_CODE = '123456';

export interface MockAccount {
  id: number;
  email: string;
  role: Role;
  fullName: string;
  mustChangePassword?: boolean;
}

/** Id 3–6 trùng mã nhân viên trong clinic-db để "lượt của tôi" ở bàn làm việc khớp. */
export const MOCK_ACCOUNTS: readonly MockAccount[] = [
  { id: 1, email: 'admin@petcare.vn', role: 'ADMIN', fullName: 'Quản trị hệ thống' },
  { id: 2, email: 'chuoi@petcare.vn', role: 'SUPER_MANAGER', fullName: 'Đỗ Thanh Hà' },
  { id: 3, email: 'manager@store1.vn', role: 'BRANCH_MANAGER', fullName: 'Hoàng Nam' },
  { id: 4, email: 'reception@store1.vn', role: 'RECEPTIONIST', fullName: 'Lan Chi' },
  { id: 5, email: 'doctor@store1.vn', role: 'VET', fullName: 'BS. Minh Anh' },
  { id: 6, email: 'caretaker@store1.vn', role: 'CARETAKER', fullName: 'Thu Hà' },
  { id: 7, email: 'khach@petcare.vn', role: 'CUSTOMER', fullName: 'Nguyễn Thu Trang' },
  { id: 8, email: 'newstaff@store1.vn', role: 'RECEPTIONIST', fullName: 'Lễ tân mới', mustChangePassword: true },
];

const RESEND_SECONDS = 60; // BR-TK-07, mặc định [CFG]
const MAX_FAILED_OTP = 5; // BR-TK-06, mặc định [CFG]
const INVALID_OTP = 'OTP không đúng hoặc đã hết hạn';
const BAD_CREDENTIALS = 'Email hoặc mật khẩu không đúng'; // BR-TK-10: một câu cho mọi trường hợp

// Trạng thái thay đổi được giữ trong localStorage để qua F5 vẫn còn.
interface Registered {
  id: number;
  email: string;
  fullName: string;
  password: string;
  active: boolean;
}
interface Otp {
  code: string;
  sentAt: number;
  failed: number;
  cancelled?: boolean;
}
interface State {
  /** Mật khẩu mới, đã gỡ mustChangePassword… theo id tài khoản. */
  changes: Record<number, { password?: string; mustChangePassword?: boolean }>;
  /** Khách tự đăng ký (UC01). */
  registered: Registered[];
  /** Mã đang dùng, khóa `REGISTER:<email>` hoặc `RESET:<email>`. */
  otps: Record<string, Otp>;
}

const STATE_KEY = 'petcare-mock-identity';

function load(): State {
  try {
    const s = JSON.parse(localStorage.getItem(STATE_KEY) ?? '{}');
    return { changes: s.changes ?? {}, registered: s.registered ?? [], otps: s.otps ?? {} };
  } catch {
    return { changes: {}, registered: [], otps: {} };
  }
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

interface Account {
  id: number;
  email: string;
  role: Role;
  fullName: string;
  password: string;
  status: 'PENDING' | 'ACTIVE';
  mustChangePassword: boolean;
}

function accounts(s: State): Account[] {
  const seeded = MOCK_ACCOUNTS.map((a): Account => ({
    ...a,
    status: 'ACTIVE',
    password: s.changes[a.id]?.password ?? MOCK_PASSWORD,
    mustChangePassword: s.changes[a.id]?.mustChangePassword ?? a.mustChangePassword ?? false,
  }));
  const registered = s.registered.map((r): Account => ({
    id: r.id,
    email: r.email,
    role: 'CUSTOMER',
    fullName: r.fullName,
    password: s.changes[r.id]?.password ?? r.password,
    status: r.active ? 'ACTIVE' : 'PENDING',
    mustChangePassword: false,
  }));
  return [...seeded, ...registered];
}

const normalize = (email: string) => email.trim().toLowerCase();
const byEmail = (s: State, email: string) => accounts(s).find((a) => a.email === normalize(email));

function summaryOf(a: Account): AccountSummary {
  return { id: a.id, email: a.email, role: a.role, status: a.status, isLocked: false, mustChangePassword: a.mustChangePassword };
}

const TOKEN_PREFIX = 'mock-token-';

/** Tài khoản của token, null nếu token không hợp lệ. */
export function accountForToken(token: string): AccountSummary | null {
  const account = accounts(load()).find((a) => a.status === 'ACTIVE' && token === TOKEN_PREFIX + a.id);
  return account ? summaryOf(account) : null;
}

// ─── Kiểm dữ liệu vào (cùng câu báo lỗi với các DTO của BE) ───

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const emailChecks = (email: unknown): Check[] => [
  ['email', 'Email không được để trống', !isText(email)],
  ['email', 'Email không đúng định dạng', isText(email) && !EMAIL_RE.test(email.trim())],
];

const codeChecks = (code: unknown): Check[] => [
  ['code', 'Mã OTP không được để trống', !isText(code)],
  ['code', 'Mã OTP chỉ gồm tối đa 8 chữ số', isText(code) && !/^\d{1,8}$/.test(code)],
];

// ─── OTP (BR-TK-04…07) ───

function issueOtp(s: State, key: string): Otp {
  s.otps[key] = { code: MOCK_OTP_CODE, sentAt: Date.now(), failed: 0 };
  return s.otps[key];
}

const resendAt = (otp: Otp) => new Date(otp.sentAt + RESEND_SECONDS * 1000).toISOString();

/** null khi mã đúng; mã sai được đếm vào `s` (handler phải lưu lại cả khi trả lỗi). */
function rejectOtp(s: State, key: string, code: string): MockReply | null {
  const otp = s.otps[key];
  if (!otp || otp.cancelled) return ruleFail('BR-TK-05', INVALID_OTP);
  if (otp.code === code) return null;
  otp.failed += 1;
  if (otp.failed < MAX_FAILED_OTP) return ruleFail('BR-TK-05', INVALID_OTP);
  otp.cancelled = true;
  return ruleFail('BR-TK-06', `Nhập sai mã OTP quá ${MAX_FAILED_OTP} lần, mã đã bị hủy. Vui lòng yêu cầu mã mới`);
}

const maskEmail = (email: string) => email.replace(/^(.).*(@.*)$/, '$1***$2');

// ─── Handlers ───

const register: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ...emailChecks(b.email),
    ['password', 'Mật khẩu không được để trống', !isText(b.password)],
    ['fullName', 'Họ tên không được để trống', !isText(b.fullName)],
    ['fullName', 'Họ tên tối đa 100 ký tự', isText(b.fullName) && b.fullName.length > 100],
    ['phone', 'Số điện thoại phải có dạng 0xxxxxxxxx', b.phone != null && !/^0\d{9}$/.test(String(b.phone))],
    ['isAdult', 'Thiếu xác nhận đủ 18 tuổi', typeof b.isAdult !== 'boolean'],
    ['termsAccepted', 'Thiếu xác nhận đồng ý điều khoản sử dụng', typeof b.termsAccepted !== 'boolean'],
  ]);
  if (bad) return bad;
  if (!b.isAdult || !b.termsAccepted) {
    return ruleFail('BR-TK-02', 'Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng');
  }
  const s = load();
  const email = normalize(b.email as string);
  if (byEmail(s, email)) {
    return ruleFail('BR-TK-01', 'Email đã được sử dụng. Vui lòng đăng nhập hoặc dùng chức năng quên mật khẩu');
  }
  const policy = passwordPolicyError(b.password as string);
  if (policy) return ruleFail('BR-TK-03', policy);

  const id = 100 + s.registered.length;
  s.registered.push({ id, email, fullName: (b.fullName as string).trim(), password: b.password as string, active: false });
  const otp = issueOtp(s, `REGISTER:${email}`);
  save(s);
  return ok({ accountId: id, email, status: 'PENDING', otpResendAvailableAt: resendAt(otp) }, 201, 'Đăng ký thành công');
};

function pendingRegistration(s: State, email: string): Registered | MockReply {
  const reg = s.registered.find((r) => r.email === normalize(email) && !r.active);
  return reg ?? fail('RESOURCE_NOT_FOUND', `Không tìm thấy tài khoản chờ xác thực #${normalize(email)}`, 404);
}

const verifyRegistration: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([...emailChecks(b.email), ...codeChecks(b.code)]);
  if (bad) return bad;
  const s = load();
  const reg = pendingRegistration(s, b.email as string);
  if ('status' in reg) return reg;
  const key = `REGISTER:${reg.email}`;
  const rejected = rejectOtp(s, key, b.code as string);
  if (rejected) {
    save(s);
    return rejected;
  }
  reg.active = true;
  delete s.otps[key];
  save(s);
  return ok({ accountId: reg.id, status: 'ACTIVE', linkDecisionPending: false });
};

const resendRegistrationOtp: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid(emailChecks(b.email));
  if (bad) return bad;
  const s = load();
  const reg = pendingRegistration(s, b.email as string);
  if ('status' in reg) return reg;
  const key = `REGISTER:${reg.email}`;
  const last = s.otps[key];
  const wait = last ? Math.ceil((last.sentAt + RESEND_SECONDS * 1000 - Date.now()) / 1000) : 0;
  if (wait > 0) return ruleFail('BR-TK-07', `Vui lòng chờ ${wait} giây trước khi gửi lại mã OTP`);
  const otp = issueOtp(s, key);
  save(s);
  return ok({ resendAvailableAt: resendAt(otp), maskedEmail: maskEmail(reg.email) });
};

const login: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([...emailChecks(b.email), ['password', 'Mật khẩu không được để trống', !isText(b.password)]]);
  if (bad) return bad;
  const account = byEmail(load(), b.email as string);
  if (!account || account.password !== b.password) return fail('UNAUTHENTICATED', BAD_CREDENTIALS, 401);
  if (account.status === 'PENDING') {
    return ruleFail('BR-TK-08', 'Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email');
  }
  const res: LoginResponse = {
    accessToken: TOKEN_PREFIX + account.id,
    expiresAt: new Date(Date.now() + 12 * 3_600_000).toISOString(), // session.ttl_hours mặc định
    account: summaryOf(account),
    linkDecisionPending: false,
  };
  return ok(res);
};

/** Luôn trả cùng một nội dung, có tài khoản hay không (BR-TK-10). */
const forgotPassword: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid(emailChecks(b.email));
  if (bad) return bad;
  const s = load();
  const account = byEmail(s, b.email as string);
  const key = `RESET:${normalize(b.email as string)}`;
  const last = s.otps[key];
  const waiting = last && last.sentAt + RESEND_SECONDS * 1000 > Date.now();
  if (account?.status === 'ACTIVE' && !waiting) {
    issueOtp(s, key);
    save(s);
  }
  return ok(
    { resendAvailableAt: new Date(Date.now() + RESEND_SECONDS * 1000).toISOString(), maskedEmail: null },
    202,
    'Nếu email đã đăng ký, mã đặt lại mật khẩu đã được gửi',
  );
};

const resetPassword: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ...emailChecks(b.email),
    ...codeChecks(b.code),
    ['newPassword', 'Mật khẩu mới không được để trống', !isText(b.newPassword)],
  ]);
  if (bad) return bad;
  // Kiểm mật khẩu trước mã: không tiêu lượt nhập.
  const policy = passwordPolicyError(b.newPassword as string);
  if (policy) return ruleFail('BR-TK-03', policy);
  const s = load();
  const account = byEmail(s, b.email as string);
  if (account?.status !== 'ACTIVE') return ruleFail('BR-TK-05', INVALID_OTP);
  const key = `RESET:${account.email}`;
  const rejected = rejectOtp(s, key, b.code as string);
  if (rejected) {
    save(s);
    return rejected;
  }
  // Mã vẫn dùng lại được khi mật khẩu mới trùng mật khẩu cũ.
  if (b.newPassword === account.password) return ruleFail('BR-TK-03', 'Mật khẩu mới không được trùng mật khẩu hiện tại');
  s.changes[account.id] = { ...s.changes[account.id], password: b.newPassword as string, mustChangePassword: false };
  delete s.otps[key];
  save(s);
  return noContent();
};

const me: MockHandler = ({ account }) => {
  const a = accounts(load()).find((x) => x.id === account!.id)!;
  const isStaffAccount = a.role !== 'CUSTOMER';
  return ok({
    account,
    ...(isStaffAccount
      ? {
          staffProfile: {
            accountId: a.id,
            fullName: a.fullName,
            phone: '0900000000',
            ...(a.role !== 'ADMIN' && a.role !== 'SUPER_MANAGER' && { branchId: 1 }),
          },
        }
      : { customerId: a.id }),
    linkDecisionPending: false,
  });
};

const changePassword: MockHandler = ({ account, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ['currentPassword', 'Mật khẩu hiện tại không được để trống', !isText(b.currentPassword)],
    ['newPassword', 'Mật khẩu mới không được để trống', !isText(b.newPassword)],
  ]);
  if (bad) return bad;
  const s = load();
  const a = accounts(s).find((x) => x.id === account!.id)!;
  if (a.password !== b.currentPassword) return ruleFail('BR-TK-14', 'Mật khẩu hiện tại không đúng');
  const policy = passwordPolicyError(b.newPassword as string);
  if (policy) return ruleFail('BR-TK-03', policy);
  if (b.newPassword === b.currentPassword) return ruleFail('BR-TK-03', 'Mật khẩu mới không được trùng mật khẩu hiện tại');
  s.changes[a.id] = { ...s.changes[a.id], password: b.newPassword as string, mustChangePassword: false };
  save(s);
  return noContent();
};

export const identityRoutes: Record<string, MockHandler> = {
  'POST /api/auth/register': register,
  'POST /api/auth/register/verify': verifyRegistration,
  'POST /api/auth/register/resend-otp': resendRegistrationOtp,
  'POST /api/auth/login': login,
  'POST /api/auth/logout': () => noContent(),
  'POST /api/auth/password/forgot': forgotPassword,
  'POST /api/auth/password/reset': resetPassword,
  'GET /api/me': me,
  'POST /api/me/password': changePassword,
};
