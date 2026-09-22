import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import {
  ChevronDown,
  ChevronUp,
  Sparkles,
  PawPrint,
  Calendar,
  User,
  ArrowRight,
  Star,
} from 'lucide-react'
import { toast } from 'sonner'
import { HeroBanner } from '@/components/customer/HeroBanner'
import { OurServicesStage } from '@/components/customer/OurServicesStage'
import { StackingProcessCards } from '@/components/customer/StackingProcessCards'
import { NoCagesPhilosophy } from '@/components/customer/NoCagesPhilosophy'
import { TestimonialsSpeechBubble } from '@/components/customer/TestimonialsSpeechBubble'
import { ContactVetCTA } from '@/components/customer/ContactVetCTA'
import { PHOTOS, FEATURED, filterFeaturedByPetType, STATS, PARTNERS, FAQS, DOCTORS } from './home.mock'
import { NEWS_ARTICLES } from './news.mock'
import styles from './HomePage.module.css'

gsap.registerPlugin(ScrollTrigger)

export function HomePage() {
  const navigate = useNavigate()
  const [openFaq, setOpenFaq] = useState<number | null>(0)
  const [petFilter, setPetFilter] = useState<'all' | 'dog' | 'cat'>('all')

  useEffect(() => {
    if (typeof window === 'undefined') return

    const ctx = gsap.context(() => {

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

      // 3. Featured Products reveal
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
        }
      )

      // 4. Doctors reveal
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

    return () => ctx.revert()
  }, [])

  const handleAddToCart = (_id: string, name: string) => {
    toast.success(`Đã thêm ${name} vào giỏ hàng`)
  }

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
              SẢN PHẨM BÁN CHẠY
            </div>
            <div className="font-friendly text-[clamp(32px,4vw,42px)] font-extrabold text-(--color-text-primary) mb-6">
              Sản Phẩm Nổi Bật
            </div>
            <div className="flex justify-center gap-2">
              {(['all', 'dog', 'cat'] as const).map((f) => (
                <button
                  key={f}
                  onClick={() => setPetFilter(f)}
                  className={`px-5 py-2 rounded-full text-[13px] font-bold transition-colors ${petFilter === f ? 'bg-accent text-white' : 'bg-white text-[#5a3a29] border border-accent/20 hover:border-accent hover:text-accent'}`}
                >
                  {f === 'all' ? 'Tất cả' : f === 'dog' ? 'Cho Chó' : 'Cho Mèo'}
                </button>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-6">
            {filterFeaturedByPetType(FEATURED, petFilter).map((p) => (
              <div className="gsap-product-card transition-transform hover:-translate-y-1" key={p.id}>
                <Link
                  to={`/shop/${p.id}`}
                  className="block w-full overflow-hidden rounded-(--radius-rounded) bg-white shadow-(--shadow-1)"
                >
                  <div className="relative aspect-4/3 bg-gray-100">
                    <img src={p.image} alt={p.name} className="h-full w-full object-cover" />
                    {p.badge && (
                      <span className="absolute top-2 left-2 rounded-full bg-amber-400 px-2 py-0.5 text-[11px] font-bold text-white">
                        {p.badge}
                      </span>
                    )}
                  </div>
                  <div className="p-3">
                    <div className="text-[11px] tracking-wide text-gray-500 uppercase">{p.category}</div>
                    <div className="my-0.5 mb-1.5 text-[15px] font-bold text-gray-900">{p.name}</div>
                    <div className="flex items-center justify-between">
                      <span className="text-base font-bold text-gray-900">{p.price}</span>
                      <span className="flex items-center gap-1 text-sm text-gray-500">
                        <Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" />
                        {p.rating}
                      </span>
                    </div>
                  </div>
                </Link>
                <button
                  onClick={() => handleAddToCart(p.id, p.name)}
                  className="mt-2 w-full rounded-lg border border-accent bg-accent px-3 py-2 text-[13px] font-semibold text-white hover:bg-accent-hover transition-colors"
                >
                  Thêm vào giỏ
                </button>
              </div>
            ))}
          </div>
          <div className="text-center mt-10">
            <button
              onClick={() => navigate('/shop')}
              className="rounded-full border-2 border-accent px-8 py-3 font-bold text-accent hover:bg-accent hover:text-white transition-colors inline-flex items-center gap-2"
            >
              Xem tất cả sản phẩm <ArrowRight size={18} />
            </button>
          </div>
        </div>
      </section>

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
      <section className="gsap-doctors-section px-6 py-14 bg-[#fdf6ec]">
        <div className={styles.wrap}>
          <div className="text-center mb-12">
            <div className="text-[12px] font-bold text-accent tracking-widest uppercase flex items-center justify-center gap-2 mb-3">
              ĐỘI NGŨ TẬN TÂM VÌ THÚ CƯNG <PawPrint size={14} />
            </div>
            <div className="font-friendly text-[clamp(32px,4vw,42px)] font-extrabold text-(--color-text-primary) leading-tight">
              Gặp Gỡ Đội Ngũ
              <br />
              Bác Sĩ Chuyên Môn
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-8 text-center">
            {DOCTORS.map((d, i) => (
              <div key={i} className={`${styles.doctorCard} ${styles.doctorCardReveal} gsap-doctor-card`}>
                <div className={styles.doctorImgWrap}>
                  <img src={d.photo} alt={d.name} className={styles.doctorImg} />
                  <img src={PHOTOS.doodlePaw} className={styles.doctorPawDecor} alt="" />
                </div>
                <h4 className="font-friendly text-[22px] font-bold text-(--color-text-primary) mt-5 mb-1">
                  {d.name}
                </h4>
                <p className="text-[14px] text-(--color-text-secondary)">{d.role}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

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
      <section className="px-6 py-14 bg-[#f6e8da]">
        <div className={styles.wrap}>
          <div className="flex flex-col md:flex-row items-center justify-between gap-6 mb-12">
            <div>
              <div className="text-[12px] font-bold text-accent tracking-widest uppercase flex items-center gap-2 mb-2">
                TIN TỨC & BÀI VIẾT <PawPrint size={14} />
              </div>
              <div className="font-friendly text-[clamp(32px,4vw,42px)] font-extrabold text-(--color-text-primary) leading-tight">
                Bài Viết Mới Nhất
              </div>
            </div>
            <button
              className="px-6 py-2.5 rounded-full bg-[#faebe4] hover:bg-accent hover:text-white transition-colors flex items-center gap-2 font-bold text-[14px] text-accent border border-accent/20"
            >
              Xem Tất Cả <ArrowRight size={18} />
            </button>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
            {NEWS_ARTICLES.slice(0, 3).map((a) => (
              <div key={a.id} className={styles.articleCard}>
                <div className={styles.articleImgWrap}>
                  <img src={a.image} alt={a.title} className={styles.articleImg} />
                  <div className={styles.articleBadges}>
                    <span className={styles.articleBadge}>{a.category}</span>
                  </div>
                </div>
                <div className="p-7">
                  <div className="flex items-center gap-5 text-[13px] font-semibold text-(--color-text-secondary) mb-4">
                    <span className="flex items-center gap-2">
                      <User size={16} className="text-accent" /> {a.author}
                    </span>
                    <span className="flex items-center gap-2">
                      <Calendar size={16} className="text-accent" /> {a.date}
                    </span>
                  </div>
                  <h3 className="font-friendly text-[22px] font-extrabold text-(--color-text-primary) leading-[1.3]">
                    {a.title}
                  </h3>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 12. Don't Board Me Style Contact Vet Hotline & Booking CTA */}
      <ContactVetCTA />
    </div>
  )
}
