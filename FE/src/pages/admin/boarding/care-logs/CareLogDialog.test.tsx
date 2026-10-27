import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import type { CareLog } from '@/shared/types/boarding';
import { CareLogDialog } from './CareLogDialog';
import {
  ACCOUNTS,
  bimInStay,
  bookingOf,
  clearRequests,
  forbidden,
  installCareMock,
  notificationsOf,
  otherUser,
  renderDialog,
  requests,
  signIn,
  startDay,
  writes,
} from './care-harness';

// UC57 "Nhật ký chăm sóc" (A7, A8, BR-LT-11, BR-LT-12). Đồng hồ ghim thứ Sáu 23/10/2026 09:00 giờ VN; dữ liệu mẫu: xem đầu care-harness.ts.

beforeAll(installCareMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const WRITERS: string[] = [ACCOUNTS.caretaker, ACCOUNTS.vet];
const HTTPS = 'https://images.example.com/cot-1.jpg';
const at = (iso: string) => vi.setSystemTime(new Date(iso));
/** Cửa sổ lấy lại tiêu điểm: hộp thoại đọc lại đồng hồ (tab nền bị trình duyệt làm chậm interval). */
const refocus = () =>
  act(async () => {
    window.dispatchEvent(new Event('focus'));
  });

async function open(email: string, bookingId = 2, canWrite = WRITERS.includes(email)) {
  await signIn(email);
  const booking = await bookingOf(bookingId);
  clearRequests();
  const onClose = vi.fn();
  const view = renderDialog(<CareLogDialog booking={booking} canWrite={canWrite} onClose={onClose} />);
  const dialog = await screen.findByRole('dialog', { name: 'Nhật ký chăm sóc' });
  return { ...view, dialog, onClose, booking };
}

const entry = (name: RegExp) => screen.findByRole('article', { name });
const COM_1 = /Thu Hà lúc 17:30 21\/10\/2026/; // Cốm #1: CARETAKER, hết hạn sửa, 2 ảnh
const COM_2 = /BS\. Minh Anh lúc 19:40 22\/10\/2026/; // Cốm #2: VET, bất thường, 1 bản bổ sung
const MUC_4 = /Thu Hà lúc 08:30 23\/10\/2026/; // Mực #4: hôm nay, CARETAKER, sửa được tới 09:30
const MUC_3 = /Thu Hà lúc 17:00 22\/10\/2026/;

/** Ghi nhật ký bằng API với phiên khác (không qua hộp thoại), theo đồng hồ đang ghim. */
const writeVia = async (email: string, bookingId: number, body: object = {}) =>
  (await otherUser(email)).post<CareLog>(`/api/boarding-bookings/${bookingId}/care-logs`, { photoUrls: [], eating: 'Ăn sáng', ...body });

const openNewForm = async (user: ReturnType<typeof renderDialog>['user']) => {
  await user.click(await screen.findByRole('button', { name: 'Ghi nhật ký mới' }));
  return screen.findByRole('form', { name: 'Ghi nhật ký mới' });
};
const paste = async (user: ReturnType<typeof renderDialog>['user'], field: HTMLElement, text: string) => {
  await user.click(field);
  await user.paste(text);
};
const save = (form: HTMLElement) => within(form).getByRole('button', { name: 'Lưu nhật ký' });

describe('danh sách (UC57, UC60)', () => {
  it('nhóm theo ngày mới → cũ; mỗi mục có ô ăn / uống / vệ sinh / hoạt động (chỉ ô có dữ liệu), ghi chú, người ghi + giờ VN', async () => {
    await open(ACCOUNTS.caretaker);
    const days = (await screen.findAllByRole('heading', { level: 3 })).map((h) => h.textContent);
    expect(days).toHaveLength(2);
    expect(days[0]).toMatch(/22\/10\/2026/);
    expect(days[1]).toMatch(/21\/10\/2026/);

    const first = await entry(COM_1);
    expect(within(first).getByText('Ăn')).toBeInTheDocument();
    expect(within(first).getByText('Ăn hết 2 bữa')).toBeInTheDocument();
    expect(within(first).getByText('Uống đủ nước')).toBeInTheDocument();
    expect(within(first).getByText('Đi vệ sinh bình thường')).toBeInTheDocument();
    expect(within(first).getByText('Chạy chơi ở sân 30 phút')).toBeInTheDocument();
    expect(within(first).getByText('Bé hơi nhút nhát lúc mới vào chuồng')).toBeInTheDocument();
    expect(within(first).getByText(/Thu Hà/)).toHaveTextContent('ghi lúc 17:30 21/10/2026');

    // Mục #2 không có hoạt động: không hiện ô trống.
    const second = await entry(COM_2);
    expect(within(second).getByText('Bỏ ăn bữa tối')).toBeInTheDocument();
    expect(within(second).queryByText('Hoạt động')).not.toBeInTheDocument();
  });

  it('bất thường: có chữ "Bất thường" (không chỉ màu); mục bình thường không có', async () => {
    await open(ACCOUNTS.caretaker);
    expect(within(await entry(COM_2)).getByText('Bất thường')).toBeInTheDocument();
    expect(within(await entry(COM_1)).queryByText('Bất thường')).not.toBeInTheDocument();
  });

  it('bản bổ sung: nội dung + người + giờ VN; người bổ sung là người ghi → tên người ghi', async () => {
    await open(ACCOUNTS.caretaker);
    const second = await entry(COM_2);
    expect(within(second).getByText(/Đã khám nhanh: bụng mềm/)).toBeInTheDocument();
    expect(within(second).getByText(/BS\. Minh Anh · 20:10 22\/10\/2026/)).toBeInTheDocument();
  });

  it('ảnh: lưới nhỏ có alt mô tả, loading=lazy, referrerpolicy=no-referrer; ảnh lỗi → khung thay thế', async () => {
    await open(ACCOUNTS.caretaker);
    const first = await entry(COM_1);
    const photos = within(first).getAllByRole('img');
    expect(photos).toHaveLength(2);
    expect(photos[0]).toHaveAccessibleName('Ảnh 1/2 nhật ký ngày 21/10/2026 của Cốm');
    expect(photos[1]).toHaveAccessibleName('Ảnh 2/2 nhật ký ngày 21/10/2026 của Cốm');
    for (const img of photos) {
      expect(img).toHaveAttribute('loading', 'lazy');
      expect(img).toHaveAttribute('referrerpolicy', 'no-referrer');
    }
    fireEvent.error(photos[0]);
    expect(within(first).getAllByRole('img')).toHaveLength(1);
    expect(within(first).getByText('Không tải được ảnh')).toBeInTheDocument();
  });

  it('banner: chưa có nhật ký hôm nay (Cốm) / đã có (Mực)', async () => {
    const view = await open(ACCOUNTS.caretaker);
    expect(await screen.findByText(/Hôm nay chưa có nhật ký/)).toBeInTheDocument();
    view.unmount();
    await open(ACCOUNTS.caretaker, 3);
    expect(await screen.findByText(/Hôm nay đã có 1 nhật ký/)).toBeInTheDocument();
    expect(screen.queryByText(/Hôm nay chưa có nhật ký/)).not.toBeInTheDocument();
    expect((await screen.findAllByRole('heading', { level: 3 }))[0]).toHaveTextContent(/Hôm nay/);
  });

  it('đang tải → hiện trạng thái tải; lỗi → câu của BE + Thử lại (gọi lại được); rỗng → báo chưa có nhật ký', async () => {
    const spy = vi.spyOn(boardingApi, 'listCareLogs');
    spy.mockReturnValueOnce(new Promise(() => {}));
    const first = await open(ACCOUNTS.caretaker);
    expect(await screen.findByText('Đang tải nhật ký…')).toBeInTheDocument();
    expect(screen.getByText(/Hôm nay chưa có nhật ký/)).toBeInTheDocument(); // chưa có danh sách: dùng cờ missingCareLogToday của đặt chỗ (Cốm = true)
    first.unmount();

    spy.mockReturnValueOnce(new Promise(() => {}));
    await open(ACCOUNTS.caretaker, 3);
    expect(await screen.findByText('Đang tải nhật ký…')).toBeInTheDocument();
    expect(screen.getByText('Hôm nay đã có nhật ký chăm sóc')).toBeInTheDocument(); // Mực = false: không in "undefined"
    cleanup();

    spy.mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy đặt chỗ lưu trú #2', 404));
    const { user } = await open(ACCOUNTS.caretaker);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy đặt chỗ lưu trú #2');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await entry(COM_1)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('thú đang lưu trú nhưng chưa có nhật ký nào: báo rỗng, banner chưa có hôm nay, người được ghi thấy nút ghi', async () => {
    await bimInStay();
    await open(ACCOUNTS.caretaker, 4);
    expect(await screen.findByText('Chưa có nhật ký nào.')).toBeInTheDocument();
    expect(screen.getByText(/Hôm nay chưa có nhật ký/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ghi nhật ký mới' })).toBeInTheDocument();
  });
});

describe('trạng thái đặt chỗ (BR-LT-11)', () => {
  it.each([
    [4, 'BOOKED', /chưa vào chuồng/],
    [6, 'CANCELLED', /đã hủy/],
    [9, 'NO_SHOW', /không đến/],
  ])('đặt chỗ #%i %s: nói chưa có nhật ký, không gọi API nào, không có nút ghi', async (id, _status, text) => {
    await open(ACCOUNTS.caretaker, id);
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(requests()).toEqual([]);
    expect(screen.queryByRole('button', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm bản bổ sung' })).not.toBeInTheDocument();
  });

  it('đã trả thú (CHECKED_OUT): gọi API lấy nhật ký cũ, chỉ xem — không ghi mới, không Sửa; vẫn thêm được bản bổ sung (A4, ASSUMPTION)', async () => {
    await open(ACCOUNTS.caretaker, 5);
    expect(await screen.findAllByRole('article')).toHaveLength(3);
    expect(requests()).toEqual(['GET /api/boarding-bookings/5/care-logs']);
    expect(screen.getByText(/Lưu trú đã kết thúc/)).toBeInTheDocument();
    expect(screen.queryByText(/Hôm nay chưa có nhật ký/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Thêm bản bổ sung' })).toHaveLength(3);
  });

  it('đã trả thú, người chỉ xem: không có nút nào', async () => {
    await open(ACCOUNTS.desk, 5);
    expect(await screen.findAllByRole('article')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: 'Thêm bản bổ sung' })).not.toBeInTheDocument();
  });

  it('đã trả thú mà mục của mình còn trong 60 phút sửa: vẫn KHÔNG có nút Sửa (chỉ xem + bổ sung), không báo "quá thời hạn"', async () => {
    await writeVia(ACCOUNTS.caretaker, 2); // 09:00, sửa được tới 10:00
    await (await otherUser(ACCOUNTS.desk)).post('/api/boarding-bookings/2/end-deceased', { note: 'Thú mất đột ngột' });
    await open(ACCOUNTS.caretaker, 2);
    const mine = await entry(/Thu Hà lúc 09:00 23\/10\/2026/);
    expect(within(mine).queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument();
    expect(within(mine).queryByText(/Đã quá thời hạn sửa/)).not.toBeInTheDocument();
    expect(within(mine).queryByText(/để sửa/)).not.toBeInTheDocument();
    expect(within(mine).getByRole('button', { name: 'Thêm bản bổ sung' })).toBeInTheDocument();
  });

  it('OVERDUE vẫn ghi được', async () => {
    (await open(ACCOUNTS.caretaker, 2)).unmount(); // nạp state ở 09:00
    at('2026-10-31T13:00:00+07:00'); // quá 12:00 ngày trả 31/10: ST15 chuyển OVERDUE
    await open(ACCOUNTS.caretaker, 2);
    expect(await screen.findByText(/Quá hạn đón/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Ghi nhật ký mới' })).toBeInTheDocument();
  });
});

describe('quyền theo vai trò', () => {
  it.each([
    ['RECEPTIONIST', ACCOUNTS.desk],
    ['BRANCH_MANAGER', ACCOUNTS.manager],
  ])('%s chỉ xem: không nút ghi / sửa / bổ sung, có lời giải thích', async (_role, email) => {
    await open(email, 3);
    expect(await entry(MUC_4)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm bản bổ sung' })).not.toBeInTheDocument();
    expect(screen.getByText(/Bạn chỉ xem nhật ký/)).toBeInTheDocument();
  });

  it.each([
    ['CARETAKER', ACCOUNTS.caretaker],
    ['VET', ACCOUNTS.vet],
    ['RECEPTIONIST', ACCOUNTS.desk],
    ['BRANCH_MANAGER', ACCOUNTS.manager],
  ])('%s mở nhật ký: chỉ một lệnh GET care-logs, không phát sinh 403', async (_role, email) => {
    await open(email, 2);
    await entry(COM_1);
    expect(requests()).toEqual(['GET /api/boarding-bookings/2/care-logs']);
    expect(forbidden()).toEqual([]);
  });

  it('VET cũng ghi được', async () => {
    const { user } = await open(ACCOUNTS.vet);
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn ít');
    await user.click(save(form));
    expect(await screen.findByRole('article', { name: /BS\. Minh Anh lúc 09:00 23\/10\/2026/ })).toBeInTheDocument();
  });
});

describe('ghi mới', () => {
  it('hợp lệ: ngày mặc định hôm nay (VN, không cho ngày tương lai), gửi đúng body, danh sách + banner cập nhật, form đóng', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    const date = within(form).getByLabelText('Ngày nhật ký');
    expect(date).toHaveValue('2026-10-23');
    expect(date).toHaveAttribute('max', '2026-10-23');
    expect(screen.queryByRole('button', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();

    await user.type(within(form).getByLabelText('Ăn'), ' Ăn hết khẩu phần ');
    await user.type(within(form).getByLabelText('Hoạt động'), 'Dạo sân 20 phút');
    await user.type(within(form).getByLabelText('Ghi chú'), 'Bé ngoan');
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh' }));
    await paste(user, within(form).getByLabelText('Đường dẫn ảnh 1'), HTTPS);
    await user.click(save(form));

    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0]).toEqual([2, { logDate: '2026-10-23', eating: 'Ăn hết khẩu phần', activity: 'Dạo sân 20 phút', note: 'Bé ngoan', photoUrls: [HTTPS], isAbnormal: false }]);
    expect(writes()).toEqual(['POST /api/boarding-bookings/2/care-logs']);
    const created = await entry(/Thu Hà lúc 09:00 23\/10\/2026/);
    expect(within(created).getByText('Ăn hết khẩu phần')).toBeInTheDocument();
    expect(within(created).getByRole('img')).toHaveAttribute('src', HTTPS);
    expect(screen.getByText(/Hôm nay đã có 1 nhật ký/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ghi nhật ký mới' })).toBeInTheDocument();
  });

  it('chọn ngày quá khứ: gửi đúng logDate đó (nhật ký bù)', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    fireEvent.change(within(form).getByLabelText('Ngày nhật ký'), { target: { value: '2026-10-22' } });
    await user.type(within(form).getByLabelText('Uống'), 'Uống đủ');
    await user.click(save(form));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0][1]).toMatchObject({ logDate: '2026-10-22', drinking: 'Uống đủ' });
  });

  it('ngày tương lai bị chặn ở form (không gọi API); xóa trống ngày cũng bị chặn', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    fireEvent.change(within(form).getByLabelText('Ngày nhật ký'), { target: { value: '2026-10-24' } });
    await user.click(save(form));
    expect(await within(form).findByText('Ngày nhật ký không được ở tương lai')).toBeInTheDocument();
    fireEvent.change(within(form).getByLabelText('Ngày nhật ký'), { target: { value: '' } });
    await user.click(save(form));
    expect(await within(form).findByText('Vui lòng chọn ngày nhật ký')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('mỗi ô ngắn tối đa 300 ký tự: 301 bị chặn kèm bộ đếm, 300 qua', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    const eating = within(form).getByLabelText('Ăn');
    await paste(user, eating, 'a'.repeat(301));
    expect(within(form).getByText('301/300')).toBeInTheDocument();
    await user.click(save(form));
    expect(await within(form).findByText('Tối đa 300 ký tự')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
    await user.clear(eating);
    await paste(user, eating, 'a'.repeat(300));
    await user.click(save(form));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
  });

  it('mục hoàn toàn trống bị chặn (ASSUMPTION); chỉ có ảnh là đủ', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    await user.click(save(form));
    expect(await within(form).findByText('Nhật ký trống: nhập ít nhất một nội dung hoặc một ảnh')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh' }));
    await paste(user, within(form).getByLabelText('Đường dẫn ảnh 1'), HTTPS);
    await user.click(save(form));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0][1]).toStrictEqual({ logDate: '2026-10-23', photoUrls: [HTTPS], isAbnormal: false });
  });

  it('ảnh: không phải https bị chặn; tối đa 5 ô (nút Thêm ảnh khóa); thêm / xóa / chuyển lên-xuống đổi đúng thứ tự gửi đi; ô trống bị bỏ', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    expect(within(form).getByText(/chưa tải ảnh lên được/)).toBeInTheDocument();
    const add = () => user.click(within(form).getByRole('button', { name: 'Thêm ảnh' }));
    await add();
    await paste(user, within(form).getByLabelText('Đường dẫn ảnh 1'), 'http://images.example.com/a.jpg');
    await user.click(save(form));
    expect(await within(form).findByText('Ảnh phải là đường dẫn https (bắt đầu bằng https://)')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();

    await user.clear(within(form).getByLabelText('Đường dẫn ảnh 1'));
    await paste(user, within(form).getByLabelText('Đường dẫn ảnh 1'), 'https://i.example.com/a.jpg');
    await add();
    await paste(user, within(form).getByLabelText('Đường dẫn ảnh 2'), 'https://i.example.com/b.jpg');
    await add();
    await paste(user, within(form).getByLabelText('Đường dẫn ảnh 3'), 'https://i.example.com/c.jpg');
    await user.click(within(form).getByRole('button', { name: 'Chuyển ảnh 3 lên' })); // a, c, b
    await user.click(within(form).getByRole('button', { name: 'Chuyển ảnh 1 xuống' })); // c, a, b
    expect(within(form).getByRole('button', { name: 'Chuyển ảnh 1 lên' })).toBeDisabled();
    expect(within(form).getByRole('button', { name: 'Chuyển ảnh 3 xuống' })).toBeDisabled();
    await user.click(within(form).getByRole('button', { name: 'Xóa ảnh 3' })); // c, a
    await add(); // ô trống thứ 3: bị bỏ khi gửi
    expect(within(form).getAllByLabelText(/^Đường dẫn ảnh \d$/).map((i) => (i as HTMLInputElement).value)).toEqual([
      'https://i.example.com/c.jpg',
      'https://i.example.com/a.jpg',
      '',
    ]);
    await add();
    await add();
    expect(within(form).getAllByLabelText(/^Đường dẫn ảnh \d$/)).toHaveLength(5);
    expect(within(form).getByRole('button', { name: 'Thêm ảnh' })).toBeDisabled();

    await user.click(save(form));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0][1]).toMatchObject({ photoUrls: ['https://i.example.com/c.jpg', 'https://i.example.com/a.jpg'] });
  });

  it('bất thường: bật công tắc kèm lời giải thích hệ quả; bấm lưu → hỏi xác nhận (chưa gửi); quay lại không gửi; xác nhận mới gửi isAbnormal=true', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    const toggle = within(form).getByRole('switch', { name: /Đánh dấu bất thường/ });
    expect(toggle).not.toBeChecked();
    expect(within(form).getByText(/thông báo ngay cho chủ thú và lễ tân/)).toBeInTheDocument();
    expect(within(form).getByText(/liên hệ chủ thú trước/)).toBeInTheDocument();
    await user.type(within(form).getByLabelText('Ghi chú'), 'Bỏ ăn, nôn hai lần');
    await user.click(toggle);
    expect(toggle).toBeChecked();

    await user.click(save(form));
    expect(await within(form).findByText('Xác nhận đánh dấu bất thường')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();

    await user.click(within(form).getByRole('button', { name: 'Quay lại chỉnh sửa' }));
    expect(within(form).queryByText('Xác nhận đánh dấu bất thường')).not.toBeInTheDocument();
    expect(toggle).toBeChecked();
    expect(spy).not.toHaveBeenCalled();

    await user.click(save(form));
    await user.click(await within(form).findByRole('button', { name: 'Xác nhận và gửi thông báo' }));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0][1]).toMatchObject({ isAbnormal: true, note: 'Bỏ ăn, nôn hai lần' });
    expect(within(await entry(/Thu Hà lúc 09:00 23\/10\/2026/)).getByText('Bất thường')).toBeInTheDocument();
  });

  it('bất thường → thông báo tới khách (link chi tiết lưu trú) và lễ tân (link /admin/boarding); mục bình thường không báo', async () => {
    await bimInStay();
    const before = { customer: (await notificationsOf(ACCOUNTS.customer)).length, desk: (await notificationsOf(ACCOUNTS.desk)).length };
    const { user } = await open(ACCOUNTS.caretaker, 4);

    let form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn tốt');
    await user.click(save(form));
    await screen.findByRole('article');
    expect(screen.queryByText('Xác nhận đánh dấu bất thường')).not.toBeInTheDocument();
    expect((await notificationsOf(ACCOUNTS.customer)).length).toBe(before.customer);

    form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ghi chú'), 'Nôn hai lần, bỏ ăn');
    await user.click(within(form).getByRole('switch', { name: /Đánh dấu bất thường/ }));
    await user.click(save(form));
    await user.click(await within(form).findByRole('button', { name: 'Xác nhận và gửi thông báo' }));
    await waitFor(async () => expect((await notificationsOf(ACCOUNTS.customer)).length).toBe(before.customer + 1));
    const mine = (await notificationsOf(ACCOUNTS.customer))[0];
    expect(mine).toMatchObject({ type: 'CARE_LOG_ABNORMAL', title: 'Nhật ký chăm sóc bất thường: Bim', linkUrl: '/boarding-bookings/4' });
    expect(mine.body).toContain('Nôn hai lần, bỏ ăn');
    expect((await notificationsOf(ACCOUNTS.desk))[0]).toMatchObject({ type: 'CARE_LOG_ABNORMAL', linkUrl: '/admin/boarding' });
    expect((await notificationsOf(ACCOUNTS.desk)).length).toBe(before.desk + 1);
  });

  it('lỗi theo trường của BE (VALIDATION_FAILED) hiện dưới đúng ô, không phải ở dải lỗi; form giữ nguyên, không kẹt nút', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    vi.spyOn(boardingApi, 'createCareLog')
      .mockRejectedValueOnce(new ApiError('VALIDATION_FAILED', 'eating: Tối đa 300 ký tự; photoUrls: Tối đa 5 ảnh', 400))
      .mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    await user.click(save(form));
    expect(await within(form).findByText('Tối đa 300 ký tự')).toBeInTheDocument();
    expect(within(form).getByText('Tối đa 5 ảnh')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(save(form)).toBeEnabled();

    await user.click(save(form));
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
    expect(screen.getByRole('form', { name: 'Ghi nhật ký mới' })).toBeInTheDocument();
    expect(save(form)).toBeEnabled();
  });

  it('đang gửi: nút khóa và không gửi hai lần', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    let release: (log: CareLog) => void = () => {};
    const spy = vi.spyOn(boardingApi, 'createCareLog').mockReturnValue(new Promise((resolve) => (release = resolve)));
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    await user.click(save(form));
    const busy = await within(form).findByRole('button', { name: 'Đang xử lý…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    expect(spy).toHaveBeenCalledTimes(1);
    release(await writeVia(ACCOUNTS.caretaker, 2));
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument());
  });

  it('409 (người khác vừa kết thúc lưu trú): hiện nguyên câu của BE, làm mới danh sách lưu trú, hộp thoại chuyển sang chỉ xem', async () => {
    const { user, client } = await open(ACCOUNTS.caretaker);
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    await (await otherUser(ACCOUNTS.desk)).post('/api/boarding-bookings/2/end-deceased', { note: 'Thú mất đột ngột' });
    const refetch = vi.spyOn(client, 'invalidateQueries');
    await user.click(save(form));
    expect(await screen.findByRole('alert')).toHaveTextContent('Thú cưng không đang lưu trú');
    expect(refetch).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['boarding'] }));
    expect(screen.queryByRole('form', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(screen.getByText(/Lưu trú đã kết thúc/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Thêm bản bổ sung' }).length).toBeGreaterThan(0);
  });

  it('Hủy đóng form mà không gửi gì', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    await user.click(within(form).getByRole('button', { name: 'Hủy' }));
    expect(screen.queryByRole('form', { name: 'Ghi nhật ký mới' })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });
});

describe('sửa trong hạn (BR-LT-11)', () => {
  it('mục của chính mình, còn hạn: có nút Sửa + nhãn "còn N phút để sửa"; mục hết hạn thì ẩn nút và nói thêm bản bổ sung', async () => {
    await open(ACCOUNTS.caretaker, 3);
    const today = await entry(MUC_4);
    expect(within(today).getByRole('button', { name: 'Sửa' })).toBeInTheDocument();
    expect(within(today).getByText('còn 30 phút để sửa')).toBeInTheDocument();
    const old = await entry(MUC_3);
    expect(within(old).queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument();
    expect(within(old).getByText('Đã quá thời hạn sửa — thêm bản bổ sung')).toBeInTheDocument();
  });

  it('mục của người khác (kể cả VET): không có nút Sửa và không có câu "quá thời hạn"', async () => {
    await open(ACCOUNTS.vet, 3);
    const today = await entry(MUC_4);
    expect(within(today).queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument();
    expect(within(today).queryByText(/Đã quá thời hạn sửa/)).not.toBeInTheDocument();
    expect(within(today).queryByText(/để sửa/)).not.toBeInTheDocument();
    expect(within(today).getByRole('button', { name: 'Thêm bản bổ sung' })).toBeInTheDocument();
  });

  it('mục vừa ghi: nhãn đúng "còn 60 phút" dù đồng hồ hộp thoại đã cũ (đọc lại đồng hồ sau khi lưu, không để hiện 61)', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    at('2026-10-23T09:00:30+07:00'); // 30 giây trôi qua mà chưa tới lần đọc đồng hồ kế tiếp
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    await user.click(save(form));
    const created = await entry(/Thu Hà lúc 09:00 23\/10\/2026/);
    expect(within(created).getByText('còn 60 phút để sửa')).toBeInTheDocument();
  });

  it('sửa: form nạp sẵn nội dung, gửi UpdateCareLogRequest (đủ ô chữ + ảnh, KHÔNG isAbnormal / logDate), danh sách cập nhật', async () => {
    const { user } = await open(ACCOUNTS.caretaker, 3);
    const spy = vi.spyOn(boardingApi, 'updateCareLog');
    await user.click(within(await entry(MUC_4)).getByRole('button', { name: 'Sửa' }));
    const form = await screen.findByRole('form', { name: 'Sửa nhật ký' });
    expect(within(form).getByLabelText('Ăn')).toHaveValue('Ăn hết khẩu phần sáng');
    expect(within(form).getByLabelText('Hoạt động')).toHaveValue('Chơi với bóng');
    expect(within(form).queryByLabelText('Ngày nhật ký')).not.toBeInTheDocument();
    expect(within(form).queryByRole('switch')).not.toBeInTheDocument();
    const drinking = within(form).getByLabelText('Uống');
    await user.clear(drinking);
    await user.clear(within(form).getByLabelText('Ăn'));
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn thêm bữa phụ');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0][0]).toBe(4);
    expect(spy.mock.calls[0][1]).toStrictEqual({ eating: 'Ăn thêm bữa phụ', drinking: '', hygiene: '', activity: 'Chơi với bóng', note: '', photoUrls: [] });
    expect(writes()).toEqual(['PATCH /api/care-logs/4']);
    expect(await screen.findByText('Ăn thêm bữa phụ')).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Sửa nhật ký' })).not.toBeInTheDocument();
    expect(screen.queryByText('Uống đủ nước', { selector: 'dd' })).toBeInTheDocument(); // chỉ còn ở mục hôm qua (#3)
    expect(within(await entry(MUC_4)).queryByText('Uống')).not.toBeInTheDocument();
  });

  it('sửa mục bất thường: không có công tắc, ghi chú rằng cờ không đổi được ở đây; xóa hết nội dung bị chặn', async () => {
    await signIn(ACCOUNTS.caretaker);
    await writeVia(ACCOUNTS.caretaker, 2, { isAbnormal: true, eating: '', note: 'Bỏ ăn' });
    const { user } = await open(ACCOUNTS.caretaker, 2);
    const spy = vi.spyOn(boardingApi, 'updateCareLog');
    await user.click(within(await entry(/Thu Hà lúc 09:00 23\/10\/2026/)).getByRole('button', { name: 'Sửa' }));
    const form = await screen.findByRole('form', { name: 'Sửa nhật ký' });
    expect(within(form).queryByRole('switch')).not.toBeInTheDocument();
    expect(within(form).getByText(/đã đánh dấu bất thường/i)).toBeInTheDocument();
    await user.clear(within(form).getByLabelText('Ghi chú'));
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await within(form).findByText('Nhật ký trống: nhập ít nhất một nội dung hoặc một ảnh')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('ranh giới 60 phút: 59:59 còn nút Sửa ("còn 1 phút"); 60:01 ẩn nút, hiện "Đã quá thời hạn sửa — thêm bản bổ sung"', async () => {
    await writeVia(ACCOUNTS.caretaker, 2); // 09:00 → sửa được tới 10:00
    at('2026-10-23T09:59:59+07:00');
    await open(ACCOUNTS.caretaker, 2);
    const mine = await entry(/Thu Hà lúc 09:00 23\/10\/2026/);
    expect(within(mine).getByRole('button', { name: 'Sửa' })).toBeInTheDocument();
    expect(within(mine).getByText('còn 1 phút để sửa')).toBeInTheDocument();

    at('2026-10-23T10:00:01+07:00');
    await refocus();
    await waitFor(() => expect(within(mine).queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument());
    expect(within(mine).getByText('Đã quá thời hạn sửa — thêm bản bổ sung')).toBeInTheDocument();
    expect(within(mine).getByRole('button', { name: 'Thêm bản bổ sung' })).toBeInTheDocument();
  });

  it('đang mở form sửa mà quá hạn: gửi nhận 409 — hiện nguyên câu, làm mới, chuyển sang chế độ bổ sung', async () => {
    await writeVia(ACCOUNTS.caretaker, 2);
    at('2026-10-23T09:30:00+07:00');
    const { user, client } = await open(ACCOUNTS.caretaker, 2);
    await user.click(within(await entry(/Thu Hà lúc 09:00 23\/10\/2026/)).getByRole('button', { name: 'Sửa' }));
    const form = await screen.findByRole('form', { name: 'Sửa nhật ký' });
    await user.type(within(form).getByLabelText('Ăn'), ' thêm');
    at('2026-10-23T10:00:30+07:00'); // quá hạn nhưng chưa tick: form vẫn mở
    const refetch = vi.spyOn(client, 'invalidateQueries');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Đã quá thời hạn sửa nhật ký, vui lòng thêm bản bổ sung');
    expect(refetch).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['boarding'] }));
    expect(screen.queryByRole('form', { name: 'Sửa nhật ký' })).not.toBeInTheDocument();
    expect(await screen.findByRole('form', { name: 'Thêm bản bổ sung' })).toBeInTheDocument();
    expect(screen.getByLabelText('Nội dung bổ sung')).toBeInTheDocument();
  });
});

describe('bản bổ sung (A4)', () => {
  it('mọi mục đều có nút, kể cả của người khác và đã hết hạn sửa; gửi đúng endpoint; hiện "Bạn" + giờ VN; chỉ thêm, không sửa / xóa', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    expect(await screen.findAllByRole('button', { name: 'Thêm bản bổ sung' })).toHaveLength(2);
    const spy = vi.spyOn(boardingApi, 'addAddendum');
    await user.click(within(await entry(COM_2)).getByRole('button', { name: 'Thêm bản bổ sung' }));
    const form = await screen.findByRole('form', { name: 'Thêm bản bổ sung' });
    expect(within(form).getByText('0/2000')).toBeInTheDocument();
    await user.type(within(form).getByLabelText('Nội dung bổ sung'), '  Đã gọi báo chủ, bé ăn lại bình thường ');
    await user.click(within(form).getByRole('button', { name: 'Lưu bản bổ sung' }));

    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0]).toEqual([2, { content: 'Đã gọi báo chủ, bé ăn lại bình thường' }]);
    expect(writes()).toEqual(['POST /api/care-logs/2/addenda']);
    const second = await entry(COM_2);
    expect(await within(second).findByText('Đã gọi báo chủ, bé ăn lại bình thường')).toBeInTheDocument();
    expect(within(second).getByText(/Bạn · 09:00 23\/10\/2026/)).toBeInTheDocument();
    expect(within(second).getByText(/Đã khám nhanh/)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Thêm bản bổ sung' })).not.toBeInTheDocument();
    expect(within(second).queryByRole('button', { name: /Sửa bản bổ sung|Xóa bản bổ sung/ })).not.toBeInTheDocument();
  });

  it('người bổ sung khác người ghi: nhãn trung tính "Nhân viên #id" (hợp đồng không trả tên người bổ sung)', async () => {
    await (await otherUser(ACCOUNTS.vet)).post('/api/care-logs/1/addenda', { content: 'Bổ sung của bác sĩ' });
    await open(ACCOUNTS.caretaker);
    expect(within(await entry(COM_1)).getByText(/Nhân viên #5 · /)).toBeInTheDocument();
  });

  it('nội dung 1–2000 ký tự: trống, toàn khoảng trắng và 2001 bị chặn (không gọi API); 2000 qua', async () => {
    const { user } = await open(ACCOUNTS.vet);
    const spy = vi.spyOn(boardingApi, 'addAddendum');
    await user.click(within(await entry(COM_1)).getByRole('button', { name: 'Thêm bản bổ sung' }));
    const form = await screen.findByRole('form', { name: 'Thêm bản bổ sung' });
    const box = within(form).getByLabelText('Nội dung bổ sung');
    const submit = () => user.click(within(form).getByRole('button', { name: 'Lưu bản bổ sung' }));
    await submit();
    expect(await within(form).findByText('Vui lòng nhập nội dung bổ sung')).toBeInTheDocument();
    await paste(user, box, '   ');
    await submit();
    expect(within(form).getByText('Vui lòng nhập nội dung bổ sung')).toBeInTheDocument();
    await user.clear(box);
    await paste(user, box, 'a'.repeat(2001));
    expect(within(form).getByText('2001/2000')).toBeInTheDocument();
    await submit();
    expect(await within(form).findByText('Nội dung tối đa 2000 ký tự')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
    await user.clear(box);
    await paste(user, box, 'a'.repeat(2000));
    await submit();
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
  });

  it('lỗi BE: lỗi theo trường (content) dưới ô; lỗi khác hiện ở dải lỗi; form giữ nguyên', async () => {
    const { user } = await open(ACCOUNTS.vet);
    vi.spyOn(boardingApi, 'addAddendum')
      .mockRejectedValueOnce(new ApiError('VALIDATION_FAILED', 'content: Nội dung tối đa 2000 ký tự', 400))
      .mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy nhật ký chăm sóc #1', 404));
    await user.click(within(await entry(COM_1)).getByRole('button', { name: 'Thêm bản bổ sung' }));
    const form = await screen.findByRole('form', { name: 'Thêm bản bổ sung' });
    await user.type(within(form).getByLabelText('Nội dung bổ sung'), 'Bổ sung');
    await user.click(within(form).getByRole('button', { name: 'Lưu bản bổ sung' }));
    expect(await within(form).findByText('Nội dung tối đa 2000 ký tự')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Lưu bản bổ sung' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy nhật ký chăm sóc #1');
    expect(within(form).getByRole('button', { name: 'Lưu bản bổ sung' })).toBeEnabled();
  });

  it('chỉ một biểu mẫu mở một lúc: mở bổ sung ở mục khác thì đóng biểu mẫu trước', async () => {
    const { user } = await open(ACCOUNTS.caretaker);
    await user.click(within(await entry(COM_1)).getByRole('button', { name: 'Thêm bản bổ sung' }));
    await user.click(within(await entry(COM_2)).getByRole('button', { name: 'Thêm bản bổ sung' }));
    expect(screen.getAllByRole('form')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Ghi nhật ký mới' }));
    expect(screen.getAllByRole('form')).toHaveLength(1);
    expect(screen.getByRole('form', { name: 'Ghi nhật ký mới' })).toBeInTheDocument();
  });
});

describe('ranh giới nửa đêm giờ Việt Nam', () => {
  it('23:59 ghi nhật ký hôm nay → đủ; 00:01 ngày mới → lại "chưa có nhật ký hôm nay" và ngày mặc định của form là ngày mới', async () => {
    at('2026-10-23T23:59:00+07:00');
    const { user } = await open(ACCOUNTS.caretaker);
    let form = await openNewForm(user);
    expect(within(form).getByLabelText('Ngày nhật ký')).toHaveValue('2026-10-23');
    await user.type(within(form).getByLabelText('Ăn'), 'Bữa tối');
    await user.click(save(form));
    expect(await screen.findByText(/Hôm nay đã có 1 nhật ký/)).toBeInTheDocument();

    at('2026-10-24T00:01:00+07:00'); // 17:01 UTC ngày 23: toISOString().slice(0, 10) sẽ ra nhầm ngày
    await refocus();
    expect(await screen.findByText(/Hôm nay chưa có nhật ký/)).toBeInTheDocument();
    expect(screen.queryByText(/Hôm nay đã có/)).not.toBeInTheDocument();
    form = await openNewForm(user);
    expect(within(form).getByLabelText('Ngày nhật ký')).toHaveValue('2026-10-24');
    expect(within(form).getByLabelText('Ngày nhật ký')).toHaveAttribute('max', '2026-10-24');
  });
});
