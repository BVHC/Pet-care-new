// Mock inpatient-v1 (UC55, UC56, BR-LT-13…17; docs/api/inpatient-v1.md #1–10; 09 §C; 03 #24, #25). T79 (25/11/2026): KHỚP BE THẬT. BE nội trú đã cài (T69, PR #34) và đã đo trên BE 8083 (bản sao petcare_t79)
// bằng `D:/Do-an/.c2-tmp/t79-m1…m5.mjs`; bảng số đo ở docs/testcases/T57-fe.md mục NT. Chỗ mock từng chép hợp đồng cũ mà BE khác thì đã đổi theo BE.
//
// Thứ tự kiểm (đo, mọi endpoint ghi): 401 (adapter) → 400 path / query / body sai kiểu (MALFORMED_REQUEST) → 400 VALIDATION_FAILED (Bean Validation chạy TRƯỚC @PreAuthorize) → 403 vai trò (ACCESS_DENIED)
//   → 404 → 403 chi nhánh (ACCESS_DENIED_SCOPE_MISMATCH) → 403 người phụ trách Visit (cùng mã; #4, #5, #8) → 409 INVALID_STATE_TRANSITION → 400 BR-LT-… (rỗng sau cắt, điều kiện).
//   Ngoại lệ: #1 kiểm nguồn / chuồng BR-LT-13 ngay sau vai trò và 409 đứng trước mọi 400 điều kiện; #4 tóm tắt rỗng (400 BR-LT-14) đứng ngay sau vai trò, trước 404.
//   Quyền: BRANCH_MANAGER, SUPER_MANAGER, ADMIN không có endpoint nào ở đây (ADR-0148).
//
// ASSUMPTION còn lại (mock vẫn lệch BE, ghi để không bị tính là lỗi):
//  A4  Dịch vụ INPATIENT: catalog.mock chưa có loại này (09 Q1), dùng bộ mẫu ở clinical-shared.ts (id 7101–7104, giá theo ngày). Giá chốt vào `unitPrice` lúc nhập viện; xuất viện thêm dòng
//      "số ngày × giá đã chốt" (số ngày = ngày VN từ ngày nhập đến ngày xuất + 1, gồm hai đầu) qua `addServiceLineToVisitOrder` (nguồn đăng ký, mặc định mã dòng giả, KHÔNG sửa Order thật: xem clinical-shared.ts).
//  A5  Chuồng/đặt chỗ lưu trú đọc từ boarding.mock qua chính route của nó (với vai trò hệ thống, như `BoardingQueryApi` / `KennelApi` không kiểm vai trò). boarding.mock không cho ghi chuồng từ ngoài, nên "chiếm chuồng"
//      (`occupyForTreatment`) được theo dõi ở ĐÂY: chuồng AVAILABLE của boarding mà đang giữ cho một ca ADMITTED khác thì coi như không còn trống. Chỉ nhập từ lượt khám mới cấp chuồng (kennelId bắt buộc);
//      nhập từ lưu trú cấm kennelId, thú ở nguyên chuồng (kennelId null, kennelCode của đặt chỗ). Xuất viện thì ca hết ADMITTED nên chuồng tự trống lại. Đặt chỗ lưu trú giữ nguyên trạng thái (BR-LT-17).
//      Chuồng của chi nhánh khác: BE 400, mock 404 (kennelsOf chỉ liệt kê chuồng của chi nhánh người gọi).
//  A6  LỆCH BE: nhập viện từ đặt chỗ (b) tạo Visit điều trị IN_PROGRESS gán VET gọi qua `startDirectVisit` (visit.mock), mà mock luôn mở Order với một dịch vụ khám EXAM đầu tiên đang bật ở chi nhánh;
//      BE (`VisitApi.startTreatmentVisit`) mở Order KHÔNG có dòng tự sinh. Thú còn lượt mở khác → 400 BR-TN-01. Thú không có trong mock thú (khách lưu trú mẫu 9002, 9003 …) coi là chưa mất,
//      không có lượt mở; cân nặng của theo dõi chỉ ghi vào hồ sơ cân khi thú có trong mock thú.
//  A9  Y lệnh MEDICATION: `prescriptionItemId` phải là dòng đơn CỦA PHÒNG KHÁM (không mua ngoài) của đúng Visit của ca (đọc qua `prescriptionItemIdsOfVisit` / `externalPrescriptionItemIdsOfVisit` của visit-clinical.mock).
//      Y lệnh không bị chặn theo `scheduledAt` (quá khứ hay tương lai đều được).
//  A10 Dữ liệu mẫu KHÔNG có ca ADMITTED nào trên lượt thật (chỉ lịch sử DISCHARGED ở lượt 61 và 1 ca ADMITTED chi nhánh 2 trên lượt giả) để không chặn hoàn tất lượt 2002 của các test GĐ1 (guard BR-LT-14 đã đăng ký vào
//      visit-clinical.mock). `code` NT-yyMM-xxxxx: bộ đếm theo tháng VN của lúc nhập viện (BE: `document_counters` theo tháng).
//  Không mô phỏng: hai lần nhập viện đồng thời (409 CONCURRENCY_CONFLICT), khóa dòng, thông báo EMAIL (mock chỉ có IN_APP).
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
import { externalPrescriptionItemIdsOfVisit, prescriptionItemIdsOfVisit, registerCompletionGuard } from './visit-clinical.mock';

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
  /** Bộ đếm mã `NT-yyMM-xxxxx` theo tháng VN của lúc nhập viện. */
  counters: Record<string, number>;
  admissions: AdmissionRow[];
  orders: OrderRow[];
  observations: ObservationRow[];
}

const iso = (ms: number) => new Date(ms).toISOString();

function nextCode(counters: Record<string, number>, atMs: number): string {
  const month = vnDate(atMs).slice(2, 7).replace('-', '');
  counters[month] = (counters[month] ?? 0) + 1;
  return `NT-${month}-${String(counters[month]).padStart(5, '0')}`;
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

/** Số ngày tính tiền = ngày xuất (hoặc hôm nay nếu còn ADMITTED) − ngày nhập + 1, theo ngày VN, gồm hai đầu (BR-LT-14, ADR-0124): BE đo 25/11/2026. */
const billedDays = (a: AdmissionRow, now: number) => daysBetween(Date.parse(a.admittedAt), a.dischargedAt ? Date.parse(a.dischargedAt) : now) + 1;

const view = (a: AdmissionRow, now = Date.now()): Admission => ({
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
  billedDays: billedDays(a, now),
  estimatedAmount: billedDays(a, now) * a.unitPrice,
  dischargeOutcome: a.dischargeOutcome,
  dischargeSummary: a.dischargeSummary,
  dischargedAt: a.dischargedAt,
  orderLineId: a.orderLineId,
});

const summary = (a: AdmissionRow, now = Date.now()): AdmissionSummary => ({
  admissionId: a.admissionId,
  code: a.code,
  petName: petNameOf(a),
  status: a.status,
  admittedAt: a.admittedAt,
  kennelCode: a.kennelCode,
  billedDays: billedDays(a, now),
  estimatedAmount: billedDays(a, now) * a.unitPrice,
});

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

/** `AdmissionAssignees.requireAssignee` của BE: việc của VET trên ca (xuất viện, ra và hủy y lệnh) thuộc VET đang được gán Visit; Visit không có trong mock Visit (dữ liệu mẫu) thì lấy người nhập viện. */
const isAssignee = (a: AdmissionRow, req: MockRequest) => (visitRowOf(a.visitId)?.assigneeId ?? a.admittedBy) === me(req).id;

/** Đọc boarding.mock qua route của nó, như `BoardingQueryApi` / `KennelApi` (không kiểm vai trò): đóng vai quản lý chi nhánh của người gọi (A5). */
const asSystem = (req: MockRequest, params: Record<string, string> = {}, query: Record<string, string> = {}): MockRequest => ({ body: null, query, params, account: { ...me(req), role: 'BRANCH_MANAGER' } });
const bookingOf = (req: MockRequest, bookingId: number): Booking | MockReply => {
  const reply = boardingRoutes['GET /api/boarding-bookings/:bookingId'](asSystem(req, { bookingId: String(bookingId) }));
  return reply.status >= 400 ? reply : (reply.body as { data: Booking }).data;
};
const kennelsOf = (req: MockRequest): Kennel[] => {
  const reply = boardingRoutes['GET /api/kennels'](asSystem(req));
  return reply.status >= 400 ? [] : (reply.body as { data: Kennel[] }).data;
};
/** `BoardingQueryApi.findCurrentStay`: thú đang nằm trong chuồng (CHECKED_IN / OVERDUE). */
const inStay = (req: MockRequest, petId: number): boolean => {
  const reply = boardingRoutes['GET /api/boarding-bookings'](asSystem(req, {}, { petId: String(petId), inStay: 'true', size: '1' }));
  return reply.status < 400 && (reply.body as { data: { content: unknown[] } }).data.content.length > 0;
};
/** Chuồng đang giữ cho một ca ADMITTED khác (A5). */
const heldKennels = (s: State) => new Set(s.admissions.filter((a) => a.status === 'ADMITTED' && a.kennelDedicated && a.kennelId !== null).map((a) => a.kennelId));

/** Dịch vụ khám mặc định để mở Order của Visit điều trị (A6). */
const treatmentService = (branchId: number) => catalogServices().find((x) => x.medicalType === 'EXAM' && x.isActive && branchBooking(branchId)?.isServiceEnabled(x.serviceId));

const blank = (v: string | null) => v === null || v.trim() === '';
const tooLong = (v: string | null, n: number) => v !== null && v.length > n;
const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const enumOf = <T extends string>(v: string | null | undefined, values: readonly T[]): T | null | undefined => (v === null || v === undefined ? null : (values as readonly string[]).includes(v) ? (v as T) : undefined);

/** Lỗi 409 "không còn ADMITTED" của BE: câu nêu việc đang làm, ví dụ "ca nội trú (chỉ ra y lệnh khi đang điều trị) từ DISCHARGED sang ra y lệnh". */
const notAdmitted = (row: AdmissionRow, what: string, to: string) => conflict(`${NAME} (${what})`, row.status, to);

// ─── #1 Nhập viện ───

/**
 * #1 (BR-LT-13, 17), thứ tự đo ở BE: body sai kiểu 400 → thiếu dịch vụ / mã ≤ 0 400 → VET 403 → nguồn (đúng một trong visitId / boardingBookingId; từ khám phải có kennelId, từ lưu trú cấm kennelId) 400 BR-LT-13
 * → Visit hoặc đặt chỗ 404 → chi nhánh 403 → thú đã ADMITTED 409 → thú đã mất 400 → (từ khám) Visit không IN_PROGRESS / không do mình phụ trách / không nhóm Khám-Tiêm / thú đang lưu trú 400
 * hoặc (từ lưu trú) đặt chỗ không CHECKED_IN / OVERDUE 400 → dịch vụ 404, không INPATIENT / ngừng / chưa bật tại chi nhánh 400 → (từ khám) chuồng 404, không AVAILABLE 400.
 * Từ lưu trú: Visit điều trị mới gán VET gọi, thú ở nguyên chuồng (kennelId null; kennelCode của đặt chỗ hiện ở danh sách).
 */
const admit: MockHandler = (req) => {
  if (!isJsonObject(req.body)) return malformed();
  const visitId = asLong(req.body.visitId);
  const bookingId = asLong(req.body.boardingBookingId);
  const serviceId = asLong(req.body.serviceId);
  const kennelId = asLong(req.body.kennelId);
  if ([visitId, bookingId, serviceId, kennelId].includes(undefined)) return malformed();
  const positive = (name: string, label: string, v: number | null | undefined): [string, string, boolean] => [name, `${label} không hợp lệ`, v != null && v <= 0];
  const bad = invalid([
    ['serviceId', 'Vui lòng chọn dịch vụ nội trú', serviceId === null],
    positive('serviceId', 'Mã dịch vụ', serviceId),
    positive('visitId', 'Mã lượt khám', visitId),
    positive('boardingBookingId', 'Mã đặt chỗ lưu trú', bookingId),
    positive('kennelId', 'Mã chuồng', kennelId),
  ]);
  if (bad) return bad;
  if (!allow(VET_ONLY, req)) return denied();
  if ((visitId === null) === (bookingId === null)) return ruleFail('BR-LT-13', 'Chọn đúng một nguồn nhập viện: lượt khám hoặc đặt chỗ lưu trú');
  if (visitId !== null && kennelId === null) return ruleFail('BR-LT-13', 'Nhập viện từ lượt khám phải chọn chuồng điều trị');
  if (bookingId !== null && kennelId !== null) return ruleFail('BR-LT-13', 'Nhập viện từ lưu trú không chọn chuồng: thú ở nguyên chuồng của đặt chỗ');

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
  if (s.admissions.some((a) => a.petId === petId && a.status === 'ADMITTED')) return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái ca nội trú (thú đã có ca đang điều trị) từ — sang ADMITTED', 409);
  if (petSummary(petId)?.deceasedOn) return ruleFail('BR-LT-13', 'Thú cưng đã được đánh dấu đã mất');
  if (visitId !== null) {
    const visit = visitRowOf(visitId as number)!;
    if (visit.status !== 'IN_PROGRESS') return ruleFail('BR-LT-13', 'Lượt khám phải đang được thực hiện (IN_PROGRESS)');
    if (visit.assigneeId !== me(req).id) return ruleFail('BR-LT-13', 'Chỉ VET đang phụ trách lượt khám mới nhập viện được');
    if (visit.serviceGroup !== 'MEDICAL') return ruleFail('BR-LT-13', 'Chỉ nhập viện được từ lượt khám / tiêm');
    if (inStay(req, petId)) return ruleFail('BR-LT-13', 'Thú đang lưu trú: nhập viện từ đặt chỗ lưu trú, không cấp thêm chuồng');
  } else if (booking!.status !== 'CHECKED_IN' && booking!.status !== 'OVERDUE') {
    return ruleFail('BR-LT-13', 'Đặt chỗ phải đang giữ thú trong chuồng (CHECKED_IN hoặc OVERDUE)');
  }
  const service = findClinicalService(serviceId as number);
  if (!service) return notFound('dịch vụ', serviceId as number);
  if (service.medicalType !== 'INPATIENT') return ruleFail('BR-LT-13', 'Dịch vụ không phải dịch vụ nội trú (INPATIENT)');
  if (!service.isActive) return ruleFail('BR-LT-13', 'Dịch vụ nội trú đã ngừng kinh doanh');
  if (!service.enabledAt(branchId)) return ruleFail('BR-LT-13', 'Dịch vụ nội trú chưa được bật tại chi nhánh');
  let kennel: Kennel | undefined;
  if (kennelId !== null) {
    kennel = kennelsOf(req).find((k) => k.kennelId === kennelId);
    if (!kennel) return notFound('chuồng', kennelId as number);
    if (kennel.status !== 'AVAILABLE' || heldKennels(s).has(kennel.kennelId)) return ruleFail('BR-LT-13', 'Chuồng không còn trống');
  }

  const now = Date.now();
  let admissionVisitId = visitId as number;
  if (booking) {
    const exam = treatmentService(branchId);
    if (!exam) return fail('INTERNAL_ERROR', 'Chi nhánh chưa bật dịch vụ khám để mở Order cho lượt điều trị', 500);
    const started = startDirectVisit({ branchId, customerId, petId, serviceId: exam.serviceId, vetId: me(req).id, appointmentId: null, now });
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
    kennelId: kennel ? kennel.kennelId : null,
    kennelCode: kennel ? kennel.code : (booking?.kennelCode ?? null),
    kennelDedicated: !!kennel,
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
  return ok(view(row, now), 201, 'Đã nhập viện');
};

// ─── #2, #3 Xem ca ───

/** #2: lễ tân, VET, CARETAKER (BRANCH_MANAGER chưa được xem: ADR-0148); query sai kiểu → vai trò. `status` vắng = ADMITTED; nhập viện sớm nhất trước; phân trang. */
const listAdmissions: MockHandler = (req) => {
  const { status } = req.query;
  if (status !== undefined && !(ADMISSION_STATUSES as readonly string[]).includes(status)) return badParam('status');
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  if (!allow(VIEW_ROLES, req)) return denied();
  const now = Date.now();
  const found = load()
    .admissions.filter((a) => a.branchId === branchOf(req) && a.status === (status ?? 'ADMITTED'))
    .sort((a, b) => Date.parse(a.admittedAt) - Date.parse(b.admittedAt) || a.admissionId - b.admissionId);
  return ok(pageOf(found.map((a) => summary(a, now)), clampPaging(req.query)));
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
 * #4 (BR-LT-14), thứ tự đo ở BE: path / body sai kiểu 400 → thiếu kết cục / tóm tắt, tóm tắt > 2000 400 VALIDATION_FAILED → VET 403 → tóm tắt rỗng sau cắt 400 BR-LT-14 (TRƯỚC 404)
 * → 404 → chi nhánh 403 → không phải VET đang được gán Visit 403 → 409 không ADMITTED → còn y lệnh ORDERED 400 BR-LT-14.
 * Kết cục DECEASED xuất viện được ngay: không đòi, cũng không tự đánh dấu thú mất (lễ tân làm sau, BR-LT-14). Thêm dòng dịch vụ nội trú (số ngày × giá đã chốt) vào Order của Visit (A4).
 */
const discharge: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  if (!isJsonObject(req.body)) return malformed();
  const outcome = enumOf(asText(req.body.outcome), DISCHARGE_OUTCOMES);
  const summaryText = asText(req.body.summary);
  if (outcome === undefined || summaryText === undefined) return malformed();
  const bad = invalid([
    ['outcome', 'Vui lòng chọn kết cục xuất viện', outcome === null],
    ['summary', 'Vui lòng nhập tóm tắt điều trị', summaryText === null],
    ['summary', `Tóm tắt điều trị tối đa ${INPATIENT_LIMITS.summary} ký tự`, tooLong(summaryText, INPATIENT_LIMITS.summary)],
  ]);
  if (bad) return bad;
  if (!allow(VET_ONLY, req)) return denied();
  if (blank(summaryText)) return ruleFail('BR-LT-14', 'Vui lòng nhập tóm tắt điều trị');

  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  if (!isAssignee(row, req)) return scopeDenied();
  if (row.status !== 'ADMITTED') return conflict(NAME, row.status, 'DISCHARGED');
  const pending = s.orders.filter((o) => o.admissionId === row.admissionId && o.status === 'ORDERED').length;
  if (pending > 0) return ruleFail('BR-LT-14', `Còn ${pending} y lệnh chưa thực hiện hoặc hủy`);

  const now = Date.now();
  const days = billedDays({ ...row, dischargedAt: iso(now) }, now);
  const lineId = addServiceLineToVisitOrder(row.visitId, row.serviceId, days, me(req).id, row.unitPrice);
  if (lineId === null) return fail('INTERNAL_ERROR', `Lượt #${row.visitId} không có Order đang mở`, 500);
  Object.assign(row, { status: 'DISCHARGED', dischargeOutcome: outcome, dischargeSummary: summaryText!.trim(), dischargedBy: me(req).id, dischargedAt: iso(now), orderLineId: lineId });
  save(s);
  return ok(view(row, now), 200, 'Đã xuất viện');
};

// ─── #5, #6 Y lệnh ───

/**
 * #5 (BR-LT-15), thứ tự đo ở BE: path / body sai kiểu 400 → thiếu loại / nội dung / giờ, nội dung > 500, mã dòng đơn ≤ 0 400 VALIDATION_FAILED → VET 403 → 404 → chi nhánh 403
 * → không phải VET đang được gán Visit 403 → 409 ca không ADMITTED → 400 BR-LT-15 (nội dung rỗng sau cắt KHÔNG phải VALIDATION_FAILED; loại khác thuốc mà có dòng đơn; thuốc thiếu dòng đơn,
 * dòng đơn không thuộc Visit, dòng đơn mua ngoài).
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
  const bad = invalid([
    ['orderType', 'Vui lòng chọn loại y lệnh', orderType === null],
    ['content', 'Vui lòng nhập nội dung y lệnh', content === null],
    ['content', `Nội dung y lệnh tối đa ${INPATIENT_LIMITS.content} ký tự`, tooLong(content, INPATIENT_LIMITS.content)],
    ['scheduledAt', 'Vui lòng nhập giờ thực hiện dự kiến', scheduledAt === null],
    ['prescriptionItemId', 'Mã dòng đơn thuốc phải lớn hơn 0', itemId !== null && itemId <= 0],
  ]);
  if (bad) return bad;
  if (!allow(VET_ONLY, req)) return denied();

  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  if (!isAssignee(row, req)) return scopeDenied();
  if (row.status !== 'ADMITTED') return notAdmitted(row, 'chỉ ra y lệnh khi đang điều trị', 'ra y lệnh');
  if (blank(content)) return ruleFail('BR-LT-15', 'Vui lòng nhập nội dung y lệnh');
  if (orderType !== 'MEDICATION') {
    if (itemId !== null) return ruleFail('BR-LT-15', 'Chỉ y lệnh thuốc mới liên kết dòng đơn thuốc');
  } else {
    if (itemId === null) return ruleFail('BR-LT-15', 'Y lệnh thuốc phải liên kết một dòng đơn thuốc');
    if (!prescriptionItemIdsOfVisit(row.visitId).includes(itemId)) return ruleFail('BR-LT-15', 'Dòng đơn thuốc không thuộc lượt khám của ca nội trú');
    if (externalPrescriptionItemIdsOfVisit(row.visitId).includes(itemId)) return ruleFail('BR-LT-15', 'Dòng đơn thuốc mua ngoài không ra y lệnh được; chỉ thuốc của phòng khám');
  }
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

/**
 * #7 (BR-LT-15), thứ tự đo ở BE: path / body sai kiểu 400 → thiếu ghi chú, ghi chú > 300 400 VALIDATION_FAILED → VET, CARETAKER 403 → y lệnh 404 → chi nhánh 403
 * → CARETAKER với y lệnh thuốc 403 ACCESS_DENIED_SCOPE_MISMATCH (không phải ACCESS_DENIED) → 409 không ORDERED → ghi chú rỗng sau cắt 400 BR-LT-15.
 * VET nào của chi nhánh cũng thực hiện được, không cần là người được gán Visit.
 */
const completeOrder: MockHandler = (req) => {
  const orderId = idParam(req, 'admissionOrderId');
  if (isReply(orderId)) return orderId;
  if (!isJsonObject(req.body)) return malformed();
  const note = asText(req.body.note);
  if (note === undefined) return malformed();
  const bad = invalid([
    ['note', 'Vui lòng nhập ghi chú thực hiện', note === null],
    ['note', `Ghi chú thực hiện tối đa ${INPATIENT_LIMITS.doneNote} ký tự`, tooLong(note, INPATIENT_LIMITS.doneNote)],
  ]);
  if (bad) return bad;
  if (!allow(COMPLETE_ROLES, req)) return denied();
  const s = load();
  const found = orderFor(req, s, orderId);
  if (!('order' in found)) return found;
  const { order } = found;
  if (me(req).role === 'CARETAKER' && order.orderType === 'MEDICATION') return scopeDenied();
  if (order.status !== 'ORDERED') return conflict('y lệnh nội trú', order.status, 'DONE');
  if (blank(note)) return ruleFail('BR-LT-15', 'Vui lòng nhập ghi chú thực hiện');
  Object.assign(order, { status: 'DONE', doneBy: me(req).id, doneAt: iso(Date.now()), doneNote: note!.trim() });
  save(s);
  return ok(orderView(order), 200, 'Đã ghi nhận thực hiện y lệnh');
};

/**
 * #8 (BR-LT-15), thứ tự đo ở BE: path / body sai kiểu 400 → thiếu lý do, lý do > 300 400 VALIDATION_FAILED → VET 403 → y lệnh 404 → chi nhánh 403 → không phải VET đang được gán Visit 403
 * → 409 không ORDERED → lý do rỗng sau cắt 400 BR-LT-15.
 */
const cancelOrder: MockHandler = (req) => {
  const orderId = idParam(req, 'admissionOrderId');
  if (isReply(orderId)) return orderId;
  if (!isJsonObject(req.body)) return malformed();
  const reason = asText(req.body.reason);
  if (reason === undefined) return malformed();
  const bad = invalid([
    ['reason', 'Vui lòng nhập lý do hủy', reason === null],
    ['reason', `Lý do hủy tối đa ${INPATIENT_LIMITS.reason} ký tự`, tooLong(reason, INPATIENT_LIMITS.reason)],
  ]);
  if (bad) return bad;
  if (!allow(VET_ONLY, req)) return denied();
  const s = load();
  const found = orderFor(req, s, orderId);
  if (!('order' in found)) return found;
  const { order, admission } = found;
  if (!isAssignee(admission, req)) return scopeDenied();
  if (order.status !== 'ORDERED') return conflict('y lệnh nội trú', order.status, 'CANCELLED');
  if (blank(reason)) return ruleFail('BR-LT-15', 'Vui lòng nhập lý do hủy');
  Object.assign(order, { status: 'CANCELLED', cancelReason: reason!.trim() });
  save(s);
  return ok(orderView(order), 200, 'Đã hủy y lệnh');
};

// ─── #9, #10 Theo dõi ───

/** Số JSON như Jackson: số, hoặc chuỗi số; `integer` thì số lẻ → sai kiểu. */
function asNumber(v: unknown, integer = false): number | null | undefined {
  if (v === undefined || v === null) return null;
  const n = typeof v === 'number' ? v : typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v.trim()) ? Number(v) : NaN;
  if (!Number.isFinite(n)) return undefined;
  return integer && !Number.isInteger(n) ? undefined : n;
}
/** Làm tròn HALF_UP (xa số 0) như BigDecimal của BE; `e` nối vào chuỗi để 38.55 → 38.6, không vướng sai số nhị phân. */
const roundHalfUp = (v: number, digits: number) => Math.sign(v) * Number(`${Math.round(Number(`${Math.abs(v)}e${digits}`))}e-${digits}`);

/** `AdmissionObservation.invalidReason` của BE: giới hạn lưu trữ của cột V38 và BR-KH-04 (cân > 0); khoảng lâm sàng chưa chốt (A4), nhiệt độ 0 và âm không bị chặn. */
function observationProblem(v: { temperatureC: number | null; heartRate: number | null; respiratoryRate: number | null; weightKg: number | null; appetite: Appetite | null; note: string | null }): string | null {
  if (v.temperatureC === null && v.heartRate === null && v.respiratoryRate === null && v.weightKg === null && v.appetite === null && blank(v.note)) return 'Vui lòng nhập ít nhất một chỉ số hoặc ghi chú theo dõi';
  if (v.temperatureC !== null && Math.abs(v.temperatureC) >= OBSERVATION_BOUNDS.temperatureC.maxAbsExclusive) return 'Nhiệt độ vượt giới hạn lưu trữ';
  const smallint = (n: number | null) => n !== null && (n < OBSERVATION_BOUNDS.heartRate.min || n > OBSERVATION_BOUNDS.heartRate.max);
  if (smallint(v.heartRate)) return `Nhịp tim phải từ 1 đến ${OBSERVATION_BOUNDS.heartRate.max}`;
  if (smallint(v.respiratoryRate)) return `Nhịp thở phải từ 1 đến ${OBSERVATION_BOUNDS.respiratoryRate.max}`;
  if (v.weightKg !== null && v.weightKg < OBSERVATION_BOUNDS.weightKg.min) return 'Cân nặng phải lớn hơn 0';
  if (v.weightKg !== null && v.weightKg >= OBSERVATION_BOUNDS.weightKg.maxExclusive) return 'Cân nặng vượt giới hạn lưu trữ';
  return null;
}

/** INPATIENT_OBSERVATION_ABNORMAL_APP (V63): báo VET đang được gán Visit (trừ chính người ghi, link màn nội trú) và khách có tài khoản (link /pets). Chữ lấy từ mẫu của BE. */
function notifyAbnormal(row: AdmissionRow, recordedBy: number, at: number) {
  const pet = petNameOf(row);
  const day = vnDate(at);
  const notice = (linkUrl: string) => ({
    type: 'INPATIENT_OBSERVATION_ABNORMAL_APP',
    title: `${pet} có dấu hiệu bất thường khi nội trú`,
    body: `Theo dõi ngày ${day.slice(8, 10)}/${day.slice(5, 7)}/${day.slice(0, 4)} của ${pet} (ca nội trú ${row.code} tại ${branchBooking(row.branchId)?.branch.name ?? `Chi nhánh #${row.branchId}`}) được đánh dấu bất thường. Vui lòng xem chỉ số theo dõi và liên hệ chi nhánh.`,
    linkUrl,
  });
  const vet = visitRowOf(row.visitId)?.assigneeId ?? row.admittedBy;
  if (vet !== recordedBy) pushNotification(vet, notice('/admin/inpatient'));
  const account = findCustomer(row.customerId)?.accountId;
  if (account != null) pushNotification(account, notice('/pets'));
}

/**
 * #9 (BR-LT-16), thứ tự đo ở BE: path / body sai kiểu 400 → thiếu `abnormal`, ghi chú > 500 400 VALIDATION_FAILED → VET, CARETAKER 403 → 404 → chi nhánh 403 → 409 ca không ADMITTED
 * → 400 BR-LT-16 (không có chỉ số hay ghi chú nào, giá trị ngoài giới hạn lưu trữ). Mọi VET / CARETAKER của chi nhánh ghi được, không cần là người được gán Visit.
 * Nhiệt độ làm tròn 1 chữ số lẻ, cân nặng 2; cân nặng ghi vào hồ sơ cân của thú (BR-KH-04); `abnormal` báo VET và khách (A8).
 */
const createObservation: MockHandler = (req) => {
  const admissionId = idParam(req, 'admissionId');
  if (isReply(admissionId)) return admissionId;
  if (!isJsonObject(req.body)) return malformed();
  const b = req.body;
  const temperatureC = asNumber(b.temperatureC);
  const heartRate = asNumber(b.heartRate, true);
  const respiratoryRate = asNumber(b.respiratoryRate, true);
  const weightKg = asNumber(b.weightKg);
  const appetite = enumOf(asText(b.appetite), APPETITES);
  const note = asText(b.note);
  const abnormal = asBoolean(b.abnormal);
  if ([temperatureC, heartRate, respiratoryRate, weightKg, appetite, note, abnormal].includes(undefined)) return malformed();
  const bad = invalid([
    ['abnormal', 'Vui lòng cho biết mục theo dõi có bất thường không', abnormal === null],
    ['note', `Ghi chú tối đa ${INPATIENT_LIMITS.observationNote} ký tự`, tooLong(note as string | null, INPATIENT_LIMITS.observationNote)],
  ]);
  if (bad) return bad;
  if (!allow(OBSERVE_ROLES, req)) return denied();

  const s = load();
  const row = admissionFor(req, s, admissionId);
  if (!isAdmission(row)) return row;
  if (row.status !== 'ADMITTED') return notAdmitted(row, 'chỉ ghi theo dõi khi đang điều trị', 'ghi theo dõi');
  const values = { temperatureC: temperatureC as number | null, heartRate: heartRate as number | null, respiratoryRate: respiratoryRate as number | null, weightKg: weightKg as number | null, appetite: appetite as Appetite | null, note: note as string | null };
  const problem = observationProblem(values);
  if (problem) return ruleFail('BR-LT-16', problem);

  const now = Date.now();
  const weight = values.weightKg === null ? null : roundHalfUp(values.weightKg, 2);
  if (weight !== null && petSummary(row.petId)) {
    const recorded = recordVisitWeight(row.petId, weight, row.visitId, iso(now));
    if (isReply(recorded)) return recorded;
  }
  const created: ObservationRow = {
    observationId: s.seq.observation + 1,
    admissionId: row.admissionId,
    recordedBy: me(req).id,
    temperatureC: values.temperatureC === null ? null : Math.abs(values.temperatureC) < 0.05 ? 0 : roundHalfUp(values.temperatureC, 1),
    heartRate: values.heartRate,
    respiratoryRate: values.respiratoryRate,
    weightKg: weight,
    appetite: values.appetite,
    note: values.note?.trim() || null,
    abnormal: abnormal === true,
    createdAt: iso(now),
  };
  s.seq.observation = created.observationId;
  s.observations.push(created);
  save(s);
  if (created.abnormal) notifyAbnormal(row, created.recordedBy, now);
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

/** BE đo: hoàn tất lượt khi còn ca ADMITTED → 409 INVALID_STATE_TRANSITION (guard Visit#5), không phải 400. */
registerCompletionGuard('inpatient', (visitId) =>
  load().admissions.some((a) => a.visitId === visitId && a.status === 'ADMITTED')
    ? fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận (còn ca nội trú đang điều trị) từ IN_PROGRESS sang COMPLETED', 409)
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
