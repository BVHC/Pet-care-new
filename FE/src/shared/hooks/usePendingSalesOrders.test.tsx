import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { salesApi } from '../api/sales.api';
import { useSession } from '../stores/session.store';
import type { AccountSummary, Role } from '../types/auth';
import type { OrderSummary } from '../types/sales';
import { usePendingSalesOrders } from './usePendingSalesOrders';

const account = (role: Role): AccountSummary => ({ id: 4, email: 'u@store1.vn', role, status: 'ACTIVE', isLocked: false, mustChangePassword: false, branchId: 1 });
const signIn = (role: Role | null) =>
  useSession.setState({ token: role && 't', expiresAt: null, account: role && account(role), fullName: null });

const summary = (orderId: number): OrderSummary => ({
  orderId,
  code: `DH-2610-0000${orderId}`,
  source: 'RETAIL',
  status: 'PENDING',
  customerId: 1,
  customerName: 'Khách',
  branchId: 1,
  totalAmount: 1000,
  createdAt: '2026-10-22T03:00:00Z',
  pendingAt: '2026-10-22T03:05:00Z',
});

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.spyOn(salesApi, 'listOrders').mockResolvedValue({ content: [summary(2), summary(4)], page: 0, size: 100, totalElements: 2, totalPages: 1 });
});

describe('usePendingSalesOrders', () => {
  it('lễ tân: lấy Order PENDING của chi nhánh (size 100) và trả về mảng OrderSummary', async () => {
    signIn('RECEPTIONIST');
    const { result } = renderHook(() => usePendingSalesOrders(), { wrapper });
    await waitFor(() => expect(result.current.data?.map((o) => o.orderId)).toEqual([2, 4]));
    expect(salesApi.listOrders).toHaveBeenCalledWith({ status: 'PENDING', size: 100 });
  });

  it.each(['BRANCH_MANAGER', 'VET', 'CARETAKER'] as Role[])('%s (A05, A07, A08) cũng đọc được danh sách Order', async (role) => {
    signIn(role);
    const { result } = renderHook(() => usePendingSalesOrders(), { wrapper });
    await waitFor(() => expect(result.current.data).toHaveLength(2));
  });

  it.each(['ADMIN', 'SUPER_MANAGER', 'CUSTOMER', null] as (Role | null)[])('%s: không gọi API (BE trả 401 / 403)', async (role) => {
    signIn(role);
    const { result } = renderHook(() => usePendingSalesOrders(), { wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(result.current.data).toBeUndefined();
    expect(salesApi.listOrders).not.toHaveBeenCalled();
  });

  it('tự làm mới (~15 giây) để quầy thấy Order mới chốt mà không cần tải lại trang', () => {
    signIn('RECEPTIONIST');
    const client = new QueryClient();
    const { result } = renderHook(() => usePendingSalesOrders(), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    expect(result.current.isLoading).toBe(true);
    const query = client.getQueryCache().findAll({ queryKey: ['sales', 'orders'] })[0];
    const interval = (query.observers[0].options as { refetchInterval?: number }).refetchInterval;
    expect(interval).toBeGreaterThanOrEqual(10_000);
    expect(interval).toBeLessThanOrEqual(20_000);
  });

  it('tắt được bằng tham số enabled', async () => {
    signIn('RECEPTIONIST');
    renderHook(() => usePendingSalesOrders(false), { wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(salesApi.listOrders).not.toHaveBeenCalled();
  });
});
