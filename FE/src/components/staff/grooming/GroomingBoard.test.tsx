import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../shared/api/api-error';
import { apiClient } from '../../../shared/api/axios';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { salesApi } from '../../../shared/api/sales.api';
import { visitApi } from '../../../shared/api/visit.api';
import { makeVisit, renderDesk, startDesk } from '../../../test/visit-harness';
import { GroomingBoard } from './GroomingBoard';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// visit.mock lúc 10:40 thứ Năm 22/10/2026, CARETAKER #6 (Thu Hà): hàng đợi của tôi = 2103 Bim (grooming, đang chờ). Cả khu thêm 2104 Mochi đang làm, người phụ trách
// #12 (Phạm Quốc Việt) không còn trong danh bạ. Chưa có lượt Thẩm mỹ nào hoàn tất hôm nay. "Xong" là hoàn tất lượt (visit-v1 #10, T37, UC52).
const GROOMER = 6;

beforeAll(() => {
  installMockApi(apiClient);
  // jsdom 25 chưa có PointerEvent / pointer capture: dựng tối thiểu để kéo thả chạy được.
  class FakePointerEvent extends MouseEvent {
    pointerId: number;
    constructor(type: string, init: MouseEventInit & { pointerId?: number } = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
    }
  }
  Object.assign(window, { PointerEvent: FakePointerEvent });
  HTMLElement.prototype.setPointerCapture = () => {};
});
beforeEach(() => toast.mockClear());
afterEach(() => vi.useRealTimers());

const column = (name: RegExp) => screen.getByRole('list', { name });
const columnOf = (name: RegExp) => within(column(name));
const cardIn = async (list: RegExp, pet: RegExp) => within(await columnOf(list).findByRole('article', { name: pet }));
const petsIn = (list: RegExp) => columnOf(list).queryAllByRole('article').map((a) => a.getAttribute('aria-label')?.split(',')[0]);

async function renderBoard(scope: 'mine' | 'all' = 'mine') {
  await startDesk('caretaker@store1.vn');
  return renderDesk(<GroomingBoard userId={GROOMER} scope={scope} />);
}

/** Kéo thẻ từ cột đang đứng thả vào cột `to` (jsdom không có bố cục: `elementFromPoint` trả cột đích). */
function drag(card: HTMLElement, to: string) {
  const handle = within(card).getByTitle('Kéo sang cột khác');
  const target = document.querySelector<HTMLElement>(`[data-column="${to}"]`)!;
  document.elementFromPoint = () => target;
  fireEvent.pointerDown(handle, { button: 0, clientX: 10, clientY: 10, pointerId: 1 });
  fireEvent.pointerMove(handle, { clientX: 120, clientY: 40, pointerId: 1 });
  fireEvent.pointerUp(handle, { clientX: 120, clientY: 40, pointerId: 1 });
}

describe('grooming board (useKanban trên API visit)', () => {
  it('cột lấy từ hàng đợi của tôi: Bim chờ làm; "Đang làm" trống; cột "Xong hôm nay" bật (không còn tắt), chưa có bé nào xong', async () => {
    await renderBoard();

    expect(await columnOf(/Chờ làm/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(columnOf(/Đang làm/).getByText('Trống')).toBeInTheDocument();
    expect(await columnOf(/Xong hôm nay/).findByText('Chưa có bé nào xong hôm nay.')).toBeInTheDocument();
    expect(document.querySelector('[data-column="READY"]')).not.toHaveAttribute('aria-disabled');
  });

  it('moves a card to "Đang làm" before the server answers', async () => {
    vi.spyOn(visitApi, 'call').mockReturnValue(new Promise(() => {}));
    const { user } = await renderBoard();

    await user.click((await cardIn(/Chờ làm/, /Bim/)).getByRole('button', { name: 'Bắt đầu' }));

    expect(await columnOf(/Đang làm/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(petsIn(/Chờ làm/)).toEqual([]);
  });

  it('puts the card back when the server refuses the move', async () => {
    vi.spyOn(visitApi, 'call').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Nhân viên không hoạt động tại chi nhánh này (BR-TN-05)', 400));
    const { user } = await renderBoard();

    await user.click((await cardIn(/Chờ làm/, /Bim/)).getByRole('button', { name: 'Bắt đầu' }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Nhân viên không hoạt động tại chi nhánh này'));
    expect(await columnOf(/Chờ làm/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(columnOf(/Đang làm/).queryByRole('article', { name: /Bim/ })).not.toBeInTheDocument();
  });

  it('Bắt đầu gọi lượt qua API; sau khi làm mới thẻ vẫn ở "Đang làm" và nút "Xong" bật', async () => {
    const call = vi.spyOn(visitApi, 'call');
    const { user } = await renderBoard();

    await user.click((await cardIn(/Chờ làm/, /Bim/)).getByRole('button', { name: 'Bắt đầu' }));

    await waitFor(() => expect(call).toHaveBeenCalledWith(2103));
    const bim = await cardIn(/Đang làm/, /Bim/);
    await waitFor(() => expect(call).toHaveBeenCalledTimes(1));
    expect(bim.getByRole('button', { name: 'Xong' })).toBeEnabled();
    expect(petsIn(/Chờ làm/)).toEqual([]);
  });

  /** Bắt đầu rồi chờ lượt thật đã IN_PROGRESS và thẻ đứng ở "Đang làm" sau lần làm mới. */
  async function startBim(user: Awaited<ReturnType<typeof renderBoard>>['user']) {
    await user.click((await cardIn(/Chờ làm/, /Bim/)).getByRole('button', { name: 'Bắt đầu' }));
    const bim = await cardIn(/Đang làm/, /Bim/);
    await waitFor(async () => expect((await visitApi.getVisit(2103)).status).toBe('IN_PROGRESS'));
    return bim;
  }

  it('"Xong" hoàn tất lượt qua API (#10): thẻ sang "Xong hôm nay" ngay trước khi BE trả lời', async () => {
    const complete = vi.spyOn(visitApi, 'complete').mockReturnValue(new Promise(() => {}));
    const { user } = await renderBoard();
    const bim = await startBim(user);

    await user.click(bim.getByRole('button', { name: 'Xong' }));

    expect(await columnOf(/Xong hôm nay/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(complete).toHaveBeenCalledWith(2103);
    expect(columnOf(/Đang làm/).queryByRole('article', { name: /Bim/ })).not.toBeInTheDocument();
  });

  it('hoàn tất thật: lượt COMPLETED, Order chờ thu, thẻ ở lại "Xong hôm nay" sau khi làm mới (nạp từ các lượt Thẩm mỹ đã xong trong ngày)', async () => {
    const list = vi.spyOn(visitApi, 'listQueue');
    const { user } = await renderBoard();
    const bim = await startBim(user);
    const { orderId } = await visitApi.getVisit(2103);

    await user.click(bim.getByRole('button', { name: 'Xong' }));

    await waitFor(async () => expect((await visitApi.getVisit(2103)).status).toBe('COMPLETED'));
    expect((await salesApi.getOrder(orderId)).status).toBe('PENDING');
    await waitFor(() => expect(list).toHaveBeenCalledWith({ serviceGroup: 'GROOMING', status: 'COMPLETED', assigneeId: GROOMER }));
    const done = await cardIn(/Xong hôm nay/, /Bim/);
    expect(done.getByText(/Xong lúc/)).toBeInTheDocument();
    expect(done.queryByRole('button')).not.toBeInTheDocument();
    expect(petsIn(/Đang làm/)).toEqual([]);
    expect(petsIn(/Chờ làm/)).toEqual([]);
  });

  it('BE từ chối hoàn tất (409): thẻ về "Đang làm", câu báo theo thao tác hoàn tất', async () => {
    vi.spyOn(visitApi, 'complete').mockRejectedValue(new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang COMPLETED', 409));
    const { user } = await renderBoard();
    const bim = await startBim(user);

    await user.click(bim.getByRole('button', { name: 'Xong' }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Lượt đã được hoàn tất rồi.'));
    expect(await columnOf(/Đang làm/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(columnOf(/Xong hôm nay/).queryByRole('article', { name: /Bim/ })).not.toBeInTheDocument();
  });

  it('kéo thẻ của mình từ "Đang làm" sang "Xong hôm nay" cũng là hoàn tất lượt', async () => {
    const complete = vi.spyOn(visitApi, 'complete');
    const { user } = await renderBoard();
    await startBim(user);

    drag(await screen.findByRole('article', { name: /Bim/ }), 'READY');

    await waitFor(() => expect(complete).toHaveBeenCalledWith(2103));
    expect(await columnOf(/Xong hôm nay/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
  });

  it('kéo thẻ của mình từ "Chờ làm" sang "Đang làm" là gọi lượt', async () => {
    const call = vi.spyOn(visitApi, 'call');
    await renderBoard();

    drag(await screen.findByRole('article', { name: /Bim/ }), 'IN_PROGRESS');

    await waitFor(() => expect(call).toHaveBeenCalledWith(2103));
    expect(await columnOf(/Đang làm/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
  });

  it('kéo bỏ qua "Đang làm" sang cột "Xong": từ chối, thẻ đứng yên, không gọi API', async () => {
    const call = vi.spyOn(visitApi, 'call');
    await renderBoard();

    drag(await screen.findByRole('article', { name: /Bim/ }), 'READY');

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Bắt đầu làm trước khi báo xong.'));
    expect(call).not.toHaveBeenCalled();
    expect(columnOf(/Chờ làm/).getByRole('article', { name: /Bim/ })).toBeInTheDocument();
  });

  it('thẻ theo đúng thứ tự mảng BE trả, kể cả khi khóa sắp xếp ngược', async () => {
    await startDesk('caretaker@store1.vn');
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([
      makeVisit(61, { petName: 'Sau', serviceGroup: 'GROOMING', assigneeId: GROOMER, queueSortKey: '2026-10-22T05:00:00Z' }),
      makeVisit(62, { petName: 'Trước', serviceGroup: 'GROOMING', assigneeId: GROOMER, queueSortKey: '2026-10-22T01:00:00Z' }),
    ]);
    renderDesk(<GroomingBoard userId={GROOMER} scope="mine" />);

    await waitFor(() => expect(petsIn(/Chờ làm/)).toEqual(['Sau', 'Trước']));
  });

  it('"Cả khu": hỏi hàng đợi chi nhánh lọc nhóm Thẩm mỹ (không hỏi hàng đợi của tôi); thẻ của người khác chỉ để xem', async () => {
    const mine = vi.spyOn(visitApi, 'getMyQueue');
    const branch = vi.spyOn(visitApi, 'listQueue');
    await renderBoard('all');

    const mochi = await cardIn(/Đang làm/, /Mochi/);
    expect(mochi.getByText('Phạm Quốc Việt')).toBeInTheDocument();
    expect(mochi.queryByRole('button')).not.toBeInTheDocument();
    expect(mochi.queryByTitle('Kéo sang cột khác')).not.toBeInTheDocument();
    const bim = await cardIn(/Chờ làm/, /Bim/);
    expect(bim.getByRole('button', { name: 'Bắt đầu' })).toBeInTheDocument();
    expect(branch).toHaveBeenCalledWith({ serviceGroup: 'GROOMING' });
    expect(mine).not.toHaveBeenCalled();
  });

  it('"Của tôi": hàng đợi của tôi cho thẻ đang chờ / đang làm; hàng đợi chi nhánh chỉ để lấy các lượt Thẩm mỹ ĐÃ XONG của chính mình hôm nay', async () => {
    const mine = vi.spyOn(visitApi, 'getMyQueue');
    const branch = vi.spyOn(visitApi, 'listQueue');
    await renderBoard('mine');
    await cardIn(/Chờ làm/, /Bim/);

    expect(mine).toHaveBeenCalled();
    await waitFor(() => expect(branch).toHaveBeenCalledWith({ serviceGroup: 'GROOMING', status: 'COMPLETED', assigneeId: GROOMER }));
    expect(branch.mock.calls.every(([query]) => query?.status === 'COMPLETED')).toBe(true);
  });

  it('"Cả khu": cột "Xong hôm nay" lấy mọi lượt Thẩm mỹ đã xong trong ngày của chi nhánh, không lọc theo người', async () => {
    await startDesk('caretaker@store1.vn');
    vi.spyOn(visitApi, 'listQueue').mockImplementation(async (query) =>
      query?.status === 'COMPLETED'
        ? [makeVisit(81, { petName: 'Đã xong', serviceGroup: 'GROOMING', status: 'COMPLETED', assigneeId: 9, assigneeName: 'Gia Huy', completedAt: '2026-10-22T02:30:00Z' })]
        : [],
    );
    renderDesk(<GroomingBoard userId={GROOMER} scope="all" />);

    const done = await cardIn(/Xong hôm nay/, /Đã xong/);
    expect(done.getByText('Gia Huy')).toBeInTheDocument();
    expect(done.queryByRole('button')).not.toBeInTheDocument();
  });

  it('lượt chưa gán trong "Cả khu" nói chờ lễ tân gán và không có nút bắt đầu', async () => {
    await startDesk('caretaker@store1.vn');
    vi.spyOn(visitApi, 'listQueue').mockResolvedValue([makeVisit(71, { petName: 'Chưa gán', serviceGroup: 'GROOMING' })]);
    renderDesk(<GroomingBoard userId={GROOMER} scope="all" />);

    const card = await cardIn(/Chờ làm/, /Chưa gán/);
    expect(card.getByText('Chờ lễ tân gán')).toBeInTheDocument();
    expect(card.queryByRole('button')).not.toBeInTheDocument();
  });
});
