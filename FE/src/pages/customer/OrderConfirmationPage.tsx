// Chi tiết đơn online của tôi (UC64, shop-v1 #9–11; payment-v1 #1): theo dõi, thanh toán lại, hủy, xác nhận đã nhận. Nút theo trạng thái đơn (03 #14).
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { CircleCheck, CreditCard, PackageSearch, Truck } from 'lucide-react'
import { toast } from 'sonner'
import { CancelOrderDialog } from '@/components/customer/shop/CancelOrderDialog'
import { OrderStatusBadge } from '@/components/customer/shop/OrderStatusBadge'
import { OrderTimeline } from '@/components/customer/shop/OrderTimeline'
import { EmptyPanel, ErrorPanel, InlineError, LoadingBlock } from '@/components/customer/shop/panels'
import { BTN_DANGER_OUTLINE, BTN_PRIMARY, CARD, H2, formatDateTime, vnd } from '@/components/customer/shop/styles'
import { usePayOrder } from '@/components/customer/shop/usePayOrder'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import { shopApi } from '@/shared/api/shop.api'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { useMyOnlineOrder, useShopMutation } from '@/shared/hooks/useShop'
import type { OnlineOrder } from '@/shared/types/shop'

const CANCELLABLE = ['PENDING_PAYMENT', 'PAID', 'PROCESSING']

function Lines({ order }: { order: OnlineOrder }) {
  return (
    <section aria-labelledby="order-lines" className={`${CARD} p-5`}>
      <h2 id="order-lines" className={H2}>
        Sản phẩm
      </h2>
      <ul className="mt-3 divide-y divide-(--color-border-default)">
        {order.lines.map((l) => (
          <li key={l.productId} className="flex items-baseline justify-between gap-4 py-2.5 text-[14px]">
            <span className="min-w-0">
              <span className="font-semibold">{l.name}</span>
              <span className="ml-2 text-(--color-text-secondary)">
                {vnd(l.unitPrice)} × {l.quantity} {l.unit}
              </span>
            </span>
            <span className="font-bold tabular-nums">{vnd(l.lineTotal)}</span>
          </li>
        ))}
      </ul>
      <dl className="mt-3 space-y-1.5 border-t border-(--color-border-default) pt-3 text-[14px]">
        <div className="flex justify-between">
          <dt className="text-(--color-text-secondary)">Tạm tính</dt>
          <dd className="tabular-nums">{vnd(order.subtotal)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-(--color-text-secondary)">Phí vận chuyển</dt>
          <dd className="tabular-nums">{order.shippingFee === 0 ? 'Miễn phí' : vnd(order.shippingFee)}</dd>
        </div>
        <div className="flex justify-between font-friendly text-[18px] font-extrabold">
          <dt>Tổng cộng</dt>
          <dd className="text-accent tabular-nums">{vnd(order.total)}</dd>
        </div>
      </dl>
    </section>
  )
}

function Delivery({ order }: { order: OnlineOrder }) {
  return (
    <section aria-labelledby="order-delivery" className={`${CARD} space-y-3 p-5 text-[14px]`}>
      <h2 id="order-delivery" className={H2}>
        Giao hàng
      </h2>
      <p>
        <span className="font-semibold">{order.receiverName}</span>, {order.receiverPhone}
      </p>
      <p className="text-(--color-text-secondary)">{order.shipAddress}</p>
      <p className="text-(--color-text-secondary)">Chi nhánh xử lý: {order.branchName}</p>
      {order.carrier && (
        <p className="flex items-center gap-2">
          <Truck size={15} aria-hidden className="text-accent" />
          {order.carrier}
          {order.trackingCode && <span className="font-mono font-bold">{order.trackingCode}</span>}
        </p>
      )}
    </section>
  )
}

/** Dòng hướng dẫn theo trạng thái; không nêu lý do giao thất bại vì hợp đồng chưa có trường đó. */
function statusNote(order: OnlineOrder): string | null {
  switch (order.status) {
    case 'PENDING_PAYMENT':
      return `Đơn được giữ hàng đến ${formatDateTime(order.paymentDueAt)}, quá hạn sẽ tự hủy.`
    case 'SHIPPED':
      return 'Đơn đang trên đường giao. Từ lúc bàn giao vận chuyển, bạn không tự hủy được đơn.'
    case 'DELIVERY_FAILED':
      return `Giao hàng chưa thành công${order.redeliveryCount > 0 ? ` (đã giao lại ${order.redeliveryCount} lần)` : ''}. Chi nhánh xử lý sẽ giao lại hoặc hủy đơn và hoàn tiền cho bạn.`
    case 'DELIVERED':
      return 'Hãy kiểm tra hàng và xác nhận đã nhận. Nếu hàng có vấn đề, liên hệ chi nhánh để được hỗ trợ trả hàng.'
    default:
      return null
  }
}

function Actions({ order }: { order: OnlineOrder }) {
  const [cancelOpen, setCancelOpen] = useState(false)
  const [error, setError] = useState('')
  const { pay, isPending: paying } = usePayOrder()
  const confirm = useShopMutation(() => shopApi.confirmReceived(order.onlineOrderId))
  const note = statusNote(order)
  const canCancel = CANCELLABLE.includes(order.status)

  const onPay = () => {
    setError('')
    pay(order.onlineOrderId).catch((e) => setError(plainMessage(toApiError(e))))
  }
  const onConfirm = () => {
    setError('')
    confirm.mutate(undefined, { onSuccess: () => toast.success('Đã xác nhận nhận hàng'), onError: (e) => setError(plainMessage(toApiError(e))) })
  }

  // Lỗi 409 của lệnh vừa bấm phải còn hiện dù đơn đã sang trạng thái cuối (BE từ chối vì đơn vừa đổi, dữ liệu được tải lại trước khi báo lỗi).
  const showSection = Boolean(note) || canCancel || error !== ''
  return (
    <>
      {showSection && (
        <section aria-label="Thao tác với đơn" className={`${CARD} space-y-3 p-5`}>
          {note && <p className="text-[13.5px] text-(--color-text-secondary)">{note}</p>}
          <InlineError message={error} />
          <div className="flex flex-wrap gap-2">
            {order.status === 'PENDING_PAYMENT' && (
              <button type="button" onClick={onPay} disabled={paying} className={BTN_PRIMARY}>
                <CreditCard size={16} aria-hidden />
                {paying ? 'Đang chuyển sang cổng…' : 'Thanh toán'}
              </button>
            )}
            {order.status === 'DELIVERED' && (
              <button type="button" onClick={onConfirm} disabled={confirm.isPending} className={BTN_PRIMARY}>
                <CircleCheck size={16} aria-hidden />
                Xác nhận đã nhận hàng
              </button>
            )}
            {canCancel && (
              <button type="button" onClick={() => setCancelOpen(true)} className={BTN_DANGER_OUTLINE}>
                Hủy đơn
              </button>
            )}
          </div>
        </section>
      )}
      {/* Giữ hộp thoại đến khi tự đóng: tải lại xong đơn có thể đã hết hủy được, nhưng lỗi / thông báo thành công của lệnh hủy còn phải hiện. */}
      {(canCancel || cancelOpen) && <CancelOrderDialog order={order} open={cancelOpen} onClose={() => setCancelOpen(false)} />}
    </>
  )
}

export function OrderConfirmationPage() {
  const { id } = useParams<{ id: string }>()
  const orderId = id && /^[1-9]\d{0,9}$/.test(id) ? Number(id) : null
  const order = useMyOnlineOrder(orderId)
  const notFound = orderId === null || (order.isError && toApiError(order.error).statusCode === 404)

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        breadcrumbs={[{ label: 'Đơn hàng', href: '/orders' }, { label: order.data?.code ?? 'Chi tiết đơn' }]}
        title={order.data ? `Đơn ${order.data.code}` : 'Chi tiết đơn hàng'}
        subtitle={order.data ? `Đặt lúc ${formatDateTime(order.data.createdAt)}` : undefined}
      />
      <div className="mx-auto max-w-[1280px] px-8 pt-8">
        {notFound ? (
          <EmptyPanel icon={<PackageSearch size={26} aria-hidden />} title="Không tìm thấy đơn hàng" action={{ to: '/orders', label: 'Về đơn hàng của tôi' }}>
            Đơn này không tồn tại hoặc không thuộc về bạn.
          </EmptyPanel>
        ) : order.isError ? (
          <ErrorPanel title="Không tải được đơn hàng" error={order.error} onRetry={() => void order.refetch()} retrying={order.isFetching} />
        ) : !order.data ? (
          <LoadingBlock label="Đang tải đơn hàng" />
        ) : (
          <div className="grid items-start gap-6 lg:grid-cols-[1fr_340px]">
            <div className="space-y-6">
              <p className="flex items-center gap-3 text-[14px]">
                <span className="text-(--color-text-secondary)">Trạng thái đơn</span>
                <OrderStatusBadge status={order.data.status} />
              </p>
              <Actions order={order.data} />
              <Lines order={order.data} />
              <Delivery order={order.data} />
            </div>
            <aside className={`${CARD} p-5`}>
              <h2 className={`${H2} mb-4`}>Tiến trình</h2>
              <OrderTimeline order={order.data} />
              <Link to="/orders" className="mt-5 inline-block text-[13px] font-semibold text-accent hover:underline">
                Tất cả đơn hàng
              </Link>
            </aside>
          </div>
        )}
      </div>
    </div>
  )
}
