// Tiếp nhận, hàng đợi, gọi lượt (UC44–UC47) theo docs/api/visit-v1.md #1–9, #11 và GET /me/queue. Chi nhánh lấy từ người gọi.
// Vai trò (đã dò BE thật, xem `VISIT_ROLES` ở types/visit.ts): tiếp nhận / gán / sắp lại / hủy là lễ tân; xem hàng đợi chi nhánh là A05–A08; hàng đợi của tôi và
// gọi lượt là VET, CARETAKER; đánh dấu cấp cứu là lễ tân hoặc VET; gán lại lượt đã gọi là BRANCH_MANAGER (SUPER_MANAGER khi chi nhánh hết quản lý ACTIVE).
// #10 hoàn tất lượt và bệnh án thuộc T37.
import { apiClient } from './axios';
import { appointmentApi } from './appointment.api';
import type { PageResponse } from '../types';
import type { Appointment, AppointmentQuery } from '../types/appointment';
import type {
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
   * Khách đã tới để check-in: lịch BOOKED hôm nay trong cửa sổ check-in (appointment-v1 #3 `checkInReady`, BR-TN-02). Lễ tân / BRANCH_MANAGER.
   * `size` mặc định 100 để một trang đủ cho một chi nhánh trong ngày.
   */
  listArrivals: (query: ArrivalsQuery = {}): Promise<PageResponse<Appointment>> => appointmentApi.list({ size: 100, ...query, checkInReady: true, status: 'BOOKED' }),
};
