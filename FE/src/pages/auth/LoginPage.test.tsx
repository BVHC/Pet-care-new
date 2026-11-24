import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { toast } from 'sonner';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { authApi } from '../../shared/api/auth.api';
import { apiClient } from '../../shared/api/axios';
import { customerOfAccount, linkCandidates } from '../../shared/api/mock/customer.mock';
import { MOCK_PASSWORD, load, save } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { fakeGoogleCredential, stubGoogle } from '../../test/google';
import { LoginPage } from './LoginPage';

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  vi.stubEnv('VITE_GOOGLE_CLIENT_ID', ''); // test cũ không phụ thuộc FE/.env của máy
  localStorage.clear();
  useSession.getState().clear();
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

/** `from`: trang khách đang mở trước khi guard đẩy sang đăng nhập (RequireRole gửi qua location state). */
function renderPage(from?: string) {
  render(
    <MemoryRouter initialEntries={[from ? { pathname: '/auth/login', state: { from } } : '/auth/login']}>
      <Routes>
        <Route path="/auth/login" element={<LoginPage />} />
        <Route path="/me/bookings" element={<p>Trang lịch hẹn</p>} />
        <Route path="/auth/verify-otp" element={<p>Nhập mã OTP</p>} />
        <Route path="/admin" element={<p>Trang quản trị</p>} />
        <Route path="/" element={<p>Trang chủ</p>} />
        <Route path="/account/link" element={<p>Liên kết hồ sơ</p>} />
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

describe('LoginPage, đăng nhập Google', () => {
  const NEW = { sub: 'g-new', email: 'khach.google@gmail.com', name: 'Khách Google' };
  const EXPIRED = 'Phiên đăng nhập Google đã hết hạn. Vui lòng bấm đăng nhập lại';
  const needConsent = () =>
    new ApiError('BUSINESS_RULE_VIOLATION', 'Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng (BR-TK-02)', 400);

  beforeEach(() => vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'test-client'));

  /** GIS giả nạp bất đồng bộ: chờ nút hiện rồi mới bấm. */
  async function clickGoogle(google: ReturnType<typeof stubGoogle>, claims: Record<string, unknown>) {
    await screen.findByRole('button', { name: 'Google' });
    google.click(fakeGoogleCredential(claims));
  }
  async function tickBoth(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
  }

  it('hidesGoogleWhenNoClientId', () => {
    vi.stubEnv('VITE_GOOGLE_CLIENT_ID', '');
    const google = stubGoogle();
    renderPage();

    expect(screen.queryByText('hoặc')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Google' })).not.toBeInTheDocument();
    expect(google.scriptCount()).toBe(0);
  });

  it('existingGmailAccountGoesHome', async () => {
    const s = load();
    s.registered.push({ id: 100, email: 'lan@gmail.com', fullName: 'Lan', password: MOCK_PASSWORD, active: true });
    save(s);
    const success = vi.spyOn(toast, 'success').mockImplementation(() => '');
    const google = stubGoogle();
    renderPage();
    expect(await screen.findByText('hoặc')).toBeInTheDocument();

    await clickGoogle(google, { sub: 'g1', email: 'lan@gmail.com' });

    expect(await screen.findByText('Trang chủ')).toBeInTheDocument();
    expect(useSession.getState().account).toMatchObject({ id: 100, email: 'lan@gmail.com' });
    expect(success).toHaveBeenCalledWith('Đăng nhập thành công!');
  });

  it('googleLoginRedirectsToFrom', async () => {
    const s = load();
    s.registered.push({ id: 100, email: 'lan@gmail.com', fullName: 'Lan', password: MOCK_PASSWORD, active: true });
    save(s);
    const google = stubGoogle();
    renderPage('/me/bookings');
    expect(await screen.findByText('hoặc')).toBeInTheDocument();

    await clickGoogle(google, { sub: 'g1', email: 'lan@gmail.com' });

    expect(await screen.findByText('Trang lịch hẹn')).toBeInTheDocument(); // về trang khách đang mở, không về `/`
    expect(screen.queryByText('Trang chủ')).not.toBeInTheDocument();
    expect(useSession.getState().account).toMatchObject({ id: 100 });
  });

  it('newGmailOpensConsentThenGoesHome', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);

    expect(await screen.findByText('Hoàn tất đăng ký bằng Google')).toBeInTheDocument();
    const next = screen.getByRole('button', { name: 'Tiếp tục' });
    expect(next).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /đủ 18 tuổi/ }));
    expect(next).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /Điều khoản sử dụng/ }));
    expect(next).toBeEnabled();
    await user.click(next);

    expect(await screen.findByText('Trang chủ')).toBeInTheDocument();
    expect(useSession.getState().account).toMatchObject({ email: NEW.email, role: 'CUSTOMER' });
    expect(error).not.toHaveBeenCalled(); // submit của hộp thoại (portal) không được nổi bọt lên form đăng nhập
  });

  it('linkDecisionGoesToAccountLink', async () => {
    const phone = customerOfAccount(10)!.phone!; // khachmoi@petcare.vn đang chờ quyết định liên kết với hồ sơ tại quầy cùng SĐT
    expect(linkCandidates(phone).length).toBeGreaterThan(0);
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await user.type(screen.getByPlaceholderText('0901 234 567'), phone.replace(/^(\d{4})(\d{3})(\d{3})$/, '$1 $2 $3'));
    await tickBoth(user);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));

    expect(await screen.findByText('Liên kết hồ sơ')).toBeInTheDocument();
    expect(screen.queryByText('Trang chủ')).not.toBeInTheDocument();
  });

  it('expiredTokenInDialogClosesAndToasts', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    vi.spyOn(authApi, 'google')
      .mockRejectedValueOnce(needConsent())
      .mockRejectedValueOnce(new ApiError('UNAUTHENTICATED', 'Đăng nhập Google không thành công. Vui lòng thử lại', 401));
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await tickBoth(user);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(error).toHaveBeenCalledTimes(1); // BR-TK-02 đầu tiên mở hộp thoại chứ không báo lỗi
    expect(error).toHaveBeenCalledWith(EXPIRED);
    expect(useSession.getState().account).toBeNull();
  });

  it('otherErrorInDialogKeepsDialogOpenAndToasts', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    vi.spyOn(authApi, 'google')
      .mockRejectedValueOnce(needConsent())
      .mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.', 500));
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await tickBoth(user);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('Máy chủ đang gặp sự cố. Vui lòng thử lại sau.'));
    expect(error).toHaveBeenCalledTimes(1); // không phải câu "hết hạn": lỗi khác 401 không đóng hộp thoại
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tiếp tục' })).toBeEnabled(); // khách thử lại được với cùng credential
    expect(useSession.getState().account).toBeNull();
  });

  it('first401OutsideDialogShowsGenericMessageNotExpired', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    vi.spyOn(authApi, 'google').mockRejectedValueOnce(
      new ApiError('UNAUTHENTICATED', 'Đăng nhập Google không thành công. Vui lòng thử lại', 401),
    );
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);

    await waitFor(() => expect(error).toHaveBeenCalledTimes(1));
    expect(error).toHaveBeenCalledWith('Đăng nhập Google không thành công. Vui lòng thử lại');
    expect(error).not.toHaveBeenCalledWith(EXPIRED);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useSession.getState().account).toBeNull();
  });

  it('ignoresSecondCredentialWhileBusy', async () => {
    const send = vi.spyOn(authApi, 'google').mockReturnValue(new Promise(() => {}));
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);
    google.click(fakeGoogleCredential({ ...NEW, sub: 'g-other' }));

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(expect.objectContaining({ credential: fakeGoogleCredential(NEW) }));
  });

  it('closingDialogDropsCredential', async () => {
    const send = vi.spyOn(authApi, 'google');
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();
    const first = fakeGoogleCredential(NEW);
    const second = fakeGoogleCredential({ ...NEW, sub: 'g-second', email: 'khach.hai@gmail.com' });

    await clickGoogle(google, NEW);
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await user.click(screen.getByRole('button', { name: 'Đóng hộp thoại' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    google.click(second);
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await tickBoth(user);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));

    expect(await screen.findByText('Trang chủ')).toBeInTheDocument();
    expect(send.mock.calls.map(([req]) => [req.credential, req.isAdult])).toEqual([
      [first, undefined],
      [second, undefined],
      [second, true], // credential cũ không bao giờ được gửi lại
    ]);
    expect(useSession.getState().account?.email).toBe('khach.hai@gmail.com');
  });

  it('staffEmailShowsBrTk17WithoutCode', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => '');
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, { sub: 'g-staff', email: 'admin@petcare.vn' });

    await waitFor(() => expect(error).toHaveBeenCalledTimes(1));
    const text = String(error.mock.calls[0][0]);
    expect(text).toBe('Tài khoản nhân viên đăng nhập bằng email và mật khẩu');
    expect(text).not.toContain('BR-TK-17');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(useSession.getState().account).toBeNull();
  });

  it('badPhoneInDialogIsRefusedBeforeSending', async () => {
    const send = vi.spyOn(authApi, 'google');
    const user = userEvent.setup();
    const google = stubGoogle();
    renderPage();

    await clickGoogle(google, NEW);
    await screen.findByText('Hoàn tất đăng ký bằng Google');
    await user.type(screen.getByPlaceholderText('0901 234 567'), '12345');
    await tickBoth(user);
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }));

    expect(await screen.findByText('Số điện thoại phải gồm 10 số và bắt đầu bằng 0.')).toBeInTheDocument();
    expect(send).toHaveBeenCalledTimes(1); // chỉ lần đầu (chưa đồng ý)
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
