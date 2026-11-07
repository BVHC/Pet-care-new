// Mock surgery-v1 (UC50, BR-KB-08…11; docs/api/surgery-v1.md #1–8; 09 §B; 03 #23) — T57 bước A. BE tầng 3 CHƯA cài (T54 chỉ duyệt đặc tả), nên không có số đo thật:
// mọi chỗ dưới đây là chép hợp đồng (openapi/surgery-v1.yaml: path, body, "Quyền:", mã lỗi) cộng ASSUMPTION khi hợp đồng im lặng.
//
// Thứ tự kiểm (00-method §3.5; chỗ yaml im lặng giữ thứ tự này cho cả 8 endpoint):
//   401 (adapter) → 400 path / body sai kiểu (MALFORMED_REQUEST) → 403 vai trò (ACCESS_DENIED) → 400 VALIDATION_FAILED (độ dài) → 404 → 403 phạm vi
//   (ACCESS_DENIED_SCOPE_MISMATCH, message chung, KHÔNG mã rule) → 400 BR-KB-… → 409 INVALID_STATE_TRANSITION.
//
// ASSUMPTION (đã cân nhắc, ghi để không bị tính là lệch BE):
//  A1  Phạm vi chi nhánh kiểm SAU 404 vì chi nhánh chỉ biết sau khi nạp Visit / phẫu thuật. VET "phụ trách Visit" = người được gán Visit hiện tại (BR-TN-08 đổi người thì đổi quyền);
//      VET không phụ trách → 403 phạm vi, cùng kiểu visit-v1 #12–20. Lễ tân hủy phẫu thuật IN_PROGRESS → 403 ACCESS_DENIED (yaml: "Lễ tân không hủy được"; quyền theo trạng thái, không phải chi nhánh).
//  A2  Mã rule theo mã ĐẦU của từng dòng lỗi trong yaml: #6 "BR-KB-09 chưa có cam kết · Visit không còn IN_PROGRESS" → cả hai là BR-KB-09 (đúng luật là BR-KB-10 cho vế sau, nhưng yaml ghi BR-KB-09).
//      Phẫu thuật PLANNED mà bấm bắt đầu là 400 BR-KB-09 (không phải 409); các trạng thái khác ngoài CONSENTED là 409.
//  A3  Thiếu `consentText` / `signerName` / `signerRelation` (rỗng hoặc toàn khoảng trắng), `resultNote`, `reason` là 400 BR-KB-09 / BR-KB-10 / BR-KB-11 (yaml ghi "thiếu …" ở dòng BR-…),
//      KHÔNG phải VALIDATION_FAILED; chỉ độ dài (`planNote` ≤ 500, `signerName` ≤ 100, `signerRelation` ≤ 50, `reason` ≤ 300) là VALIDATION_FAILED.
//  A4  Dịch vụ SURGERY: catalog.mock chưa có loại này (09 Q1), dùng bộ mẫu ở clinical-shared.ts (id 7001–7004). Dịch vụ khám / tiêm / thẩm mỹ của catalog tra ra "không phải SURGERY" (400),
//      id không tồn tại → 404. Thứ tự #1: 404 Visit → 403 phạm vi / không phụ trách → 404 dịch vụ → 400 Visit không IN_PROGRESS → 400 dịch vụ không phải SURGERY / ngừng / chưa bật.
//  A5  Hoàn tất thêm dịch vụ vào Order qua `addServiceLineToVisitOrder` (nguồn đăng ký, mặc định mã dòng giả, KHÔNG sửa Order thật: xem clinical-shared.ts). `orderLineId` luôn có sau hoàn tất.
//  A6  Cùng một Visit có thể có nhiều phẫu thuật cùng lúc (hợp đồng không giới hạn). Hủy Visit tự hủy phẫu thuật (Phẫu thuật#6) không mô phỏng: chỉ lượt WAITING hủy được ở visit.mock, mà phẫu thuật cần lượt IN_PROGRESS.
//  A7  Không mô phỏng: audit (lập cam kết, hoàn tất, hủy), khóa Visit/đồng thời. Thông báo "lễ tân chi nhánh" khi lập kế hoạch: tin trong ứng dụng loại SURGERY_PLANNED (mẫu chưa có ở BE, chữ do mock đặt).
//  A8  Dữ liệu mẫu KHÔNG có phẫu thuật đang mở trên lượt thật nào (chỉ lịch sử COMPLETED / CANCELLED ở lượt đã xong và 1 PLANNED chi nhánh 2 trên lượt giả) để không chặn hoàn tất lượt 2002 của
//      các test GĐ1 (guard BR-KB-10 đã đăng ký vào visit-clinical.mock).
import type { Role } from '../../types/auth';
import type { Surgery, SurgerySummary, SurgeryStatus } from '../../types/surgery';
import { OPEN_SURGERY_STATUSES, SURGERY_LIMITS, SURGERY_STATUSES } from '../../types/surgery';
import { pushNotification } from './care.mock';
import { addServiceLineToVisitOrder, findClinicalService } from './clinical-shared';
import { petSummary } from './customer-pet.mock';
import { denied, fail, invalid, notFound, ok, ruleFail, scopeDenied, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { MOCK_ACCOUNTS, branchIdOf } from './identity.mock';
import { staffNameOf } from './identity-admin.mock';
import { clampPaging, pageOf, typeError } from './public.mock';
import { asLong, asText, conflict, idParam, isJsonObject, isReply, malformed, visitNotFound, visitRowOf } from './visit.mock';
import { registerCompletionGuard } from './visit-clinical.mock';

const MINUTE = 60_000;
const NAME = 'phẫu thuật';

const VIEW_ROLES: readonly Role[] = ['RECEPTIONIST', 'VET', 'CARETAKER'];
const LIST_ROLES: readonly Role[] = ['RECEPTIONIST', 'VET'];
const VET_ONLY: readonly Role[] = ['VET'];
const RECEPTION_ONLY: readonly Role[] = ['RECEPTIONIST'];
const CANCEL_ROLES: readonly Role[] = ['VET', 'RECEPTIONIST'];

// ─── Dữ liệu ───

interface SurgeryRow {
  surgeryId: number;
  visitId: number;
  petId: number;
  branchId: number;
  serviceId: number;
  status: SurgeryStatus;
  plannedBy: number;
  planNote: string | null;
  consentText: string | null;
  consentSignerName: string | null;
  consentSignerRelation: string | null;
  consentedBy: number | null;
  consentedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  resultNote: string | null;
  cancelReason: string | null;
  /** null = hệ thống (Phẫu thuật#6, mock không dùng). */
  cancelledBy: number | null;
  orderLineId: number | null;
  createdAt: string;
}
interface State {
  /** Mã phẫu thuật đã cấp gần nhất. */
  seq: number;
  surgeries: SurgeryRow[];
}

/** Giá trị mặc định khi nạp state lưu thiếu trường (T47: state cũ không được làm route mới nổ). */
const ROW_DEFAULTS = {
  planNote: null,
  consentText: null,
  consentSignerName: null,
  consentSignerRelation: null,
  consentedBy: null,
  consentedAt: null,
  startedAt: null,
  completedAt: null,
  resultNote: null,
  cancelReason: null,
  cancelledBy: null,
  orderLineId: null,
  createdAt: new Date(0).toISOString(),
} as const;

const iso = (ms: number) => new Date(ms).toISOString();

/** Lịch sử trên các lượt đã xong của visit.mock (2001 hoàn tất, 61 hủy) và 1 phẫu thuật chờ cam kết ở chi nhánh 2 (lượt giả) cho thử 403 phạm vi. Xem A8. */
function seed(now = Date.now()): State {
  const surgeries: SurgeryRow[] = [];
  const done = visitRowOf(2001);
  if (done) {
    const base = Date.parse(done.calledAt ?? done.checkedInAt);
    surgeries.push({
      surgeryId: 1,
      visitId: 2001,
      petId: done.petId,
      branchId: done.branchId,
      serviceId: 7001,
      status: 'COMPLETED',
      plannedBy: 5,
      planNote: 'Triệt sản mèo cái, gây mê hít',
      consentText: 'Rủi ro gây mê và chảy máu sau mổ; chi phí dự kiến 1.800.000 ₫; phương pháp mổ đường giữa bụng.',
      consentSignerName: 'Nguyễn Thu Trang',
      consentSignerRelation: 'Chủ thú',
      consentedBy: 4,
      consentedAt: iso(base + 10 * MINUTE),
      startedAt: iso(base + 15 * MINUTE),
      completedAt: iso(base + 35 * MINUTE),
      resultNote: 'Phẫu thuật thuận lợi, không biến chứng. Hẹn cắt chỉ sau 10 ngày.',
      cancelReason: null,
      cancelledBy: null,
      orderLineId: 5001,
      createdAt: iso(base + 5 * MINUTE),
    });
  }
  const cancelled = visitRowOf(61);
  if (cancelled) {
    const base = Date.parse(cancelled.calledAt ?? cancelled.checkedInAt);
    surgeries.push({
      ...ROW_DEFAULTS,
      surgeryId: 2,
      visitId: 61,
      petId: cancelled.petId,
      branchId: cancelled.branchId,
      serviceId: 7002,
      status: 'CANCELLED',
      plannedBy: 5,
      planNote: 'U mô mềm ở sườn, cần cắt bỏ',
      cancelReason: 'Khách cân nhắc chi phí, chưa đồng ý phẫu thuật',
      cancelledBy: 4,
      createdAt: iso(base + 5 * MINUTE),
    });
  }
  surgeries.push({
    ...ROW_DEFAULTS,
    surgeryId: 3,
    visitId: 9102,
    petId: 5,
    branchId: 2,
    serviceId: 7003,
    status: 'PLANNED',
    plannedBy: 9,
    planNote: 'Nhổ răng viêm tủy',
    createdAt: iso(now - 60 * MINUTE),
  });
  return { seq: 3, surgeries };
}

const STATE_KEY = 'petcare-mock-surgery';

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

/** Nạp state; thiếu bảng / thiếu trường thì gộp giá trị mặc định (và ghi lại ngay, vì dữ liệu mẫu tính theo lúc tạo). */
function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    const stored = raw ? (JSON.parse(raw) as Partial<State> | null) : null;
    if (stored && typeof stored === 'object') {
      const surgeries = Array.isArray(stored.surgeries) ? stored.surgeries.map((r) => ({ ...ROW_DEFAULTS, ...r })) : seed().surgeries;
      const seq = typeof stored.seq === 'number' ? stored.seq : surgeries.reduce((m, r) => Math.max(m, r.surgeryId), 0);
      const merged: State = { seq, surgeries };
      if (!Array.isArray(stored.surgeries) || typeof stored.seq !== 'number') save(merged);
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

const petNameOf = (petId: number) => petSummary(petId)?.name ?? `Thú cưng #${petId}`;
const serviceNameOf = (serviceId: number) => findClinicalService(serviceId)?.name ?? `Dịch vụ #${serviceId}`;

const view = (r: SurgeryRow): Surgery => ({
  surgeryId: r.surgeryId,
  visitId: r.visitId,
  petId: r.petId,
  petName: petNameOf(r.petId),
  branchId: r.branchId,
  serviceId: r.serviceId,
  serviceName: serviceNameOf(r.serviceId),
  status: r.status,
  planNote: r.planNote,
  consentText: r.consentText,
  consentSignerName: r.consentSignerName,
  consentSignerRelation: r.consentSignerRelation,
  consentedAt: r.consentedAt,
  startedAt: r.startedAt,
  completedAt: r.completedAt,
  resultNote: r.resultNote,
  cancelReason: r.cancelReason,
  orderLineId: r.orderLineId,
  createdAt: r.createdAt,
});

const summary = (r: SurgeryRow): SurgerySummary => ({
  surgeryId: r.surgeryId,
  visitId: r.visitId,
  petName: petNameOf(r.petId),
  serviceName: serviceNameOf(r.serviceId),
  status: r.status,
  createdAt: r.createdAt,
});

/** Cũ nhất trước (yaml #2, #3), trùng giờ thì mã nhỏ trước. */
const oldestFirst = (a: SurgeryRow, b: SurgeryRow) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.surgeryId - b.surgeryId;

// ─── Dùng chung ───

const me = (req: MockRequest) => req.account!;
const branchOf = (req: MockRequest) => branchIdOf(me(req).id) ?? null;
const allow = (roles: readonly Role[], req: MockRequest) => roles.includes(me(req).role);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const isRow = (x: SurgeryRow | MockReply): x is SurgeryRow => 'surgeryId' in x;

/** Phẫu thuật theo mã (404) rồi chi nhánh của người gọi (403 phạm vi). */
function surgeryFor(req: MockRequest, s: State, surgeryId: number): SurgeryRow | MockReply {
  const row = s.surgeries.find((x) => x.surgeryId === surgeryId);
  if (!row) return notFound(NAME, surgeryId);
  return row.branchId === branchOf(req) ? row : scopeDenied();
}

/** VET phụ trách Visit của phẫu thuật (A1): Visit không còn (lượt giả) hoặc người khác phụ trách → không phải. */
const isInCharge = (req: MockRequest, row: SurgeryRow) => visitRowOf(row.visitId)?.assigneeId === me(req).id;

/** Body là đối tượng JSON; trường chuỗi đọc như Jackson (chuỗi, số / boolean ép thành chuỗi; mảng, đối tượng → sai kiểu). */
function readTexts<K extends string>(body: unknown, keys: readonly K[]): Record<K, string | null> | MockReply {
  if (!isJsonObject(body)) return malformed();
  const out = {} as Record<K, string | null>;
  for (const k of keys) {
    const v = asText(body[k]);
    if (v === undefined) return malformed();
    out[k] = v;
  }
  return out;
}
const blank = (v: string | null) => v === null || v.trim() === '';
const tooLong = (v: string | null, max: number) => v !== null && v.length > max;

// ─── #1 Lập kế hoạch ───

function notifyReception(row: SurgeryRow, serviceName: string) {
  const pet = petNameOf(row.petId);
  const notice = {
    type: 'SURGERY_PLANNED',
    title: `Cần lập cam kết phẫu thuật cho ${pet}`,
    body: `${staffNameOf(row.plannedBy) ?? 'Bác sĩ'} đã lập kế hoạch ${serviceName} cho ${pet}. Vui lòng lập cam kết phẫu thuật với chủ thú.`,
    linkUrl: null,
  };
  for (const desk of MOCK_ACCOUNTS.filter((a) => a.role === 'RECEPTIONIST' && a.branchId === row.branchId)) pushNotification(desk.id, notice);
}

/** #1 (BR-KB-08): body → VET → độ dài → Visit 404 → chi nhánh / người phụ trách 403 → dịch vụ 404 → Visit không IN_PROGRESS 400 → dịch vụ không phải SURGERY / ngừng / chưa bật 400. */
const planSurgery: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!isJsonObject(req.body)) return malformed();
  const serviceId = asLong(req.body.serviceId);
  const texts = readTexts(req.body, ['planNote']);
  if (serviceId === undefined || isReply(texts)) return isReply(texts) ? texts : malformed();
  if (!allow(VET_ONLY, req)) return denied();
  const bad = invalid([
    ['serviceId', 'Thiếu dịch vụ phẫu thuật', serviceId === null],
    ['serviceId', 'Mã dịch vụ không hợp lệ', serviceId !== null && serviceId <= 0],
    ['planNote', `Ghi chú kế hoạch tối đa ${SURGERY_LIMITS.planNote} ký tự`, tooLong(texts.planNote, SURGERY_LIMITS.planNote)],
  ]);
  if (bad) return bad;

  const visit = visitRowOf(visitId);
  if (!visit) return visitNotFound(visitId);
  if (visit.branchId !== branchOf(req) || visit.assigneeId !== me(req).id) return scopeDenied();
  const service = findClinicalService(serviceId as number);
  if (!service) return notFound('dịch vụ', serviceId as number);
  if (visit.status !== 'IN_PROGRESS') return ruleFail('BR-KB-08', 'Chỉ lập kế hoạch phẫu thuật cho lượt đang khám');
  if (service.medicalType !== 'SURGERY') return ruleFail('BR-KB-08', 'Dịch vụ không phải dịch vụ phẫu thuật');
  if (!service.isActive || !service.enabledAt(visit.branchId)) return ruleFail('BR-KB-08', 'Dịch vụ phẫu thuật đã ngừng hoặc chưa bật tại chi nhánh');

  const s = load();
  const row: SurgeryRow = {
    ...ROW_DEFAULTS,
    surgeryId: s.seq + 1,
    visitId,
    petId: visit.petId,
    branchId: visit.branchId,
    serviceId: service.serviceId,
    status: 'PLANNED',
    plannedBy: me(req).id,
    planNote: texts.planNote?.trim() || null,
    createdAt: iso(Date.now()),
  };
  s.seq = row.surgeryId;
  s.surgeries.push(row);
  save(s);
  notifyReception(row, service.name);
  return ok(view(row), 201, 'Đã lập kế hoạch phẫu thuật');
};

// ─── #2, #3, #4 Xem ───

/** #2: lễ tân, VET, CARETAKER; path → vai trò → Visit 404 → chi nhánh 403. Mảng cũ nhất trước. */
const listVisitSurgeries: MockHandler = (req) => {
  const visitId = idParam(req, 'visitId');
  if (isReply(visitId)) return visitId;
  if (!allow(VIEW_ROLES, req)) return denied();
  const visit = visitRowOf(visitId);
  if (!visit) return visitNotFound(visitId);
  if (visit.branchId !== branchOf(req)) return scopeDenied();
  return ok(
    load()
      .surgeries.filter((x) => x.visitId === visitId)
      .sort(oldestFirst)
      .map(view),
  );
};

/** #3: lễ tân, VET; query sai kiểu → vai trò. `status` vắng = PLANNED + CONSENTED + IN_PROGRESS. Cũ nhất trước, phân trang. */
const listSurgeries: MockHandler = (req) => {
  const { status } = req.query;
  if (status !== undefined && !(SURGERY_STATUSES as readonly string[]).includes(status)) return badParam('status');
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  if (!allow(LIST_ROLES, req)) return denied();
  const found = load()
    .surgeries.filter((x) => x.branchId === branchOf(req) && (status === undefined ? OPEN_SURGERY_STATUSES.includes(x.status) : x.status === status))
    .sort(oldestFirst);
  return ok(pageOf(found.map(summary), clampPaging(req.query)));
};

/** #4: lễ tân, VET, CARETAKER; path → vai trò → 404 → chi nhánh 403. */
const getSurgery: MockHandler = (req) => {
  const surgeryId = idParam(req, 'surgeryId');
  if (isReply(surgeryId)) return surgeryId;
  if (!allow(VIEW_ROLES, req)) return denied();
  const row = surgeryFor(req, load(), surgeryId);
  return isRow(row) ? ok(view(row)) : row;
};

// ─── #5 Cam kết ───

/** #5 (BR-KB-09): body → lễ tân → độ dài → 404 → chi nhánh 403 → thiếu nội dung / người ký 400 → không PLANNED 409. */
const consentSurgery: MockHandler = (req) => {
  const surgeryId = idParam(req, 'surgeryId');
  if (isReply(surgeryId)) return surgeryId;
  const texts = readTexts(req.body, ['consentText', 'signerName', 'signerRelation']);
  if (isReply(texts)) return texts;
  if (!allow(RECEPTION_ONLY, req)) return denied();
  const bad = invalid([
    ['signerName', `Họ tên người ký tối đa ${SURGERY_LIMITS.signerName} ký tự`, tooLong(texts.signerName, SURGERY_LIMITS.signerName)],
    ['signerRelation', `Quan hệ với chủ thú tối đa ${SURGERY_LIMITS.signerRelation} ký tự`, tooLong(texts.signerRelation, SURGERY_LIMITS.signerRelation)],
  ]);
  if (bad) return bad;

  const s = load();
  const row = surgeryFor(req, s, surgeryId);
  if (!isRow(row)) return row;
  if (blank(texts.consentText) || blank(texts.signerName) || blank(texts.signerRelation)) {
    return ruleFail('BR-KB-09', 'Cam kết phải có nội dung, họ tên và quan hệ của người ký');
  }
  if (row.status !== 'PLANNED') return conflict(NAME, row.status, 'CONSENTED');
  Object.assign(row, {
    status: 'CONSENTED',
    consentText: texts.consentText!.trim(),
    consentSignerName: texts.signerName!.trim(),
    consentSignerRelation: texts.signerRelation!.trim(),
    consentedBy: me(req).id,
    consentedAt: iso(Date.now()),
  });
  save(s);
  return ok(view(row), 200, 'Đã lập cam kết phẫu thuật');
};

// ─── #6 Bắt đầu, #7 Hoàn tất ───

/** #6 (BR-KB-09, 10): VET → 404 → chi nhánh / không phụ trách 403 → PLANNED (chưa cam kết) hoặc Visit không IN_PROGRESS 400 BR-KB-09 → không CONSENTED 409. */
const startSurgery: MockHandler = (req) => {
  const surgeryId = idParam(req, 'surgeryId');
  if (isReply(surgeryId)) return surgeryId;
  if (!allow(VET_ONLY, req)) return denied();
  const s = load();
  const row = surgeryFor(req, s, surgeryId);
  if (!isRow(row)) return row;
  if (!isInCharge(req, row)) return scopeDenied();
  if (row.status === 'PLANNED') return ruleFail('BR-KB-09', 'Phẫu thuật chưa có cam kết của khách');
  if (row.status === 'CONSENTED' && visitRowOf(row.visitId)?.status !== 'IN_PROGRESS') return ruleFail('BR-KB-09', 'Lượt khám không còn đang khám, không bắt đầu phẫu thuật được');
  if (row.status !== 'CONSENTED') return conflict(NAME, row.status, 'IN_PROGRESS');
  Object.assign(row, { status: 'IN_PROGRESS', startedAt: iso(Date.now()) });
  save(s);
  return ok(view(row), 200, 'Đã bắt đầu phẫu thuật');
};

/** #7 (BR-KB-10): body → VET → 404 → chi nhánh / không phụ trách 403 → thiếu ghi chú kết quả 400 → không IN_PROGRESS 409. Thêm dịch vụ vào Order của Visit (A5). */
const completeSurgery: MockHandler = (req) => {
  const surgeryId = idParam(req, 'surgeryId');
  if (isReply(surgeryId)) return surgeryId;
  const texts = readTexts(req.body, ['resultNote']);
  if (isReply(texts)) return texts;
  if (!allow(VET_ONLY, req)) return denied();
  const s = load();
  const row = surgeryFor(req, s, surgeryId);
  if (!isRow(row)) return row;
  if (!isInCharge(req, row)) return scopeDenied();
  if (blank(texts.resultNote)) return ruleFail('BR-KB-10', 'Phải ghi chú kết quả phẫu thuật (biến chứng nếu có) trước khi hoàn tất');
  if (row.status !== 'IN_PROGRESS') return conflict(NAME, row.status, 'COMPLETED');
  const lineId = addServiceLineToVisitOrder(row.visitId, row.serviceId, 1, me(req).id);
  if (lineId === null) return fail('INTERNAL_ERROR', `Lượt #${row.visitId} không có Order đang mở`, 500);
  Object.assign(row, { status: 'COMPLETED', resultNote: texts.resultNote!.trim(), completedAt: iso(Date.now()), orderLineId: lineId });
  save(s);
  return ok(view(row), 200, 'Đã hoàn tất phẫu thuật');
};

// ─── #8 Hủy hoặc dừng ───

/** #8 (BR-KB-11): body → VET, lễ tân → độ dài → 404 → chi nhánh 403 → VET không phụ trách 403 / lễ tân khi IN_PROGRESS 403 ACCESS_DENIED → thiếu lý do 400 → đã COMPLETED / CANCELLED 409. */
const cancelSurgery: MockHandler = (req) => {
  const surgeryId = idParam(req, 'surgeryId');
  if (isReply(surgeryId)) return surgeryId;
  const texts = readTexts(req.body, ['reason']);
  if (isReply(texts)) return texts;
  if (!allow(CANCEL_ROLES, req)) return denied();
  const bad = invalid([['reason', `Lý do tối đa ${SURGERY_LIMITS.reason} ký tự`, tooLong(texts.reason, SURGERY_LIMITS.reason)]]);
  if (bad) return bad;
  const s = load();
  const row = surgeryFor(req, s, surgeryId);
  if (!isRow(row)) return row;
  if (me(req).role === 'VET') {
    if (!isInCharge(req, row)) return scopeDenied();
  } else if (row.status === 'IN_PROGRESS') {
    return denied();
  }
  if (blank(texts.reason)) return ruleFail('BR-KB-11', 'Phải nhập lý do hủy hoặc dừng phẫu thuật');
  if (row.status === 'COMPLETED' || row.status === 'CANCELLED') return conflict(NAME, row.status, 'CANCELLED');
  Object.assign(row, { status: 'CANCELLED', cancelReason: texts.reason!.trim(), cancelledBy: me(req).id });
  save(s);
  return ok(view(row), 200, 'Đã hủy phẫu thuật');
};

// ─── Cho mock khác ───

/** Phẫu thuật còn mở (PLANNED / CONSENTED / IN_PROGRESS) của lượt: guard BR-KB-10 ở hoàn tất lượt (visit-v1 #10). */
export const openSurgeriesOf = (visitId: number): number =>
  load().surgeries.filter((x) => x.visitId === visitId && OPEN_SURGERY_STATUSES.includes(x.status)).length;

registerCompletionGuard('surgery', (visitId) =>
  openSurgeriesOf(visitId) > 0 ? ruleFail('BR-KB-10', 'Lượt còn phẫu thuật chưa kết thúc (chờ cam kết, đã có cam kết hoặc đang phẫu thuật), chưa hoàn tất được') : null,
);

export const surgeryRoutes: Record<string, MockHandler> = {
  'POST /api/visits/:visitId/surgeries': planSurgery,
  'GET /api/visits/:visitId/surgeries': listVisitSurgeries,
  'GET /api/surgeries': listSurgeries,
  'GET /api/surgeries/:surgeryId': getSurgery,
  'POST /api/surgeries/:surgeryId/consent': consentSurgery,
  'POST /api/surgeries/:surgeryId/start': startSurgery,
  'POST /api/surgeries/:surgeryId/complete': completeSurgery,
  'POST /api/surgeries/:surgeryId/cancel': cancelSurgery,
};
