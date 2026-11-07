import { screen, waitFor } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs, renderRoutes } from '@/components/customer/shop/testing';
import { ApiError } from '@/shared/api/api-error';
import { apiClient } from '@/shared/api/axios';
import { gatewaySignature } from '@/shared/api/mock/payment.mock';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { paymentApi } from '@/shared/api/payment.api';
import { PaymentPage } from './PaymentPage';

// UC63, payment-v1 #2: seed của khach@ — giao dịch #1 SUCCEEDED (đơn #1), #8 FAILED (đơn #8, "Khách hủy giao dịch tại cổng"); giao dịch #4 thuộc khachmoi@ nên là 404.
// Giao dịch PENDING tạo bằng `paymentApi.pay(8)`.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => loginAs());
afterEach(() => vi.restoreAllMocks());

function renderResult(query: string) {
  return renderRoutes(
    <>
      <Route path="/payment" element={<PaymentPage />} />
      <Route path="/order/:id" element={<p>Chi tiết đơn</p>} />
    </>,
    `/payment${query}`,
  );
}

describe('PaymentPage (UC63): kết quả thanh toán', () => {
  it('SUCCEEDED: báo thành công, số tiền, giờ thanh toán và link xem đơn', async () => {
    renderResult('?transactionId=1');
    expect(await screen.findByRole('heading', { name: 'Thanh toán thành công' })).toBeInTheDocument();
    expect(screen.getByLabelText('Kết quả thanh toán')).toHaveTextContent('Thanh toán lúc');
    expect(screen.getByRole('link', { name: 'Xem đơn hàng' })).toHaveAttribute('href', '/order/1');
  });

  it('FAILED: nêu lý do của cổng, dẫn về đơn để thanh toán lại', async () => {
    renderResult('?transactionId=8');
    expect(await screen.findByRole('heading', { name: 'Thanh toán thất bại' })).toBeInTheDocument();
    expect(screen.getByText('Khách hủy giao dịch tại cổng')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về đơn hàng' })).toHaveAttribute('href', '/order/8');
    expect(screen.queryByRole('button', { name: 'Kiểm tra lại' })).not.toBeInTheDocument();
  });

  it('PENDING: báo đang chờ, "Kiểm tra lại" hỏi BE lần nữa và hiện kết quả mới', async () => {
    const session = await paymentApi.pay(8);
    const get = vi.spyOn(paymentApi, 'getMyTransaction');
    const { user } = renderResult(`?transactionId=${session.transactionId}`);
    expect(await screen.findByRole('heading', { name: 'Đang chờ thanh toán' })).toBeInTheDocument();
    expect(screen.getByText('Giao dịch hết hạn')).toBeInTheDocument();

    const fields = { transactionCode: session.code, gatewayTxnId: 'G1', status: '00', amount: session.amount };
    await paymentApi.gatewayCallback({ ...fields, signature: gatewaySignature(fields) });
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }));
    expect(await screen.findByRole('heading', { name: 'Thanh toán thành công' })).toBeInTheDocument();
    expect(get.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('giao dịch của người khác (404): khung "không tìm thấy", có link về đơn hàng', async () => {
    renderResult('?transactionId=4');
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy giao dịch' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về đơn hàng của tôi' })).toHaveAttribute('href', '/orders');
  });

  it('vào /payment không kèm transactionId (vd từ menu tài khoản): khung hướng dẫn mở đơn, không gọi API', () => {
    const get = vi.spyOn(paymentApi, 'getMyTransaction');
    renderResult('');
    expect(screen.getByRole('heading', { name: 'Chưa chọn giao dịch' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về đơn hàng của tôi' })).toHaveAttribute('href', '/orders');
    expect(get).not.toHaveBeenCalled();
  });

  it.each(['?transactionId=abc', '?transactionId=0', '?transactionId=-3'])('URL "%s" không hợp lệ: không gọi API, khung không tìm thấy', (query) => {
    const get = vi.spyOn(paymentApi, 'getMyTransaction');
    renderResult(query);
    expect(screen.getByRole('heading', { name: 'Không tìm thấy giao dịch' })).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });

  it('lỗi tải khác 404: báo ApiError.message, "Thử lại" tải lại được', async () => {
    vi.spyOn(paymentApi, 'getMyTransaction').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Từ chối truy cập', 403));
    const { user } = renderResult('?transactionId=1');
    expect(await screen.findByRole('alert')).toHaveTextContent('Từ chối truy cập');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { name: 'Thanh toán thành công' })).toBeInTheDocument();
  });

  it('đang tải: trạng thái đọc được; "Về đơn hàng" dẫn tới chi tiết đơn', async () => {
    const { user } = renderResult('?transactionId=8');
    expect(screen.getByRole('status', { name: 'Đang kiểm tra giao dịch' })).toBeInTheDocument();
    await user.click(await screen.findByRole('link', { name: 'Về đơn hàng' }));
    await waitFor(() => expect(screen.getByText('Chi tiết đơn')).toBeInTheDocument());
  });
});
