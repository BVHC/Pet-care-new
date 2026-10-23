// React Query cho thông báo của tôi (UC88, care-v1 #5–10): khóa gốc ['notifications'].
// Đọc / đọc tất cả làm mới cả danh sách lẫn số chưa đọc; cài đặt (chỉ khách) không bị làm mới theo.
import { useMemo } from 'react';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDistanceStrict } from 'date-fns';
import { vi } from 'date-fns/locale';
import {
  Bell, CalendarClock, CalendarX, Package, PawPrint, Receipt, ShieldAlert, Stethoscope, Syringe, type LucideIcon,
} from 'lucide-react';
import { toast } from 'sonner';
import { careApi } from '../api/care.api';
import { plainMessage, toApiError } from '../api/api-error';
import { rolesForPath } from '../constants/staff-menu';
import { useAccount } from '../stores/session.store';
import type { Role } from '../types/auth';
import type { AppNotification } from '../types/care';

export const CUSTOMER_NOTIFICATIONS_PATH = '/notifications';
export const STAFF_NOTIFICATIONS_PATH = '/admin/notifications';

/** Kích thước trang mặc định của BE (#5). */
const PAGE_SIZE = 20;
/** Chuông hỏi lại số chưa đọc mỗi phút. Với nhân viên, mỗi lần hỏi cũng làm mới last_seen_at (nợ D024, docs/adr/0039). */
const UNREAD_POLL_MS = 60_000;

const LIST_KEY = ['notifications', 'list'] as const;
const UNREAD_KEY = ['notifications', 'unread-count'] as const;
const SETTINGS_KEY = ['notifications', 'settings'] as const;

/**
 * Trang Thông báo của role, null = không có trang để mở → không hiện chuông. BE cho mọi role đã đăng nhập (kể cả ADMIN,
 * docs/adr/0039 §1), còn FE mở trang nhân viên theo ADMIN_MENU nên chuông nhân viên đi theo quyền của trang đó.
 */
export function notificationCenterPath(role: Role): string | null {
  if (role === 'CUSTOMER') return CUSTOMER_NOTIFICATIONS_PATH;
  return rolesForPath(STAFF_NOTIFICATIONS_PATH).includes(role) ? STAFF_NOTIFICATIONS_PATH : null;
}

/** Có gọi API thông báo không: đã đăng nhập, có trang Thông báo, không còn phải đổi mật khẩu (BR-TK-17 chặn cả chuông). */
function useNotificationsEnabled(): boolean {
  const account = useAccount();
  return !!account && !account.mustChangePassword && notificationCenterPath(account.role) !== null;
}

export function useUnreadCount() {
  const enabled = useNotificationsEnabled();
  return useQuery({
    queryKey: UNREAD_KEY,
    queryFn: careApi.countUnread,
    select: (data) => data.count,
    enabled,
    refetchInterval: UNREAD_POLL_MS,
  });
}

/** Thông báo mới nhất cho khung thả xuống của chuông; chỉ tải khi mở. */
export function useLatestNotifications(open: boolean, size = 5) {
  const enabled = useNotificationsEnabled();
  return useQuery({
    queryKey: [...LIST_KEY, { page: 0, size }],
    queryFn: async () => (await careApi.listNotifications({ page: 0, size })).content,
    enabled: enabled && open,
    staleTime: 0,
  });
}

/** Danh sách có "Xem thêm": mỗi lần một trang 20, mới nhất trước. */
export function useNotificationFeed(unreadOnly: boolean) {
  const enabled = useNotificationsEnabled();
  const query = useInfiniteQuery({
    queryKey: [...LIST_KEY, { unreadOnly, size: PAGE_SIZE }],
    queryFn: ({ pageParam }) => careApi.listNotifications({ unreadOnly, page: pageParam, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
    enabled,
    staleTime: 0,
  });
  // Phân trang theo vị trí: thông báo mới chen vào giữa hai lần tải đẩy phần tử cũ sang trang sau → bỏ trùng.
  const items = useMemo(() => {
    const seen = new Set<number>();
    return (query.data?.pages ?? [])
      .flatMap((p) => p.content)
      .filter((n) => !seen.has(n.notificationId) && seen.add(n.notificationId));
  }, [query.data]);
  return { ...query, items };
}

function useNotificationWrite<A>(fn: (args: A) => Promise<void>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () =>
      Promise.all([queryClient.invalidateQueries({ queryKey: LIST_KEY }), queryClient.invalidateQueries({ queryKey: UNREAD_KEY })]),
    onError: (error) => toast.error(plainMessage(toApiError(error))),
  });
}

export const useMarkNotificationRead = () => useNotificationWrite(careApi.markRead);
export const useMarkAllNotificationsRead = () => useNotificationWrite<void>(careApi.markAllRead);

/** Chỉ đi theo đường dẫn trong ứng dụng; BE luôn gửi path bắt đầu bằng "/". */
export const notificationLink = (n: AppNotification): string | null => (n.linkUrl?.startsWith('/') ? n.linkUrl : null);

// ─── Cài đặt nhận thông báo (#9, #10 — chỉ khách) ───

export function useNotificationSettings() {
  const account = useAccount();
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: careApi.getSettings, enabled: account?.role === 'CUSTOMER' });
}

export function useSaveNotificationSettings() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: careApi.updateSettings, onSuccess: (saved) => queryClient.setQueryData(SETTINGS_KEY, saved) });
}

// ─── Hiển thị ───

export type NotificationTone = 'schedule' | 'cancel' | 'health' | 'stay' | 'work' | 'security';

/** Biểu tượng theo `type` (mã mẫu bỏ `_APP`, 06 §8 Q4); loại chưa biết dùng chuông. */
const KINDS: [test: (type: string) => boolean, icon: LucideIcon, tone: NotificationTone][] = [
  [(t) => t.endsWith('_CANCELLED_BY_CLINIC'), CalendarX, 'cancel'],
  [(t) => t.startsWith('APPOINTMENT_'), CalendarClock, 'schedule'],
  [(t) => t === 'VACCINE_REMINDER', Syringe, 'health'],
  [(t) => t === 'FOLLOW_UP_REMINDER', Stethoscope, 'health'],
  [(t) => t.startsWith('BOARDING_') || t.startsWith('CARE_LOG_'), PawPrint, 'stay'],
  [(t) => t.includes('LOCKED'), ShieldAlert, 'security'],
  [(t) => t.startsWith('STOCK_'), Package, 'work'],
  [(t) => t.startsWith('CASHIER_') || t.startsWith('PENDING_ORDERS'), Receipt, 'work'],
];

export function notificationKind(type: string): { icon: LucideIcon; tone: NotificationTone } {
  const hit = KINDS.find(([test]) => test(type));
  return hit ? { icon: hit[1], tone: hit[2] } : { icon: Bell, tone: 'work' };
}

const DATE_TIME = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** "Vừa xong", "5 phút trước", "3 ngày trước"; quá 7 ngày thì ngày giờ đầy đủ. */
export function notificationTime(iso: string, now = Date.now()): string {
  const at = Date.parse(iso);
  const ms = now - at;
  if (ms < 60_000) return 'Vừa xong';
  if (ms < 7 * 24 * 3_600_000) return formatDistanceStrict(at, now, { addSuffix: true, locale: vi });
  return DATE_TIME.format(at);
}

/** Ngày giờ đầy đủ cho `title` / `dateTime`. */
export const notificationFullTime = (iso: string) => DATE_TIME.format(Date.parse(iso));
