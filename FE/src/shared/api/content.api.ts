// Content API — docs/api/content-v1.md: phần Public (chuyên mục, danh sách và chi tiết bài viết, UC17; không cần đăng nhập, ADR-0005)
// và quản lý chuyên mục / bài viết (#3–13, UC81; chỉ SUPER_MANAGER). Nội dung trang tĩnh (UC21) ở page-content.api.ts.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  Article,
  ArticleCategory,
  ArticleCategoryRequest,
  ArticleListQuery,
  ArticleSummary,
  CreateArticleRequest,
  UpdateArticleCategoryRequest,
  UpdateArticleRequest,
} from '../types/content';
import type { PublicArticle, PublicArticleCard, PublicArticleCategory, PublicArticleQuery } from '../types/public-site';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const contentApi = {
  /** Chỉ chuyên mục không ẩn (BR-BV-04). */
  listPublicArticleCategories: () => get<PublicArticleCategory[]>('/api/public/article-categories'),
  /** Chỉ bài PUBLISHED, mới xuất bản trước (BR-BV-03). */
  listPublicArticles: (query: PublicArticleQuery = {}) => get<PageResponse<PublicArticleCard>>('/api/public/articles', query),
  /** 404 như nhau cho bài DRAFT, HIDDEN và slug không có. `content` là HTML đã lọc ở BE (ADR-0071). */
  getPublicArticle: (slug: string) => get<PublicArticle>(`/api/public/articles/${encodeURIComponent(slug)}`),

  /** #3: kể cả chuyên mục ẩn, kèm `articleCount`. */
  listArticleCategories: () => get<ArticleCategory[]>('/api/article-categories'),
  /** #4: 400 `VALIDATION_FAILED` khi tên hoặc slug trùng. */
  createArticleCategory: (body: ArticleCategoryRequest) => post<ArticleCategory>('/api/article-categories', body),
  /** #5: chỉ đổi tên và ẩn / hiện (slug không đổi, A1). */
  updateArticleCategory: (categoryId: number, body: UpdateArticleCategoryRequest) =>
    patch<ArticleCategory>(`/api/article-categories/${categoryId}`, body),
  /** #6: 400 BR-BV-04 "Chuyên mục còn N bài viết, chỉ ẩn được". */
  deleteArticleCategory: async (categoryId: number): Promise<void> => {
    await apiClient.delete(`/api/article-categories/${categoryId}`);
  },

  /** #7: mọi trạng thái, `updatedAt` giảm dần; `q` chỉ tìm trong tiêu đề. */
  listArticles: (query: ArticleListQuery = {}) => get<PageResponse<ArticleSummary>>('/api/articles', query),
  /** #9: có `content`. */
  getArticle: (articleId: number) => get<Article>(`/api/articles/${articleId}`),
  /** #8: 400 BR-BV-01 khi slug trùng hoặc nội dung trống sau khi lọc; 404 khi chuyên mục không có. */
  createArticle: (body: CreateArticleRequest) => post<Article>('/api/articles', body),
  /** #10: chỉ gửi trường đã đổi; `""` xóa ảnh bìa / tóm tắt; 400 BR-BV-01 khi đổi slug sau lần xuất bản đầu. */
  updateArticle: (articleId: number, body: UpdateArticleRequest) => patch<Article>(`/api/articles/${articleId}`, body),
  /** #11: 409 nếu đang PUBLISHED. */
  publishArticle: (articleId: number) => post<Article>(`/api/articles/${articleId}/publish`),
  /** #12: 409 nếu không PUBLISHED. */
  hideArticle: (articleId: number) => post<Article>(`/api/articles/${articleId}/hide`),
  /** #13: 400 BR-BV-02 nếu bài đã từng xuất bản. */
  deleteArticle: async (articleId: number): Promise<void> => {
    await apiClient.delete(`/api/articles/${articleId}`);
  },
};
