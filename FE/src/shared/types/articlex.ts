// Bài viết của bác sĩ và bình luận (UC80, UC81 mở rộng, UC82) — docs/api/articlex-v1.md, openapi/articlex-v1.yaml. Tầng 3, BE chưa cài.
import type { PageResponse } from './index';

/** BR-BV-02 và 03 #21. `content-v1` mới biết ba giá trị DRAFT / PUBLISHED / HIDDEN. */
export type ArticleReviewStatus = 'DRAFT' | 'PENDING_REVIEW' | 'REJECTED' | 'PUBLISHED' | 'HIDDEN';

export const ARTICLE_REVIEW_STATUS_LABELS: Record<ArticleReviewStatus, string> = {
  DRAFT: 'Nháp',
  PENDING_REVIEW: 'Chờ duyệt',
  REJECTED: 'Bị từ chối',
  PUBLISHED: 'Đã xuất bản',
  HIDDEN: 'Đã ẩn',
};

export type CommentStatus = 'ACTIVE' | 'HIDDEN' | 'DELETED';

export const COMMENT_STATUS_LABELS: Record<CommentStatus, string> = { ACTIVE: 'Đang hiện', HIDDEN: 'Đã ẩn', DELETED: 'Đã xóa' };

/** `maxLength` trong yaml; bình luận 1–1 000 ký tự (BR-BV-08), `commentPerHour` là [CFG] `content.comment_per_hour`. */
export const ARTICLEX_LIMITS = {
  title: 200,
  summary: 500,
  coverImageUrl: 500,
  reviewNote: 300,
  hideReason: 300,
  comment: 1000,
  commentPerHour: 10,
} as const;

export interface CreateMyArticleRequest {
  title: string;
  categoryId?: number | null;
  summary?: string | null;
  content: string;
  coverImageUrl?: string | null;
}

/** PATCH: trường vắng hoặc null = giữ nguyên. */
export interface UpdateMyArticleRequest {
  title?: string | null;
  categoryId?: number | null;
  summary?: string | null;
  content?: string | null;
  coverImageUrl?: string | null;
}

export interface MyArticleSummary {
  articleId: number;
  title: string;
  status: ArticleReviewStatus;
  submittedAt?: string | null;
  publishedAt?: string | null;
  updatedAt: string;
}

export interface MyArticle {
  articleId: number;
  title: string;
  slug?: string | null;
  categoryId?: number | null;
  summary?: string | null;
  content: string;
  coverImageUrl?: string | null;
  status: ArticleReviewStatus;
  /** Lý do từ chối (BR-BV-06); xóa khi tác giả sửa lại. */
  reviewNote?: string | null;
  submittedAt?: string | null;
  publishedAt?: string | null;
  updatedAt: string;
}

export interface MyArticleQuery {
  status?: ArticleReviewStatus;
  page?: number;
  size?: number;
}

export interface ReviewNoteRequest {
  reviewNote: string;
}

export interface CreateCommentRequest {
  content: string;
}
export type UpdateCommentRequest = CreateCommentRequest;

export interface HideCommentRequest {
  reason: string;
}

/** `authorName` là tên rút gọn (BR-BV-08, A5). */
export interface PublicComment {
  commentId: number;
  authorName: string;
  content: string;
  createdAt: string;
  editedAt?: string | null;
}

export interface Comment {
  commentId: number;
  articleId: number;
  content: string;
  status: CommentStatus;
  createdAt: string;
  editedAt?: string | null;
  hideReason?: string | null;
}

export interface CommentQuery {
  page?: number;
  size?: number;
}

export type PublicCommentPage = PageResponse<PublicComment>;
