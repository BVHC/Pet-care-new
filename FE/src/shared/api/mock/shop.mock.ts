// Mock shop-v1 (UC61, 62, 64, 65; docs/api/shop-v1.md #1–19): giỏ hàng, báo giá + đặt đơn online, đơn của tôi, xử lý và giao đơn của lễ tân.
// BE tầng 3 CHƯA cài (08 + yaml đã duyệt ở T54) nên mock không có số đo thật; mọi chỗ hợp đồng im lặng là ASSUMPTION dưới đây (chung cho shop / payment / returns,
// state và nghiệp vụ chéo module ở commerce.store.ts, phần HTTP dùng chung ở commerce.http.ts).
//
//  A1  Thứ tự kiểm: 401 (adapter) → 400 path / query / body SAI KIỂU (MALFORMED_REQUEST, kể cả enum lạ) → 400 VALIDATION_FAILED của BODY (maxLength, trường bắt buộc có
//      @NotNull / @NotBlank; @Valid chạy TRƯỚC @PreAuthorize, đo trên BE thật ở care.mock / cashier.mock) → 403 ACCESS_DENIED vai trò → 404 → 403 phạm vi chi nhánh
//      (ACCESS_DENIED_SCOPE_MISMATCH, message chung) → 400 BR-… → 409 INVALID_STATE_TRANSITION. Đơn của người khác với khách là 404 (ADR-0006), với nhân viên chi nhánh
//      khác là 403 phạm vi sau 404. Lệch có chủ đích so với dòng "400 VALIDATION_FAILED chạy SAU vai trò" của brief: dòng đó đúng cho tham số query (@Positive…),
//      còn body @Valid đã đo là chạy trước vai trò. BR (400) đứng TRƯỚC 409 như 00-method §3.5 (GĐ1 BE đo ngược ở sales / cashier: 409 trước rule).
//  A2  "Thiếu lý do / thiếu vận đơn" mà yaml ghi `400 BR-BH-11` (fail-delivery, cancel-failed, ship, redeliver) là 400 BUSINESS_RULE_VIOLATION sau 404 / phạm vi,
//      trước 409 (như care-v1 C2: DTO không @NotNull); kiểu sai hoặc quá maxLength vẫn là MALFORMED / VALIDATION_FAILED trước vai trò. Riêng khách hủy đơn (#10) yaml
//      không nêu BR nên thiếu `reason` là VALIDATION_FAILED. Số lượng giỏ ngoài 1–99 là BR-BH-07 (yaml), thiếu `quantity` là VALIDATION_FAILED.
//  A3  Quyền (đúng dòng "Quyền:"): /me/cart, /me/online-orders… chỉ CUSTOMER; GET /online-orders và chi tiết: RECEPTIONIST + BRANCH_MANAGER của chi nhánh xử lý;
//      mọi lệnh ghi chỉ RECEPTIONIST (BRANCH_MANAGER chỉ xem → 403). ADMIN, SUPER_MANAGER, VET, CARETAKER không có quyền nào ở đây. Chi nhánh lấy từ `branchIdOf(account.id)`.
//  A4  Giỏ: sản phẩm ngừng kinh doanh bị loại khi xem (vẫn nằm trong bảng); `available` = còn hàng ở ít nhất một chi nhánh ACTIVE (đã trừ phần giữ); PUT cùng sản phẩm
//      là đặt lại số lượng (idempotent); "quá 30 dòng" chỉ chặn dòng MỚI; DELETE sản phẩm không có trong giỏ / không tồn tại vẫn 200. Thuốc kê đơn có thông báo riêng,
//      mọi sản phẩm không phải GOODS (kể cả thuốc không kê đơn, vaccine) cùng BR-BH-07.
//  A5  Địa chỉ: id không tồn tại ở mọi sổ địa chỉ → 404; thuộc sổ của khách khác → 400 BR-BH-08 "địa chỉ không phải của khách" (đúng hai dòng của yaml; mock dò sổ
//      địa chỉ qua route của customer.mock). Chi nhánh không tồn tại → 404; không ACTIVE → 400 BR-BH-08. Thứ tự: 404 địa chỉ, 404 chi nhánh, rồi 400 (địa chỉ của người khác,
//      giỏ rỗng, chi nhánh không ACTIVE, thiếu hàng nêu dòng đầu tiên thiếu). fulfillment-branches: giỏ rỗng → mảng rỗng (yaml không có 400); sắp theo branchId.
//      Trong dữ liệu chi nhánh mẫu chỉ chi nhánh 1 ACTIVE nên mảng chỉ có tối đa 1 phần tử.
//  A6  Báo giá / đặt đơn: phí vận chuyển = `registerShippingFeeSource` (mặc định 30.000 đ, miễn phí từ 500.000 đ), chốt vào đơn lúc đặt (BR-SP-11). Giữ hàng = bảng holds;
//      tồn khả dụng = tồn lô chưa hết hạn trừ phần HELD của mọi đơn. 409 CONCURRENCY_CONFLICT "hàng vừa bị người khác giữ" KHÔNG mô phỏng (mock một luồng): thiếu hàng luôn là
//      400 BR-BH-08. `itemCount` của danh sách = số dòng hàng. Mã đơn `DH-yyMMdd-xxxx`. Mỗi đơn có `salesOrderId` = 7000 + id, KHÔNG nằm trong mock bán hàng GĐ1 (sales-v1 /
//      report-v1 chưa có ONLINE trong enum nguồn; test báo cáo khẳng định tổng): `onlineRevenueRows()` ở commerce.store.ts cho luồng chính nối vào báo cáo sau.
//  A7  Hủy đơn (#10): chưa thanh toán → nhả giữ hàng, hủy giao dịch PENDING; PAID / PROCESSING → hoàn hàng về lô gốc (lô ghi lúc xuất FEFO) và sinh hoàn tiền GATEWAY toàn bộ
//      gồm phí vận chuyển (xem trong returns-v1 #7 của chi nhánh). Đơn mẫu không xuất kho thật nên hủy không hoàn kho. Không gửi thông báo ST20 (ONLINE_ORDER_*) và không ghi audit (BR-QT-15): ngoài phạm vi.
//  A8  Lý do giao thất bại có lưu nhưng yaml `OnlineOrder` KHÔNG có field để trả (03 #14 "DELIVERY_FAILED có lý do") — đề nghị thêm `deliveryFailReason` hoặc dùng `cancelReason`;
//      `OnlineOrderLine.orderLineId` cũng không có trong yaml (returns-v1 cần `order_lines.id`): mock trả `orderLineId` (types/shop.ts ghi rõ).
//  A9  Job hệ thống ST10 / ST11 / ST12 / ST21 chạy lười mỗi request (commerce.store.ts `sweep`): ST10 hủy đơn PENDING_PAYMENT quá hạn + 15 phút; ST12 tự xác nhận đơn DELIVERED
//      sau 7 ngày nếu không có yêu cầu trả hàng đang mở.
import type { Role } from '../../types/auth';
import type { Address } from '../../types/profile';
import type { Cart, OnlineOrder, OnlineOrderStatus, OnlineOrderSummary } from '../../types/shop';
import { FULFILLMENT_DEFAULT_STATUSES, ONLINE_ORDER_STATUSES, SHOP_LIMITS } from '../../types/shop';
import { branchBooking } from './branch.mock';
import { catalogProducts } from './catalog.mock';
import {
  bodyOf,
  enumQuery,
  hasRole,
  isReply,
  malformed,
  me,
  missingParam,
  myBranch,
  newestFirst,
  oldestFirst,
  pageReply,
  paging,
  pathIds,
  stateError,
} from './commerce.http';
import {
  CFG,
  activeBranchIds,
  branchNameOf,
  cancelOrder,
  cartEntries,
  chainAvailable,
  firstShortage,
  isActiveBranch,
  loadFresh,
  placeOrder,
  save,
  shippingQuote,
  totalOf,
  type CartEntry,
  type OnlineOrderRow,
  type State,
} from './commerce.store';
import { allCustomers, customerOfAccount, customerRoutes } from './customer.mock';
import { denied, has, invalid, isText, noContent, notFound, ok, ruleFail, scopeDenied, tooLong, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { typeError } from './public.mock';

// Nối chéo cho luồng chính: `registerShippingFeeSource(() => shippingFeeConfig())` (fees.mock), `openOnlineOrderCount(branchId)` cho `registerClosureSources` của branchlife.
export { onlineRevenueRows, openOnlineOrderCount, registerShippingFeeSource } from './commerce.store';

const CUSTOMER: readonly Role[] = ['CUSTOMER'];
const STAFF_VIEW: readonly Role[] = ['RECEPTIONIST', 'BRANCH_MANAGER'];
const RECEPTION: readonly Role[] = ['RECEPTIONIST'];
const CUSTOMER_CANCELLABLE: readonly OnlineOrderStatus[] = ['PENDING_PAYMENT', 'PAID', 'PROCESSING'];

// ─── Hiển thị ───

function orderView(o: OnlineOrderRow): OnlineOrder {
  return {
    onlineOrderId: o.onlineOrderId,
    salesOrderId: o.salesOrderId,
    code: o.code,
    status: o.status,
    branchId: o.branchId,
    branchName: branchNameOf(o.branchId),
    receiverName: o.receiverName,
    receiverPhone: o.receiverPhone,
    shipAddress: o.shipAddress,
    lines: o.lines,
    subtotal: o.subtotal,
    shippingFee: o.shippingFee,
    total: totalOf(o),
    carrier: o.carrier,
    trackingCode: o.trackingCode,
    redeliveryCount: o.redeliveryCount,
    paymentDueAt: o.paymentDueAt,
    paidAt: o.paidAt,
    shippedAt: o.shippedAt,
    deliveredAt: o.deliveredAt,
    completedAt: o.completedAt,
    cancelledAt: o.cancelledAt,
    cancelReason: o.cancelReason,
    createdAt: o.createdAt,
  };
}

const summaryOf = (o: OnlineOrderRow): OnlineOrderSummary => ({
  onlineOrderId: o.onlineOrderId,
  salesOrderId: o.salesOrderId,
  code: o.code,
  status: o.status,
  branchName: branchNameOf(o.branchId),
  itemCount: o.lines.length,
  total: totalOf(o),
  createdAt: o.createdAt,
});

function cartView(s: State, customerId: number): Cart {
  const entries = cartEntries(s, customerId);
  return {
    items: entries.map(({ product, quantity }) => ({
      productId: product.productId,
      name: product.name,
      unit: product.unit,
      unitPrice: product.price,
      quantity,
      imageUrl: product.imageUrl ?? null,
      available: chainAvailable(s, product.productId) > 0,
    })),
    subtotal: entries.reduce((sum, { product, quantity }) => sum + product.price * quantity, 0),
  };
}

// ─── Dùng chung ───

/** Vai trò CUSTOMER rồi hồ sơ khách của tài khoản (không có hồ sơ → 403 phạm vi); gọi SAU các kiểm tra path / query / body. */
function asCustomer(req: MockRequest, run: (s: State, customerId: number) => MockReply): MockReply {
  if (!hasRole(req, CUSTOMER)) return denied();
  const customerId = customerOfAccount(me(req).id)?.customerId;
  return customerId === undefined ? scopeDenied() : run(loadFresh(), customerId);
}

/** Đơn của khách; đơn của người khác và id không có cùng 404 (ADR-0006). */
function mineOr404(s: State, req: MockRequest, customerId: number): OnlineOrderRow | MockReply {
  const id = Number(req.params.onlineOrderId);
  return s.orders.find((o) => o.onlineOrderId === id && o.customerId === customerId) ?? notFound('đơn hàng online', id);
}

/** Đơn theo id (404) rồi chi nhánh của người gọi (403 phạm vi, BR-QT-03). */
function ofMyBranch(s: State, req: MockRequest): OnlineOrderRow | MockReply {
  const id = Number(req.params.onlineOrderId);
  const o = s.orders.find((x) => x.onlineOrderId === id);
  if (!o) return notFound('đơn hàng online', id);
  return o.branchId === myBranch(req) ? o : scopeDenied();
}

// ─── Giỏ hàng: #1–4 (UC61, BR-BH-07) ───

const getCart: MockHandler = (req) => asCustomer(req, (s, customerId) => ok(cartView(s, customerId)));

/** #2: 400 sai kiểu → 400 thiếu `quantity` → 403 → 404 sản phẩm → 400 BR-BH-07 (số lượng, loại sản phẩm, quá 30 dòng). */
const setCartItem: MockHandler = (req) => {
  const badId = pathIds(req, ['productId']);
  if (badId) return badId;
  const b = bodyOf(req);
  if (!b || (has(b.quantity) && !Number.isInteger(b.quantity))) return malformed();
  const missing = invalid([['quantity', 'Vui lòng nhập số lượng', !has(b.quantity)]]);
  if (missing) return missing;
  return asCustomer(req, (s, customerId) => {
    const productId = Number(req.params.productId);
    const product = catalogProducts().find((p) => p.productId === productId && p.isActive);
    if (!product) return notFound('sản phẩm đang kinh doanh', productId);
    const quantity = b.quantity as number;
    if (quantity < SHOP_LIMITS.minQuantity || quantity > SHOP_LIMITS.maxQuantity) {
      return ruleFail('BR-BH-07', `Số lượng mỗi sản phẩm phải từ ${SHOP_LIMITS.minQuantity} đến ${SHOP_LIMITS.maxQuantity}`);
    }
    if (product.isPrescription) return ruleFail('BR-BH-07', 'Thuốc kê đơn không bán online');
    if (product.productType !== 'GOODS') return ruleFail('BR-BH-07', 'Thuốc và vaccine không bán online, giỏ hàng chỉ nhận hàng hóa');
    const lines = s.carts[String(customerId)] ?? [];
    const existing = lines.find((l) => l.productId === productId);
    if (!existing && cartEntries(s, customerId).length >= SHOP_LIMITS.cartLines) {
      return ruleFail('BR-BH-07', `Giỏ hàng tối đa ${SHOP_LIMITS.cartLines} dòng`);
    }
    if (existing) existing.quantity = quantity;
    else lines.push({ productId, quantity });
    s.carts[String(customerId)] = lines;
    save(s);
    return ok(cartView(s, customerId));
  });
};

const removeCartItem: MockHandler = (req) => {
  const badId = pathIds(req, ['productId']);
  if (badId) return badId;
  return asCustomer(req, (s, customerId) => {
    const productId = Number(req.params.productId);
    s.carts[String(customerId)] = (s.carts[String(customerId)] ?? []).filter((l) => l.productId !== productId);
    save(s);
    return ok(cartView(s, customerId));
  });
};

const clearCart: MockHandler = (req) =>
  asCustomer(req, (s, customerId) => {
    s.carts[String(customerId)] = [];
    save(s);
    return noContent();
  });

// ─── Đặt hàng: #5–7 (UC62, BR-BH-08) ───

type AddressLookup = { address: Address } | 'NOT_OWNED' | 'NOT_FOUND';
const addressBook = (account: MockRequest['account']): Address[] =>
  (customerRoutes['GET /api/me/addresses']({ body: null, account, params: {}, query: {} }).body as { data: Address[] } | undefined)?.data ?? [];

/** Địa chỉ trong sổ của khách (CustomerQueryApi.findAddress); có ở sổ người khác → NOT_OWNED, không ở đâu cả → NOT_FOUND. */
function lookupAddress(account: NonNullable<MockRequest['account']>, addressId: number): AddressLookup {
  const mine = addressBook(account).find((a) => a.addressId === addressId);
  if (mine) return { address: mine };
  const others = allCustomers().filter((c) => c.accountId !== null && c.accountId !== account.id);
  return others.some((c) => addressBook({ ...account, id: c.accountId!, role: 'CUSTOMER' }).some((a) => a.addressId === addressId)) ? 'NOT_OWNED' : 'NOT_FOUND';
}

/** Kiểm dùng chung của báo giá và đặt đơn: 404 địa chỉ / chi nhánh, rồi 400 BR-BH-08. */
function checkout(s: State, req: MockRequest, customerId: number, branchId: number, addressId: number): MockReply | { entries: CartEntry[]; address: Address } {
  const found = lookupAddress(me(req), addressId);
  if (found === 'NOT_FOUND') return notFound('địa chỉ', addressId);
  if (!branchBooking(branchId)) return notFound('chi nhánh', branchId);
  if (found === 'NOT_OWNED') return ruleFail('BR-BH-08', 'Địa chỉ giao hàng không thuộc sổ địa chỉ của bạn');
  const entries = cartEntries(s, customerId);
  if (entries.length === 0) return ruleFail('BR-BH-08', 'Giỏ hàng đang trống');
  if (!isActiveBranch(branchId)) return ruleFail('BR-BH-08', 'Chi nhánh không nhận xử lý đơn online');
  const short = firstShortage(s, branchId, entries);
  if (short) return ruleFail('BR-BH-08', `Chi nhánh không đủ hàng cho ${short.name} (cần ${short.quantity}, còn ${short.available})`);
  return { entries, address: found.address };
}

const listFulfillmentBranches: MockHandler = (req) =>
  asCustomer(req, (s, customerId) => {
    const entries = cartEntries(s, customerId);
    const branches = entries.length === 0 ? [] : activeBranchIds().filter((id) => firstShortage(s, id, entries) === null);
    return ok(branches.sort((a, b) => a - b).map((branchId) => ({ branchId, name: branchNameOf(branchId), address: branchBooking(branchId)!.branch.address })));
  });

/** #6 (ST09): `branchId`, `addressId` bắt buộc (thiếu / sai kiểu → 400 MALFORMED_REQUEST); không giữ hàng, không ghi gì. */
const quoteOnlineOrder: MockHandler = (req) => {
  for (const name of ['branchId', 'addressId']) if (req.query[name] === undefined) return missingParam(name);
  const badQuery = typeError(req.query, ['branchId', 'addressId']);
  if (badQuery) return badQuery;
  return asCustomer(req, (s, customerId) => {
    const branchId = Number(req.query.branchId);
    const addressId = Number(req.query.addressId);
    const checked = checkout(s, req, customerId, branchId, addressId);
    if (isReply(checked)) return checked;
    const subtotal = checked.entries.reduce((sum, { product, quantity }) => sum + product.price * quantity, 0);
    const { shippingFee, freeShippingApplied, total } = shippingQuote(subtotal);
    return ok({ branchId, addressId, subtotal, shippingFee, freeShippingApplied, total });
  });
};

/** #7 Đơn hàng online#1, Order#11: giữ hàng, chốt phí, dọn giỏ trong một lần ghi. */
const placeOnlineOrder: MockHandler = (req) => {
  const b = bodyOf(req);
  if (!b || (has(b.branchId) && !Number.isInteger(b.branchId)) || (has(b.addressId) && !Number.isInteger(b.addressId))) return malformed();
  const missing = invalid([
    ['branchId', 'Vui lòng chọn chi nhánh xử lý', !has(b.branchId)],
    ['addressId', 'Vui lòng chọn địa chỉ giao hàng', !has(b.addressId)],
  ]);
  if (missing) return missing;
  return asCustomer(req, (s, customerId) => {
    const checked = checkout(s, req, customerId, b.branchId as number, b.addressId as number);
    if (isReply(checked)) return checked;
    const { address, entries } = checked;
    const shipAddress = [address.addressLine, address.ward, address.province].filter(Boolean).join(', ');
    const row = placeOrder(
      s,
      { customerId, accountId: me(req).id, branchId: b.branchId as number, address: { receiverName: address.receiverName, receiverPhone: address.receiverPhone, shipAddress }, entries },
      Date.now(),
    );
    save(s);
    return ok(orderView(row), 201, `Đã đặt hàng, vui lòng thanh toán trong ${CFG.paymentWindowHours} giờ`);
  });
};

// ─── Đơn của tôi: #8–11 (UC64, BR-BH-10) ───

const listMyOrders: MockHandler = (req) => {
  const status = enumQuery(req, 'status', ONLINE_ORDER_STATUSES);
  if (isReply(status)) return status;
  const p = paging(req);
  if (isReply(p)) return p;
  return asCustomer(req, (s, customerId) =>
    pageReply(
      s.orders
        .filter((o) => o.customerId === customerId && (status === undefined || o.status === status))
        .sort(newestFirst((o) => o.onlineOrderId))
        .map(summaryOf),
      p,
    ),
  );
};

const getMyOrder: MockHandler = (req) => {
  const badId = pathIds(req, ['onlineOrderId']);
  if (badId) return badId;
  return asCustomer(req, (s, customerId) => {
    const o = mineOr404(s, req, customerId);
    return isReply(o) ? o : ok(orderView(o));
  });
};

/** #10 Đơn hàng online#3 / #13: PENDING_PAYMENT, PAID, PROCESSING → CANCELLED; từ SHIPPED khách không tự hủy (409). */
const cancelMyOrder: MockHandler = (req) => {
  const badId = pathIds(req, ['onlineOrderId']);
  if (badId) return badId;
  const b = bodyOf(req);
  if (!b || (has(b.reason) && typeof b.reason !== 'string')) return malformed();
  const badReason = invalid([
    ['reason', 'Vui lòng nhập lý do hủy đơn', !isText(b.reason)],
    ['reason', `Lý do tối đa ${SHOP_LIMITS.reason} ký tự`, tooLong(b.reason, SHOP_LIMITS.reason)],
  ]);
  if (badReason) return badReason;
  return asCustomer(req, (s, customerId) => {
    const o = mineOr404(s, req, customerId);
    if (isReply(o)) return o;
    if (!CUSTOMER_CANCELLABLE.includes(o.status)) return stateError('đơn hàng online', o.status, 'CANCELLED');
    const paid = o.status !== 'PENDING_PAYMENT';
    cancelOrder(s, o, { reason: (b.reason as string).trim(), by: me(req).id, at: Date.now() });
    save(s);
    return ok(orderView(o), 200, paid ? 'Đã hủy đơn, tiền sẽ được hoàn qua cổng thanh toán' : 'Đã hủy đơn hàng');
  });
};

/** #11 Đơn hàng online#11: DELIVERED → COMPLETED. */
const confirmReceived: MockHandler = (req) => {
  const badId = pathIds(req, ['onlineOrderId']);
  if (badId) return badId;
  return asCustomer(req, (s, customerId) => {
    const o = mineOr404(s, req, customerId);
    if (isReply(o)) return o;
    if (o.status !== 'DELIVERED') return stateError('đơn hàng online', o.status, 'COMPLETED');
    o.status = 'COMPLETED';
    o.completedAt = new Date().toISOString();
    save(s);
    return ok(orderView(o), 200, 'Đã xác nhận nhận hàng');
  });
};

// ─── Xử lý và giao đơn của chi nhánh: #12–19 (UC65, BR-BH-11) ───

/** #12: chi nhánh của người gọi; `status` vắng = PAID + PROCESSING + DELIVERY_FAILED; cũ nhất trước. */
const listOrders: MockHandler = (req) => {
  const status = enumQuery(req, 'status', ONLINE_ORDER_STATUSES);
  if (isReply(status)) return status;
  const p = paging(req);
  if (isReply(p)) return p;
  if (!hasRole(req, STAFF_VIEW)) return denied();
  const wanted = status === undefined ? FULFILLMENT_DEFAULT_STATUSES : [status];
  return pageReply(
    loadFresh()
      .orders.filter((o) => o.branchId === myBranch(req) && wanted.includes(o.status))
      .sort(oldestFirst((o) => o.onlineOrderId))
      .map(summaryOf),
    p,
  );
};

const getOrder: MockHandler = (req) => {
  const badId = pathIds(req, ['onlineOrderId']);
  if (badId) return badId;
  if (!hasRole(req, STAFF_VIEW)) return denied();
  const o = ofMyBranch(loadFresh(), req);
  return isReply(o) ? o : ok(orderView(o));
};

type Body = Record<string, unknown>;
interface Transition {
  from: OnlineOrderStatus[];
  to: OnlineOrderStatus;
  /** Kiểu và độ dài của body: chạy trước vai trò (A1). Có hàm này thì endpoint có body. */
  parse?: (b: Body) => MockReply | null;
  /** BR-BH-11 (400): sau 404 / phạm vi, trước 409 (A2). */
  guard?: (o: OnlineOrderRow, b: Body) => MockReply | null;
  apply: (s: State, o: OnlineOrderRow, b: Body, at: number, by: number) => void;
  message: string;
}

const transition = (t: Transition): MockHandler => (req) => {
  const badId = pathIds(req, ['onlineOrderId']);
  if (badId) return badId;
  const b = t.parse ? bodyOf(req) : {};
  if (!b) return malformed();
  const badBody = t.parse?.(b);
  if (badBody) return badBody;
  if (!hasRole(req, RECEPTION)) return denied();
  const s = loadFresh();
  const o = ofMyBranch(s, req);
  if (isReply(o)) return o;
  const broken = t.guard?.(o, b);
  if (broken) return broken;
  if (!t.from.includes(o.status)) return stateError('đơn hàng online', o.status, t.to);
  t.apply(s, o, b, Date.now(), me(req).id);
  save(s);
  return ok(orderView(o), 200, t.message);
};

const parseReason = (b: Body) => {
  if (has(b.reason) && typeof b.reason !== 'string') return malformed();
  return invalid([['reason', `Lý do tối đa ${SHOP_LIMITS.reason} ký tự`, tooLong(b.reason, SHOP_LIMITS.reason)]]);
};
const parseShip = (b: Body) => {
  if ((has(b.carrier) && typeof b.carrier !== 'string') || (has(b.trackingCode) && typeof b.trackingCode !== 'string')) return malformed();
  return invalid([
    ['carrier', `Đơn vị vận chuyển tối đa ${SHOP_LIMITS.carrier} ký tự`, tooLong(b.carrier, SHOP_LIMITS.carrier)],
    ['trackingCode', `Mã vận đơn tối đa ${SHOP_LIMITS.trackingCode} ký tự`, tooLong(b.trackingCode, SHOP_LIMITS.trackingCode)],
  ]);
};
const guardShip = (_o: OnlineOrderRow, b: Body) =>
  !isText(b.carrier) ? ruleFail('BR-BH-11', 'Vui lòng nhập đơn vị vận chuyển') : !isText(b.trackingCode) ? ruleFail('BR-BH-11', 'Vui lòng nhập mã vận đơn') : null;
const guardReason = (what: string) => (_o: OnlineOrderRow, b: Body) => (isText(b.reason) ? null : ruleFail('BR-BH-11', what));

const startProcessing = transition({
  from: ['PAID'],
  to: 'PROCESSING',
  apply: (_s, o) => {
    o.status = 'PROCESSING';
  },
  message: 'Đã bắt đầu xử lý đơn',
});

const shipOrder = transition({
  from: ['PROCESSING'],
  to: 'SHIPPED',
  parse: parseShip,
  guard: guardShip,
  apply: (_s, o, b, at) => {
    Object.assign(o, { status: 'SHIPPED', carrier: (b.carrier as string).trim(), trackingCode: (b.trackingCode as string).trim(), shippedAt: new Date(at).toISOString() });
  },
  message: 'Đã bàn giao cho đơn vị vận chuyển',
});

const markDelivered = transition({
  from: ['SHIPPED'],
  to: 'DELIVERED',
  apply: (_s, o, _b, at) => {
    Object.assign(o, { status: 'DELIVERED', deliveredAt: new Date(at).toISOString() });
  },
  message: 'Đã cập nhật đã giao, bắt đầu đếm hạn trả hàng',
});

const failDelivery = transition({
  from: ['SHIPPED'],
  to: 'DELIVERY_FAILED',
  parse: parseReason,
  guard: guardReason('Vui lòng nhập lý do giao thất bại'),
  apply: (_s, o, b) => {
    Object.assign(o, { status: 'DELIVERY_FAILED', failReason: (b.reason as string).trim() });
  },
  message: 'Đã ghi nhận giao thất bại',
});

/** Đơn đã giao lại đủ `shop.max_redelivery` lần thì không giao lại nữa (kiểm khi đang DELIVERY_FAILED; trạng thái khác là 409). */
const redeliver = transition({
  from: ['DELIVERY_FAILED'],
  to: 'SHIPPED',
  parse: parseShip,
  guard: (o, b) =>
    guardShip(o, b) ??
    (o.status === 'DELIVERY_FAILED' && o.redeliveryCount >= CFG.maxRedelivery ? ruleFail('BR-BH-11', `Đơn đã giao lại tối đa ${CFG.maxRedelivery} lần, hãy hủy đơn`) : null),
  apply: (_s, o, b, at) => {
    Object.assign(o, {
      status: 'SHIPPED',
      carrier: (b.carrier as string).trim(),
      trackingCode: (b.trackingCode as string).trim(),
      shippedAt: new Date(at).toISOString(),
      redeliveryCount: o.redeliveryCount + 1,
    });
  },
  message: 'Đã chuyển giao lại',
});

const cancelFailed = transition({
  from: ['DELIVERY_FAILED'],
  to: 'CANCELLED',
  parse: parseReason,
  guard: guardReason('Vui lòng nhập lý do hủy đơn'),
  apply: (s, o, b, at, by) => cancelOrder(s, o, { reason: (b.reason as string).trim(), by, at }),
  message: 'Đã hủy đơn, hoàn hàng về kho và sinh yêu cầu hoàn tiền',
});

/** Khóa route: các đường tĩnh (`fulfillment-branches`, `quote`) đứng TRƯỚC `:onlineOrderId` vì mock-api khớp theo thứ tự khai báo. */
export const shopRoutes: Record<string, MockHandler> = {
  'GET /api/me/cart': getCart, // 1
  'PUT /api/me/cart/items/:productId': setCartItem, // 2
  'DELETE /api/me/cart/items/:productId': removeCartItem, // 3
  'DELETE /api/me/cart': clearCart, // 4
  'GET /api/me/online-orders/fulfillment-branches': listFulfillmentBranches, // 5
  'GET /api/me/online-orders/quote': quoteOnlineOrder, // 6
  'POST /api/me/online-orders': placeOnlineOrder, // 7
  'GET /api/me/online-orders': listMyOrders, // 8
  'GET /api/me/online-orders/:onlineOrderId': getMyOrder, // 9
  'POST /api/me/online-orders/:onlineOrderId/cancel': cancelMyOrder, // 10
  'POST /api/me/online-orders/:onlineOrderId/confirm-received': confirmReceived, // 11
  'GET /api/online-orders': listOrders, // 12
  'GET /api/online-orders/:onlineOrderId': getOrder, // 13
  'POST /api/online-orders/:onlineOrderId/start-processing': startProcessing, // 14
  'POST /api/online-orders/:onlineOrderId/ship': shipOrder, // 15
  'POST /api/online-orders/:onlineOrderId/mark-delivered': markDelivered, // 16
  'POST /api/online-orders/:onlineOrderId/fail-delivery': failDelivery, // 17
  'POST /api/online-orders/:onlineOrderId/redeliver': redeliver, // 18
  'POST /api/online-orders/:onlineOrderId/cancel-failed': cancelFailed, // 19
};
