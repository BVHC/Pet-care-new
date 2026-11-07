// Mock stockops-v1 (UC77–79; docs/api/stockops-v1.md #1–13): chuyển kho giữa chi nhánh, kiểm kê kho, truy vết lô. Tồn lô thật nằm ở inventory.mock.ts
// (trừ FEFO lô nguồn / tạo lô đích / hoàn lô nguồn / điều chỉnh COUNT_DIFF đều gọi hàm export của file đó, cùng một state), file này chỉ giữ phiếu chuyển kho
// và phiên kiểm kê. Quyền chép dòng "Quyền:" của yaml: #1–11 chỉ BRANCH_MANAGER (nguồn tạo, đích nhận, cả hai hủy, BR-KO-11); #12, #13 BRANCH_MANAGER và VET.
// Chi nhánh người gọi = `branchIdOf(account.id)`. BE tầng 3 CHƯA cài, nên mọi điểm dưới đây là ASSUMPTION của T57 (không có số đo thật):
//  A1  Thứ tự kiểm (Spring): 401 → 400 MALFORMED_REQUEST (path / query sai kiểu; body sai kiểu JSON; enum lạ) → 400 VALIDATION_FAILED của BODY (`@Valid @RequestBody`,
//      chạy TRƯỚC vai trò như inventory / care mock đã đo trên BE thật) → 403 vai trò → 404 → 403 phạm vi (message chung, KHÔNG mã rule) → 400 `BR-KO-…` (guard
//      nghiệp vụ, theo 00-method §3.5 và brief T57) → 409 INVALID_STATE_TRANSITION. RỦI RO: BE-2 giai đoạn 1 kiểm 409 TRƯỚC rule (ADR-0064 §2.4); nếu tầng 3 cũng vậy
//      thì chỉ đảo ở ca "phiếu / phiên đã đóng mà còn thiếu lý do / chưa có số đếm". Tạo phiếu #1: 404 (chi nhánh đích, sản phẩm) rồi BR-KO-08.
//  A2  FEFO của một sản phẩm trải qua nhiều lô thì phiếu có NHIỀU dòng cùng sản phẩm, mỗi dòng một lô (dòng phiếu chỉ ghi một `lotNumber`, ERD `source_lot_id`);
//      `lineCount` đếm dòng sau khi tách. Chỉ lô chưa hết hạn bị trừ (BR-KO-01); hủy phiếu hoàn đúng lô nguồn kể cả lô đã hết hạn. Nhận hàng cộng vào lô đích cùng
//      số lô + hạn dùng (lô mặc định nếu sản phẩm không quản lý hạn), tạo lô nếu chưa có.
//  A3  #1: thứ tự guard BR-KO-08: đích trùng nguồn → đích không ACTIVE → trùng sản phẩm → vượt tồn khả dụng (nêu đủ các sản phẩm thiếu, nối bằng "; "). Seed chi nhánh
//      của branch.mock chỉ chi nhánh 1 ACTIVE: tạo phiếu từ chi nhánh 1 tới 2 / 3 bị 400 BR-KO-08 cho tới khi SUPER_MANAGER kích hoạt; phiếu mẫu vẫn có cả hai chiều.
//  A4  Kiểm kê: phiên chụp danh sách lô còn tồn (> 0, kể cả lô hết hạn) của chi nhánh (theo danh mục nếu CATEGORY) lúc mở; lô nhập sau không vào phiên. `systemQty` là
//      tồn hiện tại khi OPEN / CANCELLED và là tồn lúc xác nhận khi CONFIRMED. `scope = ALL` mà gửi kèm `categoryId` thì bỏ qua `categoryId`. `categoryId` không có trong
//      danh mục (yaml không liệt kê 404): 400 BR-KO-12. Phiên không có lô nào vẫn mở được, nhưng xác nhận bị 400 BR-KO-12.
//  A5  #9: lô ngoài phạm vi phiên → 400 BR-KO-12 (yaml ghi 400 không mã rule); số đếm âm / thập phân, trùng lô trong một lần gửi → 400 VALIDATION_FAILED; `lines: []` không
//      đổi gì. #10 tạo MỘT phiếu điều chỉnh lý do `COUNT_DIFF` cho mọi lô lệch (số đếm − tồn lúc xác nhận); không lô nào lệch thì không tạo phiếu. `COUNT_DIFF` chưa có trong
//      enum `AdjustmentReason` của inventory-v1: màn điều chỉnh tồn sẽ không có nhãn cho nó.
//  A6  409 "Chi nhánh đã có phiên OPEN" (#6) dùng errorCode INVALID_STATE_TRANSITION như "ca thu ngân đã mở" (cashier.mock); yaml không nêu mã.
//  A7  #12: thiếu cả `lotNumber` lẫn `productId` → 400 BR-KO-13 (yaml chỉ ghi 400); `lotNumber` toàn khoảng trắng coi như vắng. Lô số dư 0 vẫn tìm / truy vết được (truy hồi lô
//      đã dùng hết). Mới nhập trước = id lô giảm dần. VET chỉ thấy lô THUỐC / VACCINE của chi nhánh mình: #12 lọc bỏ lô khác, #13 trả 403 phạm vi.
//  A8  #13: sổ lô dựng từ `stock_movements` của mock kho (nhập, chuyển kho, xuất bán, tiêm, điều chỉnh); chỉ các lần xuất của lô thuốc / vaccine mới kèm tên thú, tên khách (BR-KO-13,
//      A3), VET và BRANCH_MANAGER thấy như nhau (Q2 chưa chốt). Mã chứng từ chuyển kho tra theo `transferId` ở file này. Mock kho chưa có chứng từ cho xuất bán / tiêm
//      do mock bán hàng, lâm sàng gọi: những dòng đó `documentCode` null. Hủy phiếu nhập đã xác nhận ghi ADJUSTMENT, vật tư tiêu hao (ST07) ghi ORDER_ISSUE (enum `kind` thiếu loại).
//  A9  Không mô phỏng audit mỗi lượt xem truy vết, audit xác nhận kiểm kê (không mock nào export hàm ghi audit), cảnh báo phiếu IN_TRANSIT quá hạn (Q1), phiên quá 24 giờ (A2).
import type { Role } from '../../types/auth';
import type {
  LotSummary,
  LotTrace,
  StockCount,
  StockCountLine,
  StockCountScope,
  StockCountStatus,
  StockCountSummary,
  StockTransfer,
  StockTransferStatus,
  StockTransferSummary,
} from '../../types/stockops';
import { STOCK_COUNT_SCOPES, STOCK_COUNT_STATUSES, STOCK_OPS_LIMITS, STOCK_TRANSFER_STATUSES, TRANSFER_DIRECTIONS } from '../../types/stockops';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { branchBooking } from './branch.mock';
import { catalogCategories, catalogProducts } from './catalog.mock';
import { findCustomer } from './customer.mock';
import { petSummary } from './customer-pet.mock';
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
import { branchIdOf } from './identity.mock';
import {
  SEED_TRANSFERS,
  allLots,
  applyCountDiff,
  availableQuantity,
  countableLots,
  lotInfoOf,
  lotMovements,
  transferCancel,
  transferIn,
  transferOut,
  type LotInfo,
} from './inventory.mock';
import { clampPaging, pageOf, typeError } from './public.mock';

const MANAGER: readonly Role[] = ['BRANCH_MANAGER'];
const LOT_READERS: readonly Role[] = ['BRANCH_MANAGER', 'VET'];

// ─── State ───

interface TransferLineRow {
  productId: number;
  sourceLotId: number;
  destLotId: number | null;
  quantity: number;
  lotNumber: string | null;
  expiryDate: string | null;
}
interface TransferRow {
  transferId: number;
  code: string;
  sourceBranchId: number;
  destBranchId: number;
  status: StockTransferStatus;
  lines: TransferLineRow[];
  createdBy: number;
  createdAt: string;
  receivedBy: number | null;
  receivedAt: string | null;
  cancelledBy: number | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}
interface CountLineRow {
  stockLotId: number;
  productId: number;
  countedQty: number | null;
  /** `system_qty_at_confirm`: chỉ có sau khi xác nhận. */
  systemQtyAtConfirm: number | null;
}
interface CountRow {
  countId: number;
  code: string;
  branchId: number;
  status: StockCountStatus;
  scope: StockCountScope;
  categoryId: number | null;
  lines: CountLineRow[];
  createdBy: number;
  createdAt: string;
  confirmedBy: number | null;
  confirmedAt: string | null;
  cancelReason: string | null;
}
interface State {
  transfers: TransferRow[];
  counts: CountRow[];
}

const pad4 = (n: number) => String(n).padStart(4, '0');
const dayCode = (iso: string) => iso.slice(2).replace(/-/g, '');
/** Quản lý chi nhánh của mock identity: chi nhánh 1 = tài khoản #3, chi nhánh 2 = #9. */
const MANAGER_OF: Record<number, number> = { 1: 3, 2: 9 };

/**
 * Phiếu mẫu dựng từ `SEED_TRANSFERS` của mock kho (sổ lô và tồn mẫu đã tính sẵn các lần chuyển này, khớp theo id phiếu): mỗi chiều một phiếu IN_TRANSIT để cả hai quản lý
 * thử "Nhận hàng", một phiếu đã nhận, một phiếu đã hủy. Phiên mẫu: chi nhánh 1 có một phiên đã xác nhận (đếm khớp, không có điều chỉnh) và một phiên đã hủy, chưa có phiên
 * OPEN để thử mở; chi nhánh 2 đang có phiên OPEN (thử 409).
 */
function seed(today = vnDate()): State {
  const at = (daysAgo: number, time: string) => `${addDays(today, -daysAgo)}T${time}:00+07:00`;
  const transfers = SEED_TRANSFERS.map((t): TransferRow => {
    const closedAt = t.closedDaysAgo === null ? null : at(t.closedDaysAgo, t.status === 'RECEIVED' ? '14:00' : '15:00');
    return {
      transferId: t.transferId,
      code: `CK-${dayCode(addDays(today, -t.createdDaysAgo))}-${pad4(t.transferId)}`,
      sourceBranchId: t.sourceBranchId,
      destBranchId: t.destBranchId,
      status: t.status,
      lines: t.lines.map((l) => {
        const lot = lotInfoOf(l.sourceLotId);
        return { productId: l.productId, sourceLotId: l.sourceLotId, destLotId: l.destLotId, quantity: l.quantity, lotNumber: lot?.lotNumber ?? null, expiryDate: lot?.expiryDate ?? null };
      }),
      createdBy: MANAGER_OF[t.sourceBranchId],
      createdAt: at(t.createdDaysAgo, '09:00'),
      receivedBy: t.status === 'RECEIVED' ? MANAGER_OF[t.destBranchId] : null,
      receivedAt: t.status === 'RECEIVED' ? closedAt : null,
      cancelledBy: t.status === 'CANCELLED' ? MANAGER_OF[t.sourceBranchId] : null,
      cancelledAt: t.status === 'CANCELLED' ? closedAt : null,
      cancelReason: t.cancelReason ?? null,
    };
  });
  const line = (stockLotId: number, productId: number, countedQty: number | null, systemQtyAtConfirm: number | null = null): CountLineRow => ({ stockLotId, productId, countedQty, systemQtyAtConfirm });
  const count = (c: Omit<CountRow, 'confirmedBy' | 'confirmedAt' | 'cancelReason' | 'categoryId'> & Partial<CountRow>): CountRow => ({ categoryId: null, confirmedBy: null, confirmedAt: null, cancelReason: null, ...c });
  return {
    transfers,
    counts: [
      // Vaccine (danh mục 3) đếm khớp tồn lúc đó (sổ lô mẫu: lô 15 còn 17, lô 16 còn 2, lô 17 còn 5 vào 14 ngày trước), nên không có phiếu điều chỉnh.
      count({
        countId: 1,
        code: `KK-${dayCode(addDays(today, -14))}-0001`,
        branchId: 1,
        status: 'CONFIRMED',
        scope: 'CATEGORY',
        categoryId: 3,
        lines: [line(15, 5, 17, 17), line(16, 5, 2, 2), line(17, 6, 5, 5)],
        createdBy: 3,
        createdAt: at(14, '08:30'),
        confirmedBy: 3,
        confirmedAt: at(14, '11:00'),
      }),
      count({
        countId: 2,
        code: `KK-${dayCode(addDays(today, -7))}-0002`,
        branchId: 1,
        status: 'CANCELLED',
        scope: 'CATEGORY',
        categoryId: 1,
        lines: [line(11, 1, 12), line(12, 2, null)],
        createdBy: 3,
        createdAt: at(7, '08:30'),
        cancelReason: 'Mở nhầm phạm vi, đếm lại theo toàn kho',
      }),
      count({
        countId: 3,
        code: `KK-${dayCode(addDays(today, -1))}-0003`,
        branchId: 2,
        status: 'OPEN',
        scope: 'ALL',
        lines: [line(18, 1, null)],
        createdBy: 9,
        createdAt: at(1, '16:00'),
      }),
    ],
  };
}

const STATE_KEY = 'petcare-mock-stockops';

/** Gộp giá trị mặc định cho bảng thiếu (state lưu từ bản cũ hơn) rồi ghi lại ngay: dữ liệu mẫu tính theo lúc tạo nên đọc lại phải ra cùng ngày. */
function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) {
      const stored = JSON.parse(raw) as Partial<State>;
      if (stored.transfers && stored.counts) return stored as State;
      const base = seed();
      const merged: State = { transfers: stored.transfers ?? base.transfers, counts: stored.counts ?? base.counts };
      save(merged);
      return merged;
    }
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const s = seed();
  save(s);
  return s;
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Đọc dữ liệu vào ───

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const stateError = (what: string, from: string, to: string) => fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái ${what} từ ${from} sang ${to}`, 409);
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isReply = (x: unknown): x is MockReply => typeof (x as MockReply).status === 'number';
const role = (req: MockRequest) => req.account!.role;
const callerId = (req: MockRequest) => req.account!.id;
/** Chi nhánh của người gọi từ danh bạ identity (không đọc `account.branchId`). */
const myBranch = (req: MockRequest) => branchIdOf(callerId(req)) ?? null;
const isMine = (req: MockRequest, branchId: number) => myBranch(req) === branchId;
const branchName = (branchId: number) => branchBooking(branchId)?.branch.name ?? `Chi nhánh #${branchId}`;
const productName = (productId: number) => catalogProducts().find((p) => p.productId === productId)?.name ?? `Sản phẩm #${productId}`;
const newestFirst = <T extends { createdAt: string }>(a: T, b: T, ida: number, idb: number) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || idb - ida;

/** Reason của hủy phiếu / phiên: body bắt buộc (yaml), `reason` phải là chuỗi, ≤ 300 ký tự (@Size); trống xử lý ở guard BR sau 404 / phạm vi. */
function reasonOf(req: MockRequest): { reason: string | null } | MockReply {
  if (!isObject(req.body)) return malformed();
  const { reason } = req.body;
  if (has(reason) && typeof reason !== 'string') return malformed();
  const tooBig = invalid([['reason', `Lý do tối đa ${STOCK_OPS_LIMITS.reason} ký tự`, tooLong(reason, STOCK_OPS_LIMITS.reason)]]);
  return tooBig ?? { reason: isText(reason) ? reason.trim() : null };
}

/** Kiểm `page`, `size` (và các tham số số nguyên khác) rồi enum; sai kiểu → 400 MALFORMED_REQUEST, đến trước vai trò. */
function listQueryError(query: Record<string, string>, ints: string[], enums: Record<string, readonly string[]>): MockReply | null {
  const bad = typeError(query, ['page', 'size', ...ints]);
  if (bad) return bad;
  const name = Object.keys(enums).find((k) => query[k] !== undefined && !enums[k].includes(query[k]));
  return name ? badParam(name) : null;
}

// ─── Chuyển kho (#1–5) ───

function transferView(t: TransferRow): StockTransfer {
  return {
    transferId: t.transferId,
    code: t.code,
    sourceBranchId: t.sourceBranchId,
    sourceBranchName: branchName(t.sourceBranchId),
    destBranchId: t.destBranchId,
    destBranchName: branchName(t.destBranchId),
    status: t.status,
    lines: t.lines.map((l) => ({ productId: l.productId, productName: productName(l.productId), quantity: l.quantity, lotNumber: l.lotNumber, expiryDate: l.expiryDate })),
    createdAt: t.createdAt,
    receivedAt: t.receivedAt,
    cancelledAt: t.cancelledAt,
    cancelReason: t.cancelReason,
  };
}

const transferSummary = (t: TransferRow): StockTransferSummary => ({
  transferId: t.transferId,
  code: t.code,
  sourceBranchId: t.sourceBranchId,
  destBranchId: t.destBranchId,
  status: t.status,
  lineCount: t.lines.length,
  createdAt: t.createdAt,
});

/** Phiếu theo id mà chi nhánh người gọi là nguồn hoặc đích (BR-KO-11): không có → 404, của chi nhánh khác → 403 phạm vi. */
function transferFor(req: MockRequest, s: State): TransferRow | MockReply {
  const t = s.transfers.find((x) => x.transferId === Number(req.params.transferId));
  if (!t) return notFound('phiếu chuyển kho', req.params.transferId);
  return isMine(req, t.sourceBranchId) || isMine(req, t.destBranchId) ? t : scopeDenied();
}

const createTransfer: MockHandler = (req) => {
  if (!isObject(req.body)) return malformed();
  const { destBranchId, lines } = req.body;
  if (
    (has(destBranchId) && typeof destBranchId !== 'number') ||
    (has(lines) && (!Array.isArray(lines) || lines.some((l) => !isObject(l) || (has(l.productId) && typeof l.productId !== 'number') || (has(l.quantity) && typeof l.quantity !== 'number'))))
  ) {
    return malformed();
  }
  const rows = (Array.isArray(lines) ? lines : []) as Record<string, unknown>[];
  const bad = invalid([
    ['destBranchId', 'Vui lòng chọn chi nhánh đích', !Number.isInteger(destBranchId)],
    ['lines', 'Phiếu chuyển kho phải có ít nhất một dòng', rows.length === 0],
    ...rows.flatMap((l, i): Check[] => [
      [`lines[${i}].productId`, 'Vui lòng chọn sản phẩm', !Number.isInteger(l.productId)],
      [`lines[${i}].quantity`, 'Số lượng phải là số nguyên từ 1 trở lên', !Number.isInteger(l.quantity) || (l.quantity as number) < 1],
    ]),
  ]);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const source = myBranch(req);
  if (source === null) return scopeDenied();

  const dest = branchBooking(destBranchId as number)?.branch;
  if (!dest) return notFound('chi nhánh', destBranchId as number);
  const products = catalogProducts();
  const missing = rows.find((l) => !products.some((p) => p.productId === l.productId));
  if (missing) return notFound('sản phẩm', missing.productId as number);

  if (dest.branchId === source) return ruleFail('BR-KO-08', 'Chi nhánh đích phải khác chi nhánh nguồn');
  if (dest.status !== 'ACTIVE') return ruleFail('BR-KO-08', `Chi nhánh đích ${dest.name} chưa hoạt động`);
  const ids = rows.map((l) => l.productId as number);
  const repeated = ids.find((id, i) => ids.indexOf(id) !== i);
  if (repeated !== undefined) return ruleFail('BR-KO-08', `Sản phẩm ${productName(repeated)} xuất hiện ở nhiều dòng`);
  const short = rows
    .map((l) => ({ id: l.productId as number, need: l.quantity as number, have: availableQuantity(source, l.productId as number) }))
    .filter((x) => x.need > x.have)
    .map((x) => `${productName(x.id)} cần ${x.need}, tồn khả dụng ${x.have}`);
  if (short.length) return ruleFail('BR-KO-08', `Số lượng vượt tồn khả dụng của chi nhánh nguồn: ${short.join('; ')}`);

  const s = load();
  const transferId = Math.max(0, ...s.transfers.map((t) => t.transferId)) + 1;
  const code = `CK-${dayCode(vnDate())}-${pad4(transferId)}`;
  const created: TransferRow = {
    transferId,
    code,
    sourceBranchId: source,
    destBranchId: dest.branchId,
    status: 'IN_TRANSIT',
    lines: rows.flatMap((l) =>
      transferOut(source, l.productId as number, l.quantity as number, { documentCode: code, transferId })!.map(
        (slice): TransferLineRow => ({ productId: l.productId as number, sourceLotId: slice.lotId, destLotId: null, quantity: slice.quantity, lotNumber: slice.lotNumber, expiryDate: slice.expiryDate }),
      ),
    ),
    createdBy: callerId(req),
    createdAt: new Date().toISOString(),
    receivedBy: null,
    receivedAt: null,
    cancelledBy: null,
    cancelledAt: null,
    cancelReason: null,
  };
  s.transfers.push(created);
  save(s);
  return ok(transferView(created), 201, 'Đã tạo phiếu chuyển kho');
};

const listTransfers: MockHandler = (req) => {
  const bad = listQueryError(req.query, [], { direction: TRANSFER_DIRECTIONS, status: STOCK_TRANSFER_STATUSES });
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const { direction, status } = req.query;
  const outgoing = (t: TransferRow) => isMine(req, t.sourceBranchId);
  const incoming = (t: TransferRow) => isMine(req, t.destBranchId);
  const found = load()
    .transfers.filter((t) => (direction === 'OUTGOING' ? outgoing(t) : direction === 'INCOMING' ? incoming(t) : outgoing(t) || incoming(t)))
    .filter((t) => status === undefined || t.status === status)
    .sort((a, b) => newestFirst(a, b, a.transferId, b.transferId));
  return ok(pageOf(found.map(transferSummary), clampPaging(req.query)));
};

const getTransfer: MockHandler = (req) => {
  const bad = typeError(req.params, ['transferId']);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const t = transferFor(req, load());
  return isReply(t) ? t : ok(transferView(t));
};

/** Xác nhận nhận hàng: BRANCH_MANAGER chi nhánh ĐÍCH (BR-KO-11); chi nhánh nguồn muốn nhận cũng 403 phạm vi. */
const receiveTransfer: MockHandler = (req) => {
  const bad = typeError(req.params, ['transferId']);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const s = load();
  const t = transferFor(req, s);
  if (isReply(t)) return t;
  if (!isMine(req, t.destBranchId)) return scopeDenied();
  if (t.status !== 'IN_TRANSIT') return stateError('phiếu chuyển kho', t.status, 'RECEIVED');
  for (const l of t.lines) {
    l.destLotId = transferIn(t.destBranchId, l.productId, l.lotNumber, l.expiryDate, l.quantity, { documentCode: t.code, transferId: t.transferId });
  }
  Object.assign(t, { status: 'RECEIVED', receivedBy: callerId(req), receivedAt: new Date().toISOString() });
  save(s);
  return ok(transferView(t), 200, 'Đã nhận hàng chuyển kho');
};

const cancelTransfer: MockHandler = (req) => {
  const badId = typeError(req.params, ['transferId']);
  if (badId) return badId;
  const body = reasonOf(req);
  if (isReply(body)) return body;
  if (!MANAGER.includes(role(req))) return denied();
  const s = load();
  const t = transferFor(req, s);
  if (isReply(t)) return t;
  if (body.reason === null) return ruleFail('BR-KO-10', 'Vui lòng nhập lý do hủy phiếu chuyển kho');
  if (t.status !== 'IN_TRANSIT') return stateError('phiếu chuyển kho', t.status, 'CANCELLED');
  for (const l of t.lines) transferCancel(l.sourceLotId, l.quantity, { documentCode: t.code, transferId: t.transferId });
  Object.assign(t, { status: 'CANCELLED', cancelledBy: callerId(req), cancelledAt: new Date().toISOString(), cancelReason: body.reason });
  save(s);
  return ok(transferView(t), 200, 'Đã hủy phiếu chuyển kho');
};

// ─── Kiểm kê (#6–11) ───

function countLines(c: CountRow): StockCountLine[] {
  const lots = new Map(allLots().map((l) => [l.lotId, l]));
  return c.lines.map((l) => {
    const lot = lots.get(l.stockLotId);
    return {
      stockLotId: l.stockLotId,
      productId: l.productId,
      productName: productName(l.productId),
      lotNumber: lot?.lotNumber ?? null,
      expiryDate: lot?.expiryDate ?? null,
      countedQty: l.countedQty,
      systemQty: c.status === 'CONFIRMED' ? (l.systemQtyAtConfirm ?? lot?.quantity ?? 0) : (lot?.quantity ?? 0),
    };
  });
}

const countView = (c: CountRow): StockCount => ({
  countId: c.countId,
  code: c.code,
  branchId: c.branchId,
  status: c.status,
  scope: c.scope,
  categoryId: c.categoryId,
  lines: countLines(c),
  createdAt: c.createdAt,
  confirmedAt: c.confirmedAt,
  cancelReason: c.cancelReason,
});

const countSummary = (c: CountRow): StockCountSummary => ({ countId: c.countId, code: c.code, status: c.status, scope: c.scope, createdAt: c.createdAt, confirmedAt: c.confirmedAt });

/** Phiên theo id của kho chi nhánh người gọi: không có → 404, của chi nhánh khác → 403 phạm vi. */
function countFor(req: MockRequest, s: State): CountRow | MockReply {
  const c = s.counts.find((x) => x.countId === Number(req.params.countId));
  if (!c) return notFound('phiên kiểm kê', req.params.countId);
  return isMine(req, c.branchId) ? c : scopeDenied();
}

/** Thứ tự dòng của phiên: theo sản phẩm, rồi hạn dùng sớm nhất (lô mặc định cuối), rồi id lô. */
const byProductThenExpiry = (a: LotInfo, b: LotInfo) =>
  a.productId - b.productId || (a.expiryDate ?? '9999-12-31').localeCompare(b.expiryDate ?? '9999-12-31') || a.lotId - b.lotId;

const openCount: MockHandler = (req) => {
  if (!isObject(req.body)) return malformed();
  const { scope, categoryId } = req.body;
  if ((has(scope) && !(STOCK_COUNT_SCOPES as readonly unknown[]).includes(scope)) || (has(categoryId) && typeof categoryId !== 'number')) return malformed();
  const bad = invalid([['scope', 'Vui lòng chọn phạm vi kiểm kê', !has(scope)]]);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const branchId = myBranch(req);
  if (branchId === null) return scopeDenied();

  let category: number | null = null;
  if (scope === 'CATEGORY') {
    if (!has(categoryId)) return ruleFail('BR-KO-12', 'Kiểm kê theo danh mục phải chọn danh mục sản phẩm');
    if (!catalogCategories().some((c) => c.categoryId === categoryId)) return ruleFail('BR-KO-12', `Không tìm thấy danh mục sản phẩm #${categoryId}`);
    category = categoryId as number;
  }
  const s = load();
  if (s.counts.some((c) => c.branchId === branchId && c.status === 'OPEN')) {
    return fail('INVALID_STATE_TRANSITION', 'Chi nhánh đã có phiên kiểm kê đang mở, hãy xác nhận hoặc hủy phiên đó trước', 409);
  }
  const countId = Math.max(0, ...s.counts.map((c) => c.countId)) + 1;
  const created: CountRow = {
    countId,
    code: `KK-${dayCode(vnDate())}-${pad4(countId)}`,
    branchId,
    status: 'OPEN',
    scope: scope as StockCountScope,
    categoryId: category,
    lines: countableLots(branchId, category)
      .sort(byProductThenExpiry)
      .map((l) => ({ stockLotId: l.lotId, productId: l.productId, countedQty: null, systemQtyAtConfirm: null })),
    createdBy: callerId(req),
    createdAt: new Date().toISOString(),
    confirmedBy: null,
    confirmedAt: null,
    cancelReason: null,
  };
  s.counts.push(created);
  save(s);
  return ok(countView(created), 201, 'Đã mở phiên kiểm kê');
};

const listCounts: MockHandler = (req) => {
  const bad = listQueryError(req.query, [], { status: STOCK_COUNT_STATUSES });
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const found = load()
    .counts.filter((c) => isMine(req, c.branchId) && (req.query.status === undefined || c.status === req.query.status))
    .sort((a, b) => newestFirst(a, b, a.countId, b.countId));
  return ok(pageOf(found.map(countSummary), clampPaging(req.query)));
};

const getCount: MockHandler = (req) => {
  const bad = typeError(req.params, ['countId']);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const c = countFor(req, load());
  return isReply(c) ? c : ok(countView(c));
};

const saveCountLines: MockHandler = (req) => {
  const badId = typeError(req.params, ['countId']);
  if (badId) return badId;
  if (!isObject(req.body)) return malformed();
  const { lines } = req.body;
  if (has(lines) && (!Array.isArray(lines) || lines.some((l) => !isObject(l) || (has(l.stockLotId) && typeof l.stockLotId !== 'number') || (has(l.countedQty) && typeof l.countedQty !== 'number')))) {
    return malformed();
  }
  const rows = (Array.isArray(lines) ? lines : []) as Record<string, unknown>[];
  const lotIds = rows.map((l) => l.stockLotId);
  const bad = invalid([
    ['lines', 'Vui lòng gửi danh sách số đếm', !has(lines)],
    ...rows.flatMap((l, i): Check[] => [
      [`lines[${i}].stockLotId`, 'Vui lòng chọn lô', !Number.isInteger(l.stockLotId)],
      [`lines[${i}].countedQty`, 'Số đếm phải là số nguyên từ 0 trở lên', !Number.isInteger(l.countedQty) || (l.countedQty as number) < 0],
    ]),
    ['lines', 'Mỗi lô chỉ được xuất hiện một lần', new Set(lotIds).size < lotIds.length],
  ]);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const s = load();
  const c = countFor(req, s);
  if (isReply(c)) return c;
  const outside = rows.find((l) => !c.lines.some((x) => x.stockLotId === l.stockLotId));
  if (outside) return ruleFail('BR-KO-12', `Lô #${outside.stockLotId} không thuộc phạm vi phiên kiểm kê`);
  if (c.status !== 'OPEN') return stateError('phiên kiểm kê', c.status, 'OPEN');
  for (const l of rows) c.lines.find((x) => x.stockLotId === l.stockLotId)!.countedQty = l.countedQty as number;
  save(s);
  return ok(countView(c), 200, 'Đã lưu số đếm');
};

const confirmCount: MockHandler = (req) => {
  const bad = typeError(req.params, ['countId']);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();
  const s = load();
  const c = countFor(req, s);
  if (isReply(c)) return c;
  if (!c.lines.some((l) => l.countedQty !== null)) return ruleFail('BR-KO-12', 'Chưa nhập số đếm cho lô nào');
  if (c.status !== 'OPEN') return stateError('phiên kiểm kê', c.status, 'CONFIRMED');
  // Tồn hệ thống lúc xác nhận (A2 của hợp đồng: không khóa nhập / xuất) đóng băng TRƯỚC khi điều chỉnh; lô chưa đếm không bị đụng tới.
  const lots = new Map(allLots().map((l) => [l.lotId, l]));
  for (const l of c.lines) l.systemQtyAtConfirm = lots.get(l.stockLotId)?.quantity ?? 0;
  const counted = c.lines.filter((l) => l.countedQty !== null).map((l) => ({ stockLotId: l.stockLotId, countedQty: l.countedQty as number }));
  applyCountDiff(c.branchId, callerId(req), counted, `Kiểm kê ${c.code}`);
  Object.assign(c, { status: 'CONFIRMED', confirmedBy: callerId(req), confirmedAt: new Date().toISOString() });
  save(s);
  return ok(countView(c), 200, 'Đã xác nhận kiểm kê');
};

const cancelCount: MockHandler = (req) => {
  const badId = typeError(req.params, ['countId']);
  if (badId) return badId;
  const body = reasonOf(req);
  if (isReply(body)) return body;
  if (!MANAGER.includes(role(req))) return denied();
  const s = load();
  const c = countFor(req, s);
  if (isReply(c)) return c;
  if (body.reason === null) return ruleFail('BR-KO-12', 'Vui lòng nhập lý do hủy phiên kiểm kê');
  if (c.status !== 'OPEN') return stateError('phiên kiểm kê', c.status, 'CANCELLED');
  Object.assign(c, { status: 'CANCELLED', cancelReason: body.reason });
  save(s);
  return ok(countView(c), 200, 'Đã hủy phiên kiểm kê');
};

// ─── Truy vết lô (#12, #13) ───

const isDrugOrVaccine = (productId: number) => ['DRUG', 'VACCINE'].includes(catalogProducts().find((p) => p.productId === productId)?.productType ?? '');
/** BRANCH_MANAGER thấy mọi lô của chi nhánh mình; VET chỉ lô thuốc, vaccine (BR-KO-13). */
const lotVisible = (req: MockRequest, lot: LotInfo) => isMine(req, lot.branchId) && (role(req) === 'BRANCH_MANAGER' || isDrugOrVaccine(lot.productId));

const lotSummary = (lot: LotInfo): LotSummary => ({
  lotId: lot.lotId,
  productId: lot.productId,
  productName: productName(lot.productId),
  lotNumber: lot.lotNumber,
  expiryDate: lot.expiryDate,
  branchId: lot.branchId,
  quantity: lot.quantity,
});

const searchLots: MockHandler = (req) => {
  const bad = typeError(req.query, ['page', 'size', 'productId']);
  if (bad) return bad;
  if (!LOT_READERS.includes(role(req))) return denied();
  const needle = req.query.lotNumber?.trim().toLowerCase() ?? '';
  const { productId } = req.query;
  if (!needle && productId === undefined) return ruleFail('BR-KO-13', 'Vui lòng nhập số lô hoặc chọn sản phẩm để tìm lô');
  const found = allLots()
    .filter((l) => lotVisible(req, l) && (productId === undefined || l.productId === Number(productId)) && (!needle || (l.lotNumber ?? '').toLowerCase().includes(needle)))
    .sort((a, b) => b.lotId - a.lotId);
  return ok(pageOf(found.map(lotSummary), clampPaging(req.query)));
};

const traceLot: MockHandler = (req) => {
  const bad = typeError(req.params, ['lotId']);
  if (bad) return bad;
  if (!LOT_READERS.includes(role(req))) return denied();
  const lot = lotInfoOf(Number(req.params.lotId));
  if (!lot) return notFound('lô hàng', req.params.lotId);
  if (!lotVisible(req, lot)) return scopeDenied();
  const withContact = isDrugOrVaccine(lot.productId);
  const transfers = load().transfers;
  const trace: LotTrace = {
    lot: lotSummary(lot),
    events: lotMovements(lot.lotId).map((m) => ({
      occurredAt: m.occurredAt,
      kind: m.kind,
      quantity: m.quantity,
      documentCode: transfers.find((t) => t.transferId === m.transferId)?.code ?? m.documentCode,
      branchId: m.branchId,
      petName: withContact && m.petId !== null ? (petSummary(m.petId)?.name ?? null) : null,
      customerName: withContact && m.customerId !== null ? (findCustomer(m.customerId)?.fullName ?? null) : null,
    })),
    remaining: lot.quantity,
  };
  return ok(trace);
};

// ─── Cho mock khác đọc chéo (nguồn đếm của branchlife `registerClosureSources`: BR-CN-07, BR-CN-08): chỉ đọc ───

/** Phiếu chuyển kho IN_TRANSIT mà chi nhánh là nguồn HOẶC đích (hàng đang trên đường, cả hai đầu đều chưa đóng được). Nối `inTransitTransfers`. */
export const inTransitTransfersOf = (branchId: number): number =>
  load().transfers.filter((t) => t.status === 'IN_TRANSIT' && (t.sourceBranchId === branchId || t.destBranchId === branchId)).length;

/** Phiên kiểm kê OPEN của chi nhánh (tối đa 1, BR-KO-12). Nối `openStockCounts`. */
export const openStockCountsOf = (branchId: number): number => load().counts.filter((c) => c.branchId === branchId && c.status === 'OPEN').length;

export const stockopsRoutes: Record<string, MockHandler> = {
  'POST /api/stock-transfers': createTransfer,
  'GET /api/stock-transfers': listTransfers,
  'GET /api/stock-transfers/:transferId': getTransfer,
  'POST /api/stock-transfers/:transferId/receive': receiveTransfer,
  'POST /api/stock-transfers/:transferId/cancel': cancelTransfer,
  'POST /api/stock-counts': openCount,
  'GET /api/stock-counts': listCounts,
  'GET /api/stock-counts/:countId': getCount,
  'PUT /api/stock-counts/:countId/lines': saveCountLines,
  'POST /api/stock-counts/:countId/confirm': confirmCount,
  'POST /api/stock-counts/:countId/cancel': cancelCount,
  'GET /api/stock-lots': searchLots,
  'GET /api/stock-lots/:lotId/trace': traceLot,
};
