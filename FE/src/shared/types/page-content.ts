// Nội dung trang tĩnh (UC21) — docs/api/content-v1.md #1, #2, #14. Cấu trúc `content` của từng khóa (Q1) ở shared/constants/page-content.ts.

export type PageKey = 'HOME_BANNER' | 'ABOUT' | 'POLICY' | 'FAQ';

export interface PageContent {
  key: PageKey;
  /** FE không dùng (mọi chữ hiển thị nằm trong `content`); BE lưu null khi không gửi. */
  title: string | null;
  /** JSON object tự do tối đa 64 KB (A3); BE không kiểm cấu trúc, FE `safeParse` khi đọc. */
  content: Record<string, unknown>;
  updatedAt: string;
}

/** `PUT` ghi đè hoàn toàn: trường vắng không được giữ lại (`title` vắng thì thành null). */
export interface UpdatePageContentRequest {
  title?: string;
  content: Record<string, unknown>;
}
