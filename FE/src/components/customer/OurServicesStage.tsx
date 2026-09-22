import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Moon, Scissors, Footprints, Stethoscope, ArrowRight, Sparkles } from 'lucide-react'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

interface ServiceItem {
  id: string
  num: string
  title: string
  subtitle: string
  desc: string
  img: string
  circleBg: string
  icon: React.ReactNode
  perk: string
  startingPrice: string
}

const SERVICES: ServiceItem[] = [
  {
    id: 'walking',
    num: '01',
    title: 'DẮT DẠO NGOÀI TRỜI',
    subtitle: 'DOG WALKING & PLAY',
    desc: 'Huấn luyện viên dắt dạo riêng theo thể lực từng bé trong công viên xanh, chơi bóng ném tennis, 100% không ghép đàn lạ gây căng thẳng.',
    img: '/imgs/slide1.png',
    circleBg: '#fde8ec',
    icon: <Footprints size={24} />,
    perk: 'Vận động ngoài trời 45 phút mỗi ngày',
    startingPrice: 'Từ 120.000đ / buổi',
  },
  {
    id: 'grooming',
    num: '02',
    title: 'SPA & CẮT TỈA LÔNG',
    subtitle: 'BATHING & STYLING',
    desc: 'Tắm thảo mộc hữu cơ khử mùi, cắt mài móng, vệ sinh tai kẽ chân, sấy phồng tơi xốp và tạo kiểu Teddy Bear độc quyền.',
    img: '/imgs/slide2.png',
    circleBg: '#eaf4fb',
    icon: <Scissors size={24} />,
    perk: 'Tặng xịt dưỡng lông thơm thảo dược 7 ngày',
    startingPrice: 'Từ 200.000đ / lần',
  },
  {
    id: 'overnight',
    num: '03',
    title: 'KHÁCH SẠN LƯU TRÚ',
    subtitle: 'OVERNIGHT CARE 24/7',
    desc: 'Phòng riêng máy lạnh 24-26°C, đệm êm ấm áp, 100% không chuồng nhốt. Camera Live Cam 24/7 xem bé ngủ và nói chuyện qua App.',
    img: '/imgs/slide3.png',
    circleBg: '#f7d6dc',
    icon: <Moon size={24} />,
    perk: 'Live Cam FHD 24/7 trực tiếp trên điện thoại',
    startingPrice: 'Từ 350.000đ / đêm',
  },
  {
    id: 'vet',
    num: '04',
    title: 'BÁC SĨ & DINH DƯỠNG',
    subtitle: 'VET CARE & NUTRITION',
    desc: 'Đội ngũ bác sĩ thú y trực 24/7 kiểm tra lâm sàng hàng ngày. Thực đơn ức gà và bò tươi áp chảo nấu mới mỗi bữa.',
    img: '/imgs/i4.png',
    circleBg: '#e4f8ec',
    icon: <Stethoscope size={24} />,
    perk: 'Thực đơn dinh dưỡng nấu mới 100% theo bữa',
    startingPrice: 'Từ 500.000đ / lượt khám',
  },
]

export const OurServicesStage: React.FC = () => {
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

  // Animate content when activeIndex changes
  useEffect(() => {
    const prev = prevIndexRef.current
    if (prev === activeIndex) return
    const goingForward = activeIndex > prev
    prevIndexRef.current = activeIndex

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

  // ScrollTrigger: pin section and scrub through 4 services (first pass only)
  useEffect(() => {
    const section = sectionRef.current
    if (!section) return

    const SCROLL_PER = 500 // px per service step
    const total = SCROLL_PER * (SERVICES.length - 1) // 1500px

    const mm = gsap.matchMedia()

    mm.add('(min-width: 769px)', () => {
      // Desktop: enable pin + scrub
      stRef.current = ScrollTrigger.create({
        trigger: section,
        start: 'top top',
        end: `+=${total}`,
        pin: true,
        scrub: 0.5,
        snap: {
          snapTo: 1 / (SERVICES.length - 1),
          duration: { min: 0.2, max: 0.45 },
          delay: 0.04,
          ease: 'power2.inOut',
        },
        onUpdate: (self) => {
          const idx = Math.min(
            SERVICES.length - 1,
            Math.round(self.progress * (SERVICES.length - 1))
          )
          setActiveIndex((prev) => (prev !== idx ? idx : prev))
        },
        onLeave: () => {
          // First pass complete — unlock click mode
          stRef.current?.kill()
          stRef.current = null
          setUnlocked(true)
          setActiveIndex(SERVICES.length - 1)
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

    mm.add('(max-width: 768px)', () => {
      // Mobile: disable pin — use native horizontal touch-snap via CSS
      section.style.height = 'auto'
      section.style.minHeight = '100vh'
      return () => {}
    })

    return () => {
      stRef.current?.kill()
      stRef.current = null
      mm.revert()
    }
  }, [])

  const service = SERVICES[activeIndex]

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
            <h2 className="font-bayon text-accent text-[clamp(26px,3.8vw,52px)] leading-[0.95] tracking-normal uppercase">
              DỊCH VỤ CỦA CHÚNG TÔI
            </h2>
            {/* Tennis ball */}
            <div className="absolute -top-2 right-[-22px] sm:right-[-28px] w-6 h-6 sm:w-7 sm:h-7 rounded-full drop-shadow-md flex items-center justify-center -rotate-12 animate-bounce pointer-events-none">
              <svg viewBox="0 0 100 100" className="w-full h-full">
                <circle cx="50" cy="50" r="47" fill="#d4f016" stroke="#c0dc05" strokeWidth="2" />
                <path d="M20 18 C38 32 38 68 20 82" stroke="#ffffff" strokeWidth="6" strokeLinecap="round" fill="none" />
                <path d="M80 18 C62 32 62 68 80 82" stroke="#ffffff" strokeWidth="6" strokeLinecap="round" fill="none" />
              </svg>
            </div>
          </div>

          {/* Scroll hint — fade out after first service */}
          <div
            className="mt-1 flex items-center justify-center gap-1.5 text-accent/70 text-[10px] sm:text-[11px] font-semibold tracking-widest uppercase transition-opacity duration-500"
            style={{ opacity: activeIndex === 0 ? 1 : 0 }}
          >
            <span>Cuộn để khám phá</span>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M12 5v14M5 12l7 7 7-7" />
            </svg>
          </div>
        </div>

        {/* 2. MAIN STAGE */}
        <div className="relative w-full max-w-6xl mx-auto flex-1 flex items-center justify-center my-1 sm:my-2">

          {/* Big number top-left */}
          <div className="absolute left-0 sm:left-2 top-0 sm:top-2 z-20 pointer-events-none">
            <div
              ref={numRef}
              className="font-bayon text-accent text-[48px] sm:text-[68px] md:text-[84px] leading-none tracking-tighter opacity-90"
            >
              {service.num}
            </div>
          </div>

          {/* Center circle stage */}
          <div
            ref={circleRef}
            className="relative w-[180px] h-[180px] sm:w-[240px] sm:h-[240px] md:w-[280px] md:h-[280px] lg:w-[320px] lg:h-[320px] rounded-full flex items-center justify-center shadow-[0_20px_45px_rgba(164,51,36,0.08)] transition-colors duration-500"
            style={{ backgroundColor: service.circleBg }}
          >
            <img
              ref={imgRef}
              src={service.img}
              alt={service.title}
              className="w-[84%] h-[84%] object-contain drop-shadow-[0_16px_28px_rgba(105,52,19,0.16)]"
            />
          </div>

          {/* Right: icon dots + connecting line */}
          <div className="absolute right-0 sm:right-2 top-1/2 -translate-y-1/2 z-20 flex flex-col gap-2.5 sm:gap-3 items-center">
            <div className="absolute inset-x-1/2 top-5 bottom-5 -translate-x-1/2 w-[1.5px] bg-accent/20 -z-10 rounded-full" />
            {SERVICES.map((s, idx) => {
              const isActive = idx === activeIndex
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
                      const SCROLL_PER = 500
                      const sectionTop = section.getBoundingClientRect().top + window.scrollY
                      window.scrollTo({ top: sectionTop + idx * SCROLL_PER, behavior: 'smooth' })
                    }
                  }}
                  aria-label={`Dịch vụ ${s.title}`}
                  className={`rounded-full flex items-center justify-center transition-all duration-300 shadow-md ${
                    isActive
                      ? 'w-10 h-10 sm:w-11 sm:h-11 bg-accent text-white ring-2 ring-white scale-110'
                      : 'w-8 h-8 sm:w-9 sm:h-9 bg-white/90 text-accent hover:bg-white hover:scale-105 border border-accent/20'
                  }`}
                >
                  {s.icon}
                </button>
              )
            })}
          </div>
        </div>

        {/* 3. BOTTOM: info + CTA */}
        <div className="relative w-full max-w-6xl mx-auto flex flex-col md:flex-row md:items-end justify-between gap-2.5 sm:gap-3 z-20 pt-2 border-t border-accent/15 shrink-0">
          <div className="max-w-lg">
            <div
              ref={perkRef}
              className="inline-flex items-center gap-1.5 px-3 py-1 bg-white/95 rounded-full text-[10px] sm:text-[11px] font-black tracking-wider text-accent uppercase mb-1.5 shadow-sm border border-accent/10"
            >
              <Sparkles size={12} className="text-amber-500 shrink-0" />
              <span>{service.perk}</span>
            </div>
            <h3
              ref={titleRef}
              className="font-bayon text-accent text-[22px] sm:text-[30px] md:text-[36px] leading-[0.95] tracking-tight uppercase"
            >
              {service.title}
            </h3>
            <p
              ref={descRef}
              className="mt-1 text-xs sm:text-[13px] font-semibold text-[#70584b] leading-relaxed line-clamp-2 max-w-md"
            >
              {service.desc}
            </p>
          </div>

          <div className="flex flex-col items-end gap-1.5 pb-1">
            {/* Starting Price Pill */}
            <span className="bg-[#faebe4] text-accent text-xs font-bold px-3 py-1 rounded-full border border-accent/15 whitespace-nowrap">
              {service.startingPrice}
            </span>
            <Link
              to="/booking"
              className="inline-flex items-center gap-2 rounded-full bg-accent hover:bg-accent-hover px-6 sm:px-8 py-2.5 sm:py-3 font-bayon text-lg sm:text-xl text-white uppercase tracking-wider shadow-[0_10px_22px_rgba(164,51,36,0.3)] -rotate-2 hover:rotate-0 hover:scale-105 active:scale-95 transition-all duration-200"
            >
              <span>ĐẶT LỊCH NGAY</span>
              <ArrowRight size={16} className="rotate-2" />
            </Link>
          </div>
        </div>

        {/* 4. Bottom progress bar */}
        <div className="absolute bottom-0 left-0 right-0 h-1 bg-accent/15 z-30">
          <div
            className="h-full bg-accent transition-all duration-300 ease-out"
            style={{ width: `${((activeIndex + 1) / SERVICES.length) * 100}%` }}
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
