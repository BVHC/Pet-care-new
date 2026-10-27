// React Query cho feedback (T47: UC83 khách, UC84 quản lý). Khóa cục bộ, gốc ['feedback', …].
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { feedbackApi } from '../api/feedback.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { CreateFeedbackRequest, FeedbackQuery, ResolveFeedbackRequest } from '../types/feedback';

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

/**
 * Mở chi tiết (#21) CÓ GHI DỮ LIỆU (NEW → SEEN) nên là mutation bắn từ thao tác bấm dòng, không phải query: không tự gọi
 * lại khi focus / mount, không prefetch. Mở xong làm mới danh sách để huy hiệu "Mới gửi" đổi thành "Đã xem".
 */
export function useOpenFeedback() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (feedbackId: number) => feedbackApi.open(feedbackId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: LIST_KEY }),
  });
}

export function useResolveFeedback() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ feedbackId, ...body }: ResolveFeedbackRequest & { feedbackId: number }) => feedbackApi.resolve(feedbackId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: LIST_KEY }),
  });
}
