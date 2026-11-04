import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { toast } from 'sonner';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { salesApi } from '@/shared/api/sales.api';
import { usePosStore } from '@/shared/stores/pos.store';
import { CheckoutDialog } from './CheckoutDialog';
import {
  ACCOUNTS,
  appQueryClient,
  bookingOf,
  clearRequests,
  forbidden,
  installStayMock,
  openShift,
  otherStaff,
  payOrder,
  renderDialog,
  requests,
  renewSession,
  signIn,
  startDay,
  writes,
} from './stay-harness';

// UC59 "Trả thú" (A3, A4, A6, A10, A11; Review Focus #4), đồng hồ ghim thứ Sáu 23/10/2026 09:00. Đặt chỗ #2: Cốm CHECKED_IN từ 21/10, chuồng C-01,
// 200.000 đ/đêm → trả hôm nay = 2 đêm = 400.000 đ. Khách của Cốm (9002) không có trong mock khách: dùng cho hộp thoại; vòng thu tiền thật ở StayCollect.test.
// Máy khách React Query giống App.tsx (staleTime 60 giây, không làm mới khi lấy lại tiêu điểm).

beforeAll(installStayMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const ORDER_CODE = /DH-\d{4}-\d{5}/;
const money = (n: string) => new RegExp(`${n.replace(/\./g, '\\.')}\\s*₫`);

async function open(opts: { email?: string; id?: number; client?: ReturnType<typeof appQueryClient>; at?: string } = {}) {
  const { email = ACCOUNTS.desk, id = 2, client, at } = opts;
  await signIn(email);
  let booking = await bookingOf(id);
  if (at) {
    vi.setSystemTime(new Date(at));
    await signIn(email); // phiên cũ hết hạn khi đồng hồ nhảy tới ngày khác
    booking = await bookingOf(id); // ST15 lười: quá 12:00 ngày trả thì CHECKED_IN → OVERDUE ở lần gọi sau
  }
  clearRequests();
  const onClose = vi.fn();
  const view = renderDialog(<CheckoutDialog booking={booking} onClose={onClose} />, {
    client,
    routes: { '/staff/reception': <p>Trang Quầy lễ tân</p> },
  });
  return { ...view, onClose, booking };
}

type User = Awaited<ReturnType<typeof open>>['user'];
const startButton = () => screen.findByRole('button', { name: 'Bắt đầu trả thú' });
/** Bắt đầu trả thú rồi chờ tới bước Order chờ thanh toán. */
async function start(user: User) {
  await user.click(await startButton());
  await screen.findByRole('button', { name: 'Kiểm tra lại' });
}
const orderOf = async (bookingId = 2) => salesApi.getOrder((await bookingOf(bookingId)).activeOrderId!);
/** Máy khác (bàn Lễ tân) thu tiền Order: mở ca rồi POST /payments. */
async function collectElsewhere(bookingId = 2) {
  const order = await orderOf(bookingId);
  const desk = await otherStaff(ACCOUNTS.desk);
  await openShift(desk);
  await payOrder(desk, order.orderId, order.totalAmount);
  return order;
}
const handOverButton = () => screen.getByRole('button', { name: 'Giao thú' });

describe('CheckoutDialog — bước chưa có Order', () => {
  it('mở ra KHÔNG gọi API ghi: chỉ có nút Bắt đầu trả thú, chưa có Order, chưa có nút Giao thú', async () => {
    await open();
    const dialog = await screen.findByRole('dialog', { name: 'Trả thú' });
    await startButton();
    expect(within(dialog).getByText(/Cốm/)).toBeInTheDocument();
    // L-50: luật `max(1, ngày ở + (sau 12:00 ? 1 : 0))` — câu cũ "tối thiểu 1 đêm; trả sau 12:00 tính thêm 1 đêm" dễ đọc thành 2 đêm cho ca cùng ngày.
    const rule = within(dialog).getByText(/Số đêm = số ngày ở/);
    expect(rule).toHaveTextContent('trả sau 12:00 cộng thêm 1 đêm');
    expect(rule).toHaveTextContent('nhận và trả cùng ngày vẫn tính 1 đêm, kể cả sau 12:00');
    expect(rule).not.toHaveTextContent('tối thiểu 1 đêm; trả sau');
    expect(within(dialog).queryByRole('button', { name: 'Giao thú' })).not.toBeInTheDocument();
    expect(within(dialog).queryByText(ORDER_CODE)).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('đang tải: báo đang tải đặt chỗ, chưa hiện nút nào', async () => {
    await open();
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải thông tin trả thú');
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    await startButton();
  });

  it('Bắt đầu trả thú: gọi start-checkout đúng một lần, hiện mã Order, số đêm, tiền; Giao thú bị khóa kèm lý do BR-LT-09', async () => {
    const { user } = await open();
    await start(user);
    expect(writes()).toEqual(['POST /api/boarding-bookings/2/start-checkout']);
    const dialog = screen.getByRole('dialog', { name: 'Trả thú' });
    expect(within(dialog).getByText(ORDER_CODE)).toBeInTheDocument();
    expect(within(dialog).getByText('2 đêm')).toBeInTheDocument();
    expect(within(dialog).getByText(money('400.000'))).toBeInTheDocument();
    expect(within(dialog).getByText('Chờ thanh toán')).toBeInTheDocument();
    for (const name of ['Thu tiền tại bàn Lễ tân', 'Kiểm tra lại', 'Tính lại tiền', 'Khách không thanh toán → Hủy phiên']) {
      expect(within(dialog).getByRole('button', { name })).toBeEnabled();
    }
    expect(handOverButton()).toBeDisabled();
    expect(within(dialog).getByText('Order lưu trú chưa thanh toán (BR-LT-09)')).toBeInTheDocument();
    expect((await orderOf()).status).toBe('PENDING');
  });

  it('đang gửi: nút khóa, không gửi hai lần', async () => {
    const { user } = await open();
    let release: (r: Awaited<ReturnType<typeof boardingApi.startCheckout>>) => void = () => {};
    const spy = vi.spyOn(boardingApi, 'startCheckout').mockReturnValue(new Promise((resolve) => (release = resolve)));
    await user.click(await startButton());
    const busy = await screen.findByRole('button', { name: 'Đang xử lý…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockRestore();
    release((await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/start-checkout')) as never);
    await screen.findByRole('button', { name: 'Kiểm tra lại' });
  });

  it('OVERDUE vẫn trả được: tính tiếp theo đêm', async () => {
    const { user, booking } = await open({ at: '2026-10-31T13:30:00+07:00' });
    expect(booking.status).toBe('OVERDUE');
    await screen.findByText('Quá hạn đón');
    await start(user);
    const dialog = screen.getByRole('dialog', { name: 'Trả thú' });
    expect(within(dialog).getByText('11 đêm')).toBeInTheDocument();
    expect(within(dialog).getByText(money('2.200.000'))).toBeInTheDocument();
  });

  it('BR-LT-04: ngoài giờ mở cửa → câu của BE, chưa có Order, nút vẫn bấm lại được', async () => {
    const { user } = await open();
    await startButton();
    vi.setSystemTime(new Date('2026-10-23T12:30:00+07:00'));
    await user.click(screen.getByRole('button', { name: 'Bắt đầu trả thú' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Chỉ trả thú trong giờ mở cửa của chi nhánh');
    expect(screen.getByRole('button', { name: 'Bắt đầu trả thú' })).toBeEnabled();
    expect((await bookingOf(2)).activeOrderId).toBeNull();
  });

  it('409 (người khác vừa kết thúc lưu trú): hiện câu của BE, làm mới, đặt chỗ hết "đang lưu trú" nên chỉ còn Đóng', async () => {
    const { user, onClose } = await open();
    await startButton();
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/end-deceased', { note: 'Người khác ghi trước' });
    await user.click(screen.getByRole('button', { name: 'Bắt đầu trả thú' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Thú không còn trong chuồng');
    expect(await screen.findByText(/không cần trả thú nữa/)).toBeInTheDocument(); // đoạn của hộp thoại (câu 409 của BE nằm ở dải lỗi, không lẫn vào đây)
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('đặt chỗ không còn trong chuồng (đã trả): báo rõ, không có nút bắt đầu, không gọi start-checkout', async () => {
    await open({ id: 5 });
    expect(await screen.findByText(/Thú không còn trong chuồng/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    expect(requests().some((r) => r.includes('start-checkout'))).toBe(false);
  });

  it('lỗi tải đặt chỗ (404): hiện câu của BE, Thử lại gọi lại, Đóng đóng được', async () => {
    await signIn(ACCOUNTS.desk);
    const booking = await bookingOf(2);
    clearRequests();
    const onClose = vi.fn();
    const { user } = renderDialog(<CheckoutDialog booking={{ ...booking, bookingId: 999 }} onClose={onClose} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy đặt chỗ lưu trú #999');
    const asked = () => requests().filter((r) => r === 'GET /api/boarding-bookings/999').length;
    expect(asked()).toBe(1);
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(asked()).toBe(2));
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('CheckoutDialog — bước Order chờ thanh toán', () => {
  it('đã có Order PENDING (dù danh sách đưa vào còn activeOrderId cũ = null): vào thẳng bước chờ thanh toán, không tự gọi start-checkout', async () => {
    await signIn(ACCOUNTS.desk);
    const stale = await bookingOf(2); // activeOrderId null
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/start-checkout');
    clearRequests();
    renderDialog(<CheckoutDialog booking={stale} onClose={() => {}} />);
    expect(await screen.findByRole('button', { name: 'Kiểm tra lại' })).toBeEnabled();
    expect(screen.getByText(ORDER_CODE)).toBeInTheDocument();
    expect(screen.getByText('Chờ thanh toán')).toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('Tính lại tiền: gọi lại start-checkout, giữ Order, đêm và tiền tăng khi sang ngày mới', async () => {
    const { user } = await open();
    await start(user);
    const code = screen.getByText(ORDER_CODE).textContent;
    vi.setSystemTime(new Date('2026-10-24T09:00:00+07:00'));
    await renewSession(ACCOUNTS.desk);
    await user.click(screen.getByRole('button', { name: 'Tính lại tiền' }));
    expect(await screen.findByText('3 đêm')).toBeInTheDocument();
    expect(screen.getByText(money('600.000'))).toBeInTheDocument();
    expect(screen.getByText(ORDER_CODE).textContent).toBe(code);
    expect(writes().filter((w) => !w.includes('/auth/'))).toEqual(['POST /api/boarding-bookings/2/start-checkout', 'POST /api/boarding-bookings/2/start-checkout']);
  });

  it('Thu tiền tại bàn Lễ tân: đặt khách + Order vào phiếu thu rồi sang /staff/reception, không ghi thêm gì', async () => {
    const { user } = await open();
    await start(user);
    const order = await orderOf();
    clearRequests();
    await user.click(screen.getByRole('button', { name: 'Thu tiền tại bàn Lễ tân' }));
    expect(await screen.findByText('Trang Quầy lễ tân')).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent('/staff/reception');
    expect(usePosStore.getState()).toMatchObject({ customerId: order.customerId, orderIds: [order.orderId] });
    expect(writes()).toEqual([]);
  });

  it('Kiểm tra lại: sau khi thu tiền ở màn khác, lấy lại trạng thái PAID và bật Giao thú', async () => {
    const { user } = await open();
    await start(user);
    expect(handOverButton()).toBeDisabled();
    await collectElsewhere();
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    const dialog = screen.getByRole('dialog', { name: 'Trả thú' });
    expect(within(dialog).getByText('Đã thanh toán')).toBeInTheDocument();
    expect(within(dialog).queryByText('Order lưu trú chưa thanh toán (BR-LT-09)')).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Tính lại tiền' })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Khách không thanh toán → Hủy phiên' })).not.toBeInTheDocument();
  });

  it('lấy lại tiêu điểm cửa sổ: tự làm mới, thấy PAID mà không phải bấm gì', async () => {
    const { user } = await open();
    await start(user);
    await collectElsewhere();
    fireEvent.focus(window);
    await waitFor(() => expect(handOverButton()).toBeEnabled());
  });

  it('đã PAID rồi mở lại (cùng máy khách React Query, dữ liệu cũ còn trong bộ nhớ): vào đúng bước Giao thú, không dùng bản PENDING cũ', async () => {
    const client = appQueryClient();
    const first = await open({ client });
    await start(first.user);
    first.unmount();
    await collectElsewhere();
    const booking = await bookingOf(2);
    clearRequests();
    renderDialog(<CheckoutDialog booking={booking} onClose={() => {}} />, { client });
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    expect(screen.getByText('Đã thanh toán')).toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('lỗi tải Order (404): hiện câu của BE, Thử lại lấy lại được', async () => {
    const { user, unmount } = await open();
    await start(user);
    unmount();
    const booking = await bookingOf(2);
    vi.spyOn(salesApi, 'getOrder').mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy đơn hàng #1', 404));
    const again = renderDialog(<CheckoutDialog booking={booking} onClose={() => {}} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy đơn hàng #1');
    await again.user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('button', { name: 'Kiểm tra lại' })).toBeEnabled();
  });
});

// BE chỉ trả `Booking.activeOrderId` cho Order OPEN / PENDING (SalesOrderRepository.findUnfinishedByBookingIds): thu tiền xong (PAID) thì `activeOrderId` = null.
// Khi đó hộp thoại hỏi `GET /api/orders?boardingBookingId=` (Order mới nhất chưa hủy) rồi đọc chi tiết Order đó. Interceptor của harness chỉ ghi `config.url`
// (không ghi params), nên lệnh hỏi danh sách hiện là 'GET /api/orders' và lệnh đọc chi tiết là 'GET /api/orders/<id>'.
type OrderPage = Awaited<ReturnType<typeof salesApi.listOrders>>;
const LIST_ORDERS = 'GET /api/orders';
const orderDetailCalls = () => requests().filter((r) => /^GET \/api\/orders\/\d+$/.test(r));

/** Trạng thái thật của BE sau khi thu tiền: Order lưu trú PAID, đặt chỗ còn trong chuồng nhưng `activeOrderId` = null. */
async function paidElsewhere(bookingId = 2) {
  await signIn(ACCOUNTS.desk);
  await (await otherStaff(ACCOUNTS.desk)).post(`/api/boarding-bookings/${bookingId}/start-checkout`);
  const order = await collectElsewhere(bookingId);
  const booking = await bookingOf(bookingId);
  expect(booking.activeOrderId).toBeNull();
  return { order, booking };
}
/** Hủy phiên ở máy khác: Order PENDING → CANCELLED (CHECKOUT_ABORTED), `activeOrderId` về null. */
async function abortElsewhere(bookingId = 2) {
  const order = await orderOf(bookingId);
  await (await otherStaff(ACCOUNTS.desk)).post(`/api/orders/${order.orderId}/abort-checkout`, { reason: 'Khách bỏ về' });
  return order;
}
/** Theo dõi lệnh hỏi danh sách Order; `gate.armed = true` thì lần hỏi KẾ TIẾP bị giữ (đang tải) cho tới khi `gate.release(trang)`, các lần khác chạy thật. */
function watchOrderList() {
  const real = salesApi.listOrders;
  const gate = { armed: false, started: false, release: (_page: OrderPage) => {} };
  const spy = vi.spyOn(salesApi, 'listOrders').mockImplementation((query) => {
    if (!gate.armed) return real(query);
    gate.armed = false;
    gate.started = true;
    return new Promise<OrderPage>((resolve) => (gate.release = resolve));
  });
  return { spy, gate, real };
}
type OrderDetail = Awaited<ReturnType<typeof salesApi.getOrder>>;
/** Giống `watchOrderList` nhưng cho lần đọc chi tiết Order KẾ TIẾP (`gate.armed = true`): giữ lại thì lệnh ghi chưa "xong" (làm mới của hook còn chờ) dù đặt chỗ đã về. */
function holdNextOrderRead() {
  const real = salesApi.getOrder;
  const gate = { armed: false, started: false, release: (_order: OrderDetail) => {} };
  vi.spyOn(salesApi, 'getOrder').mockImplementation((orderId) => {
    if (!gate.armed) return real(orderId);
    gate.armed = false;
    gate.started = true;
    return new Promise<OrderDetail>((resolve) => (gate.release = resolve));
  });
  return { gate, real };
}

describe('CheckoutDialog — Order đã PAID (BE không còn trả activeOrderId)', () => {
  it('activeOrderId = null + có Order PAID: mở ra vào thẳng bước Giao thú (nút bật), không gọi start-checkout, không ghi gì', async () => {
    const { order, booking } = await paidElsewhere();
    const spy = vi.spyOn(salesApi, 'listOrders');
    clearRequests();
    renderDialog(<CheckoutDialog booking={booking} onClose={() => {}} />);
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    const dialog = screen.getByRole('dialog', { name: 'Trả thú' });
    expect(within(dialog).getByText(order.code)).toBeInTheDocument();
    expect(within(dialog).getByText('Đã thanh toán')).toBeInTheDocument();
    expect(within(dialog).getByText('2 đêm')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    expect(spy).toHaveBeenCalledWith({ boardingBookingId: 2 });
    expect(requests().filter((r) => r === LIST_ORDERS)).toHaveLength(1);
    expect(writes()).toEqual([]);
  });

  it('đang hỏi danh sách Order của đặt chỗ: báo đang tải, KHÔNG chớp "Bắt đầu trả thú"; có kết quả PAID thì vào bước Giao thú', async () => {
    const { booking } = await paidElsewhere();
    const { gate, real } = watchOrderList();
    gate.armed = true;
    clearRequests();
    renderDialog(<CheckoutDialog booking={booking} onClose={() => {}} />);
    await waitFor(() => expect(gate.started).toBe(true)); // đặt chỗ đã tải, danh sách Order đang chờ
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải Order lưu trú');
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Giao thú' })).not.toBeInTheDocument();
    gate.release(await real({ boardingBookingId: 2 }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('lỗi hỏi danh sách (404): hiện câu của BE, không hiện Bắt đầu trả thú; Thử lại hỏi lại và vào bước Giao thú', async () => {
    const { booking } = await paidElsewhere();
    const spy = vi.spyOn(salesApi, 'listOrders').mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy đặt chỗ lưu trú #2', 404));
    clearRequests();
    const onClose = vi.fn();
    const { user } = renderDialog(<CheckoutDialog booking={booking} onClose={onClose} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy đặt chỗ lưu trú #2');
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    expect(spy).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    expect(spy).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Đã thanh toán')).toBeInTheDocument();
    expect(writes()).toEqual([]);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('lỗi hỏi danh sách: nút Đóng đóng được', async () => {
    const { booking } = await paidElsewhere();
    vi.spyOn(salesApi, 'listOrders').mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy đặt chỗ lưu trú #2', 404));
    const onClose = vi.fn();
    const { user } = renderDialog(<CheckoutDialog booking={booking} onClose={onClose} />);
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('activeOrderId có sẵn (Order PENDING): KHÔNG hỏi danh sách Order của đặt chỗ, chỉ đọc đúng Order đó', async () => {
    await signIn(ACCOUNTS.desk);
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/start-checkout');
    const stale = await bookingOf(2);
    const orderId = stale.activeOrderId!;
    const spy = vi.spyOn(salesApi, 'listOrders');
    clearRequests();
    renderDialog(<CheckoutDialog booking={stale} onClose={() => {}} />);
    expect(await screen.findByRole('button', { name: 'Kiểm tra lại' })).toBeEnabled();
    expect(spy).not.toHaveBeenCalled();
    expect(requests()).not.toContain(LIST_ORDERS);
    expect(orderDetailCalls()).toEqual([`GET /api/orders/${orderId}`]);
  });

  it('activeOrderId = null + chỉ có Order CANCELLED (đã hủy phiên): vẫn là bước Bắt đầu trả thú, không đọc lại Order đã hủy', async () => {
    await signIn(ACCOUNTS.desk);
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/start-checkout');
    const aborted = await abortElsewhere();
    const booking = await bookingOf(2);
    expect(booking.activeOrderId).toBeNull();
    expect(await salesApi.getOrder(aborted.orderId)).toMatchObject({ status: 'CANCELLED', cancelType: 'CHECKOUT_ABORTED' });
    clearRequests();
    renderDialog(<CheckoutDialog booking={booking} onClose={() => {}} />);
    expect(await startButton()).toBeEnabled();
    expect(requests().filter((r) => r === LIST_ORDERS)).toHaveLength(1);
    expect(orderDetailCalls()).toEqual([]);
    expect(screen.queryByRole('button', { name: 'Giao thú' })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('activeOrderId = null + danh sách rỗng (chưa từng bắt đầu trả thú): bước Bắt đầu trả thú, đã hỏi đúng đặt chỗ này', async () => {
    const spy = vi.spyOn(salesApi, 'listOrders');
    await open();
    expect(await startButton()).toBeEnabled();
    expect(spy).toHaveBeenCalledWith({ boardingBookingId: 2 });
    expect(await spy.mock.results[0].value).toMatchObject({ content: [], totalElements: 0 });
    expect(orderDetailCalls()).toEqual([]);
    expect(writes()).toEqual([]);
  });

  it('có cả Order CANCELLED cũ lẫn PAID mới: chọn Order PAID (mới nhất chưa hủy)', async () => {
    await signIn(ACCOUNTS.desk);
    const desk = await otherStaff(ACCOUNTS.desk);
    await desk.post('/api/boarding-bookings/2/start-checkout');
    const aborted = await abortElsewhere();
    await desk.post('/api/boarding-bookings/2/start-checkout'); // Order mới (PENDING), phiên cũ đã hủy
    const paid = await collectElsewhere();
    expect(paid.orderId).not.toBe(aborted.orderId);
    const booking = await bookingOf(2);
    expect(booking.activeOrderId).toBeNull();
    const page = await salesApi.listOrders({ boardingBookingId: 2 });
    expect(page.content.map((o) => [o.orderId, o.status])).toEqual([
      [paid.orderId, 'PAID'],
      [aborted.orderId, 'CANCELLED'],
    ]);
    clearRequests();
    renderDialog(<CheckoutDialog booking={booking} onClose={() => {}} />);
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    const dialog = screen.getByRole('dialog', { name: 'Trả thú' });
    expect(within(dialog).getByText(paid.code)).toBeInTheDocument();
    expect(within(dialog).queryByText(aborted.code)).not.toBeInTheDocument();
    expect(orderDetailCalls()).toEqual([`GET /api/orders/${paid.orderId}`]);
    expect(writes()).toEqual([]);
  });

  it('Kiểm tra lại sau khi thu tiền ở máy khác: lúc hỏi lại danh sách vẫn báo đang tải, không chớp Bắt đầu trả thú từ kết quả "chưa có Order" của lần trước', async () => {
    const { gate } = watchOrderList();
    const { user } = await open(); // danh sách rỗng → Bắt đầu trả thú
    await start(user); // activeOrderId có (PENDING) → danh sách tắt
    await collectElsewhere(); // PAID: lần làm mới tới sẽ thấy activeOrderId = null
    gate.armed = true;
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }));
    await waitFor(() => expect(gate.started).toBe(true));
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải Order lưu trú');
    expect(screen.queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    gate.release(await salesApi.listOrders({ boardingBookingId: 2 }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    expect(screen.getByText('Đã thanh toán')).toBeInTheDocument();
  });
});

describe('CheckoutDialog — hủy phiên (Review Focus #4)', () => {
  it('lý do bắt buộc; hủy xong quay về bước chưa có Order, bắt đầu lại sinh Order mới', async () => {
    const abort = vi.spyOn(salesApi, 'abortCheckout');
    const { user } = await open();
    await start(user);
    const firstCode = screen.getByText(ORDER_CODE).textContent;
    const firstId = (await orderOf()).orderId;

    await user.click(screen.getByRole('button', { name: 'Khách không thanh toán → Hủy phiên' }));
    await user.click(screen.getByRole('button', { name: 'Xác nhận hủy phiên' }));
    expect(await screen.findByText('Vui lòng nhập lý do hủy phiên trả thú')).toBeInTheDocument();
    expect(abort).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText('Lý do hủy phiên'), '   ');
    await user.click(screen.getByRole('button', { name: 'Xác nhận hủy phiên' }));
    expect(abort).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText('Lý do hủy phiên'), 'Khách chưa mang đủ tiền');
    await user.click(screen.getByRole('button', { name: 'Xác nhận hủy phiên' }));

    expect(await startButton()).toBeEnabled();
    expect(abort).toHaveBeenCalledWith(firstId, 'Khách chưa mang đủ tiền');
    expect(screen.queryByText(ORDER_CODE)).not.toBeInTheDocument();
    expect(await salesApi.getOrder(firstId)).toMatchObject({ status: 'CANCELLED', cancelType: 'CHECKOUT_ABORTED' });
    expect(await bookingOf(2)).toMatchObject({ status: 'CHECKED_IN', activeOrderId: null });

    await start(user);
    expect(screen.getByText(ORDER_CODE).textContent).not.toBe(firstCode);
  });

  it('đặt chỗ làm mới xong trước khi Order được đọc lại (bước Order đã gỡ khỏi màn hình): vẫn báo "Đã hủy phiên" đúng một lần', async () => {
    const notify = vi.spyOn(toast, 'success').mockImplementation(() => '');
    const { gate, real } = holdNextOrderRead();
    const { user } = await open();
    await start(user);
    const { orderId, code } = await orderOf();
    await user.click(screen.getByRole('button', { name: 'Khách không thanh toán → Hủy phiên' }));
    await user.type(screen.getByLabelText('Lý do hủy phiên'), 'Khách chưa mang đủ tiền');
    gate.armed = true; // lần đọc Order kế tiếp (làm mới sau khi hủy phiên) bị giữ: lệnh ghi chưa xong với màn hình
    await user.click(screen.getByRole('button', { name: 'Xác nhận hủy phiên' }));
    await waitFor(() => expect(gate.started).toBe(true));
    expect(await startButton()).toBeEnabled(); // đặt chỗ đã làm mới: bước Order bị gỡ, hiện bước Bắt đầu trả thú
    expect(notify).not.toHaveBeenCalled();
    gate.release(await real(orderId));
    await waitFor(() => expect(notify).toHaveBeenCalledTimes(1));
    expect(notify).toHaveBeenCalledWith(`Đã hủy phiên trả thú ${code}. Thú vẫn ở trong chuồng, bắt đầu lại khi khách sẵn sàng.`);
  });

  it('Quay lại trong khung lý do: đóng khung, chưa hủy gì', async () => {
    const abort = vi.spyOn(salesApi, 'abortCheckout');
    const { user } = await open();
    await start(user);
    await user.click(screen.getByRole('button', { name: 'Khách không thanh toán → Hủy phiên' }));
    await user.type(screen.getByLabelText('Lý do hủy phiên'), 'Nháp');
    await user.click(screen.getByRole('button', { name: 'Quay lại' }));
    expect(screen.queryByLabelText('Lý do hủy phiên')).not.toBeInTheDocument();
    expect(abort).not.toHaveBeenCalled();
  });

  it('409 khi hủy phiên mà khách vừa trả tiền: hiện câu của BE, làm mới sang bước Giao thú', async () => {
    const { user } = await open();
    await start(user);
    await user.click(screen.getByRole('button', { name: 'Khách không thanh toán → Hủy phiên' }));
    await user.type(screen.getByLabelText('Lý do hủy phiên'), 'Khách bảo không trả');
    await collectElsewhere();
    await user.click(screen.getByRole('button', { name: 'Xác nhận hủy phiên' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('PAID');
    await waitFor(() => expect(handOverButton()).toBeEnabled());
  });
});

describe('CheckoutDialog — giao thú (BR-LT-09)', () => {
  it('Order PAID: Giao thú gọi hand-over, đặt chỗ CHECKED_OUT, chuồng trống, báo "Đã giao" và đóng hộp thoại đúng một lần', async () => {
    const notify = vi.spyOn(toast, 'success').mockImplementation(() => '');
    const { user, onClose } = await open();
    await start(user);
    await collectElsewhere();
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    clearRequests();
    await user.click(handOverButton());
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(writes()).toEqual(['POST /api/boarding-bookings/2/hand-over']);
    expect(await bookingOf(2)).toMatchObject({ status: 'CHECKED_OUT', endReason: 'RETURNED', activeOrderId: null });
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith('Đã giao Cốm cho khách.');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('đặt chỗ làm mới xong (CHECKED_OUT, hộp thoại đã sang "không cần trả thú") trước khi các truy vấn khác về: vẫn báo "Đã giao" và đóng đúng một lần', async () => {
    const notify = vi.spyOn(toast, 'success').mockImplementation(() => '');
    const { gate, real } = watchOrderList();
    const { user, onClose } = await open();
    await start(user);
    await collectElsewhere();
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    clearRequests();
    gate.armed = true; // lần hỏi danh sách Order kế tiếp (làm mới sau hand-over) bị giữ: lệnh ghi chưa xong với màn hình
    await user.click(handOverButton());
    await waitFor(() => expect(gate.started).toBe(true));
    expect(await screen.findByText(/không cần trả thú nữa/)).toBeInTheDocument(); // bước Order (nút Giao thú) đã bị gỡ khỏi cây
    expect(screen.queryByRole('button', { name: 'Giao thú' })).not.toBeInTheDocument();
    expect(notify).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    gate.release(await real({ boardingBookingId: 2 }));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith('Đã giao Cốm cho khách.');
    expect(writes()).toEqual(['POST /api/boarding-bookings/2/hand-over']);
  });

  it('nếu bấm được khi Order chưa PAID (màn hình lệch): hiện nguyên câu 400 BR-LT-09 của BE, không đóng', async () => {
    const { user, unmount } = await open();
    await start(user);
    unmount();
    const booking = await bookingOf(2);
    const real = await orderOf();
    vi.spyOn(salesApi, 'getOrder').mockResolvedValueOnce({ ...real, status: 'PAID' });
    const onClose = vi.fn();
    const again = renderDialog(<CheckoutDialog booking={booking} onClose={onClose} />);
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    await again.user.click(handOverButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Order lưu trú chưa thanh toán');
    expect(onClose).not.toHaveBeenCalled();
    expect((await bookingOf(2)).status).toBe('CHECKED_IN');
    // Sau khi làm mới, màn hình thấy đúng trạng thái thật.
    await waitFor(() => expect(handOverButton()).toBeDisabled());
  });

  it('409 (người khác vừa giao thú): hiện câu của BE, làm mới, chỉ còn Đóng', async () => {
    const { user, onClose } = await open();
    await start(user);
    await collectElsewhere();
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lại' }));
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/hand-over');
    await user.click(handOverButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_OUT sang CHECKED_OUT'); // hand-over: câu FSM của BE
    expect(await screen.findByText(/Thú không còn trong chuồng/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Giao thú' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('CheckoutDialog — quyền', () => {
  it.each([
    ['CARETAKER', ACCOUNTS.caretaker],
    ['BRANCH_MANAGER', ACCOUNTS.manager],
    ['VET', ACCOUNTS.vet],
  ])('%s chỉ thấy lời báo không có quyền, không gọi API nào (không 403)', async (_role, email) => {
    await open({ email });
    const dialog = await screen.findByRole('dialog', { name: 'Trả thú' });
    expect(within(dialog).getByText(/Chỉ lễ tân/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Bắt đầu trả thú' })).not.toBeInTheDocument();
    expect(requests()).toEqual([]);
    expect(forbidden()).toEqual([]);
  });

  it('lễ tân: cả vòng bắt đầu → thu → giao thú không phát sinh 403', async () => {
    const { user, onClose } = await open();
    await start(user);
    await collectElsewhere();
    fireEvent.focus(window);
    await waitFor(() => expect(handOverButton()).toBeEnabled());
    await user.click(handOverButton());
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(forbidden()).toEqual([]);
  });
});
