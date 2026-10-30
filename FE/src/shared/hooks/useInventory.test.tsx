import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { inventoryApi } from '../api/inventory.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { useSession } from '../stores/session.store';
import { useInventoryMutation, useStockLots } from './useInventory';

// Lô #16 (RB2309 của Rabisin, sản phẩm 5) còn 2 trên mock kho chi nhánh 1. Điểm cần giữ: lệnh ghi làm mới ['inventory'] cả khi BE từ chối,
// vì BE từ chối BR-KO-06 nghĩa là tồn đã đổi sau lúc mở form và ô chọn lô không được giữ số cũ.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(() => ({ lots: useStockLots(5), save: useInventoryMutation(inventoryApi.createAdjustment) }), { wrapper });
}

const reduce = (quantityDelta: number) => ({ reason: 'DAMAGED' as const, lines: [{ stockLotId: 16, quantityDelta }] });

describe('useInventoryMutation làm mới ["inventory"]', () => {
  it('thành công: ô chọn lô thấy tồn mới ngay sau lệnh ghi', async () => {
    const { result } = setup();
    const quantity = () => result.current.lots.data?.find((l) => l.stockLotId === 16)?.quantity;
    await waitFor(() => expect(quantity()).toBe(2));

    await act(() => result.current.save.mutateAsync(reduce(-1)));

    await waitFor(() => expect(quantity()).toBe(1));
  });

  it('lỗi BR-KO-06 vì tồn đã đổi sau lúc mở form: ô chọn lô nạp lại, không giữ số cũ', async () => {
    const { result } = setup();
    const quantity = () => result.current.lots.data?.find((l) => l.stockLotId === 16)?.quantity;
    await waitFor(() => expect(quantity()).toBe(2));
    await inventoryApi.createAdjustment(reduce(-1)); // người khác trừ trước, ngoài hook nên cache vẫn còn 2

    await act(async () => {
      await expect(result.current.save.mutateAsync(reduce(-3))).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(quantity()).toBe(1));
  });
});
