import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { careApi } from '../../shared/api/care.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminCareTasksPage } from './AdminCareTasksPage';

// Giờ cố định thứ Hai 26/10/2026 09:00 (giờ VN), lễ tân reception@store1.vn (id 4, chi nhánh 1). Dữ liệu mẫu ở
// shared/api/mock/care.mock.ts (xem chú thích đầu phần Care Task của care.mock.test.ts):
//   Đang chờ: Lu (Lê Hoàng Long) quá hạn tái chủng 23/10 · Tôm (Nguyễn Thu Trang) quá hạn đón 25/10, chưa có SĐT ·
//   Bim (Nguyễn Thu Trang) quá hạn đón 25/10, SĐT khẩn 0987000111 · Miu (Lê Thị Hoa) tái khám 26/10 · Miu tái chủng 28/10
//   Đã xong: Miu 26/09 (liên hệ được), Bim 20/10 (không liên lạc được). Đã hủy: Lu 21/10 (lễ tân hủy), Mochi 22/10 (hệ thống hủy).

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-26T09:00:00+07:00'));
  useSession.getState().clear();
  await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
});
afterEach(() => vi.useRealTimers());

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AdminCareTasksPage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const dataRows = async () => within(await screen.findByRole('table', { name: 'Danh sách việc gọi điện' })).getAllByRole('row').slice(1);
/** Hàng chứa đoạn mô tả `context` (mỗi việc có một đoạn riêng). */
const rowWith = async (context: RegExp) => (await screen.findByText(context)).closest('tr')!;
const dueOf = (row: HTMLElement) => row.querySelector('time')!.getAttribute('datetime');
const buttonIn = (row: HTMLElement, name: RegExp) => within(row).getByRole('button', { name });

type User = ReturnType<typeof renderPage>;
const openTab = (user: User, name: string) => user.click(screen.getByRole('tab', { name }));

/** Tạo state mẫu rồi thêm `extra` việc đang chờ có hạn từ 01/10 (trước mọi việc mẫu) để có nhiều trang. */
async function addOpenTasks(extra: number) {
  await careApi.listTasks();
  const state = JSON.parse(localStorage.getItem('petcare-mock-care')!);
  const base = state.careTasks.find((t: { careTaskId: number }) => t.careTaskId === 5);
  for (let i = 0; i < extra; i++) {
    state.careTasks.push({ ...base, careTaskId: 100 + i, dueDate: `2026-10-${String(1 + i).padStart(2, '0')}`, context: `Việc thêm số ${i + 1}` });
  }
  localStorage.setItem('petcare-mock-care', JSON.stringify(state));
}

describe('AdminCareTasksPage (UC87 — lễ tân)', () => {
  // Trang không gọi chi tiết (danh sách đã đủ trường): giữ một ca cho hàm API theo hợp đồng care-v1 #2.
  it('careApi.getTask đọc chi tiết một việc của chi nhánh mình', async () => {
    expect(await careApi.getTask(7)).toMatchObject({ careTaskId: 7, petName: 'Bim', status: 'OPEN' });
  });

  describe('danh sách', () => {
    it('mặc định là việc đang chờ, hạn gần nhất ở trên, cùng hạn thì id nhỏ trước', async () => {
      renderPage();
      expect(screen.getByRole('heading', { level: 1, name: 'Việc gọi điện' })).toBeInTheDocument();
      const rows = await dataRows();
      expect(rows.map(dueOf)).toEqual(['2026-10-23', '2026-10-25', '2026-10-25', '2026-10-26', '2026-10-28']);
      expect(within(rows[0]).getByText('Lê Hoàng Long')).toBeInTheDocument();
      expect(within(rows[0]).getByText('Quá hạn tái chủng')).toBeInTheDocument();
      expect(rows[1]).toHaveTextContent('Tôm');
      expect(rows[2]).toHaveTextContent('Bim');
      expect(within(rows[3]).getByText('Tái khám')).toBeInTheDocument();
      expect(within(rows[4]).getByText('Tái chủng')).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Đang chờ' })).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByText('5 việc đang chờ')).toBeInTheDocument();
      // Hàng đang chờ có đủ hai nút; chỉ ghi tên người để phân biệt các hàng.
      expect(within(rows[0]).getByRole('button', { name: 'Đã gọi: Lê Hoàng Long (Lu)' })).toBeInTheDocument();
      expect(within(rows[0]).getByRole('button', { name: 'Hủy việc: Lê Hoàng Long (Lu)' })).toBeInTheDocument();
    });

    it.each([
      ['09:00', '2026-10-26T09:00:00+07:00'],
      ['00:30 (còn 17:30 ngày 25 theo UTC)', '2026-10-26T00:30:00+07:00'],
    ])('đánh dấu quá hạn theo ngày Việt Nam lúc %s: hôm nay và sau hôm nay không bị đánh dấu; việc đã xử lý không bao giờ bị đánh dấu', async (_at, now) => {
      vi.setSystemTime(new Date(now));
      const user = renderPage();
      const rows = await dataRows();
      expect(within(rows[0]).getByText('Quá hạn 3 ngày')).toBeInTheDocument();
      expect(within(rows[1]).getByText('Quá hạn 1 ngày')).toBeInTheDocument();
      expect(within(rows[2]).getByText('Quá hạn 1 ngày')).toBeInTheDocument();
      expect(within(rows[3]).queryByText(/^Quá hạn \d/)).not.toBeInTheDocument(); // 26/10 = hôm nay
      expect(within(rows[4]).queryByText(/^Quá hạn \d/)).not.toBeInTheDocument(); // 28/10

      await openTab(user, 'Đã xong'); // hạn 20/10 và 26/09 đều đã qua
      await screen.findByText('Gọi hai lần không nghe máy, đã nhắn tin để lại lời nhắn.');
      expect(screen.queryByText(/^Quá hạn \d/)).not.toBeInTheDocument();
    });

    it('khách có số điện thoại là link tel:; SĐT khẩn cho việc quá hạn đón; chưa có số thì báo chưa có', async () => {
      renderPage();
      const lu = await rowWith(/Vaccine Dại/);
      expect(within(lu).getByRole('link', { name: '0905111222' })).toHaveAttribute('href', 'tel:0905111222');
      const bim = await rowWith(/LT-2610-00032/);
      expect(within(bim).getByRole('link', { name: '0987000111' })).toHaveAttribute('href', 'tel:0987000111');
      const tom = await rowWith(/LT-2610-00031/);
      expect(within(tom).queryByRole('link')).not.toBeInTheDocument();
      expect(within(tom).getByText('Chưa có số điện thoại')).toBeInTheDocument();
      // Việc không có số vẫn xử lý được (hủy hoặc ghi "không liên lạc được").
      expect(buttonIn(tom, /^Đã gọi/)).toBeEnabled();
    });

    it('lọc theo loại việc', async () => {
      const user = renderPage();
      await dataRows();
      const select = screen.getByRole('combobox', { name: 'Lọc loại việc' });
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Mọi loại',
        'Tái chủng',
        'Quá hạn tái chủng',
        'Tái khám',
        'Quá hạn đón thú',
      ]);

      await user.selectOptions(select, 'Quá hạn đón thú');
      await waitFor(async () => expect(await dataRows()).toHaveLength(2));
      expect((await dataRows()).map((r) => r.textContent)).toEqual([expect.stringContaining('Tôm'), expect.stringContaining('Bim')]);

      await user.selectOptions(select, 'Mọi loại');
      await waitFor(async () => expect(await dataRows()).toHaveLength(5));
    });

    it('lọc theo khoảng hạn gồm cả hai đầu và xóa được bộ lọc', async () => {
      const user = renderPage();
      await dataRows();
      expect(screen.queryByRole('button', { name: 'Xóa bộ lọc' })).not.toBeInTheDocument();

      fireEvent.change(screen.getByLabelText('Hạn từ'), { target: { value: '2026-10-25' } });
      fireEvent.change(screen.getByLabelText('Hạn đến'), { target: { value: '2026-10-25' } });
      await waitFor(async () => expect(await dataRows()).toHaveLength(2));
      expect((await dataRows()).map(dueOf)).toEqual(['2026-10-25', '2026-10-25']);

      await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
      await waitFor(async () => expect(await dataRows()).toHaveLength(5));
      expect(screen.getByLabelText('Hạn từ')).toHaveValue('');
      expect(screen.getByLabelText('Hạn đến')).toHaveValue('');
    });

    it('việc đã xong và đã hủy hiện kết quả, ghi chú, lý do, người xử lý và giờ xử lý; không có nút', async () => {
      const user = renderPage();
      await dataRows();

      await openTab(user, 'Đã xong');
      const reached = await rowWith(/Mũi 1 vaccine 4 bệnh cho mèo/);
      expect(within(reached).getByText('Đã xong')).toBeInTheDocument();
      expect(within(reached).getByText('Liên hệ được')).toBeInTheDocument();
      expect(reached).toHaveTextContent('Khách hẹn đưa Miu đến tiêm trong tuần này.');
      expect(reached).toHaveTextContent('Bạn');
      expect(reached).toHaveTextContent('10:15 26/09/2026');
      const unreached = await rowWith(/Vaccine 5 bệnh/);
      expect(within(unreached).getByText('Không liên lạc được')).toBeInTheDocument();
      expect(unreached).toHaveTextContent('14:40 20/10/2026');
      for (const row of await dataRows()) expect(within(row).queryByRole('button')).not.toBeInTheDocument();

      await openTab(user, 'Đã hủy');
      const byStaff = await rowWith(/LT-2610-00028/);
      expect(within(byStaff).getByText('Đã hủy')).toBeInTheDocument();
      expect(byStaff).toHaveTextContent('Khách đã đến đón thú lúc 17:30, chưa kịp cập nhật hệ thống');
      expect(byStaff).toHaveTextContent('Bạn');
      const bySystem = await rowWith(/Vaccine 4 bệnh cho mèo: hạn tiêm nhắc/);
      expect(bySystem).toHaveTextContent('Đã tiêm lại (BR-TB-03)');
      expect(bySystem).toHaveTextContent('Hệ thống');
      expect(bySystem).not.toHaveTextContent('Bạn');
      for (const row of await dataRows()) expect(within(row).queryByRole('button')).not.toBeInTheDocument();
    });

    it('đang chờ dữ liệu của bộ lọc mới thì không còn nút nào của danh sách cũ bấm được', async () => {
      const original = careApi.listTasks;
      let release!: () => void;
      const gate = new Promise<void>((resolve) => (release = resolve));
      vi.spyOn(careApi, 'listTasks').mockImplementation(async (query) => {
        if (query?.status === 'DONE') await gate;
        return original(query);
      });
      const user = renderPage();
      await rowWith(/Vaccine Dại/);
      const actions = () => screen.queryAllByRole('button', { name: /^(Đã gọi|Hủy việc)/ }) as HTMLButtonElement[];
      expect(actions()).toHaveLength(10);
      expect(actions().every((b) => !b.disabled)).toBe(true);

      await openTab(user, 'Đã xong');
      // Bấm vào hàng cũ lúc này sẽ ghi nhầm việc: bản cũ không được còn nút bấm được.
      expect(screen.getByRole('tab', { name: 'Đã xong' })).toHaveAttribute('aria-selected', 'true');
      expect(actions().filter((b) => !b.disabled)).toEqual([]);

      release();
      await screen.findByText(/Mũi 1 vaccine 4 bệnh cho mèo/);
      expect(actions()).toEqual([]);
    });
  });

  describe('bốn trạng thái', () => {
    it('đang tải hiện khung chờ', async () => {
      renderPage();
      expect(screen.getByRole('status', { name: 'Đang tải' })).toBeInTheDocument();
      await dataRows();
      expect(screen.queryByRole('status', { name: 'Đang tải' })).not.toBeInTheDocument();
    });

    it('lỗi tải hiện thông báo kèm nút Thử lại; thử lại được', async () => {
      vi.spyOn(careApi, 'listTasks').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.', 0));
      const user = renderPage();
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Không tải được việc gọi điện');
      expect(alert).toHaveTextContent('Máy chủ đang gặp sự cố. Vui lòng thử lại sau.');

      await user.click(within(alert).getByRole('button', { name: 'Thử lại' }));
      expect(await dataRows()).toHaveLength(5);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('không còn việc đang chờ thì nói rõ, không để bảng trống', async () => {
      localStorage.setItem('petcare-mock-care', JSON.stringify({ notifications: [], settings: {}, careTasks: [] }));
      renderPage();
      expect(await screen.findByText('Không còn việc nào cần gọi')).toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });

    it('bộ lọc không khớp việc nào thì báo không khớp bộ lọc', async () => {
      const user = renderPage();
      await dataRows();
      await user.selectOptions(screen.getByRole('combobox', { name: 'Lọc loại việc' }), 'Tái khám');
      await openTab(user, 'Đã xong');
      expect(await screen.findByText('Không có việc nào khớp bộ lọc')).toBeInTheDocument();
    });
  });

  describe('hoàn tất cuộc gọi (BR-TB-05)', () => {
    const NOTE = 'Khách sẽ đến đón chiều nay';

    it('bắt buộc chọn kết quả rồi viết ghi chú; lưu xong việc rời danh sách đang chờ và hiện ở Đã xong', async () => {
      const complete = vi.spyOn(careApi, 'completeTask');
      const user = renderPage();
      const bim = await rowWith(/LT-2610-00032/);
      await user.click(buttonIn(bim, /^Đã gọi/));

      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveTextContent('Ghi kết quả cuộc gọi');
      expect(dialog).toHaveTextContent('Nguyễn Thu Trang');
      const reached = within(dialog).getByRole('radio', { name: 'Liên hệ được' });
      const unreachable = within(dialog).getByRole('radio', { name: 'Không liên lạc được' });
      expect(reached).not.toBeChecked();
      expect(unreachable).not.toBeChecked();
      expect(within(dialog).getByText('0/500')).toBeInTheDocument();
      expect(within(dialog).getByLabelText('Ghi chú')).toHaveAttribute('maxlength', '500');

      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Chọn kết quả cuộc gọi.');
      await user.click(reached);
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Nhập ghi chú cuộc gọi.');
      await user.type(within(dialog).getByLabelText('Ghi chú'), '   ');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Nhập ghi chú cuộc gọi.');
      expect(complete).not.toHaveBeenCalled();

      await user.clear(within(dialog).getByLabelText('Ghi chú'));
      await user.type(within(dialog).getByLabelText('Ghi chú'), NOTE);
      expect(within(dialog).getByText('26/500')).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(complete).toHaveBeenCalledOnce();
      expect(complete).toHaveBeenCalledWith(7, { result: 'REACHED', note: NOTE });
      expect(await screen.findByText('Đã ghi kết quả cuộc gọi: Nguyễn Thu Trang (Bim).')).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText(/LT-2610-00032/)).not.toBeInTheDocument());
      expect(await dataRows()).toHaveLength(4);
      expect(screen.getByText('4 việc đang chờ')).toBeInTheDocument();

      await openTab(user, 'Đã xong');
      const done = await rowWith(/LT-2610-00032/);
      expect(within(done).getByText('Liên hệ được')).toBeInTheDocument();
      expect(done).toHaveTextContent(NOTE);
      expect(done).toHaveTextContent('Bạn');
    });

    it('không liên lạc được cũng đóng việc', async () => {
      const user = renderPage();
      await user.click(buttonIn(await rowWith(/Vaccine Dại/), /^Đã gọi/));
      const dialog = screen.getByRole('dialog');
      await user.click(within(dialog).getByRole('radio', { name: 'Không liên lạc được' }));
      await user.type(within(dialog).getByLabelText('Ghi chú'), 'Gọi hai lần không bắt máy');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));

      await waitFor(() => expect(screen.queryByText(/Vaccine Dại/)).not.toBeInTheDocument());
      await openTab(user, 'Đã xong');
      const row = await rowWith(/Vaccine Dại/);
      expect(within(row).getByText('Không liên lạc được')).toBeInTheDocument();
    });

    it('đóng hộp thoại không gửi gì và lần mở sau bắt đầu lại từ trống', async () => {
      const complete = vi.spyOn(careApi, 'completeTask');
      const user = renderPage();
      const row = await rowWith(/Vaccine Dại/);
      await user.click(buttonIn(row, /^Đã gọi/));
      await user.click(within(screen.getByRole('dialog')).getByRole('radio', { name: 'Liên hệ được' }));
      await user.type(within(screen.getByRole('dialog')).getByLabelText('Ghi chú'), 'viết dở');
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Hủy' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

      await user.click(buttonIn(row, /^Đã gọi/));
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByLabelText('Ghi chú')).toHaveValue('');
      expect(within(dialog).getByRole('radio', { name: 'Liên hệ được' })).not.toBeChecked();
      expect(complete).not.toHaveBeenCalled();
    });

    it('lỗi BR-TB-05 của máy chủ hiện ngay trong hộp thoại, không đóng', async () => {
      vi.spyOn(careApi, 'completeTask').mockRejectedValueOnce(
        new ApiError('BUSINESS_RULE_VIOLATION', 'Vui lòng nhập ghi chú kết quả cuộc gọi (BR-TB-05)', 400),
      );
      const user = renderPage();
      await user.click(buttonIn(await rowWith(/Vaccine Dại/), /^Đã gọi/));
      const dialog = screen.getByRole('dialog');
      await user.click(within(dialog).getByRole('radio', { name: 'Liên hệ được' }));
      await user.type(within(dialog).getByLabelText('Ghi chú'), 'Đã gọi');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('Vui lòng nhập ghi chú kết quả cuộc gọi');
      expect(within(dialog).getByRole('alert')).not.toHaveTextContent('BR-TB-05');
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });

    it('việc đã được xử lý ở nơi khác (409): báo ngay trong hộp thoại và làm mới danh sách', async () => {
      const user = renderPage();
      const row = await rowWith(/Vaccine Dại/);
      await user.click(buttonIn(row, /^Đã gọi/));
      const dialog = screen.getByRole('dialog');
      await user.click(within(dialog).getByRole('radio', { name: 'Liên hệ được' }));
      await user.type(within(dialog).getByLabelText('Ghi chú'), 'Đã gọi');
      // Người khác (máy khác) hoàn tất việc này trước.
      await careApi.completeTask(5, { result: 'REACHED', note: 'Đồng nghiệp đã gọi' });
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái CareTask từ DONE sang DONE');
      await waitFor(() => expect(screen.queryByText(/Vaccine Dại/)).not.toBeInTheDocument());
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
      expect(await dataRows()).toHaveLength(4);
    });
  });

  describe('hủy việc', () => {
    it('bắt buộc lý do (tối đa 300 ký tự, có bộ đếm); hủy xong việc rời danh sách đang chờ và hiện ở Đã hủy', async () => {
      const cancel = vi.spyOn(careApi, 'cancelTask');
      const user = renderPage();
      await user.click(buttonIn(await rowWith(/Hẹn tái khám/), /^Hủy việc/));

      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveTextContent('Hủy việc gọi điện');
      const reason = within(dialog).getByLabelText('Lý do hủy');
      expect(reason).toHaveAttribute('maxlength', '300');
      expect(within(dialog).getByText('0/300')).toBeInTheDocument();

      await user.click(within(dialog).getByRole('button', { name: 'Hủy việc này' }));
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Nhập lý do hủy.');
      expect(cancel).not.toHaveBeenCalled();

      await user.type(reason, 'Khách báo đã tái khám ở nơi khác');
      expect(within(dialog).getByText('32/300')).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Hủy việc này' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(cancel).toHaveBeenCalledWith(8, { reason: 'Khách báo đã tái khám ở nơi khác' });
      expect(await screen.findByText('Đã hủy việc gọi điện của Lê Thị Hoa (Miu).')).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText(/Hẹn tái khám/)).not.toBeInTheDocument());
      expect(await dataRows()).toHaveLength(4);

      await openTab(user, 'Đã hủy');
      const row = await rowWith(/Hẹn tái khám/);
      expect(row).toHaveTextContent('Khách báo đã tái khám ở nơi khác');
      expect(row).toHaveTextContent('Bạn');
    });

    it('việc đã được xử lý ở nơi khác (409): báo trong hộp thoại', async () => {
      const user = renderPage();
      await user.click(buttonIn(await rowWith(/Hẹn tái khám/), /^Hủy việc/));
      const dialog = screen.getByRole('dialog');
      await user.type(within(dialog).getByLabelText('Lý do hủy'), 'Không cần gọi nữa');
      await careApi.cancelTask(8, { reason: 'Đồng nghiệp đã hủy' });
      await user.click(within(dialog).getByRole('button', { name: 'Hủy việc này' }));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái CareTask từ CANCELLED sang CANCELLED');
    });
  });

  describe('thông báo kết quả không theo sang bộ lọc khác', () => {
    /** Hủy một việc đang chờ (hộp thoại + lý do) và chờ thông báo thành công hiện ra; `context` chọn hàng cần hủy. */
    async function cancelOne(user: User, context: RegExp) {
      await user.click(buttonIn(await rowWith(context), /^Hủy việc/));
      const dialog = screen.getByRole('dialog');
      await user.type(within(dialog).getByLabelText('Lý do hủy'), 'Không cần gọi nữa');
      await user.click(within(dialog).getByRole('button', { name: 'Hủy việc này' }));
      expect(await screen.findByText(/^Đã hủy việc gọi điện của/)).toBeInTheDocument();
    }
    const notice = () => screen.queryByText(/^Đã hủy việc gọi điện của/);

    it.each([
      ['đổi tab trạng thái', (user: User) => openTab(user, 'Đã hủy')],
      ['đổi loại việc', (user: User) => user.selectOptions(screen.getByRole('combobox', { name: 'Lọc loại việc' }), 'Tái chủng')],
      ['đổi khoảng hạn', async () => void fireEvent.change(screen.getByLabelText('Hạn từ'), { target: { value: '2026-10-25' } })],
    ])('%s thì thông báo biến mất', async (_name, change) => {
      const user = renderPage();
      await cancelOne(user, /Hẹn tái khám/);
      await change(user);
      await waitFor(() => expect(notice()).not.toBeInTheDocument());
    });

    it('Xóa bộ lọc thì thông báo biến mất', async () => {
      const user = renderPage();
      await user.selectOptions(await screen.findByRole('combobox', { name: 'Lọc loại việc' }), 'Tái khám');
      await cancelOne(user, /Hẹn tái khám/);
      await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
      await waitFor(() => expect(notice()).not.toBeInTheDocument());
    });

    it('chuyển trang thì thông báo biến mất', async () => {
      await addOpenTasks(17); // 22 việc; hủy một việc thêm còn 21 nên vẫn hai trang
      const user = renderPage();
      await cancelOne(user, /^Việc thêm số 1$/);
      await user.click(screen.getByRole('button', { name: 'Sau' }));
      await screen.findByText('Trang 2/2 · 21 việc');
      expect(notice()).not.toBeInTheDocument();
    });
  });

  describe('phân trang', () => {
    it('hiện 20 việc mỗi trang; xử lý nốt việc duy nhất ở trang cuối thì quay về trang trước, không để trang trống', async () => {
      await addOpenTasks(16); // 16 + 5 mẫu = 21 việc đang chờ
      const user = renderPage();
      expect(await dataRows()).toHaveLength(20);
      expect(screen.getByText('21 việc đang chờ')).toBeInTheDocument();
      expect(screen.getByText('Trang 1/2 · 21 việc')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Sau' }));
      await screen.findByText('Trang 2/2 · 21 việc');
      await waitFor(async () => expect(await dataRows()).toHaveLength(1));
      const last = await rowWith(/Mũi 2 vaccine 4 bệnh/);
      expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();

      await user.click(buttonIn(last, /^Đã gọi/));
      const dialog = screen.getByRole('dialog');
      await user.click(within(dialog).getByRole('radio', { name: 'Liên hệ được' }));
      await user.type(within(dialog).getByLabelText('Ghi chú'), 'Khách sẽ đặt lịch tiêm');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu kết quả' }));

      expect(await screen.findByText('20 việc đang chờ')).toBeInTheDocument();
      expect(await dataRows()).toHaveLength(20);
      expect(screen.queryByText(/^Trang \d+\/\d+/)).not.toBeInTheDocument();
    });

    it('đổi bộ lọc thì xin lại từ trang đầu, không xin trang 2 của bộ lọc mới', async () => {
      await addOpenTasks(16);
      const list = vi.spyOn(careApi, 'listTasks');
      const user = renderPage();
      await dataRows();
      await user.click(screen.getByRole('button', { name: 'Sau' }));
      await screen.findByText('Trang 2/2 · 21 việc');

      await user.selectOptions(screen.getByRole('combobox', { name: 'Lọc loại việc' }), 'Tái chủng');
      await waitFor(() => expect(screen.getByText('1 việc đang chờ')).toBeInTheDocument());
      expect(list.mock.calls.filter(([query]) => query?.type === 'VACCINE_DUE').map(([query]) => query?.page)).toEqual([0]);
    });
  });
});
