import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { authApi } from '../../shared/api/auth.api';
import { MOCK_OTP_CODE, MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { OtpSentResponse } from '../../shared/types/auth';
import { ForgotPasswordPage } from './ForgotPasswordPage';

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.restoreAllMocks());

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

async function requestCode(user: ReturnType<typeof userEvent.setup>, email = 'doctor@store1.vn', placeholder = '6 chữ số') {
  await user.type(screen.getByPlaceholderText('ban@email.com'), email);
  await user.click(screen.getByRole('button', { name: 'Gửi mã xác thực' }));
  await screen.findByPlaceholderText(placeholder);
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

// BE trả độ dài mã (`codeLength`, `otp.code_length` 4–8) ở bước gửi mã: ô nhập, giới hạn gõ và lời báo theo đó.
// Vắng (BE chưa làm) hoặc không hợp lệ thì 6. Mock chỉ phát mã 6 số nên gọi API gửi / đặt lại được thay bằng spy.
describe('ForgotPasswordPage: độ dài mã theo BE (codeLength)', () => {
  const NEW_PASSWORD = 'MatKhauMoi1';
  const sent = (codeLength?: number): OtpSentResponse => ({
    resendAvailableAt: new Date(Date.now() - 1000).toISOString(),
    maskedEmail: null,
    ...(codeLength === undefined ? {} : { codeLength }),
  });

  async function fillNewPassword(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByPlaceholderText('Ít nhất 8 ký tự, có chữ và số'), NEW_PASSWORD);
    await user.type(screen.getByPlaceholderText('Nhập lại mật khẩu mới'), NEW_PASSWORD);
  }

  it.each([4, 8])('codeLength %i: ô mã, giới hạn gõ, lời báo thiếu và mã gửi đi đều theo độ dài đó', async (length) => {
    vi.spyOn(authApi, 'forgotPassword').mockResolvedValue(sent(length));
    const resetPassword = vi.spyOn(authApi, 'resetPassword').mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPage();
    await requestCode(user, 'doctor@store1.vn', `${length} chữ số`);
    const field = screen.getByPlaceholderText(`${length} chữ số`);

    await user.type(field, '9'.repeat(length + 3));
    expect(field).toHaveValue('9'.repeat(length));

    await user.clear(field);
    await user.type(field, '1'.repeat(length - 1));
    await fillNewPassword(user);
    await user.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
    expect(screen.getByRole('alert')).toHaveTextContent(`Mã xác thực gồm ${length} chữ số.`);
    expect(resetPassword).not.toHaveBeenCalled();

    await user.type(field, '1');
    await user.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
    await waitFor(() =>
      expect(resetPassword).toHaveBeenCalledWith({ email: 'doctor@store1.vn', code: '1'.repeat(length), newPassword: NEW_PASSWORD }),
    );
  });

  it.each([
    ['vắng', undefined],
    ['ngoài 4–8 (9)', 9],
    ['không nguyên (6.5)', 6.5],
  ])('codeLength %s: dùng 6', async (_label, codeLength) => {
    vi.spyOn(authApi, 'forgotPassword').mockResolvedValue(sent(codeLength));
    vi.spyOn(authApi, 'resetPassword').mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPage();
    await requestCode(user);
    const field = screen.getByPlaceholderText('6 chữ số');

    await user.type(field, '1234567');
    expect(field).toHaveValue('123456');
    await user.clear(field);
    await user.type(field, '12345');
    await fillNewPassword(user);
    await user.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Mã xác thực gồm 6 chữ số.');
  });

  it('"Gửi lại mã" nhận độ dài của lần gửi sau', async () => {
    vi.spyOn(authApi, 'forgotPassword').mockResolvedValueOnce(sent(8)).mockResolvedValueOnce(sent(4));
    const user = userEvent.setup();
    renderPage();
    await requestCode(user, 'doctor@store1.vn', '8 chữ số');
    await user.click(screen.getByRole('button', { name: 'Gửi lại mã' }));
    expect(await screen.findByPlaceholderText('4 chữ số')).toBeInTheDocument();
  });

  it('dòng giới thiệu không nêu số chữ số, khỏi sai khi BE đổi độ dài', () => {
    renderPage();
    expect(screen.getByText('Mã xác thực được gửi tới đúng email đã đăng ký')).toBeInTheDocument();
    expect(screen.queryByText(/sáu số|\b6 số/)).not.toBeInTheDocument();
  });
});
