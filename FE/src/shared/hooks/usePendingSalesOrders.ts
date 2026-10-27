// "Chờ thu" của quầy: Order PENDING của chi nhánh (sales-v1 #2) thay cho danh sách chờ thu của clinic-db (T35).
// Không còn kênh storage của clinic-db báo Order mới chốt, nên hỏi lại định kỳ để lễ tân thấy Order mới không cần tải lại trang.
import { useQuery } from '@tanstack/react-query';
import { salesApi } from '../api/sales.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import { useAccount } from '../stores/session.store';
import type { Role } from '../types/auth';
import type { OrderQuery, OrderSummary } from '../types/sales';
import type { PageResponse } from '../types';

export const PENDING_REFRESH_MS = 15_000;

const PENDING_QUERY: OrderQuery = { status: 'PENDING', size: 100 };
/** sales-v1 #2: A05–A08 của chi nhánh; ADMIN, SUPER_MANAGER, CUSTOMER bị 403 nên không gọi. */
export const ORDER_READERS: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];

const contentOf = (page: PageResponse<OrderSummary>) => page.content;

export const usePendingSalesOrders = (enabled = true) => {
  const role = useAccount()?.role;
  return useQuery({
    queryKey: ['sales', 'orders', PENDING_QUERY],
    queryFn: () => salesApi.listOrders(PENDING_QUERY),
    select: contentOf,
    enabled: enabled && role != null && ORDER_READERS.includes(role),
    refetchInterval: PENDING_REFRESH_MS,
    refetchIntervalInBackground: false,
    retry: shouldRetry,
    retryDelay,
  });
};
