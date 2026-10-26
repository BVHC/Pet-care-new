import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { salesApi } from '../api/sales.api';
import { visitApi } from '../api/visit.api';
import { useSession } from '../stores/session.store';
import type { Role } from '../types/auth';
import type { OrderSource, OrderSummary } from '../types/sales';
import { makeVisit } from '../../test/visit-harness';
import { useHandoffNotices } from './useWorkspaceSignals';

const toast = vi.hoisted(() => vi.fn());
vi.mock('sonner', () => ({ toast }));

const signIn = (role: Role) =>
  useSession.setState({
    token: 't',
    expiresAt: null,
    account: { id: 4, email: 'u@store1.vn', role, status: 'ACTIVE', isLocked: false, mustChangePassword: false, branchId: 1 },
    fullName: null,
  });

const order = (orderId: number, customerName: string, source: OrderSource): OrderSummary => ({
  orderId,
  code: `DH-2610-0000${orderId}`,
  source,
  status: 'PENDING',
  customerId: orderId,
  customerName,
  branchId: 1,
  totalAmount: 50_000,
  createdAt: '2026-10-22T03:00:00Z',
  pendingAt: '2026-10-22T03:05:00Z',
});
const page = (content: OrderSummary[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });

function setup(workspace: 'reception' | 'doctor' | 'grooming' | 'admin') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  renderHook(() => useHandoffNotices(workspace), { wrapper });
  return client;
}

beforeEach(() => {
  toast.mockClear();
});

describe('useHandoffNotices — Order mới chờ thu (T35)', () => {
  it('quầy lễ tân: Order vừa chốt hiện toast, Order có sẵn lúc mở trang thì không', async () => {
    signIn('RECEPTIONIST');
    const list = vi
      .spyOn(salesApi, 'listOrders')
      .mockResolvedValueOnce(page([order(2, 'Nguyễn Thu Trang', 'VISIT')]))
      .mockResolvedValue(page([order(2, 'Nguyễn Thu Trang', 'VISIT'), order(9, 'Lê Thị Hoa', 'RETAIL')]));
    const client = setup('reception');
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 30));
    expect(toast).not.toHaveBeenCalled();

    await client.invalidateQueries({ queryKey: ['sales'] });
    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(toast).toHaveBeenCalledWith('Đơn mới chờ thu của Lê Thị Hoa', { description: 'Mời Lê Thị Hoa đến quầy thu tiền (DH-2610-00009).' });
  });

  it.each([
    ['doctor', 'VET'],
    ['admin', 'SUPER_MANAGER'],
  ] as const)('bàn %s (%s) không hỏi danh sách Order chờ thu', async (workspace, role) => {
    signIn(role);
    const list = vi.spyOn(salesApi, 'listOrders').mockResolvedValue(page([]));
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([]);
    setup(workspace);
    await new Promise((r) => setTimeout(r, 30));
    expect(list).not.toHaveBeenCalled();
  });
});

describe('useHandoffNotices — lượt mới vào hàng đợi của tôi (T36, UC46)', () => {
  const mine = (id: number, over = {}) => makeVisit(id, { assigneeId: 4, assigneeName: 'Tôi', ...over });

  it.each([
    ['doctor', 'VET', 'MEDICAL', 'Khám/Tiêm'],
    ['grooming', 'CARETAKER', 'GROOMING', 'Thẩm mỹ'],
  ] as const)('bàn %s (%s): lượt chờ mới được gán hiện toast kèm mã lượt, lượt có sẵn lúc mở trang thì không', async (workspace, role, group, groupLabel) => {
    signIn(role);
    const existing = mine(1, { petName: 'Có sẵn', serviceGroup: group });
    const arrived = mine(2, { petName: 'Mới đến', code: 'TN-2610-00099', serviceGroup: group });
    const list = vi.spyOn(visitApi, 'getMyQueue').mockResolvedValueOnce([existing]).mockResolvedValue([existing, arrived]);
    const client = setup(workspace);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 30));
    expect(toast).not.toHaveBeenCalled();

    await client.invalidateQueries({ queryKey: ['visits'] });

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(toast).toHaveBeenCalledWith('TN-2610-00099: Mới đến vào hàng chờ của bạn', { description: groupLabel });
  });

  it('lượt xuất hiện ở trạng thái đã gọi (đã khám dở) thì không báo là vào hàng chờ', async () => {
    signIn('VET');
    const list = vi
      .spyOn(visitApi, 'getMyQueue')
      .mockResolvedValueOnce([])
      .mockResolvedValue([mine(3, { status: 'IN_PROGRESS' })]);
    const client = setup('doctor');
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));

    await client.invalidateQueries({ queryKey: ['visits'] });
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
    await new Promise((r) => setTimeout(r, 30));

    expect(toast).not.toHaveBeenCalled();
  });

  it.each([
    ['reception', 'RECEPTIONIST'],
    ['admin', 'SUPER_MANAGER'],
    ['doctor', 'BRANCH_MANAGER'],
  ] as const)('bàn %s với vai trò %s không hỏi hàng đợi của tôi (BE trả 403)', async (workspace, role) => {
    signIn(role);
    vi.spyOn(salesApi, 'listOrders').mockResolvedValue(page([]));
    const list = vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([]);
    setup(workspace);
    await new Promise((r) => setTimeout(r, 30));

    expect(list).not.toHaveBeenCalled();
  });
});
