// Tiếp nhận, hàng đợi, gọi lượt (UC44–UC47) theo docs/api/visit-v1.md #1–9, #11 và GET /me/queue. Chi nhánh lấy từ người gọi.
// Vai trò (đã dò BE thật, xem `VISIT_ROLES` ở types/visit.ts): tiếp nhận / gán / sắp lại / hủy là lễ tân; xem hàng đợi chi nhánh là A05–A08; hàng đợi của tôi và
// gọi lượt là VET, CARETAKER; đánh dấu cấp cứu là lễ tân hoặc VET; gán lại lượt đã gọi là BRANCH_MANAGER (SUPER_MANAGER khi chi nhánh hết quản lý ACTIVE).
// T37: #10 hoàn tất lượt (VET cho Khám/Tiêm, CARETAKER cho Thẩm mỹ), #12 xem bệnh án (VET, CARETAKER), #13 ghi bệnh án, #14 cân nặng, #15 bản bổ sung (VET), #18–20 tiêm chủng
// (VET được gán lượt; BE có từ T28, 26/10). T46: #16–17 kê đơn / xóa dòng đơn thuốc (VET được gán lượt; BE có từ T38).
import { apiClient } from './axios';
import { appointmentApi } from './appointment.api';
import type { PageResponse } from '../types';
import type { Appointment, AppointmentQuery } from '../types/appointment';
import type {
  Addendum,
  AddendumRequest,
  MedicalRecord,
  PrescriptionItem,
  RecordVaccinationRequest,
  RecordVaccinationResult,
  RecordWeightRequest,
  UpdateMedicalRecordRequest,
  VaccinationOptions,
  WeightRecordRef,
} from '../types/medical-record';
import type {
  AddPrescriptionRequest,
  AssignRequest,
  AssignResult,
  CancelVisitRequest,
  CheckInRequest,
  MoveVisitRequest,
  ReassignRequest,
  Visit,
  VisitQueueQuery,
} from '../types/visit';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

/** Bộ lọc thêm cho danh sách chờ check-in; `checkInReady` và `status` do `listArrivals` cố định. */
export type ArrivalsQuery = Pick<AppointmentQuery, 'date' | 'branchId' | 'page' | 'size'>;

export const visitApi = {
  /**
   * #1 (lễ tân). Lịch hẹn: `appointmentId` (dịch vụ lấy từ lịch). Walk-in: `serviceId` MEDICAL / GROOMING. Cấp cứu: thêm `isEmergency: true`
   * (tên trường đúng là `isEmergency`; BE bỏ qua trường lạ). Lỗi hay gặp: 400 BR-TN-01 / BR-TN-02 / BR-CN-05 / BR-LH-01, 409 lịch hẹn không còn BOOKED.
   */
  checkIn: (body: CheckInRequest) => post<Visit>('/api/visits', body),
  /** #2: A05–A08; mảng trần theo BR-TN-04 (cấp cứu → lịch hẹn → walk-in). Không gửi `date`/`status`: chỉ lượt WAITING / IN_PROGRESS của mọi ngày. */
  listQueue: (query: VisitQueueQuery = {}) => get<Visit[]>('/api/visits', query),
  /** #3: VET, CARETAKER; lượt WAITING / IN_PROGRESS gán cho chính mình. */
  getMyQueue: () => get<Visit[]>('/api/me/queue'),
  /** #4: A04–A08 (SUPER_MANAGER mọi chi nhánh); nhân viên xem lượt chi nhánh khác → 403. */
  getVisit: (visitId: number) => get<Visit>(`/api/visits/${visitId}`),
  /** #5 (lễ tân): lượt chưa gọi. Người offline vẫn gán được, `assigneeOffline` là cảnh báo (BR-TN-06). 400 BR-TN-05 khi sai chức vụ / không ACTIVE. */
  assign: (visitId: number, body: AssignRequest) => post<AssignResult>(`/api/visits/${visitId}/assign`, body),
  /** #6 (lễ tân): đặt `visitId` ngay trước `placeBeforeVisitId`; trả cả hàng đợi chưa kết thúc sau khi sắp. */
  move: (visitId: number, body: MoveVisitRequest) => post<Visit[]>(`/api/visits/${visitId}/move`, body),
  /** #7 (lễ tân hoặc VET): lượt WAITING lên đầu hàng đợi; 409 nếu đã là cấp cứu hoặc đã gọi. */
  markEmergency: (visitId: number) => post<Visit>(`/api/visits/${visitId}/mark-emergency`),
  /** #8 (VET, CARETAKER): chỉ người được gán; lượt chưa gán hoặc của người khác → 403. */
  call: (visitId: number) => post<Visit>(`/api/visits/${visitId}/call`),
  /** #9 (BRANCH_MANAGER): lượt đã gọi mà người phụ trách không còn đủ điều kiện (400 BR-TN-08 nếu còn); lý do bắt buộc. */
  reassign: (visitId: number, body: ReassignRequest) => post<Visit>(`/api/visits/${visitId}/reassign`, body),
  /** #11 (lễ tân): chỉ lượt WAITING (BR-TN-07); lý do không bắt buộc. */
  cancel: (visitId: number, body?: CancelVisitRequest) => post<Visit>(`/api/visits/${visitId}/cancel`, body),

  /**
   * #10 (người được gán): IN_PROGRESS → COMPLETED, lịch hẹn COMPLETED, Order PENDING, bệnh án khóa. 400 BR-KB-02 (khám thiếu chẩn đoán / chỉ tiêm chưa có mũi / không còn dịch vụ
   * Khám hoặc Tiêm), 400 BR-TN-05 (đổi chức vụ sau khi gọi), 403 không phải người phụ trách, 409 lượt không IN_PROGRESS.
   */
  complete: (visitId: number) => post<Visit>(`/api/visits/${visitId}/complete`),

  /** #12 (VET, CARETAKER chỉ xem): lượt Khám/Tiêm chưa ghi bệnh án trả khung rỗng; lượt Thẩm mỹ → 404 (không có bệnh án). */
  getMedicalRecord: (visitId: number) => get<MedicalRecord>(`/api/visits/${visitId}/medical-record`),
  /** #13 (VET được gán): PUT thay toàn bộ 5 trường. 409 khi lượt không IN_PROGRESS (bệnh án đã khóa: dùng bản bổ sung), 400 BR-KB-06 ngày tái khám. */
  saveMedicalRecord: (visitId: number, body: UpdateMedicalRecordRequest) => put<MedicalRecord>(`/api/visits/${visitId}/medical-record`, body),
  /** #14 (VET được gán): mỗi lần gọi là một bản ghi cân nặng; 400 BR-KH-04 khi ≤ 0 hoặc > 9999.99. */
  recordWeight: (visitId: number, body: RecordWeightRequest) => post<WeightRecordRef>(`/api/visits/${visitId}/weight`, body),
  /** #15 (mọi VET của chi nhánh): chỉ khi lượt đã COMPLETED, 409 nếu chưa (sửa trực tiếp bệnh án). */
  addAddendum: (visitId: number, body: AddendumRequest) => post<Addendum>(`/api/visits/${visitId}/medical-record/addenda`, body),

  /**
   * #16 (VET được gán, lượt IN_PROGRESS): thêm một dòng thuốc kê đơn. Đủ tồn → dòng đơn kèm dòng Order thuốc. Thiếu tồn mà chưa có `shortageResolution` → 400 BR-KB-03 kèm số tồn
   * trong message "Tồn khả dụng tại chi nhánh chỉ còn N; …" (gửi lại cùng body kèm `shortageResolution`). Cùng mã BR-KB-03 còn có "Sản phẩm không phải thuốc kê đơn đang kinh doanh" (id lạ, không phải thuốc
   * kê đơn, ngừng kinh doanh) và "Thuốc đã hết tồn khả dụng…" (chọn giảm khi tồn 0): hai ca này là lỗi thường. 409 khi lượt không IN_PROGRESS.
   */
  addPrescription: (visitId: number, body: AddPrescriptionRequest) => post<PrescriptionItem>(`/api/visits/${visitId}/prescriptions`, body),
  /** #17 (VET được gán, lượt IN_PROGRESS): xóa dòng đơn, kèm dòng Order thuốc của nó (nếu có). 204 rỗng. */
  deletePrescription: async (visitId: number, itemId: number): Promise<void> => {
    await apiClient.delete(`/api/visits/${visitId}/prescriptions/${itemId}`);
  },

  /**
   * #18 (VET được gán): mũi gợi ý (chỉ tham khảo; hết phác đồ thì là mũi lớn nhất để nhắc lại) và sản phẩm còn tồn khả dụng; 400 BR-KB-04 khi loại vaccine không thuộc loài của thú, 400 BR-SP-07 khi loại
   * đã ngừng sử dụng.
   */
  getVaccinationOptions: (visitId: number, vaccineTypeId: number) =>
    get<VaccinationOptions>(`/api/visits/${visitId}/vaccination-options`, { vaccineTypeId }),
  /** #19 (VET được gán, lượt IN_PROGRESS): trừ kho FEFO ngay, thêm dòng Order vaccine; `warnings` có thể chứa `UNDER_MIN_AGE`. */
  recordVaccination: (visitId: number, body: RecordVaccinationRequest) =>
    post<RecordVaccinationResult>(`/api/visits/${visitId}/vaccinations`, body),
  /**
   * #20 (VET được gán lượt HIỆN TẠI, lượt IN_PROGRESS; không xét người đã ghi mũi, ADR-0075): hoàn kho đúng lô, xóa dòng Order vaccine. 409 CONCURRENCY_CONFLICT (chung chung) khi còn Care Task
   * nhắc tái chủng trỏ tới mũi.
   */
  deleteVaccination: async (visitId: number, vaccinationId: number): Promise<void> => {
    await apiClient.delete(`/api/visits/${visitId}/vaccinations/${vaccinationId}`);
  },

  /**
   * Khách đã tới để check-in: lịch BOOKED hôm nay trong cửa sổ check-in (appointment-v1 #3 `checkInReady`, BR-TN-02). Lễ tân / BRANCH_MANAGER.
   * `size` mặc định 100 để một trang đủ cho một chi nhánh trong ngày.
   */
  listArrivals: (query: ArrivalsQuery = {}): Promise<PageResponse<Appointment>> => appointmentApi.list({ size: 100, ...query, checkInReady: true, status: 'BOOKED' }),
};
