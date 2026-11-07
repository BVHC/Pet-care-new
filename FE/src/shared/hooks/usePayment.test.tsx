import { createElement, useState, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { gatewaySignature } from '../api/mock/payment.mock';
import { paymentApi } from '../api/payment.api';
import { useSession } from '../stores/session.store';
import { useMyOnlineOrder } from './useShop';
import { useMyPaymentTransaction, usePaymentMutation, useReconciliationItems, useReconciliations } from './usePayment';
import { installMockApi } from '../api/mock/mock-api';

// Giao dịch #8 của đơn #8 (khach@petcare.vn) FAILED; phiên đối soát #2 có 3 mục (#1 AMOUNT_DIFF, #2 đã xử lý, #3 STATUS_DIFF). Điểm cần giữ: callback của cổng làm mới
// trang kết quả (giao dịch → SUCCEEDED) và đơn (→ PAID) qua ['payment'] + ['shop']; lệnh ghi làm mới cả khi BE từ chối.

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

describe('thanh toán của khách', () => {
  it('đọc: trạng thái giao dịch; id null thì không gọi', async () => {
    await login('khach@petcare.vn');
    const { wrapper } = wrap();
    const { result, rerender } = renderHook(({ id }) => useMyPaymentTransaction(id), { wrapper, initialProps: { id: null as number | null } });
    expect(result.current.fetchStatus).toBe('idle');
    rerender({ id: 8 });
    await waitFor(() => expect(result.current.data).toMatchObject({ status: 'FAILED', failureReason: 'Khách hủy giao dịch tại cổng' }));
  });

  it('pay rồi callback của cổng: trang kết quả và đơn tự thấy SUCCEEDED / PAID', async () => {
    await login('khach@petcare.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(
      () => {
        const [transactionId, setTransactionId] = useState<number | null>(null);
        return { transactionId, setTransactionId, txn: useMyPaymentTransaction(transactionId), order: useMyOnlineOrder(8), run: usePaymentMutation((fn: () => Promise<unknown>) => fn()) };
      },
      { wrapper },
    );
    await waitFor(() => expect(result.current.order.data?.status).toBe('PENDING_PAYMENT'));

    let session!: { transactionId: number; code: string; amount: number };
    await act(async () => {
      session = await result.current.run.mutateAsync(() => paymentApi.pay(8)) as typeof session;
      result.current.setTransactionId(session.transactionId);
    });
    await waitFor(() => expect(result.current.txn.data?.status).toBe('PENDING'));

    const body = { transactionCode: session.code, gatewayTxnId: 'VNP1', status: '00', amount: session.amount };
    await act(() => result.current.run.mutateAsync(() => paymentApi.gatewayCallback({ ...body, signature: gatewaySignature(body) })));

    await waitFor(() => expect(result.current.txn.data?.status).toBe('SUCCEEDED'));
    await waitFor(() => expect(result.current.order.data?.status).toBe('PAID'));
  });

  it('BE từ chối (400 BR-TG-07 chữ ký sai) vẫn làm mới: trang kết quả không giữ trạng thái cũ', async () => {
    await login('khach@petcare.vn');
    const session = await paymentApi.pay(8);
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ txn: useMyPaymentTransaction(session.transactionId), run: usePaymentMutation((fn: () => Promise<unknown>) => fn()) }), { wrapper });
    await waitFor(() => expect(result.current.txn.data?.status).toBe('PENDING'));
    const body = { transactionCode: session.code, gatewayTxnId: 'VNP1', status: '00', amount: session.amount };
    await paymentApi.gatewayCallback({ ...body, signature: gatewaySignature(body) }); // ngoài hook: cổng báo thành công, cache vẫn PENDING

    await act(async () => {
      await expect(result.current.run.mutateAsync(() => paymentApi.gatewayCallback({ ...body, signature: 'sai' }))).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(result.current.txn.data?.status).toBe('SUCCEEDED'));
  });
});

describe('đối soát (SUPER_MANAGER)', () => {
  it('đọc phiên đối soát và mục lệch; ghi chú đã xử lý làm mới danh sách mục', async () => {
    await login('chuoi@petcare.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(
      () => ({
        sessions: useReconciliations(),
        open: useReconciliationItems(2, { resolved: false }),
        resolve: usePaymentMutation((itemId: number) => paymentApi.resolveItem(itemId, { note: 'Đã đối chiếu với cổng' })),
      }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.sessions.data?.content.map((r) => r.reconciliationId)).toEqual([1, 2, 3, 4]));
    await waitFor(() => expect(result.current.open.data?.content.map((i) => i.itemId)).toEqual([1, 3]));

    await act(() => result.current.resolve.mutateAsync(1));

    await waitFor(() => expect(result.current.open.data?.content.map((i) => i.itemId)).toEqual([3]));
  });

  it('mục đã xử lý: 409 nhưng danh sách vẫn được làm mới', async () => {
    await login('chuoi@petcare.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ open: useReconciliationItems(2, { resolved: false }), resolve: usePaymentMutation((itemId: number) => paymentApi.resolveItem(itemId, { note: 'x' })) }), { wrapper });
    await waitFor(() => expect(result.current.open.data?.totalElements).toBe(2));
    await paymentApi.resolveItem(3, { note: 'người khác xử lý trước' }); // ngoài hook

    await act(async () => {
      await expect(result.current.resolve.mutateAsync(3)).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(result.current.open.data?.content.map((i) => i.itemId)).toEqual([1]));
  });
});
