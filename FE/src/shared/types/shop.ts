// Cửa hàng online (tầng 3, UC61, 62, 64, 65) theo docs/api/shop-v1.md + openapi/shop-v1.yaml (mục Schema). Trường `?` có thể vắng hoặc null.
// Thanh toán ở payment.ts, trả hàng và hoàn tiền ở returns.ts. Tiền: số nguyên đồng; thời điểm: ISO 8601 có offset.

/** 03 #14 (08 §B). */
export type OnlineOrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'DELIVERY_FAILED'
  | 'DELIVERED'
  | 'COMPLETED'
  | 'CANCELLED';

export const ONLINE_ORDER_STATUSES: readonly OnlineOrderStatus[] = [
  'PENDING_PAYMENT',
  'PAID',
  'PROCESSING',
  'SHIPPED',
  'DELIVERY_FAILED',
  'DELIVERED',
  'COMPLETED',
  'CANCELLED',
];

export const ONLINE_ORDER_STATUS_LABELS: Record<OnlineOrderStatus, string> = {
  PENDING_PAYMENT: 'Chờ thanh toán',
  PAID: 'Đã thanh toán',
  PROCESSING: 'Đang chuẩn bị hàng',
  SHIPPED: 'Đã bàn giao vận chuyển',
  DELIVERY_FAILED: 'Giao thất bại',
  DELIVERED: 'Đã giao',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy',
};

/** Việc cần làm của lễ tân khi `status` vắng (shop-v1 #12). */
export const FULFILLMENT_DEFAULT_STATUSES: readonly OnlineOrderStatus[] = ['PAID', 'PROCESSING', 'DELIVERY_FAILED'];

/** Giới hạn của hợp đồng (BR-BH-07, yaml `maxLength`). */
export const SHOP_LIMITS = { cartLines: 30, minQuantity: 1, maxQuantity: 99, reason: 300, carrier: 100, trackingCode: 100 } as const;

export interface CartItem {
  productId: number;
  name: string;
  unit: string;
  /** Giá hiện tại, chỉ để xem; giá chốt lúc đặt (BR-BH-07, BR-BH-03). */
  unitPrice: number;
  quantity: number;
  imageUrl?: string | null;
  /** false nếu hết hàng toàn chuỗi. */
  available: boolean;
}

export interface Cart {
  items: CartItem[];
  subtotal: number;
}

export interface SetCartItemRequest {
  /** 1–99 (BR-BH-07). */
  quantity: number;
}

export interface FulfillmentBranch {
  branchId: number;
  name: string;
  address: string;
}

export interface QuoteQuery {
  branchId: number;
  addressId: number;
}

export interface OnlineOrderQuote {
  branchId: number;
  addressId: number;
  subtotal: number;
  shippingFee: number;
  freeShippingApplied: boolean;
  total: number;
}

export interface PlaceOnlineOrderRequest {
  branchId: number;
  addressId: number;
}

export interface OnlineOrderLine {
  /**
   * KHÔNG có trong yaml (đề nghị thêm vào OnlineOrderLine): returns-v1 `ReturnLineInput.orderLineId` là `order_lines.id` của Order bán hàng,
   * nhưng shop-v1 không trả mã dòng nên FE không có cách biết. Mock trả để màn tạo yêu cầu trả hàng dùng được.
   */
  orderLineId?: number;
  productId: number;
  name: string;
  unit: string;
  /** Snapshot (BR-BH-03). */
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface OnlineOrderSummary {
  onlineOrderId: number;
  /** Order bán hàng tương ứng (`orders.id`), dùng khi tạo yêu cầu trả hàng. */
  salesOrderId: number;
  code: string;
  status: OnlineOrderStatus;
  branchName: string;
  itemCount: number;
  total: number;
  createdAt: string;
}

export interface OnlineOrder {
  onlineOrderId: number;
  salesOrderId: number;
  code: string;
  status: OnlineOrderStatus;
  branchId: number;
  branchName: string;
  receiverName: string;
  receiverPhone: string;
  shipAddress: string;
  lines: OnlineOrderLine[];
  subtotal: number;
  shippingFee: number;
  total: number;
  carrier?: string | null;
  trackingCode?: string | null;
  redeliveryCount: number;
  paymentDueAt: string;
  paidAt?: string | null;
  shippedAt?: string | null;
  deliveredAt?: string | null;
  completedAt?: string | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
  createdAt: string;
}

export interface ReasonRequest {
  reason: string;
}

export interface ShipRequest {
  carrier: string;
  trackingCode: string;
}

/** Danh sách đơn (#8, #12): `page` từ 0, `size` mặc định 20, tối đa 100. */
export interface OnlineOrderQuery {
  status?: OnlineOrderStatus;
  page?: number;
  size?: number;
}
