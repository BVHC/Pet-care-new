// Báo cáo UC89 — docs/api/report-v1.md (7 endpoint, chỉ đọc). Ngày dạng 'YYYY-MM-DD' (giờ Việt Nam), thời điểm dạng ISO.
// Tỷ lệ (`noShowRate`, `lateCancelRate`, `rate`) là số thập phân 0–1 kèm tử số và mẫu số (A3): FE tự định dạng phần trăm.
export type ReportSource = 'VISIT' | 'RETAIL' | 'BOARDING';
export type ReportServiceGroup = 'MEDICAL' | 'GROOMING';

/** Báo cáo theo kỳ (#1–5, #7). `branchId` bỏ trống = toàn chuỗi (SUPER_MANAGER) hoặc chi nhánh mình (BRANCH_MANAGER). */
export interface ReportPeriodQuery {
  branchId?: number;
  from: string;
  to: string;
}

/** Tồn kho (#6) không theo kỳ; `withinDays` mặc định 30 [CFG] (A2). */
export interface StockAlertQuery {
  branchId?: number;
  withinDays?: number;
}

export interface RevenueRow {
  branchId: number;
  branchName: string;
  source: ReportSource;
  orderCount: number;
  amount: number;
}
/** BR-BC-02: Order PAID có thời điểm thanh toán trong kỳ, theo đơn giá snapshot. */
export interface RevenueReport {
  from: string;
  to: string;
  rows: RevenueRow[];
  totalAmount: number;
}

export interface LostRevenueRow {
  orderId: number;
  orderCode: string;
  branchId: number;
  source: ReportSource;
  amount: number;
  reason: string;
  cancelledAt: string;
}
/** BR-BC-02: chỉ Order hủy theo BR-BH-05 (UNPAID); hủy phiên trả thú không tính. */
export interface LostRevenueReport {
  from: string;
  to: string;
  rows: LostRevenueRow[];
  count: number;
  totalAmount: number;
}

export interface ServiceCountRow {
  branchId: number;
  serviceGroup: ReportServiceGroup;
  serviceId: number;
  serviceName: string;
  count: number;
}
export interface CompletedServicesReport {
  from: string;
  to: string;
  rows: ServiceCountRow[];
}

export interface NoShowRow {
  branchId: number;
  dueCount: number;
  noShowCount: number;
  lateCancelCount: number;
  noShowRate: number;
  lateCancelRate: number;
}
export interface NoShowReport {
  from: string;
  to: string;
  rows: NoShowRow[];
}

export interface ReturnRateRow {
  branchId: number;
  vaccineTypeId: number;
  vaccineTypeName: string;
  dueCount: number;
  returnedCount: number;
  rate: number;
}
/** BR-BC-04: đo khách quay lại tiêm đúng hạn tại hệ thống. */
export interface RevaccinationReport {
  from: string;
  to: string;
  rows: ReturnRateRow[];
}

export interface LowStockRow {
  branchId: number;
  productId: number;
  productName: string;
  availableQuantity: number;
  minQuantity: number;
}
export interface ExpiringLotRow {
  branchId: number;
  productId: number;
  productName: string;
  stockLotId: number;
  lotNumber?: string | null;
  expiryDate: string;
  quantity: number;
  expired: boolean;
}
export interface StockAlertReport {
  lowStock: LowStockRow[];
  expiringLots: ExpiringLotRow[];
}

export interface FeedbackStatRow {
  branchId?: number | null;
  branchName?: string | null;
  count: number;
  averageRating?: number | null;
}
export interface FeedbackReport {
  from: string;
  to: string;
  rows: FeedbackStatRow[];
}
