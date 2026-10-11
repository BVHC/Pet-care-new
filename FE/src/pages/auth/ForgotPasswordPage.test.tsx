import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_OTP_CODE, MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { ForgotPasswordPage } from './ForgotPasswordPage';

beforeAll(() => installMockApi(apiClient));

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/auth/forgot']}>
      <Routes>
        <Route path="/auth/forgot" element={<ForgotPasswordPage />} />
        <Route path="/auth/login" element={<p>Trang đăng nhập</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function requestCode(user: ReturnType<typeof userEvent.setup>, email = 'doctor@store1.vn') {
  await user.type(screen.getByPlaceholderText('ban@email.com'), email);
  await user.click(screen.getByRole('button', { name: 'Gửi mã xác thực' }));
  await screen.findByPlaceholderText('6 chữ số');
}

async function reset(user: ReturnType<typeof userEvent.setup>, code: string, password: string) {
  await user.type(screen.getByPlaceholderText('6 chữ số'), code);
  await user.type(screen.getByPlaceholderText('Ít nhất 8 ký tự, có chữ và số'), password);
  await user.type(screen.getByPlaceholderText('Nhập lại mật khẩu mới'), password);
  await user.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
}

const login = (password: string) =>
  apiClient.post('/api/auth/login', { email: 'doctor@store1.vn', password }).then(
    (r) => r.status,
    (e) => e.response.status,
  );

describe('ForgotPasswordPage (UC04)', () => {
  it('resets the password with the emailed code, after which only the new password works', async () => {
    const user = userEvent.setup();
    renderPage();
    await requestCode(user);
    await reset(user, MOCK_OTP_CODE, 'MatKhauMoi1');

    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
    expect(await login('MatKhauMoi1')).toBe(200);
    expect(await login(MOCK_PASSWORD)).toBe(401);
  });

  it('shows the BE message for a wrong code (BR-TK-05)', async () => {
    const user = userEvent.setup();
    renderPage();
    await requestCode(user);
    await reset(user, '000000', 'MatKhauMoi1');

    expect(await screen.findByRole('alert')).toHaveTextContent('OTP không đúng hoặc đã hết hạn');
  });

  it('moves to the code step for an unknown email too, without saying it does not exist (BR-TK-10)', async () => {
    const user = userEvent.setup();
    renderPage();
    await requestCode(user, 'ai@do.vn');
    expect(screen.getByPlaceholderText('6 chữ số')).toBeInTheDocument();
  });

  it('refuses a new password without digits before calling the BE (BR-TK-03)', async () => {
    const user = userEvent.setup();
    renderPage();
    await requestCode(user);
    await reset(user, MOCK_OTP_CODE, 'chiCoChuThoi');

    expect(screen.getByRole('alert')).toHaveTextContent('Mật khẩu phải có cả chữ và số');
    expect(await login(MOCK_PASSWORD)).toBe(200);
  });
});
