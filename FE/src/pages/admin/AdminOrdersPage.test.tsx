import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { shopApi } from '../../shared/api/shop.api';
import { pinClock, signInAs } from '../../test/cashier-harness';
import { AdminOrdersPage } from './AdminOrdersPage';

// Dữ liệu mẫu (commerce.store.ts), chi nhánh 1, thứ Ba 27/10/2026 10:00. "Việc cần làm", cũ nhất trước: #6 DELIVERY_FAILED (đã giao lại 2 lần, tối đa) ·
// #7 DELIVERY_FAILED (J&T) · #4 PROCESSING · #5 PAID. Ngoài danh sách mặc định: #3 SHIPPED (Viettel Post), #2 DELIVERED, #1 COMPLETED.
vi.mock('../../shared/constants/realtime', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/constants/realtime')>()),
  retryDelay: () => 0,
}));

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  pinClock('2026-10-27T10:00:00+07:00');
  await signInAs('reception@store1.vn');
});
afterEach(() => vi.useRealTimers());

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AdminOrdersPage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const rows = async () => within(await screen.findByRole('table', { name: 'Danh sách đơn online' })).getAllByRole('row').slice(1);
const openRow = async (user: ReturnType<typeof renderPage>, index: number) => {
  await user.click(within((await rows())[index]).getByRole('button', { name: /^Xem đơn/ }));
  await screen.findByLabelText('Chi tiết đơn online');
};
const panel = () => screen.getByLabelText('Chi tiết đơn online');
const dialog = () => screen.getByRole('dialog');

describe('danh sách (UC65)', () => {
  it('mặc định là việc cần làm, cũ nhất trước', async () => {
    renderPage();
    const r = await rows();
    expect(r).toHaveLength(4);
    expect(within(r[0]).getByText('Giao thất bại')).toBeInTheDocument();
    expect(within(r[1]).getByText('Giao thất bại')).toBeInTheDocument();
    expect(within(r[2]).getByText('Đang chuẩn bị hàng')).toBeInTheDocument();
    expect(within(r[3]).getByText('Đã thanh toán')).toBeInTheDocument();
    expect(screen.getByText('4 đơn')).toBeInTheDocument();
  });

  it('lọc theo trạng thái cụ thể', async () => {
    const user = renderPage();
    await rows();
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái đơn'), 'SHIPPED');
    await waitFor(async () => expect(await rows()).toHaveLength(1));
    expect(within((await rows())[0]).getByText('Đã bàn giao vận chuyển')).toBeInTheDocument();
  });

  it('mở chi tiết: người nhận, dòng hàng, vận chuyển, mã Order bán hàng', async () => {
    const user = renderPage();
    await openRow(user, 1); // #7
    const p = panel();
    expect(await within(p).findByText('J&T · JT2611123')).toBeInTheDocument();
    expect(within(p).getByText(/Mã Order bán hàng: #7007/)).toBeInTheDocument();
    expect(within(p).getByRole('table', { name: 'Dòng hàng của đơn' })).toBeInTheDocument();
  });
});

describe('thao tác theo trạng thái', () => {
  it('PAID → PROCESSING → SHIPPED → DELIVERED: mỗi bước đổi nhãn và nút', async () => {
    const user = renderPage();
    await openRow(user, 3); // #5 PAID
    // Chỉ nút của trạng thái PAID.
    await user.click(await within(panel()).findByRole('button', { name: 'Bắt đầu xử lý' }));
    expect(await screen.findByText(/Đã bắt đầu xử lý đơn/)).toBeInTheDocument();
    await waitFor(() => expect(within(panel()).getByText('Đang chuẩn bị hàng')).toBeInTheDocument());
    expect(within(panel()).queryByRole('button', { name: 'Bắt đầu xử lý' })).not.toBeInTheDocument();

    await user.click(within(panel()).getByRole('button', { name: 'Bàn giao vận chuyển' }));
    await user.click(within(dialog()).getByRole('button', { name: 'Bàn giao' })); // trống
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Nhập đơn vị vận chuyển và mã vận đơn.');
    await user.type(within(dialog()).getByLabelText('Đơn vị vận chuyển'), 'GHN');
    await user.type(within(dialog()).getByLabelText('Mã vận đơn'), 'GHN9999');
    await user.click(within(dialog()).getByRole('button', { name: 'Bàn giao' }));
    expect(await screen.findByText(/Đã bàn giao vận chuyển đơn/)).toBeInTheDocument();
    await waitFor(() => expect(within(panel()).getByText('GHN · GHN9999')).toBeInTheDocument());

    await user.click(within(panel()).getByRole('button', { name: 'Cập nhật đã giao' }));
    expect(await screen.findByText(/Đã cập nhật đã giao đơn/)).toBeInTheDocument();
    await waitFor(() => expect(within(panel()).getByText('Đã giao')).toBeInTheDocument());
    expect(within(panel()).queryByRole('button', { name: /Cập nhật đã giao|Giao thất bại|Bàn giao/ })).not.toBeInTheDocument();
  });

  it('SHIPPED → ghi nhận giao thất bại bắt buộc lý do', async () => {
    const user = renderPage();
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái đơn'), 'SHIPPED');
    await waitFor(async () => expect(await rows()).toHaveLength(1));
    await openRow(user, 0);
    await user.click(await within(panel()).findByRole('button', { name: 'Giao thất bại' }));
    await user.click(within(dialog()).getByRole('button', { name: 'Ghi nhận' }));
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Nhập lý do giao thất bại.');
    await user.type(within(dialog()).getByLabelText('Lý do giao thất bại'), 'Khách không nhận máy');
    await user.click(within(dialog()).getByRole('button', { name: 'Ghi nhận' }));
    expect(await screen.findByText(/Đã ghi nhận giao thất bại đơn/)).toBeInTheDocument();
    await waitFor(() => expect(within(panel()).getByRole('button', { name: 'Giao lại' })).toBeInTheDocument());
  });

  it('DELIVERY_FAILED → hủy đơn: hoàn hàng, sinh hoàn tiền', async () => {
    const user = renderPage();
    await openRow(user, 1); // #7
    await user.click(await within(panel()).findByRole('button', { name: 'Hủy đơn' }));
    await user.type(within(dialog()).getByLabelText('Lý do hủy'), 'Khách không nhận hàng');
    await user.click(within(dialog()).getByRole('button', { name: 'Hủy đơn' }));
    expect(await screen.findByText(/Đã hủy đơn .* sinh yêu cầu hoàn tiền/)).toBeInTheDocument();
    await waitFor(() => expect(within(panel()).getByText('Đã hủy')).toBeInTheDocument());
    expect(within(panel()).getByText('Lý do hủy:')).toBeInTheDocument();
  });
});

describe('lỗi từ máy chủ', () => {
  it('giao lại quá số lần tối đa (400 BR-BH-11) hiện ngay trong hộp thoại, không đóng', async () => {
    const user = renderPage();
    await openRow(user, 0); // #6, đã giao lại 2 lần
    await user.click(await within(panel()).findByRole('button', { name: 'Giao lại' }));
    await user.type(within(dialog()).getByLabelText('Mã vận đơn'), 'GHN0001');
    await user.click(within(dialog()).getByRole('button', { name: 'Giao lại' }));
    expect(await within(dialog()).findByRole('alert')).toHaveTextContent('Đơn đã giao lại tối đa 2 lần, hãy hủy đơn');
  });

  it('đơn đã đổi trạng thái ở nơi khác (409) hiện thành thông báo lỗi', async () => {
    const user = renderPage();
    await openRow(user, 3); // #5 PAID
    const start = await within(panel()).findByRole('button', { name: 'Bắt đầu xử lý' });
    await shopApi.startProcessing(5); // người khác xử lý trước
    await user.click(start);
    expect(await screen.findByRole('alert')).toHaveTextContent('Đơn vừa được xử lý ở nơi khác nên không làm được thao tác này.');
  });
});

describe('vai trò', () => {
  it('quản lý chi nhánh xem được đơn nhưng không thấy nút thao tác', async () => {
    await signInAs('manager@store1.vn');
    const user = renderPage();
    expect(screen.getByText(/Bạn chỉ có quyền xem/)).toBeInTheDocument();
    await openRow(user, 3); // #5 PAID
    await within(panel()).findByText('Đã thanh toán');
    expect(within(panel()).queryByRole('button', { name: /Bắt đầu xử lý|Bàn giao|Giao lại|Hủy đơn|Cập nhật đã giao|Giao thất bại/ })).not.toBeInTheDocument();
  });
});
