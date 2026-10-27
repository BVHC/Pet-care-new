// React Query cho việc gọi điện của lễ tân (UC87, care-v1 #1–4): khóa gốc ['care-tasks'].
// Hoàn tất / hủy làm mới danh sách dù thành công hay bị từ chối: 409 hoặc 404 nghĩa là danh sách đang hiện đã cũ.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { careApi } from '../api/care.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { CancelCareTaskRequest, CareTaskQuery, CompleteCareTaskRequest } from '../types/care';

/** Lỗi 4xx (kể cả 403) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

const CARE_TASKS_KEY = ['care-tasks'] as const;

export const useCareTasks = (query: CareTaskQuery) =>
  useQuery({
    queryKey: [...CARE_TASKS_KEY, 'list', query],
    queryFn: () => careApi.listTasks(query),
    placeholderData: keepPreviousData,
    ...retry,
  });

function useCareTaskWrite<A>(fn: (args: A) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSettled: () => queryClient.invalidateQueries({ queryKey: CARE_TASKS_KEY }) });
}

export const useCompleteCareTask = () =>
  useCareTaskWrite(({ careTaskId, body }: { careTaskId: number; body: CompleteCareTaskRequest }) => careApi.completeTask(careTaskId, body));

export const useCancelCareTask = () =>
  useCareTaskWrite(({ careTaskId, body }: { careTaskId: number; body: CancelCareTaskRequest }) => careApi.cancelTask(careTaskId, body));
