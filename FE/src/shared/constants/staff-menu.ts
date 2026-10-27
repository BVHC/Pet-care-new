import type { ElementType } from 'react';
import {
  Activity, BadgePercent, BarChart3, Bell, BedDouble, Bot, Boxes, Building2, CalendarClock, ClipboardList, Crown, FileText,
  Lock, Mail, MessageSquareText, Newspaper, Package, PhoneCall, Scale, Settings, ShieldAlert, ShoppingBag,
  Tags, Truck, UserCog, UserSearch, Warehouse, LayoutGrid,
} from 'lucide-react';
import { STAFF_ROLES, type Role } from '../types/auth';
import { WORKSPACE_LABELS, WORKSPACE_ROLES, workspaceForPath } from './workspaces';

export interface MenuItem {
  path: string;
  label: string;
  icon: ElementType;
  roles: readonly Role[];
  /** Trang cũ trùng việc với một bàn làm việc: không hiện trong menu; URL và quyền giữ nguyên tới T57. */
  hidden?: true;
}

export interface MenuSection {
  section: string;
  items: MenuItem[];
}

// Role theo actor của UC (docs/01-business-operations.md). A03 ADMIN chỉ quản trị kỹ thuật.
// Trang không có UC tương ứng ở v16 (đánh dấu "ngoài phạm vi") tạm để SUPER_MANAGER, gỡ ở T57.
// Bàn làm việc và Tổng quan không nằm ở đây: thanh bên dựng nhóm đó từ WORKSPACE_*.
export const ADMIN_MENU: MenuSection[] = [
  {
    section: 'Nghiệp vụ',
    items: [
      // UC66, UC67: danh sách Order + Order bán lẻ; BRANCH_MANAGER hủy Order PENDING khách không trả (BR-BH-05). Bán nhanh ở bàn Lễ tân.
      { path: '/admin/pos', label: 'Order tại quầy', icon: ShoppingBag, roles: ['RECEPTIONIST', 'BRANCH_MANAGER'] },
      { path: '/admin/appointments', label: 'Lịch hẹn', icon: CalendarClock, roles: ['RECEPTIONIST'] }, // UC39, UC40
      // UC57 (A07, A08 ghi nhật ký), UC58 (A06 đặt tại quầy), UC59 (A06 nhận / trả, A08 chỉ nhận thú); A05 chỉ xem (boarding-v1 §B).
      { path: '/admin/boarding', label: 'Lưu trú', icon: BedDouble, roles: ['RECEPTIONIST', 'BRANCH_MANAGER', 'CARETAKER', 'VET'] },
      // UC54 (A05): tạo / sửa / xóa chuồng, bảo trì. BE: ADMIN và SUPER_MANAGER bị 403 (không có RoleHierarchy).
      { path: '/admin/kennels', label: 'Chuồng lưu trú', icon: LayoutGrid, roles: ['BRANCH_MANAGER'] },
      // UC87 (A06): Care Task của chi nhánh; BRANCH_MANAGER không thấy (BR-TB-05, care-v1 §D.1).
      { path: '/admin/care-tasks', label: 'Việc gọi điện', icon: PhoneCall, roles: ['RECEPTIONIST'] },
      // Hàng chờ (UC45, UC46), Khám & EMR (UC48), Tiêm chủng (UC49), Grooming (UC52) nằm ở các bàn làm việc; URL cũ /admin/queue|exam|vaccination|grooming chỉ còn chuyển hướng (App.tsx).
      { path: '/admin/orders', label: 'Đơn online', icon: Package, roles: ['RECEPTIONIST'] }, // UC65 (tầng 3)
      // UC23 cho A07, A08 (chỉ xem). Lễ tân tra cứu và sửa hồ sơ ngay ở bàn Lễ tân.
      { path: '/admin/customers', label: 'Tra cứu khách & thú', icon: UserSearch, roles: ['VET', 'CARETAKER'] },
      // UC88 (A02–A08: mọi nhân viên kể cả ADMIN; BE isAuthenticated, ADR-0039): mở từ chuông trên khung nhân viên.
      { path: '/admin/notifications', label: 'Thông báo', icon: Bell, roles: STAFF_ROLES, hidden: true },
    ],
  },
  {
    section: 'Thương mại',
    items: [
      // v16 không có hóa đơn: thu tiền (UC70) làm ở bàn Lễ tân; URL cũ /admin/invoices chuyển hướng (App.tsx).
      // UC72 (A05): đối soát ca thu ngân. Thu tiền (UC70) và mở / chốt ca (UC71) của lễ tân làm ngay ở bàn Lễ tân.
      { path: '/admin/payments', label: 'Ca thu ngân', icon: Scale, roles: ['BRANCH_MANAGER'] },
      { path: '/admin/refunds', label: 'Hoàn tiền', icon: Scale, roles: ['RECEPTIONIST', 'BRANCH_MANAGER'] }, // UC68 (tầng 3)
      { path: '/admin/promotions', label: 'Khuyến mãi', icon: BadgePercent, roles: ['SUPER_MANAGER'] }, // ngoài phạm vi
      { path: '/admin/membership', label: 'Thành viên', icon: Crown, roles: ['SUPER_MANAGER'] }, // ngoài phạm vi
    ],
  },
  {
    section: 'Danh mục',
    items: [
      { path: '/admin/catalog', label: 'Sản phẩm & dịch vụ', icon: Tags, roles: ['SUPER_MANAGER'] }, // UC28–UC31
    ],
  },
  {
    section: 'Nội dung',
    items: [
      { path: '/admin/page-contents', label: 'Nội dung trang', icon: FileText, roles: ['SUPER_MANAGER'] }, // UC21
      { path: '/admin/articles', label: 'Bài viết', icon: Newspaper, roles: ['SUPER_MANAGER'] }, // UC81 (tab Bài viết, Chuyên mục)
      // UC84: SUPER_MANAGER xem tất cả, BRANCH_MANAGER chỉ feedback chi nhánh mình (BR-DG-03).
      { path: '/admin/feedbacks', label: 'Góp ý của khách', icon: MessageSquareText, roles: ['SUPER_MANAGER', 'BRANCH_MANAGER'] },
    ],
  },
  {
    section: 'Kho & Mua hàng',
    items: [
      // UC73–76: tab tồn kho (A06 chỉ xem), phiếu nhập, điều chỉnh, nhà cung cấp (A05) — lọc tab theo role trong trang.
      { path: '/admin/warehouse', label: 'Kho hàng', icon: Warehouse, roles: ['BRANCH_MANAGER', 'RECEPTIONIST'] },
      // Trang cũ, nay chuyển hướng sang tab kho (gỡ ở T57).
      { path: '/admin/purchasing', label: 'Mua hàng', icon: Truck, roles: ['BRANCH_MANAGER'], hidden: true },
      { path: '/admin/vaccines', label: 'Vaccine', icon: Boxes, roles: ['BRANCH_MANAGER', 'RECEPTIONIST'], hidden: true },
    ],
  },
  {
    section: 'Quản trị',
    items: [
      { path: '/admin/workforce', label: 'Nhân sự', icon: ClipboardList, roles: ['BRANCH_MANAGER'] }, // UC34–38 (tầng 3)
      { path: '/admin/incidents', label: 'Sự cố', icon: ShieldAlert, roles: ['SUPER_MANAGER', 'BRANCH_MANAGER'] }, // UC86 (tầng 3)
      { path: '/admin/reports', label: 'Báo cáo', icon: BarChart3, roles: ['SUPER_MANAGER', 'BRANCH_MANAGER'] }, // UC89
      { path: '/admin/audit', label: 'Kiểm toán', icon: Activity, roles: ['ADMIN'] }, // UC11
    ],
  },
  {
    section: 'Hệ thống',
    items: [
      { path: '/admin/branches', label: 'Chi nhánh', icon: Building2, roles: ['SUPER_MANAGER', 'BRANCH_MANAGER'] }, // UC12, UC14, UC33, UC42
      { path: '/admin/users', label: 'Người dùng', icon: UserCog, roles: ['ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER'] }, // UC08 (Staff); UC09 (Account khóa) dùng riêng trang
      { path: '/admin/accounts', label: 'Khóa tài khoản', icon: Lock, roles: ['ADMIN'] }, // UC09 — khóa/mở khóa + lý do
      { path: '/admin/system-configs', label: 'Tham số', icon: Settings, roles: ['ADMIN'] }, // UC10
      { path: '/admin/notification-templates', label: 'Mẫu thông báo', icon: Mail, roles: ['ADMIN'] }, // UC10
      { path: '/admin/ai', label: 'Trí tuệ nhân tạo', icon: Bot, roles: ['SUPER_MANAGER'] }, // tầng 3, chưa đặc tả
    ],
  },
];

function itemForPath(path: string): MenuItem | undefined {
  for (const section of ADMIN_MENU) {
    for (const item of section.items) {
      if (path === item.path || path.startsWith(item.path + '/')) return item;
    }
  }
  return undefined;
}

/** Role được mở một trang nhân viên: bàn làm việc theo WORKSPACE_ROLES, trang admin theo ADMIN_MENU (kể cả mục ẩn), còn lại mọi nhân viên. */
export function rolesForPath(path: string): readonly Role[] {
  const workspace = workspaceForPath(path);
  if (workspace) return WORKSPACE_ROLES[workspace];
  return itemForPath(path)?.roles ?? STAFF_ROLES;
}

/** Menu quản trị của một role: bỏ mục ẩn và nhóm rỗng. */
export function menuFor(role: Role): MenuSection[] {
  return ADMIN_MENU.map((section) => ({
    ...section,
    items: section.items.filter((item) => !item.hidden && item.roles.includes(role)),
  })).filter((section) => section.items.length > 0);
}

/** Tên trang trên thanh trên ở điện thoại. */
export function pageLabel(path: string): string {
  const workspace = workspaceForPath(path);
  if (workspace) return WORKSPACE_LABELS[workspace];
  return itemForPath(path)?.label ?? 'Pet Care';
}
