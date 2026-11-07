import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { aiApi } from '../api/ai.api';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useAiDocument, useAiDocuments, useAiMutation, useAskChatbot, useItemProfiles, useRecommendations, useVaccinationSummary } from './useAi';
import { installMockApi } from '../api/mock/mock-api';

// Điểm cần giữ: lệnh ghi làm mới ['ai'] cả khi BE từ chối (409 vì tài liệu đã bị người khác lưu trữ), lỗi 4xx không thử lại, hỏi đáp chọn endpoint theo trạng thái đăng nhập.
// Tài liệu mẫu (ai.seed.ts): DRAFT 9 và 12; PUBLISHED 1–8, 10 và 13–16; ARCHIVED 11.

beforeAll(() => installMockApi(apiClient));

const login = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function setup<T>(hook: () => T) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(hook, { wrapper });
}

describe('tri thức và hồ sơ gợi ý (SUPER_MANAGER)', () => {
  beforeEach(() => login('chuoi@petcare.vn'));

  it('đọc: danh sách theo bộ lọc, chi tiết chỉ chạy khi có id, hồ sơ gợi ý', async () => {
    const { result } = setup(() => ({ drafts: useAiDocuments({ status: 'DRAFT' }), none: useAiDocument(null), one: useAiDocument(9), profiles: useItemProfiles({ missingOnly: true }) }));
    await waitFor(() => expect(result.current.drafts.data?.totalElements).toBe(2));
    expect(result.current.none.fetchStatus).toBe('idle');
    await waitFor(() => expect(result.current.one.data?.title).toBe('Chó sủa nhiều và lo âu khi xa chủ'));
    await waitFor(() => expect(result.current.profiles.data?.content.map((r) => r.itemId)).toEqual([3]));
  });

  it('ghi làm mới ["ai"]: lưu trữ bản nháp thì danh sách nháp và chi tiết cập nhật ngay', async () => {
    const { result } = setup(() => ({ drafts: useAiDocuments({ status: 'DRAFT' }), one: useAiDocument(9), archive: useAiMutation(aiApi.archiveDocument) }));
    await waitFor(() => expect(result.current.drafts.data?.totalElements).toBe(2));
    await waitFor(() => expect(result.current.one.data?.status).toBe('DRAFT'));

    await act(() => result.current.archive.mutateAsync(9));

    await waitFor(() => expect(result.current.drafts.data?.totalElements).toBe(1));
    await waitFor(() => expect(result.current.one.data?.status).toBe('ARCHIVED'));
  });

  it('BE từ chối 409 vì tài liệu đã đổi ở nơi khác: danh sách vẫn được đọc lại, không giữ trạng thái cũ', async () => {
    const { result } = setup(() => ({ published: useAiDocuments({ status: 'PUBLISHED', size: 100 }), archive: useAiMutation(aiApi.archiveDocument) }));
    await waitFor(() => expect(result.current.published.data?.content.some((d) => d.documentId === 5)).toBe(true));
    await aiApi.archiveDocument(5); // người khác lưu trữ trước, ngoài hook nên cache vẫn còn tài liệu 5

    await act(async () => {
      await expect(result.current.archive.mutateAsync(5)).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(result.current.published.data?.content.some((d) => d.documentId === 5)).toBe(false));
  });
});

describe('khách hàng', () => {
  beforeEach(() => login('khach@petcare.vn'));

  it('lịch tiêm và gợi ý theo thú; petId null thì không gọi; thú của người khác 404 và không thử lại', async () => {
    const { result } = setup(() => ({
      none: useVaccinationSummary(null),
      summary: useVaccinationSummary(2),
      recos: useRecommendations(2, { group: 'PRODUCT' }),
      others: useVaccinationSummary(4),
    }));
    expect(result.current.none.fetchStatus).toBe('idle');
    await waitFor(() => expect(result.current.summary.data?.petName).toBe('Bim'));
    await waitFor(() => expect(result.current.recos.data?.items.every((i) => i.group === 'PRODUCT')).toBe(true));
    await waitFor(() => expect(result.current.others.isError).toBe(true));
    expect(result.current.others.failureCount).toBe(1); // shouldRetry: 4xx không thử lại
  });

  it('useAskChatbot(true) gọi endpoint /me (PERSONAL cho lịch tiêm), useAskChatbot(false) gọi endpoint công khai (NEED_LOGIN)', async () => {
    const { result } = setup(() => ({ signedIn: useAskChatbot(true), guest: useAskChatbot(false) }));
    const personal = await act(() => result.current.signedIn.mutateAsync({ question: 'Mochi tiêm khi nào?' }));
    const guest = await act(() => result.current.guest.mutateAsync({ question: 'Mũi tiêm tiếp theo của tôi là khi nào?' }));
    expect(personal.result).toBe('PERSONAL');
    expect(guest.result).toBe('NEED_LOGIN');
  });

  it('hỏi đáp bị 400 (câu hỏi rỗng) vẫn ném lỗi để giao diện hiện thông báo', async () => {
    const { result } = setup(() => useAskChatbot(false));
    await act(async () => {
      await expect(result.current.mutateAsync({ question: ' ' })).rejects.toMatchObject({ response: { status: 400, data: { errorCode: 'VALIDATION_FAILED' } } });
    });
  });
});
