// Đơn hàng online của tôi (UC64, shop-v1 #8): mới nhất trước, lọc theo trạng thái, phân trang từ BE. Hủy, xác nhận đã nhận, thanh toán lại làm ở chi tiết đơn (/order/:id).
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Calendar, ChevronLeft, ChevronRight, Package, ShoppingBag } from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { OrderStatusBadge } from '@/components/customer/shop/OrderStatusBadge'
import { EmptyPanel, ErrorPanel, LoadingBlock } from '@/components/customer/shop/panels'
import { SELECT, formatDateTime, vnd } from '@/components/customer/shop/styles'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { useMyOnlineOrders } from '@/shared/hooks/useShop'
import { ONLINE_ORDER_STATUSES, ONLINE_ORDER_STATUS_LABELS, type OnlineOrderStatus, type OnlineOrderSummary } from '@/shared/types/shop'
import styles from './OrderHistoryPage.module.css'

const PAGE_SIZE = 10

function OrderCard({ order }: { order: OnlineOrderSummary }) {
  const pay = order.status === 'PENDING_PAYMENT'
  return (
    <article className={styles.card} aria-label={`Đơn ${order.code}`}>
      <div className={styles.head}>
        <div className="flex flex-wrap items-center gap-4">
          <span className={styles.code}>{order.code}</span>
          <span className={`${styles.meta} inline-flex items-center gap-1.5`}>
            <Calendar size={12} aria-hidden />
            {formatDateTime(order.createdAt)}
          </span>
        </div>
        <OrderStatusBadge status={order.status} />
      </div>
      <p className={styles.body}>
        {order.itemCount} sản phẩm · xử lý tại {order.branchName}
      </p>
      <div className={styles.foot}>
        <div className={styles.total}>
          Tổng cộng: <span className={styles.totalAmount}>{vnd(order.total)}</span>
        </div>
        <div className={styles.actions}>
          <Link to={`/order/${order.onlineOrderId}`} className={`${styles.btn} ${pay ? styles.btnPrimary : styles.btnSecondary}`}>
            {pay ? 'Thanh toán' : 'Chi tiết'}
          </Link>
        </div>
      </div>
    </article>
  )
}

export function OrderHistoryPage() {
  const [status, setStatus] = useState<OnlineOrderStatus | ''>('')
  const [page, setPage] = useState(0)
  const orders = useMyOnlineOrders({ ...(status ? { status } : {}), page, size: PAGE_SIZE })
  const data = orders.data

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero breadcrumbs={[{ label: 'Đơn hàng' }]} title="Đơn hàng của tôi" subtitle="Theo dõi đơn đặt online: chờ thanh toán, đang giao, đã nhận và đã hủy." />

      <div className="mx-auto max-w-[1280px] px-8 pt-8">
        <div className="grid gap-8 lg:grid-cols-[260px_minmax(0,1fr)]">
          <AccountSidebar active="orders" />

          <div className="min-w-0 space-y-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <label htmlFor="order-status" className="text-[13px] font-bold">
                  Trạng thái
                </label>
                <select
                  id="order-status"
                  value={status}
                  onChange={(e) => {
                    setStatus(e.target.value as OnlineOrderStatus | '')
                    setPage(0)
                  }}
                  className={`${SELECT} w-56`}
                >
                  <option value="">Tất cả</option>
                  {ONLINE_ORDER_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {ONLINE_ORDER_STATUS_LABELS[s]}
                    </option>
                  ))}
                </select>
              </div>
              {data && <span className="text-[13px] text-(--color-text-secondary)">{data.totalElements} đơn</span>}
            </div>

            {orders.isError ? (
              <ErrorPanel title="Không tải được danh sách đơn hàng" error={orders.error} onRetry={() => void orders.refetch()} retrying={orders.isFetching} />
            ) : !data ? (
              <LoadingBlock label="Đang tải đơn hàng" />
            ) : data.content.length === 0 ? (
              <EmptyPanel icon={status ? <Package size={26} aria-hidden /> : <ShoppingBag size={26} aria-hidden />} title={status ? 'Không có đơn nào ở trạng thái này' : 'Chưa có đơn hàng nào'} action={status ? undefined : { to: '/shop', label: 'Khám phá cửa hàng' }}>
                {status ? 'Thử chọn trạng thái khác.' : 'Các đơn bạn đặt online sẽ xuất hiện ở đây.'}
              </EmptyPanel>
            ) : (
              <div aria-busy={orders.isPlaceholderData} className={`space-y-4 ${orders.isPlaceholderData ? 'opacity-60' : ''}`}>
                {data.content.map((o) => (
                  <OrderCard key={o.onlineOrderId} order={o} />
                ))}
                {data.totalPages > 1 && (
                  <nav aria-label="Phân trang" className="flex items-center justify-center gap-3 pt-2 text-[13px]">
                    <button type="button" onClick={() => setPage(page - 1)} disabled={page === 0} aria-label="Trang trước" className="grid h-9 w-9 place-items-center rounded-full bg-white ring-1 ring-(--color-border-default) disabled:opacity-35">
                      <ChevronLeft size={16} aria-hidden />
                    </button>
                    <span className="tabular-nums">
                      Trang {page + 1} / {data.totalPages}
                    </span>
                    <button type="button" onClick={() => setPage(page + 1)} disabled={page + 1 >= data.totalPages} aria-label="Trang sau" className="grid h-9 w-9 place-items-center rounded-full bg-white ring-1 ring-(--color-border-default) disabled:opacity-35">
                      <ChevronRight size={16} aria-hidden />
                    </button>
                  </nav>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
