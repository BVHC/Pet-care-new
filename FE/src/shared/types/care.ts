// Thông báo trong ứng dụng & cài đặt nhận thông báo (UC88) theo docs/api/care-v1.md #5–10, việc gọi điện của lễ tân
// (Care Task, UC87) theo #1–4 (mục Schema). Trường `?` có thể vắng hoặc null.

/** Schema `Notification`. Đặt tên khác để không che kiểu `Notification` của trình duyệt. */
export interface AppNotification {
  notificationId: number;
  /** Mã mẫu bỏ hậu tố `_APP`, vd `APPOINTMENT_REMINDER` (05-erd mục 13, docs/adr/0014). */
  type: string;
  title: string;
  /** Có thể nhiều dòng (`\n`). */
  body: string;
  /** Đường dẫn trong FE; null khi thông báo không kèm liên kết. */
  linkUrl?: string | null;
  /** null = chưa đọc. Gọi đánh dấu đã đọc lần nữa vẫn giữ thời điểm đọc đầu (docs/adr/0039). */
  readAt?: string | null;
  createdAt: string;
}

export interface UnreadCount {
  count: number;
}

/** `unreadOnly` vắng = false; `page` từ 0; `size` BE kẹp 1–100. */
export interface NotificationQuery {
  unreadOnly?: boolean;
  page?: number;
  size?: number;
}

export interface ChannelToggle {
  email: boolean;
  inApp: boolean;
}

/** Ba loại nhắc khách bật / tắt được (care-v1 A1; nội dung còn chờ PO — Q1). */
export type ReminderKind = 'appointmentReminder' | 'vaccineReminder' | 'followUpReminder';

export type NotificationSettings = Record<ReminderKind, ChannelToggle>;

export const REMINDER_KINDS: readonly ReminderKind[] = ['appointmentReminder', 'vaccineReminder', 'followUpReminder'];
export const CHANNELS: readonly (keyof ChannelToggle)[] = ['email', 'inApp'];

export const REMINDER_LABELS: Record<ReminderKind, { title: string; description: string }> = {
  appointmentReminder: { title: 'Nhắc lịch hẹn', description: 'Trước giờ hẹn khám, tiêm, làm đẹp (BR-LH-12)' },
  vaccineReminder: { title: 'Nhắc tái chủng', description: 'Trước ngày tiêm mũi kế tiếp của thú cưng (BR-TB-01)' },
  followUpReminder: { title: 'Nhắc tái khám', description: 'Trước ngày tái khám bác sĩ đã hẹn (BR-TB-06)' },
};

export const CHANNEL_LABELS: Record<keyof ChannelToggle, string> = { email: 'Email', inApp: 'Trong ứng dụng' };

// ─── Việc gọi điện của lễ tân (Care Task, UC87) — care-v1 #1–4 ───

/** 05 §0: TÁI_CHỦNG, QUÁ_HẠN_TÁI_CHỦNG, TÁI_KHÁM, QUÁ_HẠN_ĐÓN. */
export type CareTaskType = 'VACCINE_DUE' | 'VACCINE_OVERDUE' | 'FOLLOW_UP_DUE' | 'PICKUP_OVERDUE';
/** 03 §10: chỉ OPEN mới hoàn tất / hủy được. */
export type CareTaskStatus = 'OPEN' | 'DONE' | 'CANCELLED';
/** LIÊN_HỆ_ĐƯỢC / KHÔNG_LIÊN_LẠC_ĐƯỢC; chỉ có khi DONE. */
export type CareTaskResult = 'REACHED' | 'UNREACHABLE';

export interface CareTask {
  careTaskId: number;
  type: CareTaskType;
  status: CareTaskStatus;
  branchId: number;
  customerId: number;
  customerName: string;
  /** BR-TB-05: đón thú quá hạn gọi SĐT khẩn ghi lúc nhận thú, các loại khác gọi SĐT hồ sơ khách. Vắng khi chưa có. */
  phoneToCall?: string | null;
  petId: number;
  petName: string;
  /** Đúng một trong ba khóa liên kết theo `type` (05-erd care_tasks). */
  vaccinationId?: number | null;
  medicalRecordVisitId?: number | null;
  boardingBookingId?: number | null;
  /** `yyyy-MM-dd`: ngày tái chủng / tái khám / ngày quá hạn. */
  dueDate: string;
  /** Mô tả ngắn: loại vaccine, ngày tái khám, mã đặt chỗ… */
  context: string;
  result?: CareTaskResult | null;
  note?: string | null;
  cancelReason?: string | null;
  /** null khi hệ thống hủy (ST19, BR-TB-03, BR-TB-06). */
  handledBy?: number | null;
  handledAt?: string | null;
  createdAt: string;
}

/** `status` vắng = OPEN (không có "tất cả"); `dueFrom` / `dueTo` gồm cả hai đầu. */
export interface CareTaskQuery {
  status?: CareTaskStatus;
  type?: CareTaskType;
  dueFrom?: string;
  dueTo?: string;
  page?: number;
  size?: number;
}

export interface CompleteCareTaskRequest {
  result: CareTaskResult;
  note: string;
}

export interface CancelCareTaskRequest {
  reason: string;
}

export const CARE_TASK_TYPES: readonly CareTaskType[] = ['VACCINE_DUE', 'VACCINE_OVERDUE', 'FOLLOW_UP_DUE', 'PICKUP_OVERDUE'];
export const CARE_TASK_STATUSES: readonly CareTaskStatus[] = ['OPEN', 'DONE', 'CANCELLED'];
export const CARE_TASK_RESULTS: readonly CareTaskResult[] = ['REACHED', 'UNREACHABLE'];

export const CARE_TASK_TYPE_LABELS: Record<CareTaskType, string> = {
  VACCINE_DUE: 'Tái chủng',
  VACCINE_OVERDUE: 'Quá hạn tái chủng',
  FOLLOW_UP_DUE: 'Tái khám',
  PICKUP_OVERDUE: 'Quá hạn đón thú',
};

export const CARE_TASK_STATUS_LABELS: Record<CareTaskStatus, string> = { OPEN: 'Đang chờ', DONE: 'Đã xong', CANCELLED: 'Đã hủy' };

export const CARE_TASK_RESULT_LABELS: Record<CareTaskResult, string> = { REACHED: 'Liên hệ được', UNREACHABLE: 'Không liên lạc được' };

/** `reason` ≤ 300 theo care-v1 (CancelCareTaskRequest); `note` ≤ 500 là giới hạn của FE / mock, hợp đồng không nêu (ASSUMPTION C8). */
export const CARE_TASK_LIMITS = { reason: 300, note: 500 } as const;
