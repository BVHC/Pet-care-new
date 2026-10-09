import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../shared/api/axios';
import { MOCK_PASSWORD } from '../shared/api/mock/identity.mock';
import { installMockApi } from '../shared/api/mock/mock-api';
import { useSession } from '../shared/stores/session.store';
import { App } from './App';

// Nghiệm thu T5 (docs/time.md): chưa đăng nhập → Đăng nhập; sai role → 403;
// mustChangePassword → màn đổi mật khẩu.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());

const signInAs = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

const forbidden = () => screen.findByRole('heading', { name: 'Bạn không có quyền vào trang này' });

describe('route guards', () => {
  it('sends a visitor from a customer page to the login page', async () => {
    open('/pets');
    expect(await screen.findByRole('heading', { name: 'Chào mừng trở lại' })).toBeInTheDocument();
  });

  it('sends a visitor from a workspace to the staff login page', async () => {
    open('/staff/doctor');
    expect(await screen.findByRole('heading', { name: 'Chọn tài khoản demo' })).toBeInTheDocument();
  });

  it('shows 403 to staff on a customer-only page', async () => {
    await signInAs('doctor@store1.vn');
    open('/pets');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('shows 403 to a customer in the back office', async () => {
    await signInAs('khach@petcare.vn');
    open('/admin/dashboard');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('keeps a receptionist out of the exam room', async () => {
    await signInAs('reception@store1.vn');
    open('/staff/doctor');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('gives the branch manager no pass to pages of other roles', async () => {
    await signInAs('manager@store1.vn');
    open('/admin/audit');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('lets the technical admin read the audit log (UC11)', async () => {
    await signInAs('admin@petcare.vn');
    open('/admin/audit');
    expect(await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Bạn không có quyền vào trang này' })).not.toBeInTheDocument();
  });

  it('forces the password change before any page (BR-TK-17)', async () => {
    await signInAs('newstaff@store1.vn');
    open('/staff/reception');
    expect(await screen.findByRole('button', { name: 'Đổi mật khẩu' })).toBeInTheDocument();
  });
});
