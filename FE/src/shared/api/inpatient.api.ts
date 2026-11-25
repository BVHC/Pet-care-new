// Nội trú (UC55, UC56) theo docs/api/inpatient-v1.md #1–10. VET của chi nhánh nhập / xuất viện, ra và hủy y lệnh; CARETAKER (A08) chỉ làm việc không phải thuốc và ghi
// theo dõi; lễ tân chỉ xem. Lỗi 400 mang mã rule ở cuối message (BR-LT-13…16), 409 INVALID_STATE_TRANSITION. Khách chưa có endpoint xem nội trú (09 Q4).
// Ghép BE thật từ T79 (25/11/2026; BE-1 cài ở T69, PR #34: AdmissionController, AdmissionOrderController, AdmissionObservationController); số đo ở docs/testcases/T57-fe.md mục NT. Mock `inpatient.mock.ts` khớp BE đo được.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  Admission,
  AdmissionOrder,
  AdmissionOrderQuery,
  AdmissionQuery,
  AdmissionSummary,
  AdmitRequest,
  CompleteAdmissionOrderRequest,
  CreateAdmissionOrderRequest,
  CreateObservationRequest,
  DischargeRequest,
  Observation,
  ObservationQuery,
  ReasonRequest,
} from '../types/inpatient';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const inpatientApi = {
  /** #1 (VET): đúng một trong visitId / boardingBookingId; từ lượt khám BẮT BUỘC kennelId, từ đặt chỗ lưu trú CẤM kennelId (400 BR-LT-13); 400 BR-LT-13 (Visit không IN_PROGRESS / không của mình / không nhóm Khám-Tiêm, thú đang lưu trú, đặt chỗ không CHECKED_IN / OVERDUE, dịch vụ không INPATIENT / ngừng / chưa bật, chuồng không AVAILABLE, thú đã mất); 409 thú đã ADMITTED (đứng trước mọi 400 điều kiện). */
  admit: (body: AdmitRequest) => post<Admission>('/api/admissions', body),
  /** #2 (lễ tân xem, VET, CARETAKER): `status` vắng = ADMITTED; nhập viện sớm nhất trước. */
  list: (query: AdmissionQuery = {}) => get<PageResponse<AdmissionSummary>>('/api/admissions', query),
  /** #3. */
  getAdmission: (admissionId: number) => get<Admission>(`/api/admissions/${admissionId}`),
  /** #4 (VET đang được gán Visit, nếu không 403): ADMITTED → DISCHARGED; 400 BR-LT-14 thiếu tóm tắt (đứng trước 404) hoặc còn y lệnh ORDERED (sau 409); kết cục DECEASED không đòi và không tự đánh dấu thú mất; thêm dòng dịch vụ nội trú (số ngày × giá) vào Order. */
  discharge: (admissionId: number, body: DischargeRequest) => post<Admission>(`/api/admissions/${admissionId}/discharge`, body),
  /** #5 (VET đang được gán Visit, nếu không 403): 409 ca không còn ADMITTED đứng trước 400 BR-LT-15 (nội dung rỗng, MEDICATION thiếu / sai / mua ngoài dòng đơn, loại khác mà có dòng đơn). */
  createOrder: (admissionId: number, body: CreateAdmissionOrderRequest) => post<AdmissionOrder>(`/api/admissions/${admissionId}/orders`, body),
  /** #6: mảng, không phân trang; scheduledAt tăng dần, trùng thì mã tăng dần. */
  listOrders: (admissionId: number, query: AdmissionOrderQuery = {}) => get<AdmissionOrder[]>(`/api/admissions/${admissionId}/orders`, query),
  /** #7 (VET nào cũng được; CARETAKER chỉ việc không phải thuốc, còn lại 403 ACCESS_DENIED_SCOPE_MISMATCH): ORDERED → DONE; 409 đứng trước 400 BR-LT-15 thiếu ghi chú. */
  completeOrder: (admissionOrderId: number, body: CompleteAdmissionOrderRequest) => post<AdmissionOrder>(`/api/admission-orders/${admissionOrderId}/complete`, body),
  /** #8 (VET đang được gán Visit, nếu không 403): ORDERED → CANCELLED; 409 đứng trước 400 BR-LT-15 thiếu lý do. */
  cancelOrder: (admissionOrderId: number, body: ReasonRequest) => post<AdmissionOrder>(`/api/admission-orders/${admissionOrderId}/cancel`, body),
  /** #9 (VET, CARETAKER, không cần là người được gán): chỉ thêm, không sửa / xóa; 409 ca không còn ADMITTED đứng trước 400 BR-LT-16. */
  createObservation: (admissionId: number, body: CreateObservationRequest) => post<Observation>(`/api/admissions/${admissionId}/observations`, body),
  /** #10: mới nhất trước, phân trang. */
  listObservations: (admissionId: number, query: ObservationQuery = {}) => get<PageResponse<Observation>>(`/api/admissions/${admissionId}/observations`, query),
};
