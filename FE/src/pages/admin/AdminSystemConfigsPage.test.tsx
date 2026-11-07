import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminSystemConfigsPage } from './AdminSystemConfigsPage';

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
      <MemoryRouter initialEntries={['/admin/system-configs']}>
        <AdminSystemConfigsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;

// otp.ttl_minutes: 1–15 phút, 16 bị BE từ chối 400 BR-QT-13 (plainMessage bỏ đuôi " (BR-QT-13)").
const OUT_OF_RANGE = 'Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút)';
// Thanh lỗi cấp trang = `role="alert"` NGOÀI hộp thoại (FormError nằm trong hộp). `hidden` vì nền bị aria-hidden khi hộp thoại mở.
const pageAlerts = () => screen.queryAllByRole('alert', { hidden: true }).filter((el) => !el.closest('[role="dialog"]'));
const openEdit = async (user: ReturnType<typeof userEvent.setup>, key: string) => {
  await user.click(await screen.findByRole('button', { name: `Sửa ${key}` }));
  return screen.findByRole('dialog');
};
const save = async (user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, value: string) => {
  const input = within(dialog).getByLabelText(/Giá trị/i);
  await user.clear(input);
  await user.type(input, value);
  await user.click(within(dialog).getByRole('button', { name: 'Lưu thay đổi' }));
};
const cancel = async (user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement) => {
  await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
};

describe('AdminSystemConfigsPage (T22 - UC10)', () => {
  describe('ADMIN view (admin@petcare.vn)', () => {
    beforeEach(() => signIn('admin@petcare.vn'));

    it('renders system configurations table', async () => {
      renderPage();
      expect(await screen.findByText('vet.bio_max_length')).toBeInTheDocument();
      expect(await screen.findByText('address.max_per_customer')).toBeInTheDocument(); // khóa thật của BE (V2); khóa cũ customer.address_book_max không tồn tại
      expect(screen.queryByText('customer.address_book_max')).not.toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /^Sửa / })).toHaveLength(85); // đủ 85 tham số [CFG] của BE (V2 + V27 + V35 + V40 + V44…V48)

      const bioRow = await rowOf('vet.bio_max_length');
      expect(within(bioRow).getByText('500 ký tự')).toBeInTheDocument(); // ô giá trị; mô tả cũng có "VARCHAR(500)" nên không dò /500/
      expect(within(bioRow).getByText('Số nguyên')).toBeInTheDocument();
    });

    it('hiện tham số kiểu thời gian (boarding.checkout_time, TIME) với khoảng 06:00 → 22:00', async () => {
      renderPage();
      const row = await rowOf('boarding.checkout_time');
      expect(within(row).getByText('12:00 giờ')).toBeInTheDocument();
      expect(within(row).getByText('Thời gian')).toBeInTheDocument();
      expect(within(row).getByText('06:00 → 22:00')).toBeInTheDocument();
    });

    it('giá trị ngoài khoảng của dòng (otp.ttl_minutes 1–15): hộp thoại ở lại, báo đúng câu BR-QT-13 của BE', async () => {
      const user = userEvent.setup();
      renderPage();
      const row = await rowOf('otp.ttl_minutes');
      await user.click(within(row).getByRole('button', { name: /Sửa otp\.ttl_minutes/i }));
      const modal = screen.getByRole('dialog');
      const input = within(modal).getByLabelText(/Giá trị/i);
      await user.clear(input);
      await user.type(input, '16');
      await user.click(within(modal).getByRole('button', { name: 'Lưu thay đổi' }));

      // plainMessage bỏ đuôi " (BR-QT-13)" khỏi câu BE.
      expect(await within(modal).findByText('Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút)')).toBeInTheDocument();
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });

    it('edits a configuration parameter value', async () => {
      const user = userEvent.setup();
      renderPage();

      const bioRow = await rowOf('vet.bio_max_length');
      const editBtn = within(bioRow).getByRole('button', { name: /Sửa vet\.bio_max_length/i });
      await user.click(editBtn);

      const modal = screen.getByRole('dialog');
      expect(within(modal).getByText('Sửa vet.bio_max_length')).toBeInTheDocument();

      const input = within(modal).getByLabelText(/Giá trị/i);
      await user.clear(input);
      await user.type(input, '300'); // trong khoảng 100–500 của BE

      await user.click(within(modal).getByRole('button', { name: 'Lưu thay đổi' }));

      // Table updates with new value
      const updatedRow = await rowOf('vet.bio_max_length');
      expect(within(updatedRow).getByText('300 ký tự')).toBeInTheDocument();
    });

    // Đo trên BE thật 1/11: lỗi lưu BR-QT-13 còn nguyên khi mở lại hộp thoại (mutation không reset), dù chưa bấm Lưu lần nào nữa.
    it('lỗi lưu của lần trước không còn khi mở lại hộp thoại sửa (cùng khóa lẫn khóa khác)', async () => {
      const user = userEvent.setup();
      renderPage();
      let dialog = await openEdit(user, 'otp.ttl_minutes');
      await save(user, dialog, '16');
      expect(await within(dialog).findByText(OUT_OF_RANGE)).toBeInTheDocument(); // lần thất bại có thật
      await cancel(user, dialog);

      dialog = await openEdit(user, 'otp.ttl_minutes');
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      await cancel(user, dialog);

      dialog = await openEdit(user, 'vet.bio_max_length');
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    });

    it('thanh lỗi cấp trang của lần lưu hỏng biến mất khi mở thao tác mới', async () => {
      const user = userEvent.setup();
      renderPage();
      const dialog = await openEdit(user, 'otp.ttl_minutes');
      await save(user, dialog, '16');
      expect(await within(dialog).findByText(OUT_OF_RANGE)).toBeInTheDocument();
      await cancel(user, dialog);
      expect(pageAlerts().map((el) => el.textContent)).toEqual([OUT_OF_RANGE]); // còn lại sau khi đóng hộp

      await openEdit(user, 'vet.bio_max_length');

      expect(pageAlerts()).toHaveLength(0);
    });

    // Thử lại ngay trong cùng hộp thoại (không mở lại) nên chỉ lần lưu thành công mới có thể xóa thanh lỗi.
    it('thanh lỗi cấp trang biến mất khi lưu lại thành công trong cùng hộp thoại', async () => {
      const user = userEvent.setup();
      renderPage();
      const dialog = await openEdit(user, 'otp.ttl_minutes');
      await save(user, dialog, '16');
      expect(await within(dialog).findByText(OUT_OF_RANGE)).toBeInTheDocument();
      expect(pageAlerts().map((el) => el.textContent)).toEqual([OUT_OF_RANGE]);

      await save(user, dialog, '7');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(pageAlerts()).toHaveLength(0);
    });
  });

  describe('Non-admin view (chuoi@petcare.vn)', () => {
    beforeEach(() => signIn('chuoi@petcare.vn'));

    it('shows access denied message for non-admin', async () => {
      renderPage();
      expect(await screen.findByText('Chỉ quản trị viên mới có thể sửa tham số hệ thống.')).toBeInTheDocument();
      expect(screen.queryByText('vet.bio_max_length')).not.toBeInTheDocument();
    });
  });
});
