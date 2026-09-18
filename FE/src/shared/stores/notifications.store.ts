import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/* ================================================================
   Types.
   ================================================================ */

export type NotificationCategory = 'order' | 'appointment' | 'promo' | 'system'

export interface NotificationItem {
  id: string
  category: NotificationCategory
  title: string
  body: string
  /** ISO date string. */
  createdAt: string
  read: boolean
}

interface NotificationsState {
  items: NotificationItem[]
  /** Tổng số thông báo CHƯA đọc — selector nhanh cho Bell badge. */
  unreadCount: () => number
  markRead: (id: string) => void
  markAllRead: () => void
  push: (n: Omit<NotificationItem, 'id' | 'createdAt' | 'read'>) => void
  clear: () => void
}

/* ================================================================
   Default seed — cho demo có số trên badge.
   ================================================================ */

const SEED: NotificationItem[] = [
  {
    id: 'n1',
    category: 'order',
    title: 'Đơn hàng ORD-20260800 đang giao',
    body: 'Đơn hàng của bạn đang được giao. Dự kiến nhận hàng trong 1-2 giờ.',
    createdAt: new Date(Date.now() - 1000 * 60 * 30).toISOString(),
    read: false,
  },
  {
    id: 'n2',
    category: 'appointment',
    title: 'Nhắc lịch khám ngày mai',
    body: 'Milo có lịch khám định kỳ vào 10:00 sáng mai tại PetCare Nguyễn Trãi.',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 3).toISOString(),
    read: false,
  },
  {
    id: 'n3',
    category: 'promo',
    title: 'Voucher -20% dịch vụ Spa',
    body: 'Tặng bạn voucher giảm 20% dịch vụ Spa & Grooming. HSD: 30/09/2026.',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24).toISOString(),
    read: false,
  },
  {
    id: 'n4',
    category: 'system',
    title: 'Cập nhật chính sách bảo mật',
    body: 'PetCare đã cập nhật chính sách bảo mật và quyền riêng tư.',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 48).toISOString(),
    read: true,
  },
  {
    id: 'n5',
    category: 'order',
    title: 'Đơn hàng ORD-20260729 đã giao',
    body: 'Bạn đã nhận hàng thành công. Cảm ơn bạn đã mua sắm tại PetCare!',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 96).toISOString(),
    read: true,
  },
]

/* ================================================================
   Store.
   ================================================================ */

export const useNotificationsStore = create<NotificationsState>()(
  persist(
    (set, get) => ({
      items: SEED,

      unreadCount: () => get().items.filter((i) => !i.read).length,

      markRead: (id) => {
        set((state) => ({
          items: state.items.map((i) => (i.id === id ? { ...i, read: true } : i)),
        }))
      },

      markAllRead: () => {
        set((state) => ({
          items: state.items.map((i) => ({ ...i, read: true })),
        }))
      },

      push: (n) => {
        set((state) => ({
          items: [
            {
              ...n,
              id: `n${Date.now()}`,
              createdAt: new Date().toISOString(),
              read: false,
            },
            ...state.items,
          ],
        }))
      },

      clear: () => set({ items: [] }),
    }),
    {
      name: 'petcare-notifications',
    },
  ),
)
