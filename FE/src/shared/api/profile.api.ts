// Hồ sơ cá nhân (UC06) và liên kết hồ sơ (UC07). identity-v1 #8, 10–14: GET /me, hồ sơ nhân viên, liên kết;
// customer-v1 #1–7: hồ sơ khách và sổ địa chỉ (chỉ CUSTOMER).
import { apiClient } from './axios';
import type { OtpSentResponse } from '../types/auth';
import type {
  Address,
  AddressRequest,
  CustomerProfile,
  LinkCandidate,
  LinkConfirmRequest,
  LinkOtpRequest,
  LinkResult,
  MeResponse,
  StaffProfile,
  UpdateAddressRequest,
  UpdateMyCustomerProfileRequest,
  UpdateStaffProfileRequest,
} from '../types/profile';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const profileApi = {
  me: () => get<MeResponse>('/api/me'),
  /** Chỉ nhân viên; `specialty`, `bio` chỉ VET. */
  updateStaffProfile: (body: UpdateStaffProfileRequest) => patch<StaffProfile>('/api/me/staff-profile', body),

  customerProfile: () => get<CustomerProfile>('/api/me/customer-profile'),
  updateCustomerProfile: (body: UpdateMyCustomerProfileRequest) => patch<CustomerProfile>('/api/me/customer-profile', body),

  /** Mặc định trước, rồi theo thứ tự thêm. */
  addresses: () => get<Address[]>('/api/me/addresses'),
  addAddress: (body: AddressRequest) => post<Address>('/api/me/addresses', body),
  updateAddress: (addressId: number, body: UpdateAddressRequest) => patch<Address>(`/api/me/addresses/${addressId}`, body),
  deleteAddress: async (addressId: number): Promise<void> => {
    await apiClient.delete(`/api/me/addresses/${addressId}`);
  },
  setDefaultAddress: (addressId: number) => post<Address>(`/api/me/addresses/${addressId}/set-default`),

  /** Không gửi `phone`: theo SĐT của hồ sơ online (khách đang có cờ chờ liên kết). */
  linkCandidates: (phone?: string) => get<LinkCandidate[]>('/api/me/link-candidates', phone ? { phone } : undefined),
  sendLinkOtp: (body: LinkOtpRequest) => post<OtpSentResponse>('/api/me/link/otp', body),
  confirmLink: (body: LinkConfirmRequest) => post<LinkResult>('/api/me/link/confirm', body),
  declineLink: async (): Promise<void> => {
    await apiClient.post('/api/me/link/decline');
  },
};
