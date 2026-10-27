import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/api-error';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { PAGES_STATE_KEY } from '../api/mock/page-content.mock';
import { pageContentApi } from '../api/page-content.api';
import { PAGE_DEFAULTS, PAGE_KEYS } from '../constants/page-content';
import type { PageContent } from '../types/page-content';
import { useSession } from '../stores/session.store';
import { usePageContents, usePublicPage, useSavePageContent } from './usePageContent';

// Hook mỏng: gọi pageContentApi thật qua apiClient (mock theo hợp đồng), nên bắt lỗi đường dẫn, cách đọc envelope và key query.
// Lỗi giả dùng 403 vì hook công khai không thử lại 4xx; riêng ca 5xx thử đúng một nhịp rồi hủy khi gỡ hook.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());
afterEach(() => useSession.getState().clear());

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return { client, wrapper };
}

/** Ghi thẳng state mock như admin đã sửa (mock đọc lại state ở mọi request). */
function setPages(pages: Record<string, unknown>) {
  const state = Object.fromEntries(
    Object.entries(pages).map(([key, content]) => [key, { key, title: null, content, updatedAt: '2026-10-20T08:00:00+07:00' }]),
  );
  localStorage.setItem(PAGES_STATE_KEY, JSON.stringify(state));
}

const editedAbout = { ...PAGE_DEFAULTS.ABOUT, stats: PAGE_DEFAULTS.ABOUT.stats.map((s) => ({ ...s, value: '99+' })) };

describe('usePublicPage: nội dung đã parse hoặc mặc định + cờ ready (để trang không nháy)', () => {
  it('chưa tải xong: ready=false, content là mặc định; tải xong với {} (BE seed) thì ready=true và vẫn là mặc định', async () => {
    const { wrapper } = setup();
    const { result } = renderHook(() => usePublicPage('ABOUT'), { wrapper });
    expect(result.current.ready).toBe(false);
    expect(result.current.content).toEqual(PAGE_DEFAULTS.ABOUT);
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.content).toEqual(PAGE_DEFAULTS.ABOUT);
  });

  it('nội dung đã sửa: trả bản sửa (đã parse), chỉ đúng khóa được hỏi', async () => {
    setPages({ ABOUT: editedAbout });
    const { wrapper } = setup();
    const about = renderHook(() => usePublicPage('ABOUT'), { wrapper });
    const faq = renderHook(() => usePublicPage('FAQ'), { wrapper });
    await waitFor(() => expect(about.result.current.ready && faq.result.current.ready).toBe(true));
    expect(about.result.current.content.stats.map((s) => s.value)).toEqual(['99+', '99+', '99+', '99+']);
    expect(faq.result.current.content).toEqual(PAGE_DEFAULTS.FAQ);
  });

  it('sai dạng (kiểu, thiếu trường, quá giới hạn): ready nhưng cả bộ về mặc định', async () => {
    setPages({ ABOUT: { stats: 'nhiều' }, FAQ: { items: [{ question: 'q' }] }, HOME_BANNER: { ...PAGE_DEFAULTS.HOME_BANNER, headline: 'x'.repeat(31) } });
    const { wrapper } = setup();
    const hooks = [
      renderHook(() => usePublicPage('ABOUT'), { wrapper }),
      renderHook(() => usePublicPage('FAQ'), { wrapper }),
      renderHook(() => usePublicPage('HOME_BANNER'), { wrapper }),
    ];
    await waitFor(() => expect(hooks.every((h) => h.result.current.ready)).toBe(true));
    expect(hooks[0].result.current.content).toEqual(PAGE_DEFAULTS.ABOUT);
    expect(hooks[1].result.current.content).toEqual(PAGE_DEFAULTS.FAQ);
    expect(hooks[2].result.current.content).toEqual(PAGE_DEFAULTS.HOME_BANNER);
  });

  it('request lỗi 4xx (không thử lại): ready=true và là mặc định, không throw', async () => {
    vi.spyOn(pageContentApi, 'getPublic').mockRejectedValue(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    const { wrapper } = setup();
    const { result } = renderHook(() => usePublicPage('POLICY'), { wrapper });
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.content).toEqual(PAGE_DEFAULTS.POLICY);
  });

  it('request lỗi 5xx: hiện mặc định ngay sau lần lỗi đầu, không chờ hết các lần thử lại (~7 s)', async () => {
    const spy = vi.spyOn(pageContentApi, 'getPublic').mockRejectedValue(new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 500));
    const { wrapper } = setup();
    const { result, unmount } = renderHook(() => usePublicPage('FAQ'), { wrapper });
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(spy).toHaveBeenCalledTimes(1);
    expect(result.current.content).toEqual(PAGE_DEFAULTS.FAQ);
    unmount();
  });

  it('khóa query bắt đầu bằng ["public", …]; đối tượng content giữ nguyên giữa các lần render', async () => {
    setPages({ ABOUT: editedAbout });
    const { client, wrapper } = setup();
    const { result, rerender } = renderHook(() => usePublicPage('ABOUT'), { wrapper });
    await waitFor(() => expect(result.current.ready).toBe(true));
    const first = result.current.content;
    rerender();
    expect(result.current.content).toBe(first);
    expect(client.getQueryCache().getAll().map((q) => q.queryKey)).toEqual([['public', 'pages', 'ABOUT']]);
  });
});

describe('usePageContents (admin, SUPER_MANAGER)', () => {
  it('trả 4 trang theo thứ tự BE', async () => {
    await signIn('chuoi@petcare.vn');
    const { wrapper } = setup();
    const { result } = renderHook(() => usePageContents(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.map((p) => p.key)).toEqual([...PAGE_KEYS]);
  });

  it('vai trò khác (ADMIN kỹ thuật): lỗi 403', async () => {
    await signIn('admin@petcare.vn');
    const { wrapper } = setup();
    const { result } = renderHook(() => usePageContents(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ response: { status: 403 } });
  });
});

describe('useSavePageContent: ghi đè rồi làm mới trang công khai', () => {
  beforeEach(() => signIn('chuoi@petcare.vn'));

  it('gửi {title?, content} tới đúng khóa; title vắng thì không gửi', async () => {
    const spy = vi.spyOn(pageContentApi, 'save').mockResolvedValue({} as never);
    const { wrapper } = setup();
    const { result } = renderHook(() => useSavePageContent(), { wrapper });
    await act(() => result.current.mutateAsync({ key: 'FAQ', content: { a: 1 } }));
    expect(spy).toHaveBeenLastCalledWith('FAQ', { content: { a: 1 } });
    await act(() => result.current.mutateAsync({ key: 'ABOUT', title: 'Tiêu đề', content: {} }));
    expect(spy).toHaveBeenLastCalledWith('ABOUT', { title: 'Tiêu đề', content: {} });
  });

  it('thành công thì invalidate ["public"] và danh sách quản trị (staleTime 5 phút nên không invalidate là trang công khai cũ mãi)', async () => {
    const { client, wrapper } = setup();
    const spy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useSavePageContent(), { wrapper });
    await act(() => result.current.mutateAsync({ key: 'ABOUT', content: editedAbout }));
    const keys = spy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey);
    expect(keys).toContainEqual(['public']);
    expect(keys).toContainEqual(['page-contents']);
  });

  it('trang công khai đang mở (dùng chung QueryClient) đổi sang bản vừa lưu mà không cần tải lại', async () => {
    const { wrapper } = setup();
    const { result } = renderHook(() => ({ page: usePublicPage('ABOUT'), save: useSavePageContent() }), { wrapper });
    await waitFor(() => expect(result.current.page.ready).toBe(true));
    expect(result.current.page.content.stats[0].value).toBe('10K+');
    await act(() => result.current.save.mutateAsync({ key: 'ABOUT', content: editedAbout }));
    await waitFor(() => expect(result.current.page.content.stats[0].value).toBe('99+'));
  });

  it('cache danh sách quản trị có ngay bản vừa lưu (đúng phần tử, giữ thứ tự), kể cả khi lần làm mới sau đó lỗi', async () => {
    const { client, wrapper } = setup();
    const { result } = renderHook(() => ({ list: usePageContents(), save: useSavePageContent() }), { wrapper });
    await waitFor(() => expect(result.current.list.isSuccess).toBe(true));
    const before = client.getQueryData<PageContent[]>(['page-contents'])!;
    // Ép lỗi ở tầng axios: hook giữ tham chiếu pageContentApi.list từ lúc render nên spy lên hàm API không có hiệu lực.
    vi.spyOn(apiClient, 'get').mockRejectedValue(new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 500));

    await act(() => result.current.save.mutateAsync({ key: 'ABOUT', title: 'Giới thiệu', content: editedAbout }));
    await waitFor(() => expect(result.current.list.isError).toBe(true)); // lần làm mới đã chạy và lỗi: dữ liệu còn lại là bản cập nhật tại chỗ
    const after = result.current.list.data!;
    expect(after.map((p) => p.key)).toEqual([...PAGE_KEYS]);
    expect(after.find((p) => p.key === 'ABOUT')).toMatchObject({ title: 'Giới thiệu', content: editedAbout });
    expect(after.filter((p) => p.key !== 'ABOUT')).toEqual(before.filter((p) => p.key !== 'ABOUT'));
  });

  it('danh sách quản trị chưa có trong cache thì lưu xong cũng không tạo mới', async () => {
    const { client, wrapper } = setup();
    const { result } = renderHook(() => useSavePageContent(), { wrapper });
    await act(() => result.current.mutateAsync({ key: 'ABOUT', content: editedAbout }));
    expect(client.getQueryCache().find({ queryKey: ['page-contents'] })).toBeUndefined();
  });

  it('lỗi 400 (quá 64 KB hoặc không phải object): không làm mới gì', async () => {
    const { client, wrapper } = setup();
    const spy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useSavePageContent(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ key: 'ABOUT', content: [] as never }).catch(() => undefined);
    });
    expect(result.current.isError).toBe(true);
    expect(spy).not.toHaveBeenCalled();
  });
});
