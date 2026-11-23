import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { MySchedulePage } from '../MySchedulePage';
import { ASSIGNMENT_SOURCE_LABELS } from './labels';

// T66: ca đích của đổi ca lấy từ #1 (BE cho A06–A08 gọi, chỉ ca đang dùng), không còn suy từ các ca có trong lịch của mình; lịch hiện nguồn phân ca (source).
// Giờ cố định thứ Sáu 06/11/2026 09:00 (giờ VN). doctor@store1.vn (BS. Minh Anh) chỉ có Ca sáng / Ca chiều trong lịch; chi nhánh còn Ca hành chính, Ca tối (đang dùng) và Ca đêm cũ (ngừng dùng).

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-06T09:00:00+07:00'));
});
afterEach(() => vi.useRealTimers());

async function renderPage(email = 'doctor@store1.vn') {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MySchedulePage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('đổi ca: danh sách ca đích lấy từ #1', () => {
  it('có mọi ca đang dùng của chi nhánh trừ ca hiện tại, kể cả ca không có trong lịch của mình; không có ca ngừng dùng', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca sáng, 2026-11-11' }));
    const dialog = await screen.findByRole('dialog', { name: 'Xin nghỉ / đổi ca' });
    await user.click(within(dialog).getByLabelText('Đổi ca'));
    const options = within(within(dialog).getByLabelText('Đổi sang ca')).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Chọn ca', 'Ca hành chính (08:00–17:00)', 'Ca chiều (13:00–17:30)', 'Ca tối (17:30–21:00)']);
  });
});

describe('nguồn phân ca (source)', () => {
  it('nhãn: SELF là tự đăng ký, MANAGER là quản lý phân', () => {
    expect(ASSIGNMENT_SOURCE_LABELS).toEqual({ SELF: 'Tự đăng ký', MANAGER: 'Quản lý phân' });
  });

  it('lịch của tôi hiện nhãn nguồn cho từng phân ca (dữ liệu mẫu do quản lý phân)', async () => {
    await renderPage();
    const rows = within(await screen.findByRole('table', { name: 'Lịch làm việc của tôi' })).getAllByRole('row').slice(1);
    expect(rows.length).toBeGreaterThan(3);
    for (const row of rows) expect(row).toHaveTextContent(/\d{2}:\d{2}–\d{2}:\d{2} · Quản lý phân/);
  });
});
