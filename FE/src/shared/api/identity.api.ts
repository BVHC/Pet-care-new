// Identity admin API — docs/api/identity-v1.md #15–36 (Staff, tài khoản của hồ sơ khách tại quầy, Accounts, Configuration, Audit).
// Bốn màn admin và hồ sơ khách ở quầy gọi qua file này; tầng hooks gọi tiếp từ đây.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { AccountSummary } from '../types/auth';
import type { LinkResult } from '../types/profile';
import type {
  AccountQuery,
  AuditLogEntry,
  AuditQuery,
  ChangeRoleRequest,
  CounterLinkRequest,
  CreateStaffRequest,
  CustomerEmailChangeRequest,
  EmailChangeRequest,
  LockResult,
  NotificationTemplate,
  OtpCodeRequest,
  OtpSentResponse,
  ReactivateStaffRequest,
  ReasonRequest,
  StaffQuery,
  StaffResponse,
  SystemConfig,
  TransferStaffRequest,
  UpdateConfigRequest,
  UpdateTemplateRequest,
} from '../types/identity';
import type { PublicVet } from '../types/public-site';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const identityApi = {
  // ─── Công khai (UC15), không cần đăng nhập ───
  /** VET ACTIVE ở chi nhánh ACTIVE, sắp theo họ tên; `branchId` không ACTIVE thì mảng rỗng. */
  listPublicVets: (params?: { branchId?: number }) => get<PublicVet[]>('/api/public/vets', params),

  // ─── Staff (UC08) ───
  listStaff: (query: StaffQuery = {}) => get<PageResponse<StaffResponse>>('/api/staff', query),
  createStaff: (body: CreateStaffRequest) => post<StaffResponse>('/api/staff', body),
  getStaff: (id: number) => get<StaffResponse>(`/api/staff/${id}`),
  changeRole: (id: number, body: ChangeRoleRequest) => post<StaffResponse>(`/api/staff/${id}/change-role`, body),
  transfer: (id: number, body: TransferStaffRequest) => post<StaffResponse>(`/api/staff/${id}/transfer`, body),
  disable: (id: number) => post<StaffResponse>(`/api/staff/${id}/disable`, {}),
  reactivate: (id: number, body: ReactivateStaffRequest) => post<StaffResponse>(`/api/staff/${id}/reactivate`, body),
  resendInitialPassword: (id: number) => post<void>(`/api/staff/${id}/resend-initial-password`, {}),
  sendEmailChange: (id: number, body: EmailChangeRequest) => post<OtpSentResponse>(`/api/staff/${id}/email-change`, body),
  confirmEmailChange: (id: number, body: OtpCodeRequest) => post<StaffResponse>(`/api/staff/${id}/email-change/confirm`, body),

  // ─── Tài khoản của hồ sơ khách tại quầy (UC22, A06) ───
  sendCustomerEmailChange: (customerId: number, body: CustomerEmailChangeRequest) =>
    post<OtpSentResponse>(`/api/customers/${customerId}/account-email-change`, body),
  /** 204: đọc `.data.data` của body rỗng ra undefined. */
  confirmCustomerEmailChange: async (customerId: number, body: OtpCodeRequest): Promise<void> => {
    await apiClient.post(`/api/customers/${customerId}/account-email-change/confirm`, body);
  },
  linkCounterAccount: (customerId: number, body: CounterLinkRequest) =>
    post<LinkResult>(`/api/customers/${customerId}/link-account`, body),

  // ─── Accounts (UC09, ADMIN only) ───
  listAccounts: (query: AccountQuery = {}) => get<PageResponse<AccountSummary>>('/api/accounts', query),
  lock: (id: number, body: ReasonRequest) => post<LockResult>(`/api/accounts/${id}/lock`, body),
  unlock: (id: number, body: ReasonRequest) => post<AccountSummary>(`/api/accounts/${id}/unlock`, body),

  // ─── Configuration (UC10, ADMIN only) ───
  listSystemConfigs: () => get<SystemConfig[]>('/api/system-configs'),
  updateSystemConfig: (key: string, body: UpdateConfigRequest) => put<SystemConfig>(`/api/system-configs/${key}`, body),

  // ─── Notification templates (UC10, ADMIN only) ───
  listNotificationTemplates: () => get<NotificationTemplate[]>('/api/notification-templates'),
  updateNotificationTemplate: (code: string, body: UpdateTemplateRequest) =>
    put<NotificationTemplate>(`/api/notification-templates/${code}`, body),
  restoreTemplateDefault: (code: string) =>
    post<NotificationTemplate>(`/api/notification-templates/${code}/restore-default`, {}),

  // ─── Audit (UC11, ADMIN only) ───
  listAuditLogs: (query: AuditQuery = {}) => get<PageResponse<AuditLogEntry>>('/api/audit-logs', query),
};
