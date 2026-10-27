// Mock module identity (docs/api/identity-v1.md): đăng ký + OTP, đăng nhập, đăng xuất,
// quên / đặt lại mật khẩu, GET /me, đổi mật khẩu, hồ sơ nhân viên, liên kết hồ sơ (UC07).
// Quản trị (UC08–11): staff, accounts, system-configs, notification-templates, audit-logs. Câu báo lỗi chép từ BE.
import type { AccountSummary, AccountStatus, LoginResponse, Role } from '../../types/auth';
import type { StaffProfile } from '../../types/profile';
import type { AuditLogEntry } from '../../types/identity';
import { passwordPolicyError } from '../../utils/password';
import {
  createOnlineProfile,
  customerOfAccount,
  declineLink,
  findCustomer,
  flagLinkDecisionIfPhoneMatches,
  linkAccount,
  linkBlocker,
  linkCandidates,
} from './customer.mock';
import {
  HTTPS_URL,
  blank,
  denied,
  fail,
  has,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  optional,
  ruleFail,
  scopeDenied,
  tooLong,
  type Check,
  type MockHandler,
  type MockReply,
} from './envelope';

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
  /** Chi nhánh làm việc của A05–A08 (staff_profiles.branch_id); ADMIN, SUPER_MANAGER, khách không có. */
  branchId?: number;
}

/** Id 3–6 trùng mã nhân viên trong clinic-db để "lượt của tôi" ở bàn làm việc khớp. Chi nhánh: branch.mock.ts. */
export const MOCK_ACCOUNTS: readonly MockAccount[] = [
  { id: 1, email: 'admin@petcare.vn', role: 'ADMIN', fullName: 'Quản trị hệ thống' },
  { id: 2, email: 'chuoi@petcare.vn', role: 'SUPER_MANAGER', fullName: 'Đỗ Thanh Hà' },
  { id: 3, email: 'manager@store1.vn', role: 'BRANCH_MANAGER', fullName: 'Hoàng Nam', branchId: 1 },
  { id: 4, email: 'reception@store1.vn', role: 'RECEPTIONIST', fullName: 'Lan Chi', branchId: 1 },
  { id: 5, email: 'doctor@store1.vn', role: 'VET', fullName: 'BS. Minh Anh', branchId: 1 },
  { id: 6, email: 'caretaker@store1.vn', role: 'CARETAKER', fullName: 'Thu Hà', branchId: 1 },
  { id: 7, email: 'khach@petcare.vn', role: 'CUSTOMER', fullName: 'Nguyễn Thu Trang' },
  { id: 8, email: 'newstaff@store1.vn', role: 'RECEPTIONIST', fullName: 'Lễ tân mới', mustChangePassword: true, branchId: 1 },
  { id: 9, email: 'manager2@store2.vn', role: 'BRANCH_MANAGER', fullName: 'Phạm Minh Đức', branchId: 2 },
  // SĐT trùng hồ sơ tại quầy chưa liên kết: đăng nhập là gặp màn liên kết hồ sơ (BR-TK-19, customer.mock.ts).
  { id: 10, email: 'khachmoi@petcare.vn', role: 'CUSTOMER', fullName: 'Lê Hoàng Long' },
  // Id 21–23: bác sĩ thêm cho trang chủ / trang bác sĩ có đủ 4 thẻ. Tránh 11 (cashier.mock) và 12 (visit.mock) vì hai mock đó giữ chỗ làm nhân viên đã nghỉ; 100+ là khách tự đăng ký, 200+ là nhân viên tạo thêm.
  { id: 21, email: 'doctor2@store1.vn', role: 'VET', fullName: 'BS. Quốc Bảo', branchId: 1 },
  { id: 22, email: 'doctor3@store1.vn', role: 'VET', fullName: 'BS. Ngọc Lan', branchId: 1 },
  { id: 23, email: 'doctor4@store1.vn', role: 'VET', fullName: 'BS. Thanh Thảo', branchId: 1 },
];

/** Hồ sơ nhân viên ban đầu (staff_profiles); họ tên lấy từ MOCK_ACCOUNTS. */
export const STAFF_SEED: Record<number, Partial<StaffProfile>> = {
  5: {
    avatarUrl: '/imgs/team1.jpg',
    specialty: 'Nội khoa, da liễu thú y',
    bio: 'Hơn 8 năm khám và điều trị chó mèo, quan tâm đặc biệt tới bệnh da và dị ứng.',
  },
  21: {
    avatarUrl: '/imgs/team2.jpg',
    specialty: 'Ngoại khoa, chỉnh hình',
    bio: 'Hơn 10 năm phẫu thuật mô mềm và chỉnh hình cho chó mèo, theo sát giai đoạn hồi phục sau mổ.',
  },
  22: {
    avatarUrl: '/imgs/team3.jpg',
    specialty: 'Chẩn đoán hình ảnh, siêu âm',
    bio: 'Chuyên siêu âm, X-quang và xét nghiệm giúp phát hiện sớm bệnh nội tạng ở thú cưng.',
  },
  23: {
    avatarUrl: '/imgs/team4.jpg',
    specialty: 'Dinh dưỡng, tiêm phòng',
    bio: 'Tư vấn dinh dưỡng và lịch tiêm phòng theo từng độ tuổi cho chó mèo con.',
  },
};

/** Chi nhánh của nhân viên A05–A08; undefined với ADMIN, SUPER_MANAGER và khách. */
export const branchIdOf = (accountId: number) => MOCK_ACCOUNTS.find((a) => a.id === accountId)?.branchId;

/** Như StaffDirectoryApi.countBranchManagers: đếm cả quản lý đang bị khóa. */
export const countBranchManagers = (branchId: number) =>
  MOCK_ACCOUNTS.filter((a) => a.role === 'BRANCH_MANAGER' && a.branchId === branchId).length;

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
  /** Mật khẩu mới, đã gỡ mustChangePassword, email do lễ tân sửa hộ (UC22)… theo id tài khoản. */
  changes: Record<number, { password?: string; mustChangePassword?: boolean; email?: string }>;
  /** Khách tự đăng ký (UC01). */
  registered: Registered[];
  /**
   * Mã đang dùng, khóa `REGISTER:<email>`, `RESET:<email>`, `LINK:<accountId>` hoặc
   * `CUSTOMER_EMAIL:<customerId>` (lễ tân sửa email tài khoản khách, giữ email mới chờ xác nhận).
   */
  otps: Record<string, Otp & { customerId?: number; newEmail?: string }>;
  /** Hồ sơ nhân viên đã sửa (PATCH /me/staff-profile) theo id tài khoản. */
  profiles: Record<number, Partial<StaffProfile>>;
  /** UC08: tài khoản nhân viên tạo thêm sau seed (đã thấy trong MOCK_ACCOUNTS). */
  extraStaff: Record<number, { id: number; email: string; fullName: string; role: Role; status: AccountStatus; branchId?: number; mustChangePassword: boolean }>;
  /** UC08: email mới đang chờ xác nhận cho staff. */
  emailChanges: Record<number, { newEmail: string; otp: Otp }>;
  /** UC09: ghi chú khóa gần nhất + status trước khi khóa. */
  locks: Record<number, { reason: string; statusBefore: AccountStatus; lockedAt: string }>;
  /** UC10: sửa tham số. */
  configOverrides: Record<string, string>;
  /** UC10: sửa mẫu thông báo. */
  templateOverrides: Record<string, { subject?: string | null; body: string }>;
  /** UC11: nhật ký kiểm toán. */
  audit: AuditLogEntry[];
}

const STATE_KEY = 'petcare-mock-identity';

export function load(): State {
  try {
    const s = JSON.parse(localStorage.getItem(STATE_KEY) ?? '{}');
    return {
      changes: s.changes ?? {},
      registered: s.registered ?? [],
      otps: s.otps ?? {},
      profiles: s.profiles ?? {},
      extraStaff: s.extraStaff ?? {},
      emailChanges: s.emailChanges ?? {},
      locks: s.locks ?? {},
      configOverrides: s.configOverrides ?? {},
      templateOverrides: s.templateOverrides ?? {},
      audit: s.audit ?? [],
    };
  } catch {
    return { changes: {}, registered: [], otps: {}, profiles: {}, extraStaff: {}, emailChanges: {}, locks: {}, configOverrides: {}, templateOverrides: {}, audit: [] };
  }
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

export { save };

interface Account {
  id: number;
  email: string;
  role: Role;
  fullName: string;
  password: string;
  status: AccountStatus;
  mustChangePassword: boolean;
  branchId?: number;
}

function accounts(s: State): Account[] {
  const seeded = MOCK_ACCOUNTS.map((a): Account => ({
    ...a,
    email: s.changes[a.id]?.email ?? a.email,
    status: 'ACTIVE',
    password: s.changes[a.id]?.password ?? MOCK_PASSWORD,
    mustChangePassword: s.changes[a.id]?.mustChangePassword ?? a.mustChangePassword ?? false,
    branchId: branchIdOf(a.id),
  }));
  const registered = s.registered.map((r): Account => ({
    id: r.id,
    email: s.changes[r.id]?.email ?? r.email,
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

/** Email hiện tại của tài khoản (kể cả email lễ tân đã sửa hộ); hồ sơ gắn tài khoản lấy email theo đây (customer-v1 A5). */
export const accountEmailOf = (accountId: number) => accounts(load()).find((a) => a.id === accountId)?.email ?? null;

function summaryOf(a: Account): AccountSummary {
  return { id: a.id, email: a.email, role: a.role, status: a.status, isLocked: false, mustChangePassword: a.mustChangePassword, branchId: a.branchId };
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

/** Như EmailMasker của BE: giữ 2 ký tự đầu (1 nếu phần trước @ chỉ có 1–2 ký tự). Tra cứu khách (BR-KH-10) dùng chung. */
export function maskEmail(email: string): string {
  const at = email.lastIndexOf('@');
  if (at <= 0 || at === email.length - 1) return '***';
  const local = [...email.slice(0, at)];
  return local.slice(0, local.length > 2 ? 2 : 1).join('') + '***' + email.slice(at);
}

/** Số giây còn phải chờ trước khi gửi lại mã (BR-TK-07); 0 nếu gửi được. */
const waitSeconds = (last?: Otp) => (last ? Math.ceil((last.sentAt + RESEND_SECONDS * 1000 - Date.now()) / 1000) : 0);

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
  const fullName = (b.fullName as string).trim();
  s.registered.push({ id, email, fullName, password: b.password as string, active: false });
  const otp = issueOtp(s, `REGISTER:${email}`);
  save(s);
  createOnlineProfile(id, fullName, (b.phone as string | undefined) ?? null);
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
  return ok({ accountId: reg.id, status: 'ACTIVE', linkDecisionPending: flagLinkDecisionIfPhoneMatches(reg.id) });
};

const resendRegistrationOtp: MockHandler = ({ body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid(emailChecks(b.email));
  if (bad) return bad;
  const s = load();
  const reg = pendingRegistration(s, b.email as string);
  if ('status' in reg) return reg;
  const key = `REGISTER:${reg.email}`;
  const wait = waitSeconds(s.otps[key]);
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
    linkDecisionPending: customerOfAccount(account.id)?.linkDecisionPending ?? false,
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

/** staff_profiles: dữ liệu mẫu cộng các lần sửa. SĐT mẫu `0900000` + id 3 chữ số. */
function staffProfileOf(s: State, a: Account): StaffProfile {
  return {
    accountId: a.id,
    fullName: a.fullName,
    avatarUrl: null,
    phone: `0900000${String(a.id).padStart(3, '0')}`,
    branchId: branchIdOf(a.id) ?? null,
    specialty: null,
    bio: null,
    ...STAFF_SEED[a.id],
    ...s.profiles[a.id],
  };
}

const me: MockHandler = ({ account }) => {
  const s = load();
  const a = accounts(s).find((x) => x.id === account!.id)!;
  if (a.role !== 'CUSTOMER') return ok({ account, staffProfile: staffProfileOf(s, a), customerId: null, linkDecisionPending: false });
  const own = customerOfAccount(a.id);
  return ok({ account, staffProfile: null, customerId: own?.customerId ?? null, linkDecisionPending: own?.linkDecisionPending ?? false });
};

/** Mặc định của [CFG] `vet.bio_max_length` (BR-TK-20). */
const BIO_MAX = 500;

/** null / không gửi = giữ nguyên; chuỗi rỗng = xóa `avatarUrl`, `specialty`, `bio` (ADR-0026). */
const updateStaffProfile: MockHandler = ({ account, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ['fullName', 'Họ tên tối đa 100 ký tự', tooLong(b.fullName, 100)],
    ['fullName', 'Họ tên không được để trống', blank(b.fullName)],
    ['avatarUrl', 'Đường dẫn ảnh đại diện tối đa 500 ký tự', tooLong(b.avatarUrl, 500)],
    ['avatarUrl', 'Ảnh đại diện phải là đường dẫn https://', has(b.avatarUrl) && !HTTPS_URL.test(String(b.avatarUrl))],
    ['phone', 'Số điện thoại phải có dạng 0xxxxxxxxx', has(b.phone) && !/^0\d{9}$/.test(String(b.phone))],
    ['specialty', 'Chuyên môn tối đa 200 ký tự', tooLong(b.specialty, 200)],
  ]);
  if (bad) return bad;
  if (account!.role === 'CUSTOMER') return denied();
  if (has(b.email)) return ruleFail('BR-TK-15', 'Không thể tự sửa email; liên hệ cấp trên để được sửa hộ');
  if (account!.role !== 'VET' && (has(b.specialty) || has(b.bio))) return scopeDenied();
  const bio = optional(b.bio);
  if (bio && [...bio].length > BIO_MAX) return ruleFail('BR-TK-20', `Mô tả ngắn tối đa ${BIO_MAX} ký tự`);
  const s = load();
  const change = { ...s.profiles[account!.id] };
  if (has(b.fullName)) change.fullName = (b.fullName as string).trim();
  if (has(b.phone)) change.phone = b.phone as string;
  for (const key of ['avatarUrl', 'specialty', 'bio'] as const) if (has(b[key])) change[key] = optional(b[key]);
  s.profiles[account!.id] = change;
  save(s);
  return ok(staffProfileOf(s, accounts(s).find((x) => x.id === account!.id)!), 200, 'Đã cập nhật hồ sơ');
};

// ─── Liên kết hồ sơ tại quầy (UC07, BR-TK-19, ADR-0027) ───

const LINK_PHONE = 'Số điện thoại phải gồm 10–11 chữ số, bắt đầu bằng 0';
const LINK_PHONE_RE = /^0[0-9]{9,10}$/;

const linkTargetChecks = (b: Record<string, unknown>): Check[] => [
  ['customerId', 'Vui lòng chọn hồ sơ cần liên kết', !has(b.customerId)],
  ['customerId', 'Mã hồ sơ không hợp lệ', has(b.customerId) && !(Number(b.customerId) > 0)],
];

function linkRefused(accountId: number): MockReply | null {
  const blocker = linkBlocker(accountId);
  if (blocker === 'ALREADY_LINKED') return ruleFail('BR-TK-19', 'Tài khoản đã được liên kết với hồ sơ khách hàng tại phòng khám');
  if (blocker === 'HAS_DATA') {
    return ruleFail(
      'BR-TK-19',
      'Hồ sơ của bạn đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không thể liên kết với hồ sơ tại quầy. Vui lòng liên hệ quầy để được hỗ trợ',
    );
  }
  return null;
}

/** SĐT khách nhập; không nhập thì SĐT của hồ sơ online. */
const phoneFor = (accountId: number, phone: unknown) => (has(phone) ? String(phone) : (customerOfAccount(accountId)?.phone ?? null));

/** Như stub của BE (LinkProfileIT): "Nguyễn Văn An" → "Ng*** V** A*". */
export function maskFullName(fullName: string): string {
  const words = fullName.trim().split(' ');
  return words
    .map((word, i) => {
      const chars = [...word];
      if (i === 0) return chars.slice(0, 2).join('') + '***';
      return chars[0] + (i === words.length - 1 ? '*' : '**');
    })
    .join(' ');
}

const linkCandidatesOf: MockHandler = ({ account, query }) => {
  const bad = invalid([['phone', LINK_PHONE, 'phone' in query && !LINK_PHONE_RE.test(query.phone)]]);
  if (bad) return bad;
  if (account!.role !== 'CUSTOMER') return denied();
  const refused = linkRefused(account!.id);
  if (refused) return refused;
  const phone = phoneFor(account!.id, query.phone);
  const candidates = phone ? linkCandidates(phone) : [];
  return ok(candidates.map((c) => ({ customerId: c.customerId, maskedFullName: maskFullName(c.fullName), hasEmail: c.email !== null })));
};

const sendLinkOtp: MockHandler = ({ account, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([...linkTargetChecks(b), ['phone', LINK_PHONE, has(b.phone) && !LINK_PHONE_RE.test(String(b.phone))]]);
  if (bad) return bad;
  if (account!.role !== 'CUSTOMER') return denied();
  const customerId = Number(b.customerId);
  const phone = phoneFor(account!.id, b.phone);
  // Không tồn tại và không phải ứng viên của SĐT: cùng một câu 404.
  const counter = phone ? linkCandidates(phone).find((c) => c.customerId === customerId) : undefined;
  if (!counter) return notFound('hồ sơ khách', customerId);
  const refused = linkRefused(account!.id);
  if (refused) return refused;
  if (!counter.email) {
    return ruleFail('BR-TK-19', 'Hồ sơ tại quầy chưa có email nên không thể liên kết online. Vui lòng ra quầy để được hỗ trợ');
  }
  const s = load();
  const key = `LINK:${account!.id}`;
  const wait = waitSeconds(s.otps[key]);
  if (wait > 0) return ruleFail('BR-TK-07', `Vui lòng chờ ${wait} giây trước khi gửi lại mã OTP`);
  // Mã mới hủy mã liên kết cũ của tài khoản.
  const otp = { ...issueOtp(s, key), customerId };
  s.otps[key] = otp;
  save(s);
  return ok({ resendAvailableAt: resendAt(otp), maskedEmail: maskEmail(counter.email) }, 200, 'Đã gửi mã xác thực tới email của hồ sơ');
};

const confirmLink: MockHandler = ({ account, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([...linkTargetChecks(b), ...codeChecks(b.code)]);
  if (bad) return bad;
  if (account!.role !== 'CUSTOMER') return denied();
  const customerId = Number(b.customerId);
  const counter = findCustomer(customerId);
  if (!counter) return notFound('hồ sơ khách', customerId);
  const s = load();
  const key = `LINK:${account!.id}`;
  if (!counter.email || s.otps[key]?.customerId !== customerId) return ruleFail('BR-TK-05', INVALID_OTP);
  const rejected = rejectOtp(s, key, b.code as string);
  if (rejected) {
    save(s);
    return rejected;
  }
  // Module customer kiểm lại dưới khóa; bị từ chối thì mã vẫn dùng lại được.
  const refused = linkRefused(account!.id);
  if (refused) return refused;
  if (!linkAccount(account!.id, customerId, account!.email)) return ruleFail('BR-TK-19', 'Không thể liên kết hồ sơ này');
  delete s.otps[key];
  save(s);
  return ok({ customerId }, 200, 'Liên kết hồ sơ thành công');
};

const declineLinkOf: MockHandler = ({ account }) => {
  if (account!.role !== 'CUSTOMER') return denied();
  if (!declineLink(account!.id)) {
    return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái liên kết hồ sơ từ không còn chờ quyết định sang Không phải tôi', 409);
  }
  return noContent();
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

// ─── Tài khoản của hồ sơ khách tại quầy (UC22, identity-v1 #25–27, chỉ lễ tân) ───

/** Lễ tân đối chiếu CCCD tại quầy; chỉ ghi phương thức, không lưu CCCD (BR-TK-16, A6). */
const verificationChecks = (method: unknown): Check[] => [
  ['verificationMethod', 'Phương thức xác minh phải là ID_CARD_IN_PERSON', method !== 'ID_CARD_IN_PERSON'],
];

const newEmailChecks = (field: string, email: unknown): Check[] => [
  [field, 'Email không được để trống', !isText(email)],
  [field, 'Email không đúng định dạng', isText(email) && !EMAIL_RE.test(email.trim())],
  [field, 'Email tối đa 255 ký tự', isText(email) && email.trim().length > 255],
];

/** Hồ sơ khách và tài khoản gắn với nó; lỗi nếu không có hồ sơ hoặc hồ sơ chưa gắn tài khoản. */
function accountOfCustomer(s: State, customerId: number): Account | MockReply {
  const customer = findCustomer(customerId);
  if (!customer) return notFound('hồ sơ khách', customerId);
  const a = customer.accountId === null ? undefined : accounts(s).find((x) => x.id === customer.accountId);
  if (!a) return ruleFail('BR-TK-16', 'Hồ sơ chưa gắn tài khoản online nên không có email tài khoản để sửa');
  return a;
}
const isAccount = (x: Account | MockReply): x is Account => 'role' in x;

/** BR-TK-01: email trùng tài khoản khác, kể cả tài khoản PENDING và nhân viên tạo thêm. */
const emailTaken = (s: State, email: string) => !!byEmail(s, email) || Object.values(s.extraStaff).some((x) => x.email === email);

const sendCustomerEmailChange: MockHandler = ({ account, params, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([...newEmailChecks('newEmail', b.newEmail), ...verificationChecks(b.verificationMethod)]);
  if (bad) return bad;
  if (account!.role !== 'RECEPTIONIST') return denied();
  const s = load();
  const customerId = Number(params.customerId);
  const a = accountOfCustomer(s, customerId);
  if (!isAccount(a)) return a;
  const newEmail = normalize(b.newEmail as string);
  if (newEmail === a.email) return ruleFail('BR-TK-01', 'Email mới trùng email hiện tại');
  if (emailTaken(s, newEmail)) return ruleFail('BR-TK-01', 'Email đã được sử dụng');
  const key = `CUSTOMER_EMAIL:${customerId}`;
  const wait = waitSeconds(s.otps[key]);
  if (wait > 0) return ruleFail('BR-TK-07', `Vui lòng chờ ${wait} giây trước khi gửi lại mã OTP`);
  const otp = issueOtp(s, key);
  s.otps[key] = { ...otp, newEmail };
  save(s);
  return ok({ resendAvailableAt: resendAt(otp), maskedEmail: maskEmail(newEmail) }, 200, 'Đã gửi mã OTP tới email mới');
};

/** Mã đúng mới đổi email; chưa xác nhận thì giữ nguyên email cũ (BR-TK-16). */
const confirmCustomerEmailChange: MockHandler = ({ account, params, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid(codeChecks(b.code));
  if (bad) return bad;
  if (account!.role !== 'RECEPTIONIST') return denied();
  const s = load();
  const customerId = Number(params.customerId);
  const a = accountOfCustomer(s, customerId);
  if (!isAccount(a)) return a;
  const key = `CUSTOMER_EMAIL:${customerId}`;
  const pending = s.otps[key];
  const rejected = rejectOtp(s, key, b.code as string);
  if (rejected) {
    save(s);
    return rejected;
  }
  // Email mới vừa bị tài khoản khác dùng: từ chối, mã vẫn dùng lại được.
  const newEmail = pending!.newEmail!;
  if (emailTaken(s, newEmail)) return ruleFail('BR-TK-01', 'Email đã được sử dụng');
  s.changes[a.id] = { ...s.changes[a.id], email: newEmail };
  delete s.otps[key];
  save(s);
  return noContent();
};

/** Không cần OTP: lễ tân đã đối chiếu CCCD với họ tên hồ sơ (identity-v1 mục B, BR-TK-19). */
const linkCounterAccount: MockHandler = ({ account, params, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([...newEmailChecks('accountEmail', b.accountEmail), ...verificationChecks(b.verificationMethod)]);
  if (bad) return bad;
  if (account!.role !== 'RECEPTIONIST') return denied();
  const customerId = Number(params.customerId);
  const counter = findCustomer(customerId);
  if (!counter) return notFound('hồ sơ khách', customerId);
  const s = load();
  const target = byEmail(s, b.accountEmail as string);
  if (!target || target.role !== 'CUSTOMER' || target.status !== 'ACTIVE') {
    return fail('RESOURCE_NOT_FOUND', 'Không tìm thấy tài khoản khách hàng đã kích hoạt với email này', 404);
  }
  if (counter.createdChannel !== 'COUNTER') return ruleFail('BR-TK-19', 'Chỉ liên kết được hồ sơ lập tại quầy');
  if (counter.accountId !== null) return ruleFail('BR-TK-19', 'Hồ sơ tại quầy này đã liên kết tài khoản');
  const blocker = linkBlocker(target.id);
  if (blocker === 'ALREADY_LINKED') return ruleFail('BR-TK-19', 'Tài khoản đã được liên kết với một hồ sơ tại quầy khác');
  if (blocker === 'HAS_DATA') {
    return ruleFail('BR-TK-19', 'Hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không liên kết được');
  }
  if (!linkAccount(target.id, customerId, target.email)) return ruleFail('BR-TK-19', 'Không thể liên kết hồ sơ này');
  return ok({ customerId }, 200, 'Đã liên kết hồ sơ với tài khoản');
};

export const identityRoutes: Record<string, MockHandler> = {
  'POST /api/customers/:customerId/account-email-change': sendCustomerEmailChange,
  'POST /api/customers/:customerId/account-email-change/confirm': confirmCustomerEmailChange,
  'POST /api/customers/:customerId/link-account': linkCounterAccount,
  'POST /api/auth/register': register,
  'POST /api/auth/register/verify': verifyRegistration,
  'POST /api/auth/register/resend-otp': resendRegistrationOtp,
  'POST /api/auth/login': login,
  'POST /api/auth/logout': () => noContent(),
  'POST /api/auth/password/forgot': forgotPassword,
  'POST /api/auth/password/reset': resetPassword,
  'GET /api/me': me,
  'POST /api/me/password': changePassword,
  'PATCH /api/me/staff-profile': updateStaffProfile,
  'GET /api/me/link-candidates': linkCandidatesOf,
  'POST /api/me/link/otp': sendLinkOtp,
  'POST /api/me/link/confirm': confirmLink,
  'POST /api/me/link/decline': declineLinkOf,
};
