// Thông báo trong ứng dụng & cài đặt nhận thông báo (UC88) theo docs/api/care-v1.md #5–10 (mục Schema).
// Trường `?` có thể vắng hoặc null.

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
