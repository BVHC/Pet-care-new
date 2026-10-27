// Report API — docs/api/report-v1.md #1–7 (UC89). Chỉ đọc. SUPER_MANAGER toàn chuỗi; BRANCH_MANAGER chỉ chi nhánh mình
// (truyền chi nhánh khác = 403 BR-BC-01). Kỳ ≤ 12 tháng và from ≤ to, sai = 400 BR-BC-01 (#6 không theo kỳ).
import { apiClient } from './axios';
import type {
  CompletedServicesReport, FeedbackReport, LostRevenueReport, NoShowReport, ReportPeriodQuery, RevaccinationReport,
  RevenueReport, StockAlertQuery, StockAlertReport,
} from '../types/report';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;

export const reportApi = {
  /** #1 */
  revenue: (query: ReportPeriodQuery) => get<RevenueReport>('/api/reports/revenue', query),
  /** #2 */
  lostRevenue: (query: ReportPeriodQuery) => get<LostRevenueReport>('/api/reports/lost-revenue', query),
  /** #3 */
  completedServices: (query: ReportPeriodQuery) => get<CompletedServicesReport>('/api/reports/completed-services', query),
  /** #4 */
  noShow: (query: ReportPeriodQuery) => get<NoShowReport>('/api/reports/no-show', query),
  /** #5 */
  revaccinationReturn: (query: ReportPeriodQuery) => get<RevaccinationReport>('/api/reports/revaccination-return', query),
  /** #6: không theo kỳ, tính tại thời điểm xem. */
  stockAlerts: (query: StockAlertQuery = {}) => get<StockAlertReport>('/api/reports/stock-alerts', query),
  /** #7 */
  feedback: (query: ReportPeriodQuery) => get<FeedbackReport>('/api/reports/feedback', query),
};
