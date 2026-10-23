import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bell, CheckCheck, X } from 'lucide-react'
import { useAccount } from '../../stores/session.store'
import type { AppNotification } from '../../types/care'
import {
  notificationCenterPath,
  notificationFullTime,
  notificationKind,
  notificationLink,
  notificationTime,
  useLatestNotifications,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useUnreadCount,
  type NotificationTone,
} from '../../hooks/useNotifications'

/* ================================================================
   Chuông thông báo trên header công khai (UC88): huy hiệu số chưa đọc, 5 thông báo mới nhất.
   Khách → /notifications; nhân viên đang xem trang công khai → /admin/notifications.
   Không hiện khi chưa đăng nhập hoặc role không có trang Thông báo (notificationCenterPath).
   ================================================================ */

const TONE_CLASS: Record<NotificationTone, string> = {
  schedule: 'text-blue-700 bg-blue-100',
  cancel: 'text-red-700 bg-red-100',
  health: 'text-green-700 bg-green-100',
  stay: 'text-amber-700 bg-amber-100',
  work: 'text-gray-700 bg-gray-100',
  security: 'text-purple-700 bg-purple-100',
}

export function NotificationBell() {
  const account = useAccount()
  const centerPath = account ? notificationCenterPath(account.role) : null
  return centerPath ? <BellMenu centerPath={centerPath} /> : null
}

function BellMenu({ centerPath }: { centerPath: string }) {
  const navigate = useNavigate()
  const panelId = useId()
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const unread = useUnreadCount().data ?? 0
  const latest = useLatestNotifications(open)
  const markRead = useMarkNotificationRead()
  const markAllRead = useMarkAllNotificationsRead()

  // Đóng khi bấm ra ngoài, cuộn trang hoặc nhấn Esc.
  useEffect(() => {
    if (!open) return
    const onPointer = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    const onScroll = () => setOpen(false)
    document.addEventListener('mousedown', onPointer)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      document.removeEventListener('mousedown', onPointer)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', onScroll)
    }
  }, [open])

  const go = (path: string) => {
    setOpen(false)
    navigate(path)
  }

  // Bấm một thông báo: đánh dấu đã đọc rồi mở liên kết; không có liên kết thì mở trang Thông báo để đọc đủ nội dung.
  const openItem = (n: AppNotification) => {
    if (!n.readAt) markRead.mutate(n.notificationId)
    go(notificationLink(n) ?? centerPath)
  }

  const items = latest.data ?? []

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={unread > 0 ? `Thông báo, ${unread} chưa đọc` : 'Thông báo'}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        className="relative flex h-10 w-10 cursor-pointer items-center justify-center rounded-full text-[#3B2A1E] transition-colors hover:bg-[#FDF6EC] hover:text-accent"
      >
        <Bell size={20} strokeWidth={2.2} />
        {unread > 0 && (
          <span
            aria-hidden
            className="absolute -right-0.5 -top-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-[#dc2626] px-1 text-[10px] font-bold leading-none text-white ring-2 ring-white"
          >
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          id={panelId}
          role="dialog"
          aria-label="Thông báo mới nhất"
          className="absolute right-0 top-[calc(100%+8px)] z-[100] w-[360px] max-w-[calc(100vw-24px)] overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.35)]"
        >
          <div className="flex items-center justify-between gap-2 border-b border-(--color-border-default) px-4 py-3">
            <div>
              <div className="text-[14px] font-bold text-(--color-text-primary)">Thông báo</div>
              <div className="text-[11.5px] text-(--color-text-secondary)">
                {unread > 0 ? `${unread} chưa đọc` : 'Không có thông báo chưa đọc'}
              </div>
            </div>
            <div className="flex items-center gap-1">
              {unread > 0 && (
                <button
                  type="button"
                  onClick={() => markAllRead.mutate()}
                  disabled={markAllRead.isPending}
                  className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-[11.5px] font-bold text-accent hover:bg-(--color-accent-soft) disabled:opacity-50"
                >
                  <CheckCheck size={13} /> Đánh dấu tất cả đã đọc
                </button>
              )}
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label="Đóng"
                className="rounded-md p-1 text-(--color-text-secondary) hover:bg-(--color-surface-2)"
              >
                <X size={14} />
              </button>
            </div>
          </div>

          <div className="max-h-[400px] overflow-y-auto">
            {latest.isPending ? (
              <p className="px-4 py-10 text-center text-[13px] text-(--color-text-secondary)">Đang tải…</p>
            ) : latest.isError ? (
              <div role="alert" className="px-4 py-8 text-center text-[13px] text-(--color-text-secondary)">
                Không tải được thông báo.{' '}
                <button type="button" onClick={() => latest.refetch()} className="font-bold text-accent hover:underline">
                  Thử lại
                </button>
              </div>
            ) : items.length === 0 ? (
              <p className="px-4 py-10 text-center text-[13px] text-(--color-text-secondary)">Chưa có thông báo nào.</p>
            ) : (
              <ul aria-label="Thông báo mới nhất">
                {items.map((n) => {
                  const { icon: Icon, tone } = notificationKind(n.type)
                  return (
                    <li key={n.notificationId}>
                      <button
                        type="button"
                        onClick={() => openItem(n)}
                        className={`flex w-full items-start gap-3 border-b border-(--color-border-default) px-4 py-3 text-left transition-colors hover:bg-(--color-surface-2) ${
                          n.readAt ? '' : 'bg-(--color-accent-soft)/40'
                        }`}
                      >
                        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${TONE_CLASS[tone]}`}>
                          <Icon size={16} aria-hidden />
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="flex items-center justify-between gap-2">
                            <span className="truncate text-[13px] font-bold text-(--color-text-primary)">{n.title}</span>
                            {!n.readAt && <span className="h-2 w-2 shrink-0 rounded-full bg-accent" aria-label="Chưa đọc" />}
                          </span>
                          <span className="mt-0.5 line-clamp-2 block text-[12px] text-(--color-text-secondary)">{n.body}</span>
                          <time
                            dateTime={n.createdAt}
                            title={notificationFullTime(n.createdAt)}
                            className="mt-1 block text-[10.5px] text-(--color-text-secondary)"
                          >
                            {notificationTime(n.createdAt)}
                          </time>
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>

          <button
            type="button"
            onClick={() => go(centerPath)}
            className="block w-full border-t border-(--color-border-default) bg-(--color-surface-2) py-2.5 text-center text-[12.5px] font-bold text-accent transition-colors hover:bg-(--color-accent-soft)"
          >
            Xem tất cả
          </button>
        </div>
      )}
    </div>
  )
}
