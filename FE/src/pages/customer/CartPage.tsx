import { Link, useNavigate } from 'react-router-dom'
import {
  ChevronRight,
  Minus,
  Plus,
  ShoppingBag,
  ShoppingCart,
  Trash2,
} from 'lucide-react'
import { toast } from 'sonner'
import { useCartStore } from '../../shared/stores/cart.store'
import styles from './CartPage.module.css'

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

const FREE_SHIPPING_THRESHOLD = 300_000

/* ================================================================
   Cart item row.
   ================================================================ */

function CartItemRow({
  item,
  onUpdateQty,
  onRemove,
}: {
  item: {
    id: number
    productId: number
    productName?: string
    quantity: number
    unitPrice: number
    subtotal: number
    image?: string
  }
  onUpdateQty: (id: number, qty: number) => void
  onRemove: (id: number) => void
}) {
  return (
    <div className={styles.cartItem}>
      <Link to={`/shop/${item.productId}`} className="shrink-0">
        {item.image ? (
          <img
            src={item.image}
            alt={item.productName}
            className={styles.itemImage}
            loading="lazy"
          />
        ) : (
          <span className={styles.itemImageFallback}>
            <ShoppingBag size={22} />
          </span>
        )}
      </Link>

      <div className={styles.itemInfo}>
        <Link
          to={`/shop/${item.productId}`}
          className={styles.itemName}
        >
          {item.productName}
        </Link>
        <span className={styles.itemUnitPrice}>{vnd.format(item.unitPrice)} / 1</span>
      </div>

      {/* Quantity stepper */}
      <div className={styles.qtyStepper}>
        <button
          type="button"
          onClick={() => onUpdateQty(item.id, item.quantity - 1)}
          className={styles.qtyBtn}
          aria-label="Giảm số lượng"
          disabled={item.quantity <= 1}
        >
          <Minus size={14} />
        </button>
        <span className={styles.qtyValue}>{item.quantity}</span>
        <button
          type="button"
          onClick={() => onUpdateQty(item.id, item.quantity + 1)}
          className={styles.qtyBtn}
          aria-label="Tăng số lượng"
        >
          <Plus size={14} />
        </button>
      </div>

      {/* Subtotal */}
      <div className={styles.itemSubtotal}>
        <span className={styles.itemSubtotalPrice}>{vnd.format(item.subtotal)}</span>
        <span className={styles.itemSubtotalUnit}>/ {item.quantity} cái</span>
      </div>

      {/* Remove */}
      <button
        type="button"
        onClick={() => onRemove(item.id)}
        className={styles.removeBtn}
        aria-label={`Xóa ${item.productName}`}
      >
        <Trash2 size={15} />
      </button>
    </div>
  )
}

/* ================================================================
   Order summary card.
   ================================================================ */

function OrderSummary({
  subtotal,
  onCheckout,
  onContinue,
}: {
  subtotal: number
  onCheckout: () => void
  onContinue: () => void
}) {
  const shipping = subtotal >= FREE_SHIPPING_THRESHOLD ? 0 : 30000
  const finalTotal = subtotal + shipping
  const progress = Math.min((subtotal / FREE_SHIPPING_THRESHOLD) * 100, 100)
  const toFreeShipping = Math.max(FREE_SHIPPING_THRESHOLD - subtotal, 0)

  return (
    <div className={styles.summaryCard}>
      <div className={styles.summaryHeader}>
        <ShoppingBag size={18} className="text-accent" />
        <h2 className={styles.summaryTitle}>Tóm tắt đơn hàng</h2>
      </div>

      <div className={styles.summaryBody}>
        {/* Free shipping progress */}
        <div className={styles.freeShipBar}>
          <div className={styles.freeShipProgress}>
            <div className={styles.freeShipFill} style={{ width: `${progress}%` }} />
          </div>
          {subtotal >= FREE_SHIPPING_THRESHOLD ? (
            <p className={styles.freeShipText}>
              <strong>Đã đạt miễn phí vận chuyển!</strong>
            </p>
          ) : (
            <p className={styles.freeShipText}>
              Thêm <strong>{vnd.format(toFreeShipping)}</strong> để được miễn phí vận chuyển
            </p>
          )}
        </div>

        <div className={styles.summaryRow}>
          <span className={styles.summaryRowLabel}>Tạm tính</span>
          <span className={styles.summaryRowValue}>{vnd.format(subtotal)}</span>
        </div>

        <div className={styles.summaryRow}>
          <span className={styles.summaryRowLabel}>Vận chuyển</span>
          {shipping === 0 ? (
            <span className={styles.summaryRowFree}>Miễn phí</span>
          ) : (
            <span className={styles.summaryRowValue}>{vnd.format(shipping)}</span>
          )}
        </div>

        <hr className={styles.summaryDivider} />

        <div className={styles.summaryTotal}>
          <span className={styles.summaryTotalLabel}>Tổng</span>
          <span className={styles.summaryTotalValue}>{vnd.format(finalTotal)}</span>
        </div>
      </div>

      <div className={styles.summaryActions}>
        <button
          type="button"
          onClick={onCheckout}
          className={styles.checkoutBtn}
        >
          <ChevronRight size={17} />
          Thanh toán ngay
        </button>
        <button
          type="button"
          onClick={onContinue}
          className={styles.continueBtn}
        >
          Tiếp tục mua sắm
        </button>
      </div>
    </div>
  )
}

/* ================================================================
   Empty state.
   ================================================================ */

function EmptyState({ onShop }: { onShop: () => void }) {
  return (
    <div className={styles.emptyState}>
      <span className={styles.emptyIcon}>
        <ShoppingCart size={36} />
      </span>
      <p className={styles.emptyTitle}>Giỏ hàng trống</p>
      <p className={styles.emptyDesc}>
        Thêm sản phẩm vào giỏ để bắt đầu mua sắm cho boss yêu nhé.
      </p>
      <button
        type="button"
        onClick={onShop}
        className={styles.emptyShopBtn}
      >
        <ShoppingBag size={16} />
        Khám phá cửa hàng
      </button>
    </div>
  )
}

/* ================================================================
   Main page.
   ================================================================ */

export function CartPage() {
  const navigate = useNavigate()
  const items = useCartStore((s) => s.items)
  const updateItem = useCartStore((s) => s.updateItem)
  const removeItem = useCartStore((s) => s.removeItem)
  const clearCart = useCartStore((s) => s.clearCart)
  const subtotal = useCartStore((s) => s.subtotal)

  const handleUpdateQty = (id: number, qty: number) => {
    if (qty < 1) return
    updateItem(id, qty)
  }

  const handleRemove = (id: number) => {
    const item = items.find((i) => i.id === id)
    removeItem(id)
    if (item) toast.success(`Đã xóa "${item.productName}" khỏi giỏ`)
  }

  const handleClear = () => {
    clearCart()
    toast.success('Đã xóa toàn bộ giỏ hàng')
  }

  const handleCheckout = () => {
    if (items.length === 0) {
      toast.error('Giỏ hàng trống.')
      return
    }
    navigate('/checkout')
  }

  const handleContinue = () => {
    navigate('/shop')
  }

  const handleEmptyShop = () => {
    navigate('/shop')
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
          <nav
            aria-label="Đường dẫn"
            className="mb-5 text-[12.5px] text-white/50"
          >
            <Link
              to="/"
              className="underline-offset-4 transition-colors hover:text-white hover:underline"
            >
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <Link
              to="/shop"
              className="underline-offset-4 transition-colors hover:text-white hover:underline"
            >
              Cửa hàng
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Giỏ hàng</span>
          </nav>

          <div className="flex flex-wrap items-end justify-between gap-4">
            <h1 className="font-friendly font-extrabold text-[clamp(24px,3.2vw,40px)] leading-[1.05] text-white">
              Giỏ hàng của bạn
            </h1>
            {items.length > 0 && (
              <p className="text-[12.5px] text-white/60">
                {items.reduce((s, i) => s + i.quantity, 0)} sản phẩm
              </p>
            )}
          </div>
        </div>
      </section>

      {/* ====== Content ====== */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        {items.length === 0 ? (
          <EmptyState onShop={handleEmptyShop} />
        ) : (
          <div className={styles.page}>
            {/* ====== Items list ====== */}
            <div className={styles.itemsSection}>
              <div className={styles.itemsHeader}>
                <span className={styles.itemsHeaderLabel}>
                  {items.length} sản phẩm
                </span>
                <button
                  type="button"
                  onClick={handleClear}
                  className={styles.clearBtn}
                >
                  Xóa tất cả
                </button>
              </div>

              {items.map((item) => (
                <CartItemRow
                  key={item.id}
                  item={item}
                  onUpdateQty={handleUpdateQty}
                  onRemove={handleRemove}
                />
              ))}
            </div>

            {/* ====== Order summary ====== */}
            <OrderSummary
              subtotal={subtotal}
              onCheckout={handleCheckout}
              onContinue={handleContinue}
            />
          </div>
        )}
      </div>
    </div>
  )
}
