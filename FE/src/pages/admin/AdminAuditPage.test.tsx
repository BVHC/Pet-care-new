import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD, load, save } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminAuditPage } from './AdminAuditPage';

beforeAll(() => installMockApi(apiClient));

const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

const signIn = async (email: string) => {
  localStorage.clear();
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/audit']}>
        <AdminAuditPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Ghi lại mọi request qua apiClient (kể cả bị từ chối): đúng query và không có 403 / 400. */
function recordRequests() {
  const seen: { url: string; params: Record<string, unknown>; status: number }[] = [];
  const note = (config: { url?: string; params?: unknown }, status: number) =>
    seen.push({ url: config.url ?? '', params: (config.params ?? {}) as Record<string, unknown>, status });
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
const auditCalls = (seen: ReturnType<typeof recordRequests>) => seen.filter((r) => r.url === '/api/audit-logs');

/** Tạo dòng nhật ký bằng chính thao tác quản trị của mock (khóa tài khoản, sửa tham số) để kiểm đúng tên bảng / mã hành động mock ghi. */
async function makeAuditRows() {
  await apiClient.post('/api/accounts/4/lock', { reason: 'Thử khóa' });
  await apiClient.put('/api/system-configs/vet.bio_max_length', { value: '600' });
}

/** `datetime-local` (giờ máy) cách bây giờ `days` ngày; biên 3 ngày dư để múi giờ không làm lệch. */
const localIn = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 16);
const bodyRows = () => screen.getAllByRole('row').length - 1;

describe('AdminAuditPage (T22 - UC11)', () => {
  describe('ADMIN view (admin@petcare.vn)', () => {
    beforeEach(() => signIn('admin@petcare.vn'));

    it('renders audit log page and table', async () => {
      renderPage();
      expect(await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' })).toBeInTheDocument();
      expect(screen.getByLabelText('Mã người thực hiện')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('VD: STAFF_CREATED')).toBeInTheDocument();
    });

    it('filters audit entries', async () => {
      const user = userEvent.setup();
      renderPage();
      await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' });

      const actorInput = screen.getByLabelText('Mã người thực hiện');
      await user.type(actorInput, '1');

      // Filter input is updated
      expect(actorInput).toHaveValue(1);
    });

    // identity-v1 #36: from / to phải kèm offset; mock trả 400 MALFORMED_REQUEST nếu thiếu.
    it('lọc theo thời gian gửi from / to kèm offset +07:00 nên không bị 400', async () => {
      renderPage();
      await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' });

      fireEvent.change(screen.getByLabelText('Từ'), { target: { value: '2020-01-01T08:00' } });
      fireEvent.change(screen.getByLabelText('Đến'), { target: { value: '2099-01-01T08:00' } });

      await waitFor(() => expect(screen.queryByText('Đang tải…')).not.toBeInTheDocument());
      // Mock mới tinh chưa có dòng nhật ký nào; điều cần chứng minh là không có lỗi 400 thiếu offset.
      expect(screen.queryByText(/múi giờ/)).not.toBeInTheDocument();
    });

    it('tải trang không phát sinh 403', async () => {
      const seen = recordRequests();
      renderPage();
      await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' });
      await waitFor(() => expect(auditCalls(seen)).toHaveLength(1));
      expect(seen.filter((r) => r.status === 403)).toEqual([]);
      expect(auditCalls(seen)[0]).toMatchObject({ status: 200, params: { page: 0, size: 50 } });
    });

    // BE: entity_type là TÊN BẢNG (accounts, system_configs, …) và so khớp đúng; option cũ "Staff" / "Account" luôn ra trang rỗng.
    it('lọc theo loại đối tượng ra đúng dòng (không rỗng)', async () => {
      await makeAuditRows();
      const user = userEvent.setup();
      renderPage();
      expect(await screen.findByText('ACCOUNT_LOCKED')).toBeInTheDocument();
      expect(screen.getByText('SYSTEM_CONFIG_UPDATED')).toBeInTheDocument();

      await user.selectOptions(screen.getByLabelText('Loại đối tượng'), 'accounts');

      await waitFor(() => expect(screen.queryByText('SYSTEM_CONFIG_UPDATED')).not.toBeInTheDocument());
      const row = screen.getByText('ACCOUNT_LOCKED').closest('tr')!;
      expect(within(row).getByText('accounts #4')).toBeInTheDocument();

      await user.selectOptions(screen.getByLabelText('Loại đối tượng'), 'system_configs');
      expect(await screen.findByText('SYSTEM_CONFIG_UPDATED')).toBeInTheDocument();
      expect(screen.queryByText('ACCOUNT_LOCKED')).not.toBeInTheDocument();
    });

    it('ô loại đối tượng liệt kê đủ 16 tên bảng BE ghi, giá trị là tên bảng', async () => {
      renderPage();
      await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' });
      const values = within(screen.getByLabelText('Loại đối tượng')).getAllByRole('option').map((o) => (o as HTMLOptionElement).value);
      expect(values).toEqual([
        '', 'accounts', 'system_configs', 'notification_templates', 'customers', 'pets', 'booking_restrictions', 'products', 'services',
        'stock_receipts', 'stock_adjustments', 'orders', 'cashier_shifts', 'visits', 'medical_records', 'medical_record_addenda', 'prescription_items',
      ]);
    });

    // BE `action = :action` (so khớp đúng); có mã người thực hiện nên không cần from / to.
    it('hành động so khớp ĐÚNG, không phải tiền tố', async () => {
      await makeAuditRows();
      const user = userEvent.setup();
      renderPage();
      await screen.findByText('ACCOUNT_LOCKED');
      await user.type(screen.getByLabelText('Mã người thực hiện'), '1');

      await user.type(screen.getByLabelText('Hành động'), 'ACCOUNT_LOCK'); // tiền tố của ACCOUNT_LOCKED
      expect(await screen.findByText('Không có dòng nhật ký nào.')).toBeInTheDocument();

      await user.type(screen.getByLabelText('Hành động'), 'ED');
      expect(await screen.findByText('ACCOUNT_LOCKED')).toBeInTheDocument();
      expect(screen.queryByText('SYSTEM_CONFIG_UPDATED')).not.toBeInTheDocument();
    });

    // BE AuditLogFilterValidator R3: lọc action mà không có người thực hiện thì cần from + to cách ≤ 31 ngày, nếu không 400.
    it('lọc theo hành động không kèm người thực hiện: FE hướng dẫn và chưa gửi cho tới khi from / to hợp lệ (≤ 31 ngày)', async () => {
      await makeAuditRows();
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await screen.findByText('ACCOUNT_LOCKED');
      const before = auditCalls(seen).length;

      await user.type(screen.getByLabelText('Hành động'), 'ACCOUNT_LOCKED');

      expect(await screen.findByRole('alert')).toHaveTextContent('Lọc theo hành động cần chọn cả "Từ" và "Đến"');
      expect(screen.queryByText('ACCOUNT_LOCKED', { selector: 'span' })).not.toBeInTheDocument(); // không giữ dòng của lần lọc trước
      fireEvent.change(screen.getByLabelText('Từ'), { target: { value: localIn(-40) } });
      fireEvent.change(screen.getByLabelText('Đến'), { target: { value: localIn(3) } });
      expect(await screen.findByRole('alert')).toHaveTextContent('tối đa 31 ngày');
      expect(auditCalls(seen)).toHaveLength(before); // chưa có request nào mang action

      fireEvent.change(screen.getByLabelText('Từ'), { target: { value: localIn(-3) } });

      await waitFor(() => expect(auditCalls(seen).filter((r) => r.params.action)).toHaveLength(1));
      expect(auditCalls(seen).find((r) => r.params.action)).toMatchObject({ status: 200, params: { action: 'ACCOUNT_LOCKED' } });
      expect(await screen.findByText('ACCOUNT_LOCKED', { selector: 'span' })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(seen.filter((r) => r.status === 400)).toEqual([]);
    });

    it('có mã người thực hiện thì lọc theo hành động không cần from / to', async () => {
      await makeAuditRows();
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await screen.findByText('ACCOUNT_LOCKED');

      await user.type(screen.getByLabelText('Mã người thực hiện'), '1');
      await user.type(screen.getByLabelText('Hành động'), 'ACCOUNT_LOCKED');

      await waitFor(() => expect(auditCalls(seen).some((r) => r.params.action === 'ACCOUNT_LOCKED' && r.status === 200)).toBe(true));
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    // BE mặc định size 20, trang đầu là dòng mới nhất; trước đây FE chỉ có 50 dòng mới nhất và không đọc totalPages.
    it('hơn một trang: trang 2 thật sự gọi page=1, đổi bộ lọc thì về trang đầu', async () => {
      const s = load();
      const base = Date.now();
      s.audit = Array.from({ length: 120 }, (_, i) => ({
        id: 1000 - i,
        createdAt: new Date(base - i * 1000).toISOString(),
        actorAccountId: 1,
        actorEmail: 'admin@petcare.vn',
        action: i % 2 ? 'ACCOUNT_LOCKED' : 'SYSTEM_CONFIG_UPDATED',
        entityType: i % 2 ? 'accounts' : 'system_configs',
        entityId: i % 2 ? i : null,
        ipAddress: '127.0.0.1',
      }));
      save(s);
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await screen.findByText('Trang 1/3 · 120 dòng');
      expect(bodyRows()).toBe(50);

      await user.click(screen.getByRole('button', { name: 'Sau' }));

      await screen.findByText('Trang 2/3 · 120 dòng');
      expect(auditCalls(seen).map((r) => r.params.page)).toEqual([0, 1]);
      expect(bodyRows()).toBe(50);

      await user.selectOptions(screen.getByLabelText('Loại đối tượng'), 'accounts');

      await screen.findByText('Trang 1/2 · 60 dòng');
      expect(auditCalls(seen).at(-1)?.params).toMatchObject({ entityType: 'accounts', page: 0 });
    });
  });

  describe('Non-admin view (chuoi@petcare.vn)', () => {
    beforeEach(() => signIn('chuoi@petcare.vn'));

    it('shows access denied message for non-admin', async () => {
      renderPage();
      expect(await screen.findByText('Chỉ quản trị viên mới xem được nhật ký kiểm toán.')).toBeInTheDocument();
    });
  });
});
