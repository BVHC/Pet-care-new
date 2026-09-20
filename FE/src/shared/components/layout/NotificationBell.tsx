import { useNavigate } from 'react-router-dom'
import { useEffect, useRef, useState } from 'react'
import { Bell, Check, Package, Sparkles, Stethoscope, X } from 'lucide-react'
import { useNotificationsStore } from '../../stores/notifications.store'

/* ================================================================
   Helpers.
   ================================================================ */

const CATEGORY_META = {
  order:       { icon: Package,    color: 'text-amber-700 bg-amber-100' },
  appointment: { icon: Stethoscope,color: 'text-blue-700 bg-blue-100' },
  promo:       { icon: Sparkles,   color: 'text-purple-700 bg-purple-100' },
  system:      { icon: Bell,       color: 'text-gray-700 bg-gray-100' },
} as const

function formatRelative(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const min = Math.floor(ms / 60_000)
  if (min < 1)  return 'Vừa xong'
  if (min < 60) return `${min} phút trước`
  const h = Math.floor(min / 60)
  if (h < 24)   return `${h} giờ trước`
  const d = Math.floor(h / 24)
  if (d < 7)    return `${d} ngày trước`
  return new Date(iso).toLocaleDateString('vi-VN')
}

/* ================================================================
   Main.
   ================================================================ */

export function NotificationBell() {
  const navigate = useNavigate()
  const items = useNotificationsStore((s) => s.items)
  const unread = useNotificationsStore((s) => s.unreadCount())
  const markRead = useNotificationsStore((s) => s.markRead)
  const markAllRead = useNotificationsStore((s) => s.markAllRead)

  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  // Close on outside click or window scroll.
  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    const handleScroll = () => {
      setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => {
      document.removeEventListener('mousedown', handler)
      window.removeEventListener('scroll', handleScroll)
    }
  }, [open])

  // Latest 5 by createdAt desc.
  const recent = [...items]
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .slice(0, 5)

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={`Thông báo${unread > 0 ? ` (${unread} chưa đọc)` : ''}`}
        className="relative flex h-10 w-10 items-center justify-center rounded-full text-[#3B2A1E] transition-colors hover:bg-[#FDF6EC] hover:text-[#a43324] cursor-pointer"
      >
        <Bell size={20} strokeWidth={2.2} />
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-[#dc2626] px-1 text-[10px] font-bold leading-none text-white ring-2 ring-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-[calc(100%+8px)] z-[100] w-[360px] max-w-[calc(100vw-24px)] overflow-hidden rounded-2xl border border-[var(--color-border-default)] bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.35)]">
          {/* Header */}
          <div className="flex items-center justify-between border-b border-[var(--color-border-default)] px-4 py-3">
            <div>
              <div className="text-[14px] font-bold text-[var(--color-text-primary)]">Thông báo</div>
              <div className="text-[11.5px] text-[var(--color-text-secondary)]">
                {unread > 0 ? `${unread} chưa đọc` : 'Tất cả đã đọc'}
              </div>
            </div>
            <div className="flex items-center gap-1">
              {unread > 0 && (
                <button
                  onClick={markAllRead}
                  className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-[11.5px] font-bold text-[var(--color-accent)] hover:bg-[var(--color-accent-soft)]"
                >
                  <Check size={12} /> Đọc hết
                </button>
              )}
              <button
                onClick={() => setOpen(false)}
                aria-label="Đóng"
                className="rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)]"
              >
                <X size={14} />
              </button>
            </div>
          </div>

          {/* List */}
          <div className="max-h-[400px] overflow-y-auto">
            {recent.length === 0 ? (
              <div className="px-4 py-10 text-center text-[13px] text-[var(--color-text-secondary)]">
                Chưa có thông báo nào.
              </div>
            ) : (
              recent.map((n) => {
                const meta = CATEGORY_META[n.category]
                const Icon = meta.icon
                return (
                  <button
                    key={n.id}
                    type="button"
                    onClick={() => {
                      markRead(n.id)
                      setOpen(false)
                      navigate('/notifications')
                    }}
                    className={`flex w-full items-start gap-3 border-b border-[var(--color-border-default)] px-4 py-3 text-left transition-colors hover:bg-[var(--color-surface-2)] ${
                      !n.read ? 'bg-[var(--color-accent-soft)]/40' : ''
                    }`}
                  >
                    <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${meta.color}`}>
                      <Icon size={16} />
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="truncate text-[13px] font-bold text-[var(--color-text-primary)]">
                          {n.title}
                        </span>
                        {!n.read && (
                          <span className="h-2 w-2 shrink-0 rounded-full bg-[var(--color-accent)]" aria-label="Chưa đọc" />
                        )}
                      </div>
                      <p className="mt-0.5 line-clamp-2 text-[12px] text-[var(--color-text-secondary)]">
                        {n.body}
                      </p>
                      <p className="mt-1 text-[10.5px] text-[var(--color-text-secondary)]">
                        {formatRelative(n.createdAt)}
                      </p>
                    </div>
                  </button>
                )
              })
            )}
          </div>

          {/* Footer */}
          <button
            onClick={() => {
              setOpen(false)
              navigate('/notifications')
            }}
            className="block w-full border-t border-[var(--color-border-default)] bg-[var(--color-surface-2)] py-2.5 text-center text-[12.5px] font-bold text-[var(--color-accent)] transition-colors hover:bg-[var(--color-accent-soft)]"
          >
            Xem tất cả thông báo →
          </button>
        </div>
      )}
    </div>
  )
}
