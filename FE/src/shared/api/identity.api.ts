// Identity admin API — docs/api/identity-v1.md #15–24, #28–36 (Staff, Accounts, Configuration, Audit).
// Bốn màn admin gọi qua file này; tầng hooks gọi tiếp từ đây.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { AccountSummary } from '../types/auth';
import type {
  AccountQuery,
  AuditLogEntry,
  AuditQuery,
  ChangeRoleRequest,
  CreateStaffRequest,
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

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const identityApi = {
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
