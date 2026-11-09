import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import axios from 'axios';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { BookingPage } from './BookingPage';

// Giờ cố định thứ Sáu 23/10/2026 09:00 (giờ VN). khach@petcare.vn: Mochi (đủ 2 lịch BOOKED), Bim (Tắm & sấy mai 10:00 —
// khung Thẩm mỹ 10:00 ngày 24/10 hết chỗ vì quota 1), Tôm (chưa có lịch). Chi nhánh 1 là chi nhánh ACTIVE duy nhất.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});
afterEach(() => vi.useRealTimers());

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/booking']}>
        <BookingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

type User = ReturnType<typeof userEvent.setup>;

async function choose(user: User, pet: string, service: RegExp, day: string) {
  await user.click(await screen.findByRole('button', { name: pet }));
  await user.click(await screen.findByRole('button', { name: service }));
  await user.click(screen.getByRole('button', { name: 'Ngày hẹn' }));
  await user.click(screen.getByRole('button', { name: day }));
  return screen.findByRole('group', { name: 'Khung giờ' });
}

describe('BookingPage (UC39)', () => {
  it('đặt lịch: thú → chi nhánh (chọn sẵn) → dịch vụ → ngày → khung → mã lịch hẹn', async () => {
    const user = renderPage();
    expect(await screen.findByRole('button', { name: /PetCare Cầu Giấy/ })).toHaveAttribute('aria-pressed', 'true');
    // Nhóm Lưu trú không đặt qua lịch hẹn.
    expect(screen.queryByRole('button', { name: /Chuồng/ })).not.toBeInTheDocument();
    const slots = await choose(user, 'Tôm', /Khám tổng quát/, '26/10/2026');
    await user.click(within(slots).getByRole('button', { name: /^10:00/ }));
    await user.type(screen.getByLabelText('Ghi chú cho phòng khám'), 'Lần đầu đến');
    await user.click(screen.getByRole('button', { name: /Xác nhận đặt lịch/ }));

    const done = await screen.findByRole('dialog', { name: 'Đặt lịch thành công' });
    expect(within(done).getByText(/^LH-261023-\d{4}$/)).toBeInTheDocument();
    expect(within(done).getByText('10:00–10:30, Thứ Hai, 26/10/2026')).toBeInTheDocument();
    expect(within(done).getByRole('link', { name: 'Xem lịch hẹn của tôi' })).toHaveAttribute('href', '/appointments');
  });

  it('khung đã đủ quota không chọn được và ghi "Hết chỗ" (BR-LH-03); ngày nghỉ báo không nhận lịch', async () => {
    const user = renderPage();
    const slots = await choose(user, 'Tôm', /Tắm & sấy/, '24/10/2026');
    const full = within(slots).getByRole('button', { name: /^10:00/ });
    expect(full).toBeDisabled();
    expect(full).toHaveTextContent('Hết chỗ');
    expect(within(slots).getByRole('button', { name: /^10:30/ })).toBeEnabled();
    // Khách chỉ thấy khung từ now + 24 giờ.
    expect(within(slots).queryByRole('button', { name: /^08:30/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Ngày hẹn' }));
    await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
    await user.click(screen.getByRole('button', { name: '12/11/2026' }));
    expect(await screen.findByText('Chi nhánh không nhận lịch ngày này')).toBeInTheDocument();
  });

  it('BE từ chối thì hiện lý do dễ đọc (BR-LH-05)', async () => {
    const user = renderPage();
    const slots = await choose(user, 'Mochi', /Khám tổng quát/, '26/10/2026');
    await user.click(within(slots).getByRole('button', { name: /^15:00/ }));
    await user.click(screen.getByRole('button', { name: /Xác nhận đặt lịch/ }));
    expect(await screen.findByText('Thú cưng đã có 2 lịch hẹn đang chờ')).toBeInTheDocument();
  });

  it('khách đang bị hạn chế (BR-LH-09) không thấy form, thấy lý do và thời điểm hết hạn chế', async () => {
    // Tạo đủ 3 vi phạm qua API: mẫu có 1 lần hủy muộn; khách hủy Mochi 11:00 hôm nay; lễ tân đặt hộ rồi hủy hộ khi còn < 12 giờ.
    const raw = axios.create();
    installMockApi(raw);
    const login = async (email: string) => ({
      headers: { Authorization: `Bearer ${(await raw.post('/api/auth/login', { email, password: MOCK_PASSWORD })).data.data.accessToken}` },
    });
    const owner = await login('khach@petcare.vn');
    const desk = await login('reception@store1.vn');
    const today = (await raw.get('/api/appointments', { params: { date: '2026-10-23' }, ...owner })).data.data.content;
    await raw.post(`/api/appointments/${today[0].appointmentId}/cancel`, {}, owner);
    const counter = (await raw.post('/api/appointments', { petId: 2, branchId: 1, serviceId: 1, slotDate: '2026-10-23', slotStart: '13:30' }, desk)).data.data;
    await raw.post(`/api/appointments/${counter.appointmentId}/cancel`, {}, desk);

    renderPage();
    const notice = await screen.findByRole('alert', { name: 'Hạn chế đặt lịch online' });
    expect(notice).toHaveTextContent('đến 09:00 22/11/2026');
    expect(notice).toHaveTextContent('3 lần không đến hoặc hủy muộn');
    expect(screen.queryByRole('form', { name: 'Thông tin đặt lịch' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Xác nhận đặt lịch/ })).not.toBeInTheDocument();
  });
});
