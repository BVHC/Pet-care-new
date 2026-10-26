import { createElement, type ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { ApiError, toApiError } from '../api/api-error';
import { apiClient } from '../api/axios';
import { installMockApi } from '../api/mock/mock-api';
import {
  usePublicArticle,
  usePublicArticles,
  usePublicProduct,
  usePublicProducts,
  usePublicServiceList,
  usePublicVets,
} from './usePublicSite';

// Hook mỏng: gọi catalogApi / contentApi / identityApi thật qua apiClient (mock theo hợp đồng), nên bắt lỗi đường dẫn và cách đọc envelope.

beforeAll(() => installMockApi(apiClient));

function wrapper() {
  const client = new QueryClient();
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
}

describe('hook trang công khai (UC15–17), không cần đăng nhập', () => {
  it('bài viết: danh sách trả trang PUBLISHED, lọc theo chuyên mục; bài đọc được theo slug', async () => {
    const all = renderHook(() => usePublicArticles(), { wrapper: wrapper() });
    await waitFor(() => expect(all.result.current.isSuccess).toBe(true));
    expect(all.result.current.data).toMatchObject({ page: 0, size: 20, totalElements: 8 });

    const health = renderHook(() => usePublicArticles({ categorySlug: 'suc-khoe-thu-y' }), { wrapper: wrapper() });
    await waitFor(() => expect(health.result.current.isSuccess).toBe(true));
    expect(health.result.current.data?.content.map((a) => a.categorySlug)).toEqual(['suc-khoe-thu-y', 'suc-khoe-thu-y', 'suc-khoe-thu-y']);

    const slug = all.result.current.data!.content[0].slug;
    const one = renderHook(() => usePublicArticle(slug), { wrapper: wrapper() });
    await waitFor(() => expect(one.result.current.isSuccess).toBe(true));
    expect(one.result.current.data).toMatchObject({ slug, content: expect.stringContaining('<p>') });
  });

  it('bài lạ hoặc bài DRAFT: lỗi 404 (ApiError), không thử lại; chưa có slug thì không gọi', async () => {
    const unknown = renderHook(() => usePublicArticle('khong-co-bai-nay'), { wrapper: wrapper() });
    const draft = renderHook(() => usePublicArticle('meo-cao-sofa-5-cach-ngan-chan'), { wrapper: wrapper() });
    await waitFor(() => expect(unknown.result.current.isError).toBe(true));
    await waitFor(() => expect(draft.result.current.isError).toBe(true));
    for (const { result } of [unknown, draft]) {
      const error = toApiError(result.current.error);
      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ statusCode: 404, errorCode: 'RESOURCE_NOT_FOUND' });
      expect(result.current.failureCount).toBe(1);
    }

    const idle = renderHook(() => usePublicArticle(null), { wrapper: wrapper() });
    expect(idle.result.current.fetchStatus).toBe('idle');
  });

  it('dịch vụ không cần chọn chi nhánh; bác sĩ; sản phẩm đọc được, thuốc kê đơn là 404 và id chưa biết thì chưa gọi', async () => {
    const services = renderHook(() => usePublicServiceList(), { wrapper: wrapper() });
    const vets = renderHook(() => usePublicVets(), { wrapper: wrapper() });
    const products = renderHook(() => usePublicProducts({ size: 2 }), { wrapper: wrapper() });
    const prescription = renderHook(() => usePublicProduct(3), { wrapper: wrapper() });
    await waitFor(() => expect(services.result.current.isSuccess && vets.result.current.isSuccess && products.result.current.isSuccess).toBe(true));
    expect(services.result.current.data).toHaveLength(5);
    expect(vets.result.current.data?.map((v) => v.fullName)).toEqual(['BS. Minh Anh']);
    expect(products.result.current.data).toMatchObject({ size: 2, totalElements: 5 });

    await waitFor(() => expect(prescription.result.current.isError).toBe(true));
    expect(toApiError(prescription.result.current.error).statusCode).toBe(404);
    expect(renderHook(() => usePublicProduct(null), { wrapper: wrapper() }).result.current.fetchStatus).toBe('idle');
  });
});
