import { useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { RetailOrderPanel } from './RetailOrderPanel';

// Mock (sales.mock.ts, inventory.mock.ts): chi nhánh 1 còn Pate Whiskas 3 gói; Order #1 bán lẻ OPEN của khách #3
// (1 túi Royal Canin). Amoxicillin là thuốc kê đơn, Rabisin / Vanguard là vaccine.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
});

function Harness({ initial = null }: { initial?: number | null }) {
  const [orderId, setOrderId] = useState<number | null>(initial);
  return <RetailOrderPanel orderId={orderId} onOrderChange={setOrderId} />;
}

function renderPanel(initial: number | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Harness initial={initial} />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const money = (text: string) => new RegExp(text.replace('.', '\\.') + '\\s*₫');

describe('RetailOrderPanel (T34 — UC66)', () => {
  it('lễ tân chọn khách → tạo Order → thêm hàng thiếu tồn (chỉ cảnh báo) → chốt, Order chờ thu và chưa giao hàng', async () => {
    const user = renderPanel();
    await user.type(screen.getByPlaceholderText('SĐT, họ tên hoặc email của khách'), 'Hoa');
    await user.click(await screen.findByRole('button', { name: /Lê Thị Hoa/ }));
    const create = await screen.findByRole('button', { name: 'Tạo Order bán lẻ' });
    await waitFor(() => expect(create).toBeEnabled());
    await user.click(create);

    await screen.findByPlaceholderText('Tìm sản phẩm, Enter để thêm');
    expect(screen.getByRole('button', { name: 'Chốt Order' })).toBeDisabled();
    // Thuốc kê đơn (BR-SP-01) và vaccine (BR-KB-04) không có trong danh sách bán lẻ.
    await screen.findByRole('button', { name: /Thêm Pate Whiskas 85g/ });
    expect(screen.queryByRole('button', { name: /Amoxicillin/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Vaccine/ })).not.toBeInTheDocument();

    const qty = screen.getByLabelText('Số lượng thêm');
    await user.clear(qty);
    await user.type(qty, '5');
    await user.click(screen.getByRole('button', { name: /Thêm Pate Whiskas 85g/ }));

    const lines = await screen.findByRole('list', { name: 'Dòng Order' });
    expect(await within(lines).findByText(/Tồn khả dụng còn 3/)).toBeInTheDocument();
    expect(within(lines).getByText(money('90.000'))).toBeInTheDocument();

    // PATCH chỉ giảm: bớt 1 → 4 gói, vẫn thiếu tồn nên cảnh báo còn.
    await user.click(within(lines).getByRole('button', { name: 'Bớt 1 Pate Whiskas 85g' }));
    expect(await within(lines).findByText(money('72.000'))).toBeInTheDocument();
    expect(within(lines).getByText(/Tồn khả dụng còn 3/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Chốt Order' }));
    expect(await screen.findByText('Chưa thanh toán – chưa giao hàng')).toBeInTheDocument();
    expect(screen.getByText('Chờ thu tiền')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Tìm sản phẩm, Enter để thêm')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Bán đơn mới' }));
    expect(await screen.findByPlaceholderText('SĐT, họ tên hoặc email của khách')).toBeInTheDocument();
  });

  it('Order đã bị hủy ở quầy khác: chốt báo dễ hiểu (409) và tải lại trạng thái mới', async () => {
    const user = renderPanel(1);
    const close = await screen.findByRole('button', { name: 'Chốt Order' });
    await apiClient.post('/api/orders/1/cancel');

    await user.click(close);
    expect(await screen.findByRole('alert')).toHaveTextContent('Order vừa được chốt, hủy hoặc thu tiền ở nơi khác');
    expect(await screen.findByText('Đã hủy')).toBeInTheDocument();
  });

  it('hủy Order chưa chốt cần xác nhận, xong quay về chọn khách', async () => {
    const user = renderPanel(1);
    await user.click(await screen.findByRole('button', { name: 'Hủy Order' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('kho không thay đổi');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));

    expect(await screen.findByPlaceholderText('SĐT, họ tên hoặc email của khách')).toBeInTheDocument();
    expect((await apiClient.get('/api/orders/1')).data.data).toMatchObject({ status: 'CANCELLED', cancelType: 'RETAIL_UNCLOSED' });
  });
});
