// Nội trú (UC55, UC56) theo docs/api/inpatient-v1.md #1–10. VET của chi nhánh nhập / xuất viện, ra và hủy y lệnh; CARETAKER (A08) chỉ làm việc không phải thuốc và ghi
// theo dõi; lễ tân chỉ xem. Lỗi 400 mang mã rule ở cuối message (BR-LT-13…16), 409 INVALID_STATE_TRANSITION. Khách chưa có endpoint xem nội trú (09 Q4).
// BE tầng 3 chưa cài (T54 chỉ duyệt đặc tả): đang chạy trên mock `inpatient.mock.ts`.
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
  /** #1 (VET): đúng một trong visitId / boardingBookingId; 400 BR-LT-13 (Visit không IN_PROGRESS / không của mình, đặt chỗ không CHECKED_IN, dịch vụ không INPATIENT, chuồng không AVAILABLE, thú đã mất); 409 thú đã ADMITTED. */
  admit: (body: AdmitRequest) => post<Admission>('/api/admissions', body),
  /** #2 (lễ tân xem, VET, CARETAKER): `status` vắng = ADMITTED; nhập viện sớm nhất trước. */
  list: (query: AdmissionQuery = {}) => get<PageResponse<AdmissionSummary>>('/api/admissions', query),
  /** #3. */
  getAdmission: (admissionId: number) => get<Admission>(`/api/admissions/${admissionId}`),
  /** #4 (VET): ADMITTED → DISCHARGED; 400 BR-LT-14 còn y lệnh ORDERED, thiếu tóm tắt, DECEASED mà thú chưa đánh dấu đã mất; thêm dòng dịch vụ nội trú (số ngày × giá) vào Order. */
  discharge: (admissionId: number, body: DischargeRequest) => post<Admission>(`/api/admissions/${admissionId}/discharge`, body),
  /** #5 (VET): 400 BR-LT-15 MEDICATION thiếu dòng đơn thuốc hoặc dòng đơn không thuộc Visit; 409 ca không còn ADMITTED. */
  createOrder: (admissionId: number, body: CreateAdmissionOrderRequest) => post<AdmissionOrder>(`/api/admissions/${admissionId}/orders`, body),
  /** #6: mảng, không phân trang; scheduledAt tăng dần, trùng thì mã tăng dần. */
  listOrders: (admissionId: number, query: AdmissionOrderQuery = {}) => get<AdmissionOrder[]>(`/api/admissions/${admissionId}/orders`, query),
  /** #7 (VET; CARETAKER chỉ việc không phải thuốc, còn lại 403): ORDERED → DONE; 400 BR-LT-15 thiếu ghi chú. */
  completeOrder: (admissionOrderId: number, body: CompleteAdmissionOrderRequest) => post<AdmissionOrder>(`/api/admission-orders/${admissionOrderId}/complete`, body),
  /** #8 (VET): ORDERED → CANCELLED; 400 BR-LT-15 thiếu lý do. */
  cancelOrder: (admissionOrderId: number, body: ReasonRequest) => post<AdmissionOrder>(`/api/admission-orders/${admissionOrderId}/cancel`, body),
  /** #9 (VET, CARETAKER): chỉ thêm, không sửa / xóa; 400 BR-LT-16; 409 ca không còn ADMITTED. */
  createObservation: (admissionId: number, body: CreateObservationRequest) => post<Observation>(`/api/admissions/${admissionId}/observations`, body),
  /** #10: mới nhất trước, phân trang. */
  listObservations: (admissionId: number, query: ObservationQuery = {}) => get<PageResponse<Observation>>(`/api/admissions/${admissionId}/observations`, query),
};
