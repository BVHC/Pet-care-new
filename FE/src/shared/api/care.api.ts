// Việc gọi điện của lễ tân (Care Task, UC87) theo docs/api/care-v1.md #1–4: chỉ RECEPTIONIST của chi nhánh được giao (BR-TB-05);
// BE chưa có (T39), FE chạy mock. Thông báo của tôi (UC88) #5–10: mọi role đã đăng nhập (docs/adr/0039);
// cài đặt nhận thông báo: chỉ CUSTOMER.
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

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const careApi = {
  /** `status` vắng = OPEN; sắp theo hạn tăng dần. */
  listTasks: (query: CareTaskQuery = {}) => get<PageResponse<CareTask>>('/api/care-tasks', query),
  getTask: (careTaskId: number) => get<CareTask>(`/api/care-tasks/${careTaskId}`),
  /** OPEN → DONE; 400 BR-TB-05 khi ghi chú trống; 409 khi task không còn OPEN. */
  completeTask: (careTaskId: number, body: CompleteCareTaskRequest) => post<CareTask>(`/api/care-tasks/${careTaskId}/complete`, body),
  /** OPEN → CANCELLED; 409 khi task không còn OPEN. */
  cancelTask: (careTaskId: number, body: CancelCareTaskRequest) => post<CareTask>(`/api/care-tasks/${careTaskId}/cancel`, body),

  listNotifications: (query: NotificationQuery = {}) => get<PageResponse<AppNotification>>('/api/me/notifications', query),
  countUnread: () => get<UnreadCount>('/api/me/notifications/unread-count'),
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
