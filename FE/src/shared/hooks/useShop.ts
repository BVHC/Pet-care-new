// React Query cho cửa hàng online (UC61, 62, 64, 65; shop-v1 #1–19): khóa gốc ['shop']. Một useQuery cho mỗi GET; mọi lệnh ghi đi qua `useShopMutation(fn)`.
// Lệnh ghi làm mới cả gốc ['shop'] KỂ CẢ khi BE từ chối (onSettled): 400 BR-BH-08 thiếu hàng / 409 đơn đã đổi trạng thái nghĩa là dữ liệu trên màn đã cũ.
// Đặt, hủy, bàn giao đơn còn đổi giao dịch thanh toán, hoàn tiền và tồn kho nên làm mới thêm ['payment'], ['returns'], ['inventory'] (COMMERCE_ROOTS).
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { shopApi } from '../api/shop.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { OnlineOrderQuery, QuoteQuery } from '../types/shop';

/** Lỗi 4xx (400 BR-BH-07/08, 403, 404) không đổi khi thử lại; lỗi mạng / 5xx thử lại tối đa 3 lần. */
export const commerceQueryOptions = {
  retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
  retryDelay,
} as const;

/** Mọi khóa gốc có thể đổi sau một lệnh ghi thương mại: thanh toán, hoàn tiền / trả hàng, tồn kho. */
export const COMMERCE_ROOTS = [['shop'], ['payment'], ['returns'], ['inventory']] as const;

export const useCart = () => useQuery({ queryKey: ['shop', 'cart'], queryFn: shopApi.getCart, ...commerceQueryOptions });

export const useFulfillmentBranches = (enabled = true) =>
  useQuery({ queryKey: ['shop', 'fulfillment-branches'], queryFn: shopApi.listFulfillmentBranches, enabled, ...commerceQueryOptions });

/** Báo giá chỉ chạy khi đã chọn chi nhánh và địa chỉ (`query` null = chưa chọn). */
export const useOnlineOrderQuote = (query: QuoteQuery | null) =>
  useQuery({ queryKey: ['shop', 'quote', query], queryFn: () => shopApi.quote(query!), enabled: query != null, ...commerceQueryOptions });

export const useMyOnlineOrders = (query: OnlineOrderQuery = {}) =>
  useQuery({ queryKey: ['shop', 'my-orders', query], queryFn: () => shopApi.listMyOrders(query), placeholderData: keepPreviousData, ...commerceQueryOptions });

export const useMyOnlineOrder = (onlineOrderId: number | null) =>
  useQuery({ queryKey: ['shop', 'my-order', onlineOrderId], queryFn: () => shopApi.getMyOrder(onlineOrderId!), enabled: onlineOrderId != null, ...commerceQueryOptions });

/** Đơn của chi nhánh cho lễ tân / quản lý (`status` vắng = việc cần làm). */
export const useOnlineOrders = (query: OnlineOrderQuery = {}) =>
  useQuery({ queryKey: ['shop', 'orders', query], queryFn: () => shopApi.listOrders(query), placeholderData: keepPreviousData, ...commerceQueryOptions });

export const useOnlineOrder = (onlineOrderId: number | null) =>
  useQuery({ queryKey: ['shop', 'order', onlineOrderId], queryFn: () => shopApi.getOrder(onlineOrderId!), enabled: onlineOrderId != null, ...commerceQueryOptions });

/** Hook ghi dùng chung của ba module thương mại: làm mới `COMMERCE_ROOTS` khi xong, kể cả khi BE từ chối. */
export function useCommerceMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => Promise.all(COMMERCE_ROOTS.map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
}

export const useShopMutation = useCommerceMutation;
