import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import axios from 'axios';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { feesApi } from '../../shared/api/fees.api';
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

// Khám tại nhà (UC43, homevisit-v1 #1, fees-v1 #3): chi nhánh 1 bật khám tại nhà, phí 100.000 ₫. Sổ địa chỉ của khach@petcare.vn: 12 Trần Thái Tông (mặc định, 0912345678) và 45 Láng Hạ.
describe('BookingPage — khám tại nhà (UC43)', () => {
  afterEach(() => vi.restoreAllMocks());
  const pickHome = async (user: User) => user.click(await screen.findByRole('button', { name: 'Khám tại nhà' }));

  it('chọn "Khám tại nhà": hiện phí của chi nhánh và sổ địa chỉ, chỉ còn dịch vụ nhóm Khám/Tiêm', async () => {
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: /PetCare Cầu Giấy/ }));
    expect(await screen.findByRole('button', { name: /Tắm & sấy/ })).toBeInTheDocument();
    await pickHome(user);

    expect(await screen.findByText(/Phí khám tại nhà/)).toHaveTextContent('100.000');
    const addresses = screen.getByRole('group', { name: 'Chọn địa chỉ khám tại nhà' });
    expect(await within(addresses).findByRole('button', { name: /12 Trần Thái Tông/ })).toBeInTheDocument();
    expect(within(addresses).getByRole('button', { name: /45 Láng Hạ/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Tắm & sấy/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Khám tổng quát/ })).toBeInTheDocument();
  });

  it('đặt lịch tại nhà: thú → dịch vụ khám → ngày → khung → địa chỉ (điền sẵn số liên hệ) → thấy mã, địa chỉ và phí', async () => {
    const user = renderPage();
    await pickHome(user);
    const slots = await choose(user, 'Tôm', /Khám tổng quát/, '26/10/2026');
    await user.click(within(slots).getByRole('button', { name: /^10:00/ }));
    await user.click(await screen.findByRole('button', { name: /45 Láng Hạ/ }));
    expect(screen.getByLabelText('Số điện thoại liên hệ')).toHaveValue('0987000111');
    await user.click(screen.getByRole('button', { name: /Xác nhận đặt lịch/ }));

    const done = await screen.findByRole('dialog', { name: 'Đặt lịch thành công' });
    expect(within(done).getByText(/^LH-261023-\d{4}$/)).toBeInTheDocument();
    expect(within(done).getByText('45 Láng Hạ, Láng Hạ, Hà Nội')).toBeInTheDocument();
    expect(within(done).getByText(/100\.000/)).toBeInTheDocument();
    expect(within(done).getByText('10:00–10:30, Thứ Hai, 26/10/2026')).toBeInTheDocument();
    expect(within(done).getByText(/Lịch khám tại nhà đã được giữ/)).toBeInTheDocument();
  });

  it('thiếu địa chỉ hoặc số liên hệ sai: báo ngay, không gọi BE', async () => {
    const user = renderPage();
    await pickHome(user);
    const slots = await choose(user, 'Tôm', /Khám tổng quát/, '26/10/2026');
    await user.click(within(slots).getByRole('button', { name: /^10:00/ }));
    await user.click(screen.getByRole('button', { name: /Xác nhận đặt lịch/ }));
    expect(await screen.findByText('Vui lòng chọn địa chỉ khám tại nhà.')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /12 Trần Thái Tông/ }));
    await user.clear(screen.getByLabelText('Số điện thoại liên hệ'));
    await user.type(screen.getByLabelText('Số điện thoại liên hệ'), '12345');
    await user.click(screen.getByRole('button', { name: /Xác nhận đặt lịch/ }));
    expect(await screen.findByText('Số điện thoại liên hệ gồm 10–11 chữ số, bắt đầu bằng 0.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Đặt lịch thành công' })).not.toBeInTheDocument();
  });

  it('BE từ chối dịch vụ không phải khám (BR-LH-13): hiện lý do của BE, form giữ nguyên để chọn lại', async () => {
    const user = renderPage();
    await pickHome(user);
    const slots = await choose(user, 'Tôm', /Tiêm phòng/, '26/10/2026');
    await user.click(within(slots).getByRole('button', { name: /^10:00/ }));
    await user.click(await screen.findByRole('button', { name: /12 Trần Thái Tông/ }));
    await user.click(screen.getByRole('button', { name: /Xác nhận đặt lịch/ }));
    expect(await screen.findByText('Dịch vụ khám tại nhà phải là dịch vụ khám')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Khám tổng quát/ })).toBeInTheDocument();
  });

  it('chi nhánh chưa bật khám tại nhà: báo rõ và không cho xác nhận', async () => {
    vi.spyOn(feesApi, 'getHomeVisitFee').mockResolvedValue({ branchId: 1, enabled: false, fee: 0 });
    const user = renderPage();
    await pickHome(user);
    expect(await screen.findByText(/Chi nhánh này chưa nhận khám tại nhà/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Xác nhận đặt lịch/ })).toBeDisabled();
    // Quay về khám tại chi nhánh thì đặt lại được.
    await user.click(screen.getByRole('button', { name: 'Khám tại chi nhánh' }));
    expect(screen.queryByText(/Chi nhánh này chưa nhận khám tại nhà/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Xác nhận đặt lịch/ })).toBeEnabled();
  });
});
