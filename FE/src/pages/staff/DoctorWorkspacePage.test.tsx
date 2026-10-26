import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { clinicApi } from '../../shared/api/clinic.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { visitApi } from '../../shared/api/visit.api';
import { makeVisit, renderDesk, startDesk } from '../../test/visit-harness';
import { DoctorWorkspacePage } from './DoctorWorkspacePage';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// visit.mock lúc 10:40 thứ Năm 22/10/2026, VET #5 (BS. Minh Anh): hàng đợi của tôi theo thứ tự BE = 2002 Lu (lịch hẹn, đang khám), 2102 Tôm (vãng lai, đang chờ).
// Ghi bệnh án, kê đơn, tiêm chủng và hoàn tất lượt là T37 (vẫn trên clinic-db): bàn này không đưa mã lượt của API vào SoapPanel / PatientPanel.

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.useRealTimers());

const NOTICE = 'Ghi bệnh án, kê đơn, tiêm chủng và hoàn tất lượt: chưa nối API (T37)';
const queueRows = () => within(screen.getByRole('list', { name: 'Hàng đợi của tôi' })).getAllByRole('listitem');
const petsInQueue = () => queueRows().map((li) => within(li).getByTestId('visit-pet').textContent);
const rowOf = async (pet: string) => within((await screen.findByText(pet, { selector: '[data-testid="visit-pet"]' })).closest('li')!);
const exam = () => within(screen.getByRole('region', { name: 'Lượt đang chọn' }));

async function renderExamRoom() {
  await startDesk('doctor@store1.vn');
  return renderDesk(<DoctorWorkspacePage />);
}

describe('exam room — hàng đợi của tôi (UC46)', () => {
  it('mở sẵn ở lượt đang khám; khung bệnh nhân nêu thú, chủ nuôi, nhóm dịch vụ, trạng thái, người phụ trách và mã lượt', async () => {
    await renderExamRoom();

    expect(await screen.findByRole('heading', { level: 2, name: 'Lu' })).toBeInTheDocument();
    const panel = exam();
    expect(panel.getByText('Lê Hoàng Long')).toBeInTheDocument();
    expect(panel.getByText('Khám/Tiêm')).toBeInTheDocument();
    expect(panel.getByText('Đang khám')).toBeInTheDocument();
    expect(panel.getByText('BS. Minh Anh')).toBeInTheDocument();
    expect(panel.getByText('TN-2610-00005')).toBeInTheDocument();
  });

  it('chỉ có lượt gán cho mình, theo đúng thứ tự mảng BE trả, lượt đang khám và đang chờ chung một danh sách kèm trạng thái', async () => {
    await renderExamRoom();

    await waitFor(() => expect(petsInQueue()).toEqual(['Lu', 'Tôm']));
    expect((await rowOf('Lu')).getByText('Đang khám')).toBeInTheDocument();
    expect((await rowOf('Tôm')).getByText('Đang chờ')).toBeInTheDocument();
    expect(screen.getByText(/1 lượt đang chờ bạn/)).toBeInTheDocument();
  });

  it('không tự sắp lại: cấp cứu đứng sau vãng lai nếu BE trả như thế; ca cấp cứu có nhãn', async () => {
    await startDesk('doctor@store1.vn');
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([
      makeVisit(51, { petName: 'Vãng lai', assigneeId: 5, assigneeName: 'BS. Minh Anh', queueSortKey: '2026-10-22T05:00:00Z' }),
      makeVisit(52, { petName: 'Cấp cứu', priorityClass: 'EMERGENCY', assigneeId: 5, assigneeName: 'BS. Minh Anh', queueSortKey: '2026-10-22T01:00:00Z' }),
    ]);
    renderDesk(<DoctorWorkspacePage />);

    await waitFor(() => expect(petsInQueue()).toEqual(['Vãng lai', 'Cấp cứu']));
    expect((await rowOf('Cấp cứu')).getByText('Cấp cứu', { selector: '.ws-chip' })).toBeInTheDocument();
  });

  it('hàng đợi trống: nói không còn ai chờ, khung bệnh nhân mời chọn lượt', async () => {
    await startDesk('doctor@store1.vn');
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([]);
    renderDesk(<DoctorWorkspacePage />);

    expect(await screen.findByText('Không còn ai chờ bạn')).toBeInTheDocument();
    expect(screen.getByText('Chưa chọn bệnh nhân')).toBeInTheDocument();
    expect(screen.getByText(/không có lượt nào đang chờ/)).toBeInTheDocument();
  });

  it('chọn lượt khác thì khung bệnh nhân đổi theo; phím ↓ / ↑ đi qua hàng chờ và mở từng lượt', async () => {
    const { user } = await renderExamRoom();
    await screen.findByRole('heading', { level: 2, name: 'Lu' });

    (await rowOf('Lu')).getByRole('button', { name: /Lu/ }).focus();
    await user.keyboard('{ArrowDown}');
    expect(await screen.findByRole('heading', { level: 2, name: 'Tôm' })).toBeInTheDocument();

    await user.keyboard('{ArrowUp}');
    expect(await screen.findByRole('heading', { level: 2, name: 'Lu' })).toBeInTheDocument();

    await user.click((await rowOf('Tôm')).getByRole('button', { name: /Tôm/ }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Tôm' })).toBeInTheDocument();
  });
});

describe('exam room — gọi lượt (UC46; BR-TN-05)', () => {
  it('chỉ lượt đang chờ có nút "Gọi lượt"; gọi xong lượt thành đang khám và gọi đúng lượt qua API', async () => {
    const call = vi.spyOn(visitApi, 'call');
    const { user } = await renderExamRoom();

    expect((await rowOf('Lu')).queryByRole('button', { name: 'Gọi lượt' })).not.toBeInTheDocument();
    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Gọi lượt' }));

    await waitFor(() => expect(call).toHaveBeenCalledWith(2102));
    await waitFor(() => expect(within(queueRows()[1]).getByText('Đang khám')).toBeInTheDocument());
    expect(await screen.findByRole('heading', { level: 2, name: 'Tôm' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gọi lượt' })).not.toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Tôm'));
    expect(screen.getByText(/không có lượt nào đang chờ/)).toBeInTheDocument();
  });

  it('nút lớn ở khung bệnh nhân cũng gọi lượt đang chọn', async () => {
    const call = vi.spyOn(visitApi, 'call');
    const { user } = await renderExamRoom();

    await user.click((await rowOf('Tôm')).getByRole('button', { name: /Tôm/ }));
    await user.click(await within(await screen.findByRole('region', { name: 'Lượt đang chọn' })).findByRole('button', { name: 'Gọi Tôm vào khám' }));

    await waitFor(() => expect(call).toHaveBeenCalledWith(2102));
  });

  it('lượt vừa được gán cho người khác (403 ACCESS_DENIED_SCOPE_MISMATCH): báo không được gán cho mình', async () => {
    vi.spyOn(visitApi, 'call').mockRejectedValue(new ApiError('ACCESS_DENIED_SCOPE_MISMATCH', 'Lượt không gán cho người gọi (BR-TN-05)', 403));
    const { user } = await renderExamRoom();

    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Gọi lượt' }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Lượt này không được gán cho bạn nên bạn không gọi được.'));
  });

  it('lượt đã được gọi hoặc hủy ở nơi khác (409): báo đã đổi và tải lại hàng đợi', async () => {
    vi.spyOn(visitApi, 'call').mockRejectedValue(new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ CANCELLED sang IN_PROGRESS', 409));
    const list = vi.spyOn(visitApi, 'getMyQueue');
    const { user } = await renderExamRoom();
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));

    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Gọi lượt' }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('Đã tải lại hàng đợi')));
    await waitFor(() => expect(list.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('chỉ gọi được lượt gán cho mình (BR-TN-05): lượt chờ của người khác lọt vào danh sách thì không có nút "Gọi lượt"', async () => {
    await startDesk('doctor@store1.vn');
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([
      makeVisit(81, { petName: 'Của tôi', assigneeId: 5, assigneeName: 'BS. Minh Anh' }),
      makeVisit(82, { petName: 'Của người khác', assigneeId: 9, assigneeName: 'BS. Khác' }),
      makeVisit(83, { petName: 'Chưa gán' }),
    ]);
    renderDesk(<DoctorWorkspacePage />);

    expect((await rowOf('Của tôi')).getByRole('button', { name: 'Gọi lượt' })).toBeInTheDocument();
    expect((await rowOf('Của người khác')).queryByRole('button', { name: 'Gọi lượt' })).not.toBeInTheDocument();
    expect((await rowOf('Chưa gán')).queryByRole('button', { name: 'Gọi lượt' })).not.toBeInTheDocument();
  });

  it('VET đánh dấu ca đang chờ là cấp cứu (UC47): lượt lên đầu hàng đợi và nút biến mất', async () => {
    const mark = vi.spyOn(visitApi, 'markEmergency');
    const { user } = await renderExamRoom();

    await user.click((await rowOf('Tôm')).getByRole('button', { name: /Tôm/ }));
    await user.click(await within(await screen.findByRole('region', { name: 'Lượt đang chọn' })).findByRole('button', { name: 'Cấp cứu' }));

    await waitFor(() => expect(mark).toHaveBeenCalledWith(2102));
    await waitFor(() => expect(petsInQueue()).toEqual(['Tôm', 'Lu']));
    await waitFor(() => expect(exam().queryByRole('button', { name: 'Cấp cứu' })).not.toBeInTheDocument());
  });
});

describe('exam room — ghi bệnh án chờ T37', () => {
  it('không đưa mã lượt của API vào bệnh án trên clinic-db: nói rõ chưa nối API, không có chữ lỗi, không gọi clinic API', async () => {
    const detail = vi.spyOn(clinicApi, 'getVisitDetail');
    const { user } = await renderExamRoom();

    await screen.findByRole('heading', { level: 2, name: 'Lu' });
    await user.click((await rowOf('Tôm')).getByRole('button', { name: /Tôm/ }));
    await screen.findByRole('heading', { level: 2, name: 'Tôm' });

    expect(exam().getByText(NOTICE)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/Không tải được|không tìm thấy|Không thực hiện được/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Hoàn tất/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Chẩn đoán/)).not.toBeInTheDocument();
    expect(detail).not.toHaveBeenCalled();
  });
});

describe('exam room — không gửi request vai trò này không được làm (VISIT_ROLES)', () => {
  it('bác sĩ chỉ hỏi hàng đợi của tôi: không hỏi lịch hẹn chờ check-in (403) và không hỏi hàng đợi chi nhánh', async () => {
    const arrivals = vi.spyOn(visitApi, 'listArrivals');
    const branchQueue = vi.spyOn(visitApi, 'listQueue');
    const myQueue = vi.spyOn(visitApi, 'getMyQueue');
    await renderExamRoom();
    await screen.findByRole('heading', { level: 2, name: 'Lu' });

    expect(myQueue).toHaveBeenCalled();
    expect(arrivals).not.toHaveBeenCalled();
    expect(branchQueue).not.toHaveBeenCalled();
  });
});
