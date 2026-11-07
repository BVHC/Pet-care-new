import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { homevisitApi } from '../../../shared/api/homevisit.api';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { visitApi } from '../../../shared/api/visit.api';
import { renderDesk, signInAs, startDesk } from '../../../test/visit-harness';
import { HomeVisitQueue } from './HomeVisitQueue';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// homevisit.mock lúc 10:40 thứ Năm 22/10/2026, chi nhánh 1 (nạp lười ở lần gọi đầu): hôm nay Tôm 16:30 BOOKED (phí 100.000 ₫); 24/10 Bim 09:30 BOOKED; 18/10 một ca NO_SHOW; 16/10 một ca CANCELLED.
// Tôm đang có lượt 2102 chưa kết thúc ở visit.mock nên "Bắt đầu" lần đầu bị BR-TN-01 cho tới khi lễ tân hủy lượt đó (đúng luật, cũng là cách thử).

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.useRealTimers());

async function renderQueue(email = 'doctor@store1.vn') {
  await startDesk(email);
  const onStarted = vi.fn();
  const view = renderDesk(<HomeVisitQueue onStarted={onStarted} />);
  return { ...view, onStarted };
}
const rowOf = async (pet: string) => within((await screen.findByText(pet)).closest('li')!);
const pickDate = (date: string) => fireEvent.change(screen.getByLabelText('Ngày khám tại nhà'), { target: { value: date } });

describe('HomeVisitQueue — bác sĩ (UC51)', () => {
  it('mặc định là lịch hôm nay của chi nhánh: giờ, thú, dịch vụ, địa chỉ, mã và phí; lịch BOOKED hôm nay có đủ hai nút', async () => {
    await renderQueue();
    const tom = await rowOf('Tôm');
    expect(tom.getByText('16:30')).toBeInTheDocument();
    expect(tom.getByText('Đã đặt')).toBeInTheDocument();
    expect(tom.getByText(/12 Trần Thái Tông/)).toBeInTheDocument();
    expect(tom.getByText(/phí tại nhà 100\.000/)).toBeInTheDocument();
    expect(tom.getByRole('button', { name: 'Bắt đầu khám tại nhà: Tôm' })).toBeEnabled();
    expect(tom.getByRole('button', { name: 'Không gặp được khách: Tôm' })).toBeEnabled();
    expect(screen.getByText(/1 chờ khám/)).toBeInTheDocument();
  });

  it('bắt đầu khi thú còn lượt mở: BE trả 400 BR-TN-01, câu báo hiện ngay trong khung; lễ tân hủy lượt 2102 rồi bắt đầu lại thì thành công và mở đúng lượt mới', async () => {
    const { user, onStarted } = await renderQueue();
    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Bắt đầu khám tại nhà: Tôm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/lượt khám chưa kết thúc|đang có lượt/i);
    expect(onStarted).not.toHaveBeenCalled();

    await signInAs('reception@store1.vn');
    await visitApi.cancel(2102, {});
    await signInAs('doctor@store1.vn');
    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Bắt đầu khám tại nhà: Tôm' }));

    await waitFor(() => expect(onStarted).toHaveBeenCalledTimes(1));
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Đã bắt đầu khám tại nhà cho Tôm'));
    const tom = await rowOf('Tôm');
    await waitFor(() => expect(tom.getByText('Đang khám')).toBeInTheDocument());
    expect(tom.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('"Không gặp được khách" cần ghi chú; ghi xong lịch chuyển sang trạng thái không gặp được và hết nút', async () => {
    const { user } = await renderQueue();
    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Không gặp được khách: Tôm' }));
    const dialog = await screen.findByRole('dialog', { name: 'Không gặp được khách' });
    await user.click(within(dialog).getByRole('button', { name: 'Ghi nhận' }));
    expect(await within(dialog).findByText(/Nhập ghi chú/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Ghi chú'), 'Gọi ba lần không ai nghe máy');
    await user.click(within(dialog).getByRole('button', { name: 'Ghi nhận' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const tom = await rowOf('Tôm');
    await waitFor(() => expect(tom.getByText('Không gặp được khách', { selector: '.ws-chip' })).toBeInTheDocument());
    expect(tom.queryByRole('button')).not.toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('không gặp được khách của Tôm'));
  });

  it('lịch đã được xử lý ở máy khác: ghi "không gặp được" nhận 409 và hộp thoại giữ câu báo của BE', async () => {
    const { user } = await renderQueue();
    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Không gặp được khách: Tôm' }));
    const dialog = await screen.findByRole('dialog', { name: 'Không gặp được khách' });
    await user.type(within(dialog).getByLabelText('Ghi chú'), 'Không ai ở nhà');
    const mine = await homevisitApi.list({ date: '2026-10-22' });
    await homevisitApi.markUnreachable(mine.content[0].appointmentId, { note: 'Máy khác ghi trước' });
    await user.click(within(dialog).getByRole('button', { name: 'Ghi nhận' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/BOOKED|NO_SHOW|trạng thái/i);
  });

  it('chọn ngày khác: lịch ngày sau chỉ xem, nút tắt và ghi chú chỉ làm được đúng ngày hẹn; ca đã xong không có nút', async () => {
    await renderQueue();
    await rowOf('Tôm');
    pickDate('2026-10-24');
    const bim = await rowOf('Bim');
    expect(bim.getByRole('button', { name: 'Bắt đầu khám tại nhà: Bim' })).toBeDisabled();
    expect(bim.getByRole('button', { name: 'Không gặp được khách: Bim' })).toBeDisabled();
    expect(bim.getByText('Chỉ bắt đầu hoặc ghi nhận được đúng ngày hẹn.')).toBeInTheDocument();

    pickDate('2026-10-18');
    const noShow = await screen.findByText('Không gặp được khách', { selector: '.ws-chip' });
    expect(within(noShow.closest('li')!).queryByRole('button')).not.toBeInTheDocument();
  });

  it('ngày không có lịch: báo trống', async () => {
    await renderQueue();
    await rowOf('Tôm');
    pickDate('2026-11-30');
    expect(await screen.findByText('Không có lịch khám tại nhà')).toBeInTheDocument();
  });
});

describe('HomeVisitQueue — vai trò không phải bác sĩ chỉ xem', () => {
  it('lễ tân xem được danh sách nhưng không có nút bắt đầu hay ghi không gặp được', async () => {
    await renderQueue('reception@store1.vn');
    await rowOf('Tôm');
    expect(screen.queryByRole('button', { name: /Bắt đầu khám tại nhà/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Không gặp được khách/ })).not.toBeInTheDocument();
  });
});
