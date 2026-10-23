import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminUsersPage } from './AdminUsersPage';

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  localStorage.clear();
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/users']}>
        <AdminUsersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;

describe('AdminUsersPage (T22 - UC08)', () => {
  describe('ADMIN view (admin@petcare.vn)', () => {
    beforeEach(() => signIn('admin@petcare.vn'));

    it('renders list of staff accounts with roles and branches', async () => {
      renderPage();
      expect(await screen.findByText('BS. Minh Anh')).toBeInTheDocument();
      expect((await screen.findAllByText(/Quản trị hệ thống/)).length).toBeGreaterThan(0);
      expect(await screen.findByText('Đỗ Thanh Hà')).toBeInTheDocument();

      const doctorRow = await rowOf('BS. Minh Anh');
      expect(within(doctorRow).getByText('Bác sĩ thú y')).toBeInTheDocument();
      expect(within(doctorRow).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
      expect(within(doctorRow).getByText('Đang hoạt động')).toBeInTheDocument();
    });

    it('searches staff by name or email', async () => {
      const user = userEvent.setup();
      renderPage();
      await screen.findByText('BS. Minh Anh');

      const searchInput = screen.getByPlaceholderText('Tìm theo tên hoặc email…');
      await user.type(searchInput, 'doctor@store1.vn');

      await waitFor(() => {
        expect(screen.queryByText('Đỗ Thanh Hà')).not.toBeInTheDocument();
      }, { timeout: 2000 });

      expect(screen.getByText('BS. Minh Anh')).toBeInTheDocument();
      expect(screen.queryByText(/Quản trị hệ thống/)).not.toBeInTheDocument();
    });

    it('opens staff detail modal showing info and VET bio/specialty', async () => {
      const user = userEvent.setup();
      renderPage();

      const viewBtn = await screen.findByRole('button', { name: 'Xem chi tiết BS. Minh Anh' });
      await user.click(viewBtn);

      const modal = screen.getByRole('dialog');
      expect(within(modal).getByText('Chi tiết nhân viên')).toBeInTheDocument();
      expect(within(modal).getByText('doctor@store1.vn')).toBeInTheDocument();
      expect(within(modal).getByText('Chuyên môn')).toBeInTheDocument();
      expect(within(modal).getByText('Nội khoa, da liễu thú y')).toBeInTheDocument();
      expect(within(modal).getByText('Hơn 8 năm khám và điều trị chó mèo, quan tâm đặc biệt tới bệnh da và dị ứng.')).toBeInTheDocument();
    });

    // BR-QT-01: ADMIN chỉ tạo SUPER_MANAGER (không thuộc chi nhánh).
    it('ADMIN chỉ thấy vai trò Quản lý chuỗi, không có ô chi nhánh, và tạo được tài khoản', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      const roles = within(within(dialog).getByLabelText('Vai trò *')).getAllByRole('option');
      expect(roles.map((o) => o.textContent)).toEqual(['Quản lý chuỗi']);
      expect(within(dialog).queryByLabelText(/Chi nhánh/)).not.toBeInTheDocument();

      await user.type(within(dialog).getByLabelText('Họ tên *'), 'Quản lý chuỗi mới');
      await user.type(within(dialog).getByLabelText('Email *'), 'sm2@petcare.vn');
      await user.type(within(dialog).getByLabelText('Số điện thoại *'), '0900000099');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));

      expect(await screen.findByText('Quản lý chuỗi mới')).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });
  });

  describe('SUPER_MANAGER view (chuoi@petcare.vn)', () => {
    beforeEach(() => signIn('chuoi@petcare.vn'));

    it('sees all staff except ADMIN, and has change role / transfer permissions', async () => {
      renderPage();
      expect(await screen.findByText(/Đỗ Thanh Hà/)).toBeInTheDocument();
      expect(await screen.findByText('BS. Minh Anh')).toBeInTheDocument();
      expect(screen.queryByText(/Quản trị hệ thống/)).not.toBeInTheDocument();

      expect(screen.getByRole('button', { name: 'Đổi chức vụ BS. Minh Anh' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Điều chuyển BS. Minh Anh' })).toBeInTheDocument();
    });

    // BR-QT-01: SUPER_MANAGER không tạo được SUPER_MANAGER; vai trò ngoài quyền không hiển thị để chọn.
    it('không có vai trò Quản lý chuỗi trong form tạo, nhưng có Quản lý chi nhánh và ô chi nhánh bắt buộc', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      const roles = within(within(dialog).getByLabelText('Vai trò *')).getAllByRole('option').map((o) => o.textContent);
      expect(roles).toEqual(['Quản lý chi nhánh', 'Lễ tân', 'Bác sĩ thú y', 'Nhân viên chăm sóc']);
      expect(within(dialog).getByLabelText('Chi nhánh *')).toBeRequired();
    });

    it('điều chuyển: ô chi nhánh mới bắt buộc (không gửi branchId = 0)', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Điều chuyển BS. Minh Anh' }));

      expect(within(await screen.findByRole('dialog')).getByLabelText('Chi nhánh mới *')).toBeRequired();
    });
  });

  describe('BRANCH_MANAGER view (manager@store1.vn)', () => {
    beforeEach(() => signIn('manager@store1.vn'));

    it('sees only staff of CN1, and cannot change role or transfer', async () => {
      renderPage();
      expect(await screen.findByText(/Hoàng Nam/)).toBeInTheDocument();
      expect(await screen.findByText('BS. Minh Anh')).toBeInTheDocument();
      expect(await screen.findByText('Lan Chi')).toBeInTheDocument();

      // Does not see SUPER_MANAGER or staff from store 2
      expect(screen.queryByText(/Đỗ Thanh Hà/)).not.toBeInTheDocument();
      expect(screen.queryByText(/Phạm Minh Đức/)).not.toBeInTheDocument();

      // Branch filter is disabled for branch manager
      expect(screen.getByLabelText('Lọc chi nhánh')).toBeDisabled();

      // Cannot change role or transfer
      expect(screen.queryByRole('button', { name: /Đổi chức vụ/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Điều chuyển/ })).not.toBeInTheDocument();

      // Cannot disable self
      expect(screen.queryByRole('button', { name: 'Vô hiệu hóa Hoàng Nam' })).not.toBeInTheDocument();
      // Can disable subordinates
      expect(screen.getByRole('button', { name: 'Vô hiệu hóa BS. Minh Anh' })).toBeInTheDocument();
    });

    // Hai test dưới bấm xác nhận thật: bản cũ gọi hook trong onConfirm nên mutation không bao giờ chạy.
    it('vô hiệu hóa: bấm xác nhận gọi API, dòng chuyển sang Kích hoạt lại', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Vô hiệu hóa Lan Chi' }));

      const dialog = await screen.findByRole('dialog');
      await user.click(within(dialog).getByRole('button', { name: 'Vô hiệu hóa' }));

      expect(await screen.findByRole('button', { name: 'Kích hoạt lại Lan Chi' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Vô hiệu hóa Lan Chi' })).not.toBeInTheDocument();
    });

    it('gửi lại mật khẩu ban đầu: bấm xác nhận gọi API và đóng hộp thoại', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Gửi lại MK cho Lễ tân mới' }));

      const dialog = await screen.findByRole('dialog');
      await user.click(within(dialog).getByRole('button', { name: 'Gửi lại' }));

      // Lỗi thì hộp thoại ở lại (handleError); thành công thì setResend(null) đóng nó.
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });
  });
});
