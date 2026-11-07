// Mock returns-v1 (UC68, UC69, ST21; docs/api/returns-v1.md #1–9): yêu cầu trả hàng (Order quầy hoặc đơn online), duyệt / từ chối, hoàn tiền (xác nhận tại quầy, thử lại qua cổng).
// State và job ST21 ở commerce.store.ts (chung với shop / payment); các ASSUMPTION chung (thứ tự kiểm, quyền…) ở đầu shop.mock.ts.
//
//  R1  Quyền: tạo / rút / xác nhận hoàn tiền = RECEPTIONIST; duyệt / từ chối / thử lại = BRANCH_MANAGER; xem danh sách và chi tiết = cả hai, trong chi nhánh của Order. ADMIN,
//      SUPER_MANAGER, VET, CARETAKER, CUSTOMER → 403. Rút yêu cầu chỉ người tạo (`createdBy`): lễ tân khác cùng chi nhánh → 403 ACCESS_DENIED_SCOPE_MISMATCH.
//  R2  `orderId` = `orders.id`: Order quầy (mock bán hàng, đọc qua `ordersByIds`) hoặc `salesOrderId` của đơn online. Quầy: chỉ Order PAID, hạn 7 ngày kể từ `paidAt`; online: chỉ
//      DELIVERED / COMPLETED, hạn 7 ngày kể từ `deliveredAt` (BR-BH-13; quá hạn khi `now − mốc > 7 ngày`). Chỉ dòng GOODS; số lượng ≤ đã mua − (PENDING + APPROVED + REFUNDED).
//      Thứ tự 400 BR-BH-13: thiếu lý do → không có dòng → dòng trùng → Order chưa thanh toán / chưa giao → quá hạn → loại dòng → vượt số lượng; 404 Order rồi 403 phạm vi rồi 404 dòng
//      đi trước. `lines` thiếu / rỗng và `reasonCode` thiếu là BR-BH-13 (yaml); `orderId` thiếu và số lượng ≤ 0 là VALIDATION_FAILED.
//  R3  Duyệt (#5): `restock` bắt buộc. true → nhập về đúng lô đã xuất lúc PAID (đơn online; đơn mẫu không có bản ghi lô nên không đổi kho) hoặc lô FEFO đầu tiên của chi nhánh (Order quầy:
//      mock bán hàng không lưu lô gốc); false → loại bỏ, KHÔNG ghi phiếu điều chỉnh `RETURN_DISCARD` (mock kho không có hàm ghi) — tồn ròng không đổi. Tiền hoàn = Σ(đơn giá snapshot × số lượng),
//      phí vận chuyển không hoàn (A1). Hình thức hoàn = hình thức đã thu: quầy → `payments.method` (CASH / TRANSFER, mặc định CASH nếu không tra được), online → GATEWAY.
//      Duyệt không đổi Order / đơn online (BR-BH-15).
//  R4  Xác nhận hoàn tiền (#8): thứ tự 404 → 403 phạm vi → 400 hoàn tiền GATEWAY (BR-TG-14: yaml gộp câu này dưới BR-TG-01 nhưng BR-TG-01 là "chưa mở ca"; dùng BR-TG-14 để FE không
//      nhầm sang màn mở ca) → 400 BR-TG-01 chưa có ca OPEN của chính mình → 409 không ở REQUESTED. Thành công: SUCCEEDED, gắn ca, `cashier.mock` ghi khoản hoàn vào ca (đối soát ca
//      trừ khoản này), yêu cầu trả hàng → REFUNDED. `note` nhận nhưng `Refund` không có field để trả. Body BẮT BUỘC (yaml `requestBody.required: true`): không gửi body → 400 MALFORMED_REQUEST, `{}` hợp lệ.
//  R5  ST21 mô phỏng lười: hoàn tiền GATEWAY mới tạo thành công sau 1 phút; không mô phỏng lỗi tạm thời / thử lại. Hoàn tiền FAILED chỉ có ở dữ liệu mẫu (#retry: FAILED → REQUESTED,
//      attempts về 0, thành công sau 1 phút). `retry` cho hoàn tiền không phải GATEWAY: 400 BR-TG-14 (yaml không ghi mã rule).
import type { Role } from '../../types/auth';
import type { PaymentMethod } from '../../types/sales';
import { REFUND_METHODS, REFUND_STATUSES, RETURN_REASONS, RETURN_REQUEST_STATUSES, RETURNS_LIMITS } from '../../types/returns';
import type { Refund, RefundMethod, ReturnRequest, ReturnRequestSummary } from '../../types/returns';
import { openShiftOf, paymentMethodOfOrder, recordRefund } from './cashier.mock';

// Nối chéo cho luồng chính: `pendingRefundCount(branchId)` cho `registerClosureSources` của branchlife (hoàn tiền chờ).
export { pendingRefundCount } from './commerce.store';
import {
  bodyOf,
  enumQuery,
  hasRole,
  isObject,
  isReply,
  malformed,
  me,
  myBranch,
  oldestFirst,
  pageReply,
  paging,
  pathIds,
  stateError,
} from './commerce.http';
import {
  CFG,
  createRefund,
  findReturnableOrder,
  iso,
  loadFresh,
  nextId,
  docCode,
  restockUnits,
  returnedQuantity,
  save,
  type RefundRow,
  type ReturnRow,
  type State,
} from './commerce.store';
import { denied, has, invalid, isText, notFound, ok, ruleFail, scopeDenied, tooLong, type MockHandler, type MockReply, type MockRequest } from './envelope';

const RECEPTION: readonly Role[] = ['RECEPTIONIST'];
const MANAGER: readonly Role[] = ['BRANCH_MANAGER'];
const STAFF_VIEW: readonly Role[] = ['RECEPTIONIST', 'BRANCH_MANAGER'];
const DAY_MS = 86_400_000;

// ─── Hiển thị ───

const requestView = (r: ReturnRow): ReturnRequest => ({
  requestId: r.requestId,
  code: r.code,
  orderId: r.orderId,
  branchId: r.branchId,
  status: r.status,
  reasonCode: r.reasonCode,
  reasonNote: r.reasonNote,
  lines: r.lines,
  restock: r.restock,
  refundAmount: r.refundAmount,
  decisionNote: r.decisionNote,
  createdAt: r.createdAt,
  decidedAt: r.decidedAt,
});
const summaryOf = (r: ReturnRow): ReturnRequestSummary => ({
  requestId: r.requestId,
  code: r.code,
  orderId: r.orderId,
  status: r.status,
  reasonCode: r.reasonCode,
  refundAmount: r.refundAmount,
  createdAt: r.createdAt,
});
const refundView = (r: RefundRow): Refund => ({
  refundId: r.refundId,
  code: r.code,
  returnRequestId: r.returnRequestId,
  onlineOrderId: r.onlineOrderId,
  method: r.method,
  amount: r.amount,
  status: r.status,
  attempts: r.attempts,
  failureReason: r.failureReason,
  completedAt: r.completedAt,
  createdAt: r.createdAt,
});

// ─── Dùng chung ───

/** Yêu cầu theo id (404) rồi chi nhánh của Order (403 phạm vi, BR-QT-03). */
function requestOfMyBranch(s: State, req: MockRequest): ReturnRow | MockReply {
  const id = Number(req.params.requestId);
  const r = s.returns.find((x) => x.requestId === id);
  if (!r) return notFound('yêu cầu trả hàng', id);
  return r.branchId === myBranch(req) ? r : scopeDenied();
}
function refundOfMyBranch(s: State, req: MockRequest): RefundRow | MockReply {
  const id = Number(req.params.refundId);
  const r = s.refunds.find((x) => x.refundId === id);
  if (!r) return notFound('hoàn tiền', id);
  return r.branchId === myBranch(req) ? r : scopeDenied();
}

// ─── Yêu cầu trả hàng: #1–6 (UC68, UC69, BR-BH-13, 14) ───

/** #1 Yêu cầu trả hàng#1. */
const createReturnRequest: MockHandler = (req) => {
  const b = bodyOf(req);
  if (!b) return malformed();
  const lines = has(b.lines) ? b.lines : [];
  if (
    (has(b.orderId) && !Number.isInteger(b.orderId)) ||
    !Array.isArray(lines) ||
    lines.some((l) => !isObject(l) || (has(l.orderLineId) && !Number.isInteger(l.orderLineId)) || (has(l.quantity) && !Number.isInteger(l.quantity))) ||
    (has(b.reasonCode) && !RETURN_REASONS.includes(b.reasonCode as never)) ||
    (has(b.reasonNote) && typeof b.reasonNote !== 'string')
  ) {
    return malformed();
  }
  const inputs = lines as { orderLineId?: number; quantity?: number }[];
  const bad = invalid([
    ['orderId', 'Vui lòng chọn Order cần trả hàng', !has(b.orderId)],
    ...inputs.flatMap((l, i): [string, string, boolean][] => [
      [`lines[${i}].orderLineId`, 'Vui lòng chọn dòng cần trả', !has(l.orderLineId)],
      [`lines[${i}].quantity`, 'Số lượng trả phải lớn hơn 0', !has(l.quantity) || (l.quantity as number) < 1],
    ]),
    ['reasonNote', `Ghi chú tối đa ${RETURNS_LIMITS.reasonNote} ký tự`, tooLong(b.reasonNote, RETURNS_LIMITS.reasonNote)],
  ]);
  if (bad) return bad;
  if (!hasRole(req, RECEPTION)) return denied();

  const s = loadFresh();
  const orderId = b.orderId as number;
  const order = findReturnableOrder(s, orderId);
  if (!order) return notFound('đơn hàng', orderId);
  if (order.branchId !== myBranch(req)) return scopeDenied();
  const wanted = inputs.map((l) => ({ orderLineId: l.orderLineId as number, quantity: l.quantity as number }));
  const found = wanted.map((w) => ({ w, line: order.lines.find((l) => l.orderLineId === w.orderLineId) }));
  const missing = found.find((f) => !f.line);
  if (missing) return notFound('dòng đơn hàng', missing.w.orderLineId);

  if (!has(b.reasonCode)) return ruleFail('BR-BH-13', 'Vui lòng chọn lý do trả hàng');
  if (wanted.length === 0) return ruleFail('BR-BH-13', 'Yêu cầu trả hàng cần ít nhất một dòng');
  if (new Set(wanted.map((w) => w.orderLineId)).size !== wanted.length) return ruleFail('BR-BH-13', 'Danh sách dòng trả hàng bị trùng');
  if (!order.eligible) return ruleFail('BR-BH-13', order.kind === 'ONLINE' ? 'Đơn online chưa giao, chưa trả hàng được' : 'Order chưa thanh toán, chưa trả hàng được');
  if (order.baseAt === null || Date.now() - order.baseAt > CFG.returnWindowDays * DAY_MS) {
    return ruleFail('BR-BH-13', `Đã quá hạn trả hàng ${CFG.returnWindowDays} ngày kể từ ${order.kind === 'ONLINE' ? 'lúc giao hàng' : 'lúc thanh toán'}`);
  }
  for (const { w, line } of found) {
    if (line!.lineType !== 'GOODS') return ruleFail('BR-BH-13', `${line!.name}: chỉ trả được hàng hóa, không trả dịch vụ, thuốc, vaccine`);
    const left = line!.quantity - returnedQuantity(s, orderId, w.orderLineId);
    if (w.quantity > left) return ruleFail('BR-BH-13', `${line!.name}: vượt số lượng được trả (còn ${Math.max(left, 0)})`);
  }

  const now = Date.now();
  const row: ReturnRow = {
    requestId: nextId(s, 'return'),
    code: docCode(s, 'TH', now),
    orderId,
    branchId: order.branchId,
    status: 'PENDING',
    reasonCode: b.reasonCode as ReturnRow['reasonCode'],
    reasonNote: isText(b.reasonNote) ? b.reasonNote.trim() : null,
    lines: found.map(({ w, line }) => ({ orderLineId: w.orderLineId, productName: line!.name, unitPrice: line!.unitPrice, quantity: w.quantity })),
    createdBy: me(req).id,
    decidedBy: null,
    decisionNote: null,
    decidedAt: null,
    restock: null,
    refundAmount: null,
    createdAt: iso(now),
  };
  s.returns.push(row);
  save(s);
  return ok(requestView(row), 201, 'Đã tạo yêu cầu trả hàng, chờ quản lý chi nhánh duyệt');
};

/** #2: chi nhánh của người gọi; `status` vắng = PENDING; cũ nhất trước. */
const listReturnRequests: MockHandler = (req) => {
  const status = enumQuery(req, 'status', RETURN_REQUEST_STATUSES);
  if (isReply(status)) return status;
  const p = paging(req);
  if (isReply(p)) return p;
  if (!hasRole(req, STAFF_VIEW)) return denied();
  const wanted = status ?? 'PENDING';
  return pageReply(
    loadFresh()
      .returns.filter((r) => r.branchId === myBranch(req) && r.status === wanted)
      .sort(oldestFirst((r) => r.requestId))
      .map(summaryOf),
    p,
  );
};

const getReturnRequest: MockHandler = (req) => {
  const badId = pathIds(req, ['requestId']);
  if (badId) return badId;
  if (!hasRole(req, STAFF_VIEW)) return denied();
  const r = requestOfMyBranch(loadFresh(), req);
  return isReply(r) ? r : ok(requestView(r));
};

/** #4 Yêu cầu trả hàng#2: chỉ người tạo (R1). */
const cancelReturnRequest: MockHandler = (req) => {
  const badId = pathIds(req, ['requestId']);
  if (badId) return badId;
  if (!hasRole(req, RECEPTION)) return denied();
  const s = loadFresh();
  const r = requestOfMyBranch(s, req);
  if (isReply(r)) return r;
  if (r.createdBy !== me(req).id) return scopeDenied();
  if (r.status !== 'PENDING') return stateError('yêu cầu trả hàng', r.status, 'CANCELLED');
  r.status = 'CANCELLED';
  save(s);
  return ok(requestView(r), 200, 'Đã rút yêu cầu trả hàng');
};

/** Hoàn kho các dòng đã duyệt trả (R3). Đơn online mẫu (không có bản ghi lô) bỏ qua. */
function restockLines(s: State, r: ReturnRow) {
  const online = s.orders.find((o) => o.salesOrderId === r.orderId);
  if (online?.consumed === null) return;
  const order = findReturnableOrder(s, r.orderId);
  for (const l of r.lines) {
    const productId = order?.lines.find((x) => x.orderLineId === l.orderLineId)?.productId;
    if (productId != null) restockUnits(r.branchId, productId, l.quantity, online?.consumed ?? []);
  }
}

/** #5 Yêu cầu trả hàng#3, Hoàn tiền#1: nhập lại lô gốc hoặc loại bỏ, sinh hoàn tiền theo hình thức đã thu, trong một lần ghi. */
const approveReturnRequest: MockHandler = (req) => {
  const badId = pathIds(req, ['requestId']);
  if (badId) return badId;
  const b = bodyOf(req);
  if (!b || (has(b.restock) && typeof b.restock !== 'boolean')) return malformed();
  const missing = invalid([['restock', 'Vui lòng chọn nhập lại kho hoặc loại bỏ', !has(b.restock)]]);
  if (missing) return missing;
  if (!hasRole(req, MANAGER)) return denied();
  const s = loadFresh();
  const r = requestOfMyBranch(s, req);
  if (isReply(r)) return r;
  if (r.status !== 'PENDING') return stateError('yêu cầu trả hàng', r.status, 'APPROVED');
  const now = Date.now();
  if (b.restock === true) restockLines(s, r);
  const amount = r.lines.reduce((sum, l) => sum + l.unitPrice * l.quantity, 0);
  const online = s.orders.some((o) => o.salesOrderId === r.orderId);
  const counterMethod: PaymentMethod = paymentMethodOfOrder(r.orderId) ?? 'CASH';
  const method: RefundMethod = online ? 'GATEWAY' : counterMethod;
  Object.assign(r, { status: 'APPROVED', restock: b.restock, refundAmount: amount, decidedBy: me(req).id, decidedAt: iso(now) });
  createRefund(s, { returnRequestId: r.requestId, branchId: r.branchId, method, amount }, now);
  save(s);
  return ok(requestView(r), 200, 'Đã duyệt yêu cầu trả hàng, đã sinh yêu cầu hoàn tiền');
};

/** #6 Yêu cầu trả hàng#4: thiếu lý do là 400 BR-BH-14 (sau 404 / phạm vi, trước 409). */
const rejectReturnRequest: MockHandler = (req) => {
  const badId = pathIds(req, ['requestId']);
  if (badId) return badId;
  const b = bodyOf(req);
  if (!b || (has(b.reason) && typeof b.reason !== 'string')) return malformed();
  const tooLongReason = invalid([['reason', `Lý do tối đa ${RETURNS_LIMITS.reason} ký tự`, tooLong(b.reason, RETURNS_LIMITS.reason)]]);
  if (tooLongReason) return tooLongReason;
  if (!hasRole(req, MANAGER)) return denied();
  const s = loadFresh();
  const r = requestOfMyBranch(s, req);
  if (isReply(r)) return r;
  if (!isText(b.reason)) return ruleFail('BR-BH-14', 'Vui lòng nhập lý do từ chối');
  if (r.status !== 'PENDING') return stateError('yêu cầu trả hàng', r.status, 'REJECTED');
  Object.assign(r, { status: 'REJECTED', decidedBy: me(req).id, decisionNote: b.reason.trim(), decidedAt: iso(Date.now()) });
  save(s);
  return ok(requestView(r), 200, 'Đã từ chối yêu cầu trả hàng');
};

// ─── Hoàn tiền: #7–9 (UC69, BR-TG-12…14) ───

/** #7: chi nhánh của người gọi; `status` vắng = REQUESTED + FAILED; cũ nhất trước. */
const listRefunds: MockHandler = (req) => {
  const status = enumQuery(req, 'status', REFUND_STATUSES);
  if (isReply(status)) return status;
  const method = enumQuery(req, 'method', REFUND_METHODS);
  if (isReply(method)) return method;
  const p = paging(req);
  if (isReply(p)) return p;
  if (!hasRole(req, STAFF_VIEW)) return denied();
  return pageReply(
    loadFresh()
      .refunds.filter(
        (r) => r.branchId === myBranch(req) && (status === undefined ? r.status !== 'SUCCEEDED' : r.status === status) && (method === undefined || r.method === method),
      )
      .sort(oldestFirst((r) => r.refundId))
      .map(refundView),
    p,
  );
};

/** #8 Hoàn tiền#2, Yêu cầu trả hàng#5: lễ tân có ca OPEN của chính mình (R4). */
const confirmRefund: MockHandler = (req) => {
  const badId = pathIds(req, ['refundId']);
  if (badId) return badId;
  const b = bodyOf(req);
  if (!b || (has(b.note) && typeof b.note !== 'string')) return malformed();
  const tooLongNote = invalid([['note', `Ghi chú tối đa ${RETURNS_LIMITS.note} ký tự`, tooLong(b.note, RETURNS_LIMITS.note)]]);
  if (tooLongNote) return tooLongNote;
  if (!hasRole(req, RECEPTION)) return denied();
  const s = loadFresh();
  const refund = refundOfMyBranch(s, req);
  if (isReply(refund)) return refund;
  if (refund.method === 'GATEWAY') return ruleFail('BR-TG-14', 'Hoàn tiền qua cổng do hệ thống xử lý, không xác nhận tay');
  const shift = openShiftOf(me(req).id);
  if (!shift || shift.branchId !== refund.branchId) return ruleFail('BR-TG-01', 'Bạn chưa mở ca thu ngân');
  if (refund.status !== 'REQUESTED') return stateError('hoàn tiền', refund.status, 'SUCCEEDED');
  Object.assign(refund, { status: 'SUCCEEDED', confirmedBy: me(req).id, cashierShiftId: shift.shiftId, completedAt: iso(Date.now()) });
  const request = s.returns.find((x) => x.requestId === refund.returnRequestId);
  if (request?.status === 'APPROVED') request.status = 'REFUNDED';
  save(s);
  recordRefund(shift.shiftId, refund.method as PaymentMethod, refund.amount, refund.refundId);
  return ok(refundView(refund), 200, 'Đã xác nhận hoàn tiền tại quầy');
};

/** #9 Hoàn tiền#4: FAILED → REQUESTED, chỉ GATEWAY. */
const retryRefund: MockHandler = (req) => {
  const badId = pathIds(req, ['refundId']);
  if (badId) return badId;
  if (!hasRole(req, MANAGER)) return denied();
  const s = loadFresh();
  const refund = refundOfMyBranch(s, req);
  if (isReply(refund)) return refund;
  if (refund.method !== 'GATEWAY') return ruleFail('BR-TG-14', 'Chỉ thử lại được hoàn tiền qua cổng');
  if (refund.status !== 'FAILED') return stateError('hoàn tiền', refund.status, 'REQUESTED');
  Object.assign(refund, { status: 'REQUESTED', attempts: 0, failureReason: null, nextAttemptAt: iso(Date.now() + CFG.refundJobMinutes * 60_000) });
  save(s);
  return ok(refundView(refund), 200, 'Đã yêu cầu hoàn tiền lại qua cổng');
};

export const returnsRoutes: Record<string, MockHandler> = {
  'POST /api/return-requests': createReturnRequest, // 1
  'GET /api/return-requests': listReturnRequests, // 2
  'GET /api/return-requests/:requestId': getReturnRequest, // 3
  'POST /api/return-requests/:requestId/cancel': cancelReturnRequest, // 4
  'POST /api/return-requests/:requestId/approve': approveReturnRequest, // 5
  'POST /api/return-requests/:requestId/reject': rejectReturnRequest, // 6
  'GET /api/refunds': listRefunds, // 7
  'POST /api/refunds/:refundId/confirm': confirmRefund, // 8
  'POST /api/refunds/:refundId/retry': retryRefund, // 9
};
