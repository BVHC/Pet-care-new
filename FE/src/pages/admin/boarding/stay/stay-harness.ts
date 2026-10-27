// Dùng chung cho test bốn hộp thoại nhận / trả thú (T48 UC59). File .ts không JSX: không vướng quy tắc react-refresh của eslint.
// Máy khách React Query dựng giống App.tsx (staleTime 60 giây, không làm mới khi lấy lại tiêu điểm) + retry:false: nếu để mặc định của thư viện
// thì các ca "mở lại sau khi đã thu tiền" và "lấy lại tiêu điểm" vẫn xanh dù hộp thoại không tự làm mới.
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { apiClient } from '@/shared/api/axios';
import { boardingApi } from '@/shared/api/boarding.api';
import { MOCK_PASSWORD } from '@/shared/api/mock/identity.mock';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { recordDoseForMock } from '@/shared/api/mock/visit-clinical.mock';
import { usePosStore } from '@/shared/stores/pos.store';
import { useSession } from '@/shared/stores/session.store';
import type { Booking } from '@/shared/types/boarding';
import { ACCOUNTS, authAs, makeClient, pinClock } from '@/test/boarding-harness';

export { ACCOUNTS };

const calls: string[] = [];
const failed: { url: string; status: number }[] = [];
let installed = false;

/** Gọi ở `beforeAll`: gắn mock vào `apiClient` và ghi lại mọi lệnh gọi / lỗi (để kiểm "không ghi khi mở", "không 403"). */
export function installStayMock() {
  if (installed) return;
  installed = true;
  installMockApi(apiClient);
  apiClient.interceptors.request.use((config) => {
    calls.push(`${(config.method ?? 'get').toUpperCase()} ${config.url}`);
    return config;
  });
  apiClient.interceptors.response.use(undefined, (error: { config?: { url?: string }; response?: { status?: number } }) => {
    failed.push({ url: error.config?.url ?? '', status: error.response?.status ?? 0 });
    return Promise.reject(error);
  });
}

/** Mỗi ca: đồng hồ ghim thứ Sáu 23/10/2026 09:00 (giờ VN), phiếu thu trống, bỏ nhật ký lệnh gọi. */
export function startDay() {
  pinClock();
  usePosStore.getState().clear();
  calls.length = 0;
  failed.length = 0;
}

export const requests = () => [...calls];
/** Lệnh ghi (POST / PUT / PATCH / DELETE) đã gửi đi từ `apiClient`. */
export const writes = () => calls.filter((c) => !c.startsWith('GET '));
export const forbidden = () => failed.filter((f) => f.status === 403);
export const clearRequests = () => {
  calls.length = 0;
  failed.length = 0;
};

export async function signIn(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

/** Cấp lại phiên khi đồng hồ nhảy sang ngày khác lúc hộp thoại đang mở (không xóa phiên trước: nếu xóa, hộp thoại thấy "mất quyền" một nhịp rồi dựng lại). */
export async function renewSession(email: string) {
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

/** Giống `defaultOptions` của App.tsx. */
export const appQueryClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000, refetchOnWindowFocus: false } } });

function Where() {
  return createElement('p', { 'data-testid': 'where' }, useLocation().pathname);
}

/** Hộp thoại ở `/admin/boarding`; `routes` thêm các trang đích (vd `/staff/reception`). */
export function renderDialog(ui: ReactElement, opts: { client?: QueryClient; routes?: Record<string, ReactElement> } = {}) {
  const client = opts.client ?? appQueryClient();
  const routes = Object.entries(opts.routes ?? {}).map(([path, element]) => createElement(Route, { key: path, path, element }));
  const view = render(
    createElement(
      QueryClientProvider,
      { client },
      createElement(
        MemoryRouter,
        { initialEntries: ['/admin/boarding'] },
        createElement(Where),
        createElement(
          Routes,
          null,
          createElement(Route, { path: '/admin/boarding', element: ui }),
          ...routes,
          createElement(Route, { path: '*', element: null }),
        ),
      ),
    ),
  );
  return { user: userEvent.setup(), client, ...view };
}

/** Đặt chỗ đọc bằng phiên đang đăng nhập (đúng thứ trang lưu trú đưa vào hộp thoại). */
export const bookingOf = (bookingId: number): Promise<Booking> => boardingApi.get(bookingId);

/** Người thao tác thứ hai (máy / tab khác) dùng chung state mock; dùng để gây 409 và các lỗi nghiệp vụ lúc nộp. */
export async function otherStaff(email: string = ACCOUNTS.desk) {
  const client = makeClient();
  const auth = await authAs(client, email);
  return {
    get: async <T,>(url: string, params?: object): Promise<T> => (await client.get(url, { params, ...auth })).data.data,
    post: async <T,>(url: string, body?: object): Promise<T> => (await client.post(url, body, auth)).data.data,
  };
}
export type OtherStaff = Awaited<ReturnType<typeof otherStaff>>;

/** Bim (#2) tiêm đủ 5 bệnh: nhận thú đặt chỗ #4 (BOOKED 26/10→28/10) mới qua BR-LT-05. Gọi SAU khi mock đã nạp dữ liệu mẫu (`bookingOf`). */
export const dosedBim = () => recordDoseForMock(2, 2, '2026-10-10');

/**
 * Rex (chó của khách #1) tiêm đủ, đặt hôm nay → ngày mai và đã nhận vào C-03 (xem `rexInStay` của boarding-stay.mock.test).
 * Khách #1 có thật trong mock khách nên bàn Lễ tân thu được; Cốm / Mực (khách 9002 / 9003) thì không.
 */
export async function rexInStay(): Promise<Booking> {
  const desk = await otherStaff(ACCOUNTS.desk);
  const created = await desk.post<{ pet: { petId: number } }>('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' });
  const petId = created.pet.petId;
  recordDoseForMock(petId, 1, '2026-10-10');
  recordDoseForMock(petId, 2, '2026-10-10');
  const booked = await desk.post<{ booking: Booking }>('/api/boarding-bookings', {
    petId,
    branchId: 1,
    kennelTypeId: 5,
    checkInDate: '2026-10-23',
    checkOutDate: '2026-10-24',
  });
  return desk.post<Booking>(`/api/boarding-bookings/${booked.booking.bookingId}/check-in`, {
    kennelId: 3,
    weightKg: 6.2,
    healthCondition: 'Khỏe, hơi nhút nhát',
    emergencyPhone: '0987000111',
  });
}

export const openShift = (desk: OtherStaff) => desk.post('/api/cashier-shifts', { afterHours: false });
export const payOrder = (desk: OtherStaff, orderId: number, amount: number) =>
  desk.post('/api/payments', { orderIds: [orderId], method: 'CASH', amount });
