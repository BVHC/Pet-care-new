import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { boardingApi } from '../../shared/api/boarding.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { Booking, BookingStatus, CareLog } from '../../shared/types/boarding';
import { ACCOUNTS, authAs, FRIDAY_9, makeClient, pinClock } from '../../test/boarding-harness';
import { BoardingDetailPage } from './BoardingDetailPage';

// UC58 gia hạn / hủy và UC60 xem lưu trú + nhật ký (T48, CUST-VIEW). Đồng hồ ghim thứ Sáu 23/10/2026 09:00 giờ VN.
// Dữ liệu mẫu của khach@petcare.vn (Bim #2): #4 BOOKED 26/10 → 28/10 (2 đêm, 200.000 đ/đêm), #5 CHECKED_OUT 03/10 → 06/10 với 3 nhật ký,
// #6 CANCELLED 01/11 → 03/11 (khách hủy sớm). Cốm #2 / Mực #3 đang CHECKED_IN nhưng của khách khác: khách này nhận 404.
// Đặt chỗ ở trạng thái mà khách này không có sẵn (đang lưu trú, quá hạn đón, không đến, thú mất) dùng bản giả của `boardingApi`.

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  pinClock();
  useSession.getState().clear();
  await useSession.getState().login({ email: ACCOUNTS.customer, password: MOCK_PASSWORD });
});
afterEach(() => {
  vi.useRealTimers();
});

const BIM_4: Booking = {
  bookingId: 4,
  code: 'LT-2610-00004',
  customerId: 1,
  customerName: 'Nguyễn Thu Trang',
  petId: 2,
  petName: 'Bim',
  branchId: 1,
  kennelTypeId: 5,
  kennelTypeName: 'Chuồng chó nhỏ',
  kennelId: null,
  kennelCode: null,
  checkInDate: '2026-10-21',
  checkOutDate: '2026-10-31',
  actualCheckInAt: null,
  actualCheckOutAt: null,
  nightlyPrice: 200_000,
  status: 'CHECKED_IN',
  endReason: null,
  channel: 'ONLINE',
  lateCancel: false,
  cancelSource: null,
  cancelReason: null,
  overdueSince: null,
  activeOrderId: null,
  missingCareLogToday: false,
};

const log = (over: Partial<CareLog> = {}): CareLog => ({
  careLogId: 1,
  bookingId: 4,
  logDate: '2026-10-22',
  eating: null,
  drinking: null,
  hygiene: null,
  activity: null,
  note: null,
  photoUrls: [],
  isAbnormal: false,
  recordedBy: 6,
  recordedByName: 'Thu Hà',
  createdAt: '2026-10-22T10:30:00.000Z',
  editableUntil: '2026-10-22T11:30:00.000Z',
  addenda: [],
  ...over,
});

/** Đặt chỗ giả (đang lưu trú mặc định, chuồng C-01) và nhật ký giả. Trả về spy để đếm số lần gọi. */
function stub(over: Partial<Booking> = {}, logs: CareLog[] = []) {
  const merged: Booking = { ...BIM_4, kennelId: 1, kennelCode: 'C-01', actualCheckInAt: '2026-10-21T02:30:00.000Z', ...over };
  return {
    get: vi.spyOn(boardingApi, 'get').mockResolvedValue(merged),
    list: vi.spyOn(boardingApi, 'listCareLogs').mockResolvedValue(logs),
  };
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/boarding-bookings" element={<p>Trang danh sách lưu trú</p>} />
          <Route path="/boarding-bookings/:bookingId" element={<BoardingDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { user: userEvent.setup(), client, ...view };
}

const loaded = () => screen.findByRole('heading', { level: 2, name: 'Bim' });
const region = (name: string) => screen.getByRole('region', { name });
/** Đợi lần làm mới sau lỗi xong (đặt chỗ được tải lại) và trang vẽ lại xong. */
async function refreshed(get: { mock: { calls: unknown[] } }) {
  await waitFor(() => expect(get.mock.calls.length).toBeGreaterThanOrEqual(2));
  await act(async () => {
    await new Promise((r) => setTimeout(r, 30));
  });
}
const NETWORK_DOWN = () => new ApiError('INTERNAL_ERROR', 'Không kết nối được máy chủ. Vui lòng thử lại.', 0);

/** Nạp dữ liệu mẫu theo ngày đang ghim (mock tính ngày mẫu lúc nạp lần đầu), để sau đó dời đồng hồ mà đặt chỗ không dời theo. */
const seed = () => boardingApi.get(4);
const moveClock = (iso: string) => vi.setSystemTime(new Date(iso));

describe('khung trang và đường dẫn', () => {
  it.each(['abc', '0', '12abc', '1.5', '-3', '007x'])('id "%s" không phải số nguyên dương: trang không tìm thấy, không gọi API', async (id) => {
    const get = vi.spyOn(apiClient, 'get');
    renderAt(`/boarding-bookings/${id}`);
    expect(screen.getByRole('heading', { level: 1, name: 'Chi tiết lưu trú' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Không tìm thấy lưu trú' })).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });

  it('đặt chỗ của khách khác (#2 Cốm): 404 → trang thân thiện, không lộ gì, không gọi nhật ký, nút quay lại danh sách', async () => {
    const list = vi.spyOn(boardingApi, 'listCareLogs');
    const { user } = renderAt('/boarding-bookings/2');
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy lưu trú' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Chi tiết lưu trú' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Cốm');
    expect(document.body).not.toHaveTextContent('#2');
    expect(list).not.toHaveBeenCalled();
    await user.click(screen.getByRole('link', { name: /Quay lại Lưu trú của tôi/ }));
    expect(await screen.findByText('Trang danh sách lưu trú')).toBeInTheDocument();
  });

  it('đặt chỗ không có (#999): cùng trang không tìm thấy', async () => {
    renderAt('/boarding-bookings/999');
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy lưu trú' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('#999');
  });

  it('đang tải: có <h1> và trạng thái chờ, chưa có nút hành động', async () => {
    vi.spyOn(boardingApi, 'get').mockReturnValue(new Promise(() => {}));
    renderAt('/boarding-bookings/4');
    expect(screen.getByRole('heading', { level: 1, name: 'Chi tiết lưu trú' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải chi tiết lưu trú');
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
  });

  it('lỗi mạng: hiện câu lỗi + "Thử lại", bấm lại thì tải được', async () => {
    const get = vi.spyOn(boardingApi, 'get').mockRejectedValueOnce(NETWORK_DOWN()).mockResolvedValue({ ...BIM_4, status: 'BOOKED' });
    vi.spyOn(boardingApi, 'listCareLogs').mockResolvedValue([]);
    const { user } = renderAt('/boarding-bookings/4');
    expect(await screen.findByRole('alert')).toHaveTextContent('Không kết nối được máy chủ. Vui lòng thử lại.');
    expect(screen.queryByRole('heading', { name: 'Không tìm thấy lưu trú' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await loaded()).toBeInTheDocument();
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('chỉ 404 mới là "không tìm thấy": 500 vẫn là lỗi có nút Thử lại', async () => {
    vi.spyOn(boardingApi, 'get').mockRejectedValue(new ApiError('INTERNAL_ERROR', 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.', 0));
    renderAt('/boarding-bookings/4');
    expect(await screen.findByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Không tìm thấy lưu trú' })).not.toBeInTheDocument();
  });
});

describe('khách đang chờ liên kết hồ sơ (BR-TK-19)', () => {
  beforeEach(async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: ACCOUNTS.pendingCustomer, password: MOCK_PASSWORD });
  });

  it('Gia hạn và Hủy không bị chặn: BR-TK-19 chỉ chặn đặt chỗ mới (#8), BE không chặn #11 / #12', async () => {
    stub({ status: 'BOOKED', checkInDate: '2026-10-26', checkOutDate: '2026-10-28', kennelId: null, kennelCode: null, actualCheckInAt: null });
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByRole('button', { name: 'Gia hạn' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hủy đặt chỗ' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Không có quyền');
  });

  it('đặt chỗ của người khác: vẫn 404 thân thiện, không lỗi quyền', async () => {
    renderAt('/boarding-bookings/4');
    expect(await screen.findByRole('heading', { name: 'Không tìm thấy lưu trú' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Không có quyền');
    expect(document.body).not.toHaveTextContent('Bim');
  });
});

describe('đặt chỗ BOOKED (#4 của Bim)', () => {
  it('đầu trang, khối thông tin và tạm tính', async () => {
    const list = vi.spyOn(boardingApi, 'listCareLogs');
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByText(/^LT-\d{4}-\d{5}$/)).toBeInTheDocument();
    expect(screen.getByText('Chuồng chó nhỏ')).toBeInTheDocument();
    expect(screen.getAllByText('Đã đặt').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Mã chuồng/)).not.toBeInTheDocument();

    const info = region('Thông tin lưu trú');
    expect(info).toHaveTextContent('26/10/2026');
    expect(info).toHaveTextContent('28/10/2026');
    expect(within(info).getByText('2 đêm')).toBeInTheDocument();
    expect(info).toHaveTextContent(/200\.000/);
    expect(info).toHaveTextContent(/400\.000/);
    expect(info).toHaveTextContent('Tiền chốt khi trả thú, tính theo đêm thực tế; trả sau 12:00 tính thêm 1 đêm');
    expect(info).toHaveTextContent('Đặt online');
    await waitFor(() => expect(info).toHaveTextContent('PetCare Cầu Giấy'));
    expect(within(info).getByRole('link', { name: /0901234567/ })).toHaveAttribute('href', 'tel:0901234567');
    expect(list).not.toHaveBeenCalled();
  });

  it('BOOKED: có Gia hạn và Hủy; nhật ký nói "chưa có" và không gọi API nhật ký', async () => {
    const list = vi.spyOn(boardingApi, 'listCareLogs');
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByRole('button', { name: 'Gia hạn' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hủy đặt chỗ' })).toBeInTheDocument();
    expect(region('Nhật ký chăm sóc')).toHaveTextContent('Nhật ký chăm sóc sẽ xuất hiện khi Bim bắt đầu lưu trú');
    expect(screen.queryByRole('button', { name: 'Làm mới' })).not.toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
  });

  it('dòng thời gian: "Đã đặt" là bước hiện tại, "Đang lưu trú" và "Đã trả thú" chưa tới, không có "Quá hạn đón"', async () => {
    renderAt('/boarding-bookings/4');
    await loaded();
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps.map((s) => s.textContent)).toEqual([
      expect.stringContaining('Đã đặt'),
      expect.stringContaining('Đang lưu trú'),
      expect.stringContaining('Đã trả thú'),
    ]);
    expect(steps[0]).toHaveAttribute('aria-current', 'step');
    expect(steps[1]).not.toHaveAttribute('aria-current');
    expect(steps[2]).toHaveTextContent('Dự kiến');
    expect(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).not.toHaveTextContent('Quá hạn đón');
  });

  it('chữ hiển thị không dùng em-dash hay en-dash', async () => {
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(document.body.textContent).not.toMatch(/[—–]/);
  });
});

describe('đặt chỗ CHECKED_OUT (#5 của Bim, 3 nhật ký)', () => {
  it('không có hành động; dòng thời gian đủ mốc giờ VN; nhật ký nhóm ngày mới → cũ', async () => {
    renderAt('/boarding-bookings/5');
    await loaded();
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Làm mới' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Mã chuồng/)).not.toBeInTheDocument(); // chuồng đã trả: chỉ nói mã chuồng khi đang ở

    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps).toHaveLength(3);
    expect(steps[1]).toHaveTextContent('09:30 03/10/2026');
    expect(steps[2]).toHaveTextContent('10:15 06/10/2026');
    expect(steps[2]).toHaveAttribute('aria-current', 'step');

    const logs = region('Nhật ký chăm sóc');
    const days = await within(logs).findAllByRole('heading', { level: 3 });
    expect(days.map((d) => d.textContent)).toEqual([
      expect.stringContaining('05/10/2026'),
      expect.stringContaining('04/10/2026'),
      expect.stringContaining('03/10/2026'),
    ]);
    expect(within(logs).getAllByRole('article')).toHaveLength(3);
    expect(logs).toHaveTextContent('Thu Hà');
    expect(logs).toHaveTextContent('Bé ngoan, sẵn sàng về nhà ngày mai');
    expect(logs).not.toHaveTextContent('Hôm nay thú cưng chưa có nhật ký');
  });

  it('nhãn tạm tính của đặt chỗ đã trả là "theo lịch đặt" (không có số tiền chốt)', async () => {
    renderAt('/boarding-bookings/5');
    await loaded();
    const info = region('Thông tin lưu trú');
    expect(info).toHaveTextContent('Tạm tính theo lịch đặt');
    expect(info).toHaveTextContent('Tiền chốt khi trả thú');
  });
});

describe('đặt chỗ CANCELLED (#6 của Bim)', () => {
  it('nguồn hủy, lý do; không hủy muộn; không hành động, không gọi nhật ký, ẩn tạm tính', async () => {
    const list = vi.spyOn(boardingApi, 'listCareLogs');
    renderAt('/boarding-bookings/6');
    await loaded();
    const notice = screen.getByRole('region', { name: 'Thông tin hủy' });
    expect(notice).toHaveTextContent('Khách hủy');
    expect(notice).toHaveTextContent('Đổi kế hoạch công tác');
    expect(notice).not.toHaveTextContent('Hủy muộn');
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
    expect(region('Thông tin lưu trú')).not.toHaveTextContent('Tạm tính');
    expect(region('Nhật ký chăm sóc')).toHaveTextContent('Đặt chỗ này không có nhật ký chăm sóc');
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps.map((s) => s.textContent)).toEqual([expect.stringContaining('Đã đặt'), expect.stringContaining('Đã hủy')]);
    expect(list).not.toHaveBeenCalled();
  });

  it('hủy muộn do lễ tân: nêu "Hủy muộn" và hệ quả hạn chế đặt online (3 lần trong 90 ngày), kể cả khi người hủy là lễ tân', async () => {
    stub({ status: 'CANCELLED', cancelSource: 'STAFF', lateCancel: true, cancelReason: 'Khách gọi báo bận', kennelId: null, kennelCode: null, actualCheckInAt: null });
    renderAt('/boarding-bookings/4');
    await loaded();
    const notice = screen.getByRole('region', { name: 'Thông tin hủy' });
    expect(notice).toHaveTextContent('Lễ tân hủy');
    expect(notice).toHaveTextContent('Hủy muộn');
    expect(notice).toHaveTextContent(/3 lần/);
    expect(notice).toHaveTextContent(/90 ngày/);
    expect(notice).toHaveTextContent(/hạn chế đặt online/);
  });

  it('phòng khám hủy (không muộn): nêu nguồn và không tính vào hạn chế', async () => {
    stub({ status: 'CANCELLED', cancelSource: 'CLINIC', lateCancel: false, cancelReason: 'Chi nhánh nghỉ vào ngày nhận', kennelId: null, kennelCode: null, actualCheckInAt: null });
    renderAt('/boarding-bookings/4');
    await loaded();
    const notice = screen.getByRole('region', { name: 'Thông tin hủy' });
    expect(notice).toHaveTextContent('Phòng khám hủy');
    expect(notice).toHaveTextContent('Chi nhánh nghỉ vào ngày nhận');
    expect(notice).toHaveTextContent('không tính vào hạn chế đặt online');
    expect(notice).not.toHaveTextContent('Hủy muộn');
  });

  it('hủy không có lý do: không in dòng lý do trống', async () => {
    stub({ status: 'CANCELLED', cancelSource: 'CUSTOMER', cancelReason: null, kennelId: null, kennelCode: null, actualCheckInAt: null });
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByRole('region', { name: 'Thông tin hủy' })).not.toHaveTextContent('Lý do');
  });
});

describe('các trạng thái khác (đặt chỗ giả)', () => {
  it('CHECKED_IN: mã chuồng, giờ nhận thực tế, có Gia hạn, không có Hủy, nhật ký gọi một lần', async () => {
    const { list } = stub({}, [log({ eating: 'Ăn tốt' })]);
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByText(/Mã chuồng/)).toHaveTextContent('C-01');
    expect(screen.getByRole('button', { name: 'Gia hạn' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps[1]).toHaveTextContent('09:30 21/10/2026');
    expect(steps[1]).toHaveAttribute('aria-current', 'step');
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
  });

  it('OVERDUE: cảnh báo quá hạn đón + liên hệ chi nhánh; không có nút Gia hạn; bước "Quá hạn đón" hiện tại', async () => {
    const { list } = stub({ status: 'OVERDUE', checkOutDate: '2026-10-22', overdueSince: '2026-10-22T05:00:00.000Z' }, [log()]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Đã quá hạn đón');
    expect(alert).toHaveTextContent('vui lòng liên hệ chi nhánh');
    expect(alert).toHaveTextContent('12:00 22/10/2026');
    await waitFor(() => expect(alert).toHaveTextContent('0901234567'));
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps.map((s) => s.textContent)).toEqual([
      expect.stringContaining('Đã đặt'),
      expect.stringContaining('Đang lưu trú'),
      expect.stringContaining('Quá hạn đón'),
      expect.stringContaining('Đã trả thú'),
    ]);
    expect(steps[2]).toHaveAttribute('aria-current', 'step');
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
  });

  it('đã trả sau khi từng quá hạn: dòng thời gian giữ bước "Quá hạn đón" đã qua', async () => {
    stub({ status: 'CHECKED_OUT', endReason: 'RETURNED', actualCheckOutAt: '2026-10-22T08:00:00.000Z', overdueSince: '2026-10-22T05:00:00.000Z' });
    renderAt('/boarding-bookings/4');
    await loaded();
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps).toHaveLength(4);
    expect(steps[2]).not.toHaveAttribute('aria-current');
    expect(steps[3]).toHaveAttribute('aria-current', 'step');
  });

  it('NO_SHOW: bước "Không đến", nêu hệ quả hạn chế đặt online, không hành động, không gọi nhật ký', async () => {
    const { list } = stub({ status: 'NO_SHOW', kennelId: null, kennelCode: null, actualCheckInAt: null });
    renderAt('/boarding-bookings/4');
    await loaded();
    const notice = screen.getByRole('region', { name: 'Không đến nhận thú' });
    expect(notice).toHaveTextContent(/hạn chế đặt online/);
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
    expect(region('Nhật ký chăm sóc')).toHaveTextContent('Đặt chỗ này không có nhật ký chăm sóc');
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps.map((s) => s.textContent)).toEqual([expect.stringContaining('Đã đặt'), expect.stringContaining('Không đến')]);
    expect(list).not.toHaveBeenCalled();
  });

  it('kết thúc do thú mất: lời nhẹ nhàng, nhãn "Đã kết thúc", không đổ lỗi, không in tạm tính', async () => {
    stub({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED', actualCheckOutAt: '2026-10-22T09:00:00.000Z' }, [log()]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const notice = screen.getByRole('region', { name: 'Lưu trú đã kết thúc' });
    expect(notice).toHaveTextContent('Chúng tôi rất tiếc');
    expect(notice).toHaveTextContent('Bim');
    expect(notice).toHaveTextContent('chia buồn');
    expect(notice).not.toHaveTextContent(/lỗi|trách nhiệm|do bạn/i);
    expect(screen.getAllByText('Đã kết thúc').length).toBeGreaterThan(0);
    expect(screen.queryByText('Đã trả thú')).not.toBeInTheDocument();
    expect(region('Thông tin lưu trú')).not.toHaveTextContent('Tạm tính');
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
  });
});

describe('ranh giới giờ Việt Nam', () => {
  it.each([
    ['2026-10-23T16:59:00.000Z', '23:59 23/10/2026'],
    ['2026-10-23T17:01:00.000Z', '00:01 24/10/2026'],
  ])('mốc %s hiện đúng giờ VN %s dù múi giờ máy là gì', async (iso, shown) => {
    stub({ actualCheckInAt: iso }, [log({ createdAt: iso })]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const steps = within(screen.getByRole('list', { name: 'Tiến trình lưu trú' })).getAllByRole('listitem');
    expect(steps[1]).toHaveTextContent(shown);
    expect(await within(region('Nhật ký chăm sóc')).findByText(new RegExp(shown))).toBeInTheDocument();
  });

  it('Gia hạn: còn nút lúc 23:59 ngày trước ngày trả, hết nút lúc 00:01 ngày trả (BR-LT-06)', async () => {
    moveClock('2026-10-23T23:59:00+07:00');
    stub({ checkOutDate: '2026-10-24' });
    const first = renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByRole('button', { name: 'Gia hạn' })).toBeInTheDocument();
    first.unmount();

    moveClock('2026-10-24T00:01:00+07:00');
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
    expect(screen.getByText(/Đã tới ngày trả dự kiến, không gia hạn online được/)).toBeInTheDocument();
  });

  it('Hủy: còn nút lúc 23:59 ngày trước ngày nhận, hết nút lúc 00:01 ngày nhận (BR-LT-06)', async () => {
    await seed();
    moveClock('2026-10-25T23:59:00+07:00');
    const first = renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.getByRole('button', { name: 'Hủy đặt chỗ' })).toBeInTheDocument();
    first.unmount();

    moveClock('2026-10-26T00:01:00+07:00');
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
    expect(screen.getByText(/Đã tới ngày nhận, không hủy online được/)).toBeInTheDocument();
  });
});

describe('Gia hạn (UC58, #11)', () => {
  const openExtend = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByRole('button', { name: 'Gia hạn' }));
    return screen.findByRole('dialog', { name: 'Gia hạn lưu trú' });
  };
  const pick = (dialog: HTMLElement, date: string) => fireEvent.change(within(dialog).getByLabelText('Ngày trả mới'), { target: { value: date } });

  it('chọn ngày trả mới: hiện số đêm mới, thêm bao nhiêu đêm, tạm tính thêm; xác nhận thì toast và trang cập nhật', async () => {
    const extend = vi.spyOn(boardingApi, 'extend');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    expect(dialog).toHaveTextContent('28/10/2026');
    pick(dialog, '2026-10-29');
    const preview = within(dialog).getByRole('status', { name: 'Tóm tắt gia hạn' });
    expect(preview).toHaveTextContent('3 đêm');
    expect(preview).toHaveTextContent('Thêm 1 đêm');
    expect(preview).toHaveTextContent(/200\.000/);

    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(extend).toHaveBeenCalledWith(4, { checkOutDate: '2026-10-29' });
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('29/10/2026'));
    const info = region('Thông tin lưu trú');
    await waitFor(() => expect(info).toHaveTextContent('29/10/2026'));
    expect(within(info).getByText('3 đêm')).toBeInTheDocument();
    expect(info).toHaveTextContent(/600\.000/);
  });

  it('BR-LT-03 hết chỗ: hiện nguyên câu BE (không đuôi mã rule), hộp thoại còn mở, nút không kẹt', async () => {
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    pick(dialog, '2026-10-31');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Hết chỗ các đêm: 30/10');
    expect(dialog).not.toHaveTextContent('BR-LT-03');
    expect(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' })).toBeEnabled();
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('ngày không hợp lệ: bỏ trống hoặc không sau ngày trả hiện tại thì báo ngay, không gọi API', async () => {
    const extend = vi.spyOn(boardingApi, 'extend');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Vui lòng chọn ngày trả mới');
    pick(dialog, '2026-10-28');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Ngày trả mới phải sau ngày trả hiện tại');
    expect(extend).not.toHaveBeenCalled();
  });

  it('vượt 30 đêm: gợi ý tối đa 30 đêm, BE vẫn là nơi quyết định (hiện nguyên câu BR-LT-02)', async () => {
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    pick(dialog, '2026-11-30');
    expect(within(dialog).getByRole('status', { name: 'Tóm tắt gia hạn' })).toHaveTextContent('tối đa 30 đêm');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận');
  });

  it('ô ngày giới hạn từ hôm sau ngày trả hiện tại tới ngày nhận + 30 đêm', async () => {
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    const input = within(dialog).getByLabelText('Ngày trả mới');
    expect(input).toHaveAttribute('min', '2026-10-29');
    expect(input).toHaveAttribute('max', '2026-11-25');
  });

  it('409 (người khác vừa hủy): hiện nguyên câu BE, trang làm mới sang "Đã hủy", nút không kẹt', async () => {
    const get = vi.spyOn(boardingApi, 'get');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    pick(dialog, '2026-10-29');
    const desk = makeClient();
    await desk.post('/api/boarding-bookings/4/cancel', {}, await authAs(desk, ACCOUNTS.desk));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đặt chỗ đang quá hạn đón hoặc đã kết thúc, không gia hạn được');
    await refreshed(get); // trang đã thành "Đã hủy" nhưng hộp thoại còn mở để khách đọc câu lỗi
    expect(screen.getByRole('dialog', { name: 'Gia hạn lưu trú' })).toHaveTextContent('không gia hạn được');
    expect(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' })).toBeEnabled();
    await user.click(within(dialog).getByRole('button', { name: 'Đóng' }));
    expect(await screen.findByRole('region', { name: 'Thông tin hủy' })).toHaveTextContent('Lễ tân hủy');
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument();
  });

  it('400 BR-LT-06 (đã tới ngày trả): hiện câu BE và làm mới đặt chỗ', async () => {
    const { get } = stub({ status: 'CHECKED_IN' });
    vi.spyOn(boardingApi, 'extend').mockRejectedValue(
      new ApiError('BUSINESS_RULE_VIOLATION', 'Đã tới ngày trả dự kiến, không gia hạn được (BR-LT-06)', 400),
    );
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    pick(dialog, '2026-11-02');
    const before = get.mock.calls.length;
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận gia hạn' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đã tới ngày trả dự kiến, không gia hạn được');
    await waitFor(() => expect(get.mock.calls.length).toBeGreaterThan(before));
  });

  it('đóng bằng nút Đóng thì không gọi API', async () => {
    const extend = vi.spyOn(boardingApi, 'extend');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openExtend(user);
    await user.click(within(dialog).getByRole('button', { name: 'Đóng' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(extend).not.toHaveBeenCalled();
  });
});

describe('Hủy (UC58, #12)', () => {
  const openCancel = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByRole('button', { name: 'Hủy đặt chỗ' }));
    return screen.findByRole('dialog', { name: 'Hủy đặt chỗ lưu trú' });
  };

  it('hủy không muộn (23/10 → nhận 26/10): ghi chú không cảnh báo, gửi lý do, toast, trang sang "Đã hủy"', async () => {
    const cancel = vi.spyOn(boardingApi, 'cancel');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    expect(dialog).toHaveTextContent('chưa bị tính là hủy muộn');
    expect(dialog).toHaveTextContent('00:00 25/10/2026'); // mốc bắt đầu hủy muộn = 00:00 ngày nhận trừ 24 giờ, giờ VN
    await user.type(within(dialog).getByLabelText('Lý do hủy (không bắt buộc)'), 'Đổi kế hoạch');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(cancel).toHaveBeenCalledWith(4, { reason: 'Đổi kế hoạch' });
    expect(toast.success).toHaveBeenCalledWith('Đã hủy đặt chỗ lưu trú');
    const notice = await screen.findByRole('region', { name: 'Thông tin hủy' });
    expect(notice).toHaveTextContent('Khách hủy');
    expect(notice).toHaveTextContent('Đổi kế hoạch');
    expect(notice).not.toHaveTextContent('Hủy muộn');
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
  });

  it('không nhập lý do thì không gửi lý do', async () => {
    const cancel = vi.spyOn(boardingApi, 'cancel');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(cancel).toHaveBeenCalledTimes(1));
    expect(cancel.mock.calls[0][1]).toEqual({});
  });

  it('hủy muộn (25/10, nhận 26/10): cảnh báo rõ trước khi xác nhận, hệ quả hạn chế đặt online; sau khi hủy trang nêu "Hủy muộn"', async () => {
    await seed();
    moveClock('2026-10-25T09:00:00+07:00');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    const warning = within(dialog).getByRole('alert');
    expect(warning).toHaveTextContent('tính là hủy muộn');
    expect(warning).toHaveTextContent('hạn chế đặt online');
    expect(warning).toHaveTextContent('3 lần');
    expect(warning).toHaveTextContent('90 ngày');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(toast.success).toHaveBeenCalledWith('Đã hủy đặt chỗ lưu trú (ghi nhận hủy muộn)');
    expect(await screen.findByRole('region', { name: 'Thông tin hủy' })).toHaveTextContent('Hủy muộn');
  });

  it.each([
    ['2026-10-24T23:59:00+07:00', false],
    ['2026-10-25T00:01:00+07:00', true],
  ])('ranh giới hủy muộn tại %s: cảnh báo = %s', async (now, warned) => {
    await seed();
    moveClock(now);
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    if (warned) expect(within(dialog).getByRole('alert')).toHaveTextContent('tính là hủy muộn');
    else expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lý do quá 300 ký tự: báo tại chỗ, không gọi API', async () => {
    const cancel = vi.spyOn(boardingApi, 'cancel');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    fireEvent.change(within(dialog).getByLabelText('Lý do hủy (không bắt buộc)'), { target: { value: 'a'.repeat(301) } });
    expect(dialog).toHaveTextContent('301/300');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Lý do tối đa 300 ký tự');
    expect(cancel).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByLabelText('Lý do hủy (không bắt buộc)'), { target: { value: 'a'.repeat(300) } });
    expect(dialog).toHaveTextContent('300/300');
  });

  it('400 "Đã tới ngày nhận, không hủy được" (qua nửa đêm khi hộp thoại đang mở): hiện nguyên câu BE, làm mới, nút Hủy biến mất', async () => {
    await seed();
    const get = vi.spyOn(boardingApi, 'get');
    moveClock('2026-10-25T23:59:00+07:00');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    moveClock('2026-10-26T00:01:00+07:00');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    // Lúc này đã là ngày nhận nên khung cảnh báo hủy muộn cũng là role=alert: tìm đúng câu lỗi của BE.
    expect(await within(dialog).findByText('Đã tới ngày nhận, không hủy được')).toHaveAttribute('role', 'alert');
    // Đợi lần làm mới xong: hộp thoại vẫn mở để khách đọc câu lỗi dù nút Hủy của trang đã biến mất.
    await refreshed(get);
    expect(screen.getByRole('dialog', { name: 'Hủy đặt chỗ lưu trú' })).toHaveTextContent('Đã tới ngày nhận, không hủy được');
    expect(within(dialog).getByRole('button', { name: 'Xác nhận hủy' })).toBeEnabled();
    await user.click(within(dialog).getByRole('button', { name: 'Giữ đặt chỗ' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Hủy đặt chỗ' })).not.toBeInTheDocument();
    expect(screen.getByText(/Đã tới ngày nhận, không hủy online được/)).toBeInTheDocument();
  });

  it('409 (lễ tân vừa hủy): hiện nguyên câu BE, trang làm mới sang "Đã hủy"', async () => {
    const get = vi.spyOn(boardingApi, 'get');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    const desk = makeClient();
    await desk.post('/api/boarding-bookings/4/cancel', { reason: 'Khách gọi tới quầy' }, await authAs(desk, ACCOUNTS.desk));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái đặt chỗ lưu trú từ CANCELLED sang CANCELLED');
    await refreshed(get);
    expect(screen.getByRole('dialog', { name: 'Hủy đặt chỗ lưu trú' })).toHaveTextContent('Không thể chuyển trạng thái');
    expect(within(dialog).getByRole('button', { name: 'Xác nhận hủy' })).toBeEnabled();
    await user.click(within(dialog).getByRole('button', { name: 'Giữ đặt chỗ' }));
    const notice = await screen.findByRole('region', { name: 'Thông tin hủy' });
    expect(notice).toHaveTextContent('Lễ tân hủy');
    expect(notice).toHaveTextContent('Khách gọi tới quầy');
  });

  it('"Giữ đặt chỗ" đóng hộp thoại, không gọi API', async () => {
    const cancel = vi.spyOn(boardingApi, 'cancel');
    const { user } = renderAt('/boarding-bookings/4');
    const dialog = await openCancel(user);
    await user.click(within(dialog).getByRole('button', { name: 'Giữ đặt chỗ' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(cancel).not.toHaveBeenCalled();
  });
});

describe('nhật ký chăm sóc (UC60)', () => {
  it.each<[BookingStatus, boolean]>([
    ['BOOKED', false],
    ['CANCELLED', false],
    ['NO_SHOW', false],
    ['CHECKED_IN', true],
    ['OVERDUE', true],
    ['CHECKED_OUT', true],
  ])('trạng thái %s: gọi API nhật ký = %s', async (status, called) => {
    const { list } = stub({ status });
    renderAt('/boarding-bookings/4');
    await loaded();
    await waitFor(() => expect(region('Nhật ký chăm sóc')).toBeInTheDocument());
    // Cho các truy vấn đang chờ (nếu có) kịp chạy trước khi khẳng định "không gọi".
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(list.mock.calls.length).toBe(called ? 1 : 0);
  });

  it('nhóm theo ngày logDate mới → cũ dù API trả lộn xộn; mỗi ngày một tiêu đề', async () => {
    stub({}, [
      log({ careLogId: 1, logDate: '2026-10-21', eating: 'Ăn hết' }),
      log({ careLogId: 3, logDate: '2026-10-23', eating: 'Ăn sáng ngon' }),
      log({ careLogId: 2, logDate: '2026-10-22', eating: 'Ăn trưa' }),
      log({ careLogId: 4, logDate: '2026-10-22', drinking: 'Uống đủ' }),
    ]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const logs = region('Nhật ký chăm sóc');
    const days = await within(logs).findAllByRole('heading', { level: 3 });
    expect(days.map((d) => d.textContent)).toEqual([
      expect.stringContaining('23/10/2026'),
      expect.stringContaining('22/10/2026'),
      expect.stringContaining('21/10/2026'),
    ]);
    expect(within(logs).getAllByRole('article')).toHaveLength(4);
  });

  it('chỉ hiện ô có dữ liệu, đủ 4 nhãn tiếng Việt khi có đủ', async () => {
    stub({}, [
      log({ careLogId: 1, eating: 'Ăn hết 2 bữa' }),
      log({ careLogId: 2, logDate: '2026-10-21', eating: 'E', drinking: 'U', hygiene: 'V', activity: 'H', note: 'Ghi chú thêm' }),
    ]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const [only, full] = await within(region('Nhật ký chăm sóc')).findAllByRole('article');
    expect(only).toHaveTextContent('Ăn hết 2 bữa');
    for (const label of ['Uống', 'Vệ sinh', 'Hoạt động', 'Ghi chú']) expect(within(only).queryByText(label)).not.toBeInTheDocument();
    for (const label of ['Ăn', 'Uống', 'Vệ sinh', 'Hoạt động', 'Ghi chú']) expect(within(full).getByText(label)).toBeInTheDocument();
  });

  it('người ghi và giờ ghi theo giờ VN', async () => {
    stub({}, [log({ createdAt: '2026-10-22T10:30:00.000Z', recordedByName: 'Thu Hà', eating: 'Ăn' })]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const [entry] = await within(region('Nhật ký chăm sóc')).findAllByRole('article');
    expect(entry).toHaveTextContent('Thu Hà');
    expect(entry).toHaveTextContent('17:30 22/10/2026');
  });

  it('bất thường nổi bật bằng chữ + biểu tượng; mục thường không có', async () => {
    stub({}, [
      log({ careLogId: 1, isAbnormal: true, note: 'Bỏ ăn bữa tối, nôn một lần' }),
      log({ careLogId: 2, logDate: '2026-10-21', eating: 'Ăn tốt' }),
    ]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const [abnormal, normal] = await within(region('Nhật ký chăm sóc')).findAllByRole('article');
    const label = within(abnormal).getByText('Bất thường');
    expect(label.closest('[data-abnormal]')).not.toBeNull();
    expect(label.parentElement?.querySelector('svg')).not.toBeNull();
    expect(abnormal).toHaveTextContent('Bỏ ăn bữa tối, nôn một lần');
    expect(within(normal).queryByText('Bất thường')).not.toBeInTheDocument();
  });

  it('bản bổ sung hiện như ghi thêm sau (nội dung + giờ VN), không phải bản sửa', async () => {
    stub({}, [
      log({
        isAbnormal: true,
        note: 'Nôn một lần',
        addenda: [{ addendumId: 1, content: 'Đã khám nhanh, cho uống men tiêu hóa', createdBy: 5, createdAt: '2026-10-22T13:10:00.000Z' }],
      }),
    ]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const [entry] = await within(region('Nhật ký chăm sóc')).findAllByRole('article');
    const addenda = within(entry).getByRole('group', { name: 'Bổ sung sau' });
    expect(addenda).toHaveTextContent('Đã khám nhanh, cho uống men tiêu hóa');
    expect(addenda).toHaveTextContent('20:10 22/10/2026');
    expect(entry).toHaveTextContent('Nôn một lần');
    expect(entry).toHaveTextContent('giữ nguyên');
  });

  it('ảnh: lazy, alt mô tả, không gửi referrer; lỗi tải thì thay bằng khung; bấm xem lớn, Esc đóng', async () => {
    stub({}, [log({ logDate: '2026-10-22', photoUrls: ['https://cdn.example.com/a.jpg', 'https://cdn.example.com/b.jpg'] })]);
    const { user } = renderAt('/boarding-bookings/4');
    await loaded();
    const entry = (await within(region('Nhật ký chăm sóc')).findAllByRole('article'))[0];
    const photos = within(entry).getAllByRole('img');
    expect(photos).toHaveLength(2);
    expect(photos[0]).toHaveAttribute('loading', 'lazy');
    expect(photos[0]).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(photos[0]).toHaveAttribute('alt', expect.stringContaining('22/10/2026'));
    expect(photos[0]).toHaveAttribute('alt', expect.stringContaining('Bim'));
    expect(photos[0].getAttribute('alt')).not.toBe(photos[1].getAttribute('alt'));

    fireEvent.error(photos[1]);
    expect(within(entry).getByText('Không tải được ảnh')).toBeInTheDocument();
    expect(within(entry).getAllByRole('img')).toHaveLength(1);

    await user.click(within(entry).getByRole('button', { name: /Xem ảnh lớn/ }));
    const viewer = await screen.findByRole('dialog', { name: /Ảnh nhật ký ngày 22\/10\/2026/ });
    const big = within(viewer).getByRole('img');
    expect(big).toHaveAttribute('src', 'https://cdn.example.com/a.jpg');
    expect(big).toHaveAttribute('referrerpolicy', 'no-referrer');
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    // Cảm ứng không có Esc: nút "Đóng" đủ lớn cũng đóng được.
    await user.click(within(entry).getByRole('button', { name: /Xem ảnh lớn/ }));
    await user.click(within(await screen.findByRole('dialog', { name: /Ảnh nhật ký ngày/ })).getByRole('button', { name: 'Đóng' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('đang lưu trú, chưa có mục nào: câu "Hôm nay thú cưng chưa có nhật ký"', async () => {
    stub({}, []);
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(await within(region('Nhật ký chăm sóc')).findByText(/Hôm nay thú cưng chưa có nhật ký/)).toBeInTheDocument();
  });

  it('đã trả thú mà không có nhật ký: nói lưu trú này không có, không dùng chữ "Hôm nay"', async () => {
    stub({ status: 'CHECKED_OUT', endReason: 'RETURNED', actualCheckOutAt: '2026-10-22T08:00:00.000Z' }, []);
    renderAt('/boarding-bookings/4');
    await loaded();
    const logs = region('Nhật ký chăm sóc');
    expect(await within(logs).findByText(/Lưu trú này không có nhật ký chăm sóc nào/)).toBeInTheDocument();
    expect(logs).not.toHaveTextContent('Hôm nay');
  });

  it('khách không thấy cờ thiếu nhật ký hôm nay của nhân viên: có nhật ký ngày khác thì không cảnh báo "hôm nay"', async () => {
    stub({ missingCareLogToday: true }, [log({ logDate: '2026-10-22', eating: 'Ăn tốt' })]);
    renderAt('/boarding-bookings/4');
    await loaded();
    await within(region('Nhật ký chăm sóc')).findAllByRole('article');
    expect(document.body).not.toHaveTextContent('Hôm nay thú cưng chưa có nhật ký');
  });

  it('đang tải nhật ký: khung chờ; lỗi: câu lỗi + Thử lại, bấm lại thì có nhật ký', async () => {
    const { list } = stub({}, []);
    list.mockRejectedValueOnce(NETWORK_DOWN()).mockResolvedValue([log({ eating: 'Ăn hết' })]);
    const { user } = renderAt('/boarding-bookings/4');
    await loaded();
    const logs = region('Nhật ký chăm sóc');
    expect(await within(logs).findByRole('alert')).toHaveTextContent('Không kết nối được máy chủ');
    await user.click(within(logs).getByRole('button', { name: 'Thử lại' }));
    expect(await within(logs).findByText('Ăn hết')).toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('khung chờ nhật ký khi đang tải', async () => {
    const { list } = stub({}, []);
    list.mockReturnValue(new Promise(() => {}));
    renderAt('/boarding-bookings/4');
    await loaded();
    expect(await within(region('Nhật ký chăm sóc')).findByRole('status')).toHaveTextContent('Đang tải nhật ký chăm sóc');
  });

  it('khách chỉ xem: không có nút ghi, sửa hay thêm bổ sung', async () => {
    stub({}, [log({ eating: 'Ăn', addenda: [{ addendumId: 1, content: 'Bổ sung', createdBy: 5, createdAt: '2026-10-22T13:10:00.000Z' }] })]);
    renderAt('/boarding-bookings/4');
    await loaded();
    const logs = region('Nhật ký chăm sóc');
    await within(logs).findAllByRole('article');
    expect(within(logs).queryByRole('button', { name: /ghi|sửa|thêm|xóa/i })).not.toBeInTheDocument();
    expect(within(logs).queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('"Làm mới": chỉ khi đang lưu trú; bấm thì tải lại nhật ký và đặt chỗ', async () => {
    const { get, list } = stub({}, [log({ eating: 'Ăn' })]);
    const { user } = renderAt('/boarding-bookings/4');
    await loaded();
    await within(region('Nhật ký chăm sóc')).findAllByRole('article');
    await user.click(screen.getByRole('button', { name: 'Làm mới' }));
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
    expect(get).toHaveBeenCalledTimes(2);
  });
});

describe('tự làm mới nhật ký mỗi 60 giây', () => {
  // Chỉ giả Date + setInterval: setTimeout vẫn thật để mock API và react-query chạy.
  beforeEach(() => {
    vi.useRealTimers();
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(FRIDAY_9));
  });
  const tick = (ms: number) =>
    act(async () => {
      vi.advanceTimersByTime(ms);
    });

  it('đang lưu trú: gọi lại sau 60 giây, và dừng hẳn khi rời trang (không timer mồ côi)', async () => {
    const { list } = stub({}, [log({ eating: 'Ăn' })]);
    const view = renderAt('/boarding-bookings/4');
    await loaded();
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await tick(59_000);
    expect(list).toHaveBeenCalledTimes(1);
    await tick(1_000);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
    await tick(60_000);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(3));
    view.unmount();
    await tick(300_000);
    expect(list).toHaveBeenCalledTimes(3);
  });

  it('OVERDUE cũng tự làm mới', async () => {
    const { list } = stub({ status: 'OVERDUE', checkOutDate: '2026-10-22', overdueSince: '2026-10-22T05:00:00.000Z' }, []);
    renderAt('/boarding-bookings/4');
    await loaded();
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await tick(60_000);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
  });

  it('đã trả thú: không tự làm mới', async () => {
    const { list } = stub({ status: 'CHECKED_OUT', endReason: 'RETURNED', actualCheckOutAt: '2026-10-22T08:00:00.000Z' }, [log()]);
    renderAt('/boarding-bookings/4');
    await loaded();
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await tick(600_000);
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('trang đang ẩn (tab nền): không gọi', async () => {
    const { list } = stub({}, [log({ eating: 'Ăn' })]);
    renderAt('/boarding-bookings/4');
    await loaded();
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    const hidden = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    await tick(180_000);
    expect(list).toHaveBeenCalledTimes(1);
    hidden.mockRestore();
    await tick(60_000);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
  });
});
