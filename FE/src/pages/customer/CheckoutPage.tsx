// Đặt hàng online (UC62, shop-v1 #5–7, BR-BH-08): chọn địa chỉ trong sổ + một chi nhánh xử lý đủ hàng, xem báo giá (ST09), đặt đơn rồi sang cổng thanh toán (UC63).
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { MapPin, ShoppingCart, Store } from 'lucide-react'
import { toast } from 'sonner'
import { EmptyPanel, ErrorPanel, InlineError, LoadingBlock } from '@/components/customer/shop/panels'
import { BTN_PRIMARY, CARD, H2, vnd } from '@/components/customer/shop/styles'
import { usePayOrder } from '@/components/customer/shop/usePayOrder'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import { shopApi } from '@/shared/api/shop.api'
import { useAddresses } from '@/shared/hooks/useProfile'
import { useCart, useFulfillmentBranches, useOnlineOrderQuote, useShopMutation } from '@/shared/hooks/useShop'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import type { Address } from '@/shared/types/profile'
import type { Cart, FulfillmentBranch, OnlineOrderQuote } from '@/shared/types/shop'

function Choice({ name, checked, onChange, children }: { name: string; checked: boolean; onChange: () => void; children: React.ReactNode }) {
  return (
    <label className={`flex cursor-pointer items-start gap-3 rounded-xl border p-3 text-[14px] ${checked ? 'border-accent bg-accent-soft' : 'border-(--color-border-default) bg-white'}`}>
      <input type="radio" name={name} checked={checked} onChange={onChange} className="mt-1 accent-[#a43324]" />
      <span className="min-w-0">{children}</span>
    </label>
  )
}

const addressText = (a: Address) => [a.addressLine, a.ward, a.province].filter(Boolean).join(', ')

function AddressStep({ addresses, value, onChange }: { addresses: Address[]; value: number | null; onChange: (id: number) => void }) {
  return (
    <section aria-labelledby="co-address" className={`${CARD} space-y-3 p-5`}>
      <h2 id="co-address" className={`${H2} flex items-center gap-2`}>
        <MapPin size={18} aria-hidden className="text-accent" /> Địa chỉ giao hàng
      </h2>
      {addresses.length === 0 ? (
        <p className="text-[14px] text-(--color-text-secondary)">Bạn chưa có địa chỉ nào trong sổ địa chỉ.</p>
      ) : (
        <div role="radiogroup" aria-labelledby="co-address" className="space-y-2">
          {addresses.map((a) => (
            <Choice key={a.addressId} name="address" checked={value === a.addressId} onChange={() => onChange(a.addressId)}>
              <span className="font-bold">
                {a.receiverName} · {a.receiverPhone}
                {a.isDefault && <span className="ml-2 rounded-full bg-white px-2 py-0.5 text-[11px] text-accent">Mặc định</span>}
              </span>
              <span className="block text-(--color-text-secondary)">{addressText(a)}</span>
            </Choice>
          ))}
        </div>
      )}
      <Link to="/account/addresses" className="inline-block text-[13px] font-semibold text-accent hover:underline">
        {addresses.length === 0 ? 'Thêm địa chỉ trong sổ địa chỉ' : 'Quản lý sổ địa chỉ'}
      </Link>
    </section>
  )
}

function BranchStep({ branches, value, onChange }: { branches: FulfillmentBranch[]; value: number | null; onChange: (id: number) => void }) {
  return (
    <section aria-labelledby="co-branch" className={`${CARD} space-y-3 p-5`}>
      <h2 id="co-branch" className={`${H2} flex items-center gap-2`}>
        <Store size={18} aria-hidden className="text-accent" /> Chi nhánh xử lý đơn
      </h2>
      {branches.length === 0 ? (
        <p role="note" className="rounded-xl bg-amber-50 px-3 py-2 text-[13.5px] text-amber-950">
          Hiện chưa có chi nhánh nào còn đủ hàng cho cả giỏ. Hãy giảm số lượng hoặc bỏ bớt sản phẩm trong giỏ hàng.
        </p>
      ) : (
        <div role="radiogroup" aria-labelledby="co-branch" className="space-y-2">
          {branches.map((b) => (
            <Choice key={b.branchId} name="branch" checked={value === b.branchId} onChange={() => onChange(b.branchId)}>
              <span className="font-bold">{b.name}</span>
              <span className="block text-(--color-text-secondary)">{b.address}</span>
            </Choice>
          ))}
        </div>
      )}
    </section>
  )
}

function Summary({ cart, quote, quoteError, quoting, canOrder, submitting, onOrder }: { cart: Cart; quote?: OnlineOrderQuote; quoteError: string; quoting: boolean; canOrder: boolean; submitting: boolean; onOrder: () => void }) {
  return (
    <aside aria-label="Tóm tắt đơn hàng" className={`${CARD} space-y-4 p-5 lg:sticky lg:top-[72px]`}>
      <h2 className={H2}>Tóm tắt đơn hàng</h2>
      <ul className="space-y-1.5 text-[13.5px]">
        {cart.items.map((i) => (
          <li key={i.productId} className="flex justify-between gap-3">
            <span className="min-w-0">
              {i.name} <span className="text-(--color-text-secondary)">× {i.quantity}</span>
            </span>
            <span className="tabular-nums">{vnd(i.unitPrice * i.quantity)}</span>
          </li>
        ))}
      </ul>
      <dl className="space-y-1.5 border-t border-(--color-border-default) pt-3 text-[14px]">
        <div className="flex justify-between">
          <dt className="text-(--color-text-secondary)">Tạm tính</dt>
          <dd className="tabular-nums">{vnd(quote?.subtotal ?? cart.subtotal)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-(--color-text-secondary)">Phí vận chuyển</dt>
          <dd className="tabular-nums">{quote ? (quote.freeShippingApplied || quote.shippingFee === 0 ? 'Miễn phí' : vnd(quote.shippingFee)) : quoting ? 'Đang tính…' : '—'}</dd>
        </div>
        <div className="flex justify-between font-friendly text-[18px] font-extrabold">
          <dt>Tổng cộng</dt>
          <dd className="text-accent tabular-nums">{quote ? vnd(quote.total) : '—'}</dd>
        </div>
      </dl>
      <InlineError message={quoteError} />
      <p className="text-[12.5px] text-(--color-text-secondary)">Thanh toán trước qua cổng thanh toán online, không thu tiền khi nhận hàng. Giá và phí được chốt khi đặt đơn.</p>
      <button type="button" onClick={onOrder} disabled={!canOrder} className={`${BTN_PRIMARY} w-full`}>
        {submitting ? 'Đang đặt hàng…' : 'Đặt hàng và thanh toán'}
      </button>
    </aside>
  )
}

export function CheckoutPage() {
  const navigate = useNavigate()
  const cart = useCart()
  const hasItems = (cart.data?.items.length ?? 0) > 0
  const addresses = useAddresses()
  const branches = useFulfillmentBranches(hasItems)
  const [addressPick, setAddressPick] = useState<number | null>(null)
  const [branchPick, setBranchPick] = useState<number | null>(null)
  const [error, setError] = useState('')
  // Đặt đơn dọn giỏ và làm mới giỏ / báo giá ngay trước khi sang cổng: giữ nguyên màn đang xem cho đến khi chuyển trang.
  const [frozen, setFrozen] = useState<{ cart: Cart; quote?: OnlineOrderQuote } | null>(null)
  const place = useShopMutation(shopApi.placeOrder)
  const { pay } = usePayOrder()

  const addressList = addresses.data ?? []
  const addressId = addressList.some((a) => a.addressId === addressPick) ? addressPick : (addressList.find((a) => a.isDefault) ?? addressList[0])?.addressId ?? null
  const branchList = branches.data ?? []
  const branchId = branchList.some((b) => b.branchId === branchPick) ? branchPick : (branchList[0]?.branchId ?? null)
  const quote = useOnlineOrderQuote(addressId != null && branchId != null && !frozen ? { branchId, addressId } : null)
  const submitting = frozen !== null

  const submit = async () => {
    if (addressId == null || branchId == null || !cart.data) return
    setError('')
    setFrozen({ cart: cart.data, quote: quote.data })
    try {
      const order = await place.mutateAsync({ branchId, addressId })
      try {
        await pay(order.onlineOrderId)
      } catch (e) {
        // Đơn đã đặt và đang giữ hàng; thanh toán lại được từ chi tiết đơn.
        toast.error(`Đã đặt đơn ${order.code} nhưng chưa mở được cổng thanh toán: ${plainMessage(toApiError(e))}`)
        navigate(`/order/${order.onlineOrderId}`)
      }
    } catch (e) {
      setError(plainMessage(toApiError(e)))
      setFrozen(null)
    }
  }

  const shown = frozen?.cart ?? cart.data
  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero breadcrumbs={[{ label: 'Giỏ hàng', href: '/cart' }, { label: 'Đặt hàng' }]} title="Đặt hàng" subtitle="Chọn địa chỉ và chi nhánh xử lý, xem phí vận chuyển rồi thanh toán online." />
      <div className="mx-auto max-w-[1280px] space-y-4 px-8 pt-8">
        <InlineError message={error} />
        {cart.isError ? (
          <ErrorPanel title="Không tải được giỏ hàng" error={cart.error} onRetry={() => void cart.refetch()} retrying={cart.isFetching} />
        ) : !shown || addresses.isPending ? (
          <LoadingBlock label="Đang tải thông tin đặt hàng" />
        ) : shown.items.length === 0 ? (
          <EmptyPanel icon={<ShoppingCart size={26} aria-hidden />} title="Giỏ hàng trống" action={{ to: '/shop', label: 'Khám phá cửa hàng' }}>
            Hãy thêm sản phẩm vào giỏ trước khi đặt hàng.
          </EmptyPanel>
        ) : (
          <div className="grid items-start gap-6 lg:grid-cols-[1fr_380px]">
            <div className="space-y-6">
              <AddressStep addresses={addressList} value={addressId} onChange={setAddressPick} />
              <BranchStep branches={branchList} value={branchId} onChange={setBranchPick} />
            </div>
            <Summary
              cart={shown}
              quote={frozen ? frozen.quote : quote.data}
              quoteError={quote.isError && !submitting ? plainMessage(toApiError(quote.error)) : ''}
              quoting={quote.isFetching}
              canOrder={!submitting && quote.isSuccess && !quote.isFetching}
              submitting={submitting}
              onOrder={() => void submit()}
            />
          </div>
        )}
      </div>
    </div>
  )
}
