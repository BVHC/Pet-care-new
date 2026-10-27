import { render, screen, within } from '@testing-library/react';
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
});
