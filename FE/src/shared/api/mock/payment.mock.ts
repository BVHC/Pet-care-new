// Mock payment-v1 (UC63, ST11, ST14; docs/api/payment-v1.md #1–6): bắt đầu thanh toán đơn online, trạng thái giao dịch, callback của cổng GIẢ LẬP (công khai, ký số),
// đối soát cổng (chỉ SUPER_MANAGER). State và job ST11 ở commerce.store.ts (chung với shop / returns); các ASSUMPTION chung (thứ tự kiểm, quyền…) ở đầu shop.mock.ts.
//
//  P1  Cổng thanh toán là giả lập: `redirectUrl` = `/payment/mock-gateway?code=<mã tham chiếu>&amount=<số tiền>` (trang này do Bước B dựng, FE nội bộ, không phải VNPay);
//      trang đó gọi `POST /public/payment-gateway/callback` với chữ ký tính bằng `gatewaySignature()` (commerce.store.ts). Mã trạng thái cổng theo VNPay: `00` thành công,
//      `24` khách hủy tại cổng, mọi mã khác thất bại. Chữ ký = băm cố định của (khóa mock | mã tham chiếu | mã giao dịch cổng | trạng thái | số tiền).
//  P2  Callback công khai (đã nằm trong PUBLIC_PATH `public/.*` của mock-api, token bỏ qua, handler không đọc `req.account`). Thứ tự: body sai kiểu → 400 MALFORMED_REQUEST →
//      thiếu / quá dài → 400 VALIDATION_FAILED → chữ ký sai → 400 BR-TG-07 (kiểm TRƯỚC khi tra mã tham chiếu, không lộ mã nào có thật, không ghi gì) → mã tham chiếu không có →
//      400 BR-TG-07 → xử lý. Giao dịch PENDING: số tiền lệch → FAILED + mục đối soát AMOUNT_DIFF, KHÔNG đánh dấu đơn đã trả (BR-TG-08); đúng + `00` → SUCCEEDED, đơn → PAID, xuất kho
//      FEFO từ phần giữ; mã khác → FAILED, đơn vẫn PENDING_PAYMENT để thử lại. Giao dịch đã ở trạng thái cuối (SUCCEEDED / FAILED / EXPIRED / CANCELLED): 200 `{received:true}`
//      và KHÔNG đổi gì, kể cả khi kết quả khác (idempotent theo mã tham chiếu; ST14 sẽ bắt lệch ở đối soát).
//  P3  Mục đối soát do callback sinh được gắn vào phiên của NGÀY HÔM NAY (reconDate = hôm nay giờ VN); chưa có phiên thì tạo mới ở trạng thái RUNNING (ST14 sẽ chốt
//      sau 02:00 hôm sau). Hợp đồng im lặng chỗ này. ST14 không mô phỏng: chỉ có dữ liệu mẫu 4 phiên + #4–#6.
//  P4  `pay`: 404 đơn của người khác → 409 đơn không PENDING_PAYMENT → 400 BR-TG-06 quá hạn thanh toán (còn PENDING_PAYMENT vì job ST10 chạy mỗi 15 phút, xem
//      commerce.store.ts) → 409 đã có giao dịch PENDING (INVALID_STATE_TRANSITION, yaml không nêu mã). Giao dịch mới hết hạn sau 15 phút (ST11 lười).
//  P5  resolve (#6): yaml ghi "Idempotent: Có" ở ma trận nhưng 409 "Mục đã được xử lý": theo 409 (INVALID_STATE_TRANSITION), gọi lại không đổi dữ liệu. `note` bắt buộc, ≤ 300.
//  P6  Thiếu tồn thật lúc PAID (kho bị điều chỉnh xuống dưới phần giữ): đơn vẫn PAID, không xuất kho và không có bản ghi lô (hủy / trả hàng sau đó không hoàn kho). BE chưa nêu.
import type { Role } from '../../types/auth';
import { PAYMENT_LIMITS } from '../../types/payment';
import type { PaymentSession, PaymentTransaction, ReconciliationItem, ReconciliationSummary } from '../../types/payment';
import { RECONCILIATION_STATUSES } from '../../types/payment';
import { vnDate } from '../../utils/vn-time';
import { bodyOf, boolQuery, enumQuery, hasRole, isReply, malformed, me, pageReply, paging, pathIds, stateError } from './commerce.http';
import {
  CFG,
  GATEWAY_CANCEL_CODE,
  GATEWAY_SUCCESS_CODE,
  docCode,
  gatewaySignature,
  iso,
  loadFresh,
  markPaid,
  nextId,
  save,
  totalOf,
  type ReconItemRow,
  type ReconRow,
  type State,
  type TxnRow,
} from './commerce.store';
import { customerOfAccount } from './customer.mock';
import { denied, fail, has, invalid, isText, notFound, ok, ruleFail, tooLong, type MockHandler } from './envelope';

export { GATEWAY_CANCEL_CODE, GATEWAY_SUCCESS_CODE, MOCK_GATEWAY_SECRET, gatewaySignature } from './commerce.store';

const CUSTOMER: readonly Role[] = ['CUSTOMER'];
const SUPER: readonly Role[] = ['SUPER_MANAGER'];

const transactionView = (t: TxnRow): PaymentTransaction => ({
  transactionId: t.transactionId,
  onlineOrderId: t.onlineOrderId,
  status: t.status,
  amount: t.amount,
  expiresAt: t.expiresAt,
  paidAt: t.paidAt,
  failureReason: t.failureReason,
});
const reconView = (r: ReconRow): ReconciliationSummary => ({
  reconciliationId: r.reconciliationId,
  reconDate: r.reconDate,
  status: r.status,
  txnCount: r.txnCount,
  mismatchCount: r.mismatchCount,
  finishedAt: r.finishedAt,
});
const itemView = (i: ReconItemRow): ReconciliationItem => ({
  itemId: i.itemId,
  kind: i.kind,
  transactionId: i.transactionId,
  gatewayRef: i.gatewayRef,
  ourAmount: i.ourAmount,
  gatewayAmount: i.gatewayAmount,
  resolved: i.resolved,
  resolvedNote: i.resolvedNote,
  resolvedAt: i.resolvedAt,
});

// ─── Thanh toán của tôi: #1, #2 (UC63, BR-TG-06) ───

/** #1 Giao dịch#1: 404 → 409 trạng thái → 400 BR-TG-06 quá hạn → 409 đã có giao dịch PENDING. */
const payOnlineOrder: MockHandler = (req) => {
  const badId = pathIds(req, ['onlineOrderId']);
  if (badId) return badId;
  if (!hasRole(req, CUSTOMER)) return denied();
  const customerId = customerOfAccount(me(req).id)?.customerId;
  const s = loadFresh();
  const id = Number(req.params.onlineOrderId);
  const order = s.orders.find((o) => o.onlineOrderId === id && o.customerId === customerId);
  if (!order) return notFound('đơn hàng online', id);
  if (order.status !== 'PENDING_PAYMENT') return stateError('đơn hàng online', order.status, 'PAID');
  const now = Date.now();
  if (Date.parse(order.paymentDueAt) < now) return ruleFail('BR-TG-06', 'Đơn hàng đã quá hạn thanh toán, vui lòng đặt lại');
  const pending = s.txns.find((t) => t.onlineOrderId === id && t.status === 'PENDING');
  if (pending) return fail('INVALID_STATE_TRANSITION', `Đơn hàng đang có giao dịch chờ thanh toán ${pending.code}`, 409);
  const txn: TxnRow = {
    transactionId: nextId(s, 'txn'),
    code: docCode(s, 'TT', now),
    onlineOrderId: id,
    amount: totalOf(order),
    status: 'PENDING',
    gatewayTxnId: null,
    expiresAt: iso(now + CFG.txnTtlMinutes * 60_000),
    paidAt: null,
    failureReason: null,
    createdAt: iso(now),
  };
  s.txns.push(txn);
  save(s);
  const session: PaymentSession = {
    transactionId: txn.transactionId,
    code: txn.code,
    amount: txn.amount,
    redirectUrl: `/payment/mock-gateway?code=${txn.code}&amount=${txn.amount}`,
    expiresAt: txn.expiresAt,
  };
  return ok(session, 201, 'Đã tạo giao dịch, chuyển khách sang cổng thanh toán');
};

/** #2: giao dịch của đơn người khác và id không có cùng 404. */
const getMyTransaction: MockHandler = (req) => {
  const badId = pathIds(req, ['transactionId']);
  if (badId) return badId;
  if (!hasRole(req, CUSTOMER)) return denied();
  const customerId = customerOfAccount(me(req).id)?.customerId;
  const s = loadFresh();
  const id = Number(req.params.transactionId);
  const txn = s.txns.find((t) => t.transactionId === id && s.orders.some((o) => o.onlineOrderId === t.onlineOrderId && o.customerId === customerId));
  return txn ? ok(transactionView(txn)) : notFound('giao dịch thanh toán', id);
};

// ─── Callback của cổng: #3 (BR-TG-07, 08, 09) ───

const CODE_MAX = 40;
const GATEWAY_REF_MAX = 100;

/** Phiên đối soát của hôm nay (tạo RUNNING nếu chưa có) để nhận mục lệch do callback (P3). */
function todaysRecon(s: State, now: number): ReconRow {
  const day = vnDate(now);
  let recon = s.recons.find((r) => r.reconDate === day);
  if (!recon) {
    recon = { reconciliationId: nextId(s, 'recon'), reconDate: day, status: 'RUNNING', txnCount: s.txns.filter((t) => vnDate(Date.parse(t.createdAt)) === day).length, mismatchCount: 0, finishedAt: null };
    s.recons.push(recon);
  }
  return recon;
}

const gatewayCallback: MockHandler = (req) => {
  const b = bodyOf(req);
  if (!b) return malformed();
  if (
    (has(b.transactionCode) && typeof b.transactionCode !== 'string') ||
    (has(b.status) && typeof b.status !== 'string') ||
    (has(b.signature) && typeof b.signature !== 'string') ||
    (has(b.gatewayTxnId) && typeof b.gatewayTxnId !== 'string') ||
    (has(b.amount) && typeof b.amount !== 'number') ||
    (has(b.payload) && (typeof b.payload !== 'object' || Array.isArray(b.payload)))
  ) {
    return malformed();
  }
  const bad = invalid([
    ['transactionCode', 'Mã tham chiếu không được để trống', !isText(b.transactionCode)],
    ['transactionCode', `Mã tham chiếu tối đa ${CODE_MAX} ký tự`, tooLong(b.transactionCode, CODE_MAX)],
    ['gatewayTxnId', `Mã giao dịch của cổng tối đa ${GATEWAY_REF_MAX} ký tự`, tooLong(b.gatewayTxnId, GATEWAY_REF_MAX)],
    ['status', 'Trạng thái không được để trống', !isText(b.status)],
    ['amount', 'Số tiền không được để trống', !has(b.amount)],
    ['amount', 'Số tiền phải là số nguyên không âm', has(b.amount) && (!Number.isInteger(b.amount) || (b.amount as number) < 0)],
    ['signature', 'Chữ ký không được để trống', !isText(b.signature)],
  ]);
  if (bad) return bad;
  const code = b.transactionCode as string;
  const amount = b.amount as number;
  const status = b.status as string;
  const gatewayTxnId = (b.gatewayTxnId as string | null | undefined) ?? null;
  if (b.signature !== gatewaySignature({ transactionCode: code, gatewayTxnId, status, amount })) return ruleFail('BR-TG-07', 'Chữ ký của cổng thanh toán không hợp lệ');

  const s = loadFresh();
  const txn = s.txns.find((t) => t.code === code);
  if (!txn) return ruleFail('BR-TG-07', 'Mã tham chiếu giao dịch không tồn tại');
  if (txn.status !== 'PENDING') return ok({ received: true });

  const now = Date.now();
  const order = s.orders.find((o) => o.onlineOrderId === txn.onlineOrderId)!;
  if (amount !== txn.amount) {
    txn.status = 'FAILED';
    txn.failureReason = 'Số tiền cổng báo không khớp số tiền giao dịch';
    txn.gatewayTxnId = gatewayTxnId ?? txn.gatewayTxnId;
    const recon = todaysRecon(s, now);
    recon.mismatchCount += 1;
    s.reconItems.push({
      itemId: nextId(s, 'item'),
      reconciliationId: recon.reconciliationId,
      kind: 'AMOUNT_DIFF',
      transactionId: txn.transactionId,
      gatewayRef: gatewayTxnId ?? txn.code,
      ourAmount: txn.amount,
      gatewayAmount: amount,
      resolved: false,
      resolvedNote: null,
      resolvedAt: null,
      resolvedBy: null,
    });
  } else if (status === GATEWAY_SUCCESS_CODE) {
    txn.status = 'SUCCEEDED';
    txn.paidAt = iso(now);
    txn.gatewayTxnId = gatewayTxnId ?? txn.gatewayTxnId;
    markPaid(s, order, txn.paidAt);
  } else {
    txn.status = 'FAILED';
    txn.failureReason = status === GATEWAY_CANCEL_CODE ? 'Khách hủy giao dịch tại cổng' : `Cổng báo giao dịch không thành công (mã ${status})`;
    txn.gatewayTxnId = gatewayTxnId ?? txn.gatewayTxnId;
  }
  save(s);
  return ok({ received: true });
};

// ─── Đối soát: #4–6 (ST14, BR-TG-10) ───

/** #4: mới nhất trước (`reconDate` giảm dần). */
const listReconciliations: MockHandler = (req) => {
  const status = enumQuery(req, 'status', RECONCILIATION_STATUSES);
  if (isReply(status)) return status;
  const p = paging(req);
  if (isReply(p)) return p;
  if (!hasRole(req, SUPER)) return denied();
  const rows = loadFresh()
    .recons.filter((r) => status === undefined || r.status === status)
    .sort((a, b) => b.reconDate.localeCompare(a.reconDate) || b.reconciliationId - a.reconciliationId);
  return pageReply(rows.map(reconView), p);
};

const listItems: MockHandler = (req) => {
  const badId = pathIds(req, ['reconciliationId']);
  if (badId) return badId;
  const resolved = boolQuery(req, 'resolved');
  if (isReply(resolved)) return resolved;
  const p = paging(req);
  if (isReply(p)) return p;
  if (!hasRole(req, SUPER)) return denied();
  const s = loadFresh();
  const id = Number(req.params.reconciliationId);
  if (!s.recons.some((r) => r.reconciliationId === id)) return notFound('phiên đối soát', id);
  const rows = s.reconItems.filter((i) => i.reconciliationId === id && (resolved === undefined || i.resolved === resolved)).sort((a, b) => a.itemId - b.itemId);
  return pageReply(rows.map(itemView), p);
};

/** #6: chỉ ghi chú, không sửa giao dịch hay đơn (BR-TG-10). */
const resolveItem: MockHandler = (req) => {
  const badId = pathIds(req, ['itemId']);
  if (badId) return badId;
  const b = bodyOf(req);
  if (!b || (has(b.note) && typeof b.note !== 'string')) return malformed();
  const badNote = invalid([
    ['note', 'Vui lòng nhập ghi chú xử lý', !isText(b.note)],
    ['note', `Ghi chú tối đa ${PAYMENT_LIMITS.note} ký tự`, tooLong(b.note, PAYMENT_LIMITS.note)],
  ]);
  if (badNote) return badNote;
  if (!hasRole(req, SUPER)) return denied();
  const s = loadFresh();
  const id = Number(req.params.itemId);
  const item = s.reconItems.find((i) => i.itemId === id);
  if (!item) return notFound('mục đối soát', id);
  if (item.resolved) return fail('INVALID_STATE_TRANSITION', 'Mục lệch đã được xử lý', 409);
  Object.assign(item, { resolved: true, resolvedNote: (b.note as string).trim(), resolvedAt: new Date().toISOString(), resolvedBy: me(req).id });
  save(s);
  return ok(itemView(item), 200, 'Đã ghi chú xử lý mục lệch');
};

export const paymentRoutes: Record<string, MockHandler> = {
  'POST /api/me/online-orders/:onlineOrderId/pay': payOnlineOrder, // 1
  'GET /api/me/payment-transactions/:transactionId': getMyTransaction, // 2
  'POST /api/public/payment-gateway/callback': gatewayCallback, // 3
  'GET /api/payment-reconciliations': listReconciliations, // 4
  'GET /api/payment-reconciliations/:reconciliationId/items': listItems, // 5
  'POST /api/payment-reconciliation-items/:itemId/resolve': resolveItem, // 6
};

