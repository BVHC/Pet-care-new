// React Query cho feedback (UC83 khách, UC84 quản lý chỉ xem). Khóa cục bộ, gốc ['feedback', …].
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { feedbackApi } from '../api/feedback.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { CreateFeedbackRequest, FeedbackQuery } from '../types/feedback';

/** Lỗi 4xx không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

const MINE_KEY = ['feedback', 'mine'] as const;
const LIST_KEY = ['feedback', 'list'] as const;

export const useMyFeedbacks = () => useQuery({ queryKey: MINE_KEY, queryFn: feedbackApi.listMine, ...retry });

/** Gửi xong thì làm mới danh sách của tôi; trang gọi tự báo thành công / lỗi. */
export function useSubmitFeedback() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateFeedbackRequest) => feedbackApi.submit(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: MINE_KEY }),
  });
}

export const useFeedbackList = (query: FeedbackQuery) =>
  useQuery({ queryKey: [...LIST_KEY, query], queryFn: () => feedbackApi.list(query), placeholderData: keepPreviousData, ...retry });

/** Chi tiết (#21) chỉ đọc nên là query bình thường: tự tải khi mở hộp thoại, "Thử lại" gọi `refetch`. */
export const useFeedbackDetail = (feedbackId: number) =>
  useQuery({ queryKey: ['feedback', 'detail', feedbackId], queryFn: () => feedbackApi.get(feedbackId), ...retry });
