// Tiện ích chung của ba file test thương mại (shop, payment, returns).
import { AxiosError, type AxiosInstance, type AxiosResponse } from 'axios';
/** 7 vai trò + hai tài khoản phụ: khachmoi@ (khách thứ hai), manager2@ (quản lý chi nhánh 2, khác chi nhánh của đơn mẫu). */
export const ACCOUNTS = {
  admin: 'admin@petcare.vn',
  chain: 'chuoi@petcare.vn',
  manager: 'manager@store1.vn',
  manager2: 'manager2@store2.vn',
  desk: 'reception@store1.vn',
  vet: 'doctor@store1.vn',
  caretaker: 'caretaker@store1.vn',
  owner: 'khach@petcare.vn',
  other: 'khachmoi@petcare.vn',
} as const;
export type Who = keyof typeof ACCOUNTS;
export type Auth = { headers: { Authorization: string } };

export async function authFor(client: AxiosInstance, who: Who): Promise<Auth> {
  const res = await client.post('/api/auth/login', { email: ACCOUNTS[who], password: 'Petcare123' });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}

/** Lỗi HTTP của một request (ném nếu request thành công). */
export async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (e instanceof AxiosError && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}
