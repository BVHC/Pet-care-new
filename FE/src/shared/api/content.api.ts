// Content API — docs/api/content-v1.md, phần Public: chuyên mục, danh sách và chi tiết bài viết (UC17), không cần đăng nhập (ADR-0005).
// Quản lý chuyên mục / bài viết (UC81) và nội dung trang tĩnh (UC21) thuộc T47.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { PublicArticle, PublicArticleCard, PublicArticleCategory, PublicArticleQuery } from '../types/public-site';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;

export const contentApi = {
  /** Chỉ chuyên mục không ẩn (BR-BV-04). */
  listPublicArticleCategories: () => get<PublicArticleCategory[]>('/api/public/article-categories'),
  /** Chỉ bài PUBLISHED, mới xuất bản trước (BR-BV-03). */
  listPublicArticles: (query: PublicArticleQuery = {}) => get<PageResponse<PublicArticleCard>>('/api/public/articles', query),
  /** 404 như nhau cho bài DRAFT, HIDDEN và slug không có. `content` là HTML đã lọc ở BE (ADR-0071). */
  getPublicArticle: (slug: string) => get<PublicArticle>(`/api/public/articles/${encodeURIComponent(slug)}`),
};
