import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { RegisterPage } from './RegisterPage';

beforeAll(() => installMockApi(apiClient));

function OtpStep() {
  const state = useLocation().state as { email: string; resendAvailableAt: string };
  return <p>{`otp ${state.email} ${state.resendAvailableAt ? 'có hạn gửi lại' : 'thiếu hạn'}`}</p>;
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/auth/register']}>
      <Routes>
        <Route path="/auth/register" element={<RegisterPage />} />
        <Route path="/auth/verify-otp" element={<OtpStep />} />
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
