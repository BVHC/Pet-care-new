import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { DeceasedDialog } from './DeceasedDialog';
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

// UC59 "Thú mất" (A5, BR-LT-12), đồng hồ ghim thứ Sáu 23/10/2026 09:00. Đặt chỗ #2: Cốm CHECKED_IN từ 21/10, chuồng C-01, 200.000 đ/đêm
// → kết thúc hôm nay = 2 đêm = 400.000 đ.

beforeAll(installStayMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function open(email: string = ACCOUNTS.desk) {
  await signIn(email);
  const booking = await bookingOf(2);
  clearRequests();
  const onClose = vi.fn();
  const view = renderDialog(<DeceasedDialog booking={booking} onClose={onClose} />);
  return { ...view, onClose, booking };
}

const NOTE = 'Thú lên cơn co giật lúc 8 giờ sáng';

describe('DeceasedDialog (UC59, A5, BR-LT-12)', () => {
  it('mở ra: giải thích kết thúc do thú mất, không cần PAID, Order chờ thu xử lý như thường; chưa ghi gì', async () => {
    await open();
    const dialog = await screen.findByRole('dialog', { name: 'Thú mất' });
    expect(within(dialog).getByText(/Cốm/)).toBeInTheDocument();
    expect(within(dialog).getByText(/lý do Thú mất/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Không giao thú nên không cần Order đã thanh toán/)).toBeInTheDocument();
    expect(within(dialog).getByText(/quản lý chi nhánh hủy/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Diễn biến')).toHaveValue('');
    expect(writes()).toEqual([]);
  });

  it('diễn biến bắt buộc: để trống hoặc toàn khoảng trắng thì báo ngay dưới ô, không gửi; gõ lại thì hết báo', async () => {
    const { user } = await open();
    await user.click(await screen.findByRole('button', { name: 'Xác nhận thú đã mất' }));
    expect(await screen.findByText('Vui lòng ghi rõ diễn biến')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Diễn biến'), '   ');
    await user.click(screen.getByRole('button', { name: 'Xác nhận thú đã mất' }));
    expect(screen.getByText('Vui lòng ghi rõ diễn biến')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Diễn biến'), 'x');
    expect(screen.queryByText('Vui lòng ghi rõ diễn biến')).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('thành công: gửi note đã cắt khoảng trắng, hiện Order / số đêm / tiền và nhắc đánh dấu thú đã mất; kết quả còn dù đặt chỗ đã rời khỏi lưu trú', async () => {
    const send = vi.spyOn(boardingApi, 'endDeceased');
    const { user, onClose } = await open();
    await user.type(await screen.findByLabelText('Diễn biến'), `  ${NOTE}  `);
    await user.click(screen.getByRole('button', { name: 'Xác nhận thú đã mất' }));

    const done = await screen.findByRole('status');
    expect(send).toHaveBeenCalledWith(2, { note: NOTE });
    expect(done).toHaveTextContent('Đã kết thúc lưu trú do thú mất');
    expect(done).toHaveTextContent(/Order #\d+/);
    expect(done).toHaveTextContent('2 đêm');
    expect(done).toHaveTextContent(/400\.000\s*₫/);
    expect(done).toHaveTextContent('Sau đó đánh dấu thú đã mất ở trang thú (BR-KH-05)');
    expect(onClose).not.toHaveBeenCalled();
    expect(await bookingOf(2)).toMatchObject({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED' });
    // Làm mới sau lệnh ghi không được xóa kết quả khỏi màn hình.
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('2 đêm'));
    expect(screen.queryByLabelText('Diễn biến')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('đã bắt đầu trả thú trước đó: dùng lại Order chờ thu cũ (cùng mã Order)', async () => {
    const desk = await otherStaff(ACCOUNTS.desk);
    const started = await desk.post<{ orderId: number }>('/api/boarding-bookings/2/start-checkout');
    const { user } = await open();
    await user.type(await screen.findByLabelText('Diễn biến'), NOTE);
    await user.click(screen.getByRole('button', { name: 'Xác nhận thú đã mất' }));
    expect(await screen.findByRole('status')).toHaveTextContent(`Order #${started.orderId}`);
  });

  it('BE trả lỗi theo trường (VALIDATION_FAILED): hiện dưới ô diễn biến, hộp thoại vẫn mở', async () => {
    vi.spyOn(boardingApi, 'endDeceased').mockRejectedValueOnce(new ApiError('VALIDATION_FAILED', 'note: Vui lòng ghi rõ diễn biến', 400));
    const { user, onClose } = await open();
    await user.type(await screen.findByLabelText('Diễn biến'), NOTE);
    await user.click(screen.getByRole('button', { name: 'Xác nhận thú đã mất' }));
    expect(await screen.findByText('Vui lòng ghi rõ diễn biến')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Xác nhận thú đã mất' })).toBeEnabled();
  });

  it('409 (người khác vừa kết thúc lưu trú): hiện câu của BE, làm mới, chỉ còn nút Đóng', async () => {
    const { user, onClose, client } = await open();
    await (await otherStaff(ACCOUNTS.desk)).post('/api/boarding-bookings/2/end-deceased', { note: 'Người khác ghi trước' });
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await user.type(await screen.findByLabelText('Diễn biến'), NOTE);
    await user.click(screen.getByRole('button', { name: 'Xác nhận thú đã mất' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Thú cưng không còn trong chuồng');
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['boarding'] }));
    expect(screen.queryByRole('button', { name: 'Xác nhận thú đã mất' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('đang gửi: nút khóa, không gửi hai lần', async () => {
    const { user } = await open();
    let release: (r: Awaited<ReturnType<typeof boardingApi.endDeceased>>) => void = () => {};
    const spy = vi.spyOn(boardingApi, 'endDeceased').mockReturnValue(new Promise((resolve) => (release = resolve)));
    await user.type(await screen.findByLabelText('Diễn biến'), NOTE);
    await user.click(screen.getByRole('button', { name: 'Xác nhận thú đã mất' }));
    const busy = await screen.findByRole('button', { name: 'Đang xử lý…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    expect(spy).toHaveBeenCalledTimes(1);
    release({ booking: await bookingOf(2), orderId: 1, nights: 2, amount: 400000 });
    expect(await screen.findByRole('status')).toHaveTextContent('Đã kết thúc lưu trú do thú mất');
  });

  it.each([
    ['CARETAKER', ACCOUNTS.caretaker],
    ['BRANCH_MANAGER', ACCOUNTS.manager],
    ['VET', ACCOUNTS.vet],
  ])('%s chỉ thấy lời báo không có quyền, không gọi API nào (không 403)', async (_role, email) => {
    await open(email);
    const dialog = await screen.findByRole('dialog', { name: 'Thú mất' });
    expect(within(dialog).getByText(/Chỉ lễ tân/)).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Diễn biến')).not.toBeInTheDocument();
    expect(requests()).toEqual([]);
    expect(forbidden()).toEqual([]);
  });
});
