// Mock complaint-v1 (UC85, UC86; docs/api/complaint-v1.md #1–12): khách gửi / theo dõi / đóng / mở lại khiếu nại, lễ tân tạo thay, BRANCH_MANAGER và SUPER_MANAGER nhận / trả lời / kết luận.
// BE tầng 3 CHƯA cài (T70), nên chưa có số đo thật: mock chép hợp đồng + BR-DG-05…09 + 09 §D (FSM #26) + V39 (CHECK của bảng). Chỗ im lặng là ASSUMPTION của T57:
//  C1  Thứ tự kiểm (00-method §3.5 + bài học report A1; CHƯA đo trên BE): 401 → 400 MALFORMED_REQUEST (path / query sai kiểu, body vắng / không phải JSON object / trường sai kiểu / ngoài
//      enum) → 403 vai trò → 400 VALIDATION_FAILED (thiếu / trống / `body` quá 2 000) → 404 → 403 phạm vi → 400 BR-DG-… → 409 INVALID_STATE_TRANSITION. Khách xem khiếu nại của người khác
//      → 404 (ADR-0006); nhân viên ngoài phạm vi (quản lý chi nhánh khác, lễ tân không phải người tạo) → 403 ACCESS_DENIED_SCOPE_MISMATCH, như care.mock. Vai trò: #1–6 chỉ CUSTOMER;
//      #7 chỉ RECEPTIONIST; #8, #9, #11 SUPER_MANAGER / BRANCH_MANAGER / RECEPTIONIST; #10, #12 SUPER_MANAGER / BRANCH_MANAGER; ADMIN, VET, CARETAKER → 403 mọi endpoint nhân viên.
//  C2  BR-DG-05 (#1, #7), theo yaml: `content` thiếu / toàn khoảng trắng → VALIDATION_FAILED; nội dung (đã cắt khoảng trắng) < 10 hoặc > 2 000 ký tự → 400 BR-DG-05 (yaml xếp cả hai vào BR-DG-05
//      dù schema có maxLength); `topic` thiếu → VALIDATION_FAILED; `relatedType` và `relatedId` chỉ có một trong hai → VALIDATION_FAILED. Thứ tự: 404 (chi nhánh, đối tượng liên quan) → BR-DG-05 theo
//      thứ tự yaml: nội dung → thiếu chi nhánh (trừ OTHER) → đối tượng không thuộc khách → quá 10 khiếu nại OPEN / IN_PROGRESS (mock không đọc [CFG] `complaint.max_open`). Chi nhánh = mọi chi nhánh
//      có trong mock chi nhánh (kể cả chưa kích hoạt). ORDER, VISIT: tra qua `allOrderRows`, `visitRowOf`; ONLINE_ORDER, BOARDING chưa có export nên đi qua `registerComplaintSources` (mặc định:
//      đơn online 5001 → khách 1, 5002 → khách 2; đặt chỗ lưu trú 4, 5, 6 → khách 1, 2 → khách 9002) — luồng chính nối với shop.mock / boarding.mock.
//  C3  Lễ tân (#7): `customerId` tra chung toàn chuỗi (khách không theo chi nhánh, 00-method §3.3) — không có → 404; `branchId` gửi lên mà khác chi nhánh của lễ tân → 403 phạm vi (trước 404). Khiếu nại do
//      lễ tân tạo có `createdByStaff = true` và chỉ lễ tân đó (cùng SUPER_MANAGER, và quản lý nếu có chi nhánh) thấy; chủ đề OTHER không kèm chi nhánh chỉ SUPER_MANAGER thấy (BR-DG-06).
//  C4  Mở lại (#6): chỉ từ RESOLVED / CLOSED; OPEN / IN_PROGRESS → 409 (các guard 400 đọc `resolvedAt` nên chỉ chạy khi đã RESOLVED / CLOSED). Guard 400 BR-DG-07 theo thứ tự: đã rút (CLOSED + WITHDRAWN)
//      → quá 7 ngày kể từ `resolvedAt` ([CFG] `complaint.reopen_days`, mock không đọc: 7) → `reopenCount` ≥ 2 ([CFG] `complaint.max_reopen`). Mở lại: OPEN, xóa người xử lý và `closedAt` / `closedReason`,
//      `reopenCount` + 1, GIỮ `resolution` / `resolvedAt` cũ làm lịch sử (V39) và `escalatedAt` (leo thang chỉ một lần, A2); ghi dòng STATUS rồi tin nhắn lý do. `body` thiếu / trống → VALIDATION_FAILED.
//  C5  Đóng / rút (#5): RESOLVED → CLOSED (`CUSTOMER`); OPEN / IN_PROGRESS → CLOSED (`WITHDRAWN`); đã CLOSED → 409. Nhận (#10): OPEN → IN_PROGRESS, gán người gọi; khác OPEN → 409.
//      Kết luận (#12): thiếu kết luận → 400 BR-DG-07 (yaml; sau 404 / phạm vi, trước 409), quá 2 000 ký tự → VALIDATION_FAILED; chỉ IN_PROGRESS, và KHÔNG bắt người kết luận phải là người đã nhận
//      (không thì SUPER_MANAGER không xử lý được khiếu nại đã leo thang đang do quản lý chi nhánh giữ).
//  C6  Tin nhắn (#4, #11): được ở OPEN / IN_PROGRESS / RESOLVED (nhân viên không cần nhận trước), CLOSED → 409; trả 201 với cả khiếu nại; cập nhật `lastActivityAt`, không đổi trạng thái, không gửi thông báo (06 §8).
//      Mọi chuyển trạng thái (kể cả tạo) ghi một dòng STATUS với tác giả CUSTOMER / STAFF; SYSTEM chỉ có trong dữ liệu mẫu (ST16 / ST17).
//  C7  Thông báo qua `pushNotification` của care.mock (IN_APP, mã mẫu và chữ chép V40, bỏ hậu tố `_APP`, `linkUrl` null; EMAIL không mô phỏng): tạo → mọi BRANCH_MANAGER ACTIVE của chi nhánh (không chi nhánh:
//      mọi SUPER_MANAGER trong MOCK_ACCOUNTS); nhận → `COMPLAINT_TAKEN`, kết luận → `COMPLAINT_RESOLVED` cho khách (chỉ khi hồ sơ có tài khoản); mở lại → `COMPLAINT_REOPENED` cho quản lý như lúc tạo (06 §8; ADR-0113 ghi
//      "người xử lý" nhưng #6 xóa người xử lý).
//  C8  ST16 (leo thang) và ST17 (tự đóng) KHÔNG chạy trong mock: dữ liệu mẫu có sẵn khiếu nại đã leo thang và đã tự đóng, khiếu nại tạo mới không tự leo thang theo thời gian. `escalated=true` chỉ lấy đã leo
//      thang; hợp đồng không nói `escalated=false`, mock hiểu là chưa leo thang. `status` vắng: #8 là OPEN + IN_PROGRESS (yaml), #2 là mọi trạng thái.
//  C9  Dữ liệu mẫu sinh MỘT LẦN lúc nạp đầu (giờ tương đối với lúc đó): khách 1 (khach@petcare.vn) có đủ trạng thái — OPEN, IN_PROGRESS, RESOLVED (còn mở lại được), RESOLVED đã mở lại 2 lần, CLOSED do khách, CLOSED do rút,
//      CLOSED tự đóng, OPEN đã leo thang, OPEN ở chi nhánh 2 (manager2) và OPEN không chi nhánh (chỉ chuoi); khách 2 (khachmoi) có 1 khiếu nại do lễ tân 4 tạo thay và 1 IN_PROGRESS đã leo thang.
import type { Role } from '../../types/auth';
import type {
  Complaint,
  ComplaintClosedReason,
  ComplaintMessage,
  ComplaintMessageAuthorType,
  ComplaintMessageKind,
  ComplaintRelatedType,
  ComplaintStatus,
  ComplaintSummary,
  ComplaintTopic,
} from '../../types/complaint';
import { COMPLAINT_LIMITS, COMPLAINT_RELATED_TYPES, COMPLAINT_STATUSES, COMPLAINT_TOPICS } from '../../types/complaint';
import { vnDate } from '../../utils/vn-time';
import { branchBooking } from './branch.mock';
import { pushNotification } from './care.mock';
import { customerOfAccount, findCustomer } from './customer.mock';
import { fail, has, notFound, ok, ruleFail, scopeDenied, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { assignableStaffOf, staffNameOf } from './identity-admin.mock';
import { MOCK_ACCOUNTS, branchIdOf } from './identity.mock';
import { PAGING, flagOf, guarded, textChecks, type Body } from './people-guard';
import { clampPaging, pageOf } from './public.mock';
import { allOrderRows } from './sales.mock';
import { visitRowOf } from './visit.mock';

/** [CFG] `complaint.max_open`, `complaint.reopen_days`, `complaint.max_reopen` (seed V40); mock không đọc cấu hình. */
const MAX_OPEN = 10;
const REOPEN_DAYS = 7;
const MAX_REOPEN = 2;
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const CUSTOMER: readonly Role[] = ['CUSTOMER'];
const REPLYING: readonly Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST'];
const HANDLING: readonly Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER'];
const TOPIC_LABELS: Record<ComplaintTopic, string> = {
  SERVICE_QUALITY: 'chất lượng dịch vụ',
  GROOMING: 'dịch vụ làm đẹp',
  BOARDING: 'lưu trú',
  PURCHASE: 'mua hàng',
  STAFF: 'thái độ nhân viên',
  OTHER: 'vấn đề khác',
};

// ─── Nguồn kiểm "đối tượng liên quan thuộc khách" (C2) ───

export interface ComplaintSources {
  /** Chủ của đơn online (shop-v1); undefined = không có đơn đó. */
  onlineOrderOwner(orderId: number): number | undefined;
  /** Chủ của đặt chỗ lưu trú (boarding-v1); undefined = không có đặt chỗ đó. */
  boardingOwner(bookingId: number): number | undefined;
}
const DEFAULT_ONLINE_ORDERS: Record<number, number> = { 5001: 1, 5002: 2 };
const DEFAULT_BOARDINGS: Record<number, number> = { 4: 1, 5: 1, 6: 1, 2: 9002 };
let sources: ComplaintSources = {
  onlineOrderOwner: (id) => DEFAULT_ONLINE_ORDERS[id],
  boardingOwner: (id) => DEFAULT_BOARDINGS[id],
};
/** Luồng chính nối với mock đơn online / lưu trú khi chúng có export tra chủ sở hữu. */
export function registerComplaintSources(next: Partial<ComplaintSources>): void {
  sources = { ...sources, ...next };
}

function relatedOwner(type: ComplaintRelatedType, id: number): number | undefined {
  if (type === 'ORDER') return allOrderRows().find((o) => o.orderId === id)?.customerId;
  if (type === 'VISIT') return visitRowOf(id)?.customerId;
  return type === 'ONLINE_ORDER' ? sources.onlineOrderOwner(id) : sources.boardingOwner(id);
}

// ─── State ───

interface MessageRow {
  messageId: number;
  authorType: ComplaintMessageAuthorType;
  authorId: number | null;
  kind: ComplaintMessageKind;
  body: string;
  createdAt: string;
}
interface ComplaintRow {
  complaintId: number;
  code: string;
  customerId: number;
  branchId: number | null;
  topic: ComplaintTopic;
  relatedType: ComplaintRelatedType | null;
  relatedId: number | null;
  content: string;
  status: ComplaintStatus;
  createdByStaffId: number | null;
  handlerId: number | null;
  resolution: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  closedReason: ComplaintClosedReason | null;
  reopenCount: number;
  escalatedAt: string | null;
  lastActivityAt: string;
  createdAt: string;
  messages: MessageRow[];
}
interface State {
  complaints: ComplaintRow[];
}

const STATE_KEY = 'petcare-mock-complaint';

/** Mã `KN-yyMMdd-xxxx`: số thứ tự theo ngày (giờ VN) của lúc tạo, như `document_counters`. */
function codeFor(createdAt: string, existing: readonly ComplaintRow[]): string {
  const prefix = `KN-${vnDate(Date.parse(createdAt)).slice(2).replace(/-/g, '')}-`;
  const last = Math.max(0, ...existing.filter((c) => c.code.startsWith(prefix)).map((c) => Number(c.code.slice(prefix.length)) || 0));
  return `${prefix}${String(last + 1).padStart(4, '0')}`;
}

function seed(): State {
  const now = Date.now();
  const ago = (hours: number) => new Date(now - hours * HOUR).toISOString();
  const complaints: ComplaintRow[] = [];
  let messageId = 0;
  const line = (authorType: ComplaintMessageAuthorType, authorId: number | null, kind: ComplaintMessageKind, body: string, hours: number): MessageRow => ({
    messageId: ++messageId, authorType, authorId, kind, body, createdAt: ago(hours),
  });
  // Khách 1 = tài khoản 7, khách 2 = tài khoản 10; quản lý chi nhánh 1 = 3, lễ tân = 4.
  const customerLine = (customerId: number, kind: ComplaintMessageKind, body: string, hours: number) =>
    line('CUSTOMER', customerId === 1 ? 7 : 10, kind, body, hours);
  const add = (
    r: Pick<ComplaintRow, 'customerId' | 'branchId' | 'topic' | 'content' | 'status'> & Partial<ComplaintRow> & { createdHoursAgo: number; activityHoursAgo?: number },
  ) => {
    const { createdHoursAgo, activityHoursAgo, ...rest } = r;
    const createdAt = ago(createdHoursAgo);
    complaints.push({
      complaintId: complaints.length + 1, code: codeFor(createdAt, complaints), relatedType: null, relatedId: null, createdByStaffId: null, handlerId: null,
      resolution: null, resolvedAt: null, closedAt: null, closedReason: null, reopenCount: 0, escalatedAt: null, messages: [],
      createdAt, lastActivityAt: ago(activityHoursAgo ?? createdHoursAgo), ...rest,
    });
  };

  add({
    customerId: 1, branchId: 1, topic: 'GROOMING', status: 'OPEN', createdHoursAgo: 3,
    content: 'Bé Mochi bị cắt tỉa lông quá ngắn so với mẫu em đã trao đổi trước khi làm, mong chi nhánh kiểm tra lại.',
    messages: [customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 3)],
  });
  add({
    customerId: 1, branchId: 1, topic: 'SERVICE_QUALITY', status: 'IN_PROGRESS', handlerId: 3, createdHoursAgo: 50, activityHoursAgo: 6,
    relatedType: 'BOARDING', relatedId: 5,
    content: 'Lần gửi bé Bim vừa rồi nhật ký chăm sóc ghi thiếu nhiều bữa ăn, em muốn biết bé có được cho ăn đủ không.',
    messages: [
      customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 50),
      line('STAFF', 3, 'STATUS', 'Quản lý đã nhận xử lý khiếu nại.', 45),
      line('STAFF', 3, 'MESSAGE', 'Chào chị, chi nhánh đã ghi nhận và đang đối chiếu nhật ký với camera. Em sẽ phản hồi chị trong hôm nay.', 44),
      customerLine(1, 'MESSAGE', 'Cảm ơn anh, em chờ phản hồi ạ.', 6),
    ],
  });
  add({
    customerId: 1, branchId: 1, topic: 'PURCHASE', status: 'RESOLVED', handlerId: 3, createdHoursAgo: 120, activityHoursAgo: 48,
    relatedType: 'ONLINE_ORDER', relatedId: 5001, resolvedAt: ago(48),
    content: 'Đơn hàng online giao thiếu một gói thức ăn so với hóa đơn.',
    resolution: 'Chi nhánh đã gửi bù gói thức ăn còn thiếu và gửi lời xin lỗi tới chị.',
    messages: [
      customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 120),
      line('STAFF', 3, 'STATUS', 'Quản lý đã nhận xử lý khiếu nại.', 100),
      line('STAFF', 3, 'STATUS', 'Quản lý đã giải quyết khiếu nại.', 48),
    ],
  });
  add({
    customerId: 1, branchId: 1, topic: 'BOARDING', status: 'CLOSED', handlerId: 3, createdHoursAgo: 15 * 24, activityHoursAgo: 11 * 24,
    relatedType: 'BOARDING', relatedId: 5, resolvedAt: ago(12 * 24), closedAt: ago(11 * 24), closedReason: 'CUSTOMER',
    content: 'Chuồng của bé Bim có mùi khó chịu khi em đến đón.',
    resolution: 'Chi nhánh đã vệ sinh và khử khuẩn khu chuồng, đồng thời nhắc nhở nhân viên.',
    messages: [
      customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 15 * 24),
      line('STAFF', 3, 'STATUS', 'Quản lý đã giải quyết khiếu nại.', 12 * 24),
      customerLine(1, 'STATUS', 'Khách đã đóng khiếu nại.', 11 * 24),
    ],
  });
  add({
    customerId: 1, branchId: 1, topic: 'STAFF', status: 'CLOSED', createdHoursAgo: 9 * 24, activityHoursAgo: 8 * 24, closedAt: ago(8 * 24), closedReason: 'WITHDRAWN',
    content: 'Nhân viên quầy trả lời em hơi cộc lốc khi em hỏi giá dịch vụ.',
    messages: [customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 9 * 24), customerLine(1, 'STATUS', 'Khách đã rút khiếu nại.', 8 * 24)],
  });
  add({
    customerId: 1, branchId: 1, topic: 'SERVICE_QUALITY', status: 'CLOSED', handlerId: 3, createdHoursAgo: 20 * 24, activityHoursAgo: 9 * 24,
    resolvedAt: ago(16 * 24), closedAt: ago(9 * 24), closedReason: 'AUTO',
    content: 'Thời gian chờ khám buổi sáng quá lâu so với giờ hẹn.',
    resolution: 'Chi nhánh đã bố trí thêm bác sĩ vào khung giờ cao điểm.',
    messages: [
      customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 20 * 24),
      line('STAFF', 3, 'STATUS', 'Quản lý đã giải quyết khiếu nại.', 16 * 24),
      line('SYSTEM', null, 'STATUS', 'Khiếu nại tự đóng vì khách không phản hồi sau 7 ngày.', 9 * 24),
    ],
  });
  add({
    customerId: 1, branchId: 1, topic: 'PURCHASE', status: 'OPEN', createdHoursAgo: 30, escalatedAt: ago(6),
    content: 'Em đã mua hai túi hạt nhưng chi nhánh chỉ xuất hóa đơn một túi, em muốn được xuất đủ.',
    messages: [customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 30), line('SYSTEM', null, 'STATUS', 'Khiếu nại quá hạn nhận xử lý, đã chuyển lên quản lý chuỗi.', 6)],
  });
  add({
    customerId: 1, branchId: 1, topic: 'GROOMING', status: 'RESOLVED', handlerId: 3, createdHoursAgo: 6 * 24, activityHoursAgo: 20, reopenCount: 2,
    resolvedAt: ago(20),
    content: 'Bé bị trầy da sau khi tắm, em đã đưa đi khám.',
    resolution: 'Chi nhánh chịu toàn bộ chi phí khám và điều trị, đồng thời rà soát lại quy trình tắm.',
    messages: [
      customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 6 * 24),
      line('STAFF', 3, 'STATUS', 'Quản lý đã giải quyết khiếu nại.', 20),
    ],
  });
  add({
    customerId: 1, branchId: 2, topic: 'BOARDING', status: 'OPEN', createdHoursAgo: 24,
    content: 'Giờ nhận thú tại chi nhánh Hà Đông khác với giờ em được thông báo khi đặt chỗ.',
    messages: [customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 24)],
  });
  add({
    customerId: 2, branchId: 1, topic: 'SERVICE_QUALITY', status: 'OPEN', createdByStaffId: 4, createdHoursAgo: 26,
    content: 'Khách phản ánh tại quầy: hẹn khám lúc 9 giờ nhưng đến 10 giờ 15 mới được gọi tên.',
    messages: [line('STAFF', 4, 'STATUS', 'Lễ tân đã tạo khiếu nại thay khách.', 26)],
  });
  add({
    customerId: 1, branchId: null, topic: 'OTHER', status: 'OPEN', createdHoursAgo: 4,
    content: 'Em muốn góp ý về cách đặt tên và bố trí các gói dịch vụ trên trang web.',
    messages: [customerLine(1, 'STATUS', 'Khách đã gửi khiếu nại.', 4)],
  });
  add({
    customerId: 2, branchId: 1, topic: 'STAFF', status: 'IN_PROGRESS', handlerId: 3, createdHoursAgo: 100, activityHoursAgo: 80, escalatedAt: ago(8),
    content: 'Nhân viên chăm sóc không nghe máy khi em gọi hỏi thăm bé đang lưu trú.',
    messages: [
      customerLine(2, 'STATUS', 'Khách đã gửi khiếu nại.', 100),
      line('STAFF', 3, 'STATUS', 'Quản lý đã nhận xử lý khiếu nại.', 80),
      line('SYSTEM', null, 'STATUS', 'Khiếu nại quá hạn xử lý, đã chuyển lên quản lý chuỗi.', 8),
    ],
  });
  return { complaints };
}

function load(): State {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(STATE_KEY) ?? 'null');
    const stored = raw && typeof raw === 'object' ? (raw as Partial<State>) : {};
    if (Array.isArray(stored.complaints)) return { complaints: stored.complaints.map((c) => ({ ...c, messages: Array.isArray(c.messages) ? c.messages : [] })) };
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  // Ghi ngay: thời gian mẫu tính theo lúc tạo, đọc lại phải ra cùng giá trị.
  const s = seed();
  save(s);
  return s;
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Tiện ích ───

const me = (req: MockRequest) => req.account!.id;
const branchOf = (req: MockRequest) => req.account!.branchId ?? branchIdOf(req.account!.id) ?? -1;
const idOf = (req: MockRequest) => Number(req.params.complaintId);
const isReply = (x: unknown): x is MockReply => typeof (x as MockReply).status === 'number';
const stateError = (from: ComplaintStatus, to: ComplaintStatus) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái khiếu nại từ ${from} sang ${to}`, 409);
const customerName = (customerId: number) => findCustomer(customerId)?.fullName ?? `Khách #${customerId}`;
const isOpenish = (c: ComplaintRow) => c.status === 'OPEN' || c.status === 'IN_PROGRESS';

/** Thêm một dòng vào vết trao đổi và đẩy `lastActivityAt` (hoạt động cuối, BR-DG-08). */
function addLine(s: State, c: ComplaintRow, authorType: ComplaintMessageAuthorType, authorId: number | null, kind: ComplaintMessageKind, body: string): void {
  const at = new Date().toISOString();
  const messageId = Math.max(0, ...s.complaints.flatMap((x) => x.messages.map((m) => m.messageId))) + 1;
  c.messages.push({ messageId, authorType, authorId, kind, body, createdAt: at });
  c.lastActivityAt = at;
}

const notify = (accountIds: (number | null | undefined)[], type: string, title: string, body: string) => {
  for (const accountId of accountIds) if (accountId != null) pushNotification(accountId, { type, title, body, linkUrl: null });
};
/** Người nhận thông báo xử lý (C7): quản lý ACTIVE của chi nhánh, không chi nhánh thì mọi SUPER_MANAGER. */
const handlersOf = (branchId: number | null) =>
  branchId === null ? MOCK_ACCOUNTS.filter((a) => a.role === 'SUPER_MANAGER').map((a) => a.id) : assignableStaffOf(branchId, 'BRANCH_MANAGER').map((m) => m.accountId);

const messageView = (c: ComplaintRow, m: MessageRow): ComplaintMessage => ({
  messageId: m.messageId,
  authorType: m.authorType,
  authorName: m.authorType === 'CUSTOMER' ? customerName(c.customerId) : m.authorType === 'STAFF' && m.authorId !== null ? (staffNameOf(m.authorId) ?? null) : null,
  kind: m.kind,
  body: m.body,
  createdAt: m.createdAt,
});

const complaintView = (c: ComplaintRow): Complaint => ({
  complaintId: c.complaintId,
  code: c.code,
  customerId: c.customerId,
  customerName: customerName(c.customerId),
  branchId: c.branchId,
  topic: c.topic,
  relatedType: c.relatedType,
  relatedId: c.relatedId,
  content: c.content,
  status: c.status,
  createdByStaff: c.createdByStaffId !== null,
  handlerName: c.handlerId === null ? null : (staffNameOf(c.handlerId) ?? `Nhân viên #${c.handlerId}`),
  resolution: c.resolution,
  resolvedAt: c.resolvedAt,
  closedAt: c.closedAt,
  closedReason: c.closedReason,
  reopenCount: c.reopenCount,
  escalatedAt: c.escalatedAt,
  lastActivityAt: c.lastActivityAt,
  createdAt: c.createdAt,
  messages: c.messages.map((m) => messageView(c, m)),
});

const summaryView = (c: ComplaintRow): ComplaintSummary => ({
  complaintId: c.complaintId,
  code: c.code,
  topic: c.topic,
  status: c.status,
  branchId: c.branchId,
  escalated: c.escalatedAt !== null,
  lastActivityAt: c.lastActivityAt,
  createdAt: c.createdAt,
});

// ─── Tạo khiếu nại (#1, #7; BR-DG-05) ───

const createBody = { topic: COMPLAINT_TOPICS, branchId: 'int', relatedType: COMPLAINT_RELATED_TYPES, relatedId: 'int', content: 'text' } as const;
const createChecks = (b: Body) => [
  ['topic', 'Chủ đề không được để trống', b.topic == null],
  ['content', 'Nội dung không được để trống', typeof b.content !== 'string' || b.content.trim() === ''],
  ['relatedId', 'relatedType và relatedId phải cùng có hoặc cùng vắng', has(b.relatedType) !== has(b.relatedId)],
] as [string, string, boolean][];

/** Phần dùng chung của #1 và #7 sau khi đã biết khách; `staffId` null = khách tự gửi. */
function createFor(req: MockRequest, b: Body, customerId: number, staffId: number | null): MockReply {
  const s = load();
  const topic = b.topic as ComplaintTopic;
  const branchId = has(b.branchId) ? Number(b.branchId) : null;
  const relatedType = has(b.relatedType) ? (b.relatedType as ComplaintRelatedType) : null;
  const relatedId = has(b.relatedId) ? Number(b.relatedId) : null;
  if (branchId !== null && !branchBooking(branchId)) return notFound('chi nhánh', branchId);
  const owner = relatedType !== null && relatedId !== null ? relatedOwner(relatedType, relatedId) : undefined;
  if (relatedType !== null && relatedId !== null && owner === undefined) return notFound('đối tượng liên quan', relatedId);

  const content = String(b.content).trim();
  if (content.length < COMPLAINT_LIMITS.contentMin || content.length > COMPLAINT_LIMITS.content) {
    return ruleFail('BR-DG-05', `Nội dung khiếu nại phải từ ${COMPLAINT_LIMITS.contentMin} đến ${COMPLAINT_LIMITS.content} ký tự`);
  }
  if (topic !== 'OTHER' && branchId === null) return ruleFail('BR-DG-05', 'Vui lòng chọn chi nhánh bị khiếu nại');
  if (owner !== undefined && owner !== customerId) return ruleFail('BR-DG-05', 'Đối tượng liên quan không thuộc khách hàng này');
  if (s.complaints.filter((c) => c.customerId === customerId && isOpenish(c)).length >= MAX_OPEN) {
    return ruleFail('BR-DG-05', `Khách chỉ được có tối đa ${MAX_OPEN} khiếu nại đang mở`);
  }

  const at = new Date().toISOString();
  const created: ComplaintRow = {
    complaintId: Math.max(0, ...s.complaints.map((c) => c.complaintId)) + 1, code: codeFor(at, s.complaints), customerId, branchId, topic, relatedType, relatedId,
    content, status: 'OPEN', createdByStaffId: staffId, handlerId: null, resolution: null, resolvedAt: null, closedAt: null, closedReason: null, reopenCount: 0,
    escalatedAt: null, lastActivityAt: at, createdAt: at, messages: [],
  };
  s.complaints.push(created);
  addLine(s, created, staffId === null ? 'CUSTOMER' : 'STAFF', me(req), 'STATUS', staffId === null ? 'Khách đã gửi khiếu nại.' : 'Lễ tân đã tạo khiếu nại thay khách.');
  save(s);
  notify(
    handlersOf(branchId),
    'COMPLAINT_CREATED',
    `Khiếu nại mới ${created.code}`,
    `Khách ${customerName(customerId)} vừa gửi khiếu nại ${created.code} về ${TOPIC_LABELS[topic]}. Vui lòng nhận xử lý.`,
  );
  return ok(complaintView(created), 201, 'Đã gửi khiếu nại');
}

const createMyComplaint = guarded({ roles: CUSTOMER, body: createBody, checks: createChecks }, (req, b) => {
  const customer = customerOfAccount(me(req));
  return customer ? createFor(req, b, customer.customerId, null) : notFound('hồ sơ khách hàng', me(req));
});

const createComplaintForCustomer = guarded(
  { roles: ['RECEPTIONIST'], body: { ...createBody, customerId: 'int' }, checks: (b) => [['customerId', 'Khách hàng không được để trống', b.customerId == null], ...createChecks(b)] },
  (req, b) => {
    if (has(b.branchId) && Number(b.branchId) !== branchOf(req)) return scopeDenied();
    if (!findCustomer(Number(b.customerId))) return notFound('khách hàng', Number(b.customerId));
    return createFor(req, b, Number(b.customerId), me(req));
  },
);

// ─── Khách (#2–6) ───

const mineId = (req: MockRequest) => customerOfAccount(me(req))?.customerId ?? -1;

/** Khiếu nại của khách: không có hoặc của người khác đều 404 (ADR-0006). */
function mineFor(req: MockRequest, s: State): ComplaintRow | MockReply {
  const id = idOf(req);
  return s.complaints.find((c) => c.complaintId === id && c.customerId === mineId(req)) ?? notFound('khiếu nại', id);
}

const STATUS_QUERY = [['status', COMPLAINT_STATUSES]] as const;

const listMyComplaints = guarded({ roles: CUSTOMER, query: [...STATUS_QUERY, ...PAGING] }, (req) => {
  const { status } = req.query;
  const rows = load()
    .complaints.filter((c) => c.customerId === mineId(req) && (status === undefined || c.status === status))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.complaintId - a.complaintId);
  return ok(pageOf(rows.map(summaryView), clampPaging(req.query)));
});

const getMyComplaint = guarded({ roles: CUSTOMER, params: ['complaintId'] }, (req) => {
  const c = mineFor(req, load());
  return isReply(c) ? c : ok(complaintView(c));
});

const bodyChecks = (b: Body) => textChecks('body', 'Nội dung', b.body, COMPLAINT_LIMITS.body);

const postMyComplaintMessage = guarded({ roles: CUSTOMER, params: ['complaintId'], body: { body: 'text' }, checks: bodyChecks }, (req, b) => {
  const s = load();
  const c = mineFor(req, s);
  if (isReply(c)) return c;
  if (c.status === 'CLOSED') return fail('INVALID_STATE_TRANSITION', 'Khiếu nại đã đóng, không thể trao đổi thêm', 409);
  addLine(s, c, 'CUSTOMER', me(req), 'MESSAGE', String(b.body).trim());
  save(s);
  return ok(complaintView(c), 201, 'Đã gửi tin nhắn');
});

const closeMyComplaint = guarded({ roles: CUSTOMER, params: ['complaintId'] }, (req) => {
  const s = load();
  const c = mineFor(req, s);
  if (isReply(c)) return c;
  if (c.status === 'CLOSED') return stateError(c.status, 'CLOSED');
  const withdrawn = isOpenish(c);
  Object.assign(c, { status: 'CLOSED', closedAt: new Date().toISOString(), closedReason: withdrawn ? 'WITHDRAWN' : 'CUSTOMER' });
  addLine(s, c, 'CUSTOMER', me(req), 'STATUS', withdrawn ? 'Khách đã rút khiếu nại.' : 'Khách đã đóng khiếu nại.');
  save(s);
  return ok(complaintView(c), 200, withdrawn ? 'Đã rút khiếu nại' : 'Đã đóng khiếu nại');
});

const reopenMyComplaint = guarded({ roles: CUSTOMER, params: ['complaintId'], body: { body: 'text' }, checks: bodyChecks }, (req, b) => {
  const s = load();
  const c = mineFor(req, s);
  if (isReply(c)) return c;
  if (isOpenish(c)) return stateError(c.status, 'OPEN');
  if (c.closedReason === 'WITHDRAWN') return ruleFail('BR-DG-07', 'Khiếu nại đã rút nên không mở lại được');
  if (Date.now() - Date.parse(c.resolvedAt ?? c.closedAt ?? c.createdAt) > REOPEN_DAYS * DAY) {
    return ruleFail('BR-DG-07', `Chỉ mở lại khiếu nại trong ${REOPEN_DAYS} ngày kể từ lúc được giải quyết`);
  }
  if (c.reopenCount >= MAX_REOPEN) return ruleFail('BR-DG-07', `Khiếu nại chỉ được mở lại tối đa ${MAX_REOPEN} lần`);
  Object.assign(c, { status: 'OPEN', handlerId: null, closedAt: null, closedReason: null, reopenCount: c.reopenCount + 1 });
  addLine(s, c, 'CUSTOMER', me(req), 'STATUS', `Khách đã mở lại khiếu nại (lần ${c.reopenCount}).`);
  addLine(s, c, 'CUSTOMER', me(req), 'MESSAGE', String(b.body).trim());
  save(s);
  notify(
    handlersOf(c.branchId),
    'COMPLAINT_REOPENED',
    `Khiếu nại ${c.code} được mở lại`,
    `Khách ${customerName(c.customerId)} đã mở lại khiếu nại ${c.code} (lần ${c.reopenCount}). Vui lòng nhận xử lý.`,
  );
  return ok(complaintView(c), 200, 'Đã mở lại khiếu nại');
});

// ─── Nhân viên (#8–12) ───

/** Khiếu nại trong phạm vi người gọi (BR-DG-06): SUPER_MANAGER mọi cái, BRANCH_MANAGER chi nhánh mình, lễ tân cái mình tạo. */
function inScope(req: MockRequest, c: ComplaintRow): boolean {
  const { role } = req.account!;
  if (role === 'SUPER_MANAGER') return true;
  if (role === 'BRANCH_MANAGER') return c.branchId !== null && c.branchId === branchOf(req);
  return c.createdByStaffId === me(req);
}

/** Theo id: không có → 404, ngoài phạm vi → 403. */
function complaintFor(req: MockRequest, s: State): ComplaintRow | MockReply {
  const id = idOf(req);
  const c = s.complaints.find((x) => x.complaintId === id);
  if (!c) return notFound('khiếu nại', id);
  return inScope(req, c) ? c : scopeDenied();
}

const listComplaints = guarded({ roles: REPLYING, query: [...STATUS_QUERY, ['escalated', 'bool'], ...PAGING] }, (req) => {
  const { status } = req.query;
  const escalated = flagOf(req.query.escalated);
  const rows = load()
    .complaints.filter(
      (c) =>
        inScope(req, c) &&
        (status === undefined ? isOpenish(c) : c.status === status) &&
        (escalated === undefined || (c.escalatedAt !== null) === escalated),
    )
    .sort((a, b) => a.lastActivityAt.localeCompare(b.lastActivityAt) || a.complaintId - b.complaintId);
  return ok(pageOf(rows.map(summaryView), clampPaging(req.query)));
});

const getComplaint = guarded({ roles: REPLYING, params: ['complaintId'] }, (req) => {
  const c = complaintFor(req, load());
  return isReply(c) ? c : ok(complaintView(c));
});

const takeComplaint = guarded({ roles: HANDLING, params: ['complaintId'] }, (req) => {
  const s = load();
  const c = complaintFor(req, s);
  if (isReply(c)) return c;
  if (c.status !== 'OPEN') return stateError(c.status, 'IN_PROGRESS');
  Object.assign(c, { status: 'IN_PROGRESS', handlerId: me(req) });
  addLine(s, c, 'STAFF', me(req), 'STATUS', 'Quản lý đã nhận xử lý khiếu nại.');
  save(s);
  notify([findCustomer(c.customerId)?.accountId], 'COMPLAINT_TAKEN', `Khiếu nại ${c.code} đã được tiếp nhận`, `Khiếu nại ${c.code} của bạn đã được tiếp nhận và đang xử lý.`);
  return ok(complaintView(c), 200, 'Đã nhận xử lý khiếu nại');
});

const postComplaintMessage = guarded({ roles: REPLYING, params: ['complaintId'], body: { body: 'text' }, checks: bodyChecks }, (req, b) => {
  const s = load();
  const c = complaintFor(req, s);
  if (isReply(c)) return c;
  if (c.status === 'CLOSED') return fail('INVALID_STATE_TRANSITION', 'Khiếu nại đã đóng, không thể trao đổi thêm', 409);
  addLine(s, c, 'STAFF', me(req), 'MESSAGE', String(b.body).trim());
  save(s);
  return ok(complaintView(c), 201, 'Đã gửi tin nhắn');
});

const resolveComplaint = guarded(
  {
    roles: HANDLING,
    params: ['complaintId'],
    body: { resolution: 'text' },
    // Thiếu kết luận là 400 BR-DG-07 (yaml), không phải VALIDATION_FAILED: chỉ @Size chạy ở đây.
    checks: (b) => [['resolution', `Kết luận tối đa ${COMPLAINT_LIMITS.resolution} ký tự`, typeof b.resolution === 'string' && b.resolution.length > COMPLAINT_LIMITS.resolution]],
  },
  (req, b) => {
    const s = load();
    const c = complaintFor(req, s);
    if (isReply(c)) return c;
    if (typeof b.resolution !== 'string' || b.resolution.trim() === '') return ruleFail('BR-DG-07', 'Vui lòng nhập kết luận');
    if (c.status !== 'IN_PROGRESS') return stateError(c.status, 'RESOLVED');
    const resolution = b.resolution.trim();
    Object.assign(c, { status: 'RESOLVED', resolution, resolvedAt: new Date().toISOString() });
    addLine(s, c, 'STAFF', me(req), 'STATUS', 'Quản lý đã giải quyết khiếu nại.');
    save(s);
    notify(
      [findCustomer(c.customerId)?.accountId],
      'COMPLAINT_RESOLVED',
      `Khiếu nại ${c.code} đã được giải quyết`,
      `Khiếu nại ${c.code} đã được giải quyết: ${resolution} Bạn có thể mở lại trong ${REOPEN_DAYS} ngày.`,
    );
    return ok(complaintView(c), 200, 'Đã kết luận khiếu nại');
  },
);

export const complaintRoutes: Record<string, MockHandler> = {
  'POST /api/me/complaints': createMyComplaint,
  'GET /api/me/complaints': listMyComplaints,
  'GET /api/me/complaints/:complaintId': getMyComplaint,
  'POST /api/me/complaints/:complaintId/messages': postMyComplaintMessage,
  'POST /api/me/complaints/:complaintId/close': closeMyComplaint,
  'POST /api/me/complaints/:complaintId/reopen': reopenMyComplaint,
  'POST /api/complaints': createComplaintForCustomer,
  'GET /api/complaints': listComplaints,
  'GET /api/complaints/:complaintId': getComplaint,
  'POST /api/complaints/:complaintId/take': takeComplaint,
  'POST /api/complaints/:complaintId/messages': postComplaintMessage,
  'POST /api/complaints/:complaintId/resolve': resolveComplaint,
};
