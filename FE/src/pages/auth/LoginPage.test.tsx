import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { toast } from 'sonner';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD, load, save } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { LoginPage } from './LoginPage';

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  localStorage.clear();
  useSession.getState().clear();
});
afterEach(() => vi.restoreAllMocks());

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/auth/login']}>
      <Routes>
        <Route path="/auth/login" element={<LoginPage />} />
        <Route path="/auth/verify-otp" element={<p>Nhập mã OTP</p>} />
        <Route path="/admin" element={<p>Trang quản trị</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function submit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByPlaceholderText('ban@email.com'), email);
  await user.type(screen.getByPlaceholderText('Nhập mật khẩu'), password);
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
}

describe('LoginPage — thông báo lỗi đăng nhập', () => {
  // BE BusinessRuleViolationException nối " (BR-TK-08)" vào message; người dùng chỉ cần câu thường, mã rule vẫn dùng để chuyển sang bước OTP.
  it('tài khoản chưa xác thực: toast không kèm mã rule, và chuyển sang bước nhập OTP', async () => {
    const s = load();
    s.registered.push({ id: 105, email: 'chuaxacthuc@seed.vn', fullName: 'Chưa xác thực', password: MOCK_PASSWORD, active: false });
    save(s);
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    renderPage();

    await submit('chuaxacthuc@seed.vn', MOCK_PASSWORD);

    expect(await screen.findByText('Nhập mã OTP')).toBeInTheDocument();
    expect(error).toHaveBeenCalledTimes(1);
    const text = String(error.mock.calls[0][0]);
    expect(text).toBe('Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email');
    expect(text).not.toContain('BR-TK-08');
  });

  it('sai mật khẩu: toast hiện câu BE (một câu cho mọi trường hợp, BR-TK-10) và ở lại màn đăng nhập', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    renderPage();

    await submit('admin@petcare.vn', 'SaiMatKhau1');

    await waitFor(() => expect(error).toHaveBeenCalledWith('Email hoặc mật khẩu không đúng'));
    expect(screen.queryByText('Nhập mã OTP')).not.toBeInTheDocument();
    expect(useSession.getState().account).toBeNull();
  });
});
