// React Query cho chi nhánh (T20). Mọi thao tác ghi làm mới cả nhánh ['branch'] vì các phần phụ thuộc nhau
// (giờ mở cửa → khung giờ của quota, kích hoạt → danh sách, ngày nghỉ → khung giờ…).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { branchApi } from '../api/branch.api';
import type { QuotaGroup } from '../types/branch';

export const useBranches = () => useQuery({ queryKey: ['branch', 'list'], queryFn: () => branchApi.listBranches() });

export const useBranch = (branchId: number) =>
  useQuery({ queryKey: ['branch', branchId], queryFn: () => branchApi.getBranch(branchId) });

export const useOpeningHours = (branchId: number) =>
  useQuery({ queryKey: ['branch', branchId, 'hours'], queryFn: () => branchApi.listOpeningHours(branchId) });

/** `from` bỏ trống = mọi ngày nghỉ, kể cả đã qua. */
export const useHolidays = (branchId: number, from?: string) =>
  useQuery({ queryKey: ['branch', branchId, 'holidays', from], queryFn: () => branchApi.listHolidays(branchId, { from }) });

export const useBranchServices = (branchId: number) =>
  useQuery({ queryKey: ['branch', branchId, 'services'], queryFn: () => branchApi.listServices(branchId) });

export const useQuotaDefaults = (branchId: number) =>
  useQuery({ queryKey: ['branch', branchId, 'quota-defaults'], queryFn: () => branchApi.listQuotaDefaults(branchId) });

export const useSlotQuotas = (branchId: number, from: string, to: string, serviceGroup?: QuotaGroup) =>
  useQuery({
    queryKey: ['branch', branchId, 'slot-quotas', from, to, serviceGroup],
    queryFn: () => branchApi.listSlotQuotas(branchId, { from, to, serviceGroup }),
  });

export function useBranchMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['branch'] }),
  });
}
