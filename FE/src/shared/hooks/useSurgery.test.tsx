import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { surgeryApi } from '../api/surgery.api';
import { useSession } from '../stores/session.store';
import { useCancelSurgery, usePlanSurgery, useSurgeries, useSurgery, useVisitSurgeries } from './useSurgery';
import { installMockApi } from '../api/mock/mock-api';

// Mock phẫu thuật: lịch sử #1 COMPLETED (lượt 2001), #2 CANCELLED (lượt 61), không có phẫu thuật mở ở chi nhánh 1. Điểm cần giữ: lệnh ghi làm mới ['surgery'] cả khi BE từ chối,
// vì 400 / 409 nghĩa là phẫu thuật hoặc lượt đã đổi ở nơi khác sau lúc mở màn.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'doctor@store1.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(
    () => ({
      done: useSurgeries({ status: 'COMPLETED' }),
      open: useSurgeries(),
      onVisit: useVisitSurgeries(2002),
      none: useSurgery(null),
      plan: usePlanSurgery(),
      cancel: useCancelSurgery(),
    }),
    { wrapper },
  );
}

describe('đọc', () => {
  it('danh sách theo trạng thái, theo lượt; chưa chọn phẫu thuật (null) thì không bắn request', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.done.data?.content.map((s) => s.surgeryId)).toEqual([1]));
    await waitFor(() => expect(result.current.onVisit.data).toEqual([]));
    expect(result.current.open.data?.totalElements).toBe(0);
    expect(result.current.none.fetchStatus).toBe('idle');
  });
});

describe('lệnh ghi làm mới ["surgery"]', () => {
  it('thành công: danh sách của lượt và danh sách việc cần làm thấy phẫu thuật mới ngay', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.onVisit.data).toEqual([]));

    await act(() => result.current.plan.mutateAsync({ visitId: 2002, serviceId: 7001, planNote: 'Triệt sản' }));

    await waitFor(() => expect(result.current.onVisit.data?.map((s) => s.status)).toEqual(['PLANNED']));
    await waitFor(() => expect(result.current.open.data?.totalElements).toBe(1));
  });

  it('BE từ chối (400 BR-KB-08) vì phẫu thuật đã đổi ở nơi khác: cache không giữ số cũ', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.onVisit.data).toEqual([]));
    const planned = await surgeryApi.plan(2002, { serviceId: 7001 }); // người khác lập trước, ngoài hook nên cache vẫn rỗng

    await act(async () => {
      await expect(result.current.plan.mutateAsync({ visitId: 2002, serviceId: 7004 })).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(result.current.onVisit.data?.map((s) => s.surgeryId)).toEqual([planned.surgeryId]));
  });

  it('hủy (409 khi đã hủy): vẫn làm mới', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.onVisit.data).toEqual([]));
    const planned = await surgeryApi.plan(2002, { serviceId: 7001 });
    await surgeryApi.cancel(planned.surgeryId, { reason: 'Khách đổi ý' }); // ngoài hook nên cache vẫn rỗng

    await act(async () => {
      await expect(result.current.cancel.mutateAsync({ surgeryId: planned.surgeryId, reason: 'Khách đổi ý' })).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(result.current.onVisit.data?.[0]?.status).toBe('CANCELLED'));
  });
});
