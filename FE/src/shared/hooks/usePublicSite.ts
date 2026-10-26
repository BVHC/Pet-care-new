// React Query cho trang công khai (T46: dịch vụ, chi nhánh, bác sĩ, sản phẩm, bài viết), khóa gốc ['public', …].
// Chỉ đọc, không cần đăng nhập. Danh sách có phân trang giữ dữ liệu trang trước khi đổi trang / bộ lọc.
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { catalogApi } from '../api/catalog.api';
import { contentApi } from '../api/content.api';
import { identityApi } from '../api/identity.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import { QUERY_KEYS } from '../constants/queryKeys';
import type { ServiceGroup } from '../types/catalog';
import type { PublicArticleQuery, PublicProductQuery } from '../types/public-site';

// Chi nhánh và dịch vụ cho trang đặt lịch giữ ở useAppointments (cùng khóa ['public', …]); đặt lại đây để trang công khai import một chỗ.
export { usePublicBranches, usePublicServices } from './useAppointments';

const { public: keys } = QUERY_KEYS;

/** Lỗi 4xx không đổi khi thử lại (như hook công khai ở useAppointments). */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** Danh mục, bác sĩ, dịch vụ, bài viết ít đổi. */
const STATIC_STALE_MS = 5 * 60_000;
/** Chi nhánh còn hàng đổi theo kho. */
const STOCK_STALE_MS = 60_000;

/** Khác usePublicServices: không chờ chọn chi nhánh, lọc tùy chọn theo nhóm / chi nhánh (trang /services). */
export const usePublicServiceList = (params: { group?: ServiceGroup; branchId?: number } = {}) =>
  useQuery({
    queryKey: keys.services(params),
    queryFn: () => catalogApi.listPublicServices(params),
    staleTime: STATIC_STALE_MS,
    ...retry,
  });

export const usePublicVets = (branchId?: number) =>
  useQuery({
    queryKey: keys.vets(branchId),
    queryFn: () => identityApi.listPublicVets(branchId === undefined ? undefined : { branchId }),
    staleTime: STATIC_STALE_MS,
    ...retry,
  });

export const usePublicProductCategories = () =>
  useQuery({ queryKey: keys.productCategories(), queryFn: catalogApi.listPublicProductCategories, staleTime: STATIC_STALE_MS, ...retry });

export const usePublicProducts = (query: PublicProductQuery = {}) =>
  useQuery({
    queryKey: keys.products(query),
    queryFn: () => catalogApi.listPublicProducts(query),
    placeholderData: keepPreviousData,
    staleTime: STOCK_STALE_MS,
    ...retry,
  });

/** `null` = chưa biết sản phẩm nào (id trên URL chưa hợp lệ) thì chưa gọi. */
export const usePublicProduct = (productId: number | null) =>
  useQuery({
    queryKey: keys.product(productId ?? 0),
    queryFn: () => catalogApi.getPublicProduct(productId!),
    enabled: productId != null,
    staleTime: STOCK_STALE_MS,
    ...retry,
  });

export const usePublicArticleCategories = () =>
  useQuery({ queryKey: keys.articleCategories(), queryFn: contentApi.listPublicArticleCategories, staleTime: STATIC_STALE_MS, ...retry });

export const usePublicArticles = (query: PublicArticleQuery = {}) =>
  useQuery({
    queryKey: keys.articles(query),
    queryFn: () => contentApi.listPublicArticles(query),
    placeholderData: keepPreviousData,
    staleTime: STATIC_STALE_MS,
    ...retry,
  });

/** 404 cho slug lạ, bài DRAFT / HIDDEN (BR-BV-03). `null` = chưa có slug thì chưa gọi. */
export const usePublicArticle = (slug: string | null) =>
  useQuery({
    queryKey: keys.article(slug ?? ''),
    queryFn: () => contentApi.getPublicArticle(slug!),
    enabled: slug != null,
    staleTime: STATIC_STALE_MS,
    ...retry,
  });
