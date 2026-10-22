// Kho (UC73–76) theo docs/api/inventory-v1.md (mục Schema). Trường `?` có thể vắng hoặc null.
import type { ProductType } from './catalog';

export type ReceiptStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED';
export type AdjustmentReason = 'DAMAGED' | 'EXPIRED' | 'COUNT_MISMATCH' | 'OTHER';

export const RECEIPT_STATUS_LABELS: Record<ReceiptStatus, string> = { DRAFT: 'Nháp', CONFIRMED: 'Đã nhập kho', CANCELLED: 'Đã hủy' };
export const ADJUSTMENT_REASON_LABELS: Record<AdjustmentReason, string> = {
  DAMAGED: 'Hỏng',
  EXPIRED: 'Hết hạn',
  COUNT_MISMATCH: 'Lệch kiểm đếm',
  OTHER: 'Khác',
};
/** [CFG] stock.expiry_warning_days mặc định 30; chỉ ADMIN đọc được tham số nên bộ lọc dùng hằng này (BR-KO-07). */
export const EXPIRY_WARNING_DAYS_DEFAULT = 30;

// ─── Nhà cung cấp (UC73, BR-KO-02) ───

export interface Supplier {
  supplierId: number;
  name: string;
  phone: string;
  address?: string | null;
  /** false = ngừng hợp tác: không chọn được ở phiếu nhập mới. */
  isActive: boolean;
}
export interface SupplierRequest {
  name: string;
  phone: string;
  address?: string;
}
export type UpdateSupplierRequest = Partial<SupplierRequest> & { isActive?: boolean };
export interface SupplierQuery {
  isActive?: boolean;
  q?: string;
}

// ─── Phiếu nhập kho (UC74, BR-KO-03, 04, 03 §9) ───

export interface ReceiptLineRequest {
  productId: number;
  quantity: number;
  /** Giá nhập, VND. */
  unitCost: number;
  /** Bắt buộc khi xác nhận nếu sản phẩm quản lý hạn dùng (BR-SP-05). */
  lotNumber?: string;
  expiryDate?: string;
}
/** Sửa phiếu nháp = gửi lại toàn bộ phiếu kể cả các dòng (A2). */
export interface StockReceiptRequest {
  supplierId: number;
  receiptDate: string;
  note?: string;
  lines: ReceiptLineRequest[];
}
export interface ReceiptLine {
  lineId: number;
  productId: number;
  productName: string;
  quantity: number;
  unitCost: number;
  lotNumber?: string | null;
  expiryDate?: string | null;
  /** Gán khi xác nhận; dùng để hủy phiếu đúng lô (BR-KO-04). */
  stockLotId?: number | null;
}
export interface StockReceipt {
  receiptId: number;
  code: string;
  branchId: number;
  supplierId: number;
  supplierName: string;
  receiptDate: string;
  status: ReceiptStatus;
  note?: string | null;
  lines: ReceiptLine[];
  createdBy: number;
  confirmedBy?: number | null;
  confirmedAt?: string | null;
  cancelledBy?: number | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
}
export interface StockReceiptSummary {
  receiptId: number;
  code: string;
  supplierName: string;
  receiptDate: string;
  status: ReceiptStatus;
  lineCount: number;
  totalCost: number;
}
export interface ReceiptQuery {
  status?: ReceiptStatus;
  from?: string;
  to?: string;
  supplierId?: number;
  page?: number;
  size?: number;
}
export interface CancelReceiptRequest {
  reason?: string;
}

// ─── Tồn kho (UC75, BR-KO-01, 07) ───

export interface InventoryItem {
  productId: number;
  productName: string;
  sku: string;
  unit: string;
  productType: ProductType;
  tracksExpiry: boolean;
  /** Tổng các lô, kể cả lô đã hết hạn. */
  totalQuantity: number;
  /** Tổng lô chưa hết hạn (BR-KO-01) — số dùng cho bán, kê đơn, tiêm. */
  availableQuantity: number;
  minQuantity: number;
  belowMin: boolean;
  nearestExpiryDate?: string | null;
  /** Tồn ở lô đã hết hạn, cần phiếu điều chỉnh lý do hết hạn (BR-KO-07). */
  expiredQuantity: number;
}
export interface InventoryQuery {
  q?: string;
  productType?: ProductType;
  belowMin?: boolean;
  expiringWithinDays?: number;
  page?: number;
  size?: number;
}
export interface StockLot {
  stockLotId: number;
  lotNumber?: string | null;
  expiryDate?: string | null;
  quantity: number;
  /** Lô duy nhất của sản phẩm không quản lý hạn dùng. */
  isDefault: boolean;
  expired: boolean;
}

// ─── Điều chỉnh tồn (UC76, BR-KO-06) ───

export interface AdjustmentLineRequest {
  stockLotId: number;
  /** ≠ 0; âm = giảm, dương = tăng. */
  quantityDelta: number;
}
export interface StockAdjustmentRequest {
  reason: AdjustmentReason;
  /** Bắt buộc khi OTHER. */
  note?: string;
  lines: AdjustmentLineRequest[];
}
export interface AdjustmentLine {
  stockLotId: number;
  productId: number;
  productName: string;
  lotNumber?: string | null;
  quantityDelta: number;
  balanceAfter: number;
}
export interface StockAdjustment {
  adjustmentId: number;
  code: string;
  branchId: number;
  reason: AdjustmentReason;
  note?: string | null;
  lines: AdjustmentLine[];
  createdBy: number;
  createdAt: string;
}
export interface AdjustmentQuery {
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}
