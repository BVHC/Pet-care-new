import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import gsap from 'gsap'
import { ChevronLeft, ChevronRight, MapPin, PawPrint, RotateCw, Search, SlidersHorizontal, Store, WifiOff, X } from 'lucide-react'
import { usePublicBranches, usePublicProductCategories, usePublicProducts } from '@/shared/hooks/usePublicSite'
import type { PublicBranch } from '@/shared/types/branch'
import type { PublicProduct, PublicProductQuery } from '@/shared/types/public-site'
import { branchNamesByIds, productPriceLabel } from '@/shared/utils/public-format'
import styles from './ShopPage.module.css'

const PAGE_SIZE = 24
/** BE trả 400 khi `q` dài hơn 100 ký tự (catalog-v1 #21). */
const MAX_Q = 100
const DEBOUNCE_MS = 300

/** "3" → 3; mọi thứ khác ("food", "-1", "1.5", rỗng) → undefined, tham số lạ trên URL bị bỏ qua thay vì gửi lên API. */
const positiveInt = (raw: string | null) => (raw && /^[1-9]\d{0,8}$/.test(raw) ? Number(raw) : undefined)

type Patch = Partial<Record<'q' | 'category' | 'branch' | 'page', string | number | null>>

export function ShopPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  // URL là nguồn sự thật của bộ lọc (chia sẻ link được). `?petType=` của mega-menu cũ và mọi khóa lạ khác không được đọc.
  const urlQ = searchParams.get('q') ?? ''
  const q = urlQ.trim().slice(0, MAX_Q)
  const categoryId = positiveInt(searchParams.get('category'))
  const branchId = positiveInt(searchParams.get('branch'))
  const page = positiveInt(searchParams.get('page')) ?? 1

  const query: PublicProductQuery = {
    ...(q ? { q } : {}),
    ...(categoryId ? { categoryId } : {}),
    ...(branchId ? { branchId } : {}),
    page: page - 1, // UI đếm từ 1, BE đếm từ 0
    size: PAGE_SIZE,
  }
  const products = usePublicProducts(query)
  const categories = usePublicProductCategories()
  const branches = usePublicBranches()

  const [drawerOpen, setDrawerOpen] = useState(false)
  const gridRef = useRef<HTMLDivElement>(null)
  const anchorRef = useRef<HTMLDivElement>(null)

  // Hàm ghi URL lấy searchParams mới nhất lúc gọi, kể cả khi gọi từ timeout của ô tìm (react-router 6 không xếp hàng cập nhật hàm).
  const latest = useRef({ searchParams, setSearchParams })
  useEffect(() => {
    latest.current = { searchParams, setSearchParams }
  })
  const patch = useCallback((changes: Patch, replace = false) => {
    const { searchParams: current, setSearchParams: write } = latest.current
    const next = new URLSearchParams(current)
    for (const [key, value] of Object.entries(changes)) {
      if (value === null || value === '') next.delete(key)
      else next.set(key, String(value))
    }
    write(next, { replace })
  }, [])

  // Ô tìm: gõ chỉ đổi chữ trong ô, ~300ms sau phím cuối (hoặc Enter) mới ghi q lên URL nên không bắn mỗi phím một request.
  const [text, setText] = useState(urlQ)
  const applied = useRef(q)
  const timer = useRef<number>()
  const applyQ = useCallback(
    (value: string) => {
      window.clearTimeout(timer.current)
      const next = value.trim().slice(0, MAX_Q)
      if (next === applied.current) return
      applied.current = next
      patch({ q: next, page: null }, true)
    },
    [patch],
  )
  const onType = (value: string) => {
    setText(value)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => applyQ(value), DEBOUNCE_MS)
  }
  // URL đổi từ ngoài (ô tìm ở header, nút xóa bộ lọc, Back) thì kéo ô nhập theo, không đè lên chữ đang gõ.
  useEffect(() => {
    if (q === applied.current) return
    applied.current = q
    window.clearTimeout(timer.current)
    setText(urlQ)
  }, [q, urlQ])
  useEffect(() => () => window.clearTimeout(timer.current), [])

  const goToPage = (next: number) => {
    patch({ page: next > 1 ? next : null })
    // Cuộn về đầu lưới, chừa chỗ cho header + thanh lọc đang dính.
    const top = (anchorRef.current?.getBoundingClientRect().top ?? 0) + window.scrollY - 130
    window.scrollTo({ top, behavior: 'smooth' })
  }
  const pickCategory = (id: number | null) => patch({ category: id, page: null })
  const pickBranch = (id: number | null) => patch({ branch: id, page: null })
  const clearAll = () => {
    window.clearTimeout(timer.current)
    setSearchParams({}, { replace: true })
  }

  const data = products.data
  const items = data?.content
  const placeholder = products.isPlaceholderData

  // Thẻ vào khung nhẹ nhàng mỗi khi có trang dữ liệu mới; người chọn giảm chuyển động thì không animate.
  useLayoutEffect(() => {
    if (!gridRef.current || !items?.length || placeholder) return
    const mm = gsap.matchMedia()
    mm.add(
      '(prefers-reduced-motion: no-preference)',
      () => {
        gsap.fromTo(
          '[data-card]',
          { opacity: 0, y: 26 },
          { opacity: 1, y: 0, duration: 0.55, stagger: 0.05, ease: 'power2.out', clearProps: 'opacity,transform' },
        )
      },
      gridRef,
    )
    return () => mm.revert()
  }, [items, placeholder])

  // Khoá cuộn nền khi mở ngăn lọc trên mobile.
  useEffect(() => {
    if (!drawerOpen) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [drawerOpen])

  const branchList = branches.data
  const categoryName = categoryId ? (categories.data?.find((c) => c.categoryId === categoryId)?.name ?? 'Danh mục') : undefined
  const branchName = branchId ? (branchList?.find((b) => b.branchId === branchId)?.name ?? 'chi nhánh đã chọn') : undefined
  const activeChips = [
    ...(q ? [{ key: 'q', label: `Từ khóa "${q}"`, clear: () => patch({ q: null, page: null }) }] : []),
    ...(categoryName ? [{ key: 'category', label: categoryName, clear: () => pickCategory(null) }] : []),
    ...(branchName ? [{ key: 'branch', label: `Còn hàng tại ${branchName}`, clear: () => pickBranch(null) }] : []),
  ]
  const total = data?.totalElements ?? 0

  return (
    <div className="bg-(--color-surface-page)">
      <ShopHero />

      <FilterBar
        text={text}
        onType={onType}
        onSubmit={() => applyQ(text)}
        categories={categories.data ?? []}
        categoryId={categoryId}
        onCategory={pickCategory}
        branches={branchList ?? []}
        branchId={branchId}
        onBranch={pickBranch}
        count={data ? total : null}
        activeCount={activeChips.length}
        onOpenDrawer={() => setDrawerOpen(true)}
      />

      <div className="mx-auto w-full max-w-[1440px] px-5 pb-24 sm:px-8">
        {activeChips.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 py-5">
            <span className="text-[13px] text-(--color-text-secondary)">Đang lọc:</span>
            {activeChips.map((chip) => (
              <button
                key={chip.key}
                type="button"
                onClick={chip.clear}
                aria-label={`Bỏ lọc ${chip.label}`}
                className="inline-flex items-center gap-1.5 rounded-full bg-(--color-accent-soft) py-1.5 pr-2.5 pl-3 text-[13px] font-semibold text-accent transition-colors hover:bg-accent hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
              >
                {chip.label}
                <X size={13} strokeWidth={2.5} aria-hidden />
              </button>
            ))}
            <button
              type="button"
              onClick={clearAll}
              className="ml-1 text-[13px] font-semibold text-(--color-text-secondary) underline-offset-4 hover:text-accent hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
            >
              Xóa hết
            </button>
          </div>
        )}

        <div ref={anchorRef} />

        <div ref={gridRef} className={activeChips.length > 0 ? '' : 'pt-6'}>
          {products.isError ? (
            <LoadError retrying={products.isFetching} onRetry={() => void products.refetch()} />
          ) : !data ? (
            <ProductGridSkeleton />
          ) : items!.length === 0 ? (
            <EmptyState onClear={clearAll} />
          ) : (
            <>
              <h2 className="sr-only">Danh sách sản phẩm</h2>
              <div
                aria-busy={placeholder}
                className={`grid grid-cols-2 gap-3 transition-opacity sm:grid-cols-3 lg:grid-cols-4 lg:gap-4 xl:grid-cols-5 2xl:grid-cols-6 ${placeholder ? 'opacity-60' : ''}`}
              >
                {items!.map((p) => (
                  <ProductCard key={p.productId} product={p} branches={branchList} branchesLoading={branches.isPending} />
                ))}
              </div>

              <Pagination
                page={page}
                totalPages={data.totalPages}
                from={(page - 1) * PAGE_SIZE + 1}
                to={(page - 1) * PAGE_SIZE + items!.length}
                total={total}
                onChange={goToPage}
              />
            </>
          )}
        </div>
      </div>

      {drawerOpen && (
        <FilterDrawer
          categories={categories.data ?? []}
          categoryId={categoryId}
          onCategory={(id) => pickCategory(id === categoryId ? null : id)}
          branches={branchList ?? []}
          branchId={branchId}
          onBranch={(id) => pickBranch(id === branchId ? null : id)}
          resultCount={total}
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
                type="button"
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
            disabled={page >= totalPages}
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
      type="button"
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

function ShopHero() {
  return (
    <section className="relative overflow-hidden bg-gradient-to-b from-[#fdf8f3] via-[#fbf3e8] to-[#f6ebd9] border-b border-[#ebdccb] px-5 py-7 sm:px-8 sm:py-9">
      {/* Decorative ambient glow */}
      <div
        aria-hidden
        className="pointer-events-none absolute -right-16 -top-16 h-56 w-56 rounded-full bg-accent/6 blur-3xl"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute left-1/4 -bottom-12 h-44 w-44 rounded-full bg-[#ffc107]/10 blur-2xl"
      />

      <div className="relative z-10 mx-auto flex w-full max-w-[1440px] flex-wrap items-end justify-between gap-x-8 gap-y-4">
        <div>
          <nav aria-label="Đường dẫn" className="mb-2 flex items-center gap-1.5 text-[12.5px] font-medium text-[#8a7565]">
            <Link
              to="/"
              className="underline-offset-4 transition-colors hover:text-accent hover:underline"
            >
              Trang chủ
            </Link>
            <span className="text-[#8a7565]/40">/</span>
            <span className="font-bold text-[#3b2a1e]">Danh mục sản phẩm</span>
          </nav>

          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-friendly font-extrabold text-[clamp(28px,3.4vw,42px)] leading-tight text-[#3b2a1e]">
              Cửa hàng của boss
            </h1>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-[#faebe4] px-3 py-1 text-xs font-bold text-accent border border-accent/15 shadow-2xs">
              <PawPrint size={13} className="fill-current" aria-hidden /> Chính hãng 100%
            </span>
          </div>
          <p className="mt-1 text-[13px] sm:text-sm text-[#70584b] font-medium max-w-xl">
            Thức ăn, phụ kiện và sản phẩm chăm sóc sức khỏe cho thú cưng, bán tại quầy các chi nhánh PetCare.
          </p>
        </div>

        {/* Không có đặt mua online (BR-CK-03): dẫn khách tới chi nhánh. */}
        <div className="flex flex-wrap items-center gap-2 sm:gap-2.5 pb-0.5">
          <div className="inline-flex items-center gap-1.5 rounded-full bg-white/90 px-3.5 py-1.5 text-[12.5px] font-bold text-[#3b2a1e] shadow-xs border border-[#ebdccb]/80 backdrop-blur-xs">
            <Store size={14} className="text-accent" aria-hidden />
            <span>Mua trực tiếp tại quầy</span>
          </div>

          <Link
            to="/branches"
            className="inline-flex items-center gap-1.5 rounded-full bg-white/90 px-3.5 py-1.5 text-[12.5px] font-bold text-[#3b2a1e] shadow-xs border border-[#ebdccb]/80 backdrop-blur-xs transition-colors hover:text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
          >
            <MapPin size={14} className="text-emerald-600" aria-hidden />
            <span>Xem chi nhánh</span>
          </Link>
        </div>
      </div>
    </section>
  )
}

/* -------------------------------------------------------------- filter bar */

interface BarCategory {
  categoryId: number
  name: string
}

function FilterBar({
  text,
  onType,
  onSubmit,
  categories,
  categoryId,
  onCategory,
  branches,
  branchId,
  onBranch,
  count,
  activeCount,
  onOpenDrawer,
}: {
  text: string
  onType: (value: string) => void
  onSubmit: () => void
  categories: BarCategory[]
  categoryId: number | undefined
  onCategory: (id: number | null) => void
  branches: Pick<PublicBranch, 'branchId' | 'name'>[]
  branchId: number | undefined
  onBranch: (id: number | null) => void
  count: number | null
  activeCount: number
  onOpenDrawer: () => void
}) {
  const [stuck, setStuck] = useState(false)
  const sentinelRef = useRef<HTMLDivElement>(null)

  // Sentinel ngay trên thanh lọc: rời khỏi tầm nhìn = thanh đã dính.
  useEffect(() => {
    const el = sentinelRef.current
    if (!el || typeof IntersectionObserver === 'undefined') return
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
        <div className="mx-auto flex max-w-[1440px] flex-wrap items-center gap-x-3 gap-y-2 px-5 py-2.5 sm:px-8">
          <button
            type="button"
            onClick={onOpenDrawer}
            className="order-1 inline-flex shrink-0 items-center gap-2 rounded-full border border-(--color-border-default) bg-white px-4 py-2 text-[13px] font-bold text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent) lg:hidden"
          >
            <SlidersHorizontal size={15} aria-hidden />
            Bộ lọc{activeCount > 0 && ` (${activeCount})`}
          </button>

          <form
            role="search"
            onSubmit={(e) => {
              e.preventDefault()
              onSubmit()
            }}
            className="relative order-2 min-w-0 flex-1 lg:w-64 lg:flex-none"
          >
            <input
              type="search"
              value={text}
              onChange={(e) => onType(e.target.value)}
              maxLength={100}
              placeholder="Tìm sản phẩm"
              aria-label="Tìm sản phẩm"
              className="w-full rounded-full border border-(--color-border-default) bg-white py-2 pr-10 pl-4 text-[13px] font-semibold text-(--color-text-primary) placeholder:font-medium placeholder:text-(--color-text-secondary) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
            />
            <button
              type="submit"
              aria-label="Tìm"
              className="absolute top-1/2 right-1.5 grid h-7 w-7 -translate-y-1/2 place-items-center rounded-full text-(--color-text-secondary) transition-colors hover:text-accent focus-visible:outline-2 focus-visible:outline-(--color-accent)"
            >
              <Search size={15} aria-hidden />
            </button>
          </form>

          {/* Danh mục — cuộn ngang khi hẹp. */}
          <div
            role="group"
            aria-label="Danh mục"
            className={`${styles.catStrip} order-3 basis-full lg:min-w-0 lg:flex-1 lg:basis-0`}
          >
            <CatChip active={categoryId === undefined} onClick={() => onCategory(null)}>
              Tất cả
            </CatChip>
            {categories.map((c) => (
              <CatChip
                key={c.categoryId}
                active={categoryId === c.categoryId}
                onClick={() => onCategory(c.categoryId)}
              >
                {c.name}
              </CatChip>
            ))}
          </div>

          <div className="order-4 hidden shrink-0 items-center gap-2 lg:flex">
            <span className="h-6 w-px bg-(--color-border-default)" aria-hidden />
            <MapPin size={15} className="-mr-0.5 text-accent" aria-hidden />
            <select
              value={branchId ?? ''}
              onChange={(e) => onBranch(e.target.value ? Number(e.target.value) : null)}
              aria-label="Còn hàng tại"
              className="cursor-pointer rounded-full border border-(--color-border-default) bg-white px-3.5 py-2 text-[13px] font-semibold text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
            >
              <option value="">Tất cả chi nhánh</option>
              {branches.map((b) => (
                <option key={b.branchId} value={b.branchId}>
                  {b.name}
                </option>
              ))}
            </select>
          </div>

          {count !== null && (
            <span className="order-5 ml-auto hidden shrink-0 text-[13px] text-(--color-text-secondary) tabular-nums xl:inline">
              {count} sản phẩm
            </span>
          )}
        </div>
      </div>
    </>
  )
}

function CatChip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`${styles.catChip} ${active ? styles.catChipActive : ''} focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)`}
    >
      {children}
    </button>
  )
}

/* ------------------------------------------------------------ product card */

/** Ảnh sản phẩm; chưa có ảnh hoặc ảnh tải lỗi thì hiện khối thay thế cùng khung, tên đã có ngay cạnh nên khối này chỉ trang trí. */
function ProductImage({ product }: { product: PublicProduct }) {
  const [broken, setBroken] = useState(false)
  if (!product.imageUrl || broken) {
    return (
      <div aria-hidden className="grid h-full w-full place-items-center bg-(--color-surface-2)">
        <PawPrint size={44} strokeWidth={1.5} className="text-accent/25" />
      </div>
    )
  }
  return <img src={product.imageUrl} alt={product.name} loading="lazy" onError={() => setBroken(true)} />
}

/** BR-CK-03: chỉ tên chi nhánh còn hàng, không số lượng. */
function StockLine({
  ids,
  branches,
  loading,
}: {
  ids: number[]
  branches: PublicBranch[] | undefined
  loading: boolean
}) {
  const names = branches ? branchNamesByIds(ids, branches) : []
  const none = ids.length === 0 || (branches !== undefined && names.length === 0)
  if (!none && !branches && loading) {
    return <div aria-hidden className="mt-2 h-4 w-3/4 animate-pulse rounded bg-(--color-surface-2) motion-reduce:animate-none" />
  }
  return (
    <p className="mt-2 flex items-start gap-1.5 text-[11.5px] leading-snug text-(--color-text-secondary)">
      <MapPin size={12} aria-hidden className={`mt-0.5 shrink-0 ${none ? 'text-(--color-text-secondary)' : 'text-accent'}`} />
      {none ? (
        <span>Tạm hết hàng tại các chi nhánh</span>
      ) : (
        <span className="line-clamp-2">
          Còn hàng tại:{' '}
          <span className="font-semibold text-(--color-text-primary)">{names.length ? names.join(', ') : 'chi nhánh PetCare'}</span>
        </span>
      )}
    </p>
  )
}

function ProductCard({
  product: p,
  branches,
  branchesLoading,
}: {
  product: PublicProduct
  branches: PublicBranch[] | undefined
  branchesLoading: boolean
}) {
  return (
    <article data-card className={styles.card}>
      <div className={styles.cardMedia}>
        {/* Link ảnh trùng với link tên bên dưới nên bỏ khỏi thứ tự Tab và trình đọc màn hình. */}
        <Link to={`/shop/${p.productId}`} aria-hidden tabIndex={-1} className="block h-full w-full">
          <ProductImage product={p} />
        </Link>
      </div>

      <div className="flex flex-1 flex-col p-3">
        <p className="mb-1 truncate text-[10px] font-semibold tracking-[0.1em] text-(--color-text-secondary) uppercase">
          {p.categoryName}
        </p>

        <h3 className="line-clamp-2 min-h-[2.6em] text-[13px] leading-[1.3] font-bold">
          <Link to={`/shop/${p.productId}`} className="hover:text-accent focus-visible:outline-none">
            {p.name}
          </Link>
        </h3>

        <StockLine ids={p.inStockBranchIds} branches={branches} loading={branchesLoading} />

        <div className="mt-auto flex min-h-[24px] flex-wrap items-baseline gap-x-1.5 pt-3">
          <span className="font-friendly text-[16px] font-extrabold tabular-nums">
            {productPriceLabel(p.price)}
          </span>
          <span className="text-[11.5px] text-(--color-text-secondary)">/ {p.unit}</span>
        </div>
      </div>
    </article>
  )
}

/* -------------------------------------------------- skeleton + lỗi + trống */

function ProductGridSkeleton() {
  return (
    <div
      role="status"
      aria-label="Đang tải sản phẩm"
      className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 lg:gap-4 xl:grid-cols-5 2xl:grid-cols-6"
    >
      {Array.from({ length: 12 }).map((_, i) => (
        <div key={i} aria-hidden className={`${styles.skeleton} h-[248px]`} />
      ))}
    </div>
  )
}

function LoadError({ retrying, onRetry }: { retrying: boolean; onRetry: () => void }) {
  return (
    <div
      role="alert"
      className="mx-auto flex max-w-[560px] flex-col items-center rounded-3xl bg-(--color-surface-2) px-6 py-14 text-center"
    >
      <span className="grid h-12 w-12 place-items-center rounded-full bg-(--color-accent-soft) text-accent">
        <WifiOff size={22} aria-hidden />
      </span>
      <p className="mt-4 font-friendly text-[20px] font-extrabold">Không tải được danh sách sản phẩm</p>
      <p className="mt-2 text-[14px] text-(--color-text-secondary)">Kết nối có thể đang gián đoạn. Bạn thử tải lại nhé.</p>
      <button
        type="button"
        onClick={onRetry}
        disabled={retrying}
        className="mt-6 inline-flex items-center gap-2 rounded-full bg-accent px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover) disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
      >
        <RotateCw size={15} aria-hidden className={retrying ? 'animate-spin motion-reduce:animate-none' : ''} />
        Thử lại
      </button>
    </div>
  )
}

function EmptyState({ onClear }: { onClear: () => void }) {
  return (
    <div className="flex flex-col items-center rounded-3xl bg-(--color-surface-2) px-6 py-20 text-center">
      <img src="/imgs/download11.png" alt="" className="mb-5 w-16 opacity-40" />
      <p className="font-friendly text-[20px] font-extrabold">Không tìm thấy sản phẩm phù hợp</p>
      <p className="mt-2 max-w-[42ch] text-[14px] text-(--color-text-secondary)">
        Bộ lọc hiện tại hơi hẹp. Bỏ bớt một vài điều kiện để xem thêm lựa chọn cho boss nhà bạn.
      </p>
      <button
        type="button"
        onClick={onClear}
        className="mt-6 rounded-full bg-accent px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent)"
      >
        Xóa bộ lọc
      </button>
    </div>
  )
}

/* ----------------------------------------------------------- mobile drawer */

function FilterDrawer({
  categories,
  categoryId,
  onCategory,
  branches,
  branchId,
  onBranch,
  resultCount,
  onClear,
  onClose,
}: {
  categories: BarCategory[]
  categoryId: number | undefined
  onCategory: (id: number) => void
  branches: Pick<PublicBranch, 'branchId' | 'name'>[]
  branchId: number | undefined
  onBranch: (id: number) => void
  resultCount: number
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
            type="button"
            onClick={onClose}
            aria-label="Đóng bộ lọc"
            className="rounded-full p-1.5 hover:bg-(--color-surface-2) focus-visible:outline-2 focus-visible:outline-(--color-accent)"
          >
            <X size={20} aria-hidden />
          </button>
        </div>

        {categories.length > 0 && (
          <DrawerGroup title="Danh mục">
            {categories.map((c) => (
              <DrawerPill key={c.categoryId} active={categoryId === c.categoryId} onClick={() => onCategory(c.categoryId)}>
                {c.name}
              </DrawerPill>
            ))}
          </DrawerGroup>
        )}

        {branches.length > 0 && (
          <DrawerGroup title="Còn hàng tại">
            {branches.map((b) => (
              <DrawerPill key={b.branchId} active={branchId === b.branchId} onClick={() => onBranch(b.branchId)}>
                {b.name}
              </DrawerPill>
            ))}
          </DrawerGroup>
        )}

        <div className="fixed inset-x-0 bottom-0 flex gap-3 border-t border-(--color-border-default) bg-white p-4">
          <button
            type="button"
            onClick={onClear}
            className="flex-1 rounded-full border border-(--color-border-default) py-3 text-[14px] font-bold text-(--color-text-primary)"
          >
            Xóa hết
          </button>
          <button
            type="button"
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
      <h2 className="mb-3 text-[12px] font-bold tracking-[0.14em] text-(--color-text-secondary) uppercase">
        {title}
      </h2>
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
      type="button"
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
