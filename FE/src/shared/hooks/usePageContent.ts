// React Query cho nội dung trang tĩnh (T47, UC21): trang công khai đọc, quản trị (SUPER_MANAGER) xem và sửa.
// Dữ liệu công khai dùng khóa ['public', 'pages', key] (staleTime 5 phút, như hook công khai khác) nên mọi lần sửa phải invalidate ['public'].
import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { pageContentApi } from '../api/page-content.api';
import { PAGE_DEFAULTS, parsePageContent, type PageContents } from '../constants/page-content';
import { QUERY_KEYS } from '../constants/queryKeys';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { PageContent, PageKey, UpdatePageContentRequest } from '../types/page-content';

const PUBLIC_STALE_MS = 5 * 60_000;
/** Khóa danh sách quản trị; cục bộ, không nằm ở queryKeys.ts. */
const ADMIN_KEY = ['page-contents'] as const;

/**
 * Nội dung một trang cho khách. `content` luôn dùng được (không bao giờ null): bản đã sửa nếu đúng cấu trúc, ngược lại PAGE_DEFAULTS
 * (BE seed `{}`, sai dạng, hoặc request lỗi). `ready=false` lúc chưa có câu trả lời: trang giữ khối (skeleton / ẩn), đừng vẽ `content`
 * khi đó, nếu không sẽ nháy từ mặc định sang chữ đã sửa. `ready=true` ngay từ lần lỗi đầu tiên: lỗi không được giữ skeleton qua các lần
 * thử lại (~7 s); nếu lần thử lại sau thành công thì `content` đổi theo.
 */
export function usePublicPage<K extends PageKey>(key: K): { content: PageContents[K]; ready: boolean } {
  const query = useQuery({
    queryKey: [...QUERY_KEYS.public.all, 'pages', key],
    queryFn: () => pageContentApi.getPublic(key),
    staleTime: PUBLIC_STALE_MS,
    retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
    retryDelay,
  });
  const content = useMemo(() => (query.data ? parsePageContent(key, query.data.content) : PAGE_DEFAULTS[key]), [key, query.data]);
  return { content, ready: !query.isPending || query.failureCount > 0 };
}

/** Quản trị: 4 trang theo thứ tự HOME_BANNER, ABOUT, POLICY, FAQ (403 với mọi vai trò khác SUPER_MANAGER). */
export const usePageContents = () => useQuery({ queryKey: ADMIN_KEY, queryFn: pageContentApi.list });

/**
 * Ghi đè hoàn toàn (`title` vắng thì BE lưu null). Thành công thì đặt ngay bản trả về vào danh sách quản trị (form không nháy lại chữ cũ
 * khi chờ, hay khi lần tải lại lỗi) rồi làm mới danh sách đó và mọi trang công khai.
 */
export function useSavePageContent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, ...body }: UpdatePageContentRequest & { key: PageKey }) => pageContentApi.save(key, body),
    onSuccess: (saved) => {
      queryClient.setQueryData<PageContent[]>(ADMIN_KEY, (list) => list?.map((p) => (p.key === saved.key ? saved : p)));
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ADMIN_KEY }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.public.all }),
      ]);
    },
  });
}
