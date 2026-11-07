import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { branchlifeApi } from '../../../shared/api/branchlife.api';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { addDays, localIsoDate } from '../../../shared/utils/branch-schedule';
import { AdminBranchDetailPage } from '../AdminBranchDetailPage';

// Chi nhánh 1 (PetCare Cầu Giấy, ACTIVE) có lịch hẹn BOOKED, tồn kho và nhân viên: tạm ngừng được nếu hủy hàng loạt, đóng cửa bị chặn.
// Chi nhánh 3 (DRAFT, không tồn kho, không nhân viên) được ép sang ACTIVE để thử đóng cửa thành công.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function Where() {
  const { pathname } = useLocation();
  return <p data-testid="where">{pathname}</p>;
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/admin/branches/:branchId/:tab?"
            element={
              <>
                <AdminBranchDetailPage />
                <Where />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

/** Ép trạng thái chi nhánh trong localStorage (cùng cách branchlife.mock.test.ts). */
async function force(statuses: Record<number, string>) {
  const all = (await apiClient.get('/api/branches')).data.data as { branchId: number; status: string }[];
  const stored = JSON.parse(localStorage.getItem('petcare-mock-branch') ?? '{}') as object;
  localStorage.setItem('petcare-mock-branch', JSON.stringify({ ...stored, branches: all.map((b) => ({ ...b, status: statuses[b.branchId] ?? b.status })) }));
}

const dialog = () => screen.findByRole('dialog');

describe('Tạm ngừng / đóng cửa chi nhánh (UC13)', () => {
  it('quản lý chi nhánh không thấy tab, gõ thẳng /lifecycle bị đưa về giờ mở cửa', async () => {
    await signIn('manager@store1.vn');
    renderAt('/admin/branches/1/lifecycle');
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/branches/1/hours'));
    expect(screen.queryByRole('tab', { name: 'Tạm ngừng / đóng cửa' })).not.toBeInTheDocument();
  });

  it('xem trước tồn đọng: số đếm và điều kiện chặn tạm ngừng / đóng cửa của chi nhánh 1', async () => {
    await signIn('chuoi@petcare.vn');
    renderAt('/admin/branches/1/lifecycle');
    const counts = await screen.findByRole('table', { name: 'Tồn đọng của chi nhánh' });
    expect(within(counts).getByText('Lịch hẹn và đặt chỗ lưu trú đang chờ').nextElementSibling).not.toHaveTextContent(/^0$/);
    const closeBlockers = screen.getByRole('list', { name: 'Điều kiện chặn đóng cửa' });
    expect(closeBlockers).toHaveTextContent(/tồn kho/);
    expect(closeBlockers).toHaveTextContent(/nhân viên/);
    expect(screen.queryByRole('list', { name: 'Điều kiện chặn tạm ngừng' })).not.toBeInTheDocument();
    expect(screen.getByText('Không có: làm được ngay.')).toBeInTheDocument();
  });

  it('tạm ngừng: bắt buộc lý do và hủy hàng loạt; thành công thì trạng thái đổi và nút thành Mở lại', async () => {
    await signIn('chuoi@petcare.vn');
    const user = renderAt('/admin/branches/1/lifecycle');
    await user.click(await screen.findByRole('button', { name: 'Tạm ngừng chi nhánh' }));
    const form = await dialog();
    await user.click(within(form).getByRole('button', { name: 'Tạm ngừng chi nhánh' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do');

    await user.type(within(form).getByLabelText('Lý do'), 'Sửa chữa mặt bằng');
    fireEvent.change(within(form).getByLabelText('Ngày dự kiến mở lại'), { target: { value: addDays(localIsoDate(), 14) } });
    await user.click(within(form).getByRole('button', { name: 'Tạm ngừng chi nhánh' }));
    expect(within(form).getByRole('alert')).toHaveTextContent(/Chọn hủy hàng loạt để tiếp tục \(còn \d+ lịch hẹn/);

    await user.click(within(form).getByRole('checkbox', { name: /Hủy hàng loạt \d+ lịch hẹn/ }));
    await user.click(within(form).getByRole('button', { name: 'Tạm ngừng chi nhánh' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã tạm ngừng chi nhánh PetCare Cầu Giấy');
    expect(await screen.findByText('Tạm ngừng', { selector: 'span' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Mở lại chi nhánh' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tạm ngừng chi nhánh' })).not.toBeInTheDocument();
  });

  it('mở lại chi nhánh đang tạm ngừng: trạng thái về Đang hoạt động', async () => {
    await signIn('chuoi@petcare.vn');
    await branchlifeApi.suspend(1, { reason: 'Bảo trì', cancelBookings: true });
    const user = renderAt('/admin/branches/1/lifecycle');
    await user.click(await screen.findByRole('button', { name: 'Mở lại chi nhánh' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã mở lại chi nhánh PetCare Cầu Giấy');
    expect(await screen.findByRole('button', { name: 'Tạm ngừng chi nhánh' })).toBeInTheDocument();
  });

  it('mở lại bị 409 khi trạng thái đã đổi sau lúc mở trang', async () => {
    await signIn('chuoi@petcare.vn');
    await branchlifeApi.suspend(1, { reason: 'Bảo trì', cancelBookings: true });
    const user = renderAt('/admin/branches/1/lifecycle');
    const resume = await screen.findByRole('button', { name: 'Mở lại chi nhánh' });
    await branchlifeApi.resume(1);
    await user.click(resume);

    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái BranchTransitionHandler từ ACTIVE sang ACTIVE');
  });

  it('tạm ngừng bị 409 khi chi nhánh vừa được tạm ngừng ở nơi khác: hộp giữ nguyên và hiện lý do', async () => {
    await signIn('chuoi@petcare.vn');
    const user = renderAt('/admin/branches/1/lifecycle');
    await user.click(await screen.findByRole('button', { name: 'Tạm ngừng chi nhánh' }));
    const form = await dialog();
    await branchlifeApi.suspend(1, { reason: 'Người khác tạm ngừng', cancelBookings: true });
    await user.type(within(form).getByLabelText('Lý do'), 'Tôi tạm ngừng');
    await user.click(within(form).getByRole('checkbox', { name: /Hủy hàng loạt/ }));
    await user.click(within(form).getByRole('button', { name: 'Tạm ngừng chi nhánh' }));

    expect(await within(form).findByRole('alert')).toHaveTextContent('từ SUSPENDED sang SUSPENDED');
  });

  it('đóng cửa chi nhánh 1 bị chặn: hộp liệt kê điều kiện và khóa nút', async () => {
    await signIn('chuoi@petcare.vn');
    const user = renderAt('/admin/branches/1/lifecycle');
    await user.click(await screen.findByRole('button', { name: 'Đóng cửa vĩnh viễn' }));
    const form = await dialog();
    expect(within(form).getByRole('note')).toHaveTextContent('không thể hoàn tác');
    expect(within(form).getByText('Chưa đóng cửa được vì:')).toBeInTheDocument();
    expect(within(form).getByRole('alert')).toHaveTextContent(/tồn kho/);
    expect(within(form).getByRole('button', { name: 'Đóng cửa vĩnh viễn' })).toBeDisabled();
  });

  it('đóng cửa chi nhánh sạch: phải gõ đúng tên chi nhánh; xong thì CLOSED và không còn nút nào', async () => {
    await signIn('chuoi@petcare.vn');
    await force({ 3: 'ACTIVE' });
    const user = renderAt('/admin/branches/3/lifecycle');
    await user.click(await screen.findByRole('button', { name: 'Đóng cửa vĩnh viễn' }));
    const form = await dialog();
    await user.type(within(form).getByLabelText('Lý do'), 'Hết hợp đồng thuê');
    await user.type(within(form).getByLabelText('Gõ "PetCare Long Biên" để xác nhận'), 'PetCare Long');
    await user.click(within(form).getByRole('button', { name: 'Đóng cửa vĩnh viễn' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Gõ đúng tên chi nhánh để xác nhận đóng cửa');

    await user.type(within(form).getByLabelText('Gõ "PetCare Long Biên" để xác nhận'), ' Biên');
    await user.click(within(form).getByRole('button', { name: 'Đóng cửa vĩnh viễn' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã đóng cửa vĩnh viễn chi nhánh PetCare Long Biên');
    expect(await screen.findByText(/đã đóng cửa vĩnh viễn, không mở lại được/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Đóng cửa vĩnh viễn|Tạm ngừng chi nhánh|Mở lại chi nhánh/ })).not.toBeInTheDocument();
  });

  it('chi nhánh nháp: xem được tồn đọng nhưng không có nút thao tác', async () => {
    await signIn('chuoi@petcare.vn');
    renderAt('/admin/branches/2/lifecycle');
    expect(await screen.findByText(/Chi nhánh còn ở trạng thái nháp/)).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Tồn đọng của chi nhánh' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Đóng cửa vĩnh viễn|Tạm ngừng chi nhánh/ })).not.toBeInTheDocument();
  });
});
