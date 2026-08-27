import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  ChevronDown,
  ChevronUp,
  ChevronLeft,
  ChevronRight,
  Sparkles,
  PawPrint,
  Calendar,
  User,
  ArrowRight,
  ShoppingBag,
} from 'lucide-react'
import { toast } from 'sonner'
import { PHOTOS, CATEGORIES, FEATURED, filterFeaturedByPetType, TESTIMONIALS, HERO_POPS, TRUST_ITEMS, STATS, STEPS, PARTNERS, FAQS, DOCTORS } from './home.mock'
import { NEWS_ARTICLES } from './news.mock'
import styles from './HomePage.module.css'

export function HomePage() {
  const navigate = useNavigate()
  const catStripRef = useRef<HTMLDivElement>(null)
  const [openFaq, setOpenFaq] = useState<number | null>(0)
  const [petFilter, setPetFilter] = useState<'all' | 'dog' | 'cat'>('all')

  const scrollStrip = (ref: React.RefObject<HTMLDivElement | null>, dir: 1 | -1) => {
    const el = ref.current
    if (el) el.scrollBy({ left: dir * el.clientWidth * 0.8, behavior: 'smooth' })
  }
  const heroCopyRef = useRef<HTMLDivElement>(null)
  const scrollHintRef = useRef<HTMLDivElement>(null)
  const heroArtRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = heroArtRef.current
    if (!el || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    const onMove = (e: MouseEvent) => {
      const r = el.getBoundingClientRect()
      const px = (e.clientX - r.left) / r.width - 0.5
      const py = (e.clientY - r.top) / r.height - 0.5
      el.style.transform = `perspective(900px) rotateY(${px * 7}deg) rotateX(${-py * 7}deg)`
    }
    const onLeave = () => {
      el.style.transform = 'perspective(900px) rotateY(0deg) rotateX(0deg)'
    }
    el.addEventListener('mousemove', onMove)
    el.addEventListener('mouseleave', onLeave)
    return () => {
      el.removeEventListener('mousemove', onMove)
      el.removeEventListener('mouseleave', onLeave)
    }
  }, [])

  useEffect(() => {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    let raf: number
    const onScroll = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => {
        const y = window.scrollY || 0
        if (heroCopyRef.current) {
          heroCopyRef.current.style.transform = `translateY(${y * 0.25}px)`
          heroCopyRef.current.style.opacity = String(Math.max(0, 1 - y / 520))
        }
        if (scrollHintRef.current)
          scrollHintRef.current.style.opacity = String(Math.max(0, 1 - y / 180))
      })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(raf)
    }
  }, [])

  const handleAddToCart = (_id: string, name: string) => {
    toast.success(`Đã thêm ${name} vào giỏ hàng`)
  }

  return (
    <div className="relative bg-[var(--color-surface-page)] font-[var(--font-professional)]">
      {/* 1. Hero */}
      <section className={styles.hero}>
        <div className={styles.heroPattern}></div>
        <div className={styles.heroInner}>
          <div className={styles.heroCopy} ref={heroCopyRef}>
            <div className="mb-4 inline-flex items-center gap-2 rounded-full bg-blue-50 px-3.5 py-1.5 text-[13px] font-bold text-teal-600">
              <Sparkles size={14} />
              Hệ thống chăm sóc thú cưng toàn diện
            </div>
            <h1 className="mb-4 font-[var(--font-friendly)] text-[clamp(26px,2.8vw,38px)] leading-[1.25] font-extrabold text-[var(--color-text-primary)]">
              Chăm sóc thú cưng<br />
              toàn diện &amp; khỏe mạnh
            </h1>
            <p className="mb-7 max-w-[480px] text-[15px] leading-[1.6] font-medium text-[var(--color-text-secondary)]">
              Đặt lịch khám thú y, mua sắm đồ dùng, đăng ký spa &amp; khách sạn lưu trú — mọi dịch vụ dành cho thú cưng chỉ trong một ứng dụng duy nhất.
            </p>
            <div className="flex flex-wrap gap-3">
              <button
                onClick={() => navigate('/')}
                className="rounded-full bg-[#843122] px-6 py-3 text-[14px] font-bold text-white hover:bg-[#6a2517] transition-colors"
              >
                Bắt đầu ngay
              </button>
              <button
                onClick={() => navigate('/')}
                className="rounded-full border-2 border-[#843122] px-6 py-3 text-[14px] font-bold text-[#843122] hover:bg-[#843122] hover:text-white transition-colors"
              >
                Tôi đã có tài khoản
              </button>
            </div>
            <div className="mt-8 flex flex-wrap gap-6 border-t border-black/10 pt-6">
              {TRUST_ITEMS.map((t) => (
                <div
                  key={t.label}
                  className="flex items-center gap-2 text-[13px] font-bold text-[var(--color-text-primary)] opacity-90"
                >
                  <t.icon size={17} className="text-[#843122]" />
                  {t.label}
                </div>
              ))}
            </div>
          </div>
          <div className={styles.heroArt} ref={heroArtRef}>
            <img src={PHOTOS.shapePaw} alt="" className={`${styles.heroDoodle} ${styles.d1}`} />
            <img src={PHOTOS.shapeBone} alt="" className={`${styles.heroDoodle} ${styles.d2}`} />
            <img src={PHOTOS.shapeCat} alt="" className={`${styles.heroDoodle} ${styles.d3}`} />
            <img
              src={PHOTOS.heroDog}
              alt="Chó khỏe mạnh được chăm sóc"
              className={styles.heroDog}
            />
            <div className={styles.heroSeal}>
              <svg viewBox="0 0 100 100" className={styles.heroSealText} aria-hidden="true">
                <defs>
                  <path id="sealPath" d="M50,50 m-37,0 a37,37 0 1,1 74,0 a37,37 0 1,1 -74,0" />
                </defs>
                <text>
                  <textPath href="#sealPath">• YÊU THƯƠNG • KHỎE MẠNH • TẬN TÂM </textPath>
                </text>
              </svg>
              <PawPrint size={26} className={styles.heroSealIcon} />
            </div>
            {HERO_POPS.map((p) => (
              <div key={p.label} className={`${styles.heroPop} ${styles[p.pos]}`}>
                <div className={styles.heroPopImg}>
                  <img src={p.image} alt={p.label} loading="lazy" />
                </div>
                <div>
                  <div className={styles.heroPopLabel}>{p.label}</div>
                  <div className={styles.heroPopSub}>{p.sub}</div>
                </div>
              </div>
            ))}
            <div className={styles.heroAvatars}>
              <div className={styles.heroAvatarStack}>
                <img src="/imgs/author1.png" alt="" />
                <img src="/imgs/author2.png" alt="" />
                <img src="/imgs/author4.png" alt="" />
              </div>
              <div>
                <div className={styles.heroAvatarCount}>12K+ khách hàng</div>
                <div className={styles.heroAvatarSub}>tin tưởng mỗi ngày</div>
              </div>
            </div>
          </div>
        </div>
        <div className={styles.heroScrollHint} ref={scrollHintRef}>
          <span>Cuộn để khám phá</span>
          <ChevronDown size={18} />
        </div>
      </section>

      {/* 2. Categories */}
      <section className="px-6 pt-6 pb-12">
        <div className={styles.wrap}>
          <div className={styles.stripHead}>
            <div className="font-[var(--font-friendly)] text-2xl font-extrabold text-[var(--color-text-primary)]">
              Chúng tôi có thể giúp gì cho bạn?
            </div>
            <div className={styles.stripArrows}>
              <button
                className={styles.arrowBtn}
                aria-label="Cuộn trái"
                onClick={() => scrollStrip(catStripRef, -1)}
              >
                <ChevronLeft size={18} />
              </button>
              <button
                className={styles.arrowBtn}
                aria-label="Cuộn phải"
                onClick={() => scrollStrip(catStripRef, 1)}
              >
                <ChevronRight size={18} />
              </button>
            </div>
          </div>
          <div className={`${styles.hScroll} flex gap-4`} ref={catStripRef}>
            {CATEGORIES.map((c) => (
              <div
                key={c.name}
                className={`${styles.catCard} ${styles.revealItem} flex min-w-[140px] flex-1 cursor-pointer flex-col items-center justify-center gap-3 rounded-[var(--radius-rounded)] bg-[var(--color-surface-card)] p-5 text-center shadow-[var(--shadow-1)]`}
                onClick={() => navigate(c.page === 'listing' ? '/shop' : c.page === 'booking' ? '/booking' : '/')}
              >
                {c.image ? (
                  <div className="flex h-10 w-10 items-center justify-center rounded-full bg-orange-50">
                    <img src={c.image} alt="" className="h-6 w-6 object-contain" />
                  </div>
                ) : (
                  <div className="flex h-10 w-10 items-center justify-center rounded-full bg-orange-50">
                    <ShoppingBag size={24} className="text-[#843122]" />
                  </div>
                )}
                <div className="text-[14px] font-bold text-[var(--color-text-primary)]">
                  {c.name}
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 3. 3 Steps */}
      <section className="relative overflow-hidden px-6 py-14">
        <img
          src={PHOTOS.doodlePaw}
          alt=""
          className={styles.sectionDoodle}
          style={{ top: 8, left: '3%', width: 56 }}
        />
        <img
          src={PHOTOS.doodleCollar}
          alt=""
          className={styles.sectionDoodle}
          style={{ bottom: 4, right: '4%', width: 64 }}
        />
        <div className={styles.wrap}>
          <div className="mb-2 text-center text-[13px] font-bold tracking-wide text-teal-600 uppercase">
            Đơn giản &amp; nhanh chóng
          </div>
          <div className="mb-10 text-center font-[var(--font-friendly)] text-[clamp(24px,3vw,32px)] font-extrabold text-[var(--color-text-primary)]">
            Chăm sóc thú cưng chỉ trong 3 bước
          </div>
          <div className={styles.stepGrid}>
            {STEPS.map((s) => (
              <div key={s.step} className={styles.stepCard}>
                <span className={styles.stepNum}>{s.step}</span>
                <div className={styles.stepIcon}>
                  <s.icon size={26} />
                </div>
                <div className="mb-1.5 text-lg font-bold text-[var(--color-text-primary)]">
                  {s.title}
                </div>
                <div className="text-sm leading-relaxed text-[var(--color-text-secondary)]">
                  {s.desc}
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 4. Featured Products */}
      <section className="relative overflow-hidden bg-gray-50 px-6 py-14">
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
            <div className="font-[var(--font-friendly)] text-[clamp(32px,4vw,42px)] font-extrabold text-[var(--color-text-primary)] mb-6">
              Sản Phẩm Nổi Bật
            </div>
            <div className="flex justify-center gap-2">
              {(['all', 'dog', 'cat'] as const).map((f) => (
                <button
                  key={f}
                  onClick={() => setPetFilter(f)}
                  className={`px-5 py-2 rounded-full text-[13px] font-bold transition-colors ${petFilter === f ? 'bg-[#843122] text-white' : 'bg-white text-gray-600 border border-gray-300 hover:border-[#843122] hover:text-[#843122]'}`}
                >
                  {f === 'all' ? 'Tất cả' : f === 'dog' ? 'Cho Chó' : 'Cho Mèo'}
                </button>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-6">
            {filterFeaturedByPetType(FEATURED, petFilter).map((p) => (
              <div className="transition-transform hover:-translate-y-1" key={p.id}>
                <Link
                  to={`/shop/${p.id}`}
                  className="block w-full overflow-hidden rounded-[var(--radius-rounded)] bg-white shadow-[var(--shadow-1)]"
                >
                  <div className="relative aspect-[4/3] bg-gray-100">
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
                        ★ {p.rating}
                      </span>
                    </div>
                  </div>
                </Link>
                <button
                  onClick={() => handleAddToCart(p.id, p.name)}
                  className="mt-2 w-full rounded-lg border border-[#843122] bg-[#843122] px-3 py-2 text-[13px] font-semibold text-white hover:bg-[#6a2517] transition-colors"
                >
                  Thêm vào giỏ
                </button>
              </div>
            ))}
          </div>
          <div className="text-center mt-10">
            <button
              onClick={() => navigate('/shop')}
              className="rounded-full border-2 border-[#843122] px-8 py-3 font-bold text-[#843122] hover:bg-[#843122] hover:text-white transition-colors inline-flex items-center gap-2"
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
              <div className="font-[var(--font-friendly)] text-[clamp(24px,3vw,32px)] font-extrabold leading-tight">
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
      <section className="px-6 py-14">
        <div className={styles.wrap}>
          <div className="text-center mb-12">
            <div className="text-[12px] font-bold text-[#843122] tracking-widest uppercase flex items-center justify-center gap-2 mb-3">
              ĐỘI NGŨ TẬN TÂM VÌ THÚ CƯNG <PawPrint size={14} />
            </div>
            <div className="font-[var(--font-friendly)] text-[clamp(32px,4vw,42px)] font-extrabold text-[var(--color-text-primary)] leading-tight">
              Gặp Gỡ Đội Ngũ
              <br />
              Bác Sĩ Chuyên Môn
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-8 text-center">
            {DOCTORS.map((d, i) => (
              <div key={i} className={`${styles.doctorCard} ${styles.doctorCardReveal}`}>
                <div className={styles.doctorImgWrap}>
                  <img src={d.photo} alt={d.name} className={styles.doctorImg} />
                  <img src={PHOTOS.doodlePaw} className={styles.doctorPawDecor} alt="" />
                </div>
                <h4 className="font-[var(--font-friendly)] text-[22px] font-bold text-[var(--color-text-primary)] mt-5 mb-1">
                  {d.name}
                </h4>
                <p className="text-[14px] text-[var(--color-text-secondary)]">{d.role}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 7. Testimonials */}
      <section className="relative overflow-hidden bg-gray-50 px-6 py-12">
        <img
          src={PHOTOS.doodleYarn}
          alt=""
          className={styles.sectionDoodle}
          style={{ top: 20, right: '5%', width: 60 }}
        />
        <div className={styles.wrap}>
          <div className={styles.testiHead}>
            <div className="font-[var(--font-friendly)] text-2xl font-extrabold text-[var(--color-text-primary)]">
              Được yêu thích bởi cộng đồng yêu thú cưng
            </div>
            <div className={styles.testiRating}>
              <div className="flex gap-0.5">
                {[1, 2, 3, 4, 5].map(i => (
                  <span key={i} className="text-amber-400">★</span>
                ))}
              </div>
              4.9/5 · 1.567 đánh giá
            </div>
          </div>
          <div className="grid grid-cols-1 gap-5 md:grid-cols-3">
            {TESTIMONIALS.map((t) => (
              <div
                key={t.name}
                className={`${styles.testiCard} ${styles.testiCardReveal} rounded-[var(--radius-rounded)] bg-white p-5 shadow-[var(--shadow-1)]`}
              >
                <span className={styles.testiQuoteMark}>&rdquo;</span>
                <div className="flex gap-0.5 mb-2">
                  {[1, 2, 3, 4, 5].map(i => (
                    <span key={i} className="text-amber-400 text-sm">★</span>
                  ))}
                </div>
                <div className="my-3 text-sm leading-relaxed text-[var(--color-text-primary)]">
                  &ldquo;{t.quote}&rdquo;
                </div>
                <div className="flex items-center gap-2.5">
                  <div className={styles.testiAvatarRing}>
                    <img
                      src={t.avatar}
                      alt={t.name}
                      className="h-9 w-9 rounded-full object-cover"
                    />
                  </div>
                  <div>
                    <div className="text-[13px] font-bold text-[var(--color-text-primary)]">
                      {t.name}
                    </div>
                    <div className="text-xs text-[var(--color-text-secondary)]">{t.pet}</div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 8. Partners */}
      <section className="px-6 py-10">
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
      <section className="relative overflow-hidden px-6 py-12">
        <img
          src={PHOTOS.doodleBone}
          alt=""
          className={styles.sectionDoodle}
          style={{ top: 6, right: '6%', width: 48, transform: 'rotate(-20deg)' }}
        />
        <div className={styles.wrap}>
          <div className="mb-2 text-center font-[var(--font-friendly)] text-[clamp(24px,3vw,32px)] font-extrabold text-[var(--color-text-primary)]">
            Câu hỏi thường gặp
          </div>
          <div className="mb-8 text-center text-sm text-[var(--color-text-secondary)]">
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

      {/* 10. Schedule Visit */}
      <section className="px-6 py-14">
        <div className={styles.scheduleBanner}>
          <div className="text-center mb-8">
            <h2 className="font-[var(--font-friendly)] text-[clamp(28px,4vw,36px)] font-extrabold text-[var(--color-text-primary)]">
              Đặt Lịch Ngay Hôm Nay!
            </h2>
          </div>
          <div className={styles.scheduleForm}>
            <div className={styles.formGroup}>
              <label>Họ tên</label>
              <input type="text" placeholder="Nhập họ tên đầy đủ" />
            </div>
            <div className={styles.formGroup}>
              <label>Loại thú cưng</label>
              <select>
                <option>Chọn loại thú cưng</option>
                <option>Chó</option>
                <option>Mèo</option>
              </select>
            </div>
            <div className={styles.formGroup}>
              <label>Dịch vụ quan tâm</label>
              <select>
                <option>Chọn dịch vụ</option>
                <option>Khám tổng quát</option>
                <option>Tiêm phòng</option>
                <option>Spa & cắt tỉa</option>
              </select>
            </div>
            <div className={styles.formGroup}>
              <label>Ngày</label>
              <input type="date" />
            </div>
            <div className={styles.formGroup}>
              <label>Giờ</label>
              <input type="time" />
            </div>
            <div className={styles.formGroup}>
              <label>Số điện thoại</label>
              <input type="tel" placeholder="09xx xxx xxx" />
            </div>
          </div>
          <div className="text-center mt-10">
            <button
              className="px-8 py-3.5 rounded-full bg-[#843122] text-white hover:bg-[#6a2517] transition-colors flex items-center justify-center mx-auto gap-2 font-bold text-[15px]"
              onClick={() => navigate('/booking')}
            >
              Đặt Lịch Ngay <ArrowRight size={18} />
            </button>
          </div>
        </div>
      </section>

      {/* 11. News */}
      <section className="px-6 py-14 bg-white">
        <div className={styles.wrap}>
          <div className="flex flex-col md:flex-row items-center justify-between gap-6 mb-12">
            <div>
              <div className="text-[12px] font-bold text-[#843122] tracking-widest uppercase flex items-center gap-2 mb-2">
                TIN TỨC & BÀI VIẾT <PawPrint size={14} />
              </div>
              <div className="font-[var(--font-friendly)] text-[clamp(32px,4vw,42px)] font-extrabold text-[var(--color-text-primary)] leading-tight">
                Bài Viết Mới Nhất
              </div>
            </div>
            <button
              className="px-6 py-2.5 rounded-full bg-gray-100 hover:bg-[#843122] hover:text-white transition-colors flex items-center gap-2 font-bold text-[14px] text-[var(--color-text-primary)]"
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
                  <div className="flex items-center gap-5 text-[13px] font-semibold text-[var(--color-text-secondary)] mb-4">
                    <span className="flex items-center gap-2">
                      <User size={16} className="text-[#843122]" /> {a.author}
                    </span>
                    <span className="flex items-center gap-2">
                      <Calendar size={16} className="text-[#843122]" /> {a.date}
                    </span>
                  </div>
                  <h3 className="font-[var(--font-friendly)] text-[22px] font-extrabold text-[var(--color-text-primary)] leading-[1.3]">
                    {a.title}
                  </h3>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  )
}
