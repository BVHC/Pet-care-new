import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { feesApi } from '../../../shared/api/fees.api';
import { useSession } from '../../../shared/stores/session.store';
import { AdminBranchDetailPage } from '../AdminBranchDetailPage';

// Phí khám tại nhà mẫu (fees.mock.ts): chi nhánh 1 bật 100.000 đ, chi nhánh 2 tắt nhưng còn lưu 80.000 đ. Chỉ SUPER_MANAGER xem / lưu (BRANCH_MANAGER bị 403 theo yaml).

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/branches/:branchId/:tab?" element={<AdminBranchDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('Phí khám tại nhà ở tab Dịch vụ (UC32)', () => {
  it('SUPER_MANAGER thấy phí đang áp dụng của từng chi nhánh', async () => {
    await signIn('chuoi@petcare.vn');
    renderAt('/admin/branches/2/services');
    expect(await screen.findByLabelText('Phí khám tại nhà (VND)')).toHaveValue(80000);
    expect(screen.getByRole('checkbox', { name: 'Nhận đặt lịch khám tại nhà' })).not.toBeChecked();
  });

  it('đổi phí và bật: lưu xong hiện thông báo và BE trả đúng số mới', async () => {
    await signIn('chuoi@petcare.vn');
    const user = renderAt('/admin/branches/1/services');
    const input = await screen.findByLabelText('Phí khám tại nhà (VND)');
    expect(input).toHaveValue(100000);
    expect(screen.getByRole('button', { name: 'Lưu phí khám tại nhà' })).toBeDisabled();

    await user.clear(input);
    await user.type(input, '120000');
    await user.click(screen.getByRole('button', { name: 'Lưu phí khám tại nhà' }));

    expect(await screen.findByRole('status')).toHaveTextContent(/Đã lưu phí khám tại nhà: bật, 120\.000/);
    expect(await feesApi.getHomeVisitFee(1)).toEqual({ branchId: 1, enabled: true, fee: 120000 });
  });

  it('phí âm: BE từ chối 400 BR-SP-09, thông báo hiện dưới khối phí và phí cũ còn nguyên', async () => {
    await signIn('chuoi@petcare.vn');
    const user = renderAt('/admin/branches/1/services');
    const input = await screen.findByLabelText('Phí khám tại nhà (VND)');
    await user.clear(input);
    await user.type(input, '-5');
    await user.click(screen.getByRole('button', { name: 'Lưu phí khám tại nhà' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Phí khám tại nhà phải là số nguyên từ 0 trở lên');
    expect(await feesApi.getHomeVisitFee(1)).toMatchObject({ fee: 100000 });
  });

  it('để trống phí thì báo ngay, không gọi BE', async () => {
    await signIn('chuoi@petcare.vn');
    const user = renderAt('/admin/branches/1/services');
    await user.clear(await screen.findByLabelText('Phí khám tại nhà (VND)'));
    await user.click(screen.getByRole('button', { name: 'Lưu phí khám tại nhà' }));

    expect(screen.getByText('Vui lòng nhập phí khám tại nhà')).toBeInTheDocument();
  });

  it('quản lý chi nhánh không thấy khối phí khám tại nhà', async () => {
    await signIn('manager@store1.vn');
    renderAt('/admin/branches/1/services');
    expect(await screen.findByRole('checkbox', { name: 'Bật Chuồng mèo tại chi nhánh' })).toBeInTheDocument();
    expect(screen.queryByText('Phí khám tại nhà')).not.toBeInTheDocument();
  });
});
