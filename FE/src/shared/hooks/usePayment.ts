// React Query cho thanh toán online và đối soát (UC63, ST14; payment-v1 #1–6): khóa gốc ['payment']. Lệnh ghi (pay, callback của cổng giả lập, ghi chú đối soát)
// đi qua `usePaymentMutation(fn)` = `useCommerceMutation` ở useShop.ts: làm mới ['payment'] và các gốc thương mại khác kể cả khi BE từ chối, vì callback đổi cả đơn
// (PENDING_PAYMENT → PAID) và tồn kho.
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { paymentApi } from '../api/payment.api';
import type { ReconciliationItemQuery, ReconciliationQuery } from '../types/payment';
import { commerceQueryOptions, useCommerceMutation } from './useShop';

/** Trang kết quả sau khi quay lại từ cổng hỏi hook này, không tin tham số trên URL quay về (payment-v1 A3). */
export const useMyPaymentTransaction = (transactionId: number | null) =>
  useQuery({ queryKey: ['payment', 'transaction', transactionId], queryFn: () => paymentApi.getMyTransaction(transactionId!), enabled: transactionId != null, ...commerceQueryOptions });

/** Chỉ SUPER_MANAGER. */
export const useReconciliations = (query: ReconciliationQuery = {}) =>
  useQuery({ queryKey: ['payment', 'reconciliations', query], queryFn: () => paymentApi.listReconciliations(query), placeholderData: keepPreviousData, ...commerceQueryOptions });

export const useReconciliationItems = (reconciliationId: number | null, query: ReconciliationItemQuery = {}) =>
  useQuery({
    queryKey: ['payment', 'reconciliation-items', reconciliationId, query],
    queryFn: () => paymentApi.listReconciliationItems(reconciliationId!, query),
    enabled: reconciliationId != null,
    placeholderData: keepPreviousData,
    ...commerceQueryOptions,
  });

export const usePaymentMutation = useCommerceMutation;
