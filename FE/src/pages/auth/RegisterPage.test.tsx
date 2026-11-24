import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../../shared/api/auth.api';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { fakeGoogleCredential, stubGoogle } from '../../test/google';
import { RegisterPage } from './RegisterPage';

beforeAll(() => installMockApi(apiClient));
beforeEach(() => vi.stubEnv('VITE_GOOGLE_CLIENT_ID', '')); // test cũ không phụ thuộc FE/.env của máy
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

function OtpStep() {
  const state = useLocation().state as { email: string; resendAvailableAt: string; otpCodeLength?: number };
  return (
    <>
      <p>{`otp ${state.email} ${state.resendAvailableAt ? 'có hạn gửi lại' : 'thiếu hạn'}`}</p>
      <p>{`độ dài mã ${state.otpCodeLength ?? 'chưa biết'}`}</p>
    </>
  );
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/auth/register']}>
      <Routes>
        <Route path="/auth/register" element={<RegisterPage />} />
        <Route path="/auth/verify-otp" element={<OtpStep />} />
        <Route path="/" element={<p>Trang chủ</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function fill(user: ReturnType<typeof userEvent.setup>, { email = 'moi@petcare.vn', password = 'MatKhau1' } = {}) {
  await user.type(screen.getByPlaceholderText('Nguyễn Văn A'), 'Khách Mới');
  await user.type(screen.getByPlaceholderText('ban@email.com'), email);
  await user.type(screen.getByPlaceholderText('Ít nhất 8 ký tự, có chữ và số'), password);
  await user.type(screen.getByPlaceholderText('Nhập lại mật khẩu'), password);
}

const submit = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

describe('RegisterPage (UC01)', () => {
  it('sends the contract payload and moves to the OTP step with the resend time from the BE', async () => {
    const user = userEvent.setup();
    renderPage();
    await fill(user);
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await submit(user);

    expect(await screen.findByText('otp moi@petcare.vn có hạn gửi lại')).toBeInTheDocument();
    expect(screen.getByText('độ dài mã 6')).toBeInTheDocument();
  });

  it.each([8, undefined])('hands the code length of the BE (otpCodeLength %s) to the OTP step', async (otpCodeLength) => {
    vi.spyOn(authApi, 'register').mockResolvedValue({
      accountId: 100,
      email: 'moi@petcare.vn',
      status: 'PENDING',
      otpResendAvailableAt: new Date(Date.now() + 60_000).toISOString(),
      otpCodeLength,
    });
    const user = userEvent.setup();
    renderPage();
    await fill(user);
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await submit(user);

    expect(await screen.findByText(`độ dài mã ${otpCodeLength ?? 'chưa biết'}`)).toBeInTheDocument();
  });

  it('asks for the 18+ and terms confirmation before sending (BR-TK-02)', async () => {
    const user = userEvent.setup();
    renderPage();
    await fill(user);
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await submit(user);

    expect(screen.getByRole('alert')).toHaveTextContent('Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng');
    expect(screen.queryByText(/^otp /)).not.toBeInTheDocument();
  });

  it('shows a used email under the email field (BR-TK-01)', async () => {
    const user = userEvent.setup();
    renderPage();
    await fill(user, { email: 'doctor@store1.vn' });
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await submit(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('Email đã được sử dụng');
  });

  it('refuses a password without digits before calling the BE (BR-TK-03)', async () => {
    const user = userEvent.setup();
    renderPage();
    await fill(user, { password: 'chiCoChu' });
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await submit(user);

    expect(screen.getByRole('alert')).toHaveTextContent('Mật khẩu phải có cả chữ và số');
  });
});

describe('RegisterPage, số điện thoại (phoneValidationUnchanged)', () => {
  const PHONE_MESSAGE = 'Số điện thoại phải gồm 10 số và bắt đầu bằng 0.';
  const registered = () =>
    vi.spyOn(authApi, 'register').mockResolvedValue({
      accountId: 100,
      email: 'moi@petcare.vn',
      status: 'PENDING',
      otpResendAvailableAt: new Date(Date.now() + 60_000).toISOString(),
      otpCodeLength: 6,
    });

  async function submitWithPhone(phone: string) {
    const send = registered();
    const user = userEvent.setup();
    renderPage();
    await fill(user);
    if (phone) await user.type(screen.getByPlaceholderText('0901 234 567'), phone);
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await submit(user);
    return send;
  }

  it('shows the message when the phone field loses focus with a bad number', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByPlaceholderText('0901 234 567'), '12345');
    await user.tab();

    expect(screen.getByText(PHONE_MESSAGE)).toBeInTheDocument();
  });

  it('refuses a bad number on submit and does not call the BE', async () => {
    const send = await submitWithPhone('12345');

    expect(await screen.findByText(PHONE_MESSAGE)).toBeInTheDocument();
    expect(send).not.toHaveBeenCalled();
  });

  it('sends a spaced number without spaces or dashes', async () => {
    const send = await submitWithPhone('0901 234-567');

    expect(await screen.findByText(/^otp /)).toBeInTheDocument();
    expect(send.mock.calls[0][0].phone).toBe('0901234567');
  });

  it('leaves the phone out when the field is blank', async () => {
    const send = await submitWithPhone('');

    expect(await screen.findByText(/^otp /)).toBeInTheDocument();
    expect(send.mock.calls[0][0].phone).toBeUndefined();
  });
});

describe('RegisterPage, đăng ký bằng Google', () => {
  beforeEach(() => vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'test-client'));

  it('googleButtonUsesSignupText', async () => {
    const google = stubGoogle();
    renderPage();

    await waitFor(() => expect(google.options()).toHaveLength(1));

    expect(google.options()[0]).toMatchObject({ theme: 'outline', size: 'large', shape: 'pill', text: 'signup_with', locale: 'vi', width: 400 });
  });

  it('a new Google account finishes through the consent dialog and lands on the home page', async () => {
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();

    await screen.findByRole('button', { name: 'Google' });
    google.click(fakeGoogleCredential({ sub: 'g-reg', email: 'dang.ky@gmail.com', name: 'Đăng Ký' }));
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));

    expect(await screen.findByText('Trang chủ')).toBeInTheDocument();
  });

  it('hides the Google button without a client id', () => {
    vi.stubEnv('VITE_GOOGLE_CLIENT_ID', '');
    const google = stubGoogle();
    renderPage();

    expect(screen.queryByText('hoặc')).not.toBeInTheDocument();
    expect(google.scriptCount()).toBe(0);
  });
});
