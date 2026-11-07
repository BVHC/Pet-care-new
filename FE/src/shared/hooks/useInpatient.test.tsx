import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { inpatientApi } from '../api/inpatient.api';
import { useSession } from '../stores/session.store';
import { useAdmissionOrders, useAdmissions, useAdmission, useAdmit, useCreateAdmissionOrder, useCreateObservation, useDischarge, useObservations } from './useInpatient';
import { installMockApi } from '../api/mock/mock-api';

// Mock nội trú: lịch sử #1 DISCHARGED (lượt 61, 3-4 y lệnh, 3 theo dõi), không có ca ADMITTED ở chi nhánh 1. Điểm cần giữ: lệnh ghi làm mới ['inpatient'] cả khi BE từ chối,
// vì 400 / 409 nghĩa là ca hoặc y lệnh đã đổi ở nơi khác sau lúc mở màn.

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'doctor@store1.vn', password: MOCK_PASSWORD });
});

function setup(admissionId: number | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(
    () => ({
      active: useAdmissions(),
      history: useAdmissions({ status: 'DISCHARGED' }),
      detail: useAdmission(admissionId),
      orders: useAdmissionOrders(admissionId),
      observations: useObservations(admissionId),
      none: useAdmission(null),
      admit: useAdmit(),
      discharge: useDischarge(),
      createOrder: useCreateAdmissionOrder(),
      observe: useCreateObservation(),
    }),
    { wrapper },
  );
}

describe('đọc', () => {
  it('danh sách theo trạng thái, y lệnh và theo dõi của ca mẫu; chưa chọn ca (null) thì không bắn request', async () => {
    const { result } = setup(1);
    await waitFor(() => expect(result.current.history.data?.content.map((a) => a.admissionId)).toEqual([1]));
    await waitFor(() => expect(result.current.orders.data?.length).toBeGreaterThanOrEqual(3));
    await waitFor(() => expect(result.current.observations.data?.totalElements).toBe(3));
    expect(result.current.detail.data).toMatchObject({ admissionId: 1, status: 'DISCHARGED' });
    expect(result.current.active.data?.totalElements).toBe(0);
    expect(result.current.none.fetchStatus).toBe('idle');
  });
});

describe('lệnh ghi làm mới ["inpatient"]', () => {
  it('nhập viện thành công: danh sách ADMITTED thấy ca mới; ra y lệnh và ghi theo dõi: danh sách của ca thấy ngay', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.active.data?.totalElements).toBe(0));

    const admitted = await act(() => result.current.admit.mutateAsync({ visitId: 2002, serviceId: 7101 }));
    await waitFor(() => expect(result.current.active.data?.content.map((a) => a.admissionId)).toEqual([admitted.admissionId]));

    const sub = setup(admitted.admissionId);
    await waitFor(() => expect(sub.result.current.orders.data).toEqual([]));
    await act(() => sub.result.current.createOrder.mutateAsync({ admissionId: admitted.admissionId, orderType: 'FEEDING', content: 'Cho ăn', scheduledAt: new Date(Date.now() + 3_600_000).toISOString() }));
    await act(() => sub.result.current.observe.mutateAsync({ admissionId: admitted.admissionId, abnormal: false, temperatureC: 38.5 }));
    await waitFor(() => expect(sub.result.current.orders.data?.map((o) => o.status)).toEqual(['ORDERED']));
    await waitFor(() => expect(sub.result.current.observations.data?.totalElements).toBe(1));
  });

  it('BE từ chối (400 BR-LT-14 còn y lệnh ORDERED) vì y lệnh đã thêm ở nơi khác: cache không giữ số cũ', async () => {
    const admitted = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    const { result } = setup(admitted.admissionId);
    await waitFor(() => expect(result.current.orders.data).toEqual([]));
    await inpatientApi.createOrder(admitted.admissionId, { orderType: 'FEEDING', content: 'Cho ăn', scheduledAt: new Date(Date.now() + 3_600_000).toISOString() }); // ngoài hook nên cache vẫn rỗng

    await act(async () => {
      await expect(result.current.discharge.mutateAsync({ admissionId: admitted.admissionId, outcome: 'HOME', summary: 'Ổn' })).rejects.toMatchObject({ response: { status: 400 } });
    });

    await waitFor(() => expect(result.current.orders.data?.length).toBe(1));
  });

  it('409 (ca đã xuất viện ở nơi khác): chi tiết nạp lại thành DISCHARGED', async () => {
    const admitted = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    const { result } = setup(admitted.admissionId);
    await waitFor(() => expect(result.current.detail.data?.status).toBe('ADMITTED'));
    await inpatientApi.discharge(admitted.admissionId, { outcome: 'HOME', summary: 'Ổn' }); // ngoài hook

    await act(async () => {
      await expect(result.current.discharge.mutateAsync({ admissionId: admitted.admissionId, outcome: 'HOME', summary: 'Ổn' })).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(result.current.detail.data?.status).toBe('DISCHARGED'));
  });
});
