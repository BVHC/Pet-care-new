import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD, load, save } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminUsersPage } from './AdminUsersPage';

// API_MOCK đọc lúc render: cho phép ca "BE thật" (mock tắt) mà request vẫn đi qua mock-api.
const apiMode = vi.hoisted(() => ({ mock: true }));
vi.mock('../../shared/api/axios', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../shared/api/axios')>();
  return Object.defineProperty({ ...original }, 'API_MOCK', { get: () => apiMode.mock, enumerable: true });
});

beforeAll(() => installMockApi(apiClient));

const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
  apiMode.mock = true;
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
      <MemoryRouter initialEntries={['/admin/users']}>
        <AdminUsersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

interface Seen {
  method: string;
  url: string;
  params: Record<string, unknown>;
  body: Record<string, unknown> | undefined;
  status: number;
}

/** Ghi lại mọi request qua apiClient (kể cả bị từ chối) để kiểm đúng query / body và không có 403. */
function recordRequests(): Seen[] {
  const seen: Seen[] = [];
  const note = (config: { method?: string; url?: string; params?: unknown; data?: unknown }, status: number) =>
    seen.push({
      method: (config.method ?? '').toUpperCase(),
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

/** Thêm `count` lễ tân chi nhánh 1 vào state mock (sau signIn: signIn xóa localStorage) để danh sách vượt một trang (20). */
function seedReceptionists(count: number) {
  const s = load();
  for (let i = 1; i <= count; i++) {
    const id = 300 + i;
    s.extraStaff[id] = { id, email: `nv${i}@seed.vn`, fullName: `Nhân viên ${String(i).padStart(2, '0')}`, role: 'RECEPTIONIST', status: 'ACTIVE', branchId: 1, mustChangePassword: false };
  }
  save(s);
}

type ExtraStaff = ReturnType<typeof load>['extraStaff'][number];

/** Thêm nhân viên chạy-lúc vào state mock (sau signIn). Nhân viên seed đổi trạng thái thì còn lại tới hết file, nhân viên chạy-lúc thì reset mỗi test. */
function seedStaff(...rows: Array<Partial<ExtraStaff> & Pick<ExtraStaff, 'id' | 'fullName' | 'role'>>) {
  const s = load();
  for (const r of rows) s.extraStaff[r.id] = { email: `nv${r.id}@seed.vn`, status: 'ACTIVE', mustChangePassword: false, ...r };
  save(s);
}

/** Khóa sẵn các tài khoản (state mock, sau signIn) để thao tác lên chúng bị BE từ chối vì đang bị khóa. */
function lockAccounts(...ids: number[]) {
  const s = load();
  for (const id of ids) s.locks[id] = { reason: 'Thử khóa', statusBefore: 'ACTIVE', lockedAt: new Date().toISOString() };
  save(s);
}

type User = ReturnType<typeof userEvent.setup>;
const openDialogOf = async (user: User, name: string | RegExp) => {
  await user.click(await screen.findByRole('button', { name }));
  return screen.findByRole('dialog');
};
const cancelDialog = async (user: User, dialog: HTMLElement) => {
  await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
};

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;
const optionsOf = (label: string) => within(screen.getByLabelText(label)).getAllByRole('option').map((o) => o.textContent);
const bodyRows = () => screen.getAllByRole('row').length - 1;

describe('AdminUsersPage (T22 - UC08)', () => {
  describe('ADMIN view (admin@petcare.vn)', () => {
    beforeEach(() => signIn('admin@petcare.vn'));

    // BE StaffQueryService.visibleRoles: ADMIN chỉ thấy SUPER_MANAGER (mock cũ cho ADMIN thấy cả bác sĩ nên che lỗi bộ lọc).
    it('chỉ thấy Quản lý chuỗi, không có ô lọc chi nhánh, bộ lọc vai trò / trạng thái chỉ có giá trị BE chấp nhận', async () => {
      renderPage();
      const row = await rowOf('Đỗ Thanh Hà');
      expect(within(row).getByText('Quản lý chuỗi')).toBeInTheDocument();
      expect(within(row).getByText('—')).toBeInTheDocument();
      expect(screen.queryByText('BS. Minh Anh')).not.toBeInTheDocument();
      expect(screen.queryByText('Quản trị hệ thống')).not.toBeInTheDocument();

      expect(optionsOf('Lọc vai trò')).toEqual(['Mọi vai trò', 'Quản lý chuỗi']);
      expect(optionsOf('Lọc trạng thái')).toEqual(['Mọi trạng thái', 'Đang hoạt động', 'Vô hiệu hóa']);
      expect(screen.queryByLabelText('Lọc chi nhánh')).not.toBeInTheDocument();
    });

    // BR-QT-01: ADMIN chỉ tạo SUPER_MANAGER (không thuộc chi nhánh).
    it('ADMIN chỉ thấy vai trò Quản lý chuỗi, không có ô chi nhánh, và tạo được tài khoản', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      const roles = within(within(dialog).getByLabelText('Vai trò *')).getAllByRole('option');
      expect(roles.map((o) => o.textContent)).toEqual(['Quản lý chuỗi']);
      expect(within(dialog).queryByLabelText(/Chi nhánh/)).not.toBeInTheDocument();

      await user.type(within(dialog).getByLabelText('Họ tên *'), 'Quản lý chuỗi mới');
      await user.type(within(dialog).getByLabelText('Email *'), 'sm2@petcare.vn');
      await user.type(within(dialog).getByLabelText('Số điện thoại *'), '0900000099');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));

      expect(await screen.findByText('Quản lý chuỗi mới')).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });

    // Mutation của hộp thoại không reset khi đóng: FormError của lần tạo hỏng trước hiện lại khi mở lại (đo trên BE thật 1/11).
    it('tạo người dùng: lỗi email trùng của lần trước không còn khi mở lại hộp thoại', async () => {
      const user = userEvent.setup();
      renderPage();
      let dialog = await openDialogOf(user, /Thêm người dùng/);
      await user.type(within(dialog).getByLabelText('Họ tên *'), 'Quản lý trùng email');
      await user.type(within(dialog).getByLabelText('Email *'), 'chuoi@petcare.vn'); // email của Đỗ Thanh Hà
      await user.type(within(dialog).getByLabelText('Số điện thoại *'), '0900000077');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));
      expect(await within(dialog).findByText('Email đã được sử dụng')).toBeInTheDocument(); // lần thất bại có thật
      await cancelDialog(user, dialog);

      dialog = await openDialogOf(user, /Thêm người dùng/);

      expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    });

    // BE StaffController: disable / reactivate / resend / email-change đều @PreAuthorize(STAFF_ADMINS) gồm ADMIN, StaffHierarchyPolicy.manages: ADMIN → SUPER_MANAGER (BR-QT-07, BR-TK-16 → BR-QT-01).
    it('ADMIN có Sửa email và Vô hiệu hóa trên dòng Quản lý chuỗi; Gửi lại MK chỉ khi chưa đổi mật khẩu lần đầu', async () => {
      seedStaff({ id: 410, fullName: 'Quản lý chuỗi mới', role: 'SUPER_MANAGER', mustChangePassword: true });
      renderPage();
      await rowOf('Đỗ Thanh Hà');

      expect(screen.getByRole('button', { name: 'Sửa email Đỗ Thanh Hà' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Vô hiệu hóa Đỗ Thanh Hà' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Gửi lại MK cho Đỗ Thanh Hà' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Gửi lại MK cho Quản lý chuỗi mới' })).toBeInTheDocument();
    });

    // BE StaffAccountService.resend: đích không ACTIVE → 409; Account.disable() giữ mustChangePassword nên cờ này không đủ để ẩn nút.
    it('dòng đã vô hiệu hóa có Kích hoạt lại nhưng KHÔNG có Gửi lại MK dù chưa đổi mật khẩu lần đầu', async () => {
      seedStaff({ id: 410, fullName: 'Quản lý chuỗi tắt', role: 'SUPER_MANAGER', status: 'DISABLED', mustChangePassword: true });
      renderPage();
      await rowOf('Quản lý chuỗi tắt');

      expect(screen.getByRole('button', { name: 'Kích hoạt lại Quản lý chuỗi tắt' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Gửi lại MK cho Quản lý chuỗi tắt' })).not.toBeInTheDocument();
    });

    // BE reactivate: SUPER_MANAGER không thuộc chi nhánh nào, body có branchId → 400 BR-QT-03; nên dialog không được mời chọn chi nhánh.
    it('ADMIN vô hiệu hóa rồi kích hoạt lại Quản lý chuỗi: hộp thoại không có ô "Đổi chi nhánh", request mang branchId null', async () => {
      seedStaff({ id: 410, fullName: 'Quản lý chuỗi phụ', role: 'SUPER_MANAGER' });
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Vô hiệu hóa Quản lý chuỗi phụ' }));
      await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vô hiệu hóa' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      await user.click(await screen.findByRole('button', { name: 'Kích hoạt lại Quản lý chuỗi phụ' }));

      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).queryByText('Đổi chi nhánh')).not.toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Kích hoạt lại' }));

      await waitFor(() => expect(seen.find((r) => r.url === '/api/staff/410/reactivate')).toMatchObject({ method: 'POST', status: 200, body: { branchId: null } }));
      expect(seen.filter((r) => r.status === 403)).toEqual([]);
    });

    it('tải trang không phát sinh 403', async () => {
      const seen = recordRequests();
      renderPage();
      await screen.findByText('Đỗ Thanh Hà');
      await waitFor(() => expect(seen.some((r) => r.url === '/api/branches')).toBe(true));
      expect(seen.filter((r) => r.status === 403)).toEqual([]);
      expect(seen.find((r) => r.url === '/api/staff')).toMatchObject({ method: 'GET', status: 200, params: { page: 0, size: 20 } });
    });
  });

  describe('SUPER_MANAGER view (chuoi@petcare.vn)', () => {
    beforeEach(() => signIn('chuoi@petcare.vn'));

    it('thấy nhân viên A05–A08 mọi chi nhánh (không thấy ADMIN, không thấy chính mình), có đổi chức vụ / điều chuyển', async () => {
      renderPage();
      const doctorRow = await rowOf('BS. Minh Anh');
      expect(within(doctorRow).getByText('Bác sĩ thú y')).toBeInTheDocument();
      expect(within(doctorRow).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
      expect(within(doctorRow).getByText('Đang hoạt động')).toBeInTheDocument();
      expect(await screen.findByText('Phạm Minh Đức')).toBeInTheDocument();
      expect(screen.queryByText('Quản trị hệ thống')).not.toBeInTheDocument();
      expect(screen.queryByText('Đỗ Thanh Hà')).not.toBeInTheDocument();

      expect(screen.getByRole('button', { name: 'Đổi chức vụ BS. Minh Anh' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Điều chuyển BS. Minh Anh' })).toBeInTheDocument();
    });

    it('bộ lọc vai trò chỉ có A05–A08, trạng thái không có "Chờ xác thực", ô lọc chi nhánh còn dùng được', async () => {
      renderPage();
      await screen.findByText('BS. Minh Anh');
      expect(optionsOf('Lọc vai trò')).toEqual(['Mọi vai trò', 'Quản lý chi nhánh', 'Lễ tân', 'Bác sĩ thú y', 'Nhân viên chăm sóc']);
      expect(optionsOf('Lọc trạng thái')).toEqual(['Mọi trạng thái', 'Đang hoạt động', 'Vô hiệu hóa']);
      expect(screen.getByLabelText('Lọc chi nhánh')).toBeEnabled();
    });

    it('searches staff by name or email', async () => {
      const user = userEvent.setup();
      renderPage();
      await screen.findByText('BS. Minh Anh');

      await user.type(screen.getByPlaceholderText('Tìm theo tên hoặc email…'), 'doctor@store1.vn');

      await waitFor(() => {
        expect(screen.queryByText('Lan Chi')).not.toBeInTheDocument();
      }, { timeout: 2000 });
      expect(screen.getByText('BS. Minh Anh')).toBeInTheDocument();
    });

    it('opens staff detail modal showing info and VET bio/specialty', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Xem chi tiết BS. Minh Anh' }));

      const modal = screen.getByRole('dialog');
      expect(within(modal).getByText('Chi tiết nhân viên')).toBeInTheDocument();
      expect(within(modal).getByText('doctor@store1.vn')).toBeInTheDocument();
      expect(within(modal).getByText('Chuyên môn')).toBeInTheDocument();
      expect(within(modal).getByText('Nội khoa, da liễu thú y')).toBeInTheDocument();
      expect(within(modal).getByText('Hơn 8 năm khám và điều trị chó mèo, quan tâm đặc biệt tới bệnh da và dị ứng.')).toBeInTheDocument();
    });

    // BR-QT-01: SUPER_MANAGER không tạo được SUPER_MANAGER; vai trò ngoài quyền không hiển thị để chọn.
    it('không có vai trò Quản lý chuỗi trong form tạo, nhưng có Quản lý chi nhánh và ô chi nhánh bắt buộc', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      const roles = within(within(dialog).getByLabelText('Vai trò *')).getAllByRole('option').map((o) => o.textContent);
      expect(roles).toEqual(['Quản lý chi nhánh', 'Lễ tân', 'Bác sĩ thú y', 'Nhân viên chăm sóc']);
      expect(within(dialog).getByLabelText('Chi nhánh *')).toBeRequired();
    });

    it('SUPER_MANAGER tạo nhân viên: request vẫn mang branchId đã chọn', async () => {
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      await user.type(within(dialog).getByLabelText('Họ tên *'), 'Lễ tân CN1');
      await user.type(within(dialog).getByLabelText('Email *'), 'letan.cn1@new.vn');
      await user.type(within(dialog).getByLabelText('Số điện thoại *'), '0900000088');
      await user.selectOptions(within(dialog).getByLabelText('Chi nhánh *'), '1');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));

      await waitFor(() => expect(seen.find((r) => r.method === 'POST' && r.url === '/api/staff')).toMatchObject({ status: 201, body: { role: 'RECEPTIONIST', branchId: 1 } }));
    });

    // L-23: ô `required` chưa chọn thì trình duyệt tự hiện bong bóng theo ngôn ngữ của nó (Chrome tiếng Anh: "Please select an item in the list.").
    it('chưa chọn chi nhánh: lời báo của ô là tiếng Việt, không gửi gì; chọn xong thì hết báo', async () => {
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      await user.type(within(dialog).getByLabelText('Họ tên *'), 'Lễ tân CN1');
      await user.type(within(dialog).getByLabelText('Email *'), 'letan.cn1@new.vn');
      await user.type(within(dialog).getByLabelText('Số điện thoại *'), '0900000088');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));

      const branch = within(dialog).getByLabelText('Chi nhánh *') as HTMLSelectElement;
      expect(branch.validationMessage).toBe('Vui lòng chọn chi nhánh');
      expect(seen.find((r) => r.method === 'POST' && r.url === '/api/staff')).toBeUndefined();

      await user.selectOptions(branch, '1');
      expect(branch.validationMessage).toBe('');
    });

    // BE StaffAdminMapper: maskedEmail của email-change nhân viên luôn null (chỉ luồng liên kết hồ sơ có), mã gửi tới chính email vừa nhập.
    const openOtpStep = async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Sửa email BS. Minh Anh' }));
      const dialog = await screen.findByRole('dialog');
      await user.type(within(dialog).getByLabelText('Email mới *'), 'moi@example.vn');
      await user.click(within(dialog).getByRole('button', { name: 'Gửi mã OTP' }));
      return dialog;
    };

    it('sửa email: bước OTP nêu email mới vừa nhập, không có "null", mock bật thì nhắc mã 123456', async () => {
      const dialog = await openOtpStep();

      expect(await within(dialog).findByText(/Mã đã gửi tới moi@example\.vn\./)).toBeInTheDocument();
      expect(dialog).not.toHaveTextContent('null');
      expect(dialog).toHaveTextContent('Mã mock: 123456');
    });

    it('sửa email trên BE thật (mock tắt): bước OTP không nhắc mã mock', async () => {
      apiMode.mock = false;
      const dialog = await openOtpStep();

      expect(await within(dialog).findByText(/Mã đã gửi tới moi@example\.vn\./)).toBeInTheDocument();
      expect(dialog).not.toHaveTextContent('Mã mock');
    });

    // Đo trên BE thật 1/11: thanh lỗi đỏ ở đầu trang (notice) đặt khi một thao tác lỗi và không bao giờ bị xóa, còn nguyên sau nhiều thao tác thành công.
    it('thanh lỗi của thao tác trước biến mất khi mở thao tác mới', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Sửa email BS. Minh Anh' }));
      const email = await screen.findByRole('dialog');
      await user.type(within(email).getByLabelText('Email mới *'), 'moi@example.vn');
      await user.click(within(email).getByRole('button', { name: 'Gửi mã OTP' }));
      await user.type(await within(email).findByLabelText('Mã OTP *'), '000000');
      await user.click(within(email).getByRole('button', { name: 'Xác nhận' }));
      await user.click(await within(email).findByRole('button', { name: 'Hủy' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      // Thanh lỗi cấp trang là `role="alert"` nằm NGOÀI hộp thoại (FormError trong hộp và toast cùng câu không tính). `hidden` vì nền bị aria-hidden khi hộp thoại mở.
      const banners = () => screen.queryAllByRole('alert', { hidden: true })
        .filter((el) => el.textContent === 'OTP không đúng hoặc đã hết hạn' && !el.closest('[role="dialog"]'));
      expect(banners()).toHaveLength(1); // còn lại sau khi đóng hộp

      await user.click(screen.getByRole('button', { name: 'Vô hiệu hóa BS. Minh Anh' }));

      expect(banners()).toHaveLength(0);
    });

    // Đối chứng với ca ADMIN → Quản lý chuỗi: đích thuộc chi nhánh thì vẫn được đổi chi nhánh khi kích hoạt lại.
    it('kích hoạt lại nhân viên chi nhánh: hộp thoại vẫn có ô "Đổi chi nhánh"', async () => {
      seedStaff({ id: 411, fullName: 'Lễ tân tắt', role: 'RECEPTIONIST', status: 'DISABLED', branchId: 1 });
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Kích hoạt lại Lễ tân tắt' }));

      expect(within(await screen.findByRole('dialog')).getByText('Đổi chi nhánh')).toBeInTheDocument();
    });

    // ReactivateDialog luôn được render và state trong dialog không bao giờ reset: tick "Đổi chi nhánh" ở dòng A còn lại khi mở dòng B.
    it('kích hoạt lại: lựa chọn "Đổi chi nhánh" của dòng này không còn lại khi mở dòng khác', async () => {
      seedStaff(
        { id: 411, fullName: 'Lễ tân tắt A', role: 'RECEPTIONIST', status: 'DISABLED', branchId: 1 },
        { id: 412, fullName: 'Lễ tân tắt B', role: 'RECEPTIONIST', status: 'DISABLED', branchId: 1 },
      );
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Kích hoạt lại Lễ tân tắt A' }));
      const first = await screen.findByRole('dialog');
      await user.click(within(first).getByText('Đổi chi nhánh'));
      expect(within(first).getByLabelText('Chi nhánh mới *')).toBeInTheDocument();
      await user.click(within(first).getByRole('button', { name: 'Hủy' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      await user.click(screen.getByRole('button', { name: 'Kích hoạt lại Lễ tân tắt B' }));
      const second = await screen.findByRole('dialog');
      expect(within(second).queryByLabelText('Chi nhánh mới *')).not.toBeInTheDocument();
    });

    // Cả 4 hộp thoại còn lại luôn được render và mutation KHÔNG reset khi đóng: FormError của lần thất bại trước hiện lại khi mở lại (đo trên BE thật 1/11).
    // Mỗi ca gây lỗi thật bằng một luật của mock (không dựng giả), rồi đóng và mở lại cho một dòng KHÁC.
    describe('FormError của lần thất bại trước không còn khi mở lại hộp thoại', () => {
      const LAST_MANAGER = 'Chi nhánh phải có ít nhất một quản lý đang hoạt động'; // Hoàng Nam là quản lý duy nhất của chi nhánh 1 (BR-QT-04)

      it('sửa email: nhập sai OTP rồi đóng, mở lại cho dòng khác', async () => {
        const user = userEvent.setup();
        renderPage();
        let dialog = await openDialogOf(user, 'Sửa email BS. Minh Anh');
        await user.type(within(dialog).getByLabelText('Email mới *'), 'moi@example.vn');
        await user.click(within(dialog).getByRole('button', { name: 'Gửi mã OTP' }));
        await user.type(await within(dialog).findByLabelText('Mã OTP *'), '000000');
        await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }));
        expect(await within(dialog).findByText('OTP không đúng hoặc đã hết hạn')).toBeInTheDocument(); // lần thất bại có thật
        await cancelDialog(user, dialog);

        dialog = await openDialogOf(user, 'Sửa email Lan Chi');
        await within(dialog).findByLabelText('Email mới *'); // hộp đặt lại về bước nhập email sau 200 ms

        expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      });

      // Hộp Sửa email có hai mutation (gửi mã, xác nhận mã); ca trên chỉ làm hỏng bước xác nhận, ca này làm hỏng bước gửi.
      it('sửa email: gửi mã tới email đã dùng bị từ chối, mở lại cho dòng khác không còn lỗi', async () => {
        const user = userEvent.setup();
        renderPage();
        let dialog = await openDialogOf(user, 'Sửa email BS. Minh Anh');
        await user.type(within(dialog).getByLabelText('Email mới *'), 'reception@store1.vn'); // email của Lan Chi
        await user.click(within(dialog).getByRole('button', { name: 'Gửi mã OTP' }));
        expect(await within(dialog).findByText('Email đã được sử dụng')).toBeInTheDocument(); // lần thất bại có thật
        await cancelDialog(user, dialog);

        dialog = await openDialogOf(user, 'Sửa email Lan Chi');

        expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      });

      it('đổi chức vụ: đổi quản lý chi nhánh cuối cùng bị từ chối, mở lại cho dòng khác không còn lỗi', async () => {
        const user = userEvent.setup();
        renderPage();
        let dialog = await openDialogOf(user, 'Đổi chức vụ Hoàng Nam');
        await user.selectOptions(within(dialog).getByLabelText('Vai trò mới *'), 'RECEPTIONIST');
        await user.click(within(dialog).getByRole('button', { name: 'Đổi chức vụ' }));
        expect(await within(dialog).findByText(LAST_MANAGER)).toBeInTheDocument();
        await cancelDialog(user, dialog);

        dialog = await openDialogOf(user, 'Đổi chức vụ BS. Minh Anh');

        expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      });

      it('điều chuyển: chuyển quản lý chi nhánh cuối cùng bị từ chối, mở lại cho dòng khác không còn lỗi', async () => {
        const user = userEvent.setup();
        renderPage();
        let dialog = await openDialogOf(user, 'Điều chuyển Hoàng Nam');
        await user.selectOptions(within(dialog).getByLabelText('Chi nhánh mới *'), '2');
        await user.click(within(dialog).getByRole('button', { name: 'Điều chuyển' }));
        expect(await within(dialog).findByText(LAST_MANAGER)).toBeInTheDocument();
        await cancelDialog(user, dialog);

        dialog = await openDialogOf(user, 'Điều chuyển BS. Minh Anh');

        expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      });

      it('kích hoạt lại: tài khoản đang bị khóa bị từ chối, mở lại cho dòng khác không còn lỗi', async () => {
        seedStaff(
          { id: 411, fullName: 'Lễ tân tắt A', role: 'RECEPTIONIST', status: 'DISABLED', branchId: 1 },
          { id: 412, fullName: 'Lễ tân tắt B', role: 'RECEPTIONIST', status: 'DISABLED', branchId: 1 },
        );
        lockAccounts(411, 412); // BR-QT-10
        const user = userEvent.setup();
        renderPage();
        let dialog = await openDialogOf(user, 'Kích hoạt lại Lễ tân tắt A');
        await user.click(within(dialog).getByRole('button', { name: 'Kích hoạt lại' }));
        expect(await within(dialog).findByText('Tài khoản đang bị khóa, cần mở khóa trước')).toBeInTheDocument();
        await cancelDialog(user, dialog);

        dialog = await openDialogOf(user, 'Kích hoạt lại Lễ tân tắt B');

        expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
      });
    });

    it('điều chuyển: ô chi nhánh mới bắt buộc (không gửi branchId = 0)', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Điều chuyển BS. Minh Anh' }));

      expect(within(await screen.findByRole('dialog')).getByLabelText('Chi nhánh mới *')).toBeRequired();
    });

    // L-23: cùng lý do với ô "Chi nhánh *" của form tạo: lời báo của trình duyệt phải là tiếng Việt.
    it('điều chuyển: chưa chọn chi nhánh mới thì lời báo của ô là tiếng Việt, chọn xong thì hết báo', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Điều chuyển BS. Minh Anh' }));
      const dialog = await screen.findByRole('dialog');
      const branch = within(dialog).getByLabelText('Chi nhánh mới *') as HTMLSelectElement;
      await user.click(within(dialog).getByRole('button', { name: 'Điều chuyển' }));

      expect(branch.validationMessage).toBe('Vui lòng chọn chi nhánh mới');
      await user.selectOptions(branch, '2');
      expect(branch.validationMessage).toBe('');
    });

    it('tải trang không phát sinh 403', async () => {
      const seen = recordRequests();
      renderPage();
      await screen.findByText('BS. Minh Anh');
      expect(seen.filter((r) => r.status === 403)).toEqual([]);
    });

    // BE mặc định size 20: trước đây FE không gửi page / size và không có nút chuyển trang nên nhân viên thứ 21 trở đi không bao giờ thấy.
    it('hơn 20 nhân viên: trang 2 thật sự gọi page=1, đổi bộ lọc thì về trang đầu', async () => {
      seedReceptionists(22); // 9 nhân viên mẫu + 22 = 31 → trang 2 còn 11
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await screen.findByText('Trang 1/2 · 31 nhân viên');
      expect(bodyRows()).toBe(20);
      expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Sau' }));

      await screen.findByText('Trang 2/2 · 31 nhân viên');
      expect(seen.filter((r) => r.url === '/api/staff').map((r) => r.params.page)).toEqual([0, 1]);
      expect(bodyRows()).toBe(11);
      expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();

      await user.selectOptions(screen.getByLabelText('Lọc vai trò'), 'RECEPTIONIST');

      await waitFor(() => expect(seen.filter((r) => r.url === '/api/staff').at(-1)?.params).toMatchObject({ role: 'RECEPTIONIST', page: 0 }));
      await screen.findByText('Trang 1/2 · 24 nhân viên'); // 2 lễ tân mẫu + 22
    });
  });

  describe('BRANCH_MANAGER view (manager@store1.vn)', () => {
    beforeEach(() => signIn('manager@store1.vn'));

    it('thấy nhân viên A06–A08 của CN1 (không thấy chính mình, SUPER_MANAGER, chi nhánh khác), không đổi chức vụ / điều chuyển', async () => {
      renderPage();
      expect(await screen.findByText('BS. Minh Anh')).toBeInTheDocument();
      expect(await screen.findByText('Lan Chi')).toBeInTheDocument();

      expect(screen.queryByText('Hoàng Nam')).not.toBeInTheDocument(); // BM không thuộc A06–A08
      expect(screen.queryByText(/Đỗ Thanh Hà/)).not.toBeInTheDocument();
      expect(screen.queryByText(/Phạm Minh Đức/)).not.toBeInTheDocument();

      // Ô lọc chi nhánh bị khóa với quản lý chi nhánh
      expect(screen.getByLabelText('Lọc chi nhánh')).toBeDisabled();
      expect(optionsOf('Lọc vai trò')).toEqual(['Mọi vai trò', 'Lễ tân', 'Bác sĩ thú y', 'Nhân viên chăm sóc']);

      expect(screen.queryByRole('button', { name: /Đổi chức vụ/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Điều chuyển/ })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Vô hiệu hóa BS. Minh Anh' })).toBeInTheDocument();
    });

    it('tải trang không phát sinh 403', async () => {
      const seen = recordRequests();
      renderPage();
      await screen.findByText('BS. Minh Anh');
      expect(seen.filter((r) => r.status === 403)).toEqual([]);
    });

    // BE login không trả branchId (chỉ GET /me → staffProfile.branchId): phiên thật không có branchId.
    it('phiên KHÔNG có branchId: chi nhánh của mình lấy từ GET /me (câu "Chỉ thấy nhân viên thuộc …")', async () => {
      useSession.setState((s) => ({ account: { ...s.account!, branchId: undefined } }));
      const seen = recordRequests();
      renderPage();

      expect(await screen.findByText('PetCare Cầu Giấy', { selector: 'strong' })).toBeInTheDocument();
      expect(seen.some((r) => r.url === '/api/me' && r.status === 200)).toBe(true);
    });

    // BE CreateStaffRequest.branchId @Positive: gửi 0 là 400; BRANCH_MANAGER không gửi = chi nhánh của mình.
    it('tạo nhân viên: request KHÔNG mang branchId (kể cả khi phiên không có branchId), ô chi nhánh hiện chi nhánh của mình', async () => {
      useSession.setState((s) => ({ account: { ...s.account!, branchId: undefined } }));
      const user = userEvent.setup();
      const seen = recordRequests();
      renderPage();
      await screen.findByText('PetCare Cầu Giấy', { selector: 'strong' });
      await user.click(screen.getByRole('button', { name: /Thêm người dùng/ }));

      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByLabelText('Chi nhánh')).toBeDisabled();
      expect(within(dialog).getByLabelText('Chi nhánh')).toHaveValue('1');
      await user.type(within(dialog).getByLabelText('Họ tên *'), 'Lễ tân do BM tạo');
      await user.type(within(dialog).getByLabelText('Email *'), 'bm.tao@new.vn');
      await user.type(within(dialog).getByLabelText('Số điện thoại *'), '0900000066');
      await user.click(within(dialog).getByRole('button', { name: 'Tạo tài khoản' }));

      expect(await screen.findByText('Lễ tân do BM tạo')).toBeInTheDocument();
      const post = seen.find((r) => r.method === 'POST' && r.url === '/api/staff')!;
      expect(post.status).toBe(201);
      expect(post.body).not.toHaveProperty('branchId');
    });

    // Hai test dưới bấm xác nhận thật: bản cũ gọi hook trong onConfirm nên mutation không bao giờ chạy.
    it('vô hiệu hóa: bấm xác nhận gọi API, dòng chuyển sang Kích hoạt lại', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Vô hiệu hóa Lan Chi' }));

      const dialog = await screen.findByRole('dialog');
      await user.click(within(dialog).getByRole('button', { name: 'Vô hiệu hóa' }));

      expect(await screen.findByRole('button', { name: 'Kích hoạt lại Lan Chi' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Vô hiệu hóa Lan Chi' })).not.toBeInTheDocument();
    });

    it('gửi lại mật khẩu ban đầu: bấm xác nhận gọi API và đóng hộp thoại', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Gửi lại MK cho Lễ tân mới' }));

      const dialog = await screen.findByRole('dialog');
      await user.click(within(dialog).getByRole('button', { name: 'Gửi lại' }));

      // Lỗi thì hộp thoại ở lại (handleError); thành công thì setResend(null) đóng nó.
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });
  });
});
