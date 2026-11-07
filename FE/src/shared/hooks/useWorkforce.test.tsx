import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { workforceApi } from '../api/workforce.api';
import { useSession } from '../stores/session.store';
import { addDays } from '../utils/branch-schedule';
import { vnDate } from '../utils/vn-time';
import { useMyShiftAssignments, useShiftRequests, useWorkforceMutation } from './useWorkforce';
import { installMockApi } from '../api/mock/mock-api';

// Dữ liệu mẫu (workforce.mock.ts): hai yêu cầu PENDING #1 (nhân viên 5 = doctor@store1.vn xin nghỉ) và #2 (nhân viên 6 xin đổi ca). Điểm cần giữ: lệnh ghi làm mới ['workforce'] cả khi BE
// từ chối, vì 409 ở duyệt nghĩa là người khác đã xử lý yêu cầu trước và danh sách chờ duyệt đang mở đã cũ.

beforeAll(() => installMockApi(apiClient));

async function loginAs(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
}

describe('useMyShiftAssignments (#8)', () => {
  beforeEach(() => loginAs('doctor@store1.vn'));

  it('đọc lịch của chính mình trong khoảng ngày; tắt được bằng enabled', async () => {
    const today = vnDate();
    const query = { from: addDays(today, -7), to: addDays(today, 10) };
    const { result } = renderHook(() => useMyShiftAssignments(query), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.length).toBeGreaterThan(5);
    expect(result.current.data!.every((a) => a.staffId === 5 && a.workDate >= query.from && a.workDate <= query.to)).toBe(true);

    const idle = renderHook(() => useMyShiftAssignments(query, false), { wrapper: wrapper() });
    expect(idle.result.current.fetchStatus).toBe('idle');
  });

  it('khoảng ngày quá 62 ngày → 400 BR-NS-03, hiện lỗi và không thử lại', async () => {
    const query = { from: '2026-01-01', to: '2026-12-31' };
    const { result } = renderHook(() => useMyShiftAssignments(query), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.failureCount).toBe(1);
    expect(result.current.error).toMatchObject({ response: { status: 400 } });
  });
});

describe('useWorkforceMutation làm mới ["workforce"]', () => {
  beforeEach(() => loginAs('manager@store1.vn'));

  function setup() {
    return renderHook(() => ({ pending: useShiftRequests(), approve: useWorkforceMutation(workforceApi.approveShiftRequest) }), { wrapper: wrapper() });
  }
  const ids = (r: ReturnType<typeof setup>['result']) => r.current.pending.data?.content.map((x) => x.shiftRequestId);

  it('thành công: danh sách chờ duyệt bỏ yêu cầu vừa duyệt ngay sau lệnh ghi', async () => {
    const { result } = setup();
    await waitFor(() => expect(ids(result)).toEqual([1, 2]));

    await act(() => result.current.approve.mutateAsync(1));

    await waitFor(() => expect(ids(result)).toEqual([2]));
  });

  it('lỗi 409 vì người khác đã xử lý trước: danh sách nạp lại, không giữ yêu cầu đã đóng', async () => {
    const { result } = setup();
    await waitFor(() => expect(ids(result)).toEqual([1, 2]));
    await workforceApi.approveShiftRequest(1); // người khác duyệt trước, ngoài hook nên cache vẫn còn [1, 2]

    await act(async () => {
      await expect(result.current.approve.mutateAsync(1)).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(ids(result)).toEqual([2]));
  });
});
