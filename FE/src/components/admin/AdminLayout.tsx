import { Link, useLocation } from 'react-router-dom';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { cn } from '../../lib/utils';
import {
  LayoutDashboard, ShoppingBag, CalendarClock, ListOrdered,
  Stethoscope, Syringe, Scissors, Receipt, Scale, BadgePercent,
  Crown, Warehouse, Truck, Boxes, ClipboardList, ShieldAlert,
  BarChart3, Activity, Building2, UserCog, ChevronDown,
  ChevronRight, LogOut, PanelLeftClose, PanelLeft,
  Package, Bot
} from 'lucide-react';
import { useState } from 'react';
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

// eslint-disable-next-line react-refresh/only-export-components
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
      { path: '/admin/orders', label: 'Đơn online', icon: Package, roles: ['SUPER_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] },
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
      { path: '/admin/ai', label: 'Trí tuệ nhân tạo', icon: Bot, roles: ['SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'VETERINARIAN'] },
    ],
  },
];

// eslint-disable-next-line react-refresh/only-export-components
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

export interface AdminSidebarProps {
  collapsed?: boolean;
  onToggle?: () => void;
}

export function AdminSidebar({ collapsed: propCollapsed, onToggle }: AdminSidebarProps) {
  const { user, logout } = useAdminSession();
  const location = useLocation();
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>(
    Object.fromEntries(ADMIN_MENU.map(s => [s.section, true]))
  );
  const [internalCollapsed, setInternalCollapsed] = useState(false);

  const isCollapsed = propCollapsed !== undefined ? propCollapsed : internalCollapsed;

  const handleToggleCollapse = () => {
    if (onToggle) {
      onToggle();
    } else {
      setInternalCollapsed(!internalCollapsed);
      localStorage.setItem('sidebar-collapsed', String(!internalCollapsed));
    }
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
    <aside className={cn(
      "admin-sidebar h-screen flex flex-col transition-all duration-300 select-none shrink-0 border-r border-(--border-color)",
      isCollapsed ? "w-16" : "w-64"
    )}>
      {/* Logo & Toggle Header */}
      <div className={cn(
        "p-4 border-b border-(--border-color) flex items-center justify-between",
        isCollapsed && "p-3 justify-center"
      )}>
        <Link to="/admin/dashboard" className={cn("flex items-center gap-3", isCollapsed && "justify-center")}>
          <div className="w-10 h-10 rounded-xl bg-amber-50 dark:bg-amber-950/30 border border-amber-200/60 dark:border-amber-800/40 flex items-center justify-center shadow-xs shrink-0 p-1">
            <img src="/imgs/CatSticker.svg" alt="Pet Care Logo" className="w-8 h-8 object-contain" />
          </div>
          {!isCollapsed && (
            <div>
              <h1 className="font-semibold text-base text-(--text-primary) leading-tight font-friendly">Pet Care</h1>
              <p className="text-xs text-(--text-secondary)">Admin Portal</p>
            </div>
          )}
        </Link>
        {!isCollapsed && (
          <button
            onClick={handleToggleCollapse}
            className="p-1.5 rounded-lg text-(--text-tertiary) hover:text-(--text-secondary) hover:bg-(--bg-secondary) transition-colors"
            title="Thu gọn sidebar"
          >
            <PanelLeftClose className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 p-3 overflow-y-auto space-y-4">
        {visibleSections.map((section) => (
          <div key={section.section}>
            {/* Section Header */}
            <button
              onClick={() => toggleSection(section.section)}
              className={cn(
                "flex items-center justify-between w-full text-[11px] font-semibold text-(--text-tertiary) uppercase tracking-wider px-3 py-1.5 hover:text-(--text-secondary) transition-colors",
                isCollapsed && "justify-center px-0 py-2"
              )}
              title={isCollapsed ? section.section : undefined}
            >
              {!isCollapsed ? (
                <>
                  <span>{section.section}</span>
                  {expandedSections[section.section] ? (
                    <ChevronDown className="h-3.5 w-3.5" />
                  ) : (
                    <ChevronRight className="h-3.5 w-3.5" />
                  )}
                </>
              ) : (
                <div className="w-1.5 h-1.5 rounded-full bg-(--text-tertiary) opacity-60" />
              )}
            </button>

            {/* Menu Items */}
            {(isCollapsed || expandedSections[section.section]) && (
              <ul className="space-y-0.5 mt-1">
                {section.items.map((item) => {
                  const isActive = location.pathname === item.path;
                  return (
                    <li key={item.path}>
                      <Link
                        to={item.path}
                        className={cn(
                          'nav-item flex items-center gap-3 px-3 py-2 rounded-xl text-sm font-medium transition-colors',
                          isActive
                            ? 'bg-blue-600 text-white shadow-sm font-semibold'
                            : 'text-(--text-secondary) hover:text-(--text-primary) hover:bg-(--bg-secondary)',
                          isCollapsed && 'justify-center px-2 py-2.5'
                        )}
                        title={isCollapsed ? item.label : undefined}
                      >
                        <item.icon className="h-[18px] w-[18px] shrink-0" />
                        {!isCollapsed && <span className="truncate">{item.label}</span>}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        ))}
      </nav>

      {/* Bottom Actions */}
      <div className={cn("p-3 border-t border-(--border-color) space-y-2", isCollapsed && "flex flex-col items-center p-2")}>
        {/* Collapse Toggle when in collapsed mode */}
        {isCollapsed && (
          <button
            onClick={handleToggleCollapse}
            className="p-2 rounded-lg text-(--text-tertiary) hover:text-(--text-primary) hover:bg-(--bg-secondary) transition-colors w-full flex justify-center"
            title="Mở rộng sidebar"
          >
            <PanelLeft className="h-5 w-5" />
          </button>
        )}
      </div>

      {/* User Info */}
      <div className={cn("p-4 border-t border-(--border-color)", isCollapsed && "p-2 flex justify-center")}>
        <div className={cn("flex items-center gap-3", isCollapsed && "flex-col")}>
          <div className="w-10 h-10 rounded-full bg-linear-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white font-semibold text-sm shrink-0">
            {user.name.charAt(0).toUpperCase()}
          </div>
          {!isCollapsed ? (
            <>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-(--text-primary) truncate">{user.name}</p>
                <p className="text-xs text-(--text-secondary) truncate">{user.role.replace('_', ' ')}</p>
              </div>
              <button
                onClick={logout}
                className="p-2 text-(--text-tertiary) hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors"
                title="Đăng xuất"
              >
                <LogOut className="h-4 w-4" />
              </button>
            </>
          ) : (
            <button
              onClick={logout}
              className="p-1.5 text-(--text-tertiary) hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors"
              title="Đăng xuất"
            >
              <LogOut className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>
    </aside>
  );
}
