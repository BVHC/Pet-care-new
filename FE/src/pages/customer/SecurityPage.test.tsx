import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { SecurityPage } from './SecurityPage';

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});

async function changePassword(current: string, next: string) {
  const user = userEvent.setup();
  render(
    <MemoryRouter>
      <SecurityPage />
    </MemoryRouter>,
  );
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
