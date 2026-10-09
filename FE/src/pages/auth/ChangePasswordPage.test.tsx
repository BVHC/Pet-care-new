import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ChangePasswordPage } from './ChangePasswordPage';

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'newstaff@store1.vn', password: MOCK_PASSWORD });
});

function renderPage() {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/auth/change-password', state: { from: '/staff/reception?tab=pos' } }]}>
      <Routes>
        <Route path="/auth/change-password" element={<ChangePasswordPage />} />
        <Route path="/staff/reception" element={<p>Bàn lễ tân</p>} />
        <Route path="/auth/login" element={<p>Trang đăng nhập</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function submit(current: string, next: string, confirm = next) {
  const user = userEvent.setup();
  await user.type(screen.getByPlaceholderText('Nhập mật khẩu hiện tại'), current);
  await user.type(screen.getByPlaceholderText('Ít nhất 8 ký tự, có chữ và số'), next);
  await user.type(screen.getByPlaceholderText('Nhập lại mật khẩu mới'), confirm);
  await user.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
}

describe('forced password change (BR-TK-17)', () => {
  it('changes the password, lifts the block and goes back to the page the user wanted', async () => {
    renderPage();
    await submit(MOCK_PASSWORD, 'MatKhauMoi1');

    expect(await screen.findByText('Bàn lễ tân')).toBeInTheDocument();
    expect(useSession.getState().account?.mustChangePassword).toBe(false);
  });

  it('shows the BE message when the current password is wrong (BR-TK-14)', async () => {
    renderPage();
    await submit('Sai12345', 'MatKhauMoi1');

    expect(await screen.findByRole('alert')).toHaveTextContent('Mật khẩu hiện tại không đúng');
    expect(useSession.getState().account?.mustChangePassword).toBe(true);
  });

  it('does not call the API when the confirmation does not match', async () => {
    renderPage();
    await submit(MOCK_PASSWORD, 'MatKhauMoi1', 'MatKhauMoi2');

    expect(screen.getByRole('alert')).toHaveTextContent('Mật khẩu nhập lại không khớp');
    expect(useSession.getState().account?.mustChangePassword).toBe(true);
  });

  it('sends a visitor without a session to the login page', () => {
    useSession.getState().clear();
    renderPage();
    expect(screen.getByText('Trang đăng nhập')).toBeInTheDocument();
  });
});
