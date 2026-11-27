// State và nghiệp vụ DÙNG CHUNG của hai mock thương mại tầng 3: shop.mock.ts (shop-v1), payment.mock.ts (payment-v1). Trả hàng, hoàn tiền đã bỏ (ADR-0156).
// Hai module chia một state (`petcare-mock-commerce`) vì BE làm các chuyển trạng thái chéo module trong MỘT transaction: callback hợp lệ →
// Giao dịch#2 → Đơn hàng online#2 → xuất kho FEFO từ phần giữ. Đơn đã thanh toán không hủy được.
// Mỗi request gọi `loadFresh()` đúng một lần rồi `save()` đúng một lần (các hàm ở đây nhận `s`), nên không ai ghi đè bản state cũ của người khác.
//
// Hệ thống (không có endpoint, 08 §B–§C) chạy LƯỜI mỗi lần nạp state — `sweep()`:
//  ST10  đơn PENDING_PAYMENT quá `paymentDueAt` + 15 phút (chu kỳ job) bị hủy, nhả giữ hàng; trong 15 phút đó đơn quá hạn mà còn PENDING_PAYMENT nên
//        `pay` trả 400 BR-TG-06 (đúng khe hở của BE).
//  ST11  giao dịch PENDING quá `expiresAt` → EXPIRED (cổng giả lập không bao giờ báo thành công sau hạn).
//  ST12  đơn DELIVERED quá 7 ngày → COMPLETED.
import type { Product } from '../../types/catalog';
import type { PaymentTransactionStatus, ReconciliationItemKind, ReconciliationStatus } from '../../types/payment';
import type { OnlineOrderLine, OnlineOrderStatus } from '../../types/shop';
import { vnDate } from '../../utils/vn-time';
import { activeBranchSummaries, branchBooking } from './branch.mock';
import { catalogProducts } from './catalog.mock';
import { availableQuantity, issueFefo } from './inventory.mock';

// ─── Cấu hình [CFG] (mock không đọc cấu hình hệ thống nên là hằng số; mặc định của 08 §G) ───

export const CFG = {
  /** shop.payment_window_hours */
  paymentWindowHours: 24,
  /** shop.auto_confirm_days (BR-BH-12) */
  autoConfirmDays: 7,
  /** shop.max_redelivery */
  maxRedelivery: 2,
  /** payment.txn_ttl_minutes */
  txnTtlMinutes: 15,
  /** Chu kỳ job ST10: đơn có thể quá hạn tối đa chừng này trước khi bị hủy. */
  cancelJobMinutes: 15,
} as const;

/** Dải id Order online (`salesOrderId` = 7000 + id) tách khỏi Order quầy. */
export const ONLINE_SALES_ORDER_BASE = 7000;

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
}

const TABLES = ['counters', 'carts', 'orders', 'holds', 'txns', 'recons', 'reconItems'] as const;

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
/** `PREFIX-yyMMdd-xxxx` theo ngày Việt Nam (ADR-0043: DH; TT cho mã tham chiếu giao dịch gửi cổng). */
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
 *  Đơn online (salesOrderId = 7000 + id): #1 COMPLETED 21 ngày trước · #2 DELIVERED hôm qua · #3 SHIPPED ·
 *  #4 PROCESSING (khách #2) · #5 PAID 40 phút trước · #6 DELIVERY_FAILED đã giao lại 2 lần (hết lượt) · #7 DELIVERY_FAILED chưa giao lại (khách #2) ·
 *  #8 PENDING_PAYMENT còn hạn, giao dịch trước FAILED, đang giữ 1 túi hạt · #9 CANCELLED khi chưa thanh toán · #10 CANCELLED quá hạn thanh toán (ST10).
 *  Đối soát: 4 phiên (khớp, lệch 3 mục trong đó 1 đã xử lý, lỗi cổng, lệch 1 mục). Giỏ khach@: 1 túi hạt + 2 gói pate (486.000 đ, chưa miễn phí vận chuyển).
 */
function seed(now = Date.now()): State {
  const s: State = { counters: {}, carts: {}, orders: [], holds: [], txns: [], recons: [], reconItems: [] };
  const ago = (ms: number) => now - ms;

  const mkOrder = (
    o: { status: OnlineOrderStatus; customerId: number; createdAt: number; items: [productId: number, quantity: number][] } & Omit<Partial<OnlineOrderRow>, 'createdAt'>,
  ): OnlineOrderRow => {
    const { items, createdAt, ...rest } = o;
    const onlineOrderId = nextId(s, 'order');
    const salesOrderId = ONLINE_SALES_ORDER_BASE + onlineOrderId;
    const lines = items.map(([productId, quantity]): OnlineOrderLine => {
      const g = GOODS[productId];
      return { productId, name: g.name, unit: g.unit, unitPrice: g.price, quantity, lineTotal: g.price * quantity };
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
  mkOrder({ status: 'DELIVERED', customerId: 1, items: [[1, 1], [2, 4]], ...created(ago(3 * DAY)), carrier: 'GHN', trackingCode: 'GHN2611021', shippedAt: iso(ago(2 * DAY)), deliveredAt: iso(ago(1 * DAY)) });
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
  const o10 = mkOrder({ status: 'CANCELLED', customerId: 1, items: [[1, 1]], createdAt: ago(9 * DAY), cancelledAt: iso(ago(9 * DAY) + CFG.paymentWindowHours * HOUR + CFG.cancelJobMinutes * MIN), cancelReason: 'Quá hạn thanh toán (hệ thống tự hủy)' });
  s.holds.push({ onlineOrderId: o10.onlineOrderId, branchId: 1, productId: 1, quantity: 1, status: 'RELEASED' });


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
  const lines = args.entries.map(({ product, quantity }): OnlineOrderLine => ({
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
  };
  s.orders.push(row);
  for (const l of lines) s.holds.push({ onlineOrderId, branchId: args.branchId, productId: l.productId, quantity: l.quantity, status: 'HELD' });
  const placed = new Set(lines.map((l) => l.productId));
  s.carts[String(args.customerId)] = (s.carts[String(args.customerId)] ?? []).filter((l) => !placed.has(l.productId));
  return row;
}

export const totalOf = (o: Pick<OnlineOrderRow, 'subtotal' | 'shippingFee'>) => o.subtotal + o.shippingFee;

/** Đơn online `PAID`: chuyển phần giữ thành xuất kho FEFO, cùng transaction với PAID (BR-BH-09, Đơn hàng online#2, Order#12). */
export function markPaid(s: State, order: OnlineOrderRow, paidAt: string) {
  const holds = s.holds.filter((h) => h.onlineOrderId === order.onlineOrderId && h.status === 'HELD');
  // Thiếu tồn thật (kho bị điều chỉnh xuống dưới phần giữ) thì issueFefo không xuất gì.
  issueFefo(order.branchId, holds.map((h) => ({ productId: h.productId, quantity: h.quantity })));
  for (const h of holds) h.status = 'CONSUMED';
  order.status = 'PAID';
  order.paidAt = paidAt;
}

/** Giao dịch PENDING của đơn → CANCELLED (Giao dịch#5) khi đơn bị hủy. */
function cancelPendingTxns(s: State, onlineOrderId: number) {
  for (const t of s.txns) if (t.onlineOrderId === onlineOrderId && t.status === 'PENDING') t.status = 'CANCELLED';
}

/** Hủy đơn chưa thanh toán (Đơn hàng online#3, #4): nhả giữ hàng, hủy giao dịch PENDING. #10, #13 đã bỏ (ADR-0156). `by` null = hệ thống. */
export function cancelOrder(s: State, order: OnlineOrderRow, args: { reason: string; by: number | null; at: number }) {
  if (order.status !== 'PENDING_PAYMENT') throw new Error(`Đơn ${order.code} đã thanh toán, không hủy được (ADR-0156)`);
  for (const h of s.holds) if (h.onlineOrderId === order.onlineOrderId && h.status === 'HELD') h.status = 'RELEASED';
  cancelPendingTxns(s, order.onlineOrderId);
  order.status = 'CANCELLED';
  order.cancelledAt = iso(args.at);
  order.cancelReason = args.reason;
  order.cancelledBy = args.by;
}

// ─── Job hệ thống (lười) ───

/** Chạy ST10, ST11, ST12 tới thời điểm `now`; trả true nếu có gì đổi. Mốc ghi vào dòng là mốc job chạy, không phải `now`, nên chạy lại cho cùng kết quả. */
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
if (at <= now) {
        o.status = 'COMPLETED';
        o.completedAt = iso(at);
        changed = true;
      }
    }
  }
  return changed;
}

// ─── Đọc chéo cho mock khác (branchlife, báo cáo) ───

/** Đơn online chưa xong của chi nhánh (chưa COMPLETED / CANCELLED): cho `registerClosureSources` của branchlife. */
export const openOnlineOrderCount = (branchId: number): number =>
  loadFresh().orders.filter((o) => o.branchId === branchId && o.status !== 'COMPLETED' && o.status !== 'CANCELLED').length;

/**
 * Doanh thu của đơn online đã thanh toán (đơn PAID trở đi; đơn đã thanh toán không hủy được, ADR-0156): cho báo cáo UC89 khi report-v1 thêm nguồn ONLINE (ADR-0116 §3.3).
 * Chưa nối vào `allOrderRows()` của sales.mock vì sales-v1 / report-v1 chưa có ONLINE trong enum nguồn và test báo cáo GĐ1 khẳng định tổng số.
 * `goodsAmount` = tiền hàng (vào doanh thu), `shippingFee` báo riêng.
 */
export function onlineRevenueRows() {
  const s = loadFresh();
  return s.orders
    .filter((o) => o.paidAt)
    .map((o) => ({ orderId: o.salesOrderId, code: o.code, branchId: o.branchId, paidAt: o.paidAt!, goodsAmount: o.subtotal, shippingFee: o.shippingFee }));
}

