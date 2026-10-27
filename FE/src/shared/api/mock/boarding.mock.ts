// Mock boarding-v1 (UC54, UC57–UC60) + hủy phiên trả thú (sales-v1 #9 ở sales.mock.ts): chuồng, còn chỗ theo đêm, đặt chỗ, gia hạn, hủy, nhận / trả thú, nhật ký chăm sóc (#1–22).
//
// #1–12 chép NGUYÊN VĂN BE (module/boarding: KennelService, BoardingAvailabilityService, BoardingBookingService, BoardingChangeService, BoardingViewService, BoardingAssembler,
// BoardingRules, BoardingNoShowService, BoardingClinicCancellationService; docs/adr/0065). Thứ tự lỗi: 401 → 400 body / path / query (đọc tham số và Bean Validation chạy TRƯỚC
// @PreAuthorize, kể cả với vai trò không được phép) → 403 vai trò → 404 / 403 phạm vi → 409 trạng thái → 400 rule (ADR-0065 §7, giống lịch hẹn: 409 trước 400 BR).
// Lệch hợp đồng mà mock chép theo BE thật: #2 loại chuồng không thuộc nhóm BOARDING → 404 (hợp đồng nói 400); #6 hết bảo trì trên chuồng OCCUPIED không 409 mà về AVAILABLE (FSM
// cho phép OCCUPIED → AVAILABLE); #9 `size` kẹp [1,100] im lặng; #8 / #11 có thêm vài 400 ngoài hợp đồng; `vaccineWarnings` là dữ liệu thật; trường null trả null.
//
// #13–22 BE CHƯA CÓ (T42, T43): hợp đồng + BR là nguồn duy nhất; chỗ im lặng là ASSUMPTION của T48 (ghi ở docs/testcases/T48-fe.md), thông điệp tự đặt theo văn phong BE "… (BR-LT-xx)":
//  A1  Nhận thú #14 (RECEPTIONIST, CARETAKER): 400 body → 403 → 404 đặt chỗ → 404 chuồng [ASSUMPTION: chuồng không có hoặc khác chi nhánh → 404, đứng TRƯỚC 409] → 409 không còn BOOKED →
//      400 BR-LT-04 ngoài giờ mở cửa → BR-LT-08 chuồng không AVAILABLE / sai loại → BR-LT-05 thiếu hoặc quá hạn mũi → BR-LT-03 nhận sớm hết chỗ. Thành công: chuồng OCCUPIED, ghi cân nặng INTAKE,
//      `actualCheckInAt` = bây giờ, nhận sớm kéo `checkInDate` về ngày nhận thực tế (ADR-0065 Nợ). GET …/check-in-readiness: vaccineGaps (tại ngày hôm nay), availableKennels, withinOpeningHours, canCheckIn.
//      [ASSUMPTION] câu BR-LT-04 / BR-LT-08 / BR-LT-05 và câu 409 "Thú cưng không còn trong chuồng" là văn bản tự đặt.
//  A2  Hủy vì hết chuồng #15 (RECEPTIONIST): 409 không BOOKED; 400 BR-LT-08 nếu còn chuồng AVAILABLE đúng loại; CANCELLED / CLINIC / không hủy muộn; báo khách (nếu có tài khoản) và BRANCH_MANAGER chi nhánh
//      [ASSUMPTION: loại thông báo BOARDING_CANCELLED_BY_CLINIC cho cả hai người nhận, nội dung riêng cho quản lý].
//  A3  Bắt đầu trả thú #16 (chỉ RECEPTIONIST): 409 thú không còn trong chuồng; 400 BR-LT-04 ngoài giờ mở cửa; số đêm = max(1, hôm nay − ngày nhận thực tế) + 1 nếu quá 12:00 giờ VN; Order BOARDING PENDING 1 dòng;
//      đã có Order PENDING thì dùng lại và tính lại số đêm; đã có Order PAID thì trả lại Order đó (BR-LT-09: mỗi đặt chỗ tối đa 1 Order chưa kết thúc).
//  A4  Giao thú #17 (RECEPTIONIST): 409 không còn trong chuồng; 400 BR-LT-09 nếu Order lưu trú mới nhất chưa hủy chưa PAID (hoặc chưa có); thành công CHECKED_OUT / RETURNED, chuồng AVAILABLE.
//  A5  Thú mất #18 (RECEPTIONIST, `note` bắt buộc): CHECKED_OUT / PET_DECEASED, chuồng AVAILABLE, Order PENDING tính đến hôm nay như A3, trả CheckoutStarted. Thú hết "đang lưu trú" nên trang thú đánh dấu mất được.
//  A6  Hủy phiên trả thú (sales #9) — ở sales.mock.ts.
//  A7  Nhật ký #19–22: ghi (VET, CARETAKER chi nhánh) chỉ khi CHECKED_IN / OVERDUE (409 khác); `logDate` mặc định hôm nay VN, không ở tương lai [ASSUMPTION: 400 VALIDATION_FAILED "logDate: …", kiểm cùng nhóm
//      Bean Validation nên đứng TRƯỚC 403 vai trò]; `photoUrls` bắt buộc, ≤ 5, https; `isAbnormal` mặc định false và đẩy thông báo CARE_LOG_ABNORMAL (khách + lễ tân chi nhánh); `editableUntil` = createdAt + 60 phút;
//      sửa chỉ người ghi (người khác 403 ACCESS_DENIED), quá hạn 409 "… (BR-LT-11)"; bản bổ sung 1–2000 ký tự bất cứ lúc nào bởi VET / CARETAKER chi nhánh (A4 hợp đồng); khách xem nhật ký của thú mình (người khác 404),
//      nhân viên chi nhánh mình (khác 403); sắp `logDate DESC, careLogId DESC`.
//  A8  Ảnh nhật ký là URL https, không tải lên (Q1 của hợp đồng còn TBD).
//  A9  Gia hạn áp cho BOOKED và CHECKED_IN (SM #6 #2), OVERDUE → 409 như BE.
//  A10 `activeOrderId` suy từ Order BOARDING mới nhất chưa CANCELLED (PENDING / PAID) của đặt chỗ đang CHECKED_IN / OVERDUE; null khi đã kết thúc. BE thật luôn null tới T43 (ADR-0065 D10).
//  A11 Thu tiền trả thú ở bàn Lễ tân bằng POST /payments của mock thu ngân (Order BOARDING không có dòng hàng nên không cần tồn kho).
//  A12 ST05 (NO_SHOW) và ST15 (OVERDUE) chạy "lười" mỗi lần nạp state (mẫu `sweep` của appointment.mock): BOOKED quá giờ đóng cửa muộn nhất của ngày nhận → NO_SHOW + đánh giá hạn chế đặt online (BR-LH-09);
//      [ASSUMPTION] thời điểm vi phạm = chính mốc đóng cửa (BE ghi `updated_at` lúc job chạy, nên lệch vài phút). CHECKED_IN quá 12:00 giờ VN của ngày trả → OVERDUE, `overdueSince` = 12:00 ngày trả [ASSUMPTION];
//      không sinh Care Task (T39 / T43) và hai tác vụ này KHÔNG đẩy thông báo.
// Không mô phỏng: khóa đồng thời, audit (BR-QT-15 không gồm chuồng / đặt chỗ), email, Care Task PICKUP_OVERDUE.
import type { Role } from '../../types/auth';
import {
  ACTIVE_BOOKING_STATUSES,
  BOARDING_CFG,
  BOOKING_STATUSES,
  IN_STAY_STATUSES,
  KENNEL_STATUSES,
  type AffectedBookingBrief,
  type Booking,
  type BookingStatus,
  type CareLog,
  type Kennel,
  type KennelStatus,
  type NightAvailability,
  type VaccineGap,
} from '../../types/boarding';
import type { AffectedBoarding } from '../../types/branch';
import { nightDates, nightsBetween, shiftDate } from '../../utils/boarding';
import { vnDate } from '../../utils/vn-time';
import { evaluateBoardingViolation, activeRestrictionEndsAt, registerBoardingSweep, registerBoardingViolations } from './appointment.mock';
import { branchBooking, isOpenAt, openingRangesOn, registerBoardingSource } from './branch.mock';
import { pushNotification } from './care.mock';
import { catalogProtocols, catalogServices, catalogVaccineTypes } from './catalog.mock';
import { customerOfAccount, findCustomer, markHasData, registerCounterGuests } from './customer.mock';
import { markPetHasBooking, petCurrentWeight, petSummary, recordIntakeWeight, registerStaySource } from './customer-pet.mock';
import { denied, fail, invalid, isText, noContent, notFound, ok, ruleFail, scopeDenied, tooLong, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { MOCK_ACCOUNTS, branchIdOf } from './identity.mock';
import { staffNameOf } from './identity-admin.mock';
import { activeBoardingOrder, openBoardingOrder, registerBoardingStay } from './sales.mock';
import { vaccineDosesOf } from './visit-clinical.mock';
import { seedState, SEED_CODE, type AddendumRow, type BookingRow, type CareLogRow, type KennelRow, type State } from './boarding.seed';

/** Mặc định của các [CFG] lưu trú (02 BR-LT-02, 03, 04, 06, 09, 11; seed V2 của BE). Mock không đọc cấu hình hệ thống. */
export const BOARDING_MOCK_CFG = {
  minNights: BOARDING_CFG.minNights,
  maxNights: BOARDING_CFG.maxNights,
  overdueHoldNights: 2,
  minLeadDays: BOARDING_CFG.minLeadDays,
  maxAdvanceDays: BOARDING_CFG.maxAdvanceDays,
  checkoutTime: BOARDING_CFG.checkoutTime,
  lateCancelHours: BOARDING_CFG.lateCancelHours,
  careLogMaxPhotos: BOARDING_CFG.careLogMaxPhotos,
  careLogEditWindowMinutes: BOARDING_CFG.careLogEditWindowMinutes,
} as const;
const CFG = BOARDING_MOCK_CFG;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const BRANCH_STAFF_VIEW: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST', 'CARETAKER'];
const MANAGER: readonly Role[] = ['BRANCH_MANAGER'];
const BOOKERS: readonly Role[] = ['CUSTOMER', 'RECEPTIONIST'];
const VIEWERS: readonly Role[] = ['CUSTOMER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const RECEIVERS: readonly Role[] = ['RECEPTIONIST', 'CARETAKER'];
const DESK: readonly Role[] = ['RECEPTIONIST'];
const CARE_WRITERS: readonly Role[] = ['VET', 'CARETAKER'];

// ─── Giờ Việt Nam (UTC+7, không đổi giờ mùa hè) ───

const instantOf = (date: string, time: string) => Date.parse(`${date}T${time}:00+07:00`);
const iso = (ms: number) => new Date(ms).toISOString();
const ddMM = (date: string) => `${date.slice(8, 10)}/${date.slice(5, 7)}`;
const ddMMyyyy = (date: string) => `${date.slice(8, 10)}/${date.slice(5, 7)}/${date.slice(0, 4)}`;
/** "HH:mm dd/MM/yyyy" giờ VN như message BR-LH-09 của BE. */
function vnDateTime(ms: number) {
  const d = new Date(ms + 7 * HOUR);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getUTCHours())}:${p(d.getUTCMinutes())} ${p(d.getUTCDate())}/${p(d.getUTCMonth() + 1)}/${d.getUTCFullYear()}`;
}

// ─── Trạng thái: localStorage, gộp mặc định khi state cũ thiếu trường ───

const STATE_KEY = 'petcare-mock-boarding';

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

/**
 * Đọc state đã lưu, trường nào thiếu (state lưu từ bản trước) lấy từ dữ liệu mẫu; không có hoặc hỏng thì dùng dữ liệu mẫu. `fresh` = chưa có gì được lưu.
 * Không chạy tác vụ hệ thống, không ghi: dùng cho các nguồn mà mock khác gọi lại từ BÊN TRONG tác vụ hệ thống (nguồn vi phạm của lịch hẹn).
 */
function readState(now: number): { state: State; fresh: boolean } {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) {
      const stored = JSON.parse(raw) as Partial<State>;
      const complete = (['kennels', 'bookings', 'careLogs', 'addenda', 'counters'] as const).every((k) => stored[k] !== undefined);
      return { state: complete ? (stored as State) : { ...seedState(now), ...stored }, fresh: false };
    }
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  return { state: seedState(now), fresh: true };
}

/** true khi ST05 / ST15 / hủy do phòng khám đang chạy: các hàm của mock khác gọi ngược vào file này không được kích hoạt vòng lặp. */
let sweeping = false;

/** Đọc state rồi chạy các tác vụ hệ thống đến hạn (xem đầu file). */
function load(now: number): State {
  const { state, fresh } = readState(now);
  if (fresh) save(state);
  if (sweeping) return state;
  sweeping = true;
  try {
    const effects: Effects = { violations: [], notices: [] };
    if (sweep(state, now, effects)) save(state);
    // Vi phạm đã nằm trong state lưu (nguồn vi phạm đọc state lưu) thì mới đánh giá, theo thứ tự thời gian.
    for (const v of effects.violations.sort((a, b) => a.at - b.at)) {
      if (findCustomer(v.customerId)) evaluateBoardingViolation(v.customerId, v.at);
    }
    for (const notify of effects.notices) notify();
  } finally {
    sweeping = false;
  }
  return state;
}

// ─── Tác vụ hệ thống ───

interface Effects {
  violations: { customerId: number; at: number }[];
  notices: (() => void)[];
}

const CLINIC_REASON = {
  deceased: 'Thú cưng đã được ghi nhận là đã mất',
  transferred: 'Thú cưng đã được chuyển sang chủ mới',
  holidayAdded: 'Chi nhánh nghỉ vào ngày nhận hoặc ngày trả của đặt chỗ',
  hoursReduced: 'Chi nhánh thay đổi giờ mở cửa, ngày nhận hoặc ngày trả nằm ngoài giờ mở cửa mới',
  noKennel: 'Chi nhánh không còn chuồng trống đúng loại vào ngày nhận',
} as const;

const branchNameOf = (branchId: number) => branchBooking(branchId)?.branch.name ?? `Chi nhánh #${branchId}`;
const petNameOf = (b: BookingRow) => petSummary(b.petId)?.name ?? b.petName;
const customerNameOf = (b: BookingRow) => findCustomer(b.customerId)?.fullName ?? b.customerName;
const customerAccountOf = (b: BookingRow) => findCustomer(b.customerId)?.accountId ?? null;

/** Lễ tân / quản lý đang hoạt động của chi nhánh (BR-TB-02: người nhận thông báo trong ứng dụng). */
const staffOf = (branchId: number, role: Role) => MOCK_ACCOUNTS.filter((a) => a.role === role && a.branchId === branchId);

/** Đặt chỗ#5, BR-LT-08, BR-KH-05, BR-KH-08, BR-LH-10: CANCELLED / CLINIC, không tính hủy muộn, báo khách (BOARDING_CANCELLED_BY_CLINIC). */
function clinicCancel(b: BookingRow, reason: string, now: number, effects?: Effects) {
  b.status = 'CANCELLED';
  b.cancelSource = 'CLINIC';
  b.cancelReason = reason;
  b.lateCancel = false;
  b.endedAt = iso(now);
  const notify = () => {
    const account = customerAccountOf(b);
    if (account === null) return;
    pushNotification(account, {
      type: 'BOARDING_CANCELLED_BY_CLINIC',
      title: `Phòng khám đã hủy đặt chỗ lưu trú của ${petNameOf(b)}`,
      body:
        `Đặt chỗ ${b.code} của ${petNameOf(b)} tại ${branchNameOf(b.branchId)} (nhận ${ddMMyyyy(b.checkInDate)}, trả ${ddMMyyyy(b.checkOutDate)}) đã bị hủy.\n` +
        `Lý do: ${reason}\nLần hủy này không tính là hủy muộn.`,
      linkUrl: `/boarding-bookings/${b.bookingId}`,
    });
  };
  if (effects) effects.notices.push(notify);
  else notify();
}

/** Mốc ST05 (Đặt chỗ#6): giờ đóng cửa muộn nhất của ngày nhận; ngày không có khung giờ thì 00:00 ngày kế (BoardingRules.noShowCutoff, ADR-0057). */
function noShowCutoff(branchId: number, date: string): number {
  const closes = openingRangesOn(branchId, date).map((r) => r.close);
  return closes.length ? instantOf(date, closes.reduce((a, c) => (c > a ? c : a))) : instantOf(shiftDate(date, 1), '00:00');
}

const inStay = (b: Pick<BookingRow, 'status'>) => IN_STAY_STATUSES.includes(b.status);

/** ASSUMPTION A12: ST05 (NO_SHOW) chép BE nhưng chạy lười, thời điểm vi phạm = mốc đóng cửa; ST15 (OVERDUE) BE chưa có (T43), `overdueSince` = 12:00 ngày trả; không đẩy thông báo. true nếu có thay đổi. */
function sweep(s: State, now: number, effects: Effects): boolean {
  let changed = false;
  const today = vnDate(now);
  // Hủy do phòng khám trước ST05: thú mất hủy cả đặt chỗ đã quá giờ mà ST05 chưa chuyển (ADR-0057 §2).
  for (const b of s.bookings.filter((x) => x.status === 'BOOKED')) {
    const pet = petSummary(b.petId);
    if (!pet) continue;
    if (pet.deceasedOn) clinicCancel(b, CLINIC_REASON.deceased, now, effects);
    else if (pet.customerId !== b.customerId) clinicCancel(b, CLINIC_REASON.transferred, now, effects);
    else continue;
    changed = true;
  }
  const due = s.bookings
    .filter((b) => b.status === 'BOOKED' && b.checkInDate <= today)
    .sort((a, b) => a.checkInDate.localeCompare(b.checkInDate) || a.bookingId - b.bookingId);
  for (const b of due) {
    const cutoff = noShowCutoff(b.branchId, b.checkInDate);
    if (now < cutoff) continue;
    b.status = 'NO_SHOW';
    b.endedAt = iso(cutoff);
    effects.violations.push({ customerId: b.customerId, at: cutoff });
    changed = true;
  }
  for (const b of s.bookings.filter((x) => x.status === 'CHECKED_IN')) {
    const checkoutAt = instantOf(b.checkOutDate, CFG.checkoutTime);
    if (now <= checkoutAt) continue;
    b.status = 'OVERDUE';
    b.overdueSince = iso(checkoutAt);
    changed = true;
  }
  return changed;
}

// ─── Hiển thị ───

const kennelTypeOf = (typeId: number) => catalogServices().find((x) => x.serviceId === typeId && x.group === 'BOARDING');
const kennelTypeNameOf = (typeId: number) => catalogServices().find((x) => x.serviceId === typeId)?.name ?? `Loại chuồng #${typeId}`;

function currentBookingOf(s: State, kennelId: number): number | null {
  return s.bookings.find((b) => b.kennelId === kennelId && inStay(b))?.bookingId ?? null;
}

const toKennel = (s: State, k: KennelRow): Kennel => ({
  kennelId: k.kennelId,
  branchId: k.branchId,
  kennelTypeId: k.kennelTypeId,
  kennelTypeName: kennelTypeNameOf(k.kennelTypeId),
  code: k.code,
  status: k.status,
  note: k.note,
  currentBookingId: currentBookingOf(s, k.kennelId),
});

const hasCareLogOn = (s: State, bookingId: number, date: string) => s.careLogs.some((l) => l.bookingId === bookingId && l.logDate === date);

function toBooking(s: State, b: BookingRow, today: string): Booking {
  return {
    bookingId: b.bookingId,
    code: b.code,
    customerId: b.customerId,
    customerName: customerNameOf(b),
    petId: b.petId,
    petName: petNameOf(b),
    branchId: b.branchId,
    kennelTypeId: b.kennelTypeId,
    kennelTypeName: kennelTypeNameOf(b.kennelTypeId),
    kennelId: b.kennelId,
    kennelCode: b.kennelId === null ? null : (s.kennels.find((k) => k.kennelId === b.kennelId)?.code ?? null),
    checkInDate: b.checkInDate,
    checkOutDate: b.checkOutDate,
    actualCheckInAt: b.actualCheckInAt,
    actualCheckOutAt: b.actualCheckOutAt,
    nightlyPrice: b.nightlyPrice,
    status: b.status,
    endReason: b.endReason,
    channel: b.channel,
    lateCancel: b.lateCancel,
    cancelSource: b.cancelSource,
    cancelReason: b.cancelReason,
    overdueSince: b.overdueSince,
    activeOrderId: inStay(b) ? (activeBoardingOrder(b.bookingId)?.orderId ?? null) : null,
    missingCareLogToday: inStay(b) && !hasCareLogOn(s, b.bookingId, today),
  };
}

const staffNameFor = (accountId: number) => staffNameOf(accountId) ?? MOCK_ACCOUNTS.find((a) => a.id === accountId)?.fullName ?? `Nhân viên #${accountId}`;

function toCareLog(s: State, l: CareLogRow): CareLog {
  return {
    careLogId: l.careLogId,
    bookingId: l.bookingId,
    logDate: l.logDate,
    eating: l.eating,
    drinking: l.drinking,
    hygiene: l.hygiene,
    activity: l.activity,
    note: l.note,
    photoUrls: l.photoUrls,
    isAbnormal: l.isAbnormal,
    recordedBy: l.recordedBy,
    recordedByName: staffNameFor(l.recordedBy),
    createdAt: l.createdAt,
    editableUntil: iso(Date.parse(l.createdAt) + CFG.careLogEditWindowMinutes * MINUTE),
    addenda: s.addenda
      .filter((a) => a.careLogId === l.careLogId)
      .sort((a, b) => a.addendumId - b.addendumId)
      .map(({ addendumId, content, createdBy, createdAt }) => ({ addendumId, content, createdBy, createdAt })),
  };
}

// ─── Đọc tham số ───

const INT_RE = /^-?\d+$/;
const bodyOf = (b: unknown) => (b && typeof b === 'object' && !Array.isArray(b) ? (b as Record<string, unknown>) : {});
const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const missingParam = (name: string) => fail('MALFORMED_REQUEST', `Thiếu tham số '${name}'`, 400);
const brokenProfile = (accountId: number) => fail('INTERNAL_ERROR', `Tài khoản khách #${accountId} không có hồ sơ khách`, 500);
const stateError = (what: string, from: string, to: string) => fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái ${what} từ ${from} sang ${to}`, 409);
const conflict = (message: string) => fail('INVALID_STATE_TRANSITION', message, 409);
/** Phản hồi mock có `status` là mã HTTP (số); đặt chỗ / chuồng cũng có `status` nhưng là chuỗi trạng thái. */
const isReply = (x: unknown): x is MockReply => typeof x === 'object' && x !== null && typeof (x as { status?: unknown }).status === 'number';

/** `yyyy-MM-dd` là ngày có thật (không nhận 2026-02-30). */
function isDate(v: unknown): v is string {
  if (typeof v !== 'string') return false;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v);
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
}

/** Tham số tùy chọn: rỗng = không gửi. */
const param = (q: Record<string, string>, name: string) => (q[name] === undefined || q[name] === '' ? undefined : q[name]);

/** Spring `StringToBooleanConverter`: true / on / yes / 1, false / off / no / 0 (không phân biệt hoa thường); khác → lỗi kiểu. */
function boolParam(v: string | undefined): boolean | null | undefined {
  if (v === undefined) return null;
  const x = v.trim().toLowerCase();
  if (['true', 'on', 'yes', '1'].includes(x)) return true;
  if (['false', 'off', 'no', '0'].includes(x)) return false;
  return undefined;
}

const me = (req: MockRequest) => req.account!;
const roleOf = (req: MockRequest) => me(req).role;
const branchOf = (req: MockRequest) => me(req).branchId ?? branchIdOf(me(req).id) ?? null;
const isCustomer = (req: MockRequest) => roleOf(req) === 'CUSTOMER';

/** Path variable kiểu Long: sai kiểu → 400 trước @PreAuthorize. */
function idParam(req: MockRequest, name: string): number | MockReply {
  return INT_RE.test(req.params[name]) ? Number(req.params[name]) : badParam(name);
}

/**
 * Đặt chỗ theo phạm vi người gọi: không có → 404; khách không phải chủ hồ sơ → 404 như không tồn tại (ADR-0006); nhân viên chi nhánh khác → 403 phạm vi, SAU 404.
 */
function bookingFor(req: MockRequest, s: State, id: number): BookingRow | MockReply {
  const b = s.bookings.find((x) => x.bookingId === id);
  if (isCustomer(req)) {
    const own = customerOfAccount(me(req).id);
    if (!own) return brokenProfile(me(req).id);
    return b && b.customerId === own.customerId ? b : notFound('đặt chỗ lưu trú', id);
  }
  if (!b) return notFound('đặt chỗ lưu trú', id);
  return branchOf(req) === b.branchId ? b : scopeDenied();
}

// ─── Sức chứa theo đêm (BR-LT-01, BR-LT-03) ───

interface Night extends NightAvailability {
  date: string;
}

/** Sức chứa = chuồng của loại tại chi nhánh không MAINTENANCE; chiếm đêm d: BOOKED / CHECKED_IN có nhận ≤ d < trả, OVERDUE có hôm nay ≤ d ≤ hôm nay + 2 đêm (BoardingCapacity, ADR-0065 §2). */
function nightsFor(s: State, branchId: number, typeId: number, from: string, to: string, today: string): Night[] {
  const capacity = s.kennels.filter((k) => k.branchId === branchId && k.kennelTypeId === typeId && k.status !== 'MAINTENANCE').length;
  const holdUntil = shiftDate(today, CFG.overdueHoldNights);
  const mine = s.bookings.filter((b) => b.branchId === branchId && b.kennelTypeId === typeId);
  return nightDates(from, to).map((date) => {
    const occupied = mine.filter(
      (b) =>
        ((b.status === 'BOOKED' || b.status === 'CHECKED_IN') && b.checkInDate <= date && date < b.checkOutDate) ||
        (b.status === 'OVERDUE' && today <= date && date <= holdUntil),
    ).length;
    return { date, capacity, occupied, remaining: Math.max(0, capacity - occupied) };
  });
}

/** Đêm đã hết chỗ (chưa tính đặt chỗ mới): số chiếm ≥ sức chứa. */
const shortNights = (s: State, branchId: number, typeId: number, from: string, to: string, today: string) =>
  nightsFor(s, branchId, typeId, from, to, today).filter((n) => n.occupied >= n.capacity).map((n) => n.date);

const fullNightsError = (dates: string[]) => ruleFail('BR-LT-03', `Hết chỗ các đêm: ${dates.map(ddMM).join(', ')}`);

/** BR-LT-02: ngày trả sau ngày nhận và số đêm trong [min, max] [CFG]. */
function nightsError(checkIn: string, checkOut: string): MockReply | null {
  const n = nightsBetween(checkIn, checkOut);
  return n < CFG.minNights || n > CFG.maxNights
    ? ruleFail('BR-LT-02', `Thời gian lưu trú phải từ ${CFG.minNights} đến ${CFG.maxNights} đêm và ngày trả phải sau ngày nhận`)
    : null;
}

/** Đặt chỗ còn hiệu lực của thú có khoảng ngày chồng `[from, to)` (BOOKED / CHECKED_IN / OVERDUE, Review Focus #1). */
const overlaps = (s: State, petId: number, from: string, to: string, exceptId = 0) =>
  s.bookings.some((b) => b.petId === petId && b.bookingId !== exceptId && ACTIVE_BOOKING_STATUSES.includes(b.status) && b.checkInDate < to && from < b.checkOutDate);

/** Loại chuồng đang bán tại chi nhánh: dịch vụ đang kinh doanh và bật tại chi nhánh. */
const bookable = (branchId: number, typeId: number) => {
  const type = kennelTypeOf(typeId);
  return !!type && type.isActive && !!branchBooking(branchId)?.isServiceEnabled(typeId);
};

// ─── Mũi tiêm bắt buộc khi lưu trú (BR-LT-05) ───

/**
 * Với mỗi loại vaccine mà phác đồ của loài có cờ bắt buộc khi lưu trú: thú phải có mũi TẠI HỆ THỐNG và mũi gần nhất chưa quá ngày tái chủng tại `onDate`
 * (VaccinationQueryApi.findBoardingVaccineGaps). Thú không có hồ sơ (đặt chỗ mẫu) không có phác đồ để đối chiếu → không thiếu gì. Sắp theo mã loại vaccine.
 */
function vaccineGaps(petId: number, onDate: string): VaccineGap[] {
  const pet = petSummary(petId);
  if (!pet) return [];
  const required = [...new Set(catalogProtocols().filter((p) => p.species === pet.species && p.requiredForBoarding && p.isActive).map((p) => p.vaccineTypeId))].sort((a, b) => a - b);
  const doses = vaccineDosesOf(petId);
  return required.flatMap((vaccineTypeId): VaccineGap[] => {
    const last = doses
      .filter((d) => d.vaccineTypeId === vaccineTypeId)
      .sort((a, b) => b.administeredOn.localeCompare(a.administeredOn) || b.vaccinationId - a.vaccinationId)[0];
    if (last && last.nextDueDate >= onDate) return [];
    return [
      {
        vaccineTypeId,
        vaccineTypeName: catalogVaccineTypes().find((t) => t.vaccineTypeId === vaccineTypeId)?.name ?? `Loại vaccine #${vaccineTypeId}`,
        lastAdministeredOn: last?.administeredOn ?? null,
        nextDueDate: last?.nextDueDate ?? null,
      },
    ];
  });
}

// ─── Chuồng #1–6 ───

const kennelsForBranch = (s: State, branchId: number | null) => s.kennels.filter((k) => k.branchId === branchId);
const byCode = (a: KennelRow, b: KennelRow) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0);
const optionalText = (v: unknown) => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null);

const listKennels: MockHandler = (req) => {
  const kennelTypeId = param(req.query, 'kennelTypeId');
  if (kennelTypeId !== undefined && !INT_RE.test(kennelTypeId)) return badParam('kennelTypeId');
  const status = param(req.query, 'status');
  if (status !== undefined && !KENNEL_STATUSES.includes(status as KennelStatus)) return badParam('status');
  if (!BRANCH_STAFF_VIEW.includes(roleOf(req))) return denied();
  const s = load(Date.now());
  const rows = kennelsForBranch(s, branchOf(req))
    .filter((k) => (kennelTypeId === undefined || k.kennelTypeId === Number(kennelTypeId)) && (status === undefined || k.status === status))
    .sort(byCode);
  return ok(rows.map((k) => toKennel(s, k)), 200, 'Danh sách chuồng');
};

const createKennel: MockHandler = (req) => {
  const b = bodyOf(req.body);
  if ((b.kennelTypeId != null && !Number.isInteger(b.kennelTypeId)) || (b.code != null && typeof b.code !== 'string') || (b.note != null && typeof b.note !== 'string')) return malformed();
  const bad = invalid([
    ['kennelTypeId', 'Vui lòng chọn loại chuồng', b.kennelTypeId == null],
    ['code', 'Vui lòng nhập mã chuồng', !isText(b.code)],
    ['code', 'Mã chuồng tối đa 20 ký tự', tooLong(b.code, 20)],
    ['note', 'Ghi chú tối đa 300 ký tự', tooLong(b.note, 300)],
  ]);
  if (bad) return bad;
  if (!MANAGER.includes(roleOf(req))) return denied();
  const branchId = branchOf(req);
  const type = kennelTypeOf(b.kennelTypeId as number);
  if (!type) return notFound('loại chuồng', b.kennelTypeId as number);
  if (branchId === null || !branchBooking(branchId)) return notFound('chi nhánh', branchId ?? 0);
  if (!bookable(branchId, type.serviceId)) return ruleFail('BR-LT-01', 'Loại chuồng không phải dịch vụ lưu trú đang bật tại chi nhánh');
  const s = load(Date.now());
  const code = (b.code as string).trim();
  if (s.kennels.some((k) => k.branchId === branchId && k.code === code)) return ruleFail('BR-LT-01', 'Mã chuồng đã tồn tại trong chi nhánh');
  const row: KennelRow = { kennelId: Math.max(0, ...s.kennels.map((k) => k.kennelId)) + 1, branchId, kennelTypeId: type.serviceId, code, note: optionalText(b.note), status: 'AVAILABLE' };
  s.kennels.push(row);
  save(s);
  return ok(toKennel(s, row), 201, 'Đã tạo chuồng');
};

/** Phân quyền theo chi nhánh của chuồng: 404 chuồng → 403 phạm vi. */
function kennelFor(req: MockRequest, s: State, id: number): KennelRow | MockReply {
  const k = s.kennels.find((x) => x.kennelId === id);
  if (!k) return notFound('chuồng', id);
  return branchOf(req) === k.branchId ? k : scopeDenied();
}

const updateKennel: MockHandler = (req) => {
  const kennelId = idParam(req, 'kennelId');
  if (isReply(kennelId)) return kennelId;
  const b = bodyOf(req.body);
  if ((b.code != null && typeof b.code !== 'string') || (b.note != null && typeof b.note !== 'string')) return malformed();
  const bad = invalid([
    ['code', 'Mã chuồng tối đa 20 ký tự', tooLong(b.code, 20)],
    ['code', 'Mã chuồng không được để trống', typeof b.code === 'string' && b.code.trim() === ''],
    ['note', 'Ghi chú tối đa 300 ký tự', tooLong(b.note, 300)],
  ]);
  if (bad) return bad;
  if (!MANAGER.includes(roleOf(req))) return denied();
  const s = load(Date.now());
  const k = kennelFor(req, s, kennelId);
  if (isReply(k)) return k;
  if (typeof b.code === 'string') {
    const code = b.code.trim();
    if (code !== k.code && s.kennels.some((x) => x.branchId === k.branchId && x.code === code)) {
      return ruleFail('BR-LT-01', 'Mã chuồng đã tồn tại trong chi nhánh');
    }
    k.code = code;
  }
  if (typeof b.note === 'string') k.note = optionalText(b.note);
  save(s);
  return ok(toKennel(s, k), 200, 'Đã cập nhật chuồng');
};

const deleteKennel: MockHandler = (req) => {
  const kennelId = idParam(req, 'kennelId');
  if (isReply(kennelId)) return kennelId;
  if (!MANAGER.includes(roleOf(req))) return denied();
  const s = load(Date.now());
  const k = kennelFor(req, s, kennelId);
  if (isReply(k)) return k;
  if (k.status === 'OCCUPIED') return conflict('Chuồng đang có thú, không xóa được');
  if (s.bookings.some((b) => b.kennelId === k.kennelId)) return conflict('Chuồng đã có lịch sử lưu trú — chuyển bảo trì thay vì xóa');
  s.kennels = s.kennels.filter((x) => x !== k);
  save(s);
  return noContent();
};

/** FSM chuồng (03 §7) chép đúng bảng: AVAILABLE → OCCUPIED, MAINTENANCE · OCCUPIED → AVAILABLE · MAINTENANCE → AVAILABLE. */
const KENNEL_FSM: Record<KennelStatus, readonly KennelStatus[]> = {
  AVAILABLE: ['OCCUPIED', 'MAINTENANCE'],
  OCCUPIED: ['AVAILABLE'],
  MAINTENANCE: ['AVAILABLE'],
};
const kennelTransition = (k: KennelRow, to: KennelStatus) => (KENNEL_FSM[k.status].includes(to) ? null : stateError('chuồng', k.status, to));

/** Đặt chỗ BOOKED cùng loại có ít nhất một đêm (từ hôm nay) mà số chiếm vượt sức chứa mới (BR-LT-01, D11); sắp (ngày nhận, id); chỉ cảnh báo. */
function shortageBookings(s: State, k: KennelRow, today: string): AffectedBookingBrief[] {
  return s.bookings
    .filter((b) => b.branchId === k.branchId && b.kennelTypeId === k.kennelTypeId && b.status === 'BOOKED' && b.checkOutDate > today)
    .sort((a, b) => a.checkInDate.localeCompare(b.checkInDate) || a.bookingId - b.bookingId)
    .filter((b) => {
      const from = b.checkInDate > today ? b.checkInDate : today;
      return nightsFor(s, k.branchId, k.kennelTypeId, from, b.checkOutDate, today).some((n) => n.occupied > n.capacity);
    })
    .map((b) => ({ bookingId: b.bookingId, code: b.code, checkInDate: b.checkInDate, checkOutDate: b.checkOutDate, petName: petNameOf(b) }));
}

const startMaintenance: MockHandler = (req) => {
  const kennelId = idParam(req, 'kennelId');
  if (isReply(kennelId)) return kennelId;
  if (!MANAGER.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const k = kennelFor(req, s, kennelId);
  if (isReply(k)) return k;
  const refused = kennelTransition(k, 'MAINTENANCE');
  if (refused) return refused;
  k.status = 'MAINTENANCE';
  save(s);
  return ok({ kennel: toKennel(s, k), shortageBookings: shortageBookings(s, k, vnDate(now)) }, 200, 'Đã chuyển chuồng sang bảo trì');
};

const endMaintenance: MockHandler = (req) => {
  const kennelId = idParam(req, 'kennelId');
  if (isReply(kennelId)) return kennelId;
  if (!MANAGER.includes(roleOf(req))) return denied();
  const s = load(Date.now());
  const k = kennelFor(req, s, kennelId);
  if (isReply(k)) return k;
  const refused = kennelTransition(k, 'AVAILABLE');
  if (refused) return refused;
  k.status = 'AVAILABLE';
  save(s);
  return ok(toKennel(s, k), 200, 'Đã hết bảo trì');
};

// ─── #7 Còn chỗ theo đêm ───

const availability: MockHandler = (req) => {
  const branchId = idParam(req, 'branchId');
  if (isReply(branchId)) return branchId;
  const q = req.query;
  const typeRaw = param(q, 'kennelTypeId');
  if (typeRaw === undefined) return missingParam('kennelTypeId');
  if (!INT_RE.test(typeRaw)) return badParam('kennelTypeId');
  const checkIn = param(q, 'checkInDate');
  if (checkIn === undefined) return missingParam('checkInDate');
  if (!isDate(checkIn)) return badParam('checkInDate');
  const checkOut = param(q, 'checkOutDate');
  if (checkOut === undefined) return missingParam('checkOutDate');
  if (!isDate(checkOut)) return badParam('checkOutDate');
  if (!BOOKERS.includes(roleOf(req))) return denied();
  const customer = isCustomer(req);
  if (!customer && branchOf(req) !== branchId) return scopeDenied();
  const branch = branchBooking(branchId);
  if (!branch || (customer && branch.branch.status !== 'ACTIVE')) return notFound('chi nhánh', branchId);
  const typeId = Number(typeRaw);
  const type = kennelTypeOf(typeId);
  if (!type) return notFound('loại chuồng', typeId);
  const nights = nightsError(checkIn, checkOut);
  if (nights) return nights;
  if (!bookable(branchId, typeId)) return ruleFail('BR-LT-02', 'Loại chuồng không đặt được tại chi nhánh này');
  const now = Date.now();
  const list = nightsFor(load(now), branchId, typeId, checkIn, checkOut, vnDate(now));
  return ok({ kennelTypeId: type.serviceId, nightlyPrice: type.price, nights: list, available: list.every((n) => n.remaining > 0) }, 200, 'Số chỗ còn lại theo đêm');
};

// ─── #8 Đặt chỗ ───

/** BR-LT-04: khoảng đặt trước của khách, lễ tân không đặt ngày đã qua / sau giờ đóng cửa, ngày nhận / trả phải mở cửa. */
function datesError(customer: boolean, now: number, today: string, branchId: number, checkIn: string, checkOut: string): MockReply | null {
  if (customer) {
    if (checkIn < shiftDate(today, CFG.minLeadDays) || checkIn > shiftDate(today, CFG.maxAdvanceDays)) {
      return ruleFail('BR-LT-04', `Chỉ đặt online trước ngày nhận ít nhất ${CFG.minLeadDays} ngày và trong vòng ${CFG.maxAdvanceDays} ngày tới`);
    }
  } else if (checkIn < today) {
    return ruleFail('BR-LT-04', 'Ngày nhận đã qua');
  } else if (checkIn === today && now >= noShowCutoff(branchId, today)) {
    return ruleFail('BR-LT-04', 'Đã hết giờ làm việc hôm nay, không nhận đặt chỗ nhận trong ngày');
  }
  if (!openingRangesOn(branchId, checkIn).length || !openingRangesOn(branchId, checkOut).length) {
    return ruleFail('BR-LT-04', 'Ngày nhận / trả trùng ngày chi nhánh không làm việc');
  }
  return null;
}

const nextCode = (s: State, today: string) => {
  const period = today.slice(2, 4) + today.slice(5, 7);
  s.counters[period] = (s.counters[period] ?? 0) + 1;
  return SEED_CODE(period, s.counters[period]);
};

const createBooking: MockHandler = (req) => {
  const b = bodyOf(req.body);
  if (['petId', 'branchId', 'kennelTypeId'].some((k) => b[k] != null && !Number.isInteger(b[k])) || ['checkInDate', 'checkOutDate'].some((k) => b[k] != null && !isDate(b[k]))) {
    return malformed();
  }
  const bad = invalid([
    ['petId', 'Vui lòng chọn thú cưng', b.petId == null],
    ['branchId', 'Vui lòng chọn chi nhánh', b.branchId == null],
    ['kennelTypeId', 'Vui lòng chọn loại chuồng', b.kennelTypeId == null],
    ['checkInDate', 'Vui lòng chọn ngày nhận', b.checkInDate == null],
    ['checkOutDate', 'Vui lòng chọn ngày trả', b.checkOutDate == null],
  ]);
  if (bad) return bad;
  if (!BOOKERS.includes(roleOf(req))) return denied();
  const customer = isCustomer(req);
  const petId = b.petId as number;
  const branchId = b.branchId as number;
  const typeId = b.kennelTypeId as number;
  const checkIn = b.checkInDate as string;
  const checkOut = b.checkOutDate as string;
  if (!customer && branchOf(req) !== branchId) return scopeDenied();
  const own = customer ? customerOfAccount(me(req).id) : undefined;
  if (customer && !own) return brokenProfile(me(req).id);

  const pet = petSummary(petId);
  if (!pet || (own && pet.customerId !== own.customerId)) return notFound('thú cưng', petId);
  const type = kennelTypeOf(typeId);
  if (!type) return notFound('loại chuồng', typeId);
  const branch = branchBooking(branchId);
  if (!branch || (customer && branch.branch.status !== 'ACTIVE')) return notFound('chi nhánh', branchId);
  const now = Date.now();
  const today = vnDate(now);
  const s = load(now);

  if (branch.branch.status !== 'ACTIVE') return ruleFail('BR-LT-02', 'Chi nhánh chưa nhận đặt chỗ lưu trú');
  if (!bookable(branchId, typeId)) return ruleFail('BR-LT-02', 'Loại chuồng không đặt được tại chi nhánh này');
  if (own?.linkDecisionPending) return ruleFail('BR-TK-19', 'Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám');
  const restrictedUntil = own ? activeRestrictionEndsAt(own.customerId, now) : null;
  if (restrictedUntil !== null) {
    return ruleFail('BR-LH-09', `Bạn đang bị hạn chế đặt online đến ${vnDateTime(restrictedUntil)}, vui lòng liên hệ chi nhánh để đặt chỗ`);
  }
  if (pet.deceasedOn) return ruleFail('BR-KH-05', 'Thú cưng đã mất, không đặt được chỗ lưu trú');
  if (pet.species !== type.kennelType?.species) return ruleFail('BR-LT-02', 'Loại chuồng không phù hợp loài của thú cưng');
  const weight = petCurrentWeight(petId);
  if (weight !== null && type.kennelType && weight > type.kennelType.maxWeightKg) return ruleFail('BR-LT-02', 'Cân nặng hiện tại vượt cân nặng tối đa của loại chuồng');
  const nights = nightsError(checkIn, checkOut);
  if (nights) return nights;
  if (overlaps(s, petId, checkIn, checkOut)) return ruleFail('BR-LT-02', 'Thú cưng đã có đặt chỗ lưu trú trùng ngày');
  const dates = datesError(customer, now, today, branchId, checkIn, checkOut);
  if (dates) return dates;
  const short = shortNights(s, branchId, typeId, checkIn, checkOut, today);
  if (short.length) return fullNightsError(short);

  const customerRecord = findCustomer(pet.customerId);
  const row: BookingRow = {
    bookingId: Math.max(0, ...s.bookings.map((x) => x.bookingId)) + 1,
    code: nextCode(s, today),
    customerId: pet.customerId,
    petId,
    branchId,
    kennelTypeId: typeId,
    kennelId: null,
    checkInDate: checkIn,
    checkOutDate: checkOut,
    actualCheckInAt: null,
    actualCheckOutAt: null,
    nightlyPrice: type.price,
    status: 'BOOKED',
    endReason: null,
    channel: customer ? 'ONLINE' : 'COUNTER',
    lateCancel: false,
    cancelSource: null,
    cancelReason: null,
    overdueSince: null,
    endedAt: null,
    createdBy: me(req).id,
    petName: pet.name,
    customerName: customerRecord?.fullName ?? `Khách #${pet.customerId}`,
    customerPhone: customerRecord?.phone ?? null,
    checkIn: null,
    endNote: null,
  };
  s.bookings.push(row);
  save(s);
  markPetHasBooking(petId);
  markHasData(pet.customerId);
  return ok({ booking: toBooking(s, row, today), vaccineWarnings: vaccineGaps(petId, checkIn) }, 201, 'Đã đặt chỗ lưu trú');
};

// ─── #9, #10 Xem ───

const listBookings: MockHandler = (req) => {
  const q = req.query;
  const status = param(q, 'status');
  if (status !== undefined && !BOOKING_STATUSES.includes(status as BookingStatus)) return badParam('status');
  for (const name of ['petId', 'customerId'] as const) if (param(q, name) !== undefined && !INT_RE.test(param(q, name)!)) return badParam(name);
  for (const name of ['from', 'to'] as const) if (param(q, name) !== undefined && !isDate(param(q, name))) return badParam(name);
  const inStayFilter = boolParam(param(q, 'inStay'));
  if (inStayFilter === undefined) return badParam('inStay');
  const missingFilter = boolParam(param(q, 'missingCareLogToday'));
  if (missingFilter === undefined) return badParam('missingCareLogToday');
  for (const name of ['page', 'size'] as const) if (param(q, name) !== undefined && !INT_RE.test(param(q, name)!)) return badParam(name);
  if (!VIEWERS.includes(roleOf(req))) return denied();
  const from = param(q, 'from');
  const to = param(q, 'to');
  if (from !== undefined && to !== undefined && from > to) return fail('VALIDATION_FAILED', 'Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc', 400);

  let ownerCustomer: number | null = null;
  let branchScope: number | null = null;
  if (isCustomer(req)) {
    const own = customerOfAccount(me(req).id);
    if (!own) return brokenProfile(me(req).id);
    ownerCustomer = own.customerId;
  } else {
    branchScope = branchOf(req);
  }
  const now = Date.now();
  const today = vnDate(now);
  const s = load(now);
  const petId = param(q, 'petId');
  const customerId = param(q, 'customerId');
  const found = s.bookings
    .filter(
      (b) =>
        (ownerCustomer === null || b.customerId === ownerCustomer) &&
        (branchScope === null || b.branchId === branchScope) &&
        (status === undefined || b.status === status) &&
        (petId === undefined || b.petId === Number(petId)) &&
        (customerId === undefined || b.customerId === Number(customerId)) &&
        (from === undefined || b.checkOutDate >= from) &&
        (to === undefined || b.checkInDate <= to) &&
        (inStayFilter !== true || inStay(b)) &&
        (missingFilter !== true || (inStay(b) && !hasCareLogOn(s, b.bookingId, today))),
    )
    .sort((a, b) => b.checkInDate.localeCompare(a.checkInDate) || b.bookingId - a.bookingId);
  const page = Math.max(Number(param(q, 'page') ?? 0), 0);
  const size = Math.min(Math.max(Number(param(q, 'size') ?? 20), 1), 100);
  return ok(
    {
      content: found.slice(page * size, page * size + size).map((b) => toBooking(s, b, today)),
      page,
      size,
      totalElements: found.length,
      totalPages: Math.ceil(found.length / size),
    },
    200,
    'Danh sách đặt chỗ lưu trú',
  );
};

const getBooking: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  if (!VIEWERS.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const b = bookingFor(req, s, id);
  return isReply(b) ? b : ok(toBooking(s, b, vnDate(now)), 200, 'Chi tiết đặt chỗ lưu trú');
};

// ─── #11 Gia hạn, #12 Hủy ───

const extendBooking: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  const body = bodyOf(req.body);
  if (body.checkOutDate != null && !isDate(body.checkOutDate)) return malformed();
  const bad = invalid([['checkOutDate', 'Vui lòng chọn ngày trả mới', body.checkOutDate == null]]);
  if (bad) return bad;
  if (!BOOKERS.includes(roleOf(req))) return denied();
  const now = Date.now();
  const today = vnDate(now);
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  const newOut = body.checkOutDate as string;

  // A9: BOOKED / CHECKED_IN; OVERDUE và đã kết thúc → 409 (kiểm trước mọi 400 rule).
  if (b.status !== 'BOOKED' && b.status !== 'CHECKED_IN') return conflict('Đặt chỗ đang quá hạn đón hoặc đã kết thúc, không gia hạn được');
  if (today >= b.checkOutDate) return ruleFail('BR-LT-06', 'Đã tới ngày trả dự kiến, không gia hạn được');
  if (newOut <= b.checkOutDate) return fail('VALIDATION_FAILED', 'Ngày trả mới phải sau ngày trả hiện tại', 400);
  const nights = nightsError(b.checkInDate, newOut);
  if (nights) return nights;
  if (!openingRangesOn(b.branchId, newOut).length) return ruleFail('BR-LT-04', 'Ngày trả mới trùng ngày chi nhánh không làm việc');
  if (overlaps(s, b.petId, b.checkOutDate, newOut, b.bookingId)) return ruleFail('BR-LT-02', 'Thú cưng đã có đặt chỗ lưu trú trùng ngày');
  const short = shortNights(s, b.branchId, b.kennelTypeId, b.checkOutDate, newOut, today);
  if (short.length) return fullNightsError(short);

  b.checkOutDate = newOut;
  save(s);
  return ok(toBooking(s, b, today), 200, 'Đã gia hạn đặt chỗ lưu trú');
};

/** Hủy muộn khi bây giờ ≥ 00:00 giờ VN của ngày nhận − 24 giờ [CFG] (BoardingRules.lateCancelFrom, ADR-0065 D4). */
const lateCancelFrom = (checkInDate: string) => instantOf(checkInDate, '00:00') - CFG.lateCancelHours * HOUR;

const cancelBooking: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  const body = bodyOf(req.body);
  if (body.reason != null && typeof body.reason !== 'string') return malformed();
  const bad = invalid([['reason', 'Lý do tối đa 300 ký tự', tooLong(body.reason, 300)]]);
  if (bad) return bad;
  if (!BOOKERS.includes(roleOf(req))) return denied();
  const now = Date.now();
  const today = vnDate(now);
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (b.status !== 'BOOKED') return stateError('đặt chỗ lưu trú', b.status, 'CANCELLED');
  if (today >= b.checkInDate) return ruleFail('BR-LT-06', 'Đã tới ngày nhận, không hủy được');
  const late = now >= lateCancelFrom(b.checkInDate);
  b.status = 'CANCELLED';
  b.cancelSource = isCustomer(req) ? 'CUSTOMER' : 'STAFF';
  b.cancelReason = optionalText(body.reason);
  b.lateCancel = late;
  b.endedAt = iso(now);
  save(s);
  if (late && findCustomer(b.customerId)) evaluateBoardingViolation(b.customerId, now);
  return ok(toBooking(s, b, today), 200, 'Đã hủy đặt chỗ lưu trú');
};

// ─── #13 Điều kiện nhận thú, #14 Nhận thú (A1) ───

const NOT_IN_STAY = 'Thú cưng không còn trong chuồng';
const PHONE_RE = /^0[0-9]{9,10}$/;
const withinOpeningHours = (branchId: number, now: number) => isOpenAt(branchId, now);

/** #13 — ASSUMPTION A1 (BE chưa có, T42): 409 nếu không còn BOOKED; vaccineGaps tại hôm nay (BR-LT-05), chuồng AVAILABLE đúng loại, giờ mở cửa; canCheckIn = không thiếu mũi && trong giờ && còn chuồng. Chỉ lễ tân, CARETAKER. */
const checkInReadiness: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  if (!RECEIVERS.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (b.status !== 'BOOKED') return stateError('đặt chỗ lưu trú', b.status, 'CHECKED_IN');
  const gaps = vaccineGaps(b.petId, vnDate(now));
  const kennels = s.kennels.filter((k) => k.branchId === b.branchId && k.kennelTypeId === b.kennelTypeId && k.status === 'AVAILABLE').sort(byCode);
  const open = withinOpeningHours(b.branchId, now);
  return ok({ vaccineGaps: gaps, availableKennels: kennels.map((k) => toKennel(s, k)), withinOpeningHours: open, canCheckIn: gaps.length === 0 && open && kennels.length > 0 }, 200, 'Điều kiện nhận thú');
};

/** Cân nặng: > 0, tối đa 9999,99 và 2 chữ số thập phân (weight_records.weight_kg NUMERIC(6,2)). */
const weightOk = (w: number) => w <= 9999.99 && Math.abs(Math.round(w * 100) - w * 100) < 1e-6;

/** #14 — ASSUMPTION A1 (BE chưa có, T42): thứ tự 400 body → 403 → 404 đặt chỗ → 404 chuồng [tự đặt, trước 409] → 409 → BR-LT-04 → BR-LT-08 → BR-LT-05 → BR-LT-03 (nhận sớm); câu BR-LT-04 / 08 / 05 tự đặt; nhận sớm kéo ngày nhận về hôm nay. */
const checkIn: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  const b = bodyOf(req.body);
  if (
    (b.kennelId != null && !Number.isInteger(b.kennelId)) ||
    (b.weightKg != null && typeof b.weightKg !== 'number') ||
    ['healthCondition', 'belongings', 'dietInstructions', 'emergencyPhone'].some((k) => b[k] != null && typeof b[k] !== 'string')
  ) {
    return malformed();
  }
  const w = b.weightKg as number | undefined | null;
  const bad = invalid([
    ['kennelId', 'Vui lòng chọn chuồng', b.kennelId == null],
    ['weightKg', 'Vui lòng nhập cân nặng', w == null],
    ['weightKg', 'Cân nặng phải lớn hơn 0', typeof w === 'number' && w <= 0],
    ['weightKg', 'Cân nặng tối đa 9999.99 kg và 2 chữ số thập phân', typeof w === 'number' && w > 0 && !weightOk(w)],
    ['healthCondition', 'Vui lòng nhập tình trạng sức khỏe', !isText(b.healthCondition)],
    ['emergencyPhone', 'Vui lòng nhập số điện thoại liên hệ khẩn', !isText(b.emergencyPhone)],
    ['emergencyPhone', 'Số điện thoại liên hệ khẩn phải bắt đầu bằng 0 và có 10–11 chữ số', isText(b.emergencyPhone) && !PHONE_RE.test(b.emergencyPhone)],
  ]);
  if (bad) return bad;
  if (!RECEIVERS.includes(roleOf(req))) return denied();
  const now = Date.now();
  const today = vnDate(now);
  const s = load(now);
  const row = bookingFor(req, s, id);
  if (isReply(row)) return row;
  const kennel = s.kennels.find((k) => k.kennelId === b.kennelId && k.branchId === row.branchId);
  if (!kennel) return notFound('chuồng', b.kennelId as number);
  if (row.status !== 'BOOKED') return stateError('đặt chỗ lưu trú', row.status, 'CHECKED_IN');
  if (!withinOpeningHours(row.branchId, now)) return ruleFail('BR-LT-04', 'Chỉ nhận thú trong giờ mở cửa của chi nhánh');
  if (kennel.status !== 'AVAILABLE') return ruleFail('BR-LT-08', 'Chuồng không ở trạng thái trống');
  if (kennel.kennelTypeId !== row.kennelTypeId) return ruleFail('BR-LT-08', 'Chuồng không đúng loại chuồng đã đặt');
  const gaps = vaccineGaps(row.petId, today);
  if (gaps.length) {
    return ruleFail('BR-LT-05', `Thú cưng thiếu hoặc quá hạn mũi tiêm bắt buộc khi lưu trú: ${gaps.map((g) => g.vaccineTypeName).join(', ')}. Hãy tiếp nhận thú như walk-in để tiêm trước`);
  }
  // Nhận sớm: các đêm từ hôm nay tới trước ngày nhận cũ cũng phải còn chỗ (BR-LT-03).
  if (today < row.checkInDate) {
    const short = shortNights(s, row.branchId, row.kennelTypeId, today, row.checkInDate, today);
    if (short.length) return fullNightsError(short);
  }

  kennel.status = 'OCCUPIED';
  row.status = 'CHECKED_IN';
  row.kennelId = kennel.kennelId;
  row.actualCheckInAt = iso(now);
  if (today < row.checkInDate) row.checkInDate = today;
  row.checkIn = {
    weightKg: w as number,
    healthCondition: (b.healthCondition as string).trim(),
    belongings: optionalText(b.belongings),
    dietInstructions: optionalText(b.dietInstructions),
    emergencyPhone: b.emergencyPhone as string,
  };
  save(s);
  recordIntakeWeight(row.petId, w as number, row.bookingId, iso(now));
  return ok(toBooking(s, row, today), 200, 'Đã nhận thú lưu trú');
};

// ─── #15 Hủy vì hết chuồng (A2) ───

/** #15 — ASSUMPTION A2 (BE chưa có, T42): chỉ lễ tân; 409 không BOOKED; 400 BR-LT-08 nếu còn chuồng AVAILABLE đúng loại; CANCELLED / CLINIC / không hủy muộn; báo khách và BRANCH_MANAGER [loại thông báo và nội dung cho quản lý tự đặt]. */
const cancelNoKennel: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  if (!DESK.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (b.status !== 'BOOKED') return stateError('đặt chỗ lưu trú', b.status, 'CANCELLED');
  if (s.kennels.some((k) => k.branchId === b.branchId && k.kennelTypeId === b.kennelTypeId && k.status === 'AVAILABLE')) {
    return ruleFail('BR-LT-08', 'Vẫn còn chuồng trống đúng loại, hãy nhận thú');
  }
  clinicCancel(b, CLINIC_REASON.noKennel, now);
  save(s);
  for (const manager of staffOf(b.branchId, 'BRANCH_MANAGER')) {
    pushNotification(manager.id, {
      type: 'BOARDING_CANCELLED_BY_CLINIC',
      title: `Hết chuồng: đã hủy đặt chỗ ${b.code}`,
      body:
        `Đặt chỗ ${b.code} của ${petNameOf(b)} (chủ: ${customerNameOf(b)}) nhận ${ddMMyyyy(b.checkInDate)} đã bị hủy vì ${branchNameOf(b.branchId)} không còn chuồng trống loại ${kennelTypeNameOf(b.kennelTypeId)}.\n` +
        'Cân nhắc bổ sung chuồng hoặc đưa chuồng bảo trì trở lại hoạt động.',
      linkUrl: '/admin/boarding',
    });
  }
  return ok(toBooking(s, b, vnDate(now)), 200, 'Đã hủy đặt chỗ vì không còn chuồng trống');
};

// ─── #16 Bắt đầu trả thú, #17 Giao thú, #18 Thú mất (A3–A5) ───

/** Số đêm tính tiền (A3): max(1, hôm nay − ngày nhận thực tế) + 1 nếu quá 12:00 giờ VN. */
function billableNights(b: BookingRow, now: number): number {
  const today = vnDate(now);
  const start = b.actualCheckInAt ? vnDate(Date.parse(b.actualCheckInAt)) : b.checkInDate;
  return Math.max(1, nightsBetween(start, today)) + (now > instantOf(today, CFG.checkoutTime) ? 1 : 0);
}

function openCheckout(req: MockRequest, b: BookingRow, now: number) {
  const nights = billableNights(b, now);
  return openBoardingOrder({
    bookingId: b.bookingId,
    branchId: b.branchId,
    customerId: b.customerId,
    serviceId: b.kennelTypeId,
    description: `Lưu trú ${kennelTypeNameOf(b.kennelTypeId)} × ${nights} đêm`,
    nights,
    unitPrice: b.nightlyPrice,
    actorId: me(req).id,
  });
}

const checkoutResult = (s: State, b: BookingRow, order: ReturnType<typeof openBoardingOrder>, today: string) => ({
  booking: toBooking(s, b, today),
  orderId: order.orderId,
  nights: order.lines[0].quantity,
  amount: order.lines.reduce((sum, l) => sum + l.quantity * l.unitPrice, 0),
});

/** #16 — ASSUMPTION A3 (BE chưa có, T43): chỉ lễ tân; 409 thú không còn trong chuồng; 400 BR-LT-04 ngoài giờ mở cửa; Order BOARDING PENDING (dùng lại PENDING, trả lại PAID); câu 409 tự đặt. */
const startCheckout: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  if (!DESK.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (!inStay(b)) return conflict(NOT_IN_STAY);
  if (!withinOpeningHours(b.branchId, now)) return ruleFail('BR-LT-04', 'Chỉ trả thú trong giờ mở cửa của chi nhánh');
  const order = openCheckout(req, b, now);
  return ok(checkoutResult(s, b, order, vnDate(now)), 200, 'Đã bắt đầu trả thú');
};

/** Thú rời chuồng: chuồng về AVAILABLE (Chuồng#3), đặt chỗ giữ mã chuồng để tra lịch sử. */
function leaveKennel(s: State, b: BookingRow, reason: 'RETURNED' | 'PET_DECEASED', now: number) {
  b.status = 'CHECKED_OUT';
  b.endReason = reason;
  b.actualCheckOutAt = iso(now);
  const kennel = s.kennels.find((k) => k.kennelId === b.kennelId);
  if (kennel) kennel.status = 'AVAILABLE';
}

/** #17 — ASSUMPTION A4 (BE chưa có, T43): chỉ lễ tân; 409 không còn trong chuồng; 400 BR-LT-09 nếu Order lưu trú mới nhất chưa hủy chưa PAID hoặc chưa có; chuồng về AVAILABLE. */
const handOver: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  if (!DESK.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (!inStay(b)) return conflict(NOT_IN_STAY);
  if (activeBoardingOrder(b.bookingId)?.status !== 'PAID') return ruleFail('BR-LT-09', 'Order lưu trú chưa thanh toán');
  leaveKennel(s, b, 'RETURNED', now);
  save(s);
  return ok(toBooking(s, b, vnDate(now)), 200, 'Đã giao thú');
};

/** #18 — ASSUMPTION A5 (BE chưa có, T43): chỉ lễ tân, `note` bắt buộc; CHECKED_OUT / PET_DECEASED, Order PENDING tính đến hôm nay như A3; chuồng về AVAILABLE. */
const endDeceased: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  const body = bodyOf(req.body);
  if (body.note != null && typeof body.note !== 'string') return malformed();
  const bad = invalid([['note', 'Vui lòng ghi rõ diễn biến', !isText(body.note)]]);
  if (bad) return bad;
  if (!DESK.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (!inStay(b)) return conflict(NOT_IN_STAY);
  const order = openCheckout(req, b, now);
  leaveKennel(s, b, 'PET_DECEASED', now);
  b.endNote = (body.note as string).trim();
  save(s);
  return ok(checkoutResult(s, b, order, vnDate(now)), 200, 'Đã kết thúc lưu trú do thú mất');
};

// ─── #19–22 Nhật ký chăm sóc (A7, A8) ───

const HTTPS_RE = /^https:\/\/\S+$/;
const TEXT_FIELDS = ['eating', 'drinking', 'hygiene', 'activity'] as const;

/** Kiểu JSON của nhật ký (sai kiểu → MALFORMED) rồi Bean Validation (VALIDATION_FAILED). `creating`: thêm ngày nhật ký và danh sách ảnh bắt buộc. */
function careLogBodyError(b: Record<string, unknown>, creating: boolean, today: string): MockReply | null {
  if (
    TEXT_FIELDS.some((k) => b[k] != null && typeof b[k] !== 'string') ||
    (b.note != null && typeof b.note !== 'string') ||
    (b.logDate != null && !isDate(b.logDate)) ||
    (b.isAbnormal != null && typeof b.isAbnormal !== 'boolean') ||
    (b.photoUrls != null && (!Array.isArray(b.photoUrls) || b.photoUrls.some((u) => typeof u !== 'string')))
  ) {
    return malformed();
  }
  const photos = Array.isArray(b.photoUrls) ? (b.photoUrls as string[]) : [];
  const checks: Check[] = [
    ['logDate', 'Ngày nhật ký không được ở tương lai', creating && typeof b.logDate === 'string' && b.logDate > today],
    ...TEXT_FIELDS.map((k): Check => [k, 'Tối đa 300 ký tự', tooLong(b[k], 300)]),
    ['photoUrls', 'Vui lòng gửi danh sách ảnh (có thể để trống)', creating && b.photoUrls == null],
    ['photoUrls', `Tối đa ${CFG.careLogMaxPhotos} ảnh`, photos.length > CFG.careLogMaxPhotos],
    ['photoUrls', 'Ảnh phải là đường dẫn https', photos.some((u) => !HTTPS_RE.test(u))],
  ];
  return invalid(checks);
}

/** #19 — ASSUMPTION A7 (BE chưa có, T42): khách thú của mình (người khác 404), nhân viên chi nhánh mình (khác 403); sắp logDate giảm dần, id giảm dần. */
const listCareLogs: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  if (!VIEWERS.includes(roleOf(req))) return denied();
  const s = load(Date.now());
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  const logs = s.careLogs
    .filter((l) => l.bookingId === b.bookingId)
    .sort((x, y) => y.logDate.localeCompare(x.logDate) || y.careLogId - x.careLogId);
  return ok(logs.map((l) => toCareLog(s, l)), 200, 'Nhật ký chăm sóc');
};

/** CARE_LOG_ABNORMAL: báo khách (nếu có tài khoản) và mọi lễ tân chi nhánh, link theo người nhận (BR-LT-12, Đặt chỗ#8). */
function notifyAbnormal(b: BookingRow, log: CareLogRow) {
  const detail = log.note ?? 'Xem chi tiết trong nhật ký chăm sóc.';
  const notice = {
    type: 'CARE_LOG_ABNORMAL',
    title: `Nhật ký chăm sóc bất thường: ${petNameOf(b)}`,
    body: `Nhật ký chăm sóc ngày ${ddMMyyyy(log.logDate)} của ${petNameOf(b)} (chủ: ${customerNameOf(b)}) tại ${branchNameOf(b.branchId)} được đánh dấu bất thường.\n${detail}\nVui lòng liên hệ chủ thú và báo bác sĩ nếu cần.`,
  };
  const account = customerAccountOf(b);
  if (account !== null) pushNotification(account, { ...notice, linkUrl: `/boarding-bookings/${b.bookingId}` });
  for (const desk of staffOf(b.branchId, 'RECEPTIONIST')) pushNotification(desk.id, { ...notice, linkUrl: '/admin/boarding' });
}

/** #20 — ASSUMPTION A7 (BE chưa có, T42): VET, CARETAKER; 409 khi không CHECKED_IN / OVERDUE; logDate tương lai và ảnh sai là 400 body [chạy trước 403, tự đặt]; bất thường → thông báo CARE_LOG_ABNORMAL. */
const createCareLog: MockHandler = (req) => {
  const id = idParam(req, 'bookingId');
  if (isReply(id)) return id;
  const body = bodyOf(req.body);
  const now = Date.now();
  const today = vnDate(now);
  const bad = careLogBodyError(body, true, today);
  if (bad) return bad;
  if (!CARE_WRITERS.includes(roleOf(req))) return denied();
  const s = load(now);
  const b = bookingFor(req, s, id);
  if (isReply(b)) return b;
  if (!inStay(b)) return conflict('Thú cưng không đang lưu trú');
  const row: CareLogRow = {
    careLogId: Math.max(0, ...s.careLogs.map((l) => l.careLogId)) + 1,
    bookingId: b.bookingId,
    logDate: (body.logDate as string | undefined) ?? today,
    eating: optionalText(body.eating),
    drinking: optionalText(body.drinking),
    hygiene: optionalText(body.hygiene),
    activity: optionalText(body.activity),
    note: optionalText(body.note),
    photoUrls: body.photoUrls as string[],
    isAbnormal: body.isAbnormal === true,
    recordedBy: me(req).id,
    createdAt: iso(now),
  };
  s.careLogs.push(row);
  save(s);
  if (row.isAbnormal) notifyAbnormal(b, row);
  return ok(toCareLog(s, row), 201, 'Đã ghi nhật ký chăm sóc');
};

/** Nhật ký theo id rồi chi nhánh của đặt chỗ (nhân viên): 404 → 403 phạm vi. */
function careLogFor(req: MockRequest, s: State, id: number): { log: CareLogRow; booking: BookingRow } | MockReply {
  const log = s.careLogs.find((l) => l.careLogId === id);
  if (!log) return notFound('nhật ký chăm sóc', id);
  const booking = s.bookings.find((b) => b.bookingId === log.bookingId)!;
  return branchOf(req) === booking.branchId ? { log, booking } : scopeDenied();
}

/** #21 — ASSUMPTION A7 (BE chưa có, T42): chỉ người ghi (người khác 403 ACCESS_DENIED), trong 60 phút [CFG], quá hạn 409 "… (BR-LT-11)". */
const updateCareLog: MockHandler = (req) => {
  const id = idParam(req, 'careLogId');
  if (isReply(id)) return id;
  const body = bodyOf(req.body);
  const now = Date.now();
  const bad = careLogBodyError(body, false, vnDate(now));
  if (bad) return bad;
  if (!CARE_WRITERS.includes(roleOf(req))) return denied();
  const s = load(now);
  const found = careLogFor(req, s, id);
  if (isReply(found)) return found;
  const { log } = found;
  if (log.recordedBy !== me(req).id) return denied();
  if (now >= Date.parse(log.createdAt) + CFG.careLogEditWindowMinutes * MINUTE) {
    return conflict('Đã quá thời hạn sửa nhật ký, vui lòng thêm bản bổ sung (BR-LT-11)');
  }
  for (const k of [...TEXT_FIELDS, 'note'] as const) if (typeof body[k] === 'string') log[k] = optionalText(body[k]);
  if (Array.isArray(body.photoUrls)) log.photoUrls = body.photoUrls as string[];
  save(s);
  return ok(toCareLog(s, log), 200, 'Đã cập nhật nhật ký chăm sóc');
};

/** #22 — ASSUMPTION A7 và A4 hợp đồng (BE chưa có, T42): VET, CARETAKER chi nhánh bổ sung bất cứ lúc nào, 1–2000 ký tự. */
const addAddendum: MockHandler = (req) => {
  const id = idParam(req, 'careLogId');
  if (isReply(id)) return id;
  const body = bodyOf(req.body);
  if (body.content != null && typeof body.content !== 'string') return malformed();
  const bad = invalid([
    ['content', 'Vui lòng nhập nội dung bổ sung', !isText(body.content)],
    ['content', 'Nội dung tối đa 2000 ký tự', tooLong(body.content, 2000)],
  ]);
  if (bad) return bad;
  if (!CARE_WRITERS.includes(roleOf(req))) return denied();
  const now = Date.now();
  const s = load(now);
  const found = careLogFor(req, s, id);
  if (isReply(found)) return found;
  const row: AddendumRow = {
    addendumId: Math.max(0, ...s.addenda.map((a) => a.addendumId)) + 1,
    careLogId: found.log.careLogId,
    content: (body.content as string).trim(),
    createdBy: me(req).id,
    createdAt: iso(now),
  };
  s.addenda.push(row);
  save(s);
  return ok({ addendumId: row.addendumId, content: row.content, createdBy: row.createdBy, createdAt: row.createdAt }, 201, 'Đã thêm bản bổ sung');
};

// ─── Đăng ký nguồn cho mock khác (tránh import vòng: những mock đó không import file này) ───

/** Lúc mock khác gọi ngược vào đây từ BÊN TRONG tác vụ hệ thống của file này, `load` không chạy lại tác vụ (cờ `sweeping`). */
const everything = () => load(Date.now());

registerBoardingSource({
  booked: (branchId): AffectedBoarding[] =>
    everything()
      .bookings.filter((b) => b.branchId === branchId && b.status === 'BOOKED')
      .sort((a, b) => a.checkInDate.localeCompare(b.checkInDate) || a.bookingId - b.bookingId)
      .map((b) => ({
        bookingId: b.bookingId,
        code: b.code,
        checkInDate: b.checkInDate,
        checkOutDate: b.checkOutDate,
        petName: petNameOf(b),
        customerName: customerNameOf(b),
        customerPhone: findCustomer(b.customerId)?.phone ?? b.customerPhone,
      })),
  cancelByClinic: (bookingIds, cause) => {
    const now = Date.now();
    const s = everything();
    const reason = cause === 'HOLIDAY_ADDED' ? CLINIC_REASON.holidayAdded : CLINIC_REASON.hoursReduced;
    for (const b of s.bookings.filter((x) => bookingIds.includes(x.bookingId) && x.status === 'BOOKED')) clinicCancel(b, reason, now);
    save(s);
  },
});
// Khách tại quầy chỉ có tên trong đặt chỗ mẫu (chưa có hồ sơ): bàn thu tiền và chi tiết khách vẫn tra được theo id.
registerCounterGuests((customerId) => {
  const b = everything().bookings.find((x) => x.customerId === customerId);
  return b && {
    customerId,
    accountId: null,
    fullName: b.customerName,
    phone: b.customerPhone,
    email: null,
    avatarUrl: null,
    createdChannel: 'COUNTER',
    linkDecisionPending: false,
    hasData: true,
  };
});
registerStaySource((petId) => everything().bookings.some((b) => b.petId === petId && inStay(b)));
registerBoardingStay((bookingId) => everything().bookings.some((b) => b.bookingId === bookingId && inStay(b)));
// Nguồn vi phạm đọc state lưu KHÔNG chạy tác vụ: nó được gọi từ trong đánh giá hạn chế của chính các tác vụ đó.
registerBoardingViolations((customerId) =>
  readState(Date.now())
    .state.bookings.filter((b) => b.customerId === customerId && b.endedAt !== null && (b.status === 'NO_SHOW' || (b.status === 'CANCELLED' && b.lateCancel)))
    .map((b) => Date.parse(b.endedAt!)),
);
registerBoardingSweep((now) => {
  if (!sweeping) load(now);
});

export const boardingRoutes: Record<string, MockHandler> = {
  'GET /api/kennels': listKennels,
  'POST /api/kennels': createKennel,
  'PATCH /api/kennels/:kennelId': updateKennel,
  'DELETE /api/kennels/:kennelId': deleteKennel,
  'POST /api/kennels/:kennelId/start-maintenance': startMaintenance,
  'POST /api/kennels/:kennelId/end-maintenance': endMaintenance,
  'GET /api/branches/:branchId/boarding-availability': availability,
  'POST /api/boarding-bookings': createBooking,
  'GET /api/boarding-bookings': listBookings,
  'GET /api/boarding-bookings/:bookingId': getBooking,
  'POST /api/boarding-bookings/:bookingId/extend': extendBooking,
  'POST /api/boarding-bookings/:bookingId/cancel': cancelBooking,
  'GET /api/boarding-bookings/:bookingId/check-in-readiness': checkInReadiness,
  'POST /api/boarding-bookings/:bookingId/check-in': checkIn,
  'POST /api/boarding-bookings/:bookingId/cancel-no-kennel': cancelNoKennel,
  'POST /api/boarding-bookings/:bookingId/start-checkout': startCheckout,
  'POST /api/boarding-bookings/:bookingId/hand-over': handOver,
  'POST /api/boarding-bookings/:bookingId/end-deceased': endDeceased,
  'GET /api/boarding-bookings/:bookingId/care-logs': listCareLogs,
  'POST /api/boarding-bookings/:bookingId/care-logs': createCareLog,
  'PATCH /api/care-logs/:careLogId': updateCareLog,
  'POST /api/care-logs/:careLogId/addenda': addAddendum,
};
