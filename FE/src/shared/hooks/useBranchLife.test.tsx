import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { branchlifeApi } from '../api/branchlife.api';
import { registerClosureSources } from '../api/mock/branchlife.mock';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useBranchLifeMutation, useClosureImpact } from './useBranchLife';
import { installMockApi } from '../api/mock/mock-api';

// Điểm cần giữ: lệnh ghi làm mới ['branchlife'] cả khi BE từ chối, vì 400 BR-CN-08 nghĩa là tồn đọng đã đổi sau lúc mở form.

beforeAll(() => installMockApi(apiClient));

let restore: (() => void) | undefined;
afterEach(() => restore?.());

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  const hook = renderHook(
    () => ({
      impact: useClosureImpact(1),
      none: useClosureImpact(null),
      suspend: useBranchLifeMutation((reason: string) => branchlifeApi.suspend(1, { reason, cancelBookings: true })),
      resume: useBranchLifeMutation(() => branchlifeApi.resume(1)),
    }),
    { wrapper },
  );
  return { ...hook, client };
}

describe('useClosureImpact', () => {
  it('đọc tồn đọng của chi nhánh; chưa chọn chi nhánh thì không gọi', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.impact.data?.bookedAppointments).toBeGreaterThan(0));
    expect(result.current.impact.data?.suspendBlockers).toEqual([]);
    expect(result.current.none.fetchStatus).toBe('idle');
    expect(result.current.none.data).toBeUndefined();
  });

  it('không retry lỗi 4xx: nhân viên chi nhánh bị 403 ngay', async () => {
    useSession.getState().clear();
    await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });
    const { result } = setup();
    await waitFor(() => expect(result.current.impact.isError).toBe(true));
    expect(result.current.impact.failureCount).toBe(1);
  });
});

describe('useBranchLifeMutation làm mới ["branchlife"]', () => {
  it('thành công: số lịch chờ về 0 ngay sau khi tạm ngừng; mở lại làm mới ["branch"]', async () => {
    const { result, client } = setup();
    await waitFor(() => expect(result.current.impact.data?.bookedAppointments).toBeGreaterThan(0));
    client.setQueryData(['branch', 1], { status: 'ACTIVE' });

    await act(() => result.current.suspend.mutateAsync('Sửa chữa'));
    await waitFor(() => expect(result.current.impact.data?.bookedAppointments).toBe(0));
    expect(client.getQueryState(['branch', 1])?.isInvalidated).toBe(true);

    let resumed: unknown;
    await act(async () => {
      resumed = await result.current.resume.mutateAsync(undefined);
    });
    expect(resumed).toMatchObject({ status: 'ACTIVE' });
  });

  it('BR-CN-08: BE từ chối vì tồn đọng đã đổi sau lúc mở form, màn hình nạp lại số mới', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.impact.data?.openVisits).toBe(0));
    restore = registerClosureSources({ openVisits: () => 2 }); // người khác mở lượt khám, ngoài hook nên cache vẫn còn 0

    await act(async () => {
      await expect(result.current.suspend.mutateAsync('Sửa chữa')).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(result.current.impact.data?.openVisits).toBe(2));
    expect(result.current.impact.data?.suspendBlockers).toHaveLength(1);
  });
});
