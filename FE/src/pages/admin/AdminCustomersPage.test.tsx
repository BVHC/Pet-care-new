import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminCustomersPage } from './AdminCustomersPage';

// UC23 cho bác sĩ (A07): chỉ xem. Mochi (hồ sơ #1, SĐT 0912345678) có bệnh án với ghi chú nội bộ.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'doctor@store1.vn', password: MOCK_PASSWORD });
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/customers']}>
        <AdminCustomersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('AdminCustomersPage — bác sĩ tra cứu khách & thú (UC23)', () => {
  it('chỉ xem: không tạo hồ sơ, không sửa, không thao tác thú', async () => {
    const user = renderPage();
    expect(screen.queryByRole('button', { name: /Tạo hồ sơ/ })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0912345678');
    await user.click(await screen.findByRole('button', { name: /^Mở hồ sơ Nguyễn Thu Trang/ }));
    const profile = await screen.findByRole('dialog', { name: 'Nguyễn Thu Trang' });
    expect(await within(profile).findByText('Mochi')).toBeInTheDocument();
    for (const name of [/^Sửa$/, /Chuyển chủ/, /Đã mất/, /Thêm thú cưng/]) {
      expect(within(profile).queryByRole('button', { name })).not.toBeInTheDocument();
    }
    await user.click(within(profile).getByRole('tab', { name: 'Thông tin khách' }));
    expect(within(profile).queryByRole('button', { name: /Sửa thông tin|Sửa email/ })).not.toBeInTheDocument();
  });

  it('xem hồ sơ sức khỏe của thú, nhân viên thấy cả ghi chú nội bộ', async () => {
    const user = renderPage();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0912345678');
    await user.click(await screen.findByRole('button', { name: /^Mở hồ sơ Nguyễn Thu Trang/ }));
    const profile = await screen.findByRole('dialog', { name: 'Nguyễn Thu Trang' });
    await user.click(await within(profile).findByRole('button', { name: 'Hồ sơ sức khỏe của Mochi' }));
    const drawer = await screen.findByRole('dialog', { name: /Mochi/ });
    expect(await within(drawer).findByText('Viêm da dị ứng — tái khám')).toBeInTheDocument();
    expect(within(drawer).getByText(/Ghi chú nội bộ: Chủ hay quên/)).toBeInTheDocument();
    expect(within(drawer).queryByLabelText('Cân nặng (kg)')).not.toBeInTheDocument();
  });
});
