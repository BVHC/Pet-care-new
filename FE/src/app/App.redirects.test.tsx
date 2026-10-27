import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../shared/api/axios';
import { MOCK_PASSWORD } from '../shared/api/mock/identity.mock';
import { installMockApi } from '../shared/api/mock/mock-api';
import { useSession } from '../shared/stores/session.store';
import { App } from './App';

// T49: năm trang mảng cứng cũ (hàng chờ, khám, tiêm chủng, grooming, hóa đơn) chỉ còn route chuyển hướng tới nơi việc đó đã nằm.
// Mỗi vai trò đi tới đích của nó; vai trò không có đích về trang đầu của chính nó thay vì vào trang 403.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());

const ACCOUNTS = {
  ADMIN: 'admin@petcare.vn',
  SUPER_MANAGER: 'chuoi@petcare.vn',
  BRANCH_MANAGER: 'manager@store1.vn',
  RECEPTIONIST: 'reception@store1.vn',
  VET: 'doctor@store1.vn',
  CARETAKER: 'caretaker@store1.vn',
} as const;
type StaffRole = keyof typeof ACCOUNTS;

const RECEPTION = '/staff/reception';
const DOCTOR = '/staff/doctor';
const GROOMING = '/staff/grooming';
const DASHBOARD = '/admin/dashboard';
const USERS = '/admin/users';
const PAYMENTS = '/admin/payments';

// Đích kỳ vọng viết tay, không suy từ WORKSPACE_ROLES / homePathFor để làm chuẩn đối chiếu độc lập với code.
const EXPECTED: Record<string, Record<StaffRole, string>> = {
  '/admin/queue': { RECEPTIONIST: RECEPTION, BRANCH_MANAGER: RECEPTION, VET: DOCTOR, CARETAKER: GROOMING, SUPER_MANAGER: DASHBOARD, ADMIN: USERS },
  '/admin/exam': { RECEPTIONIST: RECEPTION, BRANCH_MANAGER: DASHBOARD, VET: DOCTOR, CARETAKER: GROOMING, SUPER_MANAGER: DASHBOARD, ADMIN: USERS },
  '/admin/vaccination': { RECEPTIONIST: RECEPTION, BRANCH_MANAGER: DASHBOARD, VET: DOCTOR, CARETAKER: GROOMING, SUPER_MANAGER: DASHBOARD, ADMIN: USERS },
  '/admin/grooming': { RECEPTIONIST: RECEPTION, BRANCH_MANAGER: DASHBOARD, VET: DOCTOR, CARETAKER: GROOMING, SUPER_MANAGER: DASHBOARD, ADMIN: USERS },
  // UC70 thu tiền (lễ tân) ở bàn Lễ tân; UC72 đối soát ca (BRANCH_MANAGER) ở trang Ca thu ngân.
  '/admin/invoices': { RECEPTIONIST: RECEPTION, BRANCH_MANAGER: PAYMENTS, VET: DOCTOR, CARETAKER: GROOMING, SUPER_MANAGER: DASHBOARD, ADMIN: USERS },
};

function Where() {
  return <output data-testid="where" data-nav={useNavigationType()}>{useLocation().pathname}</output>;
}

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <Where />
    </MemoryRouter>,
  );
}

const where = () => screen.getByTestId('where');

describe('route cũ chuyển hướng (T49)', () => {
  const cases = Object.entries(EXPECTED).flatMap(([path, byRole]) =>
    (Object.entries(byRole) as [StaffRole, string][]).map(([role, target]) => [path, role, target] as const),
  );

  it.each(cases)('%s với %s → %s, không vào trang 403', async (path, role, target) => {
    await useSession.getState().login({ email: ACCOUNTS[role], password: MOCK_PASSWORD });
    open(path);

    await waitFor(() => expect(where()).toHaveTextContent(new RegExp(`^${target}$`)));
    expect(where()).toHaveAttribute('data-nav', 'REPLACE');
    expect(screen.queryByRole('heading', { name: 'Bạn không có quyền vào trang này' })).not.toBeInTheDocument();
  });

  it('đích của BRANCH_MANAGER ở /admin/invoices là trang Ca thu ngân đang hiển thị (UC72)', async () => {
    await useSession.getState().login({ email: ACCOUNTS.BRANCH_MANAGER, password: MOCK_PASSWORD });
    open('/admin/invoices');

    expect(await screen.findByRole('heading', { name: 'Ca thu ngân' })).toBeInTheDocument();
  });

  it('đích của lễ tân ở /admin/invoices là bàn Lễ tân (UC70)', async () => {
    await useSession.getState().login({ email: ACCOUNTS.RECEPTIONIST, password: MOCK_PASSWORD });
    open('/admin/invoices');

    expect(await screen.findByRole('heading', { name: 'Quầy lễ tân' })).toBeInTheDocument();
  });

  it('khách chưa đăng nhập vào route cũ được đưa tới trang đăng nhập nhân viên', async () => {
    open('/admin/queue');

    expect(await screen.findByRole('heading', { name: 'Chọn tài khoản demo' })).toBeInTheDocument();
  });

  it('khách hàng không vào được khu nhân viên qua route cũ', async () => {
    await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
    open('/admin/invoices');

    expect(await screen.findByRole('heading', { name: 'Bạn không có quyền vào trang này' })).toBeInTheDocument();
  });
});
