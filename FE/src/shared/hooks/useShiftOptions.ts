// Nút mở ca nào hiện cho lễ tân (UC71): đọc chi nhánh của mình (branch-v1 #3 cờ cấp cứu, #6 giờ mở cửa, #9 ngày nghỉ — A06 đọc được)
// rồi để `shiftOptions` quyết định. Khóa query trùng useBranch / useOpeningHours / useHolidays nên dùng chung cache.
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { branchApi } from '../api/branch.api';
import { useAccount } from '../stores/session.store';
import { shiftOptions, type ShiftOptions } from '../utils/shift-options';
import { vnDate } from '../utils/vn-time';
import { useNow } from './useNow';

export interface ShiftOptionsState {
  /** null khi chưa đủ dữ liệu chi nhánh. */
  options: ShiftOptions | null;
  isLoading: boolean;
  isError: boolean;
  refetch: () => void;
}

export function useShiftOptions(enabled = true): ShiftOptionsState {
  const branchId = useAccount()?.branchId ?? null;
  const now = useNow();
  const on = enabled && branchId != null;
  const today = vnDate(now);

  const branch = useQuery({ queryKey: ['branch', branchId], queryFn: () => branchApi.getBranch(branchId!), enabled: on });
  const hours = useQuery({ queryKey: ['branch', branchId, 'hours'], queryFn: () => branchApi.listOpeningHours(branchId!), enabled: on });
  const holidays = useQuery({
    queryKey: ['branch', branchId, 'holidays', today],
    queryFn: () => branchApi.listHolidays(branchId!, { from: today }),
    enabled: on,
  });

  const acceptsAfterHours = branch.data?.acceptsAfterHoursEmergency;
  const options = useMemo(
    () =>
      hours.data && holidays.data && acceptsAfterHours !== undefined
        ? shiftOptions({ hoursVersions: hours.data, holidays: holidays.data, acceptsAfterHours, nowMs: now })
        : null,
    [hours.data, holidays.data, acceptsAfterHours, now],
  );

  const queries = [branch, hours, holidays];
  return {
    options,
    isLoading: on && queries.some((q) => q.isLoading),
    isError: !on || queries.some((q) => q.isError),
    refetch: () => queries.forEach((q) => q.isError && void q.refetch()),
  };
}
