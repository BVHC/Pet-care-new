// Mock appointment-v1 (UC39, UC40): khung giờ trống, đặt / xem / đổi / hủy lịch hẹn, hạn chế đặt online (#1–9).
// Quyền, thứ tự kiểm và câu báo lỗi chép từ BE module/appointment (AvailableSlotService, AppointmentBookingService,
// AppointmentQueryService, AppointmentChangeService, BookingRestrictionService, BookingRestrictionStatusService).
// Đọc chéo như các QueryApi của BE: chi nhánh / khung / quota (branch.mock), dịch vụ (catalog.mock),
// thú (customer-pet.mock), khách (customer.mock).
// Tác vụ hệ thống chạy "lười" ở mỗi lần đọc: ST05 chuyển NO_SHOW (BR-LH-08) kèm đánh giá hạn chế (BR-LH-09);
// thú đã mất / đã chuyển chủ thì lịch BOOKED bị phòng khám hủy (03 §3 #5, BR-KH-05, BR-KH-08).
// Giả định của mock (ghi ở docs/testcases/T33-fe.md): chưa có module lưu trú nên BR-LH-09 chỉ đếm vi phạm của lịch hẹn;
// gỡ hạn chế không ghi audit vào mock quản trị.
import type { Role } from '../../types/auth';
import type {
  Appointment,
  AppointmentChannel,
  AppointmentStatus,
  BookingRestriction,
  CancelSource,
  RestrictionStatus,
  Slot,
} from '../../types/appointment';
import type { QuotaGroup } from '../../types/branch';
import { addDays, SLOT_MINUTES, toHHmm, toMinutes } from '../../utils/branch-schedule';
import { branchBooking } from './branch.mock';
import { catalogServices } from './catalog.mock';
import { customerOfAccount, findCustomer, markHasData } from './customer.mock';
import { markPetHasBooking, petSummary } from './customer-pet.mock';
import { denied, fail, invalid, isText, notFound, ok, ruleFail, scopeDenied, tooLong, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { branchIdOf } from './identity.mock';

/** Mặc định của các [CFG] (02 BR-LH-04…09, BR-TN-02). */
export const APPOINTMENT_MOCK_CFG = {
  minLeadHours: 24,
  maxAdvanceDays: 30,
  maxBookedPerPet: 2,
  maxReschedules: 3,
  rescheduleMinLeadHours: 12,
  lateCancelHours: 12,
  noShowAfterMinutes: 30,
  checkInEarlyMinutes: 30,
  violationThreshold: 3,
  windowDays: 90,
  durationDays: 30,
} as const;
const CFG = APPOINTMENT_MOCK_CFG;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// ─── Dữ liệu ───

interface Row {
  appointmentId: number;
  code: string;
  customerId: number;
  petId: number;
  branchId: number;
  serviceId: number;
  serviceGroup: QuotaGroup;
  slotDate: string;
  slotStart: string;
  status: AppointmentStatus;
  channel: AppointmentChannel;
  bookedBy: number;
  rescheduleCount: number;
  lateCancel: boolean;
  cancelSource: CancelSource | null;
  cancelReason: string | null;
  cancelledAt: string | null;
  note: string | null;
  visitId: number | null;
}

interface RestrictionRow extends BookingRestriction {
  liftedAt: string | null;
  liftedBy: number | null;
  liftReason: string | null;
}

interface State {
  nextId: number;
  nextCode: number;
  nextRestrictionId: number;
  appointments: Row[];
  restrictions: RestrictionRow[];
}

// ─── Giờ Việt Nam (UTC+7, không đổi giờ mùa hè) ───

const VN_DATE = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' });
const VN_PARTS = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});
const vnDate = (ms: number) => VN_DATE.format(ms);
/** "HH:mm dd/MM/yyyy" như message BR-LH-09 của BE (ADR-0055 §8). */
function vnDateTime(ms: number) {
  const p = Object.fromEntries(VN_PARTS.formatToParts(ms).map((x) => [x.type, x.value]));
  return `${p.hour}:${p.minute} ${p.day}/${p.month}/${p.year}`;
}
const instantOf = (date: string, time: string) => Date.parse(`${date}T${time}:00+07:00`);
const startOf = (a: Pick<Row, 'slotDate' | 'slotStart'>) => instantOf(a.slotDate, a.slotStart);
const iso = (ms: number) => new Date(ms).toISOString();
const yymmdd = (date: string) => date.slice(2).replace(/-/g, '');
/** ADR-0045: LH-<yyMMdd ngày đặt, giờ VN>-<số thứ tự ≥ 4 chữ số>. */
const codeOf = (bookedOn: string, n: number) => `LH-${yymmdd(bookedOn)}-${String(n).padStart(4, '0')}`;

// ─── Dữ liệu mẫu, tính theo hôm nay ───

const CLINIC_REASON = {
  holiday: 'Chi nhánh nghỉ vào ngày hẹn',
  deceased: 'Thú cưng đã được ghi nhận là đã mất',
  transferred: 'Thú cưng đã được chuyển sang chủ mới',
} as const;

/**
 * Chi nhánh 1. Khách #1 khach@petcare.vn (Mochi #1, Bim #2), khách #3 tại quầy Lê Hoàng Long (Lu #4).
 * Không có lịch của Tôm #3, Miu #5: hai thú này còn xóa được (BR-KH-06) ở mock thú cưng.
 * Khách #3 đang bị hạn chế đặt online (2 lần không đến + 1 lần hủy muộn trong 90 ngày, BR-LH-09).
 */
function seed(now: number): State {
  const today = vnDate(now);
  const d = (n: number) => addDays(today, n);
  const at = (date: string, time: string) => iso(instantOf(date, time));
  const rows: Row[] = [];
  const add = (r: Pick<Row, 'customerId' | 'petId' | 'serviceId' | 'serviceGroup' | 'slotDate' | 'slotStart' | 'status'> & Partial<Row>) => {
    const appointmentId = rows.length + 1;
    rows.push({
      appointmentId,
      code: codeOf(addDays(r.slotDate, -5), appointmentId),
      branchId: 1,
      channel: r.customerId === 1 ? 'ONLINE' : 'COUNTER',
      bookedBy: r.customerId === 1 ? 7 : 4,
      rescheduleCount: 0,
      lateCancel: false,
      cancelSource: null,
      cancelReason: null,
      cancelledAt: null,
      note: null,
      visitId: null,
      ...r,
    });
  };
  // Khách #1 — sắp tới
  add({ customerId: 1, petId: 1, serviceId: 1, serviceGroup: 'MEDICAL', slotDate: d(0), slotStart: '11:00', status: 'BOOKED', note: 'Bé hay gãi vùng bụng' });
  add({ customerId: 1, petId: 1, serviceId: 2, serviceGroup: 'MEDICAL', slotDate: d(3), slotStart: '09:00', status: 'BOOKED' });
  add({ customerId: 1, petId: 2, serviceId: 3, serviceGroup: 'GROOMING', slotDate: d(1), slotStart: '10:00', status: 'BOOKED', channel: 'COUNTER', bookedBy: 4, rescheduleCount: 3 });
  // Khách #1 — đã qua / đã hủy
  add({ customerId: 1, petId: 1, serviceId: 1, serviceGroup: 'MEDICAL', slotDate: d(-13), slotStart: '10:00', status: 'COMPLETED', visitId: 2001 });
  add({ customerId: 1, petId: 2, serviceId: 2, serviceGroup: 'MEDICAL', slotDate: d(-5), slotStart: '14:00', status: 'CANCELLED', cancelSource: 'CUSTOMER', lateCancel: true, cancelledAt: at(d(-5), '07:15'), cancelReason: 'Bận việc đột xuất' });
  add({ customerId: 1, petId: 1, serviceId: 3, serviceGroup: 'GROOMING', slotDate: d(-30), slotStart: '09:00', status: 'CANCELLED', cancelSource: 'CLINIC', cancelledAt: at(d(-32), '10:00'), cancelReason: CLINIC_REASON.holiday });
  // Khách #3 — 3 vi phạm → hạn chế từ 30 phút sau lần không đến cuối (ST05)
  add({ customerId: 3, petId: 4, serviceId: 1, serviceGroup: 'MEDICAL', slotDate: d(-25), slotStart: '09:00', status: 'NO_SHOW' });
  add({ customerId: 3, petId: 4, serviceId: 3, serviceGroup: 'GROOMING', slotDate: d(-15), slotStart: '16:00', status: 'CANCELLED', cancelSource: 'STAFF', lateCancel: true, cancelledAt: at(d(-15), '09:00'), cancelReason: 'Khách gọi báo bận' });
  add({ customerId: 3, petId: 4, serviceId: 2, serviceGroup: 'MEDICAL', slotDate: d(-10), slotStart: '10:30', status: 'NO_SHOW' });
  // Hôm nay / ngày mai
  add({ customerId: 3, petId: 4, serviceId: 1, serviceGroup: 'MEDICAL', slotDate: d(0), slotStart: '08:30', status: 'CHECKED_IN', visitId: 2002 });
  add({ customerId: 3, petId: 4, serviceId: 2, serviceGroup: 'MEDICAL', slotDate: d(1), slotStart: '10:00', status: 'BOOKED', note: 'Tái khám' });

  const restrictionStart = instantOf(d(-10), '10:30') + CFG.noShowAfterMinutes * MINUTE;
  return {
    nextId: rows.length + 1,
    nextCode: rows.length + 1,
    nextRestrictionId: 2,
    appointments: rows,
    restrictions: [
      {
        restrictionId: 1,
        customerId: 3,
        startsAt: iso(restrictionStart),
        endsAt: iso(restrictionStart + CFG.durationDays * DAY),
        violationCount: 3,
        liftedAt: null,
        liftedBy: null,
        liftReason: null,
      },
    ],
  };
}

const STATE_KEY = 'petcare-mock-appointments';

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

/** Đọc trạng thái rồi chạy các tác vụ hệ thống đến hạn (xem đầu file). */
function load(now: number): State {
  let s: State | null = null;
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) s = JSON.parse(raw) as State;
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const fresh = !s;
  const state = s ?? seed(now);
  if (sweep(state, now) || fresh) save(state);
  return state;
}

// ─── Hạn chế đặt online (BR-LH-09, ADR-0049 §7, ADR-0055) ───

/** `starts_at ≤ at < ends_at` và chưa gỡ sớm. */
const activeRestriction = (s: State, customerId: number, at: number) =>
  s.restrictions.find(
    (r) => r.customerId === customerId && r.liftedAt === null && Date.parse(r.startsAt) <= at && at < Date.parse(r.endsAt),
  );

/** Thời điểm vi phạm: hủy muộn theo `cancelledAt`; không đến theo giờ hẹn (ADR-0049 §7). */
const violationTimes = (s: State, customerId: number) =>
  s.appointments
    .filter((a) => a.customerId === customerId)
    .flatMap((a) => {
      if (a.status === 'NO_SHOW') return [startOf(a)];
      if (a.status === 'CANCELLED' && a.lateCancel && a.cancelledAt) return [Date.parse(a.cancelledAt)];
      return [];
    });

/**
 * Sau một vi phạm: đang bị hạn chế thì thôi; đếm vi phạm sau `max(at − 90 ngày, starts_at lần hạn chế gần nhất)`;
 * đủ 3 → hạn chế 30 ngày từ `at`. Mỗi vi phạm góp vào tối đa một lần hạn chế (ADR-0057).
 */
function evaluateAfterViolation(s: State, customerId: number, at: number) {
  if (activeRestriction(s, customerId, at)) return;
  const windowStart = at - CFG.windowDays * DAY;
  const latest = Math.max(windowStart, ...s.restrictions.filter((r) => r.customerId === customerId).map((r) => Date.parse(r.startsAt)));
  const count = violationTimes(s, customerId).filter((t) => t > latest && t <= at).length;
  if (count < CFG.violationThreshold) return;
  s.restrictions.push({
    restrictionId: s.nextRestrictionId++,
    customerId,
    startsAt: iso(at),
    endsAt: iso(at + CFG.durationDays * DAY),
    violationCount: count,
    liftedAt: null,
    liftedBy: null,
    liftReason: null,
  });
}

// ─── Tác vụ hệ thống ───

function clinicCancel(a: Row, reason: string, now: number) {
  a.status = 'CANCELLED';
  a.cancelSource = 'CLINIC';
  a.cancelReason = reason;
  a.cancelledAt = iso(now);
  a.lateCancel = false;
}

/** true nếu có thay đổi. */
function sweep(s: State, now: number): boolean {
  let changed = false;
  for (const a of s.appointments.filter((x) => x.status === 'BOOKED')) {
    const pet = petSummary(a.petId);
    if (!pet) continue;
    // Thú mất hủy cả lịch đã quá giờ mà ST05 chưa chuyển (ADR-0057 §2).
    if (pet.deceasedOn) clinicCancel(a, CLINIC_REASON.deceased, now);
    else if (pet.customerId !== a.customerId) clinicCancel(a, CLINIC_REASON.transferred, now);
    else continue;
    changed = true;
  }
  const due = s.appointments
    .filter((a) => a.status === 'BOOKED' && now > startOf(a) + CFG.noShowAfterMinutes * MINUTE)
    .sort((a, b) => startOf(a) - startOf(b));
  for (const a of due) {
    a.status = 'NO_SHOW';
    evaluateAfterViolation(s, a.customerId, startOf(a) + CFG.noShowAfterMinutes * MINUTE);
    changed = true;
  }
  return changed;
}

// ─── Quy tắc dùng chung ───

const ACTIVE: readonly AppointmentStatus[] = ['BOOKED', 'CHECKED_IN', 'COMPLETED'];
const GROUP_LABEL: Record<QuotaGroup, string> = { MEDICAL: 'Khám/Tiêm', GROOMING: 'Thẩm mỹ' };

/** BR-LH-04 (BookingWindow, ADR-0046): khách theo ngày ≤ hôm nay + 30 và giờ ≥ now + 24h; lễ tân: khung chưa bắt đầu. */
function inWindow(customer: boolean, now: number, date: string, start: string) {
  const at = instantOf(date, start);
  if (!customer) return at > now;
  const today = vnDate(now);
  return date >= today && date <= addDays(today, CFG.maxAdvanceDays) && at >= now + CFG.minLeadHours * HOUR;
}

const windowError = (customer: boolean) =>
  customer
    ? ruleFail('BR-LH-04', `Chỉ đặt được khung bắt đầu sau ít nhất ${CFG.minLeadHours} giờ và trong vòng ${CFG.maxAdvanceDays} ngày tới`)
    : ruleFail('BR-LH-04', 'Khung giờ đã bắt đầu');

const slotError = () => ruleFail('BR-LH-02', 'Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút');
const fullError = () => ruleFail('BR-LH-03', 'Khung giờ đã hết chỗ');

const countInSlot = (s: State, branchId: number, group: QuotaGroup, date: string, start: string) =>
  s.appointments.filter(
    (a) => ACTIVE.includes(a.status) && a.branchId === branchId && a.serviceGroup === group && a.slotDate === date && a.slotStart === start,
  ).length;

/** BR-LH-05 (b), (c) trên mọi chi nhánh; `exceptId` = lịch đang đổi khung. */
function petConflict(s: State, petId: number, group: QuotaGroup, date: string, start: string, exceptId?: number): MockReply | null {
  const mine = s.appointments.filter((a) => a.petId === petId && a.appointmentId !== exceptId && ACTIVE.includes(a.status));
  if (mine.some((a) => a.slotDate === date && a.slotStart === start)) {
    return ruleFail('BR-LH-05', 'Thú cưng đã có lịch hẹn khác trùng khung giờ này');
  }
  if (mine.some((a) => a.slotDate === date && a.serviceGroup === group)) {
    return ruleFail('BR-LH-05', `Thú cưng đã có lịch ${GROUP_LABEL[group]} trong ngày này`);
  }
  return null;
}

/** canReschedule / canCancel theo người xem (AppointmentActions, ADR-0047 §6, ADR-0048 §10). */
function flagsFor(a: Omit<Row, 'bookedBy'>, role: Role, now: number) {
  if (a.status !== 'BOOKED' || (role !== 'CUSTOMER' && role !== 'RECEPTIONIST')) return { canReschedule: false, canCancel: false };
  const start = startOf(a);
  const leadOk = role === 'CUSTOMER' ? now + CFG.rescheduleMinLeadHours * HOUR <= start : now < start;
  return { canReschedule: a.rescheduleCount < CFG.maxReschedules && leadOk, canCancel: now < start };
}

function toResponse({ bookedBy: _bookedBy, ...a }: Row, role: Role, now: number): Appointment {
  return {
    ...a,
    customerName: findCustomer(a.customerId)?.fullName ?? `Khách #${a.customerId}`,
    petName: petSummary(a.petId)?.name ?? `Thú cưng #${a.petId}`,
    branchName: branchBooking(a.branchId)?.branch.name ?? `Chi nhánh #${a.branchId}`,
    serviceName: catalogServices().find((x) => x.serviceId === a.serviceId)?.name ?? `Dịch vụ #${a.serviceId}`,
    ...flagsFor(a, role, now),
  };
}

// ─── Đọc tham số ───

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const ID_RE = /^-?\d+$/;
const isDate = (v: unknown): v is string => typeof v === 'string' && DATE_RE.test(v) && !Number.isNaN(Date.parse(v));
const isTime = (v: unknown): v is string => typeof v === 'string' && TIME_RE.test(v);
const bodyOf = (b: unknown) => (b ?? {}) as Record<string, unknown>;
const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const missingParam = (name: string) => fail('MALFORMED_REQUEST', `Thiếu tham số '${name}'`, 400);
const brokenProfile = (accountId: number) => fail('INTERNAL_ERROR', `Tài khoản khách #${accountId} không có hồ sơ khách`, 500);
/** Lịch hẹn cũng có `status` (chuỗi); phản hồi mock có `status` là mã HTTP. */
const isReply = (x: Row | MockReply): x is MockReply => typeof x.status === 'number';
const optionalText = (v: unknown) => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null);

/** Tham số tùy chọn: rỗng = không gửi (ADR-0048 §5). */
const param = (q: Record<string, string>, name: string) => (q[name] === undefined || q[name] === '' ? undefined : q[name]);

const roleOf = (req: MockRequest) => req.account!.role;
const isCustomer = (req: MockRequest) => roleOf(req) === 'CUSTOMER';

// ─── #1 Khung giờ trống ───

const listSlots: MockHandler = (req) => {
  if (!ID_RE.test(req.params.branchId)) return badParam('branchId');
  const serviceId = param(req.query, 'serviceId');
  const date = param(req.query, 'date');
  if (serviceId === undefined) return missingParam('serviceId');
  if (!ID_RE.test(serviceId)) return badParam('serviceId');
  if (date === undefined) return missingParam('date');
  if (!isDate(date)) return badParam('date');
  const role = roleOf(req);
  if (role !== 'CUSTOMER' && role !== 'RECEPTIONIST') return denied();
  const customer = role === 'CUSTOMER';
  const branchId = Number(req.params.branchId);
  // Lễ tân: phạm vi chi nhánh trước mọi lần đọc (ADR-0046 §3).
  if (!customer && branchIdOf(req.account!.id) !== branchId) return scopeDenied();
  const booking = branchBooking(branchId);
  if (!booking || (customer && booking.branch.status !== 'ACTIVE')) return notFound('chi nhánh', branchId);
  const service = catalogServices().find((x) => x.serviceId === Number(serviceId));
  if (!service) return notFound('dịch vụ', serviceId);
  if (booking.branch.status !== 'ACTIVE') return ruleFail('BR-LH-01', 'Chi nhánh chưa nhận đặt lịch');
  if (!service.isActive || service.group === 'BOARDING' || !booking.isServiceEnabled(service.serviceId)) {
    return ruleFail('BR-LH-01', 'Dịch vụ không đặt được tại chi nhánh này');
  }
  const now = Date.now();
  const s = load(now);
  const group = service.group;
  const slots: Slot[] = booking
    .slotsOn(date)
    .filter((start) => inWindow(customer, now, date, start))
    .map((start): Slot => {
      const quota = booking.quotaFor(group, date, start);
      const booked = countInSlot(s, branchId, group, date, start);
      return { slotStart: start, slotEnd: toHHmm(toMinutes(start) + SLOT_MINUTES), quota, booked, status: booked >= quota ? 'FULL' : 'AVAILABLE' };
    });
  return ok(slots);
};

// ─── #2 Đặt lịch ───

function bookBodyError(b: Record<string, unknown>): MockReply | null {
  const ids = ['petId', 'branchId', 'serviceId'] as const;
  if (ids.some((k) => b[k] != null && !Number.isInteger(b[k]))) return malformed();
  if ((b.slotDate != null && !isDate(b.slotDate)) || (b.slotStart != null && !isTime(b.slotStart))) return malformed();
  if (b.note != null && typeof b.note !== 'string') return malformed();
  return invalid([
    ['petId', 'Thiếu thú cưng', b.petId == null],
    ['petId', 'Mã thú cưng không hợp lệ', Number.isInteger(b.petId) && (b.petId as number) <= 0],
    ['branchId', 'Thiếu chi nhánh', b.branchId == null],
    ['branchId', 'Mã chi nhánh không hợp lệ', Number.isInteger(b.branchId) && (b.branchId as number) <= 0],
    ['serviceId', 'Thiếu dịch vụ', b.serviceId == null],
    ['serviceId', 'Mã dịch vụ không hợp lệ', Number.isInteger(b.serviceId) && (b.serviceId as number) <= 0],
    ['slotDate', 'Thiếu ngày hẹn', b.slotDate == null],
    ['slotStart', 'Thiếu giờ bắt đầu của khung', b.slotStart == null],
    ['note', 'Ghi chú tối đa 500 ký tự', tooLong(b.note, 500)],
  ]);
}

const bookAppointment: MockHandler = (req) => {
  const b = bodyOf(req.body);
  const bad = bookBodyError(b);
  if (bad) return bad;
  const role = roleOf(req);
  if (role !== 'CUSTOMER' && role !== 'RECEPTIONIST') return denied();
  const customer = role === 'CUSTOMER';
  const petId = b.petId as number;
  const branchId = b.branchId as number;
  const serviceId = b.serviceId as number;
  const date = b.slotDate as string;
  const start = b.slotStart as string;
  if (!customer && branchIdOf(req.account!.id) !== branchId) return scopeDenied();
  const own = customer ? customerOfAccount(req.account!.id) : undefined;
  if (customer && !own) return brokenProfile(req.account!.id);

  const pet = petSummary(petId);
  if (!pet || (own && pet.customerId !== own.customerId)) return notFound('thú cưng', petId);
  const service = catalogServices().find((x) => x.serviceId === serviceId);
  if (!service) return notFound('dịch vụ', serviceId);
  const booking = branchBooking(branchId);
  if (!booking || (customer && booking.branch.status !== 'ACTIVE')) return notFound('chi nhánh', branchId);
  const now = Date.now();
  const s = load(now);

  if (booking.branch.status !== 'ACTIVE') return ruleFail('BR-LH-01', 'Chi nhánh chưa nhận đặt lịch');
  if (!service.isActive || service.group === 'BOARDING' || !booking.isServiceEnabled(serviceId)) {
    return ruleFail('BR-LH-01', 'Dịch vụ không đặt được tại chi nhánh này');
  }
  const group = service.group;
  if (own?.linkDecisionPending) {
    return ruleFail('BR-TK-19', 'Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám');
  }
  const restriction = own && activeRestriction(s, own.customerId, now);
  if (restriction) {
    return ruleFail(
      'BR-LH-09',
      `Bạn đang bị hạn chế đặt lịch online đến ${vnDateTime(Date.parse(restriction.endsAt))}, vui lòng liên hệ chi nhánh để đặt lịch`,
    );
  }
  if (pet.deceasedOn) return ruleFail('BR-KH-05', 'Thú cưng đã mất, không đặt được lịch');
  if (!inWindow(customer, now, date, start)) return windowError(customer);
  if (!booking.slotsOn(date).includes(start)) return slotError();
  if (s.appointments.filter((a) => a.petId === petId && a.status === 'BOOKED').length >= CFG.maxBookedPerPet) {
    return ruleFail('BR-LH-05', `Thú cưng đã có ${CFG.maxBookedPerPet} lịch hẹn đang chờ`);
  }
  const conflict = petConflict(s, petId, group, date, start);
  if (conflict) return conflict;
  if (countInSlot(s, branchId, group, date, start) >= booking.quotaFor(group, date, start)) return fullError();

  const row: Row = {
    appointmentId: s.nextId++,
    code: codeOf(vnDate(now), s.nextCode++),
    customerId: pet.customerId,
    petId,
    branchId,
    serviceId,
    serviceGroup: group,
    slotDate: date,
    slotStart: start,
    status: 'BOOKED',
    channel: customer ? 'ONLINE' : 'COUNTER',
    bookedBy: req.account!.id,
    rescheduleCount: 0,
    lateCancel: false,
    cancelSource: null,
    cancelReason: null,
    cancelledAt: null,
    note: optionalText(b.note),
    visitId: null,
  };
  s.appointments.push(row);
  save(s);
  markPetHasBooking(petId);
  markHasData(pet.customerId);
  return ok(toResponse(row, role, now), 201, 'Đã đặt lịch hẹn');
};

// ─── #3, #4 Xem ───

const LIST_ROLES: readonly Role[] = ['CUSTOMER', 'BRANCH_MANAGER', 'RECEPTIONIST'];
const DETAIL_ROLES: readonly Role[] = [...LIST_ROLES, 'VET', 'CARETAKER'];

/** Chỉ lịch BOOKED hôm nay, giờ hẹn − 30' ≤ now ≤ giờ hẹn + 30' [CFG] (ADR-0048 §6). */
const checkInReady = (a: Row, now: number) =>
  a.status === 'BOOKED' &&
  a.slotDate === vnDate(now) &&
  startOf(a) - CFG.checkInEarlyMinutes * MINUTE <= now &&
  now <= startOf(a) + CFG.noShowAfterMinutes * MINUTE;

const listAppointments: MockHandler = (req) => {
  const q = req.query;
  const filters: Record<string, string | undefined> = {};
  for (const name of ['branchId', 'petId', 'customerId', 'page', 'size'] as const) {
    filters[name] = param(q, name);
    if (filters[name] !== undefined && !ID_RE.test(filters[name]!)) return badParam(name);
  }
  for (const name of ['date', 'from', 'to'] as const) {
    filters[name] = param(q, name);
    if (filters[name] !== undefined && !isDate(filters[name])) return badParam(name);
  }
  const status = param(q, 'status');
  if (status !== undefined && !(['BOOKED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW'] as string[]).includes(status)) {
    return badParam('status');
  }
  const ready = param(q, 'checkInReady');
  if (ready !== undefined && !['true', 'false', '1', '0'].includes(ready)) return badParam('checkInReady');
  const role = roleOf(req);
  if (!LIST_ROLES.includes(role)) return denied();

  let customerScope: number | null = null;
  let branchScope: number | null = null;
  if (role === 'CUSTOMER') {
    const own = customerOfAccount(req.account!.id);
    if (!own) return brokenProfile(req.account!.id);
    customerScope = own.customerId;
  } else {
    branchScope = branchIdOf(req.account!.id) ?? null;
    if (filters.branchId !== undefined && Number(filters.branchId) !== branchScope) return scopeDenied();
  }
  const now = Date.now();
  const s = load(now);
  const num = (k: string) => (filters[k] === undefined ? undefined : Number(filters[k]));
  const found = s.appointments
    .filter(
      (a) =>
        (customerScope === null || a.customerId === customerScope) &&
        (branchScope === null || a.branchId === branchScope) &&
        (num('branchId') === undefined || a.branchId === num('branchId')) &&
        (num('petId') === undefined || a.petId === num('petId')) &&
        (num('customerId') === undefined || a.customerId === num('customerId')) &&
        (!filters.date || a.slotDate === filters.date) &&
        (!filters.from || a.slotDate >= filters.from) &&
        (!filters.to || a.slotDate <= filters.to) &&
        (!status || a.status === status) &&
        (ready !== 'true' && ready !== '1' ? true : checkInReady(a, now)),
    )
    .sort((a, b) => a.slotDate.localeCompare(b.slotDate) || a.slotStart.localeCompare(b.slotStart) || a.appointmentId - b.appointmentId);
  const page = Math.max(num('page') ?? 0, 0);
  const size = Math.min(Math.max(num('size') ?? 20, 1), 100);
  return ok({
    content: found.slice(page * size, page * size + size).map((a) => toResponse(a, role, now)),
    page,
    size,
    totalElements: found.length,
    totalPages: Math.ceil(found.length / size),
  });
};

/**
 * Lịch hẹn theo phạm vi người gọi: khách thấy lịch của khách khác như không tồn tại (404),
 * nhân viên khác chi nhánh → 403 (ADR-0048 §4, ADR-0049 §1).
 */
function appointmentFor(req: MockRequest, s: State): Row | MockReply {
  const id = Number(req.params.appointmentId);
  const a = s.appointments.find((x) => x.appointmentId === id);
  if (isCustomer(req)) {
    const own = customerOfAccount(req.account!.id);
    if (!own) return brokenProfile(req.account!.id);
    return a && a.customerId === own.customerId ? a : notFound('lịch hẹn', id);
  }
  if (!a) return notFound('lịch hẹn', id);
  return branchIdOf(req.account!.id) === a.branchId ? a : scopeDenied();
}

const getAppointment: MockHandler = (req) => {
  if (!ID_RE.test(req.params.appointmentId)) return badParam('appointmentId');
  if (!DETAIL_ROLES.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const a = appointmentFor(req, s);
  return isReply(a) ? a : ok(toResponse(a, roleOf(req), now));
};

// ─── #5, #6 Đổi khung, hủy ───

const CHANGE_ROLES: readonly Role[] = ['CUSTOMER', 'RECEPTIONIST'];
const stateError = (from: AppointmentStatus, to: AppointmentStatus) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái lịch hẹn từ ${from} sang ${to}`, 409);

const reschedule: MockHandler = (req) => {
  if (!ID_RE.test(req.params.appointmentId)) return badParam('appointmentId');
  const b = bodyOf(req.body);
  if ((b.slotDate != null && !isDate(b.slotDate)) || (b.slotStart != null && !isTime(b.slotStart))) return malformed();
  const bad = invalid([
    ['slotDate', 'Thiếu ngày hẹn', b.slotDate == null],
    ['slotStart', 'Thiếu giờ bắt đầu của khung', b.slotStart == null],
  ]);
  if (bad) return bad;
  const role = roleOf(req);
  if (!CHANGE_ROLES.includes(role)) return denied();
  const customer = role === 'CUSTOMER';
  const now = Date.now();
  const s = load(now);
  const a = appointmentFor(req, s);
  if (isReply(a)) return a;
  // 409 ngay sau khi khóa, trước mọi guard 400 (ADR-0049 §3).
  if (a.status !== 'BOOKED') return stateError(a.status, 'BOOKED');
  const date = b.slotDate as string;
  const start = b.slotStart as string;
  // Trùng khung hiện tại: 200, không ghi, không mất lượt (ADR-0049 §4).
  if (date === a.slotDate && start === a.slotStart) return ok(toResponse(a, role, now), 200, 'Đã đổi khung giờ');

  if (a.rescheduleCount >= CFG.maxReschedules) {
    return ruleFail('BR-LH-06', `Lịch hẹn đã đổi đủ ${CFG.maxReschedules} lần, muốn đổi nữa vui lòng hủy rồi đặt lại`);
  }
  if (customer && now + CFG.rescheduleMinLeadHours * HOUR > startOf(a)) {
    return ruleFail('BR-LH-06', `Chỉ đổi được lịch trước giờ hẹn ít nhất ${CFG.rescheduleMinLeadHours} giờ, vui lòng liên hệ chi nhánh`);
  }
  if (!customer && now >= startOf(a)) return ruleFail('BR-LH-06', 'Lịch hẹn đã tới giờ, không đổi được nữa');
  if (!inWindow(customer, now, date, start)) return windowError(customer);
  const booking = branchBooking(a.branchId);
  if (!booking?.slotsOn(date).includes(start)) return slotError();
  const conflict = petConflict(s, a.petId, a.serviceGroup, date, start, a.appointmentId);
  if (conflict) return conflict;
  if (countInSlot(s, a.branchId, a.serviceGroup, date, start) >= booking.quotaFor(a.serviceGroup, date, start)) return fullError();

  a.slotDate = date;
  a.slotStart = start;
  a.rescheduleCount += 1;
  save(s);
  return ok(toResponse(a, role, now), 200, 'Đã đổi khung giờ');
};

const cancel: MockHandler = (req) => {
  if (!ID_RE.test(req.params.appointmentId)) return badParam('appointmentId');
  const b = bodyOf(req.body);
  if (b.reason != null && typeof b.reason !== 'string') return malformed();
  const bad = invalid([['reason', 'Lý do tối đa 300 ký tự', tooLong(b.reason, 300)]]);
  if (bad) return bad;
  const role = roleOf(req);
  if (!CHANGE_ROLES.includes(role)) return denied();
  const now = Date.now();
  const s = load(now);
  const a = appointmentFor(req, s);
  if (isReply(a)) return a;
  if (a.status !== 'BOOKED') return stateError(a.status, 'CANCELLED');
  const start = startOf(a);
  if (now >= start) return ruleFail('BR-LH-07', 'Đã tới giờ hẹn, không hủy được nữa');
  const late = start - now < CFG.lateCancelHours * HOUR;
  a.status = 'CANCELLED';
  a.cancelSource = role === 'CUSTOMER' ? 'CUSTOMER' : 'STAFF';
  a.cancelReason = optionalText(b.reason);
  a.cancelledAt = iso(now);
  a.lateCancel = late;
  if (late) evaluateAfterViolation(s, a.customerId, now);
  save(s);
  return ok(toResponse(a, role, now), 200, 'Đã hủy lịch hẹn');
};

// ─── #7–9 Hạn chế đặt online ───

const NOT_RESTRICTED: RestrictionStatus = { restricted: false, restriction: null };

function statusOf(s: State, customerId: number, now: number): RestrictionStatus {
  const r = activeRestriction(s, customerId, now);
  if (!r) return NOT_RESTRICTED;
  const { restrictionId, startsAt, endsAt, violationCount } = r;
  return { restricted: true, restriction: { restrictionId, customerId, startsAt, endsAt, violationCount } };
}

const myRestriction: MockHandler = (req) => {
  if (!isCustomer(req)) return denied();
  const own = customerOfAccount(req.account!.id);
  if (!own) return brokenProfile(req.account!.id);
  const now = Date.now();
  return ok(statusOf(load(now), own.customerId, now));
};

/** Toàn chuỗi, không qua phạm vi chi nhánh (ADR-0055 §2). */
const STAFF_RESTRICTION: readonly Role[] = ['RECEPTIONIST', 'BRANCH_MANAGER'];

const customerRestriction: MockHandler = (req) => {
  if (!ID_RE.test(req.params.customerId)) return badParam('customerId');
  if (!STAFF_RESTRICTION.includes(roleOf(req))) return denied();
  const customerId = Number(req.params.customerId);
  if (!findCustomer(customerId)) return notFound('khách hàng', customerId);
  const now = Date.now();
  return ok(statusOf(load(now), customerId, now));
};

const liftRestriction: MockHandler = (req) => {
  if (!ID_RE.test(req.params.customerId)) return badParam('customerId');
  const b = bodyOf(req.body);
  if (b.reason != null && typeof b.reason !== 'string') return malformed();
  const bad = invalid([
    ['reason', 'Vui lòng nhập lý do', !isText(b.reason)],
    ['reason', 'Lý do tối đa 300 ký tự', isText(b.reason) && b.reason.trim().length > 300],
  ]);
  if (bad) return bad;
  if (!STAFF_RESTRICTION.includes(roleOf(req))) return denied();
  const customerId = Number(req.params.customerId);
  if (!findCustomer(customerId)) return notFound('khách hàng', customerId);
  const now = Date.now();
  const s = load(now);
  const r = activeRestriction(s, customerId, now);
  if (!r) return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái hạn chế đặt online từ không còn hiệu lực sang gỡ sớm', 409);
  r.liftedAt = iso(now);
  r.liftedBy = req.account!.id;
  r.liftReason = (b.reason as string).trim();
  save(s);
  return ok(NOT_RESTRICTED, 200, 'Đã gỡ hạn chế đặt online');
};

// ─── Cho mock tiếp nhận (T36, visit.mock.ts): như AppointmentQueryApi / AppointmentApi của BE, chỉ thêm export ───

/** `AppointmentQueryApi.findCheckInRef` (+ trạng thái, giờ hẹn, khách): đọc không khóa trước khi visit kiểm các guard 400. */
export function findCheckInRef(appointmentId: number, now = Date.now()) {
  const a = load(now).appointments.find((x) => x.appointmentId === appointmentId);
  return (
    a && {
      appointmentId: a.appointmentId,
      branchId: a.branchId,
      customerId: a.customerId,
      petId: a.petId,
      serviceId: a.serviceId,
      serviceGroup: a.serviceGroup,
      status: a.status,
      /** Giờ bắt đầu khung (ms). */
      scheduledAt: startOf(a),
    }
  );
}

export type AppointmentCheckIn =
  | { ok: true; customerId: number; serviceGroup: QuotaGroup; scheduledAt: number }
  | { ok: false; reason: 'STATE'; status: AppointmentStatus }
  | { ok: false; reason: 'BRANCH' }
  | { ok: false; reason: 'WINDOW'; earlyMinutes: number; noShowAfterMinutes: number };

/**
 * `AppointmentApi.checkIn` (Lịch hẹn#3, BOOKED → CHECKED_IN). Thứ tự của BE: trạng thái 409 → chi nhánh 400 → cửa sổ 400 (BR-TN-02).
 * Cửa sổ: lịch của hôm nay, giờ hẹn − 30' ≤ lúc check-in ≤ giờ hẹn + 30' [CFG] (biên tính là được). Khác BE ở một điểm: ST05 của mock chạy lười
 * lúc đọc nên quá giờ hẹn + 30' lịch đã NO_SHOW (409), còn BE trả 400 cửa sổ tới khi job chạy.
 */
export function checkInAppointment(appointmentId: number, branchId: number, now: number, visitId: number): AppointmentCheckIn {
  const s = load(now);
  const a = s.appointments.find((x) => x.appointmentId === appointmentId);
  if (!a) return { ok: false, reason: 'STATE', status: 'CANCELLED' };
  if (a.status !== 'BOOKED') return { ok: false, reason: 'STATE', status: a.status };
  if (a.branchId !== branchId) return { ok: false, reason: 'BRANCH' };
  const start = startOf(a);
  if (a.slotDate !== vnDate(now) || now < start - CFG.checkInEarlyMinutes * MINUTE || now > start + CFG.noShowAfterMinutes * MINUTE) {
    return { ok: false, reason: 'WINDOW', earlyMinutes: CFG.checkInEarlyMinutes, noShowAfterMinutes: CFG.noShowAfterMinutes };
  }
  a.status = 'CHECKED_IN';
  a.visitId = visitId;
  save(s);
  return { ok: true, customerId: a.customerId, serviceGroup: a.serviceGroup, scheduledAt: start };
}

/** `AppointmentApi.cancelByVisit` (Lịch hẹn#8, CHECKED_IN → CANCELLED): nguồn STAFF, không lý do, KHÔNG tính hủy muộn nên không ảnh hưởng hạn chế đặt online. */
export function cancelAppointmentByVisit(appointmentId: number, at: string): void {
  const s = load(Date.now());
  const a = s.appointments.find((x) => x.appointmentId === appointmentId);
  if (!a || a.status !== 'CHECKED_IN') return;
  Object.assign(a, { status: 'CANCELLED', cancelSource: 'STAFF', cancelReason: null, cancelledAt: at, lateCancel: false });
  save(s);
}

/** `AppointmentApi.completeByVisit` (Lịch hẹn#7, CHECKED_IN → COMPLETED) khi lượt có lịch hẹn hoàn tất (BR-LH-11); lịch không còn CHECKED_IN thì bỏ qua. */
export function completeAppointmentByVisit(appointmentId: number): void {
  const s = load(Date.now());
  const a = s.appointments.find((x) => x.appointmentId === appointmentId);
  if (!a || a.status !== 'CHECKED_IN') return;
  a.status = 'COMPLETED';
  save(s);
}

export const appointmentRoutes: Record<string, MockHandler> = {
  'GET /api/branches/:branchId/available-slots': listSlots,
  'POST /api/appointments': bookAppointment,
  'GET /api/appointments': listAppointments,
  'GET /api/appointments/:appointmentId': getAppointment,
  'POST /api/appointments/:appointmentId/reschedule': reschedule,
  'POST /api/appointments/:appointmentId/cancel': cancel,
  'GET /api/me/booking-restriction': myRestriction,
  'GET /api/customers/:customerId/booking-restriction': customerRestriction,
  'POST /api/customers/:customerId/booking-restriction/lift': liftRestriction,
};
