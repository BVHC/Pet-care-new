import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { complaintApi } from '../api/complaint.api';
import { apiClient } from '../api/axios';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { useComplaint, useComplaintMutation, useComplaints, useMyComplaint, useMyComplaints } from './useComplaints';
import { installMockApi } from '../api/mock/mock-api';

// Dữ liệu mẫu (complaint.mock.ts): khách khach@petcare.vn có 10 khiếu nại, #1 OPEN chưa ai nhận, #3 RESOLVED; quản lý manager@store1.vn thấy 5 khiếu nại đang xử lý của chi nhánh 1.
// Điểm cần giữ: lệnh ghi làm mới ['complaint'] cả khi BE từ chối (409 nghĩa là bên kia đã đổi trạng thái), vì danh sách và chi tiết đang mở đã cũ.

beforeAll(() => installMockApi(apiClient));

async function loginAs(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
}

describe('khách: useMyComplaints, useMyComplaint', () => {
  it('đọc danh sách của mình (mới nhất trước) và chi tiết kèm tin nhắn; id null thì không gọi', async () => {
    await loginAs('khach@petcare.vn');
    const list = renderHook(() => useMyComplaints({ size: 100 }), { wrapper: wrapper() });
    await waitFor(() => expect(list.result.current.isSuccess).toBe(true));
    expect(list.result.current.data!.totalElements).toBe(10);
    expect(list.result.current.data!.content[0].complaintId).toBe(1);

    const detail = renderHook(() => useMyComplaint(2), { wrapper: wrapper() });
    await waitFor(() => expect(detail.result.current.isSuccess).toBe(true));
    expect(detail.result.current.data!.messages).toHaveLength(4);

    const idle = renderHook(() => useMyComplaint(null), { wrapper: wrapper() });
    expect(idle.result.current.fetchStatus).toBe('idle');
  });

  it('khiếu nại của người khác → 404, hiện lỗi và không thử lại', async () => {
    await loginAs('khach@petcare.vn');
    const { result } = renderHook(() => useMyComplaint(10), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.failureCount).toBe(1);
    expect(result.current.error).toMatchObject({ response: { status: 404 } });
  });

  function setup() {
    return renderHook(() => ({ detail: useMyComplaint(3), close: useComplaintMutation(complaintApi.closeMyComplaint) }), { wrapper: wrapper() });
  }

  it('thành công: chi tiết thấy trạng thái mới ngay sau lệnh đóng', async () => {
    await loginAs('khach@petcare.vn');
    const { result } = setup();
    await waitFor(() => expect(result.current.detail.data?.status).toBe('RESOLVED'));

    await act(() => result.current.close.mutateAsync(3));

    await waitFor(() => expect(result.current.detail.data).toMatchObject({ status: 'CLOSED', closedReason: 'CUSTOMER' }));
  });

  it('lỗi 409 vì đã đóng ở nơi khác: chi tiết nạp lại, không giữ trạng thái cũ', async () => {
    await loginAs('khach@petcare.vn');
    const { result } = setup();
    await waitFor(() => expect(result.current.detail.data?.status).toBe('RESOLVED'));
    await complaintApi.closeMyComplaint(3); // đóng ở tab khác, ngoài hook nên cache vẫn là RESOLVED

    await act(async () => {
      await expect(result.current.close.mutateAsync(3)).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(result.current.detail.data?.status).toBe('CLOSED'));
  });
});

describe('quản lý: useComplaints, useComplaint', () => {
  function setup() {
    return renderHook(
      () => ({
        open: useComplaints({ status: 'OPEN' }),
        detail: useComplaint(1),
        take: useComplaintMutation(complaintApi.takeComplaint),
      }),
      { wrapper: wrapper() },
    );
  }
  const openIds = (r: ReturnType<typeof setup>['result']) => r.current.open.data?.content.map((c) => c.complaintId);

  it('đọc danh sách theo phạm vi chi nhánh; lệnh nhận làm danh sách OPEN và chi tiết đổi theo', async () => {
    await loginAs('manager@store1.vn');
    const { result } = setup();
    await waitFor(() => expect(openIds(result)).toEqual([7, 10, 1]));
    await waitFor(() => expect(result.current.detail.data?.status).toBe('OPEN'));

    await act(() => result.current.take.mutateAsync(1));

    await waitFor(() => expect(openIds(result)).toEqual([7, 10]));
    await waitFor(() => expect(result.current.detail.data).toMatchObject({ status: 'IN_PROGRESS', handlerName: 'Hoàng Nam' }));
  });

  it('lỗi 409 vì người khác đã nhận trước: danh sách nạp lại', async () => {
    await loginAs('manager@store1.vn');
    const { result } = setup();
    await waitFor(() => expect(openIds(result)).toEqual([7, 10, 1]));
    await complaintApi.takeComplaint(1);

    await act(async () => {
      await expect(result.current.take.mutateAsync(1)).rejects.toMatchObject({ response: { status: 409 } });
    });

    await waitFor(() => expect(openIds(result)).toEqual([7, 10]));
  });

  it('ngoài phạm vi (khiếu nại chi nhánh khác) → 403, không thử lại', async () => {
    await loginAs('manager@store1.vn');
    const { result } = renderHook(() => useComplaint(9), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.failureCount).toBe(1);
    expect(result.current.error).toMatchObject({ response: { status: 403 } });
  });
});
