// React Query cho trả hàng và hoàn tiền (UC68, UC69, ST21; returns-v1 #1–9): khóa gốc ['returns']. Lệnh ghi (tạo / rút / duyệt / từ chối, xác nhận / thử lại hoàn tiền)
// đi qua `useReturnsMutation(fn)` = `useCommerceMutation` ở useShop.ts: làm mới ['returns'] và các gốc thương mại khác kể cả khi BE từ chối (409 vì người khác vừa duyệt,
// 400 BR-TG-01 vì ca đã đóng…), vì duyệt trả hàng còn đổi tồn kho và xác nhận hoàn tiền đổi cả yêu cầu trả hàng tương ứng.
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { returnsApi } from '../api/returns.api';
import type { RefundQuery, ReturnRequestQuery } from '../types/returns';
import { commerceQueryOptions, useCommerceMutation } from './useShop';

/** `status` vắng = PENDING; cũ nhất trước. */
export const useReturnRequests = (query: ReturnRequestQuery = {}) =>
  useQuery({ queryKey: ['returns', 'requests', query], queryFn: () => returnsApi.list(query), placeholderData: keepPreviousData, ...commerceQueryOptions });

export const useReturnRequest = (requestId: number | null) =>
  useQuery({ queryKey: ['returns', 'request', requestId], queryFn: () => returnsApi.get(requestId!), enabled: requestId != null, ...commerceQueryOptions });

/** `status` vắng = REQUESTED + FAILED; cũ nhất trước. */
export const useRefunds = (query: RefundQuery = {}) =>
  useQuery({ queryKey: ['returns', 'refunds', query], queryFn: () => returnsApi.listRefunds(query), placeholderData: keepPreviousData, ...commerceQueryOptions });

export const useReturnsMutation = useCommerceMutation;
