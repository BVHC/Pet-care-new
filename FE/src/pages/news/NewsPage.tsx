import { useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowRight, Calendar, ChevronLeft, ChevronRight, PawPrint, RotateCw, User, WifiOff } from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { usePublicArticleCategories, usePublicArticles } from '@/shared/hooks/usePublicSite'
import type { PublicArticleCard } from '@/shared/types/public-site'
import { formatPublishedDate } from '@/shared/utils/public-format'

/* ================================================================
   Data.
   ================================================================ */

/** Bài đầu trang 1 là bài nổi bật, 9 bài còn lại xếp lưới 3 cột. */
const PAGE_SIZE = 10

const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const BUTTON = `inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover ${FOCUS}`
// Cả thẻ bấm được: link ở tiêu đề phủ kín thẻ bằng ::after, viền focus vẽ quanh thẻ.
const CARD_LINK = 'after:absolute after:inset-0 after:content-[""] focus-visible:outline-none'
const CARD_FOCUS = 'has-[a:focus-visible]:outline-2 has-[a:focus-visible]:outline-offset-2 has-[a:focus-visible]:outline-accent'

/* ================================================================
   States.
   ================================================================ */

function NewsSkeleton() {
  return (
    <div role="status" aria-label="Đang tải bài viết">
      <div aria-hidden className={`${CARD} mb-10 grid overflow-hidden rounded-3xl lg:grid-cols-2`}>
        <div className={`${PULSE} aspect-4/3 lg:aspect-auto lg:min-h-72`} />
        <div className="space-y-3 p-8">
          <div className={`${PULSE} h-5 w-32 rounded-full`} />
          <div className={`${PULSE} h-8 w-4/5 rounded-lg`} />
          <div className={`${PULSE} h-4 w-full rounded-lg`} />
          <div className={`${PULSE} h-4 w-2/3 rounded-lg`} />
        </div>
      </div>
      <div aria-hidden className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className={`${CARD} overflow-hidden`}>
            <div className={`${PULSE} aspect-4/3`} />
            <div className="space-y-3 p-5">
              <div className={`${PULSE} h-4 w-24 rounded-full`} />
              <div className={`${PULSE} h-5 w-full rounded-lg`} />
              <div className={`${PULSE} h-4 w-3/4 rounded-lg`} />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function LoadError({ retrying, onRetry }: { retrying: boolean; onRetry: () => void }) {
  return (
    <div role="alert" className={`${CARD} mx-auto max-w-[560px] p-8 text-center`}>
      <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
        <WifiOff size={22} aria-hidden />
      </span>
      <p className="mt-4 font-friendly text-[20px] font-bold text-(--color-surface-dark)">Không tải được bài viết</p>
      <p className="mt-1 text-[14px] text-(--color-dark-gray)/70">Kết nối có thể đang gián đoạn. Bạn thử tải lại nhé.</p>
      <button type="button" onClick={onRetry} disabled={retrying} className={`${BUTTON} mt-5 disabled:opacity-60`}>
        <RotateCw size={15} aria-hidden className={retrying ? 'animate-spin motion-reduce:animate-none' : ''} />
        Thử lại
      </button>
    </div>
  )
}

/* ================================================================
   Pieces.
   ================================================================ */

/** Ảnh bìa; chưa có ảnh hoặc ảnh tải lỗi thì hiện khối thay thế cùng khung (tiêu đề đã nằm ngay cạnh nên ảnh chỉ trang trí). */
function Cover({ article, eager = false, className = '' }: { article: PublicArticleCard; eager?: boolean; className?: string }) {
  const [broken, setBroken] = useState(false)
  if (!article.coverImageUrl || broken) {
    return (
      <div aria-hidden className="grid h-full w-full place-items-center bg-(--color-surface-2)">
        <PawPrint size={56} strokeWidth={1.25} className="text-accent/25" />
      </div>
    )
  }
  return (
    <img
      src={article.coverImageUrl}
      alt=""
      loading={eager ? undefined : 'lazy'}
      onError={() => setBroken(true)}
      className={`h-full w-full object-cover ${className}`}
    />
  )
}

function Meta({ article, size }: { article: PublicArticleCard; size: number }) {
  return (
    <>
      <span className="flex items-center gap-1">
        <User size={size} aria-hidden /> {article.authorDisplayName}
      </span>
      <span className="flex items-center gap-1">
        <Calendar size={size} aria-hidden /> <time dateTime={article.publishedAt}>{formatPublishedDate(article.publishedAt)}</time>
      </span>
    </>
  )
}

function FeaturedArticle({ article }: { article: PublicArticleCard }) {
  return (
    <article
      className={`group relative mb-10 grid overflow-hidden rounded-3xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)] lg:grid-cols-2 ${CARD_FOCUS}`}
    >
      <div className="aspect-4/3 overflow-hidden bg-(--color-surface-1) lg:aspect-auto">
        <Cover article={article} eager className="transition-transform duration-500 ease-[cubic-bezier(0.32,0.72,0,1)] group-hover:scale-105 motion-reduce:transition-none" />
      </div>
      <div className="flex flex-col justify-center p-8">
        <span className="inline-block w-fit rounded-full bg-(--color-accent-soft) px-3 py-1 text-[11.5px] font-bold uppercase tracking-wide text-accent">
          {article.categoryName} · Mới nhất
        </span>
        <h2 className="mt-4 font-friendly text-[26px] font-extrabold leading-tight text-(--color-text-primary)">
          <Link to={`/news/${article.slug}`} className={CARD_LINK}>
            {article.title}
          </Link>
        </h2>
        {article.summary && <p className="mt-3 text-[14px] leading-relaxed text-(--color-text-secondary)">{article.summary}</p>}
        <div className="mt-4 flex items-center gap-4 text-[12.5px] text-(--color-text-secondary)">
          <Meta article={article} size={13} />
        </div>
        <span className="mt-6 inline-flex w-fit items-center gap-2 text-[13.5px] font-bold text-accent transition-colors group-hover:text-(--color-accent-hover)">
          Đọc tiếp <ArrowRight size={16} aria-hidden />
        </span>
      </div>
    </article>
  )
}

function ArticleCard({ article }: { article: PublicArticleCard }) {
  return (
    <article
      className={`group relative overflow-hidden ${CARD} transition-all duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-0.5 hover:shadow-[0_2px_4px_rgba(56,36,23,0.07),0_12px_28px_-16px_rgba(164,51,36,0.4)] motion-reduce:transition-none motion-reduce:hover:translate-y-0 ${CARD_FOCUS}`}
    >
      <div className="aspect-4/3 overflow-hidden bg-(--color-surface-1)">
        <Cover article={article} className="transition-transform duration-500 ease-[cubic-bezier(0.32,0.72,0,1)] group-hover:scale-105 motion-reduce:transition-none" />
      </div>
      <div className="p-5">
        <span className="inline-block rounded-full bg-(--color-accent-soft) px-2.5 py-0.5 text-[11px] font-bold text-accent">
          {article.categoryName}
        </span>
        <h3 className="mt-3 line-clamp-2 text-[15px] font-bold text-(--color-text-primary) transition-colors group-hover:text-accent">
          <Link to={`/news/${article.slug}`} className={CARD_LINK}>
            {article.title}
          </Link>
        </h3>
        {article.summary && <p className="mt-2 line-clamp-2 text-[13px] text-(--color-text-secondary)">{article.summary}</p>}
        <div className="mt-4 flex items-center justify-between text-[12px] text-(--color-text-secondary)">
          <Meta article={article} size={12} />
        </div>
      </div>
    </article>
  )
}

function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  const step = `inline-flex min-h-10 items-center gap-1 rounded-full border border-(--color-border-default) bg-(--color-surface-card) px-4 text-[13.5px] font-semibold text-(--color-dark-gray) transition-colors hover:bg-(--color-accent-soft) hover:text-accent disabled:pointer-events-none disabled:opacity-40 ${FOCUS}`
  return (
    <nav aria-label="Phân trang" className="mt-10 flex items-center justify-center gap-3">
      <button type="button" className={step} disabled={page <= 1} onClick={() => onChange(page - 1)} aria-label="Trang trước">
        <ChevronLeft size={16} aria-hidden /> Trước
      </button>
      <span className="text-[13.5px] text-(--color-text-secondary) tabular-nums">
        Trang {page} / {totalPages}
      </span>
      <button type="button" className={step} disabled={page >= totalPages} onClick={() => onChange(page + 1)} aria-label="Trang sau">
        Sau <ChevronRight size={16} aria-hidden />
      </button>
    </nav>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function NewsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  // Bộ lọc nằm trên URL (`?category=<slug>&page=<n>`, page đếm từ 1, BE đếm từ 0). Link thẳng tới chuyên mục ẩn vẫn lọc ra bài (BR-BV-04).
  const category = searchParams.get('category')?.trim().slice(0, 100) || undefined
  const rawPage = searchParams.get('page')
  const page = rawPage && /^[1-9]\d{0,5}$/.test(rawPage) ? Number(rawPage) : 1

  const categories = usePublicArticleCategories()
  const articles = usePublicArticles({ ...(category ? { categorySlug: category } : {}), page: page - 1, size: PAGE_SIZE })
  const sectionRef = useRef<HTMLElement>(null)

  const go = (next: { category?: string | null; page?: number }) => {
    const params = new URLSearchParams(searchParams)
    if (next.category !== undefined) {
      if (next.category) params.set('category', next.category)
      else params.delete('category')
    }
    if (next.page && next.page > 1) params.set('page', String(next.page))
    else params.delete('page')
    setSearchParams(params)
  }
  const goToPage = (next: number) => {
    go({ page: next })
    // Cuộn về đầu danh sách, chừa chỗ cho header đang dính.
    const top = (sectionRef.current?.getBoundingClientRect().top ?? 0) + window.scrollY - 130
    window.scrollTo({ top, behavior: 'smooth' })
  }

  const data = articles.data
  const items = data?.content ?? []
  const featured = page === 1 ? items[0] : undefined
  const rest = featured ? items.slice(1) : items

  const chip = (selected: boolean) =>
    `rounded-full px-5 py-2 text-[13.5px] font-semibold transition-all duration-200 ${FOCUS} ${
      selected
        ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
        : 'bg-(--color-surface-card) text-(--color-text-primary) hover:bg-(--color-accent-soft) hover:text-accent'
    }`

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Tin tức & Bài viết"
        title="Kiến thức chăm sóc thú cưng"
        subtitle="Cập nhật những kiến thức hữu ích về chăm sóc, dinh dưỡng, sức khỏe và huấn luyện thú cưng từ đội ngũ bác sĩ và chuyên gia của PetCare."
        breadcrumbs={[{ label: 'Tin tức' }]}
      />

      <section ref={sectionRef} className="mx-auto max-w-[1200px] px-5 pt-12 sm:px-8">
        {/* Chuyên mục ẩn không có chip (BR-BV-04); lỗi tải chuyên mục không chặn danh sách bài. */}
        <div role="group" aria-label="Chuyên mục" className="mb-8 flex flex-wrap justify-center gap-2">
          <button type="button" aria-pressed={!category} onClick={() => go({ category: null })} className={chip(!category)}>
            Tất cả
          </button>
          {categories.data?.map((c) => (
            <button key={c.categoryId} type="button" aria-pressed={category === c.slug} onClick={() => go({ category: c.slug })} className={chip(category === c.slug)}>
              {c.name}
            </button>
          ))}
        </div>

        {articles.isError ? (
          <LoadError retrying={articles.isFetching} onRetry={() => void articles.refetch()} />
        ) : !data ? (
          <NewsSkeleton />
        ) : items.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-(--color-border-default) bg-(--color-surface-card) p-12 text-center text-(--color-text-secondary)">
            <p>{category ? 'Chưa có bài viết trong chủ đề này.' : 'Chưa có bài viết nào được đăng.'}</p>
            {(category || page > 1) && (
              <button type="button" onClick={() => setSearchParams({})} className={`${BUTTON} mt-5`}>
                Xem tất cả bài viết
              </button>
            )}
          </div>
        ) : (
          <div aria-busy={articles.isPlaceholderData} className={`transition-opacity ${articles.isPlaceholderData ? 'opacity-60' : ''}`}>
            {featured && <FeaturedArticle article={featured} />}
            {rest.length > 0 && (
              <>
                {!featured && <h2 className="sr-only">Danh sách bài viết</h2>}
                <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
                  {rest.map((article) => (
                    <ArticleCard key={article.articleId} article={article} />
                  ))}
                </div>
              </>
            )}
            {data.totalPages > 1 && <Pager page={page} totalPages={data.totalPages} onChange={goToPage} />}
          </div>
        )}
      </section>
    </div>
  )
}
