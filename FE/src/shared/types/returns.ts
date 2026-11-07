// Trả hàng và hoàn tiền (tầng 3, UC68, UC69, ST21) theo docs/api/returns-v1.md + openapi/returns-v1.yaml (mục Schema).

export type ReturnReason = 'DEFECTIVE' | 'WRONG_ITEM' | 'CHANGED_MIND' | 'OTHER';
export const RETURN_REASONS: readonly ReturnReason[] = ['DEFECTIVE', 'WRONG_ITEM', 'CHANGED_MIND', 'OTHER'];
export const RETURN_REASON_LABELS: Record<ReturnReason, string> = {
  DEFECTIVE: 'Hàng lỗi',
  WRONG_ITEM: 'Giao sai hàng',
  CHANGED_MIND: 'Khách đổi ý',
  OTHER: 'Lý do khác',
};

/** 03 #16. */
export type ReturnRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'REFUNDED';
export const RETURN_REQUEST_STATUSES: readonly ReturnRequestStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'REFUNDED'];
export const RETURN_STATUS_LABELS: Record<ReturnRequestStatus, string> = {
  PENDING: 'Chờ duyệt',
  APPROVED: 'Đã duyệt, đang hoàn tiền',
  REJECTED: 'Bị từ chối',
  CANCELLED: 'Đã rút',
  REFUNDED: 'Đã hoàn tiền',
};

export interface ReturnLineInput {
  /** `order_lines.id` của Order bán hàng (đơn online: xem `OnlineOrderLine.orderLineId`). */
  orderLineId: number;
  /** ≤ đã mua trừ phần đã / đang trả (BR-BH-13). */
  quantity: number;
}

export interface CreateReturnRequestRequest {
  /** `orders.id`. Với đơn online dùng `salesOrderId` của `OnlineOrder`, KHÔNG dùng `onlineOrderId`. */
  orderId: number;
  lines: ReturnLineInput[];
  reasonCode: ReturnReason;
  reasonNote?: string | null;
}

export interface ReturnLine {
  orderLineId: number;
  productName: string;
  /** Snapshot (BR-BH-03). */
  unitPrice: number;
  quantity: number;
}

export interface ReturnRequestSummary {
  requestId: number;
  code: string;
  orderId: number;
  status: ReturnRequestStatus;
  reasonCode: ReturnReason;
  refundAmount?: number | null;
  createdAt: string;
}

export interface ReturnRequest {
  requestId: number;
  code: string;
  orderId: number;
  branchId: number;
  status: ReturnRequestStatus;
  reasonCode: ReturnReason;
  reasonNote?: string | null;
  lines: ReturnLine[];
  /** Có sau khi duyệt. */
  restock?: boolean | null;
  refundAmount?: number | null;
  decisionNote?: string | null;
  createdAt: string;
  decidedAt?: string | null;
}

export interface ApproveReturnRequestRequest {
  /** true = nhập lại lô gốc; false = loại bỏ (`RETURN_DISCARD`, BR-BH-14). */
  restock: boolean;
}

export interface RejectReturnRequestRequest {
  reason: string;
}

export type RefundMethod = 'CASH' | 'TRANSFER' | 'GATEWAY';
export const REFUND_METHODS: readonly RefundMethod[] = ['CASH', 'TRANSFER', 'GATEWAY'];
export const REFUND_METHOD_LABELS: Record<RefundMethod, string> = { CASH: 'Tiền mặt', TRANSFER: 'Chuyển khoản', GATEWAY: 'Qua cổng thanh toán' };

/** 03 #17. */
export type RefundStatus = 'REQUESTED' | 'SUCCEEDED' | 'FAILED';
export const REFUND_STATUSES: readonly RefundStatus[] = ['REQUESTED', 'SUCCEEDED', 'FAILED'];
export const REFUND_STATUS_LABELS: Record<RefundStatus, string> = { REQUESTED: 'Chờ hoàn', SUCCEEDED: 'Đã hoàn', FAILED: 'Hoàn thất bại' };

export interface Refund {
  refundId: number;
  code: string;
  /** Hoàn tiền sinh từ trả hàng; hoặc `onlineOrderId` khi sinh từ hủy đơn online (đúng một trong hai). */
  returnRequestId?: number | null;
  onlineOrderId?: number | null;
  method: RefundMethod;
  amount: number;
  status: RefundStatus;
  attempts: number;
  failureReason?: string | null;
  completedAt?: string | null;
  createdAt: string;
}

export interface ConfirmRefundRequest {
  note?: string | null;
}

/** `status` vắng = PENDING. */
export interface ReturnRequestQuery {
  status?: ReturnRequestStatus;
  page?: number;
  size?: number;
}

/** `status` vắng = REQUESTED + FAILED. */
export interface RefundQuery {
  status?: RefundStatus;
  method?: RefundMethod;
  page?: number;
  size?: number;
}

export const RETURNS_LIMITS = { reasonNote: 300, reason: 300, note: 300 } as const;
