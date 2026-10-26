import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../shared/api/api-error';
import { apiClient } from '../../../shared/api/axios';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { visitApi } from '../../../shared/api/visit.api';
import { makeVisit, renderDesk, startDesk } from '../../../test/visit-harness';
import { GroomingBoard } from './GroomingBoard';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// visit.mock lúc 10:40 thứ Năm 22/10/2026, CARETAKER #6 (Thu Hà): hàng đợi của tôi = 2103 Bim (grooming, đang chờ). Cả khu thêm 2104 Mochi đang làm, người phụ trách
// #12 (Phạm Quốc Việt) không còn trong danh bạ. Xong lượt (visit-v1 #10) là T37.
const GROOMER = 6;
const HINT = 'Hoàn tất lượt: chưa nối API (T37)';

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
  it('cột lấy từ hàng đợi của tôi: Bim chờ làm; "Đang làm" trống; cột "Xong" tắt kèm lời nhắn chưa nối API', async () => {
    await renderBoard();

    expect(await columnOf(/Chờ làm/).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(columnOf(/Đang làm/).getByText('Trống')).toBeInTheDocument();
    const done = document.querySelector('[data-column="READY"]')!;
    expect(done).toHaveAttribute('aria-disabled', 'true');
    expect(within(done as HTMLElement).getByText(HINT)).toBeInTheDocument();
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

  it('Bắt đầu gọi lượt qua API; sau khi làm mới thẻ vẫn ở "Đang làm" và "Xong" tắt kèm lời nhắn T37', async () => {
    const call = vi.spyOn(visitApi, 'call');
    const { user } = await renderBoard();

    await user.click((await cardIn(/Chờ làm/, /Bim/)).getByRole('button', { name: 'Bắt đầu' }));

    await waitFor(() => expect(call).toHaveBeenCalledWith(2103));
    const bim = await cardIn(/Đang làm/, /Bim/);
    await waitFor(() => expect(call).toHaveBeenCalledTimes(1));
    const done = bim.getByRole('button', { name: 'Xong' });
    expect(done).toBeDisabled();
    expect(done).toHaveAttribute('title', HINT);
    expect(bim.getByText(HINT)).toBeInTheDocument();
    expect(petsIn(/Chờ làm/)).toEqual([]);
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

  it('"Của tôi": chỉ hỏi hàng đợi của tôi, không hỏi hàng đợi chi nhánh', async () => {
    const mine = vi.spyOn(visitApi, 'getMyQueue');
    const branch = vi.spyOn(visitApi, 'listQueue');
    await renderBoard('mine');
    await cardIn(/Chờ làm/, /Bim/);

    expect(mine).toHaveBeenCalled();
    expect(branch).not.toHaveBeenCalled();
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
