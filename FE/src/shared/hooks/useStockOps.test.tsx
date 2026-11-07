import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { stockopsApi } from '../api/stockops.api';
import { useSession } from '../stores/session.store';
import { useStockLots } from './useInventory';
import { useLotTrace, useStockCount, useStockCounts, useStockLotSearch, useStockOpsMutation, useStockTransfers } from './useStockOps';
import { installMockApi } from '../api/mock/mock-api';

// Phiếu mẫu của chi nhánh 1 (stockops.mock.ts): #3 và #4 đang IN_TRANSIT; #3 chuyển 30 Amoxicillin AMX2405 từ lô 13 (tồn 200). Điểm cần giữ: lệnh ghi làm mới
// ['stockops'] VÀ ['inventory'], kể cả khi BE từ chối (409 phiếu đã đổi trạng thái), vì tồn lô và danh sách phiếu có thể đã cũ.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(
    () => ({
      inTransit: useStockTransfers({ status: 'IN_TRANSIT' }),
      lots: useStockLots(3),
      cancel: useStockOpsMutation((a: { transferId: number; reason: string }) => stockopsApi.cancelTransfer(a.transferId, { reason: a.reason })),
    }),
    { wrapper },
  );
}

const ids = (result: ReturnType<typeof setup>['result']) => result.current.inTransit.data?.content.map((t) => t.transferId);
const lotQty = (result: ReturnType<typeof setup>['result'], lotId: number) => result.current.lots.data?.find((l) => l.stockLotId === lotId)?.quantity;

describe('useStockOpsMutation làm mới ["stockops"] và ["inventory"]', () => {
  it('thành công: danh sách phiếu và tồn lô cùng cập nhật ngay sau khi hủy', async () => {
    const { result } = setup();
    await waitFor(() => expect(ids(result)).toEqual([4, 3]));
    await waitFor(() => expect(lotQty(result, 13)).toBe(200));

    await act(() => result.current.cancel.mutateAsync({ transferId: 3, reason: 'Nhầm lô' }));

    await waitFor(() => expect(ids(result)).toEqual([4]));
    await waitFor(() => expect(lotQty(result, 13)).toBe(230));
  });

  it('bị 409 vì người khác đã hủy trước (ngoài hook nên cache còn cũ): danh sách và tồn vẫn nạp lại', async () => {
    const { result } = setup();
    await waitFor(() => expect(ids(result)).toEqual([4, 3]));
    await stockopsApi.cancelTransfer(3, { reason: 'Người khác hủy' });

    await act(async () => {
      await expect(result.current.cancel.mutateAsync({ transferId: 3, reason: 'Tôi hủy' })).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(ids(result)).toEqual([4]));
    await waitFor(() => expect(lotQty(result, 13)).toBe(230));
  });
});

describe('truy vấn đọc', () => {
  const wrap = () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  };

  it('tìm lô chỉ chạy khi có số lô hoặc sản phẩm; truy vết và chi tiết phiên chạy theo id', async () => {
    const wrapper = wrap();
    const empty = renderHook(() => useStockLotSearch({}), { wrapper });
    expect(empty.result.current.fetchStatus).toBe('idle');
    const blank = renderHook(() => useStockLotSearch({ lotNumber: '  ' }), { wrapper });
    expect(blank.result.current.fetchStatus).toBe('idle');

    const search = renderHook(() => useStockLotSearch({ lotNumber: 'amx' }), { wrapper });
    await waitFor(() => expect(search.result.current.data?.content.map((l) => l.lotId)).toEqual([14, 13]));

    const none = renderHook(() => ({ trace: useLotTrace(null), count: useStockCount(null) }), { wrapper });
    expect(none.result.current.trace.fetchStatus).toBe('idle');
    expect(none.result.current.count.fetchStatus).toBe('idle');
    const trace = renderHook(() => useLotTrace(15), { wrapper });
    await waitFor(() => expect(trace.result.current.data?.remaining).toBe(15));
    const counts = renderHook(() => useStockCounts(), { wrapper });
    await waitFor(() => expect(counts.result.current.data?.content.map((c) => c.countId)).toEqual([2, 1]));
    const count = renderHook(() => useStockCount(1), { wrapper });
    await waitFor(() => expect(count.result.current.data?.status).toBe('CONFIRMED'));
  });
});
