import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Moon, Scissors, Stethoscope, ArrowRight, Sparkles } from 'lucide-react'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { usePublicServiceList } from '@/shared/hooks/usePublicSite'
import type { ServiceGroup } from '@/shared/types/catalog'
import { servicePriceLabel } from '@/shared/utils/public-format'

gsap.registerPlugin(ScrollTrigger)

/** Một bước của sân khấu = một nhóm dịch vụ có dữ liệu (UC15), nên tối đa 3 bước dù chuỗi có bao nhiêu dịch vụ. */
interface GroupMeta {
  title: string
  desc: string
  img: string
  circleBg: string
  icon: React.ReactNode
  /** Lưu trú không đặt qua lịch hẹn (BookingPage chỉ nhận khám / tiêm và thẩm mỹ): dẫn tới chi nhánh để gọi đặt chỗ. */
  cta: { to: string; label: string }
}

const GROUPS: Record<ServiceGroup, GroupMeta> = {
  MEDICAL: {
    title: 'KHÁM & TIÊM PHÒNG',
    desc: 'Khám và tiêm phòng tại chi nhánh, do bác sĩ thú y thực hiện.',
    img: '/imgs/i4.png',
    circleBg: '#e4f8ec',
    icon: <Stethoscope size={24} />,
    cta: { to: '/booking', label: 'ĐẶT LỊCH NGAY' },
  },
  GROOMING: {
    title: 'SPA & CẮT TỈA LÔNG',
    desc: 'Tắm, cắt tỉa và chăm sóc vệ sinh cho thú cưng.',
    img: '/imgs/slide2.png',
    circleBg: '#eaf4fb',
    icon: <Scissors size={24} />,
    cta: { to: '/booking', label: 'ĐẶT LỊCH NGAY' },
  },
  BOARDING: {
    title: 'KHÁCH SẠN LƯU TRÚ',
    desc: 'Gửi thú cưng theo loại chuồng, tính giá theo đêm. Đặt chỗ qua số điện thoại chi nhánh.',
    img: '/imgs/slide3.png',
    circleBg: '#f7d6dc',
    icon: <Moon size={24} />,
    cta: { to: '/branches', label: 'LIÊN HỆ CHI NHÁNH' },
  },
}

const GROUP_ORDER: ServiceGroup[] = ['MEDICAL', 'GROOMING', 'BOARDING']
const SCROLL_PER = 500 // px per service step

export const OurServicesStage: React.FC = () => {
  const services = usePublicServiceList()
  const [activeIndex, setActiveIndex] = useState<number>(0)
  const [unlocked, setUnlocked] = useState<boolean>(false)
  const prevIndexRef = useRef<number>(0)
  const stRef = useRef<ScrollTrigger | null>(null)

  const sectionRef = useRef<HTMLDivElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const circleRef = useRef<HTMLDivElement>(null)
  const numRef = useRef<HTMLDivElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const descRef = useRef<HTMLParagraphElement>(null)
  const perkRef = useRef<HTMLDivElement>(null)

  // Nhóm nào có dịch vụ thì thành một bước; giá ghi dịch vụ rẻ nhất của nhóm kèm tên, các dịch vụ còn lại nằm ở dòng "perk".
  const slides = useMemo(
    () =>
      GROUP_ORDER.flatMap((group) => {
        const list = (services.data ?? []).filter((s) => s.group === group)
        if (list.length === 0) return []
        const cheapest = list.reduce((a, b) => (b.price < a.price ? b : a))
        const names = list.map((s) => s.name)
        return [
          {
            id: group,
            meta: GROUPS[group],
            perk: names.length > 3 ? `${names.slice(0, 3).join(' · ')} +${names.length - 3}` : names.join(' · '),
            price: `${cheapest.name} · ${servicePriceLabel(cheapest)}`,
            // BR-CK-02: khám, tiêm, thẩm mỹ kèm ghi chú chi phí thực tế; lưu trú tính giá theo đêm thì không.
            doctorNote: group !== 'BOARDING',
          },
        ]
      }).map((slide, i) => ({ ...slide, num: String(i + 1).padStart(2, '0') })),
    [services.data],
  )
  const count = slides.length

  // Animate content when activeIndex changes
  useEffect(() => {
    const prev = prevIndexRef.current
    if (prev === activeIndex) return
    const goingForward = activeIndex > prev
    prevIndexRef.current = activeIndex
    if (!numRef.current || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    const tl = gsap.timeline()

    // Out
    tl.to([numRef.current, titleRef.current, descRef.current, perkRef.current], {
      opacity: 0,
      y: goingForward ? -16 : 16,
      duration: 0.16,
      ease: 'power2.in',
      stagger: 0.025,
    })
    tl.to(
      imgRef.current,
      { scale: 0.82, opacity: 0, rotate: goingForward ? 6 : -6, duration: 0.18, ease: 'power2.in' },
      '<'
    )
    // In
    tl.fromTo(
      [numRef.current, titleRef.current, descRef.current, perkRef.current],
      { opacity: 0, y: goingForward ? 16 : -16 },
      { opacity: 1, y: 0, duration: 0.3, ease: 'power2.out', stagger: 0.04 }
    )
    tl.fromTo(
      imgRef.current,
      { scale: 0.82, opacity: 0, rotate: goingForward ? -6 : 6 },
      { scale: 1, opacity: 1, rotate: 0, duration: 0.36, ease: 'back.out(1.6)' },
      '<0.04'
    )
    tl.fromTo(
      circleRef.current,
      { scale: 0.93 },
      { scale: 1, duration: 0.32, ease: 'back.out(2)' },
      '<'
    )
  }, [activeIndex])

  // ScrollTrigger: pin section and scrub through the services (first pass only). Dựng lại khi số bước đổi: dữ liệu API về
  // sau lúc mount nên lúc đầu chưa có section để pin; dưới 2 bước thì không pin (snap và tiến độ chia cho count - 1).
  useEffect(() => {
    const section = sectionRef.current
    if (!section || count < 2) return

    const total = SCROLL_PER * (count - 1)

    const mm = gsap.matchMedia()

    mm.add('(min-width: 769px) and (prefers-reduced-motion: no-preference)', () => {
      // Desktop: enable pin + scrub
      stRef.current = ScrollTrigger.create({
        trigger: section,
        start: 'top top',
        end: `+=${total}`,
        pin: true,
        scrub: 0.5,
        snap: {
          snapTo: 1 / (count - 1),
          duration: { min: 0.2, max: 0.45 },
          delay: 0.04,
          ease: 'power2.inOut',
        },
        onUpdate: (self) => {
          const idx = Math.min(count - 1, Math.round(self.progress * (count - 1)))
          setActiveIndex((prev) => (prev !== idx ? idx : prev))
        },
        onLeave: () => {
          // First pass complete — unlock click mode
          stRef.current?.kill()
          stRef.current = null
          setUnlocked(true)
          setActiveIndex(count - 1)
          // Wait for pin spacer removal + React re-render before refreshing downstream STs
          requestAnimationFrame(() => {
            ScrollTrigger.refresh()
          })
        },
      })
      return () => {
        stRef.current?.kill()
        stRef.current = null
      }
    })

    // Giảm chuyển động: không ghim màn hình khi cuộn, các chấm chuyển bước bằng cú bấm.
    mm.add('(min-width: 769px) and (prefers-reduced-motion: reduce)', () => {
      setUnlocked(true)
    })

    mm.add('(max-width: 768px)', () => {
      // Mobile: disable pin — chuyển bước bằng cú bấm vào chấm
      section.style.height = 'auto'
      section.style.minHeight = '100vh'
      setUnlocked(true)
      return () => {}
    })

    return () => {
      stRef.current?.kill()
      stRef.current = null
      mm.revert()
    }
  }, [count])

  // Có / mất section (hoặc đổi số bước) làm đổi bố cục phía dưới: đo lại mọi ScrollTrigger, kể cả các pin khác của trang chủ.
  useEffect(() => {
    const frame = requestAnimationFrame(() => ScrollTrigger.refresh())
    return () => cancelAnimationFrame(frame)
  }, [services.status, count])

  // Lỗi hoặc chưa có dịch vụ nào thì ẩn cả sân khấu, không để bước giả.
  if (services.isError || (services.isSuccess && count === 0)) return null

  if (count === 0) {
    // Đang tải: giữ đúng chiều cao 100vh của sân khấu để các trigger phía dưới đo đúng.
    return (
      <section
        role="status"
        aria-label="Đang tải dịch vụ"
        className="relative w-full bg-[#fdf6ec]"
        style={{ height: '100vh', minHeight: '660px' }}
      >
        <div aria-hidden className="flex h-full flex-col items-center justify-center gap-8 px-4">
          <div className="h-9 w-72 max-w-full animate-pulse rounded-full bg-[#faebe4] motion-reduce:animate-none" />
          <div className="h-[180px] w-[180px] animate-pulse rounded-full bg-[#faebe4] motion-reduce:animate-none sm:h-[240px] sm:w-[240px] lg:h-[320px] lg:w-[320px]" />
          <div className="h-12 w-64 max-w-full animate-pulse rounded-full bg-[#faebe4] motion-reduce:animate-none" />
        </div>
      </section>
    )
  }

  const index = Math.min(activeIndex, count - 1)
  const service = slides[index]

  return (
    <section
      ref={sectionRef}
      className="relative w-full overflow-hidden bg-[#fdf6ec] select-none"
      style={{ height: '100vh', minHeight: '660px' }}
    >
      <div className="relative w-full h-full flex flex-col justify-between pt-20 sm:pt-24 pb-4 sm:pb-5 px-4 sm:px-8 md:px-12">

        {/* 1. TOP HEADER */}
        <div className="relative text-center w-full z-10 shrink-0">
          <div className="relative inline-block">
            <h2 className="font-friendly font-extrabold text-accent text-[clamp(26px,3.8vw,52px)] leading-[1.05] tracking-normal uppercase">
              DỊCH VỤ CỦA CHÚNG TÔI
            </h2>
            {/* Tennis ball */}
            <div className="absolute -top-2 right-[-22px] sm:right-[-28px] w-6 h-6 sm:w-7 sm:h-7 rounded-full drop-shadow-md flex items-center justify-center -rotate-12 animate-bounce motion-reduce:animate-none pointer-events-none">
              <svg viewBox="0 0 100 100" className="w-full h-full">
                <circle cx="50" cy="50" r="47" fill="#d4f016" stroke="#c0dc05" strokeWidth="2" />
                <path d="M20 18 C38 32 38 68 20 82" stroke="#ffffff" strokeWidth="6" strokeLinecap="round" fill="none" />
                <path d="M80 18 C62 32 62 68 80 82" stroke="#ffffff" strokeWidth="6" strokeLinecap="round" fill="none" />
              </svg>
            </div>
          </div>

          {/* Scroll hint — fade out after first service */}
          {count > 1 && (
            <div
              className="mt-1 flex items-center justify-center gap-1.5 text-accent/70 text-[10px] sm:text-[11px] font-semibold tracking-widest uppercase transition-opacity duration-500"
              style={{ opacity: index === 0 ? 1 : 0 }}
            >
              <span>Cuộn để khám phá</span>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                <path d="M12 5v14M5 12l7 7 7-7" />
              </svg>
            </div>
          )}
        </div>

        {/* 2. MAIN STAGE */}
        <div className="relative w-full max-w-6xl mx-auto flex-1 flex items-center justify-center my-1 sm:my-2">

          {/* Big number top-left */}
          <div className="absolute left-0 sm:left-2 top-0 sm:top-2 z-20 pointer-events-none">
            <div
              ref={numRef}
              className="font-friendly font-extrabold text-accent text-[48px] sm:text-[68px] md:text-[84px] leading-none tracking-tighter opacity-90"
            >
              {service.num}
            </div>
          </div>

          {/* Center circle stage */}
          <div
            ref={circleRef}
            className="relative w-[180px] h-[180px] sm:w-[240px] sm:h-[240px] md:w-[280px] md:h-[280px] lg:w-[320px] lg:h-[320px] rounded-full flex items-center justify-center shadow-[0_20px_45px_rgba(164,51,36,0.08)] transition-colors duration-500"
            style={{ backgroundColor: service.meta.circleBg }}
          >
            <img
              ref={imgRef}
              src={service.meta.img}
              alt=""
              className="w-[84%] h-[84%] object-contain drop-shadow-[0_16px_28px_rgba(105,52,19,0.16)]"
            />
          </div>

          {/* Right: icon dots + connecting line (một bước thì không cần điều hướng) */}
          {count > 1 && (
            <div className="absolute right-0 sm:right-2 top-1/2 -translate-y-1/2 z-20 flex flex-col gap-2.5 sm:gap-3 items-center">
              <div className="absolute inset-x-1/2 top-5 bottom-5 -translate-x-1/2 w-[1.5px] bg-accent/20 -z-10 rounded-full" />
              {slides.map((s, idx) => {
                const isActive = idx === index
                return (
                  <button
                    key={s.id}
                    onClick={() => {
                      if (unlocked) {
                        prevIndexRef.current = activeIndex
                        setActiveIndex(idx)
                      } else {
                        const section = sectionRef.current
                        if (!section) return
                        const sectionTop = section.getBoundingClientRect().top + window.scrollY
                        window.scrollTo({ top: sectionTop + idx * SCROLL_PER, behavior: 'smooth' })
                      }
                    }}
                    aria-label={`Dịch vụ ${s.meta.title}`}
                    aria-current={isActive ? 'true' : undefined}
                    className={`rounded-full flex items-center justify-center transition-all duration-300 shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent ${
                      isActive
                        ? 'w-10 h-10 sm:w-11 sm:h-11 bg-accent text-white ring-2 ring-white scale-110'
                        : 'w-8 h-8 sm:w-9 sm:h-9 bg-white/90 text-accent hover:bg-white hover:scale-105 border border-accent/20'
                    }`}
                  >
                    {s.meta.icon}
                  </button>
                )
              })}
            </div>
          )}
        </div>

        {/* 3. BOTTOM: info + CTA */}
        <div className="relative w-full max-w-6xl mx-auto flex flex-col md:flex-row md:items-end justify-between gap-2.5 sm:gap-3 z-20 pt-2 border-t border-accent/15 shrink-0">
          <div className="max-w-lg">
            <div
              ref={perkRef}
              className="inline-flex max-w-full items-center gap-1.5 px-3 py-1 bg-white/95 rounded-full text-[10px] sm:text-[11px] font-black tracking-wider text-accent uppercase mb-1.5 shadow-sm border border-accent/10"
            >
              <Sparkles size={12} className="text-amber-500 shrink-0" />
              <span className="truncate">{service.perk}</span>
            </div>
            <h3
              ref={titleRef}
              className="font-friendly font-bold text-accent text-[22px] sm:text-[30px] md:text-[36px] leading-[1.05] tracking-tight uppercase"
            >
              {service.meta.title}
            </h3>
            <p
              ref={descRef}
              className="mt-1 text-xs sm:text-[13px] font-semibold text-[#70584b] leading-relaxed line-clamp-2 max-w-md"
            >
              {service.meta.desc}
            </p>
          </div>

          <div className="flex flex-col items-end gap-1.5 pb-1">
            {/* Starting Price Pill */}
            <span className="bg-[#faebe4] text-accent text-xs font-bold px-3 py-1 rounded-full border border-accent/15 whitespace-nowrap">
              {service.price}
            </span>
            {service.doctorNote && (
              <span className="max-w-[16rem] text-right text-[11px] font-medium leading-snug text-[#70584b]">
                Chi phí thực tế phụ thuộc chỉ định của bác sĩ
              </span>
            )}
            <Link
              to={service.meta.cta.to}
              className="inline-flex items-center gap-2 rounded-full bg-accent hover:bg-accent-hover px-6 sm:px-8 py-2.5 sm:py-3 font-friendly font-bold text-lg sm:text-xl text-white uppercase tracking-wider shadow-[0_10px_22px_rgba(164,51,36,0.3)] -rotate-2 hover:rotate-0 hover:scale-105 active:scale-95 transition-all duration-200 motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            >
              <span>{service.meta.cta.label}</span>
              <ArrowRight size={16} className="rotate-2" />
            </Link>
          </div>
        </div>

        {/* 4. Bottom progress bar */}
        <div className="absolute bottom-0 left-0 right-0 h-1 bg-accent/15 z-30">
          <div
            className="h-full bg-accent transition-all duration-300 ease-out"
            style={{ width: `${((index + 1) / count) * 100}%` }}
          />
        </div>
      </div>

      {/* Seamless transition into StackingProcessCards (#382417 slab toi) */}
      <div className="w-full overflow-hidden leading-none absolute bottom-0 left-0 right-0 z-20 pointer-events-none">
        <svg
          viewBox="0 0 1440 32"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-full h-5 sm:h-8 block"
          preserveAspectRatio="none"
        >
          <path
            d="M0,32 C480,0 960,0 1440,32 L1440,32 L0,32 Z"
            fill="#382417"
          />
        </svg>
      </div>
    </section>
  )
}
