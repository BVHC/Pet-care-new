// Phí vận chuyển, phí khám tại nhà, định mức vật tư (UC32) theo docs/api/fees-v1.md #1–6. Ghi và đọc phí vận chuyển / định mức: chỉ SUPER_MANAGER;
// đọc phí khám tại nhà thêm CUSTOMER và RECEPTIONIST, VET của chính chi nhánh (BRANCH_MANAGER, ADMIN, CARETAKER bị 403 theo yaml).
import { apiClient } from './axios';
import type {
  HomeVisitFee,
  MaterialNorm,
  SaveHomeVisitFeeRequest,
  SaveMaterialNormsRequest,
  SaveShippingFeeConfigRequest,
  ShippingFeeConfig,
} from '../types/fees';

const get = async <T>(url: string): Promise<T> => (await apiClient.get(url)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const feesApi = {
  /** #1 */
  getShippingFeeConfig: () => get<ShippingFeeConfig>('/api/shipping-fee-config'),
  /** #2: 400 BR-SP-08 khi số âm hoặc không phải số nguyên. */
  saveShippingFeeConfig: (body: SaveShippingFeeConfigRequest) => put<ShippingFeeConfig>('/api/shipping-fee-config', body),
  /** #3: chi nhánh chưa cấu hình trả `enabled = false`, `fee = 0`. */
  getHomeVisitFee: (branchId: number) => get<HomeVisitFee>(`/api/branches/${branchId}/home-visit-fee`),
  /** #4: 400 BR-SP-09 khi phí âm; 404 chi nhánh không có. */
  saveHomeVisitFee: (branchId: number, body: SaveHomeVisitFeeRequest) => put<HomeVisitFee>(`/api/branches/${branchId}/home-visit-fee`, body),
  /** #5: mảng, không phân trang. */
  listMaterialNorms: (serviceId: number) => get<MaterialNorm[]>(`/api/services/${serviceId}/material-norms`),
  /** #6: ghi đè toàn bộ. 400 BR-SP-10 khi sản phẩm là vaccine / không tồn tại, số lượng < 1 hoặc trùng sản phẩm. */
  saveMaterialNorms: (serviceId: number, body: SaveMaterialNormsRequest) => put<MaterialNorm[]>(`/api/services/${serviceId}/material-norms`, body),
};
