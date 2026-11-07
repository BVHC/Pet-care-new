// Khiếu nại (UC85, UC86) theo docs/api/complaint-v1.md #1–12. BE tầng 3 chưa cài (T70): chạy trên mock complaint.mock.ts.
// Khách (CUSTOMER) chỉ thấy khiếu nại của mình (của người khác trả 404, ADR-0006). Lễ tân tạo thay khách và chỉ thấy cái mình tạo.
// BRANCH_MANAGER xử lý khiếu nại gắn chi nhánh mình; SUPER_MANAGER xử lý mọi khiếu nại. Mọi lệnh ghi trả lại cả `Complaint` (kèm `messages`).
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  Complaint,
  ComplaintQuery,
  ComplaintSummary,
  CreateComplaintForCustomerRequest,
  CreateComplaintRequest,
  MyComplaintQuery,
  PostMessageRequest,
  ResolveComplaintRequest,
} from '../types/complaint';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const complaintApi = {
  // ─── Khách (A02) ───
  /** #1: 400 BR-DG-05 nội dung ngoài 10–2 000 ký tự, thiếu chi nhánh (trừ OTHER), đối tượng liên quan không thuộc khách, quá 10 khiếu nại đang mở; 404 không thấy chi nhánh / đối tượng. */
  createMyComplaint: (body: CreateComplaintRequest) => post<Complaint>('/api/me/complaints', body),
  /** #2: mới nhất trước; `status` vắng = mọi trạng thái. */
  listMyComplaints: (query: MyComplaintQuery = {}) => get<PageResponse<ComplaintSummary>>('/api/me/complaints', query),
  /** #3: của người khác cũng 404. */
  getMyComplaint: (complaintId: number) => get<Complaint>(`/api/me/complaints/${complaintId}`),
  /** #4: 201 trả cả khiếu nại; 409 khi đã CLOSED. */
  postMyComplaintMessage: (complaintId: number, body: PostMessageRequest) =>
    post<Complaint>(`/api/me/complaints/${complaintId}/messages`, body),
  /** #5: RESOLVED → CLOSED (đóng); OPEN / IN_PROGRESS → CLOSED (rút, không mở lại); 409 khi đã CLOSED. */
  closeMyComplaint: (complaintId: number) => post<Complaint>(`/api/me/complaints/${complaintId}/close`),
  /** #6: `body` là lý do mở lại; 400 BR-DG-07 đã rút, quá 7 ngày, quá 2 lần mở lại; 409 khi đang OPEN / IN_PROGRESS. */
  reopenMyComplaint: (complaintId: number, body: PostMessageRequest) =>
    post<Complaint>(`/api/me/complaints/${complaintId}/reopen`, body),

  // ─── Nhân viên (A04, A05, A06) ───
  /** #7: lễ tân; khách và chi nhánh như #1, chi nhánh phải là chi nhánh của lễ tân (403 nếu khác). */
  createComplaintForCustomer: (body: CreateComplaintForCustomerRequest) => post<Complaint>('/api/complaints', body),
  /** #8: `status` vắng = OPEN + IN_PROGRESS; cũ nhất trước (theo hoạt động cuối); tự lọc theo phạm vi người gọi. */
  listComplaints: (query: ComplaintQuery = {}) => get<PageResponse<ComplaintSummary>>('/api/complaints', query),
  /** #9: ngoài phạm vi người gọi là 403. */
  getComplaint: (complaintId: number) => get<Complaint>(`/api/complaints/${complaintId}`),
  /** #10: OPEN → IN_PROGRESS (gán chính mình); 409 khi không còn OPEN. */
  takeComplaint: (complaintId: number) => post<Complaint>(`/api/complaints/${complaintId}/take`),
  /** #11: 201 trả cả khiếu nại; 409 khi đã CLOSED. */
  postComplaintMessage: (complaintId: number, body: PostMessageRequest) =>
    post<Complaint>(`/api/complaints/${complaintId}/messages`, body),
  /** #12: IN_PROGRESS → RESOLVED; thiếu kết luận là 400 BR-DG-07; 409 khi không còn IN_PROGRESS. */
  resolveComplaint: (complaintId: number, body: ResolveComplaintRequest) =>
    post<Complaint>(`/api/complaints/${complaintId}/resolve`, body),
};
