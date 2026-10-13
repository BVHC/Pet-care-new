import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  it('lets the chain manager manage the catalog (UC28–UC31)', async () => {
    await signInAs('chuoi@petcare.vn');
    open('/admin/catalog');
    expect(await screen.findByRole('heading', { name: 'Sản phẩm & dịch vụ' })).toBeInTheDocument();
  });

  it('keeps other staff out of catalog management', async () => {
    await signInAs('reception@store1.vn');
    open('/admin/catalog/products');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('opens branch management for the chain manager (UC12)', async () => {
    await signInAs('chuoi@petcare.vn');
    open('/admin/branches');
    expect(await screen.findByRole('heading', { name: 'Chi nhánh' })).toBeInTheDocument();
  });

  it('takes a branch manager to the configuration of their branch (UC14, UC33, UC42)', async () => {
    await signInAs('manager@store1.vn');
    open('/admin/branches');
    expect(await screen.findByRole('heading', { name: 'PetCare Cầu Giấy' })).toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: 'Quota lịch hẹn' })).toBeInTheDocument();
  });

  it("never shows the previous user's data to the next user in the same tab", async () => {
    await signInAs('chuoi@petcare.vn');
    const first = render(
      <MemoryRouter initialEntries={['/admin/branches']}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByRole('button', { name: 'Cấu hình PetCare Long Biên' })).toBeInTheDocument();
    first.unmount();

    await signInAs('manager@store1.vn');
    open('/admin/branches');
    expect(await screen.findByRole('heading', { name: 'PetCare Cầu Giấy' })).toBeInTheDocument();
  });

  it('keeps a receptionist out of branch management', async () => {
    await signInAs('reception@store1.vn');
    open('/admin/branches');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('forces the password change before any page (BR-TK-17)', async () => {
    await signInAs('newstaff@store1.vn');
    open('/staff/reception');
    expect(await screen.findByRole('button', { name: 'Đổi mật khẩu' })).toBeInTheDocument();
  });

  it('takes staff from /account to their profile in the back office (UC06)', async () => {
    await signInAs('doctor@store1.vn');
    open('/account');
    expect(await screen.findByRole('heading', { name: 'Hồ sơ cá nhân' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Hồ sơ giới thiệu công khai' })).toBeInTheDocument();
  });

  it('links the workspace to the staff profile (UC06)', async () => {
    await signInAs('doctor@store1.vn');
    open('/staff/doctor');
    expect(await screen.findByRole('link', { name: 'Hồ sơ cá nhân' })).toHaveAttribute('href', '/admin/profile');
  });

  it('opens the customer profile with the address book (UC06)', async () => {
    await signInAs('khach@petcare.vn');
    open('/account/addresses');
    expect(await screen.findByRole('heading', { name: 'Sổ địa chỉ' })).toBeInTheDocument();
  });

  it('reminds of the link decision at each login (BR-TK-19)', async () => {
    const user = userEvent.setup();
    open('/auth/login');
    await user.type(await screen.findByLabelText('Email'), 'khachmoi@petcare.vn');
    await user.type(screen.getByLabelText('Mật khẩu'), MOCK_PASSWORD);
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('heading', { name: 'Liên kết hồ sơ' })).toBeInTheDocument();
  });

  it('hides pet management while a link decision is pending (BR-TK-19)', async () => {
    await signInAs('khachmoi@petcare.vn');
    open('/pets');
    expect(await screen.findByRole('heading', { name: 'Hoàn tất liên kết hồ sơ' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đến bước liên kết hồ sơ' })).toHaveAttribute('href', '/account/link');
  });
});
