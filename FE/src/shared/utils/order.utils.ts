import type { OrderStatus, PaymentMethod, PaymentStatus, OrderLine } from '../stores/order.store';
import type { BadgeVariant } from '../components/ui/Badge';

// Order status badge
export const orderStatusBadge = (
  status: OrderStatus
): { label: string; color: string; bg: string; variant: BadgeVariant } => {
  const badges: Record<OrderStatus, { label: string; color: string; bg: string; variant: BadgeVariant }> = {
    PENDING: { label: 'Chờ xác nhận', color: '#c2410c', bg: '#fff3e0', variant: 'brand' },
    CONFIRMED: { label: 'Đã xác nhận', color: '#0369a1', bg: '#e0f2fe', variant: 'info' },
    PROCESSING: { label: 'Đang xử lý', color: '#0369a1', bg: '#e0f2fe', variant: 'info' },
    SHIPPED: { label: 'Đang giao', color: '#7c3aed', bg: '#ede9fe', variant: 'info' },
    DELIVERED: { label: 'Đã giao', color: '#047857', bg: '#ecfdf5', variant: 'success' },
    CANCELLED: { label: 'Đã hủy', color: '#be123c', bg: '#ffe4e6', variant: 'error' },
  };
  return badges[status] || badges.PENDING;
};

// Payment method label
export const paymentMethodLabel = (method: PaymentMethod): string => {
  const labels: Record<PaymentMethod, string> = {
    VNPAY: 'VNPAY',
    MOMO: 'MoMo',
    COD: 'Thanh toán khi nhận hàng',
  };
  return labels[method] || method;
};

// Payment status badge
export const paymentStatusBadge = (
  status: PaymentStatus
): { label: string; color: string; bg: string } => {
  const badges: Record<PaymentStatus, { label: string; color: string; bg: string }> = {
    PENDING: { label: 'Chờ thanh toán', color: '#c2410c', bg: '#fff3e0' },
    SUCCESS: { label: 'Đã thanh toán', color: '#047857', bg: '#ecfdf5' },
    FAILED: { label: 'Thanh toán thất bại', color: '#be123c', bg: '#ffe4e6' },
  };
  return badges[status] || badges.PENDING;
};

// Count items in order
export const orderItemCount = (order: { lines: OrderLine[] }): number =>
  order.lines.reduce((sum, l) => sum + l.quantity, 0);

// Build order lines (add product info)
export interface OrderLineView {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  image?: string;
}

export const buildOrderLines = (
  lines: OrderLine[],
  _products: unknown[] = []
): OrderLineView[] =>
  lines.map((l) => ({
    id: l.id,
    productId: l.productId,
    productName: l.productName,
    quantity: l.quantity,
    unitPrice: l.unitPrice,
    lineTotal: l.subtotal,
    image: l.image,
  }));
