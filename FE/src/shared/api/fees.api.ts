// Phí vận chuyển, định mức vật tư (UC32) theo docs/api/fees-v1.md #1–4. Ghi và đọc: chỉ SUPER_MANAGER.
import { apiClient } from './axios';
import type {
  MaterialNorm,
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
  /** #3: mảng, không phân trang. */
  listMaterialNorms: (serviceId: number) => get<MaterialNorm[]>(`/api/services/${serviceId}/material-norms`),
  /** #4: ghi đè toàn bộ. 400 BR-SP-10 khi sản phẩm là vaccine / không tồn tại, số lượng < 1 hoặc trùng sản phẩm. */
  saveMaterialNorms: (serviceId: number, body: SaveMaterialNormsRequest) => put<MaterialNorm[]>(`/api/services/${serviceId}/material-norms`, body),
};
