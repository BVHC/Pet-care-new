import type { RefundStatus, ReturnRequestStatus } from '@/shared/types/returns';
import type { OnlineOrderStatus } from '@/shared/types/shop';

/** Màu nhãn trạng thái (luôn kèm chữ, không dùng màu đơn lẻ) của đơn online, yêu cầu trả hàng và hoàn tiền. */
export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

export const ORDER_TONE: Record<OnlineOrderStatus, Tone> = {
  PENDING_PAYMENT: 'neutral',
  PAID: 'info',
  PROCESSING: 'info',
  SHIPPED: 'info',
  DELIVERY_FAILED: 'danger',
  DELIVERED: 'success',
  COMPLETED: 'success',
  CANCELLED: 'neutral',
};

export const RETURN_TONE: Record<ReturnRequestStatus, Tone> = {
  PENDING: 'warning',
  APPROVED: 'info',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
  REFUNDED: 'success',
};

export const REFUND_TONE: Record<RefundStatus, Tone> = { REQUESTED: 'warning', SUCCEEDED: 'success', FAILED: 'danger' };
