// Page content API — docs/api/content-v1.md #1, #2, #14 (UC21). Quản trị (xem, sửa): chỉ SUPER_MANAGER; trang công khai không cần đăng nhập (ADR-0005).
import { apiClient } from './axios';
import type { PageContent, PageKey, UpdatePageContentRequest } from '../types/page-content';

const get = async <T>(url: string): Promise<T> => (await apiClient.get(url)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const pageContentApi = {
  /** #1: 4 trang theo thứ tự HOME_BANNER, ABOUT, POLICY, FAQ; `content` là `{}` và `title` null cho tới lần sửa đầu. */
  list: () => get<PageContent[]>('/api/page-contents'),
  /** #2: ghi đè hoàn toàn. 400 nếu `content` không phải JSON object hoặc quá 64 KB (byte UTF-8); 404 nếu khóa lạ. */
  save: (key: PageKey, body: UpdatePageContentRequest) => put<PageContent>(`/api/page-contents/${key}`, body),
  /** #14: 404 nếu khóa lạ. `content` chưa chắc đúng cấu trúc: đọc qua `parsePageContent`. */
  getPublic: (key: PageKey) => get<PageContent>(`/api/public/pages/${key}`),
};
