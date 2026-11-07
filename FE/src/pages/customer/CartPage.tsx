// Giỏ hàng (UC61, shop-v1 #1–4, BR-BH-07): giỏ lưu trên BE, mỗi thao tác là một lệnh ghi; giá chỉ để xem, giá chốt khi đặt.
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ChevronRight, Minus, Plus, ShoppingBag, ShoppingCart, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { ErrorPanel, InlineError, LoadingBlock } from '@/components/customer/shop/panels'
import { vnd } from '@/components/customer/shop/styles'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import { shopApi } from '@/shared/api/shop.api'
import { useCart, useShopMutation } from '@/shared/hooks/useShop'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { SHOP_LIMITS, type CartItem } from '@/shared/types/shop'
import styles from './CartPage.module.css'

function CartItemRow({ item, busy, onQuantity, onRemove }: { item: CartItem; busy: boolean; onQuantity: (qty: number) => void; onRemove: () => void }) {
  return (
    <div className={styles.cartItem}>
      {/* Link ảnh trùng với link tên bên cạnh nên bỏ khỏi thứ tự Tab và trình đọc màn hình. */}
      <Link to={`/shop/${item.productId}`} aria-hidden tabIndex={-1} className="shrink-0">
        {item.imageUrl ? (
          <img src={item.imageUrl} alt="" className={styles.itemImage} loading="lazy" />
        ) : (
          <span className={styles.itemImageFallback}>
            <ShoppingBag size={22} />
          </span>
        )}
      </Link>

      <div className={styles.itemInfo}>
        <Link to={`/shop/${item.productId}`} className={styles.itemName}>
          {item.name}
        </Link>
        <span className={styles.itemUnitPrice}>
          {vnd(item.unitPrice)} / {item.unit}
        </span>
        {!item.available && <span className="mt-1 text-[12px] font-bold text-red-700">Hết hàng</span>}
      </div>

      <div className={styles.qtyStepper}>
        <button type="button" onClick={() => onQuantity(item.quantity - 1)} className={styles.qtyBtn} aria-label={`Giảm số lượng ${item.name}`} disabled={busy || item.quantity <= SHOP_LIMITS.minQuantity}>
          <Minus size={14} />
        </button>
        <span className={styles.qtyValue} aria-label={`Số lượng ${item.name}`}>
          {item.quantity}
        </span>
        <button type="button" onClick={() => onQuantity(item.quantity + 1)} className={styles.qtyBtn} aria-label={`Tăng số lượng ${item.name}`} disabled={busy || item.quantity >= SHOP_LIMITS.maxQuantity}>
          <Plus size={14} />
        </button>
      </div>

      <div className={styles.itemSubtotal}>
        <span className={styles.itemSubtotalPrice}>{vnd(item.unitPrice * item.quantity)}</span>
      </div>

      <button type="button" onClick={onRemove} disabled={busy} className={styles.removeBtn} aria-label={`Xóa ${item.name}`}>
        <Trash2 size={15} />
      </button>
    </div>
  )
}

function Summary({ subtotal, soldOut, onCheckout }: { subtotal: number; soldOut: boolean; onCheckout: () => void }) {
  return (
    <aside className={styles.summaryCard} aria-label="Tóm tắt đơn hàng">
      <div className={styles.summaryHeader}>
        <ShoppingBag size={18} className="text-accent" />
        <h2 className={styles.summaryTitle}>Tóm tắt đơn hàng</h2>
      </div>
      <div className={styles.summaryBody}>
        <div className={styles.summaryRow}>
          <span className={styles.summaryRowLabel}>Tạm tính</span>
          <span className={styles.summaryRowValue}>{vnd(subtotal)}</span>
        </div>
        <p className="text-[12.5px] text-(--color-text-secondary)">Phí vận chuyển được tính ở bước đặt hàng, sau khi bạn chọn địa chỉ và chi nhánh xử lý.</p>
        {soldOut && (
          <p role="note" className="rounded-xl bg-amber-50 px-3 py-2 text-[12.5px] font-medium text-amber-900">
            Có sản phẩm đã hết hàng, hãy xóa khỏi giỏ để đặt hàng.
          </p>
        )}
      </div>
      <div className={styles.summaryActions}>
        <button type="button" onClick={onCheckout} disabled={soldOut} className={styles.checkoutBtn}>
          <ChevronRight size={17} />
          Tiến hành đặt hàng
        </button>
        <Link to="/shop" className={styles.continueBtn}>
          Tiếp tục mua sắm
        </Link>
      </div>
    </aside>
  )
}

export function CartPage() {
  const navigate = useNavigate()
  const cart = useCart()
  const [error, setError] = useState('')
  const change = useShopMutation((fn: () => Promise<unknown>) => fn())

  // Mọi lệnh ghi qua một chỗ: lỗi (400 BR-BH-07, 404 sản phẩm đã ngừng bán) hiện ngay trên giỏ, dữ liệu được tải lại.
  const run = (fn: () => Promise<unknown>, done?: string) => {
    setError('')
    change.mutate(fn, {
      onSuccess: () => done && toast.success(done),
      onError: (e) => setError(plainMessage(toApiError(e))),
    })
  }

  const items = cart.data?.items ?? []
  const count = items.reduce((sum, i) => sum + i.quantity, 0)

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero breadcrumbs={[{ label: 'Cửa hàng', href: '/shop' }, { label: 'Giỏ hàng' }]} title="Giỏ hàng của bạn" subtitle={count > 0 ? `${count} sản phẩm trong giỏ` : undefined} />

      <div className="mx-auto max-w-[1280px] px-8 pt-8">
        {cart.isError ? (
          <ErrorPanel title="Không tải được giỏ hàng" error={cart.error} onRetry={() => void cart.refetch()} retrying={cart.isFetching} />
        ) : !cart.data ? (
          <LoadingBlock label="Đang tải giỏ hàng" />
        ) : items.length === 0 ? (
          <div className={styles.emptyState}>
            <span className={styles.emptyIcon}>
              <ShoppingCart size={36} />
            </span>
            <p className={styles.emptyTitle}>Giỏ hàng trống</p>
            <p className={styles.emptyDesc}>Thêm sản phẩm vào giỏ để bắt đầu mua sắm cho boss yêu nhé.</p>
            <Link to="/shop" className={styles.emptyShopBtn}>
              <ShoppingBag size={16} />
              Khám phá cửa hàng
            </Link>
          </div>
        ) : (
          <div className="space-y-4">
            <InlineError message={error} />
            <div className={styles.page}>
              <div className={styles.itemsSection}>
                <div className={styles.itemsHeader}>
                  <span className={styles.itemsHeaderLabel}>{items.length} dòng hàng</span>
                  <button type="button" disabled={change.isPending} onClick={() => run(() => shopApi.clearCart(), 'Đã xóa toàn bộ giỏ hàng')} className={styles.clearBtn}>
                    Xóa tất cả
                  </button>
                </div>
                {items.map((item) => (
                  <CartItemRow
                    key={item.productId}
                    item={item}
                    busy={change.isPending}
                    onQuantity={(qty) => run(() => shopApi.setCartItem(item.productId, { quantity: qty }))}
                    onRemove={() => run(() => shopApi.removeCartItem(item.productId), `Đã xóa "${item.name}" khỏi giỏ`)}
                  />
                ))}
              </div>
              <Summary subtotal={cart.data.subtotal} soldOut={items.some((i) => !i.available)} onCheckout={() => navigate('/checkout')} />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
