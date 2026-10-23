// Mock module kho (docs/api/inventory-v1.md #1–17): nhà cung cấp, phiếu nhập kho (03 §9), tồn theo lô, tồn tối thiểu,
// điều chỉnh tồn. Chi nhánh lấy từ người gọi; nhà cung cấp dùng chung toàn chuỗi. Ghi: BRANCH_MANAGER; xem tồn: A05–A08;
// xem lô: A05, A06. Thứ tự kiểm như BE (00-method §3.5): dữ liệu vào → quyền → tồn tại → rule (400) → trạng thái (409).
// Điểm hợp đồng còn bỏ ngỏ (M1–M8) chọn như docs/testcases/T24-fe.md §8, chờ BE-2 (T18) chốt.
import type { Role } from '../../types/auth';
import type { Product, ProductType } from '../../types/catalog';
import type {
  AdjustmentLine,
  AdjustmentReason,
  InventoryItem,
  ReceiptStatus,
  StockAdjustment,
  StockLot,
  StockReceipt,
  StockReceiptSummary,
  Supplier,
} from '../../types/inventory';
import { addDays, localIsoDate } from '../../utils/branch-schedule';
import { foldVi } from '../../utils/text';
import { catalogProducts } from './catalog.mock';
import {
  blank,
  denied,
  fail,
  has,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  optional,
  ruleFail,
  scopeDenied,
  tooLong,
  type Check,
  type MockHandler,
  type MockReply,
  type MockRequest,
} from './envelope';

const BM: readonly Role[] = ['BRANCH_MANAGER'];
const STOCK_READ: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const LOT_READ: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST'];
const PRODUCT_TYPES: readonly ProductType[] = ['GOODS', 'DRUG', 'VACCINE'];
const STATUSES: readonly ReceiptStatus[] = ['DRAFT', 'CONFIRMED', 'CANCELLED'];
const REASONS: readonly AdjustmentReason[] = ['DAMAGED', 'EXPIRED', 'COUNT_MISMATCH', 'OTHER'];
const SUPPLIER_PHONE = /^0[0-9]{9,10}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

const allow = (roles: readonly Role[], h: MockHandler): MockHandler => (req) => (roles.includes(req.account!.role) ? h(req) : denied());
const isManager = (req: MockRequest) => req.account!.role === 'BRANCH_MANAGER';
const branchOf = (req: MockRequest) => req.account!.branchId!;
const productOf = (productId: number): Product | undefined => catalogProducts().find((p) => p.productId === productId);
const bodyOf = (req: MockRequest) => (req.body ?? {}) as Record<string, unknown>;
const isId = (v: unknown): v is number => Number.isInteger(v) && (v as number) > 0;
const isDate = (v: unknown): v is string => typeof v === 'string' && DATE.test(v) && !Number.isNaN(Date.parse(v));
/** yyMMdd của hôm nay theo giờ máy (người dùng ở Việt Nam), cho mã phiếu (M8). */
const dayCode = (iso: string) => iso.slice(2).replace(/-/g, '');
const pad4 = (n: number) => String(n).padStart(4, '0');

// ─── Dữ liệu ───

interface ItemRow {
  id: number;
  branchId: number;
  productId: number;
  minQuantity: number;
}
interface LotRow {
  id: number;
  itemId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  quantity: number;
  isDefault: boolean;
}
interface LineRow {
  lineId: number;
  productId: number;
  quantity: number;
  unitCost: number;
  lotNumber: string | null;
  expiryDate: string | null;
  stockLotId: number | null;
}
interface ReceiptRow {
  receiptId: number;
  code: string;
  branchId: number;
  supplierId: number;
  receiptDate: string;
  status: ReceiptStatus;
  note: string | null;
  lines: LineRow[];
  createdBy: number;
  confirmedBy: number | null;
  confirmedAt: string | null;
  cancelledBy: number | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}
interface State {
  seq: number;
  suppliers: Supplier[];
  items: ItemRow[];
  lots: LotRow[];
  receipts: ReceiptRow[];
  adjustments: StockAdjustment[];
}

/** Ngày tương đối với hôm nay để "sắp hết hạn / đã hết hạn" đúng ở mọi ngày chạy. Quản lý chi nhánh 1 = tài khoản #3. */
function seed(today = localIsoDate()): State {
  const lot = (id: number, itemId: number, quantity: number, lotNumber: string | null = null, expiryInDays: number | null = null): LotRow => ({
    id,
    itemId,
    lotNumber,
    expiryDate: expiryInDays === null ? null : addDays(today, expiryInDays),
    quantity,
    isDefault: lotNumber === null,
  });
  const line = (lineId: number, productId: number, quantity: number, unitCost: number, lotNumber: string | null = null, expiryInDays: number | null = null, stockLotId: number | null = null): LineRow => ({
    lineId,
    productId,
    quantity,
    unitCost,
    lotNumber,
    expiryDate: expiryInDays === null ? null : addDays(today, expiryInDays),
    stockLotId,
  });
  const receipt = (r: Pick<ReceiptRow, 'receiptId' | 'supplierId' | 'receiptDate' | 'status' | 'lines'> & Partial<ReceiptRow>): ReceiptRow => ({
    code: `PN-${dayCode(r.receiptDate)}-${pad4(r.receiptId)}`,
    branchId: 1,
    note: null,
    createdBy: 3,
    confirmedBy: null,
    confirmedAt: null,
    cancelledBy: null,
    cancelledAt: null,
    cancelReason: null,
    ...r,
  });
  return {
    seq: 100,
    suppliers: [
      { supplierId: 1, name: 'Công ty Royal Canin Việt Nam', phone: '02838123456', address: 'KCN Tân Uyên, Bình Dương', isActive: true },
      { supplierId: 2, name: 'Dược phẩm Thú y Hanvet', phone: '02438765432', address: '88 Trường Chinh, Hà Nội', isActive: true },
      { supplierId: 3, name: 'Pet Mart phân phối', phone: '0912000111', address: null, isActive: false },
      { supplierId: 4, name: 'Virbac Việt Nam', phone: '02839112233', address: 'Quận 7, TP.HCM', isActive: true },
    ],
    items: [
      { id: 1, branchId: 1, productId: 1, minQuantity: 5 },
      { id: 2, branchId: 1, productId: 2, minQuantity: 20 },
      { id: 3, branchId: 1, productId: 3, minQuantity: 100 },
      { id: 4, branchId: 1, productId: 5, minQuantity: 10 },
      { id: 5, branchId: 1, productId: 6, minQuantity: 5 },
      { id: 6, branchId: 2, productId: 1, minQuantity: 0 },
    ],
    lots: [
      lot(11, 1, 12),
      lot(12, 2, 3),
      lot(13, 3, 200, 'AMX2405', 20),
      lot(14, 3, 500, 'AMX2501', 400),
      lot(15, 4, 15, 'RB2401', 260),
      lot(16, 4, 2, 'RB2309', -16),
      lot(17, 5, 4, 'VG2402', 160),
      lot(18, 6, 7),
    ],
    receipts: [
      // Đã nhập kho; lô RB2401 nhập 18, đã tiêm 3 nên không hủy được (BR-KO-04).
      receipt({
        receiptId: 1,
        supplierId: 2,
        receiptDate: addDays(today, -30),
        status: 'CONFIRMED',
        lines: [line(21, 3, 500, 5_000, 'AMX2501', 400, 14), line(22, 5, 18, 110_000, 'RB2401', 260, 15)],
        confirmedBy: 3,
        confirmedAt: `${addDays(today, -30)}T09:30:00+07:00`,
      }),
      // Nháp: dòng vaccine chưa có số lô / hạn dùng nên chưa xác nhận được (BR-KO-03).
      receipt({
        receiptId: 2,
        supplierId: 1,
        receiptDate: today,
        status: 'DRAFT',
        note: 'Hàng về đợt 2',
        lines: [line(23, 1, 10, 380_000), line(24, 6, 10, 140_000)],
      }),
      receipt({
        receiptId: 3,
        supplierId: 2,
        receiptDate: addDays(today, -10),
        status: 'CANCELLED',
        lines: [line(25, 4, 20, 90_000, 'VT2410', 300)],
        cancelledBy: 3,
        cancelledAt: `${addDays(today, -10)}T15:00:00+07:00`,
        cancelReason: 'Nhập nhầm nhà cung cấp',
      }),
    ],
    adjustments: [
      {
        adjustmentId: 1,
        code: `DC-${dayCode(addDays(today, -5))}-0001`,
        branchId: 1,
        reason: 'DAMAGED',
        note: 'Rách bao khi bốc xếp',
        lines: [{ stockLotId: 11, productId: 1, productName: 'Hạt Royal Canin Mini Adult 2kg', lotNumber: null, quantityDelta: -1, balanceAfter: 12 }],
        createdBy: 3,
        createdAt: `${addDays(today, -5)}T10:00:00+07:00`,
      },
    ],
  };
}

const STATE_KEY = 'petcare-mock-inventory';

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

function paged<T>(list: T[], query: Record<string, string>) {
  const page = Math.max(Number(query.page ?? 0) || 0, 0);
  const size = Math.min(Math.max(Number(query.size ?? 20) || 20, 1), 100);
  return { content: list.slice(page * size, page * size + size), page, size, totalElements: list.length, totalPages: Math.ceil(list.length / size) };
}

// ─── Tồn kho (BR-KO-01, 07) ───

/** M4: lô hết hạn khi hạn dùng trước hôm nay; lô mặc định không hết hạn. */
const isExpired = (lot: LotRow, today: string) => lot.expiryDate !== null && lot.expiryDate < today;
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
const lotsOf = (s: State, itemId: number) => s.lots.filter((l) => l.itemId === itemId);
const itemOf = (s: State, branchId: number, productId: number) => s.items.find((i) => i.branchId === branchId && i.productId === productId);

function itemView(s: State, p: Product, item: ItemRow | undefined, today: string): InventoryItem {
  const lots = item ? lotsOf(s, item.id) : [];
  const total = sum(lots.map((l) => l.quantity));
  const expired = sum(lots.filter((l) => isExpired(l, today)).map((l) => l.quantity));
  const minQuantity = item?.minQuantity ?? 0;
  const nearest = lots.filter((l) => l.quantity > 0 && l.expiryDate !== null && !isExpired(l, today)).map((l) => l.expiryDate!).sort()[0] ?? null;
  return {
    productId: p.productId,
    productName: p.name,
    sku: p.sku,
    unit: p.unit,
    productType: p.productType,
    tracksExpiry: p.tracksExpiry,
    totalQuantity: total,
    availableQuantity: total - expired,
    minQuantity,
    belowMin: total - expired < minQuantity,
    nearestExpiryDate: nearest,
    expiredQuantity: expired,
  };
}

/** Dòng tồn của chi nhánh × sản phẩm; tạo nếu chưa có (M3, và khi xác nhận phiếu nhập). */
function itemFor(s: State, branchId: number, productId: number): ItemRow {
  let item = itemOf(s, branchId, productId);
  if (!item) {
    item = { id: ++s.seq, branchId, productId, minQuantity: 0 };
    s.items.push(item);
  }
  return item;
}

/** Tồn khả dụng (lô chưa hết hạn, BR-KO-01) của sản phẩm tại chi nhánh, như StockQueryApi.availableQuantity — cho mock bán hàng. */
export function availableQuantity(branchId: number, productId: number): number {
  const s = load();
  const item = itemOf(s, branchId, productId);
  const today = localIsoDate();
  return item ? sum(lotsOf(s, item.id).filter((l) => !isExpired(l, today)).map((l) => l.quantity)) : 0;
}

/** M1: chỉ liệt kê sản phẩm đã có dòng tồn ở chi nhánh. */
const listInventory: MockHandler = (req) => {
  const { q, productType, belowMin, expiringWithinDays } = req.query;
  if (productType && !PRODUCT_TYPES.includes(productType as ProductType)) return fail('VALIDATION_FAILED', 'productType: Loại sản phẩm không hợp lệ', 400);
  const within = expiringWithinDays === undefined ? null : Number(expiringWithinDays);
  if (within !== null && !(Number.isInteger(within) && within >= 0)) {
    return fail('VALIDATION_FAILED', 'expiringWithinDays: Số ngày phải là số nguyên ≥ 0', 400);
  }
  const s = load();
  const today = localIsoDate();
  const limit = within === null ? null : addDays(today, within);
  const needle = q ? foldVi(q.trim()) : '';
  const rows = s.items
    .filter((i) => i.branchId === branchOf(req))
    .flatMap((item) => {
      const p = productOf(item.productId);
      return p ? [{ item, p, view: itemView(s, p, item, today) }] : [];
    })
    .filter(({ item, p, view }) => {
      if (needle && !foldVi(p.name).includes(needle) && !foldVi(p.sku).includes(needle)) return false;
      if (productType && p.productType !== productType) return false;
      if (belowMin === 'true' && !view.belowMin) return false;
      if (belowMin === 'false' && view.belowMin) return false;
      // M7: có lô chưa hết hạn, còn hàng, hạn trong N ngày tới.
      if (limit !== null && !lotsOf(s, item.id).some((l) => l.quantity > 0 && l.expiryDate !== null && !isExpired(l, today) && l.expiryDate <= limit)) {
        return false;
      }
      return true;
    })
    .map(({ view }) => view)
    .sort((a, b) => foldVi(a.productName).localeCompare(foldVi(b.productName)));
  return ok(paged(rows, req.query));
};

/** M2: sản phẩm có trong danh mục nhưng chưa có dòng tồn → tồn 0; sản phẩm không tồn tại → 404. */
const getInventoryItem: MockHandler = (req) => {
  const p = productOf(Number(req.params.productId));
  if (!p) return notFound('sản phẩm', req.params.productId);
  const s = load();
  return ok(itemView(s, p, itemOf(s, branchOf(req), p.productId), localIsoDate()));
};

/** M5: mọi lô kể cả đã hết số lượng; hạn dùng tăng dần (thứ tự FEFO), lô mặc định cuối. */
const listLots: MockHandler = (req) => {
  const p = productOf(Number(req.params.productId));
  if (!p) return notFound('sản phẩm', req.params.productId);
  const s = load();
  const item = itemOf(s, branchOf(req), p.productId);
  const today = localIsoDate();
  const lots: StockLot[] = (item ? lotsOf(s, item.id) : [])
    .sort((a, b) => (a.expiryDate ?? '9999-12-31').localeCompare(b.expiryDate ?? '9999-12-31') || a.id - b.id)
    .map((l) => ({ stockLotId: l.id, lotNumber: l.lotNumber, expiryDate: l.expiryDate, quantity: l.quantity, isDefault: l.isDefault, expired: isExpired(l, today) }));
  return ok(lots);
};

const setMinQuantity: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid([['minQuantity', 'Tồn tối thiểu phải là số nguyên ≥ 0', !(Number.isInteger(b.minQuantity) && (b.minQuantity as number) >= 0)]]);
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const p = productOf(Number(req.params.productId));
  if (!p) return notFound('sản phẩm', req.params.productId);
  const s = load();
  const item = itemFor(s, branchOf(req), p.productId);
  item.minQuantity = b.minQuantity as number;
  save(s);
  return ok(itemView(s, p, item, localIsoDate()), 200, 'Đã đặt tồn tối thiểu');
};

// ─── Nhà cung cấp (UC73, BR-KO-02) ───

const supplierChecks = (b: Record<string, unknown>, creating: boolean): Check[] => [
  ['name', 'Vui lòng nhập tên nhà cung cấp', creating ? !isText(b.name) : blank(b.name)],
  ['name', 'Tên nhà cung cấp tối đa 200 ký tự', tooLong(b.name, 200)],
  ['phone', 'Vui lòng nhập số điện thoại', creating && !isText(b.phone)],
  ['phone', 'Số điện thoại phải bắt đầu bằng 0, gồm 10–11 chữ số', isText(b.phone) && !SUPPLIER_PHONE.test(String(b.phone).trim())],
  ['address', 'Địa chỉ tối đa 300 ký tự', tooLong(b.address, 300)],
  ['isActive', 'isActive phải là true hoặc false', has(b.isActive) && typeof b.isActive !== 'boolean'],
];

const listSuppliers: MockHandler = (req) => {
  const { isActive, q } = req.query;
  const needle = q ? foldVi(q.trim()) : '';
  const list = load().suppliers.filter(
    (sp) =>
      (isActive === undefined || String(sp.isActive) === isActive) &&
      (!needle || foldVi(sp.name).includes(needle) || sp.phone.includes(q!.trim())),
  );
  return ok(list);
};

const createSupplier: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(supplierChecks(b, true));
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const s = load();
  const created: Supplier = {
    supplierId: Math.max(0, ...s.suppliers.map((sp) => sp.supplierId)) + 1,
    name: String(b.name).trim(),
    phone: String(b.phone).trim(),
    address: optional(b.address),
    isActive: true,
  };
  s.suppliers.push(created);
  save(s);
  return ok(created, 201, 'Đã thêm nhà cung cấp');
};

const updateSupplier: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(supplierChecks(b, false));
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const s = load();
  const sp = s.suppliers.find((x) => x.supplierId === Number(req.params.supplierId));
  if (!sp) return notFound('nhà cung cấp', req.params.supplierId);
  if (has(b.name)) sp.name = String(b.name).trim();
  if (has(b.phone)) sp.phone = String(b.phone).trim();
  if (has(b.address)) sp.address = optional(b.address);
  if (has(b.isActive)) sp.isActive = b.isActive as boolean;
  save(s);
  return ok(sp, 200, 'Đã cập nhật nhà cung cấp');
};

const deleteSupplier: MockHandler = (req) => {
  if (!isManager(req)) return denied();
  const s = load();
  const id = Number(req.params.supplierId);
  if (!s.suppliers.some((sp) => sp.supplierId === id)) return notFound('nhà cung cấp', id);
  if (s.receipts.some((r) => r.supplierId === id)) {
    return ruleFail('BR-KO-02', 'Nhà cung cấp đã có phiếu nhập, chỉ chuyển ngừng hợp tác được');
  }
  s.suppliers = s.suppliers.filter((sp) => sp.supplierId !== id);
  save(s);
  return noContent();
};

// ─── Phiếu nhập kho (UC74, BR-KO-02, 03, 04; 03 §9) ───

function receiptOf(s: State, r: ReceiptRow): StockReceipt {
  return {
    receiptId: r.receiptId,
    code: r.code,
    branchId: r.branchId,
    supplierId: r.supplierId,
    supplierName: s.suppliers.find((sp) => sp.supplierId === r.supplierId)?.name ?? '',
    receiptDate: r.receiptDate,
    status: r.status,
    note: r.note,
    lines: r.lines.map((l) => ({ ...l, productName: productOf(l.productId)?.name ?? '' })),
    createdBy: r.createdBy,
    confirmedBy: r.confirmedBy,
    confirmedAt: r.confirmedAt,
    cancelledBy: r.cancelledBy,
    cancelledAt: r.cancelledAt,
    cancelReason: r.cancelReason,
  };
}

const summaryOf = (s: State, r: ReceiptRow): StockReceiptSummary => ({
  receiptId: r.receiptId,
  code: r.code,
  supplierName: s.suppliers.find((sp) => sp.supplierId === r.supplierId)?.name ?? '',
  receiptDate: r.receiptDate,
  status: r.status,
  lineCount: r.lines.length,
  totalCost: sum(r.lines.map((l) => l.quantity * l.unitCost)),
});

/**
 * M6: lưu nháp kiểm kiểu dữ liệu và hai ràng buộc cột (số lượng > 0, giá nhập ≥ 0 — 05 `stock_receipt_lines`);
 * số lô, hạn dùng, có ít nhất một dòng kiểm khi xác nhận (BR-KO-03).
 */
function receiptChecks(b: Record<string, unknown>): Check[] {
  const lines = Array.isArray(b.lines) ? (b.lines as Record<string, unknown>[]) : null;
  const checks: Check[] = [
    ['supplierId', 'Vui lòng chọn nhà cung cấp', !isId(b.supplierId)],
    ['receiptDate', 'Ngày nhập không hợp lệ', !isDate(b.receiptDate)],
    ['lines', 'Danh sách dòng không hợp lệ', lines === null],
  ];
  (lines ?? []).forEach((l, i) => {
    checks.push(
      [`lines[${i}].productId`, 'Vui lòng chọn sản phẩm', !isId(l?.productId)],
      [`lines[${i}].quantity`, 'Số lượng phải là số nguyên lớn hơn 0', !isId(l?.quantity)],
      [`lines[${i}].unitCost`, 'Giá nhập phải là số nguyên ≥ 0', !(Number.isInteger(l?.unitCost) && (l.unitCost as number) >= 0)],
      [`lines[${i}].lotNumber`, 'Số lô tối đa 50 ký tự', tooLong(l?.lotNumber, 50)],
      [`lines[${i}].expiryDate`, 'Hạn dùng không hợp lệ', isText(l?.expiryDate) && !isDate(l.expiryDate)],
    );
  });
  checks.push(['note', 'Ghi chú tối đa 1000 ký tự', tooLong(b.note, 1000)]);
  return checks;
}

/** Sản phẩm lạ → 404; nhà cung cấp lạ → 404, ngừng hợp tác → BR-KO-02. */
function receiptRefs(s: State, b: Record<string, unknown>): MockReply | null {
  const sp = s.suppliers.find((x) => x.supplierId === b.supplierId);
  if (!sp) return notFound('nhà cung cấp', String(b.supplierId));
  for (const l of b.lines as Record<string, unknown>[]) {
    if (!productOf(l.productId as number)) return notFound('sản phẩm', String(l.productId));
  }
  if (!sp.isActive) return ruleFail('BR-KO-02', `Nhà cung cấp ${sp.name} đã ngừng hợp tác`);
  return null;
}

function linesOf(s: State, b: Record<string, unknown>): LineRow[] {
  return (b.lines as Record<string, unknown>[]).map((l) => ({
    lineId: ++s.seq,
    productId: l.productId as number,
    quantity: l.quantity as number,
    unitCost: l.unitCost as number,
    lotNumber: optional(l.lotNumber),
    expiryDate: optional(l.expiryDate),
    stockLotId: null,
  }));
}

/** Phiếu của chi nhánh người gọi; chi nhánh khác → 403 (phạm vi chi nhánh). */
function receiptFor(req: MockRequest, s: State): ReceiptRow | MockReply {
  const r = s.receipts.find((x) => x.receiptId === Number(req.params.receiptId));
  if (!r) return notFound('phiếu nhập', req.params.receiptId);
  if (r.branchId !== branchOf(req)) return scopeDenied();
  return r;
}

const listReceipts: MockHandler = (req) => {
  const { status, supplierId, from, to } = req.query;
  if (status && !STATUSES.includes(status as ReceiptStatus)) return fail('VALIDATION_FAILED', 'status: Trạng thái không hợp lệ', 400);
  if ((from && !isDate(from)) || (to && !isDate(to))) return fail('VALIDATION_FAILED', 'from: Ngày không hợp lệ', 400);
  const s = load();
  const list = s.receipts
    .filter(
      (r) =>
        r.branchId === branchOf(req) &&
        (!status || r.status === status) &&
        (!supplierId || r.supplierId === Number(supplierId)) &&
        (!from || r.receiptDate >= from) &&
        (!to || r.receiptDate <= to),
    )
    .sort((a, b) => b.receiptDate.localeCompare(a.receiptDate) || b.receiptId - a.receiptId)
    .map((r) => summaryOf(s, r));
  return ok(paged(list, req.query));
};

const createReceipt: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(receiptChecks(b));
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const s = load();
  const refused = receiptRefs(s, b);
  if (refused) return refused;
  // Mã liên tục theo số phiếu, không xóa phiếu (BR-KO-03, M8).
  const receiptId = s.receipts.length + 1;
  const created: ReceiptRow = {
    receiptId,
    code: `PN-${dayCode(localIsoDate())}-${pad4(receiptId)}`,
    branchId: branchOf(req),
    supplierId: b.supplierId as number,
    receiptDate: b.receiptDate as string,
    status: 'DRAFT',
    note: optional(b.note),
    lines: linesOf(s, b),
    createdBy: req.account!.id,
    confirmedBy: null,
    confirmedAt: null,
    cancelledBy: null,
    cancelledAt: null,
    cancelReason: null,
  };
  s.receipts.push(created);
  save(s);
  return ok(receiptOf(s, created), 201, 'Đã lưu phiếu nhập nháp');
};

const getReceipt: MockHandler = (req) => {
  const s = load();
  const r = receiptFor(req, s);
  return 'receiptId' in r ? ok(receiptOf(s, r)) : r;
};

/** Sửa phiếu nháp = thay toàn bộ phiếu kể cả các dòng (A2). */
const updateReceipt: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(receiptChecks(b));
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const s = load();
  const r = receiptFor(req, s);
  if (!('receiptId' in r)) return r;
  const refused = receiptRefs(s, b);
  if (refused) return refused;
  if (r.status !== 'DRAFT') return fail('INVALID_STATE_TRANSITION', 'Phiếu nhập không còn ở trạng thái nháp, không sửa được', 409);
  r.supplierId = b.supplierId as number;
  r.receiptDate = b.receiptDate as string;
  r.note = optional(b.note);
  r.lines = linesOf(s, b);
  save(s);
  return ok(receiptOf(s, r), 200, 'Đã lưu phiếu nhập nháp');
};

/** BR-KO-03 lúc xác nhận; trả câu báo đầu tiên hoặc null. */
function confirmProblem(r: ReceiptRow): string | null {
  if (r.lines.length === 0) return 'Phiếu nhập chưa có dòng nào';
  for (const l of r.lines) {
    const p = productOf(l.productId)!;
    if (!(l.quantity > 0)) return `${p.name}: số lượng phải lớn hơn 0`;
    if (p.tracksExpiry && (!l.lotNumber || !l.expiryDate)) return `${p.name}: sản phẩm quản lý hạn dùng phải có số lô và hạn dùng`;
    if (p.tracksExpiry && l.expiryDate! <= r.receiptDate) return `${p.name}: hạn dùng phải sau ngày nhập`;
  }
  return null;
}

/** Lô nhận hàng: không quản lý hạn → lô mặc định; cùng số lô + hạn → cộng vào lô cũ ([ERD] uq_stock_lots_lot). */
function lotForLine(s: State, item: ItemRow, line: LineRow, tracksExpiry: boolean): LotRow {
  const found = s.lots.find(
    (l) => l.itemId === item.id && (tracksExpiry ? !l.isDefault && l.lotNumber === line.lotNumber && l.expiryDate === line.expiryDate : l.isDefault),
  );
  if (found) return found;
  const lot: LotRow = {
    id: ++s.seq,
    itemId: item.id,
    lotNumber: tracksExpiry ? line.lotNumber : null,
    expiryDate: tracksExpiry ? line.expiryDate : null,
    quantity: 0,
    isDefault: !tracksExpiry,
  };
  s.lots.push(lot);
  return lot;
}

/** Phiếu nhập#2: DRAFT → CONFIRMED, cộng tồn theo lô. */
const confirmReceipt: MockHandler = (req) => {
  if (!isManager(req)) return denied();
  const s = load();
  const r = receiptFor(req, s);
  if (!('receiptId' in r)) return r;
  const sp = s.suppliers.find((x) => x.supplierId === r.supplierId)!;
  if (!sp.isActive) return ruleFail('BR-KO-02', `Nhà cung cấp ${sp.name} đã ngừng hợp tác`);
  const problem = confirmProblem(r);
  if (problem) return ruleFail('BR-KO-03', problem);
  if (r.status !== 'DRAFT') return fail('INVALID_STATE_TRANSITION', 'Chỉ xác nhận được phiếu nhập nháp', 409);
  for (const line of r.lines) {
    const item = itemFor(s, r.branchId, line.productId);
    const lot = lotForLine(s, item, line, productOf(line.productId)!.tracksExpiry);
    lot.quantity += line.quantity;
    line.stockLotId = lot.id;
  }
  r.status = 'CONFIRMED';
  r.confirmedBy = req.account!.id;
  r.confirmedAt = new Date().toISOString();
  save(s);
  return ok(receiptOf(s, r), 200, 'Đã nhập kho');
};

/** BR-KO-04: cộng dồn số lượng của phiếu theo lô rồi so với tồn hiện tại của lô. */
function cancelShortages(s: State, r: ReceiptRow): string[] {
  const need = new Map<number, number>();
  for (const l of r.lines) need.set(l.stockLotId!, (need.get(l.stockLotId!) ?? 0) + l.quantity);
  return [...need].flatMap(([lotId, qty]) => {
    const lot = s.lots.find((x) => x.id === lotId)!;
    return lot.quantity < qty ? [`lô ${lot.lotNumber ?? 'mặc định'} còn ${lot.quantity}, cần trừ ${qty}`] : [];
  });
}

/** Phiếu nhập#3 (DRAFT → CANCELLED, không đổi tồn) và #4 (CONFIRMED → CANCELLED, trừ lại tồn). */
const cancelReceipt: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid([['reason', 'Lý do hủy tối đa 300 ký tự', tooLong(b.reason, 300)]]);
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const s = load();
  const r = receiptFor(req, s);
  if (!('receiptId' in r)) return r;
  if (r.status === 'CONFIRMED') {
    const shortages = cancelShortages(s, r);
    if (shortages.length) {
      return ruleFail('BR-KO-04', `Không hủy được: ${shortages.join('; ')}. Dùng phiếu điều chỉnh tồn (BR-KO-06)`);
    }
    for (const l of r.lines) s.lots.find((x) => x.id === l.stockLotId)!.quantity -= l.quantity;
  } else if (r.status !== 'DRAFT') {
    return fail('INVALID_STATE_TRANSITION', 'Phiếu nhập đã hủy', 409);
  }
  r.status = 'CANCELLED';
  r.cancelledBy = req.account!.id;
  r.cancelledAt = new Date().toISOString();
  r.cancelReason = optional(b.reason);
  save(s);
  return ok(receiptOf(s, r), 200, 'Đã hủy phiếu nhập');
};

// ─── Điều chỉnh tồn (UC76, BR-KO-01, 06) ───

/** Lô thuộc chi nhánh người gọi; lô của chi nhánh khác coi như không có. */
function lotInBranch(s: State, lotId: number, branchId: number): { lot: LotRow; item: ItemRow } | null {
  const lot = s.lots.find((l) => l.id === lotId);
  const item = lot && s.items.find((i) => i.id === lot.itemId);
  return lot && item && item.branchId === branchId ? { lot, item } : null;
}

const createAdjustment: MockHandler = (req) => {
  const b = bodyOf(req);
  const lines = Array.isArray(b.lines) ? (b.lines as Record<string, unknown>[]) : null;
  const bad = invalid([
    ['reason', 'Lý do phải là DAMAGED, EXPIRED, COUNT_MISMATCH hoặc OTHER', !REASONS.includes(b.reason as AdjustmentReason)],
    ['lines', 'Phiếu điều chỉnh phải có ít nhất một dòng', !lines || lines.length === 0],
    ...(lines ?? []).flatMap((l, i): Check[] => [
      [`lines[${i}].stockLotId`, 'Vui lòng chọn lô', !isId(l?.stockLotId)],
      [`lines[${i}].quantityDelta`, 'Số điều chỉnh phải là số nguyên khác 0', !(Number.isInteger(l?.quantityDelta) && l.quantityDelta !== 0)],
    ]),
    ['note', 'Ghi chú tối đa 1000 ký tự', tooLong(b.note, 1000)],
  ]);
  if (bad) return bad;
  if (!isManager(req)) return denied();
  const s = load();
  const branchId = branchOf(req);
  const found = lines!.map((l) => lotInBranch(s, l.stockLotId as number, branchId));
  const missing = found.findIndex((f) => f === null);
  if (missing >= 0) return notFound('lô hàng', String(lines![missing].stockLotId));
  if (b.reason === 'OTHER' && !isText(b.note)) return ruleFail('BR-KO-06', 'Lý do "Khác" bắt buộc ghi chú');
  // Không giảm quá tồn của lô; cộng dồn các dòng cùng lô (BR-KO-01, 06).
  const after = new Map<number, number>();
  lines!.forEach((l, i) => {
    const { lot } = found[i]!;
    after.set(lot.id, (after.get(lot.id) ?? lot.quantity) + (l.quantityDelta as number));
  });
  for (const [lotId, qty] of after) {
    if (qty < 0) {
      const lot = s.lots.find((x) => x.id === lotId)!;
      return ruleFail('BR-KO-06', `Giảm quá tồn của lô ${lot.lotNumber ?? 'mặc định'} (còn ${lot.quantity})`);
    }
  }
  const adjustmentLines: AdjustmentLine[] = lines!.map((l, i) => {
    const { lot, item } = found[i]!;
    lot.quantity += l.quantityDelta as number;
    return {
      stockLotId: lot.id,
      productId: item.productId,
      productName: productOf(item.productId)?.name ?? '',
      lotNumber: lot.lotNumber,
      quantityDelta: l.quantityDelta as number,
      balanceAfter: lot.quantity,
    };
  });
  const adjustmentId = s.adjustments.length + 1;
  const created: StockAdjustment = {
    adjustmentId,
    code: `DC-${dayCode(localIsoDate())}-${pad4(adjustmentId)}`,
    branchId,
    reason: b.reason as AdjustmentReason,
    note: optional(b.note),
    lines: adjustmentLines,
    createdBy: req.account!.id,
    createdAt: new Date().toISOString(),
  };
  s.adjustments.push(created);
  save(s);
  return ok(created, 201, 'Đã điều chỉnh tồn');
};

const listAdjustments: MockHandler = (req) => {
  const { from, to } = req.query;
  if ((from && !isDate(from)) || (to && !isDate(to))) return fail('VALIDATION_FAILED', 'from: Ngày không hợp lệ', 400);
  const dayOf = (a: StockAdjustment) => localIsoDate(new Date(a.createdAt));
  const list = load()
    .adjustments.filter((a) => a.branchId === branchOf(req) && (!from || dayOf(a) >= from) && (!to || dayOf(a) <= to))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.adjustmentId - a.adjustmentId);
  return ok(paged(list, req.query));
};

const getAdjustment: MockHandler = (req) => {
  const a = load().adjustments.find((x) => x.adjustmentId === Number(req.params.adjustmentId));
  if (!a) return notFound('phiếu điều chỉnh', req.params.adjustmentId);
  if (a.branchId !== branchOf(req)) return scopeDenied();
  return ok(a);
};

export const inventoryRoutes: Record<string, MockHandler> = {
  'GET /api/suppliers': allow(BM, listSuppliers),
  'POST /api/suppliers': createSupplier,
  'PATCH /api/suppliers/:supplierId': updateSupplier,
  'DELETE /api/suppliers/:supplierId': deleteSupplier,
  'GET /api/stock-receipts': allow(BM, listReceipts),
  'POST /api/stock-receipts': createReceipt,
  'GET /api/stock-receipts/:receiptId': allow(BM, getReceipt),
  'PUT /api/stock-receipts/:receiptId': updateReceipt,
  'POST /api/stock-receipts/:receiptId/confirm': confirmReceipt,
  'POST /api/stock-receipts/:receiptId/cancel': cancelReceipt,
  'GET /api/inventory': allow(STOCK_READ, listInventory),
  'GET /api/inventory/:productId': allow(STOCK_READ, getInventoryItem),
  'GET /api/inventory/:productId/lots': allow(LOT_READ, listLots),
  'PUT /api/inventory/:productId/min-quantity': setMinQuantity,
  'POST /api/stock-adjustments': createAdjustment,
  'GET /api/stock-adjustments': allow(BM, listAdjustments),
  'GET /api/stock-adjustments/:adjustmentId': allow(BM, getAdjustment),
};
