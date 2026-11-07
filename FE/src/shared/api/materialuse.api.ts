// Vật tư tiêu hao theo lượt và thiếu hụt (ST07) theo docs/api/materialuse-v1.md #1–3. ST07 là tác vụ hệ thống (trong cùng transaction hoàn tất lượt),
// không có endpoint kích hoạt. Xem vật tư của lượt: BRANCH_MANAGER của chi nhánh, VET phụ trách lượt; thiếu hụt: BRANCH_MANAGER.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { MaterialShortageQuery, MaterialUsage, ResolveShortageRequest } from '../types/materialuse';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const materialUseApi = {
  /** #1: mảng, không phân trang; rỗng khi lượt chưa hoàn tất hoặc dịch vụ không có định mức. */
  listVisitUsages: (visitId: number) => get<MaterialUsage[]>(`/api/visits/${visitId}/material-usages`),
  /** #2: `resolved` vắng = false (chưa xử lý); chỉ dòng `shortageQty > 0`, cũ nhất trước. */
  listShortages: (query: MaterialShortageQuery = {}) => get<PageResponse<MaterialUsage>>('/api/material-shortages', query),
  /** #3: chỉ ghi nhận, không tự điều chỉnh tồn. 400 BR-KB-13 khi dòng không có thiếu hụt; 409 khi đã xử lý. */
  resolveShortage: (usageId: number, body: ResolveShortageRequest) => post<MaterialUsage>(`/api/material-shortages/${usageId}/resolve`, body),
};
