import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import gsap from 'gsap'
import { toast } from 'sonner'
import {
  Check,
  ChevronLeft,
  ChevronRight,
  ChevronDown,
  PawPrint,
  Plus,
  SlidersHorizontal,
  Star,
  Truck,
  X,
} from 'lucide-react'
import {
  BRANDS,
  BRAND_BY_ID,
  CATEGORIES,
  CATEGORY_LABELS,
  PET_TYPES,
  PRICE_BANDS,
  PRODUCTS,
  SORT_OPTIONS,
  type CategoryId,
  type ShopProduct,
} from './shop.mock'
import { useCartStore } from '../../shared/stores/cart.store'
import styles from './ShopPage.module.css'

const PAGE_SIZE = 60

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

const soldFmt = new Intl.NumberFormat('vi-VN')

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}

export function ShopPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const addItem = useCartStore((s) => s.addItem)

  const searchQuery = (searchParams.get('q') || '').trim().toLowerCase()

  const [petTypes, setPetTypes] = useState<string[]>(() => {
    const p = searchParams.get('petType')
    return p ? [p] : []
  })
  const [categories, setCategories] = useState<CategoryId[]>(() => {
    const c = searchParams.get('category') as CategoryId | null
    return c ? [c] : []
  })
  const [brands, setBrands] = useState<string[]>([])
  const [bands, setBands] = useState<string[]>([])
  const [sort, setSort] = useState<string>('popular')
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const gridRef = useRef<HTMLDivElement>(null)
  const anchorRef = useRef<HTMLDivElement>(null)

  const goToPage = (next: number) => {
    setPage(next)
    // Cuộn về đầu lưới, chừa chỗ cho header + thanh lọc đang dính.
    const top = (anchorRef.current?.getBoundingClientRect().top ?? 0) + window.scrollY - 130
    window.scrollTo({ top, behavior: 'smooth' })
  }

  const products = useMemo(() => {
    const list = PRODUCTS.filter((p) => {
      if (searchQuery && !p.name.toLowerCase().includes(searchQuery)) return false
      if (petTypes.length && !petTypes.includes(p.petType)) return false
      if (categories.length && !categories.includes(p.category)) return false
      if (brands.length && !brands.includes(p.brand)) return false
      if (bands.length) {
        const hit = bands.some((id) => {
          const band = PRICE_BANDS.find((b) => b.id === id)
          return band ? p.price >= band.min && p.price < band.max : false
        })
        if (!hit) return false
      }
      return true
    })

    if (sort === 'price_asc') list.sort((a, b) => a.price - b.price)
    else if (sort === 'price_desc') list.sort((a, b) => b.price - a.price)
    else if (sort === 'rating') list.sort((a, b) => b.rating - a.rating)
    else if (sort === 'newest') list.sort((a, b) => b.id - a.id)
    else list.sort((a, b) => b.sold - a.sold)

    return list
  }, [searchQuery, petTypes, categories, brands, bands, sort])

  const totalPages = Math.max(1, Math.ceil(products.length / PAGE_SIZE))
  const shown = products.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  // Đổi bộ lọc → về trang đầu + nháy skeleton để người dùng thấy lưới đã tải lại.
  useEffect(() => {
    setPage(1)
    setLoading(true)
    const t = window.setTimeout(() => setLoading(false), 260)
    return () => window.clearTimeout(t)
  }, [petTypes, categories, brands, bands, sort])

  // Giữ URL khớp bộ lọc để chia sẻ được link.
  useEffect(() => {
    const next = new URLSearchParams(searchParams)
    if (petTypes.length === 1) next.set('petType', petTypes[0])
    else next.delete('petType')
    if (categories.length === 1) next.set('category', categories[0])
    else next.delete('category')
    if (next.toString() !== searchParams.toString()) {
      setSearchParams(next, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [petTypes, categories])

  useLayoutEffect(() => {
    if (loading || !gridRef.current) return
    const ctx = gsap.context(() => {
      gsap.fromTo(
        '[data-card]',
        { opacity: 0, y: 26 },
        { opacity: 1, y: 0, duration: 0.55, stagger: 0.05, ease: 'power2.out' },
      )
    }, gridRef)
    return () => ctx.revert()
  }, [loading, page, shown.length])

  // Khoá cuộn nền khi mở ngăn lọc trên mobile.
  useEffect(() => {
    if (!drawerOpen) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [drawerOpen])

  const activeChips = [
    ...petTypes.map((id) => ({
      key: `pet-${id}`,
      label: PET_TYPES.find((p) => p.id === id)?.label ?? id,
      clear: () => setPetTypes((a) => toggle(a, id)),
    })),
    ...categories.map((id) => ({
      key: `cat-${id}`,
      label: CATEGORY_LABELS[id],
      clear: () => setCategories((a) => toggle(a, id)),
    })),
    ...bands.map((id) => ({
      key: `band-${id}`,
      label: PRICE_BANDS.find((b) => b.id === id)?.label ?? id,
      clear: () => setBands((a) => toggle(a, id)),
    })),
    ...brands.map((id) => ({
      key: `brand-${id}`,
      label: BRAND_BY_ID[id]?.label ?? id,
      clear: () => setBrands((a) => toggle(a, id)),
    })),
  ]

  const clearAll = () => {
    setPetTypes([])
    setCategories([])
    setBrands([])
    setBands([])
  }

  const handleAdd = (p: ShopProduct) => {
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
    <div className="bg-(--color-surface-page)">
      <ShopHero total={PRODUCTS.length} />

      <FilterBar
        petTypes={petTypes}
        categories={categories}
        onCategory={(id) => setCategories((a) => toggle(a, id))}
        bands={bands}
        brands={brands}
        sort={sort}
        count={products.length}
        activeCount={activeChips.length}
        onPetType={(id) => setPetTypes((a) => toggle(a, id))}
        onBand={(id) => setBands((a) => toggle(a, id))}
        onBrand={(id) => setBrands((a) => toggle(a, id))}
        onSort={setSort}
        onOpenDrawer={() => setDrawerOpen(true)}
      />

      <main className="mx-auto w-full max-w-[1440px] px-5 pb-24 sm:px-8">
        {activeChips.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 py-5">
            <span className="text-[13px] text-(--color-text-secondary)">Đang lọc:</span>
            {activeChips.map((chip) => (
              <button
                key={chip.key}
                onClick={chip.clear}
                className="inline-flex items-center gap-1.5 rounded-full bg-(--color-accent-soft) py-1.5 pr-2.5 pl-3 text-[13px] font-semibold text-accent transition-colors hover:bg-accent hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
              >
                {chip.label}
                <X size={13} strokeWidth={2.5} />
              </button>
            ))}
            <button
              onClick={clearAll}
              className="ml-1 text-[13px] font-semibold text-(--color-text-secondary) underline-offset-4 hover:text-accent hover:underline"
            >
              Xoá hết
            </button>
          </div>
        )}

        <div ref={anchorRef} />

        <div ref={gridRef} className={activeChips.length > 0 ? '' : 'pt-6'}>
          {loading ? (
            <ProductGridSkeleton />
          ) : products.length === 0 ? (
            <EmptyState onClear={clearAll} />
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 lg:gap-4 xl:grid-cols-5 2xl:grid-cols-6">
                {shown.map((p) => (
                  <ProductCard key={p.id} product={p} onAdd={handleAdd} />
                ))}
              </div>

              <Pagination
                page={page}
                totalPages={totalPages}
                from={(page - 1) * PAGE_SIZE + 1}
                to={Math.min(page * PAGE_SIZE, products.length)}
                total={products.length}
                onChange={goToPage}
              />
            </>
          )}
        </div>
      </main>

      {drawerOpen && (
        <FilterDrawer
          petTypes={petTypes}
          categories={categories}
          bands={bands}
          brands={brands}
          resultCount={products.length}
          onPetType={(id) => setPetTypes((a) => toggle(a, id))}
          onCategory={(id) => setCategories((a) => toggle(a, id))}
          onBand={(id) => setBands((a) => toggle(a, id))}
          onBrand={(id) => setBrands((a) => toggle(a, id))}
          onClear={clearAll}
          onClose={() => setDrawerOpen(false)}
        />
      )}
    </div>
  )
}

/* ------------------------------------------------------------- phân trang */

// Dải số trang có rút gọn: 1 … 4 [5] 6 … 12
function pageWindow(page: number, total: number): (number | 'gap')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const out: (number | 'gap')[] = [1]
  const from = Math.max(2, page - 1)
  const to = Math.min(total - 1, page + 1)
  if (from > 2) out.push('gap')
  for (let i = from; i <= to; i++) out.push(i)
  if (to < total - 1) out.push('gap')
  out.push(total)
  return out
}

function Pagination({
  page,
  totalPages,
  from,
  to,
  total,
  onChange,
}: {
  page: number
  totalPages: number
  from: number
  to: number
  total: number
  onChange: (p: number) => void
}) {
  return (
    <nav
      aria-label="Phân trang"
      className="mt-10 flex flex-col items-center gap-4 border-t border-(--color-border-default) pt-8"
    >
      <p className="text-[13px] text-(--color-text-secondary) tabular-nums">
        {from}–{to} trên {total} sản phẩm
      </p>

      {totalPages > 1 && (
        <div className="flex items-center gap-1.5">
          <PageBtn disabled={page === 1} onClick={() => onChange(page - 1)} label="Trang trước">
            <ChevronLeft size={16} />
          </PageBtn>

          {pageWindow(page, totalPages).map((n, i) =>
            n === 'gap' ? (
              <span key={`gap-${i}`} className="px-1 text-(--color-text-secondary)">
                …
              </span>
            ) : (
              <button
                key={n}
                onClick={() => onChange(n)}
                aria-current={n === page ? 'page' : undefined}
                className={`h-9 min-w-9 rounded-full px-3 text-[13px] font-bold tabular-nums transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent) ${
                  n === page
                    ? 'bg-accent text-white'
                    : 'bg-white text-(--color-text-primary) ring-1 ring-(--color-border-default) ring-inset hover:text-accent'
                }`}
              >
                {n}
              </button>
            ),
          )}

          <PageBtn
            disabled={page === totalPages}
            onClick={() => onChange(page + 1)}
            label="Trang sau"
          >
            <ChevronRight size={16} />
          </PageBtn>
        </div>
      )}
    </nav>
  )
}

function PageBtn({
  disabled,
  onClick,
  label,
  children,
}: {
  disabled: boolean
  onClick: () => void
  label: string
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      className="grid h-9 w-9 place-items-center rounded-full bg-white text-(--color-text-primary) ring-1 ring-(--color-border-default) ring-inset transition-colors hover:text-accent disabled:pointer-events-none disabled:opacity-35 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
    >
      {children}
    </button>
  )
}

/* ---------------------------------------------------------------- hero slab */

function ShopHero({ total }: { total: number }) {
  return (
    <section className={`${styles.slab} px-5 py-7 sm:px-8`}>
      <img
        src="/imgs/download3.png"
        alt=""
        className={styles.slabDoodle}
        style={{ top: 12, left: '3%', width: 44 }}
      />

      <div
        className={`${styles.slabInner} mx-auto flex w-full max-w-[1440px] flex-wrap items-end justify-between gap-x-8 gap-y-3`}
      >
        <div>
          <nav aria-label="Đường dẫn" className="mb-2 text-[12.5px] text-white/50">
            <Link
              to="/"
              className="underline-offset-4 transition-colors hover:text-white hover:underline"
            >
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Danh mục sản phẩm</span>
          </nav>

          <h1 className="font-bayon text-[clamp(26px,3.4vw,40px)] leading-[1.02] font-normal text-white">
            Cửa hàng của boss
          </h1>
        </div>

        <p className="flex flex-wrap items-center gap-x-4 gap-y-1 pb-1 text-[12.5px] text-white/60">
          <span className="inline-flex items-center gap-1.5">
            <PawPrint size={13} className="text-(--color-accent-warm)" />
            <span className="tabular-nums">{total}</span> sản phẩm
          </span>
          <span aria-hidden className="text-white/25">
            ·
          </span>
          <span className="tabular-nums">4.9/5 hài lòng</span>
          <span aria-hidden className="text-white/25">
            ·
          </span>
          <span className="inline-flex items-center gap-1.5">
            <Truck size={13} />
            Giao 2 giờ nội thành
          </span>
        </p>
      </div>
    </section>
  )
}

/* -------------------------------------------------------------- filter bar */

function FilterBar({
  petTypes,
  categories,
  bands,
  brands,
  sort,
  count,
  activeCount,
  onPetType,
  onCategory,
  onBand,
  onBrand,
  onSort,
  onOpenDrawer,
}: {
  petTypes: string[]
  categories: CategoryId[]
  bands: string[]
  brands: string[]
  sort: string
  count: number
  activeCount: number
  onPetType: (id: string) => void
  onCategory: (id: CategoryId) => void
  onBand: (id: string) => void
  onBrand: (id: string) => void
  onSort: (id: string) => void
  onOpenDrawer: () => void
}) {
  const [stuck, setStuck] = useState(false)
  const sentinelRef = useRef<HTMLDivElement>(null)

  // Sentinel ngay trên thanh lọc: rời khỏi tầm nhìn = thanh đã dính.
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
        <div className="mx-auto flex max-w-[1440px] items-center gap-3 px-5 py-2.5 sm:px-8">
          <button
            onClick={onOpenDrawer}
            className="inline-flex shrink-0 items-center gap-2 rounded-full border border-(--color-border-default) bg-white px-4 py-2 text-[13px] font-bold text-(--color-text-primary) lg:hidden"
          >
            <SlidersHorizontal size={15} />
            Bộ lọc{activeCount > 0 && ` (${activeCount})`}
          </button>

          <div className="hidden shrink-0 lg:block">
            <SegmentGroup items={PET_TYPES} selected={petTypes} onToggle={onPetType} />
          </div>

          {/* Danh mục — thay cho rail cũ, cuộn ngang khi hẹp. */}
          <div className={styles.catStrip}>
            {CATEGORIES.map((c) => {
              const active = categories.includes(c.id)
              return (
                <button
                  key={c.id}
                  onClick={() => onCategory(c.id)}
                  aria-pressed={active}
                  className={`${styles.catChip} ${active ? styles.catChipActive : ''} focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)`}
                >
                  <img src={c.photo} alt="" loading="lazy" />
                  {c.label}
                </button>
              )
            })}
          </div>

          <div className="hidden shrink-0 items-center gap-2 lg:flex">
            <span className="h-6 w-px bg-(--color-border-default)" />

            <DropdownFilter
              label="Giá"
              count={bands.length}
              items={PRICE_BANDS.map((b) => ({ id: b.id, label: b.label }))}
              selected={bands}
              onToggle={onBand}
            />

            <DropdownFilter
              label="Thương hiệu"
              count={brands.length}
              width={330}
              columns={2}
              items={BRANDS.map((b) => ({ id: b.id, label: b.label, logo: b.logo }))}
              selected={brands}
              onToggle={onBrand}
            />
          </div>

          <div className="ml-auto flex shrink-0 items-center gap-3">
            <span className="hidden text-[13px] text-(--color-text-secondary) tabular-nums xl:inline">
              {count} sản phẩm
            </span>
            <select
              value={sort}
              onChange={(e) => onSort(e.target.value)}
              aria-label="Sắp xếp"
              className="cursor-pointer rounded-full border border-(--color-border-default) bg-white px-3.5 py-2 text-[13px] font-semibold text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
            >
              {SORT_OPTIONS.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>
    </>
  )
}

// Dropdown chọn nhiều — dùng chung cho Giá và Thương hiệu.
function DropdownFilter({
  label,
  count,
  items,
  selected,
  onToggle,
  width = 220,
  columns = 1,
}: {
  label: string
  count: number
  items: { id: string; label: string; logo?: string }[]
  selected: string[]
  onToggle: (id: string) => void
  width?: number
  columns?: number
}) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={wrapRef} className="relative">
      <FilterPill active={count > 0} onClick={() => setOpen((v) => !v)}>
        {label}
        {count > 0 && ` · ${count}`}
        <ChevronDown
          size={14}
          className={open ? 'rotate-180 transition-transform' : 'transition-transform'}
        />
      </FilterPill>

      {open && (
        <div
          style={{ width, gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
          className="absolute top-[calc(100%+8px)] left-0 z-40 grid gap-1 rounded-2xl bg-white p-2 shadow-[0_24px_48px_-24px_rgba(56,36,23,0.55)]"
        >
          {items.map((it) => {
            const active = selected.includes(it.id)
            return (
              <button
                key={it.id}
                onClick={() => onToggle(it.id)}
                className={`flex items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-[13px] font-semibold transition-colors ${active ? 'bg-(--color-accent-soft) text-accent' : 'text-(--color-text-primary) hover:bg-(--color-surface-2)'}`}
              >
                {it.logo && (
                  <img
                    src={it.logo}
                    alt=""
                    className="h-7 w-7 shrink-0 rounded-lg object-cover"
                    loading="lazy"
                  />
                )}
                <span className="truncate">{it.label}</span>
                {active && <Check size={14} className="ml-auto shrink-0" />}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

function SegmentGroup({
  items,
  selected,
  onToggle,
}: {
  items: readonly { id: string; label: string }[]
  selected: string[]
  onToggle: (id: string) => void
}) {
  return (
    <div className="flex rounded-full bg-(--color-surface-2) p-1">
      {items.map((it) => {
        const active = selected.includes(it.id)
        return (
          <button
            key={it.id}
            onClick={() => onToggle(it.id)}
            aria-pressed={active}
            className={`rounded-full px-4 py-1.5 text-[13px] font-bold transition-colors ${active ? 'bg-accent text-white' : 'text-(--color-text-secondary) hover:text-(--color-text-primary)'}`}
          >
            {it.label}
          </button>
        )
      })}
    </div>
  )
}

function FilterPill({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`inline-flex items-center gap-1.5 rounded-full px-3.5 py-2 text-[13px] font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent) ${
        active
          ? 'bg-accent text-white'
          : 'bg-white text-(--color-text-secondary) ring-1 ring-(--color-border-default) ring-inset hover:text-(--color-text-primary)'
      }`}
    >
      {children}
    </button>
  )
}

/* ------------------------------------------------------------ product card */

function ProductCard({ product: p, onAdd }: { product: ShopProduct; onAdd: (p: ShopProduct) => void }) {
  const brand = BRAND_BY_ID[p.brand]

  return (
    <article data-card className={styles.card}>
      <div className={styles.cardMedia}>
        <Link to={`/shop/${p.id}`} aria-label={p.name}>
          <img src={p.image} alt={p.name} loading="lazy" />
        </Link>

        {p.badge && (
          <span className="absolute top-2 left-2 rounded bg-accent px-1.5 py-0.5 text-[10px] font-bold tracking-wide text-white uppercase">
            {p.badge}
          </span>
        )}

        <div className={styles.cardAction}>
          <button
            onClick={() => onAdd(p)}
            className="flex w-full items-center justify-center gap-1.5 rounded-lg bg-accent px-2 py-2 text-[12.5px] font-bold text-white shadow-[0_10px_20px_-10px_rgba(164,51,36,0.8)] transition-colors hover:bg-(--color-accent-hover) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent) active:scale-[0.985]"
          >
            <Plus size={14} strokeWidth={2.5} />
            Thêm vào giỏ
          </button>
        </div>
      </div>

      <div className="flex flex-1 flex-col p-3">
        <p className="mb-1 truncate text-[10px] font-semibold tracking-[0.1em] text-(--color-text-secondary) uppercase">
          {CATEGORY_LABELS[p.category]}
          {brand && ` · ${brand.label}`}
        </p>

        <h3 className="mb-1.5 line-clamp-2 min-h-[2.6em] text-[13px] leading-[1.3] font-bold">
          <Link to={`/shop/${p.id}`} className="hover:text-accent">
            {p.name}
          </Link>
        </h3>

        <div className="flex items-center gap-1 text-[11.5px] text-(--color-text-secondary)">
          <Star
            size={11}
            className="fill-(--color-brand-primary) text-(--color-brand-primary)"
          />
          <span className="font-semibold text-(--color-text-primary)">
            {p.rating.toFixed(1)}
          </span>
          <span aria-hidden>·</span>
          <span className="tabular-nums">đã bán {soldFmt.format(p.sold)}</span>
        </div>

        <div className="mt-auto flex min-h-[24px] flex-wrap items-baseline gap-x-1.5 pt-3">
          <span className="font-friendly text-[16px] font-extrabold tabular-nums">
            {vnd.format(p.price)}
          </span>
          {p.oldPrice && (
            <span className="text-[11.5px] text-(--color-text-secondary) line-through tabular-nums">
              {vnd.format(p.oldPrice)}
            </span>
          )}
        </div>
      </div>
    </article>
  )
}

/* -------------------------------------------------------- skeleton + trống */

function ProductGridSkeleton() {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 lg:gap-4 xl:grid-cols-5 2xl:grid-cols-6">
      {Array.from({ length: 15 }).map((_, i) => (
        <div key={i} className={`${styles.skeleton} h-[248px]`} />
      ))}
    </div>
  )
}

function EmptyState({ onClear }: { onClear: () => void }) {
  return (
    <div className="flex flex-col items-center rounded-3xl bg-(--color-surface-2) px-6 py-20 text-center">
      <img src="/imgs/download11.png" alt="" className="mb-5 w-16 opacity-40" />
      <p className="font-friendly text-[20px] font-extrabold">
        Chưa có sản phẩm nào khớp
      </p>
      <p className="mt-2 max-w-[42ch] text-[14px] text-(--color-text-secondary)">
        Bộ lọc hiện tại hơi hẹp. Bỏ bớt một vài điều kiện để xem thêm lựa chọn cho boss nhà bạn.
      </p>
      <button
        onClick={onClear}
        className="mt-6 rounded-full bg-accent px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)"
      >
        Xoá bộ lọc
      </button>
    </div>
  )
}

/* ----------------------------------------------------------- mobile drawer */

function FilterDrawer({
  petTypes,
  categories,
  bands,
  brands,
  resultCount,
  onPetType,
  onCategory,
  onBand,
  onBrand,
  onClear,
  onClose,
}: {
  petTypes: string[]
  categories: CategoryId[]
  bands: string[]
  brands: string[]
  resultCount: number
  onPetType: (id: string) => void
  onCategory: (id: CategoryId) => void
  onBand: (id: string) => void
  onBrand: (id: string) => void
  onClear: () => void
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 lg:hidden"
      role="dialog"
      aria-modal="true"
      aria-label="Bộ lọc sản phẩm"
    >
      <div className="absolute inset-0 bg-[#382417]/55" onClick={onClose} />
      <div className="absolute inset-x-0 bottom-0 max-h-[86vh] overflow-y-auto rounded-t-3xl bg-white p-6 pb-28">
        <div className="mb-6 flex items-center justify-between">
          <span className="font-friendly text-[18px] font-extrabold">Bộ lọc</span>
          <button
            onClick={onClose}
            aria-label="Đóng bộ lọc"
            className="rounded-full p-1.5 hover:bg-(--color-surface-2)"
          >
            <X size={20} />
          </button>
        </div>

        <DrawerGroup title="Loại thú cưng">
          {PET_TYPES.map((t) => (
            <DrawerPill key={t.id} active={petTypes.includes(t.id)} onClick={() => onPetType(t.id)}>
              {t.label}
            </DrawerPill>
          ))}
        </DrawerGroup>

        <DrawerGroup title="Danh mục">
          {CATEGORIES.map((c) => (
            <DrawerPill key={c.id} active={categories.includes(c.id)} onClick={() => onCategory(c.id)}>
              {c.label}
            </DrawerPill>
          ))}
        </DrawerGroup>

        <DrawerGroup title="Khoảng giá">
          {PRICE_BANDS.map((b) => (
            <DrawerPill key={b.id} active={bands.includes(b.id)} onClick={() => onBand(b.id)}>
              {b.label}
            </DrawerPill>
          ))}
        </DrawerGroup>

        <DrawerGroup title="Thương hiệu">
          {BRANDS.map((b) => (
            <DrawerPill key={b.id} active={brands.includes(b.id)} onClick={() => onBrand(b.id)}>
              {b.label}
            </DrawerPill>
          ))}
        </DrawerGroup>

        <div className="fixed inset-x-0 bottom-0 flex gap-3 border-t border-(--color-border-default) bg-white p-4">
          <button
            onClick={onClear}
            className="flex-1 rounded-full border border-(--color-border-default) py-3 text-[14px] font-bold text-(--color-text-primary)"
          >
            Xoá hết
          </button>
          <button
            onClick={onClose}
            className="flex-[1.6] rounded-full bg-accent py-3 text-[14px] font-bold text-white active:scale-[0.985]"
          >
            Xem {resultCount} sản phẩm
          </button>
        </div>
      </div>
    </div>
  )
}

function DrawerGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mb-7">
      <h4 className="mb-3 text-[12px] font-bold tracking-[0.14em] text-(--color-text-secondary) uppercase">
        {title}
      </h4>
      <div className="flex flex-wrap gap-2">{children}</div>
    </section>
  )
}

function DrawerPill({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-full px-4 py-2 text-[13px] font-semibold transition-colors ${
        active
          ? 'bg-accent text-white'
          : 'bg-(--color-surface-2) text-(--color-text-primary)'
      }`}
    >
      {children}
    </button>
  )
}
