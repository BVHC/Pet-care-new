import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { toApiError } from '../api/api-error';
import { apiClient } from '../api/axios';
import { contentApi } from '../api/content.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { useSession } from '../stores/session.store';
import { useArticle, useArticleCategories, useArticleList, useArticleMutation } from './useArticles';
import { usePublicArticles } from './usePublicSite';

// Hook mỏng: gọi contentApi thật qua apiClient (mock theo hợp đồng content-v1). Điểm cần giữ: mọi lệnh ghi làm mới cả nhánh admin lẫn
// ['public'], vì trang /news có staleTime 5 phút và dùng chung QueryClient với màn quản trị.

beforeAll(() => installMockApi(apiClient));

async function loginAs(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

beforeEach(() => loginAs('chuoi@petcare.vn'));

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return { client, wrapper };
}

const isInvalidated = (client: QueryClient, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated === true;

describe('đọc (chỉ SUPER_MANAGER)', () => {
  it('danh sách chuyên mục, danh sách bài (phân trang), chi tiết bài có content', async () => {
    const { wrapper } = setup();
    const categories = renderHook(() => useArticleCategories(), { wrapper });
    const list = renderHook(() => useArticleList({ status: 'PUBLISHED', size: 3 }), { wrapper });
    const detail = renderHook(() => useArticle(6), { wrapper });
    await waitFor(() => expect(categories.result.current.isSuccess && list.result.current.isSuccess && detail.result.current.isSuccess).toBe(true));
    expect(categories.result.current.data).toHaveLength(4);
    expect(list.result.current.data).toMatchObject({ size: 3, totalElements: 8, totalPages: 3 });
    expect(detail.result.current.data).toMatchObject({ articleId: 6, status: 'DRAFT', content: expect.stringContaining('<h2>') });
  });

  it('chưa có id (null) thì chưa gọi chi tiết', () => {
    const { wrapper } = setup();
    expect(renderHook(() => useArticle(null), { wrapper }).result.current.fetchStatus).toBe('idle');
  });

  it('vai trò khác SUPER_MANAGER: 403 và không thử lại (lỗi 4xx không đổi khi thử lại)', async () => {
    await loginAs('manager@store1.vn');
    const client = new QueryClient(); // mặc định thử lại 3 lần: hook phải tự tắt với 4xx
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
    const list = renderHook(() => useArticleList({}), { wrapper });
    await waitFor(() => expect(list.result.current.isError).toBe(true));
    expect(toApiError(list.result.current.error)).toMatchObject({ statusCode: 403, errorCode: 'ACCESS_DENIED' });
    expect(list.result.current.failureCount).toBe(1);
  });
});

describe('lệnh ghi làm mới nhánh admin và ["public"]', () => {
  it('thành công: đánh dấu cũ mọi truy vấn ["articles"] và ["public"], không đụng nhánh khác', async () => {
    const { client, wrapper } = setup();
    client.setQueryData(['articles', 'categories'], []);
    client.setQueryData(['articles', 'list', {}], {});
    client.setQueryData(['public', 'articles', 'list', {}], {});
    client.setQueryData(['public', 'article-categories'], []);
    client.setQueryData(['catalog', 'categories'], []);
    const { result } = renderHook(() => useArticleMutation((id: number) => contentApi.hideArticle(id)), { wrapper });

    await act(() => result.current.mutateAsync(1));

    for (const key of [['articles', 'categories'], ['articles', 'list', {}], ['public', 'articles', 'list', {}], ['public', 'article-categories']]) {
      expect(isInvalidated(client, key), JSON.stringify(key)).toBe(true);
    }
    expect(isInvalidated(client, ['catalog', 'categories'])).toBe(false);
  });

  it('lỗi cũng làm mới (409 / BR-BV-04 nghĩa là dữ liệu đã đổi ở nơi khác)', async () => {
    const { client, wrapper } = setup();
    client.setQueryData(['articles', 'list', {}], {});
    client.setQueryData(['public', 'articles', 'list', {}], {});
    const { result } = renderHook(() => useArticleMutation((id: number) => contentApi.publishArticle(id)), { wrapper });

    await act(async () => {
      await expect(result.current.mutateAsync(1)).rejects.toMatchObject({ response: { status: 409 } }); // #1 đã PUBLISHED
    });

    expect(isInvalidated(client, ['articles', 'list', {}])).toBe(true);
    expect(isInvalidated(client, ['public', 'articles', 'list', {}])).toBe(true);
  });

  it('trang công khai (staleTime 5 phút) thấy bài vừa đăng ngay sau lệnh ghi, bài vừa gỡ thì biến mất', async () => {
    const { wrapper } = setup();
    const { result } = renderHook(() => ({ news: usePublicArticles(), publish: useArticleMutation(contentApi.publishArticle), hide: useArticleMutation(contentApi.hideArticle) }), {
      wrapper,
    });
    await waitFor(() => expect(result.current.news.isSuccess).toBe(true));
    const slugsOf = () => result.current.news.data?.content.map((a) => a.slug) ?? [];
    expect(slugsOf()).not.toContain('meo-cao-sofa-5-cach-ngan-chan');

    await act(() => result.current.publish.mutateAsync(6));
    await waitFor(() => expect(slugsOf()).toContain('meo-cao-sofa-5-cach-ngan-chan'));

    await act(() => result.current.hide.mutateAsync(6));
    await waitFor(() => expect(slugsOf()).not.toContain('meo-cao-sofa-5-cach-ngan-chan'));
  });

  it('màn Chuyên mục thấy số bài đổi sau khi tạo / xóa bài', async () => {
    const { wrapper } = setup();
    const { result } = renderHook(
      () => ({ categories: useArticleCategories(), create: useArticleMutation(contentApi.createArticle), remove: useArticleMutation(contentApi.deleteArticle) }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.categories.isSuccess).toBe(true));
    const countOf = () => result.current.categories.data?.find((c) => c.categoryId === 3)?.articleCount;
    expect(countOf()).toBe(2);

    let created = 0;
    await act(async () => {
      created = (
        await result.current.create.mutateAsync({ categoryId: 3, title: 'Mới', slug: 'moi', content: '<p>x</p>', authorDisplayName: 'A' })
      ).articleId;
    });
    await waitFor(() => expect(countOf()).toBe(3));

    await act(() => result.current.remove.mutateAsync(created));
    await waitFor(() => expect(countOf()).toBe(2));
  });
});
