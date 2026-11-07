import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminWarehousePage } from './AdminWarehousePage';

// Tab kho tầng 3 theo vai trò (UC77–79, ST07): quản lý thấy cả tám tab; bác sĩ chỉ có Truy vết lô; lễ tân chỉ có Tồn kho.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/admin/warehouse/:tab?"
            element={
              <>
                <AdminWarehousePage />
                <Where />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const tabNames = () => screen.getAllByRole('tab').map((t) => t.textContent);

describe('AdminWarehousePage — tab theo vai trò (UC77–79)', () => {
  it('bác sĩ: mở thẳng Truy vết lô, tab khác (kể cả đường dẫn cũ /stock) bị đưa về đó và không có tab quản lý', async () => {
    await signIn('doctor@store1.vn');
    renderAt('/admin/warehouse/stock?productType=VACCINE');
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/warehouse/trace?productType=VACCINE'));
    expect(tabNames()).toEqual(['Truy vết lô']);
    expect(screen.getByRole('button', { name: 'Tìm lô' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tạo phiếu chuyển kho' })).not.toBeInTheDocument();
  });

  it('bác sĩ gõ thẳng /counts hoặc /shortages cũng không vào được', async () => {
    await signIn('doctor@store1.vn');
    renderAt('/admin/warehouse/counts');
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/warehouse/trace'));
    expect(screen.queryByRole('button', { name: 'Mở phiên kiểm kê' })).not.toBeInTheDocument();
  });

  it('lễ tân: chỉ Tồn kho, gõ /transfers bị đưa về tồn kho', async () => {
    await signIn('reception@store1.vn');
    renderAt('/admin/warehouse/transfers');
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/warehouse/stock'));
    expect(tabNames()).toEqual(['Tồn kho']);
  });

  it('quản lý: đủ tám tab và bấm sang từng tab mới dựng nội dung của tab đó', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse/transfers');
    expect(tabNames()).toEqual(['Tồn kho', 'Phiếu nhập', 'Điều chỉnh tồn', 'Nhà cung cấp', 'Chuyển kho', 'Kiểm kê', 'Truy vết lô', 'Thiếu hụt vật tư']);
    expect(await screen.findByRole('button', { name: 'Tạo phiếu chuyển kho' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Thiếu hụt vật tư' }));
    expect(await screen.findByRole('table', { name: 'Thiếu hụt vật tư' })).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/warehouse/shortages');

    await user.click(screen.getByRole('tab', { name: 'Kiểm kê' }));
    expect(await within(screen.getByRole('tabpanel')).findByRole('button', { name: 'Mở phiên kiểm kê' })).toBeInTheDocument();
  });

  it('phiếu điều chỉnh tay không cho chọn lý do "Chênh lệch kiểm kê" (do xác nhận kiểm kê sinh ra)', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse/adjustments');
    await user.click(await screen.findByRole('button', { name: 'Lập phiếu điều chỉnh' }));
    const form = await screen.findByRole('dialog');
    expect(within(form).getByRole('option', { name: 'Hỏng' })).toBeInTheDocument();
    expect(within(form).queryByRole('option', { name: 'Chênh lệch kiểm kê' })).not.toBeInTheDocument();
    expect(within(form).queryByText(/chưa có ở giai đoạn này/)).not.toBeInTheDocument();
  });
});
