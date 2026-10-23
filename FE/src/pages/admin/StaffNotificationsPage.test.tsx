import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { StaffNotificationsPage } from './StaffNotificationsPage';

// care.mock.ts: reception@store1.vn 3 thông báo — "Nhật ký chăm sóc bất thường: Lu", "Chuồng lưu trú sắp kín"
// (chưa đọc, → /staff/reception) và việc dở của khách bị khóa (đã đọc, không liên kết). doctor@store1.vn: không có.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());

const signInAs = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/notifications']}>
        <StaffNotificationsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const items = async () => within(await screen.findByRole('list', { name: 'Danh sách thông báo' })).getAllByRole('listitem');

describe('StaffNotificationsPage (UC88)', () => {
  it('lễ tân thấy thông báo của mình, mới nhất trước; nội dung nhiều dòng hiện đủ', async () => {
    await signInAs('reception@store1.vn');
    renderPage();
    const list = await items();
    expect(list).toHaveLength(3);
    expect(within(list[0]).getByText('Nhật ký chăm sóc bất thường: Lu')).toBeInTheDocument();
    expect(within(list[2]).getByText(/Lịch hẹn #58: đã đặt/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Chưa đọc (2)' })).toBeInTheDocument();
  });

  it('đánh dấu một đã đọc, rồi tất cả; tab Chưa đọc trống', async () => {
    await signInAs('reception@store1.vn');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đánh dấu đã đọc: Chuồng lưu trú sắp kín' }));
    expect(await screen.findByRole('tab', { name: 'Chưa đọc (1)' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }));
    expect(await screen.findByRole('tab', { name: 'Chưa đọc' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Chưa đọc' }));
    expect(await screen.findByText('Bạn đã đọc hết thông báo')).toBeInTheDocument();
  });

  it('bác sĩ chưa có thông báo: trạng thái trống', async () => {
    await signInAs('doctor@store1.vn');
    renderPage();
    expect(await screen.findByText('Chưa có thông báo nào')).toBeInTheDocument();
  });
});

describe('/admin/notifications trong khung nhân viên', () => {
  it('chuông trên thanh bên có số chưa đọc và mở trang Thông báo', async () => {
    await signInAs('reception@store1.vn');
    render(
      <MemoryRouter initialEntries={['/admin/profile']}>
        <App />
      </MemoryRouter>,
    );
    const sidebar = screen.getByRole('complementary', { name: 'Thanh bên' });
    await userEvent.click(await within(sidebar).findByRole('link', { name: 'Thông báo, 2 chưa đọc' }));
    expect(await screen.findByRole('heading', { name: 'Thông báo', level: 1 })).toBeInTheDocument();
    expect(await items()).toHaveLength(3);
  });

  it('ADMIN mở được trang (UC88: A02–A08), chưa có thông báo nào', async () => {
    await signInAs('admin@petcare.vn');
    render(
      <MemoryRouter initialEntries={['/admin/notifications']}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByRole('heading', { name: 'Thông báo', level: 1 })).toBeInTheDocument();
    expect(await screen.findByText('Chưa có thông báo nào')).toBeInTheDocument();
  });
});
