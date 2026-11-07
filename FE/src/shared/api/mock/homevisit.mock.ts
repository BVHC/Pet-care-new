// Mock homevisit-v1 (UC43, UC51, BR-LH-13, 14, BR-KB-07, BR-BH-16; docs/api/homevisit-v1.md #1–6; 09 §A; 03 #22) — T57 bước A. BE tầng 3 CHƯA cài (T54 chỉ duyệt đặc tả), nên không có số đo
// thật: mọi chỗ dưới đây là chép hợp đồng (openapi/homevisit-v1.yaml: path, body, "Quyền:", mã lỗi) cộng ASSUMPTION khi hợp đồng im lặng.
//
// Lịch khám tại nhà là MỘT HÀNG của bảng `appointments` có cờ `is_home_visit` (09 §A), nên state KHÔNG ở đây mà ở appointment.mock.ts (key `petcare-mock-appointments`, thêm
// `homeSeeded` + các trường chỉ lịch tại nhà mới có): quota khung giờ, giới hạn lịch của thú, hạn chế đặt online, hủy / đổi giờ (`POST /appointments/{id}/cancel|reschedule`, BR-LH-14) dùng chung.
//
// Thứ tự kiểm (00-method §3.5; chỗ yaml im lặng giữ thứ tự này cho cả 6 endpoint):
//   401 (adapter) → 400 path / query / body sai kiểu (MALFORMED_REQUEST) → 403 vai trò (ACCESS_DENIED) → 400 VALIDATION_FAILED → 403 phạm vi chi nhánh của lễ tân ở #1 (body có `branchId`)
//   → 404 → 403 phạm vi (ACCESS_DENIED_SCOPE_MISMATCH, message chung) → 400 BR-… → 409. Quyền theo "Quyền:" của yaml: BRANCH_MANAGER, SUPER_MANAGER, ADMIN không có endpoint nào ở đây.
//
// ASSUMPTION (đã cân nhắc, ghi để không bị tính là lệch BE):
//  A1  `GET /appointments` (appointment-v1 #3) KHÔNG liệt kê lịch tại nhà (hợp đồng im lặng; chúng có tài nguyên riêng, và danh sách chờ check-in `checkInReady` không được lẫn lịch mà bác sĩ tự bắt đầu).
//      Thao tác theo mã (chi tiết, đổi giờ, hủy) của appointment-v1 vẫn chạy trên lịch tại nhà, trả `Appointment` không có trường tại nhà. Lễ tân check-in lịch tại nhà qua `POST /visits` → 400 BR-KB-07.
//  A2  ST05 (NO_SHOW tự động) bỏ qua lịch tại nhà (BR-LH-14, A3). "Không gặp được" đếm vào BR-LH-09 ngay lúc ghi (không phải giờ hẹn + 30'); khách có tài khoản nhận tin HOME_VISIT_UNREACHABLE (chữ do mock đặt).
//  A3  Mã rule theo mã ĐẦU của từng dòng lỗi trong yaml: #1 "BR-LH-13 chi nhánh chưa bật · dịch vụ không phải EXAM / không bật · địa chỉ không phải của khách · thú đã mất" → cả bốn vế là BR-LH-13
//      (lịch thường dùng BR-KH-05 cho thú mất; ở đây theo chữ yaml); #5 mọi vế là BR-KB-07 (trừ BR-TN-01); #6 là BR-LH-14. `branchId` chưa ACTIVE cũng là BR-LH-13 (lịch thường: BR-LH-01); khách còn 404.
//  A4  Địa chỉ: khách chỉ được chọn `addressId` trong sổ địa chỉ của mình (gửi `address` nhập tay → 400 BR-LH-13); lễ tân chọn `addressId` của khách đó (đọc sổ qua route `/me/addresses` của customer.mock với tài khoản
//      của khách; hồ sơ tại quầy không có tài khoản nên không có sổ) hoặc nhập `address`; đủ cả hai hoặc thiếu cả hai → 400 BR-LH-13. `addressId` không tồn tại ở mọi sổ → 404, của khách khác → 400 BR-LH-13.
//      Lễ tân truyền `customerId` không khớp chủ của thú → 404 thú cưng (như thú của khách khác). Chuỗi địa chỉ chốt = "đường, phường, tỉnh".
//  A5  Quota đầy → 409 CONCURRENCY_CONFLICT "khung vừa hết chỗ (BR-LH-03)" đúng yaml (lịch thường là 400 BR-LH-03); khung ngoài giờ / không đúng mốc 30' vẫn là 400 BR-LH-02.
//  A6  Phí khám tại nhà: nguồn đăng ký `registerHomeVisitFeeSource((branchId) => number | null)`, `null` = chi nhánh chưa bật (400 BR-LH-13). Mặc định tất định: chi nhánh 1 → 100.000 ₫, chi nhánh 2 → 120.000 ₫, còn lại null.
//      Luồng chính nối `homeVisitFeeOf` của fees.mock. Phí chốt vào lịch lúc đặt (BR-SP-11); đổi nguồn sau đó không đổi lịch cũ.
//  A7  Bắt đầu (#5): thứ tự 404 → 403 chi nhánh → 400 BR-KB-07 (không phải lịch tại nhà, ngoài ngày hẹn, thú đã mất) → 409 trạng thái → 400 BR-TN-01 (thú còn lượt mở). 409 đứng TRƯỚC BR-TN-01 vì BR-TN-01 sinh ra khi tạo Visit,
//      sau khi khóa lịch; nếu không, bấm bắt đầu lần hai sẽ báo "thú còn lượt mở" (chính lượt vừa tạo) thay vì 409. Lượt tạo ra IN_PROGRESS gán VET gọi, KHÔNG vào hàng đợi chi nhánh (`GET /visits`) nhưng có trong `GET /me/queue` của VET.
//      Order VISIT mở với dịch vụ khám của lịch; dòng HOME_FEE qua `addHomeFeeLineToVisitOrder` (nguồn đăng ký, mặc định mã dòng giả, KHÔNG sửa Order thật: xem clinical-shared.ts).
//  A8  Không mô phỏng: nhắc lịch ST03, audit, khóa lịch đồng thời. #6 chỉ thông báo khách; #4 của khách luôn 404 cho lịch người khác (ADR-0006), nhân viên chi nhánh khác 403.
//  A9  Dữ liệu mẫu nạp lười ở lần đầu một route tại nhà chạy (appointment.mock.ts `seedHomeVisits`), không đụng test GĐ1: chi nhánh 1 có 2 lịch BOOKED (Tôm hôm nay 16:30, Bim sau 2 ngày), 1 COMPLETED, 1 CANCELLED, 1 NO_SHOW;
//      chi nhánh 2 có 1 BOOKED. Mọi thú mẫu đều còn lượt mở của visit.mock nên "Bắt đầu" lần đầu cho Tôm ra 400 BR-TN-01 cho tới khi lễ tân hủy lượt 2102 (đúng luật, cũng là cách thử).
import type { AccountSummary, Role } from '../../types/auth';
import type { Address } from '../../types/profile';
import type { HomeVisitAppointment, HomeVisitAppointmentSummary, HomeVisitStarted, HomeVisitStatus } from '../../types/homevisit';
import { HOME_VISIT_LIMITS, HOME_VISIT_PHONE_PATTERN, HOME_VISIT_STATUSES } from '../../types/homevisit';
import { vnDate } from '../../utils/vn-time';
import {
  bookHomeVisitAppointment,
  checkInHomeVisit,
  findAppointmentRow,
  homeVisitAppointments,
  markHomeVisitNoShow,
  type HomeVisitRow,
} from './appointment.mock';
import { branchBooking } from './branch.mock';
import { pushNotification } from './care.mock';
import { addHomeFeeLineToVisitOrder, findClinicalService } from './clinical-shared';
import { allCustomers, customerOfAccount, customerRoutes, findCustomer } from './customer.mock';
import { petSummary } from './customer-pet.mock';
import { denied, fail, invalid, notFound, ok, ruleFail, scopeDenied, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { branchIdOf } from './identity.mock';
import { clampPaging, pageOf, typeError } from './public.mock';
import { asLong, asText, conflict, idParam, isJsonObject, isReply, malformed, startDirectVisit } from './visit.mock';

const CREATE_ROLES: readonly Role[] = ['CUSTOMER', 'RECEPTIONIST'];
const MINE_ROLES: readonly Role[] = ['CUSTOMER'];
const LIST_ROLES: readonly Role[] = ['RECEPTIONIST', 'VET'];
const DETAIL_ROLES: readonly Role[] = ['CUSTOMER', 'RECEPTIONIST', 'VET'];
const VET_ONLY: readonly Role[] = ['VET'];

// ─── Phí khám tại nhà (BR-SP-09, 11) ───

/** `null` = chi nhánh chưa bật khám tại nhà. Tất định, xem A6. */
export const defaultHomeVisitFee = (branchId: number): number | null => ({ 1: 100_000, 2: 120_000 } as Record<number, number>)[branchId] ?? null;
let feeSource: (branchId: number) => number | null = defaultHomeVisitFee;

/** Nối với `homeVisitFeeOf(branchId)` của fees.mock (`CatalogQueryApi.findHomeVisitFee`). */
export function registerHomeVisitFeeSource(source: (branchId: number) => number | null) {
  feeSource = source;
}

// ─── Dùng chung ───

const me = (req: MockRequest) => req.account!;
const branchOf = (req: MockRequest) => branchIdOf(me(req).id) ?? null;
const allow = (roles: readonly Role[], req: MockRequest) => roles.includes(me(req).role);
const isCustomer = (req: MockRequest) => me(req).role === 'CUSTOMER';
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const brokenProfile = (accountId: number) => fail('INTERNAL_ERROR', `Tài khoản khách #${accountId} không có hồ sơ khách`, 500);
const NAME = 'lịch hẹn';
const NOT_HOME = 'lịch khám tại nhà';

const isDate = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) && new Date(Date.parse(v)).toISOString().slice(0, 10) === v;
const isTime = (v: unknown): v is string => typeof v === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(v);
const blank = (v: string | null | undefined) => v === null || v === undefined || v.trim() === '';
const tooLong = (v: string | null | undefined, max: number) => typeof v === 'string' && v.length > max;

const petNameOf = (petId: number) => petSummary(petId)?.name ?? `Thú cưng #${petId}`;
const serviceNameOf = (serviceId: number) => findClinicalService(serviceId)?.name ?? `Dịch vụ #${serviceId}`;

const view = (a: HomeVisitRow): HomeVisitAppointment => ({
  appointmentId: a.appointmentId,
  code: a.code,
  petId: a.petId,
  petName: petNameOf(a.petId),
  customerId: a.customerId,
  serviceId: a.serviceId,
  serviceName: serviceNameOf(a.serviceId),
  branchId: a.branchId,
  slotDate: a.slotDate,
  slotStart: a.slotStart,
  status: a.status as HomeVisitStatus,
  homeAddress: a.homeAddress,
  homeContactPhone: a.homeContactPhone,
  homeFee: a.homeFee,
  note: a.note,
  visitId: a.visitId,
  createdAt: a.createdAt,
});

const summary = (a: HomeVisitRow): HomeVisitAppointmentSummary => ({
  appointmentId: a.appointmentId,
  code: a.code,
  petName: petNameOf(a.petId),
  serviceName: serviceNameOf(a.serviceId),
  slotDate: a.slotDate,
  slotStart: a.slotStart,
  status: a.status as HomeVisitStatus,
  homeAddress: a.homeAddress,
  homeFee: a.homeFee,
});

/** Sổ địa chỉ của một khách (BR-TK-18), đọc qua route `/me/addresses` của customer.mock với tài khoản của chính khách; hồ sơ tại quầy (không tài khoản) không có sổ (A4). */
function addressBookOf(customerId: number): Address[] {
  const accountId = findCustomer(customerId)?.accountId;
  if (accountId == null) return [];
  const account: AccountSummary = { id: accountId, email: '', role: 'CUSTOMER', status: 'ACTIVE', isLocked: false, mustChangePassword: false };
  const reply = customerRoutes['GET /api/me/addresses']({ body: null, query: {}, params: {}, account });
  return reply.status === 200 ? (reply.body as { data: Address[] }).data : [];
}
/** Địa chỉ theo mã ở sổ của bất kỳ khách nào, kèm chủ sổ. */
const findAddress = (addressId: number) => {
  for (const c of allCustomers()) {
    const address = addressBookOf(c.customerId).find((x) => x.addressId === addressId);
    if (address) return { address, customerId: c.customerId };
  }
  return undefined;
};
const addressText = (a: Address) => [a.addressLine, a.ward, a.province].filter((x) => x && x.trim() !== '').join(', ');

// ─── #1 Đặt lịch ───

/**
 * #1 (UC43, BR-LH-13): body sai kiểu → khách / lễ tân → thiếu / sai định dạng, địa chỉ / ghi chú dài 400 → lễ tân đặt chi nhánh khác 403 → thú / khách / chi nhánh / dịch vụ / địa chỉ 404
 * → 400 BR-LH-13 (chi nhánh chưa bật, dịch vụ, địa chỉ) → BR-TK-19 → (appointment.mock) BR-LH-09 → thú mất → BR-LH-04 → BR-LH-02 → BR-LH-05 → quota 409.
 */
const createHomeVisit: MockHandler = (req) => {
  if (!isJsonObject(req.body)) return malformed();
  const b = req.body;
  const petId = asLong(b.petId);
  const serviceId = asLong(b.serviceId);
  const branchId = asLong(b.branchId);
  const addressId = asLong(b.addressId);
  const customerId = asLong(b.customerId);
  const address = asText(b.address);
  const contactPhone = asText(b.contactPhone);
  const note = asText(b.note);
  const slotDate = b.slotDate ?? null;
  const slotStart = b.slotStart ?? null;
  if ([petId, serviceId, branchId, addressId, customerId, address, contactPhone, note].includes(undefined)) return malformed();
  if ((slotDate !== null && !isDate(slotDate)) || (slotStart !== null && !isTime(slotStart))) return malformed();
  if (!allow(CREATE_ROLES, req)) return denied();
  const id = (name: string, v: number | null | undefined, required: boolean): [string, string, boolean][] => [
    [name, 'Thiếu mã', required && v === null],
    [name, 'Mã không hợp lệ', v != null && v <= 0],
  ];
  const bad = invalid([
    ...id('petId', petId, true),
    ...id('serviceId', serviceId, true),
    ...id('branchId', branchId, true),
    ...id('addressId', addressId, false),
    ...id('customerId', customerId, false),
    ['slotDate', 'Thiếu ngày hẹn', slotDate === null],
    ['slotStart', 'Thiếu giờ bắt đầu của khung', slotStart === null],
    ['contactPhone', 'Thiếu số điện thoại liên hệ', blank(contactPhone)],
    ['contactPhone', 'Số điện thoại liên hệ không hợp lệ', !blank(contactPhone) && (!HOME_VISIT_PHONE_PATTERN.test(contactPhone!) || contactPhone!.length > HOME_VISIT_LIMITS.contactPhone)],
    ['address', `Địa chỉ tối đa ${HOME_VISIT_LIMITS.address} ký tự`, tooLong(address, HOME_VISIT_LIMITS.address)],
    ['note', `Ghi chú tối đa ${HOME_VISIT_LIMITS.note} ký tự`, tooLong(note, HOME_VISIT_LIMITS.note)],
  ]);
  if (bad) return bad;

  const customer = isCustomer(req);
  if (!customer && branchOf(req) !== branchId) return scopeDenied();
  const own = customer ? customerOfAccount(me(req).id) : undefined;
  if (customer && !own) return brokenProfile(me(req).id);
  const pet = petSummary(petId as number);
  if (!pet || (own && pet.customerId !== own.customerId)) return notFound('thú cưng', petId as number);
  if (!customer && customerId !== null) {
    if (!findCustomer(customerId as number)) return notFound('khách hàng', customerId as number);
    if (pet.customerId !== customerId) return notFound('thú cưng', petId as number);
  }
  const booking = branchBooking(branchId as number);
  if (!booking || (customer && booking.branch.status !== 'ACTIVE')) return notFound('chi nhánh', branchId as number);
  const service = findClinicalService(serviceId as number);
  if (!service) return notFound('dịch vụ', serviceId as number);
  const book = addressId == null ? undefined : findAddress(addressId);
  if (addressId != null && !book) return notFound('địa chỉ', addressId);

  if (booking.branch.status !== 'ACTIVE') return ruleFail('BR-LH-13', 'Chi nhánh chưa nhận đặt lịch khám tại nhà');
  const fee = feeSource(branchId as number);
  if (fee === null) return ruleFail('BR-LH-13', 'Chi nhánh chưa bật dịch vụ khám tại nhà');
  if (service.medicalType !== 'EXAM') return ruleFail('BR-LH-13', 'Dịch vụ khám tại nhà phải là dịch vụ khám');
  if (!service.isActive || !service.enabledAt(branchId as number)) return ruleFail('BR-LH-13', 'Dịch vụ khám đã ngừng hoặc chưa bật tại chi nhánh');
  const typed = !blank(address);
  if (addressId != null && typed) return ruleFail('BR-LH-13', 'Chỉ chọn một trong địa chỉ trong sổ hoặc địa chỉ nhập tay');
  if (customer && typed) return ruleFail('BR-LH-13', 'Khách chỉ chọn địa chỉ trong sổ địa chỉ của mình');
  if (addressId == null && !typed) return ruleFail('BR-LH-13', customer ? 'Vui lòng chọn địa chỉ khám tại nhà trong sổ địa chỉ' : 'Vui lòng chọn hoặc nhập địa chỉ khám tại nhà');
  if (book && book.customerId !== pet.customerId) return ruleFail('BR-LH-13', 'Địa chỉ không thuộc sổ địa chỉ của khách');
  if (own?.linkDecisionPending) return ruleFail('BR-TK-19', 'Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám');

  const booked = bookHomeVisitAppointmentChecked({
    petId: petId as number,
    branchId: branchId as number,
    serviceId: service.serviceId,
    slotDate: slotDate as string,
    slotStart: slotStart as string,
    homeAddress: book ? addressText(book.address) : address!.trim(),
    homeContactPhone: contactPhone!.trim(),
    homeFee: fee,
    note: note?.trim() || null,
    online: customer,
    bookedBy: me(req).id,
  });
  return isReply(booked) ? booked : ok(view(booked), 201, 'Đã đặt lịch khám tại nhà');
};

const bookHomeVisitAppointmentChecked = (input: Parameters<typeof bookHomeVisitAppointment>[0]): HomeVisitRow | MockReply => {
  const result = bookHomeVisitAppointment(input);
  return result.ok ? result.row : result.reply;
};

// ─── #2, #3, #4 Xem ───

/** #2 (UC43): khách; query sai kiểu → vai trò. Mới nhất trước (ngày khám giảm dần, trùng thì mã giảm dần), phân trang. */
const listMine: MockHandler = (req) => {
  const { status } = req.query;
  if (status !== undefined && !(HOME_VISIT_STATUSES as readonly string[]).includes(status)) return badParam('status');
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  if (!allow(MINE_ROLES, req)) return denied();
  const own = customerOfAccount(me(req).id);
  if (!own) return brokenProfile(me(req).id);
  const found = homeVisitAppointments()
    .filter((a) => a.customerId === own.customerId && (status === undefined || a.status === status))
    .sort((a, b) => b.slotDate.localeCompare(a.slotDate) || b.appointmentId - a.appointmentId);
  return ok(pageOf(found.map(summary), clampPaging(req.query)));
};

/** #3 (UC51): lễ tân, VET; query sai kiểu → vai trò. `date` vắng = hôm nay (giờ VN); sắp giờ bắt đầu tăng dần (trùng thì mã tăng dần); phân trang. */
const listHomeVisits: MockHandler = (req) => {
  const { date, status } = req.query;
  if (date !== undefined && !isDate(date)) return badParam('date');
  if (status !== undefined && !(HOME_VISIT_STATUSES as readonly string[]).includes(status)) return badParam('status');
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  if (!allow(LIST_ROLES, req)) return denied();
  const day = date ?? vnDate();
  const found = homeVisitAppointments()
    .filter((a) => a.branchId === branchOf(req) && a.slotDate === day && (status === undefined || a.status === status))
    .sort((a, b) => a.slotStart.localeCompare(b.slotStart) || a.appointmentId - b.appointmentId);
  return ok(pageOf(found.map(summary), clampPaging(req.query)));
};

/** #4 (UC51): path → khách / lễ tân / VET → 404 (kể cả lịch thường không phải lịch tại nhà, và lịch của người khác với khách: ADR-0006) → nhân viên chi nhánh khác 403. */
const getHomeVisit: MockHandler = (req) => {
  const appointmentId = idParam(req, 'appointmentId');
  if (isReply(appointmentId)) return appointmentId;
  if (!allow(DETAIL_ROLES, req)) return denied();
  const row = homeVisitAppointments().find((a) => a.appointmentId === appointmentId);
  if (isCustomer(req)) {
    const own = customerOfAccount(me(req).id);
    if (!own) return brokenProfile(me(req).id);
    return row && row.customerId === own.customerId ? ok(view(row)) : notFound(NOT_HOME, appointmentId);
  }
  if (!row) return notFound(NOT_HOME, appointmentId);
  return row.branchId === branchOf(req) ? ok(view(row)) : scopeDenied();
};

// ─── #5 Bắt đầu, #6 Không gặp được ───

/** Lịch theo mã (mọi lịch hẹn, để phân biệt "không có" với "không phải lịch tại nhà") rồi chi nhánh của VET (403 phạm vi). */
function appointmentFor(req: MockRequest, appointmentId: number) {
  const row = findAppointmentRow(appointmentId);
  if (!row) return notFound(NAME, appointmentId);
  return row.branchId === branchOf(req) ? row : scopeDenied();
}

/** #5 (UC51, BR-KB-07, BR-BH-16): path → VET → 404 → chi nhánh 403 → 400 BR-KB-07 → 409 → 400 BR-TN-01 (A7). Tạo Visit IN_PROGRESS gán VET, mở Order VISIT kèm dòng phí khám tại nhà. */
const startHomeVisit: MockHandler = (req) => {
  const appointmentId = idParam(req, 'appointmentId');
  if (isReply(appointmentId)) return appointmentId;
  if (!allow(VET_ONLY, req)) return denied();
  const row = appointmentFor(req, appointmentId);
  if (isReply(row)) return row;
  if (!row.isHomeVisit) return ruleFail('BR-KB-07', 'Không phải lịch khám tại nhà');
  const now = Date.now();
  if (row.slotDate !== vnDate(now)) return ruleFail('BR-KB-07', 'Chỉ bắt đầu khám tại nhà trong ngày hẹn');
  if (petSummary(row.petId)?.deceasedOn) return ruleFail('BR-KB-07', 'Thú cưng đã mất, không khám tại nhà được');
  if (row.status !== 'BOOKED') return conflict(NAME, row.status, 'CHECKED_IN');

  const started = startDirectVisit({ branchId: row.branchId, customerId: row.customerId, petId: row.petId, serviceId: row.serviceId, vetId: me(req).id, appointmentId, isHomeVisit: true, now });
  if (!started.ok) {
    return started.reason === 'PET_BUSY'
      ? ruleFail('BR-TN-01', 'Thú cưng đang có lượt khám chưa kết thúc')
      : fail('INTERNAL_ERROR', 'Dịch vụ khám của lịch không còn kinh doanh, không mở được Order', 500);
  }
  const { visitId, orderId } = started.visit;
  if (addHomeFeeLineToVisitOrder(visitId, row.homeFee ?? 0, me(req).id) === null) return fail('INTERNAL_ERROR', `Lượt #${visitId} không có Order đang mở`, 500);
  checkInHomeVisit(appointmentId, visitId);
  const result: HomeVisitStarted = { appointmentId, visitId, orderId };
  return ok(result, 200, 'Đã bắt đầu khám tại nhà');
};

/**
 * #6 (UC51, BR-LH-14, BR-LH-09): path / body sai kiểu → VET → ghi chú > 300 → 404 → chi nhánh 403 → 400 BR-LH-14 (không phải lịch tại nhà, ngoài ngày hẹn, thiếu ghi chú) → 409 không BOOKED.
 * BOOKED → NO_SHOW, tính vào hạn chế đặt online ngay lúc ghi; báo khách có tài khoản (A2).
 */
const markUnreachable: MockHandler = (req) => {
  const appointmentId = idParam(req, 'appointmentId');
  if (isReply(appointmentId)) return appointmentId;
  if (!isJsonObject(req.body)) return malformed();
  const note = asText(req.body.note);
  if (note === undefined) return malformed();
  if (!allow(VET_ONLY, req)) return denied();
  const bad = invalid([['note', `Ghi chú tối đa ${HOME_VISIT_LIMITS.note} ký tự`, tooLong(note, HOME_VISIT_LIMITS.note)]]);
  if (bad) return bad;
  const row = appointmentFor(req, appointmentId);
  if (isReply(row)) return row;
  if (!row.isHomeVisit) return ruleFail('BR-LH-14', 'Không phải lịch khám tại nhà');
  const now = Date.now();
  if (row.slotDate !== vnDate(now)) return ruleFail('BR-LH-14', 'Chỉ ghi nhận không gặp được khách trong ngày hẹn');
  if (blank(note)) return ruleFail('BR-LH-14', 'Phải ghi chú khi không gặp được khách');
  if (row.status !== 'BOOKED') return conflict(NAME, row.status, 'NO_SHOW');

  markHomeVisitNoShow(appointmentId, note!.trim(), now);
  const updated = homeVisitAppointments(now).find((a) => a.appointmentId === appointmentId)!;
  const account = findCustomer(updated.customerId)?.accountId;
  if (account != null) {
    pushNotification(account, {
      type: 'HOME_VISIT_UNREACHABLE',
      title: 'Bác sĩ chưa gặp được bạn tại địa chỉ khám tại nhà',
      body: `Bác sĩ đến khám cho ${petNameOf(updated.petId)} tại ${updated.homeAddress} nhưng chưa gặp được bạn. Lịch ${updated.code} được ghi nhận là không gặp được khách và tính vào số lần vi phạm đặt lịch online (BR-LH-09). Vui lòng liên hệ chi nhánh để đặt lại.`,
      linkUrl: null,
    });
  }
  return ok(view(updated), 200, 'Đã ghi nhận không gặp được khách');
};

export const homevisitRoutes: Record<string, MockHandler> = {
  'POST /api/home-visit-appointments': createHomeVisit,
  'GET /api/me/home-visit-appointments': listMine,
  'GET /api/home-visit-appointments': listHomeVisits,
  'GET /api/home-visit-appointments/:appointmentId': getHomeVisit,
  'POST /api/home-visit-appointments/:appointmentId/start': startHomeVisit,
  'POST /api/home-visit-appointments/:appointmentId/mark-unreachable': markUnreachable,
};
