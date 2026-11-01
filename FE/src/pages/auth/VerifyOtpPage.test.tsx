import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { toast } from 'sonner';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../../shared/api/auth.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_OTP_CODE } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { VerifyOtpPage } from './VerifyOtpPage';

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.restoreAllMocks());

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

// BE trả độ dài mã (`otpCodeLength` ở đăng ký, `codeLength` khi gửi lại; `otp.code_length` 4–8): số ô nhập theo đó.
// Vắng (BE chưa làm, hoặc vào từ trang đăng nhập) hoặc không hợp lệ thì 6. Mock chỉ phát mã 6 số nên API xác thực / gửi lại được thay bằng spy.
describe('VerifyOtpPage: độ dài mã theo BE', () => {
  const boxes = () => screen.getAllByRole('textbox');
  const verified = { accountId: 100, status: 'ACTIVE', linkDecisionPending: false } as const;
  const resent = (codeLength?: number) => ({
    resendAvailableAt: new Date(Date.now() + 60_000).toISOString(),
    ...(codeLength === undefined ? {} : { codeLength }),
  });

  it.each([4, 8])('otpCodeLength %i: số ô, lời báo thiếu và mã gửi đi đều theo độ dài đó', async (length) => {
    const verify = vi.spyOn(authApi, 'verifyRegistration').mockResolvedValue(verified);
    const notify = vi.spyOn(toast, 'error').mockReturnValue(1);
    renderPage({ email: 'moi@petcare.vn', resendAvailableAt, otpCodeLength: length });
    expect(boxes()).toHaveLength(length);
    expect(screen.queryByText(/6 chữ số/)).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(boxes()[0], '1'.repeat(length - 1));
    await user.click(screen.getByRole('button', { name: 'Xác thực' }));
    expect(notify).toHaveBeenCalledWith(`Vui lòng nhập đủ ${length} chữ số!`);
    expect(verify).not.toHaveBeenCalled();

    await user.type(boxes()[length - 1], '2');
    await user.click(screen.getByRole('button', { name: 'Xác thực' }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith('moi@petcare.vn', `${'1'.repeat(length - 1)}2`));
  });

  it.each([
    ['vắng (vào thẳng, hoặc từ trang đăng nhập)', undefined],
    ['ngoài 4–8 (9)', 9],
    ['không nguyên (6.5)', 6.5],
  ])('otpCodeLength %s: 6 ô', (_label, otpCodeLength) => {
    renderPage({ email: 'moi@petcare.vn', otpCodeLength });
    expect(boxes()).toHaveLength(6);
  });

  it('"Gửi lại mã" trả codeLength mới thì đổi số ô', async () => {
    vi.spyOn(authApi, 'resendRegistrationOtp').mockResolvedValue(resent(8));
    renderPage({ email: 'moi@petcare.vn' });
    expect(boxes()).toHaveLength(6);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Gửi lại mã' }));
    await waitFor(() => expect(boxes()).toHaveLength(8));
  });

  it('"Gửi lại mã" không kèm codeLength thì giữ độ dài đã biết', async () => {
    const resend = vi.spyOn(authApi, 'resendRegistrationOtp').mockResolvedValue(resent());
    renderPage({ email: 'moi@petcare.vn', otpCodeLength: 8 });

    await userEvent.setup().click(screen.getByRole('button', { name: 'Gửi lại mã' }));
    await waitFor(() => expect(resend).toHaveBeenCalledWith('moi@petcare.vn'));
    await screen.findByRole('button', { name: /Gửi lại sau/ });
    expect(boxes()).toHaveLength(8);
  });
});
