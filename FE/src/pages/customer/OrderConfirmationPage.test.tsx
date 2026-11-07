import { screen, waitFor, within } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs, renderRoutes } from '@/components/customer/shop/testing';
import { ApiError } from '@/shared/api/api-error';
import { apiClient } from '@/shared/api/axios';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { paymentApi } from '@/shared/api/payment.api';
import { shopApi } from '@/shared/api/shop.api';
import { OrderConfirmationPage } from './OrderConfirmationPage';

// UC64, shop-v1 #9–11 + payment-v1 #1. Đơn mẫu của khach@: #8 PENDING_PAYMENT (1 túi hạt, 480.000 đ), #5 PAID, #3 SHIPPED (Viettel Post VTP2611055), #2 DELIVERED,
// #6 DELIVERY_FAILED (đã giao lại 2 lần), #1 COMPLETED, #9 CANCELLED ("Đặt nhầm sản phẩm"). Đơn #4 thuộc khachmoi@ nên khach@ thấy 404.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  sessionStorage.clear();
  await loginAs();
});
afterEach(() => vi.restoreAllMocks());

function renderOrder(id: string) {
  return renderRoutes(
    <>
      <Route path="/order/:id" element={<OrderConfirmationPage />} />
      <Route path="/payment/mock-gateway" element={<p>Cổng thanh toán giả lập</p>} />
    </>,
    `/order/${id}`,
  );
}
/** Dòng "Trạng thái đơn" (nhãn hiện cả ở dòng tiến trình nên phải khoanh vùng). */
const statusLine = async () => (await screen.findByText('Trạng thái đơn')).closest('p')!;
const actions = () => screen.queryByRole('region', { name: 'Thao tác với đơn' });

describe('OrderConfirmationPage (UC64): chi tiết đơn', () => {
  it('đơn chờ thanh toán: dòng hàng, tiền, địa chỉ nhận, hạn giữ hàng, nút Thanh toán và Hủy đơn', async () => {
    renderOrder('8');
    expect(await screen.findByRole('heading', { level: 1, name: /^Đơn DH-/ })).toBeInTheDocument();
    expect(within(await statusLine()).getByText('Chờ thanh toán')).toBeInTheDocument();
    expect(screen.getByText('Hạt Royal Canin Mini Adult 2kg')).toBeInTheDocument();
    expect(screen.getByLabelText('Sản phẩm', { selector: 'section' })).toHaveTextContent('Tổng cộng480.000 đ');
    expect(screen.getByLabelText('Giao hàng', { selector: 'section' })).toHaveTextContent('PetCare Cầu Giấy');
    expect(actions()).toHaveTextContent('Đơn được giữ hàng đến');
    expect(screen.getByRole('button', { name: 'Thanh toán' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Hủy đơn' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xác nhận đã nhận hàng' })).not.toBeInTheDocument();
  });

  it('"Thanh toán": mở giao dịch rồi chuyển sang cổng giả lập với đúng mã và số tiền', async () => {
    const { user } = renderOrder('8');
    await user.click(await screen.findByRole('button', { name: 'Thanh toán' }));
    expect(await screen.findByText('Cổng thanh toán giả lập')).toBeInTheDocument();
    expect(screen.getByTestId('url')).toHaveTextContent(/^\/payment\/mock-gateway\?code=TT-.+&amount=480000$/);
  });

  it('đã có giao dịch chờ (409): lỗi hiện ngay chỗ nút, đơn không đổi', async () => {
    await paymentApi.pay(8);
    const { user } = renderOrder('8');
    await user.click(await screen.findByRole('button', { name: 'Thanh toán' }));
    expect(await within(await screen.findByRole('region', { name: 'Thao tác với đơn' })).findByRole('alert')).toHaveTextContent('Đơn hàng đang có giao dịch chờ thanh toán');
    expect(screen.getByTestId('url')).toHaveTextContent('/order/8');
  });

  it('hủy đơn chưa thanh toán: bắt buộc nhập lý do, hủy xong đơn thành Đã hủy kèm lý do và hết nút', async () => {
    const { user } = renderOrder('8');
    await user.click(await screen.findByRole('button', { name: 'Hủy đơn' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('hàng đang giữ cho bạn sẽ được nhả ra');

    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do hủy đơn');

    await user.type(within(dialog).getByLabelText('Lý do hủy'), 'Đổi ý');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Đã hủy đơn hàng')).toBeInTheDocument();
    await waitFor(async () => expect(within(await statusLine()).getByText('Đã hủy')).toBeInTheDocument());
    expect(screen.getByLabelText('Tiến trình đơn hàng')).toHaveTextContent('Lý do: Đổi ý');
    expect(actions()).not.toBeInTheDocument();
  });

  it('hủy đơn đã thanh toán (#5): cảnh báo hoàn tiền qua cổng; lý do quá 300 ký tự bị chặn trước khi gửi', async () => {
    const cancel = vi.spyOn(shopApi, 'cancelMyOrder');
    const { user } = renderOrder('5');
    expect(within(await statusLine()).getByText('Đã thanh toán')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Hủy đơn' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('được hoàn qua cổng thanh toán');

    await user.click(within(dialog).getByLabelText('Lý do hủy'));
    await user.paste('x'.repeat(301));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Lý do tối đa 300 ký tự');
    expect(cancel).not.toHaveBeenCalled();
  });

  it('đơn vừa đổi trạng thái ở nơi khác (409): lỗi hiện trong hộp thoại, màn hình tải lại theo trạng thái mới', async () => {
    const { user } = renderOrder('5');
    await user.click(await screen.findByRole('button', { name: 'Hủy đơn' }));
    const dialog = await screen.findByRole('dialog');
    await shopApi.cancelMyOrder(5, { reason: 'Hủy ở thiết bị khác' });
    await user.type(within(dialog).getByLabelText('Lý do hủy'), 'Đổi ý');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái');
    await waitFor(async () => expect(within(await statusLine()).getByText('Đã hủy')).toBeInTheDocument());
    // Dữ liệu đã tải lại sang trạng thái cuối nhưng lỗi vẫn còn trong hộp thoại, không biến mất theo.
    expect(within(screen.getByRole('dialog')).getByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái');
  });

  it('đơn đã giao (#2): "Xác nhận đã nhận hàng" chuyển sang Hoàn tất và hết nút', async () => {
    const { user } = renderOrder('2');
    expect(within(await statusLine()).getByText('Đã giao')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy đơn' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Xác nhận đã nhận hàng' }));
    await waitFor(async () => expect(within(await statusLine()).getByText('Hoàn tất')).toBeInTheDocument());
    expect(actions()).not.toBeInTheDocument();
  });

  it('xác nhận khi đơn đã hoàn tất ở nơi khác (409): lỗi ngay chỗ nút, trạng thái được làm mới', async () => {
    const { user } = renderOrder('2');
    await screen.findByRole('button', { name: 'Xác nhận đã nhận hàng' });
    await shopApi.confirmReceived(2);
    await user.click(screen.getByRole('button', { name: 'Xác nhận đã nhận hàng' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái');
    await waitFor(async () => expect(within(await statusLine()).getByText('Hoàn tất')).toBeInTheDocument());
    // Đơn đã ở trạng thái cuối (không còn nút, không còn ghi chú) nhưng lỗi của lệnh vừa bấm vẫn còn hiện.
    expect(within(screen.getByRole('region', { name: 'Thao tác với đơn' })).getByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái');
    expect(screen.queryByRole('button', { name: 'Xác nhận đã nhận hàng' })).not.toBeInTheDocument();
  });

  it('đơn đang giao (#3): có hãng và mã vận đơn, không có nút hủy hay xác nhận', async () => {
    renderOrder('3');
    expect(within(await statusLine()).getByText('Đã bàn giao vận chuyển')).toBeInTheDocument();
    expect(screen.getByLabelText('Giao hàng', { selector: 'section' })).toHaveTextContent('Viettel PostVTP2611055');
    expect(actions()).toHaveTextContent('không tự hủy được đơn');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('giao thất bại (#6): chỉ báo tình trạng và số lần giao lại, không bịa lý do (hợp đồng chưa có trường), không có nút', async () => {
    renderOrder('6');
    expect(within(await statusLine()).getByText('Giao thất bại')).toBeInTheDocument();
    expect(actions()).toHaveTextContent('đã giao lại 2 lần');
    expect(document.body).not.toHaveTextContent('Người nhận không nghe máy');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('đơn hoàn tất (#1) và đơn đã hủy (#9, có lý do): không có khối thao tác', async () => {
    renderOrder('1');
    expect(within(await statusLine()).getByText('Hoàn tất')).toBeInTheDocument();
    expect(actions()).not.toBeInTheDocument();
  });

  it('đơn đã hủy (#9) hiện lý do hủy trong tiến trình', async () => {
    renderOrder('9');
    expect(within(await statusLine()).getByText('Đã hủy')).toBeInTheDocument();
    expect(screen.getByLabelText('Tiến trình đơn hàng')).toHaveTextContent('Lý do: Đặt nhầm sản phẩm');
    expect(actions()).not.toBeInTheDocument();
  });

  it.each(['4', '999', 'abc', '0'])('/order/%s (đơn của người khác, không có, id hỏng): khung "không tìm thấy"', async (id) => {
    renderOrder(id);
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy đơn hàng' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về đơn hàng của tôi' })).toHaveAttribute('href', '/orders');
  });

  it('lỗi tải khác 404: báo ApiError.message, "Thử lại" tải lại được', async () => {
    vi.spyOn(shopApi, 'getMyOrder').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Từ chối truy cập', 403));
    const { user } = renderOrder('8');
    expect(await screen.findByRole('alert')).toHaveTextContent('Từ chối truy cập');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(within(await statusLine()).getByText('Chờ thanh toán')).toBeInTheDocument();
  });
});
