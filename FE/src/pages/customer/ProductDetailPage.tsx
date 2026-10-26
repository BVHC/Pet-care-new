import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { MapPin, PawPrint, RotateCw, Store, WifiOff } from 'lucide-react'
import { toApiError } from '@/shared/api/api-error'
import { usePublicBranches, usePublicProduct } from '@/shared/hooks/usePublicSite'
import type { PublicBranch } from '@/shared/types/branch'
import type { PublicProduct } from '@/shared/types/public-site'
import { branchNamesByIds, productPriceLabel } from '@/shared/utils/public-format'

/* ================================================================
   Data.
   ================================================================ */

// Id trên URL phải là số nguyên dương; thứ khác ("abc", "0", "-1", "1.5") coi như không tìm thấy mà không gọi API.
const PRODUCT_ID = /^[1-9]\d{0,8}$/

const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const BUTTON = `inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover ${FOCUS}`

/* ================================================================
   States.
   ================================================================ */

function Skeleton() {
  return (
    <div role="status" aria-label="Đang tải sản phẩm" className="grid gap-8 lg:grid-cols-2">
      <div aria-hidden className={`${PULSE} aspect-square rounded-2xl`} />
      <div aria-hidden className="space-y-4">
        <div className={`${PULSE} h-4 w-24 rounded-lg`} />
        <div className={`${PULSE} h-9 w-4/5 rounded-lg`} />
        <div className={`${PULSE} h-9 w-40 rounded-lg`} />
        <div className={`${PULSE} h-28 w-full rounded-2xl`} />
      </div>
    </div>
  )
}

function Notice({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className={`${CARD} mx-auto max-w-[560px] px-6 py-14 text-center`}>
      <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
        {icon}
      </span>
      {children}
    </div>
  )
}

/* ================================================================
   Pieces.
   ================================================================ */

/** Ảnh sản phẩm; chưa có ảnh hoặc ảnh tải lỗi thì hiện khối thay thế cùng khung. */
function ProductImage({ product }: { product: PublicProduct }) {
  const [broken, setBroken] = useState(false)
  if (!product.imageUrl || broken) {
    return (
      <div aria-hidden className="grid h-full w-full place-items-center bg-(--color-surface-2)">
        <PawPrint size={88} strokeWidth={1.25} className="text-accent/25" />
      </div>
    )
  }
  return (
    <img
      src={product.imageUrl}
      alt={product.name}
      onError={() => setBroken(true)}
      className="h-full w-full object-contain p-4 mix-blend-multiply"
    />
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function ProductDetailPage() {
  const { id } = useParams<{ id: string }>()
  const productId = id && PRODUCT_ID.test(id) ? Number(id) : null
  const product = usePublicProduct(productId)
  const branches = usePublicBranches()

  const name = product.data?.name
  useEffect(() => {
    if (!name) return
    const previous = document.title
    document.title = `${name} | PetCare`
    return () => {
      document.title = previous
    }
  }, [name])

  const notFound = productId === null || (product.isError && toApiError(product.error).statusCode === 404)

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-8 sm:px-6">
      {notFound ? (
        <Notice icon={<PawPrint size={22} aria-hidden />}>
          <h1 className="mt-4 font-friendly text-[26px] font-extrabold text-(--color-surface-dark)">Không tìm thấy sản phẩm</h1>
          <p className="mt-2 text-[14px] text-(--color-dark-gray)/70">Sản phẩm này không còn được bán hoặc đường dẫn chưa đúng.</p>
          <Link to="/shop" className={`${BUTTON} mt-6`}>
            Quay lại cửa hàng
          </Link>
        </Notice>
      ) : product.isError ? (
        <div role="alert">
          <Notice icon={<WifiOff size={22} aria-hidden />}>
            <p className="mt-4 font-friendly text-[22px] font-extrabold text-(--color-surface-dark)">Không tải được sản phẩm</p>
            <p className="mt-2 text-[14px] text-(--color-dark-gray)/70">Kết nối có thể đang gián đoạn. Bạn thử tải lại nhé.</p>
            <button type="button" onClick={() => void product.refetch()} disabled={product.isFetching} className={`${BUTTON} mt-6 disabled:opacity-60`}>
              <RotateCw size={15} aria-hidden className={product.isFetching ? 'animate-spin motion-reduce:animate-none' : ''} />
              Thử lại
            </button>
          </Notice>
        </div>
      ) : !product.data ? (
        <Skeleton />
      ) : (
        <ProductView product={product.data} branches={branches.data} branchesLoading={branches.isPending} />
      )}
    </div>
  )
}

function ProductView({
  product: p,
  branches,
  branchesLoading,
}: {
  product: PublicProduct
  branches: PublicBranch[] | undefined
  branchesLoading: boolean
}) {
  // BR-CK-03: chỉ tên chi nhánh còn hàng, không số lượng, không nút mua.
  const stockedAt = branches ? branchNamesByIds(p.inStockBranchIds, branches) : []
  const outOfStock = p.inStockBranchIds.length === 0 || (branches !== undefined && stockedAt.length === 0)

  return (
    <>
      <nav aria-label="Đường dẫn" className="mb-5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-(--color-text-secondary)">
        <Link to="/" className={`hover:text-accent ${FOCUS}`}>
          Trang chủ
        </Link>
        <span aria-hidden>/</span>
        <Link to="/shop" className={`hover:text-accent ${FOCUS}`}>
          Danh mục sản phẩm
        </Link>
        <span aria-hidden>/</span>
        <Link to={`/shop?category=${p.categoryId}`} className={`hover:text-accent ${FOCUS}`}>
          {p.categoryName}
        </Link>
        <span aria-hidden>/</span>
        <span aria-current="page" className="font-semibold text-(--color-text-primary)">
          {p.name}
        </span>
      </nav>

      <article className="grid gap-8 lg:grid-cols-2 lg:gap-12">
        <div className="aspect-square overflow-hidden rounded-2xl border border-(--color-border-default) bg-(--color-surface-1)">
          <ProductImage product={p} />
        </div>

        <div>
          <p className="text-[12px] font-semibold tracking-widest text-(--color-text-secondary) uppercase">{p.categoryName}</p>
          <h1 className="mt-2 font-friendly text-[clamp(26px,3.4vw,36px)] leading-tight font-extrabold text-(--color-surface-dark)">{p.name}</h1>

          <p className="mt-5 flex flex-wrap items-baseline gap-x-2">
            <span className="font-friendly text-[clamp(28px,3.6vw,36px)] font-extrabold text-accent tabular-nums">{productPriceLabel(p.price)}</span>
            <span className="text-[15px] text-(--color-text-secondary)">/ {p.unit}</span>
          </p>

          {p.description && <p className="mt-5 max-w-[60ch] leading-relaxed text-(--color-dark-gray)/80">{p.description}</p>}

          <section aria-labelledby="product-stock" className={`${CARD} mt-8 p-5 sm:p-6`}>
            <h2 id="product-stock" className="flex items-center gap-2 font-friendly text-[18px] font-bold text-(--color-surface-dark)">
              <Store size={18} aria-hidden className="text-accent" />
              Mua tại chi nhánh
            </h2>

            {outOfStock ? (
              <p className="mt-3 text-[14px] text-(--color-dark-gray)/70">Tạm hết hàng tại các chi nhánh</p>
            ) : stockedAt.length > 0 ? (
              <>
                <p className="mt-3 text-[13px] font-semibold text-(--color-dark-gray)/70">Còn hàng tại</p>
                <ul aria-label="Chi nhánh còn hàng" className="mt-2 flex flex-wrap gap-2">
                  {stockedAt.map((branch) => (
                    <li key={branch}>
                      <Link
                        to="/branches"
                        className={`inline-flex items-center gap-1.5 rounded-full border border-(--color-border-default) bg-(--color-surface-1) px-3.5 py-1.5 text-[13.5px] font-semibold text-(--color-dark-gray) transition-colors hover:border-accent hover:text-accent ${FOCUS}`}
                      >
                        <MapPin size={13} aria-hidden className="text-accent" />
                        {branch}
                      </Link>
                    </li>
                  ))}
                </ul>
              </>
            ) : branchesLoading ? (
              <div aria-hidden className={`${PULSE} mt-3 h-8 w-48 rounded-full`} />
            ) : (
              <p className="mt-3 text-[14px] text-(--color-dark-gray)/70">Còn hàng tại chi nhánh PetCare.</p>
            )}

            <p className="mt-4 text-[13.5px] leading-relaxed text-(--color-dark-gray)/70">
              Sản phẩm bán tại quầy, vui lòng liên hệ chi nhánh để mua.
            </p>
            <Link to="/branches" className={`${BUTTON} mt-4`}>
              Xem chi nhánh &amp; số điện thoại
            </Link>
          </section>
        </div>
      </article>
    </>
  )
}
