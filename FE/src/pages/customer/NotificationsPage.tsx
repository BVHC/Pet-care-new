import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AlertTriangle, Bell, Check, CheckCheck } from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { useNow } from '@/shared/hooks/useNow'
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
} from '@/shared/hooks/useNotifications'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import type { AppNotification } from '@/shared/types/care'
import styles from './NotificationsPage.module.css'

/* ================================================================
   T34 — Thông báo của tôi (UC88, care-v1 #5–8). Tab Tất cả / Chưa đọc là tham số `unreadOnly`;
   BE không có loại / nhóm để lọc nên bỏ các tab Đơn hàng, Khuyến mãi… của bản cũ.
   ================================================================ */

const TONE_CLASS: Record<NotificationTone, string> = {
  schedule: styles.toneSchedule,
  cancel: styles.toneCancel,
  health: styles.toneHealth,
  stay: styles.toneStay,
  work: styles.toneWork,
  security: styles.toneSecurity,
}

type Tab = 'all' | 'unread'

export function NotificationsPage() {
  const [tab, setTab] = useState<Tab>('all')
  const navigate = useNavigate()
  const now = useNow(60_000)
  const feed = useNotificationFeed(tab === 'unread')
  const unread = useUnreadCount().data ?? 0
  const markRead = useMarkNotificationRead()
  const markAllRead = useMarkAllNotificationsRead()

  // Bấm thông báo: đánh dấu đã đọc, có liên kết thì mở.
  const open = (n: AppNotification) => {
    if (!n.readAt) markRead.mutate(n.notificationId)
    const link = notificationLink(n)
    if (link) navigate(link)
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* hero slab */}
      <section className={styles.slab}>
        <img src="/imgs/hero-dog-clean.png" alt="" className={styles.slabBg} aria-hidden loading="eager" />
        <div className={styles.slabOverlay} aria-hidden />
        <div className={styles.slabNoise} aria-hidden />

        <div className={`${styles.slabContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="hover:text-white hover:underline underline-offset-4 transition-colors">
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Thông báo</span>
          </nav>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
              Thông báo
            </h1>
            {unread > 0 && (
              <span className="rounded-full bg-white/20 px-3 py-1 text-[12px] font-bold text-white backdrop-blur-sm">
                {unread} chưa đọc
              </span>
            )}
          </div>
        </div>
      </section>

      {/* content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar active="notifications" />

          <div className={styles.main}>
            <div className={styles.toolbar}>
              <div role="tablist" aria-label="Lọc thông báo" className={styles.filterTabs}>
                {([
                  ['all', 'Tất cả'],
                  ['unread', unread > 0 ? `Chưa đọc (${unread})` : 'Chưa đọc'],
                ] as const).map(([key, label]) => (
                  <button
                    key={key}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => setTab(key)}
                    className={`${styles.filterTab} ${tab === key ? styles.filterTabActive : ''}`}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <button
                type="button"
                className={styles.markAll}
                onClick={() => markAllRead.mutate()}
                disabled={unread === 0 || markAllRead.isPending}
              >
                <CheckCheck size={16} aria-hidden />
                Đánh dấu tất cả đã đọc
              </button>
            </div>

            {feed.isPending ? (
              <div role="status" aria-label="Đang tải" className={styles.list}>
                {[0, 1, 2].map((i) => (
                  <div key={i} className={`${styles.notifCard} ${styles.skeleton}`} />
                ))}
              </div>
            ) : feed.isError && feed.items.length === 0 ? (
              <div role="alert" className={styles.emptyState}>
                <span className={`${styles.emptyIcon} ${styles.emptyIconError}`}>
                  <AlertTriangle size={32} />
                </span>
                <p className={styles.emptyTitle}>Không tải được thông báo</p>
                <p className={styles.emptyDesc}>{plainMessage(toApiError(feed.error))}</p>
                <button type="button" className={styles.loadMore} onClick={() => feed.refetch()}>
                  Thử lại
                </button>
              </div>
            ) : feed.items.length === 0 ? (
              <div className={styles.emptyState}>
                <span className={styles.emptyIcon}>
                  <Bell size={36} />
                </span>
                <p className={styles.emptyTitle}>{tab === 'unread' ? 'Bạn đã đọc hết thông báo' : 'Chưa có thông báo nào'}</p>
                <p className={styles.emptyDesc}>Nhắc lịch hẹn, tái chủng, tái khám và tin từ phòng khám sẽ xuất hiện ở đây.</p>
              </div>
            ) : (
              <>
                <ul aria-label="Danh sách thông báo" className={styles.list}>
                  {feed.items.map((n) => {
                    const { icon: Icon, tone } = notificationKind(n.type)
                    const actionable = !n.readAt || notificationLink(n) !== null
                    const content = (
                      <>
                        <span className={`${styles.notifIconWrap} ${TONE_CLASS[tone]}`}>
                          <Icon size={20} aria-hidden />
                        </span>
                        <span className={styles.notifContent}>
                          <span className={styles.notifTitle}>{n.title}</span>
                          <span className={styles.notifBody}>{n.body}</span>
                          <time dateTime={n.createdAt} title={notificationFullTime(n.createdAt)} className={styles.notifTime}>
                            {notificationTime(n.createdAt, now)}
                          </time>
                        </span>
                      </>
                    )
                    return (
                      <li key={n.notificationId} className={`${styles.notifCard} ${n.readAt ? '' : styles.notifCardUnread}`}>
                        {actionable ? (
                          <button type="button" className={styles.notifMain} onClick={() => open(n)}>
                            {content}
                          </button>
                        ) : (
                          <div className={styles.notifMain}>{content}</div>
                        )}
                        {!n.readAt && (
                          <button
                            type="button"
                            className={styles.markOne}
                            onClick={() => markRead.mutate(n.notificationId)}
                            aria-label={`Đánh dấu đã đọc: ${n.title}`}
                            title="Đánh dấu đã đọc"
                          >
                            <Check size={16} aria-hidden />
                          </button>
                        )}
                      </li>
                    )
                  })}
                </ul>
                {feed.hasNextPage && (
                  <button
                    type="button"
                    className={styles.loadMore}
                    onClick={() => feed.fetchNextPage()}
                    disabled={feed.isFetchingNextPage}
                  >
                    {feed.isFetchingNextPage ? 'Đang tải…' : 'Xem thêm'}
                  </button>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
