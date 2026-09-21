import { Link, useLocation } from 'react-router-dom';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { cn } from '../../lib/utils';
import {
  LayoutDashboard, ShoppingBag, CalendarClock, ListOrdered,
  Stethoscope, Syringe, Scissors, Package, Receipt, Scale, BadgePercent,
  Crown, Warehouse, Truck, Boxes, ClipboardList, ShieldAlert,
  BarChart3, Activity, Building2, UserCog, ChevronDown,
  ChevronRight, LogOut, Sun, Moon
} from 'lucide-react';
import { useState, useEffect } from 'react';
import type { Role } from '../../shared/types/admin';

export interface MenuItem {
  path: string;
  label: string;
  icon: React.ElementType;
  roles: Role[];
}

export interface MenuSection {
  section: string;
  items: MenuItem[];
}

const ALL_ROLES: Role[] = [
  'SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST',
  'VETERINARIAN', 'GROOMER', 'INVENTORY_STAFF', 'FINANCE_STAFF',
];

export const ADMIN_MENU: MenuSection[] = [
  {
    section: 'Tổng quan',
    items: [
      { path: '/admin/dashboard', label: 'Bảng điều khiển', icon: LayoutDashboard, roles: ALL_ROLES },
    ],
  },
  {
    section: 'Nghiệp vụ',
    items: [
      { path: '/admin/pos', label: 'Quầy POS', icon: ShoppingBag, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] },
      { path: '/admin/appointments', label: 'Lịch hẹn', icon: CalendarClock, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST', 'VETERINARIAN'] },
      { path: '/admin/queue', label: 'Hàng chờ', icon: ListOrdered, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST', 'VETERINARIAN', 'GROOMER'] },
      { path: '/admin/exam', label: 'Khám & EMR', icon: Stethoscope, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'VETERINARIAN'] },
      { path: '/admin/vaccination', label: 'Tiêm chủng', icon: Syringe, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'VETERINARIAN'] },
      { path: '/admin/grooming', label: 'Grooming', icon: Scissors, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST', 'GROOMER'] },
      { path: '/admin/orders', label: 'Đơn hàng online', icon: Package, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] },
    ],
  },
  {
    section: 'Thương mại',
    items: [
      { path: '/admin/invoices', label: 'Hóa đơn', icon: Receipt, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'ORG_ADMIN', 'RECEPTIONIST', 'FINANCE_STAFF'] },
      { path: '/admin/payments', label: 'Thanh toán', icon: Scale, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'ORG_ADMIN', 'RECEPTIONIST', 'FINANCE_STAFF'] },
      { path: '/admin/refunds', label: 'Hoàn tiền', icon: Scale, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'ORG_ADMIN', 'RECEPTIONIST', 'FINANCE_STAFF'] },
      { path: '/admin/promotions', label: 'Khuyến mãi', icon: BadgePercent, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER'] },
      { path: '/admin/membership', label: 'Thành viên', icon: Crown, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] },
    ],
  },
  {
    section: 'Kho & Mua hàng',
    items: [
      { path: '/admin/warehouse', label: 'Kho hàng', icon: Warehouse, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'INVENTORY_STAFF'] },
      { path: '/admin/purchasing', label: 'Mua hàng', icon: Truck, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'INVENTORY_STAFF'] },
      { path: '/admin/vaccines', label: 'Vaccine', icon: Boxes, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'INVENTORY_STAFF', 'VETERINARIAN'] },
    ],
  },
  {
    section: 'Quản trị',
    items: [
      { path: '/admin/workforce', label: 'Nhân sự', icon: ClipboardList, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER'] },
      { path: '/admin/incidents', label: 'Sự cố', icon: ShieldAlert, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'VETERINARIAN'] },
      { path: '/admin/reports', label: 'Báo cáo', icon: BarChart3, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'FINANCE_STAFF', 'INVENTORY_STAFF', 'VETERINARIAN'] },
      { path: '/admin/audit', label: 'Kiểm toán', icon: Activity, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'FINANCE_STAFF'] },
    ],
  },
  {
    section: 'Hệ thống',
    items: [
      { path: '/admin/tenants', label: 'Tổ chức', icon: Building2, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER'] },
      { path: '/admin/users', label: 'Người dùng', icon: UserCog, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER'] },
    ],
  },
];

export function routeAllowed(path: string, role: Role): boolean {
  if (role === 'SUPER_ADMIN' || role === 'STORE_MANAGER') return true;
  for (const section of ADMIN_MENU) {
    for (const item of section.items) {
      if (path === item.path || path.startsWith(item.path + '/')) {
        return item.roles.includes(role);
      }
    }
  }
  return true;
}

export function AdminSidebar() {
  const { user, logout } = useAdminSession();
  const location = useLocation();
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>(
    Object.fromEntries(ADMIN_MENU.map(s => [s.section, true]))
  );
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('theme');
    if (stored === 'dark' || (!stored && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
      setIsDark(true);
      document.documentElement.classList.add('dark');
    }
  }, []);

  const toggleTheme = () => {
    setIsDark(!isDark);
    document.documentElement.classList.toggle('dark');
    localStorage.setItem('theme', !isDark ? 'dark' : 'light');
  };

  if (!user) return null;

  const toggleSection = (section: string) => {
    setExpandedSections(prev => ({ ...prev, [section]: !prev[section] }));
  };

  const visibleSections = ADMIN_MENU.map((section) => ({
    ...section,
    items: section.items.filter((item) => item.roles.includes(user.role)),
  })).filter((section) => section.items.length > 0);

  return (
    <aside className="admin-sidebar w-64 h-screen overflow-y-auto flex flex-col">
      {/* Logo */}
      <div className="p-5 border-b border-[var(--border-color)]">
        <Link to="/admin/dashboard" className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center shadow-md">
            <span className="text-white font-bold text-lg">PC</span>
          </div>
          <div>
            <h1 className="font-semibold text-[var(--text-primary)]">Pet Care</h1>
            <p className="text-xs text-[var(--text-secondary)]">Admin Panel</p>
          </div>
        </Link>
      </div>

      {/* Navigation */}
      <nav className="flex-1 p-3 overflow-y-auto">
        {visibleSections.map((section) => (
          <div key={section.section} className="mb-4">
            <button
              onClick={() => toggleSection(section.section)}
              className="flex items-center justify-between w-full text-[11px] font-semibold text-[var(--text-tertiary)] uppercase tracking-wider px-3 py-2 hover:text-[var(--text-secondary)] transition-colors"
            >
              {section.section}
              {expandedSections[section.section] ? (
                <ChevronDown className="h-3.5 w-3.5" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5" />
              )}
            </button>
            {expandedSections[section.section] && (
              <ul className="space-y-0.5">
                {section.items.map((item) => {
                  const isActive = location.pathname === item.path;
                  return (
                    <li key={item.path}>
                      <Link
                        to={item.path}
                        className={cn(
                          'nav-item',
                          isActive && 'active'
                        )}
                      >
                        <item.icon className="h-[18px] w-[18px]" />
                        {item.label}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        ))}
      </nav>

      {/* Theme Toggle */}
      <div className="px-3 pb-2">
        <button
          onClick={toggleTheme}
          className="theme-toggle w-full mb-2"
          title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {isDark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
        </button>
      </div>

      {/* User Info */}
      <div className="p-4 border-t border-[var(--border-color)]">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white font-semibold text-sm">
            {user.name.charAt(0).toUpperCase()}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium text-[var(--text-primary)] truncate">{user.name}</p>
            <p className="text-xs text-[var(--text-secondary)] truncate">{user.role.replace('_', ' ')}</p>
          </div>
          <button
            onClick={logout}
            className="p-2 text-[var(--text-tertiary)] hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors"
            title="Đăng xuất"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
    </aside>
  );
}
