import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { visitApi } from '../../shared/api/visit.api';
import { useGroomingStore } from '../../shared/stores/grooming.store';
import { renderDesk, startDesk } from '../../test/visit-harness';
import { GroomingWorkspacePage } from './GroomingWorkspacePage';

// visit.mock lúc 10:40 thứ Năm 22/10/2026, CARETAKER #6 (Thu Hà): hàng đợi của tôi = 2103 Bim (đang chờ). Cả khu thêm 2104 Mochi (người phụ trách #12 không còn trong danh bạ).

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useGroomingStore.setState({ scope: null }));
afterEach(() => vi.useRealTimers());

describe('grooming desk (khu grooming trên API visit)', () => {
  it('nhân viên chăm sóc mở sẵn "Của tôi"; tiêu đề đếm số bé của mình; hàng đợi chi nhánh chỉ được hỏi để lấy các lượt đã xong của chính mình hôm nay', async () => {
    const branch = vi.spyOn(visitApi, 'listQueue');
    const arrivals = vi.spyOn(visitApi, 'listArrivals');
    await startDesk('caretaker@store1.vn');
    renderDesk(<GroomingWorkspacePage />);

    expect(await screen.findByText(/1 bé đang chờ hoặc đang làm/)).toBeInTheDocument();
    expect(await within(screen.getByRole('list', { name: 'Chờ làm' })).findByRole('article', { name: /Bim/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Của tôi' })).toBeChecked();
    await waitFor(() => expect(branch).toHaveBeenCalledWith({ serviceGroup: 'GROOMING', status: 'COMPLETED', assigneeId: 6 }));
    expect(branch.mock.calls.every(([query]) => query?.status === 'COMPLETED')).toBe(true);
    expect(arrivals).not.toHaveBeenCalled();
  });

  it('"Cả khu" đổi sang hàng đợi chi nhánh: thấy cả thẻ của người khác, tiêu đề vẫn đếm lượt của mình', async () => {
    await startDesk('caretaker@store1.vn');
    const { user } = renderDesk(<GroomingWorkspacePage />);
    await screen.findByText(/1 bé đang chờ hoặc đang làm/);

    await user.click(screen.getByRole('radio', { name: 'Cả khu' }));

    expect(await within(screen.getByRole('list', { name: 'Đang làm' })).findByRole('article', { name: /Mochi/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Cả khu' })).toBeChecked());
    expect(screen.getByText(/1 bé đang chờ hoặc đang làm/)).toBeInTheDocument();
  });

  it('chưa có bé nào được gán: tiêu đề nói thẳng, cột "Chờ làm" báo trống', async () => {
    await startDesk('caretaker@store1.vn');
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([]);
    renderDesk(<GroomingWorkspacePage />);

    expect(await screen.findByText(/chưa có bé nào được gán/)).toBeInTheDocument();
    expect(await screen.findByText('Không có bé nào đang chờ.')).toBeInTheDocument();
  });
});
