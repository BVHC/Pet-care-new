import { Link } from 'react-router-dom'
import { Heart, ShoppingBag, ShoppingCart, Star } from 'lucide-react'
import { toast } from 'sonner'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './FavoritesPage.module.css'

/* ================================================================
   Mock data.
   ================================================================ */

interface FavoriteItem {
  id: number
  name: string
  category: string
  price: number
  originalPrice?: number
  rating: number
  image: string
  badge?: string
}

const FAVORITES: FavoriteItem[] = [
  {
    id: 1,
    name: 'Royal Canin Adult — Thức ăn hạt cho chó trưởng thành',
    category: 'Thức ăn',
    price: 450_000,
    originalPrice: 520_000,
    rating: 4.8,
    image: '/imgs/prod1.jpg',
    badge: '-13%',
  },
  {
    id: 2,
    name: 'Whiskas Salmon — Thức ăn mèo vị cá hồi',
    category: 'Thức ăn',
    price: 120_000,
    rating: 4.7,
    image: '/imgs/prod3.jpg',
  },
  {
    id: 3,
    name: 'Orthopedic Pet Bed — Đệm êm cho thú cưng',
    category: 'Giường & Đệm',
    price: 890_000,
    originalPrice: 1_100_000,
    rating: 4.9,
    image: '/imgs/hero-dog.png',
    badge: 'Bán chạy',
  },
  {
    id: 4,
    name: 'Interactive Ball — Bóng phát sáng cho chó',
    category: 'Đồ chơi',
    price: 89_000,
    rating: 4.3,
    image: '/imgs/prod4.jpg',
  },
]

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

/* ================================================================
   Main.
   ================================================================ */

export function FavoritesPage() {
  const remove = (name: string) => {
    toast.success(`Đã xóa "${name}" khỏi yêu thích`)
  }

  const addToCart = (name: string) => {
    toast.success(`Đã thêm "${name}" vào giỏ hàng`)
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* Hero slab */}
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
            <span className="font-semibold text-white/85">Yêu thích</span>
          </nav>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <h1 className="font-bayon text-[clamp(22px,3vw,36px)] leading-[1.02] font-normal text-white">
              Sản phẩm yêu thích
            </h1>
            {FAVORITES.length > 0 && (
              <p className="text-[12.5px] text-white/60">{FAVORITES.length} sản phẩm</p>
            )}
          </div>
        </div>
      </section>

      {/* Content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar active="favorites" />

          <div className={styles.main}>
            {FAVORITES.length === 0 ? (
              <div className={styles.emptyState}>
                <span className={styles.emptyIcon}>
                  <Heart size={36} />
                </span>
                <p className={styles.emptyTitle}>Chưa có sản phẩm yêu thích</p>
                <p className={styles.emptyDesc}>
                  Lưu lại sản phẩm bạn thích để mua sắm dễ dàng hơn.
                </p>
                <Link to="/shop" className={styles.emptyShopBtn}>
                  <ShoppingBag size={15} />
                  Khám phá cửa hàng
                </Link>
              </div>
            ) : (
              <>
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                  {FAVORITES.map((item) => (
                    <div key={item.id} className={styles.prodCard}>
                      <div className={styles.prodMedia}>
                        <img src={item.image} alt={item.name} loading="lazy" />
                        {item.badge && (
                          <span className={styles.badge}>{item.badge}</span>
                        )}
                        <button
                          type="button"
                          onClick={() => remove(item.name)}
                          className={styles.heartBtn}
                          aria-label={`Xóa ${item.name} khỏi yêu thích`}
                        >
                          <Heart size={16} fill="currentColor" />
                        </button>
                      </div>
                      <div className={styles.prodBody}>
                        <div className={styles.prodCategory}>{item.category}</div>
                        <div className={styles.prodName}>{item.name}</div>
                        <div className={styles.prodMeta}>
                          <span className={styles.prodRating}>
                            <Star size={12} className="fill-amber-400 text-amber-400" />
                            {item.rating}
                          </span>
                        </div>
                        <div className={styles.prodPriceRow}>
                          <span className={styles.prodPrice}>{vnd.format(item.price)}</span>
                          {item.originalPrice && (
                            <span className={styles.prodPriceOld}>
                              {vnd.format(item.originalPrice)}
                            </span>
                          )}
                        </div>
                        <button
                          type="button"
                          onClick={() => addToCart(item.name)}
                          className={styles.addCartBtn}
                        >
                          <ShoppingCart size={14} />
                          Thêm vào giỏ
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
