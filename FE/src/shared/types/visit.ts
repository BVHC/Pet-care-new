// Tiếp nhận, hàng đợi, gọi lượt (UC44–UC47) — theo docs/api/visit-v1.md #1–9, #11 và `GET /me/queue`, kiểm lại bằng JSON
// quan sát từ BE thật (docs/testcases/T36-fe.md). Mã số luôn là `number`. Thời điểm là chuỗi ISO UTC (`Instant`, có thể tới 6 chữ số lẻ).
import type { Role } from './auth';
import type { Species } from './catalog';

/** 03 §4 Visit; BR-TN-09. COMPLETED, CANCELLED là trạng thái cuối. */
export type VisitStatus = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
/** Hạng trong hàng đợi (BR-TN-03, 04): cấp cứu → lịch hẹn → walk-in. Khách trễ 15–30 phút có lịch vẫn là `WALK_IN`. */
export type PriorityClass = 'EMERGENCY' | 'APPOINTMENT' | 'WALK_IN';
/** `visits.service_group` chỉ có hai nhóm này; Lưu trú không tiếp nhận qua Visit (BR-TN-05: MEDICAL → VET, GROOMING → CARETAKER). */
export type VisitServiceGroup = 'MEDICAL' | 'GROOMING';
/** Nguồn của lượt. BE không có trường này: có `appointmentId` là có lịch hẹn (xem `visitSourceOf`). */
export type VisitSource = 'APPOINTMENT' | 'WALK_IN';

export const VISIT_STATUSES: readonly VisitStatus[] = ['WAITING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
export const PRIORITY_CLASSES: readonly PriorityClass[] = ['EMERGENCY', 'APPOINTMENT', 'WALK_IN'];
/** Lượt chưa kết thúc: mặc định của hàng đợi chi nhánh (#2) và hàng đợi của tôi (#3). */
export const OPEN_VISIT_STATUSES: readonly VisitStatus[] = ['WAITING', 'IN_PROGRESS'];

export const VISIT_STATUS_LABELS: Record<VisitStatus, string> = {
  WAITING: 'Đang chờ',
  IN_PROGRESS: 'Đang khám',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy',
};

export const PRIORITY_LABELS: Record<PriorityClass, string> = {
  EMERGENCY: 'Cấp cứu',
  APPOINTMENT: 'Có lịch hẹn',
  WALK_IN: 'Khách vãng lai',
};

export const VISIT_SOURCE_LABELS: Record<VisitSource, string> = {
  APPOINTMENT: 'Có lịch hẹn',
  WALK_IN: 'Khách vãng lai',
};

/** Tên nhóm theo nghiệp vụ tiếp nhận (BR-TN-05), kèm chức vụ được gán. */
export const VISIT_GROUP_LABELS: Record<VisitServiceGroup, string> = { MEDICAL: 'Khám/Tiêm', GROOMING: 'Thẩm mỹ' };
/** Chức vụ được gán cho từng nhóm (BR-TN-05). */
export const ASSIGNEE_ROLE_OF_GROUP: Record<VisitServiceGroup, 'VET' | 'CARETAKER'> = { MEDICAL: 'VET', GROOMING: 'CARETAKER' };

/** visit-v1 schema `Visit`: dùng cho hàng đợi (#2, #3, #6) lẫn chi tiết (#4) và mọi lệnh ghi. */
export interface Visit {
  visitId: number;
  /** `TN-yyMM-nnnnn` */
  code: string;
  branchId: number;
  /** Chủ tại thời điểm tiếp nhận. */
  customerId: number;
  customerName: string;
  petId: number;
  petName: string;
  species: Species;
  /** null = walk-in. */
  appointmentId: number | null;
  serviceGroup: VisitServiceGroup;
  status: VisitStatus;
  priorityClass: PriorityClass;
  /**
   * Giờ hẹn (lịch hẹn giữ ưu tiên) hoặc giờ check-in / giờ đánh dấu cấp cứu; lễ tân sắp lại thì BE sửa khóa này (khóa đích − 1 µs).
   * ĐỪNG tự sắp lại ở FE bằng `Date.parse`: hai lượt có thể chỉ khác nhau 1 µs mà `Date` chỉ tới mili giây. Dùng đúng thứ tự mảng BE trả (BR-TN-04).
   */
  queueSortKey: string;
  /** null khi chưa gán (WAITING). */
  assigneeId: number | null;
  assigneeName: string | null;
  /** Người phụ trách lượt đã gọi bị khóa tài khoản (BR-QT-11, BR-TN-08). */
  needsReassign: boolean;
  /** Order nguồn VISIT của lượt. */
  orderId: number;
  checkedInAt: string;
  calledAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}

/** Một dòng của hàng đợi — BE dùng cùng schema `Visit`. */
export type QueueItem = Visit;

/** Có lịch hẹn hay không, tách khỏi `priorityClass` (khách trễ 15–30 phút có lịch nhưng xếp `WALK_IN`). */
export const visitSourceOf = (visit: Pick<Visit, 'appointmentId'>): VisitSource => (visit.appointmentId == null ? 'WALK_IN' : 'APPOINTMENT');

// ─── Yêu cầu ───

interface CheckInBase {
  petId: number;
  /**
   * Tên trường ĐÚNG là `isEmergency`. BE bỏ qua trường lạ, nên gửi `emergency: true` vẫn được 201 nhưng lượt KHÔNG thành cấp cứu.
   * Bắt buộc `true` khi tiếp nhận ngoài giờ mở cửa (BR-CN-05). Có thì xếp `EMERGENCY` kể cả có lịch hẹn.
   */
  isEmergency?: boolean;
}

/** Check-in lịch hẹn: dịch vụ lấy từ lịch (`serviceId` nếu gửi thì bị bỏ qua); `petId` phải là thú của lịch (BR-TN-01). */
export interface CheckInAppointmentRequest extends CheckInBase {
  appointmentId: number;
  serviceId?: never;
}

/** Walk-in (và cấp cứu không hẹn trước): bắt buộc `serviceId` thuộc MEDICAL / GROOMING, đang kinh doanh, bật tại chi nhánh (BR-TN-01, BR-LH-01). */
export interface CheckInWalkInRequest extends CheckInBase {
  serviceId: number;
  appointmentId?: never;
}

/** Cấp cứu = walk-in hoặc check-in lịch hẹn kèm `isEmergency: true`. */
export type CheckInEmergencyRequest = (CheckInAppointmentRequest | CheckInWalkInRequest) & { isEmergency: true };

export type CheckInRequest = CheckInAppointmentRequest | CheckInWalkInRequest;

/** #5: VET cho MEDICAL, CARETAKER cho GROOMING, ACTIVE, cùng chi nhánh (BR-TN-05). */
export interface AssignRequest {
  assigneeId: number;
}

/** #6: đặt lượt ngay trước lượt này. */
export interface MoveVisitRequest {
  placeBeforeVisitId: number;
}

/** #9: lý do bắt buộc, 1–300 ký tự sau khi bỏ khoảng trắng hai đầu (BR-TN-08). */
export interface ReassignRequest {
  assigneeId: number;
  reason: string;
}

/** #11: không gửi body cũng được (A4). */
export interface CancelVisitRequest {
  /** ≤ 300 ký tự. */
  reason?: string | null;
}

/** #2. Không gửi `date`: lượt WAITING / IN_PROGRESS của mọi ngày; gửi `status` COMPLETED / CANCELLED mà không `date`: chỉ hôm nay (A1). */
export interface VisitQueueQuery {
  /** `YYYY-MM-DD`, ngày check-in theo giờ Việt Nam. */
  date?: string;
  status?: VisitStatus;
  serviceGroup?: VisitServiceGroup;
  assigneeId?: number;
  needsReassign?: boolean;
}

// ─── Kết quả ───

/** #5. `assigneeOffline` là cảnh báo không chặn của BR-TN-06. */
export interface AssignResult {
  visit: Visit;
  assigneeOffline: boolean;
}

// ─── Vai trò theo từng thao tác (quan sát từ BE thật; xem `visitPermissions`) ───

/** Thao tác tiếp nhận / hàng đợi mà UI cần biết vai trò nào được làm, để không gọi API sẽ bị 403. */
export type VisitAction = 'checkIn' | 'queue' | 'myQueue' | 'detail' | 'assign' | 'move' | 'markEmergency' | 'call' | 'reassign' | 'cancel' | 'arrivals';

export const VISIT_ROLES: Record<VisitAction, readonly Role[]> = {
  checkIn: ['RECEPTIONIST'],
  queue: ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
  myQueue: ['VET', 'CARETAKER'],
  detail: ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
  assign: ['RECEPTIONIST'],
  move: ['RECEPTIONIST'],
  markEmergency: ['RECEPTIONIST', 'VET'],
  call: ['VET', 'CARETAKER'],
  /** SUPER_MANAGER chỉ khi chi nhánh không còn BRANCH_MANAGER ACTIVE — BE quyết định (403 nếu còn). */
  reassign: ['BRANCH_MANAGER', 'SUPER_MANAGER'],
  cancel: ['RECEPTIONIST'],
  /** appointment-v1 #3 (`checkInReady`): chỉ RECEPTIONIST / BRANCH_MANAGER / CUSTOMER xem được danh sách lịch hẹn. */
  arrivals: ['RECEPTIONIST', 'BRANCH_MANAGER'],
};

export type VisitPermissions = Record<VisitAction, boolean>;

/** `role` rỗng (chưa đăng nhập) → không thao tác nào. */
export function visitPermissions(role: Role | null | undefined): VisitPermissions {
  const entries = (Object.keys(VISIT_ROLES) as VisitAction[]).map((action) => [action, role != null && VISIT_ROLES[action].includes(role)]);
  return Object.fromEntries(entries) as VisitPermissions;
}
