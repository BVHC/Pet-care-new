// React Query cho phí vận chuyển, định mức vật tư (UC32): khóa gốc ['fees']. Lệnh ghi làm mới cả gốc, kể cả khi BE từ chối
// (400 BR-SP-…, 404) vì form đang mở có thể đang giữ số cũ. Lỗi 4xx không thử lại (`shouldRetry`).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { feesApi } from '../api/fees.api';
import { retryDelay, shouldRetry } from '../constants/realtime';

const opts = {
  retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
  retryDelay,
  refetchOnWindowFocus: false,
} as const;

export const useShippingFeeConfig = () => useQuery({ queryKey: ['fees', 'shipping'], queryFn: feesApi.getShippingFeeConfig, ...opts });

export const useMaterialNorms = (serviceId: number | null) =>
  useQuery({ queryKey: ['fees', 'norms', serviceId], queryFn: () => feesApi.listMaterialNorms(serviceId!), enabled: serviceId != null, ...opts });

export function useFeesMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSettled: () => queryClient.invalidateQueries({ queryKey: ['fees'] }) });
}
