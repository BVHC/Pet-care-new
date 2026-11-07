import { screen, waitFor, within } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs, renderRoutes } from '@/components/customer/shop/testing';
import { ApiError } from '@/shared/api/api-error';
import { apiClient } from '@/shared/api/axios';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { shopApi } from '@/shared/api/shop.api';
import { CartPage } from './CartPage';

// UC61, shop-v1 #1–4: giỏ mẫu khach@ = 1 túi Hạt Royal Canin (450.000 đ) + 2 gói Pate Whiskas (18.000 đ) = 486.000 đ. khachmoi@ giỏ rỗng.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => loginAs());
afterEach(() => vi.restoreAllMocks());

const HAT = 'Hạt Royal Canin Mini Adult 2kg';
const PATE = 'Pate Whiskas 85g';

function renderCart() {
  return renderRoutes(
    <>
      <Route path="/cart" element={<CartPage />} />
      <Route path="/checkout" element={<p>Trang đặt hàng</p>} />
    </>,
    '/cart',
  );
}
const summary = () => screen.getByRole('complementary', { name: 'Tóm tắt đơn hàng' });

describe('CartPage (UC61)', () => {
  it('hiện các dòng của giỏ trên BE, số lượng, thành tiền từng dòng và tạm tính', async () => {
    renderCart();
    expect(await screen.findByRole('link', { name: HAT })).toHaveAttribute('href', '/shop/1');
    expect(screen.getByRole('link', { name: PATE })).toBeInTheDocument();
    expect(screen.getByLabelText(`Số lượng ${HAT}`)).toHaveTextContent('1');
    expect(screen.getByLabelText(`Số lượng ${PATE}`)).toHaveTextContent('2');
    expect(screen.getByText('2 dòng hàng')).toBeInTheDocument();
    expect(within(summary()).getByText('486.000 đ')).toBeInTheDocument();
    // Phí vận chuyển chỉ tính ở bước đặt hàng (báo giá), trang giỏ không tự đặt con số nào.
    expect(summary()).toHaveTextContent('Phí vận chuyển được tính ở bước đặt hàng');
    expect(document.body).not.toHaveTextContent(/miễn phí vận chuyển|voucher|khuyến mãi|điểm thưởng/i);
  });

  it('tăng số lượng: ghi lên BE và tạm tính đổi; giảm bị khóa ở 1', async () => {
    const { user } = renderCart();
    await screen.findByRole('link', { name: HAT });
    expect(screen.getByRole('button', { name: `Giảm số lượng ${HAT}` })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: `Tăng số lượng ${HAT}` }));
    await waitFor(() => expect(within(summary()).getByText('936.000 đ')).toBeInTheDocument());
    expect(screen.getByLabelText(`Số lượng ${HAT}`)).toHaveTextContent('2');
    expect((await shopApi.getCart()).items.find((i) => i.productId === 1)?.quantity).toBe(2);
  });

  it('xóa một dòng rồi xóa tất cả: tới khung giỏ trống có link về cửa hàng', async () => {
    const { user } = renderCart();
    await user.click(await screen.findByRole('button', { name: `Xóa ${PATE}` }));
    await waitFor(() => expect(screen.queryByRole('link', { name: PATE })).not.toBeInTheDocument());
    expect(within(summary()).getByText('450.000 đ')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xóa tất cả' }));
    expect(await screen.findByText('Giỏ hàng trống')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Khám phá cửa hàng' })).toHaveAttribute('href', '/shop');
  });

  it('khách mới (giỏ rỗng): khung giỏ trống, không có nút đặt hàng', async () => {
    await loginAs('khachmoi@petcare.vn');
    renderCart();
    expect(await screen.findByText('Giỏ hàng trống')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tiến hành đặt hàng' })).not.toBeInTheDocument();
  });

  it('"Tiến hành đặt hàng" sang /checkout', async () => {
    const { user } = renderCart();
    await user.click(await screen.findByRole('button', { name: 'Tiến hành đặt hàng' }));
    expect(await screen.findByText('Trang đặt hàng')).toBeInTheDocument();
  });

  it('BE từ chối ghi (400 BR-BH-07): thông báo ngay trên giỏ, giỏ giữ nguyên', async () => {
    vi.spyOn(shopApi, 'setCartItem').mockRejectedValueOnce(new ApiError('BUSINESS_RULE_VIOLATION', 'Số lượng mỗi sản phẩm phải từ 1 đến 99 (BR-BH-07)', 400));
    const { user } = renderCart();
    await user.click(await screen.findByRole('button', { name: `Tăng số lượng ${PATE}` }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Số lượng mỗi sản phẩm phải từ 1 đến 99');
    expect(screen.getByLabelText(`Số lượng ${PATE}`)).toHaveTextContent('2');
  });

  it('có sản phẩm hết hàng toàn chuỗi (available=false): ghi chú và khóa nút đặt hàng', async () => {
    vi.spyOn(shopApi, 'getCart').mockResolvedValue({
      items: [{ productId: 1, name: HAT, unit: 'túi', unitPrice: 450_000, quantity: 1, available: false }],
      subtotal: 450_000,
    });
    renderCart();
    expect(await screen.findByText('Hết hàng')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tiến hành đặt hàng' })).toBeDisabled();
    expect(screen.getByRole('note')).toHaveTextContent('hãy xóa khỏi giỏ');
  });

  it('lỗi tải giỏ: báo lỗi có "Thử lại", bấm thì tải lại được', async () => {
    vi.spyOn(shopApi, 'getCart').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    const { user } = renderCart();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được giỏ hàng');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('link', { name: HAT })).toBeInTheDocument();
  });

  it('đang tải: trạng thái đọc được', async () => {
    renderCart();
    expect(screen.getByRole('status', { name: 'Đang tải giỏ hàng' })).toBeInTheDocument();
    await screen.findByRole('link', { name: HAT });
  });
});
