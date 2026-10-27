import { describe, expect, it, vi } from 'vitest';

// T49: sau khi bỏ KPI, nút "Đặt lại dữ liệu demo" và useClinicSync, không module nào trong luồng chạy được nạp clinic-db (localStorage, luật v13 cũ).
// Hai module dưới đây ném lỗi ngay khi bị import, nên bất kỳ file nào kéo chúng vào đồ thị import (kể cả gián tiếp qua useClinic) làm test đỏ.
vi.mock('../shared/api/clinic-db', () => {
  throw new Error('clinic-db bị nạp trong luồng chạy');
});
vi.mock('../shared/api/clinic.api', () => {
  throw new Error('clinic.api bị nạp trong luồng chạy');
});

// Nạp cả đồ thị import của App mất vài giây (GSAP, mọi trang), nên máy chậm vượt 5 giây mặc định.
const IMPORT_TIMEOUT = 30_000;

describe('luồng chạy không còn nạp clinic-db', () => {
  it('bảng điều khiển', async () => {
    await expect(import('../pages/admin/AdminDashboardPage')).resolves.toHaveProperty('AdminDashboardPage');
  }, IMPORT_TIMEOUT);

  it('khung nhân viên (thanh bên, signals, ui dùng chung)', async () => {
    await expect(import('../components/staff/StaffShell')).resolves.toHaveProperty('StaffShell');
  }, IMPORT_TIMEOUT);

  it('toàn bộ App (mọi route)', async () => {
    await expect(import('./App')).resolves.toHaveProperty('App');
  }, IMPORT_TIMEOUT);
});
