import { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { DEMO_USERS, ROLE_LABELS, type Role } from '../../shared/types/admin';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { cn } from '../../lib/utils';
import {
  PawPrint, ShieldCheck, Building2, Crown,
  Stethoscope, Scissors, Boxes, Receipt,
  Sparkles, CheckCircle2, Sun, Moon, ArrowRight
} from 'lucide-react';

interface RoleMeta {
  role: Role;
  icon: React.ElementType;
  subtitle: string;
  permissions: string[];
}

const ROLE_METAS: Record<Role, RoleMeta> = {
  SUPER_ADMIN: {
    role: 'SUPER_ADMIN',
    icon: ShieldCheck,
    subtitle: 'Quản trị hệ sinh thái tối cao',
    permissions: ['Quản lý toàn bộ hệ thống', 'Truy cập tất cả 22 trang nghiệp vụ', 'Multi-tenancy'],
  },
  ORG_ADMIN: {
    role: 'ORG_ADMIN',
    icon: Building2,
    subtitle: 'Quản trị tổ chức & thương mại',
    permissions: ['Cấu hình khuyến mãi & voucher', 'Báo cáo chuỗi', 'Nhật ký kiểm toán'],
  },
  STORE_MANAGER: {
    role: 'STORE_MANAGER',
    icon: Crown,
    subtitle: 'Quản lý vận hành chi nhánh',
    permissions: ['Full access cửa hàng', 'Duyệt hoàn tiền Maker-Checker', 'Điều phối nhân sự'],
  },
  RECEPTIONIST: {
    role: 'RECEPTIONIST',
    icon: Receipt,
    subtitle: 'Tiếp đón, Bán lẻ POS & Thu ngân',
    permissions: ['Quầy POS', 'Check-in lịch hẹn', 'Hàng chờ Walk-in'],
  },
  VETERINARIAN: {
    role: 'VETERINARIAN',
    icon: Stethoscope,
    subtitle: 'Bác sĩ thú y & Bệnh án EMR',
    permissions: ['Khám bệnh lâm sàng', 'Hồ sơ EMR', 'Tiêm chủng'],
  },
  GROOMER: {
    role: 'GROOMER',
    icon: Scissors,
    subtitle: 'Chăm sóc & Dịch vụ Grooming Spa',
    permissions: ['Bảng điều phối grooming', 'Cập nhật tiến trình Spa'],
  },
  INVENTORY_STAFF: {
    role: 'INVENTORY_STAFF',
    icon: Boxes,
    subtitle: 'Quản lý kho hàng & Mua hàng',
    permissions: ['Nhập - Xuất - Tồn kho', 'Đơn mua hàng NCC', 'Vaccine & hạn dùng'],
  },
  FINANCE_STAFF: {
    role: 'FINANCE_STAFF',
    icon: Receipt,
    subtitle: 'Kế toán, Hóa đơn & Đối soát',
    permissions: ['Quản lý hóa đơn', 'Đối soát thanh toán', 'Xử lý hoàn tiền'],
  },
  CUSTOMER: {
    role: 'CUSTOMER',
    icon: Sparkles,
    subtitle: 'Khách hàng B2C',
    permissions: ['Portal mua sắm', 'Đặt lịch dịch vụ'],
  },
};

const SELECTABLE_ROLES: Role[] = [
  'SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST',
  'VETERINARIAN', 'GROOMER', 'INVENTORY_STAFF', 'FINANCE_STAFF',
];

export function AdminLoginPage() {
  const navigate = useNavigate();
  const { login } = useAdminSession();
  const [selectedRole, setSelectedRole] = useState<Role>('STORE_MANAGER');
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (stored === 'dark' || (!stored && prefersDark)) {
      setIsDark(true);
      document.documentElement.classList.add('dark');
    }
  }, []);

  const toggleTheme = () => {
    setIsDark(!isDark);
    document.documentElement.classList.toggle('dark');
    localStorage.setItem('theme', !isDark ? 'dark' : 'light');
  };

  const handleLogin = () => {
    const user = DEMO_USERS.find(u => u.role === selectedRole);
    if (user) {
      login(user);
      navigate('/admin/dashboard');
    }
  };

  const selectedUser = DEMO_USERS.find(u => u.role === selectedRole);
  const meta = ROLE_METAS[selectedRole];
  const IconComponent = meta.icon;

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

            {/* Left Panel - Role Selection */}
            <div className="lg:col-span-2 bg-(--bg-secondary) p-6 border-b lg:border-b-0 lg:border-r border-(--border-color)">
              <h2 className="text-sm font-semibold text-(--text-secondary) uppercase tracking-wider mb-4">
                Chọn vai trò demo
              </h2>

              <div className="space-y-1.5">
                {SELECTABLE_ROLES.map((role) => {
                  const isSelected = selectedRole === role;
                  const roleMeta = ROLE_METAS[role];
                  const RIcon = roleMeta.icon;
                  const userObj = DEMO_USERS.find(u => u.role === role);

                  return (
                    <button
                      key={role}
                      onClick={() => setSelectedRole(role)}
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
                          {userObj?.name}
                        </p>
                        <p className="text-xs text-(--text-tertiary)">{ROLE_LABELS[role]}</p>
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
                <div className={cn(
                  'w-14 h-14 rounded-xl flex items-center justify-center mb-4 transition-colors',
                  selectedRole === 'SUPER_ADMIN' ? 'bg-red-100' :
                  selectedRole === 'ORG_ADMIN' ? 'bg-purple-100' :
                  selectedRole === 'STORE_MANAGER' ? 'bg-blue-100' :
                  selectedRole === 'RECEPTIONIST' ? 'bg-cyan-100' :
                  selectedRole === 'VETERINARIAN' ? 'bg-green-100' :
                  selectedRole === 'GROOMER' ? 'bg-pink-100' :
                  selectedRole === 'INVENTORY_STAFF' ? 'bg-amber-100' :
                  selectedRole === 'FINANCE_STAFF' ? 'bg-teal-100' : 'bg-gray-100'
                )}>
                  <IconComponent className={cn(
                    'h-7 w-7',
                    selectedRole === 'SUPER_ADMIN' ? 'text-red-500' :
                    selectedRole === 'ORG_ADMIN' ? 'text-purple-500' :
                    selectedRole === 'STORE_MANAGER' ? 'text-blue-500' :
                    selectedRole === 'RECEPTIONIST' ? 'text-cyan-500' :
                    selectedRole === 'VETERINARIAN' ? 'text-green-500' :
                    selectedRole === 'GROOMER' ? 'text-pink-500' :
                    selectedRole === 'INVENTORY_STAFF' ? 'text-amber-500' :
                    selectedRole === 'FINANCE_STAFF' ? 'text-teal-500' : 'text-gray-500'
                  )} />
                </div>
                <h2 className="text-2xl font-semibold text-(--text-primary) mb-1">
                  {selectedUser?.name}
                </h2>
                <p className="text-(--text-secondary)">
                  {meta.subtitle}
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
                className={cn(
                  'w-full py-3.5 rounded-xl font-semibold text-white transition-all',
                  'bg-(--color-primary) hover:bg-(--color-primary-hover)',
                  'shadow-lg shadow-blue-500/20',
                  'flex items-center justify-center gap-2'
                )}
              >
                <span>Đăng nhập với vai trò {ROLE_LABELS[selectedRole]}</span>
                <ArrowRight className="h-4 w-4" />
              </button>

              {/* Footer */}
              <p className="text-center text-xs text-(--text-tertiary) mt-6">
                Demo mode - Không cần mật khẩu
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
