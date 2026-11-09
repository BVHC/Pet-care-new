import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminAppointmentsPage } from './AdminAppointmentsPage';

// Giờ cố định thứ Sáu 23/10/2026 09:00 (giờ VN), lễ tân chi nhánh 1. Hôm nay: Lu 08:30 đã tiếp nhận (khách #3
// Lê Hoàng Long, đang bị hạn chế đặt online), Mochi 11:00 đã đặt (khách Nguyễn Thu Trang).

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
  useSession.getState().clear();
  await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
});
afterEach(() => vi.useRealTimers());

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/appointments']}>
        <AdminAppointmentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const rowOf = async (petName: string) => (await screen.findByRole('cell', { name: petName })).closest('tr')!;

describe('AdminAppointmentsPage (UC39, UC40 — lễ tân)', () => {
  it('lịch hôm nay của chi nhánh với 5 trạng thái thật; không có nút check-in', async () => {
    renderPage();
    const lu = await rowOf('Lu');
    expect(lu).toHaveTextContent('08:30–09:00');
    expect(lu).toHaveTextContent('Đã tiếp nhận');
    expect(within(lu).queryByRole('button', { name: /Hủy/ })).not.toBeInTheDocument();
    const mochi = await rowOf('Mochi');
    expect(mochi).toHaveTextContent('Đã đặt');
    expect(within(mochi).getByRole('button', { name: /^Đổi giờ/ })).toBeInTheDocument();
    expect(within(mochi).getByRole('button', { name: /^Hủy/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /check-in/i })).not.toBeInTheDocument();
    const statuses = within(screen.getByRole('combobox', { name: 'Lọc trạng thái' })).getAllByRole('option').map((o) => o.textContent);
    expect(statuses).toEqual(['Mọi trạng thái', 'Đã đặt', 'Đã tiếp nhận', 'Hoàn tất', 'Đã hủy', 'Không đến']);
  });

  it('đặt lịch hộ: tìm khách → chọn thú → dịch vụ → khung → lịch tại quầy hiện trong danh sách', async () => {
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: /Đặt lịch hộ/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Đặt lịch hộ khách' });
    await user.type(within(dialog).getByLabelText('Tìm khách theo SĐT hoặc họ tên'), 'Nguyễn Thu Trang');
    await user.click(await within(dialog).findByRole('button', { name: /^Chọn khách Nguyễn Thu Trang/ }));
    await user.click(await within(dialog).findByRole('button', { name: /^Tôm/ }));
    await user.selectOptions(within(dialog).getByLabelText('Dịch vụ'), 'Khám tổng quát (Khám/Tiêm)');
    const slots = await within(dialog).findByRole('group', { name: 'Khung giờ' });
    // Lễ tân thấy cả khung sắp bắt đầu hôm nay.
    await user.click(within(slots).getByRole('button', { name: /^09:30/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Đặt lịch' }));

    const tom = await rowOf('Tôm');
    expect(tom).toHaveTextContent('09:30–10:00');
    expect(tom).toHaveTextContent('Đặt tại quầy');
  });

  it('xem hạn chế đặt online của khách và gỡ sớm (lý do bắt buộc)', async () => {
    const user = renderPage();
    await user.click(within(await rowOf('Lu')).getByRole('button', { name: /^Chi tiết/ }));
    const detail = await screen.findByRole('dialog', { name: /^Lịch hẹn LH-/ });
    const panel = await within(detail).findByRole('region', { name: 'Hạn chế đặt online' });
    expect(panel).toHaveTextContent('3 lần không đến / hủy muộn');
    await user.click(within(panel).getByRole('button', { name: 'Gỡ hạn chế' }));

    const lift = await screen.findByRole('dialog', { name: 'Gỡ hạn chế đặt online' });
    await user.click(within(lift).getByRole('button', { name: 'Gỡ hạn chế' }));
    expect(within(lift).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do');
    await user.type(within(lift).getByLabelText('Lý do gỡ hạn chế'), 'Khách có lý do chính đáng');
    await user.click(within(lift).getByRole('button', { name: 'Gỡ hạn chế' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Gỡ hạn chế đặt online' })).not.toBeInTheDocument());
    expect(await screen.findByText('Khách không bị hạn chế đặt lịch online.')).toBeInTheDocument();
  });
});
