// React Query cho bài viết của bác sĩ và bình luận (articlex-v1; UC80, UC81 mở rộng, UC82). Khóa gốc ['articlex', …].
// Hàng chờ duyệt của SUPER_MANAGER là `contentApi.listArticles({ status: 'PENDING_REVIEW' })` của content-v1, dùng `useArticleList` ở useArticles.ts (khóa ['articles', …]).
// Mọi lệnh ghi làm mới ['articlex'] (bài của tôi, bình luận), ['articles'] (hàng chờ duyệt và danh sách quản trị: duyệt / từ chối / ẩn đổi trạng thái bài) và ['public'] (trang tin tức
// có staleTime 5 phút và dùng chung QueryClient với màn quản trị: bài vừa duyệt, bình luận vừa đăng / ẩn phải hiện ngay). Làm mới cả khi BE từ chối: 409 và BR-BV-07 nghĩa là bài đã đổi
// trạng thái ở nơi khác (người duyệt vừa duyệt, tác giả vừa rút), 429 không đổi dữ liệu nhưng làm mới vẫn vô hại.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { articlexApi } from '../api/articlex.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import { QUERY_KEYS } from '../constants/queryKeys';
import type { CommentQuery, MyArticleQuery } from '../types/articlex';

const ROOT = ['articlex'] as const;

/** Lỗi 4xx (403, 404, 409…) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** #2: bài của bác sĩ đang đăng nhập, mới sửa trước. */
export const useMyArticles = (query: MyArticleQuery = {}) =>
  useQuery({ queryKey: [...ROOT, 'mine', query], queryFn: () => articlexApi.listMy(query), placeholderData: keepPreviousData, ...retry });

/** #3: chi tiết có nội dung; `null` = chưa chọn bài thì chưa gọi. Không giữ cache sau khi đóng form (`gcTime: 0`) để form sửa không khởi tạo từ bản cũ. */
export const useMyArticle = (articleId: number | null) =>
  useQuery({ queryKey: [...ROOT, 'mine', 'detail', articleId], queryFn: () => articlexApi.getMy(articleId!), enabled: articleId != null, gcTime: 0, ...retry });

/** #9: công khai; `null` = chưa biết slug thì chưa gọi. */
export const usePublicComments = (slug: string | null, query: CommentQuery = {}) =>
  useQuery({ queryKey: [...ROOT, 'comments', slug, query], queryFn: () => articlexApi.listComments(slug!, query), enabled: slug != null, placeholderData: keepPreviousData, ...retry });

/** Một hook cho cả lệnh ghi #1, #4–8, #10–14, vd `useArticlexMutation((id: number) => articlexApi.submit(id))`. */
export function useArticlexMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ROOT }),
        queryClient.invalidateQueries({ queryKey: ['articles'] }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.public.all }),
      ]),
  });
}
