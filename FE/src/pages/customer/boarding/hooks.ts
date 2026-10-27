// Dữ liệu công khai cho lưu trú (khách chưa đăng nhập cũng xem được): dịch vụ nhóm BOARDING = loại chuồng, kèm loài / cân nặng tối đa
// và các chi nhánh đang bật. Không gọi `boarding-availability` ở đây: endpoint đó cần đăng nhập.
import { useQuery } from '@tanstack/react-query';
import { catalogApi } from '@/shared/api/catalog.api';
import { toApiError } from '@/shared/api/api-error';
import { retryDelay, shouldRetry } from '@/shared/constants/realtime';
import { kennelOptions } from './rules';

/** Mọi loại chuồng đang kinh doanh ở ít nhất một chi nhánh ACTIVE (catalog-v1 #19, lọc nhóm BOARDING). */
export const usePublicKennelTypes = () =>
  useQuery({
    queryKey: ['public', 'services', 'boarding'],
    queryFn: async () => kennelOptions(await catalogApi.listPublicServices({ group: 'BOARDING' })),
    retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
    retryDelay,
  });
