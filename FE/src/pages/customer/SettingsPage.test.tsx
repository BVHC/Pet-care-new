import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { SettingsPage } from './SettingsPage';

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/settings']}>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { user: userEvent.setup(), unmount: view.unmount };
}

const vaccineEmail = () => screen.findByRole('switch', { name: 'Nhắc tái chủng qua email' });

describe('SettingsPage — cài đặt nhận thông báo (UC88, care-v1 #9, #10)', () => {
  it('3 loại nhắc × 2 kênh, mặc định bật hết; ghi rõ email bảo mật luôn gửi; bỏ các mục không có use case', async () => {
    renderPage();
    expect(await vaccineEmail()).toBeInTheDocument();
    const switches = screen.getAllByRole('switch');
    expect(switches).toHaveLength(6);
    for (const s of switches) expect(s).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('Email bảo mật luôn được gửi')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
    for (const gone of ['Chế độ tối', 'Thông báo SMS', 'Xóa dữ liệu cá nhân', 'Múi giờ']) {
      expect(screen.queryByText(gone)).not.toBeInTheDocument();
    }
  });

  it('đổi → có thay đổi chưa lưu; hủy thì trở lại; lưu thì giữ sau khi mở lại trang', async () => {
    const { user, unmount } = renderPage();
    await user.click(await vaccineEmail());
    expect(await vaccineEmail()).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText('Có thay đổi chưa lưu')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hủy thay đổi' }));
    expect(await vaccineEmail()).toHaveAttribute('aria-checked', 'true');
    expect(screen.queryByText('Có thay đổi chưa lưu')).not.toBeInTheDocument();

    await user.click(await vaccineEmail());
    await user.click(screen.getByRole('switch', { name: 'Nhắc tái chủng trong ứng dụng' }));
    expect(screen.getByText('Đã tắt cả hai kênh — bạn sẽ không nhận nhắc này.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
    expect(screen.queryByText('Có thay đổi chưa lưu')).not.toBeInTheDocument();

    unmount();
    renderPage();
    expect(await vaccineEmail()).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('switch', { name: 'Nhắc lịch hẹn qua email' })).toHaveAttribute('aria-checked', 'true');
  });
});
