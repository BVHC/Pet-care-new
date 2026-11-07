import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../api/axios';
import { homevisitApi } from '../api/homevisit.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { useSession } from '../stores/session.store';
import { addDays } from '../utils/branch-schedule';
import { vnDate } from '../utils/vn-time';
import { useCreateHomeVisit, useHomeVisit, useHomeVisits, useMarkHomeVisitUnreachable, useMyHomeVisits, useStartHomeVisit } from './useHomeVisit';
import { installMockApi } from '../api/mock/mock-api';

// Mock khám tại nhà (appointment.mock.ts nạp lười): khach@petcare.vn có 4 lịch (13 BOOKED sau 2 ngày, 12 BOOKED hôm nay 16:30, 15 CANCELLED, 14 COMPLETED). Điểm cần giữ: lệnh ghi làm mới ['homevisit']
// cả khi BE từ chối, vì 400 / 409 nghĩa là khung giờ hay lịch đã đổi ở nơi khác sau lúc mở màn.

beforeAll(() => installMockApi(apiClient));

const loginAs = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};
beforeEach(() => loginAs('khach@petcare.vn'));

function setup(appointmentId: number | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
  return renderHook(
    () => ({
      mine: useMyHomeVisits(),
      today: useHomeVisits(),
      detail: useHomeVisit(appointmentId),
      none: useHomeVisit(null),
      create: useCreateHomeVisit(),
      start: useStartHomeVisit(),
      unreachable: useMarkHomeVisitUnreachable(),
    }),
    { wrapper },
  );
}

/** Khung AVAILABLE đầu tiên của chi nhánh 1 từ 5 ngày tới, qua appointment-v1 #1 (mock GĐ1 có sẵn trong adapter gốc). */
async function freeSlot() {
  for (let d = 5; d < 19; d += 1) {
    const date = addDays(vnDate(), d);
    const slots = (await apiClient.get('/api/branches/1/available-slots', { params: { serviceId: 1, date } })).data.data as { slotStart: string; status: string }[];
    const free = slots.find((s) => s.status === 'AVAILABLE');
    if (free) return { slotDate: date, slotStart: free.slotStart };
  }
  throw new Error('không có khung trống');
}

describe('đọc', () => {
  it('lịch của tôi (mới nhất trước); chưa chọn lịch (null) thì không bắn request', async () => {
    const { result } = setup(13);
    await waitFor(() => expect(result.current.mine.data?.content.map((a) => a.appointmentId)).toEqual([13, 12, 15, 14]));
    await waitFor(() => expect(result.current.detail.data).toMatchObject({ appointmentId: 13, status: 'BOOKED', petName: 'Bim' }));
    expect(result.current.none.fetchStatus).toBe('idle');
  });

  it('nhân viên: danh sách chi nhánh theo ngày (vắng = hôm nay)', async () => {
    await loginAs('reception@store1.vn');
    const { result } = setup();
    await waitFor(() => expect(result.current.today.data?.content.map((a) => a.appointmentId)).toEqual([12]));
  });
});

describe('lệnh ghi làm mới ["homevisit"]', () => {
  it('đặt lịch thành công: danh sách của tôi thấy lịch mới ngay', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.mine.data?.totalElements).toBe(4));

    const created = await act(async () => result.current.create.mutateAsync({ petId: 3, serviceId: 1, branchId: 1, ...(await freeSlot()), addressId: 1, contactPhone: '0912345678' }));

    await waitFor(() => expect(result.current.mine.data?.totalElements).toBe(5));
    expect(result.current.mine.data?.content.some((a) => a.appointmentId === created.appointmentId)).toBe(true);
  });

  it('BE từ chối (400 BR-LH-13) vì lịch đã thêm ở nơi khác: danh sách nạp lại, không giữ số cũ', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.mine.data?.totalElements).toBe(4));
    const other = await homevisitApi.create({ petId: 3, serviceId: 1, branchId: 1, ...(await freeSlot()), addressId: 1, contactPhone: '0912345678' }); // ngoài hook nên cache vẫn 4

    await act(async () => {
      await expect(result.current.create.mutateAsync({ petId: 3, serviceId: 2, branchId: 1, slotDate: other.slotDate, slotStart: other.slotStart, addressId: 1, contactPhone: '0912345678' })).rejects.toMatchObject({
        response: { status: 400 },
      });
    });

    await waitFor(() => expect(result.current.mine.data?.totalElements).toBe(5));
  });

  it('nhân viên ghi "không gặp được": chi tiết và danh sách theo trạng thái nạp lại; ghi lần hai (409) vẫn làm mới', async () => {
    await loginAs('doctor@store1.vn');
    const { result } = setup(12);
    await waitFor(() => expect(result.current.detail.data?.status).toBe('BOOKED'));

    await act(() => result.current.unreachable.mutateAsync({ appointmentId: 12, note: 'Không ai ra mở cửa' }));
    await waitFor(() => expect(result.current.detail.data?.status).toBe('NO_SHOW'));

    await act(async () => {
      await expect(result.current.unreachable.mutateAsync({ appointmentId: 12, note: 'Lần hai' })).rejects.toMatchObject({ response: { status: 409 } });
    });
    await waitFor(() => expect(result.current.today.data?.content.find((a) => a.appointmentId === 12)?.status).toBe('NO_SHOW'));
  });

  it('bắt đầu khám khi thú còn lượt mở (400 BR-TN-01): lỗi trả về, lịch vẫn BOOKED', async () => {
    await loginAs('doctor@store1.vn');
    const { result } = setup(12);
    await waitFor(() => expect(result.current.detail.data?.status).toBe('BOOKED'));
    await act(async () => {
      await expect(result.current.start.mutateAsync(12)).rejects.toMatchObject({ response: { status: 400 } });
    });
    await waitFor(() => expect(result.current.detail.isFetching).toBe(false));
    expect(result.current.detail.data?.status).toBe('BOOKED');
  });
});
