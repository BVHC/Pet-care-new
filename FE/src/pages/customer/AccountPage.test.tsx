import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_OTP_CODE, MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { profileApi } from '../../shared/api/profile.api';
import { useSession } from '../../shared/stores/session.store';
import { AccountPage } from './AccountPage';

// Dữ liệu mẫu (customer.mock.ts): khach@petcare.vn có 2 địa chỉ (mặc định: 12 Trần Thái Tông) và đã có thú cưng;
// khachmoi@petcare.vn đang chờ quyết định liên kết, SĐT trùng hồ sơ tại quầy #3 (có email) và #4 (không có email).
beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/account/:section?" element={<AccountPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('customer profile (UC06)', () => {
  it('shows the profile once, with the email read-only (BR-TK-15)', async () => {
    await signIn('khach@petcare.vn');
    renderAt('/account');
    expect(await screen.findByLabelText('Họ tên')).toHaveValue('Nguyễn Thu Trang');
    expect(screen.getAllByLabelText('Họ tên')).toHaveLength(1);
    expect(screen.getByLabelText('Số điện thoại')).toHaveValue('0912345678');
    expect(screen.getByLabelText('Email')).toHaveAttribute('readonly');
    expect(screen.queryByRole('link', { name: 'Liên kết hồ sơ ngay' })).not.toBeInTheDocument();
  });

  it('saves the profile and shows the new name', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account');
    const name = await screen.findByLabelText('Họ tên');
    await user.clear(name);
    await user.type(name, 'Nguyễn Thu Trang Anh');
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã cập nhật hồ sơ');
    expect(useSession.getState().fullName).toBe('Nguyễn Thu Trang Anh');
  });

  it('puts the BE errors under their fields', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account');
    const phone = await screen.findByLabelText('Số điện thoại');
    await user.clear(phone);
    await user.type(phone, '0912');
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByText('Số điện thoại phải có dạng 0xxxxxxxxx')).toBeInTheDocument();
  });

  it('reminds a customer with a pending link decision (BR-TK-19)', async () => {
    await signIn('khachmoi@petcare.vn');
    renderAt('/account');
    expect(await screen.findByRole('link', { name: 'Liên kết hồ sơ ngay' })).toHaveAttribute('href', '/account/link');
  });
});

describe('address book (UC06, BR-TK-18)', () => {
  const card = (addressLine: string) => screen.getByRole('article', { name: addressLine });

  it('lists the default first and adds an address', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/addresses');
    expect(await screen.findByRole('heading', { name: 'Sổ địa chỉ' })).toBeInTheDocument();
    const cards = await screen.findAllByRole('article');
    expect(within(cards[0]).getByText('Mặc định')).toBeInTheDocument();
    expect(screen.getByText('2/5')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Thêm địa chỉ' }));
    const form = screen.getByRole('form', { name: 'Thêm địa chỉ' });
    await user.type(within(form).getByLabelText('Tên người nhận'), 'Trần Minh');
    await user.type(within(form).getByLabelText('SĐT người nhận'), '0911222333');
    await user.type(within(form).getByLabelText('Địa chỉ'), '1 Kim Mã');
    await user.type(within(form).getByLabelText('Tỉnh/thành phố'), 'Hà Nội');
    await user.click(within(form).getByRole('button', { name: 'Lưu địa chỉ' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã thêm địa chỉ');
    expect(card('1 Kim Mã')).toBeInTheDocument();
    expect(screen.getByText('3/5')).toBeInTheDocument();
  });

  it('shows the BE errors of a new address under their fields', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/addresses');
    await user.click(await screen.findByRole('button', { name: 'Thêm địa chỉ' }));
    const form = screen.getByRole('form', { name: 'Thêm địa chỉ' });
    await user.type(within(form).getByLabelText('SĐT người nhận'), '0911');
    await user.click(within(form).getByRole('button', { name: 'Lưu địa chỉ' }));

    expect(await within(form).findByText('Số điện thoại người nhận phải có dạng 0xxxxxxxxx')).toBeInTheDocument();
    expect(within(form).getByText('Vui lòng nhập tên người nhận')).toBeInTheDocument();
  });

  it('keeps the default while other addresses remain, and moves the default', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/addresses');
    await screen.findAllByRole('article');
    expect(within(card('12 Trần Thái Tông')).getByRole('button', { name: 'Xóa' })).toBeDisabled();

    await user.click(within(card('45 Láng Hạ')).getByRole('button', { name: 'Đặt mặc định' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã đặt địa chỉ mặc định');
    expect(within(screen.getAllByRole('article')[0]).getByText('45 Láng Hạ, Láng Hạ, Hà Nội')).toBeInTheDocument();
  });

  it('edits and deletes an address', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/addresses');
    await screen.findAllByRole('article');
    await user.click(within(card('45 Láng Hạ')).getByRole('button', { name: 'Sửa' }));
    const form = screen.getByRole('form', { name: 'Sửa địa chỉ' });
    await user.clear(within(form).getByLabelText('Địa chỉ'));
    await user.type(within(form).getByLabelText('Địa chỉ'), '47 Láng Hạ');
    await user.click(within(form).getByRole('button', { name: 'Lưu địa chỉ' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã cập nhật địa chỉ');

    await user.click(within(card('47 Láng Hạ')).getByRole('button', { name: 'Xóa' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));
    expect(await screen.findByText('1/5')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Đã xóa địa chỉ');
  });

  it('stops adding at 5 addresses', async () => {
    await signIn('khach@petcare.vn');
    for (const n of [3, 4, 5]) {
      await profileApi.addAddress({ receiverName: 'Trần Minh', receiverPhone: '0911222333', addressLine: `${n} Kim Mã`, province: 'Hà Nội' });
    }
    renderAt('/account/addresses');
    expect(await screen.findByText('5/5')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thêm địa chỉ' })).toBeDisabled();
  });
});

describe('profile link (UC07, BR-TK-19)', () => {
  it('links the account to a counter profile with the code sent to its email', async () => {
    await signIn('khachmoi@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/link');
    const noEmail = await screen.findByRole('button', { name: 'Chọn hồ sơ Lê*** T** H*' });
    expect(noEmail).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Chọn hồ sơ Lê*** H** L*' }));
    expect(screen.getByText(/sổ địa chỉ hiện tại \(1 địa chỉ\) sẽ bị xóa/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gửi mã xác thực' }));
    expect(await screen.findByText(/lo\*\*\*@gmail\.com/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Mã xác thực'), '000000');
    await user.click(screen.getByRole('button', { name: 'Xác nhận liên kết' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('OTP không đúng hoặc đã hết hạn');

    await user.clear(screen.getByLabelText('Mã xác thực'));
    await user.type(screen.getByLabelText('Mã xác thực'), MOCK_OTP_CODE);
    await user.click(screen.getByRole('button', { name: 'Xác nhận liên kết' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Liên kết hồ sơ thành công');
    expect(await screen.findByText('Tài khoản đã gắn với hồ sơ tại phòng khám')).toBeInTheDocument();
    expect(useSession.getState().linkDecisionPending).toBe(false);
  });

  it('keeps the online profile on "Không phải tôi" and then offers the search by phone', async () => {
    await signIn('khachmoi@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/link');
    await user.click(await screen.findByRole('button', { name: 'Không phải tôi' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xác nhận' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Bạn tiếp tục dùng hồ sơ online');
    expect(useSession.getState().linkDecisionPending).toBe(false);
    expect(await screen.findByLabelText('Số điện thoại trong hồ sơ tại phòng khám')).toBeInTheDocument();
  });

  it('explains why a profile with data cannot be linked', async () => {
    await signIn('khach@petcare.vn');
    const user = userEvent.setup();
    renderAt('/account/link');
    await user.type(await screen.findByLabelText('Số điện thoại trong hồ sơ tại phòng khám'), '0905111222');
    await user.click(screen.getByRole('button', { name: 'Tìm hồ sơ' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Hồ sơ của bạn đã có thú cưng, lịch hẹn');
  });
});
