import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { boardingApi } from '../../shared/api/boarding.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ACCOUNTS, pinClock } from '../../test/boarding-harness';
import { AdminBoardingPage } from './AdminBoardingPage';

// Bộ lọc ngày: Từ > Đến thì FE không gửi request danh sách (BE sẽ trả 400), báo lỗi ngay cạnh bộ lọc. Dữ liệu mẫu xem boarding-harness.ts
// (giờ ghim 23/10/2026): Bơ BOOKED 30/10→01/11 nằm trong tab Sắp nhận.

const RANGE_ERROR = 'Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc';

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  pinClock();
  useSession.getState().clear();
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function openPage() {
  await useSession.getState().login({ email: ACCOUNTS.desk, password: MOCK_PASSWORD });
  const spy = vi.spyOn(boardingApi, 'list');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/boarding']}>
        <AdminBoardingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByText('Bơ', { selector: 'p, span, strong' });
  return spy;
}

const setDates = (from: string, to: string) => {
  fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: to } });
};
const settle = () => new Promise((resolve) => setTimeout(resolve, 60));
const noReversedRange = (spy: { mock: { calls: Array<[{ from?: string; to?: string }?]> } }) =>
  spy.mock.calls.every(([q]) => !(q?.from && q?.to && q.from > q.to));

describe('AdminBoardingPage — bộ lọc ngày Từ > Đến', () => {
  it('Từ > Đến: không gửi request danh sách, hiện lỗi cục bộ, không có khối lỗi tải / nút Thử lại', async () => {
    const spy = await openPage();
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-11-05' } });
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', from: '2026-11-05', page: 0, size: 20 }));
    const before = spy.mock.calls.length;

    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-11-01' } });
    expect(await screen.findByRole('alert')).toHaveTextContent(RANGE_ERROR);
    await settle();

    expect(spy.mock.calls).toHaveLength(before);
    expect(noReversedRange(spy)).toBe(true);
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByText('Đang tải danh sách…')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Xóa lọc ngày' })).toBeInTheDocument();
  });

  it('sửa lại hợp lệ (Từ ≤ Đến): gửi request, lỗi biến mất, bảng trở lại', async () => {
    const spy = await openPage();
    setDates('2026-11-05', '2026-11-01');
    expect(await screen.findByRole('alert')).toHaveTextContent(RANGE_ERROR);
    await settle();
    expect(noReversedRange(spy)).toBe(true);
    const before = spy.mock.calls.length;

    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-01' } });
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', from: '2026-10-01', to: '2026-11-01', page: 0, size: 20 }));
    expect(spy.mock.calls.length).toBe(before + 1);
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(await screen.findByText('Bơ', { selector: 'p, span, strong' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
  });

  it('Từ = Đến là hợp lệ: gửi request, không có lỗi', async () => {
    const spy = await openPage();
    setDates('2026-10-30', '2026-10-30');
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith({ status: 'BOOKED', from: '2026-10-30', to: '2026-10-30', page: 0, size: 20 }));
    expect(await screen.findByText('Bơ', { selector: 'p, span, strong' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
