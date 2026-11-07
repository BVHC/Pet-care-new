import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { shopApi } from '../api/shop.api';
import { useSession } from '../stores/session.store';
import { COMMERCE_ROOTS, useCart, useFulfillmentBranches, useMyOnlineOrder, useMyOnlineOrders, useOnlineOrder, useOnlineOrderQuote, useOnlineOrders, useShopMutation } from './useShop';
import { installMockApi } from '../api/mock/mock-api';

// Giỏ mẫu của khach@petcare.vn: 1 túi hạt + 2 gói pate (486.000 đ). Điểm cần giữ: lệnh ghi làm mới ['shop'] (cả khi BE từ chối) và các gốc thương mại khác.
// Ba bảng route thương mại chưa nằm trong mock-api.ts nên test cài qua installCommerceMockApi (bỏ khi luồng chính đã đăng ký).

beforeAll(() => installMockApi(apiClient));

async function login(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}
beforeEach(() => login('khach@petcare.vn'));

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return { client, wrapper };
}

const setQuantity = ({ productId, quantity }: { productId: number; quantity: number }) => shopApi.setCartItem(productId, { quantity });

describe('đọc', () => {
  it('useCart: giỏ của khách đang đăng nhập', async () => {
    const { wrapper } = wrap();
    const { result } = renderHook(() => useCart(), { wrapper });
    await waitFor(() => expect(result.current.data?.items).toHaveLength(2));
    expect(result.current.data?.subtotal).toBe(486_000);
  });

  it('danh sách đơn của tôi, chi nhánh xử lý được, chi tiết đơn', async () => {
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ list: useMyOnlineOrders({ status: 'SHIPPED' }), branches: useFulfillmentBranches(), one: useMyOnlineOrder(3) }), { wrapper });
    await waitFor(() => expect(result.current.list.data?.content.map((o) => o.onlineOrderId)).toEqual([3]));
    await waitFor(() => expect(result.current.branches.data).toHaveLength(1));
    await waitFor(() => expect(result.current.one.data?.trackingCode).toBe('VTP2611055'));
  });

  it('báo giá và chi tiết chỉ chạy khi đã có tham số; id null thì không gọi', async () => {
    const { wrapper } = wrap();
    const { result, rerender } = renderHook(({ q, id }) => ({ quote: useOnlineOrderQuote(q), one: useMyOnlineOrder(id) }), {
      wrapper,
      initialProps: { q: null as { branchId: number; addressId: number } | null, id: null as number | null },
    });
    expect(result.current.quote.fetchStatus).toBe('idle');
    expect(result.current.one.fetchStatus).toBe('idle');
    rerender({ q: { branchId: 1, addressId: 1 }, id: 8 });
    await waitFor(() => expect(result.current.quote.data).toMatchObject({ subtotal: 486_000, shippingFee: 30_000, total: 516_000 }));
    await waitFor(() => expect(result.current.one.data?.status).toBe('PENDING_PAYMENT'));
  });

  it('nhân viên: danh sách và chi tiết đơn của chi nhánh', async () => {
    await login('reception@store1.vn');
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ list: useOnlineOrders(), one: useOnlineOrder(5) }), { wrapper });
    await waitFor(() => expect(result.current.list.data?.content.map((o) => o.onlineOrderId)).toEqual([6, 7, 4, 5]));
    await waitFor(() => expect(result.current.one.data?.status).toBe('PAID'));
  });
});

describe('useShopMutation làm mới ["shop"]', () => {
  function setup() {
    const { client, wrapper } = wrap();
    return { client, ...renderHook(() => ({ cart: useCart(), save: useShopMutation(setQuantity) }), { wrapper }) };
  }
  const quantityOf = (r: ReturnType<typeof setup>['result'], productId: number) => r.current.cart.data?.items.find((i) => i.productId === productId)?.quantity;

  it('thành công: giỏ thấy số lượng mới ngay sau lệnh ghi', async () => {
    const { result } = setup();
    await waitFor(() => expect(quantityOf(result, 2)).toBe(2));

    await act(() => result.current.save.mutateAsync({ productId: 2, quantity: 3 }));

    await waitFor(() => expect(quantityOf(result, 2)).toBe(3));
  });

  it('BE từ chối (400 BR-BH-07 số lượng ngoài 1–99) vẫn làm mới: giỏ không giữ số cũ', async () => {
    const { result } = setup();
    await waitFor(() => expect(quantityOf(result, 2)).toBe(2));
    await shopApi.setCartItem(2, { quantity: 1 }); // thiết bị khác đổi trước, ngoài hook nên cache vẫn còn 2

    await act(async () => {
      await expect(result.current.save.mutateAsync({ productId: 2, quantity: 100 })).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(quantityOf(result, 2)).toBe(1));
  });

  it('làm mới cả gốc thanh toán, trả hàng / hoàn tiền và kho (hủy đơn đã trả đổi cả bốn)', async () => {
    const { client, result } = setup();
    const spy = vi.spyOn(client, 'invalidateQueries');
    await waitFor(() => expect(result.current.cart.data).toBeDefined());

    await act(() => result.current.save.mutateAsync({ productId: 1, quantity: 2 }));

    for (const queryKey of COMMERCE_ROOTS) expect(spy).toHaveBeenCalledWith({ queryKey });
  });

  it('hủy đơn đã thanh toán: danh sách đơn của tôi thấy CANCELLED', async () => {
    const { wrapper } = wrap();
    const { result } = renderHook(() => ({ orders: useMyOnlineOrders(), cancel: useShopMutation((id: number) => shopApi.cancelMyOrder(id, { reason: 'Đổi ý' })) }), { wrapper });
    await waitFor(() => expect(result.current.orders.data?.content.find((o) => o.onlineOrderId === 5)?.status).toBe('PAID'));

    await act(() => result.current.cancel.mutateAsync(5));

    await waitFor(() => expect(result.current.orders.data?.content.find((o) => o.onlineOrderId === 5)?.status).toBe('CANCELLED'));
  });
});
