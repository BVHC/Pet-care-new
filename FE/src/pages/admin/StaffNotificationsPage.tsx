import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Check, CheckCheck } from 'lucide-react';
import { QueryError, RowsSkeleton, StatePanel } from '../../components/staff/ui';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { cn } from '../../lib/utils';
import { useNow } from '../../shared/hooks/useNow';
import {
  notificationFullTime,
  notificationKind,
  notificationLink,
  notificationTime,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationFeed,
  useUnreadCount,
  type NotificationTone,
} from '../../shared/hooks/useNotifications';
import type { AppNotification } from '../../shared/types/care';

const TONE_CLASS: Record<NotificationTone, string> = {
  schedule: 'bg-(--ws-busy-bg) text-(--ws-busy-ink)',
  cancel: 'bg-(--ws-urgent-bg) text-(--ws-urgent-ink)',
  health: 'bg-(--ws-ready-bg) text-(--ws-ready-ink)',
  stay: 'bg-(--ws-wait-bg) text-(--ws-wait-ink)',
  work: 'bg-(--ws-done-bg) text-(--ws-done-ink)',
  security: 'bg-(--ws-accent-soft) text-(--ws-accent-ink)',
};

type Tab = 'all' | 'unread';

/** T34 — trung tâm thông báo của nhân viên (UC88, care-v1 #5–8); mở từ chuông trên khung nhân viên. */
export function StaffNotificationsPage() {
  const [tab, setTab] = useState<Tab>('all');
  const navigate = useNavigate();
  const now = useNow(60_000);
  const feed = useNotificationFeed(tab === 'unread');
  const unread = useUnreadCount().data ?? 0;
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllNotificationsRead();

  const open = (n: AppNotification) => {
    if (!n.readAt) markRead.mutate(n.notificationId);
    const link = notificationLink(n);
    if (link) navigate(link);
  };

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Thông báo</h1>
        <p className="text-(--text-secondary)">
          Thông báo hệ thống gửi cho bạn: việc dở dang cần xử lý, ca thu ngân, Order chờ thu tiền… Mới nhất ở trên cùng.
        </p>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs value={tab} onValueChange={(value) => setTab(value as Tab)}>
          <TabsList aria-label="Lọc thông báo">
            <TabsTrigger value="all">Tất cả</TabsTrigger>
            <TabsTrigger value="unread">{unread > 0 ? `Chưa đọc (${unread})` : 'Chưa đọc'}</TabsTrigger>
          </TabsList>
        </Tabs>
        <Button variant="outline" size="sm" onClick={() => markAllRead.mutate()} disabled={unread === 0 || markAllRead.isPending}>
          <CheckCheck className="h-4 w-4" aria-hidden />
          Đánh dấu tất cả đã đọc
        </Button>
      </div>

      <Card className="card">
        <CardContent className="overflow-hidden p-0">
          {feed.isPending ? (
            <RowsSkeleton rows={5} />
          ) : feed.isError && feed.items.length === 0 ? (
            <QueryError error={feed.error} onRetry={() => feed.refetch()} what="thông báo" />
          ) : feed.items.length === 0 ? (
            <StatePanel
              icon={Bell}
              title={tab === 'unread' ? 'Bạn đã đọc hết thông báo' : 'Chưa có thông báo nào'}
              body="Thông báo về việc cần xử lý ở chi nhánh sẽ xuất hiện ở đây."
            />
          ) : (
            <ul aria-label="Danh sách thông báo" className="divide-y divide-(--ws-line)">
              {feed.items.map((n) => {
                const { icon: Icon, tone } = notificationKind(n.type);
                const actionable = !n.readAt || notificationLink(n) !== null;
                const content = (
                  <>
                    <span className={cn('mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-lg', TONE_CLASS[tone])}>
                      <Icon className="h-[18px] w-[18px]" aria-hidden />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        {!n.readAt && <span className="h-2 w-2 shrink-0 rounded-full bg-(--ws-accent)" aria-label="Chưa đọc" />}
                        <span className={cn('text-sm text-(--ws-ink)', n.readAt ? 'font-medium' : 'font-semibold')}>{n.title}</span>
                      </span>
                      <span className="mt-1 block whitespace-pre-line text-[13px] leading-relaxed text-(--ws-ink-2)">{n.body}</span>
                      <time dateTime={n.createdAt} title={notificationFullTime(n.createdAt)} className="mt-1.5 block text-xs text-(--ws-ink-3)">
                        {notificationTime(n.createdAt, now)}
                      </time>
                    </span>
                  </>
                );
                return (
                  <li key={n.notificationId} className={cn('flex items-start gap-2 pr-3', !n.readAt && 'bg-(--ws-raised)')}>
                    {actionable ? (
                      <button type="button" onClick={() => open(n)} className="flex min-w-0 flex-1 gap-3 px-4 py-3.5 text-left hover:bg-(--ws-raised)">
                        {content}
                      </button>
                    ) : (
                      <div className="flex min-w-0 flex-1 gap-3 px-4 py-3.5">{content}</div>
                    )}
                    {!n.readAt && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="mt-2.5 h-8 w-8 shrink-0"
                        onClick={() => markRead.mutate(n.notificationId)}
                        aria-label={`Đánh dấu đã đọc: ${n.title}`}
                        title="Đánh dấu đã đọc"
                      >
                        <Check className="h-4 w-4" aria-hidden />
                      </Button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {feed.hasNextPage && (
            <div className="flex justify-center border-t border-(--ws-line) px-4 py-3">
              <Button variant="outline" size="sm" onClick={() => feed.fetchNextPage()} disabled={feed.isFetchingNextPage}>
                {feed.isFetchingNextPage ? 'Đang tải…' : 'Xem thêm'}
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
