// Module KH (UC22..26): docs/api/customer-v1.md #8..21, hồ sơ sức khỏe docs/api/visit-v1.md #21.
// Cùng quy ước với shared/api/catalog.api.ts: đường dẫn hợp đồng thêm tiền tố /api, trả `data` của envelope,
// lỗi là AxiosError — màn hình đổi bằng toApiError rồi plainMessage / fieldErrorsOf.
// Quyền: tra cứu + xem chi tiết A06–A08; tạo / sửa hồ sơ, chuyển chủ A06; /me/pets A02 (chủ hiện tại).

import { apiClient } from '../../shared/api/axios';
import type { PageResponse } from '../../shared/types';
import type {
  AddWeightRequest,
  CreateCounterCustomerRequest,
  CreatePetRequest,
  CustomerDetail,
  CustomerSearchItem,
  CustomerSearchQuery,
  CustomerWriteResult,
  HealthRecord,
  MarkDeceasedRequest,
  PetResponse,
  PetWriteResult,
  TransferOwnerRequest,
  UpdateCustomerRequest,
  UpdatePetRequest,
  WeightRecord,
} from './types';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const customerPetApi = {
  // Tra cứu (UC23) và hồ sơ khách tại quầy (UC22)
  searchCustomers: (query: CustomerSearchQuery) => get<PageResponse<CustomerSearchItem>>('/api/customers', query),
  getCustomer: (customerId: number) => get<CustomerDetail>(`/api/customers/${customerId}`),
  createCounterCustomer: (body: CreateCounterCustomerRequest) => post<CustomerWriteResult>('/api/customers', body),
  updateCustomer: (customerId: number, body: UpdateCustomerRequest) =>
    patch<CustomerWriteResult>(`/api/customers/${customerId}`, body),

  // Thú cưng (UC24)
  addPetForCustomer: (customerId: number, body: CreatePetRequest) =>
    post<PetWriteResult>(`/api/customers/${customerId}/pets`, body),
  listMyPets: () => get<PetResponse[]>('/api/me/pets'),
  addMyPet: (body: CreatePetRequest) => post<PetWriteResult>('/api/me/pets', body),
  getPet: (petId: number) => get<PetResponse>(`/api/pets/${petId}`),
  updatePet: (petId: number, body: UpdatePetRequest) => patch<PetWriteResult>(`/api/pets/${petId}`, body),
  markDeceased: (petId: number, body: MarkDeceasedRequest) => post<PetResponse>(`/api/pets/${petId}/mark-deceased`, body),
  deletePet: async (petId: number): Promise<void> => {
    await apiClient.delete(`/api/pets/${petId}`);
  },

  // Chuyển chủ (UC26)
  transferOwner: (petId: number, body: TransferOwnerRequest) =>
    post<PetResponse>(`/api/pets/${petId}/transfer-owner`, body),

  // Cân nặng (BR-KH-04): khách chỉ thêm được bản "chủ tự khai"
  listWeights: (petId: number) => get<WeightRecord[]>(`/api/pets/${petId}/weights`),
  addWeight: (petId: number, body: AddWeightRequest) => post<WeightRecord>(`/api/pets/${petId}/weights`, body),

  // Hồ sơ sức khỏe (UC25)
  getHealthRecord: (petId: number) => get<HealthRecord>(`/api/pets/${petId}/health-record`),
};
