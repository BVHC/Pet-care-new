import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { articlexApi } from '../api/articlex.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useArticlexMutation, useMyArticle, useMyArticles, usePublicComments } from './useArticlex';
import { installMockApi } from '../api/mock/mock-api';

// Điểm cần giữ: lệnh ghi làm mới ['articlex'], ['articles'], ['public'] cả khi BE từ chối, vì 409 / BR-BV-07 nghĩa là bài đã đổi trạng thái ở nơi khác.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};
beforeEach(() => signIn('doctor@store1.vn'));

const SLUG_1 = 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung';

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  const hook = renderHook(
    () => ({
      mine: useMyArticles(),
      detail: useMyArticle(13),
      none: useMyArticle(null),
      comments: usePublicComments(SLUG_1),
      submit: useArticlexMutation((id: number) => articlexApi.submit(id)),
      edit: useArticlexMutation((id: number) => articlexApi.updateMy(id, { title: 'Tiêu đề khác' })),
      comment: useArticlexMutation((content: string) => articlexApi.createComment(1, content)),
    }),
    { wrapper },
  );
  return { ...hook, client };
}

describe('hook đọc', () => {
  it('bài của tôi, chi tiết, bình luận công khai; chưa chọn bài thì không gọi', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.mine.data?.totalElements).toBe(4));
    await waitFor(() => expect(result.current.detail.data?.reviewNote).toContain('dẫn nguồn'));
    await waitFor(() => expect(result.current.comments.data?.totalElements).toBe(2));
    expect(result.current.none.fetchStatus).toBe('idle');
  });

  it('không retry lỗi 4xx: người không phải bác sĩ bị 403 ngay', async () => {
    await signIn('khach@petcare.vn');
    const { result } = setup();
    await waitFor(() => expect(result.current.mine.isError).toBe(true));
    expect(result.current.mine.failureCount).toBe(1);
  });
});

describe('useArticlexMutation làm mới cả ba khóa', () => {
  it('thành công: danh sách của tôi thấy trạng thái mới ngay; ["articles"] và ["public"] bị làm mới', async () => {
    const { result, client } = setup();
    await waitFor(() => expect(result.current.mine.data?.content.find((a) => a.articleId === 11)?.status).toBe('DRAFT'));
    client.setQueryData(['articles', 'list', {}], { stale: true });
    client.setQueryData(['public', 'articles', 'list'], { stale: true });

    await act(() => result.current.submit.mutateAsync(11)); // bài mẫu 11 đã có chuyên mục nên gửi duyệt được

    await waitFor(() => expect(result.current.mine.data?.content.find((a) => a.articleId === 11)?.status).toBe('PENDING_REVIEW'));
    expect(client.getQueryState(['articles', 'list', {}])?.isInvalidated).toBe(true);
    expect(client.getQueryState(['public', 'articles', 'list'])?.isInvalidated).toBe(true);
  });

  it('409: BE từ chối vì bài đã đổi trạng thái sau lúc mở màn hình, danh sách nạp lại trạng thái thật', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.mine.data?.content.find((a) => a.articleId === 12)?.status).toBe('PENDING_REVIEW'));
    await act(async () => {
      await expect(result.current.edit.mutateAsync(12)).rejects.toMatchObject({ response: { status: 409 } }); // đang chờ duyệt: BR-BV-07
    });
    await act(async () => {
      await expect(result.current.submit.mutateAsync(12)).rejects.toMatchObject({ response: { status: 409 } });
    });
    expect(result.current.mine.data?.content.find((a) => a.articleId === 12)?.status).toBe('PENDING_REVIEW');
  });

  it('khách đăng bình luận: danh sách công khai đang mở thấy ngay', async () => {
    await signIn('khach@petcare.vn');
    const { result } = setup();
    await waitFor(() => expect(result.current.comments.data?.totalElements).toBe(2));
    await act(() => result.current.comment.mutateAsync('Bài viết rất hay'));
    await waitFor(() => expect(result.current.comments.data?.totalElements).toBe(3));
    expect(result.current.comments.data?.content.at(-1)?.content).toBe('Bài viết rất hay');
  });
});
