// Branch API — docs/api/branch-v1.md #1–20. Tạo / sửa / kích hoạt: SUPER_MANAGER; giờ mở cửa, ngày nghỉ,
// dịch vụ, quota: BRANCH_MANAGER của chính chi nhánh; đọc quota: BRANCH_MANAGER, RECEPTIONIST.
import { apiClient } from './axios';
import type {
  Branch,
  BranchServiceItem,
  BranchStatus,
  CreateBranchRequest,
  CreateHolidayRequest,
  Holiday,
  OpeningHoursVersion,
  PublicBranch,
  QuotaDefault,
  QuotaGroup,
  ScheduleImpact,
  SetOpeningHoursRequest,
  SetSlotQuotaRequest,
  SlotQuota,
  UpdateBranchRequest,
} from '../types/branch';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;
const remove = async (url: string): Promise<void> => {
  await apiClient.delete(url);
};

const base = (branchId: number) => `/api/branches/${branchId}`;

export const branchApi = {
  /** #20, không cần đăng nhập: chỉ chi nhánh ACTIVE (BR-CK-01). */
  listPublicBranches: (params?: { acceptsAfterHoursEmergency?: boolean }) => get<PublicBranch[]>('/api/public/branches', params),
  /** #21, không cần đăng nhập: 404 nếu chi nhánh không ACTIVE hoặc không có. */
  getPublicBranch: (branchId: number) => get<PublicBranch>(`/api/public/branches/${branchId}`),

  /** Nhân viên chi nhánh chỉ nhận được chi nhánh của mình. */
  listBranches: (params?: { status?: BranchStatus }) => get<Branch[]>('/api/branches', params),
  getBranch: (branchId: number) => get<Branch>(base(branchId)),
  createBranch: (body: CreateBranchRequest) => post<Branch>('/api/branches', body),
  updateBranch: (branchId: number, body: UpdateBranchRequest) => patch<Branch>(base(branchId), body),
  /** 400 BR-QT-04 / BR-CN-01 kèm điều kiện còn thiếu; 409 nếu đã ACTIVE. */
  activateBranch: (branchId: number) => post<Branch>(`${base(branchId)}/activate`),

  /** Bản đang áp dụng và các bản tương lai. */
  listOpeningHours: (branchId: number) => get<OpeningHoursVersion[]>(`${base(branchId)}/opening-hours`),
  previewOpeningHours: (branchId: number, body: OpeningHoursVersion) =>
    post<ScheduleImpact>(`${base(branchId)}/opening-hours/impact`, body),
  setOpeningHours: (branchId: number, body: SetOpeningHoursRequest) =>
    put<OpeningHoursVersion>(`${base(branchId)}/opening-hours`, body),

  listHolidays: (branchId: number, params?: { from?: string; to?: string }) =>
    get<Holiday[]>(`${base(branchId)}/holidays`, params),
  previewHoliday: (branchId: number, holidayDate: string) =>
    post<ScheduleImpact>(`${base(branchId)}/holidays/impact`, { holidayDate }),
  addHoliday: (branchId: number, body: CreateHolidayRequest) => post<Holiday>(`${base(branchId)}/holidays`, body),
  /** Chỉ ngày nghỉ trong tương lai; lịch đã hủy hàng loạt không được khôi phục. */
  deleteHoliday: (branchId: number, holidayId: number) => remove(`${base(branchId)}/holidays/${holidayId}`),

  listServices: (branchId: number) => get<BranchServiceItem[]>(`${base(branchId)}/services`),
  setService: (branchId: number, serviceId: number, enabled: boolean) =>
    put<BranchServiceItem>(`${base(branchId)}/services/${serviceId}`, { enabled }),

  listQuotaDefaults: (branchId: number) => get<QuotaDefault[]>(`${base(branchId)}/quota-defaults`),
  setQuotaDefault: (branchId: number, group: QuotaGroup, defaultQuota: number) =>
    put<QuotaDefault>(`${base(branchId)}/quota-defaults/${group}`, { defaultQuota }),
  listSlotQuotas: (branchId: number, params: { from: string; to: string; serviceGroup?: QuotaGroup }) =>
    get<SlotQuota[]>(`${base(branchId)}/slot-quotas`, params),
  /** Tạo mới hoặc ghi đè quota riêng của một khung; 0 = khóa khung. */
  setSlotQuota: (branchId: number, body: SetSlotQuotaRequest) => put<SlotQuota>(`${base(branchId)}/slot-quotas`, body),
  deleteSlotQuota: (branchId: number, slotQuotaId: number) => remove(`${base(branchId)}/slot-quotas/${slotQuotaId}`),
};
