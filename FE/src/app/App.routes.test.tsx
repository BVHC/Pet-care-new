import { render, screen, within } from '@testing-library/react';
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

// UC15 (T46): dịch vụ, chi nhánh, bác sĩ là trang công khai, khách vãng lai xem được.
describe('public pages (UC15)', () => {
  it.each([
    ['/services', 'Dịch vụ tại PetCare'],
    ['/branches', 'Hệ thống chi nhánh'],
    ['/doctors', 'Đội ngũ bác sĩ'],
  ])('opens %s for a visitor without sending them to the login page', async (path, title) => {
    open(path);
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Chào mừng trở lại' })).not.toBeInTheDocument();
  });

  it.each([
    ['banner', 'Dịch vụ', 'Dịch vụ tại PetCare'],
    ['banner', 'Chi nhánh', 'Hệ thống chi nhánh'],
    ['banner', 'Bác sĩ', 'Đội ngũ bác sĩ'],
    ['contentinfo', 'Dịch vụ', 'Dịch vụ tại PetCare'],
    ['contentinfo', 'Chi nhánh', 'Hệ thống chi nhánh'],
    ['contentinfo', 'Đội ngũ bác sĩ', 'Đội ngũ bác sĩ'],
  ])('reaches the page from the %s link "%s"', async (landmark, link, title) => {
    open('/help');
    await userEvent.click(within(await screen.findByRole(landmark)).getByRole('link', { name: link }));
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument();
  });
});

// UC16–17 (T46): cửa hàng, tin tức và bài viết là trang công khai, khách vãng lai xem được (ADR-0005).
describe('public pages (UC16–17)', () => {
  it.each([
    ['/shop', 'Cửa hàng của boss'],
    ['/shop/1', 'Hạt Royal Canin Mini Adult 2kg'],
    ['/news', 'Kiến thức chăm sóc thú cưng'],
    ['/news/royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung', 'Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?'],
  ])('opens %s for a visitor without sending them to the login page', async (path, title) => {
    open(path);
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Chào mừng trở lại' })).not.toBeInTheDocument();
  });

  it('shows the not-found frame (not the login page) for a draft article slug', async () => {
    open('/news/meo-cao-sofa-5-cach-ngan-chan');
    expect(await screen.findByRole('heading', { level: 1, name: 'Không tìm thấy bài viết' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Chào mừng trở lại' })).not.toBeInTheDocument();
  });
});

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

  it('sends the old purchasing page to the stock receipts of the warehouse (T24)', async () => {
    await signInAs('manager@store1.vn');
    open('/admin/purchasing');
    expect(await screen.findByRole('tab', { name: 'Phiếu nhập', selected: true })).toBeInTheDocument();
  });

  it('keeps a vet out of the warehouse (UC75 is A05, A06)', async () => {
    await signInAs('doctor@store1.vn');
    open('/admin/warehouse');
    expect(await forbidden()).toBeInTheDocument();
  });

  it('opens the read-only customer lookup for a vet (UC23)', async () => {
    await signInAs('doctor@store1.vn');
    open('/admin/customers');
    expect(await screen.findByRole('heading', { name: 'Tra cứu khách & thú cưng' })).toBeInTheDocument();
  });

  it('keeps the receptionist on the desk for customers, not the read-only page', async () => {
    await signInAs('reception@store1.vn');
    open('/admin/customers');
    expect(await forbidden()).toBeInTheDocument();
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

// T47: quản trị nội dung, feedback và Care Task. Mỗi trang giữ một <h1> cố định (route test dựa vào đó); quyền theo ADMIN_MENU.
describe('content, feedback and care task pages (T47)', () => {
  it.each([
    ['chuoi@petcare.vn', '/admin/page-contents', 'Nội dung trang'],
    ['chuoi@petcare.vn', '/admin/articles', 'Bài viết'],
    ['chuoi@petcare.vn', '/admin/articles/categories', 'Bài viết'],
    ['chuoi@petcare.vn', '/admin/feedbacks', 'Góp ý của khách'],
    ['manager@store1.vn', '/admin/feedbacks', 'Góp ý của khách'],
    ['reception@store1.vn', '/admin/care-tasks', 'Việc gọi điện'],
  ])('lets %s open %s', async (email, path, title) => {
    await signInAs(email);
    open(path);
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Bạn không có quyền vào trang này' })).not.toBeInTheDocument();
  });

  it.each([
    ['manager@store1.vn', '/admin/page-contents'],
    ['manager@store1.vn', '/admin/articles'],
    // BR-TB-05: chỉ lễ tân của chi nhánh được giao xem và làm Care Task.
    ['manager@store1.vn', '/admin/care-tasks'],
    ['chuoi@petcare.vn', '/admin/care-tasks'],
    ['reception@store1.vn', '/admin/feedbacks'],
    // ADMIN kỹ thuật không vào được các endpoint content (BE: hasRole SUPER_MANAGER, không có RoleHierarchy).
    ['admin@petcare.vn', '/admin/feedbacks'],
    ['admin@petcare.vn', '/admin/articles'],
    ['doctor@store1.vn', '/admin/page-contents'],
  ])('shows 403 in place to %s on %s', async (email, path) => {
    await signInAs(email);
    open(path);
    expect(await forbidden()).toBeInTheDocument();
  });
});
