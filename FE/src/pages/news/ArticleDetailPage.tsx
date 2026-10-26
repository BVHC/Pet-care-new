import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Calendar, PawPrint, RotateCw, User, WifiOff } from 'lucide-react'
import { toApiError } from '@/shared/api/api-error'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { usePublicArticle } from '@/shared/hooks/usePublicSite'
import type { PublicArticle } from '@/shared/types/public-site'
import { formatPublishedDate } from '@/shared/utils/public-format'
import styles from './ArticleDetailPage.module.css'

/* ================================================================
   Data.
   ================================================================ */

const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const BUTTON = `inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover ${FOCUS}`

/* ================================================================
   States.
   ================================================================ */

function ArticleSkeleton() {
  return (
    <div role="status" aria-label="Đang tải bài viết">
      <div aria-hidden className="h-[220px] bg-(--color-surface-dark)" />
      <div aria-hidden className="mx-auto max-w-[760px] space-y-4 px-5 pt-10 sm:px-8">
        <div className={`${PULSE} h-5 w-56 rounded-full`} />
        <div className={`${PULSE} aspect-video w-full rounded-2xl`} />
        <div className={`${PULSE} h-4 w-full rounded-lg`} />
        <div className={`${PULSE} h-4 w-full rounded-lg`} />
        <div className={`${PULSE} h-4 w-2/3 rounded-lg`} />
      </div>
    </div>
  )
}

function Notice({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="mx-auto max-w-[620px] px-5 py-20 sm:px-8">
      <div className={`${CARD} px-6 py-12 text-center`}>
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
          {icon}
        </span>
        {children}
      </div>
    </div>
  )
}

/* ================================================================
   Pieces.
   ================================================================ */

function ArticleView({ article }: { article: PublicArticle }) {
  const [coverBroken, setCoverBroken] = useState(false)
  return (
    <>
      <CommonPageHero
        eyebrow={article.categoryName}
        title={article.title}
        breadcrumbs={[{ label: 'Tin tức', href: '/news' }]}
      />

      <div className="mx-auto max-w-[760px] px-5 pt-10 sm:px-8">
        <article>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[13px] text-(--color-text-secondary)">
            <Link
              to={`/news?category=${encodeURIComponent(article.categorySlug)}`}
              className={`rounded-full bg-(--color-accent-soft) px-3 py-1 text-[12px] font-bold text-accent transition-colors hover:bg-accent hover:text-white ${FOCUS}`}
            >
              {article.categoryName}
            </Link>
            <span className="flex items-center gap-1.5">
              <User size={14} aria-hidden /> {article.authorDisplayName}
            </span>
            <span className="flex items-center gap-1.5">
              <Calendar size={14} aria-hidden /> <time dateTime={article.publishedAt}>{formatPublishedDate(article.publishedAt)}</time>
            </span>
          </div>

          {/* Ảnh bìa trang trí (tiêu đề đã ở ngay trên); chưa có hoặc tải lỗi thì bỏ hẳn khung, không để khối trống. */}
          {article.coverImageUrl && !coverBroken && (
            <img
              src={article.coverImageUrl}
              alt=""
              onError={() => setCoverBroken(true)}
              className="mt-6 aspect-video w-full rounded-2xl border border-(--color-border-default) bg-(--color-surface-1) object-cover"
            />
          )}

          {/* ADR-0071: `content` là HTML đã lọc ở BE (jsoup Safelist.relaxed, a[href] / img[src] chỉ https) khi lưu bài, nên chỗ này
              là NƠI DUY NHẤT của FE render HTML thô. Không thêm chỗ thứ hai, không render nội dung page_contents (UC21) kiểu này. */}
          <div className={`${styles.prose} mt-8`} dangerouslySetInnerHTML={{ __html: article.content }} />
        </article>

        <div className="mt-12 border-t border-(--color-border-default) pt-6">
          <Link to="/news" className={`inline-flex items-center gap-2 text-[14px] font-bold text-accent hover:text-accent-hover ${FOCUS}`}>
            <ArrowLeft size={16} aria-hidden /> Tất cả bài viết
          </Link>
        </div>
      </div>
    </>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function ArticleDetailPage() {
  const { slug } = useParams<{ slug: string }>()
  const article = usePublicArticle(slug ?? null)

  const title = article.data?.title
  useEffect(() => {
    if (!title) return
    const previous = document.title
    document.title = `${title} | PetCare`
    return () => {
      document.title = previous
    }
  }, [title])

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {article.isError ? (
        // Slug lạ, bài DRAFT và HIDDEN cùng một 404 và cùng một thông điệp (BR-BV-03): không để lộ bài có tồn tại hay không.
        toApiError(article.error).statusCode === 404 ? (
          <Notice icon={<PawPrint size={22} aria-hidden />}>
            <h1 className="mt-4 font-friendly text-[26px] font-extrabold text-(--color-surface-dark)">Không tìm thấy bài viết</h1>
            <p className="mt-2 text-[14px] text-(--color-dark-gray)/70">Bài viết này không tồn tại hoặc không còn được hiển thị.</p>
            <Link to="/news" className={`${BUTTON} mt-6`}>
              Xem tất cả bài viết
            </Link>
          </Notice>
        ) : (
          <div role="alert">
            <Notice icon={<WifiOff size={22} aria-hidden />}>
              <p className="mt-4 font-friendly text-[22px] font-extrabold text-(--color-surface-dark)">Không tải được bài viết</p>
              <p className="mt-2 text-[14px] text-(--color-dark-gray)/70">Kết nối có thể đang gián đoạn. Bạn thử tải lại nhé.</p>
              <button type="button" onClick={() => void article.refetch()} disabled={article.isFetching} className={`${BUTTON} mt-6 disabled:opacity-60`}>
                <RotateCw size={15} aria-hidden className={article.isFetching ? 'animate-spin motion-reduce:animate-none' : ''} />
                Thử lại
              </button>
            </Notice>
          </div>
        )
      ) : !article.data ? (
        <ArticleSkeleton />
      ) : (
        <ArticleView key={article.data.slug} article={article.data} />
      )}
    </div>
  )
}
