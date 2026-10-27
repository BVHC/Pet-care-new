import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppointmentsPage } from '../../pages/customer/AppointmentsPage';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';

// Giờ cố định thứ Sáu 23/10/2026 09:00 (giờ VN), như AppointmentsPage.test: khach@petcare.vn có 3 lịch BOOKED; lịch thứ ba (thứ Hai 26/10 09:00, id 2)
// còn đổi / hủy được. Ca cần kiểm: trong lúc khách mở hộp thoại, lễ tân vừa tiếp nhận lịch đó → BE trả 409 INVALID_STATE_TRANSITION.

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

/** Lễ tân tiếp nhận lịch ở nơi khác: đổi trạng thái trong state mock mà trang không hay biết. */
function checkInElsewhere(appointmentId: number) {
  const state = JSON.parse(localStorage.getItem('petcare-mock-appointments')!);
  state.appointments.find((a: { appointmentId: number }) => a.appointmentId === appointmentId).status = 'CHECKED_IN';
  localStorage.setItem('petcare-mock-appointments', JSON.stringify(state));
}

const STALE = 'Lịch hẹn đã được tiếp nhận hoặc thay đổi, vui lòng tải lại.';

describe('Đổi giờ / hủy lịch khi lịch không còn BOOKED (appointment-v1 #5, #6 → 409)', () => {
  it('hủy: hiện câu dễ hiểu (không có tên trạng thái tiếng Anh) và làm mới danh sách', async () => {
    const user = renderPage();
    const monday = (await cards())[2];
    await user.click(within(monday).getByRole('button', { name: /Hủy lịch/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy lịch hẹn' });
    checkInElsewhere(2);

    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(STALE);
    expect(dialog).not.toHaveTextContent('CHECKED_IN');
    // Làm mới: lịch vừa được tiếp nhận không còn trong "Sắp tới" (3 → 2 thẻ; hộp thoại mở nên nền bị aria-hidden).
    await vi.waitFor(() => expect(screen.getAllByRole('article', { hidden: true })).toHaveLength(2));
  });

  it('đổi giờ: hiện câu dễ hiểu và làm mới danh sách', async () => {
    const user = renderPage();
    const monday = (await cards())[2];
    await user.click(within(monday).getByRole('button', { name: /Đổi giờ/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Đổi giờ hẹn' });
    const slots = await within(dialog).findByRole('group', { name: 'Khung giờ' });
    await user.click(within(slots).getByRole('button', { name: /^09:30/ }));
    checkInElsewhere(2);

    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đổi giờ' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(STALE);
    expect(dialog).not.toHaveTextContent('CHECKED_IN');
    await vi.waitFor(() => expect(screen.getAllByRole('article', { hidden: true })).toHaveLength(2));
  });
});
