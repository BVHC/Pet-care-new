import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { customerPetApi } from '../../domains/pets/customer-pet.api';
import { apiClient } from '../../shared/api/axios';
import { custmergeApi } from '../../shared/api/custmerge.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminCustomersPage } from './AdminCustomersPage';

// UC27 — gộp hồ sơ khách trùng. Khách mẫu #1–#4 (customer.mock.ts): #1 và #2 đều có tài khoản; hồ sơ quầy mới tạo đầu tiên là #5, kế tiếp #6.
// Lễ tân tạo hai hồ sơ quầy trùng SĐT; quản lý chi nhánh xem trước rồi gộp.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

/** Lễ tân tạo hai hồ sơ trùng SĐT (#5 "Phạm Văn An", #6 "Phạm V. An"), rồi đăng nhập lại là quản lý chi nhánh 1. */
async function twoDuplicates() {
  await signIn('reception@store1.vn');
  await customerPetApi.createCounterCustomer({ fullName: 'Phạm Văn An', phone: '0933444555' });
  await customerPetApi.createCounterCustomer({ fullName: 'Phạm V. An', phone: '0933444555' });
  await signIn('manager@store1.vn');
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminCustomersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

async function preview(user: ReturnType<typeof renderPage>, target: string, source: string) {
  await user.type(screen.getByLabelText('Mã hồ sơ đích (giữ lại)'), target);
  await user.type(screen.getByLabelText('Mã hồ sơ nguồn (bị gộp)'), source);
  await user.click(screen.getByRole('button', { name: 'Xem trước gộp' }));
}

describe('AdminCustomersPage — gộp hồ sơ (UC27)', () => {
  it('quản lý chi nhánh chỉ thấy Gộp hồ sơ (không tra cứu, UC23 không có A05); bác sĩ và nhân viên chăm sóc không thấy Gộp hồ sơ', async () => {
    await signIn('doctor@store1.vn');
    renderPage();
    expect(screen.queryByLabelText('Mã hồ sơ đích (giữ lại)')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Tra cứu theo SĐT')).toBeInTheDocument();
  });

  it('quản lý chi nhánh vào thẳng Gộp hồ sơ, không có ô tra cứu', async () => {
    await signIn('manager@store1.vn');
    renderPage();
    expect(await screen.findByLabelText('Mã hồ sơ đích (giữ lại)')).toBeInTheDocument();
    expect(screen.queryByLabelText('Tra cứu theo SĐT')).not.toBeInTheDocument();
  });

  it('chọn hai hồ sơ trùng, xem trước số bản ghi và cảnh báo, rồi xác nhận gộp: nguồn bị khóa', async () => {
    await twoDuplicates();
    const user = renderPage();
    await preview(user, '6', '5');

    expect(await screen.findByRole('heading', { name: 'Gộp hồ sơ #5 vào hồ sơ #6' })).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Dữ liệu sẽ chuyển' });
    expect(within(table).getByText('Thú cưng').nextElementSibling).toHaveTextContent('0');
    expect(screen.getByRole('list', { name: 'Cảnh báo' })).toHaveTextContent('Họ tên hai hồ sơ khác nhau');
    expect(screen.queryByRole('list', { name: 'Điều kiện chặn' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Gộp hồ sơ' }));
    const confirm = await screen.findByRole('dialog');
    expect(confirm).toHaveTextContent('Không thể hoàn tác');
    await user.click(within(confirm).getByRole('button', { name: 'Xác nhận gộp' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã gộp hồ sơ #5 vào hồ sơ #6');
    expect(screen.queryByRole('heading', { name: /Gộp hồ sơ #5/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Mã hồ sơ đích (giữ lại)')).toHaveValue('');

    // Xem lại cặp đó: hồ sơ nguồn đã bị khóa nên BE đưa vào điều kiện chặn.
    await user.type(screen.getByLabelText('Mã hồ sơ đích (giữ lại)'), '6');
    await user.type(screen.getByLabelText('Mã hồ sơ nguồn (bị gộp)'), '5');
    await user.click(screen.getByRole('button', { name: 'Xem trước gộp' }));
    expect(await screen.findByRole('list', { name: 'Điều kiện chặn' })).toHaveTextContent('đã được gộp vào hồ sơ #6');
    expect(screen.getByRole('button', { name: 'Gộp hồ sơ' })).toBeDisabled();
  });

  it('hai hồ sơ cùng có tài khoản: có điều kiện chặn và nút Gộp hồ sơ bị khóa', async () => {
    await signIn('manager@store1.vn');
    const user = renderPage();
    await preview(user, '1', '2');

    expect(await screen.findByRole('list', { name: 'Điều kiện chặn' })).toHaveTextContent('cả hai hồ sơ đã liên kết tài khoản');
    expect(screen.getByRole('button', { name: 'Gộp hồ sơ' })).toBeDisabled();
  });

  it('mã sai: nguồn trùng đích hoặc không phải số bị chặn tại form; hồ sơ không có thì BE trả 404 ngay dưới form', async () => {
    await signIn('manager@store1.vn');
    const user = renderPage();
    await preview(user, '3', '3');
    expect(screen.getByRole('alert')).toHaveTextContent('Hồ sơ nguồn phải khác hồ sơ đích');

    await user.clear(screen.getByLabelText('Mã hồ sơ nguồn (bị gộp)'));
    await user.type(screen.getByLabelText('Mã hồ sơ nguồn (bị gộp)'), 'abc');
    await user.click(screen.getByRole('button', { name: 'Xem trước gộp' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Mã hồ sơ là số nguyên dương');

    await user.clear(screen.getByLabelText('Mã hồ sơ nguồn (bị gộp)'));
    await user.type(screen.getByLabelText('Mã hồ sơ nguồn (bị gộp)'), '999');
    await user.click(screen.getByRole('button', { name: 'Xem trước gộp' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Không tìm thấy/);
  });

  it('người khác gộp cặp này trước: xác nhận nhận 400 BR-KH-11 và lỗi hiện trong hộp, hộp không đóng', async () => {
    await twoDuplicates();
    const user = renderPage();
    await preview(user, '6', '5');
    await user.click(await screen.findByRole('button', { name: 'Gộp hồ sơ' }));
    const confirm = await screen.findByRole('dialog');
    await custmergeApi.merge({ sourceCustomerId: 5, targetCustomerId: 6, confirm: true });
    await user.click(within(confirm).getByRole('button', { name: 'Xác nhận gộp' }));

    expect(await within(confirm).findByRole('alert')).toHaveTextContent('đã được gộp vào hồ sơ #6');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
