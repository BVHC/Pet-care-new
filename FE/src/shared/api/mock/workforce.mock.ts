// Mock workforce-v1 (UC34–38; docs/api/workforce-v1.md #1–14): ca làm việc, phân ca, lịch của tôi, yêu cầu nghỉ / đổi ca, duyệt, vắng đột xuất.
// BE tầng 3 CHƯA cài (T58–T59), nên chưa có số đo thật: mock chép hợp đồng + BR-NS-01…10 + 03 #11, #12 + V36 (CHECK của bảng). Chỗ im lặng là ASSUMPTION của T57:
//  W1  Thứ tự kiểm (00-method §3.5 + bài học report A1; CHƯA đo trên BE): 401 → 400 MALFORMED_REQUEST (path / query sai kiểu hoặc thiếu `from`, `to`; body vắng,
//      không phải JSON object, trường sai kiểu / ngoài enum, ngày không có thật) → 403 vai trò → 400 VALIDATION_FAILED (thiếu / trống / quá dài / sai dạng `HH:mm`)
//      → 404 → 403 phạm vi (ACCESS_DENIED_SCOPE_MISMATCH, câu chung) → 400 BR-NS-… → 409 INVALID_STATE_TRANSITION. Id của chi nhánh khác: 404 nếu không có, 403 phạm
//      vi nếu có (như care.mock); riêng tra theo chi nhánh của người gọi (nhân viên / ca khi phân ca) thì "không thấy" = 404 như yaml. 400 BR đến TRƯỚC 409 theo
//      00-method: yêu cầu đã duyệt mà phân ca đã bắt đầu duyệt lại ra 400 BR-NS-06, không phải 409.
//  W2  Vai trò: #1–7, #12–14 chỉ BRANCH_MANAGER; #8–11 chỉ A06–A08 (RECEPTIONIST, VET, CARETAKER). SUPER_MANAGER, ADMIN, CUSTOMER → 403 mọi endpoint ("SUPER_MANAGER không có
//      chức năng nhân sự", D1); BRANCH_MANAGER gọi `/me/…` → 403 (Q3 TBD, UC35–36 chỉ nêu A06–A08). Phân ca / yêu cầu của người khác ở `/me/…` → 404 (D2).
//  W3  BR-NS-03: khoảng ngày tính CẢ HAI ĐẦU, tối đa 62 ngày (to − from ≤ 61, đủ hai tháng 31 ngày); `to` < `from` → 400 BR-NS-03. `from`, `to` thiếu → 400 MALFORMED_REQUEST
//      (adapter bỏ giá trị rỗng nên `from=` cũng là "Thiếu tham số"). `staffId` / `status` ở #4 và `status` ở #10, #12 ngoài kiểu / enum → MALFORMED_REQUEST.
//  W4  "Đã bắt đầu" = bây giờ ≥ `workDate` + `startTime` (giờ VN). Hủy (#6) chỉ trước giờ đó; vắng (#7) tính theo NGÀY: `workDate` ≤ hôm nay (ngày tương lai → 400 BR-NS-07) và
//      hôm nay − `workDate` ≤ 7 ngày [CFG] `workforce.absence_report_max_days` (mock không đọc cấu hình: 7). Gửi yêu cầu cần startTime − bây giờ ≥ 24 giờ [CFG]
//      `workforce.request_lead_hours` (đúng 24 giờ là được; ca đã bắt đầu cũng bị từ chối bằng cùng câu).
//  W5  SWAP: ca đích là MỘT CA (không phải phân ca) áp vào cùng ngày nên "khác ngày" không xảy ra. Ca đích không có / thuộc chi nhánh khác → 404; ngừng dùng, trùng ca hiện tại,
//      chồng giờ phân ca SCHEDULED khác của người gửi cùng ngày (chạm đầu mút không tính là chồng) → 400 BR-NS-04; thiếu ca đích (SWAP) hoặc thừa (LEAVE) → 400 BR-NS-04.
//      Lúc duyệt kiểm lại ca đích còn dùng và không chồng giờ (400 BR-NS-06).
//  W6  Ca (#2, #3): giờ `HH:mm` hợp lệ là 00:00–23:59 (pattern yaml cho phép giờ 24–29, mock từ chối bằng VALIDATION_FAILED); tên trùng so không phân biệt hoa thường (có dấu),
//      tính cả ca ngừng dùng (unique index V36); #3 gửi giờ bằng giá trị hiện tại thì coi như không đổi (không bị chặn bởi "ca đã có phân ca"). Thứ tự kiểm BR-NS-01 theo yaml:
//      tên trùng → giờ (bắt đầu < kết thúc) → sửa giờ khi đã có phân ca → ngừng dùng khi còn phân ca SCHEDULED từ hôm nay.
//  W7  Thông báo (BR-NS-08) qua `pushNotification` của care.mock, IN_APP, mã mẫu và chữ chép V40 (bỏ hậu tố `_APP`), `linkUrl` null (FE chưa có trang): yêu cầu mới → mọi
//      BRANCH_MANAGER ACTIVE của chi nhánh; duyệt / từ chối / hủy phân ca / vắng → nhân viên. EMAIL không mô phỏng. KHÔNG thông báo khi phân ca (BR-NS-08 không liệt kê,
//      V40 không có mẫu, dù 03 #12 dòng 1 có ghi "Thông báo nhân viên"). Yêu cầu tự bị từ chối (Yêu cầu#5) không có thông báo riêng.
//  W8  Không ghi audit (BR-NS-10; không mock GĐ1 nào ghi audit cho thao tác BRANCH_MANAGER). BR-NS-09 (nhân viên bị vô hiệu hóa / điều chuyển) là hệ quả hệ thống: mock export
//      `staffLeftBranch(staffId, branchId)` cho luồng chính nối với identity-admin.mock (Q1 TBD); chưa ai gọi nó.
//  W9  `ShiftRequest.currentShiftName` suy từ phân ca HIỆN TẠI (schema V36 không có cột chụp lại), nên yêu cầu SWAP đã duyệt hiện ca mới ở cả hai tên. Nhân viên bị khóa
//      (`staffSummaryOf().active` = false) coi như không ACTIVE khi phân ca.
//  W10 Dữ liệu mẫu sinh MỘT LẦN lúc nạp đầu, tính theo `vnDate()` của lúc đó: branch 1 có 5 ca (một ngừng dùng) và lịch −7…+10 ngày cho nhân viên 4, 5, 6, 21 (mỗi ngày: ca sáng /
//      ca chiều / nghỉ luân phiên), kèm 2 yêu cầu PENDING (nhân viên 5 xin nghỉ +3 ngày, nhân viên 6 xin đổi ca +4 ngày), 4 yêu cầu đã đóng và các phân ca ON_LEAVE / ABSENT /
//      CANCELLED; branch 2 chỉ có 2 ca, chưa có nhân viên. State cũ thiếu bảng nào thì bảng đó lấy dữ liệu mẫu; view chịu được tham chiếu mồ côi.
import type { Role } from '../../types/auth';
import type {
  ShiftAssignment,
  ShiftAssignmentStatus,
  ShiftDefinition,
  ShiftRequest,
  ShiftRequestStatus,
  ShiftRequestType,
} from '../../types/workforce';
import { SHIFT_ASSIGNMENT_STATUSES, SHIFT_REQUEST_STATUSES, SHIFT_REQUEST_TYPES, WORKFORCE_LIMITS } from '../../types/workforce';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { branchBooking } from './branch.mock';
import { pushNotification } from './care.mock';
import { blank, fail, has, isText, notFound, ok, ruleFail, scopeDenied, tooLong, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { assignableStaffOf, staffNameOf, staffSummaryOf } from './identity-admin.mock';
import { branchIdOf } from './identity.mock';
import { PAGING, flagOf, guarded, textChecks } from './people-guard';
import { clampPaging, pageOf } from './public.mock';

/** [CFG] `workforce.request_lead_hours` và `workforce.absence_report_max_days` (seed V40); mock không đọc cấu hình. */
const REQUEST_LEAD_HOURS = 24;
const ABSENCE_MAX_DAYS = 7;
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const BM: readonly Role[] = ['BRANCH_MANAGER'];
const STAFF: readonly Role[] = ['RECEPTIONIST', 'VET', 'CARETAKER'];
const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

// ─── State ───

type DefinitionRow = ShiftDefinition;
interface AssignmentRow {
  shiftAssignmentId: number;
  branchId: number;
  staffId: number;
  shiftDefinitionId: number;
  workDate: string;
  status: ShiftAssignmentStatus;
  reason: string | null;
  statusChangedBy: number | null;
  statusChangedAt: string | null;
}
interface RequestRow {
  shiftRequestId: number;
  shiftAssignmentId: number;
  requesterId: number;
  type: ShiftRequestType;
  targetShiftDefinitionId: number | null;
  reason: string;
  status: ShiftRequestStatus;
  decidedBy: number | null;
  decisionNote: string | null;
  closedAt: string | null;
  createdAt: string;
}
interface State {
  definitions: DefinitionRow[];
  assignments: AssignmentRow[];
  requests: RequestRow[];
}

const STATE_KEY = 'petcare-mock-workforce';

function seed(): State {
  const now = Date.now();
  const today = vnDate(now);
  const day = (offset: number) => addDays(today, offset);
  const ago = (hours: number) => new Date(now - hours * HOUR).toISOString();

  const shift = (shiftDefinitionId: number, branchId: number, name: string, startTime: string, endTime: string, active = true): DefinitionRow => ({
    shiftDefinitionId, branchId, name, startTime, endTime, active,
  });
  const definitions = [
    shift(1, 1, 'Ca sáng', '07:30', '12:00'),
    shift(2, 1, 'Ca chiều', '13:00', '17:30'),
    shift(3, 1, 'Ca hành chính', '08:00', '17:00'),
    shift(4, 1, 'Ca tối', '17:30', '21:00'),
    shift(5, 1, 'Ca đêm cũ', '21:00', '23:00', false),
    shift(6, 2, 'Ca sáng', '08:00', '12:00'),
    shift(7, 2, 'Ca chiều', '13:00', '17:00'),
  ];

  const assignments: AssignmentRow[] = [];
  /** Mỗi nhân viên một phân ca mỗi ngày: gọi lại cùng (nhân viên, ngày) thì thay. */
  const assign = (staffId: number, offset: number, shiftDefinitionId: number, extra: Partial<AssignmentRow> = {}): AssignmentRow => {
    const workDate = day(offset);
    const at = assignments.findIndex((a) => a.staffId === staffId && a.workDate === workDate);
    if (at >= 0) assignments.splice(at, 1);
    const row: AssignmentRow = {
      shiftAssignmentId: Math.max(0, ...assignments.map((a) => a.shiftAssignmentId)) + 1,
      branchId: 1, staffId, shiftDefinitionId, workDate, status: 'SCHEDULED', reason: null, statusChangedBy: null, statusChangedAt: null, ...extra,
    };
    assignments.push(row);
    return row;
  };
  for (const staffId of [4, 5, 6, 21]) {
    for (let offset = -7; offset <= 10; offset++) {
      const turn = (((offset + staffId) % 3) + 3) % 3; // 0 nghỉ, 1 ca sáng, 2 ca chiều
      if (turn > 0) assign(staffId, offset, turn);
    }
  }

  const requests: RequestRow[] = [];
  const request = (a: AssignmentRow, r: Pick<RequestRow, 'type' | 'status' | 'reason' | 'createdAt'> & Partial<RequestRow>) => {
    requests.push({
      shiftRequestId: requests.length + 1, shiftAssignmentId: a.shiftAssignmentId, requesterId: a.staffId,
      targetShiftDefinitionId: null, decidedBy: null, decisionNote: null, closedAt: null, ...r,
    });
  };
  // Quản lý chi nhánh 1 là tài khoản 3.
  request(assign(5, 3, 2), { type: 'LEAVE', status: 'PENDING', reason: 'Đưa mẹ đi khám bệnh, xin nghỉ ca chiều.', createdAt: ago(26) });
  request(assign(6, 4, 1), { type: 'SWAP', targetShiftDefinitionId: 2, status: 'PENDING', reason: 'Sáng hôm đó có buổi họp phụ huynh, xin đổi sang ca chiều.', createdAt: ago(5) });
  request(assign(4, 5, 1, { status: 'ON_LEAVE', statusChangedBy: 3, statusChangedAt: ago(20) }), {
    type: 'LEAVE', status: 'APPROVED', reason: 'Việc gia đình.', decidedBy: 3, closedAt: ago(20), createdAt: ago(48),
  });
  request(assign(21, 6, 2), {
    type: 'LEAVE', status: 'REJECTED', reason: 'Xin nghỉ đi du lịch.', decidedBy: 3, closedAt: ago(30), createdAt: ago(52),
    decisionNote: 'Ca chiều hôm đó đã đông lịch hẹn, vui lòng chọn ngày khác.',
  });
  request(assign(5, 7, 1), { type: 'SWAP', targetShiftDefinitionId: 2, status: 'WITHDRAWN', reason: 'Xin đổi sang ca chiều.', closedAt: ago(40), createdAt: ago(60) });
  request(assign(4, 8, 2, { status: 'CANCELLED', reason: 'Chi nhánh đóng cửa sớm để bảo trì điện.', statusChangedBy: 3, statusChangedAt: ago(10) }), {
    type: 'LEAVE', status: 'REJECTED', reason: 'Xin nghỉ nửa ngày.', decisionNote: 'Phân ca đã bị hủy.', closedAt: ago(10), createdAt: ago(30),
  });
  // Lịch sử gần đây để thử vắng đột xuất: hôm qua, 2 ngày, 3 ngày trước còn trong hạn 7 ngày; 9 ngày trước đã quá hạn.
  assign(4, -2, 1, { status: 'ABSENT', reason: 'Nghỉ ốm không báo trước.', statusChangedBy: 3, statusChangedAt: ago(30) });
  assign(5, -1, 1);
  assign(6, -3, 2);
  assign(21, -9, 1);

  return { definitions, assignments, requests };
}

function load(): State {
  let stored: Partial<State> = {};
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(STATE_KEY) ?? 'null');
    if (raw && typeof raw === 'object') stored = raw as Partial<State>;
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  if (Array.isArray(stored.definitions) && Array.isArray(stored.assignments) && Array.isArray(stored.requests)) return stored as State;
  // Gộp từng bảng còn thiếu và ghi ngay: thời gian mẫu tính theo lúc tạo, đọc lại phải ra cùng giá trị.
  const seeded = seed();
  const merged: State = {
    definitions: Array.isArray(stored.definitions) ? stored.definitions : seeded.definitions,
    assignments: Array.isArray(stored.assignments) ? stored.assignments : seeded.assignments,
    requests: Array.isArray(stored.requests) ? stored.requests : seeded.requests,
  };
  save(merged);
  return merged;
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Tiện ích ───

const branchOf = (req: MockRequest) => req.account!.branchId ?? branchIdOf(req.account!.id) ?? -1;
const me = (req: MockRequest) => req.account!.id;
const idOf = (req: MockRequest, name: string) => Number(req.params[name]);
const nextId = (ids: number[]) => Math.max(0, ...ids) + 1;
const defOf = (s: State, id: number) => s.definitions.find((d) => d.shiftDefinitionId === id);
const assignmentOf = (s: State, id: number) => s.assignments.find((a) => a.shiftAssignmentId === id);
const isReply = (x: unknown): x is MockReply => typeof (x as MockReply).status === 'number';
const dmy = (iso: string) => iso.split('-').reverse().join('/');
const daysBetween = (from: string, to: string) => (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY;
const stateError = (what: string, from: string, to: string) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái ${what} từ ${from} sang ${to}`, 409);
const branchName = (branchId: number) => branchBooking(branchId)?.branch.name ?? `Chi nhánh #${branchId}`;

const startMs = (s: State, a: AssignmentRow) => Date.parse(`${a.workDate}T${defOf(s, a.shiftDefinitionId)?.startTime ?? '00:00'}:00+07:00`);
const hasStarted = (s: State, a: AssignmentRow) => Date.now() >= startMs(s, a);
const overlaps = (a: { startTime: string; endTime: string }, b: { startTime: string; endTime: string }) => a.startTime < b.endTime && b.startTime < a.endTime;
const range = (d: { startTime: string; endTime: string }) => `${d.startTime}–${d.endTime}`;

/** Phân ca SCHEDULED của nhân viên trong ngày chồng giờ với `shift` (bỏ qua `exceptId`); chạm đầu mút không tính. */
function clash(s: State, staffId: number, workDate: string, shift: DefinitionRow, exceptId?: number) {
  for (const a of s.assignments) {
    const d = defOf(s, a.shiftDefinitionId);
    if (d && a.staffId === staffId && a.workDate === workDate && a.status === 'SCHEDULED' && a.shiftAssignmentId !== exceptId && overlaps(d, shift)) return d;
  }
  return undefined;
}

function badRange(from: string, to: string): MockReply | null {
  if (to < from) return ruleFail('BR-NS-03', 'Ngày kết thúc phải từ ngày bắt đầu trở đi');
  if (daysBetween(from, to) + 1 > WORKFORCE_LIMITS.rangeDays) return ruleFail('BR-NS-03', `Khoảng ngày xem lịch tối đa ${WORKFORCE_LIMITS.rangeDays} ngày`);
  return null;
}

const nameTaken = (s: State, branchId: number, name: string, exceptId?: number) =>
  s.definitions.some((d) => d.branchId === branchId && d.shiftDefinitionId !== exceptId && d.name.toLowerCase() === name.toLowerCase());

/** Yêu cầu PENDING của phân ca tự bị từ chối (Yêu cầu#5): hệ thống ghi lý do, `decidedBy` rỗng. */
function rejectPendingOf(s: State, shiftAssignmentId: number, note: string) {
  const pending = s.requests.find((r) => r.shiftAssignmentId === shiftAssignmentId && r.status === 'PENDING');
  if (pending) Object.assign(pending, { status: 'REJECTED', decidedBy: null, decisionNote: note, closedAt: new Date().toISOString() });
}

// ─── Thông báo (BR-NS-08, mẫu V40) ───

const notify = (accountIds: number[], type: string, title: string, body: string) => {
  for (const accountId of accountIds) pushNotification(accountId, { type, title, body, linkUrl: null });
};
const managersOf = (branchId: number) => assignableStaffOf(branchId, 'BRANCH_MANAGER').map((m) => m.accountId);

// ─── View ───

const assignmentView = (s: State, a: AssignmentRow): ShiftAssignment => {
  const d = defOf(s, a.shiftDefinitionId);
  return {
    shiftAssignmentId: a.shiftAssignmentId,
    branchId: a.branchId,
    staffId: a.staffId,
    staffName: staffNameOf(a.staffId) ?? `Nhân viên #${a.staffId}`,
    shiftDefinitionId: a.shiftDefinitionId,
    shiftName: d?.name ?? `Ca #${a.shiftDefinitionId}`,
    startTime: d?.startTime ?? '00:00',
    endTime: d?.endTime ?? '00:00',
    workDate: a.workDate,
    status: a.status,
    reason: a.reason,
    pendingRequestId: s.requests.find((r) => r.shiftAssignmentId === a.shiftAssignmentId && r.status === 'PENDING')?.shiftRequestId ?? null,
  };
};

const requestView = (s: State, r: RequestRow): ShiftRequest => {
  const a = assignmentOf(s, r.shiftAssignmentId);
  return {
    shiftRequestId: r.shiftRequestId,
    shiftAssignmentId: r.shiftAssignmentId,
    requesterId: r.requesterId,
    requesterName: staffNameOf(r.requesterId) ?? `Nhân viên #${r.requesterId}`,
    workDate: a?.workDate ?? '',
    currentShiftName: (a && defOf(s, a.shiftDefinitionId)?.name) ?? '',
    type: r.type,
    targetShiftDefinitionId: r.targetShiftDefinitionId,
    targetShiftName: r.targetShiftDefinitionId == null ? null : (defOf(s, r.targetShiftDefinitionId)?.name ?? `Ca #${r.targetShiftDefinitionId}`),
    reason: r.reason,
    status: r.status,
    decidedBy: r.decidedBy,
    decisionNote: r.decisionNote,
    closedAt: r.closedAt,
    createdAt: r.createdAt,
  };
};

const byDateThenTime = (s: State) => (a: AssignmentRow, b: AssignmentRow) =>
  a.workDate.localeCompare(b.workDate) ||
  (defOf(s, a.shiftDefinitionId)?.startTime ?? '').localeCompare(defOf(s, b.shiftDefinitionId)?.startTime ?? '') ||
  a.shiftAssignmentId - b.shiftAssignmentId;

/** Mô tả yêu cầu cho thông báo (`mo_ta_yeu_cau`), tính TRƯỚC khi đổi phân ca. */
function describeRequest(s: State, r: RequestRow): string {
  const a = assignmentOf(s, r.shiftAssignmentId);
  const current = (a && defOf(s, a.shiftDefinitionId)?.name) ?? '';
  const date = a ? ` ngày ${dmy(a.workDate)}` : '';
  return r.type === 'LEAVE'
    ? `nghỉ ca ${current}${date}`
    : `đổi ca ${current} sang ca ${(r.targetShiftDefinitionId != null && defOf(s, r.targetShiftDefinitionId)?.name) || ''}${date}`;
}

// ─── Ca làm việc (#1–3, UC34, BR-NS-01) ───

const timeChecks = (field: string, label: string, value: unknown, required: boolean): [string, string, boolean][] => [
  [field, `${label} không được để trống`, required && !isText(value)],
  // Có gửi mà toàn khoảng trắng: bắt buộc thì đã báo "trống" ở trên, tùy chọn (PATCH) thì là sai dạng.
  [field, `${label} phải có dạng HH:mm (00:00–23:59)`, has(value) && (isText(value) ? !TIME.test(value) : !required)],
];

const listShiftDefinitions = guarded({ roles: BM, query: [['activeOnly', 'bool']] }, (req) => {
  const activeOnly = flagOf(req.query.activeOnly) === true;
  const rows = load()
    .definitions.filter((d) => d.branchId === branchOf(req) && (!activeOnly || d.active))
    .sort((a, b) => a.startTime.localeCompare(b.startTime) || a.shiftDefinitionId - b.shiftDefinitionId);
  return ok(rows);
});

const createShiftDefinition = guarded(
  {
    roles: BM,
    body: { name: 'text', startTime: 'text', endTime: 'text' },
    checks: (b) => [
      ...textChecks('name', 'Tên ca', b.name, WORKFORCE_LIMITS.name),
      ...timeChecks('startTime', 'Giờ bắt đầu', b.startTime, true),
      ...timeChecks('endTime', 'Giờ kết thúc', b.endTime, true),
    ],
  },
  (req, b) => {
    const s = load();
    const name = String(b.name).trim();
    if (nameTaken(s, branchOf(req), name)) return ruleFail('BR-NS-01', `Tên ca "${name}" đã tồn tại trong chi nhánh`);
    if (String(b.startTime) >= String(b.endTime)) return ruleFail('BR-NS-01', 'Giờ bắt đầu phải nhỏ hơn giờ kết thúc');
    const created: DefinitionRow = {
      shiftDefinitionId: nextId(s.definitions.map((d) => d.shiftDefinitionId)),
      branchId: branchOf(req),
      name,
      startTime: String(b.startTime),
      endTime: String(b.endTime),
      active: true,
    };
    s.definitions.push(created);
    save(s);
    return ok(created, 201, 'Đã tạo ca làm việc');
  },
);

const updateShiftDefinition = guarded(
  {
    roles: BM,
    params: ['shiftDefinitionId'],
    body: { name: 'text', startTime: 'text', endTime: 'text', active: 'bool' },
    checks: (b) => [
      ['name', 'Tên ca không được để trống', blank(b.name)],
      ['name', `Tên ca tối đa ${WORKFORCE_LIMITS.name} ký tự`, tooLong(b.name, WORKFORCE_LIMITS.name)],
      ...timeChecks('startTime', 'Giờ bắt đầu', b.startTime, false),
      ...timeChecks('endTime', 'Giờ kết thúc', b.endTime, false),
    ],
  },
  (req, b) => {
    const s = load();
    const id = idOf(req, 'shiftDefinitionId');
    const d = defOf(s, id);
    if (!d) return notFound('ca làm việc', id);
    if (d.branchId !== branchOf(req)) return scopeDenied();
    const name = has(b.name) ? String(b.name).trim() : d.name;
    const startTime = has(b.startTime) ? String(b.startTime) : d.startTime;
    const endTime = has(b.endTime) ? String(b.endTime) : d.endTime;
    if (nameTaken(s, d.branchId, name, id)) return ruleFail('BR-NS-01', `Tên ca "${name}" đã tồn tại trong chi nhánh`);
    if (startTime >= endTime) return ruleFail('BR-NS-01', 'Giờ bắt đầu phải nhỏ hơn giờ kết thúc');
    if ((startTime !== d.startTime || endTime !== d.endTime) && s.assignments.some((a) => a.shiftDefinitionId === id)) {
      return ruleFail('BR-NS-01', 'Ca đã có phân ca nên không sửa được giờ; hãy tạo ca mới');
    }
    if (b.active === false && s.assignments.some((a) => a.shiftDefinitionId === id && a.status === 'SCHEDULED' && a.workDate >= vnDate())) {
      return ruleFail('BR-NS-01', 'Ca còn phân ca chưa làm từ hôm nay nên chưa ngừng dùng được');
    }
    Object.assign(d, { name, startTime, endTime, active: has(b.active) ? (b.active as boolean) : d.active });
    save(s);
    return ok(d, 200, 'Đã cập nhật ca làm việc');
  },
);

// ─── Phân ca (#4–8, UC34, UC35, UC38, BR-NS-02, 03, 07) ───

const REQUIRED_RANGE = [['from', 'date', true], ['to', 'date', true]] as const;

const listShiftAssignments = guarded(
  { roles: BM, query: [...REQUIRED_RANGE, ['staffId', 'int'], ['status', SHIFT_ASSIGNMENT_STATUSES], ...PAGING] },
  (req) => {
    const { from, to, staffId, status } = req.query;
    const bad = badRange(from, to);
    if (bad) return bad;
    const s = load();
    const rows = s.assignments
      .filter(
        (a) =>
          a.branchId === branchOf(req) &&
          a.workDate >= from &&
          a.workDate <= to &&
          (staffId === undefined || a.staffId === Number(staffId)) &&
          (status === undefined || a.status === status),
      )
      .sort(byDateThenTime(s));
    return ok(pageOf(rows.map((a) => assignmentView(s, a)), clampPaging(req.query)));
  },
);

const createShiftAssignment = guarded(
  {
    roles: BM,
    body: { staffId: 'int', shiftDefinitionId: 'int', workDate: 'date' },
    checks: (b) => [
      ['staffId', 'Nhân viên không được để trống', b.staffId == null],
      ['shiftDefinitionId', 'Ca làm việc không được để trống', b.shiftDefinitionId == null],
      ['workDate', 'Ngày làm việc không được để trống', b.workDate == null],
    ],
  },
  (req, b) => {
    const s = load();
    const branchId = branchOf(req);
    const staff = staffSummaryOf(Number(b.staffId));
    if (!staff || staff.branchId !== branchId) return notFound('nhân viên', Number(b.staffId));
    const shift = defOf(s, Number(b.shiftDefinitionId));
    if (!shift || shift.branchId !== branchId) return notFound('ca làm việc', Number(b.shiftDefinitionId));
    const workDate = String(b.workDate);
    if (workDate < vnDate()) return ruleFail('BR-NS-02', 'Không phân ca cho ngày trong quá khứ');
    if (!shift.active) return ruleFail('BR-NS-02', `Ca "${shift.name}" đã ngừng dùng`);
    if (!STAFF.includes(staff.role) || !staff.active) {
      return ruleFail('BR-NS-02', 'Chỉ phân ca cho lễ tân, bác sĩ thú y hoặc nhân viên chăm sóc đang hoạt động của chi nhánh');
    }
    const same = s.assignments.some((a) => a.staffId === staff.accountId && a.shiftDefinitionId === shift.shiftDefinitionId && a.workDate === workDate && a.status === 'SCHEDULED');
    if (same) return ruleFail('BR-NS-02', `Nhân viên đã được phân ca "${shift.name}" ngày ${dmy(workDate)}`);
    const other = clash(s, staff.accountId, workDate, shift);
    if (other) return ruleFail('BR-NS-02', `Chồng giờ với ca "${other.name}" (${range(other)}) đã phân cho nhân viên trong ngày ${dmy(workDate)}`);
    const created: AssignmentRow = {
      shiftAssignmentId: nextId(s.assignments.map((a) => a.shiftAssignmentId)),
      branchId, staffId: staff.accountId, shiftDefinitionId: shift.shiftDefinitionId, workDate,
      status: 'SCHEDULED', reason: null, statusChangedBy: null, statusChangedAt: null,
    };
    s.assignments.push(created);
    save(s);
    return ok(assignmentView(s, created), 201, 'Đã phân ca');
  },
);

/** Phân ca theo id trong chi nhánh của người gọi: không có → 404, của chi nhánh khác → 403 phạm vi. */
function assignmentFor(req: MockRequest, s: State): AssignmentRow | MockReply {
  const id = idOf(req, 'shiftAssignmentId');
  const a = assignmentOf(s, id);
  if (!a) return notFound('phân ca', id);
  return a.branchId === branchOf(req) ? a : scopeDenied();
}

const reasonSpec = { body: { reason: 'text' }, checks: (b: Record<string, unknown>) => textChecks('reason', 'Lý do', b.reason, WORKFORCE_LIMITS.reason) } as const;

const cancelShiftAssignment = guarded({ roles: BM, params: ['shiftAssignmentId'], ...reasonSpec }, (req, b) => {
  const s = load();
  const a = assignmentFor(req, s);
  if (isReply(a)) return a;
  if (hasStarted(s, a)) return ruleFail('BR-NS-02', 'Ca đã bắt đầu nên không hủy được; hãy ghi nhận vắng đột xuất');
  if (a.status !== 'SCHEDULED') return stateError('phân ca', a.status, 'CANCELLED');
  const reason = String(b.reason).trim();
  Object.assign(a, { status: 'CANCELLED', reason, statusChangedBy: me(req), statusChangedAt: new Date().toISOString() });
  rejectPendingOf(s, a.shiftAssignmentId, 'Phân ca đã bị hủy.');
  save(s);
  notifyCancelled(s, a, reason);
  return ok(assignmentView(s, a), 200, 'Đã hủy phân ca');
});

function notifyCancelled(s: State, a: AssignmentRow, reason: string) {
  const d = defOf(s, a.shiftDefinitionId);
  notify(
    [a.staffId],
    'SHIFT_ASSIGNMENT_CANCELLED',
    `Ca làm việc ngày ${dmy(a.workDate)} đã bị hủy`,
    `Ca ${d?.name ?? ''} ngày ${dmy(a.workDate)} (${d ? range(d) : ''}) tại ${branchName(a.branchId)} của bạn đã bị hủy. Lý do: ${reason}.`,
  );
}

const recordShiftAbsence = guarded({ roles: BM, params: ['shiftAssignmentId'], ...reasonSpec }, (req, b) => {
  const s = load();
  const a = assignmentFor(req, s);
  if (isReply(a)) return a;
  const today = vnDate();
  if (a.workDate > today) return ruleFail('BR-NS-07', 'Ca của ngày tương lai không ghi vắng được; hãy hủy phân ca');
  if (daysBetween(a.workDate, today) > ABSENCE_MAX_DAYS) return ruleFail('BR-NS-07', `Chỉ ghi nhận vắng trong ${ABSENCE_MAX_DAYS} ngày kể từ ngày làm việc`);
  if (a.status !== 'SCHEDULED') return stateError('phân ca', a.status, 'ABSENT');
  const reason = String(b.reason).trim();
  Object.assign(a, { status: 'ABSENT', reason, statusChangedBy: me(req), statusChangedAt: new Date().toISOString() });
  rejectPendingOf(s, a.shiftAssignmentId, 'Phân ca đã được ghi nhận vắng đột xuất.');
  save(s);
  notify(
    [a.staffId],
    'SHIFT_ABSENCE_RECORDED',
    `Bạn được ghi nhận vắng mặt ngày ${dmy(a.workDate)}`,
    `Quản lý đã ghi nhận bạn vắng mặt ca ${defOf(s, a.shiftDefinitionId)?.name ?? ''} ngày ${dmy(a.workDate)} tại ${branchName(a.branchId)}. Lý do: ${reason}.`,
  );
  return ok(assignmentView(s, a), 200, 'Đã ghi nhận vắng đột xuất');
});

const listMyShiftAssignments = guarded({ roles: STAFF, query: REQUIRED_RANGE }, (req) => {
  const { from, to } = req.query;
  const bad = badRange(from, to);
  if (bad) return bad;
  const s = load();
  const rows = s.assignments.filter((a) => a.staffId === me(req) && a.workDate >= from && a.workDate <= to).sort(byDateThenTime(s));
  return ok(rows.map((a) => assignmentView(s, a)));
});

// ─── Yêu cầu nghỉ / đổi ca (#9–14, UC36, UC37, BR-NS-04, 05, 06) ───

const createShiftRequest = guarded(
  {
    roles: STAFF,
    body: { shiftAssignmentId: 'int', type: SHIFT_REQUEST_TYPES, targetShiftDefinitionId: 'int', reason: 'text' },
    checks: (b) => [
      ['shiftAssignmentId', 'Phân ca không được để trống', b.shiftAssignmentId == null],
      ['type', 'Loại yêu cầu không được để trống', b.type == null],
      ...textChecks('reason', 'Lý do', b.reason, WORKFORCE_LIMITS.reason),
    ],
  },
  (req, b) => {
    const s = load();
    const id = Number(b.shiftAssignmentId);
    const a = assignmentOf(s, id);
    if (!a || a.staffId !== me(req)) return fail('RESOURCE_NOT_FOUND', `Không tìm thấy phân ca #${id} của bạn`, 404);
    const type = b.type as ShiftRequestType;
    const targetId = has(b.targetShiftDefinitionId) ? Number(b.targetShiftDefinitionId) : null;
    const target = type === 'SWAP' && targetId !== null ? defOf(s, targetId) : undefined;
    if (type === 'SWAP' && targetId !== null && (!target || target.branchId !== a.branchId)) return notFound('ca làm việc', targetId);

    if (a.status !== 'SCHEDULED') return ruleFail('BR-NS-04', 'Chỉ gửi yêu cầu cho phân ca đang chờ làm việc');
    if (startMs(s, a) - Date.now() < REQUEST_LEAD_HOURS * HOUR) {
      return ruleFail('BR-NS-04', `Phải gửi yêu cầu trước giờ bắt đầu ca ít nhất ${REQUEST_LEAD_HOURS} giờ`);
    }
    if (s.requests.some((r) => r.shiftAssignmentId === id && r.status === 'PENDING')) return ruleFail('BR-NS-04', 'Phân ca này đã có yêu cầu đang chờ duyệt');
    if (type === 'LEAVE' && targetId !== null) return ruleFail('BR-NS-04', 'Yêu cầu nghỉ không có ca đích');
    if (type === 'SWAP') {
      if (!target) return ruleFail('BR-NS-04', 'Yêu cầu đổi ca phải chọn ca đích');
      if (!target.active) return ruleFail('BR-NS-04', `Ca đích "${target.name}" đã ngừng dùng`);
      if (target.shiftDefinitionId === a.shiftDefinitionId) return ruleFail('BR-NS-04', 'Ca đích trùng với ca hiện tại');
      const other = clash(s, a.staffId, a.workDate, target, a.shiftAssignmentId);
      if (other) return ruleFail('BR-NS-04', `Ca đích chồng giờ với ca "${other.name}" (${range(other)}) bạn đã được phân trong ngày`);
    }

    const created: RequestRow = {
      shiftRequestId: nextId(s.requests.map((r) => r.shiftRequestId)),
      shiftAssignmentId: id,
      requesterId: me(req),
      type,
      targetShiftDefinitionId: type === 'SWAP' ? targetId : null,
      reason: String(b.reason).trim(),
      status: 'PENDING',
      decidedBy: null,
      decisionNote: null,
      closedAt: null,
      createdAt: new Date().toISOString(),
    };
    s.requests.push(created);
    save(s);
    const kind = type === 'LEAVE' ? 'nghỉ' : 'đổi ca';
    notify(
      managersOf(a.branchId),
      'SHIFT_REQUEST_SUBMITTED',
      `Yêu cầu ${kind} ca làm việc mới`,
      `${staffNameOf(me(req)) ?? 'Nhân viên'} gửi yêu cầu ${kind} ca ${defOf(s, a.shiftDefinitionId)?.name ?? ''} ngày ${dmy(a.workDate)} (${branchName(a.branchId)}). Lý do: ${created.reason}. Vui lòng duyệt hoặc từ chối.`,
    );
    return ok(requestView(s, created), 201, 'Đã gửi yêu cầu');
  },
);

const REQUEST_LIST_QUERY = [['status', SHIFT_REQUEST_STATUSES], ...PAGING] as const;

const listMyShiftRequests = guarded({ roles: STAFF, query: REQUEST_LIST_QUERY }, (req) => {
  const { status } = req.query;
  const s = load();
  const rows = s.requests
    .filter((r) => r.requesterId === me(req) && (status === undefined || r.status === status))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.shiftRequestId - a.shiftRequestId);
  return ok(pageOf(rows.map((r) => requestView(s, r)), clampPaging(req.query)));
});

const withdrawShiftRequest = guarded({ roles: STAFF, params: ['shiftRequestId'] }, (req) => {
  const s = load();
  const id = idOf(req, 'shiftRequestId');
  const r = s.requests.find((x) => x.shiftRequestId === id && x.requesterId === me(req));
  if (!r) return notFound('yêu cầu nghỉ / đổi ca', id);
  if (r.status !== 'PENDING') return stateError('yêu cầu nghỉ / đổi ca', r.status, 'WITHDRAWN');
  Object.assign(r, { status: 'WITHDRAWN', closedAt: new Date().toISOString() });
  save(s);
  return ok(requestView(s, r), 200, 'Đã rút yêu cầu');
});

const listShiftRequests = guarded({ roles: BM, query: REQUEST_LIST_QUERY }, (req) => {
  const status = req.query.status ?? 'PENDING';
  const s = load();
  const rows = s.requests
    .filter((r) => r.status === status && assignmentOf(s, r.shiftAssignmentId)?.branchId === branchOf(req))
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.shiftRequestId - b.shiftRequestId);
  return ok(pageOf(rows.map((r) => requestView(s, r)), clampPaging(req.query)));
});

/** Yêu cầu theo id và phân ca của nó trong chi nhánh của người gọi: không có → 404, chi nhánh khác → 403 phạm vi. */
function requestFor(req: MockRequest, s: State): { r: RequestRow; a: AssignmentRow } | MockReply {
  const id = idOf(req, 'shiftRequestId');
  const r = s.requests.find((x) => x.shiftRequestId === id);
  const a = r && assignmentOf(s, r.shiftAssignmentId);
  if (!r || !a) return notFound('yêu cầu nghỉ / đổi ca', id);
  return a.branchId === branchOf(req) ? { r, a } : scopeDenied();
}

const approveShiftRequest = guarded({ roles: BM, params: ['shiftRequestId'] }, (req) => {
  const s = load();
  const found = requestFor(req, s);
  if (isReply(found)) return found;
  const { r, a } = found;
  if (hasStarted(s, a)) return ruleFail('BR-NS-06', 'Phân ca đã bắt đầu nên không duyệt được');
  const target = r.type === 'SWAP' && r.targetShiftDefinitionId != null ? defOf(s, r.targetShiftDefinitionId) : undefined;
  if (r.type === 'SWAP') {
    if (!target || !target.active) return ruleFail('BR-NS-06', 'Ca đích đã ngừng dùng');
    const other = clash(s, a.staffId, a.workDate, target, a.shiftAssignmentId);
    if (other) return ruleFail('BR-NS-06', `Ca đích chồng giờ với ca "${other.name}" (${range(other)}) đã phân cho nhân viên trong ngày`);
  }
  if (r.status !== 'PENDING') return stateError('yêu cầu nghỉ / đổi ca', r.status, 'APPROVED');
  if (a.status !== 'SCHEDULED') return ruleFail('BR-NS-06', 'Phân ca không còn hiệu lực');
  const description = describeRequest(s, r);
  const at = new Date().toISOString();
  if (r.type === 'LEAVE') Object.assign(a, { status: 'ON_LEAVE', statusChangedBy: me(req), statusChangedAt: at });
  else a.shiftDefinitionId = target!.shiftDefinitionId;
  Object.assign(r, { status: 'APPROVED', decidedBy: me(req), closedAt: at });
  save(s);
  notify([r.requesterId], 'SHIFT_REQUEST_APPROVED', 'Yêu cầu ca làm việc đã được duyệt', `Yêu cầu ${description} tại ${branchName(a.branchId)} của bạn đã được quản lý duyệt.`);
  return ok(requestView(s, r), 200, 'Đã duyệt yêu cầu');
});

const rejectShiftRequest = guarded(
  {
    roles: BM,
    params: ['shiftRequestId'],
    body: { decisionNote: 'text' },
    checks: (b) => textChecks('decisionNote', 'Lý do từ chối', b.decisionNote, WORKFORCE_LIMITS.reason),
  },
  (req, b) => {
    const s = load();
    const found = requestFor(req, s);
    if (isReply(found)) return found;
    const { r, a } = found;
    if (r.status !== 'PENDING') return stateError('yêu cầu nghỉ / đổi ca', r.status, 'REJECTED');
    const description = describeRequest(s, r);
    const decisionNote = String(b.decisionNote).trim();
    Object.assign(r, { status: 'REJECTED', decidedBy: me(req), decisionNote, closedAt: new Date().toISOString() });
    save(s);
    notify(
      [r.requesterId],
      'SHIFT_REQUEST_REJECTED',
      'Yêu cầu ca làm việc bị từ chối',
      `Yêu cầu ${description} tại ${branchName(a.branchId)} của bạn không được duyệt. Lý do: ${decisionNote}.`,
    );
    return ok(requestView(s, r), 200, 'Đã từ chối yêu cầu');
  },
);

// ─── BR-NS-09 (hệ quả hệ thống, không có endpoint) ───

/**
 * Nhân viên bị vô hiệu hóa (BR-QT-09) hoặc điều chuyển khỏi `branchId` (BR-QT-06): phân ca SCHEDULED chưa bắt đầu của họ tại chi nhánh đó → CANCELLED
 * (`statusChangedBy` rỗng), yêu cầu PENDING → REJECTED; lịch sử đã qua giữ nguyên. Luồng chính gọi khi identity-admin.mock vô hiệu hóa / điều chuyển (Q1).
 */
export function staffLeftBranch(staffId: number, branchId: number): void {
  const s = load();
  const reason = 'Nhân viên không còn làm việc tại chi nhánh (BR-NS-09).';
  const cancelled = s.assignments.filter((a) => a.staffId === staffId && a.branchId === branchId && a.status === 'SCHEDULED' && !hasStarted(s, a));
  if (cancelled.length === 0) return;
  for (const a of cancelled) {
    Object.assign(a, { status: 'CANCELLED', reason, statusChangedBy: null, statusChangedAt: new Date().toISOString() });
    rejectPendingOf(s, a.shiftAssignmentId, 'Nhân viên không còn làm việc tại chi nhánh.');
  }
  save(s);
  for (const a of cancelled) notifyCancelled(s, a, reason);
}

export const workforceRoutes: Record<string, MockHandler> = {
  'GET /api/shift-definitions': listShiftDefinitions,
  'POST /api/shift-definitions': createShiftDefinition,
  'PATCH /api/shift-definitions/:shiftDefinitionId': updateShiftDefinition,
  'GET /api/shift-assignments': listShiftAssignments,
  'POST /api/shift-assignments': createShiftAssignment,
  'POST /api/shift-assignments/:shiftAssignmentId/cancel': cancelShiftAssignment,
  'POST /api/shift-assignments/:shiftAssignmentId/absent': recordShiftAbsence,
  'GET /api/me/shift-assignments': listMyShiftAssignments,
  'POST /api/me/shift-requests': createShiftRequest,
  'GET /api/me/shift-requests': listMyShiftRequests,
  'POST /api/me/shift-requests/:shiftRequestId/withdraw': withdrawShiftRequest,
  'GET /api/shift-requests': listShiftRequests,
  'POST /api/shift-requests/:shiftRequestId/approve': approveShiftRequest,
  'POST /api/shift-requests/:shiftRequestId/reject': rejectShiftRequest,
};
