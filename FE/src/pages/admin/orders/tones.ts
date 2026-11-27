import type { OnlineOrderStatus } from '@/shared/types/shop';

/** Màu nhãn trạng thái (luôn kèm chữ, không dùng màu đơn lẻ) của đơn online. */
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
