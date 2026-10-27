import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import type userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, describe, expect, it, vi, type MockInstance } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { salesApi } from '../../shared/api/sales.api';
import { visitApi } from '../../shared/api/visit.api';
import { makeVisit, renderDesk, startDesk } from '../../test/visit-harness';
import { DoctorWorkspacePage } from './DoctorWorkspacePage';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// visit.mock lúc 10:40 thứ Năm 22/10/2026, VET #5 (BS. Minh Anh): hàng đợi của tôi theo thứ tự BE = 2002 Lu (lịch hẹn, đang khám), 2102 Tôm (vãng lai, đang chờ).
// Đã khám hôm nay: lượt 61 Mochi (hoàn tất, bệnh án đã khóa, có một bản bổ sung). Bệnh án, cân nặng, tiêm chủng, hoàn tất lượt chạy trên visit-clinical.mock (T37, docs/testcases/T37-fe.md).

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.useRealTimers());

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

const FIELDS = ['Khám lâm sàng', 'Chẩn đoán', 'Hướng điều trị', 'Hẹn tái khám', 'Ghi chú nội bộ'];
const status = () => exam().getByRole('status', { name: 'Trạng thái bệnh án' });
const labelOf = (name: string) => exam().findByLabelText(name);
const NO_AUTOSAVE_WAIT_MS = 1700;

describe('exam room — ghi bệnh án (#12, #13, UC48; BR-KB-01)', () => {
  it('lượt đang khám mở sẵn form bệnh án đúng 5 trường của hợp đồng (khám lâm sàng, chẩn đoán, hướng điều trị, hẹn tái khám, ghi chú nội bộ), chưa có thay đổi', async () => {
    await renderExamRoom();

    await screen.findByRole('heading', { level: 2, name: 'Lu' });
    for (const label of FIELDS) expect(await labelOf(label)).not.toHaveAttribute('readonly');
    expect(status()).toHaveTextContent('Chưa có thay đổi');
    expect(exam().queryByText(/chưa nối API \(T37\)/)).not.toBeInTheDocument();
    expect(exam().queryByText(/chưa nối API \(T46\)/)).not.toBeInTheDocument();
    expect(exam().getByRole('region', { name: 'Đơn thuốc' })).toBeInTheDocument();
    expect(exam().getByText('Khách không xem được ghi chú này.')).toBeInTheDocument();
  });

  it('lượt đang chờ chưa có form: mời gọi lượt trước; gọi xong thì form hiện ra cho đúng lượt đó', async () => {
    const read = vi.spyOn(visitApi, 'getMedicalRecord');
    const { user } = await renderExamRoom();
    await user.click((await rowOf('Tôm')).getByRole('button', { name: /Tôm/ }));
    await screen.findByRole('heading', { level: 2, name: 'Tôm' });

    expect(exam().queryByLabelText('Chẩn đoán')).not.toBeInTheDocument();
    expect(exam().getByText(/Gọi lượt để ghi bệnh án/)).toBeInTheDocument();
    expect(read).not.toHaveBeenCalledWith(2102);

    await user.click(exam().getByRole('button', { name: 'Gọi Tôm vào khám' }));
    expect(await labelOf('Chẩn đoán')).toBeInTheDocument();
    expect(read).toHaveBeenCalledWith(2102);
  });

  it('KHÔNG tự lưu: gõ xong chờ quá 1,5 giây vẫn chưa gọi PUT (mỗi lần lưu có thay đổi là một dòng audit, ADR-0066); bấm "Lưu" mới ghi đủ 5 trường, chuỗi trống thành null', async () => {
    const save = vi.spyOn(visitApi, 'saveMedicalRecord');
    const { user } = await renderExamRoom();

    await user.type(await labelOf('Chẩn đoán'), 'Viêm da dị ứng');
    expect(status()).toHaveTextContent('Có thay đổi chưa lưu');
    await new Promise((resolve) => setTimeout(resolve, NO_AUTOSAVE_WAIT_MS));
    expect(save).not.toHaveBeenCalled();

    await user.click(exam().getByRole('button', { name: /^Lưu/ }));
    await waitFor(() => expect(save).toHaveBeenCalledWith(2002, { examination: null, diagnosis: 'Viêm da dị ứng', treatmentPlan: null, internalNote: null, followUpDate: null }));
    await waitFor(() => expect(status()).toHaveTextContent(/Đã lưu lúc/));
    expect((await visitApi.getMedicalRecord(2002)).diagnosis).toBe('Viêm da dị ứng');
  });

  it('Ctrl+S lưu ngay khi đang gõ; không có thay đổi thì nút Lưu tắt', async () => {
    const save = vi.spyOn(visitApi, 'saveMedicalRecord');
    const { user } = await renderExamRoom();
    expect(await exam().findByRole('button', { name: /^Lưu/ })).toBeDisabled();

    await user.type(await labelOf('Hướng điều trị'), 'Tắm dầu gội y tế');
    await user.keyboard('{Control>}s{/Control}');

    await waitFor(() => expect(save).toHaveBeenCalledWith(2002, expect.objectContaining({ treatmentPlan: 'Tắm dầu gội y tế' })));
    await waitFor(() => expect(exam().getByRole('button', { name: /^Lưu/ })).toBeDisabled());
  });

  it('hẹn tái khám sai (BR-KB-06): BE từ chối 400, câu báo hiện ngay trong form, vẫn còn thay đổi chưa lưu', async () => {
    const { user } = await renderExamRoom();

    fireEvent.change(await labelOf('Hẹn tái khám'), { target: { value: '2026-10-22' } });
    await user.click(exam().getByRole('button', { name: /^Lưu/ }));

    expect(await exam().findByRole('alert')).toHaveTextContent('Ngày tái khám phải sau ngày khám và không quá 180 ngày');
    expect(status()).toHaveTextContent('Có thay đổi chưa lưu');
    expect((await visitApi.getMedicalRecord(2002)).followUpDate).toBeNull();
  });

  it('hẹn tái khám hợp lệ được lưu; ô ngày chỉ cho chọn từ ngày sau ngày khám tới tối đa 180 ngày', async () => {
    const { user } = await renderExamRoom();

    const date = await labelOf('Hẹn tái khám');
    expect(date).toHaveAttribute('min', '2026-10-23');
    expect(date).toHaveAttribute('max', '2027-04-20');
    fireEvent.change(date, { target: { value: '2026-11-05' } });
    await user.click(exam().getByRole('button', { name: /^Lưu/ }));

    await waitFor(() => expect(status()).toHaveTextContent(/Đã lưu lúc/));
    expect((await visitApi.getMedicalRecord(2002)).followUpDate).toBe('2026-11-05');
  });
});

describe('exam room — hoàn tất lượt (#10, UC48; BR-KB-02)', () => {
  it('thiếu chẩn đoán: FE KHÔNG tự chặn mà hỏi BE, hiện nguyên câu BE trả (bỏ mã rule) ngay trong form và đưa con trỏ về ô Chẩn đoán; lượt vẫn đang khám', async () => {
    const complete = vi.spyOn(visitApi, 'complete');
    const { user } = await renderExamRoom();

    await user.click(await exam().findByRole('button', { name: /Hoàn tất/ }));

    expect(await exam().findByRole('alert')).toHaveTextContent('Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất');
    expect(exam().getByRole('alert')).not.toHaveTextContent('BR-KB-02');
    expect(complete).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(exam().getByLabelText('Chẩn đoán')).toHaveFocus());
    expect((await visitApi.getVisit(2002)).status).toBe('IN_PROGRESS');
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('có chẩn đoán: lưu bệnh án rồi mới hoàn tất; lượt COMPLETED, Order chờ thu, chuyển sang lượt kế tiếp của hàng chờ', async () => {
    const save = vi.spyOn(visitApi, 'saveMedicalRecord');
    const complete = vi.spyOn(visitApi, 'complete');
    const { user } = await renderExamRoom();
    const orderId = (await visitApi.getVisit(2002)).orderId;

    await user.type(await labelOf('Chẩn đoán'), 'Viêm da dị ứng');
    await user.click(exam().getByRole('button', { name: /Hoàn tất/ }));

    await waitFor(() => expect(complete).toHaveBeenCalledWith(2002));
    expect(save.mock.invocationCallOrder[0]).toBeLessThan(complete.mock.invocationCallOrder[0]);
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Lu')));
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('thu ngân'));
    expect((await visitApi.getVisit(2002)).status).toBe('COMPLETED');
    expect((await salesApi.getOrder(orderId)).status).toBe('PENDING');
    expect(await screen.findByRole('heading', { level: 2, name: 'Tôm' })).toBeInTheDocument();
    await waitFor(() => expect(petsInQueue()).toEqual(['Tôm']));
  });

  it('Ctrl+Enter cũng hoàn tất lượt đang khám', async () => {
    const complete = vi.spyOn(visitApi, 'complete');
    const { user } = await renderExamRoom();

    await user.type(await labelOf('Chẩn đoán'), 'Khỏe');
    await user.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => expect(complete).toHaveBeenCalledWith(2002));
  });

  it('lưu trước khi hoàn tất bị BE từ chối (BR-KB-06): không gọi hoàn tất, câu báo hiện trong form', async () => {
    const complete = vi.spyOn(visitApi, 'complete');
    const { user } = await renderExamRoom();

    await user.type(await labelOf('Chẩn đoán'), 'Viêm da');
    fireEvent.change(exam().getByLabelText('Hẹn tái khám'), { target: { value: '2026-10-22' } });
    await user.click(exam().getByRole('button', { name: /Hoàn tất/ }));

    expect(await exam().findByRole('alert')).toHaveTextContent('Ngày tái khám phải sau ngày khám');
    expect(complete).not.toHaveBeenCalled();
  });

  it('BE trả 409 khi hoàn tất (lượt vừa hoàn tất ở nơi khác): câu báo theo thao tác, không phải "đã tải lại hàng đợi"', async () => {
    vi.spyOn(visitApi, 'complete').mockRejectedValue(new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang COMPLETED', 409));
    const { user } = await renderExamRoom();

    await user.type(await labelOf('Chẩn đoán'), 'Khỏe');
    await user.click(exam().getByRole('button', { name: /Hoàn tất/ }));

    expect(await exam().findByRole('alert')).toHaveTextContent('Lượt đã được hoàn tất rồi.');
  });
});

describe('exam room — đã khám hôm nay, bệnh án đã khóa và bản bổ sung (#12, #15, UC53; BR-KB-01)', () => {
  const todayList = () => within(screen.getByRole('list', { name: 'Đã khám hôm nay' })).getAllByRole('listitem');
  const openCompleted = async (user: ReturnType<typeof userEvent.setup>, pet = 'Mochi') => {
    await waitFor(() => expect(todayList().length).toBeGreaterThan(0));
    await user.click(within(todayList().find((li) => li.textContent?.includes(pet))!).getByRole('button', { name: new RegExp(pet) }));
    await screen.findByRole('heading', { level: 2, name: pet });
  };

  it('hàng chờ có thêm danh sách "Đã khám hôm nay" (lượt Khám/Tiêm đã hoàn tất trong ngày của cả chi nhánh): mở ra là bệnh án đã khóa, chỉ đọc', async () => {
    const { user } = await renderExamRoom();
    await openCompleted(user);

    for (const label of FIELDS) expect(await labelOf(label)).toHaveAttribute('readonly');
    expect(await exam().findByText(/Bệnh án đã khóa/)).toBeInTheDocument();
    expect(exam().queryByRole('button', { name: /^Lưu/ })).not.toBeInTheDocument();
    expect(exam().queryByRole('button', { name: /Hoàn tất/ })).not.toBeInTheDocument();
    expect(exam().queryByRole('button', { name: 'Gọi Mochi vào khám' })).not.toBeInTheDocument();
    expect(await labelOf('Chẩn đoán')).toHaveValue('Viêm đường hô hấp trên');
  });

  it('hiện bản bổ sung đã có theo thứ tự ghi kèm người ghi; thêm bản bổ sung (mặc định khách xem được) và bản nội bộ', async () => {
    const add = vi.spyOn(visitApi, 'addAddendum');
    const { user } = await renderExamRoom();
    await openCompleted(user);

    const list = await exam().findByRole('list', { name: 'Bản bổ sung' });
    expect(within(list).getByText('Kết quả xét nghiệm nhanh về sau: FeLV/FIV âm tính')).toBeInTheDocument();
    expect(within(list).getByText(/BS\. Minh Anh/)).toBeInTheDocument();

    const content = exam().getByLabelText('Nội dung bổ sung');
    const submit = exam().getByRole('button', { name: 'Thêm bổ sung' });
    expect(submit).toBeDisabled();
    await user.type(content, '  Kết quả máu về sau: bạch cầu tăng ');
    await user.click(submit);
    await waitFor(() => expect(add).toHaveBeenCalledWith(61, { content: 'Kết quả máu về sau: bạch cầu tăng', isInternal: false }));
    expect(await within(await exam().findByRole('list', { name: 'Bản bổ sung' })).findByText('Kết quả máu về sau: bạch cầu tăng')).toBeInTheDocument();
    expect(exam().getByLabelText('Nội dung bổ sung')).toHaveValue('');

    await user.type(exam().getByLabelText('Nội dung bổ sung'), 'Chủ khó tính');
    await user.click(exam().getByLabelText('Chỉ nhân viên xem'));
    await user.click(exam().getByRole('button', { name: 'Thêm bổ sung' }));
    await waitFor(() => expect(add).toHaveBeenLastCalledWith(61, { content: 'Chủ khó tính', isInternal: true }));
    const internal = (await within(await exam().findByRole('list', { name: 'Bản bổ sung' })).findByText('Chủ khó tính')).closest('li')!;
    expect(within(internal).getByText('Nội bộ')).toBeInTheDocument();
  });

  it('lỗi từ BE khi thêm bản bổ sung hiện trong form (không mất nội dung đã gõ)', async () => {
    vi.spyOn(visitApi, 'addAddendum').mockRejectedValue(new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang COMPLETED', 409));
    const { user } = await renderExamRoom();
    await openCompleted(user);

    await user.type(await exam().findByLabelText('Nội dung bổ sung'), 'Ghi chú');
    await user.click(exam().getByRole('button', { name: 'Thêm bổ sung' }));

    expect(await exam().findByRole('alert')).toHaveTextContent('sửa trực tiếp bệnh án');
    expect(exam().getByLabelText('Nội dung bổ sung')).toHaveValue('Ghi chú');
  });
});

describe('exam room — cân nặng khi khám (#14; BR-KH-04)', () => {
  it('ghi cân: gọi đúng đối số, báo thành công, xóa ô nhập, cân nặng hiện tại của thú đổi theo', async () => {
    const weigh = vi.spyOn(visitApi, 'recordWeight');
    const { user } = await renderExamRoom();

    expect(await exam().findByText('Hiện tại 18.3 kg')).toBeInTheDocument();
    const input = await exam().findByLabelText('Cân nặng (kg)');
    await user.type(input, '18.4');
    await user.click(exam().getByRole('button', { name: 'Ghi cân' }));

    await waitFor(() => expect(weigh).toHaveBeenCalledWith(2002, { weightKg: 18.4 }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('18.4')));
    await waitFor(() => expect(input).toHaveValue(null));
    expect(await exam().findByText('Hiện tại 18.4 kg')).toBeInTheDocument();
  });

  it('cân nặng không hợp lệ: BE từ chối (BR-KH-04), câu báo hiện ngay trong form và giữ giá trị đã gõ; ô trống thì chưa cho bấm', async () => {
    const { user } = await renderExamRoom();

    expect(await exam().findByRole('button', { name: 'Ghi cân' })).toBeDisabled();
    await user.type(exam().getByLabelText('Cân nặng (kg)'), '0');
    await user.click(exam().getByRole('button', { name: 'Ghi cân' }));

    expect(await exam().findByRole('alert')).toHaveTextContent('Cân nặng phải lớn hơn 0');
    expect(exam().getByLabelText('Cân nặng (kg)')).toHaveValue(0);
  });
});

describe('exam room — tiêm chủng trong lượt khám (#18–20, UC49; BR-KB-04)', () => {
  const FIVE = { vaccineTypeId: 2, protocolId: 1, productId: 6 };
  const section = async () => within(await exam().findByRole('region', { name: 'Tiêm chủng' }));
  const lines = async () => within(await exam().findByRole('list', { name: 'Chỉ định trong lượt' }));
  /** Dựng sẵn mũi tiêm bằng API (đăng nhập VET) rồi mở màn hình. */
  async function renderAfter(setup: () => Promise<unknown>) {
    await startDesk('doctor@store1.vn');
    await setup();
    return renderDesk(<DoctorWorkspacePage />);
  }

  it('chọn loại vaccine → mũi gợi ý và sản phẩm còn tồn được chọn sẵn → ghi mũi: đúng đối số, mũi hiện trong lượt, dòng vaccine vào Chỉ định, gợi ý chuyển sang mũi kế tiếp', async () => {
    const record = vi.spyOn(visitApi, 'recordVaccination');
    const { user } = await renderExamRoom();
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');
    expect(await vaccination.findByLabelText('Mũi theo phác đồ')).toHaveDisplayValue(/Mũi 1 .*gợi ý/);
    expect(vaccination.getByLabelText('Sản phẩm vaccine')).toHaveDisplayValue('Vaccine 5 bệnh Vanguard (còn 4 liều)');
    await user.click(vaccination.getByRole('button', { name: 'Ghi mũi tiêm' }));

    await waitFor(() => expect(record).toHaveBeenCalledWith(2002, FIVE));
    expect(within(await vaccination.findByRole('list', { name: 'Mũi tiêm trong lượt' })).getByText(/Mũi 1 · 5 bệnh/)).toBeInTheDocument();
    expect(vaccination.getByRole('list', { name: 'Mũi tiêm trong lượt' })).not.toHaveTextContent(/lô |hạn /); // BE chưa điền lô / hạn (null, D046): màn ẩn chứ không in "lô null"
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Đã ghi mũi 1 5 bệnh'));
    await waitFor(async () => expect((await lines()).getByText('Vaccine 5 bệnh Vanguard')).toBeInTheDocument());
    expect((await lines()).getByText('Mũi tiêm')).toBeInTheDocument();
    await waitFor(() => expect(vaccination.getByLabelText('Mũi theo phác đồ')).toHaveDisplayValue(/Mũi 2 .*gợi ý/));
    expect(vaccination.getByLabelText('Sản phẩm vaccine')).toHaveDisplayValue('Vaccine 5 bệnh Vanguard (còn 3 liều)');
  });

  it('VET chọn mũi khác với gợi ý (đã tiêm ở nơi khác, hỏi chủ rồi quyết định): gửi đúng mũi đã chọn', async () => {
    const record = vi.spyOn(visitApi, 'recordVaccination');
    const { user } = await renderExamRoom();
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');
    await user.selectOptions(await vaccination.findByLabelText('Mũi theo phác đồ'), 'Mũi 2 (cách 21 ngày, từ 9 tuần tuổi)');
    await user.click(vaccination.getByRole('button', { name: 'Ghi mũi tiêm' }));

    await waitFor(() => expect(record).toHaveBeenCalledWith(2002, { ...FIVE, protocolId: 2 }));
  });

  it('xóa mũi ghi nhầm khi lượt còn đang khám: mũi và dòng vaccine biến mất, kho được hoàn', async () => {
    const remove = vi.spyOn(visitApi, 'deleteVaccination');
    const { user } = await renderAfter(() => visitApi.recordVaccination(2002, FIVE));
    const vaccination = await section();
    expect((await apiClient.get('/api/inventory/6')).data.data.availableQuantity).toBe(3);

    await user.click(await vaccination.findByRole('button', { name: 'Xóa mũi 1 5 bệnh' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith(2002, expect.any(Number)));
    expect(await vaccination.findByText('Chưa ghi mũi tiêm nào trong lượt này.')).toBeInTheDocument();
    await waitFor(async () => expect((await lines()).queryByText('Vaccine 5 bệnh Vanguard')).not.toBeInTheDocument());
    expect((await apiClient.get('/api/inventory/6')).data.data.availableQuantity).toBe(4);
  });

  /** Đổi người ghi mọi mũi trong dữ liệu mock sang VET #7: như lượt đã gán lại cho người mở màn hình sau khi VET cũ ghi mũi. */
  const recordedByAnotherVet = () => {
    const state = JSON.parse(localStorage.getItem('petcare-mock-clinical')!);
    for (const dose of state.vaccinations) dose.recordedBy = 7;
    localStorage.setItem('petcare-mock-clinical', JSON.stringify(state));
  };

  it('người phụ trách hiện tại xóa được mũi do VET KHÁC ghi (lượt đã gán lại, ADR-0075): nút Xóa theo quyền tiêm chứ không theo người ghi; xóa xong mũi biến mất và kho được hoàn', async () => {
    const remove = vi.spyOn(visitApi, 'deleteVaccination');
    const { user } = await renderAfter(async () => {
      await visitApi.recordVaccination(2002, FIVE);
      recordedByAnotherVet();
    });
    const vaccination = await section();
    expect(await vaccination.findByText(/Mũi 1 · 5 bệnh/)).toBeInTheDocument();

    await user.click(await vaccination.findByRole('button', { name: 'Xóa mũi 1 5 bệnh' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith(2002, expect.any(Number)));
    expect(await vaccination.findByText('Chưa ghi mũi tiêm nào trong lượt này.')).toBeInTheDocument();
    expect((await apiClient.get('/api/inventory/6')).data.data.availableQuantity).toBe(4);
  });

  it('xóa mũi mà Care Task nhắc tái chủng còn trỏ tới: BE trả 409 CONCURRENCY_CONFLICT chung chung, màn nói đúng nguyên nhân (không phải "vừa được gán lại") và mũi vẫn còn', async () => {
    vi.spyOn(visitApi, 'deleteVaccination').mockRejectedValue(new ApiError('CONCURRENCY_CONFLICT', 'Dữ liệu vừa được thay đổi bởi thao tác khác, vui lòng thử lại', 409));
    const { user } = await renderAfter(() => visitApi.recordVaccination(2002, FIVE));
    const vaccination = await section();

    await user.click(await vaccination.findByRole('button', { name: 'Xóa mũi 1 5 bệnh' }));

    const alert = await vaccination.findByRole('alert');
    expect(alert).toHaveTextContent('Mũi này đã gắn nhắc tái chủng nên không xóa được.');
    expect(alert).not.toHaveTextContent('gán lại');
    expect(vaccination.getByText(/Mũi 1 · 5 bệnh/)).toBeInTheDocument();
  });

  it('hết phác đồ: sau mũi cuối vẫn gợi ý mũi lớn nhất (BE) kèm câu "Đã hết phác đồ, gợi ý nhắc lại mũi N" và chọn sẵn mũi đó; còn mũi kế tiếp thì không có câu này', async () => {
    const { user } = await renderAfter(() => visitApi.recordVaccination(2002, FIVE));
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');
    expect(await vaccination.findByText(/Mũi gần nhất tại hệ thống: mũi 1 /)).toBeInTheDocument();
    expect(vaccination.queryByText(/Đã hết phác đồ/)).not.toBeInTheDocument();
    await waitFor(() => expect(vaccination.getByLabelText('Mũi theo phác đồ')).toHaveDisplayValue(/Mũi 2 .*gợi ý/));

    await user.click(vaccination.getByRole('button', { name: 'Ghi mũi tiêm' }));

    expect(await vaccination.findByText(/Mũi gần nhất tại hệ thống: mũi 2 .*Đã hết phác đồ, gợi ý nhắc lại mũi 2\./)).toBeInTheDocument();
    expect(vaccination.getByLabelText('Mũi theo phác đồ')).toHaveDisplayValue(/Mũi 2 .*gợi ý/);
    expect(vaccination.getByRole('button', { name: 'Ghi mũi tiêm' })).toBeEnabled();
  });

  it('chưa đủ tuổi tối thiểu: cảnh báo của BE hiện bằng cảnh báo (không chặn), mũi vẫn được ghi', async () => {
    const dose = { vaccinationId: 9, visitId: 2002, branchId: 1, vaccineTypeId: 2, vaccineTypeName: '5 bệnh', protocolId: 1, doseNumber: 1, productId: 6, productName: 'Vaccine 5 bệnh Vanguard', lotNumber: null, expiryDate: null, administeredOn: '2026-10-22', nextDueDate: '2026-11-12', superseded: false, recordedBy: 5, orderLineId: 99 };
    vi.spyOn(visitApi, 'recordVaccination').mockResolvedValue({ vaccination: dose, warnings: ['UNDER_MIN_AGE'] });
    const { user } = await renderExamRoom();
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');
    await user.click(await vaccination.findByRole('button', { name: 'Ghi mũi tiêm' }));

    await waitFor(() => expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('chưa đủ tuổi tối thiểu')));
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Đã ghi mũi 1'));
  });

  it('loại vaccine hết sản phẩm còn tồn khả dụng: không cho chọn (nêu rõ lý do), nút ghi mũi tắt', async () => {
    const { user } = await renderAfter(async () => {
      for (let i = 0; i < 4; i += 1) await visitApi.recordVaccination(2002, FIVE);
    });
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');

    expect(await vaccination.findByText('Loại vaccine này hiện không còn sản phẩm nào có tồn khả dụng.')).toBeInTheDocument();
    expect(vaccination.queryByLabelText('Sản phẩm vaccine')).not.toBeInTheDocument();
    expect(vaccination.getByRole('button', { name: 'Ghi mũi tiêm' })).toBeDisabled();
  });

  it('BE từ chối ghi mũi: câu báo hiện trong mục tiêm chủng, không có mũi nào được thêm', async () => {
    vi.spyOn(visitApi, 'recordVaccination').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh (BR-KB-04)', 400));
    const { user } = await renderExamRoom();
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');
    await user.click(await vaccination.findByRole('button', { name: 'Ghi mũi tiêm' }));

    expect(await vaccination.findByRole('alert')).toHaveTextContent('Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh');
    expect(vaccination.getByText('Chưa ghi mũi tiêm nào trong lượt này.')).toBeInTheDocument();
  });

  it('lỗi khi tải gợi ý mũi tiêm (#18 trả 404, vd. loại vaccine vừa bị xóa): câu báo hiện ngay trong mục "Tiêm chủng", màn không vỡ, bệnh án vẫn dùng được và nút ghi mũi tắt', async () => {
    vi.spyOn(visitApi, 'getVaccinationOptions').mockRejectedValue(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy tài nguyên yêu cầu', 404));
    const { user } = await renderExamRoom();
    const vaccination = await section();

    await user.selectOptions(await vaccination.findByLabelText('Loại vaccine'), '5 bệnh');

    expect(await vaccination.findByText('Không tìm thấy tài nguyên yêu cầu')).toBeInTheDocument();
    expect(vaccination.getByRole('button', { name: 'Ghi mũi tiêm' })).toBeDisabled();
    expect(vaccination.queryByLabelText('Mũi theo phác đồ')).not.toBeInTheDocument();
    // Chọn lại loại khác vẫn được, và phần còn lại của bệnh án không bị ảnh hưởng.
    await user.selectOptions(vaccination.getByLabelText('Loại vaccine'), 'Dại');
    expect(vaccination.getByLabelText('Loại vaccine')).toHaveDisplayValue('Dại');
    expect(exam().getByLabelText('Chẩn đoán')).not.toHaveAttribute('readonly');
    expect(exam().getByRole('button', { name: /Hoàn tất/ })).toBeEnabled();
  });

  it('thú nuôi loài chưa có loại vaccine nào (hamster): nói rõ, không có form ghi mũi', async () => {
    const { user } = await renderExamRoom();
    await user.click((await rowOf('Tôm')).getByRole('button', { name: /Tôm/ }));
    await user.click(await exam().findByRole('button', { name: 'Gọi Tôm vào khám' }));

    expect(await (await section()).findByText('Loài này chưa có loại vaccine nào trong danh mục.')).toBeInTheDocument();
    expect((await section()).queryByLabelText('Loại vaccine')).not.toBeInTheDocument();
  });

  it('lượt đã hoàn tất: mũi tiêm chỉ để xem, không có nút xóa và không có form ghi mũi; sai sót ghi bằng bản bổ sung (BR-KB-04)', async () => {
    const { user } = await renderAfter(async () => {
      await visitApi.recordVaccination(2002, FIVE);
      await visitApi.saveMedicalRecord(2002, { diagnosis: 'Khỏe' });
      await visitApi.complete(2002);
    });
    const list = await screen.findByRole('list', { name: 'Đã khám hôm nay' });
    await user.click(within(list).getByRole('button', { name: /^Lu,/ }));
    await screen.findByRole('heading', { level: 2, name: 'Lu' });

    const vaccination = await section();
    expect(await vaccination.findByText(/Mũi 1 · 5 bệnh/)).toBeInTheDocument();
    expect(vaccination.queryByRole('button', { name: /Xóa mũi/ })).not.toBeInTheDocument();
    expect(vaccination.queryByLabelText('Loại vaccine')).not.toBeInTheDocument();
    expect(vaccination.getByText(/bản bổ sung/)).toBeInTheDocument();
  });
});

describe('exam room — kê đơn thuốc (#16–17, UC48; BR-KB-03, BR-BH-02)', () => {
  // Amoxicillin #3 (thuốc kê đơn) chi nhánh 1 tồn 700; Vitamin #4 là thuốc KHÔNG kê đơn. Màn chạy trên visit-clinical.mock, đã chép thứ tự kiểm và câu lỗi từ BE T38 (PrescriptionService).
  const AMOX = { productId: 3, quantity: 10, dosageInstructions: 'Ngày 2 lần, mỗi lần 1 viên sau ăn' };
  const AMOX_NAME = 'Amoxicillin 250mg';
  const rx = async () => within(await exam().findByRole('region', { name: 'Đơn thuốc' }));
  const rxList = async () => within(await (await rx()).findByRole('list', { name: 'Thuốc đã kê' }));
  const orderLines = async () => within(await exam().findByRole('list', { name: 'Chỉ định trong lượt' }));
  type Section = Awaited<ReturnType<typeof rx>>;
  type User = ReturnType<typeof userEvent.setup>;
  const SUBMIT = 'Kê thuốc';
  const SHORT_ALERT = /Tồn khả dụng tại chi nhánh chỉ còn 700; chọn giảm số lượng hoặc mua ngoài/;
  const NO_ITEM = 'Chưa kê thuốc nào trong lượt này.';
  const EXTERNAL_800 = { ...AMOX, quantity: 800, shortageResolution: 'EXTERNAL_PURCHASE' } as const;

  async function renderAfter(setup: () => Promise<unknown>) {
    await startDesk('doctor@store1.vn');
    await setup();
    return renderDesk(<DoctorWorkspacePage />);
  }

  /** Chọn thuốc, nhập số lượng và liều dùng (không bấm kê). */
  async function fill(user: User, section: Section, over: { product?: string; quantity?: number; dosage?: string } = {}) {
    await section.findByRole('option', { name: over.product ?? AMOX_NAME });
    await user.selectOptions(section.getByLabelText('Chọn thuốc'), over.product ?? AMOX_NAME);
    const quantity = section.getByLabelText('Số lượng');
    await user.clear(quantity);
    await user.type(quantity, String(over.quantity ?? AMOX.quantity));
    await user.type(section.getByLabelText('Liều dùng'), over.dosage ?? AMOX.dosageInstructions);
  }
  const submit = (section: Section) => section.getByRole('button', { name: SUBMIT });
  const removeButton = `Xóa dòng thuốc ${AMOX_NAME}`;

  it('form chỉ liệt kê thuốc kê đơn đang kinh doanh (hỏi DRUG đang bán rồi lọc isPrescription ở FE, vì API không có bộ lọc này); chưa chọn gì thì chưa cho kê', async () => {
    const list = vi.spyOn(catalogApi, 'listProducts');
    await renderExamRoom();
    const section = await rx();

    await section.findByRole('option', { name: AMOX_NAME });
    expect(section.getAllByRole('option').map((o) => o.textContent)).toEqual(['Chọn thuốc…', AMOX_NAME]);
    expect(list).toHaveBeenCalledWith({ productType: 'DRUG', isActive: true, size: 100 });
    expect(section.getByText(NO_ITEM)).toBeInTheDocument();
    expect(submit(section)).toBeDisabled();
  });

  it('nút kê chỉ bật khi đã chọn thuốc, số lượng ≥ 1 và có liều dùng (khoảng trắng không tính); bộ đếm ký tự liều dùng chạy theo, tối đa 500', async () => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();
    await section.findByRole('option', { name: AMOX_NAME });

    await user.selectOptions(section.getByLabelText('Chọn thuốc'), AMOX_NAME);
    expect(section.getByText('0/500')).toBeInTheDocument();
    await user.type(section.getByLabelText('Liều dùng'), '   ');
    expect(submit(section)).toBeDisabled();
    await user.type(section.getByLabelText('Liều dùng'), 'abc');
    expect(section.getByText('6/500')).toBeInTheDocument();
    expect(submit(section)).toBeEnabled();
    await user.clear(section.getByLabelText('Số lượng'));
    expect(submit(section)).toBeDisabled();
    await user.type(section.getByLabelText('Số lượng'), '0');
    expect(submit(section)).toBeDisabled();
    await user.clear(section.getByLabelText('Số lượng'));
    await user.type(section.getByLabelText('Số lượng'), '2');
    expect(submit(section)).toBeEnabled();
    expect(section.getByLabelText('Liều dùng')).toHaveAttribute('maxlength', '500');
    expect(add).not.toHaveBeenCalled();
  });

  it('đủ tồn: gửi đúng đối số (liều dùng bỏ khoảng trắng hai đầu, không kèm shortageResolution), dòng đơn hiện huy hiệu "Tính vào hóa đơn" và dòng Thuốc vào Chỉ định; form được làm trống; kho không đổi', async () => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();

    await fill(user, section, { dosage: `  ${AMOX.dosageInstructions}  ` });
    await user.click(submit(section));

    await waitFor(() => expect(add).toHaveBeenCalledWith(2002, AMOX));
    expect(Object.keys(add.mock.calls[0][1])).toEqual(['productId', 'quantity', 'dosageInstructions']);
    const row = (await (await rxList()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(row).getByText('Tính vào hóa đơn')).toBeInTheDocument();
    expect(within(row).getByText(AMOX.dosageInstructions)).toBeInTheDocument();
    expect(within(row).getByText(/10/)).toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining(AMOX_NAME));
    const line = (await (await orderLines()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(line).getByText('Thuốc')).toBeInTheDocument();
    expect(section.getByLabelText('Liều dùng')).toHaveValue('');
    expect(section.getByLabelText('Chọn thuốc')).toHaveDisplayValue('Chọn thuốc…');
    expect((await apiClient.get('/api/inventory/3')).data.data.availableQuantity).toBe(700);
  });

  it('thiếu tồn (800 > 700): hiện khung cảnh báo với câu BE nguyên văn (bỏ mã rule) cùng 3 nút, chưa có dòng nào được ghi', async () => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();

    await fill(user, section, { quantity: 800 });
    await user.click(submit(section));

    const alert = await section.findByRole('alert');
    expect(alert).toHaveTextContent(SHORT_ALERT);
    expect(alert).not.toHaveTextContent('BR-KB-03');
    for (const name of ['Giảm xuống vừa tồn', 'Mua ngoài, giữ nguyên số lượng', 'Hủy']) expect(within(alert).getByRole('button', { name })).toBeInTheDocument();
    expect(add).toHaveBeenCalledTimes(1);
    expect(section.getByText(NO_ITEM)).toBeInTheDocument();
    expect(section.getByLabelText('Số lượng')).toHaveValue(800);
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('"Giảm xuống vừa tồn": gửi lại cùng body kèm REDUCE_TO_AVAILABLE; dòng đơn có số lượng bằng tồn (700), vẫn tính vào hóa đơn và vào Chỉ định; khung biến mất', async () => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section, { quantity: 800 });
    await user.click(submit(section));

    await user.click(await section.findByRole('button', { name: 'Giảm xuống vừa tồn' }));

    await waitFor(() => expect(add).toHaveBeenCalledTimes(2));
    expect(add).toHaveBeenLastCalledWith(2002, { ...AMOX, quantity: 800, shortageResolution: 'REDUCE_TO_AVAILABLE' });
    const row = (await (await rxList()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(row).getByText('Tính vào hóa đơn')).toBeInTheDocument();
    expect(within(row).getByText(/700/)).toBeInTheDocument();
    await waitFor(() => expect(section.queryByRole('alert')).not.toBeInTheDocument());
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('700'));
    const line = (await (await orderLines()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(line).getByText(/700 ×/)).toBeInTheDocument();
  });

  it('"Mua ngoài, giữ nguyên số lượng": gửi lại kèm EXTERNAL_PURCHASE; dòng đơn giữ 800, huy hiệu "Mua ngoài" kèm lý do "hết tồn khi kê", KHÔNG có dòng thuốc nào trong Chỉ định', async () => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section, { quantity: 800 });
    await user.click(submit(section));

    await user.click(await section.findByRole('button', { name: 'Mua ngoài, giữ nguyên số lượng' }));

    await waitFor(() => expect(add).toHaveBeenCalledTimes(2));
    expect(add).toHaveBeenLastCalledWith(2002, EXTERNAL_800);
    const row = (await (await rxList()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(row).getByText('Mua ngoài')).toBeInTheDocument();
    expect(within(row).getByText(/hết tồn khi kê/)).toBeInTheDocument();
    expect(within(row).queryByText('Tính vào hóa đơn')).not.toBeInTheDocument();
    expect(within(row).getByText(/800/)).toBeInTheDocument();
    expect((await orderLines()).queryByText(AMOX_NAME)).not.toBeInTheDocument();
  });

  it('"Hủy" ở khung thiếu tồn: không gửi thêm request nào, khung biến mất, dữ liệu đã nhập còn nguyên để sửa', async () => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section, { quantity: 800 });
    await user.click(submit(section));

    await user.click(await section.findByRole('button', { name: 'Hủy' }));

    expect(section.queryByRole('alert')).not.toBeInTheDocument();
    expect(add).toHaveBeenCalledTimes(1);
    expect(section.getByLabelText('Số lượng')).toHaveValue(800);
    expect(section.getByLabelText('Liều dùng')).toHaveValue(AMOX.dosageInstructions);
    expect(section.getByText(NO_ITEM)).toBeInTheDocument();
  });

  it.each<[string, (user: User, section: Section) => Promise<void>]>([
    ['số lượng', async (user, section) => user.type(section.getByLabelText('Số lượng'), '0')],
    ['liều dùng', async (user, section) => user.type(section.getByLabelText('Liều dùng'), '!')],
    ['thuốc', async (user, section) => user.selectOptions(section.getByLabelText('Chọn thuốc'), 'Chọn thuốc…')],
  ])('sửa ô %s sau khi báo thiếu tồn thì bỏ khung (nút cũ không còn gửi body đã lỗi thời)', async (_name, edit) => {
    const add = vi.spyOn(visitApi, 'addPrescription');
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section, { quantity: 800 });
    await user.click(submit(section));
    await section.findByRole('alert');

    await edit(user, section);

    expect(section.queryByRole('alert')).not.toBeInTheDocument();
    expect(section.queryByRole('button', { name: 'Giảm xuống vừa tồn' })).not.toBeInTheDocument();
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('BR-KB-03 của ca "không phải thuốc kê đơn đang kinh doanh" (cùng mã với thiếu tồn) là lỗi thường: câu BE bỏ mã, KHÔNG hiện khung giảm / mua ngoài, dữ liệu đã nhập còn nguyên', async () => {
    const add = vi
      .spyOn(visitApi, 'addPrescription')
      .mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Sản phẩm không phải thuốc kê đơn đang kinh doanh (BR-KB-03)', 400));
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section);

    await user.click(submit(section));

    const alert = await section.findByRole('alert');
    expect(alert).toHaveTextContent('Sản phẩm không phải thuốc kê đơn đang kinh doanh');
    expect(alert).not.toHaveTextContent('BR-KB-03');
    expect(section.queryByRole('button', { name: 'Giảm xuống vừa tồn' })).not.toBeInTheDocument();
    expect(section.queryByRole('button', { name: 'Mua ngoài, giữ nguyên số lượng' })).not.toBeInTheDocument();
    expect(section.queryByRole('button', { name: 'Hủy' })).not.toBeInTheDocument();
    expect(add).toHaveBeenCalledTimes(1);
    expect(section.getByLabelText('Số lượng')).toHaveValue(10);
    expect(section.getByLabelText('Liều dùng')).toHaveValue(AMOX.dosageInstructions);
  });

  it('lỗi 400 BR-KB-03 của request ĐÃ có shortageResolution (vd. thuốc hết tồn mà chọn giảm) hiện như lỗi thường: câu BE bỏ mã, không hiện lại khung 3 nút', async () => {
    const add = vi
      .spyOn(visitApi, 'addPrescription')
      .mockRejectedValueOnce(new ApiError('BUSINESS_RULE_VIOLATION', 'Tồn khả dụng tại chi nhánh chỉ còn 0; chọn giảm số lượng hoặc mua ngoài (BR-KB-03)', 400))
      .mockRejectedValueOnce(new ApiError('BUSINESS_RULE_VIOLATION', 'Thuốc đã hết tồn khả dụng tại chi nhánh, chỉ chọn được mua ngoài (BR-KB-03)', 400));
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section, { quantity: 800 });
    await user.click(submit(section));

    await user.click(await section.findByRole('button', { name: 'Giảm xuống vừa tồn' }));

    await waitFor(() => expect(add).toHaveBeenCalledTimes(2));
    expect(await section.findByText(/Thuốc đã hết tồn khả dụng tại chi nhánh, chỉ chọn được mua ngoài/)).toBeInTheDocument();
    expect(section.getByRole('alert')).not.toHaveTextContent('BR-KB-03');
    expect(section.queryByRole('button', { name: 'Giảm xuống vừa tồn' })).not.toBeInTheDocument();
    expect(section.queryByRole('button', { name: 'Hủy' })).not.toBeInTheDocument();
    expect(section.getByLabelText('Số lượng')).toHaveValue(800);
  });

  it('lỗi khác của BE (409 lượt đã hoàn tất ở nơi khác) hiện trong mục Đơn thuốc theo thao tác kê đơn, không mất dữ liệu đã nhập', async () => {
    vi.spyOn(visitApi, 'addPrescription').mockRejectedValue(new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS', 409));
    const { user } = await renderExamRoom();
    const section = await rx();
    await fill(user, section);

    await user.click(submit(section));

    expect(await section.findByRole('alert')).toHaveTextContent('Lượt đã hoàn tất nên đơn thuốc đã khóa');
    expect(section.queryByRole('button', { name: 'Hủy' })).not.toBeInTheDocument();
    expect(section.getByLabelText('Liều dùng')).toHaveValue(AMOX.dosageInstructions);
  });

  it('xóa dòng đơn: gọi đúng #17, dòng đơn và dòng thuốc trong Chỉ định cùng biến mất', async () => {
    const remove = vi.spyOn(visitApi, 'deletePrescription');
    const { user } = await renderAfter(() => visitApi.addPrescription(2002, AMOX));
    const section = await rx();
    const itemId = (await visitApi.getMedicalRecord(2002)).prescriptions[0].itemId;
    expect(await (await orderLines()).findByText(AMOX_NAME)).toBeInTheDocument();

    await user.click(await section.findByRole('button', { name: removeButton }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith(2002, itemId));
    expect(await section.findByText(NO_ITEM)).toBeInTheDocument();
    await waitFor(async () => expect((await orderLines()).queryByText(AMOX_NAME)).not.toBeInTheDocument());
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Đã xóa'));
  });

  it('xóa dòng mua ngoài: dòng đơn biến mất, Order không đổi', async () => {
    const { user } = await renderAfter(() => visitApi.addPrescription(2002, EXTERNAL_800));
    const section = await rx();
    const before = (await (await orderLines()).findAllByRole('listitem')).length;

    await user.click(await section.findByRole('button', { name: removeButton }));

    expect(await section.findByText(NO_ITEM)).toBeInTheDocument();
    expect((await orderLines()).getAllByRole('listitem')).toHaveLength(before);
  });

  it('BE từ chối xóa (403 không phải người phụ trách): câu báo hiện trong mục Đơn thuốc, dòng đơn vẫn còn', async () => {
    vi.spyOn(visitApi, 'deletePrescription').mockRejectedValue(new ApiError('ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 403));
    const { user } = await renderAfter(() => visitApi.addPrescription(2002, AMOX));
    const section = await rx();

    await user.click(await section.findByRole('button', { name: removeButton }));

    expect(await section.findByRole('alert')).toHaveTextContent('Chỉ bác sĩ được gán lượt này mới kê đơn hoặc xóa thuốc được');
    expect((await rxList()).getByText(AMOX_NAME)).toBeInTheDocument();
  });

  it('dòng mua ngoài do hết tồn lúc thu tiền ghi lý do "hết tồn khi thu tiền"', async () => {
    await startDesk('doctor@store1.vn');
    const item = { itemId: 7, productId: 3, productName: AMOX_NAME, quantity: 5, dosageInstructions: 'Uống sau ăn', isExternalPurchase: true, externalReason: 'OUT_OF_STOCK_AT_PAYMENT' as const, orderLineId: null };
    const real = visitApi.getMedicalRecord;
    vi.spyOn(visitApi, 'getMedicalRecord').mockImplementation(async (id) => ({ ...(await real(id)), prescriptions: [item] }));
    renderDesk(<DoctorWorkspacePage />);

    const row = (await (await rxList()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(row).getByText('Mua ngoài')).toBeInTheDocument();
    expect(within(row).getByText(/hết tồn khi thu tiền/)).toBeInTheDocument();
  });

  it('bệnh án đã khóa (lượt vừa hoàn tất và lượt mẫu 61): chỉ liệt kê đơn thuốc đã kê (cả dòng mua ngoài), KHÔNG có form, KHÔNG có nút xóa và không hỏi danh mục thuốc (BR-KB-01)', async () => {
    const list = vi.spyOn(catalogApi, 'listProducts');
    const { user } = await renderAfter(async () => {
      await visitApi.addPrescription(2002, AMOX);
      await visitApi.addPrescription(2002, EXTERNAL_800);
      await visitApi.saveMedicalRecord(2002, { diagnosis: 'Khỏe' });
      await visitApi.complete(2002);
    });
    const open = async (pet: string) => {
      await user.click(within(await screen.findByRole('list', { name: 'Đã khám hôm nay' })).getByRole('button', { name: new RegExp(`^${pet},`) }));
      await screen.findByRole('heading', { level: 2, name: pet });
    };
    const expectReadOnly = async () => {
      const section = await rx();
      expect(section.queryByRole('button', { name: /Xóa dòng thuốc/ })).not.toBeInTheDocument();
      expect(section.queryByLabelText('Chọn thuốc')).not.toBeInTheDocument();
      expect(section.queryByRole('button', { name: SUBMIT })).not.toBeInTheDocument();
    };

    await open('Lu');
    const rows = (await rxList()).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('Tính vào hóa đơn')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Mua ngoài')).toBeInTheDocument();
    await expectReadOnly();

    await open('Mochi');
    const row = (await (await rxList()).findByText(AMOX_NAME)).closest('li')!;
    expect(within(row).getByText('Tính vào hóa đơn')).toBeInTheDocument();
    expect(within(row).getByText('Ngày 2 lần, mỗi lần 1 viên sau ăn, uống 5 ngày')).toBeInTheDocument();
    await expectReadOnly();
    expect(list).not.toHaveBeenCalled();
  });

  describe('lượt đang khám của bác sĩ khác (chỉ xem)', () => {
    const empty = { visitId: 91, examination: null, diagnosis: null, treatmentPlan: null, internalNote: null, followUpDate: null, locked: false, lastEditedBy: null, prescriptions: [], vaccinations: [], addenda: [] };
    const item = { itemId: 3, productId: 3, productName: AMOX_NAME, quantity: 10, dosageInstructions: 'Uống sau ăn', isExternalPurchase: false, externalReason: null, orderLineId: 5 };
    async function renderOthers(prescriptions: (typeof item)[]) {
      await startDesk('doctor@store1.vn');
      vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([makeVisit(91, { petName: 'Của bạn khác', status: 'IN_PROGRESS', assigneeId: 9, assigneeName: 'BS. Khác' })]);
      vi.spyOn(visitApi, 'getMedicalRecord').mockResolvedValue({ ...empty, prescriptions });
      const list = vi.spyOn(catalogApi, 'listProducts');
      renderDesk(<DoctorWorkspacePage />);
      expect(await exam().findByLabelText('Chẩn đoán')).toHaveAttribute('readonly');
      return list;
    }

    it('thấy đơn đã kê nhưng không có form, không có nút xóa và không hỏi danh mục thuốc', async () => {
      const list = await renderOthers([item]);

      const section = await rx();
      expect(await (await rxList()).findByText(AMOX_NAME)).toBeInTheDocument();
      expect(section.queryByRole('button', { name: /Xóa dòng thuốc/ })).not.toBeInTheDocument();
      expect(section.queryByLabelText('Chọn thuốc')).not.toBeInTheDocument();
      expect(list).not.toHaveBeenCalled();
    });

    it('chưa kê gì thì không hiện mục Đơn thuốc', async () => {
      const list = await renderOthers([]);

      expect(exam().queryByRole('region', { name: 'Đơn thuốc' })).not.toBeInTheDocument();
      expect(list).not.toHaveBeenCalled();
    });
  });

  describe('bản nháp bệnh án chưa lưu không bị mất khi kê / xóa thuốc (useExamMutation làm mới [visits] nên bệnh án được tải lại)', () => {
    const DRAFT = 'Viêm da dị ứng, nghi ve';
    /** Bản nháp còn nguyên, chưa có request lưu nào và BE vẫn chưa có chẩn đoán. */
    async function expectDraftKept(save: MockInstance) {
      expect(exam().getByLabelText('Chẩn đoán')).toHaveValue(DRAFT);
      expect(status()).toHaveTextContent('Có thay đổi chưa lưu');
      expect(save).not.toHaveBeenCalled();
      expect((await visitApi.getMedicalRecord(2002)).diagnosis).toBeNull();
    }

    it('gõ chẩn đoán, KHÔNG lưu, kê một thuốc: ô chẩn đoán còn nguyên chữ vừa gõ; bệnh án được tải lại thật; bấm Lưu vẫn ghi đúng bản nháp', async () => {
      const save = vi.spyOn(visitApi, 'saveMedicalRecord');
      const read = vi.spyOn(visitApi, 'getMedicalRecord');
      const { user } = await renderExamRoom();
      await user.type(await labelOf('Chẩn đoán'), DRAFT);
      const section = await rx();
      await fill(user, section);
      const readsBefore = read.mock.calls.length;

      await user.click(submit(section));
      expect(await (await rxList()).findByText(AMOX_NAME)).toBeInTheDocument(); // dòng này đến từ bệnh án tải lại
      expect(read.mock.calls.length).toBeGreaterThan(readsBefore);

      await expectDraftKept(save);
      await user.click(exam().getByRole('button', { name: /^Lưu/ }));
      await waitFor(() => expect(save).toHaveBeenCalledWith(2002, expect.objectContaining({ diagnosis: DRAFT })));
      await waitFor(() => expect(status()).toHaveTextContent(/Đã lưu lúc/));
      expect((await visitApi.getMedicalRecord(2002)).prescriptions).toHaveLength(1);
    });

    it('gõ chẩn đoán, KHÔNG lưu, xóa một thuốc: ô chẩn đoán còn nguyên chữ vừa gõ', async () => {
      const save = vi.spyOn(visitApi, 'saveMedicalRecord');
      const read = vi.spyOn(visitApi, 'getMedicalRecord');
      const { user } = await renderAfter(() => visitApi.addPrescription(2002, AMOX));
      await user.type(await labelOf('Chẩn đoán'), DRAFT);
      const section = await rx();
      const readsBefore = read.mock.calls.length;

      await user.click(await section.findByRole('button', { name: removeButton }));
      expect(await section.findByText(NO_ITEM)).toBeInTheDocument();
      expect(read.mock.calls.length).toBeGreaterThan(readsBefore);

      await expectDraftKept(save);
    });

    it('kê thuốc mua ngoài (qua khung thiếu tồn) cũng giữ nguyên bản nháp ở các ô khác (hẹn tái khám, ghi chú nội bộ)', async () => {
      const save = vi.spyOn(visitApi, 'saveMedicalRecord');
      const { user } = await renderExamRoom();
      await user.type(await labelOf('Ghi chú nội bộ'), 'Chủ nuôi hay quên liều');
      fireEvent.change(exam().getByLabelText('Hẹn tái khám'), { target: { value: '2026-11-05' } });
      const section = await rx();
      await fill(user, section, { quantity: 800 });
      await user.click(submit(section));
      await user.click(await section.findByRole('button', { name: 'Mua ngoài, giữ nguyên số lượng' }));
      expect(await (await rxList()).findByText(AMOX_NAME)).toBeInTheDocument();

      expect(exam().getByLabelText('Ghi chú nội bộ')).toHaveValue('Chủ nuôi hay quên liều');
      expect(exam().getByLabelText('Hẹn tái khám')).toHaveValue('2026-11-05');
      expect(status()).toHaveTextContent('Có thay đổi chưa lưu');
      expect(save).not.toHaveBeenCalled();
    });
  });
});

describe('exam room — không gửi request vai trò này không được làm (VISIT_ROLES)', () => {
  it('bác sĩ hỏi hàng đợi của tôi và chỉ hỏi hàng đợi chi nhánh cho danh sách "Đã khám hôm nay"; không hỏi lịch hẹn chờ check-in (403)', async () => {
    const arrivals = vi.spyOn(visitApi, 'listArrivals');
    const branchQueue = vi.spyOn(visitApi, 'listQueue');
    const myQueue = vi.spyOn(visitApi, 'getMyQueue');
    await renderExamRoom();
    await screen.findByRole('heading', { level: 2, name: 'Lu' });

    expect(myQueue).toHaveBeenCalled();
    expect(arrivals).not.toHaveBeenCalled();
    expect(branchQueue).toHaveBeenCalledWith({ status: 'COMPLETED', serviceGroup: 'MEDICAL' });
    expect(branchQueue.mock.calls.every(([query]) => query?.status === 'COMPLETED')).toBe(true);
  });

  it('bác sĩ không gọi API ghi của lễ tân và không gọi hoàn tất khi lượt không phải của mình', async () => {
    await startDesk('doctor@store1.vn');
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([makeVisit(91, { petName: 'Của bạn khác', status: 'IN_PROGRESS', assigneeId: 9, assigneeName: 'BS. Khác' })]);
    const complete = vi.spyOn(visitApi, 'complete');
    const save = vi.spyOn(visitApi, 'saveMedicalRecord');
    vi.spyOn(visitApi, 'getMedicalRecord').mockResolvedValue({
      visitId: 91, examination: null, diagnosis: null, treatmentPlan: null, internalNote: null, followUpDate: null, locked: false, lastEditedBy: null, prescriptions: [], vaccinations: [], addenda: [],
    });
    renderDesk(<DoctorWorkspacePage />);

    expect(await exam().findByLabelText('Chẩn đoán')).toHaveAttribute('readonly');
    expect(exam().getByText(/Lượt của BS\. Khác\. Bạn chỉ xem được/)).toBeInTheDocument();
    expect(exam().queryByRole('button', { name: /Hoàn tất|^Lưu/ })).not.toBeInTheDocument();
    expect(complete).not.toHaveBeenCalled();
    expect(save).not.toHaveBeenCalled();
  });
});
