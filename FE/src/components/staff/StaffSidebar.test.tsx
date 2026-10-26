import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { visitApi } from '../../shared/api/visit.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { Role } from '../../shared/types/auth';
import { startDesk } from '../../test/visit-harness';
import { StaffSidebar, type StaffSidebarProps } from './StaffSidebar';

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.useRealTimers());

function renderSidebar(role: Role, { path = '/staff/reception', ...props }: Partial<StaffSidebarProps> & { path?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <StaffSidebar
          user={{ id: '4', email: 'staff@store1.vn', name: 'Lan Chi', role }}
          collapsed={false}
          dark={false}
          onToggleTheme={() => {}}
          onHelp={() => {}}
          {...props}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const nav = (name: string) => screen.getByRole('navigation', { name });

describe('staff sidebar', () => {
  it('lists the desk and the overview, then only back-office pages no workspace covers', () => {
    renderSidebar('RECEPTIONIST');

    const desks = within(nav('Bàn làm việc')).getAllByRole('link').map((l) => l.getAttribute('aria-label'));
    expect(desks).toEqual(['Lễ tân', 'Tổng quan']);
    const admin = within(nav('Quản trị'));
    expect(admin.getByRole('link', { name: 'Lịch hẹn' })).toBeInTheDocument();
    expect(admin.queryByRole('link', { name: 'Hàng chờ' })).not.toBeInTheDocument();
    expect(admin.getByRole('link', { name: 'Order tại quầy' })).toHaveAttribute('href', '/admin/pos');
  });

  it('gives a vet the clinic counts and only the read-only customer lookup in the back-office menu (UC23)', () => {
    renderSidebar('VET', { path: '/staff/doctor' });

    const admin = within(nav('Quản trị'));
    expect(admin.getAllByRole('link')).toHaveLength(1);
    expect(admin.getByRole('link', { name: 'Tra cứu khách & thú' })).toHaveAttribute('href', '/admin/customers');
    expect(screen.getByRole('heading', { name: 'Cả phòng khám hôm nay' })).toBeInTheDocument();
  });

  it('gives the technical admin no desks, no clinic counts and no demo reset', () => {
    renderSidebar('ADMIN', { path: '/admin/users' });

    expect(screen.queryByRole('navigation', { name: 'Bàn làm việc' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Cả phòng khám hôm nay' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đặt lại dữ liệu demo' })).not.toBeInTheDocument();
  });

  it('keeps every link named in the rail and drops the clinic counts', () => {
    renderSidebar('RECEPTIONIST', { collapsed: true });

    expect(within(nav('Quản trị')).getByRole('link', { name: 'Lịch hẹn' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Cả phòng khám hôm nay' })).not.toBeInTheDocument();
  });

  it('says the sidebar is open and asks the shell to fold it', async () => {
    const onToggleCollapsed = vi.fn();
    renderSidebar('VET', { path: '/staff/doctor', onToggleCollapsed });

    const toggle = screen.getByRole('button', { name: 'Thu gọn thanh bên' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await userEvent.click(toggle);
    expect(onToggleCollapsed).toHaveBeenCalledOnce();
  });

  it('marks the menu entry of a sub-page as current', () => {
    renderSidebar('BRANCH_MANAGER', { path: '/admin/branches/3/hours' });

    expect(within(nav('Quản trị')).getByRole('link', { name: 'Chi nhánh' })).toHaveAttribute('aria-current', 'page');
  });

  it('puts the notification bell with the unread count next to the logo, rail included (UC88)', async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
    renderSidebar('RECEPTIONIST', { collapsed: true });

    expect(await screen.findByRole('link', { name: 'Thông báo, 2 chưa đọc' })).toHaveAttribute('href', '/admin/notifications');
    useSession.getState().clear();
  });

  it('gives the technical admin the bell too (UC88: A02–A08)', async () => {
    renderSidebar('ADMIN', { path: '/admin/users' });

    expect(await screen.findByRole('link', { name: 'Thông báo' })).toHaveAttribute('href', '/admin/notifications');
  });

  // T35: "Chờ thu tiền" đếm Order PENDING của chi nhánh từ sales-v1 #2 (sales.mock: #2, #3, #4), không còn đếm clinic-db.
  it('counts the orders waiting for payment from the sales API', async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
    renderSidebar('RECEPTIONIST');

    const row = (await screen.findByText('Chờ thu tiền')).closest('div')!;
    await waitFor(() => expect(within(row).getByText('3')).toBeInTheDocument());
    useSession.getState().clear();
  });

  // T36: "Chờ tiếp nhận / Đang chờ / Đang làm" đếm từ API visit (lịch hẹn chờ check-in, hàng đợi chi nhánh), không còn đếm clinic-db. Đồng hồ ghim 10:40 thứ Năm 22/10/2026:
  // hàng đợi mẫu có 3 lượt đang chờ (2101, 2102, 2103) và 2 lượt đã gọi (2002, 2104); lịch hẹn #1 vào cửa sổ check-in từ 10:30.
  const count = async (label: string) => (await screen.findByText(label)).closest('div')!;

  it('counts arrivals, waiting and called visits from the visit API for the front desk', async () => {
    await startDesk('reception@store1.vn');
    const myQueue = vi.spyOn(visitApi, 'getMyQueue');
    renderSidebar('RECEPTIONIST');

    await waitFor(async () => expect(within(await count('Chờ tiếp nhận')).getByText('1')).toBeInTheDocument());
    await waitFor(async () => expect(within(await count('Đang chờ')).getByText('3')).toBeInTheDocument());
    await waitFor(async () => expect(within(await count('Đang làm')).getByText('2')).toBeInTheDocument());
    expect(myQueue).not.toHaveBeenCalled();
  });

  it('gives a vet the branch counts but never asks for the check-in list (403 for VET / CARETAKER)', async () => {
    await startDesk('doctor@store1.vn');
    const arrivals = vi.spyOn(visitApi, 'listArrivals');
    const branchQueue = vi.spyOn(visitApi, 'listQueue');
    renderSidebar('VET', { path: '/staff/doctor' });

    await waitFor(async () => expect(within(await count('Đang chờ')).getByText('3')).toBeInTheDocument());
    expect(within(await count('Chờ tiếp nhận')).getByText('–')).toBeInTheDocument();
    expect(branchQueue).toHaveBeenCalled();
    expect(arrivals).not.toHaveBeenCalled();
  });

  it('shows the branch manager the same live counts', async () => {
    await startDesk('manager@store1.vn');
    renderSidebar('BRANCH_MANAGER');

    await waitFor(async () => expect(within(await count('Đang chờ')).getByText('3')).toBeInTheDocument());
    await waitFor(async () => expect(within(await count('Chờ tiếp nhận')).getByText('1')).toBeInTheDocument());
  });
});
