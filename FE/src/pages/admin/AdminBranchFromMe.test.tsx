// T49: các trang nhân viên cần chi nhánh (đặt tại quầy, tạo chuồng, đặt lịch hộ) khi phiên KHÔNG có `branchId` (BE thật chỉ
// trả ở GET /me). Trước đây ba trang đọc `account.branchId` nên nút bị ẩn / khóa trên BE thật mà mock không lộ.
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ReactElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ACCOUNTS, pinClock } from '../../test/boarding-harness';
import { AdminAppointmentsPage } from './AdminAppointmentsPage';
import { AdminBoardingPage } from './AdminBoardingPage';
import { AdminKennelsPage } from './AdminKennelsPage';

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }) }));

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  pinClock();
  useSession.getState().clear();
});
afterEach(() => vi.useRealTimers());

async function sessionWithoutBranch(email: string) {
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  useSession.setState((s) => ({ account: { ...s.account!, branchId: null } }));
}

function renderAt(path: string, page: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>{page}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('trang nhân viên lấy chi nhánh từ GET /me khi phiên không có branchId', () => {
  it('lưu trú: lễ tân vẫn có nút "Đặt tại quầy"', async () => {
    await sessionWithoutBranch(ACCOUNTS.desk);
    renderAt('/admin/boarding', <AdminBoardingPage />);

    expect(await screen.findByRole('button', { name: /Đặt tại quầy/ })).toBeInTheDocument();
  });

  it('chuồng: quản lý không thấy câu "chưa gắn chi nhánh" và tạo được chuồng', async () => {
    await sessionWithoutBranch(ACCOUNTS.manager);
    renderAt('/admin/kennels', <AdminKennelsPage />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Tạo chuồng' })).toBeEnabled());
    expect(screen.queryByText(/chưa gắn chi nhánh/)).not.toBeInTheDocument();
  });

  it('lịch hẹn: lễ tân vẫn có nút "Đặt lịch hộ"', async () => {
    await sessionWithoutBranch(ACCOUNTS.desk);
    renderAt('/admin/appointments', <AdminAppointmentsPage />);

    expect(await screen.findByRole('button', { name: /Đặt lịch hộ/ })).toBeInTheDocument();
  });
});
