// React Query cho báo cáo (T49: UC89). Gốc khóa ['reports', tên, query]. Chỉ đọc, số liệu tính tại thời điểm xem nên không
// tự hỏi lại khi focus; trang đổi query (kỳ, chi nhánh) thì khóa đổi và tải lại. `enabled=false` khi kỳ chưa hợp lệ ở FE.
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { reportApi } from '../api/report.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { ReportPeriodQuery, StockAlertQuery } from '../types/report';

/** Lỗi 4xx (400 kỳ, 403 phạm vi, 404 chưa có endpoint) không đổi khi thử lại. */
const opts = {
  retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
  retryDelay,
  refetchOnWindowFocus: false,
  placeholderData: keepPreviousData,
} as const;

function period<T>(name: string, fn: (q: ReportPeriodQuery) => Promise<T>) {
  return (query: ReportPeriodQuery, enabled = true) =>
    useQuery({ queryKey: ['reports', name, query], queryFn: () => fn(query), enabled, ...opts });
}

export const useRevenueReport = period('revenue', reportApi.revenue);
export const useLostRevenueReport = period('lost-revenue', reportApi.lostRevenue);
export const useCompletedServicesReport = period('completed-services', reportApi.completedServices);
export const useNoShowReport = period('no-show', reportApi.noShow);
export const useRevaccinationReport = period('revaccination-return', reportApi.revaccinationReturn);
export const useFeedbackReport = period('feedback', reportApi.feedback);

export const useStockAlertReport = (query: StockAlertQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['reports', 'stock-alerts', query], queryFn: () => reportApi.stockAlerts(query), enabled, ...opts });
