// Quản lý chuyên mục và bài viết (UC81) — theo docs/api/content-v1.md #3–13, chỉ SUPER_MANAGER. Trường `?` có thể vắng hoặc null.
// Bản công khai (PublicArticle*) ở ./public-site.

export type ArticleStatus = 'DRAFT' | 'PUBLISHED' | 'HIDDEN';

export const ARTICLE_STATUS_LABELS: Record<ArticleStatus, string> = {
  DRAFT: 'Nháp',
  PUBLISHED: 'Đang đăng',
  HIDDEN: 'Đã ẩn',
};

/** #3: kể cả chuyên mục ẩn; `articleCount` đếm bài ở mọi trạng thái (BR-BV-04). */
export interface ArticleCategory {
  categoryId: number;
  name: string;
  slug: string;
  isHidden: boolean;
  articleCount: number;
}

/** #4: tên ≤ 100, slug ≤ 120 theo `^[a-z0-9]+(?:-[a-z0-9]+)*$`. */
export interface ArticleCategoryRequest {
  name: string;
  slug: string;
}

/** #5: không có slug (không đổi sau khi tạo, A1). */
export interface UpdateArticleCategoryRequest {
  name?: string;
  isHidden?: boolean;
}

/** #7: không có nội dung, ảnh bìa, tóm tắt, tác giả; chỉ có `categoryId` (ghép tên với danh sách chuyên mục). */
export interface ArticleSummary {
  articleId: number;
  categoryId: number;
  title: string;
  slug: string;
  status: ArticleStatus;
  /** Ghi một lần ở lần PUBLISHED đầu tiên; có giá trị thì khóa slug và không xóa được (BR-BV-01, 02). */
  firstPublishedAt?: string | null;
  updatedAt: string;
}

/** #9: `content` là HTML đã lọc ở BE (ADR-0071). */
export interface Article {
  articleId: number;
  categoryId: number;
  title: string;
  slug: string;
  coverImageUrl?: string | null;
  summary?: string | null;
  content: string;
  authorDisplayName: string;
  status: ArticleStatus;
  firstPublishedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

/** #8: tiêu đề ≤ 250, slug ≤ 270, ảnh bìa `https` ≤ 500 (bỏ trường nếu không có, không gửi chuỗi rỗng), tóm tắt ≤ 500, tác giả ≤ 100. */
export interface CreateArticleRequest {
  categoryId: number;
  title: string;
  slug: string;
  coverImageUrl?: string;
  summary?: string;
  content: string;
  authorDisplayName: string;
}

/** #10: trường vắng hoặc null = giữ nguyên; chuỗi rỗng ở `coverImageUrl` / `summary` = xóa. */
export interface UpdateArticleRequest {
  categoryId?: number;
  title?: string;
  slug?: string;
  coverImageUrl?: string;
  summary?: string;
  content?: string;
  authorDisplayName?: string;
}

/** #7: `q` chỉ tìm trong tiêu đề, tối đa 100 ký tự; `page` từ 0, `size` 1–100 (mặc định 20). */
export interface ArticleListQuery {
  status?: ArticleStatus;
  categoryId?: number;
  q?: string;
  page?: number;
  size?: number;
}
