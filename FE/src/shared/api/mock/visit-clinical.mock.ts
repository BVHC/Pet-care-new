// Mock visit-v1 hoàn tất lượt, bệnh án, cân nặng, bản bổ sung, tiêm chủng (#10, #12–15, #18–20; UC48, UC49, UC52, UC53; docs/adr/0066–0070) — T37.
// #10, #12–15 chép từ BE (module visit: MedicalRecordService, VisitCompletionService, MedicalRecordController, DTO, mapper). Thứ tự kiểm khác 00-method §3.5:
// đọc JSON / tham số path và Bean Validation chạy TRƯỚC `@PreAuthorize` (400 trước 403, kể cả ADMIN) → vai trò 403 → lượt 404 → chi nhánh 403 → nhóm Thẩm mỹ 404
// → người phụ trách 403 (đứng TRƯỚC 409, để người không phải chủ lượt không dò được trạng thái) → trạng thái 409 → rule 400.
// #18–20 (tiêm chủng) BE CHƯA CÓ (T28, `VaccinationQueryApiPlaceholder`): chỉ theo hợp đồng visit-v1 + BR-KB-04; chỗ hợp đồng im lặng là ASSUMPTION ghi ở
// docs/testcases/T37-fe.md. Không mô phỏng: audit (BE ghi MEDICAL_RECORD_UPDATED, MEDICAL_RECORD_ADDENDUM_ADDED), khóa đồng thời, Care Task#5 khi tiêm.
import type { Role } from '../../types/auth';
import type { Addendum, MedicalRecord, ProtocolDoseOption, VaccinationOptions, VaccinationRecord } from '../../types/medical-record';
import type { VaccineType } from '../../types/catalog';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { completeAppointmentByVisit } from './appointment.mock';
import { catalogProducts, catalogProtocols, catalogServices, catalogVaccineTypes } from './catalog.mock';
import type { HealthVisitEntry } from '../../../domains/pets/types';
import { branchBooking } from './branch.mock';
import { petSummary, recordVisitWeight, registerHealthSource, setPetOpenVisit } from './customer-pet.mock';
import { denied, fail, invalid, noContent, notFound, ok, ruleFail, scopeDenied, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { branchIdOf } from './identity.mock';
import { staffNameOf } from './identity-admin.mock';
import { availableQuantity, issueOneFefo, returnToLot } from './inventory.mock';
import { addVaccineLine, markOrderPendingByVisit, removeVaccineLine, serviceIdsOfVisit } from './sales.mock';
import {
  FSM_NAME,
  ROLE_OF_GROUP,
  asBoolean,
  asLong,
  asText,
  completedVisitsOfPet,
  conflict,
  hasOtherOpenVisit,
  idParam,
  isJsonObject,
  isoOf,
  isReply,
  malformed,
  transition,
  updateVisitRow,
  visitNotFound,
  visitRowOf,
  visitView,
  type VisitRow,
} from './visit.mock';

const VIEW_ROLES: readonly Role[] = ['VET', 'CARETAKER'];
const WRITE_ROLES: readonly Role[] = ['VET'];
/** Hoàn tất lượt: người được gán, VET cho Khám/Tiêm, CARETAKER cho Thẩm mỹ (BR-TN-05). */
const COMPLETE_ROLES: readonly Role[] = ['VET', 'CARETAKER'];
/** Tiêm chủng #18–20: VET (được gán lượt; xóa mũi: VET đã ghi mũi đó). */
const VACCINATE_ROLES: readonly Role[] = ['VET'];
/** [CFG] `medical_record.follow_up_max_days` (seed BE V2__seed_system_configs.sql): sau ngày khám, xa nhất 180 ngày (BR-KB-06). */
const FOLLOW_UP_MAX_DAYS = 180;
const MINUTE = 60_000;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// ─── Dữ liệu ───

interface RecordRow {
  visitId: number;
  examination: string | null;
  diagnosis: string | null;
  treatmentPlan: string | null;
  internalNote: string | null;
  followUpDate: string | null;
  lastEditedBy: number | null;
}
interface AddendumRow {
  addendumId: number;
  visitId: number;
  content: string;
  isInternal: boolean;
  createdBy: number;
  createdAt: string;
}
interface VaccinationRow {
  vaccinationId: number;
  visitId: number;
  petId: number;
  branchId: number;
  vaccineTypeId: number;
  protocolId: number;
  doseNumber: number;
  productId: number;
  /** Lô đã trừ (để hoàn đúng lô khi xóa mũi, BR-KB-04) và dòng Order vaccine tương ứng. */
  lotId: number;
  orderLineId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  administeredOn: string;
  nextDueDate: string;
  recordedBy: number;
}
interface State {
  seq: number;
  records: RecordRow[];
  addenda: AddendumRow[];
  vaccinations: VaccinationRow[];
}

/**
 * Dữ liệu mẫu cho các lượt Khám/Tiêm đã hoàn tất của visit.mock (người ghi: VET #5): 61 hôm nay (có hẹn tái khám sau 7 ngày và một bản bổ sung), 58, 40 và 2001.
 * Bệnh án đã khóa vì lượt COMPLETED. Không có mũi tiêm mẫu: dòng vaccine của Order #3 (lượt 58) là sản phẩm của chó còn thú là mèo, nên không dựng mũi tiêm khớp.
 */
function seed(): State {
  const s: State = { seq: 0, records: [], addenda: [], vaccinations: [] };
  const rows: [number, Partial<RecordRow>, number | null][] = [
    [61, { examination: 'Mèo sốt nhẹ, bỏ ăn 1 ngày, nghe phổi có ran', diagnosis: 'Viêm đường hô hấp trên', treatmentPlan: 'Kháng sinh 7 ngày, ăn thức ăn mềm', internalNote: 'Chủ lo lắng, nhắc tái khám' }, 7],
    [58, { examination: 'Khám trước tiêm, thể trạng tốt', diagnosis: 'Khỏe mạnh' }, null],
    [40, { examination: 'Tiêu chảy 2 ngày', diagnosis: 'Rối loạn tiêu hóa', treatmentPlan: 'Men vi sinh, ăn kiêng 24 giờ' }, null],
    [2001, { examination: 'Khám định kỳ', diagnosis: 'Khỏe mạnh, đủ cân' }, null],
  ];
  for (const [visitId, fields, followUpInDays] of rows) {
    const visit = visitRowOf(visitId);
    if (!visit) continue;
    s.records.push({
      visitId,
      examination: null,
      diagnosis: null,
      treatmentPlan: null,
      internalNote: null,
      ...fields,
      followUpDate: followUpInDays === null ? null : addDays(vnDate(Date.parse(visit.checkedInAt)), followUpInDays),
      lastEditedBy: 5,
    });
  }
  const done = visitRowOf(61);
  if (done?.completedAt) {
    s.addenda.push({ addendumId: ++s.seq, visitId: 61, content: 'Kết quả xét nghiệm nhanh về sau: FeLV/FIV âm tính', isInternal: false, createdBy: 5, createdAt: new Date(Date.parse(done.completedAt) + 20 * MINUTE).toISOString() });
  }
  return s;
}

const STATE_KEY = 'petcare-mock-clinical';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) return JSON.parse(raw) as State;
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const fresh = seed();
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

function addendumView(a: AddendumRow): Addendum {
  return { addendumId: a.addendumId, content: a.content, isInternal: a.isInternal, createdBy: a.createdBy, createdByName: staffNameOf(a.createdBy) ?? `Nhân viên #${a.createdBy}`, createdAt: a.createdAt };
}

/** `superseded`: đã có mũi mới hơn cùng loại vaccine của cùng thú tại hệ thống (BR-TB-03); tính lúc đọc nên xóa mũi mới thì mũi cũ hết bị thay thế. */
function vaccinationView(s: State, v: VaccinationRow): VaccinationRecord {
  const newer = s.vaccinations.some(
    (o) => o.petId === v.petId && o.vaccineTypeId === v.vaccineTypeId && (o.administeredOn > v.administeredOn || (o.administeredOn === v.administeredOn && o.vaccinationId > v.vaccinationId)),
  );
  return {
    vaccinationId: v.vaccinationId,
    visitId: v.visitId,
    branchId: v.branchId,
    vaccineTypeId: v.vaccineTypeId,
    vaccineTypeName: catalogVaccineTypes().find((t) => t.vaccineTypeId === v.vaccineTypeId)?.name ?? `Loại vaccine #${v.vaccineTypeId}`,
    protocolId: v.protocolId,
    doseNumber: v.doseNumber,
    productId: v.productId,
    productName: catalogProducts().find((p) => p.productId === v.productId)?.name ?? `Sản phẩm #${v.productId}`,
    lotNumber: v.lotNumber,
    expiryDate: v.expiryDate,
    administeredOn: v.administeredOn,
    nextDueDate: v.nextDueDate,
    superseded: newer,
    recordedBy: v.recordedBy,
    orderLineId: v.orderLineId,
  };
}

/** `MedicalRecordMapper.toResponse` / `toEmptyResponse`: `locked` = lượt COMPLETED; bản bổ sung chỉ có khi đã khóa, theo thứ tự ghi. */
function recordView(s: State, visit: VisitRow): MedicalRecord {
  const row = s.records.find((r) => r.visitId === visit.visitId);
  const locked = visit.status === 'COMPLETED';
  return {
    visitId: visit.visitId,
    examination: row?.examination ?? null,
    diagnosis: row?.diagnosis ?? null,
    treatmentPlan: row?.treatmentPlan ?? null,
    internalNote: row?.internalNote ?? null,
    followUpDate: row?.followUpDate ?? null,
    locked,
    lastEditedBy: row?.lastEditedBy ?? null,
    prescriptions: [],
    // BE trả mảng rỗng tới khi T28 xong (MedicalRecordMapper); mock mô phỏng T28 theo hợp đồng nên trả các mũi đã ghi trong lượt.
    vaccinations: s.vaccinations.filter((v) => v.visitId === visit.visitId).sort((a, b) => a.vaccinationId - b.vaccinationId).map((v) => vaccinationView(s, v)),
    addenda: locked ? s.addenda.filter((a) => a.visitId === visit.visitId).sort((a, b) => a.addendumId - b.addendumId).map(addendumView) : [],
  };
}

// ─── Dùng chung ───

const me = (req: MockRequest) => req.account!;
const branchOf = (req: MockRequest) => me(req).branchId ?? branchIdOf(me(req).id) ?? null;

/** `findMedicalVisit`: lượt tồn tại (404), thuộc chi nhánh người gọi (403), nhóm Khám/Tiêm (404 — Thẩm mỹ không có bệnh án). */
function medicalVisit(req: MockRequest, visitId: number): VisitRow | MockReply {
  const visit = visitRowOf(visitId);
  if (!visit) return visitNotFound(visitId);
  if (visit.branchId !== branchOf(req)) return scopeDenied();
  return visit.serviceGroup === 'GROOMING' ? notFound('bệnh án', visitId) : visit;
}

/** Người gọi phải là người phụ trách hiện tại (403, kể cả lượt chưa gán) rồi lượt phải IN_PROGRESS (409): `requireAssigneeInProgress`. */
function assigneeInProgress(req: MockRequest, visit: VisitRow): MockReply | null {
  if (visit.assigneeId !== me(req).id) return scopeDenied();
  return visit.status === 'IN_PROGRESS' ? null : conflict(FSM_NAME, visit.status, 'IN_PROGRESS');
}

// ─── #12, #13 Bệnh án ───

/** `UpdateMedicalRecordRequest`: chuỗi bỏ khoảng trắng hai đầu, rỗng thành null; `followUpDate` là ngày `YYYY-MM-DD` thật. Sai kiểu → MALFORMED (trước @PreAuthorize). */
function readRecord(body: unknown) {
  if (!isJsonObject(body)) return malformed();
  const text = (v: unknown) => {
    const t = asText(v);
    return t === undefined ? undefined : (t?.trim() || null);
  };
  const examination = text(body.examination);
  const diagnosis = text(body.diagnosis);
  const treatmentPlan = text(body.treatmentPlan);
  const internalNote = text(body.internalNote);
  const followUp = body.followUpDate ?? null;
  const validDate = followUp === null || (typeof followUp === 'string' && DATE.test(followUp) && new Date(`${followUp}T00:00:00Z`).toISOString().slice(0, 10) === followUp);
  if ([examination, diagnosis, treatmentPlan, internalNote].includes(undefined) || !validDate) return malformed();
  return {
    examination: examination as string | null,
    diagnosis: diagnosis as string | null,
    treatmentPlan: treatmentPlan as string | null,
    internalNote: internalNote as string | null,
    followUpDate: followUp as string | null,
  };
}

/** #12 (getMedicalRecord): path → vai trò (VET, CARETAKER) → 404 → chi nhánh 403 → Thẩm mỹ 404. Lượt chưa ghi bệnh án trả khung rỗng. */
const getMedicalRecord: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!VIEW_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  return ok(recordView(load(), visit), 200, 'Bệnh án của lượt');
};

/**
 * #13 (updateMedicalRecord): body → path → vai trò (VET) → 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → không IN_PROGRESS 409 → BR-KB-06 400 (chỉ khi ngày
 * tái khám đổi) → nội dung không đổi: 200, không ghi (không đổi `lastEditedBy`) → ghi.
 */
const saveMedicalRecord: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const parsed = readRecord(req.body);
  if (isReply(parsed)) return parsed;
  if (!WRITE_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  const blocked = assigneeInProgress(req, visit);
  if (blocked) return blocked;

  const s = load();
  const existing = s.records.find((r) => r.visitId === visitId);
  if (parsed.followUpDate !== null && parsed.followUpDate !== (existing?.followUpDate ?? null)) {
    const examDate = vnDate(Date.parse(visit.checkedInAt));
    if (parsed.followUpDate <= examDate || parsed.followUpDate > addDays(examDate, FOLLOW_UP_MAX_DAYS)) {
      return ruleFail('BR-KB-06', `Ngày tái khám phải sau ngày khám và không quá ${FOLLOW_UP_MAX_DAYS} ngày`);
    }
  }
  const sameContent =
    existing !== undefined &&
    (['examination', 'diagnosis', 'treatmentPlan', 'internalNote', 'followUpDate'] as const).every((key) => existing[key] === parsed[key]);
  if (!sameContent) {
    const row: RecordRow = { visitId, ...parsed, lastEditedBy: me(req).id };
    if (existing) Object.assign(existing, row);
    else s.records.push(row);
    save(s);
  }
  return ok(recordView(s, visit), 200, 'Đã lưu bệnh án');
};

// ─── #14 Cân nặng, #15 Bản bổ sung ───

/** `RecordWeightRequest`: `weightKg` bắt buộc (@NotNull → 400 VALIDATION_FAILED); số hoặc chuỗi số (Jackson ép kiểu), còn lại MALFORMED. */
function readWeight(body: unknown): number | MockReply {
  if (!isJsonObject(body)) return malformed();
  const raw = body.weightKg;
  if (raw === undefined || raw === null) return invalid([['weightKg', 'Bắt buộc nhập cân nặng', true]])!;
  const value = typeof raw === 'number' ? raw : typeof raw === 'string' && raw.trim() !== '' ? Number(raw) : Number.NaN;
  return Number.isFinite(value) ? value : malformed();
}

/** #14 (recordWeight): body → path → vai trò (VET) → 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → không IN_PROGRESS 409 → BR-KH-04 400 → BR-KH-05 400. Không audit (ADR-0068 §4). */
const recordWeight: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const weightKg = readWeight(req.body);
  if (typeof weightKg !== 'number') return weightKg;
  if (!WRITE_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  const blocked = assigneeInProgress(req, visit);
  if (blocked) return blocked;
  const recorded = recordVisitWeight(visit.petId, weightKg, visitId, isoOf(Date.now()));
  return 'status' in recorded ? recorded : ok(recorded, 201, 'Đã ghi cân nặng');
};

/** `AddendumRequest`: `content` bỏ khoảng trắng hai đầu rồi @NotBlank (400 VALIDATION_FAILED); `isInternal` vắng hoặc null = false. */
function readAddendum(body: unknown) {
  if (!isJsonObject(body)) return malformed();
  const content = asText(body.content);
  const internal = asBoolean(body.isInternal);
  if (content === undefined || internal === undefined) return malformed();
  const text = content?.trim() ?? '';
  return invalid([['content', 'Bắt buộc nhập nội dung bổ sung', text === '']]) ?? { content: text, isInternal: internal === true };
}

/**
 * #15 (addAddendum): body → path → vai trò (VET) → 404 → chi nhánh 403 → Thẩm mỹ 404 → lượt chưa COMPLETED 409. KHÔNG cần là người phụ trách: mọi VET của chi nhánh nơi khám ghi được (A7,
 * ADR-0070). Lượt chỉ tiêm không có dòng bệnh án vẫn thêm được (A17); thú đã mất vẫn thêm được. Chỉ thêm, không sửa / xóa nội dung cũ.
 */
const addAddendum: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const parsed = readAddendum(req.body);
  if (isReply(parsed)) return parsed;
  if (!WRITE_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  if (visit.status !== 'COMPLETED') return conflict(FSM_NAME, visit.status, 'COMPLETED');
  const s = load();
  const row: AddendumRow = { addendumId: ++s.seq, visitId, content: parsed.content, isInternal: parsed.isInternal, createdBy: me(req).id, createdAt: isoOf(Date.now()) };
  s.addenda.push(row);
  save(s);
  return ok(addendumView(row), 201, 'Đã thêm bản bổ sung');
};

// ─── #10 Hoàn tất lượt ───

/**
 * BR-KB-02, BR-SP-06 (chỉ nhóm Khám/Tiêm): đọc loại dịch vụ từ Order LÚC NÀY (VET đổi / xóa được dòng tự sinh). Có dịch vụ Khám → phải có chẩn đoán; chỉ có dịch vụ Tiêm → phải
 * có ≥ 1 mũi tiêm; không còn dịch vụ Khám/Tiêm nào → từ chối (A16). BE kiểm sau các lệnh ghi rồi hoàn tác; mock kiểm trước, kết quả nhìn từ ngoài như nhau.
 */
function completionGuard(s: State, visit: VisitRow): MockReply | null {
  const services = serviceIdsOfVisit(visit.visitId).flatMap((id) => catalogServices().filter((x) => x.serviceId === id));
  if (services.some((x) => x.medicalType === 'EXAM')) {
    return s.records.find((r) => r.visitId === visit.visitId)?.diagnosis ? null : ruleFail('BR-KB-02', 'Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất');
  }
  if (services.some((x) => x.medicalType === 'VACCINE')) {
    return s.vaccinations.some((v) => v.visitId === visit.visitId) ? null : ruleFail('BR-KB-02', 'Lượt chỉ có dịch vụ tiêm phải ghi nhận ít nhất 1 mũi tiêm');
  }
  return ruleFail('BR-KB-02', 'Lượt khám/tiêm phải còn ít nhất 1 dịch vụ khám hoặc tiêm');
}

/**
 * #10 (VisitCompletionService.completeVisit): path → vai trò (VET, CARETAKER) → 404 → chi nhánh 403 → người phụ trách 403 (kể cả lượt chưa gán) → chức vụ khớp nhóm 400 BR-TN-05
 * → không IN_PROGRESS 409 → BR-KB-02 400 (chỉ nhóm Khám/Tiêm). Một giao dịch: Visit#5 COMPLETED + gỡ cờ cần gán lại, Lịch hẹn#7, Order#4 PENDING, bệnh án khóa (khóa = lượt COMPLETED).
 */
const completeVisit: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!COMPLETE_ROLES.includes(me(req).role)) return denied();
  const visit = visitRowOf(visitId);
  if (!visit) return visitNotFound(visitId);
  if (visit.branchId !== branchOf(req)) return scopeDenied();
  if (visit.assigneeId !== me(req).id) return scopeDenied();
  if (ROLE_OF_GROUP[visit.serviceGroup] !== me(req).role) return ruleFail('BR-TN-05', 'Chức vụ hiện tại không phụ trách được nhóm dịch vụ của lượt — cần gán lại lượt');
  const wrongState = transition(visit.status, 'COMPLETED');
  if (wrongState) return wrongState;
  if (visit.serviceGroup === 'MEDICAL') {
    const rejected = completionGuard(load(), visit);
    if (rejected) return rejected;
  }

  const at = isoOf(Date.now());
  const done = updateVisitRow(visitId, { status: 'COMPLETED', completedAt: at, needsReassign: false })!;
  if (visit.appointmentId !== null) completeAppointmentByVisit(visit.appointmentId);
  markOrderPendingByVisit(visitId, at);
  if (!hasOtherOpenVisit(visit.petId, visitId)) setPetOpenVisit(visit.petId, false);
  return ok(visitView(done), 200, 'Đã hoàn tất lượt');
};

// ─── #18–20 Tiêm chủng (BR-KB-04). BE chưa có (T28): hợp đồng + BR; thứ tự kiểm, câu báo và tên trường tham số là ASSUMPTION (docs/testcases/T37-fe.md) ───

const INTEGER = /^-?\d+$/;
const DAY_MS = 86_400_000;

/** Tham số query bắt buộc kiểu số nguyên: thiếu / sai kiểu → 400 MALFORMED (trước @PreAuthorize như tham số path). */
function requiredId(req: MockRequest, name: string): number | MockReply {
  const raw = req.query[name];
  if (raw === undefined) return fail('MALFORMED_REQUEST', `Thiếu tham số '${name}'`, 400);
  return INTEGER.test(raw) && Number.isSafeInteger(Number(raw)) ? Number(raw) : fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
}

/** Tuổi tính bằng tuần tại hôm nay (giờ Việt Nam); null khi chưa biết ngày sinh. */
function ageInWeeks(birthDate: string | null): number | null {
  return birthDate === null ? null : Math.floor((Date.parse(`${vnDate()}T00:00:00Z`) - Date.parse(`${birthDate}T00:00:00Z`)) / (7 * DAY_MS));
}

/** Mũi mới nhất của thú cùng loại vaccine tại hệ thống (BR-KB-04, nguyên tắc 8: chỉ tin mũi do hệ thống ghi). */
const lastDoseOf = (s: State, petId: number, vaccineTypeId: number): VaccinationRow | undefined =>
  s.vaccinations
    .filter((v) => v.petId === petId && v.vaccineTypeId === vaccineTypeId)
    .sort((a, b) => b.administeredOn.localeCompare(a.administeredOn) || b.vaccinationId - a.vaccinationId)[0];

/** Phác đồ đang dùng của loại vaccine cho loài của thú, theo thứ tự mũi. */
const protocolOf = (vaccineTypeId: number, species: string) =>
  catalogProtocols()
    .filter((p) => p.vaccineTypeId === vaccineTypeId && p.species === species && p.isActive)
    .sort((a, b) => a.doseNumber - b.doseNumber);

const doseOption = (p: ReturnType<typeof protocolOf>[number]): ProtocolDoseOption => ({
  protocolId: p.protocolId,
  doseNumber: p.doseNumber,
  intervalDays: p.intervalDays,
  minAgeWeeks: p.minAgeWeeks,
  requiredForBoarding: p.requiredForBoarding,
});

const NOT_FOR_SPECIES = 'Loại vaccine không áp dụng cho loài của thú';

/**
 * #18 (getVaccinationOptions): tham số → vai trò (VET) → 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → loại vaccine 404 → loài không khớp 400 BR-KB-04.
 * Mũi gợi ý = mũi kế tiếp sau mũi mới nhất cùng loại tại hệ thống (mũi 1 nếu chưa tiêm); hết phác đồ thì null để VET tự chọn (hợp đồng cho phép null; không suy ra tiêm nhắc lại).
 */
const getVaccinationOptions: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const vaccineTypeId = requiredId(req, 'vaccineTypeId');
  if (isReply(vaccineTypeId)) return vaccineTypeId;
  if (!VACCINATE_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  if (visit.assigneeId !== me(req).id) return scopeDenied();
  const type: VaccineType | undefined = catalogVaccineTypes().find((t) => t.vaccineTypeId === vaccineTypeId);
  if (!type) return notFound('loại vaccine', vaccineTypeId);
  const pet = petSummary(visit.petId)!;
  if (type.species !== pet.species) return ruleFail('BR-KB-04', NOT_FOR_SPECIES);

  const s = load();
  const protocol = protocolOf(type.vaccineTypeId, pet.species);
  const last = lastDoseOf(s, visit.petId, type.vaccineTypeId);
  const suggested = last ? protocol.find((p) => p.doseNumber === last.doseNumber + 1) : protocol[0];
  const age = ageInWeeks(pet.birthDate);
  const result: VaccinationOptions = {
    vaccineTypeId: type.vaccineTypeId,
    protocol: protocol.map(doseOption),
    suggestedProtocolId: suggested?.protocolId ?? null,
    lastDose: last ? vaccinationView(s, last) : null,
    products: catalogProducts()
      .filter((p) => p.productType === 'VACCINE' && p.vaccineTypeId === type.vaccineTypeId && p.isActive)
      .map((p) => ({ productId: p.productId, name: p.name, availableQuantity: availableQuantity(visit.branchId, p.productId) }))
      .filter((p) => p.availableQuantity > 0)
      .sort((a, b) => a.productId - b.productId),
    underMinAge: suggested !== undefined && age !== null && age < suggested.minAgeWeeks,
  };
  return ok(result);
};

/** `RecordVaccinationRequest`: ba mã bắt buộc, số dương. Sai kiểu → MALFORMED, thiếu / không dương → VALIDATION_FAILED (cả hai trước @PreAuthorize). */
function readVaccination(body: unknown) {
  if (!isJsonObject(body)) return malformed();
  const ids = { vaccineTypeId: asLong(body.vaccineTypeId), protocolId: asLong(body.protocolId), productId: asLong(body.productId) };
  if (Object.values(ids).includes(undefined)) return malformed();
  const bad = invalid([
    ['vaccineTypeId', 'Thiếu loại vaccine', ids.vaccineTypeId === null],
    ['vaccineTypeId', 'Mã loại vaccine không hợp lệ', ids.vaccineTypeId != null && ids.vaccineTypeId <= 0],
    ['protocolId', 'Thiếu mũi theo phác đồ', ids.protocolId === null],
    ['protocolId', 'Mã phác đồ không hợp lệ', ids.protocolId != null && ids.protocolId <= 0],
    ['productId', 'Thiếu sản phẩm vaccine', ids.productId === null],
    ['productId', 'Mã sản phẩm không hợp lệ', ids.productId != null && ids.productId <= 0],
  ]);
  return bad ?? (ids as { vaccineTypeId: number; protocolId: number; productId: number });
}

/**
 * #19 (recordVaccination): body → path → vai trò (VET) → 404 → chi nhánh 403 → Thẩm mỹ 404 → người phụ trách 403 → không IN_PROGRESS 409 → loại / phác đồ / sản phẩm 404 → BR-KB-04 400
 * (loài của loại, phác đồ đúng loại + loài, sản phẩm đúng loại, còn tồn khả dụng). Một giao dịch: trừ kho FEFO 1 liều NGAY (không chờ PAID), thêm dòng Order VACCINE, ghi mũi (ngày tiêm = hôm nay,
 * A6; ngày tái chủng = ngày tiêm + khoảng cách của mũi). Thú chưa đủ tuổi tối thiểu: cảnh báo `UNDER_MIN_AGE`, không chặn. Care Task#5 chưa mô phỏng.
 */
const recordVaccination: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const parsed = readVaccination(req.body);
  if (isReply(parsed)) return parsed;
  if (!VACCINATE_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  const blocked = assigneeInProgress(req, visit);
  if (blocked) return blocked;

  const type = catalogVaccineTypes().find((t) => t.vaccineTypeId === parsed.vaccineTypeId);
  if (!type) return notFound('loại vaccine', parsed.vaccineTypeId);
  const protocol = catalogProtocols().find((p) => p.protocolId === parsed.protocolId);
  if (!protocol) return notFound('phác đồ tiêm chủng', parsed.protocolId);
  const product = catalogProducts().find((p) => p.productId === parsed.productId);
  if (!product) return notFound('sản phẩm', parsed.productId);
  const pet = petSummary(visit.petId)!;
  if (type.species !== pet.species) return ruleFail('BR-KB-04', NOT_FOR_SPECIES);
  if (protocol.vaccineTypeId !== type.vaccineTypeId || protocol.species !== pet.species || !protocol.isActive) {
    return ruleFail('BR-KB-04', 'Mũi tiêm không thuộc phác đồ của loại vaccine và loài của thú');
  }
  if (product.productType !== 'VACCINE' || product.vaccineTypeId !== type.vaccineTypeId || !product.isActive) {
    return ruleFail('BR-KB-04', 'Sản phẩm không thuộc loại vaccine đã chọn');
  }
  const lot = issueOneFefo(visit.branchId, product.productId);
  if (!lot) return ruleFail('BR-KB-04', 'Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh');
  const lineId = addVaccineLine(visit.visitId, { productId: product.productId, description: product.name, unitPrice: product.price, actorId: me(req).id });
  if (lineId === null) {
    returnToLot(lot.lotId, 1);
    return fail('INTERNAL_ERROR', `Lượt #${visit.visitId} không có Order đang mở`, 500);
  }

  const s = load();
  const administeredOn = vnDate();
  const row: VaccinationRow = {
    vaccinationId: ++s.seq,
    visitId: visit.visitId,
    petId: visit.petId,
    branchId: visit.branchId,
    vaccineTypeId: type.vaccineTypeId,
    protocolId: protocol.protocolId,
    doseNumber: protocol.doseNumber,
    productId: product.productId,
    lotId: lot.lotId,
    orderLineId: lineId,
    lotNumber: lot.lotNumber,
    expiryDate: lot.expiryDate,
    administeredOn,
    nextDueDate: addDays(administeredOn, protocol.intervalDays),
    recordedBy: me(req).id,
  };
  s.vaccinations.push(row);
  save(s);
  const age = ageInWeeks(pet.birthDate);
  const warnings = age !== null && age < protocol.minAgeWeeks ? ['UNDER_MIN_AGE'] : [];
  return ok({ vaccination: vaccinationView(s, row), warnings }, 201, 'Đã ghi nhận mũi tiêm');
};

/**
 * #20 (deleteVaccination): path → vai trò (VET) → 404 → chi nhánh 403 → Thẩm mỹ 404 → mũi tiêm của lượt này 404 → người ghi mũi đó 403 (hợp đồng: "VET đã ghi mũi đó", không phải người phụ trách)
 * → lượt không IN_PROGRESS 409 (đã COMPLETED: sai sót ghi bằng bản bổ sung). Một giao dịch: hoàn kho đúng lô đã trừ, xóa dòng Order vaccine, xóa mũi.
 */
const deleteVaccination: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  const vaccinationId = idParam(req, 'vaccinationId');
  if (isReply(vaccinationId)) return vaccinationId;
  if (!VACCINATE_ROLES.includes(me(req).role)) return denied();
  const visit = medicalVisit(req, visitId);
  if (isReply(visit)) return visit;
  const s = load();
  const dose = s.vaccinations.find((v) => v.vaccinationId === vaccinationId && v.visitId === visitId);
  if (!dose) return notFound('mũi tiêm', vaccinationId);
  if (dose.recordedBy !== me(req).id) return scopeDenied();
  if (visit.status !== 'IN_PROGRESS') return conflict(FSM_NAME, visit.status, 'IN_PROGRESS');

  returnToLot(dose.lotId, 1);
  removeVaccineLine(visitId, dose.orderLineId);
  s.vaccinations = s.vaccinations.filter((v) => v.vaccinationId !== vaccinationId);
  save(s);
  return noContent();
};

// Hồ sơ sức khỏe thú cưng (visit-v1 #21, UC25) đọc dữ liệu lâm sàng thật của mock này: lượt đã hoàn tất kèm bệnh án và bản bổ sung, mũi tiêm tại hệ thống (BR-KB-04, nguyên tắc 8).
// Khách (chủ hiện tại) bị lọc ghi chú nội bộ và bản bổ sung nội bộ ở customer-pet.mock (BR-KH-07, BR-KB-01).
registerHealthSource((petId) => {
  const s = load();
  return {
    visits: completedVisitsOfPet(petId).map(
      (v): HealthVisitEntry => {
        const r = s.records.find((x) => x.visitId === v.visitId);
        return {
          visitId: v.visitId,
          visitDate: vnDate(Date.parse(v.completedAt ?? v.checkedInAt)),
          branchName: branchBooking(v.branchId)?.branch.name ?? `Chi nhánh #${v.branchId}`,
          serviceGroup: v.serviceGroup,
          examination: r?.examination ?? null,
          diagnosis: r?.diagnosis ?? null,
          treatmentPlan: r?.treatmentPlan ?? null,
          internalNote: r?.internalNote ?? null,
          followUpDate: r?.followUpDate ?? null,
          prescriptions: [],
          addenda: s.addenda.filter((a) => a.visitId === v.visitId).sort((a, b) => a.addendumId - b.addendumId).map(addendumView),
        };
      },
    ),
    vaccinations: s.vaccinations.filter((d) => d.petId === petId).map((d) => vaccinationView(s, d)),
  };
});

export const visitClinicalRoutes: Record<string, MockHandler> = {
  'POST /api/visits/:visitId/complete': completeVisit,
  'GET /api/visits/:visitId/vaccination-options': getVaccinationOptions,
  'POST /api/visits/:visitId/vaccinations': recordVaccination,
  'DELETE /api/visits/:visitId/vaccinations/:vaccinationId': deleteVaccination,
  'GET /api/visits/:visitId/medical-record': getMedicalRecord,
  'PUT /api/visits/:visitId/medical-record': saveMedicalRecord,
  'POST /api/visits/:visitId/weight': recordWeight,
  'POST /api/visits/:visitId/medical-record/addenda': addAddendum,
};
