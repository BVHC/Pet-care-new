// Mock module identity (docs/api/identity-v1.md): đăng nhập, đăng xuất, GET /me, đổi mật khẩu.
import type { AccountSummary, LoginResponse, Role } from '../../types/auth';
import { fail, noContent, ok, ruleFail, type MockHandler } from './envelope';

/** Mật khẩu chung của mọi tài khoản demo (BR-TK-03: ≥ 8 ký tự, có chữ và số). */
export const MOCK_PASSWORD = 'Petcare123';

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

// Thay đổi (mật khẩu mới, đã gỡ mustChangePassword) giữ trong localStorage để qua F5 vẫn còn.
const STATE_KEY = 'petcare-mock-identity';
type Override = { password?: string; mustChangePassword?: boolean };

function overrides(): Record<number, Override> {
  try {
    return JSON.parse(localStorage.getItem(STATE_KEY) ?? '{}');
  } catch {
    return {};
  }
}

function saveOverride(id: number, change: Override) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify({ ...overrides(), [id]: { ...overrides()[id], ...change } }));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

const passwordOf = (a: MockAccount) => overrides()[a.id]?.password ?? MOCK_PASSWORD;

function summaryOf(a: MockAccount): AccountSummary {
  return {
    id: a.id,
    email: a.email,
    role: a.role,
    status: 'ACTIVE',
    isLocked: false,
    mustChangePassword: overrides()[a.id]?.mustChangePassword ?? a.mustChangePassword ?? false,
  };
}

const TOKEN_PREFIX = 'mock-token-';

/** Tài khoản của token, null nếu token không hợp lệ. */
export function accountForToken(token: string): AccountSummary | null {
  const account = MOCK_ACCOUNTS.find((a) => token === TOKEN_PREFIX + a.id);
  return account ? summaryOf(account) : null;
}

const BAD_CREDENTIALS = 'Email hoặc mật khẩu không đúng'; // BR-TK-10: một câu cho mọi trường hợp

const login: MockHandler = ({ body }) => {
  const { email, password } = (body ?? {}) as { email?: string; password?: string };
  if (!email || !password) return fail('VALIDATION_FAILED', 'Nhập email và mật khẩu.', 400);
  const account = MOCK_ACCOUNTS.find((a) => a.email === email.trim().toLowerCase());
  if (!account || passwordOf(account) !== password) return fail('UNAUTHENTICATED', BAD_CREDENTIALS, 401);
  const res: LoginResponse = {
    accessToken: TOKEN_PREFIX + account.id,
    expiresAt: new Date(Date.now() + 12 * 3_600_000).toISOString(), // session.ttl_hours mặc định
    account: summaryOf(account),
    linkDecisionPending: false,
  };
  return ok(res);
};

const me: MockHandler = ({ account }) => {
  const a = MOCK_ACCOUNTS.find((x) => x.id === account!.id)!;
  return ok({
    account,
    ...(a.role === 'CUSTOMER'
      ? { customerId: a.id }
      : { staffProfile: { accountId: a.id, fullName: a.fullName, phone: '0900000000', ...(a.role !== 'ADMIN' && a.role !== 'SUPER_MANAGER' && { branchId: 1 }) } }),
    linkDecisionPending: false,
  });
};

const changePassword: MockHandler = ({ account, body }) => {
  const { currentPassword, newPassword } = (body ?? {}) as { currentPassword?: string; newPassword?: string };
  if (!currentPassword || !newPassword) return fail('VALIDATION_FAILED', 'Nhập mật khẩu hiện tại và mật khẩu mới.', 400);
  const a = MOCK_ACCOUNTS.find((x) => x.id === account!.id)!;
  if (passwordOf(a) !== currentPassword) return ruleFail('BR-TK-14', 'Mật khẩu hiện tại không đúng');
  if (newPassword.length < 8 || !/\p{L}/u.test(newPassword) || !/\d/.test(newPassword)) {
    return ruleFail('BR-TK-03', 'Mật khẩu mới phải dài tối thiểu 8 ký tự, có cả chữ và số');
  }
  if (newPassword === currentPassword) return ruleFail('BR-TK-03', 'Mật khẩu mới không được trùng mật khẩu hiện tại');
  saveOverride(a.id, { password: newPassword, mustChangePassword: false });
  return noContent();
};

export const identityRoutes: Record<string, MockHandler> = {
  'POST /api/auth/login': login,
  'POST /api/auth/logout': () => noContent(),
  'GET /api/me': me,
  'POST /api/me/password': changePassword,
};
