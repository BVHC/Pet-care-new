import { describe, expect, it } from 'vitest';
import { STAFF_ROLES, type Role } from '../types/auth';
import { ADMIN_MENU, menuFor, pageLabel, rolesForPath } from './staff-menu';

const labels = (role: Role) => menuFor(role).flatMap((s) => s.items.map((i) => i.label));

describe('staff menu', () => {
  it('leaves the pages a workspace already covers out of the receptionist menu', () => {
    expect(labels('RECEPTIONIST')).toEqual(['Order tại quầy', 'Lịch hẹn', 'Lưu trú', 'Việc gọi điện', 'Đơn online', 'Hoàn tiền', 'Kho hàng']);
  });

  // T47: nội dung công khai (UC21, UC81) là việc của A04; feedback (UC84) của A04 và A05; Care Task (UC87) chỉ của A06 (BR-TB-05).
  it('gives the content pages to the chain manager and the feedback page also to the branch manager', () => {
    expect(labels('SUPER_MANAGER')).toEqual(expect.arrayContaining(['Nội dung trang', 'Bài viết', 'Góp ý của khách']));
    expect(labels('BRANCH_MANAGER')).toContain('Góp ý của khách');
    expect(labels('BRANCH_MANAGER')).not.toContain('Bài viết');
    expect(labels('BRANCH_MANAGER')).not.toContain('Nội dung trang');
    expect(rolesForPath('/admin/page-contents')).toEqual(['SUPER_MANAGER']);
    expect(rolesForPath('/admin/articles')).toEqual(['SUPER_MANAGER']);
    expect(rolesForPath('/admin/articles/categories')).toEqual(['SUPER_MANAGER']);
    expect(rolesForPath('/admin/feedbacks')).toEqual(['SUPER_MANAGER', 'BRANCH_MANAGER']);
  });

  it('shows Care Tasks to the receptionist only, and nothing of T47 to the technical admin (BR-TB-05)', () => {
    expect(rolesForPath('/admin/care-tasks')).toEqual(['RECEPTIONIST']);
    expect(labels('BRANCH_MANAGER')).not.toContain('Việc gọi điện');
    expect(labels('SUPER_MANAGER')).not.toContain('Việc gọi điện');
    for (const label of ['Việc gọi điện', 'Nội dung trang', 'Bài viết', 'Góp ý của khách']) expect(labels('ADMIN')).not.toContain(label);
    expect(pageLabel('/admin/care-tasks')).toBe('Việc gọi điện');
    expect(pageLabel('/admin/articles/categories')).toBe('Bài viết');
  });

  // T35: lễ tân thu tiền và mở / chốt ca ngay ở bàn Lễ tân (UC70, UC71); đối soát ca (UC72) là việc của A05.
  it('gives the shift reconciliation page to the branch manager only (UC72), not the receptionist', () => {
    expect(labels('BRANCH_MANAGER')).toContain('Ca thu ngân');
    expect(labels('RECEPTIONIST')).not.toContain('Ca thu ngân');
    expect(labels('RECEPTIONIST')).not.toContain('Thanh toán');
    expect(rolesForPath('/admin/payments')).toEqual(['BRANCH_MANAGER']);
    expect(pageLabel('/admin/payments')).toBe('Ca thu ngân');
  });

  it('gives vets and caretakers the boarding page and the read-only customer lookup besides their workspace (UC23, UC57)', () => {
    expect(labels('VET')).toEqual(['Lưu trú', 'Tra cứu khách & thú']);
    expect(labels('CARETAKER')).toEqual(['Lưu trú', 'Tra cứu khách & thú']);
    expect(rolesForPath('/admin/customers')).not.toContain('RECEPTIONIST');
  });

  // T48: lưu trú (UC54, UC57–59). BE: ADMIN và SUPER_MANAGER bị 403 ở cả 12 endpoint (không có RoleHierarchy).
  it('gives the boarding board to the four branch roles and the kennel page to the branch manager only', () => {
    expect(rolesForPath('/admin/boarding')).toEqual(['RECEPTIONIST', 'BRANCH_MANAGER', 'CARETAKER', 'VET']);
    expect(rolesForPath('/admin/kennels')).toEqual(['BRANCH_MANAGER']);
    expect(labels('BRANCH_MANAGER')).toEqual(expect.arrayContaining(['Lưu trú', 'Chuồng lưu trú']));
    for (const role of ['SUPER_MANAGER', 'ADMIN'] as const) {
      expect(labels(role)).not.toContain('Lưu trú');
      expect(labels(role)).not.toContain('Chuồng lưu trú');
    }
    expect(labels('RECEPTIONIST')).not.toContain('Chuồng lưu trú');
    expect(pageLabel('/admin/boarding')).toBe('Lưu trú');
    expect(pageLabel('/admin/kennels')).toBe('Chuồng lưu trú');
  });

  // T49: trang mảng cứng của hàng chờ / khám / tiêm / grooming / hóa đơn đã gỡ; URL cũ chỉ còn chuyển hướng (App.redirects.test), nên không còn mục menu giữ quyền cho chúng.
  it('has no menu entry, hidden or shown, for the retired queue, exam, vaccination, grooming and invoice pages', () => {
    const paths = ADMIN_MENU.flatMap((s) => s.items.map((i) => i.path));
    for (const old of ['/admin/queue', '/admin/exam', '/admin/vaccination', '/admin/grooming', '/admin/invoices']) {
      expect(paths).not.toContain(old);
      expect(rolesForPath(old)).toEqual(STAFF_ROLES);
    }
    expect(rolesForPath('/admin/pos')).not.toContain('VET');
  });

  it('keeps the payments entry that carries the cashier reconciliation after "Hóa đơn" is gone (UC72)', () => {
    expect(labels('BRANCH_MANAGER')).not.toContain('Hóa đơn');
    expect(labels('BRANCH_MANAGER')).toContain('Ca thu ngân');
  });

  it('keeps the technical admin out of the dashboard', () => {
    expect(rolesForPath('/admin/dashboard')).not.toContain('ADMIN');
  });

  it('gives a sub-page the roles of its menu entry', () => {
    expect(rolesForPath('/admin/catalog/products')).toEqual(['SUPER_MANAGER']);
  });

  it('lets any staff open a page outside the menu', () => {
    expect(rolesForPath('/admin/profile')).toEqual(STAFF_ROLES);
  });

  it('names the page for the phone top bar', () => {
    expect(pageLabel('/staff/doctor')).toBe('Phòng khám');
    expect(pageLabel('/admin/dashboard')).toBe('Tổng quan');
    expect(pageLabel('/admin/branches/3/hours')).toBe('Chi nhánh');
    expect(pageLabel('/admin/profile')).toBe('Pet Care');
  });
});
