// React Query cho quản lý chuyên mục và bài viết (UC81, chỉ SUPER_MANAGER), khóa gốc ['articles', …] (khóa cục bộ, không nằm trong queryKeys.ts).
// Mọi lệnh ghi làm mới cả nhánh ['articles'] (số bài của chuyên mục đổi theo bài) lẫn ['public']: trang /news có staleTime 5 phút và dùng
// chung QueryClient với màn quản trị, không làm mới thì khách thấy bài cũ tới 5 phút. Làm mới cả khi lỗi: 409, 404, BR-BV-02 và
// BR-BV-04 nghĩa là dữ liệu đã đổi ở nơi khác.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { contentApi } from '../api/content.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import { QUERY_KEYS } from '../constants/queryKeys';
import type { ArticleListQuery } from '../types/content';

const ARTICLES_KEY = ['articles'] as const;

/** Lỗi 4xx (403, 404…) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

export const useArticleCategories = () =>
  useQuery({ queryKey: [...ARTICLES_KEY, 'categories'], queryFn: contentApi.listArticleCategories, ...retry });

export const useArticleList = (query: ArticleListQuery) =>
  useQuery({
    queryKey: [...ARTICLES_KEY, 'list', query],
    queryFn: () => contentApi.listArticles(query),
    placeholderData: keepPreviousData,
    ...retry,
  });

/**
 * Chi tiết có `content`. `null` = chưa chọn bài thì chưa gọi. Không giữ cache sau khi đóng form (`gcTime: 0`): mở lại luôn lấy bản mới
 * nhất, để form sửa không khởi tạo từ dữ liệu cũ.
 */
export const useArticle = (articleId: number | null) =>
  useQuery({
    queryKey: [...ARTICLES_KEY, 'detail', articleId],
    queryFn: () => contentApi.getArticle(articleId!),
    enabled: articleId != null,
    gcTime: 0,
    ...retry,
  });

export function useArticleMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () =>
      Promise.all([queryClient.invalidateQueries({ queryKey: ARTICLES_KEY }), queryClient.invalidateQueries({ queryKey: QUERY_KEYS.public.all })]),
  });
}
