// Việc gọi điện của lễ tân (Care Task, UC87) theo docs/api/care-v1.md #1–4: chỉ RECEPTIONIST của chi nhánh được giao (BR-TB-05);
// BE đã có care #1–8 (T39 của BE-1): CareTaskController #1–4 chỉ RECEPTIONIST; MyNotificationController #5–8 (Thông báo của tôi,
// UC88) mọi role đã đăng nhập (docs/adr/0039). Care #9–10 (cài đặt nhận thông báo, chỉ CUSTOMER) chưa có BE (D025, chờ PO).
import type { AxiosRequestConfig } from 'axios';
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  AppNotification,
  CancelCareTaskRequest,
  CareTask,
  CareTaskQuery,
  CompleteCareTaskRequest,
  NotificationQuery,
  NotificationSettings,
  UnreadCount,
} from '../types/care';

/** `config`: truyền `POLL` (axios.ts) cho request theo nhịp refetchInterval. */
const get = async <T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> => (await apiClient.get(url, { params, ...config })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const careApi = {
  /** `status` vắng = OPEN; sắp theo hạn tăng dần. */
  listTasks: (query: CareTaskQuery = {}) => get<PageResponse<CareTask>>('/api/care-tasks', query),
  getTask: (careTaskId: number) => get<CareTask>(`/api/care-tasks/${careTaskId}`),
  /** OPEN → DONE; 400 BR-TB-05 khi thiếu kết quả hoặc ghi chú trống; 409 khi task không còn OPEN. */
  completeTask: (careTaskId: number, body: CompleteCareTaskRequest) => post<CareTask>(`/api/care-tasks/${careTaskId}/complete`, body),
  /** OPEN → CANCELLED; 409 khi task không còn OPEN. */
  cancelTask: (careTaskId: number, body: CancelCareTaskRequest) => post<CareTask>(`/api/care-tasks/${careTaskId}/cancel`, body),

  listNotifications: (query: NotificationQuery = {}) => get<PageResponse<AppNotification>>('/api/me/notifications', query),
  countUnread: (config?: AxiosRequestConfig) => get<UnreadCount>('/api/me/notifications/unread-count', undefined, config),
  /** 204; gọi lại vẫn 204. Thông báo của người khác: 404. */
  markRead: async (notificationId: number): Promise<void> => {
    await apiClient.post(`/api/me/notifications/${notificationId}/read`);
  },
  markAllRead: async (): Promise<void> => {
    await apiClient.post('/api/me/notifications/read-all');
  },
  getSettings: () => get<NotificationSettings>('/api/me/notification-settings'),
  updateSettings: (body: NotificationSettings) => put<NotificationSettings>('/api/me/notification-settings', body),
};
