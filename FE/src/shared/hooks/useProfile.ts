// React Query cho hồ sơ cá nhân (T13). Mọi thao tác ghi làm mới cả nhánh ['me']: liên kết hồ sơ đổi cả
// hồ sơ khách, sổ địa chỉ (hồ sơ online bị xóa) lẫn cờ chờ liên kết của GET /me.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { profileApi } from '../api/profile.api';

export const useMe = () => useQuery({ queryKey: ['me'], queryFn: profileApi.me });

export const useCustomerProfile = () => useQuery({ queryKey: ['me', 'customer-profile'], queryFn: profileApi.customerProfile });

export const useAddresses = () => useQuery({ queryKey: ['me', 'addresses'], queryFn: profileApi.addresses });

export function useProfileMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['me'] }),
  });
}
