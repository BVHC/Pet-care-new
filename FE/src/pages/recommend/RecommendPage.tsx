import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import {
  ChevronRight,
  Cat,  Dog,  Heart,  Search,
  Plus,

  Star,
} from 'lucide-react'
import { MOCK_PETS, getRecommendationCount, getRecommendations, type Pet, type RecProduct } from './recommend.mock'
import { useCartStore } from '../../shared/stores/cart.store'
import { CATEGORY_LABELS } from '../customer/shop.mock'
import styles from './RecommendPage.module.css'

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

const soldFmt = new Intl.NumberFormat('vi-VN')

/* ================================================================
   Formatter helpers.
   ================================================================ */

function isPetActive(pet: Pet, selectedId: number | null): boolean {
  return pet.id === selectedId
}

/* ================================================================
   Pet avatar in sidebar.
   ================================================================ */

function SidebarPetItem({
  pet,
  active,
  onClick,
}: {
  pet: Pet
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={`${styles.petItem} ${active ? styles.petItemActive : ''}`}
    >
      <img src={pet.image} alt={pet.name} className={styles.petAvatar} loading="lazy" />
      <div className={styles.petInfo}>
        <div className={styles.petName}>{pet.name}</div>
        <div className={styles.petMeta}>
          {pet.breed} · {pet.age}
        </div>
      </div>
      <span className={styles.petDot} aria-hidden />
    </button>
  )
}

/* ================================================================
   Featured pet card — hiện ở đầu main.
   ================================================================ */

function FeaturedPet({ pet }: { pet: Pet }) {
  const count = getRecommendationCount(pet.id)
  return (
    <div className={styles.featured}>
      <div className={styles.featuredInner}>
        <img src={pet.image} alt={pet.name} className={styles.featuredAvatar} />
        <div>
          <div className={styles.featuredName}>{pet.name}</div>
          <div className={styles.featuredBreed}>
            {pet.breed} · {pet.age} · {pet.weight}
          </div>
          <div className={styles.featuredTags}>
            {pet.type === 'dog' ? (
              <span className={styles.featuredTag}>
                <Dog size={12} />
                Chó
              </span>
            ) : (
              <span className={styles.featuredTag}>
                <Cat size={12} />
                Mèo
              </span>
            )}
            <span className={styles.featuredTag}>{pet.weight}</span>
            {pet.healthNote && (
              <span className={styles.featuredTag}>{pet.healthNote}</span>
            )}
          </div>
        </div>
        <div className={styles.featuredMeta}>
          <div className={styles.featuredMetaLabel}>Gợi ý</div>
          <div className={styles.featuredMetaCount}>{count}</div>
        </div>
      </div>
    </div>
  )
}

/* ================================================================
   Sticky filter bar.
   ================================================================ */

const SORT_OPTIONS = [
  { id: 'popular', label: 'Bán chạy' },
  { id: 'rating', label: 'Đánh giá cao' },
  { id: 'price_asc', label: 'Giá thấp → cao' },
  { id: 'price_desc', label: 'Giá cao → thấp' },
]

function FilterBar({
  petType,
  sort,
  onType,
  onSort,
}: {
  petType: 'dog' | 'cat'
  sort: string
  onType: (t: 'dog' | 'cat') => void
  onSort: (s: string) => void
}) {
  const [stuck, setStuck] = useState(false)
  const sentinelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = sentinelRef.current
    if (!el) return
    const io = new IntersectionObserver(([e]) => setStuck(!e.isIntersecting), {
      rootMargin: '-62px 0px 0px 0px',
      threshold: 0,
    })
    io.observe(el)
    return () => io.disconnect()
  }, [])

  return (
    <>
      <div ref={sentinelRef} aria-hidden className="h-px" />
      <div className={`${styles.filterBar} ${stuck ? styles.filterBarStuck : ''}`}>
        <div className={styles.filterInner}>
          <div className={styles.segment}>
            <button
              onClick={() => onType('dog')}
              className={`${styles.segmentBtn} ${petType === 'dog' ? styles.segmentBtnActive : ''}`}
            >
              <span className="inline-flex items-center gap-1.5">
                <Dog size={14} />
                Cho chó
              </span>
            </button>
            <button
              onClick={() => onType('cat')}
              className={`${styles.segmentBtn} ${petType === 'cat' ? styles.segmentBtnActive : ''}`}
            >
              <span className="inline-flex items-center gap-1.5">
                <Cat size={14} />
                Cho mèo
              </span>
            </button>
          </div>

          <select
            value={sort}
            onChange={(e) => onSort(e.target.value)}
            className={styles.sortSelect}
            aria-label="Sắp xếp"
          >
            {SORT_OPTIONS.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>

          <Link
            to={`/shop?petType=${petType}`}
            className="ml-auto mr-1 shrink-0 whitespace-nowrap rounded-full bg-(--color-accent-soft) px-3.5 py-2 text-[12px] font-bold text-accent transition-colors hover:bg-accent hover:text-white"
          >
            Xem tất cả →
          </Link>
        </div>
      </div>
    </>
  )
}

/* ================================================================
   Product card.
   ================================================================ */

function RecProductCard({
  product: p,
  onAdd,
}: {
  product: RecProduct
  onAdd: (p: RecProduct) => void
}) {
  return (
    <article data-card className={styles.card}>
      <div className={styles.cardMedia}>
        <Link to={`/shop/${p.id}`} aria-label={p.name}>
          <img src={p.image} alt={p.name} loading="lazy" />
        </Link>

        {p.badge && <span className={styles.cardBadge}>{p.badge}</span>}

        <div className={styles.cardAction}>
          <button
            onClick={() => onAdd(p)}
            className="flex w-full items-center justify-center gap-1.5 rounded-lg bg-accent px-2 py-2 text-[12.5px] font-bold text-white shadow-[0_8px_16px_-8px_rgba(164,51,36,0.8)] transition-colors hover:bg-(--color-accent-hover) active:scale-[0.985]"
          >
            <Plus size={14} strokeWidth={2.5} />
            Thêm vào giỏ
          </button>
        </div>
      </div>

      <div className={styles.cardBody}>
        <p className={styles.cardCategory}>{CATEGORY_LABELS[p.category as keyof typeof CATEGORY_LABELS] ?? p.category}</p>

        <h3 className={styles.cardName}>
          <Link to={`/shop/${p.id}`}>{p.name}</Link>
        </h3>

        <div className={styles.cardRating}>
          <Star
            size={11}
            className="fill-(--color-brand-primary) text-(--color-brand-primary)"
          />
          <span className={styles.cardRatingStrong}>{p.rating.toFixed(1)}</span>
          <span>· đã bán {soldFmt.format(p.sold)}</span>
        </div>

        <div className={styles.cardFooter}>
          <span className={styles.cardPrice}>{vnd.format(p.price)}</span>
          {p.oldPrice && (
            <span className={styles.cardOldPrice}>{vnd.format(p.oldPrice)}</span>
          )}
        </div>
      </div>
    </article>
  )
}

/* ================================================================
   Main page.
   ================================================================ */

function loadPetsFromStorage(): Pet[] {
  try {
    const raw = localStorage.getItem('myPets')
    if (raw) return JSON.parse(raw)
  } catch {
    // ignore
  }
  return MOCK_PETS
}

export function RecommendPage() {
  const addItem = useCartStore((s) => s.addItem)

  const [pets] = useState<Pet[]>(loadPetsFromStorage)
  const [selectedPetId, setSelectedPetId] = useState<number>(() => pets[0]?.id ?? null)
  const [petType, setPetType] = useState<'dog' | 'cat'>(() => pets[0]?.type ?? 'dog')
  const [sort, setSort] = useState('popular')

  // Khi chọn pet mới, đồng bộ loài.
  const handleSelectPet = (pet: Pet) => {
    setSelectedPetId(pet.id)
    setPetType(pet.type)
  }

  const selectedPet = pets.find((p) => p.id === selectedPetId) ?? null

  const products = useMemo(() => {
    const list = getRecommendations(selectedPetId ?? 0).filter((p) => p.petType === petType)
    if (sort === 'price_asc') list.sort((a, b) => a.price - b.price)
    else if (sort === 'price_desc') list.sort((a, b) => b.price - a.price)
    else if (sort === 'rating') list.sort((a, b) => b.rating - a.rating)
    else list.sort((a, b) => b.sold - a.sold)
    return list
  }, [selectedPetId, petType, sort])

  const handleAdd = (p: RecProduct) => {
    addItem({
      id: p.id,
      productId: p.id,
      productName: p.name,
      quantity: 1,
      unitPrice: p.price,
      subtotal: p.price,
    })
    toast.success('Đã thêm vào giỏ', { description: p.name })
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* Breadcrumb */}
      <div className="mx-auto max-w-[1280px] px-5 py-4 sm:px-8">
        <nav aria-label="Đường dẫn" className="flex items-center gap-1.5 text-[12.5px] text-(--color-text-secondary)">
          <Link to="/" className="underline-offset-4 transition-colors hover:text-(--color-text-primary) hover:underline">
            Trang chủ
          </Link>
          <ChevronRight size={13} />
          <span className="font-semibold text-(--color-text-primary)">Gợi ý sản phẩm</span>
        </nav>
      </div>

      {/* 2-column layout */}
      <div className={`${styles.page} mx-auto max-w-[1280px] px-0`}>
        {/* ====== Sidebar ====== */}
        <aside className={styles.sidebar}>
          <div className={styles.sidebarHead}>
            <span className={styles.sidebarTitle}>
              <Heart size={14} />
              Thú cưng của bạn
            </span>
            <Link
              to="/account#pets"
              className="text-[11.5px] font-semibold text-accent underline-offset-4 transition-colors hover:text-(--color-accent-hover) hover:underline"
            >
              Quản lý
            </Link>
          </div>

          <div className={styles.sidebarList}>
            {pets.map((pet) => (
              <SidebarPetItem
                key={pet.id}
                pet={pet}
                active={isPetActive(pet, selectedPetId)}
                onClick={() => handleSelectPet(pet)}
              />
            ))}

            <button className={styles.addPetBtn}>
              <Plus size={15} />
              Thêm thú cưng
            </button>
          </div>
        </aside>

        {/* ====== Main ====== */}
        <div className="min-w-0">
          {/* Featured pet */}
          {selectedPet && <FeaturedPet pet={selectedPet} />}

          {/* Filter bar */}
          <FilterBar petType={petType} sort={sort} onType={setPetType} onSort={setSort} />

          {/* Product grid */}
          <div className={styles.main}>
            {products.length === 0 ? (
              <div className={styles.empty}>
                <Search size={48} strokeWidth={1.5} className="text-(--color-border-strong)" />
                <p className={styles.emptyTitle}>Chưa có gợi ý cho pet này</p>
                <p className={styles.emptyDesc}>
                  Thêm thú cưng của bạn để nhận gợi ý sản phẩm phù hợp.
                </p>
                <Link
                  to="/account#pets"
                  className="mt-4 rounded-full bg-accent px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)"
                >
                  Thêm thú cưng
                </Link>
              </div>
            ) : (
              <div className={styles.grid}>
                {products.map((p) => (
                  <RecProductCard key={p.id} product={p} onAdd={handleAdd} />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
