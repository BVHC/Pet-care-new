// Customer merge API (UC27) — docs/api/custmerge-v1.md #1–2. Tầng 3, BE chưa cài (T75). Chỉ BRANCH_MANAGER, gộp được mọi hồ sơ khách dùng chung toàn chuỗi.
// Gộp không hoàn tác (BR-KH-13): luôn gọi #1 trước, chỉ gửi #2 với `confirm: true` khi `blockers` rỗng (BR-KH-14).
import { apiClient } from './axios';
import type { CustomerMergeRequest, CustomerMergeResult, MergePreview } from '../types/custmerge';

export const custmergeApi = {
  /** #1: hồ sơ `targetCustomerId` (đích) sẽ nhận dữ liệu của `sourceCustomerId` (nguồn). 400 nguồn trùng đích hoặc thiếu; 404 thiếu một trong hai hồ sơ. Không ghi gì. */
  preview: async (targetCustomerId: number, sourceCustomerId: number): Promise<MergePreview> =>
    (await apiClient.get(`/api/customers/${targetCustomerId}/merge-preview`, { params: { sourceCustomerId } })).data.data,
  /** #2: 201. 400 BR-KH-11 (hai hồ sơ cùng có tài khoản, đích không phải bên có tài khoản, nguồn còn Visit mở / Order OPEN / PENDING, đã MERGED) · BR-KH-14 chưa `confirm`; 404; 409 CONCURRENCY_CONFLICT. */
  merge: async (body: CustomerMergeRequest): Promise<CustomerMergeResult> => (await apiClient.post('/api/customer-merges', body)).data.data,
};
