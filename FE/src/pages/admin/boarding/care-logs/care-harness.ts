// Dùng chung cho test CareLogDialog (T48 UC57). File .ts không JSX: không vướng quy tắc react-refresh của eslint.
// Máy khách React Query dựng giống App.tsx (staleTime 60 giây, không làm mới khi lấy lại tiêu điểm) + retry:false.
//
// Dữ liệu mẫu ở mốc ghim (thứ Sáu 23/10/2026 09:00 giờ VN), xem boarding.seed.ts:
//   #2 Cốm CHECKED_IN: nhật ký #1 (21/10 17:30, Thu Hà = CARETAKER id 6, 2 ảnh), #2 (22/10 19:40, BS. Minh Anh = VET id 5, bất thường, 1 bản bổ sung 20:10); chưa có nhật ký hôm nay
//   #3 Mực CHECKED_IN: nhật ký #3 (22/10 17:00) và #4 (hôm nay 08:30, Thu Hà, còn sửa tới 09:30)
//   #5 Bim CHECKED_OUT: nhật ký #5–7 · #4 Bim BOOKED · #6 Bim CANCELLED · #9 Khoai NO_SHOW
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactElement } from 'react';
import { apiClient } from '@/shared/api/axios';
import { boardingApi } from '@/shared/api/boarding.api';
import { MOCK_PASSWORD } from '@/shared/api/mock/identity.mock';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { recordDoseForMock } from '@/shared/api/mock/visit-clinical.mock';
import { useSession } from '@/shared/stores/session.store';
import type { Booking } from '@/shared/types/boarding';
import { ACCOUNTS, authAs, makeClient, pinClock } from '@/test/boarding-harness';

export { ACCOUNTS };

const calls: string[] = [];
const failed: { url: string; status: number }[] = [];
let installed = false;

/** Gọi ở `beforeAll`: gắn mock vào `apiClient` và ghi lại mọi lệnh gọi / lỗi (để kiểm "không gọi khi không cần", "không 403"). */
export function installCareMock() {
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

/** Mỗi ca: đồng hồ ghim thứ Sáu 23/10/2026 09:00 (giờ VN), bỏ nhật ký lệnh gọi. */
export function startDay() {
  pinClock();
  calls.length = 0;
  failed.length = 0;
}

export const requests = () => [...calls];
export const writes = () => calls.filter((c) => !c.startsWith('GET ') && !c.includes('/auth/'));
export const forbidden = () => failed.filter((f) => f.status === 403);
export const clearRequests = () => {
  calls.length = 0;
  failed.length = 0;
};

export async function signIn(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

/** Cấp lại phiên khi đồng hồ nhảy xa lúc hộp thoại đang mở (không xóa phiên trước: nếu xóa, hộp thoại thấy "mất quyền" một nhịp). */
export async function renewSession(email: string) {
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

/** Giống `defaultOptions` của App.tsx. */
export const appQueryClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000, refetchOnWindowFocus: false } } });

export function renderDialog(ui: ReactElement, client: QueryClient = appQueryClient()) {
  const view = render(createElement(QueryClientProvider, { client }, ui));
  return { user: userEvent.setup(), client, ...view };
}

/** Đặt chỗ đọc bằng phiên đang đăng nhập (đúng thứ trang lưu trú đưa vào hộp thoại). */
export const bookingOf = (bookingId: number): Promise<Booking> => boardingApi.get(bookingId);

/** Người thao tác thứ hai (máy / tab khác) dùng chung state mock: gây 409, đọc thông báo của khách… */
export async function otherUser(email: string) {
  const client = makeClient();
  const auth = await authAs(client, email);
  return {
    get: async <T,>(url: string, params?: object): Promise<T> => (await client.get(url, { params, ...auth })).data.data,
    post: async <T,>(url: string, body?: object): Promise<T> => (await client.post(url, body, auth)).data.data,
  };
}

/** Bim (#2) tiêm đủ 5 bệnh rồi lễ tân nhận đặt chỗ #4 vào C-03: thú có tài khoản khách, đang lưu trú, CHƯA có nhật ký nào. Trả về đặt chỗ #4. */
export async function bimInStay(): Promise<Booking> {
  const desk = await otherUser(ACCOUNTS.desk);
  await desk.get('/api/boarding-bookings/4'); // nạp dữ liệu mẫu trước khi ghi liều
  recordDoseForMock(2, 2, '2026-10-10');
  return desk.post<Booking>('/api/boarding-bookings/4/check-in', { kennelId: 3, weightKg: 6.2, healthCondition: 'Khỏe', emergencyPhone: '0987000111' });
}

export interface Notice {
  type: string;
  title: string;
  body: string;
  linkUrl: string | null;
}
export const notificationsOf = async (email: string): Promise<Notice[]> =>
  (await (await otherUser(email)).get<{ content: Notice[] }>('/api/me/notifications', { size: 100 })).content;
