// Dùng chung cho test mock lưu trú (T48) và test màn hình lưu trú: đồng hồ ghim ở thứ Sáu 23/10/2026 09:00 giờ Việt Nam
// (chi nhánh 1 đang mở cửa), đăng nhập bằng mock. Gọi `pinClock` TRƯỚC khi mock nạp dữ liệu lần đầu (dữ liệu mẫu tính theo ngày nạp).
//
// Dữ liệu mẫu lưu trú ở mốc này (xem boarding.seed.ts): ngày tương đối với hôm nay T = 2026-10-23.
//   #1 Bơ BOOKED 30/10→01/11 (Chủ nhật) · #2 Cốm CHECKED_IN 21/10→31/10 chuồng C-01 · #3 Mực CHECKED_IN 22/10→01/11 chuồng C-02
//   #4 Bim BOOKED 26/10→28/10 · #5 Bim CHECKED_OUT 03/10→06/10 · #6 Bim CANCELLED 01/11→03/11 · #7 Nâu CHECKED_OUT (thú mất)
//   #8 Tép CANCELLED do phòng khám · #9 Khoai NO_SHOW · #10 Đậu CANCELLED hủy muộn · #11 Gạo CHECKED_OUT.
import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { vi } from 'vitest';
import { MOCK_PASSWORD } from '../shared/api/mock/identity.mock';
import { installMockApi } from '../shared/api/mock/mock-api';

export const FRIDAY_9 = '2026-10-23T09:00:00+07:00';

/** Chỉ giả `Date`: timer thật để userEvent và react-query chạy bình thường. */
export function pinClock(iso: string = FRIDAY_9) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

export function makeClient(): AxiosInstance {
  const client = axios.create();
  installMockApi(client);
  return client;
}

export async function authAs(client: AxiosInstance, email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}

export async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

export const ACCOUNTS = {
  customer: 'khach@petcare.vn',
  pendingCustomer: 'khachmoi@petcare.vn',
  desk: 'reception@store1.vn',
  manager: 'manager@store1.vn',
  manager2: 'manager2@store2.vn',
  caretaker: 'caretaker@store1.vn',
  vet: 'doctor@store1.vn',
  chain: 'chuoi@petcare.vn',
  admin: 'admin@petcare.vn',
} as const;
