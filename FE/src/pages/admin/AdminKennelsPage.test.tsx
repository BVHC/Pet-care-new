import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { boardingApi } from '../../shared/api/boarding.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ACCOUNTS, pinClock } from '../../test/boarding-harness';
import { AdminKennelsPage } from './AdminKennelsPage';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// Đồng hồ ghim thứ Sáu 23/10/2026 09:00 (giờ VN), quản lý chi nhánh 1 (manager@store1.vn). Dữ liệu mẫu (boarding.seed.ts):
//   loại chuồng #5 "Chuồng chó nhỏ" (bật tại CN1; loại #6 "Chuồng mèo" CHƯA bật) · C-01 OCCUPIED (Cốm), C-02 OCCUPIED (Mực),
//   C-03 AVAILABLE (đã có lịch sử lưu trú → xóa bị 409), C-04 MAINTENANCE (ghi chú "Hỏng khóa cửa, chờ thợ thay", xóa được).
//   Sức chứa loại #5 = 3. Chuyển C-03 sang bảo trì → sức chứa 2 → Bim (26/10→28/10) và Bơ (30/10→01/11) thiếu chỗ.

const STORE_KEY = 'petcare-mock-boarding';
const cleanups: Array<() => void> = [];

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  pinClock();
  toast.success.mockClear();
  toast.error.mockClear();
  useSession.getState().clear();
  await loginAs(ACCOUNTS.manager);
});
afterEach(() => {
  cleanups.splice(0).forEach((fn) => fn());
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function loginAs(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/kennels']}>
        <AdminKennelsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

interface Seen {
  method: string;
  url: string;
  params: Record<string, unknown>;
  body: Record<string, unknown> | undefined;
  status: number;
}

/** Ghi lại mọi request qua apiClient (kể cả bị từ chối) để kiểm đúng endpoint, đúng query / body và không có 403. */
function recordRequests(): Seen[] {
  const seen: Seen[] = [];
  const note = (config: { method?: string; url?: string; params?: unknown; data?: unknown }, status: number) =>
    seen.push({
      method: config.method ?? '',
      url: config.url ?? '',
      params: (config.params ?? {}) as Record<string, unknown>,
      body: typeof config.data === 'string' ? JSON.parse(config.data) : undefined,
      status,
    });
  const id = apiClient.interceptors.response.use(
    (res) => {
      note(res.config, res.status);
      return res;
    },
    (err) => {
      note(err.config ?? {}, err.response?.status ?? 0);
      return Promise.reject(err);
    },
  );
  cleanups.push(() => apiClient.interceptors.response.eject(id));
  return seen;
}

type KennelSeed = { kennelId: number; branchId: number; kennelTypeId: number; code: string; note: string | null; status: string };
const kennelSeed = (kennelId: number, code: string, status: string, note: string | null = null): KennelSeed => ({ kennelId, branchId: 1, kennelTypeId: 5, code, note, status });
/** Đặt state mock trước khi trang tải lần đầu; phần không nêu được gộp từ dữ liệu mẫu khi nạp. */
const seedStore = (partial: { kennels?: KennelSeed[]; bookings?: unknown[] }) => localStorage.setItem(STORE_KEY, JSON.stringify(partial));

/** Sửa state mock đã lưu NGAY SAU KHI trang tải: mô phỏng người khác vừa thao tác (trang đang giữ dữ liệu cũ). */
function patchStore(fn: (state: { kennels: Array<{ kennelId: number; status: string }> }) => void) {
  const state = JSON.parse(localStorage.getItem(STORE_KEY)!);
  fn(state);
  localStorage.setItem(STORE_KEY, JSON.stringify(state));
}

const table = () => screen.findByRole('table', { name: 'Danh sách chuồng' });
const rowOf = async (code: string) => (await screen.findByRole('cell', { name: code })).closest('tr')!;
const bodyRows = async () => within(await table()).getAllByRole('row').slice(1);
const codes = async () => (await bodyRows()).map((r) => within(r).getAllByRole('cell')[0].textContent);
const summaryOf = async (typeName: string) => screen.findByRole('group', { name: `Sức chứa ${typeName}` });
const stat = (group: HTMLElement, label: string) => within(group).getByText(label).parentElement!.querySelector('dd')!.textContent;
const openDialog = () => screen.findByRole('dialog');
/** Khối của một ô trong hộp thoại (nhãn + ô nhập + lỗi của riêng ô): để chắc câu lỗi nằm dưới đúng ô, không chỉ đâu đó trong hộp thoại. */
const fieldBlock = (dialog: HTMLElement, label: string) => within(dialog).getByLabelText(label).parentElement!;

async function waitForList() {
  await table();
  await screen.findByRole('cell', { name: 'C-04' });
}

describe('AdminKennelsPage (UC54 — quản lý chi nhánh)', () => {
  describe('danh sách', () => {
    it('có <h1> ngay khi đang tải, rồi bảng 4 chuồng sắp theo mã tăng', async () => {
      renderPage();
      expect(screen.getByRole('heading', { level: 1, name: 'Chuồng lưu trú' })).toBeInTheDocument();
      expect(screen.getByText('Đang tải…')).toBeInTheDocument();
      expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04']);
      const heads = within(await table()).getAllByRole('columnheader').map((h) => h.textContent);
      expect(heads).toEqual(['Mã chuồng', 'Loại chuồng', 'Trạng thái', 'Ghi chú', 'Đang giữ', 'Thao tác']);
    });

    it('mỗi hàng đủ loại, nhãn trạng thái, ghi chú; bảng cuộn ngang trong khung', async () => {
      renderPage();
      const c3 = await rowOf('C-03');
      expect(c3).toHaveTextContent('Chuồng chó nhỏ');
      expect(within(c3).getByText('Trống')).toBeInTheDocument();
      expect(within(await rowOf('C-01')).getByText('Đang có thú')).toBeInTheDocument();
      const c4 = await rowOf('C-04');
      expect(within(c4).getByText('Bảo trì')).toBeInTheDocument();
      expect(c4).toHaveTextContent('Hỏng khóa cửa, chờ thợ thay');
      expect((await table()).parentElement).toHaveClass('overflow-x-auto');
    });

    it('"Đang giữ": mã đặt chỗ và tên thú tra từ đặt chỗ đang lưu trú; chuồng không giữ thú hiện dấu gạch', async () => {
      const stays = (await boardingApi.list({ inStay: true })).content;
      const codeOf = (kennelCode: string) => stays.find((b) => b.kennelCode === kennelCode)!.code;
      renderPage();
      const c1 = await rowOf('C-01');
      await waitFor(() => expect(c1).toHaveTextContent(codeOf('C-01')));
      expect(c1).toHaveTextContent('Cốm');
      const c2 = await rowOf('C-02');
      expect(c2).toHaveTextContent(codeOf('C-02'));
      expect(c2).toHaveTextContent('Mực');
      expect((await rowOf('C-03')).querySelectorAll('td')[4]).toHaveTextContent('—');
    });

    it('tra đặt chỗ lỗi thì cột "Đang giữ" rơi về "Đặt chỗ #id", trang vẫn dùng được và không báo lỗi', async () => {
      vi.spyOn(boardingApi, 'list').mockRejectedValue(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
      renderPage();
      expect(await rowOf('C-01')).toHaveTextContent('Đặt chỗ #2');
      expect(await rowOf('C-02')).toHaveTextContent('Đặt chỗ #3');
      await waitFor(() => expect(boardingApi.list).toHaveBeenCalled());
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('không có chuồng nào đang giữ đặt chỗ thì không gọi tra đặt chỗ đang lưu trú', async () => {
      seedStore({ kennels: [kennelSeed(3, 'C-03', 'AVAILABLE'), kennelSeed(4, 'C-04', 'MAINTENANCE')], bookings: [] });
      const seen = recordRequests();
      renderPage();
      expect(await codes()).toEqual(['C-03', 'C-04']);
      await new Promise((r) => setTimeout(r, 30));
      expect(seen.map((r) => r.url)).toContain('/api/kennels');
      expect(seen.filter((r) => r.url === '/api/boarding-bookings')).toEqual([]);
    });

    it('lỗi tải: hiện câu của BE và nút Thử lại; bấm lại thì có dữ liệu', async () => {
      vi.spyOn(boardingApi, 'listKennels').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
      const user = renderPage();
      expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
      expect(screen.getByRole('heading', { level: 1, name: 'Chuồng lưu trú' })).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Thử lại' }));
      expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04']);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('chưa có chuồng nào: lời mời tạo chuồng đầu tiên mở hộp thoại tạo', async () => {
      seedStore({ kennels: [] });
      const user = renderPage();
      expect(await screen.findByText('Chi nhánh chưa có chuồng nào')).toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Tạo chuồng đầu tiên' }));
      expect(within(await openDialog()).getByLabelText('Mã chuồng')).toBeInTheDocument();
    });

    it('chi nhánh DRAFT (manager2): không lỗi 403 / 404, danh sách rỗng, giải thích chưa có loại chuồng và khóa nút tạo', async () => {
      await loginAs(ACCOUNTS.manager2);
      const seen = recordRequests();
      renderPage();
      expect(await screen.findByText(/Chưa có loại chuồng lưu trú nào đang bật/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Tạo chuồng' })).toBeDisabled();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Dịch vụ của chi nhánh/ })).toHaveAttribute('href', '/admin/branches/2/services');
      await waitFor(() => expect(seen.map((r) => r.url)).toContain('/api/public/services'));
      expect(seen.every((r) => r.status === 200)).toBe(true);
    });
  });

  describe('lọc', () => {
    it('loại chuồng lấy từ dịch vụ lưu trú bật tại chi nhánh (không có "Chuồng mèo"); trạng thái đủ 3 giá trị', async () => {
      renderPage();
      await waitForList();
      const types = within(screen.getByLabelText('Lọc loại chuồng')).getAllByRole('option');
      expect(types.map((o) => o.textContent)).toEqual(['Mọi loại chuồng', 'Chuồng chó nhỏ']);
      const statuses = within(screen.getByLabelText('Lọc trạng thái')).getAllByRole('option');
      expect(statuses.map((o) => o.textContent)).toEqual(['Mọi trạng thái', 'Trống', 'Đang có thú', 'Bảo trì']);
    });

    it('lọc theo trạng thái gửi đúng query status; lọc theo loại gửi kennelTypeId là số; kết hợp cả hai', async () => {
      const user = renderPage();
      await waitForList();
      const seen = recordRequests();
      await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Bảo trì');
      await waitFor(async () => expect(await codes()).toEqual(['C-04']));
      const queries = () => seen.filter((r) => r.url === '/api/kennels').map((r) => r.params);
      expect(queries()).toContainEqual({ status: 'MAINTENANCE' });

      await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Mọi trạng thái');
      await user.selectOptions(screen.getByLabelText('Lọc loại chuồng'), 'Chuồng chó nhỏ');
      await waitFor(async () => expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04']));
      expect(queries()).toContainEqual({ kennelTypeId: 5 });

      await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Trống');
      await waitFor(async () => expect(await codes()).toEqual(['C-03']));
      expect(queries()).toContainEqual({ kennelTypeId: 5, status: 'AVAILABLE' });
    });

    it('lọc không khớp: báo không có chuồng và nút Xóa bộ lọc trả lại cả danh sách', async () => {
      seedStore({ kennels: [kennelSeed(1, 'C-01', 'OCCUPIED'), kennelSeed(2, 'C-02', 'OCCUPIED'), kennelSeed(3, 'C-03', 'OCCUPIED'), kennelSeed(4, 'C-04', 'MAINTENANCE')] });
      const user = renderPage();
      await waitForList();
      await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Trống');
      expect(await screen.findByText('Không có chuồng nào khớp bộ lọc')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
      expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04']);
    });
  });

  describe('sức chứa theo loại (BR-LT-01)', () => {
    it('sức chứa = chuồng không bảo trì: 3, trong đó 2 đang có thú, 1 trống; 1 chuồng bảo trì', async () => {
      renderPage();
      const group = await summaryOf('Chuồng chó nhỏ');
      await waitFor(() => expect(stat(group, 'Sức chứa')).toBe('3'));
      expect(stat(group, 'Đang có thú')).toBe('2');
      expect(stat(group, 'Trống')).toBe('1');
      expect(stat(group, 'Bảo trì')).toBe('1');
    });

    it('bộ lọc chỉ lọc bảng, không đổi sức chứa', async () => {
      const user = renderPage();
      await waitForList();
      await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Bảo trì');
      await waitFor(async () => expect(await codes()).toEqual(['C-04']));
      const group = await summaryOf('Chuồng chó nhỏ');
      expect(stat(group, 'Sức chứa')).toBe('3');
      expect(stat(group, 'Đang có thú')).toBe('2');
    });
  });

  describe('tạo chuồng', () => {
    async function openCreate(user: ReturnType<typeof renderPage>) {
      await waitForList();
      await user.click(screen.getByRole('button', { name: 'Tạo chuồng' }));
      return openDialog();
    }
    const submit = (user: ReturnType<typeof renderPage>, dialog: HTMLElement) => user.click(within(dialog).getByRole('button', { name: 'Tạo chuồng' }));

    it('loại chuồng chỉ gồm loại lưu trú bật tại chi nhánh (chọn sẵn khi chỉ có một); tạo thành công: đúng body, toast, hàng mới, sức chứa tăng', async () => {
      const user = renderPage();
      await waitForList();
      const seen = recordRequests();
      const dialog = await openCreate(user);
      const type = within(dialog).getByLabelText('Loại chuồng');
      expect(within(type).getAllByRole('option').map((o) => o.textContent)).toEqual(['Chuồng chó nhỏ (Chó, tối đa 10 kg)']);
      expect(type).toHaveValue('5');
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'C-05');
      await user.type(within(dialog).getByLabelText('Ghi chú'), 'Gần cửa sổ');
      await submit(user, dialog);

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04', 'C-05']);
      expect(await rowOf('C-05')).toHaveTextContent('Gần cửa sổ');
      expect(toast.success).toHaveBeenCalledWith('Đã tạo chuồng C-05');
      const post = seen.find((r) => r.method === 'post' && r.url === '/api/kennels')!;
      expect(post.body).toEqual({ kennelTypeId: 5, code: 'C-05', note: 'Gần cửa sổ' });
      expect(post.status).toBe(201);
      await waitFor(async () => expect(stat(await summaryOf('Chuồng chó nhỏ'), 'Sức chứa')).toBe('4'));
    });

    it('mã có khoảng trắng hai đầu được cắt trước khi gửi', async () => {
      const user = renderPage();
      const seen = recordRequests();
      const dialog = await openCreate(user);
      await user.type(within(dialog).getByLabelText('Mã chuồng'), '  C-06  ');
      await user.type(within(dialog).getByLabelText('Ghi chú'), '  gần cổng  ');
      await submit(user, dialog);
      await waitFor(() => expect(seen.some((r) => r.method === 'post')).toBe(true));
      expect(seen.find((r) => r.method === 'post')!.body).toEqual({ kennelTypeId: 5, code: 'C-06', note: 'gần cổng' });
    });

    it('ghi chú để trống thì không gửi note', async () => {
      const user = renderPage();
      const seen = recordRequests();
      const dialog = await openCreate(user);
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'C-05');
      await submit(user, dialog);
      await waitFor(() => expect(seen.some((r) => r.method === 'post' && r.url === '/api/kennels')).toBe(true));
      expect(seen.find((r) => r.method === 'post')!.body).toEqual({ kennelTypeId: 5, code: 'C-05' });
    });

    it('thiếu mã: câu của BE hiện dưới ô Mã chuồng, hộp thoại không đóng, không tạo chuồng', async () => {
      const user = renderPage();
      const dialog = await openCreate(user);
      await submit(user, dialog);
      expect(await within(dialog).findByText('Vui lòng nhập mã chuồng')).toBeInTheDocument();
      expect(fieldBlock(dialog, 'Mã chuồng')).toHaveTextContent('Vui lòng nhập mã chuồng');
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
      expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04']);
      expect(toast.success).not.toHaveBeenCalled();
    });

    it('mã dài 21 ký tự và ghi chú dài 301 ký tự: hai câu của BE hiện đúng ô; không chặn gõ ở trình duyệt', async () => {
      const user = renderPage();
      const dialog = await openCreate(user);
      await user.click(within(dialog).getByLabelText('Mã chuồng'));
      await user.paste('A'.repeat(21));
      await user.click(within(dialog).getByLabelText('Ghi chú'));
      await user.paste('g'.repeat(301));
      expect(within(dialog).getByLabelText('Mã chuồng')).toHaveValue('A'.repeat(21));
      await submit(user, dialog);
      expect(await within(dialog).findByText('Mã chuồng tối đa 20 ký tự')).toBeInTheDocument();
      expect(fieldBlock(dialog, 'Mã chuồng')).toHaveTextContent('Mã chuồng tối đa 20 ký tự');
      expect(fieldBlock(dialog, 'Ghi chú')).toHaveTextContent('Ghi chú tối đa 300 ký tự');
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    });

    it('trùng mã: nguyên câu BR-LT-01 của BE (không kèm mã luật); phân biệt hoa thường nên c-01 tạo được', async () => {
      const user = renderPage();
      const dialog = await openCreate(user);
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'C-01');
      await submit(user, dialog);
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(/^Mã chuồng đã tồn tại trong chi nhánh$/);
      expect(screen.getByRole('dialog')).toBeInTheDocument();

      await user.clear(within(dialog).getByLabelText('Mã chuồng'));
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'c-01');
      await submit(user, dialog);
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(await codes()).toContain('c-01');
    });

    it('loại chuồng vừa bị tắt tại chi nhánh (danh sách cũ): nguyên câu BR-LT-01 của BE, nút không kẹt', async () => {
      const user = renderPage();
      await waitForList();
      const dialog = await openCreate(user);
      await within(dialog).findByLabelText('Loại chuồng');
      await apiClient.put('/api/branches/1/services/5', { enabled: false });
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'C-09');
      await submit(user, dialog);
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(/^Loại chuồng không phải dịch vụ lưu trú đang bật tại chi nhánh$/);
      expect(within(dialog).getByRole('button', { name: 'Tạo chuồng' })).toBeEnabled();
    });
  });

  describe('tạo chuồng khi chi nhánh bật nhiều loại', () => {
    it('loại đã bật mà chưa có chuồng vẫn hiện sức chứa 0; chưa chọn loại thì BE nhắc chọn; chọn loại mới tạo được', async () => {
      await apiClient.put('/api/branches/1/services/6', { enabled: true });
      const user = renderPage();
      const seen = recordRequests();
      await waitForList();
      const cats = await summaryOf('Chuồng mèo');
      await waitFor(() => expect(stat(cats, 'Sức chứa')).toBe('0'));
      expect(cats).toHaveTextContent('khách chưa đặt chỗ được loại này');

      await user.click(screen.getByRole('button', { name: 'Tạo chuồng' }));
      const dialog = await openDialog();
      const type = within(dialog).getByLabelText('Loại chuồng');
      expect(within(type).getAllByRole('option').map((o) => o.textContent)).toEqual([
        'Chọn loại chuồng',
        'Chuồng chó nhỏ (Chó, tối đa 10 kg)',
        'Chuồng mèo (Mèo, tối đa 8 kg)',
      ]);
      expect(type).toHaveValue('');
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'M-01');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo chuồng' }));
      expect(await within(dialog).findByText('Vui lòng chọn loại chuồng')).toBeInTheDocument();
      expect(seen.find((r) => r.method === 'post')!.body).toEqual({ code: 'M-01' });

      await user.selectOptions(type, 'Chuồng mèo (Mèo, tối đa 8 kg)');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo chuồng' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(seen.filter((r) => r.method === 'post').at(-1)!.body).toEqual({ kennelTypeId: 6, code: 'M-01' });
      expect(within(await rowOf('M-01')).getByText('Chuồng mèo')).toBeInTheDocument();
      await waitFor(async () => expect(stat(await summaryOf('Chuồng mèo'), 'Sức chứa')).toBe('1'));
    });
  });

  describe('sửa chuồng', () => {
    async function openEdit(user: ReturnType<typeof renderPage>, code: string) {
      await user.click(within(await rowOf(code)).getByRole('button', { name: `Sửa ${code}` }));
      return openDialog();
    }

    it('chỉ sửa mã và ghi chú (không có ô loại chuồng); xóa ghi chú gửi note rỗng', async () => {
      const user = renderPage();
      const seen = recordRequests();
      const dialog = await openEdit(user, 'C-04');
      expect(within(dialog).getByLabelText('Mã chuồng')).toHaveValue('C-04');
      expect(within(dialog).getByLabelText('Ghi chú')).toHaveValue('Hỏng khóa cửa, chờ thợ thay');
      expect(within(dialog).queryByLabelText('Loại chuồng')).not.toBeInTheDocument();
      await user.clear(within(dialog).getByLabelText('Ghi chú'));
      await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      const patch = seen.find((r) => r.method === 'patch')!;
      expect(patch.url).toBe('/api/kennels/4');
      expect(patch.body).toEqual({ code: 'C-04', note: '' });
      expect(toast.success).toHaveBeenCalledWith('Đã cập nhật chuồng C-04');
      await waitFor(async () => expect(await rowOf('C-04')).not.toHaveTextContent('Hỏng khóa cửa'));
    });

    it('đổi mã: hàng mới theo mã mới; chuồng đang có thú vẫn sửa được', async () => {
      const user = renderPage();
      const dialog = await openEdit(user, 'C-01');
      await user.clear(within(dialog).getByLabelText('Mã chuồng'));
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'A-01');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
      await waitFor(async () => expect(await codes()).toEqual(['A-01', 'C-02', 'C-03', 'C-04']));
    });

    it('chuồng đã bị người khác xóa (404): câu của BE hiện trong hộp thoại, danh sách làm mới, nút Lưu không kẹt', async () => {
      const user = renderPage();
      const dialog = await openEdit(user, 'C-04');
      await apiClient.delete('/api/kennels/4');
      await user.type(within(dialog).getByLabelText('Ghi chú'), ' thêm');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(/^Không tìm thấy chuồng #4$/);
      await waitFor(async () => expect(await codes()).toEqual(['C-01', 'C-02', 'C-03']));
      expect(within(dialog).getByRole('button', { name: 'Lưu' })).toBeEnabled();
    });

    it('mã trống và mã trùng: câu của BE; hộp thoại giữ nguyên', async () => {
      const user = renderPage();
      const dialog = await openEdit(user, 'C-03');
      await user.clear(within(dialog).getByLabelText('Mã chuồng'));
      await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
      expect(await within(dialog).findByText('Mã chuồng không được để trống')).toBeInTheDocument();
      expect(fieldBlock(dialog, 'Mã chuồng')).toHaveTextContent('Mã chuồng không được để trống');
      await user.type(within(dialog).getByLabelText('Mã chuồng'), 'C-01');
      await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(/^Mã chuồng đã tồn tại trong chi nhánh$/);
    });
  });

  describe('bảo trì và hết bảo trì', () => {
    it('chuồng đang có thú: nút Bảo trì và Xóa khóa kèm lý do; nút Sửa vẫn mở; không có nút Hết bảo trì', async () => {
      renderPage();
      const row = await rowOf('C-01');
      const maintenance = within(row).getByRole('button', { name: 'Bảo trì C-01' });
      const remove = within(row).getByRole('button', { name: 'Xóa C-01' });
      expect(maintenance).toBeDisabled();
      expect(maintenance).toHaveAttribute('title', expect.stringContaining('đang có thú'));
      expect(remove).toBeDisabled();
      expect(remove).toHaveAttribute('title', expect.stringContaining('đang có thú'));
      expect(row).toHaveTextContent('Chuồng có thú: chưa bảo trì hay xóa được.');
      expect(within(row).getByRole('button', { name: 'Sửa C-01' })).toBeEnabled();
      expect(within(row).queryByRole('button', { name: 'Hết bảo trì C-01' })).not.toBeInTheDocument();
    });

    it('nút theo trạng thái: Trống có Bảo trì + Xóa; Bảo trì có Hết bảo trì + Xóa (không có Bảo trì)', async () => {
      renderPage();
      const available = await rowOf('C-03');
      expect(within(available).getByRole('button', { name: 'Bảo trì C-03' })).toBeEnabled();
      expect(within(available).getByRole('button', { name: 'Xóa C-03' })).toBeEnabled();
      expect(within(available).queryByRole('button', { name: 'Hết bảo trì C-03' })).not.toBeInTheDocument();
      const maintenance = await rowOf('C-04');
      expect(within(maintenance).getByRole('button', { name: 'Hết bảo trì C-04' })).toBeEnabled();
      expect(within(maintenance).getByRole('button', { name: 'Xóa C-04' })).toBeEnabled();
      expect(within(maintenance).queryByRole('button', { name: 'Bảo trì C-04' })).not.toBeInTheDocument();
    });

    it('chuyển C-03 sang bảo trì: xác nhận → cảnh báo thiếu chỗ liệt kê Bim và Bơ (theo ngày nhận), không tự hủy đặt chỗ', async () => {
      const booked = (await boardingApi.list({ status: 'BOOKED' })).content;
      const bim = booked.find((b) => b.petName === 'Bim')!;
      const bo = booked.find((b) => b.petName === 'Bơ')!;
      const user = renderPage();
      const seen = recordRequests();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' }));
      const confirm = await openDialog();
      expect(confirm).toHaveTextContent('Chuyển C-03 sang bảo trì');
      expect(confirm).toHaveTextContent('giảm từ 3 xuống 2');
      expect(confirm).toHaveTextContent('chỉ cảnh báo, không tự hủy');
      await user.click(within(confirm).getByRole('button', { name: 'Chuyển bảo trì' }));

      const warning = await screen.findByRole('region', { name: 'Cảnh báo thiếu chỗ' });
      expect(warning).toHaveTextContent('2 đặt chỗ');
      const items = within(warning).getAllByRole('listitem');
      expect(items).toHaveLength(2);
      expect(items[0]).toHaveTextContent(bim.code);
      expect(items[0]).toHaveTextContent('Bim');
      expect(items[0]).toHaveTextContent('26/10/2026');
      expect(items[0]).toHaveTextContent('28/10/2026');
      expect(items[1]).toHaveTextContent(bo.code);
      expect(items[1]).toHaveTextContent('Bơ');
      expect(items[1]).toHaveTextContent('30/10/2026');
      expect(items[1]).toHaveTextContent('01/11/2026');
      expect(warning).toHaveTextContent('không tự hủy');

      expect(seen.some((r) => r.method === 'post' && r.url === '/api/kennels/3/start-maintenance' && r.status === 200)).toBe(true);
      expect(toast.success).toHaveBeenCalledWith('Đã chuyển chuồng C-03 sang bảo trì');
      expect(within(await rowOf('C-03')).getByText('Bảo trì')).toBeInTheDocument();
      expect(stat(await summaryOf('Chuồng chó nhỏ'), 'Sức chứa')).toBe('2');
      // Cảnh báo, không hủy: hai đặt chỗ vẫn BOOKED.
      const after = (await boardingApi.list({ status: 'BOOKED' })).content.map((b) => b.bookingId);
      expect(after).toEqual(expect.arrayContaining([bim.bookingId, bo.bookingId]));
      // Làm mới danh sách (invalidate) không làm mất cảnh báo.
      expect(screen.getByRole('region', { name: 'Cảnh báo thiếu chỗ' })).toBeInTheDocument();
    });

    it('chuyển bảo trì không làm ai thiếu chỗ: báo không có đặt chỗ bị ảnh hưởng, không có khung cảnh báo', async () => {
      await boardingApi.createKennel({ kennelTypeId: 5, code: 'C-05' });
      const user = renderPage();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Chuyển bảo trì' }));
      expect(await screen.findByText(/Không có đặt chỗ nào bị thiếu chỗ/)).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'Cảnh báo thiếu chỗ' })).not.toBeInTheDocument();
      expect(toast.success).toHaveBeenCalledWith('Đã chuyển chuồng C-03 sang bảo trì');
    });

    it('khung cảnh báo đóng được bằng "Đã hiểu"', async () => {
      const user = renderPage();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Chuyển bảo trì' }));
      const warning = await screen.findByRole('region', { name: 'Cảnh báo thiếu chỗ' });
      await user.click(within(warning).getByRole('button', { name: 'Đã hiểu' }));
      expect(screen.queryByRole('region', { name: 'Cảnh báo thiếu chỗ' })).not.toBeInTheDocument();
    });

    it('hết bảo trì C-04: gọi end-maintenance, chuồng về Trống, sức chứa tăng, toast', async () => {
      const user = renderPage();
      const seen = recordRequests();
      await user.click(within(await rowOf('C-04')).getByRole('button', { name: 'Hết bảo trì C-04' }));
      await waitFor(async () => expect(within(await rowOf('C-04')).getByText('Trống')).toBeInTheDocument());
      expect(seen.some((r) => r.method === 'post' && r.url === '/api/kennels/4/end-maintenance' && r.status === 200)).toBe(true);
      expect(toast.success).toHaveBeenCalledWith('Đã hết bảo trì chuồng C-04');
      await waitFor(async () => expect(stat(await summaryOf('Chuồng chó nhỏ'), 'Sức chứa')).toBe('4'));
    });

    it('đang chạy một lệnh ghi thì khóa các nút khác của bảng, xong thì mở lại', async () => {
      let finish!: () => void;
      const real = boardingApi.endMaintenance;
      vi.spyOn(boardingApi, 'endMaintenance').mockImplementationOnce(async (id) => {
        await new Promise<void>((resolve) => (finish = resolve));
        return real(id);
      });
      const user = renderPage();
      await user.click(within(await rowOf('C-04')).getByRole('button', { name: 'Hết bảo trì C-04' }));
      await waitFor(() => expect(within(screen.getByRole('table')).getByRole('button', { name: 'Bảo trì C-03' })).toBeDisabled());
      expect(within(screen.getByRole('table')).getByRole('button', { name: 'Xóa C-03' })).toBeDisabled();
      expect(within(screen.getByRole('table')).getByRole('button', { name: 'Hết bảo trì C-04' })).toBeDisabled();
      // Hộp Sửa mở trong lúc lệnh đang chạy không bị đóng khi lệnh xong.
      await user.click(within(screen.getByRole('table')).getByRole('button', { name: 'Sửa C-03' }));
      expect(await openDialog()).toHaveTextContent('Sửa chuồng C-03');
      finish();
      await waitFor(() => expect(within(screen.getByRole('table')).getByRole('button', { name: 'Bảo trì C-03' })).toBeEnabled());
      expect(screen.getByRole('dialog')).toHaveTextContent('Sửa chuồng C-03');
      expect(within(await rowOf('C-04')).getByText('Trống')).toBeInTheDocument();
    });

    it('cảnh báo thiếu chỗ còn nguyên khi quản lý làm việc khác (hết bảo trì chuồng khác)', async () => {
      const user = renderPage();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Chuyển bảo trì' }));
      await screen.findByRole('region', { name: 'Cảnh báo thiếu chỗ' });
      await user.click(within(await rowOf('C-04')).getByRole('button', { name: 'Hết bảo trì C-04' }));
      await waitFor(async () => expect(within(await rowOf('C-04')).getByText('Trống')).toBeInTheDocument());
      expect(screen.getByRole('region', { name: 'Cảnh báo thiếu chỗ' })).toHaveTextContent('Chuồng C-03');
    });

    it('hết bảo trì đúng chuồng đang cảnh báo thì khung cảnh báo của nó biến mất', async () => {
      const user = renderPage();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Chuyển bảo trì' }));
      await screen.findByRole('region', { name: 'Cảnh báo thiếu chỗ' });
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Hết bảo trì C-03' }));
      await waitFor(() => expect(screen.queryByRole('region', { name: 'Cảnh báo thiếu chỗ' })).not.toBeInTheDocument());
    });

    it('người khác vừa đưa thú vào chuồng (409): hiện nguyên câu BE, đóng hộp xác nhận, làm mới hàng, nút không kẹt', async () => {
      const user = renderPage();
      await waitForList();
      patchStore((s) => (s.kennels.find((k) => k.kennelId === 3)!.status = 'OCCUPIED'));
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Chuyển bảo trì' }));
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Không thể chuyển trạng thái chuồng từ OCCUPIED sang MAINTENANCE');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(toast.error).toHaveBeenCalledWith('Không thể chuyển trạng thái chuồng từ OCCUPIED sang MAINTENANCE');
      await waitFor(async () => expect(within(await rowOf('C-03')).getByText('Đang có thú')).toBeInTheDocument());
      expect(within(await rowOf('C-03')).getByRole('button', { name: 'Bảo trì C-03' })).toBeDisabled();
      expect(screen.queryByRole('region', { name: 'Cảnh báo thiếu chỗ' })).not.toBeInTheDocument();
    });
  });

  describe('xóa chuồng', () => {
    it('C-04 (bảo trì, chưa có lịch sử): xác nhận nói rõ xóa không đổi sức chứa; xóa được, hàng biến mất, sức chứa vẫn 3', async () => {
      const user = renderPage();
      const seen = recordRequests();
      await user.click(within(await rowOf('C-04')).getByRole('button', { name: 'Xóa C-04' }));
      const confirm = await openDialog();
      expect(confirm).toHaveTextContent('Xóa chuồng C-04');
      expect(confirm).toHaveTextContent('không tính vào sức chứa');
      await user.click(within(confirm).getByRole('button', { name: 'Xóa chuồng' }));
      await waitFor(async () => expect(await codes()).toEqual(['C-01', 'C-02', 'C-03']));
      expect(seen.some((r) => r.method === 'delete' && r.url === '/api/kennels/4' && r.status === 204)).toBe(true);
      expect(toast.success).toHaveBeenCalledWith('Đã xóa chuồng C-04');
      expect(stat(await summaryOf('Chuồng chó nhỏ'), 'Sức chứa')).toBe('3');
    });

    it('C-03 (đang trống): văn bản khuyên chuyển bảo trì thay vì xóa và nói xóa làm giảm sức chứa ngay từ 3 xuống 2', async () => {
      const user = renderPage();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Xóa C-03' }));
      const confirm = await openDialog();
      expect(confirm).toHaveTextContent('giảm sức chứa');
      expect(confirm).toHaveTextContent('từ 3 xuống 2');
      expect(confirm).toHaveTextContent('chuyển bảo trì thay vì xóa');
      expect(confirm).toHaveTextContent('không cảnh báo');
    });

    it('chuồng đã có lịch sử lưu trú (409): nguyên câu BE, chuồng còn nguyên, nút không kẹt', async () => {
      const user = renderPage();
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Xóa C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Xóa chuồng' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Chuồng đã có lịch sử lưu trú — chuyển bảo trì thay vì xóa');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(await codes()).toEqual(['C-01', 'C-02', 'C-03', 'C-04']);
      expect(within(await rowOf('C-03')).getByRole('button', { name: 'Xóa C-03' })).toBeEnabled();
    });

    it('người khác vừa đưa thú vào chuồng (409): nguyên câu "Chuồng đang có thú, không xóa được" và hàng chuyển thành Đang có thú', async () => {
      const user = renderPage();
      await waitForList();
      patchStore((s) => (s.kennels.find((k) => k.kennelId === 3)!.status = 'OCCUPIED'));
      await user.click(within(await rowOf('C-03')).getByRole('button', { name: 'Xóa C-03' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Xóa chuồng' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Chuồng đang có thú, không xóa được');
      await waitFor(async () => expect(within(await rowOf('C-03')).getByText('Đang có thú')).toBeInTheDocument());
      expect(within(await rowOf('C-03')).getByRole('button', { name: 'Xóa C-03' })).toBeDisabled();
    });

    it('chuồng đã bị người khác xóa (404): hiện câu BE và làm mới danh sách', async () => {
      const user = renderPage();
      await waitForList();
      await apiClient.delete('/api/kennels/4');
      await user.click(within(await rowOf('C-04')).getByRole('button', { name: 'Xóa C-04' }));
      await user.click(within(await openDialog()).getByRole('button', { name: 'Xóa chuồng' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy chuồng #4');
      await waitFor(async () => expect(await codes()).toEqual(['C-01', 'C-02', 'C-03']));
    });
  });

  describe('quyền và truy vấn theo vai trò', () => {
    it('quản lý chi nhánh 1: chỉ gọi 3 endpoint được phép, không có 403, không có ô chọn chi nhánh', async () => {
      const seen = recordRequests();
      renderPage();
      await waitForList();
      await waitFor(() => expect(seen.map((r) => r.url)).toContain('/api/boarding-bookings'));
      expect(new Set(seen.map((r) => `${r.method} ${r.url}`))).toEqual(
        new Set(['get /api/kennels', 'get /api/public/services', 'get /api/boarding-bookings']),
      );
      expect(seen.every((r) => r.status === 200)).toBe(true);
      expect(seen.filter((r) => r.url === '/api/kennels')).toHaveLength(1);
      expect(seen.find((r) => r.url === '/api/public/services')!.params).toEqual({ branchId: 1 });
      expect(seen.find((r) => r.url === '/api/boarding-bookings')!.params).toEqual({ inStay: true, size: 100 });
      expect(screen.queryByLabelText(/chi nhánh/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('phiên không có chi nhánh: báo đăng nhập lại, không hiện liên kết tới /admin/branches/null', async () => {
      useSession.setState((s) => ({ account: { ...s.account!, branchId: null } }));
      renderPage();
      expect(await screen.findByText(/Phiên đăng nhập không có chi nhánh/)).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /Dịch vụ của chi nhánh/ })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Tạo chuồng' })).toBeDisabled();
    });

    it('vai trò khác BRANCH_MANAGER mở nhầm trang: giữ <h1>, báo không có quyền và không gọi endpoint nào của chuồng', async () => {
      await loginAs(ACCOUNTS.vet);
      const seen = recordRequests();
      renderPage();
      expect(screen.getByRole('heading', { level: 1, name: 'Chuồng lưu trú' })).toBeInTheDocument();
      expect(screen.getByText('Chỉ quản lý chi nhánh được quản lý chuồng lưu trú.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Tạo chuồng' })).not.toBeInTheDocument();
      await new Promise((r) => setTimeout(r, 30));
      expect(seen.filter((r) => r.url === '/api/kennels' || r.url === '/api/boarding-bookings')).toEqual([]);
    });
  });
});
