// React Query cho AI (tầng 3, UC90–UC94). Khóa gốc ['ai', …]. Mọi lệnh ghi (tạo / sửa / xuất bản / lưu trữ / nạp lại tài liệu, lưu hồ sơ gợi ý, và cả hỏi đáp) làm mới khóa gốc,
// kể cả khi BE từ chối 4xx: lệnh có thể đã đổi trạng thái (vd tài liệu đã bị lưu trữ ở tab khác → 409) nên danh sách / chi tiết phải đọc lại, không giữ trạng thái cũ.
// Lịch tiêm và gợi ý tính tại thời điểm xem nên không tự hỏi lại khi focus; lỗi 4xx (404 thú của người khác, 400 thú đã mất) không đổi khi thử lại.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { aiApi } from '../api/ai.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { AiDocumentQuery, ItemProfileQuery, RecommendationQuery } from '../types/ai';

const opts = { retry: shouldRetry, retryDelay, refetchOnWindowFocus: false } as const;

export const useAiDocuments = (query: AiDocumentQuery = {}) =>
  useQuery({ queryKey: ['ai', 'documents', query], queryFn: () => aiApi.listDocuments(query), placeholderData: keepPreviousData, ...opts });

export const useAiDocument = (documentId: number | null) =>
  useQuery({ queryKey: ['ai', 'document', documentId], queryFn: () => aiApi.getDocument(documentId!), enabled: documentId != null, ...opts });

export const useItemProfiles = (query: ItemProfileQuery = {}) =>
  useQuery({ queryKey: ['ai', 'item-profiles', query], queryFn: () => aiApi.listItemProfiles(query), placeholderData: keepPreviousData, ...opts });

export const useVaccinationSummary = (petId: number | null) =>
  useQuery({ queryKey: ['ai', 'vaccination-summary', petId], queryFn: () => aiApi.getVaccinationSummary(petId!), enabled: petId != null, ...opts });

export const useRecommendations = (petId: number | null, query: RecommendationQuery = {}) =>
  useQuery({ queryKey: ['ai', 'recommendations', petId, query], queryFn: () => aiApi.getRecommendations(petId!, query), enabled: petId != null, ...opts });

export function useAiMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSettled: () => queryClient.invalidateQueries({ queryKey: ['ai'] }) });
}

/** Hỏi đáp: khách đăng nhập gọi #2 (cá nhân hóa), chưa đăng nhập gọi #1. */
export const useAskChatbot = (authenticated: boolean) => useAiMutation(authenticated ? aiApi.askAsCustomer : aiApi.ask);
