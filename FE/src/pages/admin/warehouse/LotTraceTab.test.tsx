import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { LotTraceTab } from './LotTraceTab';

// Sổ lô mẫu (inventory.mock.ts) của chi nhánh 1: lô 13 Amoxicillin AMX2405 (nhập 330, bán cho Lu của Lê Hoàng Long 60 và Miu của Lê Thị Hoa 40, chuyển kho #3 30, còn 200),
// lô 14 AMX2501, lô 15 / 16 Rabisin RB2401 / RB2309, lô 17 Vanguard VG2402, lô 11 / 12 là hàng hóa không số lô.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <LotTraceTab />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('LotTraceTab (UC79)', () => {
  it('quản lý: bắt buộc số lô hoặc sản phẩm; tìm theo số lô rồi xem đường đi kèm tên thú, tên khách', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await user.click(screen.getByRole('button', { name: 'Tìm lô' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Nhập số lô hoặc chọn sản phẩm để tìm lô');
    expect(screen.queryByRole('table', { name: 'Kết quả tìm lô' })).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Số lô'), 'amx24');
    await user.click(screen.getByRole('button', { name: 'Tìm lô' }));
    const results = await screen.findByRole('table', { name: 'Kết quả tìm lô' });
    expect(await within(results).findByText('AMX2405')).toBeInTheDocument();
    expect(within(results).queryByText('AMX2501')).not.toBeInTheDocument();

    await user.click(within(results).getByRole('button', { name: /Xem đường đi lô AMX2405/ }));
    const path = await screen.findByRole('table', { name: 'Đường đi của lô' });
    expect(within(path).getByText('Nhập kho')).toBeInTheDocument();
    expect(within(path).getByText('Xuất chuyển kho')).toBeInTheDocument();
    expect(within(path).getByText('Lu — Lê Hoàng Long')).toBeInTheDocument();
    expect(within(path).getByText('Miu — Lê Thị Hoa')).toBeInTheDocument();
    expect(screen.getByText(/còn lại 200/)).toBeInTheDocument();
  });

  it('quản lý tìm theo sản phẩm hàng hóa thì thấy lô mặc định, không có cột thú / khách', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await user.selectOptions(await screen.findByLabelText('Sản phẩm'), await screen.findByRole('option', { name: 'Hạt Royal Canin Mini Adult 2kg' }));
    await user.click(screen.getByRole('button', { name: 'Tìm lô' }));
    await user.click(await screen.findByRole('button', { name: /Xem đường đi lô mặc định Hạt Royal Canin/ }));

    const path = await screen.findByRole('table', { name: 'Đường đi của lô' });
    expect(within(path).queryByText('Thú / khách')).not.toBeInTheDocument();
  });

  it('bác sĩ: ô chọn sản phẩm chỉ có thuốc, vaccine và tìm theo số lô không lộ lô hàng hóa', async () => {
    await signIn('doctor@store1.vn');
    const user = renderTab();
    expect(await screen.findByRole('option', { name: 'Vaccine Dại Rabisin' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Hạt Royal Canin Mini Adult 2kg' })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Sản phẩm'), 'Vaccine Dại Rabisin');
    await user.click(screen.getByRole('button', { name: 'Tìm lô' }));
    const results = await screen.findByRole('table', { name: 'Kết quả tìm lô' });
    expect(await within(results).findByText('RB2401')).toBeInTheDocument();
    expect(within(results).getByText('RB2309')).toBeInTheDocument();
    expect(within(results).queryByText('Lô mặc định')).not.toBeInTheDocument();
  });

  it('vai trò không được truy vết (đổi quyền giữa chừng): lỗi 403 của BE hiện trong bảng, có nút thử lại', async () => {
    await signIn('doctor@store1.vn');
    const user = renderTab();
    await user.type(screen.getByLabelText('Số lô'), 'AMX');
    await signIn('reception@store1.vn');
    await user.click(screen.getByRole('button', { name: 'Tìm lô' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được danh sách lô');
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
  });
});
