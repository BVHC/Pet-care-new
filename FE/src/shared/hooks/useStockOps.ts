// React Query cho chuyển kho, kiểm kê, truy vết lô (UC77–79): khóa gốc ['stockops']. Mọi lệnh ghi làm mới cả ['stockops'] lẫn ['inventory']
// (chuyển kho, nhận hàng, hủy và xác nhận kiểm kê đổi tồn, lô và phiếu điều chỉnh của kho) và làm mới cả khi BE từ chối, vì phiếu / phiên có thể
// đã đổi trạng thái (409) hoặc tồn đã đổi sau lúc mở form (400 BR-KO-08). Lỗi 4xx không thử lại (`shouldRetry`).
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { stockopsApi } from '../api/stockops.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { LotSearchQuery, StockCountQuery, StockTransferQuery } from '../types/stockops';

const opts = {
  retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
  retryDelay,
  refetchOnWindowFocus: false,
} as const;

export const useStockTransfers = (query: StockTransferQuery = {}) =>
  useQuery({ queryKey: ['stockops', 'transfers', query], queryFn: () => stockopsApi.listTransfers(query), placeholderData: keepPreviousData, ...opts });

export const useStockTransfer = (transferId: number | null) =>
  useQuery({ queryKey: ['stockops', 'transfer', transferId], queryFn: () => stockopsApi.getTransfer(transferId!), enabled: transferId != null, ...opts });

export const useStockCounts = (query: StockCountQuery = {}) =>
  useQuery({ queryKey: ['stockops', 'counts', query], queryFn: () => stockopsApi.listCounts(query), placeholderData: keepPreviousData, ...opts });

export const useStockCount = (countId: number | null) =>
  useQuery({ queryKey: ['stockops', 'count', countId], queryFn: () => stockopsApi.getCount(countId!), enabled: countId != null, ...opts });

/** Chỉ gọi khi đã có số lô hoặc sản phẩm (thiếu cả hai BE trả 400 BR-KO-13). */
export const useStockLotSearch = (query: LotSearchQuery) =>
  useQuery({
    queryKey: ['stockops', 'lots', query],
    queryFn: () => stockopsApi.searchLots(query),
    enabled: Boolean(query.lotNumber?.trim()) || query.productId != null,
    placeholderData: keepPreviousData,
    ...opts,
  });

export const useLotTrace = (lotId: number | null) =>
  useQuery({ queryKey: ['stockops', 'trace', lotId], queryFn: () => stockopsApi.traceLot(lotId!), enabled: lotId != null, ...opts });

export function useStockOpsMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => Promise.all([queryClient.invalidateQueries({ queryKey: ['stockops'] }), queryClient.invalidateQueries({ queryKey: ['inventory'] })]),
  });
}
