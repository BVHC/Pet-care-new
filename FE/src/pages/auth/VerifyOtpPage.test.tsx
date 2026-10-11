import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_OTP_CODE } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { VerifyOtpPage } from './VerifyOtpPage';

beforeAll(() => installMockApi(apiClient));

let resendAvailableAt: string;

beforeEach(async () => {
  const res = await apiClient.post('/api/auth/register', {
    email: 'moi@petcare.vn',
    password: 'MatKhau1',
    fullName: 'Khách Mới',
    isAdult: true,
    termsAccepted: true,
  });
  resendAvailableAt = res.data.data.otpResendAvailableAt;
});

function renderPage(state: object) {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/auth/verify-otp', state }]}>
      <Routes>
        <Route path="/auth/verify-otp" element={<VerifyOtpPage />} />
        <Route path="/auth/login" element={<p>Trang đăng nhập</p>} />
        <Route path="/auth/register" element={<p>Trang đăng ký</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function typeCode(code: string) {
  const user = userEvent.setup();
  await user.type(screen.getAllByRole('textbox')[0], code);
  await user.click(screen.getByRole('button', { name: 'Xác thực' }));
}

describe('VerifyOtpPage (UC02)', () => {
  it('activates the account and sends the customer to the login page', async () => {
    renderPage({ email: 'moi@petcare.vn', resendAvailableAt });
    await typeCode(MOCK_OTP_CODE);
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
  });

  it('shows the BE message for a wrong code (BR-TK-05)', async () => {
    renderPage({ email: 'moi@petcare.vn', resendAvailableAt });
    await typeCode('000000');
    expect(await screen.findByRole('alert')).toHaveTextContent('OTP không đúng hoặc đã hết hạn');
  });

  it('counts the resend button down from resendAvailableAt, not a fixed 60 s (BR-TK-07)', () => {
    renderPage({ email: 'moi@petcare.vn', resendAvailableAt: new Date(Date.now() + 45_000).toISOString() });
    expect(screen.getByRole('button', { name: /Gửi lại sau 45s/ })).toBeDisabled();
  });

  it('lets the user resend at once when no resend time is known (coming from login, BR-TK-08)', () => {
    renderPage({ email: 'moi@petcare.vn' });
    expect(screen.getByRole('button', { name: 'Gửi lại mã' })).toBeEnabled();
  });
});
