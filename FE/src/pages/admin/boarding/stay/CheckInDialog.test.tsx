import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { recordDoseForMock } from '@/shared/api/mock/visit-clinical.mock';
import { CheckInDialog } from './CheckInDialog';
import {
  ACCOUNTS,
  bookingOf,
  clearRequests,
  dosedBim,
  forbidden,
  installStayMock,
  otherStaff,
  renderDialog,
  requests,
  rexInStay,
  signIn,
  startDay,
  writes,
} from './stay-harness';

// UC59 "Nhận thú" (A1, BR-LT-03/04/05/08), đồng hồ ghim thứ Sáu 23/10/2026 09:00, chi nhánh 1 mở cửa 08:00–12:00 và 13:30–19:00.
// Đặt chỗ #4: Bim BOOKED 26/10→28/10 (nhận sớm 3 ngày), chưa tiêm 5 bệnh. #1: Bơ BOOKED 30/10→01/11, không có hồ sơ tiêm nên không thiếu mũi.
// Loại chuồng #5 "Chuồng chó nhỏ": C-01 (Cốm), C-02 (Mực) có thú, C-03 trống, C-04 bảo trì → sức chứa 3.

beforeAll(installStayMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

type OpenOptions = { email?: string; id?: number; dosed?: boolean; at?: string };

async function open({ email = ACCOUNTS.desk, id = 4, dosed = false, at }: OpenOptions = {}) {
  await signIn(email);
  const booking = await bookingOf(id); // nạp dữ liệu mẫu trước khi ghi liều
  if (dosed) dosedBim();
  if (at) {
    vi.setSystemTime(new Date(at));
    await signIn(email); // phiên cũ hết hạn khi đồng hồ nhảy tới ngày khác
  }
  clearRequests();
  const onClose = vi.fn();
  const view = renderDialog(<CheckInDialog booking={booking} onClose={onClose} />);
  return { ...view, onClose, booking };
}

type User = ReturnType<typeof userEvent.setup>;
const W = 'Cân nặng khi nhận (kg)';
const submitName = 'Xác nhận nhận thú';

async function fill(user: User, o: { weight?: string; health?: string; belongings?: string; diet?: string; phone?: string } = {}) {
  await screen.findByLabelText(W);
  await user.type(screen.getByLabelText(W), o.weight ?? '6,25');
  await user.type(screen.getByLabelText('Tình trạng sức khỏe'), o.health ?? 'Khỏe, hơi nhút nhát');
  if (o.belongings) await user.type(screen.getByLabelText('Đồ gửi kèm'), o.belongings);
  if (o.diet) await user.type(screen.getByLabelText('Chế độ ăn riêng'), o.diet);
  await user.type(screen.getByLabelText('SĐT liên hệ khẩn cấp'), o.phone ?? '0987000111');
}

const submit = (user: User) => user.click(screen.getByRole('button', { name: submitName }));
const noRoomLeft = async () => (await otherStaff(ACCOUNTS.manager)).post('/api/kennels/3/start-maintenance');

/** Rex đặt 24/10→25/10 (chưa nhận): đêm 24/10 kín chỗ (Cốm + Mực + Rex) nên Bim nhận sớm bị BR-LT-03. */
async function fillNight24() {
  const desk = await otherStaff(ACCOUNTS.desk);
  const { pet } = await desk.post<{ pet: { petId: number } }>('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' });
  await desk.post('/api/boarding-bookings', { petId: pet.petId, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' });
}

describe('CheckInDialog — điều kiện nhận (A1)', () => {
  it('đang tải: báo đang kiểm tra điều kiện, chưa hiện form', async () => {
    await open();
    expect(screen.getByRole('dialog', { name: 'Nhận thú' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Đang kiểm tra điều kiện nhận thú');
    expect(screen.queryByLabelText(W)).not.toBeInTheDocument();
    await screen.findByRole('table', { name: 'Mũi tiêm còn thiếu' });
  });

  it('thiếu mũi (Bim chưa tiêm 5 bệnh): liệt kê, giải thích BR-LT-05, dẫn tới walk-in, không có form và không gửi gì', async () => {
    await open();
    const table = await screen.findByRole('table', { name: 'Mũi tiêm còn thiếu' });
    const row = within(table).getByRole('row', { name: /5 bệnh/ });
    expect(row).toHaveTextContent('Chưa tiêm');
    const dialog = screen.getByRole('dialog', { name: 'Nhận thú' });
    expect(within(dialog).getByText(/BR-LT-05/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Mũi tiêm ở nơi khác không được tính/)).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: /Tiếp nhận như walk-in/ })).toHaveAttribute('href', '/staff/reception');
    expect(within(dialog).queryByLabelText(W)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: submitName })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('mũi gần nhất đã quá hạn: hiện ngày tiêm cuối và hạn tái chủng', async () => {
    await signIn(ACCOUNTS.desk);
    await bookingOf(4);
    recordDoseForMock(2, 2, '2026-10-01'); // hạn 22/10 < hôm nay 23/10
    const booking = await bookingOf(4);
    renderDialog(<CheckInDialog booking={booking} onClose={() => {}} />);
    const row = within(await screen.findByRole('table', { name: 'Mũi tiêm còn thiếu' })).getByRole('row', { name: /5 bệnh/ });
    expect(row).toHaveTextContent('01/10/2026');
    expect(row).toHaveTextContent('22/10/2026');
  });

  it('CARETAKER thấy thiếu mũi nhưng không có đường dẫn sang bàn Lễ tân (nhờ lễ tân)', async () => {
    await open({ email: ACCOUNTS.caretaker });
    await screen.findByRole('table', { name: 'Mũi tiêm còn thiếu' });
    expect(screen.queryByRole('link', { name: /walk-in/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Nhờ lễ tân tiếp nhận/)).toBeInTheDocument();
  });

  it('ngoài giờ mở cửa (nghỉ trưa): chặn có lý do BR-LT-04, không có form', async () => {
    await open({ id: 1, at: '2026-10-23T12:30:00+07:00' });
    const dialog = screen.getByRole('dialog', { name: 'Nhận thú' });
    expect(await within(dialog).findByText(/Ngoài giờ mở cửa của chi nhánh/)).toBeInTheDocument();
    expect(within(dialog).getByText(/BR-LT-04/)).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(W)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: submitName })).not.toBeInTheDocument();
  });

  it('hết chuồng đúng loại: chặn, nói rõ lý do; lễ tân có nút hủy vì hết chuồng, CARETAKER thì không', async () => {
    await signIn(ACCOUNTS.desk);
    await bookingOf(4);
    await noRoomLeft();
    dosedBim();
    const booking = await bookingOf(4);
    const first = renderDialog(<CheckInDialog booking={booking} onClose={() => {}} />);
    const dialog = await screen.findByRole('dialog', { name: 'Nhận thú' });
    expect(await within(dialog).findByText(/Không còn chuồng trống đúng loại/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Hủy vì hết chuồng' })).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(W)).not.toBeInTheDocument();
    first.unmount();

    await signIn(ACCOUNTS.caretaker);
    renderDialog(<CheckInDialog booking={booking} onClose={() => {}} />);
    const asCaretaker = await screen.findByRole('dialog', { name: 'Nhận thú' });
    expect(await within(asCaretaker).findByText(/Không còn chuồng trống đúng loại/)).toBeInTheDocument();
    expect(within(asCaretaker).queryByRole('button', { name: 'Hủy vì hết chuồng' })).not.toBeInTheDocument();
  });

  it('nút Hủy vì hết chuồng mở NoKennelDialog; hủy xong thì đóng cả hộp thoại nhận thú', async () => {
    await signIn(ACCOUNTS.desk);
    await bookingOf(4);
    await noRoomLeft();
    dosedBim();
    const booking = await bookingOf(4);
    const onClose = vi.fn();
    const { user } = renderDialog(<CheckInDialog booking={booking} onClose={onClose} />);
    await user.click(await screen.findByRole('button', { name: 'Hủy vì hết chuồng' }));
    const nested = await screen.findByRole('dialog', { name: 'Hủy vì hết chuồng' });
    await user.click(within(nested).getByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(await bookingOf(4)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC' });
  });

  it('lỗi tải (404): hiện câu của BE, Thử lại gọi lại, Đóng đóng được', async () => {
    await signIn(ACCOUNTS.desk);
    const booking = await bookingOf(4);
    clearRequests();
    const onClose = vi.fn();
    const { user } = renderDialog(<CheckInDialog booking={{ ...booking, bookingId: 999 }} onClose={onClose} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy đặt chỗ lưu trú #999');
    const asked = () => requests().filter((r) => r.includes('/999/check-in-readiness')).length;
    expect(asked()).toBe(1);
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(asked()).toBe(2));
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('CheckInDialog — nhận thú', () => {
  it('đủ điều kiện, nhận sớm: ghi chú tính đêm theo thực tế; một chuồng đúng loại thì chọn sẵn; gửi đúng thân lệnh rồi đóng', async () => {
    const send = vi.spyOn(boardingApi, 'checkIn');
    const { user, onClose } = await open({ dosed: true });
    await fill(user, { belongings: 'Đệm, 1 gói pate', diet: 'Ăn hạt mềm' });
    const dialog = screen.getByRole('dialog', { name: 'Nhận thú' });
    const note = within(dialog).getByText(/Nhận sớm hơn ngày đặt/);
    expect(note).toHaveTextContent(/tính đêm theo thực tế/);
    expect(note).toHaveTextContent('26/10/2026');
    const kennel = within(dialog).getByLabelText('Chuồng');
    expect(kennel).toHaveValue('3');
    expect(within(kennel).getAllByRole('option').map((o) => o.textContent)).toEqual(['Chọn chuồng', 'C-03 · Chuồng chó nhỏ']);
    await submit(user);

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(send).toHaveBeenCalledWith(4, {
      kennelId: 3,
      weightKg: 6.25,
      healthCondition: 'Khỏe, hơi nhút nhát',
      belongings: 'Đệm, 1 gói pate',
      dietInstructions: 'Ăn hạt mềm',
      emergencyPhone: '0987000111',
    });
    expect(writes()).toEqual(['POST /api/boarding-bookings/4/check-in']);
    expect(await bookingOf(4)).toMatchObject({ status: 'CHECKED_IN', kennelCode: 'C-03', checkInDate: '2026-10-23' });
  });

  it('đúng ngày nhận: không có ghi chú nhận sớm; ô tùy chọn để trống thì không gửi', async () => {
    const send = vi.spyOn(boardingApi, 'checkIn');
    const { user, onClose } = await open({ id: 1, at: '2026-10-30T09:00:00+07:00' });
    await fill(user, { weight: '8' });
    expect(screen.queryByText(/Nhận sớm hơn ngày đặt/)).not.toBeInTheDocument();
    await submit(user);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(send).toHaveBeenCalledWith(1, { kennelId: 3, weightKg: 8, healthCondition: 'Khỏe, hơi nhút nhát', emergencyPhone: '0987000111' });
    expect(send.mock.calls[0][1]).not.toHaveProperty('belongings');
    expect(send.mock.calls[0][1]).not.toHaveProperty('dietInstructions');
  });

  it('CARETAKER nhận được thú, không thấy nút hủy vì hết chuồng, không phát sinh 403', async () => {
    const { user, onClose } = await open({ email: ACCOUNTS.caretaker, id: 1, at: '2026-10-30T09:00:00+07:00' });
    await fill(user);
    expect(screen.queryByRole('button', { name: 'Hủy vì hết chuồng' })).not.toBeInTheDocument();
    await submit(user);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect((await bookingOf(1)).status).toBe('CHECKED_IN');
    expect(forbidden()).toEqual([]);
  });

  it('nhiều chuồng đúng loại: chỉ liệt kê chuồng trống đúng loại và bắt chọn', async () => {
    await signIn(ACCOUNTS.desk);
    await bookingOf(4);
    await (await otherStaff(ACCOUNTS.manager)).post('/api/kennels', { kennelTypeId: 5, code: 'C-09' });
    dosedBim();
    const booking = await bookingOf(4);
    clearRequests();
    const { user } = renderDialog(<CheckInDialog booking={booking} onClose={() => {}} />);
    await fill(user);
    const kennel = screen.getByLabelText('Chuồng');
    expect(kennel).toHaveValue('');
    expect(within(kennel).getAllByRole('option').map((o) => o.textContent)).toEqual(['Chọn chuồng', 'C-03 · Chuồng chó nhỏ', 'C-09 · Chuồng chó nhỏ']);
    await submit(user);
    expect(await screen.findByText('Vui lòng chọn chuồng')).toBeInTheDocument();
    expect(writes()).toEqual([]);
    await user.selectOptions(kennel, 'C-09 · Chuồng chó nhỏ');
    expect(screen.queryByText('Vui lòng chọn chuồng')).not.toBeInTheDocument();
  });

  it('kiểm dữ liệu từng trường trước khi gửi: cân nặng, tình trạng, SĐT khẩn', async () => {
    const { user } = await open({ dosed: true });
    await screen.findByLabelText(W);
    await submit(user);
    expect(await screen.findByText('Vui lòng nhập cân nặng')).toBeInTheDocument();
    expect(screen.getByText('Vui lòng nhập tình trạng sức khỏe')).toBeInTheDocument();
    expect(screen.getByText('Vui lòng nhập số điện thoại liên hệ khẩn')).toBeInTheDocument();

    const weight = screen.getByLabelText(W);
    for (const [typed, message] of [
      ['0', 'Cân nặng phải lớn hơn 0'],
      ['-1', 'Cân nặng phải lớn hơn 0'],
      ['6.123', 'Cân nặng tối đa 9999.99 kg và 2 chữ số thập phân'],
      ['10000', 'Cân nặng tối đa 9999.99 kg và 2 chữ số thập phân'],
      ['abc', 'Cân nặng phải là số, ví dụ 6,5'],
    ] as const) {
      await user.clear(weight);
      await user.type(weight, typed);
      await submit(user);
      expect(screen.getByText(message), typed).toBeInTheDocument();
    }
    await user.clear(weight);
    await user.type(weight, '9999,99');
    await user.type(screen.getByLabelText('Tình trạng sức khỏe'), '   ');
    const phone = screen.getByLabelText('SĐT liên hệ khẩn cấp');
    expect(phone).toHaveAttribute('maxlength', '15');
    for (const bad of ['123456789', '012345678', '012345678901', '09a7000111']) {
      await user.clear(phone);
      await user.type(phone, bad);
      await submit(user);
      expect(screen.getByText('Số điện thoại liên hệ khẩn phải bắt đầu bằng 0 và có 10–11 chữ số'), bad).toBeInTheDocument();
    }
    expect(screen.getByText('Vui lòng nhập tình trạng sức khỏe')).toBeInTheDocument();
    expect(writes()).toEqual([]);
    await user.type(screen.getByLabelText('Tình trạng sức khỏe'), 'Ổn');
    expect(screen.queryByText('Vui lòng nhập tình trạng sức khỏe')).not.toBeInTheDocument();
  });

  it('lỗi theo trường của BE (VALIDATION_FAILED): hiện dưới đúng ô', async () => {
    vi.spyOn(boardingApi, 'checkIn').mockRejectedValueOnce(
      new ApiError('VALIDATION_FAILED', 'emergencyPhone: Số điện thoại liên hệ khẩn không hợp lệ; weightKg: Cân nặng phải lớn hơn 0', 400),
    );
    const { user, onClose } = await open({ dosed: true });
    await fill(user);
    await submit(user);
    expect(await screen.findByText('Số điện thoại liên hệ khẩn không hợp lệ')).toBeInTheDocument();
    expect(screen.getByText('Cân nặng phải lớn hơn 0')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: submitName })).toBeEnabled();
  });

  it('đang gửi: nút khóa, không gửi hai lần', async () => {
    const { user } = await open({ dosed: true });
    await fill(user);
    let release: (b: Awaited<ReturnType<typeof boardingApi.checkIn>>) => void = () => {};
    const spy = vi.spyOn(boardingApi, 'checkIn').mockReturnValue(new Promise((resolve) => (release = resolve)));
    await submit(user);
    const busy = await screen.findByRole('button', { name: 'Đang xử lý…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    expect(spy).toHaveBeenCalledTimes(1);
    release(await bookingOf(4));
  });
});

describe('CheckInDialog — lỗi nghiệp vụ của BE hiện nguyên câu', () => {
  it('BR-LT-04: giờ mở cửa hết trong lúc điền form', async () => {
    const { user, onClose } = await open({ id: 1, at: '2026-10-30T09:00:00+07:00' });
    await fill(user);
    vi.setSystemTime(new Date('2026-10-30T12:30:00+07:00'));
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Chỉ nhận thú trong giờ mở cửa của chi nhánh');
    expect(onClose).not.toHaveBeenCalled();
    expect((await bookingOf(1)).status).toBe('BOOKED');
  });

  it('BR-LT-08: chuồng vừa bị người khác dùng; form làm mới danh sách chuồng', async () => {
    const { user } = await open({ dosed: true });
    await fill(user);
    await rexInStay(); // Rex vào C-03
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Chuồng không ở trạng thái trống');
    expect((await bookingOf(4)).status).toBe('BOOKED');
    // Làm mới điều kiện: không còn chuồng trống nên form biến mất, câu lỗi vẫn đọc được.
    await waitFor(() => expect(screen.queryByLabelText(W)).not.toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent('Chuồng không ở trạng thái trống');
  });

  it('BR-LT-05: thiếu mũi (điều kiện đã cũ: màn hình tưởng đủ mũi, BE vẫn thấy thiếu)', async () => {
    await signIn(ACCOUNTS.desk);
    const booking = await bookingOf(4);
    const real = await boardingApi.checkInReadiness(4);
    vi.spyOn(boardingApi, 'checkInReadiness').mockResolvedValue({ ...real, vaccineGaps: [], canCheckIn: true });
    const { user } = renderDialog(<CheckInDialog booking={booking} onClose={() => {}} />);
    await fill(user);
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Hãy tiếp nhận thú như walk-in để tiêm trước');
  });

  it('BR-LT-03: nhận sớm nhưng một đêm đã kín chỗ', async () => {
    const { user } = await open({ dosed: true });
    await fill(user);
    await fillNight24();
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Hết chỗ các đêm: 24/10');
    expect((await bookingOf(4)).status).toBe('BOOKED');
  });

  it('409 (người khác vừa nhận): hiện câu của BE, làm mới danh sách, không kẹt nút, chỉ còn Đóng', async () => {
    const { user, onClose, client } = await open({ dosed: true });
    await fill(user);
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/4/check-in', {
      kennelId: 3,
      weightKg: 6,
      healthCondition: 'Khỏe',
      emergencyPhone: '0987000111',
    });
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CHECKED_IN');
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['boarding'] }));
    expect(screen.queryByRole('button', { name: submitName })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(W)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('CheckInDialog — quyền', () => {
  it.each([
    ['BRANCH_MANAGER', ACCOUNTS.manager],
    ['VET', ACCOUNTS.vet],
  ])('%s chỉ thấy lời báo không có quyền, không hỏi điều kiện nhận thú (không 403)', async (_role, email) => {
    await open({ email });
    const dialog = await screen.findByRole('dialog', { name: 'Nhận thú' });
    expect(within(dialog).getByText(/Chỉ lễ tân và nhân viên chăm sóc/)).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(W)).not.toBeInTheDocument();
    expect(requests()).toEqual([]);
    expect(forbidden()).toEqual([]);
  });

  it('Esc đóng hộp thoại', async () => {
    const { user, onClose } = await open();
    await screen.findByRole('table', { name: 'Mũi tiêm còn thiếu' });
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
