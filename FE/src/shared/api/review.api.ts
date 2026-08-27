// Review API hook - using React Query + axios client
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './axios';

export interface Review {
  id: number;
  productId?: number;
  authorName: string;
  rating: number;
  comment: string;
  createdAt: string;
}

export function useReviews(targetId?: number | string) {
  return useQuery({
    queryKey: ['reviews', targetId ?? 'all'],
    queryFn: async () => {
      const endpoint = targetId ? `/reviews?productId=${targetId}` : '/reviews';
      const { data } = await apiClient.get<Review[]>(endpoint);
      return data;
    },
  });
}

export function useCreateReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { productId: number; rating: number; comment: string }) =>
      apiClient.post('/reviews', input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reviews'] });
    },
  });
}
