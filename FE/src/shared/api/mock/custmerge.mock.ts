// Mock custmerge-v1 (UC27, tầng 3; docs/api/custmerge-v1.md #1–2, 08 §F.2 BR-KH-11…14): xem trước và gộp hồ sơ khách trùng. Chỉ BRANCH_MANAGER, gộp được mọi hồ sơ khách
// (hồ sơ dùng chung toàn chuỗi, quyết định T54 / A1): không có 403 phạm vi chi nhánh. Gộp không hoàn tác (BR-KH-13), nên mỗi lần gộp ghi một dòng `customer_merges`
// (state riêng ở localStorage `petcare-mock-custmerge`); hồ sơ nguồn chuyển MERGED ngay trong state khách (customer.mock.ts, `mergedIntoId`).
//
// Chuyển dữ liệu (BR-KH-12, `CustomerMergedEvent`): mỗi loại dữ liệu có MỘT nguồn vừa đếm (preview) vừa chuyển (gộp), nên `moved[loại]` luôn bằng số đếm của preview.
//  - Chuyển thật: hồ sơ khách + sổ địa chỉ (customer.mock.ts, địa chỉ trùng bị bỏ), thú cưng (customer-pet.mock.ts), feedback (feedback.mock.ts).
//  - Nguồn đăng ký `registerMergeSources` (mặc định đếm 0, chuyển 0): lịch hẹn, đặt chỗ lưu trú, Order, đơn online, khiếu nại, Care Task. Các mock đó chưa export hàm
//    đếm / chuyển theo khách (hoặc chưa tồn tại: shop, complaint), nên dữ liệu của chúng vẫn nằm ở hồ sơ nguồn cho tới khi luồng chính nối nguồn.
//  - Chặn BR-KH-11 về Visit mở / Order OPEN / PENDING đọc thật: thú còn lượt khám mở hoặc đang lưu trú (customer-pet.mock.ts) và Order trong mock bán hàng.
//
// ASSUMPTION (hợp đồng im lặng hoặc mock đơn giản hơn BE; BE tầng 3 CHƯA cài nên không có số đo thật):
//  A1  Thứ tự kiểm theo 00-method §3.5 và brief T57: 401 → 400 sai kiểu (MALFORMED_REQUEST: id không phải số, thiếu `sourceCustomerId` ở #1, body sai kiểu ở #2) → 403 vai trò
//      (chỉ BRANCH_MANAGER; SUPER_MANAGER, ADMIN và các vai khác bị chặn) → 400 VALIDATION_FAILED (id ≤ 0, thiếu trường, nguồn trùng đích ở #1) → 404 (hồ sơ đích trước, rồi nguồn)
//      → 400 BR-KH-11 → 400 BR-KH-14 → ghi. Không mô phỏng 409 CONCURRENCY_CONFLICT (mock chạy đơn luồng) và không ghi audit (BR-QT-15).
//  A2  Preview (#1) KHÔNG trả lỗi khi hồ sơ không gộp được: điều kiện chặn nằm ở `blockers` ("blockers rỗng mới gộp được"). Riêng nguồn trùng đích là 400 VALIDATION_FAILED
//      (yaml #1 không gắn mã rule), còn ở #2 cùng lỗi đó là 400 BR-KH-11 ("hai hồ sơ khác nhau" nằm trong BR-KH-11).
//  A3  BR-KH-11 gồm: nguồn / đích đã MERGED; cả hai đã liên kết tài khoản; nguồn có tài khoản mà đích không (đích phải là bên có tài khoản); nguồn còn lượt khám mở, thú đang lưu trú
//      hoặc Order OPEN / PENDING. Một lần trả đủ mọi điều kiện còn vướng, nối bằng "; " rồi mã `(BR-KH-11)`. `blockers` ở #1 là cùng danh sách.
//  A4  `warnings` (không chặn): khác họ tên, khác số điện thoại (hoặc một bên thiếu), thú nghi trùng (cùng loài, cùng tên không phân biệt dấu / hoa thường; không tự gộp, BR-KH-12 / A2),
//      hồ sơ đích đang chờ quyết định liên kết (BR-TK-19).
//  A5  Địa chỉ trùng (cùng đường, phường, tỉnh, không phân biệt hoa thường) bị bỏ; còn lại chuyển sang đích và KHÔNG áp trần 5 địa chỉ (BR-TK-18 chỉ áp cho khách tự thêm). Địa chỉ mặc
//      định của đích được giữ. `moved.addresses` = số địa chỉ đã chuyển = `addressCount − duplicateAddressCount` của preview.
//  A7  Đích đang chờ khách quyết định liên kết (BR-TK-19) mà gộp xong không còn hồ sơ tại quầy nào cùng SĐT để liên kết thì cờ chờ được gỡ (customer.mock.ts `mergeCustomerRecords`).
//  A6  `moved` có đủ chín khóa: pets, addresses, appointments, boardings, orders, onlineOrders, feedbacks, complaints, careTasks (yaml chỉ ghi "số bản ghi đã chuyển theo loại").
import { foldVi } from '../../utils/text';
import type { CustomerMergeResult, MergePreview } from '../../types/custmerge';
import { addressMergeStats, findCustomer, mergeCustomerRecords, type MockCustomer } from './customer.mock';
import { movePetsOfCustomer, petsOfCustomer } from './customer-pet.mock';
import { denied, fail, has, invalid, notFound, ok, ruleFail, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { feedbackCountOf, moveFeedbacks } from './feedback.mock';
import { typeError } from './public.mock';
import { allOrderRows } from './sales.mock';

// ─── Nguồn của module khác ───

/** Loại dữ liệu chưa đọc / chuyển được từ mock khác; khóa cũng là khóa trong `moved` của kết quả (A6). */
export type MergeKind = 'appointments' | 'boardings' | 'orders' | 'onlineOrders' | 'complaints' | 'careTasks';

export interface MergeSource {
  /** Số bản ghi của khách (mọi trạng thái) sẽ chuyển. */
  count: (customerId: number) => number;
  /** `CustomerMergedEvent(source, target)` của module đó: đổi `customer_id`, trả số bản ghi đã chuyển (bằng `count` ngay trước đó). */
  move: (fromCustomerId: number, toCustomerId: number) => number;
}

const NONE: MergeSource = { count: () => 0, move: () => 0 };
let sources: Record<MergeKind, MergeSource> = { appointments: NONE, boardings: NONE, orders: NONE, onlineOrders: NONE, complaints: NONE, careTasks: NONE };

/** Đăng ký (đè từng phần) nguồn của module khác; trả hàm khôi phục nguồn trước đó (dùng trong test). */
export function registerMergeSources(next: Partial<Record<MergeKind, MergeSource>>): () => void {
  const before = sources;
  sources = { ...sources, ...next };
  return () => {
    sources = before;
  };
}

// ─── State: bảng `customer_merges` (LOG) ───

interface MergeRow {
  mergeId: number;
  sourceCustomerId: number;
  targetCustomerId: number;
  executedBy: number;
  moved: Record<string, number>;
  createdAt: string;
}
interface State {
  seq: number;
  merges: MergeRow[];
}

const STATE_KEY = 'petcare-mock-custmerge';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    // Gộp với giá trị mặc định: state thiếu bảng (vd chỉ `{}`) vẫn đọc được.
    return { seq: 0, merges: [], ...(raw ? (JSON.parse(raw) as Partial<State>) : {}) };
  } catch {
    return { seq: 0, merges: [] };
  }
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Điều kiện và số đếm ───

const label = (c: MockCustomer | undefined, id: number) => `#${id}${c ? ` (${c.fullName})` : ''}`;

/** BR-KH-11. Hai hồ sơ đã tồn tại và khác nhau. */
function blockersOf(source: MockCustomer, target: MockCustomer): string[] {
  const pets = petsOfCustomer(source.customerId);
  const openOrders = allOrderRows().filter((o) => o.customerId === source.customerId && (o.status === 'OPEN' || o.status === 'PENDING')).length;
  const visits = pets.filter((p) => p.busy === 'OPEN_VISIT').length;
  const stays = pets.filter((p) => p.busy === 'BOARDING').length;
  return [
    source.mergedIntoId != null && `hồ sơ nguồn ${label(source, source.customerId)} đã được gộp vào hồ sơ #${source.mergedIntoId}`,
    target.mergedIntoId != null && `hồ sơ đích ${label(target, target.customerId)} đã được gộp vào hồ sơ #${target.mergedIntoId}`,
    source.accountId !== null && target.accountId !== null && 'cả hai hồ sơ đã liên kết tài khoản, mỗi hồ sơ chỉ có tối đa một tài khoản',
    source.accountId !== null && target.accountId === null && 'hồ sơ nguồn có tài khoản nên hồ sơ đích phải là bên có tài khoản',
    visits > 0 && `hồ sơ nguồn còn ${visits} thú đang có lượt khám chưa kết thúc`,
    stays > 0 && `hồ sơ nguồn còn ${stays} thú đang lưu trú`,
    openOrders > 0 && `hồ sơ nguồn còn ${openOrders} Order chưa thanh toán (OPEN / PENDING)`,
  ].filter((x): x is string => !!x);
}

/** Cảnh báo không chặn (A4). */
function warningsOf(source: MockCustomer, target: MockCustomer): string[] {
  const twins = petsOfCustomer(source.customerId).flatMap((p) =>
    petsOfCustomer(target.customerId)
      .filter((t) => t.species === p.species && foldVi(t.name.trim()) === foldVi(p.name.trim()))
      .map(() => `Thú "${p.name}" có ở cả hai hồ sơ, nghi trùng; gộp xong lễ tân kiểm tra và xử lý sau`),
  );
  return [
    foldVi(source.fullName.trim()) !== foldVi(target.fullName.trim()) && `Họ tên hai hồ sơ khác nhau ("${source.fullName}" và "${target.fullName}")`,
    (source.phone ?? '') !== (target.phone ?? '') && `Số điện thoại hai hồ sơ khác nhau (${source.phone ?? 'không có'} và ${target.phone ?? 'không có'})`,
    target.linkDecisionPending && 'Hồ sơ đích đang chờ khách quyết định liên kết với hồ sơ tại quầy',
    ...twins,
  ].filter((x): x is string => !!x);
}

function previewOf(source: MockCustomer, target: MockCustomer): MergePreview {
  const addresses = addressMergeStats(source.customerId, target.customerId);
  const id = source.customerId;
  return {
    targetCustomerId: target.customerId,
    sourceCustomerId: id,
    petCount: petsOfCustomer(id).length,
    addressCount: addresses.count,
    duplicateAddressCount: addresses.duplicates,
    appointmentCount: sources.appointments.count(id),
    boardingCount: sources.boardings.count(id),
    orderCount: sources.orders.count(id),
    onlineOrderCount: sources.onlineOrders.count(id),
    feedbackCount: feedbackCountOf(id),
    complaintCount: sources.complaints.count(id),
    careTaskCount: sources.careTasks.count(id),
    warnings: warningsOf(source, target),
    blockers: blockersOf(source, target),
  };
}

// ─── Đọc dữ liệu vào ───

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const isManager = (req: MockRequest) => req.account!.role === 'BRANCH_MANAGER';
const notFoundCustomer = (id: number) => notFound('khách hàng', id);

// ─── #1 Xem trước ───

const preview: MockHandler = (req) => {
  const badPath = typeError(req.params, ['customerId']);
  if (badPath) return badPath;
  const raw = req.query.sourceCustomerId;
  if (raw === undefined) return fail('MALFORMED_REQUEST', "Thiếu tham số 'sourceCustomerId'", 400);
  const badQuery = typeError(req.query, ['sourceCustomerId']);
  if (badQuery) return badQuery;
  if (!isManager(req)) return denied();
  const targetId = Number(req.params.customerId);
  const sourceId = Number(raw);
  const bad = invalid([
    ['customerId', 'customerId phải là số dương', targetId <= 0],
    ['sourceCustomerId', 'sourceCustomerId phải là số dương', sourceId <= 0],
    ['sourceCustomerId', 'Hồ sơ nguồn phải khác hồ sơ đích', sourceId === targetId && targetId > 0],
  ]);
  if (bad) return bad;
  const target = findCustomer(targetId);
  if (!target) return notFoundCustomer(targetId);
  const source = findCustomer(sourceId);
  if (!source) return notFoundCustomer(sourceId);
  return ok(previewOf(source, target));
};

// ─── #2 Gộp ───

function bodyOf(body: unknown): Record<string, unknown> | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const b = body as Record<string, unknown>;
  const wrong = (has(b.sourceCustomerId) && !isInt(b.sourceCustomerId)) || (has(b.targetCustomerId) && !isInt(b.targetCustomerId)) || (has(b.confirm) && typeof b.confirm !== 'boolean');
  return wrong ? null : b;
}

const merge: MockHandler = (req) => {
  const b = bodyOf(req.body);
  if (!b) return malformed();
  if (!isManager(req)) return denied();
  const checks: Check[] = [
    ['sourceCustomerId', 'Vui lòng chọn hồ sơ nguồn', !has(b.sourceCustomerId)],
    ['sourceCustomerId', 'sourceCustomerId phải là số dương', isInt(b.sourceCustomerId) && b.sourceCustomerId <= 0],
    ['targetCustomerId', 'Vui lòng chọn hồ sơ đích', !has(b.targetCustomerId)],
    ['targetCustomerId', 'targetCustomerId phải là số dương', isInt(b.targetCustomerId) && b.targetCustomerId <= 0],
    ['confirm', 'Thiếu xác nhận gộp', !has(b.confirm)],
  ];
  const bad = invalid(checks);
  if (bad) return bad;
  const sourceId = b.sourceCustomerId as number;
  const targetId = b.targetCustomerId as number;
  const target = findCustomer(targetId);
  if (!target) return notFoundCustomer(targetId);
  const source = findCustomer(sourceId);
  if (!source) return notFoundCustomer(sourceId);

  if (sourceId === targetId) return ruleFail('BR-KH-11', 'Hồ sơ nguồn phải khác hồ sơ đích');
  const blockers = blockersOf(source, target);
  if (blockers.length) return ruleFail('BR-KH-11', `Không gộp được: ${blockers.join('; ')}`);
  if (b.confirm !== true) return ruleFail('BR-KH-14', 'Cần xem trước (merge-preview) và xác nhận trước khi gộp hồ sơ');

  return executeMerge(source, target, req.account!.id);
};

/** Chuyển dữ liệu từng loại rồi ghi LOG; điều kiện đã kiểm ở trên. Số chuyển tính bằng chính hàm chuyển của từng nguồn. */
function executeMerge(source: MockCustomer, target: MockCustomer, executedBy: number): MockReply {
  const from = source.customerId;
  const to = target.customerId;
  const pets = movePetsOfCustomer(from, to);
  const feedbacks = moveFeedbacks(from, to);
  const moved: Record<string, number> = {
    pets,
    addresses: mergeCustomerRecords(from, to)?.addressesMoved ?? 0,
    appointments: sources.appointments.move(from, to),
    boardings: sources.boardings.move(from, to),
    orders: sources.orders.move(from, to),
    onlineOrders: sources.onlineOrders.move(from, to),
    feedbacks,
    complaints: sources.complaints.move(from, to),
    careTasks: sources.careTasks.move(from, to),
  };
  const s = load();
  const row: MergeRow = { mergeId: ++s.seq, sourceCustomerId: from, targetCustomerId: to, executedBy, moved, createdAt: new Date().toISOString() };
  s.merges.push(row);
  save(s);
  const result: CustomerMergeResult = { mergeId: row.mergeId, sourceCustomerId: from, targetCustomerId: to, moved, createdAt: row.createdAt };
  return ok(result, 201, 'Đã gộp hồ sơ khách');
}

export const custmergeRoutes: Record<string, MockHandler> = {
  'GET /api/customers/:customerId/merge-preview': preview,
  'POST /api/customer-merges': merge,
};
