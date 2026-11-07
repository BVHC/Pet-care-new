// Cổng thanh toán GIẢ LẬP (UC63, payment-v1 #3): chỉ dùng khi chưa nối cổng thật (VNPay, T65). Thay cho cổng, trang này ký callback bằng `gatewaySignature` rồi gọi
// endpoint công khai; kết quả (PAID / FAILED) do BE quyết định, trang `/payment` hỏi lại giao dịch ở BE chứ không tin tham số trên URL.
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { CreditCard, ShieldAlert } from 'lucide-react'
import { InlineError } from '@/components/customer/shop/panels'
import { BTN_OUTLINE, BTN_PRIMARY, CARD, vnd } from '@/components/customer/shop/styles'
import { recallMockTransaction } from '@/components/customer/shop/usePayOrder'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import { GATEWAY_CANCEL_CODE, GATEWAY_SUCCESS_CODE, gatewaySignature } from '@/shared/api/mock/payment.mock'
import { paymentApi } from '@/shared/api/payment.api'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { usePaymentMutation } from '@/shared/hooks/usePayment'

export function PaymentGatewayMockPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const callback = usePaymentMutation(paymentApi.gatewayCallback)
  const [error, setError] = useState('')

  const code = params.get('code')?.trim() ?? ''
  const amountText = params.get('amount') ?? ''
  const amount = /^\d{1,12}$/.test(amountText) ? Number(amountText) : null
  const valid = code !== '' && amount !== null

  const send = async (status: string) => {
    if (!valid) return
    setError('')
    const gatewayTxnId = `MOCK${Date.now()}`
    try {
      await callback.mutateAsync({ transactionCode: code, gatewayTxnId, status, amount, signature: gatewaySignature({ transactionCode: code, gatewayTxnId, status, amount }) })
    } catch (e) {
      setError(plainMessage(toApiError(e)))
      return
    }
    // Không có mã giao dịch đã nhớ (mở đường dẫn cổng ở tab khác): về danh sách đơn, đơn sẽ cho biết đã thanh toán chưa.
    const transactionId = recallMockTransaction(code)
    navigate(transactionId ? `/payment?transactionId=${transactionId}` : '/orders')
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero breadcrumbs={[{ label: 'Đơn hàng', href: '/orders' }, { label: 'Cổng thanh toán giả lập' }]} title="Cổng thanh toán giả lập" />

      <div className="mx-auto max-w-[560px] px-8 pt-8">
        <div className={`${CARD} space-y-5 p-6`}>
          <p role="note" className="flex items-start gap-2 rounded-xl bg-amber-50 px-3 py-2.5 text-[13px] font-semibold text-amber-950">
            <ShieldAlert size={16} aria-hidden className="mt-0.5 shrink-0" />
            Cổng giả lập, không thu tiền thật. Hai nút bên dưới thay cho kết quả mà cổng thanh toán thật sẽ trả về.
          </p>

          {valid ? (
            <>
              <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-[14px]">
                <dt className="text-(--color-text-secondary)">Mã giao dịch</dt>
                <dd className="font-mono font-bold break-all">{code}</dd>
                <dt className="text-(--color-text-secondary)">Số tiền</dt>
                <dd className="font-friendly text-[20px] font-extrabold text-accent tabular-nums">{vnd(amount)}</dd>
              </dl>
              <InlineError message={error} />
              <div className="flex flex-col gap-2 sm:flex-row">
                <button type="button" onClick={() => void send(GATEWAY_SUCCESS_CODE)} disabled={callback.isPending} className={`${BTN_PRIMARY} flex-1`}>
                  <CreditCard size={16} aria-hidden />
                  Thanh toán thành công
                </button>
                <button type="button" onClick={() => void send(GATEWAY_CANCEL_CODE)} disabled={callback.isPending} className={`${BTN_OUTLINE} flex-1`}>
                  Hủy thanh toán
                </button>
              </div>
            </>
          ) : (
            <div role="alert" className="space-y-3 text-[14px]">
              <p className="font-bold text-red-700">Đường dẫn cổng thanh toán không hợp lệ</p>
              <p className="text-(--color-text-secondary)">Thiếu mã giao dịch hoặc số tiền. Hãy mở lại từ bước thanh toán của đơn hàng.</p>
              <Link to="/orders" className={BTN_OUTLINE}>
                Về đơn hàng của tôi
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
