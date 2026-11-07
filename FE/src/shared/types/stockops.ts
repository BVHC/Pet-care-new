// Chuyển kho, kiểm kê, truy vết lô (UC77–79) theo docs/api/stockops-v1.md #1–13 (mục Schema). Trường `?` có thể vắng hoặc null.

// ─── Chuyển kho (UC77, BR-KO-08…11; 03 #18) ───

export type StockTransferStatus = 'IN_TRANSIT' | 'RECEIVED' | 'CANCELLED';
export const STOCK_TRANSFER_STATUSES: readonly StockTransferStatus[] = ['IN_TRANSIT', 'RECEIVED', 'CANCELLED'];
export const STOCK_TRANSFER_STATUS_LABELS: Record<StockTransferStatus, string> = {
  IN_TRANSIT: 'Đang chuyển',
  RECEIVED: 'Đã nhận',
  CANCELLED: 'Đã hủy',
};

export type TransferDirection = 'OUTGOING' | 'INCOMING';
export const TRANSFER_DIRECTIONS: readonly TransferDirection[] = ['OUTGOING', 'INCOMING'];

export interface StockTransferLineInput {
  productId: number;
  /** ≥ 1 và ≤ tồn khả dụng của nguồn (BR-KO-08). */
  quantity: number;
}
export interface CreateStockTransferRequest {
  destBranchId: number;
  /** ≥ 1 dòng, mỗi sản phẩm một dòng. */
  lines: StockTransferLineInput[];
}
/** Một dòng của phiếu ứng với MỘT lô nguồn: FEFO trừ qua nhiều lô thì phiếu có nhiều dòng cùng sản phẩm. */
export interface StockTransferLine {
  productId: number;
  productName: string;
  quantity: number;
  lotNumber?: string | null;
  expiryDate?: string | null;
}
export interface StockTransferSummary {
  transferId: number;
  /** `CK-yyMMdd-xxxx`. */
  code: string;
  sourceBranchId: number;
  destBranchId: number;
  status: StockTransferStatus;
  lineCount: number;
  createdAt: string;
}
export interface StockTransfer {
  transferId: number;
  code: string;
  sourceBranchId: number;
  sourceBranchName: string;
  destBranchId: number;
  destBranchName: string;
  status: StockTransferStatus;
  lines: StockTransferLine[];
  createdAt: string;
  receivedAt?: string | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
}
/** `direction` vắng = cả hai chiều. */
export interface StockTransferQuery {
  direction?: TransferDirection;
  status?: StockTransferStatus;
  page?: number;
  size?: number;
}
/** Lý do hủy phiếu chuyển kho (BR-KO-10) hoặc phiên kiểm kê (BR-KO-12). */
export interface ReasonRequest {
  /** Bắt buộc, ≤ 300 ký tự. */
  reason: string;
}

// ─── Kiểm kê (UC78, BR-KO-12; 03 #19) ───

export type StockCountStatus = 'OPEN' | 'CONFIRMED' | 'CANCELLED';
export const STOCK_COUNT_STATUSES: readonly StockCountStatus[] = ['OPEN', 'CONFIRMED', 'CANCELLED'];
export const STOCK_COUNT_STATUS_LABELS: Record<StockCountStatus, string> = { OPEN: 'Đang đếm', CONFIRMED: 'Đã xác nhận', CANCELLED: 'Đã hủy' };

export type StockCountScope = 'ALL' | 'CATEGORY';
export const STOCK_COUNT_SCOPES: readonly StockCountScope[] = ['ALL', 'CATEGORY'];

export interface OpenStockCountRequest {
  scope: StockCountScope;
  /** Bắt buộc khi `scope = CATEGORY`. */
  categoryId?: number | null;
}
export interface StockCountLine {
  stockLotId: number;
  productId: number;
  productName: string;
  lotNumber?: string | null;
  expiryDate?: string | null;
  /** null = chưa đếm. */
  countedQty?: number | null;
  /** Tồn hệ thống hiện tại; phiên đã xác nhận thì là tồn lúc xác nhận. */
  systemQty: number;
}
export interface StockCountSummary {
  countId: number;
  /** `KK-yyMMdd-xxxx`. */
  code: string;
  status: StockCountStatus;
  scope: StockCountScope;
  createdAt: string;
  confirmedAt?: string | null;
}
export interface StockCount {
  countId: number;
  code: string;
  branchId: number;
  status: StockCountStatus;
  scope: StockCountScope;
  categoryId?: number | null;
  lines: StockCountLine[];
  createdAt: string;
  confirmedAt?: string | null;
  cancelReason?: string | null;
}
export interface StockCountQuery {
  status?: StockCountStatus;
  page?: number;
  size?: number;
}
export interface CountedQtyInput {
  stockLotId: number;
  /** ≥ 0. */
  countedQty: number;
}
/** Ghi đè số đếm của các lô gửi lên; lô không gửi giữ nguyên. */
export interface SaveStockCountLinesRequest {
  lines: CountedQtyInput[];
}

// ─── Truy vết lô (UC79, BR-KO-13) ───

/** Phải có `lotNumber` (khớp một phần, không phân biệt hoa thường) hoặc `productId`. */
export interface LotSearchQuery {
  lotNumber?: string;
  productId?: number;
  page?: number;
  size?: number;
}
export interface LotSummary {
  lotId: number;
  productId: number;
  productName: string;
  lotNumber?: string | null;
  expiryDate?: string | null;
  branchId: number;
  quantity: number;
}
export type LotTraceEventKind = 'RECEIPT' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'TRANSFER_CANCEL' | 'ORDER_ISSUE' | 'VACCINATION' | 'ADJUSTMENT';
export const LOT_TRACE_EVENT_LABELS: Record<LotTraceEventKind, string> = {
  RECEIPT: 'Nhập kho',
  TRANSFER_IN: 'Nhận chuyển kho',
  TRANSFER_OUT: 'Xuất chuyển kho',
  TRANSFER_CANCEL: 'Hoàn chuyển kho',
  ORDER_ISSUE: 'Xuất bán',
  VACCINATION: 'Tiêm phòng',
  ADJUSTMENT: 'Điều chỉnh tồn',
};
export interface LotTraceEvent {
  occurredAt: string;
  kind: LotTraceEventKind;
  /** Dương = nhập, âm = xuất. */
  quantity: number;
  documentCode?: string | null;
  branchId: number;
  /** Chỉ với lô thuốc, vaccine (BR-KO-13). */
  petName?: string | null;
  customerName?: string | null;
}
export interface LotTrace {
  lot: LotSummary;
  /** Cũ nhất trước. */
  events: LotTraceEvent[];
  remaining: number;
}

/** `reason` tối đa 300 ký tự (yaml `ReasonRequest`). */
export const STOCK_OPS_LIMITS = { reason: 300 } as const;
