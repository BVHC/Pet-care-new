// Branch lifecycle API (UC13) — docs/api/branchlife-v1.md #1–4. Tầng 3, BE chưa cài (T75). Chỉ SUPER_MANAGER; DRAFT → ACTIVE vẫn ở branch.api.ts (activate).
import { apiClient } from './axios';
import type { BranchLifecycle, ClosureImpact, CloseBranchRequest, SuspendBranchRequest } from '../types/branchlife';

const get = async <T>(url: string): Promise<T> => (await apiClient.get(url)).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const branchlifeApi = {
  /** #1: không ghi gì; đọc cả chi nhánh DRAFT / SUSPENDED / CLOSED. */
  closureImpact: (branchId: number) => get<ClosureImpact>(`/api/branches/${branchId}/closure-impact`),
  /** #2: ACTIVE → SUSPENDED. 400 BR-CN-08 còn điều kiện chặn · BR-CN-09 còn lịch BOOKED mà `cancelBookings` = false; 409 trạng thái không cho phép. */
  suspend: (branchId: number, body: SuspendBranchRequest) => post<BranchLifecycle>(`/api/branches/${branchId}/suspend`, body),
  /** #3: SUSPENDED → ACTIVE. 400 BR-CN-01 chưa có giờ mở cửa · BR-QT-04 chưa có BRANCH_MANAGER ACTIVE; 409 trạng thái không cho phép. */
  resume: (branchId: number) => post<BranchLifecycle>(`/api/branches/${branchId}/resume`),
  /** #4: ACTIVE / SUSPENDED → CLOSED, không mở lại được. 400 như #2, thêm tồn kho > 0 và nhân viên ACTIVE còn gắn chi nhánh. */
  close: (branchId: number, body: CloseBranchRequest) => post<BranchLifecycle>(`/api/branches/${branchId}/close`, body),
};
