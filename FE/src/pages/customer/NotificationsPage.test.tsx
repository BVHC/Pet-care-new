import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { NotificationsPage } from './NotificationsPage';

// care.mock.ts: khach@petcare.vn 26 thông báo (trang 20 + 6), 6 chưa đọc (1 nằm ở trang 2).

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/notifications']}>
        <Routes>
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/appointments" element={<p>Trang lịch hẹn</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const items = async () => within(await screen.findByRole('list', { name: 'Danh sách thông báo' })).getAllByRole('listitem');

describe('NotificationsPage (UC88)', () => {
  it('tải 20 thông báo mới nhất, "Xem thêm" tải trang sau', async () => {
    const user = renderPage();
    const first = await items();
    expect(first).toHaveLength(20);
    expect(within(first[0]).getByText('Nhắc lịch hẹn của Bim')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Xem thêm' }));
    await waitFor(async () => expect(await items()).toHaveLength(26));
    expect(screen.queryByRole('button', { name: 'Xem thêm' })).not.toBeInTheDocument();
  });

  it('tab Chưa đọc chỉ hiện thông báo chưa đọc, gồm cả thông báo cũ', async () => {
    const user = renderPage();
    await user.click(await screen.findByRole('tab', { name: 'Chưa đọc (6)' }));
    await screen.findByRole('tab', { name: 'Chưa đọc (6)', selected: true });
    const list = await items();
    expect(list).toHaveLength(6);
    expect(screen.queryByRole('button', { name: 'Xem thêm' })).not.toBeInTheDocument();
    expect(within(list[5]).getByText('Nhắc lịch hẹn của Mochi')).toBeInTheDocument();
  });

  it('đánh dấu một thông báo đã đọc rồi đánh dấu tất cả: tab Chưa đọc trống', async () => {
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đánh dấu đã đọc: Nhắc lịch hẹn của Bim' }));
    expect(await screen.findByRole('tab', { name: 'Chưa đọc (5)' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đánh dấu đã đọc: Nhắc lịch hẹn của Bim' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }));
    expect(await screen.findByRole('tab', { name: 'Chưa đọc' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' })).toBeDisabled();
    await user.click(screen.getByRole('tab', { name: 'Chưa đọc' }));
    expect(await screen.findByText('Bạn đã đọc hết thông báo')).toBeInTheDocument();
  });

  it('bấm thông báo có liên kết: đánh dấu đã đọc và mở trang', async () => {
    const user = renderPage();
    const [newest] = await items();
    await user.click(within(newest).getByRole('button', { name: /^Nhắc lịch hẹn của Bim/ }));
    expect(await screen.findByText('Trang lịch hẹn')).toBeInTheDocument();
    const count = await apiClient.get('/api/me/notifications/unread-count');
    expect(count.data.data.count).toBe(5);
  });

  it('khách chưa có thông báo nào thấy trạng thái trống', async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: 'khachmoi@petcare.vn', password: MOCK_PASSWORD });
    renderPage();
    expect(await screen.findByText('Chưa có thông báo nào')).toBeInTheDocument();
  });
});
