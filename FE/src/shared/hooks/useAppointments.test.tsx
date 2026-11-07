import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/axios';
import { appointmentApi } from '../api/appointment.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { useSession } from '../stores/session.store';
import { useAppointmentMutation, useRefreshAppointments } from './useAppointments';
import { useHomeVisit, useMyHomeVisits } from './useHomeVisit';

// T57: lịch khám tại nhà cũng là lịch hẹn: hủy / đổi giờ đi qua appointment-v1 (BR-LH-14) nên phải làm mới cả ['homevisit'] để màn khám tại nhà không giữ trạng thái cũ.
// Mock khám tại nhà: khach@petcare.vn có lịch #13 (Bim, BOOKED sau 2 ngày) và #12 (Tôm, BOOKED hôm nay).

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  const spy = vi.spyOn(client, 'invalidateQueries');
  const rendered = renderHook(
    () => ({
      mine: useMyHomeVisits(),
      detail: useHomeVisit(13),
      cancel: useAppointmentMutation((id: number) => appointmentApi.cancel(id, { reason: 'Khách bận' })),
      refresh: useRefreshAppointments(),
    }),
    { wrapper },
  );
  return { ...rendered, spy };
}
const keysOf = (spy: ReturnType<typeof setup>['spy']) => spy.mock.calls.map(([filters]) => filters?.queryKey);

describe('lệnh ghi lịch hẹn làm mới cả ["appointments"] và ["homevisit"]', () => {
  it('hủy lịch tại nhà: danh sách và chi tiết khám tại nhà thấy CANCELLED ngay, không phải chờ tải lại', async () => {
    const { result, spy } = setup();
    await waitFor(() => expect(result.current.detail.data).toMatchObject({ appointmentId: 13, status: 'BOOKED' }));
    await act(async () => {
      await result.current.cancel.mutateAsync(13);
    });
    expect(keysOf(spy)).toEqual(expect.arrayContaining([['appointments'], ['homevisit']]));
    await waitFor(() => expect(result.current.detail.data).toMatchObject({ appointmentId: 13, status: 'CANCELLED' }));
    await waitFor(() => expect(result.current.mine.data?.content.find((a) => a.appointmentId === 13)?.status).toBe('CANCELLED'));
  });

  it('hàm làm mới sau lệnh bị từ chối (đặt / đổi 4xx) cũng chạm ["homevisit"] và trả Promise<void>', async () => {
    const { result, spy } = setup();
    await waitFor(() => expect(result.current.mine.data).toBeDefined());
    spy.mockClear();
    let settled: unknown = 'chưa';
    await act(async () => {
      settled = await result.current.refresh();
    });
    expect(settled).toBeUndefined();
    expect(keysOf(spy)).toEqual([['appointments'], ['homevisit']]);
  });
});
