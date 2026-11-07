// Kết quả thanh toán (UC63, payment-v1 #2): khách quay về từ cổng với `?transactionId=`. Trạng thái lấy từ BE, không tin tham số trên URL quay về (A3).
import { Link, useSearchParams } from 'react-router-dom'
import { CircleAlert, CircleCheck, CircleX, Hourglass, ReceiptText, RotateCw } from 'lucide-react'
import { EmptyPanel, ErrorPanel, LoadingBlock } from '@/components/customer/shop/panels'
import { BTN_OUTLINE, BTN_PRIMARY, CARD, formatDateTime, vnd } from '@/components/customer/shop/styles'
import { toApiError } from '@/shared/api/api-error'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { useMyPaymentTransaction } from '@/shared/hooks/usePayment'
import { PAYMENT_STATUS_LABELS, type PaymentTransaction, type PaymentTransactionStatus } from '@/shared/types/payment'
import styles from './PaymentPage.module.css'

const LOOK: Record<PaymentTransactionStatus, { icon: typeof CircleCheck; ring: string }> = {
  SUCCEEDED: { icon: CircleCheck, ring: styles.ringSuccess },
  PENDING: { icon: Hourglass, ring: styles.ringWait },
  FAILED: { icon: CircleX, ring: styles.ringFail },
  EXPIRED: { icon: CircleAlert, ring: styles.ringFail },
  CANCELLED: { icon: CircleX, ring: styles.ringFail },
}

function Result({ txn, refreshing, onRefresh }: { txn: PaymentTransaction; refreshing: boolean; onRefresh: () => void }) {
  const { icon: Icon, ring } = LOOK[txn.status]
  const orderLink = `/order/${txn.onlineOrderId}`
  return (
    <section aria-label="Kết quả thanh toán" className={`${CARD} mx-auto max-w-[560px] space-y-5 p-8 text-center`}>
      <span className={`${styles.ring} ${ring}`}>
        <Icon size={34} aria-hidden />
      </span>
      <h2 className="font-friendly text-[24px] font-extrabold">{PAYMENT_STATUS_LABELS[txn.status]}</h2>

      <dl className="mx-auto grid max-w-[360px] grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-left text-[14px]">
        <dt className="text-(--color-text-secondary)">Số tiền</dt>
        <dd className="text-right font-bold tabular-nums">{vnd(txn.amount)}</dd>
        {txn.status === 'SUCCEEDED' && txn.paidAt && (
          <>
            <dt className="text-(--color-text-secondary)">Thanh toán lúc</dt>
            <dd className="text-right font-bold">{formatDateTime(txn.paidAt)}</dd>
          </>
        )}
        {txn.status === 'PENDING' && (
          <>
            <dt className="text-(--color-text-secondary)">Giao dịch hết hạn</dt>
            <dd className="text-right font-bold">{formatDateTime(txn.expiresAt)}</dd>
          </>
        )}
      </dl>

      {txn.status === 'PENDING' && <p className="text-[13.5px] text-(--color-text-secondary)">Hệ thống chưa nhận được kết quả từ cổng thanh toán. Bạn có thể kiểm tra lại sau ít phút.</p>}
      {txn.failureReason && txn.status !== 'SUCCEEDED' && <p className="rounded-xl bg-red-50 px-3 py-2 text-[13.5px] font-medium text-red-700">{txn.failureReason}</p>}
      {(txn.status === 'FAILED' || txn.status === 'EXPIRED' || txn.status === 'CANCELLED') && (
        <p className="text-[13.5px] text-(--color-text-secondary)">Đơn hàng vẫn được giữ trong thời hạn thanh toán, bạn có thể thanh toán lại từ chi tiết đơn.</p>
      )}

      <div className="flex flex-col justify-center gap-2 sm:flex-row">
        {txn.status === 'PENDING' && (
          <button type="button" onClick={onRefresh} disabled={refreshing} className={BTN_OUTLINE}>
            <RotateCw size={15} aria-hidden className={refreshing ? 'animate-spin motion-reduce:animate-none' : ''} />
            Kiểm tra lại
          </button>
        )}
        <Link to={orderLink} className={BTN_PRIMARY}>
          <ReceiptText size={16} aria-hidden />
          {txn.status === 'SUCCEEDED' ? 'Xem đơn hàng' : 'Về đơn hàng'}
        </Link>
      </div>
    </section>
  )
}

export function PaymentPage() {
  const [params] = useSearchParams()
  const raw = params.get('transactionId') ?? ''
  const transactionId = /^[1-9]\d{0,9}$/.test(raw) ? Number(raw) : null
  const txn = useMyPaymentTransaction(transactionId)
  const notFound = transactionId === null || (txn.isError && toApiError(txn.error).statusCode === 404)

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero breadcrumbs={[{ label: 'Đơn hàng', href: '/orders' }, { label: 'Thanh toán' }]} title="Kết quả thanh toán" />
      <div className="mx-auto max-w-[1280px] px-8 pt-8">
        {notFound ? (
          <EmptyPanel icon={<ReceiptText size={26} aria-hidden />} title={raw === '' ? 'Chưa chọn giao dịch' : 'Không tìm thấy giao dịch'} action={{ to: '/orders', label: 'Về đơn hàng của tôi' }}>
            {raw === '' ? 'Trang này hiện kết quả sau khi bạn thanh toán một đơn hàng. Hãy mở đơn trong "Đơn hàng của tôi".' : 'Giao dịch này không tồn tại hoặc không thuộc về bạn.'}
          </EmptyPanel>
        ) : txn.isError ? (
          <ErrorPanel title="Không tải được kết quả thanh toán" error={txn.error} onRetry={() => void txn.refetch()} retrying={txn.isFetching} />
        ) : !txn.data ? (
          <LoadingBlock label="Đang kiểm tra giao dịch" />
        ) : (
          <Result txn={txn.data} refreshing={txn.isFetching} onRefresh={() => void txn.refetch()} />
        )}
      </div>
    </div>
  )
}
