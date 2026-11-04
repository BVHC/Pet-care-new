import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { profileApi } from '../../shared/api/profile.api';
import { useSession } from '../../shared/stores/session.store';
import { SecurityPage } from './SecurityPage';

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SecurityPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

async function changePassword(current: string, next: string) {
  const user = renderPage();
  await user.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
  await user.type(screen.getByPlaceholderText('Nhập mật khẩu hiện tại'), current);
  await user.type(screen.getByPlaceholderText('Ít nhất 8 ký tự, có chữ và số'), next);
  await user.type(screen.getByPlaceholderText('Nhập lại mật khẩu mới'), next);
  await user.click(screen.getByRole('button', { name: 'Lưu mật khẩu' }));
}

const loginStatus = (password: string) =>
  apiClient.post('/api/auth/login', { email: 'khach@petcare.vn', password }).then(
    (r) => r.status,
    (e) => e.response.status,
  );

describe('SecurityPage — đổi mật khẩu (UC05)', () => {
  it('changes the password through POST /me/password', async () => {
    await changePassword(MOCK_PASSWORD, 'MatKhauMoi1');

    expect(await screen.findByRole('button', { name: 'Đổi mật khẩu' })).toBeInTheDocument(); // form closed
    expect(await loginStatus('MatKhauMoi1')).toBe(200);
  });

  it('shows the BE message when the current password is wrong (BR-TK-14)', async () => {
    await changePassword('Sai12345', 'MatKhauMoi1');

    expect(await screen.findByText(/Mật khẩu hiện tại không đúng/)).toBeInTheDocument();
    expect(await loginStatus(MOCK_PASSWORD)).toBe(200);
  });
});

// M-1 (C8, 4/11): trang từng hiện email, SĐT và hai "phiên" viết cứng (nguyenvana@email.com, 0901 234 567, MacBook Pro, iPhone 15 Pro) cho mọi người dùng.
const sectionOf = (name: string) => screen.getByRole('heading', { name }).closest('section')!;
const HARD_CODED = ['nguyenvana', '0901 234 567', 'MacBook', 'iPhone'];

describe('SecurityPage — thông tin tài khoản thật (M-1)', () => {
  it('khách: hiện email của tài khoản và SĐT trong hồ sơ khách; không còn dữ liệu viết cứng ở bất kỳ đâu', async () => {
    renderPage();
    const contact = sectionOf('Email & SĐT');
    expect(within(contact).getByText('khach@petcare.vn')).toBeInTheDocument();
    expect(await within(contact).findByText('0912345678')).toBeInTheDocument();
    for (const text of HARD_CODED) expect(document.body.textContent).not.toContain(text);
  });

  it('khách chưa khai SĐT: nói rõ "Chưa có số điện thoại", không để trống hay bịa số', async () => {
    const real = await profileApi.customerProfile();
    vi.spyOn(profileApi, 'customerProfile').mockResolvedValue({ ...real, phone: null });
    renderPage();
    expect(await within(sectionOf('Email & SĐT')).findByText('Chưa có số điện thoại')).toBeInTheDocument();
  });

  it('nhân viên (trang mở cho mọi vai trò): SĐT lấy ở GET /api/me, không gọi hồ sơ khách (403)', async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
    const phone = (await profileApi.me()).staffProfile!.phone;
    const customerProfile = vi.spyOn(profileApi, 'customerProfile');
    renderPage();
    const contact = sectionOf('Email & SĐT');
    expect(within(contact).getByText('reception@store1.vn')).toBeInTheDocument();
    expect(await within(contact).findByText(phone)).toBeInTheDocument();
    expect(customerProfile).not.toHaveBeenCalled();
    for (const text of HARD_CODED) expect(document.body.textContent).not.toContain(text);
  });

  it('phiên đăng nhập: chỉ một dòng "Phiên hiện tại" (trình duyệt này) kèm chú thích, không có thiết bị bịa hay nút đăng xuất giả', () => {
    renderPage();
    const sessions = sectionOf('Phiên đăng nhập');
    expect(within(sessions).getAllByText(/Phiên hiện tại/)).toHaveLength(1);
    expect(within(sessions).getByText('Trình duyệt này')).toBeInTheDocument();
    expect(within(sessions).getByText(/Đổi mật khẩu sẽ đăng xuất các thiết bị khác/)).toBeInTheDocument();
    expect(within(sessions).queryByRole('button')).not.toBeInTheDocument();
  });
});
