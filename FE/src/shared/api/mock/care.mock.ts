// Mock care-v1: việc gọi điện của lễ tân (#1–4, UC87) và #5–10 (UC88): thông báo trong ứng dụng của người đang đăng nhập
// và cài đặt nhận thông báo của khách.
// Thông báo: mọi role đã đăng nhập, kể cả ADMIN (01 UC88 A02–A08; BE MyNotificationController `isAuthenticated()`,
// docs/adr/0039 §1). Mới nhất trước, trùng giờ thì id lớn trước; `page` < 0 → 0, `size` kẹp 1–100; đánh dấu đã đọc
// giữ `readAt` lần đầu; thông báo của người khác → 404 cùng câu với id không tồn tại.
// Cài đặt (#9, #10): chỉ CUSTOMER; BE chưa làm (nợ D025) nên mock là nguồn — nội dung theo care-v1 A1, chờ PO (Q1).
// Thứ tự kiểm như BE (00-method §3.5): dữ liệu vào → quyền.
import type {
  AppNotification,
  CareTask,
  CareTaskResult,
  CareTaskStatus,
  CareTaskType,
  ChannelToggle,
  NotificationSettings,
  ReminderKind,
} from '../../types/care';
import { CARE_TASK_LIMITS, CARE_TASK_RESULTS, CARE_TASK_STATUSES, CARE_TASK_TYPES, CHANNELS, REMINDER_KINDS } from '../../types/care';
import { vnDate } from '../../utils/vn-time';
import { findCustomer } from './customer.mock';
import { petSummary } from './customer-pet.mock';
import {
  denied,
  fail,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  ruleFail,
  scopeDenied,
  tooLong,
  type Check,
  type MockHandler,
  type MockReply,
  type MockRequest,
} from './envelope';
import { clampPaging, pageOf, typeError } from './public.mock';

interface NotificationRow extends AppNotification {
  accountId: number;
}
/** Dòng `care_tasks` (05-erd) cộng SĐT khẩn của đặt chỗ lưu trú; mock chưa có đặt chỗ lưu trú nên SĐT khẩn giữ ở đây. */
interface CareTaskRow extends Omit<CareTask, 'customerName' | 'petName' | 'phoneToCall'> {
  emergencyPhone: string | null;
}
interface State {
  notifications: NotificationRow[];
  /** accounts.notification_settings; vắng = mặc định (05 DEFAULT '{}'). */
  settings: Record<number, NotificationSettings>;
  /** care_tasks (UC87). State lưu từ trước T47 chưa có trường này: `load()` thêm dữ liệu mẫu. */
  careTasks: CareTaskRow[];
}

/** Mặc định bật hết: cột ERD DEFAULT '{}' và docs không nói loại nào tắt sẵn (giả định, chờ Q1). */
export const DEFAULT_SETTINGS: NotificationSettings = {
  appointmentReminder: { email: true, inApp: true },
  vaccineReminder: { email: true, inApp: true },
  followUpReminder: { email: true, inApp: true },
};

// ─── Dữ liệu mẫu (thời gian tương đối với lúc tạo) ───

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const pad = (n: number) => String(n).padStart(2, '0');

/** Giờ Việt Nam (UTC+7, không có giờ mùa hè) như biến mẫu của BE: `HH:mm dd/MM/yyyy`, `dd/MM/yyyy`, `yyMMdd`. */
function vn(ms: number) {
  const d = new Date(ms + 7 * HOUR);
  const date = `${pad(d.getUTCDate())}/${pad(d.getUTCMonth() + 1)}/${d.getUTCFullYear()}`;
  return {
    date,
    dateTime: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} ${date}`,
    code: `${String(d.getUTCFullYear()).slice(2)}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}`,
  };
}

/** Lúc hẹn: ngày `ms` theo giờ Việt Nam, giờ cố định `hh:mm`. */
function at(ms: number, hh: number, mm = 0) {
  const d = new Date(ms + 7 * HOUR);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), hh - 7, mm);
}

const BRANCH = { name: 'PetCare Cầu Giấy', address: '123 Trần Duy Hưng, Cầu Giấy, Hà Nội', phone: '0901234567' };

type Draft = Omit<AppNotification, 'notificationId' | 'createdAt' | 'readAt'> & { createdAt: number; read: boolean };

// Nội dung theo mẫu IN_APP đã seed ở BE (V12, V17–V20) và linkUrl như BE gửi. Mẫu trong danh sách 06 §8 Q4 nhưng
// BE chưa seed (VACCINE_REMINDER, FOLLOW_UP_REMINDER, CARE_LOG_ABNORMAL, BOARDING_CAPACITY_WARNING,
// STOCK_ALERT_DIGEST): tiêu đề / nội dung / linkUrl tạm của FE, thay khi BE seed.
const appointmentReminder = (createdAt: number, pet: string, service: string, code: string, read: boolean): Draft => {
  const when = vn(at(createdAt + DAY, 9, 30)).dateTime;
  return {
    type: 'APPOINTMENT_REMINDER',
    title: `Nhắc lịch hẹn của ${pet}`,
    body:
      `${pet} có lịch hẹn ${service} lúc ${when} tại ${BRANCH.name} (${BRANCH.address}). Mã lịch hẹn: ${code}.\n` +
      `Nếu không thể đến, vui lòng hủy hoặc đổi lịch sớm, hoặc gọi chi nhánh theo số ${BRANCH.phone}.`,
    linkUrl: '/appointments',
    createdAt,
    read,
  };
};

const appointmentCancelled = (createdAt: number, pet: string, service: string, reason: string, read: boolean): Draft => {
  const when = vn(at(createdAt + 3 * DAY, 14)).dateTime;
  return {
    type: 'APPOINTMENT_CANCELLED_BY_CLINIC',
    title: `Phòng khám đã hủy lịch hẹn của ${pet}`,
    body: `Lịch hẹn ${service} của ${pet} tại ${BRANCH.name} lúc ${when} đã bị hủy.\nLý do: ${reason}\nLần hủy này không tính là hủy muộn.`,
    linkUrl: '/appointments',
    createdAt,
    read,
  };
};

/** BR-TB-01: link mở form đặt lịch; FE chưa có tham số điền sẵn thú + dịch vụ nên tạm trỏ `/booking`. */
const vaccineReminder = (createdAt: number, pet: string, vaccine: string, read: boolean): Draft => ({
  type: 'VACCINE_REMINDER',
  title: `${pet} sắp đến hạn tiêm nhắc ${vaccine}`,
  body: `Mũi ${vaccine} của ${pet} đến hạn tiêm nhắc ngày ${vn(createdAt + 7 * DAY).date}. Đặt lịch tiêm để giữ hiệu lực bảo vệ cho bé.`,
  linkUrl: '/booking',
  createdAt,
  read,
});

/** BR-TB-06: link mở form đặt lịch đã chọn sẵn thú và chi nhánh — tạm `/booking` như trên. */
const followUpReminder = (createdAt: number, pet: string, read: boolean): Draft => ({
  type: 'FOLLOW_UP_REMINDER',
  title: `${pet} sắp đến ngày tái khám`,
  body: `Bác sĩ đã hẹn ${pet} tái khám ngày ${vn(createdAt + 3 * DAY).date} tại ${BRANCH.name}. Đặt lịch khám để bác sĩ theo dõi tiếp tình trạng của bé.`,
  linkUrl: '/booking',
  createdAt,
  read,
});

/** BE gửi linkUrl `/boarding-bookings` (BoardingClinicCancellationNotices) nhưng FE chưa có trang đó: mock để null. */
const boardingCancelled = (createdAt: number, pet: string, read: boolean): Draft => {
  const checkIn = vn(createdAt + 5 * DAY);
  return {
    type: 'BOARDING_CANCELLED_BY_CLINIC',
    title: `Phòng khám đã hủy đặt chỗ lưu trú của ${pet}`,
    body:
      `Đặt chỗ LT-${checkIn.code.slice(0, 4)}-000${checkIn.code.slice(4)} của ${pet} tại ${BRANCH.name} (nhận ${checkIn.date}, trả ${vn(createdAt + 8 * DAY).date}) đã bị hủy.\n` +
      'Lý do: Chi nhánh nghỉ vào ngày nhận hoặc ngày trả của đặt chỗ\nLần hủy này không tính là hủy muộn.',
    linkUrl: null,
    createdAt,
    read,
  };
};

/** V19, ST13: BE không kèm linkUrl. */
const pendingOrdersDigest = (createdAt: number, orders: number, read: boolean): Draft => ({
  type: 'PENDING_ORDERS_DIGEST',
  title: `${orders} Order chờ thu tiền quá hạn tại ${BRANCH.name}`,
  body: `Chi nhánh ${BRANCH.name} có ${orders} Order đang chờ thu tiền từ 3 ngày trở lên, cũ nhất chốt ngày ${vn(createdAt - 6 * DAY).date}. Vui lòng xem danh sách Order quá hạn để xử lý.`,
  linkUrl: null,
  createdAt,
  read,
});

const cashierShiftAutoClosed = (createdAt: number, shiftId: number, read: boolean): Draft => ({
  type: 'CASHIER_SHIFT_AUTO_CLOSED',
  title: `Ca thu ngân ${shiftId} đã được tự chốt`,
  body: `Ca thường ${shiftId} của Lan Chi tại ${BRANCH.name} (ngày ${vn(createdAt - DAY).date}) chưa được chốt nên hệ thống đã tự chốt lúc ${vn(createdAt).dateTime}. Vui lòng nhập số tiền thực đếm khi đối soát ca.`,
  linkUrl: null,
  createdAt,
  read,
});

const accountLockedFollowUp = (createdAt: number, who: string, work: string, read: boolean): Draft => ({
  type: 'ACCOUNT_LOCKED_FOLLOW_UP',
  title: 'Tài khoản đã bị khóa — cần xử lý việc dở dang',
  body: `Tài khoản ${who} đã bị quản trị viên khóa lúc ${vn(createdAt).dateTime}.\nLý do: Nghỉ việc, bàn giao chưa xong\n\nViệc dở dang cần xử lý tiếp:\n${work}`,
  linkUrl: null,
  createdAt,
  read,
});

function seed(now = Date.now()): State {
  const drafts: [accountId: number, draft: Draft][] = [];
  const add = (accountId: number, draft: Draft) => drafts.push([accountId, draft]);

  // Khách khach@petcare.vn (#7): 26 thông báo trong ~2 tháng, 6 chưa đọc (1 ở trang 2) — đủ để thử "Xem thêm".
  const PETS = ['Mochi', 'Bim', 'Tôm'];
  for (let i = 20; i >= 0; i--) {
    const createdAt = now - (5 + i * 3) * DAY - (i % 4) * HOUR;
    const pet = PETS[i % PETS.length];
    const read = i !== 18; // một thông báo cũ chưa đọc, nằm ở trang 2 (size 20)
    const pick = i % 5;
    if (pick === 0) add(7, appointmentReminder(createdAt, pet, 'Khám tổng quát', `LH-${vn(createdAt).code}-${100 + i}`, read));
    else if (pick === 1) add(7, vaccineReminder(createdAt, pet === 'Tôm' ? 'Bim' : pet, pet === 'Mochi' ? '4 bệnh cho mèo' : 'Dại', read));
    else if (pick === 2) add(7, followUpReminder(createdAt, pet, read));
    else if (pick === 3) add(7, appointmentReminder(createdAt, pet, 'Tắm & cắt tỉa', `LH-${vn(createdAt).code}-${100 + i}`, read));
    else add(7, boardingCancelled(createdAt, pet, read));
  }
  // Hai lịch bị hủy cùng lúc (chi nhánh thêm ngày nghỉ): trùng createdAt, id lớn hơn đứng trước.
  const closedAt = now - 2 * DAY;
  add(7, appointmentCancelled(closedAt, 'Tôm', 'Khám tổng quát', 'Chi nhánh nghỉ vào ngày hẹn', false));
  add(7, appointmentCancelled(closedAt, 'Bim', 'Tiêm phòng', 'Chi nhánh nghỉ vào ngày hẹn', false));
  add(7, followUpReminder(now - DAY, 'Mochi', false));
  add(7, vaccineReminder(now - 3 * HOUR, 'Mochi', '4 bệnh cho mèo', false));
  add(7, appointmentReminder(now - 20 * MIN, 'Bim', 'Khám tổng quát', `LH-${vn(now).code}-7`, false));

  // Quản lý chi nhánh 1 (#3): ST13 + khóa tài khoản lễ tân (BR-QT-11) + tồn kho (ST08, mẫu chưa seed).
  add(3, accountLockedFollowUp(now - 9 * DAY, 'reception2@store1.vn (Trần Mai, Lễ tân)', '- Ca thu ngân #12: đang mở', true));
  add(3, cashierShiftAutoClosed(now - 6 * DAY, 12, true));
  add(3, {
    type: 'STOCK_ALERT_DIGEST',
    title: `Cảnh báo tồn kho tại ${BRANCH.name}`,
    body: '2 sản phẩm dưới tồn tối thiểu (Pate Whiskas 85g, Vaccine 5 bệnh Vanguard) và 1 lô sắp hết hạn trong 30 ngày (Amoxicillin 250mg lô AMX2405).',
    linkUrl: '/admin/warehouse/stock',
    createdAt: now - 26 * HOUR,
    read: false,
  });
  add(3, pendingOrdersDigest(now - 2 * HOUR, 3, false));

  // Quản lý chuỗi (#2): quản lý cuối của chi nhánh bị khóa (BR-QT-04).
  add(2, {
    type: 'LAST_BRANCH_MANAGER_LOCKED',
    title: 'Chi nhánh không còn quản lý đang hoạt động',
    body:
      `Quản lý cuối cùng của chi nhánh PetCare Hà Đông là Phạm Minh Đức (manager2@store2.vn) đã bị quản trị viên khóa lúc ${vn(now - 4 * DAY).dateTime}.\n` +
      'Lý do: Tạm đình chỉ chờ xác minh\n\nChi nhánh vẫn hoạt động bình thường. Hãy bổ nhiệm quản lý thay thế hoặc chờ tài khoản được mở khóa.\n\n' +
      'Việc dở dang cần xử lý tiếp:\nKhông có',
    linkUrl: null,
    createdAt: now - 4 * DAY,
    read: false,
  });

  // Lễ tân chi nhánh 1 (#4): việc dở của khách bị khóa (06 Q2), nhật ký lưu trú bất thường và sức chứa (mẫu chưa seed).
  add(4, accountLockedFollowUp(now - 3 * DAY, 'khachcu@gmail.com (Vũ Hải, Khách hàng)', '- Lịch hẹn #58: đã đặt\n- Order #231: chưa thanh toán', true));
  add(4, {
    type: 'BOARDING_CAPACITY_WARNING',
    title: 'Chuồng lưu trú sắp kín',
    body: `Chuồng mèo tại ${BRANCH.name} còn 1 chỗ trống cho ngày ${vn(now + DAY).date}. Cân nhắc trước khi nhận thêm thú lưu trú.`,
    linkUrl: '/staff/reception',
    createdAt: now - 5 * HOUR,
    read: false,
  });
  add(4, {
    type: 'CARE_LOG_ABNORMAL',
    title: 'Nhật ký chăm sóc bất thường: Lu',
    body: 'Lu (chủ: Trần Văn Bình) bỏ ăn bữa sáng và nôn một lần. Vui lòng liên hệ chủ thú và báo bác sĩ.',
    linkUrl: '/staff/reception',
    createdAt: now - 40 * MIN,
    read: false,
  });

  // id tăng theo thứ tự tạo như cột serial; thông báo đã đọc thì đọc sau khi nhận 2 giờ.
  drafts.sort(([, a], [, b]) => a.createdAt - b.createdAt);
  const notifications = drafts.map(([accountId, { createdAt, read, ...rest }], i): NotificationRow => ({
    ...rest,
    notificationId: i + 1,
    accountId,
    createdAt: new Date(createdAt).toISOString(),
    readAt: read ? new Date(Math.min(createdAt + 2 * HOUR, now)).toISOString() : null,
  }));
  return { notifications, settings: {}, careTasks: seedCareTasks(now) };
}

/**
 * 9 việc gọi điện của chi nhánh 1 (lễ tân reception@store1.vn, id 4): 5 đang chờ, 2 đã xong, 2 đã hủy, đủ 4 loại. Hạn tính theo
 * ngày Việt Nam so với lúc tạo. Mảng xếp id giảm dần (#6 và #7 cùng hạn) để thứ tự trả về do sắp xếp chứ không do thứ tự mảng.
 * Khóa liên kết (mũi tiêm, bệnh án, đặt chỗ lưu trú) là id giả: mock tiêm chủng / bệnh án / lưu trú không cần có dòng tương ứng.
 * #6 không có SĐT khẩn để thử giao diện "chưa có số điện thoại" (ERD đặt emergency_phone NN, hợp đồng cho `phoneToCall` vắng).
 */
function seedCareTasks(now = Date.now()): CareTaskRow[] {
  const date = (days: number) => vnDate(now + days * DAY);
  const text = (days: number) => vn(now + days * DAY).date;
  const when = (days: number, hh: number, mm = 0) => new Date(at(now + days * DAY, hh, mm)).toISOString();
  type Seed = Pick<CareTaskRow, 'careTaskId' | 'type' | 'status' | 'customerId' | 'petId' | 'dueDate' | 'context' | 'createdAt'> & Partial<CareTaskRow>;
  const task = (t: Seed): CareTaskRow => ({
    branchId: 1,
    vaccinationId: null,
    medicalRecordVisitId: null,
    boardingBookingId: null,
    result: null,
    note: null,
    cancelReason: null,
    handledBy: null,
    handledAt: null,
    emergencyPhone: null,
    ...t,
  });
  const RECEPTION = 4;
  return [
    task({ careTaskId: 9, type: 'VACCINE_DUE', status: 'OPEN', customerId: 4, petId: 5, vaccinationId: 706, dueDate: date(2), createdAt: when(-5, 6), context: `Mũi 2 vaccine 4 bệnh cho mèo, đến hạn ${text(2)}` }),
    task({ careTaskId: 8, type: 'FOLLOW_UP_DUE', status: 'OPEN', customerId: 4, petId: 5, medicalRecordVisitId: 1104, dueDate: date(0), createdAt: when(-3, 6), context: `Hẹn tái khám ngày ${text(0)} sau đợt điều trị viêm da` }),
    task({ careTaskId: 7, type: 'PICKUP_OVERDUE', status: 'OPEN', customerId: 1, petId: 2, boardingBookingId: 3002, emergencyPhone: '0987000111', dueDate: date(-1), createdAt: when(-1, 6), context: `Đặt chỗ LT-2610-00032: dự kiến trả ngày ${text(-1)}, chưa đến đón` }),
    task({ careTaskId: 6, type: 'PICKUP_OVERDUE', status: 'OPEN', customerId: 1, petId: 3, boardingBookingId: 3001, dueDate: date(-1), createdAt: when(-1, 6), context: `Đặt chỗ LT-2610-00031: dự kiến trả ngày ${text(-1)}, chưa đến đón` }),
    task({ careTaskId: 5, type: 'VACCINE_OVERDUE', status: 'OPEN', customerId: 3, petId: 4, vaccinationId: 705, dueDate: date(-3), createdAt: when(-3, 6), context: `Vaccine Dại: hạn tiêm nhắc ${text(-10)}, đã quá 7 ngày chưa tiêm lại` }),
    // Hệ thống hủy khi mũi đã được tiêm lại (BR-TB-03): không có người xử lý.
    task({ careTaskId: 4, type: 'VACCINE_OVERDUE', status: 'CANCELLED', customerId: 1, petId: 1, vaccinationId: 704, dueDate: date(-4), createdAt: when(-4, 6), cancelReason: 'Đã tiêm lại (BR-TB-03)', context: `Vaccine 4 bệnh cho mèo: hạn tiêm nhắc ${text(-11)}, đã quá 7 ngày chưa tiêm lại` }),
    task({ careTaskId: 3, type: 'PICKUP_OVERDUE', status: 'CANCELLED', customerId: 3, petId: 4, boardingBookingId: 3003, emergencyPhone: '0905111222', dueDate: date(-5), createdAt: when(-5, 6), cancelReason: 'Khách đã đến đón thú lúc 17:30, chưa kịp cập nhật hệ thống', handledBy: RECEPTION, handledAt: when(-5, 17, 45), context: `Đặt chỗ LT-2610-00028: dự kiến trả ngày ${text(-5)}, chưa đến đón` }),
    task({ careTaskId: 2, type: 'VACCINE_DUE', status: 'DONE', customerId: 4, petId: 5, vaccinationId: 702, dueDate: date(-30), createdAt: when(-37, 6), result: 'REACHED', note: 'Khách hẹn đưa Miu đến tiêm trong tuần này.', handledBy: RECEPTION, handledAt: when(-30, 10, 15), context: `Mũi 1 vaccine 4 bệnh cho mèo, đến hạn ${text(-30)}` }),
    task({ careTaskId: 1, type: 'VACCINE_OVERDUE', status: 'DONE', customerId: 1, petId: 2, vaccinationId: 703, dueDate: date(-6), createdAt: when(-6, 6), result: 'UNREACHABLE', note: 'Gọi hai lần không nghe máy, đã nhắn tin để lại lời nhắn.', handledBy: RECEPTION, handledAt: when(-6, 14, 40), context: `Vaccine 5 bệnh: hạn tiêm nhắc ${text(-13)}, đã quá 7 ngày chưa tiêm lại` }),
  ];
}

const STATE_KEY = 'petcare-mock-care';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) {
      const stored = JSON.parse(raw) as Partial<State>;
      if (stored.careTasks) return stored as State;
      // State lưu trong trình duyệt từ trước T47 chỉ có notifications, settings: giữ nguyên, thêm Care Task mẫu và ghi lại ngay.
      const merged: State = { notifications: [], settings: {}, ...stored, careTasks: seedCareTasks() };
      save(merged);
      return merged;
    }
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  // Ghi ngay: thời gian mẫu tính theo lúc tạo, đọc lại phải ra cùng createdAt / readAt.
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

// ─── Cho mock lưu trú (T48): như NotificationApi.enqueue (IN_APP) của BE, chỉ thêm export ───

/** Nội dung một thông báo trong ứng dụng; `type` là mã mẫu bỏ hậu tố `_APP` (vd `CARE_LOG_ABNORMAL`). */
export interface InAppNotice {
  type: string;
  title: string;
  body: string;
  linkUrl: string | null;
}

/** Thêm thông báo chưa đọc, createdAt = bây giờ, cho người nhận (id tăng như cột serial). Gọi cho khách có tài khoản và nhân viên; hồ sơ không có tài khoản thì không có thông báo trong ứng dụng (BR-TB-02). */
export function pushNotification(accountId: number, notice: InAppNotice): void {
  const s = load();
  s.notifications.push({
    ...notice,
    notificationId: Math.max(0, ...s.notifications.map((n) => n.notificationId)) + 1,
    accountId,
    createdAt: new Date().toISOString(),
    readAt: null,
  });
  save(s);
}

// ─── Thông báo (#5–8) ───

const me = (req: MockRequest) => req.account!.id;
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
/** `@RequestParam int`: số nguyên, vắng thì mặc định. */
const intParam = (v: string | undefined, fallback: number) => (v === undefined || v === '' ? fallback : /^-?\d+$/.test(v) ? Number(v) : null);

const view = ({ accountId: _accountId, ...n }: NotificationRow): AppNotification => n;
/** createdAt giảm dần, trùng thì id giảm dần (docs/adr/0039 §6). */
const newestFirst = (a: NotificationRow, b: NotificationRow) =>
  Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.notificationId - a.notificationId;
const unread = (n: NotificationRow) => !n.readAt;

const listMine: MockHandler = (req) => {
  const { unreadOnly = '' } = req.query;
  if (!['', 'true', 'false'].includes(unreadOnly)) return badParam('unreadOnly');
  const rawPage = intParam(req.query.page, 0);
  if (rawPage === null) return badParam('page');
  const rawSize = intParam(req.query.size, 20);
  if (rawSize === null) return badParam('size');
  const page = Math.max(rawPage, 0);
  const size = Math.min(Math.max(rawSize, 1), 100);

  const mine = load()
    .notifications.filter((n) => n.accountId === me(req) && (unreadOnly !== 'true' || unread(n)))
    .sort(newestFirst);
  return ok({
    content: mine.slice(page * size, page * size + size).map(view),
    page,
    size,
    totalElements: mine.length,
    totalPages: Math.ceil(mine.length / size),
  });
};

const countUnread: MockHandler = (req) =>
  ok({ count: load().notifications.filter((n) => n.accountId === me(req) && unread(n)).length });

const markRead: MockHandler = (req) => {
  const { notificationId } = req.params;
  if (!/^-?\d+$/.test(notificationId)) return badParam('notificationId');
  const s = load();
  const row = s.notifications.find((n) => n.notificationId === Number(notificationId) && n.accountId === me(req));
  if (!row) return notFound('thông báo', notificationId);
  if (unread(row)) {
    row.readAt = new Date().toISOString();
    save(s);
  }
  return noContent();
};

const markAllRead: MockHandler = (req) => {
  const s = load();
  const readAt = new Date().toISOString();
  for (const n of s.notifications) if (n.accountId === me(req) && unread(n)) n.readAt = readAt;
  save(s);
  return noContent();
};

// ─── Cài đặt nhận thông báo (#9, #10) ───

const onlyCustomer = (h: MockHandler): MockHandler => (req) => (req.account!.role === 'CUSTOMER' ? h(req) : denied());

/** Đủ 3 loại nhắc × 2 kênh, mỗi ô là boolean; khóa lạ bị bỏ qua như Jackson của BE. */
function settingsChecks(body: unknown): Check[] {
  const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>;
  return REMINDER_KINDS.flatMap((kind): Check[] => {
    const toggle = b[kind];
    if (!toggle || typeof toggle !== 'object') return [[kind, 'không được để trống', true]];
    const t = toggle as Record<string, unknown>;
    return CHANNELS.map((ch): Check => [`${kind}.${ch}`, 'phải là true hoặc false', typeof t[ch] !== 'boolean']);
  });
}

const pickSettings = (body: Record<ReminderKind, ChannelToggle>): NotificationSettings =>
  Object.fromEntries(REMINDER_KINDS.map((k) => [k, { email: body[k].email, inApp: body[k].inApp }])) as NotificationSettings;

// ─── Việc gọi điện của lễ tân (#1–4, UC87, BR-TB-05, 03 §10) ───
// BE chưa có (T39) nên mock theo hợp đồng; chỗ hợp đồng im lặng là ASSUMPTION (plan T47 C1–C8):
//  C1  Sắp theo hạn tăng dần rồi id tăng dần; `status` vắng = OPEN (không có "tất cả"); `dueFrom` / `dueTo` gồm cả hai đầu,
//      `dueFrom` sau `dueTo` ra trang rỗng (hợp đồng không nêu 400); `page` / `size` kẹp như các danh sách khác.
//  C2  Hoàn tất cần cả `result` và `note`: thiếu → 400 VALIDATION_FAILED theo trường; `result` ngoài enum hoặc `note` không
//      phải chuỗi → 400 MALFORMED_REQUEST; `note` toàn khoảng trắng → 400 BR-TB-05 (sau 404 / 403 phạm vi, trước 409).
//      Hợp đồng ghi BR-TB-05 cho cả "chưa chọn kết quả"; mock tách theo C2, FE luôn chặn trước nên không phân biệt.
//  C3  Hủy: `reason` thiếu / trống / quá 300 ký tự → 400 VALIDATION_FAILED.
//  C4  Task của chi nhánh khác → 403 ACCESS_DENIED_SCOPE_MISMATCH (00-method §3.3).
//  C5  Hoàn tất / hủy tay ghi handledBy = người gọi, handledAt = bây giờ; hệ thống hủy (ST19, BR-TB-03, BR-TB-06) để null (seed).
//  C6  409 INVALID_STATE_TRANSITION "Không thể chuyển trạng thái CareTask từ X sang Y".
//  C7  Mọi role khác lễ tân, kể cả BRANCH_MANAGER và SUPER_MANAGER → 403 ACCESS_DENIED.
//  C8  `note` ≤ 500 ký tự (giới hạn của giao diện, hợp đồng không có).
//  Khác: id không tồn tại → 404 "Không tìm thấy việc gọi điện #id" (câu do mock đặt); id / tham số sai kiểu → 400 MALFORMED_REQUEST.
// Thứ tự: 400 path / query / body → 403 vai trò → 404 / 403 phạm vi → 400 BR-TB-05 → 409.
// customerName, petName, phoneToCall đọc từ mock khách / thú như BE đọc qua QueryApi. BR-TB-05: đón thú quá hạn gọi SĐT khẩn ghi
// lúc nhận thú (giữ trong dòng, mock chưa có đặt chỗ lưu trú), các loại khác gọi SĐT hồ sơ khách.

const isReceptionist = (req: MockRequest) => req.account!.role === 'RECEPTIONIST';
const branchOf = (req: MockRequest) => req.account!.branchId ?? null;
const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const stateError = (from: CareTaskStatus, to: CareTaskStatus) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái CareTask từ ${from} sang ${to}`, 409);
const isReply = (x: CareTaskRow | MockReply): x is MockReply => typeof x.status === 'number';

/** `yyyy-MM-dd` là ngày có thật (không nhận 2026-02-30). */
function isDate(v: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v);
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
}

function careTaskView({ emergencyPhone, ...row }: CareTaskRow): CareTask {
  const customer = findCustomer(row.customerId);
  return {
    ...row,
    customerName: customer?.fullName ?? `Khách #${row.customerId}`,
    petName: petSummary(row.petId)?.name ?? `Thú #${row.petId}`,
    phoneToCall: row.type === 'PICKUP_OVERDUE' ? emergencyPhone : (customer?.phone ?? null),
  };
}

/** Task theo id trong phạm vi chi nhánh của người gọi: không có → 404, của chi nhánh khác → 403 phạm vi. */
function careTaskFor(req: MockRequest, s: State): CareTaskRow | MockReply {
  const id = Number(req.params.careTaskId);
  const row = s.careTasks.find((t) => t.careTaskId === id);
  if (!row) return notFound('việc gọi điện', id);
  return row.branchId === branchOf(req) ? row : scopeDenied();
}

const listCareTasks: MockHandler = (req) => {
  const { status = 'OPEN', type, dueFrom, dueTo } = req.query;
  if (!CARE_TASK_STATUSES.includes(status as CareTaskStatus)) return badParam('status');
  if (type !== undefined && !CARE_TASK_TYPES.includes(type as CareTaskType)) return badParam('type');
  if (dueFrom !== undefined && !isDate(dueFrom)) return badParam('dueFrom');
  if (dueTo !== undefined && !isDate(dueTo)) return badParam('dueTo');
  const badPaging = typeError(req.query, ['page', 'size']);
  if (badPaging) return badPaging;
  if (!isReceptionist(req)) return denied();

  const found = load()
    .careTasks.filter(
      (t) =>
        t.branchId === branchOf(req) &&
        t.status === status &&
        (type === undefined || t.type === type) &&
        (dueFrom === undefined || t.dueDate >= dueFrom) &&
        (dueTo === undefined || t.dueDate <= dueTo),
    )
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.careTaskId - b.careTaskId);
  return ok(pageOf(found.map(careTaskView), clampPaging(req.query)));
};

const getCareTask: MockHandler = (req) => {
  const badId = typeError(req.params, ['careTaskId']);
  if (badId) return badId;
  if (!isReceptionist(req)) return denied();
  const row = careTaskFor(req, load());
  return isReply(row) ? row : ok(careTaskView(row));
};

const completeCareTask: MockHandler = (req) => {
  const badId = typeError(req.params, ['careTaskId']);
  if (badId) return badId;
  const b = (req.body ?? {}) as Record<string, unknown>;
  if ((b.result != null && !CARE_TASK_RESULTS.includes(b.result as CareTaskResult)) || (b.note != null && typeof b.note !== 'string')) {
    return malformed();
  }
  const badBody = invalid([
    ['result', 'Vui lòng chọn kết quả cuộc gọi', b.result == null],
    ['note', 'Vui lòng nhập ghi chú kết quả cuộc gọi', b.note == null],
    ['note', `Ghi chú tối đa ${CARE_TASK_LIMITS.note} ký tự`, tooLong(b.note, CARE_TASK_LIMITS.note)],
  ]);
  if (badBody) return badBody;
  if (!isReceptionist(req)) return denied();

  const s = load();
  const row = careTaskFor(req, s);
  if (isReply(row)) return row;
  const note = (b.note as string).trim();
  if (note === '') return ruleFail('BR-TB-05', 'Vui lòng nhập ghi chú kết quả cuộc gọi');
  if (row.status !== 'OPEN') return stateError(row.status, 'DONE');
  Object.assign(row, { status: 'DONE', result: b.result, note, handledBy: me(req), handledAt: new Date().toISOString() });
  save(s);
  return ok(careTaskView(row), 200, 'Đã ghi kết quả cuộc gọi');
};

const cancelCareTask: MockHandler = (req) => {
  const badId = typeError(req.params, ['careTaskId']);
  if (badId) return badId;
  const b = (req.body ?? {}) as Record<string, unknown>;
  if (b.reason != null && typeof b.reason !== 'string') return malformed();
  const badBody = invalid([
    ['reason', 'Vui lòng nhập lý do hủy', !isText(b.reason)],
    ['reason', `Lý do tối đa ${CARE_TASK_LIMITS.reason} ký tự`, tooLong(b.reason, CARE_TASK_LIMITS.reason)],
  ]);
  if (badBody) return badBody;
  if (!isReceptionist(req)) return denied();

  const s = load();
  const row = careTaskFor(req, s);
  if (isReply(row)) return row;
  if (row.status !== 'OPEN') return stateError(row.status, 'CANCELLED');
  Object.assign(row, { status: 'CANCELLED', cancelReason: (b.reason as string).trim(), handledBy: me(req), handledAt: new Date().toISOString() });
  save(s);
  return ok(careTaskView(row), 200, 'Đã hủy việc gọi điện');
};

export const careRoutes: Record<string, MockHandler> = {
  'GET /api/care-tasks': listCareTasks,
  'GET /api/care-tasks/:careTaskId': getCareTask,
  'POST /api/care-tasks/:careTaskId/complete': completeCareTask,
  'POST /api/care-tasks/:careTaskId/cancel': cancelCareTask,

  'GET /api/me/notifications': listMine,
  'GET /api/me/notifications/unread-count': countUnread,
  'POST /api/me/notifications/read-all': markAllRead,
  'POST /api/me/notifications/:notificationId/read': markRead,

  'GET /api/me/notification-settings': onlyCustomer((req) => ok(load().settings[me(req)] ?? DEFAULT_SETTINGS)),
  'PUT /api/me/notification-settings': (req) => {
    const bad = invalid(settingsChecks(req.body));
    if (bad) return bad;
    if (req.account!.role !== 'CUSTOMER') return denied();
    const s = load();
    s.settings[me(req)] = pickSettings(req.body as NotificationSettings);
    save(s);
    return ok(s.settings[me(req)]);
  },
};
