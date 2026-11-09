import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { feesApi } from '../api/fees.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useFeesMutation, useMaterialNorms, useShippingFeeConfig } from './useFees';
import { installMockApi } from '../api/mock/mock-api';

// Dữ liệu mẫu (fees.mock.ts): phí vận chuyển 30.000, dịch vụ 1 có 2 dòng định mức. Điểm cần giữ: lệnh ghi làm mới ['fees'],
// kể cả khi BE từ chối (400 BR-SP-10), vì form đang mở có thể đang giữ số cũ.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(
    () => ({
      shipping: useShippingFeeConfig(),
      norms: useMaterialNorms(1),
      none: useMaterialNorms(null),
      saveShipping: useFeesMutation(feesApi.saveShippingFeeConfig),
      saveNorms: useFeesMutation((items: { productId: number; quantity: number }[]) => feesApi.saveMaterialNorms(1, { items })),
    }),
    { wrapper },
  );
}

describe('useFees', () => {
  it('đọc: phí vận chuyển, định mức; id null thì không gọi', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.shipping.data).toMatchObject({ baseFee: 30_000, freeThreshold: 500_000 }));
    await waitFor(() => expect(result.current.norms.data).toHaveLength(2));
    expect(result.current.none.fetchStatus).toBe('idle');
  });

  it('ghi thành công làm mới cấu hình đang hiện', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.shipping.data?.baseFee).toBe(30_000));

    await act(() => result.current.saveShipping.mutateAsync({ baseFee: 20_000, freeThreshold: 0 }));

    await waitFor(() => expect(result.current.shipping.data).toMatchObject({ baseFee: 20_000, freeThreshold: 0 }));
  });

  it('BE từ chối BR-SP-10 (vaccine) vẫn làm mới định mức đang hiện, giữ nguyên giá trị đã lưu', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.norms.data).toHaveLength(2));
    await feesApi.saveMaterialNorms(1, { items: [{ productId: 3, quantity: 7 }] }); // người khác sửa trước, ngoài hook nên cache còn 2 dòng

    await act(async () => {
      await expect(result.current.saveNorms.mutateAsync([{ productId: 5, quantity: 1 }])).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(result.current.norms.data?.map((n) => [n.productId, n.quantity])).toEqual([[3, 7]]));
  });
});
