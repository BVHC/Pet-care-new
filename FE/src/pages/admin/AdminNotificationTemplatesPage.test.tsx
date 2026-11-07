import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminNotificationTemplatesPage } from './AdminNotificationTemplatesPage';

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
      <MemoryRouter initialEntries={['/admin/notification-templates']}>
        <AdminNotificationTemplatesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// identity-v1 #34: `subject` bắt buộc với mọi kênh. Mock đã siết theo BE nên bản cũ (gửi subject: null cho IN_APP) bị 400.
describe('AdminNotificationTemplatesPage (T22 - UC10)', () => {
  beforeEach(() => signIn('admin@petcare.vn'));

  it('mẫu IN_APP cũng có ô Tiêu đề và lưu được với tiêu đề không null', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sửa LAST_BRANCH_MANAGER_LOCKED_APP' }));

    const dialog = await screen.findByRole('dialog');
    const subject = within(dialog).getByLabelText('Tiêu đề *');
    expect(subject).toHaveValue('Chi nhánh không còn quản lý đang hoạt động');
    expect(subject).toBeRequired();
    expect(within(dialog).getByText(/Biến được phép: \{ho_ten\}, \{email_tai_khoan\}, \{vai_tro\}, \{ly_do\}, \{thoi_diem\}, \{viec_do_dang\}, \{ten_chi_nhanh\}/)).toBeInTheDocument();

    await user.type(subject, ' (đã sửa)');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Chi nhánh không còn quản lý đang hoạt động (đã sửa)')).toBeInTheDocument();
  });

  it('liệt kê đủ 70 mẫu của BE (kể cả 40 mẫu tầng 3); mã bịa BRANCH_MANAGER_LOCKED_APP của bản mock cũ không còn', async () => {
    renderPage();
    await screen.findByRole('button', { name: 'Sửa OTP_REGISTER' });
    expect(screen.getAllByRole('button', { name: /^Sửa / })).toHaveLength(70);
    expect(screen.queryByRole('button', { name: 'Sửa BRANCH_MANAGER_LOCKED_APP' })).not.toBeInTheDocument();
  });

  it('nội dung dùng biến lạ: hộp thoại ở lại, hiện đúng câu BR-QT-14 của BE; sửa hợp lệ rồi khôi phục mặc định', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sửa OTP_REGISTER' }));
    const dialog = await screen.findByRole('dialog');
    const body = within(dialog).getByLabelText('Nội dung *');
    fireEvent.change(body, { target: { value: 'Mã {ma_otp} và {fullName}' } }); // user.type hiểu "{" là phím đặc biệt
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    // plainMessage bỏ đuôi " (BR-QT-14)" khỏi câu BE.
    expect(await within(dialog).findByText('Nội dung dùng biến không có: {fullName}; Biến dùng được: {ten_khach}, {ma_otp}, {thoi_han_phut}')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.change(body, { target: { value: 'Mã {ma_otp} hết hạn sau {thoi_han_phut} phút' } });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(await screen.findByRole('button', { name: 'Khôi phục mặc định OTP_REGISTER' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Khôi phục mặc định OTP_REGISTER' })).not.toBeInTheDocument());
    expect(screen.queryByText('Đã sửa')).not.toBeInTheDocument();
  });

  it('tiêu đề chỉ toàn khoảng trắng: báo lỗi, không gọi API, hộp thoại ở lại', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sửa LAST_BRANCH_MANAGER_LOCKED_APP' }));

    const dialog = await screen.findByRole('dialog');
    const subject = within(dialog).getByLabelText('Tiêu đề *');
    await user.clear(subject);
    await user.type(subject, '   ');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

    expect(await within(dialog).findByText(/Tiêu đề không được để trống/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  // Đo trên BE thật 1/11: lỗi BR-QT-14 của lần lưu trước còn nguyên khi mở lại hộp thoại (mutation không reset), thanh lỗi cấp trang không bao giờ bị xóa.
  describe('lỗi cũ không còn sót', () => {
    const UNKNOWN_VAR = 'Nội dung dùng biến không có: {fullName}; Biến dùng được: {ten_khach}, {ma_otp}, {thoi_han_phut}';
    const VALID_BODY = 'Mã {ma_otp} hết hạn sau {thoi_han_phut} phút';
    // Thanh lỗi cấp trang = `role="alert"` NGOÀI hộp thoại (FormError nằm trong hộp). `hidden` vì nền bị aria-hidden khi hộp thoại mở.
    const pageAlerts = () => screen.queryAllByRole('alert', { hidden: true }).filter((el) => !el.closest('[role="dialog"]'));
    type User = ReturnType<typeof userEvent.setup>;
    const openEdit = async (user: User, code: string) => {
      await user.click(await screen.findByRole('button', { name: `Sửa ${code}` }));
      return screen.findByRole('dialog');
    };
    /** Sửa nội dung rồi Lưu (user.type hiểu "{" là phím đặc biệt nên dùng fireEvent.change). */
    const save = async (user: User, dialog: HTMLElement, body: string) => {
      fireEvent.change(within(dialog).getByLabelText('Nội dung *'), { target: { value: body } });
      await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    };
    const cancel = async (user: User, dialog: HTMLElement) => {
      await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    };
    /** Lưu nội dung dùng biến lạ rồi Hủy: để lại FormError (trong hộp) + thanh lỗi cấp trang. */
    const failAndCancel = async (user: User, code: string) => {
      const dialog = await openEdit(user, code);
      await save(user, dialog, 'Mã {ma_otp} và {fullName}');
      expect(await within(dialog).findByText(UNKNOWN_VAR)).toBeInTheDocument(); // lần thất bại có thật
      await cancel(user, dialog);
      expect(pageAlerts().map((el) => el.textContent)).toEqual([UNKNOWN_VAR]);
    };

    it('lỗi lưu của lần trước không còn khi mở lại hộp thoại sửa (cùng mẫu lẫn mẫu khác)', async () => {
      const user = userEvent.setup();
      renderPage();
      await failAndCancel(user, 'OTP_REGISTER');

      let dialog = await openEdit(user, 'OTP_REGISTER');
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      await cancel(user, dialog);

      dialog = await openEdit(user, 'LAST_BRANCH_MANAGER_LOCKED_APP');
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    });

    it('thanh lỗi cấp trang của lần lưu hỏng biến mất khi mở thao tác mới', async () => {
      const user = userEvent.setup();
      renderPage();
      await failAndCancel(user, 'OTP_REGISTER');

      await openEdit(user, 'LAST_BRANCH_MANAGER_LOCKED_APP');

      expect(pageAlerts()).toHaveLength(0);
    });

    // Thử lại ngay trong cùng hộp thoại (không mở lại) nên chỉ lần lưu thành công mới có thể xóa thanh lỗi.
    it('thanh lỗi cấp trang biến mất khi lưu lại thành công trong cùng hộp thoại', async () => {
      const user = userEvent.setup();
      renderPage();
      const dialog = await openEdit(user, 'OTP_REGISTER');
      await save(user, dialog, 'Mã {ma_otp} và {fullName}');
      expect(await within(dialog).findByText(UNKNOWN_VAR)).toBeInTheDocument();
      expect(pageAlerts().map((el) => el.textContent)).toEqual([UNKNOWN_VAR]);

      await save(user, dialog, VALID_BODY);

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(pageAlerts()).toHaveLength(0);
    });

    // Khôi phục mặc định không có hộp thoại: thành công của nó là chỗ duy nhất xóa được thanh lỗi của lần lưu hỏng trước.
    it('thanh lỗi cấp trang biến mất khi khôi phục mặc định thành công', async () => {
      const user = userEvent.setup();
      renderPage();
      const first = await openEdit(user, 'OTP_REGISTER');
      await save(user, first, VALID_BODY); // thành công: mẫu thành "Đã sửa", hiện nút khôi phục
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await failAndCancel(user, 'OTP_REGISTER');

      await user.click(await screen.findByRole('button', { name: 'Khôi phục mặc định OTP_REGISTER' }));

      await waitFor(() => expect(screen.queryByRole('button', { name: 'Khôi phục mặc định OTP_REGISTER' })).not.toBeInTheDocument());
      expect(pageAlerts()).toHaveLength(0);
    });
  });

  // Chạy với BE thật (T49): hộp thoại sửa chưa mở thì `editing` null, mô tả không được ghi "Biến được phép: undefined".
  it('chưa mở hộp thoại sửa thì không rò chữ "undefined" ra trang', async () => {
    renderPage();
    await screen.findByRole('button', { name: 'Sửa LAST_BRANCH_MANAGER_LOCKED_APP' });
    expect(document.body).not.toHaveTextContent('undefined');
  });
});
