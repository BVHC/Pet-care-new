import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Calendar,
  CheckCircle,
  ChevronRight,
  Clock,
  DollarSign,
  Package,
  ShoppingBag,
  Truck,
  Upload,
  X,
  XCircle,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './OrderHistoryPage.module.css'

/* ================================================================
   Refund modal.
   ================================================================ */

function RefundModal({ order, onClose }: { order: Order; onClose: () => void }) {
  const [reason, setReason] = useState('')
  const [images, setImages] = useState<string[]>([])
  const [submitted, setSubmitted] = useState(false)

  const reasons = [
    'Sản phẩm không đúng mô tả',
    'Sản phẩm bị hư hỏng trong vận chuyển',
    'Giao sai sản phẩm',
    'Không nhận được hàng',
    'Khác',
  ]

  const handleImageAdd = () => {
    // Demo: thêm 1 placeholder image
    setImages((prev) => [...prev, `https://picsum.photos/seed/${Date.now()}/200/200`])
  }

  const handleRemoveImage = (idx: number) => {
    setImages((prev) => prev.filter((_, i) => i !== idx))
  }

  const handleSubmit = () => {
    if (!reason) return
    setSubmitted(true)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="w-full max-w-lg overflow-hidden rounded-3xl border border-(--color-border-default) bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]">
        <div className="flex items-center justify-between border-b border-(--color-border-default) px-6 py-4">
          <div className="flex items-center gap-2">
            <DollarSign size={18} className="text-accent" />
            <h2 className="font-friendly text-lg font-extrabold text-(--color-text-primary)">Yêu cầu hoàn tiền</h2>
          </div>
          <button onClick={onClose} className="text-(--color-text-secondary) hover:text-(--color-text-primary)"><X size={20} /></button>
        </div>

        {!submitted ? (
          <div className="p-6 space-y-4">
            {/* Order info */}
            <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3">
              <div className="flex items-center justify-between">
                <span className="text-[12px] font-bold text-(--color-text-secondary)">{order.id}</span>
                <span className="font-friendly text-base font-extrabold text-accent">{vnd.format(order.total)}</span>
              </div>
              <div className="mt-1 text-[11.5px] text-(--color-text-secondary)">
                {order.items.map((i) => i.name).join(', ')}
              </div>
            </div>

            {/* Reason */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-(--color-text-primary)">Lý do hoàn tiền *</label>
              <div className="space-y-1.5">
                {reasons.map((r) => (
                  <label key={r}
                    className={`flex cursor-pointer items-center gap-2.5 rounded-xl border px-3 py-2.5 text-[13px] transition-all ${reason === r ? 'border-(--color-accent) bg-(--color-accent-soft)' : 'border-(--color-border-default) hover:bg-(--color-surface-2)'}`}>
                    <input type="radio" name="refund-reason" value={r} checked={reason === r} onChange={() => setReason(r)}
                      className="accent-(--color-accent)" />
                    <span className={reason === r ? 'font-bold text-accent' : 'text-(--color-text-primary)'}>{r}</span>
                  </label>
                ))}
              </div>
            </div>

            {/* Images */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-(--color-text-primary)">Hình ảnh đính kèm (tùy chọn)</label>
              <div className="flex flex-wrap gap-2">
                {images.map((src, i) => (
                  <div key={i} className="relative h-16 w-16 overflow-hidden rounded-xl border border-(--color-border-default)">
                    <img src={src} alt="" className="h-full w-full object-cover" />
                    <button onClick={() => handleRemoveImage(i)}
                      className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-white">
                      <X size={10} />
                    </button>
                  </div>
                ))}
                {images.length < 3 && (
                  <button onClick={handleImageAdd}
                    className="flex h-16 w-16 items-center justify-center rounded-xl border-2 border-dashed border-(--color-border-default) text-(--color-text-secondary) hover:border-(--color-accent) hover:text-accent">
                    <Upload size={18} />
                  </button>
                )}
              </div>
              <p className="mt-1 text-[11px] text-(--color-text-secondary)">Tối đa 3 hình · JPG, PNG</p>
            </div>

            {/* Policy */}
            <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-[12px] text-blue-800">
              Yêu cầu hoàn tiền sẽ được xử lý trong <strong>3–5 ngày làm việc</strong> kể từ khi xác nhận. Tiền sẽ được hoàn vào phương thức thanh toán ban đầu hoặc ví PetCare. Áp dụng cho đơn hàng trong vòng <strong>30 ngày</strong> kể từ ngày giao hàng.
            </div>

            <div className="flex gap-2">
              <button onClick={onClose} className="flex-1 rounded-xl border border-(--color-border-default) py-2.5 text-[13px] font-bold text-(--color-text-secondary) transition-colors hover:bg-(--color-surface-2)">Hủy</button>
              <button onClick={handleSubmit} disabled={!reason}
                className="flex-1 flex items-center justify-center gap-1.5 rounded-xl bg-accent py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover) disabled:cursor-not-allowed disabled:opacity-50">
                <DollarSign size={14} /> Gửi yêu cầu
              </button>
            </div>
          </div>
        ) : (
          <div className="p-8 text-center">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
              <CheckCircle size={32} className="text-emerald-600" />
            </div>
            <h3 className="font-friendly text-xl font-extrabold text-(--color-text-primary)">Yêu cầu đã được gửi!</h3>
            <p className="mt-2 text-[13px] text-(--color-text-secondary)">
              Yêu cầu hoàn tiền cho đơn {order.id} đã được gửi. PetCare sẽ xử lý trong 3–5 ngày làm việc.
            </p>
            <button onClick={onClose}
              className="mt-6 inline-flex items-center gap-1.5 rounded-full bg-accent px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)">
              Đã hiểu <ChevronRight size={14} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/* ================================================================
   Types.
   ================================================================ */

interface OrderItem {
  name: string
  qty: number
  image?: string
}

interface Order {
  id: string
  date: string
  items: OrderItem[]
  total: number
  status: 'pending' | 'processing' | 'shipped' | 'delivered' | 'cancelled'
}

const MOCK_ORDERS: Order[] = [
  {
    id: 'ORD-20260801',
    date: '2026-08-25',
    items: [
      { name: 'Royal Canin Adult', qty: 2, image: '/imgs/prod1.jpg' },
      { name: 'Interactive Ball', qty: 1, image: '/imgs/prod4.jpg' },
    ],
    total: 1_010_000,
    status: 'delivered',
  },
  {
    id: 'ORD-20260800',
    date: '2026-08-20',
    items: [
      { name: 'Whiskas Salmon', qty: 3, image: '/imgs/prod3.jpg' },
    ],
    total: 360_000,
    status: 'shipped',
  },
  {
    id: 'ORD-20260729',
    date: '2026-08-15',
    items: [
      { name: 'Catnip Toy Mouse', qty: 5, image: '/imgs/prod5.jpg' },
      { name: 'Pate Nekko Premium', qty: 2, image: '/imgs/prod2.jpg' },
    ],
    total: 445_000,
    status: 'delivered',
  },
  {
    id: 'ORD-20260720',
    date: '2026-08-10',
    items: [
      { name: 'Orthopedic Pet Bed', qty: 1, image: '/imgs/hero-dog.png' },
    ],
    total: 890_000,
    status: 'cancelled',
  },
]

/* ================================================================
   Status config.
   ================================================================ */

const STATUS_CONFIG = {
  pending: {
    label: 'Chờ xác nhận',
    icon: Clock,
    cls: styles.statusPending,
  },
  processing: {
    label: 'Đang xử lý',
    icon: Package,
    cls: styles.statusProcessing,
  },
  shipped: {
    label: 'Đang giao',
    icon: Truck,
    cls: styles.statusShipped,
  },
  delivered: {
    label: 'Đã giao',
    icon: CheckCircle,
    cls: styles.statusDelivered,
  },
  cancelled: {
    label: 'Đã hủy',
    icon: XCircle,
    cls: styles.statusCancelled,
  },
} as const

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

/* ================================================================
   Order card.
   ================================================================ */

function OrderCard({ order, onRefund }: { order: Order; onRefund: (o: Order) => void }) {
  const status = STATUS_CONFIG[order.status]
  const StatusIcon = status.icon
  const dateDisplay = new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(order.date + 'T00:00:00'))

  return (
    <div className={styles.orderCard}>
      {/* Header */}
      <div className={styles.orderHeader}>
        <div className={styles.orderMeta}>
          <span className={styles.orderId}>{order.id}</span>
          <span className={styles.orderDate}>
            <Calendar size={12} />
            {dateDisplay}
          </span>
        </div>
        <span className={`${styles.statusBadge} ${status.cls}`}>
          <StatusIcon size={12} />
          {status.label}
        </span>
      </div>

      {/* Items */}
      <div className={styles.orderItems}>
        {order.items.map((item, i) => (
          <div key={i} className={styles.orderItem}>
            {item.image ? (
              <img
                src={item.image}
                alt={item.name}
                className={styles.orderItemImg}
                loading="lazy"
              />
            ) : (
              <span className={styles.orderItemImgFallback}>
                <Package size={18} />
              </span>
            )}
            <span className={styles.orderItemName}>{item.name}</span>
            <span className={styles.orderItemQty}>×{item.qty}</span>
          </div>
        ))}
      </div>

      {/* Footer */}
      <div className={styles.orderFooter}>
        <div className={styles.orderTotal}>
          Tổng cộng:{' '}
          <span className={styles.orderTotalAmount}>
            {vnd.format(order.total)}
          </span>
        </div>
        <div className={styles.orderActions}>
          <Link
            to={`/order/${order.id}`}
            className={`${styles.orderBtn} ${styles.orderBtnSecondary}`}
          >
            Chi tiết
          </Link>
          {order.status === 'delivered' && (
            <Link
              to={`/order/${order.id}/reorder`}
              className={`${styles.orderBtn} ${styles.orderBtnPrimary}`}
            >
              Mua lại
            </Link>
          )}
          {(order.status === 'delivered' || order.status === 'cancelled') && (
            <button
              onClick={() => onRefund(order)}
              className={`${styles.orderBtn} ${styles.orderBtnSecondary} ${styles.orderBtnRefund}`}
            >
              <DollarSign size={12} />
              Hoàn tiền
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

/* ================================================================
   Main page.
   ================================================================ */

export function OrderHistoryPage() {
  const [refundOrder, setRefundOrder] = useState<Order | null>(null)

  const stats = {
    total: MOCK_ORDERS.length,
    delivered: MOCK_ORDERS.filter((o) => o.status === 'delivered').length,
    pending: MOCK_ORDERS.filter(
      (o) => o.status === 'pending' || o.status === 'processing' || o.status === 'shipped'
    ).length,
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* ====== Hero slab ====== */}
      <section className={styles.slab}>
        <img
          src="/imgs/hero-dog-clean.png"
          alt=""
          className={styles.slabBg}
          aria-hidden
          loading="eager"
        />
        <div className={styles.slabOverlay} aria-hidden />
        <div className={styles.slabNoise} aria-hidden />

        <div className={`${styles.slabContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link
              to="/"
              className="underline-offset-4 transition-colors hover:text-white hover:underline"
            >
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Đơn hàng</span>
          </nav>

          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
            Đơn hàng của tôi
          </h1>
        </div>
      </section>

      {/* ====== Content ====== */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          {/* ====== Sidebar ====== */}
          <AccountSidebar active="orders" />

          {/* ====== Main ====== */}
          <div className={styles.main}>
            {/* Stats */}
            <div className={styles.statsStrip}>
              <div className={styles.statCard}>
                <div className={styles.statValue}>{stats.total}</div>
                <div className={styles.statLabel}>Tổng đơn</div>
              </div>
              <div className={styles.statCard}>
                <div className={`${styles.statValue} ${styles.statValueAccent}`}>
                  {stats.delivered}
                </div>
                <div className={styles.statLabel}>Đã giao</div>
              </div>
              <div className={styles.statCard}>
                <div className={styles.statValue}>{stats.pending}</div>
                <div className={styles.statLabel}>Đang xử lý</div>
              </div>
            </div>

            {/* Orders list */}
            {MOCK_ORDERS.length === 0 ? (
              <div className={styles.emptyState}>
                <span className={styles.emptyIcon}>
                  <Package size={36} />
                </span>
                <p className={styles.emptyTitle}>Chưa có đơn hàng nào</p>
                <p className={styles.emptyDesc}>
                  Các đơn hàng của bạn sẽ xuất hiện ở đây sau khi đặt mua.
                </p>
                <Link to="/shop" className={styles.emptyShopBtn}>
                  <ShoppingBag size={15} />
                  Khám phá cửa hàng
                </Link>
              </div>
            ) : (
              MOCK_ORDERS.map((order) => (
                <OrderCard key={order.id} order={order} onRefund={setRefundOrder} />
              ))
            )}
          </div>
        </div>
      </div>

      {refundOrder && <RefundModal order={refundOrder} onClose={() => setRefundOrder(null)} />}
    </div>
  )
}
