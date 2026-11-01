import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD, load, save } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ROLE_LABELS } from '../../shared/types/auth';
import { AdminAccountsPage } from './AdminAccountsPage';

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
      <MemoryRouter initialEntries={['/admin/accounts']}>
        <AdminAccountsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Ghi lại mọi request qua apiClient (kể cả bị từ chối): đúng query và không có 403. */
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

/** Thêm `count` khách tự đăng ký (id 101…) vào state mock; 11 tài khoản nhân viên mẫu + khách = tổng. */
function seedCustomers(count: number) {
  const s = load();
  for (let i = 1; i <= count; i++) s.registered.push({ id: 100 + i, email: `kh${i}@seed.vn`, fullName: `Khách ${i}`, password: MOCK_PASSWORD, active: true });
  save(s);
}

const bodyRows = () => screen.getAllByRole('row').length - 1;

describe('AdminAccountsPage (T22 - UC09)', () => {
  beforeEach(() => signIn('admin@petcare.vn'));

  it('tải trang không phát sinh 403, gửi page=0 và size=20', async () => {
    const seen = recordRequests();
    renderPage();
    expect(await screen.findByText('admin@petcare.vn')).toBeInTheDocument();
    expect(seen.filter((r) => r.status === 403)).toEqual([]);
    expect(seen.find((r) => r.url === '/api/accounts')).toMatchObject({ status: 200, params: { page: 0, size: 20 } });
  });

  // BE: `q` chỉ tìm trong email (AccountRepository.findAccountPage), nên nhãn không được hứa tìm theo tên.
  it('ô tìm ghi "Tìm theo email" và chỉ khớp email, không khớp họ tên', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('chuoi@petcare.vn');
    const search = screen.getByLabelText('Tìm theo email');
    expect(search).toHaveAttribute('placeholder', 'Tìm theo email…');

    await user.type(search, 'Hoàng Nam'); // họ tên của manager@store1.vn
    expect(await screen.findByText('Không có tài khoản nào.')).toBeInTheDocument();

    await user.clear(search);
    await user.type(search, 'manager@store1');
    expect(await screen.findByText('manager@store1.vn')).toBeInTheDocument();
    expect(screen.queryByText('chuoi@petcare.vn')).not.toBeInTheDocument();
  });

  // BE mặc định size 20: trước đây FE không có nút chuyển trang nên tài khoản thứ 21 trở đi chỉ tìm được bằng ô tìm.
  it('hơn 20 tài khoản: trang 2 thật sự gọi page=1, đổi bộ lọc thì về trang đầu', async () => {
    seedCustomers(15); // 11 nhân viên mẫu + 15 khách = 26, sắp theo id nên khách cuối nằm ở trang 2
    const user = userEvent.setup();
    const seen = recordRequests();
    renderPage();
    await screen.findByText('Trang 1/2 · 26 tài khoản');
    expect(bodyRows()).toBe(20);
    expect(screen.queryByText('kh15@seed.vn')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Sau' }));

    await screen.findByText('Trang 2/2 · 26 tài khoản');
    expect(seen.filter((r) => r.url === '/api/accounts').map((r) => r.params.page)).toEqual([0, 1]);
    expect(bodyRows()).toBe(6);
    expect(within(screen.getByRole('table')).getByText('kh15@seed.vn')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc vai trò'), 'CUSTOMER');
    expect(await screen.findByText('kh1@seed.vn')).toBeInTheDocument(); // 15 khách vừa một trang: chân trang biến mất
    expect(screen.queryByText(/^Trang d/)).not.toBeInTheDocument();
    expect(seen.filter((r) => r.url === '/api/accounts').at(-1)?.params).toMatchObject({ role: 'CUSTOMER', page: 0 });
  });

  // Chạy với BE thật (T49): cột Vai trò hiện ROLE_LABELS nên ô lọc phải cùng tên, nếu không lọc "Phụ tá" ra toàn dòng "Nhân viên chăm sóc".
  it('ô lọc vai trò dùng cùng nhãn với cột Vai trò', async () => {
    renderPage();
    await screen.findByText('chuoi@petcare.vn');
    const options = within(screen.getByLabelText('Lọc vai trò'))
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(options).toEqual(['Mọi vai trò', ...(['ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER', 'CUSTOMER'] as const).map((r) => ROLE_LABELS[r])]);
  });

  // Đo trên BE thật 1/11: lỗi của lần khóa / mở khóa trước còn nguyên khi mở lại hộp thoại (mutation không reset), thanh lỗi cấp trang không bao giờ bị xóa.
  // Lỗi gây bằng lý do chỉ toàn khoảng trắng: qua được `required` của ô nhập nhưng BE (mock) trả 400 "Lý do không được để trống".
  describe('lỗi cũ không còn sót', () => {
    const BLANK = 'Lý do không được để trống';
    const LAN_CHI = 'reception@store1.vn';
    const MINH_ANH = 'doctor@store1.vn';
    type User = ReturnType<typeof userEvent.setup>;
    // Thanh lỗi cấp trang = `role="alert"` NGOÀI hộp thoại (FormError nằm trong hộp). `hidden` vì nền bị aria-hidden khi hộp thoại mở.
    const pageAlerts = () => screen.queryAllByRole('alert', { hidden: true }).filter((el) => !el.closest('[role="dialog"]'));
    const openDialog = async (user: User, action: 'Khóa' | 'Mở khóa', email: string) => {
      await user.click(await screen.findByRole('button', { name: `${action} ${email}` }));
      return screen.findByRole('dialog');
    };
    const submit = async (user: User, dialog: HTMLElement, reason: string, button: 'Khóa' | 'Mở khóa') => {
      const input = within(dialog).getByLabelText(/^Lý do/);
      await user.clear(input);
      await user.type(input, reason);
      await user.click(within(dialog).getByRole('button', { name: button }));
    };
    const cancel = async (user: User, dialog: HTMLElement) => {
      await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    };
    /** Khóa / mở khóa với lý do toàn khoảng trắng rồi Hủy: để lại FormError (trong hộp) + thanh lỗi cấp trang. */
    const failAndCancel = async (user: User, action: 'Khóa' | 'Mở khóa', email: string) => {
      const dialog = await openDialog(user, action, email);
      await submit(user, dialog, '   ', action);
      expect(await within(dialog).findByText(BLANK)).toBeInTheDocument(); // lần thất bại có thật
      await cancel(user, dialog);
      expect(pageAlerts().map((el) => el.textContent)).toEqual([BLANK]);
    };
    /** Khóa sẵn Lan Chi và BS. Minh Anh trong state mock (sau signIn: signIn xóa localStorage) để có dòng "Mở khóa". */
    const seedLocked = (...ids: number[]) => {
      const s = load();
      for (const id of ids) s.locks[id] = { reason: 'Thử khóa', statusBefore: 'ACTIVE', lockedAt: new Date().toISOString() };
      save(s);
    };

    it('khóa: lỗi của lần trước không còn khi mở lại hộp thoại (cùng dòng lẫn dòng khác)', async () => {
      const user = userEvent.setup();
      renderPage();
      await failAndCancel(user, 'Khóa', LAN_CHI);

      let dialog = await openDialog(user, 'Khóa', LAN_CHI);
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      await cancel(user, dialog);

      dialog = await openDialog(user, 'Khóa', MINH_ANH);
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    });

    it('mở khóa: lỗi của lần trước không còn khi mở lại hộp thoại (cùng dòng lẫn dòng khác)', async () => {
      seedLocked(4, 5);
      const user = userEvent.setup();
      renderPage();
      await failAndCancel(user, 'Mở khóa', LAN_CHI);

      let dialog = await openDialog(user, 'Mở khóa', LAN_CHI);
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      await cancel(user, dialog);

      dialog = await openDialog(user, 'Mở khóa', MINH_ANH);
      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    });

    it('thanh lỗi cấp trang của lần khóa hỏng biến mất khi mở hộp thoại khóa khác', async () => {
      const user = userEvent.setup();
      renderPage();
      await failAndCancel(user, 'Khóa', LAN_CHI);

      await openDialog(user, 'Khóa', MINH_ANH);

      expect(pageAlerts()).toHaveLength(0);
    });

    it('thanh lỗi cấp trang của lần khóa hỏng biến mất khi mở hộp thoại mở khóa', async () => {
      seedLocked(5);
      const user = userEvent.setup();
      renderPage();
      await failAndCancel(user, 'Khóa', LAN_CHI);

      await openDialog(user, 'Mở khóa', MINH_ANH);

      expect(pageAlerts()).toHaveLength(0);
    });

    // Thử lại ngay trong cùng hộp thoại (không mở lại) nên chỉ lần thành công mới có thể xóa thanh lỗi.
    it('thanh lỗi cấp trang biến mất khi khóa lại thành công trong cùng hộp thoại', async () => {
      const user = userEvent.setup();
      renderPage();
      const dialog = await openDialog(user, 'Khóa', LAN_CHI);
      await submit(user, dialog, '   ', 'Khóa');
      expect(await within(dialog).findByText(BLANK)).toBeInTheDocument();
      expect(pageAlerts().map((el) => el.textContent)).toEqual([BLANK]);

      await submit(user, dialog, 'Tài khoản có dấu hiệu bị chiếm', 'Khóa');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(pageAlerts()).toHaveLength(0);
    });

    it('thanh lỗi cấp trang biến mất khi mở khóa lại thành công trong cùng hộp thoại', async () => {
      seedLocked(4);
      const user = userEvent.setup();
      renderPage();
      const dialog = await openDialog(user, 'Mở khóa', LAN_CHI);
      await submit(user, dialog, '   ', 'Mở khóa');
      expect(await within(dialog).findByText(BLANK)).toBeInTheDocument();
      expect(pageAlerts().map((el) => el.textContent)).toEqual([BLANK]);

      await submit(user, dialog, 'Đã xác minh chủ tài khoản', 'Mở khóa');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(pageAlerts()).toHaveLength(0);
    });
  });
});
