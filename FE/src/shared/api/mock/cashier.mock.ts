// Mock sales-v1 thu tiền và ca thu ngân (docs/api/sales-v1.md #12–21; UC70, UC71, UC72; docs/adr/0064) — T35.
// Chép từ BE (module sales: PaymentController, CashierShiftController, PaymentService, PaymentQueryService, CashierShiftService,
// CashierShiftCloseService, ShiftReconcileService, CashierShiftQueryService). Thứ tự kiểm khi khác 00-method §3.5: đọc JSON và
// Bean Validation của body / tham số chạy TRƯỚC `@PreAuthorize` (400 trước 403) → vai trò 403 → tồn tại 404 → phạm vi 403 →
// trạng thái 409 → rule 400 (ADR-0064 §2.4: 409 trước rule). Hợp đồng lệch BE: xem docs/testcases/T35-fe.md.
// Không mô phỏng: audit (BE ghi ORDER_PAID, CASHIER_SHIFT_RECONCILED), ST13 tự chốt ca (job của BE; dữ liệu mẫu có sẵn một ca đã
// tự chốt), khóa đồng thời. Như BE, không chặn chi nhánh DRAFT (không có rule nào nói vậy, ADR-0064 §3.1).
import type { Role } from '../../types/auth';
import type { CashierShift, CashierShiftStatus, OrderLineType, PaymentMethod, ShortageLine } from '../../types/sales';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { acceptsAfterHours, isOpenAt } from './branch.mock';
import { findCustomerOrGuest } from './customer.mock';
import {
  denied,
  fail,
  has,
  invalid,
  isText,
  notFound,
  ok,
  ruleFail,
  scopeDenied,
  tooLong,
  type Check,
  type MockHandler,
  type MockReply,
  type MockRequest,
} from './envelope';
import { staffNameOf } from './identity-admin.mock';
import { availableQuantity, issueFefo, type StockShortage } from './inventory.mock';
import { markOrdersPaid, ordersByIds, ordersOfPayment, orderTotal, payableOrdersOf, type OrderRow } from './sales.mock';

const RECEPTIONIST: readonly Role[] = ['RECEPTIONIST'];
const CASH_STAFF: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST'];
const SHIFT_STATUSES: readonly CashierShiftStatus[] = ['OPEN', 'CLOSED', 'RECONCILED'];
const MAX_PAGE_SIZE = 100;
const INTEGER = /^-?\d+$/;

// ─── Dữ liệu ───

interface ShiftRow {
  shiftId: number;
  branchId: number;
  cashierId: number;
  isAfterHours: boolean;
  businessDate: string;
  status: CashierShiftStatus;
  openedAt: string;
  closedAt: string | null;
  autoClosed: boolean;
  countedCash: number | null;
  difference: number | null;
  reconcileNote: string | null;
  reconciledBy: number | null;
  reconciledAt: string | null;
}
interface PaymentRow {
  paymentId: number;
  code: string;
  branchId: number;
  customerId: number;
  cashierShiftId: number;
  method: PaymentMethod;
  amount: number;
  receivedBy: number;
  paidAt: string;
}
interface State {
  shiftSeq: number;
  paymentSeq: number;
  /** Bộ đếm mã phiếu thu `PT-yyMM-nnnnn` theo tháng (toàn chuỗi, như `document_counters`). */
  counters: Record<string, number>;
  shifts: ShiftRow[];
  payments: PaymentRow[];
}

/** Lễ tân chi nhánh 2 của dữ liệu mẫu: chưa có tài khoản đăng nhập trong mock nên chỉ có tên để hiển thị. */
const SEED_CASHIERS: Record<number, string> = { 11: 'Vũ Hải Yến' };
const cashierNameOf = (cashierId: number) => staffNameOf(cashierId) ?? SEED_CASHIERS[cashierId] ?? `Lễ tân #${cashierId}`;

const periodOf = (iso: string) => vnDate(Date.parse(iso)).slice(2, 7).replace('-', '');
const codeOf = (period: string, n: number) => `PT-${period}-${String(n).padStart(5, '0')}`;

const sumOf = (rows: PaymentRow[], method: PaymentMethod) => rows.filter((p) => p.method === method).reduce((n, p) => n + p.amount, 0);

/**
 * Ngày tính từ lúc nạp (giờ Việt Nam). Chi nhánh 1: ca #1 ĐÃ ĐỐI SOÁT (3 ngày trước, chứa phiếu #1 của Order #5) · ca #2 TỰ CHỐT chưa
 * có số thực đếm (2 ngày trước, lễ tân #8) · ca #3 ĐÃ CHỐT chờ đối soát, thực đếm lệch (hôm qua). Chi nhánh 2: ca #4 đã chốt.
 * Lễ tân #4 chưa có ca OPEN. Phiếu #2–#8 là phiếu lịch sử không gắn Order trong dữ liệu mẫu bán lẻ nên `orders = []`.
 */
function seed(): State {
  const order5PaidAt = ordersByIds([5])[0]?.paidAt ?? new Date(`${addDays(vnDate(), -3)}T15:00:00+07:00`).toISOString();
  const d1 = vnDate(Date.parse(order5PaidAt));
  const d2 = addDays(vnDate(), -2);
  const d3 = addDays(vnDate(), -1);
  const at = (date: string, hhmm: string) => new Date(`${date}T${hhmm}:00+07:00`).toISOString();

  const counters: Record<string, number> = {};
  let paymentId = 0;
  const payment = (p: Omit<PaymentRow, 'paymentId' | 'code'>): PaymentRow => {
    const period = periodOf(p.paidAt);
    counters[period] = (counters[period] ?? 0) + 1;
    paymentId += 1;
    return { paymentId, code: codeOf(period, counters[period]), ...p };
  };
  const payments = [
    payment({ branchId: 1, customerId: 1, cashierShiftId: 1, method: 'CASH', amount: 108_000, receivedBy: 4, paidAt: order5PaidAt }),
    payment({ branchId: 1, customerId: 3, cashierShiftId: 1, method: 'TRANSFER', amount: 350_000, receivedBy: 4, paidAt: at(d1, '16:10') }),
    payment({ branchId: 1, customerId: 4, cashierShiftId: 2, method: 'CASH', amount: 200_000, receivedBy: 8, paidAt: at(d2, '10:15') }),
    payment({ branchId: 1, customerId: 2, cashierShiftId: 2, method: 'TRANSFER', amount: 80_000, receivedBy: 8, paidAt: at(d2, '14:40') }),
    payment({ branchId: 1, customerId: 3, cashierShiftId: 3, method: 'CASH', amount: 450_000, receivedBy: 4, paidAt: at(d3, '09:30') }),
    payment({ branchId: 1, customerId: 1, cashierShiftId: 3, method: 'CASH', amount: 126_000, receivedBy: 4, paidAt: at(d3, '11:05') }),
    payment({ branchId: 1, customerId: 4, cashierShiftId: 3, method: 'TRANSFER', amount: 300_000, receivedBy: 4, paidAt: at(d3, '15:20') }),
    payment({ branchId: 2, customerId: 1, cashierShiftId: 4, method: 'CASH', amount: 900_000, receivedBy: 11, paidAt: at(d3, '10:00') }),
  ];
  const shift = (r: Pick<ShiftRow, 'shiftId' | 'branchId' | 'cashierId' | 'businessDate' | 'status' | 'openedAt' | 'closedAt'> & Partial<ShiftRow>): ShiftRow => ({
    isAfterHours: false,
    autoClosed: false,
    countedCash: null,
    difference: null,
    reconcileNote: null,
    reconciledBy: null,
    reconciledAt: null,
    ...r,
  });
  const cashOf = (shiftId: number) => sumOf(payments.filter((p) => p.cashierShiftId === shiftId), 'CASH');
  return {
    shiftSeq: 100,
    paymentSeq: 100,
    counters,
    payments,
    shifts: [
      shift({
        shiftId: 1,
        branchId: 1,
        cashierId: 4,
        businessDate: d1,
        status: 'RECONCILED',
        openedAt: at(d1, '09:00'),
        closedAt: at(d1, '18:00'),
        countedCash: 100_000,
        difference: 100_000 - cashOf(1),
        reconcileNote: 'Thiếu 8.000đ, lễ tân xác nhận trả nhầm tiền thừa',
        reconciledBy: 3,
        reconciledAt: at(addDays(d1, 1), '09:00'),
      }),
      shift({ shiftId: 2, branchId: 1, cashierId: 8, businessDate: d2, status: 'CLOSED', openedAt: at(d2, '08:30'), closedAt: at(d2, '19:30'), autoClosed: true }),
      shift({ shiftId: 3, branchId: 1, cashierId: 4, businessDate: d3, status: 'CLOSED', openedAt: at(d3, '08:00'), closedAt: at(d3, '18:45'), countedCash: 570_000 }),
      shift({ shiftId: 4, branchId: 2, cashierId: 11, businessDate: d3, status: 'CLOSED', openedAt: at(d3, '08:00'), closedAt: at(d3, '17:30'), countedCash: 900_000 }),
    ],
  };
}

const STATE_KEY = 'petcare-mock-cashier';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) return JSON.parse(raw) as State;
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  return seed();
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Hiển thị ───

/** `expected*` tính từ phiếu thu của ca (BE chụp lúc chốt; sau khi chốt không thêm phiếu được nên luôn bằng nhau), OPEN thì chưa có. */
function shiftView(s: State, r: ShiftRow): CashierShift {
  const paid = s.payments.filter((p) => p.cashierShiftId === r.shiftId);
  const closed = r.status !== 'OPEN';
  return {
    shiftId: r.shiftId,
    branchId: r.branchId,
    cashierId: r.cashierId,
    cashierName: cashierNameOf(r.cashierId),
    isAfterHours: r.isAfterHours,
    businessDate: r.businessDate,
    status: r.status,
    openedAt: r.openedAt,
    closedAt: r.closedAt,
    autoClosed: r.autoClosed,
    expectedCash: closed ? sumOf(paid, 'CASH') : null,
    expectedTransfer: closed ? sumOf(paid, 'TRANSFER') : null,
    countedCash: r.countedCash,
    difference: r.difference,
    reconcileNote: r.reconcileNote,
    reconciledBy: r.reconciledBy,
    reconciledAt: r.reconciledAt,
    paymentCount: paid.length,
  };
}

const paymentView = (p: PaymentRow) => ({
  paymentId: p.paymentId,
  code: p.code,
  branchId: p.branchId,
  customerId: p.customerId,
  cashierShiftId: p.cashierShiftId,
  method: p.method,
  amount: p.amount,
  receivedBy: p.receivedBy,
  paidAt: p.paidAt,
  orders: ordersOfPayment(p.paymentId),
});

// ─── Dùng chung ───

const me = (req: MockRequest) => req.account!;
const allow = (roles: readonly Role[], h: MockHandler): MockHandler => (req) => (roles.includes(me(req).role) ? h(req) : denied());
const isReply = (x: unknown): x is MockReply => typeof x === 'object' && x !== null && 'status' in x;
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isJsonObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const conflict = (what: string, from: string, to: string) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái ${what} từ ${from} sang ${to}`, 409);

/** Tham số path số nguyên: sai kiểu → 400 trước cả kiểm vai trò (MethodArgumentTypeMismatchException). */
function idParam(req: MockRequest, name: string): number | MockReply {
  const raw = req.params[name];
  return INTEGER.test(raw) ? Number(raw) : badParam(name);
}

// ─── Đọc: #15, #17, #19, #20 ───

const getPayment: MockHandler = (req) => {
  const paymentId = idParam(req, 'paymentId');
  if (isReply(paymentId)) return paymentId;
  if (!CASH_STAFF.includes(me(req).role)) return denied();
  const payment = load().payments.find((p) => p.paymentId === paymentId);
  if (!payment) return notFound('phiếu thu', paymentId);
  return payment.branchId === me(req).branchId ? ok(paymentView(payment)) : scopeDenied();
};

/** #17: ca OPEN của chính người gọi; chưa mở thì `{ open: false, shift: null }`. */
const getMyShift: MockHandler = (req) => {
  const s = load();
  const open = s.shifts.find((x) => x.cashierId === me(req).id && x.status === 'OPEN');
  return ok(open ? { open: true, shift: shiftView(s, open) } : { open: false, shift: null });
};

const isDay = (v: string) => /^\d{4}-\d{2}-\d{2}$/.test(v) && new Date(Date.parse(v)).toISOString().slice(0, 10) === v;

/** #19: tham số sai kiểu → 400 theo thứ tự tham số của controller, rồi vai trò; `size` kẹp 1–100 và `page` âm về 0, không báo lỗi. */
const listShifts: MockHandler = (req) => {
  const { status, businessDate, cashierId } = req.query;
  if (status !== undefined && !SHIFT_STATUSES.includes(status as CashierShiftStatus)) return badParam('status');
  if (businessDate !== undefined && !isDay(businessDate)) return badParam('businessDate');
  if (cashierId !== undefined && !INTEGER.test(cashierId)) return badParam('cashierId');
  if (req.query.page !== undefined && !INTEGER.test(req.query.page)) return badParam('page');
  if (req.query.size !== undefined && !INTEGER.test(req.query.size)) return badParam('size');
  if (me(req).role !== 'BRANCH_MANAGER') return denied();
  const page = Math.max(Number(req.query.page ?? 0), 0);
  const size = Math.min(Math.max(Number(req.query.size ?? 20), 1), MAX_PAGE_SIZE);
  const s = load();
  const list = s.shifts
    .filter(
      (x) =>
        x.branchId === me(req).branchId &&
        (status === undefined || x.status === status) &&
        (businessDate === undefined || x.businessDate === businessDate) &&
        (cashierId === undefined || x.cashierId === Number(cashierId)),
    )
    .sort((a, b) => b.businessDate.localeCompare(a.businessDate) || b.shiftId - a.shiftId);
  return ok({
    content: list.slice(page * size, page * size + size).map((x) => shiftView(s, x)),
    page,
    size,
    totalElements: list.length,
    totalPages: Math.ceil(list.length / size),
  });
};

/** #20: 404 → quản lý cùng chi nhánh (403 khác) / lễ tân chỉ ca của mình (403 ca người khác, kể cả cùng chi nhánh). */
const getShift: MockHandler = (req) => {
  const shiftId = idParam(req, 'shiftId');
  if (isReply(shiftId)) return shiftId;
  if (!CASH_STAFF.includes(me(req).role)) return denied();
  const s = load();
  const row = s.shifts.find((x) => x.shiftId === shiftId);
  if (!row) return notFound('ca thu ngân', shiftId);
  const mine = me(req).role === 'RECEPTIONIST' ? row.cashierId === me(req).id : row.branchId === me(req).branchId;
  return mine ? ok(shiftView(s, row)) : scopeDenied();
};

// ─── Mở ca: #16 ───

/**
 * Bean Validation (`afterHours` @NotNull) → vai trò → đã có ca OPEN 409 → luật giờ (CashierShiftService, BR-TG-05, BR-CN-05):
 * ca ngoài giờ cần cờ chi nhánh rồi phải đang ngoài giờ mở cửa; ca thường phải đang trong giờ mở cửa (không nghỉ trưa, không ngày nghỉ).
 */
const openShift: MockHandler = (req) => {
  if (!isJsonObject(req.body)) return malformed();
  const { afterHours } = req.body;
  if (has(afterHours) && typeof afterHours !== 'boolean') return malformed();
  const bad = invalid([['afterHours', 'Vui lòng chọn loại ca', !has(afterHours)]]);
  if (bad) return bad;
  const caller = me(req);
  if (caller.role !== 'RECEPTIONIST') return denied();
  const s = load();
  if (s.shifts.some((x) => x.cashierId === caller.id && x.status === 'OPEN')) {
    return conflict('ca thu ngân (bạn đã có ca đang mở)', 'OPEN', 'OPEN');
  }
  const now = Date.now();
  const branchId = caller.branchId!;
  if (afterHours === true) {
    if (!acceptsAfterHours(branchId)) return ruleFail('BR-CN-05', 'Chi nhánh không nhận cấp cứu ngoài giờ nên không mở được ca ngoài giờ');
    if (isOpenAt(branchId, now)) return ruleFail('BR-TG-05', 'Đang trong giờ mở cửa: hãy mở ca thường');
  } else if (!isOpenAt(branchId, now)) {
    return ruleFail('BR-TG-05', 'Chỉ mở được ca thường trong giờ mở cửa của chi nhánh');
  }
  const row: ShiftRow = {
    shiftId: ++s.shiftSeq,
    branchId,
    cashierId: caller.id,
    isAfterHours: afterHours === true,
    businessDate: vnDate(now),
    status: 'OPEN',
    openedAt: new Date(now).toISOString(),
    closedAt: null,
    autoClosed: false,
    countedCash: null,
    difference: null,
    reconcileNote: null,
    reconciledBy: null,
    reconciledAt: null,
  };
  s.shifts.push(row);
  save(s);
  return ok(shiftView(s, row), 201, 'Đã mở ca thu ngân');
};

// ─── Thu tiền: #12, #13 ───

const STOCK_LINES: readonly OrderLineType[] = ['GOODS', 'DRUG'];
const MAX_ORDERS_PER_PAYMENT = 20;
const SAME_CUSTOMER = 'Chỉ thu gộp Order của cùng một khách tại cùng chi nhánh';

/** #12: vai trò → khách 404 → PENDING của khách tại chi nhánh của lễ tân, chốt lâu nhất trước. */
const listPayable: MockHandler = (req) => {
  const customerId = idParam(req, 'customerId');
  if (isReply(customerId)) return customerId;
  if (me(req).role !== 'RECEPTIONIST') return denied();
  if (!findCustomerOrGuest(customerId)) return notFound('khách hàng', customerId);
  return ok(payableOrdersOf(customerId, me(req).branchId!));
};

/** Kiểu JSON của `orderIds` (Jackson): không phải mảng, hoặc phần tử không phải số nguyên / null → MALFORMED_REQUEST. */
const malformedOrderIds = (v: unknown) => has(v) && (!Array.isArray(v) || v.some((x) => has(x) && !Number.isInteger(x)));

/** Bean Validation của PaymentPreviewRequest / CreatePaymentRequest: @NotEmpty, @Size(max 20), từng phần tử @NotNull @Positive, không trùng. */
function orderIdsChecks(orderIds: unknown): Check[] {
  const list = Array.isArray(orderIds) ? (orderIds as (number | null)[]) : [];
  return [
    ['orderIds', 'Vui lòng chọn Order cần thu', !has(orderIds) || list.length === 0],
    ['orderIds', `Một phiếu thu tối đa ${MAX_ORDERS_PER_PAYMENT} Order`, list.length > MAX_ORDERS_PER_PAYMENT],
    ...list.flatMap((id, i): Check[] => [
      [`orderIds[${i}]`, 'Mã Order không được trống', !has(id)],
      [`orderIds[${i}]`, 'Mã Order không hợp lệ', has(id) && (id as number) <= 0],
    ]),
    ['distinct', 'Danh sách Order bị trùng', new Set(list).size !== list.length],
  ];
}

/** Order theo id tăng dần: id đầu tiên không có → 404; Order chi nhánh khác → 403 (kiểm cả tập trước mọi rule, như BE). */
function lockOrders(req: MockRequest, orderIds: number[]): OrderRow[] | MockReply {
  const ids = [...orderIds].sort((a, b) => a - b);
  const found = ordersByIds(ids);
  const missing = ids.find((id) => !found.some((o) => o.orderId === id));
  if (missing !== undefined) return notFound('đơn hàng', missing);
  return found.some((o) => o.branchId !== me(req).branchId) ? scopeDenied() : found;
}

/** Dòng hàng / thuốc (không vaccine: đã trừ khi tiêm, BR-BH-04) kèm Order chứa nó, theo orderId rồi lineId. */
const stockLinesOf = (orders: OrderRow[]) =>
  orders
    .flatMap((order) => order.lines.filter((l) => STOCK_LINES.includes(l.lineType)).map((line) => ({ order, line })))
    .sort((a, b) => a.order.orderId - b.order.orderId || a.line.lineId - b.line.lineId);

/** `PaymentLines.shortageLines`: mỗi DÒNG của sản phẩm mà tổng cần (qua mọi Order) lớn hơn tồn khả dụng của chi nhánh. */
function shortageLines(orders: OrderRow[], branchId: number): ShortageLine[] {
  const lines = stockLinesOf(orders);
  const needed = new Map<number, number>();
  for (const { line } of lines) needed.set(line.productId!, (needed.get(line.productId!) ?? 0) + line.quantity);
  const available = new Map([...needed.keys()].map((productId) => [productId, availableQuantity(branchId, productId)]));
  return lines
    .filter(({ line }) => needed.get(line.productId!)! > available.get(line.productId!)!)
    .map(({ order, line }) => ({
      orderId: order.orderId,
      lineId: line.lineId,
      lineType: line.lineType as 'GOODS' | 'DRUG',
      productId: line.productId!,
      productName: line.description,
      requested: line.quantity,
      available: available.get(line.productId!)!,
    }));
}

const totalOf = (orders: OrderRow[]) => orders.reduce((sum, o) => sum + orderTotal(o), 0);

/** #13 (PaymentQueryService.previewPayment): body → vai trò → 404 → 403 chi nhánh → BR-TG-03 → BR-TG-01 (không PENDING); không cần ca, không ghi gì. */
const previewPayment: MockHandler = (req) => {
  if (!isJsonObject(req.body) || malformedOrderIds(req.body.orderIds)) return malformed();
  const bad = invalid(orderIdsChecks(req.body.orderIds));
  if (bad) return bad;
  if (me(req).role !== 'RECEPTIONIST') return denied();
  const found = lockOrders(req, req.body.orderIds as number[]);
  if (isReply(found)) return found;
  // Sau kiểm chi nhánh, mọi Order cùng chi nhánh của người gọi; BR-TG-03 chỉ còn có thể do khác khách.
  if (new Set(found.map((o) => o.customerId)).size > 1) return ruleFail('BR-TG-03', SAME_CUSTOMER);
  if (found.some((o) => o.status !== 'PENDING')) return ruleFail('BR-TG-01', 'Chỉ thu được Order đang chờ thu tiền');
  const branchId = found[0].branchId;
  return ok({ customerId: found[0].customerId, branchId, totalAmount: totalOf(found), shortages: shortageLines(found, branchId) });
};

// ─── Thu tiền: #14 ───

const METHODS: readonly PaymentMethod[] = ['CASH', 'TRANSFER'];

/** Câu BR-BH-04 của PaymentService.shortageMessage: theo SẢN PHẨM (tổng cần qua các Order), tên lấy từ dòng đầu tiên chứa sản phẩm. */
function shortageMessage(orders: OrderRow[], shortages: StockShortage[]): string {
  const names = new Map<number, string>();
  for (const order of orders) {
    for (const line of order.lines) {
      if (line.productId !== null && !names.has(line.productId)) names.set(line.productId, line.description);
    }
  }
  const detail = shortages
    .map((x) => `${names.get(x.productId) ?? `Sản phẩm #${x.productId}`} (cần ${x.requested}, còn ${x.available})`)
    .join(', ');
  return `Không đủ tồn kho cho: ${detail}. Xem trước phiếu thu để biết dòng cần xử lý`;
}

/**
 * #14 (PaymentService.collectPayment): đọc JSON + Bean Validation → vai trò → BR-TG-01 chưa có ca OPEN của mình → 404 → 403 chi nhánh
 * → 409 có Order không PENDING → BR-TG-03 khác khách → BR-TG-02 số tiền ≠ tổng → BR-BH-04 thiếu tồn (không trừ gì) → ghi.
 */
const createPayment: MockHandler = (req) => {
  if (!isJsonObject(req.body)) return malformed();
  const { orderIds, method, amount } = req.body;
  if (malformedOrderIds(orderIds) || (has(method) && !METHODS.includes(method as PaymentMethod)) || (has(amount) && !Number.isInteger(amount))) {
    return malformed();
  }
  const bad = invalid([
    ...orderIdsChecks(orderIds),
    ['method', 'Vui lòng chọn phương thức thanh toán', !has(method)],
    ['amount', 'Vui lòng nhập số tiền thu', !has(amount)],
    ['amount', 'Số tiền thu không được âm', has(amount) && (amount as number) < 0],
  ]);
  if (bad) return bad;
  const caller = me(req);
  if (caller.role !== 'RECEPTIONIST') return denied();
  const s = load();
  const shift = s.shifts.find((x) => x.cashierId === caller.id && x.status === 'OPEN');
  if (!shift) return ruleFail('BR-TG-01', 'Bạn chưa mở ca thu ngân');
  const locked = lockOrders(req, orderIds as number[]);
  if (isReply(locked)) return locked;
  const notPending = locked.find((o) => o.status !== 'PENDING');
  if (notPending) return conflict('đơn hàng (chỉ thu được Order đang chờ thu tiền)', notPending.status, 'PAID');
  if (new Set(locked.map((o) => o.customerId)).size > 1) return ruleFail('BR-TG-03', SAME_CUSTOMER);
  const total = totalOf(locked);
  if (amount !== total) return ruleFail('BR-TG-02', `Số tiền thu (${amount}) khác tổng phải trả (${total})`);
  const branchId = locked[0].branchId;
  const shortages = issueFefo(branchId, stockLinesOf(locked).map(({ line }) => ({ productId: line.productId!, quantity: line.quantity })));
  if (shortages.length) return ruleFail('BR-BH-04', shortageMessage(locked, shortages));

  const paidAt = new Date().toISOString();
  const period = periodOf(paidAt);
  s.counters[period] = (s.counters[period] ?? 0) + 1;
  const row: PaymentRow = {
    paymentId: ++s.paymentSeq,
    code: codeOf(period, s.counters[period]),
    branchId,
    customerId: locked[0].customerId,
    cashierShiftId: shift.shiftId,
    method: method as PaymentMethod,
    amount: total,
    receivedBy: caller.id,
    paidAt,
  };
  s.payments.push(row);
  save(s);
  markOrdersPaid(locked.map((o) => o.orderId), row.paymentId, paidAt);
  return ok(paymentView(row), 201, 'Đã thu tiền');
};

// ─── Chốt ca và đối soát: #18, #21 ───

/**
 * #18 (CashierShiftCloseService): shiftId → body → vai trò → 404 → chủ ca (403 kể cả khác chi nhánh) → 409 không OPEN → ghi.
 * `expected*` tính từ phiếu thu của ca (xem `shiftView`); `difference` để trống tới đối soát (CashierShift.closeByCashier).
 */
const closeShift: MockHandler = (req) => {
  const shiftId = idParam(req, 'shiftId');
  if (isReply(shiftId)) return shiftId;
  if (!isJsonObject(req.body)) return malformed();
  const { countedCash } = req.body;
  if (has(countedCash) && !Number.isInteger(countedCash)) return malformed();
  const bad = invalid([
    ['countedCash', 'Vui lòng nhập số tiền mặt thực đếm', !has(countedCash)],
    ['countedCash', 'Số tiền thực đếm không được âm', has(countedCash) && (countedCash as number) < 0],
  ]);
  if (bad) return bad;
  if (me(req).role !== 'RECEPTIONIST') return denied();
  const s = load();
  const row = s.shifts.find((x) => x.shiftId === shiftId);
  if (!row) return notFound('ca thu ngân', shiftId);
  if (row.cashierId !== me(req).id) return scopeDenied();
  if (row.status !== 'OPEN') return conflict('ca thu ngân', row.status, 'CLOSED');
  Object.assign(row, { status: 'CLOSED', closedAt: new Date().toISOString(), countedCash, autoClosed: false });
  save(s);
  return ok(shiftView(s, row), 200, 'Đã chốt ca thu ngân');
};

/**
 * #21 (ShiftReconcileService): shiftId → body → vai trò BRANCH_MANAGER → 404 → chi nhánh 403 → 409 không CLOSED → BR-TG-05 (ca tự chốt chưa có
 * số thực đếm mà request không gửi `countedCash`) → ghi. `countedCash` gửi lên ghi đè; `difference = thực đếm − tiền mặt hệ thống`.
 */
const reconcileShift: MockHandler = (req) => {
  const shiftId = idParam(req, 'shiftId');
  if (isReply(shiftId)) return shiftId;
  if (!isJsonObject(req.body)) return malformed();
  const { countedCash, note } = req.body;
  if ((has(countedCash) && !Number.isInteger(countedCash)) || (has(note) && typeof note !== 'string')) return malformed();
  const bad = invalid([
    ['countedCash', 'Số tiền thực đếm không được âm', has(countedCash) && (countedCash as number) < 0],
    ['note', 'Vui lòng nhập ghi chú đối soát', !isText(note)],
    ['note', 'Ghi chú tối đa 1000 ký tự', tooLong(note, 1000)],
  ]);
  if (bad) return bad;
  if (me(req).role !== 'BRANCH_MANAGER') return denied();
  const s = load();
  const row = s.shifts.find((x) => x.shiftId === shiftId);
  if (!row) return notFound('ca thu ngân', shiftId);
  if (row.branchId !== me(req).branchId) return scopeDenied();
  if (row.status !== 'CLOSED') return conflict('ca thu ngân (chỉ đối soát được ca đã chốt)', row.status, 'RECONCILED');
  if (!has(countedCash) && row.countedCash === null) {
    return ruleFail('BR-TG-05', 'Ca tự chốt chưa có số tiền thực đếm: vui lòng nhập số tiền khi đối soát');
  }
  if (has(countedCash)) row.countedCash = countedCash as number;
  row.difference = row.countedCash! - shiftView(s, row).expectedCash!;
  Object.assign(row, { status: 'RECONCILED', reconcileNote: (note as string).trim(), reconciledBy: me(req).id, reconciledAt: new Date().toISOString() });
  save(s);
  return ok(shiftView(s, row), 200, 'Đã đối soát ca thu ngân');
};

/** sales-v1 #12–21 (đánh số theo docs/api/sales-v1.md mục A). */
export const cashierRoutes: Record<string, MockHandler> = {
  'GET /api/customers/:customerId/payable-orders': listPayable, // 12
  'POST /api/payments/preview': previewPayment, // 13
  'POST /api/payments': createPayment, // 14
  'GET /api/payments/:paymentId': getPayment, // 15
  'POST /api/cashier-shifts': openShift, // 16
  'GET /api/me/cashier-shift': allow(RECEPTIONIST, getMyShift), // 17
  'POST /api/cashier-shifts/:shiftId/close': closeShift, // 18
  'GET /api/cashier-shifts': listShifts, // 19
  'GET /api/cashier-shifts/:shiftId': getShift, // 20
  'POST /api/cashier-shifts/:shiftId/reconcile': reconcileShift, // 21
};
