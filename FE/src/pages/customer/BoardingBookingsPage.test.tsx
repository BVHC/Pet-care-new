import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import axios from 'axios';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { boardingApi } from '../../shared/api/boarding.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { Booking } from '../../shared/types/boarding';
import { pinClock } from '../../test/boarding-harness';
import { BoardingBookingsPage } from './BoardingBookingsPage';
import { apiFailure, asPage, fakeBooking } from './boarding/testing';

// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). khach@petcare.vn chỉ có Bim: #4 BOOKED 26/10→28/10, #5 CHECKED_OUT 03/10→06/10 (chuồng C-03),
// #6 CANCELLED 01/11→03/11 (khách hủy sớm). Các trạng thái còn lại (đang ở, quá hạn, không đến, phòng khám hủy, hủy muộn) dùng bản ghi giả qua boardingApi.list.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  pinClock();
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/boarding-bookings']}>
        <BoardingBookingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const fake = fakeBooking;
const stub = (rows: Booking[]) => vi.spyOn(boardingApi, 'list').mockResolvedValue(asPage(rows));
const cards = () => screen.queryAllByRole('article');
const cardOf = (code: string) => screen.getByRole('article', { name: `Lưu trú ${code}` });
const tab = (name: RegExp) => screen.getByRole('tab', { name });
/** Danh sách đã tải xong (hết khung chờ), dù tab đang mở có thẻ hay không. */
const loaded = () => waitFor(() => expect(screen.queryByRole('status', { name: 'Đang tải lưu trú' })).not.toBeInTheDocument());

describe('BoardingBookingsPage (UC60): danh sách lưu trú của tôi', () => {
  it('hiện đang tải rồi tới thẻ; gọi API một lần với size 100', async () => {
    const spy = vi.spyOn(boardingApi, 'list');
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải lưu trú' })).toBeInTheDocument();
    expect(await screen.findAllByRole('article')).toHaveLength(1);
    expect(screen.queryByRole('status', { name: 'Đang tải lưu trú' })).not.toBeInTheDocument();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith({ size: 100 });
  });

  it('có đúng một <h1> "Lưu trú của tôi" và nút sang trang đặt chỗ', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { level: 1, name: 'Lưu trú của tôi' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Đặt chỗ lưu trú' })).toHaveAttribute('href', '/boarding-bookings/new');
  });

  it('tab "Đang & sắp tới" là mặc định: chỉ đặt chỗ BOOKED của Bim, kèm số lượng từng tab', async () => {
    renderPage();
    await screen.findAllByRole('article');
    expect(tab(/^Đang & sắp tới/)).toHaveAttribute('aria-selected', 'true');
    expect(tab(/^Đang & sắp tới/)).toHaveTextContent('1');
    expect(tab(/^Đã qua/)).toHaveTextContent('2');
    const [card] = cards();
    expect(within(card).getByText('Đã đặt')).toBeInTheDocument();
    expect(within(card).getByRole('heading', { name: 'Bim' })).toBeInTheDocument();
  });

  it('thẻ đặt chỗ: loại chuồng, chi nhánh, ngày nhận → trả, số đêm, giá đêm, tiền tạm tính và ghi chú chốt khi trả thú', async () => {
    renderPage();
    const [card] = await screen.findAllByRole('article');
    expect(within(card).getByText('Chuồng chó nhỏ')).toBeInTheDocument();
    expect(await within(card).findByText('PetCare Cầu Giấy')).toBeInTheDocument();
    expect(within(card).getByText('26/10/2026')).toBeInTheDocument();
    expect(within(card).getByText('28/10/2026')).toBeInTheDocument();
    expect(within(card).getByText('2 đêm')).toBeInTheDocument();
    expect(within(card).getByText(/200\.000/)).toHaveTextContent('/đêm');
    expect(within(card).getByText(/^Tạm tính/)).toHaveTextContent(/400\.000/);
    expect(within(card).getByText(/Tiền chốt khi trả thú/)).toBeInTheDocument();
    // Chưa nhận thú thì chưa có mã chuồng.
    expect(within(card).queryByText(/Mã chuồng/)).not.toBeInTheDocument();
  });

  it('"Xem chi tiết" dẫn tới /boarding-bookings/:bookingId của đúng đặt chỗ', async () => {
    renderPage();
    const [card] = await screen.findAllByRole('article');
    expect(within(card).getByRole('link', { name: /Xem chi tiết/ })).toHaveAttribute('href', '/boarding-bookings/4');
  });

  it('tab "Đã qua": đã trả thú và đã hủy, mới nhất lên đầu; hủy sớm của khách không có chip hủy muộn', async () => {
    const user = renderPage();
    await screen.findAllByRole('article');
    await user.click(tab(/^Đã qua/));
    expect(tab(/^Đã qua/)).toHaveAttribute('aria-selected', 'true');
    const [cancelled, returned] = cards();
    expect(within(cancelled).getByText('Đã hủy')).toBeInTheDocument();
    expect(within(cancelled).getByText('Khách hủy')).toBeInTheDocument();
    expect(within(cancelled).getByText(/Đổi kế hoạch công tác/)).toBeInTheDocument();
    expect(within(cancelled).queryByText(/Hủy muộn/)).not.toBeInTheDocument();
    expect(within(returned).getByText('Đã trả thú')).toBeInTheDocument();
    expect(within(returned).getByText('3 đêm')).toBeInTheDocument();
    // Đặt chỗ đã hủy / đã trả không còn "tạm tính" để khỏi hiểu là còn phải trả.
    expect(within(cancelled).queryByText(/^Tạm tính/)).not.toBeInTheDocument();
    expect(within(returned).queryByText(/^Tạm tính/)).not.toBeInTheDocument();
  });

  it('đang ở: hiện mã chuồng, quá hạn đón lên đầu và nêu từ ngày nào, hướng dẫn gọi chi nhánh', async () => {
    stub([
      fake({ bookingId: 11, code: 'LT-2610-00011', status: 'BOOKED', checkInDate: '2026-11-05', checkOutDate: '2026-11-06' }),
      fake({ bookingId: 12, code: 'LT-2610-00012', status: 'CHECKED_IN', kennelId: 1, kennelCode: 'C-01', checkInDate: '2026-10-21', checkOutDate: '2026-10-31' }),
      fake({ bookingId: 13, code: 'LT-2610-00013', status: 'OVERDUE', kennelId: 2, kennelCode: 'C-02', checkInDate: '2026-10-15', checkOutDate: '2026-10-22', overdueSince: '2026-10-22T05:00:00.000Z' }),
      fake({ bookingId: 14, code: 'LT-2610-00014', status: 'BOOKED', checkInDate: '2026-10-25', checkOutDate: '2026-10-26' }),
    ]);
    renderPage();
    const list = await screen.findAllByRole('article');
    expect(list.map((c) => c.getAttribute('aria-label'))).toEqual([
      'Lưu trú LT-2610-00013',
      'Lưu trú LT-2610-00012',
      'Lưu trú LT-2610-00014',
      'Lưu trú LT-2610-00011',
    ]);
    expect(within(cardOf('LT-2610-00012')).getByText('Đang lưu trú')).toBeInTheDocument();
    expect(within(cardOf('LT-2610-00012')).getByText('Mã chuồng C-01')).toBeInTheDocument();
    const overdue = cardOf('LT-2610-00013');
    expect(within(overdue).getByText('Quá hạn đón')).toBeInTheDocument();
    expect(within(overdue).getByText('Mã chuồng C-02')).toBeInTheDocument();
    expect(within(overdue).getByRole('note')).toHaveTextContent(/Đã quá hạn đón từ 22\/10\/2026/);
    expect(within(overdue).getByRole('note')).toHaveTextContent(/liên hệ chi nhánh/);
  });

  it('đặt chỗ bị hủy: nguồn hủy (khách / lễ tân / phòng khám) và chip hủy muộn tính vào hạn chế đặt online', async () => {
    stub([
      fake({ bookingId: 21, code: 'LT-2610-00021', status: 'CANCELLED', cancelSource: 'CLINIC', cancelReason: 'Chi nhánh nghỉ vào ngày nhận', lateCancel: false }),
      fake({ bookingId: 22, code: 'LT-2610-00022', status: 'CANCELLED', cancelSource: 'STAFF', lateCancel: true, cancelReason: 'Khách gọi báo bận' }),
      fake({ bookingId: 23, code: 'LT-2610-00023', status: 'NO_SHOW' }),
    ]);
    const user = renderPage();
    await loaded();
    await user.click(tab(/^Đã qua/));
    const clinic = cardOf('LT-2610-00021');
    expect(within(clinic).getByText('Phòng khám hủy')).toBeInTheDocument();
    expect(within(clinic).getByText(/Chi nhánh nghỉ vào ngày nhận/)).toBeInTheDocument();
    expect(within(clinic).queryByText(/Hủy muộn/)).not.toBeInTheDocument();
    const late = cardOf('LT-2610-00022');
    expect(within(late).getByText('Lễ tân hủy')).toBeInTheDocument();
    expect(within(late).getByText('Hủy muộn')).toBeInTheDocument();
    expect(within(late).getByText(/tính vào hạn chế đặt online/)).toHaveTextContent(/dưới 24 giờ/);
    const missed = cardOf('LT-2610-00023');
    expect(within(missed).getByText('Không đến')).toBeInTheDocument();
    expect(within(missed).getByText(/tính vào hạn chế đặt online/)).toBeInTheDocument();
  });

  it('thú mất trong lúc lưu trú: ghi rõ lý do kết thúc', async () => {
    stub([fake({ bookingId: 31, code: 'LT-2610-00031', status: 'CHECKED_OUT', endReason: 'PET_DECEASED' })]);
    const user = renderPage();
    await loaded();
    await user.click(tab(/^Đã qua/));
    expect(within(cardOf('LT-2610-00031')).getByText('Thú đã mất')).toBeInTheDocument();
  });

  it('chưa có lưu trú nào: hiện trạng thái rỗng của từng tab, không thẻ nào', async () => {
    stub([]);
    const user = renderPage();
    expect(await screen.findByText('Chưa có lưu trú sắp tới')).toBeInTheDocument();
    expect(cards()).toHaveLength(0);
    await user.click(tab(/^Đã qua/));
    expect(screen.getByText('Chưa có lưu trú đã qua')).toBeInTheDocument();
  });

  it('lỗi từ BE hiện nguyên câu, có nút Thử lại gọi lại API và hiện dữ liệu khi thành công', async () => {
    const spy = vi.spyOn(boardingApi, 'list').mockRejectedValueOnce(apiFailure(403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này'));
    const user = renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
    expect(cards()).toHaveLength(0);
    spy.mockRestore();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findAllByRole('article')).toHaveLength(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('khách bị hạn chế đặt online (BR-LH-09): thấy thông báo, danh sách vẫn xem được, không còn nút đặt chỗ', async () => {
    // Hạn chế dùng chung với lịch hẹn: mẫu có 1 lần hủy muộn, thêm 2 vi phạm qua API (như BookingPage.test).
    const raw = axios.create();
    installMockApi(raw);
    const login = async (email: string) => ({
      headers: { Authorization: `Bearer ${(await raw.post('/api/auth/login', { email, password: MOCK_PASSWORD })).data.data.accessToken}` },
    });
    const owner = await login('khach@petcare.vn');
    const desk = await login('reception@store1.vn');
    const today = (await raw.get('/api/appointments', { params: { date: '2026-10-23' }, ...owner })).data.data.content;
    await raw.post(`/api/appointments/${today[0].appointmentId}/cancel`, {}, owner);
    const counter = (await raw.post('/api/appointments', { petId: 2, branchId: 1, serviceId: 1, slotDate: '2026-10-23', slotStart: '13:30' }, desk)).data.data;
    await raw.post(`/api/appointments/${counter.appointmentId}/cancel`, {}, desk);

    renderPage();
    expect(await screen.findByRole('alert', { name: 'Hạn chế đặt lịch online' })).toHaveTextContent('đến 09:00 22/11/2026');
    expect(await screen.findAllByRole('article')).toHaveLength(1);
    expect(screen.queryByRole('link', { name: 'Đặt chỗ lưu trú' })).not.toBeInTheDocument();
  });

  it('khách chưa bị hạn chế không thấy thông báo hạn chế', async () => {
    renderPage();
    await screen.findAllByRole('article');
    expect(screen.queryByRole('alert', { name: 'Hạn chế đặt lịch online' })).not.toBeInTheDocument();
  });

  it('khách chờ liên kết hồ sơ (BR-TK-19) vẫn xem được danh sách: trống, không lỗi', async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: 'khachmoi@petcare.vn', password: MOCK_PASSWORD });
    renderPage();
    expect(await screen.findByText('Chưa có lưu trú sắp tới')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đặt chỗ lưu trú' })).toHaveAttribute('href', '/boarding-bookings/new');
  });

  it('chuyển tab không gọi lại API (lọc ở client)', async () => {
    const spy = vi.spyOn(boardingApi, 'list');
    const user = renderPage();
    await screen.findAllByRole('article');
    await user.click(tab(/^Đã qua/));
    await user.click(tab(/^Đang & sắp tới/));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
  });

  it('tên thú rất dài không làm vỡ thẻ (chỉ kiểm có hiện; tràn ngang xem bằng trình duyệt)', async () => {
    const longName = 'Bé Bông Tuyết Cực Kỳ Đáng Yêu Của Gia Đình Nguyễn Văn Thu Trang Phường Dịch Vọng Hậu';
    stub([fake({ petName: longName })]);
    renderPage();
    expect(await screen.findByRole('heading', { name: longName })).toBeInTheDocument();
  });
});
