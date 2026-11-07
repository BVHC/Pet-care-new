// Mock inpatient-v1 (UC55, UC56, BR-LT-13…17; docs/api/inpatient-v1.md #1–10; 09 §C; 03 #24, #25) — T57 bước A. BE tầng 3 CHƯA cài (T54 chỉ duyệt đặc tả), nên không có số đo thật:
// mọi chỗ dưới đây là chép hợp đồng (openapi/inpatient-v1.yaml: path, body, "Quyền:", mã lỗi) cộng ASSUMPTION khi hợp đồng im lặng.
//
// Thứ tự kiểm (00-method §3.5; chỗ yaml im lặng giữ thứ tự này cho cả 10 endpoint):
//   401 (adapter) → 400 path / query / body sai kiểu (MALFORMED_REQUEST) → 403 vai trò (ACCESS_DENIED) → 400 VALIDATION_FAILED → 404 → 403 phạm vi (ACCESS_DENIED_SCOPE_MISMATCH, message chung)
//   → 400 BR-LT-… → 409 INVALID_STATE_TRANSITION. Quyền theo "Quyền:" của yaml: BRANCH_MANAGER, SUPER_MANAGER, ADMIN không có endpoint nào ở đây.
//
// ASSUMPTION (đã cân nhắc, ghi để không bị tính là lệch BE):
//  A1  Phạm vi chi nhánh kiểm SAU 404 (chi nhánh chỉ biết sau khi nạp ca / lượt / đặt chỗ). "Visit không của mình" ở #1 là 400 BR-LT-13 (đúng chữ yaml), không phải 403.
//  A2  Mã rule theo mã ĐẦU của dòng lỗi trong yaml: #1 mọi vế là BR-LT-13, #4 là BR-LT-14, #5 / #7 / #8 là BR-LT-15, #9 là BR-LT-16. Thiếu tóm tắt (#4), thiếu ghi chú (#7), thiếu lý do (#8) là 400 BR-LT-*,
//      KHÔNG phải VALIDATION_FAILED; chỉ `outcome`, `serviceId`, `orderType`, `content`, `scheduledAt`, `abnormal` thiếu hoặc quá độ dài (`content` ≤ 500, `note` ≤ 300, `reason` ≤ 300, ghi chú theo dõi ≤ 500) là VALIDATION_FAILED.
//  A3  409 khi thú đã có ca ADMITTED: yaml không ghi mã, mock dùng INVALID_STATE_TRANSITION ("Thú cưng đã có ca nội trú đang điều trị"); kiểm SAU mọi 400 rule của #1 và TRƯỚC khi tạo Visit điều trị.
//  A4  Dịch vụ INPATIENT: catalog.mock chưa có loại này (09 Q1), dùng bộ mẫu ở clinical-shared.ts (id 7101–7104, giá theo ngày). Giá chốt vào `unitPrice` lúc nhập viện; xuất viện thêm dòng
//      "số ngày × giá đã chốt" (số ngày = ngày VN từ ngày nhập đến ngày xuất, tối thiểu 1) qua `addServiceLineToVisitOrder` (nguồn đăng ký, mặc định mã dòng giả, KHÔNG sửa Order thật: xem clinical-shared.ts).
//  A5  Chuồng/đặt chỗ lưu trú đọc từ boarding.mock qua chính route của nó (với vai trò hệ thống, như `BoardingQueryApi` / `KennelApi` không kiểm vai trò). boarding.mock không cho ghi chuồng từ ngoài, nên "chiếm chuồng"
//      (`occupyForTreatment`) được theo dõi ở ĐÂY: chuồng AVAILABLE của boarding mà đang giữ cho một ca ADMITTED khác thì coi như không còn trống. Chuồng cấp riêng cho nội trú = `kennelId` truyền vào khác chuồng đang ở;
//      xuất viện thì ca hết ADMITTED nên chuồng tự trống lại. Đặt chỗ lưu trú giữ nguyên CHECKED_IN (BR-LT-17).
//  A6  Nhập viện từ đặt chỗ (b) tạo Visit điều trị IN_PROGRESS gán VET gọi qua `startDirectVisit` (visit.mock), Order mở với dịch vụ khám EXAM đầu tiên đang bật ở chi nhánh (hợp đồng im lặng dịch vụ nào);
//      thú còn lượt mở khác → 400 BR-TN-01. Thú không có trong mock thú (khách lưu trú mẫu 9002, 9003 …) coi là chưa mất, không có lượt mở; cân nặng của theo dõi chỉ ghi vào hồ sơ cân khi thú có trong mock thú.
//  A7  DECEASED chỉ xuất được khi thú ĐÃ đánh dấu mất (400 BR-LT-14 nếu chưa). Nhưng mark-deceased (customer-v1) từ chối khi thú còn lượt mở (BR-KH-05) mà ca ADMITTED luôn có lượt mở: hợp đồng tự mâu thuẫn,
//      nên trong luồng thật DECEASED không tới được. Mock giữ đúng chữ hợp đồng (kiểm `deceasedOn` của mock thú), không gỡ mâu thuẫn.
//  A8  Chỉ số theo dõi: khoảng hợp lệ theo kiểu cột (OBSERVATION_BOUNDS) vì yaml chỉ ghi "ngoài khoảng". "Không có chỉ số hay ghi chú nào" = cả 4 số, tình trạng ăn và ghi chú đều vắng; `abnormal` một mình không đủ.
//      `abnormal = true` báo VET phụ trách và khách (có tài khoản) bằng tin CARE_LOG_ABNORMAL (mẫu của nhật ký lưu trú, BR-LT-12; chữ do mock đặt); người ghi là chính VET thì không tự báo mình.
//  A9  Y lệnh MEDICATION: `prescriptionItemId` phải là dòng đơn của đúng Visit của ca (đọc qua `prescriptionItemIdsOfVisit` của visit-clinical.mock); loại khác bỏ qua `prescriptionItemId` (lưu null).
//      Y lệnh không bị chặn theo `scheduledAt` (quá khứ hay tương lai đều được). CARETAKER thực hiện y lệnh MEDICATION → 403 ACCESS_DENIED (yaml), kiểm sau 404 / phạm vi.
//  A10 Dữ liệu mẫu KHÔNG có ca ADMITTED nào trên lượt thật (chỉ lịch sử DISCHARGED ở lượt 61 và 1 ca ADMITTED chi nhánh 2 trên lượt giả) để không chặn hoàn tất lượt 2002 của các test GĐ1 (guard BR-LT-14 đã đăng ký vào
//      visit-clinical.mock). `code` NT-yyMMdd-xxxx: bộ đếm theo ngày VN của lúc nhập viện.
import type { Role } from '../../types/auth';
import type { Booking, Kennel } from '../../types/boarding';
import type {
  Admission,
  AdmissionOrder,
  AdmissionOrderStatus,
  AdmissionOrderType,
  AdmissionStatus,
  AdmissionSummary,
  Appetite,
  DischargeOutcome,
  Observation,
} from '../../types/inpatient';
import { ADMISSION_ORDER_STATUSES, ADMISSION_ORDER_TYPES, ADMISSION_STATUSES, APPETITES, DISCHARGE_OUTCOMES, INPATIENT_LIMITS, OBSERVATION_BOUNDS } from '../../types/inpatient';
import { vnDate } from '../../utils/vn-time';
import { boardingRoutes } from './boarding.mock';
import { branchBooking } from './branch.mock';
import { catalogServices } from './catalog.mock';
import { pushNotification } from './care.mock';
import { addServiceLineToVisitOrder, findClinicalService } from './clinical-shared';
import { findCustomer } from './customer.mock';
import { petSummary, recordVisitWeight } from './customer-pet.mock';
import { denied, fail, invalid, notFound, ok, ruleFail, scopeDenied, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { MOCK_ACCOUNTS, branchIdOf } from './identity.mock';
import { staffNameOf } from './identity-admin.mock';
import { clampPaging, pageOf, typeError } from './public.mock';
import { asBoolean, asLong, asText, conflict, idParam, isJsonObject, isReply, malformed, startDirectVisit, visitNotFound, visitRowOf } from './visit.mock';
import { prescriptionItemIdsOfVisit, registerCompletionGuard } from './visit-clinical.mock';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

const VET_ONLY: readonly Role[] = ['VET'];
const VIEW_ROLES: readonly Role[] = ['RECEPTIONIST', 'VET', 'CARETAKER'];
const COMPLETE_ROLES: readonly Role[] = ['VET', 'CARETAKER'];
const OBSERVE_ROLES: readonly Role[] = ['VET', 'CARETAKER'];

// ─── Dữ liệu ───

interface AdmissionRow {
  admissionId: number;
  code: string;
  visitId: number;
  petId: number;
  /** Tên thú lúc nhập viện, cho thú ngoài mock thú (khách lưu trú mẫu). */
  petName: string | null;
  customerId: number;
  branchId: number;
  boardingBookingId: number | null;
  kennelId: number | null;
  kennelCode: string | null;
  /** Chuồng cấp riêng cho nội trú: giữ chuồng tới khi xuất viện (A5). */
  kennelDedicated: boolean;
  serviceId: number;
  unitPrice: number;
  status: AdmissionStatus;
  admittedBy: number;
  admittedAt: string;
  dischargeOutcome: DischargeOutcome | null;
  dischargeSummary: string | null;
  dischargedBy: number | null;
  dischargedAt: string | null;
  orderLineId: number | null;
}
interface OrderRow {
  admissionOrderId: number;
  admissionId: number;
  orderType: AdmissionOrderType;
  content: string;
  scheduledAt: string;
  status: AdmissionOrderStatus;
  prescriptionItemId: number | null;
  createdBy: number;
  doneBy: number | null;
  doneAt: string | null;
  doneNote: string | null;
  cancelReason: string | null;
}
interface ObservationRow {
  observationId: number;
  admissionId: number;
  recordedBy: number;
  temperatureC: number | null;
  heartRate: number | null;
  respiratoryRate: number | null;
  weightKg: number | null;
  appetite: Appetite | null;
  note: string | null;
  abnormal: boolean;
  createdAt: string;
}
interface State {
  /** Mã đã cấp gần nhất của từng bảng. */
  seq: { admission: number; order: number; observation: number };
  /** Bộ đếm mã `NT-yyMMdd-xxxx` theo ngày VN của lúc nhập viện. */
  counters: Record<string, number>;
  admissions: AdmissionRow[];
  orders: OrderRow[];
  observations: ObservationRow[];
}

const iso = (ms: number) => new Date(ms).toISOString();

function nextCode(counters: Record<string, number>, atMs: number): string {
  const day = vnDate(atMs).slice(2).replace(/-/g, '');
  counters[day] = (counters[day] ?? 0) + 1;
  return `NT-${day}-${String(counters[day]).padStart(4, '0')}`;
}

/** Lịch sử 1 ca DISCHARGED về nhà trên lượt 61 (Mochi, kèm y lệnh và theo dõi) và 1 ca ADMITTED ở chi nhánh 2 trên lượt giả (để thử 403 phạm vi). Xem A10. */
function seed(now = Date.now()): State {
  const s: State = { seq: { admission: 0, order: 0, observation: 0 }, counters: {}, admissions: [], orders: [], observations: [] };
  const order = (admissionId: number, o: Pick<OrderRow, 'orderType' | 'content' | 'scheduledAt' | 'status' | 'createdBy'> & Partial<OrderRow>) =>
    s.orders.push({ ...ORDER_DEFAULTS, admissionOrderId: ++s.seq.order, admissionId, ...o });
  const observation = (admissionId: number, o: Pick<ObservationRow, 'recordedBy' | 'createdAt'> & Partial<ObservationRow>) =>
    s.observations.push({ ...OBSERVATION_DEFAULTS, observationId: ++s.seq.observation, admissionId, ...o });

  const visit = visitRowOf(61);
  if (visit) {
    const t = Date.parse(visit.checkedInAt);
    const at = (minutes: number) => iso(t + minutes * MINUTE);
    s.admissions.push({
      ...ADMISSION_DEFAULTS,
      admissionId: ++s.seq.admission,
      code: nextCode(s.counters, t + 2 * MINUTE),
      visitId: 61,
      petId: visit.petId,
      customerId: visit.customerId,
      branchId: visit.branchId,
      kennelId: 3,
      kennelCode: 'C-03',
      kennelDedicated: true,
      serviceId: 7101,
      unitPrice: 350_000,
      status: 'DISCHARGED',
      admittedBy: 5,
      admittedAt: at(2),
      dischargeOutcome: 'HOME',
      dischargeSummary: 'Điều trị viêm đường hô hấp: truyền dịch, kháng sinh, theo dõi nhiệt độ. Thú tỉnh táo, ăn uống bình thường, về nhà theo dõi và tái khám sau 7 ngày.',
      dischargedBy: 5,
      dischargedAt: at(16),
      orderLineId: 5002,
    });
    order(1, { orderType: 'FEEDING', content: 'Cho ăn pate mềm 3 bữa/ngày', scheduledAt: at(4), status: 'DONE', createdBy: 5, doneBy: 6, doneAt: at(6), doneNote: 'Thú ăn hết 2/3 khẩu phần' });
    order(1, { orderType: 'PROCEDURE', content: 'Truyền dịch Ringer lactat 100 ml trong 30 phút', scheduledAt: at(5), status: 'DONE', createdBy: 5, doneBy: 5, doneAt: at(8), doneNote: 'Truyền xong, không phản ứng phụ' });
    order(1, { orderType: 'MONITORING', content: 'Đo nhiệt độ mỗi 4 giờ', scheduledAt: at(8), status: 'CANCELLED', createdBy: 5, cancelReason: 'Thú ổn định, giảm tần suất theo dõi' });
    const item = prescriptionItemIdsOfVisit(61)[0];
    if (item !== undefined) {
      order(1, { orderType: 'MEDICATION', content: 'Amoxicillin 250mg: 1 viên, ngày 2 lần sau ăn', scheduledAt: at(6), status: 'DONE', createdBy: 5, prescriptionItemId: item, doneBy: 5, doneAt: at(7), doneNote: 'Đã cho uống liều đầu' });
    }
    observation(1, { recordedBy: 6, createdAt: at(5), temperatureC: 39.4, heartRate: 168, respiratoryRate: 40, appetite: 'REDUCED', note: 'Thú lờ đờ, thở nhanh', abnormal: true });
    observation(1, { recordedBy: 6, createdAt: at(9), temperatureC: 38.9, appetite: 'REDUCED' });
    observation(1, { recordedBy: 5, createdAt: at(14), temperatureC: 38.5, heartRate: 150, respiratoryRate: 32, weightKg: 4.2, appetite: 'NORMAL', note: 'Thú tỉnh táo, đã ăn' });
  }
  const t = now - 3 * 60 * MINUTE;
  s.admissions.push({
    ...ADMISSION_DEFAULTS,
    admissionId: ++s.seq.admission,
    code: nextCode(s.counters, t),
    visitId: 9103,
    petId: 9005,
    petName: 'Tép',
    customerId: 9005,
    branchId: 2,
    serviceId: 7103,
    unitPrice: 300_000,
    status: 'ADMITTED',
    admittedBy: 9,
    admittedAt: iso(t),
  });
  const branch2 = s.admissions[s.admissions.length - 1].admissionId;
  order(branch2, { orderType: 'FEEDING', content: 'Cho ăn thức ăn mềm, chia 4 bữa', scheduledAt: iso(now + 60 * MINUTE), status: 'ORDERED', createdBy: 9 });
  observation(branch2, { recordedBy: 9, createdAt: iso(t + 30 * MINUTE), temperatureC: 38.7, appetite: 'NORMAL' });
  return s;
}

const STATE_KEY = 'petcare-mock-inpatient';

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

/** Giá trị mặc định khi nạp state lưu thiếu trường (T47: state cũ không được làm route mới nổ). */
const ADMISSION_DEFAULTS = { petName: null, boardingBookingId: null, kennelId: null, kennelCode: null, kennelDedicated: false, dischargeOutcome: null, dischargeSummary: null, dischargedBy: null, dischargedAt: null, orderLineId: null } as const;
const ORDER_DEFAULTS = { prescriptionItemId: null, doneBy: null, doneAt: null, doneNote: null, cancelReason: null } as const;
const OBSERVATION_DEFAULTS = { temperatureC: null, heartRate: null, respiratoryRate: null, weightKg: null, appetite: null, note: null, abnormal: false } as const;

const max = (rows: number[]) => rows.reduce((m, n) => Math.max(m, n), 0);

/** Nạp state; thiếu bảng / thiếu trường thì gộp giá trị mặc định (và ghi lại ngay, vì dữ liệu mẫu tính theo lúc tạo). */
function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    const stored = raw ? (JSON.parse(raw) as Partial<State> | null) : null;
    if (stored && typeof stored === 'object') {
      const fresh = seed();
      const merged: State = {
        counters: stored.counters ?? {},
        admissions: (stored.admissions ?? fresh.admissions).map((a) => ({ ...ADMISSION_DEFAULTS, ...a })),
        orders: (stored.orders ?? fresh.orders).map((o) => ({ ...ORDER_DEFAULTS, ...o })),
        observations: (stored.observations ?? fresh.observations).map((o) => ({ ...OBSERVATION_DEFAULTS, ...o })),
        seq: { admission: 0, order: 0, observation: 0 },
      };
      merged.seq = {
        admission: stored.seq?.admission ?? max(merged.admissions.map((a) => a.admissionId)),
        order: stored.seq?.order ?? max(merged.orders.map((o) => o.admissionOrderId)),
        observation: stored.seq?.observation ?? max(merged.observations.map((o) => o.observationId)),
      };
      if (!stored.admissions || !stored.orders || !stored.observations || !stored.seq || !stored.counters) save(merged);
      return merged;
    }
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const fresh = seed();
  save(fresh);
  return fresh;
}

// ─── Hiển thị ───

const petNameOf = (a: AdmissionRow) => petSummary(a.petId)?.name ?? a.petName ?? `Thú cưng #${a.petId}`;

const view = (a: AdmissionRow): Admission => ({
  admissionId: a.admissionId,
  code: a.code,
  visitId: a.visitId,
  petId: a.petId,
  petName: petNameOf(a),
  branchId: a.branchId,
  boardingBookingId: a.boardingBookingId,
  kennelId: a.kennelId,
  serviceId: a.serviceId,
  serviceName: findClinicalService(a.serviceId)?.name ?? `Dịch vụ #${a.serviceId}`,
  unitPrice: a.unitPrice,
  status: a.status,
  admittedAt: a.admittedAt,
  dischargeOutcome: a.dischargeOutcome,
  dischargeSummary: a.dischargeSummary,
  dischargedAt: a.dischargedAt,
  orderLineId: a.orderLineId,
});

const summary = (a: AdmissionRow): AdmissionSummary => ({ admissionId: a.admissionId, code: a.code, petName: petNameOf(a), status: a.status, admittedAt: a.admittedAt, kennelCode: a.kennelCode });

const orderView = (o: OrderRow): AdmissionOrder => ({
  admissionOrderId: o.admissionOrderId,
  admissionId: o.admissionId,
  orderType: o.orderType,
  content: o.content,
  scheduledAt: o.scheduledAt,
  status: o.status,
  prescriptionItemId: o.prescriptionItemId,
  doneAt: o.doneAt,
  doneNote: o.doneNote,
  cancelReason: o.cancelReason,
});

const staffName = (accountId: number) => staffNameOf(accountId) ?? MOCK_ACCOUNTS.find((a) => a.id === accountId)?.fullName ?? `Nhân viên #${accountId}`;

const observationView = (o: ObservationRow): Observation => ({
  observationId: o.observationId,
  admissionId: o.admissionId,
  recordedByName: staffName(o.recordedBy),
  temperatureC: o.temperatureC,
  heartRate: o.heartRate,
  respiratoryRate: o.respiratoryRate,
  weightKg: o.weightKg,
  appetite: o.appetite,
  note: o.note,
  abnormal: o.abnormal,
  createdAt: o.createdAt,
});

// ─── Dùng chung ───

const me = (req: MockRequest) => req.account!;
const branchOf = (req: MockRequest) => branchIdOf(me(req).id) ?? null;
const allow = (roles: readonly Role[], req: MockRequest) => roles.includes(me(req).role);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const NAME = 'ca nội trú';

/** Ca theo mã (404) rồi chi nhánh của người gọi (403 phạm vi). */
function admissionFor(req: MockRequest, s: State, admissionId: number): AdmissionRow | MockReply {
  const row = s.admissions.find((x) => x.admissionId === admissionId);
  if (!row) return notFound(NAME, admissionId);
  return row.branchId === branchOf(req) ? row : scopeDenied();
}
const isAdmission = (x: AdmissionRow | MockReply): x is AdmissionRow => 'admissionId' in x;

/** Y lệnh theo mã (404) rồi chi nhánh của ca của nó (403). */
function orderFor(req: MockRequest, s: State, orderId: number): { order: OrderRow; admission: AdmissionRow } | MockReply {
  const order = s.orders.find((x) => x.admissionOrderId === orderId);
  if (!order) return notFound('y lệnh nội trú', orderId);
  const admission = s.admissions.find((x) => x.admissionId === order.admissionId);
  return admission && admission.branchId === branchOf(req) ? { order, admission } : scopeDenied();
}

/** Đọc boarding.mock qua route của nó, như `BoardingQueryApi` / `KennelApi` (không kiểm vai trò): đóng vai quản lý chi nhánh của người gọi (A5). */
const asSystem = (req: MockRequest, params: Record<string, string> = {}): MockRequest => ({ body: null, query: {}, params, account: { ...me(req), role: 'BRANCH_MANAGER' } });
const bookingOf = (req: MockRequest, bookingId: number): Booking | MockReply => {
  const reply = boardingRoutes['GET /api/boarding-bookings/:bookingId'](asSystem(req, { bookingId: String(bookingId) }));
  return reply.status >= 400 ? reply : (reply.body as { data: Booking }).data;
};
const kennelsOf = (req: MockRequest): Kennel[] => {
  const reply = boardingRoutes['GET /api/kennels'](asSystem(req));
  return reply.status >= 400 ? [] : (reply.body as { data: Kennel[] }).data;
};
/** Chuồng đang giữ cho một ca ADMITTED khác (A5). */
const heldKennels = (s: State) => new Set(s.admissions.filter((a) => a.status === 'ADMITTED' && a.kennelDedicated && a.kennelId !== null).map((a) => a.kennelId));

/** Dịch vụ khám mặc định để mở Order của Visit điều trị (A6). */
const treatmentService = (branchId: number) => catalogServices().find((x) => x.medicalType === 'EXAM' && x.isActive && branchBooking(branchId)?.isServiceEnabled(x.serviceId));

const blank = (v: string | null) => v === null || v.trim() === '';
const tooLong = (v: string | null, n: number) => v !== null && v.length > n;
const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const enumOf = <T extends string>(v: string | null | undefined, values: readonly T[]): T | null | undefined => (v === null || v === undefined ? null : (values as readonly string[]).includes(v) ? (v as T) : undefined);

// ─── #1 Nhập viện ───

/**
 * #1 (BR-LT-13, 17): body → VET → thiếu dịch vụ / mã ≤ 0 400 → không đúng một trong visitId / boardingBookingId 400 → Visit hoặc đặt chỗ 404 / chi nhánh 403 → dịch vụ 404 → chuồng 404
 * → 400 BR-LT-13 (Visit không IN_PROGRESS / không của mình, đặt chỗ không CHECKED_IN, thú đã mất, dịch vụ không INPATIENT / ngừng / chưa bật, chuồng không AVAILABLE) → 409 thú đã ADMITTED
 * → (b) Visit điều trị: 400 BR-TN-01 thú còn lượt mở.
 */
const admit: MockHandler = (req) => {
  if (!isJsonObject(req.body)) return malformed();
  const visitId = asLong(req.body.visitId);
  const bookingId = asLong(req.body.boardingBookingId);
  const serviceId = asLong(req.body.serviceId);
  const kennelId = asLong(req.body.kennelId);
  if ([visitId, bookingId, serviceId, kennelId].includes(undefined)) return malformed();
  if (!allow(VET_ONLY, req)) return denied();
  const positive = (name: string, v: number | null | undefined): [string, string, boolean] => [name, `Mã không hợp lệ`, v != null && v <= 0];
  const bad = invalid([['serviceId', 'Thiếu dịch vụ nội trú', serviceId === null], positive('serviceId', serviceId), positive('visitId', visitId), positive('boardingBookingId', bookingId), positive('kennelId', kennelId)]);
  if (bad) return bad;
  if ((visitId === null) === (bookingId === null)) return ruleFail('BR-LT-13', 'Phải chọn đúng một trong lượt khám đang khám hoặc đặt chỗ lưu trú đang CHECKED_IN');

  const s = load();
  const branchId = branchOf(req)!;
  let petId: number;
  let customerId: number;
  let petName: string | null = null;
  let booking: Booking | null = null;
  if (visitId !== null) {
    const visit = visitRowOf(visitId as number);
    if (!visit) return visitNotFound(visitId as number);
    if (visit.branchId !== branchId) return scopeDenied();
    ({ petId, customerId } = visit);
  } else {
    const found = bookingOf(req, bookingId as number);
    if (isReply(found)) return found;
    booking = found;
    ({ petId, customerId, petName } = found);
  }
  const service = findClinicalService(serviceId as number);
  if (!service) return notFound('dịch vụ', serviceId as number);
  const kennel = kennelId === null ? undefined : kennelsOf(req).find((k) => k.kennelId === kennelId);
  if (kennelId !== null && !kennel) return notFound('chuồng', kennelId as number);

  if (visitId !== null) {
    const visit = visitRowOf(visitId as number)!;
    if (visit.status !== 'IN_PROGRESS') return ruleFail('BR-LT-13', 'Chỉ nhập viện từ lượt đang khám');
    if (visit.assigneeId !== me(req).id) return ruleFail('BR-LT-13', 'Chỉ nhập viện từ lượt khám do chính bạn phụ trách');
  } else if (booking!.status !== 'CHECKED_IN') {
    return ruleFail('BR-LT-13', 'Chỉ chuyển sang điều trị từ đặt chỗ lưu trú đang CHECKED_IN');
  }
  if (petSummary(petId)?.deceasedOn) return ruleFail('BR-LT-13', 'Thú cưng đã mất, không nhập viện được');
  if (service.medicalType !== 'INPATIENT') return ruleFail('BR-LT-13', 'Dịch vụ không phải dịch vụ nội trú');
  if (!service.isActive || !service.enabledAt(branchId)) return ruleFail('BR-LT-13', 'Dịch vụ nội trú đã ngừng hoặc chưa bật tại chi nhánh');
  // Chuồng đang ở của chính đặt chỗ (OCCUPIED trong boarding) vẫn dùng được; chuồng khác phải AVAILABLE và chưa giữ cho ca khác (A5).
  const ownKennel = !!kennel && kennel.kennelId === booking?.kennelId;
  if (kennel && !ownKennel && (kennel.status !== 'AVAILABLE' || heldKennels(s).has(kennel.kennelId))) return ruleFail('BR-LT-13', `Chuồng ${kennel.code} không còn trống`);
  if (s.admissions.some((a) => a.petId === petId && a.status === 'ADMITTED')) return fail('INVALID_STATE_TRANSITION', 'Thú cưng đã có ca nội trú đang điều trị', 409);

  const now = Date.now();
  let admissionVisitId = visitId as number;
  if (booking) {
    const exam = treatmentService(branchId);
    if (!exam) return fail('INTERNAL_ERROR', 'Chi nhánh chưa bật dịch vụ khám để mở Order cho lượt điều trị', 500);
    const started = startDirectVisit({ branchId, customerId, petId, serviceId: exam.serviceId, vetId: me(req).id, appointmentId: null, isHomeVisit: false, now });
    if (!started.ok) {
      return started.reason === 'PET_BUSY' ? ruleFail('BR-TN-01', 'Thú cưng đang có lượt khám chưa kết thúc') : fail('INTERNAL_ERROR', 'Không mở được Order cho lượt điều trị', 500);
    }
    admissionVisitId = started.visit.visitId;
  }
  const row: AdmissionRow = {
    admissionId: s.seq.admission + 1,
    code: nextCode(s.counters, now),
    visitId: admissionVisitId,
    petId,
    petName,
    customerId,
    branchId,
    boardingBookingId: booking ? booking.bookingId : null,
    // Vắng kennelId → dùng chuồng đang ở của đặt chỗ (không cấp riêng); truyền đúng chuồng đang ở cũng không phải cấp riêng.
    kennelId: kennel ? kennel.kennelId : (booking?.kennelId ?? null),
    kennelCode: kennel ? kennel.code : (booking?.kennelCode ?? null),
    kennelDedicated: !!kennel && !ownKennel,
    serviceId: service.serviceId,
    unitPrice: service.price,
    status: 'ADMITTED',
    admittedBy: me(req).id,
    admittedAt: iso(now),
    dischargeOutcome: null,
    dischargeSummary: null,
    dischargedBy: null,
    dischargedAt: null,
    orderLineId: null,
  };
  s.seq.admission = row.admissionId;
  s.admissions.push(row);
  save(s);
  return ok(view(row), 201, 'Đã nhập viện');
};

// ─── #2, #3 Xem ca ───

/** #2: lễ tân, VET, CARETAKER; query sai kiểu → vai trò. `status` vắng = ADMITTED; nhập viện sớm nhất trước; phân trang. */
const listAdmissions: MockHandler = (req) => {
  const { status } = req.query;
  if (status !== undefined && !(ADMISSION_STATUSES as readonly string[]).includes(status)) return badParam('status');
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  if (!allow(VIEW_ROLES, req)) return denied();
  const found = load()
    .admissions.filter((a) => a.branchId === branchOf(req) && a.status === (status ?? 'ADMITTED'))
    .sort((a, b) => Date.parse(a.admittedAt) - Date.parse(b.admittedAt) || a.admissionId - b.admissionId);
  return ok(pageOf(found.map(summary), clampPaging(req.query)));
};

/** #3: path → vai trò → 404 → chi nhánh 403. */
const getAdmission: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  if (!allow(VIEW_ROLES, req)) return denied();
  const row = admissionFor(req, load(), admissionId);
  return isAdmission(row) ? ok(view(row)) : row;
};

// ─── #4 Xuất viện ───

const daysBetween = (fromMs: number, toMs: number) => Math.round((Date.parse(vnDate(toMs)) - Date.parse(vnDate(fromMs))) / DAY);

/**
 * #4 (BR-LT-14): path / body sai kiểu → VET → thiếu `outcome` 400 → 404 → chi nhánh 403 → 400 BR-LT-14 (thiếu tóm tắt, còn y lệnh ORDERED, DECEASED mà thú chưa đánh dấu mất) → 409 không ADMITTED.
 * Thêm dòng dịch vụ nội trú (số ngày × giá đã chốt) vào Order của Visit (A4).
 */
const discharge: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  if (!isJsonObject(req.body)) return malformed();
  const outcome = enumOf(asText(req.body.outcome), DISCHARGE_OUTCOMES);
  const summaryText = asText(req.body.summary);
  if (outcome === undefined || summaryText === undefined) return malformed();
  if (!allow(VET_ONLY, req)) return denied();
  const bad = invalid([['outcome', 'Thiếu kết cục xuất viện', outcome === null]]);
  if (bad) return bad;

  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  if (blank(summaryText)) return ruleFail('BR-LT-14', 'Phải nhập tóm tắt điều trị trước khi xuất viện');
  const pending = s.orders.filter((o) => o.admissionId === row.admissionId && o.status === 'ORDERED').length;
  if (pending > 0) return ruleFail('BR-LT-14', `Còn ${pending} y lệnh chưa thực hiện hoặc chưa hủy`);
  if (outcome === 'DECEASED' && !petSummary(row.petId)?.deceasedOn) return ruleFail('BR-LT-14', 'Kết cục thú đã mất yêu cầu đánh dấu thú đã mất trước (BR-KH-05)');
  if (row.status !== 'ADMITTED') return conflict(NAME, row.status, 'DISCHARGED');

  const now = Date.now();
  const days = Math.max(1, daysBetween(Date.parse(row.admittedAt), now));
  const lineId = addServiceLineToVisitOrder(row.visitId, row.serviceId, days, me(req).id, row.unitPrice);
  if (lineId === null) return fail('INTERNAL_ERROR', `Lượt #${row.visitId} không có Order đang mở`, 500);
  Object.assign(row, { status: 'DISCHARGED', dischargeOutcome: outcome, dischargeSummary: summaryText!.trim(), dischargedBy: me(req).id, dischargedAt: iso(now), orderLineId: lineId });
  save(s);
  return ok(view(row), 200, 'Đã xuất viện');
};

// ─── #5, #6 Y lệnh ───

/**
 * #5 (BR-LT-15): path / body sai kiểu → VET → thiếu loại / nội dung / giờ, nội dung > 500 400 → 404 → chi nhánh 403 → 400 BR-LT-15 (MEDICATION thiếu dòng đơn, dòng đơn không thuộc Visit) → 409 ca không ADMITTED.
 */
const createOrder: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  if (!isJsonObject(req.body)) return malformed();
  const orderType = enumOf(asText(req.body.orderType), ADMISSION_ORDER_TYPES);
  const content = asText(req.body.content);
  const scheduledAt = asText(req.body.scheduledAt);
  const itemId = asLong(req.body.prescriptionItemId);
  if (orderType === undefined || content === undefined || scheduledAt === undefined || itemId === undefined) return malformed();
  if (scheduledAt !== null && (!DATE_TIME.test(scheduledAt) || Number.isNaN(Date.parse(scheduledAt)))) return malformed();
  if (!allow(VET_ONLY, req)) return denied();
  const bad = invalid([
    ['orderType', 'Thiếu loại y lệnh', orderType === null],
    ['content', 'Nội dung y lệnh không được để trống', blank(content)],
    ['content', `Nội dung y lệnh tối đa ${INPATIENT_LIMITS.content} ký tự`, tooLong(content, INPATIENT_LIMITS.content)],
    ['scheduledAt', 'Thiếu giờ thực hiện dự kiến', scheduledAt === null],
    ['prescriptionItemId', 'Mã dòng đơn không hợp lệ', itemId !== null && itemId <= 0],
  ]);
  if (bad) return bad;

  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  if (orderType === 'MEDICATION') {
    if (itemId === null) return ruleFail('BR-LT-15', 'Y lệnh thuốc phải liên kết một dòng đơn thuốc của lượt khám');
    if (!prescriptionItemIdsOfVisit(row.visitId).includes(itemId)) return ruleFail('BR-LT-15', 'Dòng đơn thuốc không thuộc lượt khám của ca nội trú');
  }
  if (row.status !== 'ADMITTED') return fail('INVALID_STATE_TRANSITION', `Không thể ra y lệnh cho ca nội trú ở trạng thái ${row.status}`, 409);
  const created: OrderRow = {
    admissionOrderId: s.seq.order + 1,
    admissionId: row.admissionId,
    orderType: orderType as AdmissionOrderType,
    content: content!.trim(),
    scheduledAt: iso(Date.parse(scheduledAt as string)),
    status: 'ORDERED',
    prescriptionItemId: orderType === 'MEDICATION' ? itemId : null,
    createdBy: me(req).id,
    doneBy: null,
    doneAt: null,
    doneNote: null,
    cancelReason: null,
  };
  s.seq.order = created.admissionOrderId;
  s.orders.push(created);
  save(s);
  return ok(orderView(created), 201, 'Đã ra y lệnh');
};

/** #6: lễ tân, VET, CARETAKER; path / query sai kiểu → vai trò → 404 → chi nhánh 403. Mảng, không phân trang; `scheduledAt` tăng dần, trùng thì mã tăng dần. */
const listOrders: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  const { status } = req.query;
  if (status !== undefined && !(ADMISSION_ORDER_STATUSES as readonly string[]).includes(status)) return badParam('status');
  if (!allow(VIEW_ROLES, req)) return denied();
  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  return ok(
    s.orders
      .filter((o) => o.admissionId === admissionId && (status === undefined || o.status === status))
      .sort((a, b) => Date.parse(a.scheduledAt) - Date.parse(b.scheduledAt) || a.admissionOrderId - b.admissionOrderId)
      .map(orderView),
  );
};

/** #7 (BR-LT-15): path / body sai kiểu → VET, CARETAKER → ghi chú > 300 400 → y lệnh 404 → chi nhánh 403 → CARETAKER với y lệnh thuốc 403 ACCESS_DENIED → thiếu ghi chú 400 → không ORDERED 409. */
const completeOrder: MockHandler = (req) => {
  const orderId = idParam(req, 'admissionOrderId');
  if (isReply(orderId)) return orderId;
  if (!isJsonObject(req.body)) return malformed();
  const note = asText(req.body.note);
  if (note === undefined) return malformed();
  if (!allow(COMPLETE_ROLES, req)) return denied();
  const bad = invalid([['note', `Ghi chú tối đa ${INPATIENT_LIMITS.doneNote} ký tự`, tooLong(note, INPATIENT_LIMITS.doneNote)]]);
  if (bad) return bad;
  const s = load();
  const found = orderFor(req, s, orderId);
  if (!('order' in found)) return found;
  const { order } = found;
  if (me(req).role === 'CARETAKER' && order.orderType === 'MEDICATION') return denied();
  if (blank(note)) return ruleFail('BR-LT-15', 'Phải ghi chú khi đánh dấu đã thực hiện y lệnh');
  if (order.status !== 'ORDERED') return conflict('y lệnh nội trú', order.status, 'DONE');
  Object.assign(order, { status: 'DONE', doneBy: me(req).id, doneAt: iso(Date.now()), doneNote: note!.trim() });
  save(s);
  return ok(orderView(order), 200, 'Đã thực hiện y lệnh');
};

/** #8 (BR-LT-15): path / body sai kiểu → VET → lý do > 300 400 → 404 → chi nhánh 403 → thiếu lý do 400 → không ORDERED 409. */
const cancelOrder: MockHandler = (req) => {
  const orderId = idParam(req, 'admissionOrderId');
  if (isReply(orderId)) return orderId;
  if (!isJsonObject(req.body)) return malformed();
  const reason = asText(req.body.reason);
  if (reason === undefined) return malformed();
  if (!allow(VET_ONLY, req)) return denied();
  const bad = invalid([['reason', `Lý do tối đa ${INPATIENT_LIMITS.reason} ký tự`, tooLong(reason, INPATIENT_LIMITS.reason)]]);
  if (bad) return bad;
  const s = load();
  const found = orderFor(req, s, orderId);
  if (!('order' in found)) return found;
  const { order } = found;
  if (blank(reason)) return ruleFail('BR-LT-15', 'Phải nhập lý do hủy y lệnh');
  if (order.status !== 'ORDERED') return conflict('y lệnh nội trú', order.status, 'CANCELLED');
  Object.assign(order, { status: 'CANCELLED', cancelReason: reason!.trim() });
  save(s);
  return ok(orderView(order), 200, 'Đã hủy y lệnh');
};

// ─── #9, #10 Theo dõi ───

const asNumber = (v: unknown): number | null | undefined => (v === undefined || v === null ? null : typeof v === 'number' && Number.isFinite(v) ? v : undefined);
const outOfRange = (v: number | null, bounds: { min: number; max: number }) => v !== null && (v < bounds.min || v > bounds.max);

/** CARE_LOG_ABNORMAL (A8): báo VET phụ trách (trừ chính người ghi) và khách có tài khoản. */
function notifyAbnormal(row: AdmissionRow, recordedBy: number) {
  const pet = petNameOf(row);
  const notice = {
    type: 'CARE_LOG_ABNORMAL',
    title: `Theo dõi nội trú bất thường: ${pet}`,
    body: `${staffName(recordedBy)} vừa ghi một chỉ số theo dõi bất thường của ${pet} (ca ${row.code}). Vui lòng liên hệ chủ thú và báo bác sĩ phụ trách nếu cần.`,
    linkUrl: null,
  };
  const vet = visitRowOf(row.visitId)?.assigneeId ?? row.admittedBy;
  if (vet !== recordedBy) pushNotification(vet, notice);
  const account = findCustomer(row.customerId)?.accountId;
  if (account != null) pushNotification(account, notice);
}

/**
 * #9 (BR-LT-16): path / body sai kiểu → VET, CARETAKER → thiếu `abnormal`, ghi chú > 500 400 → 404 → chi nhánh 403 → 400 BR-LT-16 (không có chỉ số hay ghi chú nào, giá trị ngoài khoảng) → 409 ca không ADMITTED.
 * Cân nặng ghi vào hồ sơ cân của thú (BR-KH-04); `abnormal` báo VET và khách (A8).
 */
const createObservation: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  if (!isJsonObject(req.body)) return malformed();
  const b = req.body;
  const temperatureC = asNumber(b.temperatureC);
  const heartRate = asNumber(b.heartRate);
  const respiratoryRate = asNumber(b.respiratoryRate);
  const weightKg = asNumber(b.weightKg);
  const appetite = enumOf(asText(b.appetite), APPETITES);
  const note = asText(b.note);
  const abnormal = asBoolean(b.abnormal);
  if ([temperatureC, heartRate, respiratoryRate, weightKg, appetite, note, abnormal].includes(undefined)) return malformed();
  if ((heartRate != null && !Number.isInteger(heartRate)) || (respiratoryRate != null && !Number.isInteger(respiratoryRate))) return malformed();
  if (!allow(OBSERVE_ROLES, req)) return denied();
  const bad = invalid([
    ['abnormal', 'Thiếu cờ bất thường', abnormal === null],
    ['note', `Ghi chú tối đa ${INPATIENT_LIMITS.observationNote} ký tự`, tooLong(note as string | null, INPATIENT_LIMITS.observationNote)],
  ]);
  if (bad) return bad;

  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  const values = [temperatureC, heartRate, respiratoryRate, weightKg, appetite];
  if (values.every((v) => v === null) && blank(note as string | null)) return ruleFail('BR-LT-16', 'Phải ghi ít nhất một chỉ số theo dõi hoặc ghi chú');
  if (
    outOfRange(temperatureC as number | null, OBSERVATION_BOUNDS.temperatureC) ||
    outOfRange(heartRate as number | null, OBSERVATION_BOUNDS.heartRate) ||
    outOfRange(respiratoryRate as number | null, OBSERVATION_BOUNDS.respiratoryRate) ||
    outOfRange(weightKg as number | null, OBSERVATION_BOUNDS.weightKg)
  ) {
    return ruleFail('BR-LT-16', 'Chỉ số theo dõi nằm ngoài khoảng cho phép');
  }
  if (row.status !== 'ADMITTED') return fail('INVALID_STATE_TRANSITION', `Không thể ghi theo dõi cho ca nội trú ở trạng thái ${row.status}`, 409);

  const now = Date.now();
  if (weightKg !== null && petSummary(row.petId)) {
    const recorded = recordVisitWeight(row.petId, weightKg as number, row.visitId, iso(now));
    if (isReply(recorded)) return recorded;
  }
  const created: ObservationRow = {
    observationId: s.seq.observation + 1,
    admissionId: row.admissionId,
    recordedBy: me(req).id,
    temperatureC: temperatureC as number | null,
    heartRate: heartRate as number | null,
    respiratoryRate: respiratoryRate as number | null,
    weightKg: weightKg as number | null,
    appetite: appetite as Appetite | null,
    note: (note as string | null)?.trim() || null,
    abnormal: abnormal === true,
    createdAt: iso(now),
  };
  s.seq.observation = created.observationId;
  s.observations.push(created);
  save(s);
  if (created.abnormal) notifyAbnormal(row, created.recordedBy);
  return ok(observationView(created), 201, 'Đã ghi chỉ số theo dõi');
};

/** #10: lễ tân, VET, CARETAKER; path / query sai kiểu → vai trò → 404 → chi nhánh 403. Mới nhất trước (trùng giờ thì mã lớn trước), phân trang. */
const listObservations: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  if (!allow(VIEW_ROLES, req)) return denied();
  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  const found = s.observations
    .filter((o) => o.admissionId === admissionId)
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.observationId - a.observationId);
  return ok(pageOf(found.map(observationView), clampPaging(req.query)));
};

// ─── Cho mock khác ───

/** `AdmissionQueryApi.hasActiveAdmission(petId)` (BR-LT-17): guard "không trả thú lưu trú khi còn ca ADMITTED" của boarding (luồng chính nối vào hand-over, 409 BR-LT-17). */
export const hasActiveAdmission = (petId: number): boolean => load().admissions.some((a) => a.petId === petId && a.status === 'ADMITTED');

registerCompletionGuard('inpatient', (visitId) =>
  load().admissions.some((a) => a.visitId === visitId && a.status === 'ADMITTED')
    ? ruleFail('BR-LT-14', 'Lượt còn ca nội trú chưa xuất viện, chưa hoàn tất được')
    : null,
);

export const inpatientRoutes: Record<string, MockHandler> = {
  'POST /api/admissions': admit,
  'GET /api/admissions': listAdmissions,
  'GET /api/admissions/:admissionId': getAdmission,
  'POST /api/admissions/:admissionId/discharge': discharge,
  'POST /api/admissions/:admissionId/orders': createOrder,
  'GET /api/admissions/:admissionId/orders': listOrders,
  'POST /api/admission-orders/:admissionOrderId/complete': completeOrder,
  'POST /api/admission-orders/:admissionOrderId/cancel': cancelOrder,
  'POST /api/admissions/:admissionId/observations': createObservation,
  'GET /api/admissions/:admissionId/observations': listObservations,
};
