// Mock branchlife-v1 (UC13, tầng 3; docs/api/branchlife-v1.md #1–4, 08 §F.1 BR-CN-06…10, FSM #20): xem trước tồn đọng, tạm ngừng, mở lại, đóng cửa chi nhánh.
// Chỉ SUPER_MANAGER. Trạng thái SUSPENDED / CLOSED và các cột vòng đời nằm trong state của branch.mock.ts (nơi các mock đặt lịch, lưu trú, tiếp nhận
// và trang công khai đã chặn / ẩn mọi chi nhánh không ACTIVE); file này giữ route, đếm tồn đọng và kiểm điều kiện. KHÔNG có state riêng.
//
// Tồn đọng (BR-CN-07) đếm từ nhiều module. Đọc thật qua export có sẵn: lịch hẹn mẫu + đặt chỗ lưu trú BOOKED (branch.mock), Order OPEN / PENDING
// (sales.mock), tồn kho (inventory.mock, cộng cả lô hết hạn), nhân viên (identity-admin.mock). Phần còn lại là *nguồn đăng ký* `registerClosureSources`
// (mặc định 0): lượt khám mở, thú đang lưu trú, ca thu ngân mở, Care Task OPEN, đơn online chưa xong, hoàn tiền chờ, phiếu chuyển kho đang đi, kiểm kê mở
// — các mock đó chưa export hàm đếm (hoặc chưa tồn tại: shop, returns, stockops); luồng chính nối khi đăng ký route.
//
// ASSUMPTION (hợp đồng im lặng hoặc mock đơn giản hơn BE; BE tầng 3 CHƯA cài nên không có số đo thật):
//  A1  Thứ tự kiểm theo 00-method §3.5 và brief T57: 401 → 400 sai kiểu / thiếu body (MALFORMED_REQUEST) → 403 vai trò (chỉ SUPER_MANAGER, kể cả ADMIN
//      và BRANCH_MANAGER bị chặn) → 400 VALIDATION_FAILED (`reason` trống / quá 300, thiếu `cancelBookings`) → 404 → 400 BR-CN-08 → 400 BR-CN-09 → 409.
//      Chú ý: `activate` của branch-v1 (đã đo trên BE thật) kiểm 409 TRƯỚC guard 400; nếu BE-2 cài UC13 giống `activate` thì hai bước cuối đổi chỗ.
//      SUPER_MANAGER có phạm vi cả chuỗi nên không có 403 phạm vi.
//  A2  Mở lại (#3): 400 BR-CN-01 (chưa có giờ mở cửa) hoặc BR-QT-04 (chưa có BRANCH_MANAGER ACTIVE chưa bị khóa), cùng một câu như `activate`: thiếu cả hai thì
//      báo mã BR-QT-04. Hai guard này chạy trước 409, nên mở lại một chi nhánh DRAFT chưa có quản lý ra 400 chứ không phải 409.
//  A3  `bookedAppointments` = lịch hẹn BOOKED (mẫu của branch.mock) + đặt chỗ lưu trú BOOKED; `activeBoardingStays` = thú đang trong chuồng (CHECKED_IN /
//      OVERDUE, BR-CN-08) và chỉ có từ nguồn đăng ký. Lịch hẹn thật của appointment.mock chưa thấy được ở đây (cùng giới hạn của hủy hàng loạt BR-LH-10 ở GĐ1).
//  A4  `activeStaff` đếm nhân viên ACTIVE chưa bị khóa (`assignableStaffOf` cộng 4 chức vụ): mock không có hàm đếm nhân viên ACTIVE đang bị khóa tạm.
//      `stockItemCount` = số mặt hàng còn tồn > 0; `stockTotalQuantity` cộng mọi lô, kể cả lô hết hạn (phải điều chỉnh giảm UC76 mới về 0).
//  A5  `careTaskTargetBranchId` (nếu gửi) phải là chi nhánh KHÁC và ACTIVE, sai thì 400 BR-CN-09; vắng = hủy Care Task OPEN kèm lý do (Q1, quyết định T54).
//      Mock chuyển / hủy qua nguồn đăng ký `reassignCareTasks` (mặc định không làm gì). `expectedResumeOn` tùy chọn, không kiểm quá khứ (Q2 còn mở).
//  A6  Hủy hàng loạt (BR-CN-09) bỏ lịch hẹn mẫu và chuyển đặt chỗ lưu trú BOOKED sang CANCELLED qua mock lưu trú; câu lý do báo khách của mock lưu trú
//      hiện dùng câu của "giờ mở cửa thu hẹp" vì chưa có câu riêng cho tạm ngừng / đóng cửa.
//  A7  Không ghi audit (BR-QT-15) và không có 409 CONCURRENCY_CONFLICT: mock chạy đơn luồng.
import { vnDate } from '../../utils/vn-time';
import type { BranchLifecycle, ClosureImpact } from '../../types/branchlife';
import { BRANCH_LIFECYCLE_LIMITS } from '../../types/branchlife';
import { applyLifecycle, bookedOfBranch, hasOpeningHours, lifecycleOf } from './branch.mock';
import { denied, fail, has, invalid, isText, notFound, ok, ruleFail, tooLong, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { assignableStaffOf, hasActiveBranchManager } from './identity-admin.mock';
import { stockSnapshot } from './inventory.mock';
import { typeError } from './public.mock';
import { allOrderRows } from './sales.mock';

// ─── Nguồn đếm của module khác ───

type Count = (branchId: number) => number;

/** Số đếm mà mock hiện chưa đọc được; mặc định 0 để chi nhánh làm được mọi thao tác, luồng chính nối nguồn thật khi có. */
export interface ClosureSources {
  /** Thú đang trong chuồng: đặt chỗ CHECKED_IN / OVERDUE (`BoardingQueryApi.countActiveStaysByBranch`). */
  activeBoardingStays: Count;
  /** Visit chưa kết thúc (`VisitQueryApi.countOpenByBranch`). */
  openVisits: Count;
  /** Ca thu ngân OPEN (`CashierShiftQueryApi.hasOpenShift`, 0 hoặc 1 mỗi người mở). */
  openCashierShifts: Count;
  /** Care Task OPEN của chi nhánh. */
  openCareTasks: Count;
  /** Đơn online chưa COMPLETED / CANCELLED (shop). */
  unfinishedOnlineOrders: Count;
  /** Hoàn tiền chưa SUCCEEDED (returns). */
  pendingRefunds: Count;
  /** Phiếu chuyển kho IN_TRANSIT (stockops). */
  inTransitTransfers: Count;
  /** Phiên kiểm kê OPEN (stockops). */
  openStockCounts: Count;
  /**
   * `CareTaskApi.reassignBranch(from, to)`: Care Task OPEN chuyển sang chi nhánh `toBranchId`, hoặc (null) hủy kèm `reason`. Gọi mỗi lần tạm ngừng / đóng
   * thành công, sau khi đã kiểm điều kiện.
   */
  reassignCareTasks: (fromBranchId: number, toBranchId: number | null, reason: string) => void;
}

const NONE: Count = () => 0;
const DEFAULT_SOURCES: ClosureSources = {
  activeBoardingStays: NONE,
  openVisits: NONE,
  openCashierShifts: NONE,
  openCareTasks: NONE,
  unfinishedOnlineOrders: NONE,
  pendingRefunds: NONE,
  inTransitTransfers: NONE,
  openStockCounts: NONE,
  reassignCareTasks: () => undefined,
};
let sources: ClosureSources = { ...DEFAULT_SOURCES };

/** Đăng ký (đè từng phần) nguồn đếm; trả hàm khôi phục lại nguồn trước đó (dùng trong test). */
export function registerClosureSources(next: Partial<ClosureSources>): () => void {
  const before = sources;
  sources = { ...sources, ...next };
  return () => {
    sources = before;
  };
}

// ─── Tồn đọng (BR-CN-07, BR-CN-08) ───

const STAFF_ROLES = ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'] as const;

function impactOf(branchId: number): ClosureImpact {
  const booked = bookedOfBranch(branchId);
  const items = stockSnapshot(vnDate())
    .filter((i) => i.branchId === branchId)
    .map((i) => i.lots.reduce((sum, lot) => sum + lot.quantity, 0))
    .filter((quantity) => quantity > 0);
  const impact = {
    bookedAppointments: booked.appointments + booked.boardings,
    activeBoardingStays: sources.activeBoardingStays(branchId),
    openVisits: sources.openVisits(branchId),
    openCashierShifts: sources.openCashierShifts(branchId),
    openOrders: allOrderRows().filter((o) => o.branchId === branchId && (o.status === 'OPEN' || o.status === 'PENDING')).length,
    unfinishedOnlineOrders: sources.unfinishedOnlineOrders(branchId),
    openCareTasks: sources.openCareTasks(branchId),
    pendingRefunds: sources.pendingRefunds(branchId),
    inTransitTransfers: sources.inTransitTransfers(branchId),
    openStockCounts: sources.openStockCounts(branchId),
    stockItemCount: items.length,
    stockTotalQuantity: items.reduce((sum, quantity) => sum + quantity, 0),
    activeStaff: STAFF_ROLES.reduce((sum, role) => sum + assignableStaffOf(branchId, role).length, 0),
  };
  return { ...impact, suspendBlockers: suspendBlockers(impact), closeBlockers: closeBlockers(impact) };
}

type Counts = Omit<ClosureImpact, 'suspendBlockers' | 'closeBlockers'>;

/** BR-CN-08, mức tạm ngừng: việc đang dở tại chỗ. */
function suspendBlockers(c: Counts): string[] {
  return [
    c.openVisits > 0 && `còn ${c.openVisits} lượt khám đang mở`,
    c.activeBoardingStays > 0 && `còn ${c.activeBoardingStays} thú đang trong chuồng lưu trú`,
    c.openCashierShifts > 0 && `còn ${c.openCashierShifts} ca thu ngân đang mở`,
  ].filter((x): x is string => !!x);
}

/** BR-CN-08, mức đóng cửa: thêm Order, đơn online, hoàn tiền, kho, kiểm kê, tồn kho và nhân viên. */
function closeBlockers(c: Counts): string[] {
  return [
    ...suspendBlockers(c),
    c.openOrders > 0 && `còn ${c.openOrders} Order chưa thanh toán (OPEN / PENDING)`,
    c.unfinishedOnlineOrders > 0 && `còn ${c.unfinishedOnlineOrders} đơn online chưa hoàn tất hoặc hủy`,
    c.pendingRefunds > 0 && `còn ${c.pendingRefunds} khoản hoàn tiền chưa thành công`,
    c.inTransitTransfers > 0 && `còn ${c.inTransitTransfers} phiếu chuyển kho đang vận chuyển`,
    c.openStockCounts > 0 && `còn ${c.openStockCounts} phiên kiểm kê đang mở`,
    c.stockTotalQuantity > 0 && `còn tồn kho ${c.stockTotalQuantity} đơn vị của ${c.stockItemCount} mặt hàng (chuyển kho hoặc điều chỉnh giảm trước)`,
    c.activeStaff > 0 && `còn ${c.activeStaff} nhân viên đang hoạt động gắn chi nhánh (điều chuyển hoặc vô hiệu hóa trước)`,
  ].filter((x): x is string => !!x);
}

// ─── Đọc dữ liệu vào ───

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isSuperManager = (req: MockRequest) => req.account!.role === 'SUPER_MANAGER';
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);

/** `yyyy-MM-dd` là ngày có thật (không nhận 2026-02-30). */
function isDate(v: unknown): v is string {
  const m = typeof v === 'string' ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(v) : null;
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
}

/** Body phải là object và các trường đã gửi đúng kiểu (Jackson); `expectedResumeOn` chỉ có ở tạm ngừng. */
function bodyOf(body: unknown, suspend: boolean): Record<string, unknown> | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const b = body as Record<string, unknown>;
  const wrong =
    (has(b.reason) && typeof b.reason !== 'string') ||
    (has(b.cancelBookings) && typeof b.cancelBookings !== 'boolean') ||
    (has(b.careTaskTargetBranchId) && !isInt(b.careTaskTargetBranchId)) ||
    (suspend && has(b.expectedResumeOn) && !isDate(b.expectedResumeOn));
  return wrong ? null : b;
}

const bodyChecks = (b: Record<string, unknown>): Check[] => [
  ['reason', 'Vui lòng nhập lý do', !isText(b.reason)],
  ['reason', `Lý do tối đa ${BRANCH_LIFECYCLE_LIMITS.reason} ký tự`, tooLong(b.reason, BRANCH_LIFECYCLE_LIMITS.reason)],
  ['cancelBookings', 'Vui lòng chọn có hủy hàng loạt lịch đặt trước hay không', !has(b.cancelBookings)],
];

// ─── Handler ───

type Row = NonNullable<ReturnType<typeof lifecycleOf>>;
const stateError = (from: BranchLifecycle['status'], to: BranchLifecycle['status']) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái BranchTransitionHandler từ ${from} sang ${to}`, 409);

/** Cổng chung: id sai kiểu (400) → vai trò (403). Trả null nếu qua được. */
function gate(req: MockRequest): MockReply | null {
  return typeError(req.params, ['branchId']) ?? (isSuperManager(req) ? null : denied());
}

const closureImpact: MockHandler = (req) => {
  const refused = gate(req);
  if (refused) return refused;
  const branchId = Number(req.params.branchId);
  return lifecycleOf(branchId) ? ok(impactOf(branchId)) : notFound('chi nhánh', req.params.branchId);
};

/** Dùng chung cho tạm ngừng và đóng cửa: cùng body, cùng guard BR-CN-08 / BR-CN-09, khác điều kiện chặn và trạng thái đi. */
function leave(to: 'SUSPENDED' | 'CLOSED'): MockHandler {
  const verb = to === 'SUSPENDED' ? 'tạm ngừng' : 'đóng cửa';
  return (req) => {
    const badId = typeError(req.params, ['branchId']);
    if (badId) return badId;
    const b = bodyOf(req.body, to === 'SUSPENDED');
    if (!b) return malformed();
    if (!isSuperManager(req)) return denied();
    const bad = invalid(bodyChecks(b));
    if (bad) return bad;
    const branchId = Number(req.params.branchId);
    const current: Row | null = lifecycleOf(branchId);
    if (!current) return notFound('chi nhánh', req.params.branchId);

    const impact = impactOf(branchId);
    const blockers = to === 'SUSPENDED' ? impact.suspendBlockers : impact.closeBlockers;
    if (blockers.length) return ruleFail('BR-CN-08', `Không ${verb} được chi nhánh: ${blockers.join('; ')}`);
    if (impact.bookedAppointments > 0 && b.cancelBookings !== true) {
      return ruleFail('BR-CN-09', `Chi nhánh còn ${impact.bookedAppointments} lịch hẹn và đặt chỗ lưu trú đang chờ; hãy chọn hủy hàng loạt để tiếp tục`);
    }
    const target = has(b.careTaskTargetBranchId) ? (b.careTaskTargetBranchId as number) : null;
    if (target !== null && (target === branchId || lifecycleOf(target)?.status !== 'ACTIVE')) {
      return ruleFail('BR-CN-09', 'Chi nhánh nhận Care Task phải là chi nhánh khác đang hoạt động');
    }
    // FSM #20: tạm ngừng từ ACTIVE; đóng cửa từ ACTIVE hoặc SUSPENDED; DRAFT và CLOSED không đi tiếp.
    const from = current.status;
    if (to === 'SUSPENDED' ? from !== 'ACTIVE' : from !== 'ACTIVE' && from !== 'SUSPENDED') return stateError(from, to);

    const reason = (b.reason as string).trim();
    sources.reassignCareTasks(branchId, target, reason);
    const result =
      to === 'SUSPENDED'
        ? applyLifecycle(branchId, { to, reason, expectedResumeOn: has(b.expectedResumeOn) ? (b.expectedResumeOn as string) : null }, b.cancelBookings === true)
        : applyLifecycle(branchId, { to, reason }, b.cancelBookings === true);
    return ok(result, 200, to === 'SUSPENDED' ? 'Đã tạm ngừng chi nhánh' : 'Đã đóng cửa chi nhánh');
  };
}

const resume: MockHandler = (req) => {
  const refused = gate(req);
  if (refused) return refused;
  const branchId = Number(req.params.branchId);
  const current = lifecycleOf(branchId);
  if (!current) return notFound('chi nhánh', req.params.branchId);

  const noManager = !hasActiveBranchManager(branchId);
  const noHours = !hasOpeningHours(branchId);
  const missing = [noManager && 'chưa có BRANCH_MANAGER đang hoạt động', noHours && 'chưa cấu hình giờ mở cửa'].filter(Boolean);
  if (missing.length) return ruleFail(noManager ? 'BR-QT-04' : 'BR-CN-01', `Không mở lại được chi nhánh: ${missing.join(', ')}`);
  if (current.status !== 'SUSPENDED') return stateError(current.status, 'ACTIVE');
  return ok(applyLifecycle(branchId, { to: 'ACTIVE' }), 200, 'Đã mở lại chi nhánh');
};

export const branchlifeRoutes: Record<string, MockHandler> = {
  'GET /api/branches/:branchId/closure-impact': closureImpact,
  'POST /api/branches/:branchId/suspend': leave('SUSPENDED'),
  'POST /api/branches/:branchId/resume': resume,
  'POST /api/branches/:branchId/close': leave('CLOSED'),
};
