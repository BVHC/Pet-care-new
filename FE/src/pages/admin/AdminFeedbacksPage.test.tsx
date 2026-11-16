import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { feedbackApi } from '../../shared/api/feedback.api';
import { FEEDBACK_STATE_KEY, type FeedbackRecord } from '../../shared/api/mock/feedback.mock';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { Feedback } from '../../shared/types/feedback';
import { AdminFeedbacksPage } from './AdminFeedbacksPage';

// Dữ liệu mẫu ở shared/api/mock/feedback.mock.ts, đồng hồ ghim ở thứ Ba 27/10/2026 10:00 giờ Việt Nam. Mới nhất trước:
// #3 (khách 1, chi nhánh 1, 26/10 16:20) · #5 (khách 4, OTHER không chi nhánh, 26/10 08:10) · #4 (khách 3, chi nhánh 1, 25/10 09:05) ·
// #2 (khách 1, WEBSITE không chi nhánh, 24/10 20:40) · #6 (khách 4, chi nhánh 1, 21/10) · #1 (khách 1, chi nhánh 1, 18/10).
// Feedback không có trạng thái (ADR-0122, V55): màn chỉ đọc. SUPER_MANAGER chuoi@ thấy cả 6; BRANCH_MANAGER manager@ (chi nhánh 1) thấy
// #3, #4, #6, #1; manager2@ (chi nhánh 2) không thấy gì.
beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-27T10:00:00+07:00'));
  await signInAs('chuoi@petcare.vn');
});

afterEach(() => vi.useRealTimers());

const signInAs = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AdminFeedbacksPage />
    </QueryClientProvider>,
  );
}

const table = () => screen.findByRole('table', { name: 'Danh sách góp ý' });
/** Các dòng dữ liệu (bỏ hàng tiêu đề). */
const rows = async () => within(await table()).getAllByRole('row').slice(1);
const openButton = (id: number) => new RegExp(`^Xem góp ý #${id} của `);
const rowOf = async (id: number) => (await within(await table()).findByRole('button', { name: openButton(id) })).closest('tr')!;
const idsShown = async () => (await rows()).map((r) => Number(/#(\d+)/.exec(within(r).getByRole('button').getAttribute('aria-label') ?? '')![1]));
const dialog = () => screen.findByRole('dialog');

function inject(records: Partial<FeedbackRecord>[]) {
  const full = records.map(
    (r, i): FeedbackRecord => ({
      feedbackId: i + 1,
      customerId: 1,
      topic: 'WEBSITE',
      branchId: null,
      rating: null,
      content: `Góp ý số ${i + 1} của khách hàng`,
      createdAt: new Date(Date.parse('2026-10-20T03:00:00Z') + i * 60_000).toISOString(),
      ...r,
    }),
  );
  localStorage.setItem(FEEDBACK_STATE_KEY, JSON.stringify({ feedbacks: full }));
}

describe('danh sách (UC84)', () => {
  it('có <h1> "Góp ý của khách"; SUPER_MANAGER thấy cả 6 góp ý mẫu, mới nhất trước', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Góp ý của khách' })).toBeInTheDocument();
    expect(await idsShown()).toEqual([3, 5, 4, 2, 6, 1]);
  });

  it('mỗi dòng có ngày gửi giờ Việt Nam, khách, chủ đề, chi nhánh, điểm, nội dung; không có trạng thái', async () => {
    renderPage();
    const first = await rowOf(3);
    expect(within(first).getByText('26/10/2026 16:20')).toBeInTheDocument();
    expect(within(first).getByText('Nguyễn Thu Trang')).toBeInTheDocument();
    expect(within(first).getByText('Dịch vụ tại chi nhánh')).toBeInTheDocument();
    expect(within(first).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
    expect(within(first).getByRole('img', { name: 'Mức hài lòng 3 trên 5' })).toBeInTheDocument();
    expect(within(first).getByText(/Phòng chờ hơi đông/)).toBeInTheDocument();
    expect(within(first).queryByText(/Mới gửi|Đã xem|Đã xử lý/)).not.toBeInTheDocument();
    // Góp ý không gắn chi nhánh và không chấm điểm.
    const other = await rowOf(5);
    expect(within(other).getByText('Không gắn chi nhánh')).toBeInTheDocument();
    expect(within(other).getByText('Chưa chấm')).toBeInTheDocument();
  });

  it('không có cột hay bộ lọc trạng thái; nói rõ góp ý chỉ để đọc và chỉ được lưu có thời hạn (BR-DG-05)', async () => {
    renderPage();
    await table();
    expect(screen.queryByRole('columnheader', { name: 'Trạng thái' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Trạng thái')).not.toBeInTheDocument();
    const note = screen.getByText(/quá hạn hệ thống tự xóa/);
    expect(note).toHaveTextContent('feedback.retention_days');
    expect(note).toHaveTextContent(/mặc định 30 ngày/);
  });

  it('gọi BE không kèm tham số status (BE đã bỏ), kể cả khi đổi bộ lọc', async () => {
    const get = vi.spyOn(apiClient, 'get');
    const user = userEvent.setup();
    renderPage();
    await table();
    await user.selectOptions(screen.getByLabelText('Chủ đề'), 'Website');
    await waitFor(async () => expect(await idsShown()).toEqual([2]));
    const params = get.mock.calls.filter(([url]) => url === '/api/feedbacks').map(([, config]) => config?.params ?? {});
    expect(params.length).toBeGreaterThanOrEqual(2);
    for (const p of params) expect(p).not.toHaveProperty('status');
  });

  it('nội dung dài được rút gọn trong bảng', async () => {
    inject([{ content: `${'Rất dài '.repeat(40)}hết` }]);
    renderPage();
    const cell = within(await rowOf(1)).getByText(/^Rất dài/);
    expect(cell.textContent!.endsWith('…')).toBe(true);
    expect(cell.textContent!.length).toBeLessThan(120);
    expect(cell).toHaveAttribute('title', expect.stringContaining('hết'));
  });

  describe('4 trạng thái', () => {
    it('đang tải: khung chờ', async () => {
      renderPage();
      expect(screen.getByRole('status', { name: 'Đang tải góp ý' })).toBeInTheDocument();
      await table();
      expect(screen.queryByRole('status', { name: 'Đang tải góp ý' })).not.toBeInTheDocument();
    });

    it('lỗi: câu của BE kèm nút "Thử lại", bấm thì tải lại được', async () => {
      vi.spyOn(feedbackApi, 'list').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
      const user = userEvent.setup();
      renderPage();
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Không tải được danh sách góp ý');
      expect(alert).toHaveTextContent('Không có quyền thực hiện thao tác này');

      await user.click(within(alert).getByRole('button', { name: 'Thử lại' }));
      expect(await idsShown()).toHaveLength(6);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('rỗng: chưa có góp ý nào (BRANCH_MANAGER chi nhánh 2 không có góp ý, không phải lỗi 403)', async () => {
      await signInAs('manager2@store2.vn');
      renderPage();
      expect(await screen.findByText('Chưa có góp ý nào')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });

    it('rỗng vì bộ lọc: câu riêng kèm "Xóa lọc"', async () => {
      const user = userEvent.setup();
      renderPage();
      await table();
      await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Hà Đông');
      expect(await screen.findByText('Không có góp ý nào khớp bộ lọc')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Xóa lọc' }));
      expect(await idsShown()).toHaveLength(6);
    });
  });

  describe('phạm vi theo vai trò (BR-DG-03)', () => {
    it('SUPER_MANAGER có bộ lọc chi nhánh', async () => {
      renderPage();
      await table();
      const select = screen.getByLabelText('Chi nhánh');
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Mọi chi nhánh',
        'PetCare Cầu Giấy',
        'PetCare Hà Đông',
        'PetCare Long Biên',
      ]);
    });

    it('BRANCH_MANAGER không có bộ lọc chi nhánh, chỉ thấy góp ý chi nhánh mình, không thấy góp ý không gắn chi nhánh', async () => {
      await signInAs('manager@store1.vn');
      renderPage();
      expect(await idsShown()).toEqual([3, 4, 6, 1]);
      expect(screen.queryByLabelText('Chi nhánh')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Trạng thái')).not.toBeInTheDocument();
    });
  });

  describe('bộ lọc và phân trang', () => {
    it('lọc theo chủ đề và chi nhánh', async () => {
      const user = userEvent.setup();
      renderPage();
      await table();

      await user.selectOptions(screen.getByLabelText('Chủ đề'), 'Website');
      await waitFor(async () => expect(await idsShown()).toEqual([2]));
      await user.selectOptions(screen.getByLabelText('Chủ đề'), 'Mọi chủ đề');
      await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Cầu Giấy');
      await waitFor(async () => expect(await idsShown()).toEqual([3, 4, 6, 1]));
    });

    it('lọc theo từ ngày / đến ngày (giờ Việt Nam, gồm hai đầu); "Xóa lọc" trả về đủ', async () => {
      const user = userEvent.setup();
      renderPage();
      await table();

      fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-25' } });
      fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-10-26' } });
      await waitFor(async () => expect(await idsShown()).toEqual([3, 5, 4]));

      await user.click(screen.getByRole('button', { name: 'Xóa lọc' }));
      await waitFor(async () => expect(await idsShown()).toHaveLength(6));
      expect(screen.getByLabelText('Từ ngày')).toHaveValue('');
      expect(screen.queryByRole('button', { name: 'Xóa lọc' })).not.toBeInTheDocument();
    });

    it('từ ngày sau đến ngày: hiện nguyên câu 400 của BE', async () => {
      renderPage();
      await table();
      fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-26' } });
      fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-10-20' } });
      expect(await screen.findByRole('alert')).toHaveTextContent('Ngày bắt đầu không được sau ngày kết thúc');
    });

    it('chia trang 20 dòng; Trước / Sau; đổi bộ lọc thì về trang đầu', async () => {
      inject(Array.from({ length: 45 }, (_, i) => ({ topic: i % 2 === 0 ? 'WEBSITE' : 'OTHER' })));
      const user = userEvent.setup();
      renderPage();

      expect(await rows()).toHaveLength(20);
      expect((await idsShown())[0]).toBe(45);
      expect(screen.getByText(/Trang 1\/3/)).toBeInTheDocument();
      expect(screen.getByText(/45 góp ý/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Sau' }));
      await waitFor(async () => expect((await idsShown())[0]).toBe(25));
      expect(screen.getByText(/Trang 2\/3/)).toBeInTheDocument();

      await user.selectOptions(screen.getByLabelText('Chủ đề'), 'Website');
      await waitFor(() => expect(screen.getByText(/Trang 1\/2/)).toBeInTheDocument());
      expect(await rows()).toHaveLength(20);

      await user.click(screen.getByRole('button', { name: 'Sau' }));
      await waitFor(async () => expect(await rows()).toHaveLength(3));
      expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();
    });

    it('chỉ một trang thì không hiện thanh phân trang', async () => {
      renderPage();
      await table();
      expect(screen.queryByRole('button', { name: 'Sau' })).not.toBeInTheDocument();
    });
  });
});

describe('mở chi tiết (chỉ đọc, #21)', () => {
  const FIRST_CONTENT = /Phòng chờ hơi đông vào cuối tuần/;

  it('bấm dòng: hiện đủ thông tin; không có trạng thái, ô ghi chú hay nút xử lý', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(within(await rowOf(3)).getByText('Nguyễn Thu Trang'));
    const modal = await dialog();
    expect(await within(modal).findByText(FIRST_CONTENT)).toBeInTheDocument();
    expect(within(modal).getByRole('heading', { name: 'Góp ý #3' })).toBeInTheDocument();
    expect(modal).toHaveTextContent('Nguyễn Thu Trang');
    expect(modal).toHaveTextContent('Dịch vụ tại chi nhánh');
    expect(modal).toHaveTextContent('PetCare Cầu Giấy');
    expect(modal).toHaveTextContent('26/10/2026 16:20');
    expect(within(modal).getByRole('img', { name: 'Mức hài lòng 3 trên 5' })).toBeInTheDocument();
    expect(within(modal).queryByText(/Mới gửi|Đã xem|Đã xử lý|Xem lần đầu|Trạng thái/)).not.toBeInTheDocument();
    expect(within(modal).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(modal).queryByRole('button', { name: /xử lý/i })).not.toBeInTheDocument();
  });

  it('mở chỉ GET chi tiết một lần, không có request ghi nào (không /resolve) và không làm mới danh sách', async () => {
    const get = vi.spyOn(apiClient, 'get');
    const post = vi.spyOn(apiClient, 'post');
    const user = userEvent.setup();
    renderPage();
    await user.click(within(await rowOf(3)).getByText('Nguyễn Thu Trang'));
    await within(await dialog()).findByText(FIRST_CONTENT);
    const calls = (path: string) => get.mock.calls.filter(([url]) => url === path).length;
    expect(calls('/api/feedbacks/3')).toBe(1);
    expect(calls('/api/feedbacks')).toBe(1);
    expect(post).not.toHaveBeenCalled();
  });

  it('nút tên khách (bàn phím vào được) cũng mở được; đóng bằng nút X', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await within(await table()).findByRole('button', { name: openButton(4) }));
    const modal = await dialog();
    expect(await within(modal).findByRole('heading', { name: 'Góp ý #4' })).toBeInTheDocument();

    await user.click(within(modal).getByRole('button', { name: 'Đóng' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('đang mở: khung chờ trong hộp thoại cho tới khi BE trả lời', async () => {
    const real = await feedbackApi.get(4);
    let answer!: (feedback: Feedback) => void;
    vi.spyOn(feedbackApi, 'get').mockImplementationOnce(() => new Promise<Feedback>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    renderPage();
    await user.click(await within(await table()).findByRole('button', { name: openButton(4) }));
    const modal = await dialog();
    expect(within(modal).getByRole('status', { name: 'Đang mở góp ý' })).toBeInTheDocument();
    expect(within(modal).queryByText(/Mình chờ lấy thuốc/)).not.toBeInTheDocument();

    await act(async () => answer(real));
    expect(await within(modal).findByText(/Mình chờ lấy thuốc/)).toBeInTheDocument();
    expect(within(modal).queryByRole('status', { name: 'Đang mở góp ý' })).not.toBeInTheDocument();
  });

  it('lỗi mở (403 / 404): câu của BE trong hộp thoại kèm "Thử lại"; thử lại được', async () => {
    vi.spyOn(feedbackApi, 'get').mockRejectedValueOnce(new ApiError('ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 403));
    const user = userEvent.setup();
    renderPage();
    await user.click(await within(await table()).findByRole('button', { name: openButton(4) }));
    const modal = await dialog();
    expect(await within(modal).findByRole('alert')).toHaveTextContent('Không có quyền truy cập dữ liệu này');
    expect(within(modal).queryByText(/Mình chờ lấy thuốc/)).not.toBeInTheDocument();

    await user.click(within(modal).getByRole('button', { name: 'Thử lại' }));
    expect(await within(modal).findByText(/Mình chờ lấy thuốc/)).toBeInTheDocument();
  });

  it('BRANCH_MANAGER mở được góp ý của chi nhánh mình', async () => {
    await signInAs('manager@store1.vn');
    const user = userEvent.setup();
    renderPage();
    await user.click(within(await rowOf(3)).getByText('Nguyễn Thu Trang'));
    expect(await within(await dialog()).findByText(FIRST_CONTENT)).toBeInTheDocument();
  });
});
