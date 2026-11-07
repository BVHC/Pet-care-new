import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { returnsApi } from '../api/returns.api';
import { useSession } from '../stores/session.store';
import { useRefunds, useReturnRequest, useReturnRequests, useReturnsMutation } from './useReturns';
import { installMockApi } from '../api/mock/mock-api';

// Trả hàng chi nhánh 1: #1 và #5 PENDING; hoàn tiền #3 (GATEWAY FAILED) và #2 (CASH REQUESTED). Điểm cần giữ: lệnh ghi làm mới ['returns'] cả khi BE từ chối
// (người khác vừa duyệt → 409), vì trạng thái yêu cầu và hoàn tiền đã đổi sau lúc mở màn.

beforeAll(() => installMockApi(apiClient));

async function login(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return { client, wrapper };
}

const ids = (page?: { content: { requestId?: number; refundId?: number }[] }) => page?.content.map((x) => x.requestId ?? x.refundId);

describe('lễ tân', () => {
  it('đọc: yêu cầu PENDING và chi tiết; id null thì không gọi', async () => {
    await login('reception@store1.vn');
    const { wrapper } = wrap();
    const { result, rerender } = renderHook(({ id }) => ({ list: useReturnRequests(), one: useReturnRequest(id) }), { wrapper, initialProps: { id: null as number | null } });
    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5]));
    expect(result.current.one.fetchStatus).toBe('idle');
    rerender({ id: 2 });
    await waitFor(() => expect(result.current.one.data).toMatchObject({ requestId: 2, status: 'APPROVED' }));
  });

  it('tạo yêu cầu làm mới danh sách', async () => {
    await login('reception@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ list: useReturnRequests(), create: useReturnsMutation(returnsApi.create) }), { wrapper });
    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5]));

    await act(() => result.current.create.mutateAsync({ orderId: 5, lines: [{ orderLineId: 51, quantity: 1 }], reasonCode: 'DEFECTIVE' }));

    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5, 8]));
  });

  it('BE từ chối (400 BR-BH-13 vượt số lượng được trả) vẫn làm mới: danh sách thấy yêu cầu do máy khác tạo', async () => {
    await login('reception@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ list: useReturnRequests(), create: useReturnsMutation(returnsApi.create) }), { wrapper });
    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5]));
    await returnsApi.create({ orderId: 5, lines: [{ orderLineId: 51, quantity: 3 }], reasonCode: 'OTHER' }); // máy khác: dòng #51 hết số lượng được trả

    await act(async () => {
      await expect(result.current.create.mutateAsync({ orderId: 5, lines: [{ orderLineId: 51, quantity: 1 }], reasonCode: 'OTHER' })).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5, 8]));
  });

  it('hoàn tiền: danh sách mặc định rồi xác nhận làm mới (không có ca OPEN → 400 BR-TG-01 vẫn làm mới)', async () => {
    await login('reception@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ refunds: useRefunds(), confirm: useReturnsMutation((id: number) => returnsApi.confirmRefund(id)) }), { wrapper });
    await waitFor(() => expect(ids(result.current.refunds.data)).toEqual([3, 2]));

    await act(async () => {
      await expect(result.current.confirm.mutateAsync(2)).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(result.current.refunds.isFetching).toBe(false));
    expect(ids(result.current.refunds.data)).toEqual([3, 2]);
  });
});

describe('quản lý chi nhánh', () => {
  it('duyệt làm mới danh sách PENDING và hoàn tiền', async () => {
    await login('manager@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(
      () => ({ list: useReturnRequests(), refunds: useRefunds({ method: 'CASH' }), approve: useReturnsMutation((id: number) => returnsApi.approve(id, { restock: true })) }),
      { wrapper },
    );
    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5]));
    await waitFor(() => expect(ids(result.current.refunds.data)).toEqual([2]));

    await act(() => result.current.approve.mutateAsync(1));

    await waitFor(() => expect(ids(result.current.list.data)).toEqual([5]));
    await waitFor(() => expect(ids(result.current.refunds.data)).toEqual([2, 5])); // hoàn tiền mới #5 (CASH, vì Order quầy thu tiền mặt)
  });

  it('409 vì người khác vừa duyệt: danh sách vẫn được làm mới', async () => {
    await login('manager@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ list: useReturnRequests(), approve: useReturnsMutation((id: number) => returnsApi.approve(id, { restock: false })) }), { wrapper });
    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1, 5]));
    await returnsApi.reject(5, { reason: 'Quản lý khác từ chối trước' }); // ngoài hook

    await act(async () => {
      await expect(result.current.approve.mutateAsync(5)).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(ids(result.current.list.data)).toEqual([1]));
  });

  it('thử lại hoàn tiền qua cổng: FAILED → REQUESTED trong danh sách mặc định', async () => {
    await login('manager@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ refunds: useRefunds({ method: 'GATEWAY' }), retry: useReturnsMutation((id: number) => returnsApi.retryRefund(id)) }), { wrapper });
    await waitFor(() => expect(result.current.refunds.data?.content[0]).toMatchObject({ refundId: 3, status: 'FAILED', attempts: 3 }));

    await act(() => result.current.retry.mutateAsync(3));

    await waitFor(() => expect(result.current.refunds.data?.content[0]).toMatchObject({ refundId: 3, status: 'REQUESTED', attempts: 0 }));
  });
});
