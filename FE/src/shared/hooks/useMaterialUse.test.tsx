import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { materialUseApi } from '../api/materialuse.api';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useMaterialShortages, useResolveShortage, useVisitMaterialUsages } from './useMaterialUse';
import { installMockApi } from '../api/mock/mock-api';

// Dữ liệu mẫu (materialuse.mock.ts): chi nhánh 1 có đúng một thiếu hụt chưa xử lý, dòng 3 (lượt 58). Điểm cần giữ: xử lý thiếu hụt làm mới ['materialuse'],
// kể cả khi BE từ chối 409 vì người khác đã xử lý trước.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(() => ({ open: useMaterialShortages(), done: useMaterialShortages({ resolved: true }), visit: useVisitMaterialUsages(58), resolve: useResolveShortage() }), { wrapper });
}

describe('useMaterialUse', () => {
  it('đọc: thiếu hụt chưa xử lý và vật tư của lượt', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.open.data?.content.map((u) => u.usageId)).toEqual([3]));
    await waitFor(() => expect(result.current.visit.data?.map((u) => u.usageId)).toEqual([3, 4]));
  });

  it('thành công: dòng chuyển từ "chưa xử lý" sang "đã xử lý" ở cả hai danh sách và ở lượt', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.open.data?.content).toHaveLength(1));
    await waitFor(() => expect(result.current.done.data?.content.map((u) => u.usageId)).toEqual([5]));

    await act(() => result.current.resolve.mutateAsync({ usageId: 3, note: 'Đã nhập thêm' }));

    await waitFor(() => expect(result.current.open.data?.content).toEqual([]));
    await waitFor(() => expect(result.current.done.data?.content.map((u) => u.usageId)).toEqual([5, 3]));
    await waitFor(() => expect(result.current.visit.data?.find((u) => u.usageId === 3)?.resolvedNote).toBe('Đã nhập thêm'));
  });

  it('bị 409 vì người khác đã xử lý trước (ngoài hook nên cache còn cũ): danh sách vẫn nạp lại', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.open.data?.content).toHaveLength(1));
    await materialUseApi.resolveShortage(3, { note: 'Người khác xử lý' });

    await act(async () => {
      await expect(result.current.resolve.mutateAsync({ usageId: 3, note: 'Tôi xử lý' })).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(result.current.open.data?.content).toEqual([]));
  });
});
