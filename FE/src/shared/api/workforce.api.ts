// Nhân sự (UC34–38) theo docs/api/workforce-v1.md #1–14. BE tầng 3 chưa cài (T58–T59): chạy trên mock workforce.mock.ts.
// Ca, phân ca, duyệt yêu cầu, vắng đột xuất: chỉ BRANCH_MANAGER của chi nhánh mình (chi nhánh lấy từ người gọi, không có `branchId`).
// `/me/…`: chỉ A06–A08 (lễ tân, bác sĩ, chăm sóc), chỉ dữ liệu của mình; phân ca người khác trả 404.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  CreateShiftAssignmentRequest,
  CreateShiftDefinitionRequest,
  CreateShiftRequestRequest,
  MyShiftAssignmentQuery,
  ReasonRequest,
  RejectShiftRequestRequest,
  ShiftAssignment,
  ShiftAssignmentQuery,
  ShiftDefinition,
  ShiftDefinitionQuery,
  ShiftRequest,
  ShiftRequestQuery,
  UpdateShiftDefinitionRequest,
} from '../types/workforce';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const workforceApi = {
  // ─── Ca làm việc (UC34) ───
  /** #1: không phân trang, sắp theo giờ bắt đầu; `activeOnly` vắng = cả ca ngừng dùng. */
  listShiftDefinitions: (query: ShiftDefinitionQuery = {}) => get<ShiftDefinition[]>('/api/shift-definitions', query),
  /** #2: 400 BR-NS-01 tên trùng trong chi nhánh hoặc giờ bắt đầu không nhỏ hơn giờ kết thúc. */
  createShiftDefinition: (body: CreateShiftDefinitionRequest) => post<ShiftDefinition>('/api/shift-definitions', body),
  /** #3: 400 BR-NS-01 tên trùng, sửa giờ khi ca đã có phân ca, ngừng dùng khi ca còn phân ca SCHEDULED từ hôm nay. */
  updateShiftDefinition: (shiftDefinitionId: number, body: UpdateShiftDefinitionRequest) =>
    patch<ShiftDefinition>(`/api/shift-definitions/${shiftDefinitionId}`, body),

  // ─── Phân ca (UC34, UC38) ───
  /** #4: `from`, `to` bắt buộc, tối đa 62 ngày (400 BR-NS-03); sắp theo ngày, giờ bắt đầu. */
  listShiftAssignments: (query: ShiftAssignmentQuery) => get<PageResponse<ShiftAssignment>>('/api/shift-assignments', query),
  /** #5: 400 BR-NS-02 ngày quá khứ, ca ngừng dùng, nhân viên không phải A06–A08 ACTIVE của chi nhánh, trùng hoặc chồng giờ; 404 không thấy nhân viên / ca. */
  createShiftAssignment: (body: CreateShiftAssignmentRequest) => post<ShiftAssignment>('/api/shift-assignments', body),
  /** #6: SCHEDULED → CANCELLED; 400 BR-NS-02 khi ca đã bắt đầu (dùng ghi vắng); 409 khi không còn SCHEDULED. Yêu cầu PENDING của phân ca tự bị từ chối. */
  cancelShiftAssignment: (shiftAssignmentId: number, body: ReasonRequest) =>
    post<ShiftAssignment>(`/api/shift-assignments/${shiftAssignmentId}/cancel`, body),
  /** #7: SCHEDULED → ABSENT; 400 BR-NS-07 ca của ngày tương lai hoặc quá 7 ngày; 409 khi không còn SCHEDULED. */
  recordShiftAbsence: (shiftAssignmentId: number, body: ReasonRequest) =>
    post<ShiftAssignment>(`/api/shift-assignments/${shiftAssignmentId}/absent`, body),
  /** #8: không phân trang, tối đa 62 ngày (400 BR-NS-03); gồm cả phân ca đã nghỉ / vắng / hủy. */
  listMyShiftAssignments: (query: MyShiftAssignmentQuery) => get<ShiftAssignment[]>('/api/me/shift-assignments', query),

  // ─── Yêu cầu nghỉ / đổi ca (UC36, UC37) ───
  /** #9: 400 BR-NS-04 phân ca không SCHEDULED, còn dưới 24 giờ, đã có yêu cầu chờ, SWAP thiếu / thừa / chồng giờ ca đích; 404 phân ca không phải của mình. */
  createShiftRequest: (body: CreateShiftRequestRequest) => post<ShiftRequest>('/api/me/shift-requests', body),
  /** #10: mới nhất trước. */
  listMyShiftRequests: (query: ShiftRequestQuery = {}) => get<PageResponse<ShiftRequest>>('/api/me/shift-requests', query),
  /** #11: PENDING → WITHDRAWN; 404 khi không phải của mình; 409 khi không còn PENDING. */
  withdrawShiftRequest: (shiftRequestId: number) => post<ShiftRequest>(`/api/me/shift-requests/${shiftRequestId}/withdraw`),
  /** #12: `status` vắng = PENDING; cũ nhất trước. */
  listShiftRequests: (query: ShiftRequestQuery = {}) => get<PageResponse<ShiftRequest>>('/api/shift-requests', query),
  /** #13: PENDING → APPROVED (LEAVE: phân ca → ON_LEAVE; SWAP: đổi sang ca đích); 400 BR-NS-06 ca đã bắt đầu hoặc ca đích ngừng dùng / chồng giờ; 409 khi không còn PENDING. */
  approveShiftRequest: (shiftRequestId: number) => post<ShiftRequest>(`/api/shift-requests/${shiftRequestId}/approve`),
  /** #14: PENDING → REJECTED; thiếu lý do là 400 VALIDATION_FAILED; 409 khi không còn PENDING. */
  rejectShiftRequest: (shiftRequestId: number, body: RejectShiftRequestRequest) =>
    post<ShiftRequest>(`/api/shift-requests/${shiftRequestId}/reject`, body),
};
