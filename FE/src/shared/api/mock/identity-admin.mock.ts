// Quản trị (UC08–11) — mock handlers viết riêng để file identity.mock.ts không quá dài.
import type { AccountStatus, AccountSummary, Role } from '../../types/auth';
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
import { SEED_CONFIGS, SEED_TEMPLATES } from './identity-admin.seed';
import {
  fail,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  ruleFail,
  scopeDenied,
  type MockHandler,
  type MockReply,
} from './envelope';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const RESEND_SECONDS = 60;
const MAX_FAILED_OTP = 5;

const normalize = (email: string) => email.trim().toLowerCase();
const resendAt = (sentAt: number) => new Date(sentAt + RESEND_SECONDS * 1000).toISOString();
const waitSeconds = (sentAt?: number) =>
  sentAt ? Math.ceil((sentAt + RESEND_SECONDS * 1000 - Date.now()) / 1000) : 0;

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

// Seed nhân viên (mọi vai trừ CUSTOMER) lấy từ MOCK_ACCOUNTS, định dạng phẳng hơn: 1=ADMIN, 2=SUPER_MANAGER, 3 và 9=BRANCH_MANAGER (chi nhánh 1, 2),
// 4 và 8=RECEPTIONIST, 5 và 21–23=VET, 6=CARETAKER. Id 7 và 10 là KHÁCH nên không có ở đây (route nhân viên trả 404 cho hai id đó); 200+ là nhân viên tạo lúc chạy (`extraStaff`).
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

// BE: `online` = có phiên chưa hủy với access token còn hạn (`sessions.access_expires_at > now`, BR-TN-06, ADR-0091); từ V27 không còn `last_seen_at` hay
// cửa sổ `staff.online_window_minutes`. Mock không theo dõi phiên nên mặc định ai cũng online;
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
 * identity-v1 #38 `GET /api/staff/assignable` (ADR-0089): người gán lượt cho lễ tân (gán) và quản lý (gán lại), mảng không phân trang, KHÔNG mở `GET /api/staff`.
 * Vai gọi: RECEPTIONIST, BRANCH_MANAGER, SUPER_MANAGER; vai khác 403 ACCESS_DENIED. `role` bắt buộc VET | CARETAKER (400 MALFORMED_REQUEST), `branchId` tùy chọn là số dương.
 * Lễ tân / quản lý chi nhánh: bỏ `branchId` = chi nhánh của mình, chi nhánh khác 403 ACCESS_DENIED_SCOPE_MISMATCH. SUPER_MANAGER (cấp chuỗi): bắt buộc `branchId`
 * (thiếu 400 VALIDATION_FAILED), chi nhánh không có nhân viên nào thì mảng rỗng. Chỉ ACTIVE, chưa khóa, đúng chi nhánh và chức vụ; online trước, rồi họ tên, rồi accountId.
 * Thứ tự lỗi theo BE (đã dò): tham số sai 400 TRƯỚC cổng vai 403 (StaffController#listAssignableStaff: "Tham số sai là 400 trước @PreAuthorize";
 * AssignableStaffIT#badParametersAre400BeforeTheRoleCheckAndValidOnesAre403, #anAuthorizedCallerWithBadParametersGets400) → 403 ACCESS_DENIED → 403 phạm vi → 400 thiếu chi nhánh.
 * Tham số: `role` thiếu → MALFORMED_REQUEST "Thiếu tham số 'role'", ngoài VET / CARETAKER (phân biệt hoa thường) → MALFORMED_REQUEST "Tham số 'role' sai kiểu dữ liệu"; `branchId` sai kiểu →
 * MALFORMED_REQUEST, ≤ 0 → VALIDATION_FAILED "branchId: branchId phải là số dương" (@Positive; GlobalExceptionHandler). `role` được giải trước `branchId`, và @Positive chạy sau khi cả hai đã giải xong.
 */
const listAssignableStaff: MockHandler = ({ account, query }) => {
  const caller = account!;
  const role = query.role;
  if (role === undefined) return fail('MALFORMED_REQUEST', "Thiếu tham số 'role'", 400);
  if (role !== 'VET' && role !== 'CARETAKER') return fail('MALFORMED_REQUEST', "Tham số 'role' sai kiểu dữ liệu", 400);
  if (query.branchId !== undefined && !/^[+-]?\d+$/.test(query.branchId)) return fail('MALFORMED_REQUEST', "Tham số 'branchId' sai kiểu dữ liệu", 400);
  const asked = query.branchId === undefined ? undefined : Number(query.branchId);
  if (asked !== undefined && asked <= 0) return fail('VALIDATION_FAILED', 'branchId: branchId phải là số dương', 400);
  if (caller.role !== 'RECEPTIONIST' && caller.role !== 'BRANCH_MANAGER' && caller.role !== 'SUPER_MANAGER') return denied();
  const s = load();
  let branchId: number;
  if (caller.role === 'SUPER_MANAGER') {
    if (asked === undefined) return fail('VALIDATION_FAILED', 'branchId: Vui lòng chọn chi nhánh', 400);
    branchId = asked;
  } else {
    const mine = findStaffAccount(s, caller.id)?.branchId;
    if (asked !== undefined && asked !== mine) return fail('ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 403);
    branchId = mine!;
  }
  const rows = allStaff(s)
    .filter((a) => a.role === role && a.branchId === branchId && a.status === 'ACTIVE' && !s.locks[a.id])
    .map((a) => ({ accountId: a.id, fullName: a.fullName, role, online: staffResponseOf(s, a).online }));
  rows.sort((a, b) => Number(b.online) - Number(a.online) || a.fullName.localeCompare(b.fullName, 'vi') || a.accountId - b.accountId);
  return ok(rows);
};

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
  const visible = VISIBLE_STAFF_ROLES[account!.role];
  if (!visible) return denied(); // RECEPTIONIST, VET, CARETAKER: BE `@PreAuthorize(STAFF_ADMINS)`; lễ tân chọn người gán qua `GET /api/staff/assignable` (ADR-0089)
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
  if (!VISIBLE_STAFF_ROLES[account!.role]) return denied(); // như `GET /api/staff`: chỉ ADMIN, SUPER_MANAGER, BRANCH_MANAGER (BE `STAFF_ADMINS`); lễ tân 403
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

// ─── Cổng vai + phân cấp cho 5 route thao tác lên MỘT nhân viên khác: disable, reactivate, resend-initial-password, email-change, email-change/confirm ───
// Thứ tự BE (ADR-0029): validation body 400 → cổng vai 403 → đích không có / là khách 404 → phân cấp 403 (ghi audit) → các guard BR / trạng thái.

type StaffAction = 'DISABLE' | 'REACTIVATE' | 'RESEND_INITIAL_PASSWORD' | 'CHANGE_EMAIL';

/** BE `@PreAuthorize(STAFF_ADMINS)`: ADMIN | SUPER_MANAGER | BRANCH_MANAGER; vai khác (kể cả lễ tân, bác sĩ, chăm sóc, khách) 403 ACCESS_DENIED, không audit. */
const isStaffAdmin = (role: Role) => VISIBLE_STAFF_ROLES[role] !== undefined;

/**
 * BE `StaffHierarchyPolicy.allows`: không ai tác động lên chính mình; `manages` = tập vai nhìn thấy của người gọi (ADMIN: SUPER_MANAGER; SUPER_MANAGER: A05–A08 mọi chi nhánh;
 * BRANCH_MANAGER: A06–A08 VÀ chi nhánh người gọi ≠ null VÀ bằng chi nhánh đích, không quản BRANCH_MANAGER khác kể cả cùng chi nhánh). REACTIVATE thêm: BRANCH_MANAGER gửi
 * `branchId` khác chi nhánh mình cũng bị từ chối. Từ chối: 403 ACCESS_DENIED_SCOPE_MISMATCH (message chung, không lộ lý do) + audit STAFF_ACTION_DENIED (BE reason = null).
 * Chi nhánh người gọi tra theo state hiện tại (đích / người gọi có thể vừa được điều chuyển), chỉ rơi về chi nhánh trong token khi không tìm thấy.
 */
function hierarchyDenied(
  s: ReturnType<typeof load>,
  caller: AccountSummary,
  target: MockStaffWithState,
  action: StaffAction,
  requestedBranchId: number | null = null,
): MockReply | null {
  const callerBranch = findStaffAccount(s, caller.id)?.branchId ?? caller.branchId ?? null;
  const targetBranch = target.branchId ?? null;
  const manages =
    VISIBLE_STAFF_ROLES[caller.role]?.includes(target.role) === true &&
    (caller.role !== 'BRANCH_MANAGER' || (callerBranch !== null && callerBranch === targetBranch));
  const branchOk = action !== 'REACTIVATE' || caller.role !== 'BRANCH_MANAGER' || requestedBranchId === null || requestedBranchId === callerBranch;
  if (target.id !== caller.id && manages && branchOk) return null;
  logAudit(
    s,
    {
      action: 'STAFF_ACTION_DENIED',
      entityType: 'accounts',
      entityId: target.id,
      afterData: { action, targetRole: target.role, targetBranchId: targetBranch, requestedRole: null, requestedBranchId },
    },
    caller.id,
    caller.email,
  );
  save(s);
  return scopeDenied();
}

const disable: MockHandler = ({ account, params }) => {
  if (!isStaffAdmin(account!.role)) return denied();
  const id = Number(params.id);
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  const refused = hierarchyDenied(s, account!, a, 'DISABLE');
  if (refused) return refused;
  // Message chép từ BE (StaffAccountService.disableStaff; 409 là InvalidStateTransitionException "Không thể chuyển trạng thái {fsm} từ {from} sang {to}").
  if (s.locks[a.id]) return ruleFail('BR-QT-12', 'Tài khoản đang bị khóa, cần mở khóa trước khi vô hiệu hóa');
  if (a.status === 'DISABLED') return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái tài khoản từ DISABLED sang DISABLED', 409);
  if (a.role === 'BRANCH_MANAGER' && countActiveBranchManagers(s, a.branchId ?? -1, a.id) < 1) {
    return ruleFail('BR-QT-04', 'Không vô hiệu hóa được: đây là quản lý cuối cùng của chi nhánh đang hoạt động');
  }
  a.status = 'DISABLED'; // BE Account.disable() chỉ đặt status; mustChangePassword giữ nguyên
  logAudit(s, { action: 'STAFF_DISABLED', entityType: 'accounts', entityId: id, beforeData: { status: 'ACTIVE' }, afterData: { status: 'DISABLED' } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

// BE thứ tự: validation body (branchId sai kiểu / ≤ 0, chạy trước @PreAuthorize) → cổng vai → 404 → phân cấp (gồm BRANCH_MANAGER gửi chi nhánh khác mình) → BR-QT-10 đang bị khóa → BR-QT-03 → 409 đang ACTIVE.
const reactivate: MockHandler = ({ account, params, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const hasBranch = b.branchId != null && b.branchId !== '';
  if (hasBranch) {
    if (!isText(String(b.branchId)) || !/^\d+$/.test(String(b.branchId))) {
      return fail('MALFORMED_REQUEST', 'branchId: phải là số nguyên', 400);
    }
    if (Number(b.branchId) <= 0) return fail('VALIDATION_FAILED', 'branchId: phải > 0', 400);
  }
  const requestedBranchId = hasBranch ? Number(b.branchId) : null;
  if (!isStaffAdmin(account!.role)) return denied();
  const id = Number(params.id);
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  const refused = hierarchyDenied(s, account!, a, 'REACTIVATE', requestedBranchId);
  if (refused) return refused;
  if (s.locks[a.id]) return ruleFail('BR-QT-10', 'Tài khoản đang bị khóa, cần mở khóa trước');
  if (a.role === 'SUPER_MANAGER' && b.branchId != null) return ruleFail('BR-QT-03', 'Quản lý cấp chuỗi không thuộc chi nhánh nào');
  if (a.status === 'ACTIVE') return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái tài khoản từ ACTIVE sang ACTIVE', 409);
  if (requestedBranchId !== null) a.branchId = requestedBranchId;
  a.status = 'ACTIVE';
  a.mustChangePassword = true;
  s.changes[a.id] = { ...s.changes[a.id], password: MOCK_PASSWORD, mustChangePassword: true };
  logAudit(s, { action: 'STAFF_REACTIVATED', entityType: 'accounts', entityId: id, beforeData: { status: 'DISABLED' }, afterData: { status: 'ACTIVE' } }, account!.id, account!.email);
  save(s);
  return ok(staffResponseOf(s, a));
};

const resendInitialPassword: MockHandler = ({ account, params }) => {
  if (!isStaffAdmin(account!.role)) return denied();
  const id = Number(params.id);
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  const refused = hierarchyDenied(s, account!, a, 'RESEND_INITIAL_PASSWORD');
  if (refused) return refused;
  if (a.status !== 'ACTIVE') {
    return fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái tài khoản nhân viên từ ${a.status} sang gửi lại mật khẩu ban đầu`, 409);
  }
  if (!a.mustChangePassword) {
    return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái tài khoản nhân viên từ đã đổi mật khẩu lần đầu sang gửi lại mật khẩu ban đầu', 409);
  }
  s.changes[a.id] = { ...s.changes[a.id], password: MOCK_PASSWORD, mustChangePassword: true };
  logAudit(s, { action: 'STAFF_INITIAL_PASSWORD_RESENT', entityType: 'accounts', entityId: id }, account!.id, account!.email);
  save(s);
  return noContent();
};

const sendEmailChange: MockHandler = ({ account, params, body }) => {
  const id = Number(params.id);
  const b = (body ?? {}) as Record<string, unknown>;
  const newEmail = String(b.newEmail ?? '').trim().toLowerCase();
  const bad = invalid([
    ['newEmail', 'Email không được để trống', !isText(b.newEmail)],
    ['newEmail', 'Email không đúng định dạng', isText(b.newEmail) && !EMAIL_RE.test(newEmail)],
    ['newEmail', 'Email tối đa 255 ký tự', isText(b.newEmail) && newEmail.length > 255],
  ]);
  if (bad) return bad;
  if (!isStaffAdmin(account!.role)) return denied();
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  const refused = hierarchyDenied(s, account!, a, 'CHANGE_EMAIL');
  if (refused) return refused;
  if (a.email === newEmail) return ruleFail('BR-TK-01', 'Email mới trùng email hiện tại');
  if (Object.values(MOCK_STAFF).some((x) => x.email === newEmail) || Object.values(s.extraStaff).some((x) => x.email === newEmail)) {
    return ruleFail('BR-TK-01', 'Email đã được sử dụng');
  }
  const change = s.emailChanges[id];
  const wait = waitSeconds(change?.otp.sentAt);
  if (wait > 0) return ruleFail('BR-TK-07', `Vui lòng chờ ${wait} giây trước khi gửi lại mã OTP`);
  s.emailChanges[id] = { newEmail, otp: { code: MOCK_OTP_CODE, sentAt: Date.now(), failed: 0 } };
  save(s);
  // BE StaffAdminMapper: OTP của nhân viên luôn `maskedEmail: null` (chỉ luồng liên kết hồ sơ mới trả email đã che), kèm `codeLength` (độ dài mã vừa gửi; mock luôn 6).
  return ok({ resendAvailableAt: resendAt(s.emailChanges[id].otp.sentAt), maskedEmail: null, codeLength: MOCK_OTP_CODE.length }, 200, 'Đã gửi mã OTP tới email mới');
};

const confirmEmailChange: MockHandler = ({ account, params, body }) => {
  const id = Number(params.id);
  const b = (body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ['code', 'Mã OTP không được để trống', !isText(b.code)],
    ['code', 'Mã OTP chỉ gồm tối đa 8 chữ số', isText(b.code) && !/^\d{1,8}$/.test(String(b.code))],
  ]);
  if (bad) return bad;
  if (!isStaffAdmin(account!.role)) return denied();
  const s = load();
  const a = findStaffAccount(s, id);
  if (!a) return notFound('nhân viên', id);
  // Phân cấp kiểm TRƯỚC khi tra mã (BE findEmailChangeCode): người ngoài phân cấp không đốt bộ đếm OTP và không biết nhân viên có mã đang mở hay không.
  const refused = hierarchyDenied(s, account!, a, 'CHANGE_EMAIL');
  if (refused) return refused;
  // BE OtpService.INVALID_OTP_MESSAGE: không có mã mở, mã bị hủy và mã sai đều là MỘT câu (không lộ nhân viên có mã đang mở hay không).
  const change = s.emailChanges[id];
  if (!change) return ruleFail('BR-TK-05', 'OTP không đúng hoặc đã hết hạn');
  if (change.otp.cancelled) return ruleFail('BR-TK-05', 'OTP không đúng hoặc đã hết hạn');
  if (change.otp.code !== b.code) {
    change.otp.failed += 1;
    if (change.otp.failed >= MAX_FAILED_OTP) {
      change.otp.cancelled = true;
      save(s);
      return ruleFail('BR-TK-06', `Nhập sai mã OTP quá ${MAX_FAILED_OTP} lần, mã đã bị hủy. Vui lòng yêu cầu mã mới`);
    }
    save(s);
    return ruleFail('BR-TK-05', 'OTP không đúng hoặc đã hết hạn');
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

// ─── Configuration (UC10, ADMIN only; BE SystemConfigEditService, BR-QT-13) ───

/**
 * Số so sánh được của giá trị `raw` theo kiểu (BE `ConfigValues.parseRequest`): INT như `Integer.valueOf` (dấu +/- tùy chọn, vừa int32), DECIMAL không dạng mũ,
 * TIME chỉ `HH:mm` hợp lệ (đổi ra số phút trong ngày), BOOL chỉ `true` / `false` (BE không đặt khoảng cho BOOL). Sai kiểu thì null.
 */
function configNumber(type: SystemConfig['valueType'], raw: string): number | null {
  const n = Number(raw);
  switch (type) {
    case 'INT':
      return /^[+-]?\d+$/.test(raw) && n >= -2147483648 && n <= 2147483647 ? n : null;
    case 'DECIMAL':
      return /^[+-]?\d+(\.\d+)?$/.test(raw) ? n : null;
    case 'TIME': {
      const m = /^(\d{2}):(\d{2})$/.exec(raw);
      return m && Number(m[1]) < 24 && Number(m[2]) < 60 ? Number(m[1]) * 60 + Number(m[2]) : null;
    }
    case 'BOOL':
      return raw === 'true' ? 1 : raw === 'false' ? 0 : null;
  }
}

/** BE `ConfigConstraints.ALL` (ADR-0036, 0056), chỉ khóa INT: `lower` phải nhỏ hơn (`strict`) hoặc không lớn hơn `upper`. */
const CONFIG_CONSTRAINTS = [
  { lower: 'visit.late_priority_minutes', upper: 'appointment.no_show_after_minutes', strict: true },
  { lower: 'boarding.min_nights', upper: 'boarding.max_nights', strict: false },
  { lower: 'appointment.late_cancel_hours', upper: 'appointment.reminder_hours_before', strict: true },
];

/** BE `ConfigConstraints.narrow`: thu hẹp [min, max] tĩnh theo giá trị hiện hành của khóa cùng cặp; chỉ khi khoảng thật sự hẹp lại mới có lý do. */
function narrowConfigRange(key: string, range: { min?: number; max?: number }, current: (key: string) => number) {
  let { min, max } = range;
  const reasons: string[] = [];
  for (const c of CONFIG_CONSTRAINTS) {
    if (c.lower === key) {
      const upper = current(c.upper);
      const bound = c.strict ? upper - 1 : upper;
      if (max === undefined || bound < max) {
        max = bound;
        reasons.push(`${c.strict ? 'phải nhỏ hơn' : 'không được lớn hơn'} ${c.upper} đang là ${upper}`);
      }
    } else if (c.upper === key) {
      const lower = current(c.lower);
      const bound = c.strict ? lower + 1 : lower;
      if (min === undefined || bound > min) {
        min = bound;
        reasons.push(`${c.strict ? 'phải lớn hơn' : 'không được nhỏ hơn'} ${c.lower} đang là ${lower}`);
      }
    }
  }
  return { min, max, reasons };
}

const CONFIG_TYPE_PHRASE: Record<SystemConfig['valueType'], string> = {
  INT: 'số nguyên',
  DECIMAL: 'số thập phân',
  BOOL: 'true hoặc false',
  TIME: 'giờ dạng HH:mm',
};

/** BE `ConfigValues.describeAllowed`: khoảng chỉ ghi cho kiểu có khoảng; đơn vị chỉ ghi cho INT / DECIMAL; lý do của ràng buộc chéo nối sau dấu hai chấm. */
function describeAllowed(seed: SystemConfig, min: string | undefined, max: string | undefined, reasons: string[]): string {
  let text = `Giá trị của ${seed.key} phải là ${CONFIG_TYPE_PHRASE[seed.valueType]}`;
  if (seed.valueType !== 'BOOL') {
    if (min !== undefined && max !== undefined) text += ` từ ${min} đến ${max}`;
    else if (min !== undefined) text += ` từ ${min} trở lên`;
    else if (max !== undefined) text += ` không quá ${max}`;
  }
  if ((seed.valueType === 'INT' || seed.valueType === 'DECIMAL') && seed.unit?.trim()) text += ` (${seed.unit})`;
  return reasons.length ? `${text}: ${reasons.join('; ')}` : text;
}

const listSystemConfigs: MockHandler = ({ account }) => {
  if (account!.role !== 'ADMIN') return denied();
  const s = load();
  return ok(SEED_CONFIGS.map((c) => ({ ...c, value: s.configOverrides[c.key] ?? c.value })));
};

/**
 * identity-v1 #32. Thứ tự BE: hình thức (@Valid: không rỗng, ≤ 255) → quyền ADMIN → khóa lạ 404 → kiểu / min–max của chính dòng / ràng buộc chéo (400 BR-QT-13, message ghi
 * khoảng cho phép thực tế) → trùng giá trị hiện tại sau chuẩn hóa ("05" = "5") thì trả luôn, không ghi, không audit → ghi và audit {key, value} trước / sau.
 */
const updateSystemConfig: MockHandler = ({ account, params, body }) => {
  const sent = (body as { value?: unknown } | null)?.value;
  const text = typeof sent === 'number' || typeof sent === 'boolean' ? String(sent) : sent; // Jackson ép số / boolean JSON thành chuỗi
  const badForm = invalid([
    ['value', 'Vui lòng nhập giá trị', !isText(text)],
    ['value', 'Giá trị tối đa 255 ký tự', isText(text) && text.length > 255],
  ]);
  if (badForm) return badForm;
  if (account!.role !== 'ADMIN') return denied();
  const key = params.key;
  const seed = SEED_CONFIGS.find((c) => c.key === key);
  if (!seed) return notFound('tham số', key);
  const s = load();
  const currentOf = (k: string) => s.configOverrides[k] ?? SEED_CONFIGS.find((c) => c.key === k)!.value;
  const raw = String(text).trim();
  const type = seed.valueType;
  let min = seed.minValue != null ? (configNumber(type, seed.minValue) ?? undefined) : undefined;
  let max = seed.maxValue != null ? (configNumber(type, seed.maxValue) ?? undefined) : undefined;
  let minText = seed.minValue ?? undefined;
  let maxText = seed.maxValue ?? undefined;
  let reasons: string[] = [];
  if (CONFIG_CONSTRAINTS.some((c) => c.lower === key || c.upper === key)) {
    const narrowed = narrowConfigRange(key, { min, max }, (k) => Number(currentOf(k)));
    if (narrowed.min !== min) minText = String(narrowed.min);
    if (narrowed.max !== max) maxText = String(narrowed.max);
    ({ min, max, reasons } = narrowed);
  }
  const value = configNumber(type, raw);
  if (value === null || (min !== undefined && value < min) || (max !== undefined && value > max)) {
    return ruleFail('BR-QT-13', describeAllowed(seed, minText, maxText, reasons));
  }
  const canonical = type === 'INT' || type === 'DECIMAL' ? String(value) : raw; // "05" → "5"; TIME / BOOL đã đúng dạng
  const before = currentOf(key);
  if (canonical !== before) {
    s.configOverrides[key] = canonical;
    logAudit(s, { action: 'SYSTEM_CONFIG_UPDATED', entityType: 'system_configs', beforeData: { key, value: before }, afterData: { key, value: canonical } }, account!.id, account!.email);
    save(s);
  }
  return ok({ ...seed, value: canonical }, 200, 'Đã cập nhật tham số');
};

// ─── Templates (UC10, ADMIN only; BE NotificationTemplateAdminService, BR-QT-14) ───

/** Bản đang dùng của mẫu: bản đã sửa nếu có, không thì bản mặc định của seed. `isDefault` = tiêu đề VÀ nội dung trùng từng ký tự với bản mặc định (như BE). */
function templateView(s: ReturnType<typeof load>, seed: NotificationTemplate): NotificationTemplate {
  const edited = s.templateOverrides[seed.code];
  const subject = edited && edited.subject !== undefined ? edited.subject : seed.subject;
  const body = edited ? edited.body : seed.body;
  return { ...seed, subject, body, isDefault: subject === seed.subject && body === seed.body };
}

const templateSnapshot = (t: NotificationTemplate) => ({ code: t.code, subject: t.subject, body: t.body });

/**
 * BE `TemplateVariables.scan`: ngoặc nhọn chỉ dùng để viết `{ten}` với ten thuộc `allowed` (đúng chữ hoa / thường); mọi `{` hay `}` khác là lỗi.
 * `used`: tên hợp lệ có mặt; `unknown`: token `{…}` ngoài `allowed`, viết kèm ngoặc; `stray`: có `{` không đóng hoặc `}` không mở.
 */
function scanVariables(text: string, allowed: string[]) {
  const used = new Set<string>();
  const unknown = new Set<string>();
  let stray = false;
  for (let i = 0; i < text.length; ) {
    if (text[i] === '}') {
      stray = true;
      i++;
    } else if (text[i] === '{') {
      const offset = text.slice(i + 1).search(/[{}]/); // ký tự { hoặc } đầu tiên sau dấu mở
      if (offset < 0 || text[i + 1 + offset] === '{') {
        stray = true;
        i++;
      } else {
        const name = text.slice(i + 1, i + 1 + offset);
        if (allowed.includes(name)) used.add(name);
        else unknown.add(`{${name}}`);
        i += offset + 2;
      }
    } else {
      i++;
    }
  }
  return { used, unknown: [...unknown], stray };
}

const bracedNames = (names: string[]) => (names.length ? names.map((n) => `{${n}}`).join(', ') : '(không có)');

/** BE `checkVariables`: gom mọi lỗi vào MỘT message, nêu đủ tên biến; null nếu mẫu hợp lệ. Biến bắt buộc tính trên tiêu đề ∪ nội dung. */
function variableProblems(seed: NotificationTemplate, subject: string, body: string): string | null {
  const scans = [
    ['Tiêu đề', scanVariables(subject, seed.allowedVars)],
    ['Nội dung', scanVariables(body, seed.allowedVars)],
  ] as const;
  const used = new Set(scans.flatMap(([, scan]) => [...scan.used]));
  const missing = seed.requiredVars.filter((name) => !used.has(name));
  if (missing.length === 0 && scans.every(([, scan]) => scan.unknown.length === 0 && !scan.stray)) return null;
  const problems: string[] = [];
  for (const [part, scan] of scans) {
    if (scan.unknown.length) problems.push(`${part} dùng biến không có: ${scan.unknown.join(', ')}`);
    if (scan.stray) problems.push(`${part} có dấu { hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien}`);
  }
  if (scans.some(([, scan]) => scan.unknown.length)) problems.push(`Biến dùng được: ${bracedNames(seed.allowedVars)}`);
  if (missing.length) problems.push(`Mẫu thiếu biến bắt buộc: ${bracedNames(missing)}`);
  return problems.join('; ');
}

const listNotificationTemplates: MockHandler = ({ account }) => {
  if (account!.role !== 'ADMIN') return denied();
  const s = load();
  return ok(SEED_TEMPLATES.map((t) => templateView(s, t)));
};

/**
 * identity-v1 #34. Thứ tự BE: hình thức (tiêu đề / nội dung không rỗng, tiêu đề ≤ 255) → quyền ADMIN → mã lạ 404 → biến (400 BR-QT-14) → lưu nguyên văn và audit
 * {code, subject, body} trước / sau. Nội dung trùng bản mặc định thì mẫu là mặc định trở lại; nội dung trùng bản đang dùng vẫn audit (BR-QT-15 "mọi thao tác").
 */
const updateNotificationTemplate: MockHandler = ({ account, params, body }) => {
  const b = (body ?? {}) as Record<string, unknown>;
  const badForm = invalid([
    ['subject', 'Vui lòng nhập tiêu đề', !isText(b.subject)],
    ['subject', 'Tiêu đề tối đa 255 ký tự', isText(b.subject) && b.subject.length > 255],
    ['body', 'Vui lòng nhập nội dung', !isText(b.body)],
  ]);
  if (badForm) return badForm;
  if (account!.role !== 'ADMIN') return denied();
  const seed = SEED_TEMPLATES.find((t) => t.code === params.code);
  if (!seed) return notFound('mẫu thông báo', params.code);
  const subject = String(b.subject);
  const content = String(b.body);
  const problems = variableProblems(seed, subject, content);
  if (problems) return ruleFail('BR-QT-14', problems);
  const s = load();
  const before = templateView(s, seed);
  if (subject === seed.subject && content === seed.body) delete s.templateOverrides[seed.code];
  else s.templateOverrides[seed.code] = { subject, body: content };
  const after = templateView(s, seed);
  logAudit(s, { action: 'NOTIFICATION_TEMPLATE_UPDATED', entityType: 'notification_templates', beforeData: templateSnapshot(before), afterData: templateSnapshot(after) }, account!.id, account!.email);
  save(s);
  return ok(after, 200, 'Đã cập nhật mẫu thông báo');
};

/** identity-v1 #35: chép bản mặc định (`default_subject` / `default_body`) sang bản đang dùng; đã là mặc định thì 200, không đổi gì nhưng vẫn audit (before = after). */
const restoreTemplateDefault: MockHandler = ({ account, params }) => {
  if (account!.role !== 'ADMIN') return denied();
  const seed = SEED_TEMPLATES.find((t) => t.code === params.code);
  if (!seed) return notFound('mẫu thông báo', params.code);
  const s = load();
  const before = templateView(s, seed);
  delete s.templateOverrides[seed.code];
  const after = templateView(s, seed);
  logAudit(s, { action: 'NOTIFICATION_TEMPLATE_RESTORED', entityType: 'notification_templates', beforeData: templateSnapshot(before), afterData: templateSnapshot(after) }, account!.id, account!.email);
  save(s);
  return ok(after, 200, 'Đã khôi phục mẫu mặc định');
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
  // Khai báo TRƯỚC ':id': router khớp theo thứ tự khai báo và ':id' ăn cả chữ 'assignable'.
  'GET /api/staff/assignable': listAssignableStaff,
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
