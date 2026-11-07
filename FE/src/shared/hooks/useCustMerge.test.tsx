import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useMergeCustomers, useMergePreview } from './useCustMerge';
import { installMockApi } from '../api/mock/mock-api';

// Điểm cần giữ: lệnh ghi làm mới ['custmerge'] và ['counter-customers'] cả khi BE từ chối, vì BR-KH-11 / BR-KH-14 nghĩa là dữ liệu hai hồ sơ đã đổi sau lúc xem trước.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

/** Lễ tân tạo hai hồ sơ tại quầy trùng SĐT rồi quản lý đăng nhập lại. */
async function twoDuplicates() {
  await signIn('reception@store1.vn');
  const create = async () => (await apiClient.post('/api/customers', { fullName: 'Hà Minh', phone: '0905123123' })).data.data.customer.customerId as number;
  const ids = [await create(), await create()];
  await apiClient.post(`/api/customers/${ids[0]}/pets`, { name: 'Bi', species: 'DOG', sex: 'MALE' });
  await signIn('manager@store1.vn');
  return ids;
}

beforeEach(() => useSession.getState().clear());

function setup(target: number | null, source: number | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  const hook = renderHook(() => ({ preview: useMergePreview(target, source), merge: useMergeCustomers() }), { wrapper });
  return { ...hook, client };
}

describe('useMergePreview', () => {
  it('đọc xem trước; chưa chọn đủ hai hồ sơ hoặc chọn trùng thì không gọi', async () => {
    const [a, b] = await twoDuplicates();
    const { result } = setup(b, a);
    await waitFor(() => expect(result.current.preview.data?.petCount).toBe(1));
    expect(result.current.preview.data?.blockers).toEqual([]);
    expect(setup(b, null).result.current.preview.fetchStatus).toBe('idle');
    expect(setup(a, a).result.current.preview.fetchStatus).toBe('idle');
  });

  it('không retry lỗi 4xx: nhân viên không phải quản lý bị 403 ngay', async () => {
    const [a, b] = await twoDuplicates();
    await signIn('reception@store1.vn');
    const { result } = setup(b, a);
    await waitFor(() => expect(result.current.preview.isError).toBe(true));
    expect(result.current.preview.failureCount).toBe(1);
  });
});

describe('useMergeCustomers làm mới ["custmerge"]', () => {
  it('thành công: xem trước đang mở thấy hồ sơ nguồn đã gộp ngay; ["counter-customers"] bị làm mới', async () => {
    const [a, b] = await twoDuplicates();
    const { result, client } = setup(b, a);
    await waitFor(() => expect(result.current.preview.data?.blockers).toEqual([]));
    client.setQueryData(['counter-customers', 'detail', b], { stale: true });

    await act(() => result.current.merge.mutateAsync({ sourceCustomerId: a, targetCustomerId: b, confirm: true }));

    await waitFor(() => expect(result.current.preview.data?.blockers.join('')).toContain('đã được gộp'));
    expect(client.getQueryState(['counter-customers', 'detail', b])?.isInvalidated).toBe(true);
  });

  it('BR-KH-14: BE từ chối vì chưa xác nhận, các khóa vẫn được làm mới', async () => {
    const [a, b] = await twoDuplicates();
    const { result, client } = setup(b, a);
    await waitFor(() => expect(result.current.preview.isSuccess).toBe(true));
    client.setQueryData(['pets', 'mine'], []);

    await act(async () => {
      await expect(result.current.merge.mutateAsync({ sourceCustomerId: a, targetCustomerId: b, confirm: false })).rejects.toMatchObject({ response: { status: 400 } });
    });

    expect(client.getQueryState(['pets', 'mine'])?.isInvalidated).toBe(true);
    expect(result.current.preview.data?.blockers).toEqual([]); // chưa gộp
  });
});
