import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetClinicDb, seedClinicDb } from '../../../shared/api/clinic-db';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { usePosStore } from '../../../shared/stores/pos.store';
import { FrontDeskPanel } from './FrontDeskPanel';

// sales.mock, chi nhánh 1, chờ thu (PENDING): #2 Visit của khách #1 Nguyễn Thu Trang 266.000 · #3 Visit của khách #4 Lê Thị Hoa 200.000
// · #4 bán lẻ của khách #2 Lê Hoàng Long 450.000.

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  resetClinicDb(seedClinicDb());
  usePosStore.getState().clear();
  useSession.getState().clear();
});

const signIn = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

async function renderPending(onCollect = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <FrontDeskPanel actorId="4" onCheckIn={vi.fn()} onWalkIn={vi.fn()} onCollect={onCollect} />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(await screen.findByRole('tab', { name: /Chờ thu/ }));
  return { user, onCollect };
}

const row = async (code: RegExp) => within((await screen.findByText(code)).closest('li')!);

describe('FrontDeskPanel — tab Chờ thu đọc Order PENDING từ sales-v1 (T35)', () => {
  it('lễ tân: liệt kê Order chờ thu (mã, khách, nguồn, tổng tiền); số trên tab là số Order của API', async () => {
    await signIn('reception@store1.vn');
    await renderPending();

    const trang = await row(/DH-\d{4}-00002/);
    expect(trang.getByText('Nguyễn Thu Trang')).toBeInTheDocument();
    expect(trang.getByText('Lượt tiếp nhận')).toBeInTheDocument();
    expect(trang.getByText(/266\.000\s*₫/)).toBeInTheDocument();
    const retail = await row(/DH-\d{4}-00004/);
    expect(retail.getByText('Lê Hoàng Long')).toBeInTheDocument();
    expect(retail.getByText('Bán lẻ')).toBeInTheDocument();
    expect(retail.getByText(/450\.000\s*₫/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Chờ thu/ })).toHaveTextContent('3');
  });

  it('"Thu tiền" đưa Order vào phiếu thu của đúng khách rồi báo bàn để chuyển sang phiếu', async () => {
    await signIn('reception@store1.vn');
    const { user, onCollect } = await renderPending();

    const trang = await row(/DH-\d{4}-00002/);
    await user.click(trang.getByRole('button', { name: 'Thu tiền' }));

    expect(usePosStore.getState()).toMatchObject({ customerId: 1, orderIds: [2] });
    expect(onCollect).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(trang.getByRole('button', { name: 'Trong phiếu' })).toBeDisabled());
  });

  it('quản lý chi nhánh xem được danh sách nhưng không có nút thu tiền (thu tiền là việc của lễ tân)', async () => {
    await signIn('manager@store1.vn');
    await renderPending();

    await row(/DH-\d{4}-00002/);
    expect(screen.queryByRole('button', { name: 'Thu tiền' })).not.toBeInTheDocument();
  });
});
