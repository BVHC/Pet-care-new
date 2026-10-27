// Hook riêng của màn Chuồng lưu trú (UC54). Hook dùng chung ở shared/hooks/useBoarding.ts; ở đây chỉ phần ghép riêng cho màn này.
import { useMemo } from 'react';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBookingList, useBoardingMutation } from '@/shared/hooks/useBoarding';
import { usePublicServices } from '@/shared/hooks/usePublicSite';
import { SPECIES_LABELS } from '@/shared/types/catalog';
import type { Booking, CreateKennelRequest, UpdateKennelRequest } from '@/shared/types/boarding';
import type { KennelTypeRef } from './capacity';

export interface KennelTypeOption extends KennelTypeRef {
  /** "Chó, tối đa 10 kg" */
  spec: string | null;
}

/**
 * Loại chuồng tạo được ở chi nhánh: dịch vụ công khai nhóm BOARDING bật tại chi nhánh (BR-LT-01). Không dùng GET /kennels (không có loại chưa có chuồng).
 * `branchId` null (không phải quản lý chi nhánh) thì không gọi.
 */
export function useKennelTypes(branchId: number | null) {
  const query = usePublicServices(branchId);
  const types = useMemo<KennelTypeOption[]>(
    () =>
      (query.data ?? [])
        .filter((s) => s.group === 'BOARDING')
        .map((s) => ({
          kennelTypeId: s.serviceId,
          name: s.name,
          spec: s.kennelType ? `${SPECIES_LABELS[s.kennelType.species]}, tối đa ${s.kennelType.maxWeightKg} kg` : null,
        })),
    [query.data],
  );
  return { types, isLoading: query.isLoading, isError: query.isError, error: query.error };
}

/** Tra mã đặt chỗ + tên thú cho cột "Đang giữ": một lần gọi (đặt chỗ đang lưu trú, tối đa 100 theo BE), không gọi từng chuồng. */
export function useStayLookup(enabled: boolean): Map<number, Booking> {
  const { data } = useBookingList({ inStay: true, size: 100 }, enabled);
  return useMemo(() => new Map((data?.content ?? []).map((b) => [b.bookingId, b])), [data]);
}

export const useCreateKennel = () => useBoardingMutation((body: CreateKennelRequest) => boardingApi.createKennel(body));
export const useUpdateKennel = () =>
  useBoardingMutation(({ kennelId, body }: { kennelId: number; body: UpdateKennelRequest }) => boardingApi.updateKennel(kennelId, body));
export const useDeleteKennel = () => useBoardingMutation((kennelId: number) => boardingApi.deleteKennel(kennelId));
export const useStartMaintenance = () => useBoardingMutation((kennelId: number) => boardingApi.startMaintenance(kennelId));
export const useEndMaintenance = () => useBoardingMutation((kennelId: number) => boardingApi.endMaintenance(kennelId));
