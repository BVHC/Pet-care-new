// React Query cho vật tư tiêu hao theo lượt và thiếu hụt (ST07): khóa gốc ['materialuse']. Xử lý thiếu hụt làm mới cả gốc, kể cả khi BE từ chối
// (409 đã có người xử lý, 400 dòng không có thiếu hụt) vì danh sách đang hiện có thể đã cũ. Lỗi 4xx không thử lại (`shouldRetry`).
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { materialUseApi } from '../api/materialuse.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { MaterialShortageQuery, ResolveShortageRequest } from '../types/materialuse';

const opts = {
  retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
  retryDelay,
  refetchOnWindowFocus: false,
} as const;

export const useVisitMaterialUsages = (visitId: number | null) =>
  useQuery({ queryKey: ['materialuse', 'visit', visitId], queryFn: () => materialUseApi.listVisitUsages(visitId!), enabled: visitId != null, ...opts });

export const useMaterialShortages = (query: MaterialShortageQuery = {}) =>
  useQuery({ queryKey: ['materialuse', 'shortages', query], queryFn: () => materialUseApi.listShortages(query), placeholderData: keepPreviousData, ...opts });

export function useResolveShortage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ usageId, ...body }: ResolveShortageRequest & { usageId: number }) => materialUseApi.resolveShortage(usageId, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['materialuse'] }),
  });
}
