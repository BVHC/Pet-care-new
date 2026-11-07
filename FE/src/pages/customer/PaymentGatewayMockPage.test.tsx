import { screen, waitFor, within } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { loginAs, renderRoutes } from '@/components/customer/shop/testing';
import { rememberMockTransaction } from '@/components/customer/shop/usePayOrder';
import { apiClient } from '@/shared/api/axios';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { paymentApi } from '@/shared/api/payment.api';
import { OrderConfirmationPage } from './OrderConfirmationPage';
import { PaymentGatewayMockPage } from './PaymentGatewayMockPage';
import { PaymentPage } from './PaymentPage';

// UC63, payment-v1 #1–3: đơn #8 của khach@petcare.vn đang PENDING_PAYMENT (tổng 480.000 đ, giao dịch cũ FAILED). Cổng giả lập ký callback rồi quay về /payment.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  sessionStorage.clear();
  await loginAs();
});

const routes = (
  <>
    <Route path="/payment/mock-gateway" element={<PaymentGatewayMockPage />} />
    <Route path="/payment" element={<PaymentPage />} />
    <Route path="/order/:id" element={<OrderConfirmationPage />} />
    <Route path="/orders" element={<p>Danh sách đơn</p>} />
  </>
);

/** Bắt đầu thanh toán đơn #8 như CheckoutPage / chi tiết đơn, rồi mở đúng `redirectUrl` mà BE trả. */
async function openGateway() {
  const session = await paymentApi.pay(8);
  rememberMockTransaction(session.code, session.transactionId);
  return { session, ...renderRoutes(routes, session.redirectUrl) };
}

describe('PaymentGatewayMockPage (UC63): cổng giả lập', () => {
  it('ghi rõ cổng giả lập, hiện mã giao dịch và số tiền từ đường dẫn', async () => {
    const { session } = await openGateway();
    expect(screen.getByRole('note')).toHaveTextContent('Cổng giả lập, không thu tiền thật');
    expect(screen.getByText(session.code)).toBeInTheDocument();
    expect(screen.getByText('480.000 đ')).toBeInTheDocument();
  });

  it('"Thanh toán thành công": callback ký đúng, về /payment thấy SUCCEEDED, đơn chuyển sang Đã thanh toán', async () => {
    const { session, user } = await openGateway();
    await user.click(screen.getByRole('button', { name: 'Thanh toán thành công' }));

    await waitFor(() => expect(screen.getByTestId('url')).toHaveTextContent(`/payment?transactionId=${session.transactionId}`));
    expect(await screen.findByRole('heading', { name: 'Thanh toán thành công' })).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: 'Xem đơn hàng' }));
    expect(await screen.findByRole('heading', { level: 1, name: /^Đơn DH-/ })).toBeInTheDocument();
    const status = (await screen.findByText('Trạng thái đơn')).closest('p')!;
    expect(within(status).getByText('Đã thanh toán')).toBeInTheDocument();
  });

  it('"Hủy thanh toán": giao dịch FAILED với lý do của cổng, đơn vẫn chờ thanh toán', async () => {
    const { user } = await openGateway();
    await user.click(screen.getByRole('button', { name: 'Hủy thanh toán' }));
    expect(await screen.findByRole('heading', { name: 'Thanh toán thất bại' })).toBeInTheDocument();
    expect(screen.getByText('Khách hủy giao dịch tại cổng')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Về đơn hàng' }));
    const status = (await screen.findByText('Trạng thái đơn')).closest('p')!;
    expect(within(status).getByText('Chờ thanh toán')).toBeInTheDocument();
  });

  it('mã tham chiếu lạ: BE từ chối 400 BR-TG-07, thông báo hiện ngay trên trang và không chuyển trang', async () => {
    const { user } = renderRoutes(routes, '/payment/mock-gateway?code=TT-KHONG-CO&amount=480000');
    await user.click(screen.getByRole('button', { name: 'Thanh toán thành công' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mã tham chiếu giao dịch không tồn tại');
    expect(screen.getByTestId('url')).toHaveTextContent('/payment/mock-gateway');
  });

  it('không nhớ được mã giao dịch (mở ở tab khác): về danh sách đơn sau khi gọi callback', async () => {
    const session = await paymentApi.pay(8);
    const { user } = renderRoutes(routes, session.redirectUrl);
    await user.click(screen.getByRole('button', { name: 'Thanh toán thành công' }));
    expect(await screen.findByText('Danh sách đơn')).toBeInTheDocument();
  });

  it('thiếu mã hoặc số tiền sai: báo đường dẫn không hợp lệ, không có nút thanh toán', () => {
    renderRoutes(routes, '/payment/mock-gateway?amount=abc');
    expect(screen.getByRole('alert')).toHaveTextContent('Đường dẫn cổng thanh toán không hợp lệ');
    expect(screen.queryByRole('button', { name: 'Thanh toán thành công' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về đơn hàng của tôi' })).toHaveAttribute('href', '/orders');
  });
});
