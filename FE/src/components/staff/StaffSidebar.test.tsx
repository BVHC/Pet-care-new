import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { Role } from '../../shared/types/auth';
import { StaffSidebar, type StaffSidebarProps } from './StaffSidebar';

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
    expect(admin.queryByRole('link', { name: 'Quầy POS' })).not.toBeInTheDocument();
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
});
