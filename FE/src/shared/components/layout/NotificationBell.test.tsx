import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../api/axios';
import { MOCK_PASSWORD } from '../../api/mock/identity.mock';
import { installMockApi } from '../../api/mock/mock-api';
import { useSession } from '../../stores/session.store';
import { NotificationBell } from './NotificationBell';

// care.mock.ts: khach@petcare.vn 6 chưa đọc, mới nhất "Nhắc lịch hẹn của Bim" (→ /appointments);
// reception@store1.vn 2 chưa đọc.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());

const signInAs = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

function renderBell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <NotificationBell />
        <Routes>
          <Route path="/" element={<p>Trang chủ</p>} />
          <Route path="/appointments" element={<p>Trang lịch hẹn</p>} />
          <Route path="/notifications" element={<p>Trang thông báo khách</p>} />
          <Route path="/admin/notifications" element={<p>Trang thông báo nhân viên</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('chuông thông báo trên trang công khai (UC88)', () => {
  it('không hiện khi chưa đăng nhập', () => {
    renderBell();
    expect(screen.queryByRole('button', { name: /Thông báo/ })).not.toBeInTheDocument();
  });

  it('huy hiệu số chưa đọc; bấm thông báo → đánh dấu đã đọc và mở liên kết', async () => {
    await signInAs('khach@petcare.vn');
    const user = renderBell();
    await user.click(await screen.findByRole('button', { name: 'Thông báo, 6 chưa đọc' }));

    const list = await screen.findByRole('list', { name: 'Thông báo mới nhất' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(5);
    await user.click(within(list).getByRole('button', { name: /Nhắc lịch hẹn của Bim/ }));

    expect(await screen.findByText('Trang lịch hẹn')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Thông báo, 5 chưa đọc' })).toBeInTheDocument();
  });

  it('đánh dấu tất cả đã đọc; "Xem tất cả" mở /notifications', async () => {
    await signInAs('khach@petcare.vn');
    const user = renderBell();
    await user.click(await screen.findByRole('button', { name: /Thông báo, 6 chưa đọc/ }));
    await user.click(await screen.findByRole('button', { name: 'Đánh dấu tất cả đã đọc' }));
    expect(await screen.findByRole('button', { name: 'Thông báo' })).toBeInTheDocument();
    expect(screen.getByText('Không có thông báo chưa đọc')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xem tất cả' }));
    expect(await screen.findByText('Trang thông báo khách')).toBeInTheDocument();
  });

  it('nhân viên đang xem trang công khai: "Xem tất cả" mở trang thông báo của khu nhân viên', async () => {
    await signInAs('reception@store1.vn');
    const user = renderBell();
    await user.click(await screen.findByRole('button', { name: 'Thông báo, 2 chưa đọc' }));
    await user.click(screen.getByRole('button', { name: 'Xem tất cả' }));
    expect(await screen.findByText('Trang thông báo nhân viên')).toBeInTheDocument();
  });

  it('ADMIN cũng có chuông (UC88: A02–A08), chưa có thông báo nào', async () => {
    await signInAs('admin@petcare.vn');
    renderBell();
    expect(await screen.findByRole('button', { name: 'Thông báo' })).toBeInTheDocument();
  });
});
