import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { boardingApi } from '../../shared/api/boarding.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { Booking } from '../../shared/types/boarding';
import { nightDates } from '../../shared/utils/boarding';
import { ACCOUNTS, authAs, makeClient, pinClock } from '../../test/boarding-harness';
import { AdminBoardingPage } from './AdminBoardingPage';

// Dialog của WS STAY / CARELOG chỉ được MOUNT ở đây: thay bằng bản giả để test không phụ thuộc phần thân họ viết (hợp đồng: chữ ký + tiêu đề).
vi.mock('./boarding/stay/CheckInDialog', () => ({
  CheckInDialog: ({ booking, onClose }: { booking: Booking; onClose: () => void }) => (
    <div role="dialog" aria-label="Nhận thú">
      <h2>Nhận thú</h2>
      <p>mã {booking.code}</p>
      <button onClick={onClose}>Đóng dialog giả</button>
    </div>
  ),
}));
vi.mock('./boarding/stay/NoKennelDialog', () => ({
  NoKennelDialog: ({ booking, onClose }: { booking: Booking; onClose: () => void }) => (
    <div role="dialog" aria-label="Hủy vì hết chuồng">
      <h2>Hủy vì hết chuồng</h2>
      <p>mã {booking.code}</p>
      <button onClick={onClose}>Đóng dialog giả</button>
    </div>
  ),
}));
vi.mock('./boarding/stay/CheckoutDialog', () => ({
  CheckoutDialog: ({ booking, onClose }: { booking: Booking; onClose: () => void }) => (
    <div role="dialog" aria-label="Trả thú">
      <h2>Trả thú</h2>
      <p>mã {booking.code}</p>
      <button onClick={onClose}>Đóng dialog giả</button>
    </div>
  ),
}));
vi.mock('./boarding/stay/DeceasedDialog', () => ({
  DeceasedDialog: ({ booking, onClose }: { booking: Booking; onClose: () => void }) => (
    <div role="dialog" aria-label="Thú mất">
      <h2>Thú mất</h2>
      <p>mã {booking.code}</p>
      <button onClick={onClose}>Đóng dialog giả</button>
    </div>
  ),
}));
vi.mock('./boarding/care-logs/CareLogDialog', () => ({
  CareLogDialog: ({ booking, canWrite, onClose }: { booking: Booking; canWrite: boolean; onClose: () => void }) => (
    <div role="dialog" aria-label="Nhật ký chăm sóc">
      <h2>Nhật ký chăm sóc</h2>
      <p>mã {booking.code}</p>
      <p>{canWrite ? 'quyền ghi' : 'chỉ xem'}</p>
      <button onClick={onClose}>Đóng dialog giả</button>
    </div>
  ),
}));

// Giờ ghim thứ Sáu 23/10/2026 09:00 (giờ VN), chi nhánh 1 (xem boarding-harness.ts). Dữ liệu mẫu tính theo ngày nạp lần đầu:
//   Sắp nhận: Bơ 30/10→01/11 (quầy), Bim 26/10→28/10 · Đang lưu trú: Cốm (chưa có nhật ký hôm nay), Mực (đã có)
//   Lịch sử: Đã trả thú Bim / Nâu / Gạo · Đã hủy Bim / Tép / Đậu (hủy muộn) · Không đến Khoai.

interface Call {
  method: string;
  url: string;
  params?: unknown;
  status: number;
}
let calls: Call[] = [];
let recorder = -1;

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  pinClock();
  useSession.getState().clear();
  calls = [];
  const note = (config: { method?: string; url?: string; params?: unknown } | undefined, status: number) =>
    calls.push({ method: (config?.method ?? 'get').toUpperCase(), url: config?.url ?? '', params: config?.params, status });
  recorder = apiClient.interceptors.response.use(
    (res) => {
      note(res.config, res.status);
      return res;
    },
    (error) => {
      note(error.config, error.response?.status ?? 0);
      return Promise.reject(error);
    },
  );
});
afterEach(() => {
  apiClient.interceptors.response.eject(recorder);
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function loginAs(email: string) {
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  calls = [];
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/boarding']}>
        <AdminBoardingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

async function openAs(email: string) {
  await loginAs(email);
  return renderPage();
}

const rowOf = async (petName: string) => (await screen.findByText(petName, { selector: 'p, span, strong' })).closest('tr')!;
const buttonNames = (row: HTMLElement) => within(row).queryAllByRole('button').map((b) => b.textContent?.trim() ?? '');
const listSpy = () => vi.spyOn(boardingApi, 'list');

function fakeBooking(id: number, over: Partial<Booking> = {}): Booking {
  return {
    bookingId: id,
    code: `LT-2610-9${String(id).padStart(4, '0')}`,
    customerId: 1,
    customerName: 'Nguyễn Thu Trang',
    petId: 2,
    petName: `Thú ${id}`,
    branchId: 1,
    kennelTypeId: 5,
    kennelTypeName: 'Chuồng chó nhỏ',
    kennelId: null,
    kennelCode: null,
    checkInDate: '2026-11-10',
    checkOutDate: '2026-11-12',
    nightlyPrice: 200_000,
    status: 'BOOKED',
    channel: 'COUNTER',
    lateCancel: false,
    missingCareLogToday: false,
    ...over,
  };
}

describe('AdminBoardingPage — tab, lọc, phân trang (UC58, UC59)', () => {
  it('Sắp nhận mặc định: gửi status=BOOKED, trang 0, size 20; chỉ hiện các đặt chỗ BOOKED', async () => {
    const spy = listSpy();
    await openAs(ACCOUNTS.desk);
    expect(screen.getByRole('heading', { level: 1, name: 'Lưu trú' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Sắp nhận' })).toHaveAttribute('aria-selected', 'true');
    const bo = await rowOf('Bơ');
    expect(spy).toHaveBeenCalledWith({ status: 'BOOKED', page: 0, size: 20 });
    expect(bo).toHaveTextContent('Phạm Gia Bảo');
    expect(bo).toHaveTextContent('Chuồng chó nhỏ');
    expect(bo).toHaveTextContent('30/10/2026');
    expect(bo).toHaveTextContent('01/11/2026');
    expect(bo).toHaveTextContent('2 đêm');
    expect(bo).toHaveTextContent('Đã đặt');
    expect(bo).toHaveTextContent('Đặt tại quầy');
    expect(bo).toHaveTextContent('Chưa gán chuồng');
    expect(screen.queryByText('Cốm')).not.toBeInTheDocument();
    expect(screen.queryByText('Nâu')).not.toBeInTheDocument();
  });

  it('Đang lưu trú: inStay=true gồm chuồng, chip "Chưa có nhật ký hôm nay"; bật nút lọc gửi missingCareLogToday=true', async () => {
    const spy = listSpy();
    const user = await openAs(ACCOUNTS.desk);
    await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    const com = await rowOf('Cốm');
    expect(spy).toHaveBeenLastCalledWith({ inStay: true, page: 0, size: 20 });
    expect(com).toHaveTextContent('C-01');
    expect(com).toHaveTextContent('Đang lưu trú');
    expect(com).toHaveTextContent('Chưa có nhật ký hôm nay');
    const muc = await rowOf('Mực');
    expect(muc).toHaveTextContent('C-02');
    expect(muc).not.toHaveTextContent('Chưa có nhật ký hôm nay');
    expect(screen.queryByText('Bơ')).not.toBeInTheDocument();

    const toggle = screen.getByRole('button', { name: 'Chưa có nhật ký hôm nay', pressed: false });
    await user.click(toggle);
    await waitFor(() => expect(screen.queryByText('Mực')).not.toBeInTheDocument());
    expect(spy).toHaveBeenLastCalledWith({ inStay: true, missingCareLogToday: true, page: 0, size: 20 });
    expect(screen.getByText('Cốm')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chưa có nhật ký hôm nay', pressed: true })).toBeInTheDocument();
  });

  it('nhãn trạng thái mang tông của shared/utils/boarding (kèm chữ, không chỉ màu)', async () => {
    const user = await openAs(ACCOUNTS.desk);
    expect(within(await rowOf('Bơ')).getByText('Đã đặt')).toHaveClass('bg-blue-100');
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    expect(within(await rowOf('Cốm')).getByText('Đang lưu trú')).toHaveClass('bg-amber-100');
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    expect(within(await rowOf('Gạo')).getByText('Đã trả thú')).toHaveClass('bg-green-100');
  });

  it('nút lọc nhật ký chỉ có ở tab Đang lưu trú', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await rowOf('Bơ');
    expect(screen.queryByRole('button', { name: 'Chưa có nhật ký hôm nay' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    await rowOf('Gạo');
    expect(screen.queryByRole('button', { name: 'Chưa có nhật ký hôm nay' })).not.toBeInTheDocument();
  });

  it('Lịch sử: mặc định Đã trả thú; chọn Đã hủy / Không đến gửi đúng một status (hủy muộn có chip)', async () => {
    const spy = listSpy();
    const user = await openAs(ACCOUNTS.desk);
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    await rowOf('Gạo');
    expect(spy).toHaveBeenLastCalledWith({ status: 'CHECKED_OUT', page: 0, size: 20 });
    const options = within(screen.getByRole('combobox', { name: 'Trạng thái lịch sử' })).getAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['Đã trả thú', 'Đã hủy', 'Không đến']);
    expect(screen.queryByText('Đậu')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Trạng thái lịch sử' }), 'Đã hủy');
    const dau = await rowOf('Đậu');
    expect(spy).toHaveBeenLastCalledWith({ status: 'CANCELLED', page: 0, size: 20 });
    expect(dau).toHaveTextContent('Hủy muộn');
    expect(dau).toHaveTextContent('Đã hủy');
    expect(await rowOf('Tép')).not.toHaveTextContent('Hủy muộn');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Trạng thái lịch sử' }), 'Không đến');
    expect(await rowOf('Khoai')).toHaveTextContent('Không đến');
    expect(spy).toHaveBeenLastCalledWith({ status: 'NO_SHOW', page: 0, size: 20 });
  });

  it('lọc Từ ngày / Đến ngày gửi from, to (giao với [ngày nhận, ngày trả])', async () => {
    const spy = listSpy();
    await openAs(ACCOUNTS.desk);
    await rowOf('Bơ');
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-29' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-10-31' } });
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', from: '2026-10-29', to: '2026-10-31', page: 0, size: 20 }));
    await waitFor(() => expect(screen.queryByText('Bim')).not.toBeInTheDocument());
    expect(screen.getByText('Bơ')).toBeInTheDocument();
  });

  it('phân trang: Sau gửi page=1; đổi tab hoặc đổi bộ lọc quay về trang 0', async () => {
    const spy = listSpy().mockImplementation(async (q) => ({
      content: [fakeBooking(1 + (q?.page ?? 0))],
      page: q?.page ?? 0,
      size: 20,
      totalElements: 45,
      totalPages: 3,
    }));
    const user = await openAs(ACCOUNTS.desk);
    expect(await screen.findByText(/Trang 1\/3/)).toHaveTextContent('45 đặt chỗ');
    expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Sau' }));
    await screen.findByText(/Trang 2\/3/);
    expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', page: 1, size: 20 });
    await user.click(screen.getByRole('button', { name: 'Sau' }));
    await screen.findByText(/Trang 3\/3/);
    expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();
    expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', page: 2, size: 20 });

    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-12-31' } });
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', to: '2026-12-31', page: 0, size: 20 }));

    await user.click(screen.getByRole('button', { name: 'Sau' }));
    await screen.findByText(/Trang 2\/3/);
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith({ inStay: true, to: '2026-12-31', page: 0, size: 20 }));
  });
});

describe('AdminBoardingPage — 4 trạng thái', () => {
  it('đang tải: hiện thông báo tải, chưa có bảng', async () => {
    await loginAs(ACCOUNTS.desk);
    vi.spyOn(boardingApi, 'list').mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(await screen.findByText('Đang tải danh sách…')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('lỗi: hiện nguyên câu của BE (from > to) kèm nút Thử lại; sửa bộ lọc thì có dữ liệu lại', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await rowOf('Bơ');
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-12-01' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-11-01' } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-01' } });
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    await user.click(await screen.findByRole('button', { name: 'Xóa lọc ngày' }));
    expect(await rowOf('Bơ')).toBeInTheDocument();
  });

  it('Thử lại gọi lại API khi lỗi tạm thời', async () => {
    await loginAs(ACCOUNTS.desk);
    const real = boardingApi.list.bind(boardingApi);
    vi.spyOn(boardingApi, 'list').mockImplementationOnce(() =>
      apiClient.get('/api/boarding-bookings', { params: { from: '2026-12-01', to: '2026-11-01' } }).then((r) => r.data.data),
    );
    const user = renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc');
    vi.spyOn(boardingApi, 'list').mockImplementation(real);
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await rowOf('Bơ')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('rỗng: mỗi tab nói rõ chưa có đặt chỗ nào', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await rowOf('Bơ');
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2030-01-01' } });
    expect(await screen.findByText('Không có đặt chỗ sắp nhận nào.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    expect(await screen.findByText('Không có thú nào đang lưu trú.')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    expect(await screen.findByText('Không có đặt chỗ nào trong lịch sử.')).toBeInTheDocument();
  });
});

describe('AdminBoardingPage — nút theo vai trò × trạng thái (hợp đồng §B)', () => {
  it('RECEPTIONIST, BOOKED: Nhận thú, Hủy vì hết chuồng, Gia hạn, Hủy đặt chỗ', async () => {
    await openAs(ACCOUNTS.desk);
    const bo = await rowOf('Bơ');
    expect(buttonNames(bo)).toEqual(['Nhận thú', 'Hủy vì hết chuồng', 'Gia hạn', 'Hủy đặt chỗ', '']);
    expect(within(bo).getByRole('button', { name: 'Chi tiết ' + within(bo).getByText(/^LT-/).textContent })).toBeInTheDocument();
  });

  it('RECEPTIONIST, đang lưu trú: Nhật ký, Trả thú, Thú mất, Gia hạn', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    expect(buttonNames(await rowOf('Cốm'))).toEqual(['Nhật ký', 'Trả thú', 'Thú mất', 'Gia hạn', '']);
  });

  it('CARETAKER: chỉ Nhận thú ở Sắp nhận; Nhật ký ở Đang lưu trú; không thao tác tiền / hủy', async () => {
    const user = await openAs(ACCOUNTS.caretaker);
    expect(buttonNames(await rowOf('Bơ'))).toEqual(['Nhận thú', '']);
    expect(screen.queryByRole('button', { name: /Đặt tại quầy/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    expect(buttonNames(await rowOf('Cốm'))).toEqual(['Nhật ký', '']);
  });

  it('VET: không có nút ở Sắp nhận (chỉ xem chi tiết); Nhật ký ở Đang lưu trú', async () => {
    const user = await openAs(ACCOUNTS.vet);
    expect(buttonNames(await rowOf('Bơ'))).toEqual(['']);
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    expect(buttonNames(await rowOf('Cốm'))).toEqual(['Nhật ký', '']);
  });

  it('BRANCH_MANAGER chỉ xem: không nút ghi nào, kể cả Đặt tại quầy; vẫn mở Nhật ký để đọc', async () => {
    const user = await openAs(ACCOUNTS.manager);
    expect(buttonNames(await rowOf('Bơ'))).toEqual(['']);
    expect(screen.queryByRole('button', { name: /Đặt tại quầy/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    expect(buttonNames(await rowOf('Cốm'))).toEqual(['Nhật ký', '']);
  });

  it('Lịch sử chỉ có Chi tiết cho mọi vai trò', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    expect(buttonNames(await rowOf('Gạo'))).toEqual(['']);
  });

  it('OVERDUE: có chip Quá hạn từ …, lễ tân không còn Gia hạn', async () => {
    await loginAs(ACCOUNTS.desk);
    // Nạp state ở 23/10, rồi sang 03/11 13:00: ST15 chuyển Cốm (ngày trả 31/10) và Mực (01/11) sang OVERDUE.
    await apiClient.get('/api/boarding-bookings');
    vi.setSystemTime(new Date('2026-11-03T13:00:00+07:00'));
    await useSession.getState().login({ email: ACCOUNTS.desk, password: MOCK_PASSWORD });
    const user = renderPage();
    await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    const com = await rowOf('Cốm');
    expect(com).toHaveTextContent('Quá hạn đón');
    expect(com).toHaveTextContent('Quá hạn từ 12:00 31/10/2026');
    expect(buttonNames(com)).toEqual(['Nhật ký', 'Trả thú', 'Thú mất', '']);
  });
});

describe('AdminBoardingPage — mount dialog của WS khác (chữ ký cố định)', () => {
  const openDialog = async (user: ReturnType<typeof userEvent.setup>, row: HTMLElement, button: RegExp, title: string) => {
    await user.click(within(row).getByRole('button', { name: button }));
    const dialog = await screen.findByRole('dialog', { name: title });
    expect(within(dialog).getByRole('heading', { name: title })).toBeInTheDocument();
    return dialog;
  };

  it('Nhận thú (lễ tân và CARETAKER) mount CheckInDialog với đúng đặt chỗ; Đóng gỡ dialog', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const bo = await rowOf('Bơ');
    const code = within(bo).getByText(/^LT-/).textContent!;
    const dialog = await openDialog(user, bo, /^Nhận thú/, 'Nhận thú');
    expect(dialog).toHaveTextContent(`mã ${code}`);
    await user.click(within(dialog).getByRole('button', { name: 'Đóng dialog giả' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('CARETAKER cũng mở được Nhận thú', async () => {
    const user = await openAs(ACCOUNTS.caretaker);
    await openDialog(user, await rowOf('Bơ'), /^Nhận thú/, 'Nhận thú');
  });

  it('Hủy vì hết chuồng mount NoKennelDialog', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await openDialog(user, await rowOf('Bơ'), /^Hủy vì hết chuồng/, 'Hủy vì hết chuồng');
  });

  it('Trả thú và Thú mất mount CheckoutDialog / DeceasedDialog', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    const dialog = await openDialog(user, await rowOf('Cốm'), /^Trả thú/, 'Trả thú');
    await user.click(within(dialog).getByRole('button', { name: 'Đóng dialog giả' }));
    await openDialog(user, await rowOf('Cốm'), /^Thú mất/, 'Thú mất');
  });

  it.each([
    [ACCOUNTS.desk, 'chỉ xem'],
    [ACCOUNTS.manager, 'chỉ xem'],
    [ACCOUNTS.caretaker, 'quyền ghi'],
    [ACCOUNTS.vet, 'quyền ghi'],
  ])('%s mở Nhật ký chăm sóc với canWrite đúng (%s)', async (email, access) => {
    const user = await openAs(email);
    await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    const dialog = await openDialog(user, await rowOf('Cốm'), /^Nhật ký/, 'Nhật ký chăm sóc');
    expect(dialog).toHaveTextContent(access);
  });
});

describe('AdminBoardingPage — mỗi vai trò vào được, tải trang không phát sinh 403', () => {
  it.each([
    ['RECEPTIONIST', ACCOUNTS.desk],
    ['BRANCH_MANAGER', ACCOUNTS.manager],
    ['CARETAKER', ACCOUNTS.caretaker],
    ['VET', ACCOUNTS.vet],
  ])('%s: duyệt cả 3 tab, không 4xx, không gọi /kennels, check-in-readiness, start-checkout', async (_role, email) => {
    const user = await openAs(email);
    await rowOf('Bơ');
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    await rowOf('Cốm');
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    await rowOf('Gạo');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(calls.length).toBeGreaterThan(0);
    expect(calls.filter((c) => c.status >= 400)).toEqual([]);
    expect(calls.filter((c) => /kennels|check-in-readiness|start-checkout/.test(c.url))).toEqual([]);
    expect(calls.every((c) => c.method === 'GET' && c.url.startsWith('/api/boarding-bookings'))).toBe(true);
  });
});

// Người thứ hai thao tác cùng dữ liệu (cùng localStorage của mock): dùng để tạo xung đột 409 hoặc đặt chỗ ngày mai.
async function asOther(email: string) {
  const client = makeClient();
  const auth = await authAs(client, email);
  return {
    book: async (body: object) => (await client.post('/api/boarding-bookings', body, auth)).data.data as { booking: Booking },
    cancel: async (bookingId: number) => client.post(`/api/boarding-bookings/${bookingId}/cancel`, {}, auth),
  };
}

const rowOfCode = async (code: string) => (await screen.findByText(code)).closest('tr')!;

describe('AdminBoardingPage — chi tiết (xem mọi trường)', () => {
  it('thú mất: kết thúc do thú mất, giá đêm, giờ nhận / trả thực tế', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    const nau = await rowOf('Nâu');
    await user.click(within(nau).getByRole('button', { name: /^Chi tiết LT-/ }));
    const dialog = await screen.findByRole('dialog', { name: /^Chi tiết lưu trú LT-/ });
    const field = (label: string) => within(dialog).getByText(label, { exact: true }).closest('div')!;
    expect(field('Kết thúc')).toHaveTextContent('Thú đã mất');
    expect(field('Giá đêm')).toHaveTextContent('200.000 ₫');
    expect(field('Thú cưng')).toHaveTextContent('Nâu');
    expect(field('Khách')).toHaveTextContent('Vũ Thanh Hà');
    expect(field('Chuồng')).toHaveTextContent('C-03');
    expect(field('Nhận thực tế')).toHaveTextContent('08:45');
    expect(field('Hủy muộn')).toHaveTextContent('Không');
    expect(field('Nguồn hủy')).toHaveTextContent('—');
  });

  it('đã hủy: nguồn hủy, lý do hủy, hủy muộn', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Trạng thái lịch sử' }), 'Đã hủy');
    await user.click(within(await rowOf('Đậu')).getByRole('button', { name: /^Chi tiết LT-/ }));
    const dialog = await screen.findByRole('dialog', { name: /^Chi tiết lưu trú LT-/ });
    const field = (label: string) => within(dialog).getByText(label, { exact: true }).closest('div')!;
    expect(field('Nguồn hủy')).toHaveTextContent('Lễ tân hủy');
    expect(field('Lý do hủy')).toHaveTextContent('Khách gọi báo bận đột xuất');
    expect(field('Hủy muộn')).toHaveTextContent('Có');
    expect(field('Kết thúc')).toHaveTextContent('—');
    await user.click(within(dialog).getByRole('button', { name: 'Đóng' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('BRANCH_MANAGER cũng xem được chi tiết', async () => {
    const user = await openAs(ACCOUNTS.manager);
    await user.click(within(await rowOf('Bơ')).getByRole('button', { name: /^Chi tiết LT-/ }));
    const dialog = await screen.findByRole('dialog', { name: /^Chi tiết lưu trú LT-/ });
    expect(dialog).toHaveTextContent('Phạm Gia Bảo');
    expect(dialog).toHaveTextContent('Đã đặt');
  });
});

describe('AdminBoardingPage — gia hạn (UC58)', () => {
  const openExtend = async (user: ReturnType<typeof userEvent.setup>, row: HTMLElement) => {
    await user.click(within(row).getByRole('button', { name: /^Gia hạn LT-/ }));
    return screen.findByRole('dialog', { name: 'Gia hạn lưu trú' });
  };

  it('ngày trả mới phải sau ngày trả hiện tại; hiện số đêm mới và tiền tạm tính; gửi extend; danh sách cập nhật', async () => {
    const extend = vi.spyOn(boardingApi, 'extend');
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await openExtend(user, await rowOf('Bơ'));
    expect(dialog).toHaveTextContent('01/11/2026');
    const submit = within(dialog).getByRole('button', { name: 'Gia hạn' });
    const input = within(dialog).getByLabelText('Ngày trả mới');
    expect(input).toHaveAttribute('min', '2026-11-02');
    expect(submit).toBeDisabled();
    fireEvent.change(input, { target: { value: '2026-11-01' } });
    expect(submit).toBeDisabled();
    fireEvent.change(input, { target: { value: '2026-11-02' } });
    expect(dialog).toHaveTextContent('3 đêm (thêm 1 đêm)');
    expect(dialog).toHaveTextContent('Tạm tính 600.000 ₫');
    await user.click(submit);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(extend).toHaveBeenCalledWith(1, { checkOutDate: '2026-11-02' });
    await waitFor(async () => expect(await rowOf('Bơ')).toHaveTextContent('02/11/2026'));
    expect(await rowOf('Bơ')).toHaveTextContent('3 đêm');
  });

  it('đang lưu trú (CHECKED_IN) gia hạn tính đêm từ ngày nhận gốc', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await user.click(screen.getByRole('tab', { name: 'Đang lưu trú' }));
    const dialog = await openExtend(user, await rowOf('Cốm'));
    fireEvent.change(within(dialog).getByLabelText('Ngày trả mới'), { target: { value: '2026-11-01' } });
    expect(dialog).toHaveTextContent('11 đêm (thêm 1 đêm)');
    expect(dialog).toHaveTextContent('Tạm tính 2.200.000 ₫');
    await user.click(within(dialog).getByRole('button', { name: 'Gia hạn' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(async () => expect(await rowOf('Cốm')).toHaveTextContent('01/11/2026'));
  });

  it('lỗi BR của BE hiện nguyên câu, giữ hộp thoại và không kẹt nút', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await openExtend(user, await rowOf('Bơ'));
    fireEvent.change(within(dialog).getByLabelText('Ngày trả mới'), { target: { value: '2026-12-15' } });
    const submit = within(dialog).getByRole('button', { name: 'Gia hạn' });
    await user.click(submit);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận');
    expect(submit).toBeEnabled();
  });

  it('409 (người khác vừa hủy): hiện câu BE, làm mới danh sách, nút không kẹt', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const bo = await rowOf('Bơ');
    const code = within(bo).getByText(/^LT-/).textContent!;
    const dialog = await openExtend(user, bo);
    await (await asOther(ACCOUNTS.desk)).cancel(1);
    fireEvent.change(within(dialog).getByLabelText('Ngày trả mới'), { target: { value: '2026-11-02' } });
    const submit = within(dialog).getByRole('button', { name: 'Gia hạn' });
    await user.click(submit);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đặt chỗ đang quá hạn đón hoặc đã kết thúc, không gia hạn được');
    expect(submit).toBeEnabled();
    await waitFor(() => expect(screen.queryByText(code)).not.toBeInTheDocument());
  });
});

describe('AdminBoardingPage — hủy đặt chỗ (UC58)', () => {
  const openCancel = async (user: ReturnType<typeof userEvent.setup>, row: HTMLElement) => {
    await user.click(within(row).getByRole('button', { name: /^Hủy đặt chỗ LT-/ }));
    return screen.findByRole('dialog', { name: 'Hủy đặt chỗ lưu trú' });
  };

  it('hủy sớm: không cảnh báo hủy muộn; lý do tùy chọn ≤ 300; gửi cancel; hàng biến khỏi Sắp nhận', async () => {
    const cancel = vi.spyOn(boardingApi, 'cancel');
    const user = await openAs(ACCOUNTS.desk);
    const bo = await rowOf('Bơ');
    const code = within(bo).getByText(/^LT-/).textContent!;
    const dialog = await openCancel(user, bo);
    expect(dialog).not.toHaveTextContent('Hủy muộn');
    const reason = within(dialog).getByLabelText('Lý do hủy (không bắt buộc)');
    expect(reason).toHaveAttribute('maxlength', '300');
    await user.type(reason, 'Khách đổi kế hoạch');
    expect(dialog).toHaveTextContent('18/300');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(cancel).toHaveBeenCalledWith(1, { reason: 'Khách đổi kế hoạch' });
    await waitFor(() => expect(screen.queryByText(code)).not.toBeInTheDocument());
  });

  it('để trống lý do vẫn hủy được (không gửi reason)', async () => {
    const cancel = vi.spyOn(boardingApi, 'cancel');
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await openCancel(user, await rowOf('Bơ'));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(cancel).toHaveBeenCalledTimes(1));
    expect(cancel.mock.calls[0][1]).toStrictEqual({});
  });

  it('hủy muộn (nhận ngày mai): cảnh báo ảnh hưởng hạn chế đặt online; sau khi hủy có chip Hủy muộn ở Lịch sử', async () => {
    const other = await asOther(ACCOUNTS.desk);
    const { booking } = await other.book({ petId: 2, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' });
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await openCancel(user, await rowOfCode(booking.code));
    expect(dialog).toHaveTextContent('Hủy muộn');
    expect(dialog).toHaveTextContent('hạn chế đặt online');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: 'Lịch sử' }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Trạng thái lịch sử' }), 'Đã hủy');
    expect(await rowOfCode(booking.code)).toHaveTextContent('Hủy muộn');
  });

  it('409 (người khác vừa hủy): hiện câu BE, làm mới danh sách, nút không kẹt', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const bo = await rowOf('Bơ');
    const code = within(bo).getByText(/^LT-/).textContent!;
    const dialog = await openCancel(user, bo);
    await (await asOther(ACCOUNTS.desk)).cancel(1);
    const confirm = within(dialog).getByRole('button', { name: 'Xác nhận hủy' });
    await user.click(confirm);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái đặt chỗ lưu trú từ CANCELLED sang CANCELLED');
    expect(confirm).toBeEnabled();
    await waitFor(() => expect(screen.queryByText(code)).not.toBeInTheDocument());
  });
});

describe('AdminBoardingPage — đặt tại quầy (UC58, lễ tân)', () => {
  type User = ReturnType<typeof userEvent.setup>;

  async function chooseCustomer(user: User, search: string, pick: RegExp) {
    await user.click(screen.getByRole('button', { name: 'Đặt tại quầy' }));
    const dialog = await screen.findByRole('dialog', { name: 'Đặt chỗ lưu trú tại quầy' });
    await user.type(within(dialog).getByRole('searchbox', { name: 'Tìm khách theo SĐT, họ tên hoặc email' }), search);
    await user.click(await within(dialog).findByRole('button', { name: pick }));
    return dialog;
  }

  const setDates = (dialog: HTMLElement, checkIn: string, checkOut: string) => {
    fireEvent.change(within(dialog).getByLabelText('Ngày nhận'), { target: { value: checkIn } });
    fireEvent.change(within(dialog).getByLabelText('Ngày trả'), { target: { value: checkOut } });
  };

  /** Khách Nguyễn Thu Trang → Bim → Chuồng chó nhỏ. */
  async function pickBim(user: User) {
    const dialog = await chooseCustomer(user, 'Nguyễn Thu Trang', /^Nguyễn Thu Trang/);
    await user.click(within(dialog).getByRole('button', { name: /^Bim/ }));
    await user.click(await within(dialog).findByRole('button', { name: /^Chuồng chó nhỏ/ }));
    return dialog;
  }

  it('chỉ lễ tân thấy nút Đặt tại quầy', async () => {
    await openAs(ACCOUNTS.desk);
    expect(await screen.findByRole('button', { name: 'Đặt tại quầy' })).toBeInTheDocument();
  });

  it('chọn khách → thú → loại chuồng → ngày → còn chỗ từng đêm → đặt; cảnh báo thiếu mũi không chặn; danh sách có đặt chỗ mới', async () => {
    const book = vi.spyOn(boardingApi, 'book');
    const user = await openAs(ACCOUNTS.desk);
    await rowOf('Bơ');
    const dialog = await chooseCustomer(user, 'Nguyễn Thu Trang', /^Nguyễn Thu Trang/);
    expect(within(dialog).getByRole('group', { name: 'Chọn thú cưng' })).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: /^Bim/ }));
    await user.click(await within(dialog).findByRole('button', { name: /^Chuồng chó nhỏ/ }));
    setDates(dialog, '2026-10-24', '2026-10-26');

    const nights = await within(dialog).findByRole('list', { name: 'Còn chỗ từng đêm' });
    await waitFor(() => expect(within(nights).getAllByRole('listitem')).toHaveLength(2));
    const items = within(nights).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('24/10');
    expect(items[0]).toHaveTextContent('còn 1/3');
    expect(items[1]).toHaveTextContent('25/10');
    expect(dialog).toHaveTextContent('Tạm tính 400.000 ₫');

    await user.click(within(dialog).getByRole('button', { name: 'Đặt chỗ' }));
    const warnings = await within(dialog).findByRole('region', { name: 'Cảnh báo tiêm phòng' });
    expect(warnings).toHaveTextContent('5 bệnh');
    expect(warnings).toHaveTextContent('Chưa tiêm');
    expect(dialog).toHaveTextContent(/Đã đặt chỗ LT-/);
    expect(book).toHaveBeenCalledWith({ petId: 2, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-26' });
    await user.click(within(dialog).getByRole('button', { name: 'Xong' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const added = await screen.findByText('24/10/2026 → 26/10/2026');
    expect(added.closest('tr')).toHaveTextContent('Bim');
    expect(added.closest('tr')).toHaveTextContent('Đặt tại quầy');
  });

  it('lễ tân đặt được cả trong ngày: ngày nhận mặc định là hôm nay', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await pickBim(user);
    expect(within(dialog).getByLabelText('Ngày nhận')).toHaveValue('2026-10-23');
    expect(within(dialog).getByLabelText('Ngày nhận')).toHaveAttribute('min', '2026-10-23');
    await within(dialog).findByRole('list', { name: 'Còn chỗ từng đêm' });
    await user.click(within(dialog).getByRole('button', { name: 'Đặt chỗ' }));
    expect(await within(dialog).findByText(/Đã đặt chỗ LT-/)).toBeInTheDocument();
  });

  it('đặt không có cảnh báo mũi thì đóng hộp thoại ngay', async () => {
    vi.spyOn(boardingApi, 'book').mockResolvedValue({ booking: fakeBooking(77), vaccineWarnings: [] });
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await pickBim(user);
    await within(dialog).findByRole('list', { name: 'Còn chỗ từng đêm' });
    await user.click(within(dialog).getByRole('button', { name: 'Đặt chỗ' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('ẩn loại chuồng sai loài (mèo Mochi) và nói rõ vì sao không chọn được', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await chooseCustomer(user, 'Nguyễn Thu Trang', /^Nguyễn Thu Trang/);
    await user.click(within(dialog).getByRole('button', { name: /^Mochi/ }));
    expect(await within(dialog).findByText('Chưa có loại chuồng phù hợp với Mochi tại chi nhánh này.')).toBeInTheDocument();
    expect(within(dialog).getByText('Đã ẩn 1 loại chuồng sai loài hoặc quá cân nặng.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: /^Chuồng chó nhỏ/ })).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Ngày nhận')).not.toBeInTheDocument();
  });

  it('ẩn loại chuồng quá cân (Lu 18,3 kg > 10 kg)', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await chooseCustomer(user, '0905111222', /lo\*\*\*@gmail\.com/);
    await user.click(await within(dialog).findByRole('button', { name: /^Lu/ }));
    expect(await within(dialog).findByText('Chưa có loại chuồng phù hợp với Lu tại chi nhánh này.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: /^Chuồng chó nhỏ/ })).not.toBeInTheDocument();
  });

  it('đêm hết chỗ được tô rõ, nêu câu "Hết chỗ các đêm" và khóa nút Đặt chỗ', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await pickBim(user);
    setDates(dialog, '2026-10-29', '2026-10-31');
    const nights = await within(dialog).findByRole('list', { name: 'Còn chỗ từng đêm' });
    await waitFor(() => expect(within(nights).getAllByRole('listitem')).toHaveLength(2));
    const [ok, full] = within(nights).getAllByRole('listitem');
    expect(ok).toHaveTextContent('còn 1/3');
    expect(full).toHaveTextContent('30/10');
    expect(full).toHaveTextContent('hết chỗ');
    expect(dialog).toHaveTextContent('Hết chỗ các đêm: 30/10');
    expect(within(dialog).getByRole('button', { name: 'Đặt chỗ' })).toBeDisabled();
  });

  it('availability cũ báo còn chỗ nhưng BE trả BR-LT-03: hiện nguyên câu, không kẹt nút', async () => {
    const availability = vi.spyOn(boardingApi, 'availability').mockImplementation(async (_branch, q) => ({
      kennelTypeId: q.kennelTypeId,
      nightlyPrice: 200_000,
      nights: nightDates(q.checkInDate, q.checkOutDate).map((date) => ({ date, capacity: 3, occupied: 0, remaining: 3 })),
      available: true,
    }));
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await pickBim(user);
    setDates(dialog, '2026-10-29', '2026-10-31');
    await waitFor(() => expect(within(dialog).getByRole('list', { name: 'Còn chỗ từng đêm' }).children).toHaveLength(2));
    const submit = within(dialog).getByRole('button', { name: 'Đặt chỗ' });
    expect(submit).toBeEnabled();
    const before = availability.mock.calls.length;
    await user.click(submit);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Hết chỗ các đêm: 30/10');
    expect(submit).toBeEnabled();
    // Lỗi làm mới còn chỗ (người khác vừa đặt trước) để bảng đêm khớp thực tế.
    await waitFor(() => expect(availability.mock.calls.length).toBeGreaterThan(before));
  });

  it('BR-LT-02 của BE (ngày trả không sau ngày nhận) hiện nguyên câu và khóa nút', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await pickBim(user);
    setDates(dialog, '2026-10-24', '2026-10-24');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận');
    expect(within(dialog).getByRole('button', { name: 'Đặt chỗ' })).toBeDisabled();
  });

  it('lỗi BR khi đặt (ngày nhận trùng ngày chi nhánh nghỉ, T+20) hiện nguyên câu BE', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await pickBim(user);
    setDates(dialog, '2026-11-12', '2026-11-13');
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Đặt chỗ' })).toBeEnabled());
    await user.click(within(dialog).getByRole('button', { name: 'Đặt chỗ' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Ngày nhận / trả trùng ngày chi nhánh không làm việc');
    expect(within(dialog).getByRole('button', { name: 'Đặt chỗ' })).toBeEnabled();
  });

  it('Đổi khách quay về bước tìm khách và bỏ thú đã chọn', async () => {
    const user = await openAs(ACCOUNTS.desk);
    const dialog = await chooseCustomer(user, 'Nguyễn Thu Trang', /^Nguyễn Thu Trang/);
    await user.click(within(dialog).getByRole('button', { name: /^Bim/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Đổi khách' }));
    expect(within(dialog).getByRole('searchbox', { name: 'Tìm khách theo SĐT, họ tên hoặc email' })).toBeInTheDocument();
    expect(within(dialog).queryByRole('group', { name: 'Chọn thú cưng' })).not.toBeInTheDocument();
  });

  it('loại chuồng lấy từ dữ liệu công khai theo chi nhánh của lễ tân, không gọi /kennels', async () => {
    const user = await openAs(ACCOUNTS.desk);
    await pickBim(user);
    expect(calls.find((c) => c.url === '/api/public/services')?.params).toEqual({ group: 'BOARDING', branchId: 1 });
    expect(calls.some((c) => /kennels/.test(c.url))).toBe(false);
    expect(calls.filter((c) => c.status >= 400)).toEqual([]);
  });
});
