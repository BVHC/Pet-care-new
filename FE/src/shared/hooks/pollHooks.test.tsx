// D055 / ADR-0091: mọi poll (refetchInterval) phải (1) đánh dấu request là poll để interceptor không bao giờ làm mới token (tái tạo D024),
// (2) tạm dừng khi access token đã hết hạn và (3) chạy lại khi token được làm mới.
import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { useStayCareLogs } from '../../pages/customer/boarding-detail/hooks';
import { apiClient } from '../api/axios';
import { useSession } from '../stores/session.store';
import { useUnreadCount } from './useNotifications';
import { usePendingSalesOrders } from './usePendingSalesOrders';
import { useArrivals, useMyQueue, useVisitQueue } from './useVisits';

const HOOKS: [name: string, hook: () => unknown, url: string][] = [
  ['useVisitQueue', () => useVisitQueue(), '/api/visits'],
  ['useMyQueue', () => useMyQueue(), '/api/me/queue'],
  ['useArrivals', () => useArrivals(), '/api/appointments'],
  ['usePendingSalesOrders', () => usePendingSalesOrders(), '/api/orders'],
  ['useUnreadCount', () => useUnreadCount(), '/api/me/notifications/unread-count'],
  ['useStayCareLogs', () => useStayCareLogs({ bookingId: 7, status: 'CHECKED_IN' }), '/api/boarding-bookings/7/care-logs'],
];

const inMinutes = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

let get: MockInstance<typeof apiClient.get>;
const callsTo = (url: string) => get.mock.calls.filter(([u]) => u === url);

beforeEach(() => {
  get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: { content: [], count: 0 } } });
  useSession.setState({
    token: 't',
    expiresAt: null,
    account: { id: 4, email: 'u@store1.vn', role: 'RECEPTIONIST', status: 'ACTIVE', isLocked: false, mustChangePassword: false, branchId: 1 },
    refreshToken: 'r',
    accessTokenExpiresAt: inMinutes(8),
  });
});

afterEach(() => vi.restoreAllMocks());

describe.each(HOOKS)('%s', (_name, hook, url) => {
  it('đánh dấu request của nó là poll', async () => {
    renderHook(hook, { wrapper });

    await waitFor(() => expect(callsTo(url).length).toBeGreaterThan(0));
    expect(callsTo(url)[0][1]).toMatchObject({ poll: true });
  });

  it('access token đã hết hạn: không gọi API; token mới về thì gọi lại', async () => {
    useSession.setState({ accessTokenExpiresAt: inMinutes(-1) });
    renderHook(hook, { wrapper });
    await new Promise((r) => setTimeout(r, 30));
    expect(callsTo(url)).toHaveLength(0);

    act(() => useSession.setState({ accessTokenExpiresAt: inMinutes(10) }));

    await waitFor(() => expect(callsTo(url).length).toBeGreaterThan(0));
  });

  it('phiên cũ chưa có hạn access (accessTokenExpiresAt null): vẫn gọi như trước', async () => {
    useSession.setState({ refreshToken: null, accessTokenExpiresAt: null });
    renderHook(hook, { wrapper });

    await waitFor(() => expect(callsTo(url).length).toBeGreaterThan(0));
  });
});
