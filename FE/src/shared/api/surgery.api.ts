// Phẫu thuật (UC50) theo docs/api/surgery-v1.md #1–8. VET phụ trách Visit lập kế hoạch / bắt đầu / hoàn tất; lễ tân của chi nhánh lập cam kết; VET và lễ tân hủy
// (lễ tân chỉ khi PLANNED / CONSENTED); CARETAKER chỉ xem. Lỗi 400 mang mã rule ở cuối message (BR-KB-08…11), 409 INVALID_STATE_TRANSITION.
// BE tầng 3 chưa cài (T54 chỉ duyệt đặc tả): đang chạy trên mock `surgery.mock.ts`.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  CancelSurgeryRequest,
  CompleteSurgeryRequest,
  ConsentSurgeryRequest,
  PlanSurgeryRequest,
  Surgery,
  SurgeryQuery,
  SurgerySummary,
} from '../types/surgery';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const surgeryApi = {
  /** #1 (VET phụ trách Visit): 400 BR-KB-08 khi Visit không IN_PROGRESS, dịch vụ không phải SURGERY hoặc không bật ở chi nhánh. */
  plan: (visitId: number, body: PlanSurgeryRequest) => post<Surgery>(`/api/visits/${visitId}/surgeries`, body),
  /** #2 (lễ tân, VET, CARETAKER của chi nhánh): mảng, không phân trang, cũ nhất trước. */
  listByVisit: (visitId: number) => get<Surgery[]>(`/api/visits/${visitId}/surgeries`),
  /** #3 (lễ tân, VET): `status` vắng = việc cần làm (PLANNED, CONSENTED, IN_PROGRESS); cũ nhất trước. */
  list: (query: SurgeryQuery = {}) => get<PageResponse<SurgerySummary>>('/api/surgeries', query),
  /** #4 (lễ tân, VET, CARETAKER của chi nhánh). */
  getSurgery: (surgeryId: number) => get<Surgery>(`/api/surgeries/${surgeryId}`),
  /** #5 (lễ tân): PLANNED → CONSENTED; 400 BR-KB-09 thiếu nội dung hoặc người ký; 409 khi không còn PLANNED. */
  consent: (surgeryId: number, body: ConsentSurgeryRequest) => post<Surgery>(`/api/surgeries/${surgeryId}/consent`, body),
  /** #6 (VET phụ trách): CONSENTED → IN_PROGRESS; 400 BR-KB-09 chưa có cam kết hoặc Visit không còn IN_PROGRESS. */
  start: (surgeryId: number) => post<Surgery>(`/api/surgeries/${surgeryId}/start`, {}),
  /** #7 (VET phụ trách): IN_PROGRESS → COMPLETED, thêm dịch vụ vào Order của Visit; 400 BR-KB-10 thiếu ghi chú kết quả. */
  complete: (surgeryId: number, body: CompleteSurgeryRequest) => post<Surgery>(`/api/surgeries/${surgeryId}/complete`, body),
  /** #8 (VET phụ trách; lễ tân chỉ khi PLANNED / CONSENTED, còn lại 403): 400 BR-KB-11 thiếu lý do. */
  cancel: (surgeryId: number, body: CancelSurgeryRequest) => post<Surgery>(`/api/surgeries/${surgeryId}/cancel`, body),
};
