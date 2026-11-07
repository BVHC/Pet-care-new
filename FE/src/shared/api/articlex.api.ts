// Doctor articles & comments API (UC80, UC81 mở rộng, UC82) — docs/api/articlex-v1.md #1–14. Tầng 3, BE chưa cài (T75).
// Bác sĩ (VET) soạn → gửi duyệt → SUPER_MANAGER duyệt / từ chối. Danh sách bài chờ duyệt KHÔNG có endpoint riêng: dùng `contentApi.listArticles({ status: 'PENDING_REVIEW' })` của content-v1.
// Bình luận: #9 công khai, #10–12 chỉ CUSTOMER (của mình), #13–14 chỉ SUPER_MANAGER; không có endpoint liệt kê bình luận đã ẩn, nên bỏ ẩn chỉ làm được khi đã biết `commentId`.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  Comment,
  CommentQuery,
  CreateMyArticleRequest,
  MyArticle,
  MyArticleQuery,
  MyArticleSummary,
  PublicComment,
  UpdateMyArticleRequest,
} from '../types/articlex';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const articlexApi = {
  /** #1: 201, luôn DRAFT. 400 BR-BV-01 tiêu đề rỗng / quá 200 ký tự, chuyên mục không tồn tại, nội dung trống sau khi lọc. Chỉ VET. */
  createMy: (body: CreateMyArticleRequest) => post<MyArticle>('/api/me/articles', body),
  /** #2: bài của tôi, mới sửa trước; `status` lọc. */
  listMy: (query: MyArticleQuery = {}) => get<PageResponse<MyArticleSummary>>('/api/me/articles', query),
  /** #3: bài của người khác = 404. */
  getMy: (articleId: number) => get<MyArticle>(`/api/me/articles/${articleId}`),
  /** #4: chỉ DRAFT / REJECTED sửa được (sửa bài REJECTED đưa về DRAFT). 409 BR-BV-07 khi đang PENDING_REVIEW hoặc đã PUBLISHED / HIDDEN. */
  updateMy: (articleId: number, body: UpdateMyArticleRequest) => patch<MyArticle>(`/api/me/articles/${articleId}`, body),
  /** #5: DRAFT → PENDING_REVIEW. 400 BR-BV-01 thiếu chuyên mục / nội dung; 409 INVALID_STATE_TRANSITION. */
  submit: (articleId: number) => post<MyArticle>(`/api/me/articles/${articleId}/submit`),
  /** #6: PENDING_REVIEW → DRAFT. */
  withdraw: (articleId: number) => post<MyArticle>(`/api/me/articles/${articleId}/withdraw`),
  /** #7 (SUPER_MANAGER): PENDING_REVIEW → PUBLISHED. */
  approve: (articleId: number) => post<MyArticle>(`/api/articles/${articleId}/approve`),
  /** #8 (SUPER_MANAGER): PENDING_REVIEW → REJECTED; 400 BR-BV-06 thiếu lý do. */
  reject: (articleId: number, reviewNote: string) => post<MyArticle>(`/api/articles/${articleId}/reject`, { reviewNote }),
  /** #9: công khai; chỉ bình luận ACTIVE của bài PUBLISHED, cũ nhất trước; 404 khi bài chưa PUBLISHED. */
  listComments: (slug: string, query: CommentQuery = {}) => get<PageResponse<PublicComment>>(`/api/public/articles/${encodeURIComponent(slug)}/comments`, query),
  /** #10 (CUSTOMER): 201. 400 BR-BV-08 rỗng / quá 1 000 ký tự; 404 bài chưa PUBLISHED; 429 quá 10 bình luận / giờ. */
  createComment: (articleId: number, content: string) => post<Comment>(`/api/me/articles/${articleId}/comments`, { content }),
  /** #11 (CUSTOMER, của mình): 409 khi bình luận đã ẩn hoặc đã xóa. */
  updateComment: (commentId: number, content: string) => patch<Comment>(`/api/me/comments/${commentId}`, { content }),
  /** #12 (CUSTOMER, của mình): 204, xóa mềm; gọi lại vẫn 204. */
  deleteComment: async (commentId: number): Promise<void> => {
    await apiClient.delete(`/api/me/comments/${commentId}`);
  },
  /** #13 (SUPER_MANAGER): ACTIVE → HIDDEN; 400 BR-BV-08 thiếu lý do. */
  hideComment: (commentId: number, reason: string) => post<Comment>(`/api/comments/${commentId}/hide`, { reason }),
  /** #14 (SUPER_MANAGER): HIDDEN → ACTIVE. */
  unhideComment: (commentId: number) => post<Comment>(`/api/comments/${commentId}/unhide`),
};
