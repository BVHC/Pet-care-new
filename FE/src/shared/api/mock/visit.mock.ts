// Mock visit-v1 tiếp nhận, hàng đợi, gọi lượt (docs/api/visit-v1.md #1–9, #11, GET /me/queue; UC44–UC47; docs/adr/0058–0063) — T36.
// Chép từ BE (module visit: VisitController, MyQueueController, VisitCheckInService, VisitQueueService, VisitDispatchService,
// VisitProgressService, VisitReassignService, QueuePlacement, QueueOrder, QueueReorder). Thứ tự kiểm khi khác 00-method §3.5: đọc JSON /
// tham số path và Bean Validation chạy TRƯỚC `@PreAuthorize` (400 trước 403, kể cả ADMIN) → vai trò 403 → tồn tại 404 → chi nhánh 403 →
// guard 400 → 409 (từng endpoint ghi rõ ở chỗ định nghĩa, vì có chỗ 400 đứng trước 409 và có chỗ ngược lại). Hợp đồng lệch BE:
// docs/testcases/T36-fe.md. Không mô phỏng: audit (BE ghi VISIT_REASSIGNED), `visit_assignments`, khóa đồng thời, Care Task#6 (chưa nối ở BE),
// cờ needsReassign tự bật khi ADMIN khóa tài khoản (mock chỉ có dữ liệu mẫu cho cờ này; gán lại dùng điều kiện "người cũ không còn đủ điều kiện").
import type { Role } from '../../types/auth';
import type { QuotaGroup } from '../../types/branch';
import type { PriorityClass, Visit, VisitServiceGroup, VisitStatus } from '../../types/visit';
import { OPEN_VISIT_STATUSES, VISIT_STATUSES } from '../../types/visit';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { cancelAppointmentByVisit, checkInAppointment, findCheckInRef } from './appointment.mock';
import { acceptsAfterHours, branchBooking, isOpenAt } from './branch.mock';
import { catalogServices } from './catalog.mock';
import { findCustomer, markHasData } from './customer.mock';
import { petSummary, setPetOpenVisit } from './customer-pet.mock';
import { denied, fail, invalid, notFound, ok, ruleFail, scopeDenied, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { branchIdOf } from './identity.mock';
import { hasActiveBranchManager, staffNameOf, staffSummaryOf, type StaffSummary } from './identity-admin.mock';
import { cancelOrderOfVisit, openOrderForVisit, orderOfVisit, setVisitOrderAssignee, transferVisitOrderLines } from './sales.mock';

const MINUTE = 60_000;
/** [CFG] `visit.late_priority_minutes` (seed BE V2__seed_system_configs.sql). Hai mốc còn lại dùng chung với mock lịch hẹn. */
const LATE_PRIORITY_MINUTES = 15;
const MAX_REASON = 300;
const INTEGER = /^-?\d+$/;

const QUEUE_ROLES: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const DETAIL_ROLES: readonly Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const MY_QUEUE_ROLES: readonly Role[] = ['VET', 'CARETAKER'];
const RECEPTIONIST: readonly Role[] = ['RECEPTIONIST'];
const EMERGENCY_ROLES: readonly Role[] = ['RECEPTIONIST', 'VET'];
const REASSIGN_ROLES: readonly Role[] = ['BRANCH_MANAGER', 'SUPER_MANAGER'];

/** Người phụ trách của dữ liệu mẫu đã không còn trong danh bạ nhân viên (đã nghỉ / bị khóa): chỉ để hiển thị tên. */
const GHOST_STAFF: Record<number, string> = { 12: 'Phạm Quốc Việt' };

// ─── Dữ liệu ───

interface VisitRow {
  visitId: number;
  code: string;
  branchId: number;
  customerId: number;
  petId: number;
  appointmentId: number | null;
  serviceGroup: VisitServiceGroup;
  status: VisitStatus;
  priorityClass: PriorityClass;
  /** Micro giây từ epoch: sắp lại hàng đợi cộng trừ 1 µs như BE (`queue_sort_key` là timestamp µs). */
  queueSortKeyUs: number;
  assigneeId: number | null;
  needsReassign: boolean;
  orderId: number;
  checkedInBy: number;
  checkedInAt: string;
  calledAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}
interface State {
  seq: number;
  /** Bộ đếm mã `TN-yyMM-nnnnn` theo tháng nghiệp vụ (toàn chuỗi, như `document_counters`). */
  counters: Record<string, number>;
  visits: VisitRow[];
}

/** `Instant.toString` của Java: bỏ phần lẻ khi chẵn giây, 3 chữ số khi chẵn mili giây, còn lại 6 chữ số. */
function instantOf(us: number): string {
  const base = new Date(Math.floor(us / 1000)).toISOString().slice(0, 19);
  const micros = ((us % 1_000_000) + 1_000_000) % 1_000_000;
  if (micros === 0) return `${base}Z`;
  const digits = String(micros).padStart(6, '0');
  return `${base}.${micros % 1000 === 0 ? digits.slice(0, 3) : digits}Z`;
}
const isoOf = (ms: number) => instantOf(ms * 1000);

/** Mã `TN-yyMM-nnnnn` theo tháng của lúc tiếp nhận (giờ Việt Nam). */
function nextCode(s: State, checkedInMs: number): string {
  const period = vnDate(checkedInMs).slice(2, 7).replace('-', '');
  s.counters[period] = (s.counters[period] ?? 0) + 1;
  return `TN-${period}-${String(s.counters[period]).padStart(5, '0')}`;
}

/**
 * Dữ liệu mẫu theo lúc nạp (giờ Việt Nam), chi nhánh 1, khớp sales.mock (Order VISIT #2, #3, #7 và các Order OPEN sinh ở đây) và
 * appointment.mock (lịch #4 COMPLETED → lượt 2001, lịch #10 CHECKED_IN → lượt 2002). Chi tiết bảng ở docs/testcases/T36-fe.md.
 * Order của lượt đang mở được tạo ở đây (một lần, theo visitId) vì seed bán lẻ không có Order VISIT OPEN.
 */
function seed(now: number): State {
  const s: State = { seq: 3000, counters: {}, visits: [] };
  const dayStart = Date.parse(`${vnDate(now)}T00:00:00+07:00`);
  const ago = (minutes: number) => Math.max(now - minutes * MINUTE, dayStart);
  const rows: VisitRow[] = [];
  const base = (v: Pick<VisitRow, 'visitId' | 'customerId' | 'petId' | 'status' | 'priorityClass'> & Partial<VisitRow> & { checkedInMs: number; keyMs?: number }): VisitRow => {
    const { checkedInMs, keyMs, ...rest } = v;
    return {
      code: '',
      branchId: 1,
      appointmentId: null,
      serviceGroup: 'MEDICAL',
      queueSortKeyUs: (keyMs ?? checkedInMs) * 1000,
      assigneeId: null,
      needsReassign: false,
      orderId: 0,
      checkedInBy: 4,
      checkedInAt: isoOf(checkedInMs),
      calledAt: null,
      completedAt: null,
      cancelledAt: null,
      cancelReason: null,
      ...rest,
    };
  };

  // Đã hoàn tất, khớp Order VISIT PENDING / UNPAID của dữ liệu mẫu bán lẻ (thời điểm lấy từ chính Order).
  for (const [visitId, customerId, petId] of [[61, 1, 1], [58, 4, 5], [40, 4, 5]] as const) {
    const order = orderOfVisit(visitId);
    const checkedIn = order ? Date.parse(order.createdAt) : ago(150);
    const done = order?.pendingAt ? Date.parse(order.pendingAt) : checkedIn + 60 * MINUTE;
    rows.push(base({ visitId, customerId, petId, status: 'COMPLETED', priorityClass: 'WALK_IN', checkedInMs: checkedIn, assigneeId: 5, orderId: order?.orderId ?? 0, calledAt: isoOf(checkedIn + (done - checkedIn) / 3), completedAt: isoOf(done) }));
  }
  // Lịch hẹn #4 đã xong cách đây 13 ngày; seed bán lẻ không có Order cho lượt này nên `orderId` là 0.
  const past = findCheckInRef(4, now);
  if (past) {
    rows.push(base({ visitId: 2001, customerId: past.customerId, petId: past.petId, appointmentId: 4, serviceGroup: past.serviceGroup, status: 'COMPLETED', priorityClass: 'APPOINTMENT', checkedInMs: past.scheduledAt - 10 * MINUTE, keyMs: past.scheduledAt, assigneeId: 5, calledAt: isoOf(past.scheduledAt + 5 * MINUTE), completedAt: isoOf(past.scheduledAt + 45 * MINUTE) }));
  }
  // Lịch hẹn #10 (Lu, hẹn 08:30 hôm nay) đã CHECKED_IN: lượt đang khám do VET #5.
  const today = findCheckInRef(10, now);
  if (today) {
    const checkedIn = Math.min(today.scheduledAt - 5 * MINUTE, now);
    rows.push(base({ visitId: 2002, customerId: today.customerId, petId: today.petId, appointmentId: 10, serviceGroup: today.serviceGroup, status: 'IN_PROGRESS', priorityClass: 'APPOINTMENT', checkedInMs: checkedIn, keyMs: today.scheduledAt, assigneeId: 5, calledAt: isoOf(Math.min(checkedIn + 10 * MINUTE, now)) }));
  }
  // Còn lại hôm nay: cấp cứu chờ gán · walk-in chờ đã gán VET · Thẩm mỹ chờ đã gán CARETAKER · Thẩm mỹ đang làm mà người phụ trách #12 không còn.
  rows.push(base({ visitId: 2101, customerId: 4, petId: 5, status: 'WAITING', priorityClass: 'EMERGENCY', checkedInMs: ago(20) }));
  rows.push(base({ visitId: 2102, customerId: 1, petId: 3, status: 'WAITING', priorityClass: 'WALK_IN', checkedInMs: ago(30), assigneeId: 5 }));
  rows.push(base({ visitId: 2103, customerId: 1, petId: 2, serviceGroup: 'GROOMING', status: 'WAITING', priorityClass: 'WALK_IN', checkedInMs: ago(15), assigneeId: 6 }));
  rows.push(base({ visitId: 2104, customerId: 1, petId: 1, serviceGroup: 'GROOMING', status: 'IN_PROGRESS', priorityClass: 'WALK_IN', checkedInMs: ago(95), assigneeId: 12, needsReassign: true, calledAt: isoOf(ago(60)) }));

  const serviceOf: Record<number, number> = { 2002: today?.serviceId ?? 1, 2101: 1, 2102: 1, 2103: 3, 2104: 3 };
  for (const row of rows.sort((a, b) => Date.parse(a.checkedInAt) - Date.parse(b.checkedInAt) || a.visitId - b.visitId)) {
    row.code = nextCode(s, Date.parse(row.checkedInAt));
    if (row.visitId in serviceOf) {
      const order = openOrderForVisit({ visitId: row.visitId, branchId: row.branchId, customerId: row.customerId, serviceId: serviceOf[row.visitId], assigneeId: row.assigneeId, actorId: row.checkedInBy, createdAt: row.checkedInAt });
      row.orderId = order?.orderId ?? 0;
    }
    s.visits.push(row);
    if ((OPEN_VISIT_STATUSES as readonly string[]).includes(row.status)) setPetOpenVisit(row.petId, true);
  }
  return s;
}

const STATE_KEY = 'petcare-mock-visit';

/** Đọc trạng thái; lần đầu thì dựng dữ liệu mẫu rồi LƯU NGAY, vì dựng mẫu tạo Order bên sales (một lần, không lặp ở mỗi lần đọc). */
function load(now = Date.now()): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) return JSON.parse(raw) as State;
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const fresh = seed(now);
  save(fresh);
  return fresh;
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Hiển thị ───

function view(row: VisitRow): Visit {
  const pet = petSummary(row.petId);
  return {
    visitId: row.visitId,
    code: row.code,
    branchId: row.branchId,
    customerId: row.customerId,
    customerName: findCustomer(row.customerId)?.fullName ?? `Khách #${row.customerId}`,
    petId: row.petId,
    petName: pet?.name ?? `Thú cưng #${row.petId}`,
    species: pet?.species ?? 'OTHER',
    appointmentId: row.appointmentId,
    serviceGroup: row.serviceGroup,
    status: row.status,
    priorityClass: row.priorityClass,
    queueSortKey: instantOf(row.queueSortKeyUs),
    assigneeId: row.assigneeId,
    assigneeName: row.assigneeId == null ? null : (staffNameOf(row.assigneeId) ?? GHOST_STAFF[row.assigneeId] ?? `Nhân viên #${row.assigneeId}`),
    needsReassign: row.needsReassign,
    orderId: row.orderId,
    checkedInAt: row.checkedInAt,
    calledAt: row.calledAt,
    completedAt: row.completedAt,
    cancelledAt: row.cancelledAt,
    cancelReason: row.cancelReason,
  };
}

/** BR-TN-04 (QueueOrder): cấp cứu → lịch hẹn → walk-in, cùng hạng theo khóa sắp xếp, hòa thì theo id. */
const RANK: Record<PriorityClass, number> = { EMERGENCY: 0, APPOINTMENT: 1, WALK_IN: 2 };
const byQueueOrder = (a: VisitRow, b: VisitRow) => RANK[a.priorityClass] - RANK[b.priorityClass] || a.queueSortKeyUs - b.queueSortKeyUs || a.visitId - b.visitId;

// ─── Dùng chung ───

const me = (req: MockRequest) => req.account!;
const branchOf = (req: MockRequest) => me(req).branchId ?? branchIdOf(me(req).id) ?? null;
const isReply = (x: unknown): x is MockReply => typeof x === 'object' && x !== null && 'status' in x && typeof (x as MockReply).status === 'number' && !('visitId' in x);
const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const isJsonObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const conflict = (what: string, from: string, to: string) => fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái ${what} từ ${from} sang ${to}`, 409);
const visitNotFound = (visitId: number) => notFound('lượt tiếp nhận', visitId);

/** Số nguyên JSON như Jackson của BE: số nguyên hoặc chuỗi toàn chữ số; số lẻ, boolean, đối tượng → sai kiểu. */
function asLong(v: unknown): number | null | undefined {
  if (v === undefined || v === null) return null;
  if (typeof v === 'number') return Number.isSafeInteger(v) ? v : undefined;
  if (typeof v === 'string' && INTEGER.test(v)) return Number(v);
  return undefined;
}

/** Chuỗi JSON như Jackson: chuỗi, hoặc số / boolean ép thành chuỗi (đã dò: `{"reason":5}` → 200, lý do "5"); mảng, đối tượng → sai kiểu. */
function asText(v: unknown): string | null | undefined {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return undefined;
}

/** Boolean JSON như Jackson: true / false, "true" / "false", 0 / 1. */
function asBoolean(v: unknown): boolean | null | undefined {
  if (v === undefined || v === null) return null;
  if (typeof v === 'boolean') return v;
  if (v === 'true' || v === 1) return true;
  if (v === 'false' || v === 0) return false;
  return undefined;
}

/** Tham số path số nguyên: sai kiểu → 400 trước cả kiểm vai trò (MethodArgumentTypeMismatchException). */
function idParam(req: MockRequest, name: string): number | MockReply {
  const raw = req.params[name];
  return INTEGER.test(raw) && Number.isSafeInteger(Number(raw)) ? Number(raw) : badParam(name);
}

const allow = (roles: readonly Role[], h: MockHandler): MockHandler => (req) => (roles.includes(me(req).role) ? h(req) : denied());

// ─── #1 Tiếp nhận ───

const OUT_OF_HOURS_NO_FLAG = 'Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ';
const OUT_OF_HOURS_NOT_EMERGENCY = 'Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu';

/** Đọc JSON (Jackson) rồi Bean Validation của CreateVisitRequest; cả hai chạy trước @PreAuthorize. */
function readCheckIn(body: unknown) {
  if (!isJsonObject(body)) return malformed();
  const petId = asLong(body.petId);
  const appointmentId = asLong(body.appointmentId);
  const serviceId = asLong(body.serviceId);
  const emergency = asBoolean(body.isEmergency);
  if ([petId, appointmentId, serviceId, emergency].includes(undefined)) return malformed();
  const checks: Check[] = [
    ['petId', 'Thiếu thú cưng', petId === null],
    ['petId', 'Mã thú cưng không hợp lệ', petId != null && petId <= 0],
    ['appointmentId', 'Mã lịch hẹn không hợp lệ', appointmentId != null && appointmentId <= 0],
    ['serviceId', 'Mã dịch vụ không hợp lệ', serviceId != null && serviceId <= 0],
  ];
  return invalid(checks) ?? { petId: petId as number, appointmentId: appointmentId as number | null, serviceId: serviceId as number | null, emergency: emergency === true };
}

/** Hạng ưu tiên và khóa sắp xếp lúc tiếp nhận (QuotaPlacement): cấp cứu → giữ ưu tiên lịch hẹn nếu không trễ quá mốc → walk-in. */
function placementOf(emergency: boolean, scheduledAt: number | null, now: number): { priorityClass: PriorityClass; keyMs: number } {
  if (emergency) return { priorityClass: 'EMERGENCY', keyMs: now };
  if (scheduledAt !== null && now <= scheduledAt + LATE_PRIORITY_MINUTES * MINUTE) return { priorityClass: 'APPOINTMENT', keyMs: scheduledAt };
  return { priorityClass: 'WALK_IN', keyMs: now };
}

/**
 * VisitCheckInService.checkInVisit: thú 404 → lịch hẹn 404, chi nhánh khác 400 BR-TN-02 → dịch vụ walk-in 404 → BR-TN-01 (thiếu dịch vụ → lệch thú → thú mất)
 * → BR-CN-01 → BR-CN-05 → BR-LH-01 → lịch hẹn 409 → cửa sổ 400 BR-TN-02 → tạo Lượt + mở Order. Mọi 400 của visit đứng trước câu ghi đầu tiên.
 */
const checkInVisit: MockHandler = (req) => {
  const parsed = readCheckIn(req.body);
  if (isReply(parsed)) return parsed;
  if (!RECEPTIONIST.includes(me(req).role)) return denied();
  const { petId, appointmentId, emergency } = parsed;
  const branchId = branchOf(req)!;
  const now = Date.now();

  const pet = petSummary(petId);
  if (!pet) return notFound('thú cưng', petId);
  const ref = appointmentId === null ? undefined : findCheckInRef(appointmentId, now);
  if (appointmentId !== null) {
    if (!ref) return notFound('lịch hẹn', appointmentId);
    if (ref.branchId !== branchId) return ruleFail('BR-TN-02', 'Lịch hẹn thuộc chi nhánh khác');
  }
  const serviceId = ref ? ref.serviceId : parsed.serviceId;
  const service = serviceId === null ? undefined : catalogServices().find((x) => x.serviceId === serviceId);
  if (serviceId !== null && !service) return notFound('dịch vụ', serviceId);

  if (!service) return ruleFail('BR-TN-01', 'Khách không hẹn trước phải chọn dịch vụ');
  if (ref && ref.petId !== pet.petId) return ruleFail('BR-TN-01', 'Thú cưng không khớp lịch hẹn');
  if (pet.deceasedOn) return ruleFail('BR-TN-01', 'Thú cưng đã mất, không tiếp nhận được');
  const booking = branchBooking(branchId);
  if (!booking) return fail('INTERNAL_ERROR', `Chi nhánh của nhân viên không tồn tại: ${branchId}`, 500);
  if (booking.branch.status !== 'ACTIVE') return ruleFail('BR-CN-01', 'Chi nhánh chưa hoạt động, chưa tiếp nhận được');
  if (!isOpenAt(branchId, now)) {
    if (!acceptsAfterHours(branchId)) return ruleFail('BR-CN-05', OUT_OF_HOURS_NO_FLAG);
    if (!emergency) return ruleFail('BR-CN-05', OUT_OF_HOURS_NOT_EMERGENCY);
  }
  if (ref) {
    if (!service.isActive) return ruleFail('BR-LH-01', 'Dịch vụ của lịch hẹn đã ngừng kinh doanh');
  } else if (!service.isActive || service.group === 'BOARDING' || !booking.isServiceEnabled(service.serviceId)) {
    return ruleFail('BR-LH-01', 'Dịch vụ không tiếp nhận được tại chi nhánh này');
  }

  const s = load(now);
  const visitId = s.seq + 1;
  let group: QuotaGroup = service.group as QuotaGroup;
  let scheduledAt: number | null = null;
  if (ref) {
    const checkedIn = checkInAppointment(ref.appointmentId, branchId, now, visitId);
    if (!checkedIn.ok) {
      if (checkedIn.reason === 'STATE') return conflict('lịch hẹn', checkedIn.status, 'CHECKED_IN');
      if (checkedIn.reason === 'BRANCH') return ruleFail('BR-TN-02', 'Lịch hẹn thuộc chi nhánh khác');
      return ruleFail(
        'BR-TN-02',
        `Chỉ tiếp nhận lịch hẹn của hôm nay, sớm nhất ${checkedIn.earlyMinutes} phút trước giờ hẹn và trễ nhất ${checkedIn.noShowAfterMinutes} phút sau giờ hẹn`,
      );
    }
    group = checkedIn.serviceGroup;
    scheduledAt = checkedIn.scheduledAt;
  }
  const { priorityClass, keyMs } = placementOf(emergency, scheduledAt, now);
  const order = openOrderForVisit({ visitId, branchId, customerId: pet.customerId, serviceId: service.serviceId, assigneeId: null, actorId: me(req).id, createdAt: isoOf(now) });
  s.seq = visitId;
  const row: VisitRow = {
    visitId,
    code: nextCode(s, now),
    branchId,
    customerId: pet.customerId,
    petId,
    appointmentId,
    serviceGroup: group,
    status: 'WAITING',
    priorityClass,
    queueSortKeyUs: keyMs * 1000,
    assigneeId: null,
    needsReassign: false,
    orderId: order?.orderId ?? 0,
    checkedInBy: me(req).id,
    checkedInAt: isoOf(now),
    calledAt: null,
    completedAt: null,
    cancelledAt: null,
    cancelReason: null,
  };
  s.visits.push(row);
  save(s);
  markHasData(pet.customerId);
  setPetOpenVisit(petId, true);
  return ok(view(row), 201, 'Đã tiếp nhận');
};

// ─── #2, #3, #4 Xem ───

const isDay = (v: string) => /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) && new Date(Date.parse(v)).toISOString().slice(0, 10) === v;
const TRUE_WORDS = ['true', 'on', 'yes', '1'];
const FALSE_WORDS = ['false', 'off', 'no', '0'];

/**
 * #2 (VisitQueueService.listQueue + queueSpec, A1): tham số sai kiểu → 400 theo thứ tự tham số của controller (date, status, serviceGroup, assigneeId,
 * needsReassign) → vai trò → lọc theo chi nhánh của người gọi. Không gửi `date`: lượt chưa kết thúc của mọi ngày, riêng `status` COMPLETED / CANCELLED
 * thì chỉ hôm nay; gửi `date`: lượt check-in trong ngày đó (giờ Việt Nam), không gửi `status` thì mọi trạng thái.
 */
const listQueue: MockHandler = (req) => {
  const { date, status, serviceGroup, assigneeId, needsReassign } = req.query;
  if (date !== undefined && !isDay(date)) return badParam('date');
  if (status !== undefined && !(VISIT_STATUSES as readonly string[]).includes(status)) return badParam('status');
  if (serviceGroup !== undefined && serviceGroup !== 'MEDICAL' && serviceGroup !== 'GROOMING') return badParam('serviceGroup');
  if (assigneeId !== undefined && !INTEGER.test(assigneeId)) return badParam('assigneeId');
  const flag = needsReassign?.toLowerCase();
  if (flag !== undefined && !TRUE_WORDS.includes(flag) && !FALSE_WORDS.includes(flag)) return badParam('needsReassign');
  if (!QUEUE_ROLES.includes(me(req).role)) return denied();

  const now = Date.now();
  const unfinished = OPEN_VISIT_STATUSES as readonly string[];
  const day = date ?? (status !== undefined && !unfinished.includes(status) ? vnDate(now) : undefined);
  const dayStart = day === undefined ? 0 : Date.parse(`${day}T00:00:00+07:00`);
  const dayEnd = day === undefined ? 0 : Date.parse(`${addDays(day, 1)}T00:00:00+07:00`);
  const branchId = branchOf(req);
  const rows = load(now)
    .visits.filter((v) => {
      if (v.branchId !== branchId) return false;
      if (status !== undefined ? v.status !== status : date === undefined && !unfinished.includes(v.status)) return false;
      if (day !== undefined) {
        const checkedIn = Date.parse(v.checkedInAt);
        if (checkedIn < dayStart || checkedIn >= dayEnd) return false;
      }
      return (
        (serviceGroup === undefined || v.serviceGroup === serviceGroup) &&
        (assigneeId === undefined || v.assigneeId === Number(assigneeId)) &&
        (flag === undefined || v.needsReassign === TRUE_WORDS.includes(flag))
      );
    })
    .sort(byQueueOrder);
  return ok(rows.map(view));
};

/** #3 (listMyQueue): lượt WAITING / IN_PROGRESS gán cho người gọi, mọi chi nhánh, thứ tự BR-TN-04. */
const listMyQueue: MockHandler = (req) =>
  ok(
    load()
      .visits.filter((v) => v.assigneeId === me(req).id && (OPEN_VISIT_STATUSES as readonly string[]).includes(v.status))
      .sort(byQueueOrder)
      .map(view),
  );

/** #4 (getVisit): 404 → nhân viên chi nhánh khác 403 (SUPER_MANAGER toàn chuỗi, A14). */
const getVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!DETAIL_ROLES.includes(me(req).role)) return denied();
  const row = load().visits.find((v) => v.visitId === visitId);
  if (!row) return visitNotFound(visitId);
  if (me(req).role !== 'SUPER_MANAGER' && row.branchId !== branchOf(req)) return scopeDenied();
  return ok(view(row));
};

// ─── Điều phối: FSM, gán, sắp lại, cấp cứu (#5–#7) ───

/** 03 §4 Visit (VisitTransitionHandler): #2 gán WAITING→WAITING, #3 gọi, #6 hủy, #4 gán lại IN_PROGRESS→IN_PROGRESS, #5 hoàn tất. */
const ALLOWED: Record<VisitStatus, readonly VisitStatus[]> = {
  WAITING: ['WAITING', 'IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['IN_PROGRESS', 'COMPLETED'],
  COMPLETED: [],
  CANCELLED: [],
};
const FSM_NAME = 'lượt tiếp nhận';
const transition = (from: VisitStatus, to: VisitStatus): MockReply | null => (ALLOWED[from].includes(to) ? null : conflict(FSM_NAME, from, to));

const ROLE_OF_GROUP: Record<VisitServiceGroup, Role> = { MEDICAL: 'VET', GROOMING: 'CARETAKER' };
const WRONG_ROLE = 'Lượt Khám/Tiêm phải gán bác sĩ thú y; lượt Thẩm mỹ phải gán nhân viên chăm sóc';

/** BR-TN-05 dạng điều kiện (VisitDispatchService.isAssignable): còn ACTIVE, không khóa, cùng chi nhánh của lượt, đúng chức vụ theo nhóm. */
const isAssignable = (staff: StaffSummary | null, row: VisitRow) =>
  staff !== null && staff.active && staff.branchId === row.branchId && staff.role === ROLE_OF_GROUP[row.serviceGroup];

/** BR-TN-05 (requireAssignable): không hoạt động / khác chi nhánh trước, rồi mới sai chức vụ. */
function requireAssignable(staff: StaffSummary | null, row: VisitRow): MockReply | null {
  if (staff === null || !staff.active || staff.branchId !== row.branchId) return ruleFail('BR-TN-05', 'Nhân viên không hoạt động tại chi nhánh này');
  return staff.role === ROLE_OF_GROUP[row.serviceGroup] ? null : ruleFail('BR-TN-05', WRONG_ROLE);
}

/** Lượt theo id (404) rồi chi nhánh của nhân viên (403); SUPER_MANAGER không qua cửa này (chỉ #4, #9). */
function ownedVisit(s: State, req: MockRequest, visitId: number): VisitRow | MockReply {
  const row = s.visits.find((v) => v.visitId === visitId);
  if (!row) return visitNotFound(visitId);
  return row.branchId === branchOf(req) ? row : scopeDenied();
}
const isRow = (x: VisitRow | MockReply): x is VisitRow => 'visitId' in x;

/** Body bắt buộc có `assigneeId` (@NotNull @Positive): sai kiểu → MALFORMED, thiếu / không dương → VALIDATION_FAILED. */
function readAssignee(body: unknown, extra: (b: Record<string, unknown>) => Check[] = () => []): { assigneeId: number; body: Record<string, unknown> } | MockReply {
  if (!isJsonObject(body)) return malformed();
  const assigneeId = asLong(body.assigneeId);
  if (assigneeId === undefined) return malformed();
  const bad = invalid([
    ['assigneeId', 'Thiếu nhân viên được gán', assigneeId === null],
    ['assigneeId', 'Mã nhân viên không hợp lệ', assigneeId !== null && assigneeId <= 0],
    ...extra(body),
  ]);
  return bad ?? { assigneeId: assigneeId as number, body };
}

/** #5 (VisitDispatchService.assignVisit): path → body → vai trò → 404 → chi nhánh 403 → BR-TN-05 400 → 409 (400 đứng TRƯỚC 409) → ghi. */
const assignVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const parsed = readAssignee(req.body);
  if ('status' in parsed) return parsed;
  if (!RECEPTIONIST.includes(me(req).role)) return denied();
  const s = load();
  const row = ownedVisit(s, req, visitId);
  if (!isRow(row)) return row;
  const staff = staffSummaryOf(parsed.assigneeId);
  const notAssignable = requireAssignable(staff, row);
  if (notAssignable) return notAssignable;
  const wrongState = transition(row.status, 'WAITING');
  if (wrongState) return wrongState;
  const result = (): MockReply => ok({ visit: view(row), assigneeOffline: !staff!.online }, 200, 'Đã gán nhân viên');
  if (row.assigneeId === parsed.assigneeId) return result();
  const previous = row.assigneeId;
  row.assigneeId = parsed.assigneeId;
  save(s);
  setVisitOrderAssignee(visitId, parsed.assigneeId);
  if (previous !== null) transferVisitOrderLines(visitId, previous, parsed.assigneeId);
  return result();
};

/** #7 (markVisitEmergency): path → vai trò (lễ tân, VET) → 404 → chi nhánh 403 → 409 trạng thái → 409 đã là cấp cứu → hạng EMERGENCY, khóa = lúc đánh dấu. */
const markEmergency: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!EMERGENCY_ROLES.includes(me(req).role)) return denied();
  const s = load();
  const row = ownedVisit(s, req, visitId);
  if (!isRow(row)) return row;
  const wrongState = transition(row.status, 'WAITING');
  if (wrongState) return wrongState;
  if (row.priorityClass === 'EMERGENCY') return conflict('hạng ưu tiên của lượt', 'EMERGENCY', 'EMERGENCY');
  row.priorityClass = 'EMERGENCY';
  row.queueSortKeyUs = Date.now() * 1000;
  save(s);
  return ok(view(row), 200, 'Đã xác định ca cấp cứu');
};

interface Reorder {
  row: VisitRow;
  priorityClass: PriorityClass;
  keyUs: number;
}

/**
 * QueueReorder.placeBefore: lượt được sắp nhận hạng của lượt đích, khóa = khóa đích − 1 µs (nhưng phải lớn hơn khóa lượt liền trước cùng hạng ít nhất 1 µs);
 * lượt cùng hạng từ lượt đích trở đi có khóa ≤ khóa vừa đặt thì dồn +1 µs, dừng ở lượt đầu tiên không phải đổi. Rỗng = không đổi gì.
 */
function placeBefore(waiting: VisitRow[], moved: VisitRow, target: VisitRow): Reorder[] {
  const sorted = [...waiting].sort(byQueueOrder);
  const movedAt = sorted.indexOf(moved);
  const priorityClass = target.priorityClass;
  if (movedAt === sorted.indexOf(target) - 1 && moved.priorityClass === priorityClass) return [];
  sorted.splice(movedAt, 1);
  const targetAt = sorted.indexOf(target);
  const previous = targetAt > 0 ? sorted[targetAt - 1] : null;
  let key = target.queueSortKeyUs - 1;
  if (previous && previous.priorityClass === priorityClass) key = Math.max(key, previous.queueSortKeyUs + 1);
  const changes: Reorder[] = [{ row: moved, priorityClass, keyUs: key }];
  let last = key;
  for (let i = targetAt; i < sorted.length; i += 1) {
    const next = sorted[i];
    if (next.priorityClass !== priorityClass || next.queueSortKeyUs > last) break;
    last += 1;
    changes.push({ row: next, priorityClass, keyUs: last });
  }
  return changes;
}

/**
 * #6 (moveVisitInQueue): path → body → vai trò → nguồn 404 → chi nhánh 403 → chính nó 400 → đích 404 → đích khác chi nhánh 400 → ranh giới cấp cứu 400 → nguồn 409 → đích 409.
 * Trả hàng đợi chưa kết thúc của chi nhánh (như #2 không lọc).
 */
const moveVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!isJsonObject(req.body)) return malformed();
  const targetId = asLong(req.body.placeBeforeVisitId);
  if (targetId === undefined) return malformed();
  const bad = invalid([
    ['placeBeforeVisitId', 'Thiếu lượt đích', targetId === null],
    ['placeBeforeVisitId', 'Mã lượt đích không hợp lệ', targetId !== null && targetId <= 0],
  ]);
  if (bad) return bad;
  if (!RECEPTIONIST.includes(me(req).role)) return denied();
  const s = load();
  const moved = ownedVisit(s, req, visitId);
  if (!isRow(moved)) return moved;
  if (targetId === visitId) return ruleFail('BR-TN-04', 'Không đặt lượt trước chính nó được');
  const target = s.visits.find((v) => v.visitId === targetId);
  if (!target) return visitNotFound(targetId as number);
  if (target.branchId !== moved.branchId) return ruleFail('BR-TN-04', 'Lượt đích thuộc chi nhánh khác');
  if ((moved.priorityClass === 'EMERGENCY') !== (target.priorityClass === 'EMERGENCY')) {
    return ruleFail('BR-TN-04', 'Ca cấp cứu chỉ sắp trong nhóm cấp cứu; muốn đưa lượt lên nhóm cấp cứu hãy xác định ca cấp cứu');
  }
  const sourceState = transition(moved.status, 'WAITING');
  if (sourceState) return sourceState;
  const targetState = transition(target.status, 'WAITING');
  if (targetState) return targetState;
  const changes = placeBefore(
    s.visits.filter((v) => v.branchId === moved.branchId && v.status === 'WAITING'),
    moved,
    target,
  );
  for (const c of changes) Object.assign(c.row, { priorityClass: c.priorityClass, queueSortKeyUs: c.keyUs });
  if (changes.length) save(s);
  return ok(
    s.visits
      .filter((v) => v.branchId === moved.branchId && (OPEN_VISIT_STATUSES as readonly string[]).includes(v.status))
      .sort(byQueueOrder)
      .map(view),
    200,
    'Đã sắp lại hàng đợi',
  );
};

// ─── Tiến trình: gọi, hủy, gán lại lượt đã gọi (#8, #11, #9) ───

/** #8 (callVisit): path → vai trò → 404 → chi nhánh 403 → không phải người được gán 403 (kể cả chưa gán) → BR-TN-05 400 cho người gọi → 409 → IN_PROGRESS. */
const callVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!MY_QUEUE_ROLES.includes(me(req).role)) return denied();
  const s = load();
  const row = ownedVisit(s, req, visitId);
  if (!isRow(row)) return row;
  if (row.assigneeId !== me(req).id) return scopeDenied();
  const notAssignable = requireAssignable(staffSummaryOf(me(req).id), row);
  if (notAssignable) return notAssignable;
  if (row.status !== 'WAITING') return conflict(FSM_NAME, row.status, 'IN_PROGRESS');
  row.status = 'IN_PROGRESS';
  row.calledAt = isoOf(Date.now());
  save(s);
  return ok(view(row), 200, 'Đã gọi lượt');
};

const petHasOtherOpenVisit = (s: State, row: VisitRow) =>
  s.visits.some((v) => v.petId === row.petId && v.visitId !== row.visitId && (OPEN_VISIT_STATUSES as readonly string[]).includes(v.status));

/**
 * #11 (cancelVisit, BR-TN-07): path → body tùy chọn (lý do ≤ 300) → vai trò → 404 → chi nhánh 403 → 409 (chỉ WAITING). Hủy: lượt CANCELLED, lịch hẹn (nếu có)
 * CANCELLED nguồn STAFF không lý do và KHÔNG tính hủy muộn, Order CANCELLED (VISIT_CANCELLED) kèm dòng tự sinh.
 */
const cancelVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const body = req.body ?? {};
  if (!isJsonObject(body)) return malformed();
  const reasonText = asText(body.reason);
  if (reasonText === undefined) return malformed();
  const bad = invalid([['reason', `Lý do tối đa ${MAX_REASON} ký tự`, reasonText !== null && reasonText.length > MAX_REASON]]);
  if (bad) return bad;
  if (!RECEPTIONIST.includes(me(req).role)) return denied();
  const s = load();
  const row = ownedVisit(s, req, visitId);
  if (!isRow(row)) return row;
  const wrongState = transition(row.status, 'CANCELLED');
  if (wrongState) return wrongState;
  const at = isoOf(Date.now());
  const reason = reasonText?.trim() ?? '';
  Object.assign(row, { status: 'CANCELLED', cancelReason: reason === '' ? null : reason, cancelledAt: at });
  save(s);
  if (row.appointmentId !== null) cancelAppointmentByVisit(row.appointmentId, at);
  cancelOrderOfVisit(visitId, me(req).id, at);
  if (!petHasOtherOpenVisit(s, row)) setPetOpenVisit(row.petId, false);
  return ok(view(row), 200, 'Đã hủy lượt');
};

/**
 * #9 (reassignCalledVisit): path → body (assigneeId, lý do bắt buộc) → vai trò (BRANCH_MANAGER, SUPER_MANAGER) → 404 → phạm vi 403 (quản lý: chi nhánh của lượt;
 * SUPER_MANAGER: chi nhánh KHÔNG còn BRANCH_MANAGER ACTIVE) → 409 không IN_PROGRESS → BR-TN-08 400 (người cũ còn đủ điều kiện) → BR-TN-05 400 (người mới).
 */
const reassignVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (isJsonObject(req.body) && asText(req.body.reason) === undefined) return malformed();
  const parsed = readAssignee(req.body, (b) => [
    ['reason', 'Bắt buộc nhập lý do gán lại', (asText(b.reason) ?? '').trim() === ''],
    ['reason', `Lý do tối đa ${MAX_REASON} ký tự`, (asText(b.reason) ?? '').trim().length > MAX_REASON],
  ]);
  if ('status' in parsed) return parsed;
  if (!REASSIGN_ROLES.includes(me(req).role)) return denied();
  const s = load();
  const row = s.visits.find((v) => v.visitId === visitId);
  if (!row) return visitNotFound(visitId);
  if (me(req).role === 'SUPER_MANAGER' ? hasActiveBranchManager(row.branchId) : row.branchId !== branchOf(req)) return scopeDenied();
  if (row.status !== 'IN_PROGRESS') return conflict(FSM_NAME, row.status, 'IN_PROGRESS');
  const previous = row.assigneeId;
  if (isAssignable(previous === null ? null : staffSummaryOf(previous), row)) {
    return ruleFail('BR-TN-08', 'Người phụ trách vẫn đang hoạt động; chỉ gán lại lượt đã gọi khi người phụ trách không còn hoạt động');
  }
  const notAssignable = requireAssignable(staffSummaryOf(parsed.assigneeId), row);
  if (notAssignable) return notAssignable;
  Object.assign(row, { assigneeId: parsed.assigneeId, needsReassign: false });
  save(s);
  if (previous !== null) transferVisitOrderLines(visitId, previous, parsed.assigneeId);
  return ok(view(row), 200, 'Đã gán lại lượt');
};

/** visit-v1 #1–9, #11 và GET /me/queue (#10 hoàn tất và các endpoint bệnh án thuộc T37). */
export const visitRoutes: Record<string, MockHandler> = {
  'POST /api/visits': checkInVisit,
  'GET /api/visits': listQueue,
  'GET /api/me/queue': allow(MY_QUEUE_ROLES, listMyQueue),
  'GET /api/visits/:visitId': getVisit,
  'POST /api/visits/:visitId/assign': assignVisit,
  'POST /api/visits/:visitId/move': moveVisit,
  'POST /api/visits/:visitId/mark-emergency': markEmergency,
  'POST /api/visits/:visitId/call': callVisit,
  'POST /api/visits/:visitId/reassign': reassignVisit,
  'POST /api/visits/:visitId/cancel': cancelVisit,
};
