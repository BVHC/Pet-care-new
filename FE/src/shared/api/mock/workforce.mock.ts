// Mock workforce-v1 (UC34–38; docs/api/workforce-v1.md #1–17): ca làm việc, phân ca, lịch của tôi, yêu cầu nghỉ / đổi ca, duyệt, vắng đột xuất, đăng ký ca theo tuần, thống kê tuần.
// KHỚP BE THẬT: thứ tự kiểm lỗi, mã lỗi, câu thông báo và trường đã ĐO trên BE 8083 (bản sao petcare_t66, 23/11/2026; script .c2-tmp/t66-measure*.mjs). Chỗ chưa đo được ghi "CHƯA ĐO":
//  W1  Thứ tự kiểm (ĐO): 401 → 400 MALFORMED_REQUEST (path / query sai kiểu hoặc thiếu `from`, `to`; body vắng, không phải object, trường sai kiểu / ngoài enum, giờ sai dạng `HH:mm`,
//      ngày không có thật) → 400 VALIDATION_FAILED (@Valid chạy khi nạp tham số nên đến TRƯỚC vai trò) → 403 vai trò → 404 → 403 phạm vi (ACCESS_DENIED_SCOPE_MISMATCH, câu chung)
//      → 409 INVALID_STATE_TRANSITION (hủy / vắng / rút / duyệt / từ chối: A11, đến TRƯỚC mọi guard 400) → 400 BR-NS-… (guard thời gian, nghiệp vụ). Id của chi nhánh khác: 404 nếu
//      không có, 403 phạm vi nếu có; tra theo chi nhánh của người gọi (nhân viên, ca khi phân ca) thì "không thấy" = 404.
//  W2  Vai trò (ĐO): #1 cho BRANCH_MANAGER và A06–A08 (A06–A08 chỉ nhận ca đang dùng, `activeOnly` bị bỏ qua, A10); #2–7, #12–14, #16 chỉ BRANCH_MANAGER; #8–11, #15, #17 chỉ A06–A08 (RECEPTIONIST,
//      VET, CARETAKER). SUPER_MANAGER, ADMIN, CUSTOMER → 403 mọi endpoint; BRANCH_MANAGER gọi `/me/…` → 403. Phân ca / yêu cầu của người khác ở `/me/…` → 404.
//  W3  BR-NS-03 (ĐO): khoảng ngày tính CẢ HAI ĐẦU, tối đa 62 ngày (to − from ≤ 61); `to` < `from` → 400 BR-NS-03. `from`, `to` thiếu → 400 MALFORMED_REQUEST (adapter bỏ giá trị rỗng
//      nên `from=` cũng là "Thiếu tham số"). `staffId` / `status` ở #4 và `status` ở #10, #12 ngoài kiểu / enum → MALFORMED_REQUEST. `size` kẹp 1–100, mặc định 20.
//  W4  "Đã bắt đầu" = bây giờ ≥ `workDate` + `startTime` (giờ VN). Hủy (#6) chỉ trước giờ đó (400 BR-NS-02); vắng (#7) tính theo NGÀY: ngày tương lai → 400 BR-NS-07 và quá 7 ngày [CFG]
//      `workforce.absence_report_max_days` → 400 BR-NS-07 (mock không đọc cấu hình: 7). Gửi yêu cầu cần startTime − bây giờ ≥ 24 giờ [CFG] `workforce.request_lead_hours` (đúng 24 giờ
//      là được; ca đã bắt đầu cũng bị từ chối bằng cùng câu).
//  W5  SWAP: ca đích là MỘT CA áp vào cùng ngày. Thứ tự #9 (ĐO): 404 phân ca của mình → hình dạng (LEAVE có ca đích / SWAP thiếu ca đích, 400 BR-NS-04) → 404 ca đích → phân ca không
//      SCHEDULED → còn dưới 24 giờ → đã có yêu cầu chờ (CHƯA ĐO so với giờ) → ca đích ngừng dùng → trùng ca hiện tại → chồng giờ phân ca KHÁC (chạm đầu mút không tính). Duyệt (#13, BE đã
//      cài SWAP): 409 → ca hiện tại đã bắt đầu → ca đích đã bắt đầu (CHƯA ĐO) → ca đích ngừng dùng / trùng / chồng giờ (400 BR-NS-06). Câu "đã bắt đầu" lấy từ mã BE.
//  W6  Ca (#2, #3) (ĐO): giờ đúng `HH:mm` (sai dạng → MALFORMED_REQUEST; `24:00` BE nhận thành `00:00`); giờ trống / null → VALIDATION_FAILED. Thứ tự BR-NS-01: giờ bắt đầu < kết thúc → tên
//      trùng (không phân biệt hoa thường, tính cả ca ngừng dùng) → sửa giờ khi ca đã có phân ca → ngừng dùng khi còn phân ca SCHEDULED từ hôm nay; #3 gửi giờ bằng giá trị hiện tại thì
//      coi như không đổi. PATCH không có trường nào (hoặc toàn null) → VALIDATION_FAILED `notEmpty`. KHÁC BE (không đo, FE không gửi): `active` kiểu chuỗi "false" BE coi là false, mock
//      báo MALFORMED_REQUEST.
//  W7  Thông báo (BR-NS-08, ĐO chữ) qua `pushNotification` của care.mock, IN_APP, `linkUrl` null: yêu cầu mới → mọi BRANCH_MANAGER ACTIVE của chi nhánh; duyệt / từ chối / hủy phân ca /
//      vắng → nhân viên. KHÔNG thông báo khi phân ca (BR-NS-08 không liệt kê). EMAIL không mô phỏng. BE còn dự phòng gửi SUPER_MANAGER khi chi nhánh không có quản lý ACTIVE (CHƯA mô phỏng).
//  W8  Không ghi audit (BR-NS-10). BR-NS-09 (nhân viên bị vô hiệu hóa / điều chuyển) là hệ quả hệ thống: mock export `staffLeftBranch(staffId, branchId)` cho identity-admin.mock.
//  W9  `ShiftRequest.currentShiftName` chụp LÚC GỬI (BE V60): yêu cầu SWAP đã duyệt vẫn hiện ca cũ ở `currentShiftName`, ca mới ở `targetShiftName`. Duyệt LEAVE ghi lý do của yêu cầu vào
//      phân ca (ĐO). `ShiftAssignment.source`: MANAGER (phân hộ #5 và mọi dữ liệu mẫu) hoặc SELF (đăng ký tuần). Nhân viên bị khóa (`staffSummaryOf().active` = false) coi như không ACTIVE.
//  W10 Dữ liệu mẫu sinh MỘT LẦN lúc nạp đầu, tính theo `vnDate()` của lúc đó: branch 1 có 5 ca (một ngừng dùng) và lịch −7…+10 ngày cho nhân viên 4, 5, 6, 21 (mỗi ngày: ca sáng /
//      ca chiều / nghỉ luân phiên), kèm 2 yêu cầu PENDING (nhân viên 5 xin nghỉ +3 ngày, nhân viên 6 xin đổi ca +4 ngày), 4 yêu cầu đã đóng và các phân ca ON_LEAVE / ABSENT /
//      CANCELLED; branch 2 chỉ có 2 ca, chưa có nhân viên. State cũ thiếu bảng nào thì bảng đó lấy dữ liệu mẫu; view chịu được tham chiếu mồ côi và state cũ chưa có `source` /
//      `currentShiftName`.
//  W11 Đăng ký tuần #15–17 (ĐO). `weekStart` ở path / query sai kiểu → MALFORMED_REQUEST. #15 body: `items` không phải mảng, phần tử không phải object, `workDate` không phải ngày thật hoặc
//      `shiftDefinitionId` không nguyên → MALFORMED_REQUEST; rồi VALIDATION_FAILED (thiếu `items`, phần tử null, thiếu trường, quá 100 mục; BE trả nhiều lỗi theo thứ tự băm nên mock sắp theo mục,
//      `shiftDefinitionId` trước `workDate`) — cả hai TRƯỚC vai trò. Sau vai trò: BR-NS-11 thứ Hai → năm 2000–2100 → (chỉ #15) quá hạn khi bây giờ ≥ 00:00 thứ Hai giờ VN → lỗi theo mục gộp MỘT
//      câu → BR-NS-12 (đếm cả ca MANAGER; `items` rỗng vẫn qua). Lỗi theo mục: ngoài tuần, rồi ca không có / ngừng dùng (một mục có thể báo cả hai), rồi trùng (chỉ mục đã hợp lệ); sau các mục
//      là chồng giờ giữa mục hợp lệ (i < j), rồi chồng ca MANAGER (thứ tự giữa nhiều ca MANAGER CHƯA ĐO). #15 chỉ thay phân ca SELF SCHEDULED của chính mình: thiếu → CANCELLED ("Nhân viên sửa
//      đăng ký tuần", yêu cầu PENDING → REJECTED không người duyệt), mới → SELF SCHEDULED, trùng thì giữ nguyên; không thông báo. Định mức = [CFG] `workforce.weekly_required_shifts`, mock cố
//      định 10. #16 đếm phân ca SCHEDULED của tuần ở mọi chi nhánh (SELF + MANAGER). State cũ chưa có `source` coi là MANAGER.
import type { Role } from '../../types/auth';
import type {
  ShiftAssignment,
  ShiftAssignmentSource,
  ShiftAssignmentStatus,
  ShiftDefinition,
  ShiftRegistration,
  ShiftRequest,
  ShiftRequestStatus,
  ShiftRequestType,
  ShiftWeeklySummary,
} from '../../types/workforce';
import { SHIFT_ASSIGNMENT_STATUSES, SHIFT_REQUEST_STATUSES, SHIFT_REQUEST_TYPES, WORKFORCE_LIMITS } from '../../types/workforce';
import { addDays, isoDayOfWeek } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { branchBooking } from './branch.mock';
import { pushNotification } from './care.mock';
import { blank, fail, has, isText, notFound, ok, ruleFail, scopeDenied, tooLong, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { assignableStaffOf, staffNameOf, staffSummaryOf } from './identity-admin.mock';
import { branchIdOf } from './identity.mock';
import { PAGING, flagOf, guarded, wrongType, type Body } from './people-guard';
import { clampPaging, pageOf } from './public.mock';

/** [CFG] `workforce.request_lead_hours` và `workforce.absence_report_max_days` (seed V40); mock không đọc cấu hình. */
const REQUEST_LEAD_HOURS = 24;
const ABSENCE_MAX_DAYS = 7;
/** [CFG] `workforce.weekly_required_shifts` (ADR-0148); mock không đọc cấu hình. */
const WEEKLY_REQUIRED_SHIFTS = 10;
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const BM: readonly Role[] = ['BRANCH_MANAGER'];
const STAFF: readonly Role[] = ['RECEPTIONIST', 'VET', 'CARETAKER'];
const BM_OR_STAFF: readonly Role[] = [...BM, ...STAFF];
/** BE đo: @Valid chạy trước vai trò. */
const BE = { validateFirst: true } as const;
/** BE nhận `24:00` thành `00:00`. */
const hhmm = (v: unknown) => (v === '24:00' ? '00:00' : String(v));
/** `@NotBlank(message)` + `@Size(max)`: câu báo trống khác nhau theo trường nên truyền riêng. */
const textRule = (field: string, blankMessage: string, label: string, value: unknown, max: number): Check[] => [
  [field, blankMessage, !isText(value)],
  [field, `${label} tối đa ${max} ký tự`, tooLong(value, max)],
];

// ─── State ───

type DefinitionRow = ShiftDefinition;
interface AssignmentRow {
  shiftAssignmentId: number;
  branchId: number;
  staffId: number;
  shiftDefinitionId: number;
  workDate: string;
  status: ShiftAssignmentStatus;
  /** State cũ trong localStorage chưa có: coi là MANAGER. */
  source?: ShiftAssignmentSource;
  reason: string | null;
  statusChangedBy: number | null;
  statusChangedAt: string | null;
}
interface RequestRow {
  shiftRequestId: number;
  shiftAssignmentId: number;
  requesterId: number;
  type: ShiftRequestType;
  /** Tên ca lúc gửi (BE V60); state cũ chưa có thì lấy theo phân ca hiện tại. */
  currentShiftName?: string;
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
      branchId: 1, staffId, shiftDefinitionId, workDate, status: 'SCHEDULED', source: 'MANAGER', reason: null, statusChangedBy: null, statusChangedAt: null, ...extra,
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
      currentShiftName: definitions.find((d) => d.shiftDefinitionId === a.shiftDefinitionId)?.name,
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
  if (daysBetween(from, to) + 1 > WORKFORCE_LIMITS.rangeDays) return ruleFail('BR-NS-03', `Khoảng ngày tối đa ${WORKFORCE_LIMITS.rangeDays} ngày`);
  return null;
}

/** `checkTarget` của BE (dùng chung gửi #9 và duyệt #13): ca đích ngừng dùng → trùng ca hiện tại → chồng giờ phân ca KHÁC trong ngày. */
function targetProblem(s: State, a: AssignmentRow, target: DefinitionRow): string | null {
  if (!target.active) return 'Ca đích đã ngừng dùng';
  if (target.shiftDefinitionId === a.shiftDefinitionId) return 'Ca đích trùng với ca hiện tại';
  const other = clash(s, a.staffId, a.workDate, target, a.shiftAssignmentId);
  return other ? `Ca đích chồng giờ với phân ca khác trong ngày: ${other.name} (${range(other)})` : null;
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
    source: a.source ?? 'MANAGER',
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
    currentShiftName: r.currentShiftName ?? (a && defOf(s, a.shiftDefinitionId)?.name) ?? '',
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

const START_REQUIRED = 'Vui lòng nhập giờ bắt đầu';
const END_REQUIRED = 'Vui lòng nhập giờ kết thúc';

const listShiftDefinitions = guarded({ roles: BM_OR_STAFF, query: [['activeOnly', 'bool']] }, (req) => {
  // A06–A08 chỉ nhận ca đang dùng, `activeOnly` bị bỏ qua (A10).
  const activeOnly = !BM.includes(req.account!.role) || flagOf(req.query.activeOnly) === true;
  const rows = load()
    .definitions.filter((d) => d.branchId === branchOf(req) && (!activeOnly || d.active))
    .sort((a, b) => a.startTime.localeCompare(b.startTime) || a.shiftDefinitionId - b.shiftDefinitionId);
  return ok(rows);
});

const createShiftDefinition = guarded(
  {
    roles: BM,
    ...BE,
    body: { name: 'text', startTime: 'time', endTime: 'time' },
    checks: (b) => [
      ...textRule('name', 'Vui lòng nhập tên ca', 'Tên ca', b.name, WORKFORCE_LIMITS.name),
      ['startTime', START_REQUIRED, !isText(b.startTime)],
      ['endTime', END_REQUIRED, !isText(b.endTime)],
    ],
  },
  (req, b) => {
    const s = load();
    const name = String(b.name).trim();
    const startTime = hhmm(b.startTime);
    const endTime = hhmm(b.endTime);
    // BE đo: giờ sai đến TRƯỚC tên trùng.
    if (startTime >= endTime) return ruleFail('BR-NS-01', 'Giờ bắt đầu phải nhỏ hơn giờ kết thúc');
    if (nameTaken(s, branchOf(req), name)) return ruleFail('BR-NS-01', 'Tên ca đã tồn tại trong chi nhánh');
    const created: DefinitionRow = {
      shiftDefinitionId: nextId(s.definitions.map((d) => d.shiftDefinitionId)),
      branchId: branchOf(req),
      name,
      startTime,
      endTime,
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
    ...BE,
    params: ['shiftDefinitionId'],
    body: { name: 'text', startTime: 'time', endTime: 'time', active: 'bool' },
    checks: (b) => [
      // BE đo: không trường nào có giá trị (cả khi toàn null) → `notEmpty`. Giờ trống chưa đo ở PATCH: coi như vắng.
      ['notEmpty', 'Cần gửi ít nhất một trường cần sửa (name, startTime, endTime, active)', !has(b.name) && !isText(b.startTime) && !isText(b.endTime) && !has(b.active)],
      ['name', 'Tên ca không được để trống', blank(b.name)],
      ['name', `Tên ca tối đa ${WORKFORCE_LIMITS.name} ký tự`, tooLong(b.name, WORKFORCE_LIMITS.name)],
    ],
  },
  (req, b) => {
    const s = load();
    const id = idOf(req, 'shiftDefinitionId');
    const d = defOf(s, id);
    if (!d) return notFound('ca làm việc', id);
    if (d.branchId !== branchOf(req)) return scopeDenied();
    const name = has(b.name) ? String(b.name).trim() : d.name;
    const startTime = isText(b.startTime) ? hhmm(b.startTime) : d.startTime;
    const endTime = isText(b.endTime) ? hhmm(b.endTime) : d.endTime;
    // BE đo: giờ sai → tên trùng → sửa giờ ca đã có phân ca → ngừng dùng khi còn phân ca.
    if (startTime >= endTime) return ruleFail('BR-NS-01', 'Giờ bắt đầu phải nhỏ hơn giờ kết thúc');
    if (nameTaken(s, d.branchId, name, id)) return ruleFail('BR-NS-01', 'Tên ca đã tồn tại trong chi nhánh');
    if ((startTime !== d.startTime || endTime !== d.endTime) && s.assignments.some((a) => a.shiftDefinitionId === id)) {
      return ruleFail('BR-NS-01', 'Ca đã có phân ca, không đổi được giờ; hãy tạo ca mới');
    }
    if (b.active === false && s.assignments.some((a) => a.shiftDefinitionId === id && a.status === 'SCHEDULED' && a.workDate >= vnDate())) {
      return ruleFail('BR-NS-01', 'Ca còn phân ca từ hôm nay trở đi, không ngừng dùng được');
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
    ...BE,
    body: { staffId: 'int', shiftDefinitionId: 'int', workDate: 'date' },
    checks: (b) => [
      ['staffId', 'Vui lòng chọn nhân viên', b.staffId == null],
      ['shiftDefinitionId', 'Vui lòng chọn ca làm việc', b.shiftDefinitionId == null],
      ['workDate', 'Vui lòng chọn ngày làm', b.workDate == null],
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
    // BE đo: quá khứ → nhân viên không hợp lệ → ca ngừng dùng → trùng / chồng giờ (một câu cho cả hai).
    if (workDate < vnDate()) return ruleFail('BR-NS-02', 'Không phân ca cho ngày trong quá khứ');
    if (!STAFF.includes(staff.role) || !staff.active) {
      return ruleFail('BR-NS-02', 'Chỉ phân ca cho lễ tân, bác sĩ hoặc nhân viên chăm sóc đang hoạt động của chi nhánh');
    }
    if (!shift.active) return ruleFail('BR-NS-02', 'Ca đã ngừng dùng, không phân ca được');
    const other = clash(s, staff.accountId, workDate, shift);
    if (other) return ruleFail('BR-NS-02', `Nhân viên đã có phân ca trùng hoặc chồng giờ: ${other.name} (${range(other)})`);
    const created: AssignmentRow = {
      shiftAssignmentId: nextId(s.assignments.map((a) => a.shiftAssignmentId)),
      branchId, staffId: staff.accountId, shiftDefinitionId: shift.shiftDefinitionId, workDate,
      status: 'SCHEDULED', source: 'MANAGER', reason: null, statusChangedBy: null, statusChangedAt: null,
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

const reasonSpec = {
  ...BE,
  body: { reason: 'text' },
  checks: (b: Record<string, unknown>) => textRule('reason', 'Vui lòng nhập lý do', 'Lý do', b.reason, WORKFORCE_LIMITS.reason),
} as const;

const cancelShiftAssignment = guarded({ roles: BM, params: ['shiftAssignmentId'], ...reasonSpec }, (req, b) => {
  const s = load();
  const a = assignmentFor(req, s);
  if (isReply(a)) return a;
  // BE đo (A11): 409 đến TRƯỚC guard "ca đã bắt đầu".
  if (a.status !== 'SCHEDULED') return stateError('phân ca', a.status, 'CANCELLED');
  if (hasStarted(s, a)) return ruleFail('BR-NS-02', 'Ca đã bắt đầu, không hủy được; hãy ghi nhận vắng đột xuất');
  const reason = String(b.reason).trim();
  Object.assign(a, { status: 'CANCELLED', reason, statusChangedBy: me(req), statusChangedAt: new Date().toISOString() });
  rejectPendingOf(s, a.shiftAssignmentId, 'Phân ca đã bị quản lý hủy');
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
  // BE đo (A11): 409 đến TRƯỚC guard ngày.
  if (a.status !== 'SCHEDULED') return stateError('phân ca', a.status, 'ABSENT');
  const today = vnDate();
  if (a.workDate > today) return ruleFail('BR-NS-07', 'Ca của ngày tương lai chưa ghi nhận vắng được; hãy hủy phân ca hoặc để nhân viên xin nghỉ');
  if (daysBetween(a.workDate, today) > ABSENCE_MAX_DAYS) return ruleFail('BR-NS-07', `Chỉ ghi nhận vắng cho ca trong vòng ${ABSENCE_MAX_DAYS} ngày gần đây`);
  const reason = String(b.reason).trim();
  Object.assign(a, { status: 'ABSENT', reason, statusChangedBy: me(req), statusChangedAt: new Date().toISOString() });
  rejectPendingOf(s, a.shiftAssignmentId, 'Phân ca đã được ghi nhận vắng đột xuất');
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
    ...BE,
    body: { shiftAssignmentId: 'int', type: SHIFT_REQUEST_TYPES, targetShiftDefinitionId: 'int', reason: 'text' },
    checks: (b) => [
      ['shiftAssignmentId', 'Vui lòng chọn phân ca', b.shiftAssignmentId == null],
      ['type', 'Vui lòng chọn loại yêu cầu', b.type == null],
      ...textRule('reason', 'Vui lòng nhập lý do', 'Lý do', b.reason, WORKFORCE_LIMITS.reason),
    ],
  },
  (req, b) => {
    const s = load();
    const id = Number(b.shiftAssignmentId);
    const a = assignmentOf(s, id);
    if (!a || a.staffId !== me(req)) return notFound('phân ca', id);
    const type = b.type as ShiftRequestType;
    const targetId = has(b.targetShiftDefinitionId) ? Number(b.targetShiftDefinitionId) : null;
    // BE đo: hình dạng LEAVE / SWAP (400) và 404 ca đích đến TRƯỚC trạng thái phân ca, giờ, yêu cầu chờ.
    if (type === 'LEAVE' && targetId !== null) return ruleFail('BR-NS-04', 'Yêu cầu nghỉ không có ca đích');
    if (type === 'SWAP' && targetId === null) return ruleFail('BR-NS-04', 'Yêu cầu đổi ca phải chọn ca đích');
    const target = type === 'SWAP' && targetId !== null ? defOf(s, targetId) : undefined;
    if (type === 'SWAP' && targetId !== null && (!target || target.branchId !== a.branchId)) return notFound('ca làm việc', targetId);

    if (a.status !== 'SCHEDULED') return ruleFail('BR-NS-04', 'Chỉ phân ca đang chờ làm mới được gửi yêu cầu');
    if (startMs(s, a) - Date.now() < REQUEST_LEAD_HOURS * HOUR) {
      return ruleFail('BR-NS-04', `Phải gửi yêu cầu trước giờ bắt đầu ca ít nhất ${REQUEST_LEAD_HOURS} giờ`);
    }
    if (s.requests.some((r) => r.shiftAssignmentId === id && r.status === 'PENDING')) return ruleFail('BR-NS-04', 'Phân ca này đã có yêu cầu đang chờ xử lý');
    if (target) {
      const other = targetProblem(s, a, target);
      if (other) return ruleFail('BR-NS-04', other);
    }

    const created: RequestRow = {
      shiftRequestId: nextId(s.requests.map((r) => r.shiftRequestId)),
      shiftAssignmentId: id,
      requesterId: me(req),
      currentShiftName: defOf(s, a.shiftDefinitionId)?.name,
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
    const kind = type === 'LEAVE' ? 'nghỉ' : 'đổi';
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
  // BE đo (A11): 409 đến TRƯỚC guard BR-NS-06.
  if (r.status !== 'PENDING') return stateError('yêu cầu nghỉ / đổi ca', r.status, 'APPROVED');
  if (hasStarted(s, a)) return ruleFail('BR-NS-06', 'Ca đã bắt đầu, không duyệt được');
  const target = r.type === 'SWAP' && r.targetShiftDefinitionId != null ? defOf(s, r.targetShiftDefinitionId) : undefined;
  if (r.type === 'SWAP') {
    if (!target) return ruleFail('BR-NS-06', 'Ca đích đã ngừng dùng');
    if (Date.now() >= Date.parse(`${a.workDate}T${target.startTime}:00+07:00`)) return ruleFail('BR-NS-06', 'Ca đích đã bắt đầu, không duyệt được');
    const problem = targetProblem(s, a, target);
    if (problem) return ruleFail('BR-NS-06', problem);
  }
  if (a.status !== 'SCHEDULED') return ruleFail('BR-NS-06', 'Phân ca không còn hiệu lực');
  const description = describeRequest(s, r);
  const at = new Date().toISOString();
  // BE đo: duyệt LEAVE ghi lý do của yêu cầu vào phân ca; SWAP không đổi `reason`.
  if (r.type === 'LEAVE') Object.assign(a, { status: 'ON_LEAVE', reason: r.reason, statusChangedBy: me(req), statusChangedAt: at });
  else a.shiftDefinitionId = target!.shiftDefinitionId;
  Object.assign(r, { status: 'APPROVED', decidedBy: me(req), closedAt: at });
  save(s);
  notify([r.requesterId], 'SHIFT_REQUEST_APPROVED', 'Yêu cầu ca làm việc đã được duyệt', `Yêu cầu ${description} tại ${branchName(a.branchId)} của bạn đã được quản lý duyệt.`);
  return ok(requestView(s, r), 200, 'Đã duyệt yêu cầu');
});

const rejectShiftRequest = guarded(
  {
    roles: BM,
    ...BE,
    params: ['shiftRequestId'],
    body: { decisionNote: 'text' },
    checks: (b) => textRule('decisionNote', 'Vui lòng nhập lý do từ chối', 'Lý do từ chối', b.decisionNote, WORKFORCE_LIMITS.reason),
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

// ─── Đăng ký ca theo tuần (#15, #17, UC35, BR-NS-11, BR-NS-12) và thống kê tuần (#16, UC34) ───

interface RegistrationItem {
  workDate: string;
  shiftDefinitionId: number;
}
const weekEnd = (weekStart: string) => addDays(weekStart, 6);
/** Hạn đăng ký: 00:00 thứ Hai của tuần theo giờ Việt Nam. */
const deadlineMs = (weekStart: string) => Date.parse(`${weekStart}T00:00:00+07:00`);
const keyOf = (workDate: string, shiftDefinitionId: number) => `${workDate}#${shiftDefinitionId}`;
/** State cũ chưa có `source`: coi là MANAGER. */
const isSelf = (a: AssignmentRow) => a.source === 'SELF';

/** BR-NS-11 (ĐO): thứ Hai được kiểm trước năm 2000–2100. */
function weekProblem(weekStart: string): MockReply | null {
  if (isoDayOfWeek(weekStart) !== 1) return ruleFail('BR-NS-11', 'Ngày bắt đầu tuần phải là thứ Hai');
  const year = Number(weekStart.slice(0, 4));
  return year < 2000 || year > 2100 ? ruleFail('BR-NS-11', 'Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100') : null;
}

/** Phân ca SCHEDULED của nhân viên trong tuần ở mọi chi nhánh (SELF và MANAGER), sắp theo ngày rồi giờ. */
const weekAssignments = (s: State, staffId: number, weekStart: string) =>
  s.assignments
    .filter((a) => a.staffId === staffId && a.status === 'SCHEDULED' && a.workDate >= weekStart && a.workDate <= weekEnd(weekStart))
    .sort(byDateThenTime(s));

const registrationView = (s: State, staffId: number, weekStart: string): ShiftRegistration => {
  const rows = weekAssignments(s, staffId, weekStart);
  return {
    weekStart,
    requiredShifts: WEEKLY_REQUIRED_SHIFTS,
    shiftCount: rows.length,
    editableUntil: new Date(deadlineMs(weekStart)).toISOString().replace('.000Z', 'Z'),
    assignments: rows.map((a) => assignmentView(s, a)),
  };
};

/** `items` đúng kiểu JSON: mảng; phần tử null để dành cho VALIDATION_FAILED, phần tử khác là object có `workDate` ngày thật và `shiftDefinitionId` số nguyên. */
const itemsMalformed = (items: unknown) =>
  !Array.isArray(items) ||
  items.some((x: unknown) => {
    if (x === null) return false;
    if (typeof x !== 'object' || Array.isArray(x)) return true;
    const { workDate, shiftDefinitionId } = x as Record<string, unknown>;
    return wrongType(workDate, 'date') || wrongType(shiftDefinitionId, 'int');
  });

function registrationChecks(b: Body): Check[] {
  if (!Array.isArray(b.items)) return [['items', 'Vui lòng gửi danh sách ca đăng ký', true]];
  const items = b.items as (Record<string, unknown> | null)[];
  return [
    ['items', `Tối đa ${WORKFORCE_LIMITS.registrationItems} mục mỗi lần đăng ký`, items.length > WORKFORCE_LIMITS.registrationItems],
    ...items.flatMap((x, i): Check[] =>
      x === null
        ? [[`items[${i}]`, 'Mục đăng ký không được để trống', true]]
        : [
            [`items[${i}].shiftDefinitionId`, 'Vui lòng chọn ca làm việc', x.shiftDefinitionId == null],
            [`items[${i}].workDate`, 'Vui lòng chọn ngày làm', x.workDate == null],
          ],
    ),
  ];
}

/** Lỗi theo mục của #15 (ĐO, xem W11): từng mục theo thứ tự, rồi chồng giờ giữa các mục hợp lệ, rồi chồng ca do quản lý xếp. */
function registrationProblems(s: State, branchId: number, staffId: number, weekStart: string, items: RegistrationItem[]): string[] {
  const problems: string[] = [];
  const firstOf = new Map<string, number>();
  const valid: { n: number; workDate: string; shift: DefinitionRow }[] = [];
  items.forEach(({ workDate, shiftDefinitionId }, i) => {
    const n = i + 1;
    const found = defOf(s, shiftDefinitionId);
    const shift = found?.branchId === branchId ? found : undefined;
    const outside = workDate < weekStart || workDate > weekEnd(weekStart);
    if (outside) problems.push(`Mục ${n}: ngày ${dmy(workDate)} nằm ngoài tuần ${dmy(weekStart)} – ${dmy(weekEnd(weekStart))}`);
    if (!shift) problems.push(`Mục ${n}: ca #${shiftDefinitionId} không tồn tại hoặc không thuộc chi nhánh của bạn`);
    else if (!shift.active) problems.push(`Mục ${n}: ca '${shift.name}' đã ngừng dùng`);
    if (outside || !shift || !shift.active) return;
    const twin = firstOf.get(keyOf(workDate, shiftDefinitionId));
    if (twin) problems.push(`Mục ${n}: trùng với mục ${twin} (cùng ngày, cùng ca)`);
    else {
      firstOf.set(keyOf(workDate, shiftDefinitionId), n);
      valid.push({ n, workDate, shift });
    }
  });
  valid.forEach((a, i) => {
    for (const b of valid.slice(i + 1)) {
      if (a.workDate === b.workDate && overlaps(a.shift, b.shift)) {
        problems.push(`Mục ${a.n} và mục ${b.n} chồng giờ ngày ${dmy(a.workDate)}: ${a.shift.name} (${range(a.shift)}) / ${b.shift.name} (${range(b.shift)})`);
      }
    }
  });
  const managed = weekAssignments(s, staffId, weekStart).filter((a) => !isSelf(a));
  for (const a of valid) {
    for (const m of managed) {
      const d = defOf(s, m.shiftDefinitionId);
      if (d && m.workDate === a.workDate && overlaps(d, a.shift)) problems.push(`Mục ${a.n}: chồng giờ với phân ca do quản lý xếp ngày ${dmy(a.workDate)}: ${d.name} (${range(d)})`);
    }
  }
  return problems;
}

const getMyShiftRegistration = guarded({ roles: STAFF, dateParams: ['weekStart'] }, (req) => {
  const { weekStart } = req.params;
  return weekProblem(weekStart) ?? ok(registrationView(load(), me(req), weekStart), 200, 'Đăng ký ca của tôi trong tuần');
});

const putMyShiftRegistration = guarded(
  { roles: STAFF, ...BE, dateParams: ['weekStart'], body: { items: itemsMalformed }, checks: registrationChecks },
  (req, b) => {
    const { weekStart } = req.params;
    const bad = weekProblem(weekStart);
    if (bad) return bad;
    if (Date.now() >= deadlineMs(weekStart)) return ruleFail('BR-NS-11', `Đã quá hạn đăng ký tuần ${dmy(weekStart)} (hạn là 00:00 thứ Hai); chỉ còn xin nghỉ hoặc đổi ca`);
    const s = load();
    const staffId = me(req);
    const branchId = branchOf(req);
    const items = b.items as RegistrationItem[];
    const problems = registrationProblems(s, branchId, staffId, weekStart, items);
    if (problems.length) return ruleFail('BR-NS-11', `Đăng ký tuần không hợp lệ: ${problems.join('; ')}`);
    const current = weekAssignments(s, staffId, weekStart);
    const managed = current.filter((a) => !isSelf(a)).length;
    const total = items.length + managed;
    if (total < WEEKLY_REQUIRED_SHIFTS) {
      return ruleFail('BR-NS-12', `Tuần này có ${total} ca (gồm ${managed} ca do quản lý xếp), cần tối thiểu ${WEEKLY_REQUIRED_SHIFTS} ca, còn thiếu ${WEEKLY_REQUIRED_SHIFTS - total}`);
    }
    // Chỉ đụng phân ca SELF của mình: mục thiếu → hủy, mục đã có giữ nguyên, mục mới → tạo.
    const wanted = new Set(items.map((x) => keyOf(x.workDate, x.shiftDefinitionId)));
    const kept = new Set<string>();
    const at = new Date().toISOString();
    for (const a of current.filter(isSelf)) {
      const key = keyOf(a.workDate, a.shiftDefinitionId);
      if (wanted.has(key)) {
        kept.add(key);
        continue;
      }
      Object.assign(a, { status: 'CANCELLED', reason: 'Nhân viên sửa đăng ký tuần', statusChangedBy: staffId, statusChangedAt: at });
      rejectPendingOf(s, a.shiftAssignmentId, 'Phân ca đã bị bỏ khi nhân viên sửa đăng ký tuần');
    }
    for (const x of items) {
      if (kept.has(keyOf(x.workDate, x.shiftDefinitionId))) continue;
      s.assignments.push({
        shiftAssignmentId: nextId(s.assignments.map((a) => a.shiftAssignmentId)),
        branchId, staffId, shiftDefinitionId: x.shiftDefinitionId, workDate: x.workDate,
        status: 'SCHEDULED', source: 'SELF', reason: null, statusChangedBy: null, statusChangedAt: null,
      });
    }
    save(s);
    return ok(registrationView(s, staffId, weekStart), 200, 'Đã lưu đăng ký ca của tuần');
  },
);

const listShiftWeeklySummaries = guarded({ roles: BM, query: [['weekStart', 'date', true]] }, (req) => {
  const { weekStart } = req.query;
  const bad = weekProblem(weekStart);
  if (bad) return bad;
  const s = load();
  const rows: ShiftWeeklySummary[] = STAFF.flatMap((role) => assignableStaffOf(branchOf(req), role)).map((m) => {
    const shiftCount = weekAssignments(s, m.accountId, weekStart).length;
    return {
      staffId: m.accountId,
      staffName: m.fullName,
      role: m.role as ShiftWeeklySummary['role'],
      shiftCount,
      requiredShifts: WEEKLY_REQUIRED_SHIFTS,
      meetsRequirement: shiftCount >= WEEKLY_REQUIRED_SHIFTS,
    };
  });
  return ok(rows.sort((a, b) => a.staffName.localeCompare(b.staffName) || a.staffId - b.staffId), 200, 'Thống kê số ca trong tuần');
});

// ─── BR-NS-09 (hệ quả hệ thống, không có endpoint) ───

/**
 * Nhân viên bị vô hiệu hóa (BR-QT-09) hoặc điều chuyển khỏi `branchId` (BR-QT-06): phân ca SCHEDULED chưa bắt đầu của họ tại chi nhánh đó → CANCELLED
 * (`statusChangedBy` rỗng), yêu cầu PENDING → REJECTED; lịch sử đã qua giữ nguyên. Luồng chính gọi khi identity-admin.mock vô hiệu hóa / điều chuyển (Q1).
 */
export function staffLeftBranch(staffId: number, branchId: number): void {
  const s = load();
  const reason = 'Nhân viên không còn làm việc tại chi nhánh';
  const cancelled = s.assignments.filter((a) => a.staffId === staffId && a.branchId === branchId && a.status === 'SCHEDULED' && !hasStarted(s, a));
  if (cancelled.length === 0) return;
  for (const a of cancelled) {
    Object.assign(a, { status: 'CANCELLED', reason, statusChangedBy: null, statusChangedAt: new Date().toISOString() });
    rejectPendingOf(s, a.shiftAssignmentId, reason);
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
  'PUT /api/me/shift-registrations/:weekStart': putMyShiftRegistration,
  'GET /api/me/shift-registrations/:weekStart': getMyShiftRegistration,
  'GET /api/shift-weekly-summaries': listShiftWeeklySummaries,
};
