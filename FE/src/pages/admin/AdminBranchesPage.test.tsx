import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminBranchesPage } from './AdminBranchesPage';

// Dữ liệu mẫu ở shared/api/mock/branch.mock.ts: Cầu Giấy ACTIVE, Hà Đông DRAFT có quản lý, Long Biên DRAFT chưa có.
beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/branches']}>
        <Routes>
          <Route path="/admin/branches" element={<AdminBranchesPage />} />
          <Route path="/admin/branches/:branchId/:tab?" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;

describe('chain manager (UC12)', () => {
  beforeEach(() => signIn('chuoi@petcare.vn'));

  it('lists the branches with their status and emergency flag', async () => {
    renderList();
    expect(within(await rowOf('PetCare Cầu Giấy')).getByText('Đang hoạt động')).toBeInTheDocument();
    expect(within(await rowOf('PetCare Cầu Giấy')).getByText('Có')).toBeInTheDocument();
    expect(within(await rowOf('PetCare Long Biên')).getByText('Nháp')).toBeInTheDocument();
  });

  it('creates a draft branch', async () => {
    const user = userEvent.setup();
    renderList();
    await user.click(await screen.findByRole('button', { name: 'Thêm chi nhánh' }));
    const form = screen.getByRole('dialog');
    await user.type(within(form).getByLabelText('Tên chi nhánh'), 'PetCare Tây Hồ');
    await user.type(within(form).getByLabelText('Địa chỉ'), '12 Xuân Diệu, Tây Hồ');
    await user.type(within(form).getByLabelText('Số điện thoại'), '0243123456');
    await user.type(within(form).getByLabelText('Vĩ độ'), '21.06');
    await user.type(within(form).getByLabelText('Kinh độ'), '105.83');
    await user.click(within(form).getByRole('button', { name: 'Tạo chi nhánh' }));

    expect(within(await rowOf('PetCare Tây Hồ')).getByText('Nháp')).toBeInTheDocument();
  });

  it('checks the phone number before calling the BE', async () => {
    const user = userEvent.setup();
    renderList();
    await user.click(await screen.findByRole('button', { name: 'Thêm chi nhánh' }));
    const form = screen.getByRole('dialog');
    await user.type(within(form).getByLabelText('Tên chi nhánh'), 'PetCare Tây Hồ');
    await user.type(within(form).getByLabelText('Địa chỉ'), '12 Xuân Diệu');
    await user.type(within(form).getByLabelText('Số điện thoại'), '12345');
    await user.type(within(form).getByLabelText('Vĩ độ'), '21.06');
    await user.type(within(form).getByLabelText('Kinh độ'), '105.83');
    await user.click(within(form).getByRole('button', { name: 'Tạo chi nhánh' }));

    expect(within(form).getByRole('alert')).toHaveTextContent('Số điện thoại gồm 10–11 số, bắt đầu bằng 0');
  });

  it('tells what a branch still lacks before it can be activated (BR-QT-04, BR-CN-01)', async () => {
    const user = userEvent.setup();
    renderList();
    await user.click(within(await rowOf('PetCare Long Biên')).getByRole('button', { name: 'Kích hoạt PetCare Long Biên' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Kích hoạt' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Không kích hoạt được chi nhánh: chưa có BRANCH_MANAGER, chưa cấu hình giờ mở cửa',
    );
    expect(within(await rowOf('PetCare Long Biên')).getByText('Nháp')).toBeInTheDocument();
  });

  it('opens the configuration of a branch', async () => {
    const user = userEvent.setup();
    renderList();
    await user.click(within(await rowOf('PetCare Hà Đông')).getByRole('button', { name: 'Cấu hình PetCare Hà Đông' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/branches/2');
  });
});

describe('branch manager', () => {
  it('goes straight to their own branch', async () => {
    await signIn('manager@store1.vn');
    renderList();
    expect(await screen.findByTestId('where')).toHaveTextContent('/admin/branches/1');
  });
});
