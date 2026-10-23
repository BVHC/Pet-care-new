import { describe, expect, it } from 'vitest';
import { STAFF_ROLES, type Role } from '../types/auth';
import { menuFor, pageLabel, rolesForPath } from './staff-menu';

const labels = (role: Role) => menuFor(role).flatMap((s) => s.items.map((i) => i.label));

describe('staff menu', () => {
  it('leaves the pages a workspace already covers out of the receptionist menu', () => {
    expect(labels('RECEPTIONIST')).toEqual(['Order tại quầy', 'Lịch hẹn', 'Đơn online', 'Hóa đơn', 'Hoàn tiền', 'Kho hàng']);
  });

  // T35: lễ tân thu tiền và mở / chốt ca ngay ở bàn Lễ tân (UC70, UC71); đối soát ca (UC72) là việc của A05.
  it('gives the shift reconciliation page to the branch manager only (UC72), not the receptionist', () => {
    expect(labels('BRANCH_MANAGER')).toContain('Ca thu ngân');
    expect(labels('RECEPTIONIST')).not.toContain('Ca thu ngân');
    expect(labels('RECEPTIONIST')).not.toContain('Thanh toán');
    expect(rolesForPath('/admin/payments')).toEqual(['BRANCH_MANAGER']);
    expect(pageLabel('/admin/payments')).toBe('Ca thu ngân');
  });

  it('gives vets and caretakers only the read-only customer lookup besides their workspace (UC23)', () => {
    expect(labels('VET')).toEqual(['Tra cứu khách & thú']);
    expect(labels('CARETAKER')).toEqual(['Tra cứu khách & thú']);
    expect(rolesForPath('/admin/customers')).not.toContain('RECEPTIONIST');
  });

  it('keeps the hidden pages reachable by URL for the roles that had them', () => {
    expect(rolesForPath('/admin/queue')).toContain('RECEPTIONIST');
    expect(rolesForPath('/admin/pos')).not.toContain('VET');
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
