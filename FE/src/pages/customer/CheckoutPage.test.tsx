import { screen, waitFor, within } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs, renderRoutes } from '@/components/customer/shop/testing';
import { ApiError } from '@/shared/api/api-error';
import { apiClient } from '@/shared/api/axios';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { paymentApi } from '@/shared/api/payment.api';
import { profileApi } from '@/shared/api/profile.api';
import { shopApi } from '@/shared/api/shop.api';
import { CheckoutPage } from './CheckoutPage';

// UC62, shop-v1 #5–7: khach@ có giỏ 486.000 đ, sổ địa chỉ 2 dòng (Nguyễn Thu Trang là mặc định), chi nhánh xử lý duy nhất PetCare Cầu Giấy.
// Phí vận chuyển mặc định 30.000 đ, miễn phí từ 500.000 đ. Đặt xong sang cổng giả lập `/payment/mock-gateway?code=&amount=`.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  sessionStorage.clear();
  await loginAs();
});
afterEach(() => vi.restoreAllMocks());

function renderCheckout() {
  return renderRoutes(
    <>
      <Route path="/checkout" element={<CheckoutPage />} />
      <Route path="/payment/mock-gateway" element={<p>Cổng thanh toán giả lập</p>} />
      <Route path="/order/:id" element={<p>Chi tiết đơn</p>} />
    </>,
    '/checkout',
  );
}
const summary = () => screen.getByRole('complementary', { name: 'Tóm tắt đơn hàng' });
const quoted = () => waitFor(() => expect(within(summary()).getByRole('button', { name: 'Đặt hàng và thanh toán' })).toBeEnabled());

describe('CheckoutPage (UC62)', () => {
  it('hiện địa chỉ trong sổ (mặc định chọn sẵn), chi nhánh xử lý và báo giá có phí vận chuyển', async () => {
    renderCheckout();
    const trang = await screen.findByRole('radio', { name: /Nguyễn Thu Trang/ });
    expect(trang).toBeChecked();
    expect(screen.getByRole('radio', { name: /Nguyễn Văn Bình/ })).not.toBeChecked();
    expect(await screen.findByRole('radio', { name: /PetCare Cầu Giấy/ })).toBeChecked();
    await quoted();
    expect(summary()).toHaveTextContent('Hạt Royal Canin Mini Adult 2kg × 1');
    expect(summary()).toHaveTextContent('Phí vận chuyển30.000 đ');
    expect(summary()).toHaveTextContent('Tổng cộng516.000 đ');
    expect(document.body).not.toHaveTextContent(/COD|MoMo|voucher|khuyến mãi/i);
  });

  it('tiền hàng đạt ngưỡng: báo giá ghi miễn phí vận chuyển', async () => {
    await shopApi.setCartItem(1, { quantity: 2 });
    renderCheckout();
    await quoted();
    expect(summary()).toHaveTextContent('Phí vận chuyểnMiễn phí');
    expect(summary()).toHaveTextContent('Tổng cộng936.000 đ');
  });

  it('chọn địa chỉ khác vẫn báo giá lại được', async () => {
    const quote = vi.spyOn(shopApi, 'quote');
    const { user } = renderCheckout();
    await user.click(await screen.findByRole('radio', { name: /Nguyễn Văn Bình/ }));
    await waitFor(() => expect(quote).toHaveBeenLastCalledWith({ branchId: 1, addressId: 2 }));
    await quoted();
  });

  it('đặt hàng: tạo đơn PENDING_PAYMENT, dọn giỏ rồi chuyển sang cổng giả lập', async () => {
    const { user } = renderCheckout();
    await quoted();
    await user.click(within(summary()).getByRole('button', { name: 'Đặt hàng và thanh toán' }));
    expect(await screen.findByText('Cổng thanh toán giả lập')).toBeInTheDocument();
    expect(screen.getByTestId('url')).toHaveTextContent(/^\/payment\/mock-gateway\?code=TT-.+&amount=516000$/);
    expect((await shopApi.getCart()).items).toEqual([]);
    const newest = (await shopApi.listMyOrders({ size: 1 })).content[0];
    expect(newest).toMatchObject({ status: 'PENDING_PAYMENT', total: 516_000, itemCount: 2 });
  });

  it('giỏ vừa bị xóa ở nơi khác (400 BR-BH-08): lỗi hiện ngay, không tạo đơn', async () => {
    const { user } = renderCheckout();
    await quoted();
    await shopApi.clearCart();
    const before = (await shopApi.listMyOrders()).totalElements;
    await user.click(within(summary()).getByRole('button', { name: 'Đặt hàng và thanh toán' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Giỏ hàng đang trống');
    expect((await shopApi.listMyOrders()).totalElements).toBe(before);
    expect(screen.getByTestId('url')).toHaveTextContent('/checkout');
  });

  it('đã đặt nhưng mở cổng thất bại (409): báo lỗi và đưa tới chi tiết đơn để thanh toán lại', async () => {
    vi.spyOn(paymentApi, 'pay').mockRejectedValueOnce(new ApiError('INVALID_STATE_TRANSITION', 'Đơn hàng đang có giao dịch chờ thanh toán TT-1', 409));
    const { user } = renderCheckout();
    await quoted();
    await user.click(within(summary()).getByRole('button', { name: 'Đặt hàng và thanh toán' }));
    expect(await screen.findByText('Chi tiết đơn')).toBeInTheDocument();
    expect(await screen.findByText(/chưa mở được cổng thanh toán: Đơn hàng đang có giao dịch chờ thanh toán TT-1/)).toBeInTheDocument();
  });

  it('thiếu hàng ở mọi chi nhánh: báo rõ, không chọn được chi nhánh và khóa nút đặt', async () => {
    await shopApi.setCartItem(1, { quantity: 99 });
    renderCheckout();
    expect(await screen.findByText(/chưa có chi nhánh nào còn đủ hàng/)).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /PetCare Cầu Giấy/ })).not.toBeInTheDocument();
    expect(within(summary()).getByRole('button', { name: 'Đặt hàng và thanh toán' })).toBeDisabled();
  });

  it('chưa có địa chỉ nào: hướng dẫn thêm vào sổ địa chỉ, khóa nút đặt', async () => {
    vi.spyOn(profileApi, 'addresses').mockResolvedValue([]);
    renderCheckout();
    expect(await screen.findByText('Bạn chưa có địa chỉ nào trong sổ địa chỉ.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Thêm địa chỉ trong sổ địa chỉ' })).toHaveAttribute('href', '/account/addresses');
    expect(within(summary()).getByRole('button', { name: 'Đặt hàng và thanh toán' })).toBeDisabled();
  });

  it('giỏ rỗng (khachmoi@): khung giỏ trống thay cho form', async () => {
    await loginAs('khachmoi@petcare.vn');
    renderCheckout();
    expect(await screen.findByText('Giỏ hàng trống')).toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Khám phá cửa hàng' })).toHaveAttribute('href', '/shop');
  });
});
