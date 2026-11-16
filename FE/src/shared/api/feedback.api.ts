// Feedback API — docs/api/content-v1.md #18–21. Khách (CUSTOMER) gửi và xem lại; SUPER_MANAGER xem tất cả,
// BRANCH_MANAGER chỉ chi nhánh mình (BR-DG-03).
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { CreateFeedbackRequest, Feedback, FeedbackQuery, MyFeedback } from '../types/feedback';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const feedbackApi = {
  /** #18, 201. 400 `BR-DG-01` (nội dung, điểm, thiếu chi nhánh, quá giới hạn ngày) hoặc `BR-TK-19`; 404 chi nhánh không ACTIVE. */
  submit: (body: CreateFeedbackRequest) => post<MyFeedback>('/api/me/feedbacks', body),
  /** #19: mảng, mới nhất trước, không phân trang. */
  listMine: () => get<MyFeedback[]>('/api/me/feedbacks'),
  /** #20: `createdAt` mới nhất trước. */
  list: (query: FeedbackQuery = {}) => get<PageResponse<Feedback>>('/api/feedbacks', query),
  /** #21: chỉ đọc. 404 không có, 403 ngoài phạm vi (BR-DG-03). */
  get: (feedbackId: number) => get<Feedback>(`/api/feedbacks/${feedbackId}`),
};
