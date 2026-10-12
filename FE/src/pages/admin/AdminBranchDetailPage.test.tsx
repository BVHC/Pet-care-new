import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { formatDate } from '../../lib/utils';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { addDays, localIsoDate, nextWeekday } from '../../shared/utils/branch-schedule';
import { AdminBranchDetailPage } from './AdminBranchDetailPage';

// Dữ liệu mẫu ở shared/api/mock/branch.mock.ts. Chi nhánh 1: T2–T7 08:00–12:00 và 13:30–19:00, CN 08:00–12:00;
// lịch hẹn thứ Ba tới 09:00 (Mochi) và 18:30 (Lucky); quota mặc định Khám/Tiêm 3.
beforeAll(() => installMockApi(apiClient));

const today = localIsoDate();
const tuesday = nextWeekday(today, 2);

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/branches/:branchId/:tab?" element={<AdminBranchDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const asManager = () => signIn('manager@store1.vn');

describe('opening hours (UC14)', () => {
  it('shows the version in force', async () => {
    await asManager();
    renderAt('/admin/branches/1/hours');
    expect(await screen.findByText('Đang áp dụng từ 01/01/2026')).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /Chủ nhật/ })).toHaveTextContent('08:00–12:00');
  });

  it('refuses overlapping ranges before calling the BE (BR-CN-02)', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/hours');
    await user.click(await screen.findByRole('button', { name: 'Đặt giờ mở cửa mới' }));
    const editor = screen.getByRole('dialog');
    fireEvent.change(within(editor).getByLabelText('Thứ Ba khoảng 2: giờ mở'), { target: { value: '11:00' } });
    await user.click(within(editor).getByRole('button', { name: 'Kiểm tra và lưu' }));

    expect(within(editor).getByRole('alert')).toHaveTextContent('Thứ Ba: các khoảng giờ mở cửa không được chồng nhau');
  });

  it('lists the bookings a shorter evening drops and saves only after they are cancelled (BR-CN-04)', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/hours');
    await user.click(await screen.findByRole('button', { name: 'Đặt giờ mở cửa mới' }));
    const editor = screen.getByRole('dialog');
    fireEvent.change(within(editor).getByLabelText('Thứ Ba khoảng 2: giờ đóng'), { target: { value: '18:00' } });
    await user.click(within(editor).getByRole('button', { name: 'Kiểm tra và lưu' }));

    expect(await within(editor).findByText('Lucky')).toBeInTheDocument();
    expect(within(editor).getByText('Trần Đức Huy')).toBeInTheDocument();
    await user.click(within(editor).getByRole('button', { name: 'Lưu và hủy hàng loạt' }));
    expect(within(editor).getByRole('alert')).toHaveTextContent('Chọn hủy hàng loạt để lưu');

    await user.click(within(editor).getByRole('checkbox', { name: /Hủy hàng loạt các lịch trên/ }));
    await user.click(within(editor).getByRole('button', { name: 'Lưu và hủy hàng loạt' }));

    expect(await screen.findByRole('status')).toHaveTextContent('hủy 1 lịch hẹn và 0 đặt chỗ lưu trú');
    expect(await screen.findByText(`Áp dụng từ ${formatDate(addDays(today, 1))}`)).toBeInTheDocument();
  });
});

describe('holidays (UC14)', () => {
  it('adds a holiday after showing the appointments it cancels (BR-LH-10)', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/holidays');
    await user.click(await screen.findByRole('button', { name: 'Thêm ngày nghỉ' }));
    const form = screen.getByRole('dialog');
    fireEvent.change(within(form).getByLabelText('Ngày nghỉ'), { target: { value: tuesday } });
    await user.type(within(form).getByLabelText('Lý do'), 'Sửa điện');
    await user.click(within(form).getByRole('button', { name: 'Kiểm tra và thêm' }));

    expect(await within(form).findByText('Mochi')).toBeInTheDocument();
    await user.click(within(form).getByRole('checkbox', { name: /Hủy hàng loạt các lịch trên/ }));
    await user.click(within(form).getByRole('button', { name: 'Thêm và hủy hàng loạt' }));

    expect(await screen.findByRole('status')).toHaveTextContent(`Đã thêm ngày nghỉ ${formatDate(tuesday)}`);
    expect(await screen.findByRole('row', { name: /Sửa điện/ })).toBeInTheDocument();
  });

  it('only offers to delete holidays that are still ahead (BR-CN-03)', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/holidays');
    const ahead = formatDate(addDays(today, 20));
    const past = formatDate(addDays(today, -30));
    expect(await screen.findByRole('button', { name: `Xóa ngày nghỉ ${ahead}` })).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Hiển thị'), 'Tất cả');
    expect(await screen.findByRole('row', { name: new RegExp(past) })).toHaveTextContent('Đã qua');
    expect(screen.queryByRole('button', { name: `Xóa ngày nghỉ ${past}` })).not.toBeInTheDocument();
  });
});

describe('services at the branch (UC33)', () => {
  it('turns a service on', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/services');
    const toggle = await screen.findByRole('checkbox', { name: 'Bật Chuồng mèo tại chi nhánh' });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);

    expect(await screen.findByRole('status')).toHaveTextContent('Đã bật Chuồng mèo tại chi nhánh');
    expect(await screen.findByRole('checkbox', { name: 'Bật Chuồng mèo tại chi nhánh' })).toBeChecked();
  });
});

describe('quota (UC42)', () => {
  it('sets the default quota of a group', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/quota');
    await user.type(await screen.findByLabelText('Quota mặc định Thẩm mỹ'), '2');
    await user.click(screen.getByRole('button', { name: 'Lưu quota Thẩm mỹ' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã đặt quota mặc định Thẩm mỹ: 2');
  });

  it('locks a slot with quota 0 (BR-LH-03)', async () => {
    await asManager();
    const user = userEvent.setup();
    renderAt('/admin/branches/1/quota');
    fireEvent.change(await screen.findByLabelText('Ngày'), { target: { value: tuesday } });
    await user.click(await screen.findByRole('button', { name: 'Khung 09:30: 3 lượt' }));
    const form = screen.getByRole('dialog');
    await user.clear(within(form).getByLabelText('Quota (0 = khóa khung)'));
    await user.type(within(form).getByLabelText('Quota (0 = khóa khung)'), '0');
    await user.click(within(form).getByRole('button', { name: 'Lưu quota riêng' }));

    expect(await screen.findByRole('button', { name: 'Khung 09:30: Khóa (quota riêng)' })).toBeInTheDocument();
  });

  it('shows that a holiday has no slot', async () => {
    await asManager();
    renderAt('/admin/branches/1/quota');
    fireEvent.change(await screen.findByLabelText('Ngày'), { target: { value: addDays(today, 20) } });
    expect(await screen.findByText('Ngày nghỉ của chi nhánh, không có khung đặt lịch.')).toBeInTheDocument();
  });
});

describe('chain manager', () => {
  it('reads the configuration without editing it and has no quota tab', async () => {
    await signIn('chuoi@petcare.vn');
    const user = userEvent.setup();
    renderAt('/admin/branches/1/hours');
    expect(await screen.findByText('Đang áp dụng từ 01/01/2026')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đặt giờ mở cửa mới' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Quota lịch hẹn' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Dịch vụ' }));
    expect(await screen.findByRole('checkbox', { name: 'Bật Chuồng mèo tại chi nhánh' })).toBeDisabled();
  });
});
