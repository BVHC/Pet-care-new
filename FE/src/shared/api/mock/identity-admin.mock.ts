// Quản trị (UC08–11) — mock handlers viết riêng để file identity.mock.ts không quá dài.
import type { AccountStatus, Role } from '../../types/auth';
import type {
  AuditLogEntry,
  ChangeableStaffRole,
  CreateableStaffRole,
  LockResult,
  NotificationTemplate,
  StaffResponse,
  SystemConfig,
} from '../../types/identity';
import { MOCK_OTP_CODE, MOCK_PASSWORD, MOCK_ACCOUNTS, STAFF_SEED, branchIdOf, load, save, type MockAccount } from './identity.mock';
import {
  fail,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  page,
  ruleFail,
  type MockHandler,
  type MockReply,
} from './envelope';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const RESEND_SECONDS = 60;
const MAX_FAILED_OTP = 5;

const normalize = (email: string) => email.trim().toLowerCase();
const maskEmail = (email: string) => {
  const at = email.lastIndexOf('@');
  if (at <= 0 || at === email.length - 1) return '***';
  const local = [...email.slice(0, at)];
  return local.slice(0, local.length > 2 ? 2 : 1).join('') + '***' + email.slice(at);
};
const resendAt = (sentAt: number) => new Date(sentAt + RESEND_SECONDS * 1000).toISOString();
const waitSeconds = (sentAt?: number) =>
  sentAt ? Math.ceil((sentAt + RESEND_SECONDS * 1000 - Date.now()) / 1000) : 0;

const SEED_CONFIGS: SystemConfig[] = [
  { key: 'vet.bio_max_length', value: '500', valueType: 'INT', minValue: '100', maxValue: '2000', unit: 'ký tự', description: 'Độ dài tối đa mô tả ngắn của bác sĩ thú y' },
  { key: 'customer.address_book_max', value: '5', valueType: 'INT', minValue: '1', maxValue: '20', unit: 'địa chỉ', description: 'Số địa chỉ tối đa trong sổ địa chỉ của khách' },
  { key: 'auth.otp_resend_seconds', value: '60', valueType: 'INT', minValue: '30', maxValue: '300', unit: 'giây', description: 'Số giây tối thiểu giữa hai lần gửi OTP' },
  { key: 'auth.otp_max_per_hour', value: '5', valueType: 'INT', minValue: '1', maxValue: '10', unit: 'mã', description: 'Số mã OTP tối đa gửi tới một email trong 1 giờ' },
  { key: 'auth.session_ttl_hours', value: '12', valueType: 'INT', minValue: '1', maxValue: '72', unit: 'giờ', description: 'Thời hạn tuyệt đối của phiên đăng nhập' },
];

const SEED_TEMPLATES: NotificationTemplate[] = [
  {
    code: 'STAFF_TEMP_PASSWORD',
    channel: 'EMAIL',
    subject: 'Mật khẩu tạm thời cho tài khoản nhân viên {email}',
    body: 'Xin chào {fullName},\nMật khẩu tạm thời của bạn là: {tempPassword}\nVui lòng đăng nhập và đổi mật khẩu trong vòng 24 giờ.',
    allowedVars: ['fullName', 'email', 'tempPassword'],
    requiredVars: ['fullName', 'tempPassword'],
    isDefault: true,
  },
  {
    code: 'EMAIL_CHANGED_NOTICE',
    channel: 'EMAIL',
    subject: 'Email tài khoản đã được thay đổi',
    body: 'Email đăng nhập Pet Care của {fullName} đã được đổi từ {oldEmail} sang {newEmail}. Nếu bạn không thực hiện thao tác này, hãy liên hệ quản trị viên.',
    allowedVars: ['fullName', 'oldEmail', 'newEmail'],
    requiredVars: ['fullName', 'oldEmail', 'newEmail'],
    isDefault: true,
  },
  {
    code: 'OTP_PROFILE_LINK',
    channel: 'EMAIL',
    subject: 'Mã xác nhận liên kết hồ sơ',
    body: 'Mã xác nhận liên kết hồ sơ của {fullName}: {code}. Mã có hiệu lực trong 10 phút.',
    allowedVars: ['fullName', 'code'],
    requiredVars: ['fullName', 'code'],
    isDefault: true,
  },
  {
    code: 'BRANCH_MANAGER_LOCKED_APP',
    channel: 'IN_APP',
    subject: 'Quản lý chi nhánh bị khóa',
    body: 'Tài khoản {email} (quản lý cuối của {branchName}) đã bị khóa. Vui lòng phân công quản lý mới.',
    allowedVars: ['email', 'branchName'],
    requiredVars: ['email', 'branchName'],
    isDefault: true,
  },
];

function logAudit(
  s: ReturnType<typeof load>,
  entry: Omit<AuditLogEntry, 'id' | 'createdAt' | 'ipAddress'>,
  actorAccountId: number | null,
  actorEmail: string | null,
) {
  s.audit.unshift({
    id: (s.audit[0]?.id ?? 0) + 1,
    createdAt: new Date().toISOString(),
    actorAccountId,
    actorEmail,
    ipAddress: '127.0.0.1',
    ...entry,
  });
  if (s.audit.length > 500) s.audit.length = 500;
}

/** Tài khoản là nhân viên (role ≠ CUSTOMER) và tồn tại. */
function findStaffAccount(s: ReturnType<typeof load>, id: number): MockStaffWithState | undefined {
  const seed = MOCK_STAFF[id];
  if (seed) return seed;
  return s.extraStaff[id];
}

/** Họ tên nhân viên theo id, kể cả tài khoản tạo lúc chạy (UC08); null nếu không có. Như `StaffDirectoryApi.findStaffByIds`, cho mock thu ngân. */
export const staffNameOf = (accountId: number): string | null => findStaffAccount(load(), accountId)?.fullName ?? null;

/** Danh sách nhân viên từ seed + extra. */
function allStaff(s: ReturnType<typeof load>) {
  return [...Object.values(MOCK_STAFF), ...Object.values(s.extraStaff)];
}

/** Tính tổng quản lý ACTIVE còn lại của 1 chi nhánh. */
function countActiveBranchManagers(s: ReturnType<typeof load>, branchId: number, exceptId?: number) {
  return allStaff(s).filter(
    (a) =>
      a.role === 'BRANCH_MANAGER' &&
      a.id !== exceptId &&
      a.status === 'ACTIVE' &&
      !s.locks[a.id] &&
      a.branchId === branchId,
  ).length;
}

/** MockAccount + status + mustChangePassword; tránh đụng field của extraStaff (đang mutable). */
interface MockStaffWithState extends MockAccount {
  status: AccountStatus;
  mustChangePassword: boolean;
}

// Seed nhân viên — id 1=ADMIN, 2=SUPER_MANAGER, 3–10=A05–A08. Lấy từ MOCK_ACCOUNTS nhưng định dạng phẳng hơn.
const MOCK_STAFF: Record<number, MockStaffWithState> = Object.fromEntries(
  MOCK_ACCOUNTS.filter((a) => a.role !== 'CUSTOMER').map((a) => [
    a.id,
    {
      ...a,
      status: 'ACTIVE' as AccountStatus,
      mustChangePassword: a.mustChangePassword ?? false,
    },
  ]),
);

function staffResponseOf(s: ReturnType<typeof load>, a: MockStaffWithState): StaffResponse {
  return {
    accountId: a.id,
    email: a.email,
    phone: `0900000${String(a.id).padStart(3, '0')}`,
    fullName: a.fullName,
    role: a.role,
    status: a.status,
    isLocked: !!s.locks[a.id],
    branchId: a.branchId ?? null,
    specialty: a.role === 'VET' ? (s.profiles[a.id]?.specialty ?? STAFF_SEED[a.id]?.specialty ?? null) : null,
    bio: a.role === 'VET' ? (s.profiles[a.id]?.bio ?? STAFF_SEED[a.id]?.bio ?? null) : null,
    mustChangePassword: a.mustChangePassword,
    online: a.status === 'ACTIVE' && !s.locks[a.id] && !loadPresence().offline.includes(a.id),
  };
}

const denied = () => fail('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403);

// ─── Phân trang như BE (chỉ dùng trong file này; KHÔNG đổi `page()` toàn cục của envelope.ts) ───

const PAGE_SIZE_DEFAULT = 20; // BE: @RequestParam(defaultValue = "20") của staff / accounts / audit-logs
const PAGE_SIZE_MAX = 100; // BE: MAX_PAGE_SIZE, kẹp 1–100; page âm = 0

/**
 * `PageRequest.of(max(page, 0), clamp(size, 1, 100))` rồi `PageResponse.of`. `totalPages` của Spring là ceil(total / size) nên danh sách
 * rỗng ra 0 trang (`envelope.page()` luôn ≥ 1). `page` / `size` không phải số nguyên → 400 MALFORMED_REQUEST (MethodArgumentTypeMismatch).
 */
function pagedOk<T>(all: T[], query: Record<string, string>): MockReply {
  for (const name of ['page', 'size'] as const) {
    if (query[name] !== undefined && !/^[+-]?\d+$/.test(query[name])) return fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
  }
  const pageNo = Math.max(Number(query.page ?? 0), 0);
  const size = Math.min(Math.max(Number(query.size ?? PAGE_SIZE_DEFAULT), 1), PAGE_SIZE_MAX);
  return ok({
    content: all.slice(pageNo * size, (pageNo + 1) * size),
    page: pageNo,
    size,
    totalElements: all.length,
    totalPages: Math.ceil(all.length / size),
  });
}

// ─── Hiện diện (BR-TN-06) ───

// BE: `online` = `last_seen_at` trong `staff.online_window_minutes`. Mock không theo dõi thao tác nên mặc định ai cũng online;
// `setStaffOnline` đặt riêng một người offline (kho riêng, không đụng state danh tính) để dựng cảnh báo gán người offline (T36).
const PRESENCE_KEY = 'petcare-mock-presence';

function loadPresence(): { offline: number[] } {
  try {
    const raw = JSON.parse(localStorage.getItem(PRESENCE_KEY) ?? '{}');
    return { offline: Array.isArray(raw.offline) ? raw.offline : [] };
  } catch {
    return { offline: [] };
  }
}

export function setStaffOnline(accountId: number, online: boolean): void {
  const offline = loadPresence().offline.filter((id) => id !== accountId);
  if (!online) offline.push(accountId);
  try {
    localStorage.setItem(PRESENCE_KEY, JSON.stringify({ offline }));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Đọc chéo cho mock tiếp nhận (như StaffDirectoryApi của BE, T36) ───

/** `StaffDirectoryApi.StaffSummary`: `active` = ACTIVE và không bị khóa; tìm được cả nhân viên tạo lúc chạy. */
export interface StaffSummary {
  accountId: number;
  fullName: string;
  role: Role;
  branchId: number | null;
  active: boolean;
  online: boolean;
}

function summaryOf(s: ReturnType<typeof load>, a: MockStaffWithState): StaffSummary {
  const active = a.status === 'ACTIVE' && !s.locks[a.id];
  return { accountId: a.id, fullName: a.fullName, role: a.role, branchId: a.branchId ?? null, active, online: staffResponseOf(s, a).online };
}

export function staffSummaryOf(accountId: number): StaffSummary | null {
  const s = load();
  const a = findStaffAccount(s, accountId);
  return a ? summaryOf(s, a) : null;
}

/** `findAssignableStaff`: nhân viên active của chi nhánh theo chức vụ, người online xếp trước. */
export function assignableStaffOf(branchId: number, role: Role): StaffSummary[] {
  const s = load();
  return allStaff(s)
    .filter((a) => a.role === role && a.branchId === branchId)
    .map((a) => summaryOf(s, a))
    .filter((x) => x.active)
    .sort((a, b) => Number(b.online) - Number(a.online) || a.accountId - b.accountId);
}

/** `findActiveStaffIds(branchId, BRANCH_MANAGER)`: chi nhánh còn quản lý ACTIVE không bị khóa nào không (BR-TN-08). */
export const hasActiveBranchManager = (branchId: number): boolean => countActiveBranchManagers(load(), branchId) > 0;

// ─── Staff (UC08) ───

/**
 * ASSUMPTION (T36, chờ BE-1): lễ tân chọn người gán lượt (UC45) nhưng identity-v1 #15 loại RECEPTIONIST. Mock mở đúng phạm vi nhỏ nhất:
 * VET / CARETAKER ACTIVE không bị khóa của chi nhánh mình. Lọc ngoài phạm vi (role, status, branchId) → 403. BE thật hiện trả 403 cho cả lời gọi hợp lệ.
 */
function listAssignableForReceptionist(s: ReturnType<typeof load>, account: { id: number; branchId?: number | null }, query: Record<string, string>) {
  const branchId = account.branchId ?? MOCK_STAFF[account.id]?.branchId;
  if (query.role && query.role !== 'VET' && query.role !== 'CARETAKER') return denied();
  if (query.status && query.status !== 'ACTIVE') return denied();
  if (query.branchId && Number(query.branchId) !== branchId) return denied();
  const q = (query.q ?? '').trim().toLowerCase();
  const staff = allStaff(s)
    .filter((a) => (a.role === 'VET' || a.role === 'CARETAKER') && a.branchId === branchId && a.status === 'ACTIVE' && !s.locks[a.id])
    .filter((a) => !query.role || a.role === query.role)
    .filter((a) => !q || a.email.toLowerCase().includes(q) || a.fullName.toLowerCase().includes(q));
  return ok(page(staff.map((a) => staffResponseOf(s, a))));
}

// BE `StaffQueryService.visibleRoles`: ADMIN chỉ thấy SUPER_MANAGER; SUPER_MANAGER thấy A05–A08; BRANCH_MANAGER thấy A06–A08.
// Không ai thấy chính mình. Lọc `role` ngoài tập này → trang rỗng (không lỗi).
const VISIBLE_STAFF_ROLES: Partial<Record<Role, Role[]>> = {
  ADMIN: ['SUPER_MANAGER'],
  SUPER_MANAGER: ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
  BRANCH_MANAGER: ['RECEPTIONIST', 'VET', 'CARETAKER'],
};

const listStaff: MockHandler = ({ account, query }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const s = load();
  // BE-1 chưa mở GET /api/staff cho lễ tân: BE thật 403 (STAFF_ADMINS), mock mở phạm vi nhỏ để dựng luồng gán lượt (T36 A.7).
  if (account!.role === 'RECEPTIONIST') return listAssignableForReceptionist(s, account!, query);
  const visible = VISIBLE_STAFF_ROLES[account!.role];
  if (!visible) return denied(); // VET, CARETAKER: BE `@PreAuthorize(STAFF_ADMINS)`
  const q = (query.q ?? '').trim().toLowerCase();
  const roleFilter = query.role as Role | undefined;
  const statusFilter = query.status as AccountStatus | undefined;
  const branchFilter = query.branchId ? Number(query.branchId) : undefined;
  let staff = allStaff(s).filter((a) => visible.includes(a.role));
  if (account!.role === 'BRANCH_MANAGER') {
    const myBranch = findStaffAccount(s, account!.id)?.branchId;
    // BranchScope.resolve: `branchId` khác chi nhánh mình → 403 (không audit).
    if (branchFilter !== undefined && branchFilter !== myBranch) return fail('ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 403);
    staff = staff.filter((a) => a.branchId === myBranch);
  }
  // ADMIN gửi `branchId` → rỗng vì SUPER_MANAGER không có chi nhánh (tự ra từ bộ lọc dưới). `status` ngoài {ACTIVE, DISABLED} → rỗng.
  const filtered = staff.filter((a) => {
    if (q && !a.email.toLowerCase().includes(q) && !a.fullName.toLowerCase().includes(q)) return false;
    if (roleFilter && a.role !== roleFilter) return false;
    if (statusFilter && a.status !== statusFilter) return false;
    if (branchFilter && a.branchId !== branchFilter) return false;
    return true;
  });
  // BE: ORDER BY họ tên, accountId. ASSUMPTION: collation DB chưa rõ, mock so sánh theo tiếng Việt.
  filtered.sort((a, b) => a.fullName.localeCompare(b.fullName, 'vi') || a.id - b.id);
  return pagedOk(filtered.map((a) => staffResponseOf(s, a)), query);
};

const createStaff: MockHandler = ({ account, body }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const b = (body ?? {}) as Record<string, unknown>;
  const roleList: CreateableStaffRole[] = ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
  const bad = invalid([
    ['email', 'Email không được để trộn', !isText(b.email)],
    ['email', 'Email không đúng định dạng', isText(b.email) && !EMAIL_RE.test(String(b.email).trim())],
    ['phone', 'Số điện thoại phải có dạng 0xxxxxxxxx', !isText(b.phone) || !/^0\d{9}$/.test(String(b.phone))],
    ['fullName', 'Họ tên không được để trống', !isText(b.fullName)],
    ['fullName', 'Họ tên tối đa 100 ký tự', isText(b.fullName) && String(b.fullName).length > 100],
    ['role', 'Vai trò không hợp lệ', !isText(b.role) || !roleList.includes(b.role as CreateableStaffRole)],
    // BE `CreateStaffRequest.branchId` @Positive: gửi 0 / âm là 400 với MỌI vai trò (BRANCH_MANAGER không gửi = chi nhánh của mình).
    ['branchId', 'branchId phải là số dương', b.branchId != null && Number(b.branchId) <= 0],
  ]);
  if (bad) return bad;
  const newRole = b.role as CreateableStaffRole;
  // BR-QT-01: ADMIN chỉ tạo SUPER_MANAGER; SUPER_MANAGER tạo A05–A08; BRANCH_MANAGER chỉ A06–A08. Ngoài quyền: 403 + audit.
  const creatable: Partial<Record<Role, CreateableStaffRole[]>> = {
    ADMIN: ['SUPER_MANAGER'],
    SUPER_MANAGER: ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
    BRANCH_MANAGER: ['RECEPTIONIST', 'VET', 'CARETAKER'],
  };
  if (!creatable[account!.role]?.includes(newRole)) {
    const s0 = load();
    // BE ghi STAFF_ACTION_DENIED; với thao tác tạo thì không có đối tượng (StaffHierarchyPolicy: entity chỉ khi có targetAccountId).
    logAudit(s0, { action: 'STAFF_ACTION_DENIED', reason: 'BR-QT-01' }, account!.id, account!.email);
    save(s0);
    return denied();
  }
  let branchId: number | undefined;
  if (newRole === 'SUPER_MANAGER') {
    if (b.branchId != null) return ruleFail('BR-QT-03', 'SUPER_MANAGER không thuộc chi nhánh');
  } else if (account!.role === 'BRANCH_MANAGER') {
    branchId = MOCK_STAFF[account!.id]?.branchId;
  } else {
    if (b.branchId == null) return ruleFail('BR-QT-03', 'Thiếu chi nhánh');
    if (!isText(String(b.branchId)) || !/^\d+$/.test(String(b.branchId))) {
      return fail('MALFORMED_REQUEST', 'branchId: phải là số nguyên', 400);
    }
    branchId = Number(b.branchId);
  }
  const s = load();
  const email = normalize(String(b.email));
  if (Object.values(MOCK_STAFF).some((a) => a.email === email) || Object.values(s.extraStaff).some((a) => a.email === email)) {
    return ruleFail('BR-TK-01', 'Email đã được sử dụng');
  }
  const id = 200 + Object.keys(s.extraStaff).length;
  s.extraStaff[id] = {
    id,
    email,
    fullName: String(b.fullName).trim(),
    role: newRole,
    status: 'ACTIVE',
    branchId,
    mustChangePassword: true,
  };
  s.changes[id] = { password: MOCK_PASSWORD, mustChangePassword: true };
  logAudit(s, { action: 'STAFF_CREATED', entityType: 'accounts', entityId: id, afterData: { email, role: newRole, branchId } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, s.extraStaff[id]!), 201, 'Đã tạo tài khoản nhân viên, mật khẩu tạm đã gửi qua email');
};

const getStaffOne: MockHandler = ({ account, params }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const id = Number(params.id);
  if (!Number.isInteger(id) || id <= 0) return fail('MALFORMED_REQUEST', 'Mã nhân viên không hợp lệ', 400);
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (account!.role === 'BRANCH_MANAGER' && a.branchId !== MOCK_STAFF[account!.id]?.branchId) {
    return fail('ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 403);
  }
  return ok(staffResponseOf(s, a));
};

const changeRole: MockHandler = ({ account, params, body }) => {
  if (account!.role !== 'SUPER_MANAGER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const b = (body ?? {}) as Record<string, unknown>;
  const roleList: ChangeableStaffRole[] = ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
  if (!isText(b.role) || !roleList.includes(b.role as ChangeableStaffRole)) {
    return fail('VALIDATION_FAILED', 'role: Vai trò không hợp lệ', 400);
  }
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (a.role === (b.role as Role)) return ok(staffResponseOf(s, a));
  if (a.role === 'BRANCH_MANAGER') {
    if (countActiveBranchManagers(s, a.branchId ?? -1, a.id) < 1) {
      return ruleFail('BR-QT-04', 'Chi nhánh phải có ít nhất một quản lý đang hoạt động');
    }
  }
  const before = { role: a.role };
  a.role = b.role as Role;
  if (a.role !== 'VET') {
    s.profiles[a.id] = { ...s.profiles[a.id], specialty: null, bio: null };
  }
  logAudit(s, { action: 'STAFF_ROLE_CHANGED', entityType: 'accounts', entityId: id, beforeData: before, afterData: { role: a.role } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

const transfer: MockHandler = ({ account, params, body }) => {
  if (account!.role !== 'SUPER_MANAGER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const b = (body ?? {}) as Record<string, unknown>;
  if (!isText(String(b.branchId)) || !/^\d+$/.test(String(b.branchId))) {
    return fail('MALFORMED_REQUEST', 'branchId: phải là số nguyên', 400);
  }
  const branchId = Number(b.branchId);
  if (branchId <= 0) return fail('VALIDATION_FAILED', 'branchId: phải > 0', 400);
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (a.branchId === branchId) return ok(staffResponseOf(s, a));
  if (a.role === 'BRANCH_MANAGER' && countActiveBranchManagers(s, a.branchId ?? -1, a.id) < 1) {
    return ruleFail('BR-QT-04', 'Chi nhánh phải có ít nhất một quản lý đang hoạt động');
  }
  const before = { branchId: a.branchId };
  a.branchId = branchId;
  logAudit(s, { action: 'STAFF_TRANSFERRED', entityType: 'accounts', entityId: id, beforeData: before, afterData: { branchId } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

const disable: MockHandler = ({ account, params }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (account!.role === 'BRANCH_MANAGER' && a.branchId !== MOCK_STAFF[account!.id]?.branchId) {
    logAudit(s, { action: 'STAFF_ACTION_DENIED', entityType: 'accounts', entityId: id, reason: 'BR-QT-07' }, account!.id, account!.email);
    save(s);
    return denied();
  }
  if (s.locks[a.id]) return ruleFail('BR-QT-12', 'Đang bị khóa, cần mở khóa trước');
  if (a.status === 'DISABLED') return fail('INVALID_STATE_TRANSITION', 'Tài khoản đã vô hiệu hóa', 409);
  if (a.role === 'BRANCH_MANAGER' && countActiveBranchManagers(s, a.branchId ?? -1, a.id) < 1) {
    return ruleFail('BR-QT-04', 'Chi nhánh phải có ít nhất một quản lý đang hoạt động');
  }
  a.status = 'DISABLED';
  a.mustChangePassword = false;
  logAudit(s, { action: 'STAFF_DISABLED', entityType: 'accounts', entityId: id, beforeData: { status: 'ACTIVE' }, afterData: { status: 'DISABLED' } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

const reactivate: MockHandler = ({ account, params, body }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const b = (body ?? {}) as Record<string, unknown>;
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (s.locks[a.id]) return ruleFail('BR-QT-10', 'Đang bị khóa, cần mở khóa trước');
  if (a.status === 'ACTIVE') return fail('INVALID_STATE_TRANSITION', 'Tài khoản đang hoạt động', 409);
  if (account!.role === 'BRANCH_MANAGER' && a.branchId !== MOCK_STAFF[account!.id]?.branchId) {
    logAudit(s, { action: 'STAFF_ACTION_DENIED', entityType: 'accounts', entityId: id, reason: 'BR-QT-10' }, account!.id, account!.email);
    save(s);
    return denied();
  }
  if (a.role === 'SUPER_MANAGER' && b.branchId != null) return ruleFail('BR-QT-03', 'SUPER_MANAGER không thuộc chi nhánh');
  if (b.branchId != null && b.branchId !== '') {
    if (!isText(String(b.branchId)) || !/^\d+$/.test(String(b.branchId))) {
      return fail('MALFORMED_REQUEST', 'branchId: phải là số nguyên', 400);
    }
    const branchId = Number(b.branchId);
    if (branchId <= 0) return fail('VALIDATION_FAILED', 'branchId: phải > 0', 400);
    a.branchId = branchId;
  }
  a.status = 'ACTIVE';
  a.mustChangePassword = true;
  s.changes[a.id] = { ...s.changes[a.id], password: MOCK_PASSWORD, mustChangePassword: true };
  logAudit(s, { action: 'STAFF_REACTIVATED', entityType: 'accounts', entityId: id, beforeData: { status: 'DISABLED' }, afterData: { status: 'ACTIVE' } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

const resendInitialPassword: MockHandler = ({ account, params }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (a.status === 'DISABLED') return fail('INVALID_STATE_TRANSITION', 'Tài khoản đang DISABLED', 409);
  if (!a.mustChangePassword) {
    return fail('INVALID_STATE_TRANSITION', 'Nhân viên đã đổi mật khẩu lần đầu', 409);
  }
  s.changes[a.id] = { ...s.changes[a.id], password: MOCK_PASSWORD, mustChangePassword: true };
  logAudit(s, { action: 'STAFF_INITIAL_PASSWORD_RESENT', entityType: 'accounts', entityId: id }, account!.id, account!.email);
  save(s);
  return noContent();
};

const sendEmailChange: MockHandler = ({ account, params, body }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const b = (body ?? {}) as Record<string, unknown>;
  const newEmail = String(b.newEmail ?? '').trim().toLowerCase();
  const bad = invalid([
    ['newEmail', 'Email không được để trống', !isText(b.newEmail)],
    ['newEmail', 'Email không đúng định dạng', isText(b.newEmail) && !EMAIL_RE.test(newEmail)],
    ['newEmail', 'Email tối đa 255 ký tự', isText(b.newEmail) && newEmail.length > 255],
  ]);
  if (bad) return bad;
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  if (a.email === newEmail) return ruleFail('BR-TK-01', 'Email mới trùng email hiện tại');
  if (Object.values(MOCK_STAFF).some((x) => x.email === newEmail) || Object.values(s.extraStaff).some((x) => x.email === newEmail)) {
    return ruleFail('BR-TK-01', 'Email đã được sử dụng');
  }
  const change = s.emailChanges[id];
  const wait = waitSeconds(change?.otp.sentAt);
  if (wait > 0) return ruleFail('BR-TK-07', `Vui lòng chờ ${wait} giây trước khi gửi lại mã OTP`);
  s.emailChanges[id] = { newEmail, otp: { code: MOCK_OTP_CODE, sentAt: Date.now(), failed: 0 } };
  save(s);
  return ok({ resendAvailableAt: resendAt(s.emailChanges[id].otp.sentAt), maskedEmail: maskEmail(newEmail) }, 200, 'Đã gửi mã OTP tới email mới');
};

const confirmEmailChange: MockHandler = ({ account, params, body }) => {
  if (account!.role === 'CUSTOMER') return denied();
  const id = Number(params.id);
  if (id === account!.id) return denied();
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ['code', 'Mã OTP không được để trống', !isText(b.code)],
    ['code', 'Mã OTP chỉ gồm tối đa 8 chữ số', isText(b.code) && !/^\d{1,8}$/.test(String(b.code))],
  ]);
  if (bad) return bad;
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  const change = s.emailChanges[id];
  if (!change) return ruleFail('BR-TK-05', 'Không có mã đổi email đang mở');
  if (change.otp.cancelled) return ruleFail('BR-TK-05', 'Mã OTP không đúng hoặc đã hết hạn');
  if (change.otp.code !== b.code) {
    change.otp.failed += 1;
    if (change.otp.failed >= MAX_FAILED_OTP) {
      change.otp.cancelled = true;
      save(s);
      return ruleFail('BR-TK-06', `Nhập sai mã OTP quá ${MAX_FAILED_OTP} lần, mã đã bị hủy. Vui lòng yêu cầu mã mới`);
    }
    save(s);
    return ruleFail('BR-TK-05', 'Mã OTP không đúng hoặc đã hết hạn');
  }
  const before = { email: a.email };
  a.email = change.newEmail;
  delete s.emailChanges[id];
  logAudit(s, { action: 'STAFF_EMAIL_CHANGED', entityType: 'accounts', entityId: id, beforeData: before, afterData: { email: a.email } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

// ─── Accounts (UC09, ADMIN only) ───

const listAccounts: MockHandler = ({ account, query }) => {
  if (account!.role !== 'ADMIN') return denied();
  const s = load();
  // BE `AccountRepository.findAccountPage`: `q` chỉ tìm trong EMAIL (không có họ tên), ORDER BY id.
  const q = (query.q ?? '').trim().toLowerCase();
  const roleFilter = query.role as Role | undefined;
  const isLockedFilter = query.isLocked === 'true' ? true : query.isLocked === 'false' ? false : undefined;
  const all = [
    ...Object.values(MOCK_STAFF).map((a) => ({ ...a, isCustomer: false })),
    ...Object.values(s.extraStaff).map((a) => ({ ...a, isCustomer: false })),
    ...s.registered.map((r) => ({
      id: r.id,
      email: r.email,
      fullName: r.fullName,
      role: 'CUSTOMER' as const,
      status: (r.active ? 'ACTIVE' : 'PENDING') as AccountStatus,
      branchId: undefined,
      mustChangePassword: false,
      isCustomer: true,
    })),
  ];
  const filtered = all.filter((a) => {
    if (q && !a.email.toLowerCase().includes(q)) return false;
    if (roleFilter && a.role !== roleFilter) return false;
    if (isLockedFilter !== undefined && !!s.locks[a.id] !== isLockedFilter) return false;
    return true;
  });
  filtered.sort((a, b) => a.id - b.id);
  return pagedOk(
    filtered.map((a) => ({
      id: a.id,
      email: a.email,
      role: a.role,
      status: a.status,
      isLocked: !!s.locks[a.id],
      mustChangePassword: a.mustChangePassword,
    })),
    query,
  );
};

const lockAccount: MockHandler = ({ account, params, body }) => {
  if (account!.role !== 'ADMIN') return denied();
  const id = Number(params.id);
  if (id === account!.id) return ruleFail('BR-QT-11', 'Không thể khóa chính mình');
  const b = (body ?? {}) as Record<string, unknown>;
  if (!isText(b.reason) || String(b.reason).trim() === '') {
    return fail('VALIDATION_FAILED', 'reason: Lý do không được để trống', 400);
  }
  const s = load();
  const target = findStaffAccount(s, id) ?? s.registered.find((r) => r.id === id);
  if (!target) return notFound('tài khoản', id);
  if (s.locks[id]) return fail('INVALID_STATE_TRANSITION', 'Tài khoản đã bị khóa', 409);
  const role: Role = 'role' in target ? target.role : 'CUSTOMER';
  const status: AccountStatus = 'status' in target ? target.status : target.active ? 'ACTIVE' : 'PENDING';
  s.locks[id] = { reason: String(b.reason).trim(), statusBefore: status, lockedAt: new Date().toISOString() };
  logAudit(s, { action: 'ACCOUNT_LOCKED', entityType: 'accounts', entityId: id, reason: s.locks[id].reason }, account!.id, account!.email);
  save(s);
  const summary = {
    id,
    email: 'email' in target ? target.email : '',
    role,
    status,
    isLocked: true,
    mustChangePassword: 'mustChangePassword' in target ? target.mustChangePassword : false,
  };
  return ok({ account: summary as LockResult['account'], pendingWork: [] });
};

const unlockAccount: MockHandler = ({ account, params, body }) => {
  if (account!.role !== 'ADMIN') return denied();
  const id = Number(params.id);
  const b = (body ?? {}) as Record<string, unknown>;
  if (!isText(b.reason) || String(b.reason).trim() === '') {
    return fail('VALIDATION_FAILED', 'reason: Lý do không được để trống', 400);
  }
  const s = load();
  const target = findStaffAccount(s, id) ?? s.registered.find((r) => r.id === id);
  if (!target) return notFound('tài khoản', id);
  if (!s.locks[id]) return fail('INVALID_STATE_TRANSITION', 'Tài khoản không bị khóa', 409);
  const reason = String(b.reason).trim();
  delete s.locks[id];
  logAudit(s, { action: 'ACCOUNT_UNLOCKED', entityType: 'accounts', entityId: id, reason }, account!.id, account!.email);
  save(s);
  return ok({
    id,
    email: 'email' in target ? target.email : '',
    role: ('role' in target ? target.role : 'CUSTOMER') as Role,
    status: ('status' in target ? target.status : target.active ? 'ACTIVE' : 'PENDING') as AccountStatus,
    isLocked: false,
    mustChangePassword: 'mustChangePassword' in target ? target.mustChangePassword : false,
  });
};

// ─── Configuration (UC10, ADMIN only) ───

const listSystemConfigs: MockHandler = ({ account }) => {
  if (account!.role !== 'ADMIN') return denied();
  const s = load();
  return ok(SEED_CONFIGS.map((c) => ({ ...c, value: s.configOverrides[c.key] ?? c.value })));
};

const updateSystemConfig: MockHandler = ({ account, params, body }) => {
  if (account!.role !== 'ADMIN') return denied();
  const key = params.key;
  const seed = SEED_CONFIGS.find((c) => c.key === key);
  if (!seed) return notFound('tham số', key);
  const b = (body ?? {}) as Record<string, unknown>;
  if (!isText(b.value)) return fail('VALIDATION_FAILED', 'value: Không được để trống', 400);
  const value = String(b.value).trim();
  if (seed.valueType === 'INT' || seed.valueType === 'DECIMAL') {
    if (!/^-?\d+(\.\d+)?$/.test(value)) {
      return ruleFail('BR-QT-13', `Giá trị phải là ${seed.valueType === 'INT' ? 'số nguyên' : 'số thập phân'}`);
    }
    const num = Number(value);
    const min = seed.minValue != null ? Number(seed.minValue) : undefined;
    const max = seed.maxValue != null ? Number(seed.maxValue) : undefined;
    if ((min !== undefined && num < min) || (max !== undefined && num > max)) {
      return ruleFail('BR-QT-13', `Giá trị phải từ ${min} đến ${max}`);
    }
  } else if (seed.valueType === 'BOOL') {
    if (!['true', 'false'].includes(value)) return ruleFail('BR-QT-13', 'Giá trị phải là true hoặc false');
  }
  const s = load();
  const before = { value: s.configOverrides[key] ?? seed.value };
  s.configOverrides[key] = value;
  logAudit(s, { action: 'SYSTEM_CONFIG_UPDATED', entityType: 'system_configs', beforeData: before, afterData: { value } }, account!.id, account!.email);
  save(s);
  return ok({ ...seed, value });
};

// ─── Templates (UC10, ADMIN only) ───

const listNotificationTemplates: MockHandler = ({ account }) => {
  if (account!.role !== 'ADMIN') return denied();
  const s = load();
  return ok(
    SEED_TEMPLATES.map((t) => {
      const override = s.templateOverrides[t.code];
      return override
        ? { ...t, subject: override.subject !== undefined ? override.subject : t.subject, body: override.body, isDefault: false }
        : t;
    }),
  );
};

const updateNotificationTemplate: MockHandler = ({ account, params, body }) => {
  if (account!.role !== 'ADMIN') return denied();
  const code = params.code;
  const seed = SEED_TEMPLATES.find((t) => t.code === code);
  if (!seed) return notFound('mẫu thông báo', code);
  const b = (body ?? {}) as Record<string, unknown>;
  // identity-v1 #34 (BE UpdateTemplateRequest): tiêu đề bắt buộc với MỌI kênh, tối đa 255.
  const badForm = invalid([
    ['subject', 'Tiêu đề không được để trống', !isText(b.subject)],
    ['subject', 'Tiêu đề tối đa 255 ký tự', isText(b.subject) && String(b.subject).length > 255],
    ['body', 'Nội dung không được để trống', !isText(b.body)],
  ]);
  if (badForm) return badForm;
  const bodyStr = String(b.body);
  const subj = String(b.subject);
  const extract = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
  const usedVars = new Set(extract(bodyStr));
  extract(subj).forEach((v) => usedVars.add(v));
  const unknown = [...usedVars].filter((v) => !seed.allowedVars.includes(v));
  if (unknown.length) return ruleFail('BR-QT-14', `Biến không tồn tại: ${unknown.join(', ')}`);
  const missing = seed.requiredVars.filter((v) => !usedVars.has(v));
  if (missing.length) return ruleFail('BR-QT-14', `Thiếu biến bắt buộc: ${missing.join(', ')}`);
  const s = load();
  s.templateOverrides[code] = { subject: subj, body: bodyStr };
  logAudit(s, { action: 'NOTIFICATION_TEMPLATE_UPDATED', entityType: 'notification_templates', afterData: { code, body: bodyStr } }, account!.id, account!.email);
  save(s);
  return ok({ ...seed, subject: subj, body: bodyStr, isDefault: false });
};

const restoreTemplateDefault: MockHandler = ({ account, params }) => {
  if (account!.role !== 'ADMIN') return denied();
  const code = params.code;
  const seed = SEED_TEMPLATES.find((t) => t.code === code);
  if (!seed) return notFound('mẫu thông báo', code);
  const s = load();
  delete s.templateOverrides[code];
  logAudit(s, { action: 'NOTIFICATION_TEMPLATE_RESTORED', entityType: 'notification_templates', afterData: { code } }, account!.id, account!.email);
  save(s);
  return ok(seed);
};

// ─── Audit (UC11, ADMIN only) ───

const AUDIT_ACTION_WINDOW_MS = 31 * 24 * 60 * 60 * 1000; // AuditLogFilter.MAX_ACTION_WINDOW

const listAuditLogs: MockHandler = ({ account, query }) => {
  if (account!.role !== 'ADMIN') return denied();
  // identity-v1 #36: `from` / `to` là date-time kèm offset; thiếu offset → 400 MALFORMED_REQUEST.
  for (const key of ['from', 'to'] as const) {
    const v = query[key];
    if (v && !/(Z|[+-]\d{2}:?\d{2})$/i.test(v)) return fail('MALFORMED_REQUEST', `${key}: phải kèm múi giờ (offset), vd 2026-10-23T08:00:00+07:00`, 400);
  }
  const s = load();
  const from = query.from ? new Date(query.from).getTime() : -Infinity;
  const to = query.to ? new Date(query.to).getTime() : Infinity;
  // `action` / `entityType` toàn khoảng trắng = không lọc; còn lại giữ nguyên văn (AuditLogFilter.blankToNull).
  const action = query.action?.trim() ? query.action : undefined;
  const entityType = query.entityType?.trim() ? query.entityType : undefined;
  // AuditLogFilterValidator: R1 from > to; R2 entityId không kèm entityType; R3 lọc action mà không có người thực hiện / (loại + id đối tượng)
  // thì phải có from + to cách nhau ≤ 31 ngày (không có index trên action). Báo đủ cả ba, theo thứ tự to → entityType → from.
  const anchored = !!query.actorAccountId || (!!entityType && !!query.entityId);
  const bounded = !!query.from && !!query.to && to - from <= AUDIT_ACTION_WINDOW_MS;
  const violations = [
    ...(query.from && query.to && from > to ? ['to: Thời điểm kết thúc không được trước thời điểm bắt đầu'] : []),
    ...(query.entityId && !entityType ? ['entityType: Bắt buộc khi lọc theo entityId'] : []),
    ...(action && !anchored && !bounded
      ? ['from: Lọc theo hành động cần from và to cách nhau tối đa 31 ngày, hoặc kèm actorAccountId hay entityType + entityId']
      : []),
  ];
  if (violations.length) return fail('VALIDATION_FAILED', violations.join('; '), 400);
  const filtered = s.audit.filter((a) => {
    const ts = new Date(a.createdAt).getTime();
    if (ts < from || ts > to) return false;
    if (query.actorAccountId && a.actorAccountId !== Number(query.actorAccountId)) return false;
    if (action && a.action !== action) return false;
    if (entityType && a.entityType !== entityType) return false;
    if (query.entityId && a.entityId !== Number(query.entityId)) return false;
    return true;
  });
  // BE: ORDER BY created_at DESC, id DESC.
  filtered.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.id - a.id);
  return pagedOk(filtered, query);
};

export const adminIdentityRoutes: Record<string, MockHandler> = {
  'GET /api/staff': listStaff,
  'POST /api/staff': createStaff,
  'GET /api/staff/:id': getStaffOne,
  'POST /api/staff/:id/change-role': changeRole,
  'POST /api/staff/:id/transfer': transfer,
  'POST /api/staff/:id/disable': disable,
  'POST /api/staff/:id/reactivate': reactivate,
  'POST /api/staff/:id/resend-initial-password': resendInitialPassword,
  'POST /api/staff/:id/email-change': sendEmailChange,
  'POST /api/staff/:id/email-change/confirm': confirmEmailChange,
  'GET /api/accounts': listAccounts,
  'POST /api/accounts/:id/lock': lockAccount,
  'POST /api/accounts/:id/unlock': unlockAccount,
  'GET /api/system-configs': listSystemConfigs,
  'PUT /api/system-configs/:key': updateSystemConfig,
  'GET /api/notification-templates': listNotificationTemplates,
  'PUT /api/notification-templates/:code': updateNotificationTemplate,
  'POST /api/notification-templates/:code/restore-default': restoreTemplateDefault,
  'GET /api/audit-logs': listAuditLogs,
};

// Tránh unused import warning — dùng trong test/debug
export const __debug = { branchIdOf, RESEND_SECONDS: 60, MOCK_OTP_CODE };
