import { screen, waitFor, within } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs, renderRoutes } from '@/components/customer/shop/testing';
import { ApiError } from '@/shared/api/api-error';
import { apiClient } from '@/shared/api/axios';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { shopApi } from '@/shared/api/shop.api';
import { OrderHistoryPage } from './OrderHistoryPage';

// UC64, shop-v1 #8: khach@ có 8 đơn, mới nhất trước theo ngày tạo: #8 PENDING_PAYMENT, #5 PAID, #3 SHIPPED, #2 DELIVERED, #6 DELIVERY_FAILED, #9 CANCELLED, #10 CANCELLED, #1 COMPLETED.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => loginAs());
afterEach(() => vi.restoreAllMocks());

function renderOrders() {
  return renderRoutes(
    <>
      <Route path="/orders" element={<OrderHistoryPage />} />
      <Route path="/order/:id" element={<p>Chi tiết đơn</p>} />
    </>,
    '/orders',
  );
}
const cards = () => screen.queryAllByRole('article');
const STATUS = /^(Chờ thanh toán|Đã thanh toán|Đang chuẩn bị hàng|Đã bàn giao vận chuyển|Giao thất bại|Đã giao|Hoàn tất|Đã hủy)$/;

describe('OrderHistoryPage (UC64)', () => {
  it('liệt kê đơn của khách, mới nhất trước, mỗi thẻ có mã, trạng thái, số sản phẩm, chi nhánh và tổng tiền', async () => {
    renderOrders();
    await waitFor(() => expect(cards()).toHaveLength(8));
    expect(screen.getByText('8 đơn')).toBeInTheDocument();
    const statuses = cards().map((c) => within(c).getByText(STATUS).textContent);
    expect(statuses).toEqual(['Chờ thanh toán', 'Đã thanh toán', 'Đã bàn giao vận chuyển', 'Đã giao', 'Giao thất bại', 'Đã hủy', 'Đã hủy', 'Hoàn tất']);
    const pending = cards()[0];
    expect(pending).toHaveTextContent('1 sản phẩm · xử lý tại PetCare Cầu Giấy');
    expect(pending).toHaveTextContent('Tổng cộng: 480.000 đ');
  });

  it('chỉ đơn chờ thanh toán có nút "Thanh toán"; mọi thẻ dẫn tới /order/:id', async () => {
    const { user } = renderOrders();
    await waitFor(() => expect(cards()).toHaveLength(8));
    const inCards = (name: string) => cards().flatMap((c) => within(c).queryAllByRole('link', { name }));
    expect(inCards('Thanh toán')).toHaveLength(1);
    expect(inCards('Chi tiết')).toHaveLength(7);
    await user.click(within(cards()[0]).getByRole('link', { name: 'Thanh toán' }));
    expect(await screen.findByText('Chi tiết đơn')).toBeInTheDocument();
    expect(screen.getByTestId('url')).toHaveTextContent('/order/8');
  });

  it('lọc theo trạng thái gọi API với status và hiện đúng đơn', async () => {
    const list = vi.spyOn(shopApi, 'listMyOrders');
    const { user } = renderOrders();
    await waitFor(() => expect(cards()).toHaveLength(8));
    await user.selectOptions(screen.getByLabelText('Trạng thái'), 'Đã hủy');
    await waitFor(() => expect(cards()).toHaveLength(2));
    expect(list).toHaveBeenLastCalledWith({ status: 'CANCELLED', page: 0, size: 10 });
    expect(screen.getByText('2 đơn')).toBeInTheDocument();
  });

  it('trạng thái không có đơn nào: khung trống riêng cho bộ lọc, không dẫn đi mua', async () => {
    const { user } = renderOrders();
    await waitFor(() => expect(cards()).toHaveLength(8));
    await user.selectOptions(screen.getByLabelText('Trạng thái'), 'Đang chuẩn bị hàng');
    expect(await screen.findByText('Không có đơn nào ở trạng thái này')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Khám phá cửa hàng' })).not.toBeInTheDocument();
  });

  it('nhiều trang: phân trang gửi page theo BE (từ 0), nút trang trước khóa ở trang đầu', async () => {
    const list = vi.spyOn(shopApi, 'listMyOrders').mockImplementation(async (query) => ({
      content: [
        {
          onlineOrderId: 100 + (query?.page ?? 0),
          salesOrderId: 7100,
          code: `DH-TRANG-${(query?.page ?? 0) + 1}`,
          status: 'COMPLETED',
          branchName: 'PetCare Cầu Giấy',
          itemCount: 1,
          total: 100_000,
          createdAt: '2026-11-01T09:00:00+07:00',
        },
      ],
      page: query?.page ?? 0,
      size: 10,
      totalElements: 25,
      totalPages: 3,
    }));
    const { user } = renderOrders();
    expect(await screen.findByText('DH-TRANG-1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trang trước' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Trang sau' }));
    expect(await screen.findByText('DH-TRANG-2')).toBeInTheDocument();
    expect(list).toHaveBeenLastCalledWith({ page: 1, size: 10 });
    expect(screen.getByText('Trang 2 / 3')).toBeInTheDocument();
  });

  it('lỗi tải: báo ApiError.message, có "Thử lại"', async () => {
    vi.spyOn(shopApi, 'listMyOrders').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Bạn không có quyền xem đơn', 403));
    const { user } = renderOrders();
    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền xem đơn');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(cards()).toHaveLength(8));
  });

  it('khách chưa từng đặt đơn nào: khung trống dẫn tới cửa hàng', async () => {
    vi.spyOn(shopApi, 'listMyOrders').mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    renderOrders();
    expect(await screen.findByText('Chưa có đơn hàng nào')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Khám phá cửa hàng' })).toHaveAttribute('href', '/shop');
  });

  it('khách khác chỉ thấy đơn của mình (khachmoi@ có 2 đơn)', async () => {
    await loginAs('khachmoi@petcare.vn');
    renderOrders();
    await waitFor(() => expect(cards()).toHaveLength(2));
  });
});
