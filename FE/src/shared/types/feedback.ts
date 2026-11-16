// Feedback (UC83 khách gửi / xem, UC84 quản lý chỉ xem) theo docs/api/content-v1.md #18–21 (mục Schema); luật: BR-DG-01…03, 05.
// Không có trạng thái, ghi chú xử lý hay endpoint resolve (ADR-0122, V55); feedback tự bị xóa sau `feedback.retention_days` (ST24).
import { vnClock } from '../utils/vn-time';

export type FeedbackTopic = 'BRANCH_SERVICE' | 'WEBSITE' | 'OTHER';

export const FEEDBACK_TOPIC_LABELS: Record<FeedbackTopic, string> = {
  BRANCH_SERVICE: 'Dịch vụ tại chi nhánh',
  WEBSITE: 'Website',
  OTHER: 'Khác',
};

/** BR-DG-01: nội dung 10–2000 ký tự, đếm theo code point sau NFC + strip. */
export const FEEDBACK_CONTENT_MIN = 10;
export const FEEDBACK_CONTENT_MAX = 2000;

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

/** #18, #19: bản của khách (không có tên khách, tên chi nhánh). */
export interface MyFeedback {
  feedbackId: number;
  topic: FeedbackTopic;
  branchId?: number | null;
  rating?: number | null;
  content: string;
  createdAt: string;
}

/** #20, #21: bản của SUPER_MANAGER / BRANCH_MANAGER. */
export interface Feedback {
  feedbackId: number;
  customerId: number;
  customerName: string;
  topic: FeedbackTopic;
  branchId?: number | null;
  branchName?: string | null;
  rating?: number | null;
  content: string;
  createdAt: string;
}

/** #20: `from`, `to` là `yyyy-MM-dd` giờ Việt Nam, gồm cả hai đầu. */
export interface FeedbackQuery {
  branchId?: number;
  topic?: FeedbackTopic;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}
