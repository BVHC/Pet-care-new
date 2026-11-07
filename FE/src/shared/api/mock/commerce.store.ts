// State và nghiệp vụ DÙNG CHUNG của ba mock thương mại tầng 3: shop.mock.ts (shop-v1), payment.mock.ts (payment-v1), returns.mock.ts (returns-v1).
// Ba module chia một state (`petcare-mock-commerce`) vì BE làm các chuyển trạng thái chéo module trong MỘT transaction: callback hợp lệ →
// Giao dịch#2 → Đơn hàng online#2 → xuất kho FEFO từ phần giữ; hủy đơn đã thanh toán → hoàn hàng về lô gốc + sinh hoàn tiền; duyệt trả hàng → hoàn tiền.
// Mỗi request gọi `loadFresh()` đúng một lần rồi `save()` đúng một lần (các hàm ở đây nhận `s`), nên không ai ghi đè bản state cũ của người khác.
//
// Hệ thống (không có endpoint, 08 §B–§D) chạy LƯỜI mỗi lần nạp state — `sweep()`:
//  ST10  đơn PENDING_PAYMENT quá `paymentDueAt` + 15 phút (chu kỳ job) bị hủy, nhả giữ hàng; trong 15 phút đó đơn quá hạn mà còn PENDING_PAYMENT nên
//        `pay` trả 400 BR-TG-06 (đúng khe hở của BE).
//  ST11  giao dịch PENDING quá `expiresAt` → EXPIRED (cổng giả lập không bao giờ báo thành công sau hạn).
//  ST12  đơn DELIVERED quá 7 ngày và không có yêu cầu trả hàng đang mở (PENDING / APPROVED) → COMPLETED.
//  ST21  hoàn tiền GATEWAY ở REQUESTED tới `nextAttemptAt` → cổng giả lập hoàn thành công (SUCCEEDED, yêu cầu trả hàng → REFUNDED). Không mô phỏng
//        lỗi tạm thời / thử lại; hoàn tiền FAILED chỉ có ở dữ liệu mẫu để thử `retry`.
import type { Product } from '../../types/catalog';
import type { PaymentTransactionStatus, ReconciliationItemKind, ReconciliationStatus } from '../../types/payment';
import type { RefundMethod, RefundStatus, ReturnReason, ReturnRequestStatus } from '../../types/returns';
import type { OnlineOrderLine, OnlineOrderStatus } from '../../types/shop';
import { vnDate } from '../../utils/vn-time';
import { activeBranchSummaries, branchBooking } from './branch.mock';
import { catalogProducts } from './catalog.mock';
import { availableQuantity, issueFefo, returnToLot, stockSnapshot } from './inventory.mock';
import { ordersByIds } from './sales.mock';

// ─── Cấu hình [CFG] (mock không đọc cấu hình hệ thống nên là hằng số; mặc định của 08 §G) ───

export const CFG = {
  /** shop.payment_window_hours */
  paymentWindowHours: 24,
  /** shop.auto_confirm_days = returns.window_days (BR-BH-12, BR-BH-13) */
  autoConfirmDays: 7,
  /** shop.max_redelivery */
  maxRedelivery: 2,
  /** payment.txn_ttl_minutes */
  txnTtlMinutes: 15,
  /** returns.window_days */
  returnWindowDays: 7,
  /** Chu kỳ job ST10: đơn có thể quá hạn tối đa chừng này trước khi bị hủy. */
  cancelJobMinutes: 15,
  /** Hoàn tiền GATEWAY mới tạo được ST21 xử lý sau chừng này. */
  refundJobMinutes: 1,
} as const;

/** `order_lines.id` của Order ONLINE = `salesOrderId` × 100 + số thứ tự dòng (mock tự đặt; yaml không có). Dải id Order online tách khỏi Order quầy. */
export const ONLINE_SALES_ORDER_BASE = 7000;
export const orderLineIdOf = (salesOrderId: number, index: number) => salesOrderId * 100 + index + 1;

// ─── Phí vận chuyển (ST09, BR-SP-08): nguồn đăng ký để luồng chính nối với fees.mock.ts (`shippingFeeConfig`) ───

export interface ShippingFeeSource {
  baseFee: number;
  freeThreshold: number;
}
const DEFAULT_SHIPPING_FEE: ShippingFeeSource = { baseFee: 30_000, freeThreshold: 500_000 };
let shippingFeeSource: () => ShippingFeeSource = () => DEFAULT_SHIPPING_FEE;
/** Nối `() => shippingFeeConfig()` của fees.mock.ts; chưa nối thì phí cố định 30.000 đ, miễn phí từ 500.000 đ. */
export function registerShippingFeeSource(source: () => ShippingFeeSource) {
  shippingFeeSource = source;
}
const feeFor = (config: ShippingFeeSource, subtotal: number) => (config.freeThreshold > 0 && subtotal >= config.freeThreshold ? 0 : config.baseFee);
export function shippingQuote(subtotal: number) {
  const config = shippingFeeSource();
  const shippingFee = feeFor(config, subtotal);
  return { shippingFee, freeShippingApplied: config.freeThreshold > 0 && subtotal >= config.freeThreshold, total: subtotal + shippingFee };
}

// ─── Cổng thanh toán GIẢ LẬP: chữ ký callback (BR-TG-07). Cổng thật ký HMAC bằng khóa ở biến môi trường; mock dùng băm cố định. ───

export const MOCK_GATEWAY_SECRET = 'petcare-mock-gateway-secret';
/** Mã trạng thái "thành công" của cổng giả lập (như VNPay `00`); mọi mã khác là thất bại. */
export const GATEWAY_SUCCESS_CODE = '00';
/** Khách bấm hủy tại cổng (như VNPay `24`). */
export const GATEWAY_CANCEL_CODE = '24';

function fnv1a(text: string, seed: number): string {
  let h = seed >>> 0;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}
/** Chữ ký hợp lệ của một callback: cổng giả lập (và test) gọi hàm này để ký. */
export function gatewaySignature(f: { transactionCode: string; gatewayTxnId?: string | null; status: string; amount: number }): string {
  const text = [MOCK_GATEWAY_SECRET, f.transactionCode, f.gatewayTxnId ?? '', f.status, f.amount].join('|');
  return fnv1a(text, 2166136261) + fnv1a(text, 3266489917);
}

// ─── Dòng dữ liệu ───

export interface CartLine {
  productId: number;
  quantity: number;
}
export interface ConsumedLot {
  productId: number;
  lotId: number;
  quantity: number;
}
export interface OnlineOrderRow {
  onlineOrderId: number;
  salesOrderId: number;
  code: string;
  customerId: number;
  branchId: number;
  status: OnlineOrderStatus;
  receiverName: string;
  receiverPhone: string;
  shipAddress: string;
  lines: OnlineOrderLine[];
  subtotal: number;
  shippingFee: number;
  carrier: string | null;
  trackingCode: string | null;
  redeliveryCount: number;
  paymentDueAt: string;
  paidAt: string | null;
  shippedAt: string | null;
  deliveredAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  /** online_orders.cancelled_by: null = hệ thống (ST10). */
  cancelledBy: number | null;
  /** Lý do giao thất bại (03 #14 "Có lý do"); yaml không có field để trả ra. */
  failReason: string | null;
  createdAt: string;
  /**
   * Lô đã xuất FEFO lúc PAID (còn nằm ngoài kho) để hoàn đúng lô gốc (BR-BH-09, BR-BH-14). null = đơn mẫu, mock không xuất kho thật nên không hoàn kho
   * khi hủy / trả.
   */
  consumed: ConsumedLot[] | null;
}
export interface HoldRow {
  onlineOrderId: number;
  branchId: number;
  productId: number;
  quantity: number;
  status: 'HELD' | 'CONSUMED' | 'RELEASED';
}
export interface TxnRow {
  transactionId: number;
  code: string;
  onlineOrderId: number;
  amount: number;
  status: PaymentTransactionStatus;
  gatewayTxnId: string | null;
  expiresAt: string;
  paidAt: string | null;
  failureReason: string | null;
  createdAt: string;
}
export interface ReconRow {
  reconciliationId: number;
  reconDate: string;
  status: ReconciliationStatus;
  txnCount: number;
  mismatchCount: number;
  finishedAt: string | null;
}
export interface ReconItemRow {
  itemId: number;
  reconciliationId: number;
  kind: ReconciliationItemKind;
  transactionId: number | null;
  gatewayRef: string | null;
  ourAmount: number | null;
  gatewayAmount: number | null;
  resolved: boolean;
  resolvedNote: string | null;
  resolvedAt: string | null;
  resolvedBy: number | null;
}
export interface ReturnLineRow {
  orderLineId: number;
  productName: string;
  unitPrice: number;
  quantity: number;
}
export interface ReturnRow {
  requestId: number;
  code: string;
  /** `orders.id`: Order quầy hoặc `salesOrderId` của đơn online. */
  orderId: number;
  branchId: number;
  status: ReturnRequestStatus;
  reasonCode: ReturnReason;
  reasonNote: string | null;
  lines: ReturnLineRow[];
  createdBy: number;
  decidedBy: number | null;
  decisionNote: string | null;
  decidedAt: string | null;
  restock: boolean | null;
  refundAmount: number | null;
  createdAt: string;
}
export interface RefundRow {
  refundId: number;
  code: string;
  returnRequestId: number | null;
  onlineOrderId: number | null;
  branchId: number;
  method: RefundMethod;
  amount: number;
  status: RefundStatus;
  attempts: number;
  /** Chỉ hoàn tiền GATEWAY ở REQUESTED: lúc ST21 xử lý. */
  nextAttemptAt: string | null;
  failureReason: string | null;
  confirmedBy: number | null;
  cashierShiftId: number | null;
  completedAt: string | null;
  createdAt: string;
}

export interface State {
  /** Bộ đếm id và mã chứng từ theo ngày. */
  counters: Record<string, number>;
  /** Giỏ theo `customerId` (mỗi khách một giỏ ở server, BR-BH-07). */
  carts: Record<string, CartLine[]>;
  orders: OnlineOrderRow[];
  holds: HoldRow[];
  txns: TxnRow[];
  recons: ReconRow[];
  reconItems: ReconItemRow[];
  returns: ReturnRow[];
  refunds: RefundRow[];
}

const TABLES = ['counters', 'carts', 'orders', 'holds', 'txns', 'recons', 'reconItems', 'returns', 'refunds'] as const;

// ─── Thời gian, id, mã ───

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
export const iso = (ms: number) => new Date(ms).toISOString();
const pad = (n: number, width: number) => String(n).padStart(width, '0');

export function nextId(s: State, key: string): number {
  s.counters[key] = (s.counters[key] ?? 0) + 1;
  return s.counters[key];
}
/** `PREFIX-yyMMdd-xxxx` theo ngày Việt Nam (ADR-0043: DH, TH, HT; TT cho mã tham chiếu giao dịch gửi cổng). */
export function docCode(s: State, prefix: string, atMs: number): string {
  const day = vnDate(atMs).slice(2).replace(/-/g, '');
  return `${prefix}-${day}-${pad(nextId(s, `${prefix}-${day}`), 4)}`;
}

// ─── Dữ liệu mẫu (thời gian tương đối với lúc tạo; ghi ngay khi tạo để đọc lại ra cùng mốc) ───

const GOODS: Record<number, { name: string; unit: string; price: number }> = {
  1: { name: 'Hạt Royal Canin Mini Adult 2kg', unit: 'túi', price: 450_000 },
  2: { name: 'Pate Whiskas 85g', unit: 'gói', price: 18_000 },
};
const RECEIVERS: Record<number, { receiverName: string; receiverPhone: string; shipAddress: string }> = {
  1: { receiverName: 'Nguyễn Thu Trang', receiverPhone: '0912345678', shipAddress: '12 Trần Thái Tông, Dịch Vọng Hậu, Hà Nội' },
  2: { receiverName: 'Lê Hoàng Long', receiverPhone: '0905111222', shipAddress: '8 Nguyễn Chí Thanh, Láng Thượng, Hà Nội' },
};

/**
 * Chi nhánh 1 (ACTIVE duy nhất trong mock chi nhánh). Khách khach@petcare.vn = hồ sơ #1, khachmoi@petcare.vn = hồ sơ #2.
 *  Đơn online (salesOrderId = 7000 + id): #1 COMPLETED 21 ngày trước (hết hạn trả hàng) · #2 DELIVERED hôm qua (trả hàng được) · #3 SHIPPED ·
 *  #4 PROCESSING (khách #2) · #5 PAID 40 phút trước · #6 DELIVERY_FAILED đã giao lại 2 lần (hết lượt) · #7 DELIVERY_FAILED chưa giao lại (khách #2) ·
 *  #8 PENDING_PAYMENT còn hạn, giao dịch trước FAILED, đang giữ 1 túi hạt · #9 CANCELLED khi chưa thanh toán · #10 CANCELLED sau PAID (đã hoàn tiền).
 *  Trả hàng (chi nhánh 1): #1 PENDING (Order quầy #5) · #2 APPROVED + hoàn CASH đang chờ lễ tân xác nhận · #3 REJECTED · #4 CANCELLED ·
 *  #5 PENDING do tài khoản #8 tạo (đơn online #2) · #6 APPROVED + hoàn GATEWAY FAILED (thử lại được) · #7 REFUNDED (đơn online #1).
 *  Đối soát: 4 phiên (khớp, lệch 3 mục trong đó 1 đã xử lý, lỗi cổng, lệch 1 mục). Giỏ khach@: 1 túi hạt + 2 gói pate (486.000 đ, chưa miễn phí vận chuyển).
 */
function seed(now = Date.now()): State {
  const s: State = { counters: {}, carts: {}, orders: [], holds: [], txns: [], recons: [], reconItems: [], returns: [], refunds: [] };
  const ago = (ms: number) => now - ms;

  const mkOrder = (
    o: { status: OnlineOrderStatus; customerId: number; createdAt: number; items: [productId: number, quantity: number][] } & Omit<Partial<OnlineOrderRow>, 'createdAt'>,
  ): OnlineOrderRow => {
    const { items, createdAt, ...rest } = o;
    const onlineOrderId = nextId(s, 'order');
    const salesOrderId = ONLINE_SALES_ORDER_BASE + onlineOrderId;
    const lines = items.map(([productId, quantity], i): OnlineOrderLine => {
      const g = GOODS[productId];
      return { orderLineId: orderLineIdOf(salesOrderId, i), productId, name: g.name, unit: g.unit, unitPrice: g.price, quantity, lineTotal: g.price * quantity };
    });
    const subtotal = lines.reduce((sum, l) => sum + l.lineTotal, 0);
    const row: OnlineOrderRow = {
      onlineOrderId,
      salesOrderId,
      code: docCode(s, 'DH', createdAt),
      branchId: 1,
      ...RECEIVERS[o.customerId],
      lines,
      subtotal,
      shippingFee: feeFor(DEFAULT_SHIPPING_FEE, subtotal),
      carrier: null,
      trackingCode: null,
      redeliveryCount: 0,
      paymentDueAt: iso(createdAt + CFG.paymentWindowHours * HOUR),
      paidAt: null,
      shippedAt: null,
      deliveredAt: null,
      completedAt: null,
      cancelledAt: null,
      cancelReason: null,
      cancelledBy: null,
      failReason: null,
      createdAt: iso(createdAt),
      consumed: null,
      ...rest,
    };
    s.orders.push(row);
    if (row.paidAt) {
      const transactionId = nextId(s, 'txn');
      s.txns.push({
        transactionId,
        code: docCode(s, 'TT', Date.parse(row.paidAt) - 3 * MIN),
        onlineOrderId,
        amount: subtotal + row.shippingFee,
        status: 'SUCCEEDED',
        gatewayTxnId: `14${pad(transactionId * 7919, 6)}`,
        expiresAt: iso(Date.parse(row.paidAt) + 12 * MIN),
        paidAt: row.paidAt,
        failureReason: null,
        createdAt: iso(Date.parse(row.paidAt) - 3 * MIN),
      });
    }
    return row;
  };
  const paidAfter = (createdAt: number) => iso(createdAt + 6 * MIN);
  const created = (ms: number) => ({ createdAt: ms, paidAt: paidAfter(ms) });

  const o1 = mkOrder({ status: 'COMPLETED', customerId: 1, items: [[1, 1], [2, 2]], ...created(ago(21 * DAY)), carrier: 'GHN', trackingCode: 'GHN2510001', shippedAt: iso(ago(15 * DAY)), deliveredAt: iso(ago(14 * DAY)), completedAt: iso(ago(7 * DAY)) });
  const o2 = mkOrder({ status: 'DELIVERED', customerId: 1, items: [[1, 1], [2, 4]], ...created(ago(3 * DAY)), carrier: 'GHN', trackingCode: 'GHN2611021', shippedAt: iso(ago(2 * DAY)), deliveredAt: iso(ago(1 * DAY)) });
  const o3 = mkOrder({ status: 'SHIPPED', customerId: 1, items: [[2, 6]], ...created(ago(2 * DAY)), carrier: 'Viettel Post', trackingCode: 'VTP2611055', shippedAt: iso(ago(5 * HOUR)) });
  mkOrder({ status: 'PROCESSING', customerId: 2, items: [[1, 1]], ...created(ago(5 * HOUR)) });
  mkOrder({ status: 'PAID', customerId: 1, items: [[1, 1], [2, 1]], createdAt: ago(50 * MIN), paidAt: iso(ago(40 * MIN)) });
  mkOrder({ status: 'DELIVERY_FAILED', customerId: 1, items: [[2, 3]], ...created(ago(5 * DAY)), carrier: 'GHN', trackingCode: 'GHN2611090', shippedAt: iso(ago(1 * DAY)), redeliveryCount: CFG.maxRedelivery, failReason: 'Người nhận không nghe máy, giao lần 3 thất bại' });
  const o7 = mkOrder({ status: 'DELIVERY_FAILED', customerId: 2, items: [[1, 1]], ...created(ago(3 * DAY)), carrier: 'J&T', trackingCode: 'JT2611123', shippedAt: iso(ago(20 * HOUR)), failReason: 'Sai địa chỉ, người nhận vắng nhà' });
  const o8 = mkOrder({ status: 'PENDING_PAYMENT', customerId: 1, items: [[1, 1]], createdAt: ago(10 * MIN) });
  s.holds.push({ onlineOrderId: o8.onlineOrderId, branchId: 1, productId: 1, quantity: 1, status: 'HELD' });
  s.txns.push({
    transactionId: nextId(s, 'txn'),
    code: docCode(s, 'TT', ago(8 * MIN)),
    onlineOrderId: o8.onlineOrderId,
    amount: o8.subtotal + o8.shippingFee,
    status: 'FAILED',
    gatewayTxnId: null,
    expiresAt: iso(ago(8 * MIN) + CFG.txnTtlMinutes * MIN),
    paidAt: null,
    failureReason: 'Khách hủy giao dịch tại cổng',
    createdAt: iso(ago(8 * MIN)),
  });
  const o9 = mkOrder({ status: 'CANCELLED', customerId: 1, items: [[2, 2]], createdAt: ago(6 * DAY), cancelledAt: iso(ago(6 * DAY) + 2 * HOUR), cancelReason: 'Đặt nhầm sản phẩm', cancelledBy: 7 });
  s.holds.push({ onlineOrderId: o9.onlineOrderId, branchId: 1, productId: 2, quantity: 2, status: 'RELEASED' });
  const o10 = mkOrder({ status: 'CANCELLED', customerId: 1, items: [[1, 1]], ...created(ago(9 * DAY)), cancelledAt: iso(ago(9 * DAY) + 2 * HOUR), cancelReason: 'Đổi ý, không cần nữa', cancelledBy: 7 });

  const refund = (r: Partial<RefundRow> & Pick<RefundRow, 'method' | 'amount' | 'status' | 'createdAt'>): RefundRow => {
    const row: RefundRow = {
      refundId: nextId(s, 'refund'),
      code: docCode(s, 'HT', Date.parse(r.createdAt)),
      returnRequestId: null,
      onlineOrderId: null,
      branchId: 1,
      attempts: 0,
      nextAttemptAt: null,
      failureReason: null,
      confirmedBy: null,
      cashierShiftId: null,
      completedAt: null,
      ...r,
    };
    s.refunds.push(row);
    return row;
  };
  refund({ onlineOrderId: o10.onlineOrderId, method: 'GATEWAY', amount: o10.subtotal + o10.shippingFee, status: 'SUCCEEDED', attempts: 1, createdAt: o10.cancelledAt!, completedAt: iso(Date.parse(o10.cancelledAt!) + 3 * MIN) });

  const ret = (r: Partial<ReturnRow> & Pick<ReturnRow, 'orderId' | 'status' | 'reasonCode' | 'lines' | 'createdAt'>): ReturnRow => {
    const row: ReturnRow = {
      requestId: nextId(s, 'return'),
      code: docCode(s, 'TH', Date.parse(r.createdAt)),
      branchId: 1,
      reasonNote: null,
      createdBy: 4,
      decidedBy: null,
      decisionNote: null,
      decidedAt: null,
      restock: null,
      refundAmount: null,
      ...r,
    };
    s.returns.push(row);
    return row;
  };
  const pate = (orderLineId: number, quantity: number): ReturnLineRow => ({ orderLineId, productName: 'Pate Whiskas 85g', unitPrice: 18_000, quantity });
  const approved = (at: number) => ({ decidedBy: 3, decidedAt: iso(at) });
  // Order quầy #5 (sales.mock): dòng 51 pate × 6, thu tiền mặt (phiếu #1) 3 ngày trước.
  ret({ orderId: 5, status: 'PENDING', reasonCode: 'DEFECTIVE', reasonNote: 'Hai gói bị rách vỏ', lines: [pate(51, 2)], createdAt: iso(ago(20 * HOUR)) });
  const r2 = ret({ orderId: 5, status: 'APPROVED', reasonCode: 'CHANGED_MIND', lines: [pate(51, 1)], createdAt: iso(ago(8 * HOUR)), ...approved(ago(5 * HOUR)), restock: true, refundAmount: 18_000 });
  refund({ returnRequestId: r2.requestId, method: 'CASH', amount: 18_000, status: 'REQUESTED', createdAt: r2.decidedAt! });
  ret({ orderId: 5, status: 'REJECTED', reasonCode: 'WRONG_ITEM', lines: [pate(51, 1)], createdAt: iso(ago(2 * DAY)), decidedBy: 3, decidedAt: iso(ago(2 * DAY) + 3 * HOUR), decisionNote: 'Hàng đã mở bao, không nhận trả' });
  ret({ orderId: 5, status: 'CANCELLED', reasonCode: 'OTHER', reasonNote: 'Nhập nhầm số lượng', lines: [pate(51, 1)], createdAt: iso(ago(1 * DAY)) });
  // Đơn online #2 (DELIVERED hôm qua). Yêu cầu #5 do tài khoản #8 (lễ tân mới) tạo: lễ tân #4 không rút được ("Người tạo").
  ret({ orderId: o2.salesOrderId, status: 'PENDING', reasonCode: 'OTHER', reasonNote: 'Túi hạt bị thủng góc', lines: [{ orderLineId: o2.lines[0].orderLineId!, productName: o2.lines[0].name, unitPrice: 450_000, quantity: 1 }], createdAt: iso(ago(3 * HOUR)), createdBy: 8 });
  const r6 = ret({ orderId: o2.salesOrderId, status: 'APPROVED', reasonCode: 'DEFECTIVE', lines: [pate(o2.lines[1].orderLineId!, 1)], createdAt: iso(ago(20 * HOUR)), ...approved(ago(18 * HOUR)), restock: false, refundAmount: 18_000 });
  refund({
    returnRequestId: r6.requestId,
    method: 'GATEWAY',
    amount: 18_000,
    status: 'FAILED',
    attempts: 3,
    failureReason: 'Cổng từ chối hoàn tiền: tài khoản nguồn tạm khóa',
    createdAt: r6.decidedAt!,
  });
  const r7 = ret({ orderId: o1.salesOrderId, status: 'REFUNDED', reasonCode: 'DEFECTIVE', lines: [pate(o1.lines[1].orderLineId!, 1)], createdAt: iso(ago(12 * DAY)), decidedBy: 3, decidedAt: iso(ago(12 * DAY) + 2 * HOUR), restock: true, refundAmount: 18_000 });
  refund({ returnRequestId: r7.requestId, method: 'GATEWAY', amount: 18_000, status: 'SUCCEEDED', attempts: 1, createdAt: r7.decidedAt!, completedAt: iso(Date.parse(r7.decidedAt!) + 4 * MIN) });

  const txnOf = (orderId: number) => s.txns.find((t) => t.onlineOrderId === orderId && t.status === 'SUCCEEDED')!;
  const recon = (r: Pick<ReconRow, 'status' | 'txnCount' | 'mismatchCount'> & { daysAgo: number }): ReconRow => {
    const row: ReconRow = {
      reconciliationId: nextId(s, 'recon'),
      reconDate: vnDate(ago(r.daysAgo * DAY)),
      status: r.status,
      txnCount: r.txnCount,
      mismatchCount: r.mismatchCount,
      // ST14 chạy sau 02:00 của ngày kế tiếp (BR-TG-10).
      finishedAt: iso(Math.min(now, Date.parse(`${vnDate(ago((r.daysAgo - 1) * DAY))}T02:10:00+07:00`))),
    };
    s.recons.push(row);
    return row;
  };
  const item = (reconciliationId: number, kind: ReconciliationItemKind, i: Partial<ReconItemRow>): ReconItemRow => {
    const row: ReconItemRow = { itemId: nextId(s, 'item'), reconciliationId, kind, transactionId: null, gatewayRef: null, ourAmount: null, gatewayAmount: null, resolved: false, resolvedNote: null, resolvedAt: null, resolvedBy: null, ...i };
    s.reconItems.push(row);
    return row;
  };
  recon({ daysAgo: 1, status: 'MATCHED', txnCount: 6, mismatchCount: 0 });
  const mismatched = recon({ daysAgo: 2, status: 'MISMATCHED', txnCount: 9, mismatchCount: 3 });
  const t3 = txnOf(o3.onlineOrderId);
  item(mismatched.reconciliationId, 'AMOUNT_DIFF', { transactionId: t3.transactionId, gatewayRef: t3.gatewayTxnId, ourAmount: t3.amount, gatewayAmount: t3.amount - 30_000 });
  item(mismatched.reconciliationId, 'MISSING_LOCAL', {
    gatewayRef: '14020771',
    gatewayAmount: 270_000,
    resolved: true,
    resolvedNote: 'Giao dịch thử của cổng, không có đơn tương ứng',
    resolvedAt: iso(ago(1 * DAY) + 9 * HOUR),
    resolvedBy: 2,
  });
  item(mismatched.reconciliationId, 'STATUS_DIFF', { transactionId: txnOf(o7.onlineOrderId).transactionId, gatewayRef: txnOf(o7.onlineOrderId).gatewayTxnId, ourAmount: txnOf(o7.onlineOrderId).amount, gatewayAmount: txnOf(o7.onlineOrderId).amount });
  recon({ daysAgo: 3, status: 'FAILED', txnCount: 0, mismatchCount: 0 });
  const old = recon({ daysAgo: 5, status: 'MISMATCHED', txnCount: 4, mismatchCount: 1 });
  item(old.reconciliationId, 'MISSING_GATEWAY', { transactionId: txnOf(o1.onlineOrderId).transactionId, gatewayRef: txnOf(o1.onlineOrderId).gatewayTxnId, ourAmount: txnOf(o1.onlineOrderId).amount });

  s.carts = { '1': [{ productId: 1, quantity: 1 }, { productId: 2, quantity: 2 }], '2': [] };
  return s;
}

// ─── Lưu trữ ───

const STATE_KEY = 'petcare-mock-commerce';

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}
export { save };

/** Nạp state; bảng nào thiếu (state lưu từ bản cũ, hoặc `{}`) thì gộp từ dữ liệu mẫu rồi ghi lại ngay. */
export function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) {
      const stored = JSON.parse(raw) as Partial<State> | null;
      if (stored && typeof stored === 'object') {
        if (TABLES.every((k) => stored[k] != null)) return stored as State;
        const base = seed();
        const merged = { ...base, ...Object.fromEntries(Object.entries(stored).filter(([, v]) => v !== undefined && v !== null)) } as State;
        syncCounters(merged);
        save(merged);
        return merged;
      }
    }
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const fresh = seed();
  save(fresh);
  return fresh;
}

/** Bộ đếm id không được thấp hơn id lớn nhất đang có (state cũ thiếu `counters` nhưng đã có bảng). */
function syncCounters(s: State) {
  const bump = (key: string, ids: number[]) => {
    s.counters[key] = Math.max(s.counters[key] ?? 0, 0, ...ids);
  };
  bump('order', s.orders.map((o) => o.onlineOrderId));
  bump('txn', s.txns.map((t) => t.transactionId));
  bump('recon', s.recons.map((r) => r.reconciliationId));
  bump('item', s.reconItems.map((i) => i.itemId));
  bump('return', s.returns.map((r) => r.requestId));
  bump('refund', s.refunds.map((r) => r.refundId));
}

/** `load()` rồi cho các job hệ thống chạy; ghi lại ngay nếu có gì đổi (các job có tác dụng phụ lên kho, không được chạy hai lần). */
export function loadFresh(now = Date.now()): State {
  const s = load();
  if (sweep(s, now)) save(s);
  return s;
}

// ─── Tồn khả dụng trừ phần giữ (BR-BH-08, BR-BH-09) ───

export const heldQuantity = (s: State, branchId: number, productId: number) =>
  s.holds.filter((h) => h.status === 'HELD' && h.branchId === branchId && h.productId === productId).reduce((n, h) => n + h.quantity, 0);

/** `StockQueryApi.findAvailableQuantity`: tồn khả dụng (lô chưa hết hạn) đã trừ phần đang giữ. */
export const availableAt = (s: State, branchId: number, productId: number) => Math.max(0, availableQuantity(branchId, productId) - heldQuantity(s, branchId, productId));

export const activeBranchIds = () => activeBranchSummaries().map((b) => b.branchId);

/** "Hết hàng toàn chuỗi" của CartItem.available: tổng tồn khả dụng ở mọi chi nhánh ACTIVE. */
export const chainAvailable = (s: State, productId: number) => activeBranchIds().reduce((n, id) => n + availableAt(s, id, productId), 0);

export const branchNameOf = (branchId: number) => branchBooking(branchId)?.branch.name ?? `Chi nhánh #${branchId}`;
export const isActiveBranch = (branchId: number) => branchBooking(branchId)?.branch.status === 'ACTIVE';

// ─── Giỏ hàng (BR-BH-07) ───

export interface CartEntry {
  product: Product;
  quantity: number;
}
/** Dòng giỏ còn kinh doanh (sản phẩm ngừng kinh doanh tự bị loại khi xem giỏ). */
export function cartEntries(s: State, customerId: number): CartEntry[] {
  const products = catalogProducts();
  return (s.carts[String(customerId)] ?? []).flatMap((line) => {
    const product = products.find((p) => p.productId === line.productId);
    return product && product.isActive ? [{ product, quantity: line.quantity }] : [];
  });
}

/** Dòng đầu tiên của giỏ mà chi nhánh không đủ hàng (đã trừ phần giữ); null nếu đủ mọi dòng. */
export function firstShortage(s: State, branchId: number, entries: CartEntry[]) {
  for (const { product, quantity } of entries) {
    const available = availableAt(s, branchId, product.productId);
    if (available < quantity) return { name: product.name, quantity, available };
  }
  return null;
}

// ─── Đơn online ───

export function placeOrder(
  s: State,
  args: { customerId: number; accountId: number; branchId: number; address: { receiverName: string; receiverPhone: string; shipAddress: string }; entries: CartEntry[] },
  now: number,
): OnlineOrderRow {
  const onlineOrderId = nextId(s, 'order');
  const salesOrderId = ONLINE_SALES_ORDER_BASE + onlineOrderId;
  const lines = args.entries.map(({ product, quantity }, i): OnlineOrderLine => ({
    orderLineId: orderLineIdOf(salesOrderId, i),
    productId: product.productId,
    name: product.name,
    unit: product.unit,
    unitPrice: product.price,
    quantity,
    lineTotal: product.price * quantity,
  }));
  const subtotal = lines.reduce((sum, l) => sum + l.lineTotal, 0);
  const row: OnlineOrderRow = {
    onlineOrderId,
    salesOrderId,
    code: docCode(s, 'DH', now),
    customerId: args.customerId,
    branchId: args.branchId,
    status: 'PENDING_PAYMENT',
    ...args.address,
    lines,
    subtotal,
    shippingFee: shippingQuote(subtotal).shippingFee,
    carrier: null,
    trackingCode: null,
    redeliveryCount: 0,
    paymentDueAt: iso(now + CFG.paymentWindowHours * HOUR),
    paidAt: null,
    shippedAt: null,
    deliveredAt: null,
    completedAt: null,
    cancelledAt: null,
    cancelReason: null,
    cancelledBy: null,
    failReason: null,
    createdAt: iso(now),
    consumed: [],
  };
  s.orders.push(row);
  for (const l of lines) s.holds.push({ onlineOrderId, branchId: args.branchId, productId: l.productId, quantity: l.quantity, status: 'HELD' });
  const placed = new Set(lines.map((l) => l.productId));
  s.carts[String(args.customerId)] = (s.carts[String(args.customerId)] ?? []).filter((l) => !placed.has(l.productId));
  return row;
}

export const totalOf = (o: Pick<OnlineOrderRow, 'subtotal' | 'shippingFee'>) => o.subtotal + o.shippingFee;

/** Xuất FEFO phần đang giữ và ghi lại lô đã xuất (so hai ảnh chụp tồn). Thiếu tồn thật (kho bị điều chỉnh xuống dưới phần giữ) thì không xuất gì. */
function consumeStock(branchId: number, lines: { productId: number; quantity: number }[]): ConsumedLot[] {
  const today = vnDate();
  const before = stockSnapshot(today).filter((i) => i.branchId === branchId);
  if (issueFefo(branchId, lines).length) return [];
  const after = stockSnapshot(today).filter((i) => i.branchId === branchId);
  return before.flatMap((b) => {
    const a = after.find((x) => x.productId === b.productId);
    return b.lots.flatMap((lot) => {
      const taken = lot.quantity - (a?.lots.find((x) => x.stockLotId === lot.stockLotId)?.quantity ?? lot.quantity);
      return taken > 0 ? [{ productId: b.productId, lotId: lot.stockLotId, quantity: taken }] : [];
    });
  });
}

/** Lô để nhập lại khi không có bản ghi lô gốc: lô còn hạn có hạn dùng sớm nhất (FEFO), lô mặc định cuối; không có dòng tồn thì thôi. */
function fallbackLot(branchId: number, productId: number): number | null {
  const lots = stockSnapshot(vnDate()).find((i) => i.branchId === branchId && i.productId === productId)?.lots ?? [];
  const usable = lots.filter((l) => !l.expired).sort((a, b) => (a.expiryDate ?? '9999-12-31').localeCompare(b.expiryDate ?? '9999-12-31') || a.stockLotId - b.stockLotId);
  return (usable[0] ?? lots[0])?.stockLotId ?? null;
}

/** Hoàn `quantity` đơn vị về kho: về đúng lô đã xuất của đơn (BR-BH-14); phần không có bản ghi lô (Order quầy: `pool` rỗng) thì về lô FEFO đầu tiên. */
export function restockUnits(branchId: number, productId: number, quantity: number, pool: ConsumedLot[]) {
  let rest = quantity;
  for (const c of pool.filter((x) => x.productId === productId && x.quantity > 0)) {
    const give = Math.min(c.quantity, rest);
    returnToLot(c.lotId, give);
    c.quantity -= give;
    rest -= give;
    if (rest === 0) return;
  }
  const lotId = fallbackLot(branchId, productId);
  if (lotId !== null) returnToLot(lotId, rest);
}

/** Đơn online `PAID`: chuyển phần giữ thành xuất kho FEFO, cùng transaction với PAID (BR-BH-09, Đơn hàng online#2, Order#12). */
export function markPaid(s: State, order: OnlineOrderRow, paidAt: string) {
  const holds = s.holds.filter((h) => h.onlineOrderId === order.onlineOrderId && h.status === 'HELD');
  const taken = consumeStock(order.branchId, holds.map((h) => ({ productId: h.productId, quantity: h.quantity })));
  order.consumed = taken.length ? taken : null;
  for (const h of holds) h.status = 'CONSUMED';
  order.status = 'PAID';
  order.paidAt = paidAt;
}

export function createRefund(s: State, args: { returnRequestId?: number; onlineOrderId?: number; branchId: number; method: RefundMethod; amount: number }, now: number): RefundRow {
  const row: RefundRow = {
    refundId: nextId(s, 'refund'),
    code: docCode(s, 'HT', now),
    returnRequestId: args.returnRequestId ?? null,
    onlineOrderId: args.onlineOrderId ?? null,
    branchId: args.branchId,
    method: args.method,
    amount: args.amount,
    status: 'REQUESTED',
    attempts: 0,
    nextAttemptAt: args.method === 'GATEWAY' ? iso(now + CFG.refundJobMinutes * MIN) : null,
    failureReason: null,
    confirmedBy: null,
    cashierShiftId: null,
    completedAt: null,
    createdAt: iso(now),
  };
  s.refunds.push(row);
  return row;
}

/** Giao dịch PENDING của đơn → CANCELLED (Giao dịch#5) khi đơn bị hủy. */
function cancelPendingTxns(s: State, onlineOrderId: number) {
  for (const t of s.txns) if (t.onlineOrderId === onlineOrderId && t.status === 'PENDING') t.status = 'CANCELLED';
}

/**
 * Hủy đơn (Đơn hàng online#3, #4, #10, #13). Chưa thanh toán: nhả giữ hàng, hủy giao dịch PENDING. Đã thanh toán (PAID / PROCESSING / DELIVERY_FAILED): hoàn hàng
 * về lô gốc và sinh hoàn tiền GATEWAY toàn bộ (gồm phí vận chuyển, BR-TG-12). `by` null = hệ thống.
 */
export function cancelOrder(s: State, order: OnlineOrderRow, args: { reason: string; by: number | null; at: number }) {
  if (order.status === 'PENDING_PAYMENT') {
    for (const h of s.holds) if (h.onlineOrderId === order.onlineOrderId && h.status === 'HELD') h.status = 'RELEASED';
    cancelPendingTxns(s, order.onlineOrderId);
  } else {
    for (const c of order.consumed ?? []) {
      returnToLot(c.lotId, c.quantity);
      c.quantity = 0;
    }
    createRefund(s, { onlineOrderId: order.onlineOrderId, branchId: order.branchId, method: 'GATEWAY', amount: totalOf(order) }, args.at);
  }
  order.status = 'CANCELLED';
  order.cancelledAt = iso(args.at);
  order.cancelReason = args.reason;
  order.cancelledBy = args.by;
}

// ─── Trả hàng: Order quầy hoặc đơn online (BR-BH-13) ───

export interface ReturnableLine {
  orderLineId: number;
  /** Order quầy: loại dòng (chỉ GOODS trả được); đơn online luôn GOODS. */
  lineType: string;
  productId: number | null;
  name: string;
  unitPrice: number;
  quantity: number;
}
export interface ReturnableOrder {
  kind: 'ONLINE' | 'COUNTER';
  orderId: number;
  branchId: number;
  /** Đã PAID (quầy) / đã giao (online). */
  eligible: boolean;
  /** Mốc tính hạn trả hàng: lúc thanh toán (quầy) hoặc `delivered_at` (online); null nếu chưa có. */
  baseAt: number | null;
  lines: ReturnableLine[];
}

/** `OrderQueryApi.findLinesForReturn`. */
export function findReturnableOrder(s: State, orderId: number): ReturnableOrder | null {
  const online = s.orders.find((o) => o.salesOrderId === orderId);
  if (online) {
    return {
      kind: 'ONLINE',
      orderId,
      branchId: online.branchId,
      eligible: online.status === 'DELIVERED' || online.status === 'COMPLETED',
      baseAt: online.deliveredAt ? Date.parse(online.deliveredAt) : null,
      lines: online.lines.map((l) => ({ orderLineId: l.orderLineId!, lineType: 'GOODS', productId: l.productId, name: l.name, unitPrice: l.unitPrice, quantity: l.quantity })),
    };
  }
  const counter = ordersByIds([orderId])[0];
  if (!counter) return null;
  return {
    kind: 'COUNTER',
    orderId,
    branchId: counter.branchId,
    eligible: counter.status === 'PAID',
    baseAt: counter.paidAt ? Date.parse(counter.paidAt) : null,
    lines: counter.lines.map((l) => ({ orderLineId: l.lineId, lineType: l.lineType, productId: l.productId, name: l.description, unitPrice: l.unitPrice, quantity: l.quantity })),
  };
}

/** Số lượng của dòng đã / đang trả: yêu cầu PENDING, APPROVED, REFUNDED (REJECTED, CANCELLED nhả lại). */
export const returnedQuantity = (s: State, orderId: number, orderLineId: number) =>
  s.returns
    .filter((r) => r.orderId === orderId && (r.status === 'PENDING' || r.status === 'APPROVED' || r.status === 'REFUNDED'))
    .flatMap((r) => r.lines)
    .filter((l) => l.orderLineId === orderLineId)
    .reduce((n, l) => n + l.quantity, 0);

// ─── Job hệ thống (lười) ───

/** Chạy ST10, ST11, ST12, ST21 tới thời điểm `now`; trả true nếu có gì đổi. Mốc ghi vào dòng là mốc job chạy, không phải `now`, nên chạy lại cho cùng kết quả. */
export function sweep(s: State, now: number): boolean {
  let changed = false;
  for (const t of s.txns) {
    if (t.status === 'PENDING' && Date.parse(t.expiresAt) <= now) {
      t.status = 'EXPIRED';
      changed = true;
    }
  }
  for (const o of s.orders) {
    if (o.status === 'PENDING_PAYMENT') {
      const at = Date.parse(o.paymentDueAt) + CFG.cancelJobMinutes * MIN;
      if (at <= now) {
        cancelOrder(s, o, { reason: 'Quá hạn thanh toán (hệ thống tự hủy)', by: null, at });
        changed = true;
      }
    } else if (o.status === 'DELIVERED' && o.deliveredAt) {
      const at = Date.parse(o.deliveredAt) + CFG.autoConfirmDays * DAY;
      const open = s.returns.some((r) => r.orderId === o.salesOrderId && (r.status === 'PENDING' || r.status === 'APPROVED'));
      if (at <= now && !open) {
        o.status = 'COMPLETED';
        o.completedAt = iso(at);
        changed = true;
      }
    }
  }
  for (const r of s.refunds) {
    if (r.method === 'GATEWAY' && r.status === 'REQUESTED' && r.nextAttemptAt && Date.parse(r.nextAttemptAt) <= now) {
      r.status = 'SUCCEEDED';
      r.attempts += 1;
      r.completedAt = r.nextAttemptAt;
      r.nextAttemptAt = null;
      const request = s.returns.find((x) => x.requestId === r.returnRequestId);
      if (request?.status === 'APPROVED') request.status = 'REFUNDED';
      changed = true;
    }
  }
  return changed;
}

// ─── Đọc chéo cho mock khác (branchlife, báo cáo) ───

/** Đơn online chưa xong của chi nhánh (chưa COMPLETED / CANCELLED): cho `registerClosureSources` của branchlife. */
export const openOnlineOrderCount = (branchId: number): number =>
  loadFresh().orders.filter((o) => o.branchId === branchId && o.status !== 'COMPLETED' && o.status !== 'CANCELLED').length;

/** Hoàn tiền chờ xử lý (REQUESTED + FAILED) của chi nhánh: cho `registerClosureSources` của branchlife. */
export const pendingRefundCount = (branchId: number): number =>
  loadFresh().refunds.filter((r) => r.branchId === branchId && r.status !== 'SUCCEEDED').length;

/**
 * Doanh thu của đơn online đã thanh toán (đơn PAID trở đi, kể cả đã hủy sau PAID): cho báo cáo UC89 khi report-v1 thêm nguồn ONLINE (ADR-0116 §3.3).
 * Chưa nối vào `allOrderRows()` của sales.mock vì sales-v1 / report-v1 chưa có ONLINE trong enum nguồn và test báo cáo GĐ1 khẳng định tổng số.
 * `goodsAmount` = tiền hàng (vào doanh thu), `shippingFee` báo riêng, `refunded` = hoàn tiền đã SUCCEEDED của đơn (hủy đơn + trả hàng, 08 Q4).
 */
export function onlineRevenueRows() {
  const s = loadFresh();
  return s.orders
    .filter((o) => o.paidAt)
    .map((o) => {
      const returnIds = s.returns.filter((r) => r.orderId === o.salesOrderId).map((r) => r.requestId);
      const refunded = s.refunds
        .filter((r) => r.status === 'SUCCEEDED' && (r.onlineOrderId === o.onlineOrderId || (r.returnRequestId !== null && returnIds.includes(r.returnRequestId))))
        .reduce((n, r) => n + r.amount, 0);
      return { orderId: o.salesOrderId, code: o.code, branchId: o.branchId, paidAt: o.paidAt!, goodsAmount: o.subtotal, shippingFee: o.shippingFee, refunded };
    });
}

