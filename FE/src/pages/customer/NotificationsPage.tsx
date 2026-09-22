import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Bell,
  Package,
  PawPrint,
  Settings,
  Tag,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './NotificationsPage.module.css'

/* ================================================================
   Types.
   ================================================================ */

type NotifType = 'order' | 'promo' | 'system' | 'pet'

interface Notification {
  id: number
  type: NotifType
  title: string
  body: string
  time: string
  unread: boolean
  link?: string
}

const MOCK_NOTIFS: Notification[] = [
  {
    id: 1,
    type: 'order',
    title: 'Đơn hàng ORD-20260800 đã được giao!',
    body: 'Đơn hàng Whiskas Salmon đã được giao đến địa chỉ của bạn. Cảm ơn bạn đã mua sắm tại Pet Care!',
    time: '2 giờ trước',
    unread: true,
    link: '/order/ORD-20260800',
  },
  {
    id: 2,
    type: 'promo',
    title: 'Sale 20% cho thức ăn thú cưng',
    body: 'Chương trình khuyến mãi đặc biệt dành riêng cho bạn — giảm 20% cho tất cả sản phẩm thức ăn. Chỉ áp dụng đến hết tuần này!',
    time: '1 ngày trước',
    unread: true,
  },
  {
    id: 3,
    type: 'pet',
    title: 'Lịch khám sức khỏe cho Milo sắp đến hạn',
    body: 'Milo cần được tiêm phòng định kỳ. Đặt lịch khám ngay hôm nay để giữ sức khỏe tốt nhất cho boss.',
    time: '2 ngày trước',
    unread: false,
    link: '/booking',
  },
  {
    id: 4,
    type: 'order',
    title: 'Đơn hàng ORD-20260801 đã được xác nhận',
    body: 'Cảm ơn bạn đã đặt hàng. Đơn hàng Royal Canin Adult đang được chuẩn bị và sẽ giao trong 2–3 ngày.',
    time: '5 ngày trước',
    unread: false,
    link: '/order/ORD-20260801',
  },
  {
    id: 5,
    type: 'system',
    title: 'Cập nhật chính sách đổi trả mới',
    body: 'Pet Care thông báo cập nhật chính sách đổi trả: thời gian đổi trả được kéo dài lên 30 ngày kể từ ngày nhận hàng.',
    time: '1 tuần trước',
    unread: false,
  },
  {
    id: 6,
    type: 'promo',
    title: 'Quà tặng khi mua đơn từ 500K',
    body: 'Đơn hàng từ 500.000đ sẽ được tặng kèm bộ dụng cụ vệ sinh cho thú cưng. Khuyến mãi có thể kết thúc sớm nếu hết quà.',
    time: '2 tuần trước',
    unread: false,
  },
]

const TYPE_CONFIG = {
  order: {
    icon: Package,
    cls: styles.notifIconOrder,
    label: 'Đơn hàng',
  },
  promo: {
    icon: Tag,
    cls: styles.notifIconPromo,
    label: 'Khuyến mãi',
  },
  system: {
    icon: Settings,
    cls: styles.notifIconSystem,
    label: 'Hệ thống',
  },
  pet: {
    icon: PawPrint,
    cls: styles.notifIconPet,
    label: 'Thú cưng',
  },
} as const

/* ================================================================
   Main.
   ================================================================ */

export function NotificationsPage() {
  const [tab, setTab] = useState<'all' | NotifType>('all')

  const filtered =
    tab === 'all' ? MOCK_NOTIFS : MOCK_NOTIFS.filter((n) => n.type === tab)

  const unreadCount = MOCK_NOTIFS.filter((n) => n.unread).length

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
            {unreadCount > 0 && (
              <span className="rounded-full bg-white/20 px-3 py-1 text-[12px] font-bold text-white backdrop-blur-sm">
                {unreadCount} chưa đọc
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
            {/* filter tabs */}
            <div className={styles.filterTabs}>
              {([
                ['all', 'Tất cả'],
                ['order', 'Đơn hàng'],
                ['promo', 'Khuyến mãi'],
                ['system', 'Hệ thống'],
                ['pet', 'Thú cưng'],
              ] as const).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setTab(key)}
                  className={`${styles.filterTab} ${tab === key ? styles.filterTabActive : ''}`}
                >
                  {label}
                </button>
              ))}
            </div>

            {filtered.length === 0 ? (
              <div className={styles.emptyState}>
                <span className={styles.emptyIcon}>
                  <Bell size={36} />
                </span>
                <p className={styles.emptyTitle}>Không có thông báo nào</p>
                <p className={styles.emptyDesc}>
                  Các thông báo quan trọng sẽ xuất hiện ở đây.
                </p>
              </div>
            ) : (
              filtered.map((notif) => {
                const cfg = TYPE_CONFIG[notif.type]
                const Icon = cfg.icon
                return (
                  <div
                    key={notif.id}
                    className={`${styles.notifCard} ${notif.unread ? styles.notifCardUnread : ''}`}
                  >
                    <div className={`${styles.notifIconWrap} ${cfg.cls}`}>
                      <Icon size={20} />
                    </div>
                    <div className={styles.notifContent}>
                      <div className={styles.notifTitle}>{notif.title}</div>
                      <div className={styles.notifBody}>{notif.body}</div>
                      <div className={styles.notifTime}>{notif.time}</div>
                    </div>
                    {notif.unread && <div className={styles.notifDot} />}
                  </div>
                )
              })
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
