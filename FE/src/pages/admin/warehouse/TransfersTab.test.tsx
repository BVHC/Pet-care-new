import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { stockopsApi } from '../../../shared/api/stockops.api';
import { useSession } from '../../../shared/stores/session.store';
import { vnDate } from '../../../shared/utils/vn-time';
import { TransfersTab } from './TransfersTab';

// Phiếu mẫu (stockops.mock.ts): #3 IN_TRANSIT chi nhánh 1 → 2 (30 Amoxicillin), #4 IN_TRANSIT 2 → 1 (3 Royal Canin), #2 đã nhận 1 → 2, #1 đã hủy 2 → 1.
// Chi nhánh 1 chỉ có quản lý manager@store1.vn; chi nhánh 2 là manager2@store2.vn.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};
beforeEach(() => signIn('manager@store1.vn'));

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <TransfersTab />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const open = async (user: ReturnType<typeof renderTab>, suffix: string) => {
  await user.click(await screen.findByRole('button', { name: new RegExp(`^Mở phiếu CK-\\d{6}-${suffix}$`) }));
  return screen.findByRole('dialog');
};

/** Chi nhánh 2 đặt giờ mở cửa, SUPER_MANAGER kích hoạt: chi nhánh 1 mới có đích hợp lệ (BR-KO-08). */
async function activateBranch2() {
  await signIn('manager2@store2.vn');
  const days = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, ranges: [{ open: '08:00', close: '17:00' }] }));
  await apiClient.put('/api/branches/2/opening-hours', { effectiveFrom: vnDate(), days });
  await signIn('chuoi@petcare.vn');
  await apiClient.post('/api/branches/2/activate');
  await signIn('manager@store1.vn');
}

describe('TransfersTab (UC77)', () => {
  it('liệt kê phiếu đi và đến của chi nhánh mình, lọc theo hướng và trạng thái', async () => {
    const user = renderTab();
    await screen.findByRole('button', { name: /-0003$/ });
    const rows = within(screen.getByRole('table', { name: 'Phiếu chuyển kho' })).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(rows.map((r) => r.textContent)).toEqual(
      expect.arrayContaining([expect.stringMatching(/-0003.*Chuyển đi.*Đang chuyển/), expect.stringMatching(/-0004.*Chuyển đến.*Đang chuyển/)]),
    );

    await user.selectOptions(screen.getByLabelText('Lọc hướng chuyển'), 'INCOMING');
    await waitFor(() => expect(screen.queryByRole('button', { name: /-0003$/ })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: /-0004$/ })).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái phiếu chuyển'), 'RECEIVED');
    await waitFor(() => expect(screen.queryByRole('button', { name: /-0004$/ })).not.toBeInTheDocument());
    expect(screen.getByText('Chưa có dữ liệu.')).toBeInTheDocument();
  });

  it('nhận hàng phiếu đến: phiếu chuyển sang Đã nhận, không còn nút nhận / hủy', async () => {
    const user = renderTab();
    const detail = await open(user, '0004');
    expect(await within(detail).findByText('Hạt Royal Canin Mini Adult 2kg')).toBeInTheDocument();
    await user.click(within(detail).getByRole('button', { name: 'Nhận hàng' }));

    expect(await screen.findByRole('status')).toHaveTextContent(/Đã nhận hàng phiếu CK-\d{6}-0004/);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /^Mở phiếu CK-\d{6}-0004$/ }));
    const again = await screen.findByRole('dialog');
    expect(await within(again).findByText('Đã nhận')).toBeInTheDocument();
    expect(within(again).queryByRole('button', { name: 'Nhận hàng' })).not.toBeInTheDocument();
    expect(within(again).queryByRole('button', { name: 'Hủy phiếu' })).not.toBeInTheDocument();
  });

  it('phiếu đi: chi nhánh nguồn không có nút Nhận hàng; hủy bắt buộc lý do rồi báo đã hủy', async () => {
    const user = renderTab();
    const detail = await open(user, '0003');
    await within(detail).findByText('Amoxicillin 250mg');
    expect(within(detail).queryByRole('button', { name: 'Nhận hàng' })).not.toBeInTheDocument();

    await user.click(within(detail).getByRole('button', { name: 'Hủy phiếu' }));
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận hủy phiếu' }));
    expect(within(detail).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do hủy phiếu chuyển kho');

    await user.type(within(detail).getByLabelText('Lý do hủy (bắt buộc)'), 'Gửi nhầm lô');
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận hủy phiếu' }));
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã hủy phiếu CK-\d{6}-0003/);
  });

  it('phiếu vừa bị người khác hủy: nút hủy nhận 409 của BE và hiện ngay trong hộp', async () => {
    const user = renderTab();
    const detail = await open(user, '0003');
    await within(detail).findByText('Amoxicillin 250mg');
    await stockopsApi.cancelTransfer(3, { reason: 'Người khác hủy' });

    await user.click(within(detail).getByRole('button', { name: 'Hủy phiếu' }));
    await user.type(within(detail).getByLabelText('Lý do hủy (bắt buộc)'), 'Tôi hủy');
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận hủy phiếu' }));

    expect(await within(detail).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái phiếu chuyển kho từ CANCELLED sang CANCELLED');
  });

  it('tạo phiếu: chưa có chi nhánh khác đang hoạt động thì báo rõ và khóa nút tạo', async () => {
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: 'Tạo phiếu chuyển kho' }));
    const form = await screen.findByRole('dialog');
    expect(await within(form).findByRole('note')).toHaveTextContent('Chưa có chi nhánh nào khác đang hoạt động');
    expect(within(form).getByRole('button', { name: 'Tạo phiếu' })).toBeDisabled();
  });

  it('tạo phiếu: chặn trước khi gửi nếu vượt tồn khả dụng; hợp lệ thì tạo phiếu Đang chuyển', async () => {
    await activateBranch2();
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: 'Tạo phiếu chuyển kho' }));
    const form = await screen.findByRole('dialog');
    await user.selectOptions(await within(form).findByLabelText('Chi nhánh đích'), await within(form).findByRole('option', { name: 'PetCare Hà Đông' }));
    await user.selectOptions(await within(form).findByLabelText('Dòng 1 — sản phẩm'), within(form).getByRole('option', { name: /^Amoxicillin 250mg/ }));
    await user.type(within(form).getByLabelText('Dòng 1 — số lượng'), '9999');
    await user.click(within(form).getByRole('button', { name: 'Tạo phiếu' }));
    expect(within(form).getByRole('alert')).toHaveTextContent(/Dòng 1: vượt tồn khả dụng/);

    await user.clear(within(form).getByLabelText('Dòng 1 — số lượng'));
    await user.type(within(form).getByLabelText('Dòng 1 — số lượng'), '5');
    await user.click(within(form).getByRole('button', { name: 'Tạo phiếu' }));

    expect(await screen.findByRole('status')).toHaveTextContent(/Đã tạo phiếu chuyển kho CK-\d{6}-0005/);
    expect(await screen.findByRole('button', { name: /^Mở phiếu CK-\d{6}-0005$/ })).toBeInTheDocument();
  });

  it('tồn đổi sau lúc mở form: BE từ chối 400 BR-KO-08 và hộp hiện lý do', async () => {
    await activateBranch2();
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: 'Tạo phiếu chuyển kho' }));
    const form = await screen.findByRole('dialog');
    await user.selectOptions(await within(form).findByLabelText('Chi nhánh đích'), await within(form).findByRole('option', { name: 'PetCare Hà Đông' }));
    const product = await within(form).findByRole('option', { name: /^Amoxicillin 250mg/ });
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — sản phẩm'), product);
    const available = Number(/khả dụng (\d+)/.exec(product.textContent ?? '')![1]);
    // Người khác chuyển gần hết số này đi trong lúc form đang mở (cache của form còn cũ).
    await stockopsApi.createTransfer({ destBranchId: 2, lines: [{ productId: Number(product.getAttribute('value')), quantity: available - 1 }] });
    await user.type(within(form).getByLabelText('Dòng 1 — số lượng'), String(available));
    await user.click(within(form).getByRole('button', { name: 'Tạo phiếu' }));

    expect(await within(form).findByRole('alert')).toHaveTextContent(/Số lượng vượt tồn khả dụng của chi nhánh nguồn/);
  });
});
