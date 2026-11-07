// Thanh toán online (tầng 3, UC63, ST11, ST14) theo docs/api/payment-v1.md + openapi/payment-v1.yaml (mục Schema).

/** 03 #15. */
export type PaymentTransactionStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'CANCELLED';

export const PAYMENT_STATUS_LABELS: Record<PaymentTransactionStatus, string> = {
  PENDING: 'Đang chờ thanh toán',
  SUCCEEDED: 'Thanh toán thành công',
  FAILED: 'Thanh toán thất bại',
  EXPIRED: 'Giao dịch hết hạn',
  CANCELLED: 'Đã hủy',
};

export interface PaymentSession {
  transactionId: number;
  /** Mã tham chiếu gửi cổng. */
  code: string;
  /** Tiền hàng + phí vận chuyển (BR-TG-06). */
  amount: number;
  /** Địa chỉ chuyển khách sang cổng. */
  redirectUrl: string;
  /** Bây giờ + 15 phút [CFG] (BR-TG-09). */
  expiresAt: string;
}

export interface PaymentTransaction {
  transactionId: number;
  onlineOrderId: number;
  status: PaymentTransactionStatus;
  amount: number;
  expiresAt: string;
  paidAt?: string | null;
  failureReason?: string | null;
}

/** Dạng chung của callback mọi cổng (payment-v1 A1). */
export interface GatewayCallbackRequest {
  transactionCode: string;
  gatewayTxnId?: string | null;
  /** Mã trạng thái nguyên văn của cổng. */
  status: string;
  amount: number;
  signature: string;
  payload?: Record<string, unknown> | null;
}

export interface CallbackAck {
  received: boolean;
}

export type ReconciliationStatus = 'RUNNING' | 'MATCHED' | 'MISMATCHED' | 'FAILED';
export const RECONCILIATION_STATUSES: readonly ReconciliationStatus[] = ['RUNNING', 'MATCHED', 'MISMATCHED', 'FAILED'];

export interface ReconciliationSummary {
  reconciliationId: number;
  reconDate: string;
  status: ReconciliationStatus;
  txnCount: number;
  mismatchCount: number;
  finishedAt?: string | null;
}

export type ReconciliationItemKind = 'MISSING_LOCAL' | 'MISSING_GATEWAY' | 'AMOUNT_DIFF' | 'STATUS_DIFF';

export const RECONCILIATION_KIND_LABELS: Record<ReconciliationItemKind, string> = {
  MISSING_LOCAL: 'Cổng có, hệ thống không có',
  MISSING_GATEWAY: 'Hệ thống có, cổng không có',
  AMOUNT_DIFF: 'Lệch số tiền',
  STATUS_DIFF: 'Lệch trạng thái',
};

export interface ReconciliationItem {
  itemId: number;
  kind: ReconciliationItemKind;
  transactionId?: number | null;
  gatewayRef?: string | null;
  ourAmount?: number | null;
  gatewayAmount?: number | null;
  resolved: boolean;
  resolvedNote?: string | null;
  resolvedAt?: string | null;
}

export interface ResolveItemRequest {
  note: string;
}

export interface ReconciliationQuery {
  status?: ReconciliationStatus;
  page?: number;
  size?: number;
}

export interface ReconciliationItemQuery {
  resolved?: boolean;
  page?: number;
  size?: number;
}

export const PAYMENT_LIMITS = { note: 300 } as const;
