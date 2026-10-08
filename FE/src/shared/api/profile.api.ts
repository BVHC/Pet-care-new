// ===========================================
// Profile & Link API - T13
// Gọi T8 (BE-1) endpoints:
//   GET /me                    - thông tin người đang đăng nhập
//   PATCH /me/staff-profile    - sửa hồ sơ nhân viên
//   GET /me/addresses          - sổ địa chỉ (module customer)
//   POST/PUT/DELETE /me/addresses/:id
//   GET /me/link-candidates    - danh sách hồ sơ có thể liên kết
//   POST /me/link/otp          - gửi OTP liên kết
//   POST /me/link/confirm      - xác nhận liên kết
//   POST /me/link/decline      - từ chối liên kết
// ===========================================
import { apiClient } from './axios';
import type {
  MeResponse,
  UpdateStaffProfileRequest,
  StaffProfile,
  Address,
  CreateAddressRequest,
  UpdateAddressRequest,
  LinkCandidate,
  LinkOtpRequest,
  LinkConfirmRequest,
  LinkResult,
  OtpSentResponse,
} from '../models/profile.model';

// === Me ===

/** GET /me - thông tin người đang đăng nhập */
export async function getMe(): Promise<MeResponse> {
  const res = await apiClient.get<{ data: MeResponse }>('/api/me');
  return res.data.data;
}

/** PATCH /me/staff-profile - sửa hồ sơ nhân viên */
export async function updateStaffProfile(
  data: UpdateStaffProfileRequest
): Promise<StaffProfile> {
  const res = await apiClient.patch<{ data: StaffProfile }>('/api/me/staff-profile', data);
  return res.data.data;
}

// === Address (Sổ địa chỉ) ===

/** GET /me/addresses - danh sách địa chỉ */
export async function getAddresses(): Promise<Address[]> {
  const res = await apiClient.get<{ data: Address[] }>('/api/me/addresses');
  return res.data.data;
}

/** POST /me/addresses - thêm địa chỉ mới */
export async function createAddress(
  data: CreateAddressRequest
): Promise<Address> {
  const res = await apiClient.post<{ data: Address }>('/api/me/addresses', data);
  return res.data.data;
}

/** PUT /me/addresses/:id - sửa địa chỉ */
export async function updateAddress(
  id: number,
  data: UpdateAddressRequest
): Promise<Address> {
  const res = await apiClient.put<{ data: Address }>(`/api/me/addresses/${id}`, data);
  return res.data.data;
}

/** DELETE /me/addresses/:id - xóa địa chỉ */
export async function deleteAddress(id: number): Promise<void> {
  await apiClient.delete(`/api/me/addresses/${id}`);
}

/** PUT /me/addresses/:id/default - đặt mặc định */
export async function setDefaultAddress(id: number): Promise<Address> {
  const res = await apiClient.put<{ data: Address }>(`/api/me/addresses/${id}/default`);
  return res.data.data;
}

// === Profile Linking (Liên kết hồ sơ) - BR-TK-19 ===

/**
 * GET /me/link-candidates - danh sách hồ sơ tại quầy có thể liên kết
 * @param phone - filter theo SĐT (tùy chọn)
 */
export async function getLinkCandidates(
  phone?: string
): Promise<LinkCandidate[]> {
  const params = phone ? { phone } : undefined;
  const res = await apiClient.get<{ data: LinkCandidate[] }>('/api/me/link-candidates', {
    params,
  });
  return res.data.data;
}

/** POST /me/link/otp - gửi OTP tới email hồ sơ tại quầy */
export async function sendLinkOtp(data: LinkOtpRequest): Promise<OtpSentResponse> {
  const res = await apiClient.post<{ data: OtpSentResponse }>('/api/me/link/otp', data);
  return res.data.data;
}

/** POST /me/link/confirm - xác nhận liên kết */
export async function confirmLink(data: LinkConfirmRequest): Promise<LinkResult> {
  const res = await apiClient.post<{ data: LinkResult }>('/api/me/link/confirm', data);
  return res.data.data;
}

/** POST /me/link/decline - chọn "Không phải tôi" */
export async function declineLink(): Promise<void> {
  await apiClient.post('/api/me/link/decline');
}
