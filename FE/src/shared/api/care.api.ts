// Thông báo của tôi (UC88) theo docs/api/care-v1.md #5–10. Thông báo: mọi role đã đăng nhập (docs/adr/0039);
// cài đặt nhận thông báo: chỉ CUSTOMER. Care Task (#1–4) chưa dùng ở FE.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { AppNotification, NotificationQuery, NotificationSettings, UnreadCount } from '../types/care';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const careApi = {
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
