// Feedback (UC83 khách gửi / xem, UC84 quản lý xem / xử lý) theo docs/api/content-v1.md #18–22 (mục Schema); luật: BR-DG-01…04.
import { vnClock } from '../utils/vn-time';

export type FeedbackTopic = 'BRANCH_SERVICE' | 'WEBSITE' | 'OTHER';
export type FeedbackStatus = 'NEW' | 'SEEN' | 'RESOLVED';

export const FEEDBACK_TOPIC_LABELS: Record<FeedbackTopic, string> = {
  BRANCH_SERVICE: 'Dịch vụ tại chi nhánh',
  WEBSITE: 'Website',
  OTHER: 'Khác',
};

/** Khách và quản lý cùng thấy một tên trạng thái. */
export const FEEDBACK_STATUS_LABELS: Record<FeedbackStatus, string> = {
  NEW: 'Mới gửi',
  SEEN: 'Đã xem',
  RESOLVED: 'Đã xử lý',
};

/** Màu huy hiệu (`StatusBadge`): chờ ai xem là vàng, đã xem là xanh dương, đã xử lý là xanh lá. */
export const FEEDBACK_STATUS_VARIANT: Record<FeedbackStatus, 'warning' | 'info' | 'success'> = {
  NEW: 'warning',
  SEEN: 'info',
  RESOLVED: 'success',
};

/** BR-DG-01: nội dung 10–2000 ký tự, đếm theo code point sau NFC + strip. */
export const FEEDBACK_CONTENT_MIN = 10;
export const FEEDBACK_CONTENT_MAX = 2000;
/** `ResolveFeedbackRequest.resolutionNote`: BE `@Size(max = 2000)` đếm đơn vị UTF-16, khác nội dung feedback ở trên. */
export const RESOLUTION_NOTE_MAX = 2000;

/** Như BE `ContentText`: NFC, bỏ khoảng trắng hai đầu, đếm code point (một emoji là 1, không phải 2 như `String.length`). */
export const feedbackContentLength = (text: string): number => [...text.normalize('NFC').trim()].length;

/** `dd/MM/yyyy HH:mm` theo giờ Việt Nam (+07:00 cố định, docs/time.md), không phụ thuộc múi giờ máy; nhận cả `…Z` lẫn `…+07:00`. */
export function formatFeedbackTime(iso: string): string {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return '';
  const { date, minutes } = vnClock(ms);
  const [year, month, day] = date.split('-');
  const hh = String(Math.floor(minutes / 60)).padStart(2, '0');
  const mm = String(minutes % 60).padStart(2, '0');
  return `${day}/${month}/${year} ${hh}:${mm}`;
}

/** #18. `branchId` bắt buộc khi `BRANCH_SERVICE`, tùy chọn với chủ đề khác (BR-DG-01); `rating` 1–5 tùy chọn. */
export interface CreateFeedbackRequest {
  topic: FeedbackTopic;
  branchId?: number;
  rating?: number;
  content: string;
}

/** #18, #19: khách chỉ thấy trạng thái, KHÔNG có ghi chú nội bộ (BR-DG-04). */
export interface MyFeedback {
  feedbackId: number;
  topic: FeedbackTopic;
  branchId?: number | null;
  rating?: number | null;
  content: string;
  status: FeedbackStatus;
  createdAt: string;
}

/** #20–22: bản của SUPER_MANAGER / BRANCH_MANAGER, có ghi chú nội bộ. `resolvedBy` chỉ là mã tài khoản (hợp đồng không kèm tên). */
export interface Feedback {
  feedbackId: number;
  customerId: number;
  customerName: string;
  topic: FeedbackTopic;
  branchId?: number | null;
  branchName?: string | null;
  rating?: number | null;
  content: string;
  status: FeedbackStatus;
  seenAt?: string | null;
  resolvedBy?: number | null;
  resolvedAt?: string | null;
  resolutionNote?: string | null;
  createdAt: string;
}

export interface ResolveFeedbackRequest {
  resolutionNote: string;
}

/** #20: `from`, `to` là `yyyy-MM-dd` giờ Việt Nam, gồm cả hai đầu. */
export interface FeedbackQuery {
  branchId?: number;
  status?: FeedbackStatus;
  topic?: FeedbackTopic;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}
