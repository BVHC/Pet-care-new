import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { boardingApi } from '@/shared/api/boarding.api';
import { NoKennelDialog } from './NoKennelDialog';
import {
  ACCOUNTS,
  bookingOf,
  clearRequests,
  forbidden,
  installStayMock,
  otherStaff,
  renderDialog,
  requests,
  signIn,
  startDay,
  writes,
} from './stay-harness';

// UC59 "Hủy vì hết chuồng" (A2, BR-LT-08), đồng hồ ghim thứ Sáu 23/10/2026 09:00. Đặt chỗ #4: Bim BOOKED 26/10→28/10, loại chuồng #5 "Chuồng chó nhỏ"
// còn đúng một chuồng trống C-03 (kennelId 3); quản lý chuyển C-03 sang bảo trì thì "hết chuồng".

beforeAll(installStayMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function open(email: string = ACCOUNTS.desk) {
  await signIn(email);
  const booking = await bookingOf(4);
  clearRequests();
  const onClose = vi.fn();
  const view = renderDialog(<NoKennelDialog booking={booking} onClose={onClose} />);
  return { ...view, onClose, booking };
}

/** Quản lý đưa C-03 vào bảo trì: không còn chuồng trống đúng loại. */
const noRoomLeft = async () => (await otherStaff(ACCOUNTS.manager)).post('/api/kennels/3/start-maintenance');

describe('NoKennelDialog (UC59, A2)', () => {
  it('mở ra: giải thích đây là hủy do phòng khám và chỉ ghi khi bấm xác nhận', async () => {
    await open();
    const dialog = await screen.findByRole('dialog', { name: 'Hủy vì hết chuồng' });
    expect(within(dialog).getByText(/Bim/)).toBeInTheDocument();
    expect(within(dialog).getByText(/như hủy do phòng khám/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/không tính hủy muộn/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/báo khách/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/quản lý chi nhánh/i)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Xác nhận hủy' })).toBeEnabled();
    expect(writes()).toEqual([]);
  });

  it('hết chuồng: gọi cancel-no-kennel, đặt chỗ thành CANCELLED do phòng khám (không hủy muộn), đóng hộp thoại', async () => {
    const { user, onClose } = await open();
    await noRoomLeft();
    await user.click(await screen.findByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(writes()).toEqual(['POST /api/boarding-bookings/4/cancel-no-kennel']);
    expect(await bookingOf(4)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', lateCancel: false });
  });

  it('báo thành công cho cha qua onDone (CheckInDialog đóng theo)', async () => {
    await signIn(ACCOUNTS.desk);
    const booking = await bookingOf(4);
    const onClose = vi.fn();
    const onDone = vi.fn();
    const { user } = renderDialog(<NoKennelDialog booking={booking} onClose={onClose} onDone={onDone} />);
    await noRoomLeft();
    await user.click(await screen.findByRole('button', { name: 'Xác nhận hủy' }));
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(onClose).toHaveBeenCalled();
  });

  it('còn chuồng trống: hiện nguyên câu 400 BR-LT-08 của BE, đặt chỗ giữ BOOKED, không kẹt nút', async () => {
    const { user, onClose } = await open();
    await user.click(await screen.findByRole('button', { name: 'Xác nhận hủy' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Vẫn còn chuồng trống đúng loại, hãy nhận thú');
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Xác nhận hủy' })).toBeEnabled();
    expect((await bookingOf(4)).status).toBe('BOOKED');
  });

  it('409 (người khác vừa hủy): hiện câu của BE, làm mới danh sách lưu trú, chỉ còn nút Đóng', async () => {
    const { user, onClose, client } = await open();
    await noRoomLeft();
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/4/cancel-no-kennel');
    const refetch = vi.spyOn(client, 'invalidateQueries');
    await user.click(await screen.findByRole('button', { name: 'Xác nhận hủy' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái đặt chỗ lưu trú từ CANCELLED sang CANCELLED');
    expect(refetch).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['boarding'] }));
    expect(screen.queryByRole('button', { name: 'Xác nhận hủy' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('đang gửi: nút khóa và không gửi hai lần', async () => {
    const { user } = await open();
    await noRoomLeft();
    let release: (b: Awaited<ReturnType<typeof boardingApi.cancelNoKennel>>) => void = () => {};
    const spy = vi.spyOn(boardingApi, 'cancelNoKennel').mockReturnValue(new Promise((resolve) => (release = resolve)));
    await user.click(await screen.findByRole('button', { name: 'Xác nhận hủy' }));
    const busy = await screen.findByRole('button', { name: 'Đang xử lý…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    expect(spy).toHaveBeenCalledTimes(1);
    release(await bookingOf(4));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Đang xử lý…' })).not.toBeInTheDocument());
  });

  it('Quay lại đóng mà không gửi gì', async () => {
    const { user, onClose } = await open();
    await user.click(await screen.findByRole('button', { name: 'Quay lại' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(writes()).toEqual([]);
  });

  it.each([
    ['CARETAKER', ACCOUNTS.caretaker],
    ['BRANCH_MANAGER', ACCOUNTS.manager],
    ['VET', ACCOUNTS.vet],
  ])('%s chỉ thấy lời báo không có quyền, không gọi API nào (không 403)', async (_role, email) => {
    await open(email);
    const dialog = await screen.findByRole('dialog', { name: 'Hủy vì hết chuồng' });
    expect(within(dialog).getByText(/Chỉ lễ tân/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Xác nhận hủy' })).not.toBeInTheDocument();
    expect(requests()).toEqual([]);
    expect(forbidden()).toEqual([]);
  });
});
