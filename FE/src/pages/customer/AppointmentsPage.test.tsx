import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AppointmentsPage } from './AppointmentsPage';

// Giờ cố định thứ Sáu 23/10/2026 09:00 (giờ VN). Lịch BOOKED của khach@petcare.vn theo giờ hẹn:
// Mochi hôm nay 11:00 (còn 2 giờ: hủy được nhưng là hủy muộn, không đổi giờ online), Bim mai 10:00 (đã đổi 3 lần),
// Mochi thứ Hai 26/10 09:00 (đổi / hủy được). Đã qua: hoàn tất, Bim hủy muộn, lịch phòng khám hủy do ngày nghỉ.

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
      <MemoryRouter initialEntries={['/appointments']}>
        <AppointmentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const cards = async () => screen.findAllByRole('article');

describe('AppointmentsPage (UC40)', () => {
  it('nút đổi giờ / hủy theo cờ canReschedule / canCancel của BE (BR-LH-06, 07)', async () => {
    renderPage();
    const [today, tomorrow, monday] = await cards();
    expect(within(today).getByText('Mochi')).toBeInTheDocument();
    expect(within(today).queryByRole('button', { name: /Đổi giờ/ })).not.toBeInTheDocument();
    expect(within(today).getByRole('button', { name: /Hủy lịch/ })).toBeInTheDocument();
    expect(within(today).getByText(/còn dưới 12 giờ trước giờ hẹn/)).toBeInTheDocument();

    expect(within(tomorrow).getByText('Bim')).toBeInTheDocument();
    expect(within(tomorrow).queryByRole('button', { name: /Đổi giờ/ })).not.toBeInTheDocument();
    expect(within(tomorrow).getByText(/đã đổi đủ 3 lần/)).toBeInTheDocument();

    expect(within(monday).getByRole('button', { name: /Đổi giờ/ })).toBeInTheDocument();
    expect(within(monday).getByRole('button', { name: /Hủy lịch/ })).toBeInTheDocument();
  });

  it('hủy khi còn dưới 12 giờ: cảnh báo hủy muộn, lịch chuyển sang "Đã qua / đã hủy" kèm nguồn hủy', async () => {
    const user = renderPage();
    const [today] = await cards();
    await user.click(within(today).getByRole('button', { name: /Hủy lịch/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy lịch hẹn' });
    expect(within(dialog).getByRole('alert')).toHaveTextContent('hủy lúc này được tính là hủy muộn');
    await user.type(within(dialog).getByLabelText('Lý do hủy (không bắt buộc)'), 'Kẹt xe');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));

    expect(await cards()).toHaveLength(2);
    await user.click(screen.getByRole('tab', { name: /Đã qua/ }));
    const cancelled = (await cards()).find((c) => within(c).queryByText(/Kẹt xe/))!;
    expect(cancelled).toHaveTextContent('Khách hủy');
    expect(cancelled).toHaveTextContent('Hủy muộn');
    expect(screen.getAllByText('Phòng khám hủy').length).toBeGreaterThan(0);
  });

  it('đổi giờ trong cùng chi nhánh, dịch vụ: giữ mã, tăng số lần đổi', async () => {
    const user = renderPage();
    const monday = (await cards())[2];
    const code = within(monday).getByText(/^LH-/).textContent;
    await user.click(within(monday).getByRole('button', { name: /Đổi giờ/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Đổi giờ hẹn' });
    expect(dialog).toHaveTextContent('hãy hủy lịch này rồi đặt lại');
    const slots = await within(dialog).findByRole('group', { name: 'Khung giờ' });
    expect(within(slots).getByRole('button', { name: /^09:00/ })).toBeDisabled();
    await user.click(within(slots).getByRole('button', { name: /^09:30/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đổi giờ' }));

    const moved = (await screen.findByText(/đã đổi giờ 1\/3 lần/)).closest('article')!;
    expect(moved).toHaveTextContent('09:30–10:00');
    expect(within(moved).getByText(code!)).toBeInTheDocument();
  });
});
