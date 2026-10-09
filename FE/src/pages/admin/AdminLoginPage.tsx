import { useState, useEffect } from 'react';
import { useNavigate, Link, Navigate, useLocation } from 'react-router-dom';
import { toast } from 'sonner';
import { API_MOCK } from '../../shared/api/axios';
import { toApiError } from '../../shared/api/api-error';
import { MOCK_ACCOUNTS, MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { ROUTES } from '../../shared/constants/routes';
import { homePathFor } from '../../shared/constants/workspaces';
import { useSession } from '../../shared/stores/session.store';
import { ROLE_LABELS, type Role } from '../../shared/types/auth';
import { cn } from '../../lib/utils';
import {
  PawPrint, ShieldCheck, Building2, Crown,
  Stethoscope, Scissors, Receipt,
  Sparkles, CheckCircle2, Sun, Moon, ArrowRight
} from 'lucide-react';

interface RoleMeta {
  icon: React.ElementType;
  subtitle: string;
  permissions: string[];
  tone: string;
}

// Theo mô tả actor A02–A08 (docs/01-business-operations.md).
const ROLE_METAS: Record<Role, RoleMeta> = {
  ADMIN: {
    icon: ShieldCheck,
    subtitle: 'Quản trị kỹ thuật, không làm nghiệp vụ phòng khám',
    permissions: ['Tài khoản, khóa / mở khóa người dùng', 'Tham số hệ thống & mẫu thông báo', 'Nhật ký audit'],
    tone: 'bg-red-100 text-red-500',
  },
  SUPER_MANAGER: {
    icon: Building2,
    subtitle: 'Quản lý cấp chuỗi, mọi chi nhánh',
    permissions: ['Chi nhánh, danh mục, giá, phác đồ tiêm', 'Nhân viên toàn chuỗi', 'Nội dung trang & báo cáo toàn chuỗi'],
    tone: 'bg-purple-100 text-purple-500',
  },
  BRANCH_MANAGER: {
    icon: Crown,
    subtitle: 'Quản lý một chi nhánh',
    permissions: ['Giờ mở cửa, quota lịch hẹn, chuồng', 'Nhập kho & đối soát ca thu ngân', 'Báo cáo chi nhánh'],
    tone: 'bg-blue-100 text-blue-500',
  },
  RECEPTIONIST: {
    icon: Receipt,
    subtitle: 'Lễ tân kiêm thu ngân',
    permissions: ['Tiếp nhận & điều phối hàng đợi', 'Bán hàng, thu tiền', 'Nhận / trả thú lưu trú'],
    tone: 'bg-cyan-100 text-cyan-500',
  },
  VET: {
    icon: Stethoscope,
    subtitle: 'Bác sĩ thú y',
    permissions: ['Khám, chẩn đoán, kê đơn', 'Tiêm chủng', 'Bệnh án & nhật ký chăm sóc'],
    tone: 'bg-green-100 text-green-500',
  },
  CARETAKER: {
    icon: Scissors,
    subtitle: 'Nhân viên chăm sóc',
    permissions: ['Dịch vụ thẩm mỹ', 'Nhận thú lưu trú', 'Nhật ký chăm sóc'],
    tone: 'bg-pink-100 text-pink-500',
  },
  CUSTOMER: {
    icon: Sparkles,
    subtitle: 'Chủ thú cưng',
    permissions: ['Hồ sơ & thú cưng', 'Đặt lịch khám, lưu trú', 'Gửi feedback'],
    tone: 'bg-amber-100 text-amber-500',
  },
};

/** Đăng nhập nhanh bằng tài khoản demo của lớp mock API; tắt mock thì dùng trang đăng nhập thật. */
export function AdminLoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from;
  const login = useSession((s) => s.login);
  const [selectedEmail, setSelectedEmail] = useState('manager@store1.vn');
  const [loading, setLoading] = useState(false);
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (stored === 'dark' || (!stored && prefersDark)) {
      setIsDark(true);
      document.documentElement.classList.add('dark');
    }
  }, []);

  if (!API_MOCK) return <Navigate to={ROUTES.login} replace state={location.state} />;

  const toggleTheme = () => {
    setIsDark(!isDark);
    document.documentElement.classList.toggle('dark');
    localStorage.setItem('theme', !isDark ? 'dark' : 'light');
  };

  const selected = MOCK_ACCOUNTS.find((a) => a.email === selectedEmail) ?? MOCK_ACCOUNTS[0];
  const meta = ROLE_METAS[selected.role];
  const IconComponent = meta.icon;

  const handleLogin = async () => {
    setLoading(true);
    try {
      const account = await login({ email: selected.email, password: MOCK_PASSWORD });
      navigate(from ?? homePathFor(account.role));
    } catch (error) {
      toast.error(toApiError(error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-6 bg-(--bg-secondary)">
      {/* Background Decoration */}
      <div className="fixed inset-0 -z-10 overflow-hidden pointer-events-none">
        <div className="absolute top-0 right-0 w-[600px] h-[600px] bg-linear-to-bl from-blue-500/5 to-transparent rounded-full blur-3xl" />
        <div className="absolute bottom-0 left-0 w-[600px] h-[600px] bg-linear-to-tr from-indigo-500/5 to-transparent rounded-full blur-3xl" />
      </div>

      <div className="w-full max-w-5xl">
        {/* Theme Toggle & Header */}
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-linear-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-lg">
              <PawPrint className="h-5 w-5 text-white" />
            </div>
            <div>
              <h1 className="font-semibold text-(--text-primary)">Pet Care</h1>
              <p className="text-xs text-(--text-secondary)">Admin Panel</p>
            </div>
          </div>

          <button
            onClick={toggleTheme}
            className="theme-toggle"
          >
            {isDark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
          </button>
        </div>

        {/* Main Card */}
        <div className="bg-(--bg-primary) rounded-2xl border border-(--border-color) shadow-lg overflow-hidden">
          <div className="grid grid-cols-1 lg:grid-cols-5">

            {/* Left Panel - Account Selection */}
            <div className="lg:col-span-2 bg-(--bg-secondary) p-6 border-b lg:border-b-0 lg:border-r border-(--border-color)">
              <h2 className="text-sm font-semibold text-(--text-secondary) uppercase tracking-wider mb-4">
                Chọn tài khoản demo
              </h2>

              <div className="space-y-1.5">
                {MOCK_ACCOUNTS.map((account) => {
                  const isSelected = selected.email === account.email;
                  const RIcon = ROLE_METAS[account.role].icon;

                  return (
                    <button
                      key={account.email}
                      onClick={() => setSelectedEmail(account.email)}
                      className={cn(
                        'w-full flex items-center gap-3 p-3 rounded-xl transition-all text-left',
                        isSelected
                          ? 'bg-(--color-primary-light) border-2 border-(--color-primary)'
                          : 'bg-transparent hover:bg-(--bg-tertiary) border-2 border-transparent'
                      )}
                    >
                      <div className={cn(
                        'w-9 h-9 rounded-lg flex items-center justify-center shrink-0 transition-colors',
                        isSelected ? 'bg-(--color-primary)' : 'bg-(--bg-tertiary)'
                      )}>
                        <RIcon className={cn('h-4 w-4', isSelected ? 'text-white' : 'text-(--text-secondary)')} />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className={cn(
                          'font-medium text-sm truncate',
                          isSelected ? 'text-(--color-primary-dark)' : 'text-(--text-primary)'
                        )}>
                          {account.fullName}
                        </p>
                        <p className="text-xs text-(--text-tertiary)">
                          {ROLE_LABELS[account.role]}
                          {account.mustChangePassword && ' · phải đổi mật khẩu'}
                        </p>
                      </div>
                      {isSelected && (
                        <CheckCircle2 className="h-4 w-4 text-(--color-primary) shrink-0" />
                      )}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Right Panel - Role Info */}
            <div className="lg:col-span-3 p-8 lg:p-10">
              {/* Selected Role Display */}
              <div className="mb-8">
                <div className={cn('w-14 h-14 rounded-xl flex items-center justify-center mb-4 transition-colors', meta.tone)}>
                  <IconComponent className="h-7 w-7" />
                </div>
                <h2 className="text-2xl font-semibold text-(--text-primary) mb-1">
                  {selected.fullName}
                </h2>
                <p className="text-(--text-secondary)">
                  {meta.subtitle} · {selected.email}
                </p>
              </div>

              {/* Permissions */}
              <div className="mb-8">
                <h3 className="text-sm font-semibold text-(--text-secondary) uppercase tracking-wider mb-3">
                  Quyền hạn
                </h3>
                <ul className="space-y-2.5">
                  {meta.permissions.map((perm, idx) => (
                    <li key={idx} className="flex items-start gap-3">
                      <div className="w-5 h-5 rounded-full bg-(--color-success-light) flex items-center justify-center shrink-0 mt-0.5">
                        <CheckCircle2 className="h-3 w-3 text-(--color-success)" />
                      </div>
                      <span className="text-(--text-primary)">{perm}</span>
                    </li>
                  ))}
                </ul>
              </div>

              {/* Login Button */}
              <button
                onClick={handleLogin}
                disabled={loading}
                className={cn(
                  'w-full py-3.5 rounded-xl font-semibold text-white transition-all',
                  'bg-(--color-primary) hover:bg-(--color-primary-hover) disabled:opacity-60',
                  'shadow-lg shadow-blue-500/20',
                  'flex items-center justify-center gap-2'
                )}
              >
                <span>Đăng nhập với vai trò {ROLE_LABELS[selected.role]}</span>
                <ArrowRight className="h-4 w-4" />
              </button>

              {/* Footer */}
              <p className="text-center text-xs text-(--text-tertiary) mt-6">
                Tài khoản của lớp mock API · mật khẩu chung {MOCK_PASSWORD}
              </p>
            </div>
          </div>
        </div>

        {/* Back to Home */}
        <div className="text-center mt-6">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm text-(--text-secondary) hover:text-(--text-primary) transition-colors"
          >
            ← Quay về trang chủ
          </Link>
        </div>
      </div>
    </div>
  );
}
