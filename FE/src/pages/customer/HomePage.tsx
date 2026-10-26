import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { useGSAP } from '@gsap/react'
import {
  ChevronDown,
  ChevronUp,
  Sparkles,
  PawPrint,
  Calendar,
  User,
  ArrowRight,
  MapPin,
} from 'lucide-react'
import { HeroBanner } from '@/components/customer/HeroBanner'
import { OurServicesStage } from '@/components/customer/OurServicesStage'
import { StackingProcessCards } from '@/components/customer/StackingProcessCards'
import { NoCagesPhilosophy } from '@/components/customer/NoCagesPhilosophy'
import { TestimonialsSpeechBubble } from '@/components/customer/TestimonialsSpeechBubble'
import { ContactVetCTA } from '@/components/customer/ContactVetCTA'
import { doctorInitials } from '@/pages/doctors/initials'
import { usePublicArticles, usePublicProducts, usePublicVets } from '@/shared/hooks/usePublicSite'
import type { PublicProduct } from '@/shared/types/public-site'
import { formatPublishedDate, productPriceLabel } from '@/shared/utils/public-format'
import { PHOTOS, STATS, PARTNERS, FAQS } from './home.mock'
import styles from './HomePage.module.css'

gsap.registerPlugin(ScrollTrigger)

/** Số thẻ mỗi khối dữ liệu trên trang chủ: 4 cột sản phẩm, 4 cột bác sĩ, 3 cột tin. */
const FEATURED_COUNT = 4
const DOCTOR_COUNT = 4
const NEWS_COUNT = 3

const MOTION_OK = '(prefers-reduced-motion: no-preference)'

/** Ảnh sản phẩm; chưa có ảnh hoặc ảnh tải lỗi thì hiện khối thay thế cùng khung. */
function ProductThumb({ product }: { product: PublicProduct }) {
  const [broken, setBroken] = useState(false)
  if (!product.imageUrl || broken) {
    return (
      <div aria-hidden className="grid h-full w-full place-items-center bg-(--color-surface-1)">
        <PawPrint size={44} strokeWidth={1.5} className="text-accent/30" />
      </div>
    )
  }
  return <img src={product.imageUrl} alt="" loading="lazy" onError={() => setBroken(true)} className="h-full w-full object-cover" />
}

/** Khung chờ cùng chiều cao với khối thật để ScrollTrigger phía dưới không bị lệch khi dữ liệu về. */
function SkeletonCard({ className }: { className: string }) {
  return <div aria-hidden className={`animate-pulse rounded-(--radius-rounded) bg-white/70 motion-reduce:animate-none ${className}`} />
}

export function HomePage() {
  const [openFaq, setOpenFaq] = useState<number | null>(0)

  // Dữ liệu thật (T46). Khối nào rỗng hoặc lỗi thì ẩn cả section, không để placeholder giả.
  const featured = usePublicProducts({ size: FEATURED_COUNT })
  const vets = usePublicVets()
  const news = usePublicArticles({ size: NEWS_COUNT })
  const featuredItems = featured.data?.content ?? []
  const doctorItems = (vets.data ?? []).slice(0, DOCTOR_COUNT)
  const newsItems = news.data?.content ?? []

  // Phần tĩnh: dựng một lần lúc mount.
  useEffect(() => {
    if (typeof window === 'undefined') return

    const mm = gsap.matchMedia()
    mm.add(MOTION_OK, () => {
      // 2. 3 Steps Section reveal
      gsap.fromTo('.gsap-step-card',
        { opacity: 0, y: 40, scale: 0.96 },
        {
          scrollTrigger: {
            trigger: '.gsap-steps-section',
            start: 'top 85%',
          },
          opacity: 1,
          y: 0,
          scale: 1,
          stagger: 0.12,
          duration: 0.7,
          ease: 'back.out(1.2)',
        }
      )

      // 5. Testimonials reveal
      gsap.fromTo('.gsap-testi-card',
        { opacity: 0, y: 35 },
        {
          scrollTrigger: {
            trigger: '.gsap-testi-section',
            start: 'top 88%',
          },
          opacity: 1,
          y: 0,
          stagger: 0.1,
          duration: 0.7,
          ease: 'power2.out',
        }
      )
    })

    return () => mm.revert()
  }, [])

  // Phần theo dữ liệu: thẻ chỉ có trong DOM sau khi API trả về nên dựng lại khi số thẻ đổi (revertOnUpdate), nếu không trigger
  // sẽ chạy lúc chưa có thẻ nào hoặc đo lệch. Người chọn giảm chuyển động thì không ẩn thẻ trước, nội dung luôn hiện.
  useGSAP(
    () => {
      if (featuredItems.length === 0) return
      const mm = gsap.matchMedia()
      mm.add(MOTION_OK, () => {
        gsap.fromTo('.gsap-product-card',
          { opacity: 0, y: 35 },
          {
            scrollTrigger: {
              trigger: '.gsap-featured-section',
              start: 'top 85%',
            },
            opacity: 1,
            y: 0,
            stagger: 0.08,
            duration: 0.65,
            ease: 'power2.out',
            clearProps: 'opacity,transform',
          }
        )
      })
    },
    { dependencies: [featuredItems.length], revertOnUpdate: true },
  )

  useGSAP(
    () => {
      if (doctorItems.length === 0) return
      const mm = gsap.matchMedia()
      mm.add(MOTION_OK, () => {
        gsap.fromTo('.gsap-doctor-card',
          { opacity: 0, y: 40 },
          {
            scrollTrigger: {
              trigger: '.gsap-doctors-section',
              start: 'top 85%',
            },
            opacity: 1,
            y: 0,
            stagger: 0.1,
            duration: 0.7,
            ease: 'power2.out',
            clearProps: 'opacity,transform',
          }
        )
      })
    },
    { dependencies: [doctorItems.length], revertOnUpdate: true },
  )

  // Khối dữ liệu hiện / ẩn / thay skeleton làm đổi chiều cao trang: đo lại mọi ScrollTrigger (cả pin của OurServicesStage).
  const featuredReady = featuredItems.length > 0
  const doctorsReady = doctorItems.length > 0
  const newsReady = newsItems.length > 0
  useEffect(() => {
    const frame = requestAnimationFrame(() => ScrollTrigger.refresh())
    return () => cancelAnimationFrame(frame)
  }, [featured.isPending, vets.isPending, news.isPending, featuredReady, doctorsReady, newsReady])

  return (
    <div className="relative bg-(--color-surface-page) font-professional">
      {/* 1. Hero - CozyPaws Style */}
      <HeroBanner />

      {/* 2. Dịch Vụ Của Chúng Tôi (Don't Board Me Panoramic Circular Stage - thay thế Categories) */}
      <OurServicesStage />

      {/* 3. 4 Bước Tận Tâm Tại PetCare (Don't Board Me Pinned Stacking Process Cards) */}
      <StackingProcessCards />

      {/* 3.1 Don't Board Me About Us Style: 100% Không Chuồng Nhốt Philosophy */}
      <NoCagesPhilosophy />

      {/* 4. Featured Products */}
      {(featured.isPending || featuredReady) && (
        <section className="gsap-featured-section relative overflow-hidden bg-[#f6e8da] px-6 py-14">
          <img
            src={PHOTOS.doodleBone}
            alt=""
            className={styles.sectionDoodle}
            style={{ top: 18, right: '4%', width: 54 }}
          />
          <div className={styles.wrap}>
            <div className="mb-10 text-center">
              <div className={styles.eyebrow}>
                <Sparkles size={13} />
                SẢN PHẨM TẠI PETCARE
              </div>
              <h2 className="font-friendly text-[clamp(32px,4vw,42px)] font-extrabold text-(--color-text-primary)">
                Sản Phẩm Nổi Bật
              </h2>
            </div>
            {featuredReady ? (
              <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-6">
                {featuredItems.map((p) => (
                  <div className="gsap-product-card transition-transform hover:-translate-y-1" key={p.productId}>
                    <Link
                      to={`/shop/${p.productId}`}
                      className="block w-full overflow-hidden rounded-(--radius-rounded) bg-white shadow-(--shadow-1) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                    >
                      <div className="relative aspect-4/3 bg-gray-100">
                        <ProductThumb product={p} />
                      </div>
                      <div className="p-3">
                        <div className="text-[11px] tracking-wide text-gray-500 uppercase">{p.categoryName}</div>
                        <h3 className="my-0.5 mb-1.5 line-clamp-2 text-[15px] font-bold text-gray-900">{p.name}</h3>
                        <div className="flex items-baseline gap-1.5">
                          <span className="text-base font-bold text-gray-900 tabular-nums">{productPriceLabel(p.price)}</span>
                          <span className="text-sm text-gray-500">/ {p.unit}</span>
                        </div>
                      </div>
                    </Link>
                  </div>
                ))}
              </div>
            ) : (
              <div role="status" aria-label="Đang tải sản phẩm" className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-6">
                {Array.from({ length: FEATURED_COUNT }, (_, i) => (
                  <SkeletonCard key={i} className="h-[268px]" />
                ))}
              </div>
            )}
            <div className="text-center mt-10">
              <Link
                to="/shop"
                className="rounded-full border-2 border-accent px-8 py-3 font-bold text-accent hover:bg-accent hover:text-white transition-colors inline-flex items-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
              >
                Xem tất cả sản phẩm <ArrowRight size={18} />
              </Link>
            </div>
          </div>
        </section>
      )}

      {/* 5. Stats + Vet Care */}
      <section className={styles.statsBand}>
        <div className={`${styles.wrap} ${styles.statsBandInner}`}>
          <div className={styles.statsIntro}>
            <div className={styles.profCenterArt}>
              <img src={PHOTOS.vetCounter} alt="Đội ngũ chăm sóc thú cưng chuyên nghiệp" />
            </div>
            <div>
              <div className="mb-2 text-[12px] font-bold tracking-widest uppercase opacity-90">
                Sự tin tưởng của bạn là ưu tiên của chúng tôi
              </div>
              <div className="font-friendly text-[clamp(24px,3vw,32px)] font-extrabold leading-tight">
                Chăm sóc chuyên nghiệp, chất lượng được đảm bảo
              </div>
            </div>
          </div>
          <div className={styles.statsGrid}>
            {STATS.map((s) => (
              <div key={s.label} className={styles.statItem}>
                <div className={styles.statIcon}>
                  {s.image ? (
                    <img src={s.image} alt="" style={{ width: 26, height: 26, objectFit: 'contain' }} />
                  ) : (
                    <s.icon size={24} />
                  )}
                </div>
                <div className={styles.statValue} data-count={s.value}>
                  {s.value}
                </div>
                <div className={styles.statLabel}>{s.label}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 6. Doctors */}
      {(vets.isPending || doctorsReady) && (
        <section className="gsap-doctors-section px-6 py-14 bg-[#fdf6ec]">
          <div className={styles.wrap}>
            <div className="text-center mb-12">
              <div className="text-[12px] font-bold text-accent tracking-widest uppercase flex items-center justify-center gap-2 mb-3">
                ĐỘI NGŨ TẬN TÂM VÌ THÚ CƯNG <PawPrint size={14} />
              </div>
              <h2 className="font-friendly text-[clamp(32px,4vw,42px)] font-extrabold text-(--color-text-primary) leading-tight">
                Gặp Gỡ Đội Ngũ
                <br />
                Bác Sĩ Chuyên Môn
              </h2>
            </div>
            {doctorsReady ? (
              <>
                {/* Chịu được 1..4 bác sĩ: thẻ cố định bề rộng theo số cột, dồn giữa thay vì dồn trái. */}
                <div className="flex flex-wrap justify-center gap-8 text-center">
                  {doctorItems.map((d) => (
                    <div
                      key={d.accountId}
                      className={`${styles.doctorCard} ${styles.doctorCardReveal} gsap-doctor-card w-full sm:w-[calc(50%-1rem)] md:w-[calc(25%-1.5rem)]`}
                    >
                      <div className={styles.doctorImgWrap}>
                        {d.avatarUrl ? (
                          <img src={d.avatarUrl} alt={d.fullName} className={styles.doctorImg} />
                        ) : (
                          <div
                            aria-hidden
                            className={`${styles.doctorImg} grid place-items-center bg-(--color-accent-soft) font-friendly text-[56px] font-extrabold text-accent`}
                          >
                            {doctorInitials(d.fullName)}
                          </div>
                        )}
                        <img src={PHOTOS.doodlePaw} className={styles.doctorPawDecor} alt="" />
                      </div>
                      <h3 className="font-friendly text-[22px] font-bold text-(--color-text-primary) mt-5 mb-1">
                        {d.fullName}
                      </h3>
                      <p className="text-[14px] text-(--color-text-secondary)">{d.specialty || 'Bác sĩ thú y'}</p>
                      <p className="mt-1 flex items-center justify-center gap-1 text-[12.5px] text-(--color-text-secondary)">
                        <MapPin size={12} aria-hidden /> {d.branchName}
                      </p>
                    </div>
                  ))}
                </div>
                <div className="text-center mt-10">
                  <Link
                    to="/doctors"
                    className="rounded-full border-2 border-accent px-8 py-3 font-bold text-accent hover:bg-accent hover:text-white transition-colors inline-flex items-center gap-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                  >
                    Xem đội ngũ bác sĩ <ArrowRight size={18} />
                  </Link>
                </div>
              </>
            ) : (
              <div role="status" aria-label="Đang tải bác sĩ" className="flex flex-wrap justify-center gap-8">
                <SkeletonCard className="h-[300px] w-full sm:w-[calc(50%-1rem)] md:w-[calc(25%-1.5rem)]" />
              </div>
            )}
          </div>
        </section>
      )}

      {/* 7. Don't Board Me Style Speech Bubble Testimonials */}
      <TestimonialsSpeechBubble />

      {/* 8. Partners */}
      <section className="px-6 pt-14 pb-4 bg-[#fdf6ec]">
        <div className={styles.wrap}>
          <div className="mb-7 text-center">
            <div className={`${styles.eyebrow} ${styles.sectionEyebrow}`}>
              Đối tác thương hiệu tin cậy
            </div>
          </div>
          <div className="flex flex-wrap justify-center gap-8">
            {PARTNERS.map((p) => (
              <img
                key={p.name}
                src={p.logo}
                alt={p.name}
                title={p.name}
                className="h-10 w-auto object-contain opacity-60 hover:opacity-100 transition-opacity"
                loading="lazy"
              />
            ))}
          </div>
        </div>
      </section>

      {/* 9. FAQ */}
      <section className="relative overflow-hidden px-6 pt-4 pb-14 bg-[#fdf6ec]">
        <img
          src={PHOTOS.doodleBone}
          alt=""
          className={styles.sectionDoodle}
          style={{ top: 6, right: '6%', width: 48, transform: 'rotate(-20deg)' }}
        />
        <div className={styles.wrap}>
          <div className="mb-2 text-center font-friendly text-[clamp(24px,3vw,32px)] font-extrabold text-(--color-text-primary)">
            Câu hỏi thường gặp
          </div>
          <div className="mb-8 text-center text-sm text-(--color-text-secondary)">
            Chưa tìm thấy câu trả lời? Liên hệ đội hỗ trợ của chúng tôi bất cứ lúc nào.
          </div>
          <div className={styles.faqList}>
            {FAQS.map((f, i) => (
              <div key={f.q} className={styles.faqItem} data-open={openFaq === i}>
                <button
                  className={styles.faqQ}
                  onClick={() => setOpenFaq(openFaq === i ? null : i)}
                  aria-expanded={openFaq === i}
                >
                  {f.q}
                  {openFaq === i ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
                </button>
                <div className={styles.faqA}>
                  <div className={styles.faqAInner}>{f.a}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>



      {/* 11. News */}
      {(news.isPending || newsReady) && (
        <section className="px-6 py-14 bg-[#f6e8da]">
          <div className={styles.wrap}>
            <div className="flex flex-col md:flex-row items-center justify-between gap-6 mb-12">
              <div>
                <div className="text-[12px] font-bold text-accent tracking-widest uppercase flex items-center gap-2 mb-2">
                  TIN TỨC & BÀI VIẾT <PawPrint size={14} />
                </div>
                <h2 className="font-friendly text-[clamp(32px,4vw,42px)] font-extrabold text-(--color-text-primary) leading-tight">
                  Bài Viết Mới Nhất
                </h2>
              </div>
              <Link
                to="/news"
                className="px-6 py-2.5 rounded-full bg-[#faebe4] hover:bg-accent hover:text-white transition-colors flex items-center gap-2 font-bold text-[14px] text-accent border border-accent/20 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
              >
                Xem Tất Cả <ArrowRight size={18} />
              </Link>
            </div>
            {newsReady ? (
              <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
                {newsItems.map((a) => (
                  <Link
                    key={a.articleId}
                    to={`/news/${a.slug}`}
                    className="block h-full rounded-[20px] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                  >
                    <div className={`${styles.articleCard} h-full`}>
                      <div className={styles.articleImgWrap}>
                        {a.coverImageUrl ? (
                          <img src={a.coverImageUrl} alt="" loading="lazy" className={styles.articleImg} />
                        ) : (
                          <div aria-hidden className={`${styles.articleImg} grid place-items-center bg-(--color-surface-2)`}>
                            <PawPrint size={48} strokeWidth={1.5} className="text-accent/25" />
                          </div>
                        )}
                        <div className={styles.articleBadges}>
                          <span className={styles.articleBadge}>{a.categoryName}</span>
                        </div>
                      </div>
                      <div className="p-7">
                        <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-[13px] font-semibold text-(--color-text-secondary) mb-4">
                          <span className="flex items-center gap-2">
                            <User size={16} className="text-accent" /> {a.authorDisplayName}
                          </span>
                          <span className="flex items-center gap-2">
                            <Calendar size={16} className="text-accent" /> {formatPublishedDate(a.publishedAt)}
                          </span>
                        </div>
                        <h3 className="font-friendly text-[22px] font-extrabold text-(--color-text-primary) leading-[1.3] line-clamp-3">
                          {a.title}
                        </h3>
                      </div>
                    </div>
                  </Link>
                ))}
              </div>
            ) : (
              <div role="status" aria-label="Đang tải bài viết" className="grid grid-cols-1 md:grid-cols-3 gap-8">
                {Array.from({ length: NEWS_COUNT }, (_, i) => (
                  <SkeletonCard key={i} className="h-[380px]" />
                ))}
              </div>
            )}
          </div>
        </section>
      )}

      {/* 12. Don't Board Me Style Contact Vet Hotline & Booking CTA */}
      <ContactVetCTA />
    </div>
  )
}
