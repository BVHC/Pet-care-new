import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

interface StepItem {
  num: string
  titleLine1: string
  titleLine2: string
  desc: string
  img: string
  badgeNum: string
  badgePos: 'tab' | 'top-left' | 'mid-left' | 'top-right'
  badgeColor?: string
}

const STEPS: StepItem[] = [
  {
    num: '01.',
    titleLine1: 'ĐIỀN THÔNG TIN',
    titleLine2: 'ĐẶT LỊCH HẸN',
    desc: 'ĐIỀN THÔNG TIN THÚ CƯNG, CHỌN GÓI DỊCH VỤ VÀ TRỞ THÀNH THÀNH VIÊN THÂN THIẾT CỦA PETCARE.',
    img: '/imgs/i1.png',
    badgeNum: '1',
    badgePos: 'tab',
  },
  {
    num: '02.',
    titleLine1: 'BÁC SĨ LIÊN HỆ',
    titleLine2: 'XÁC NHẬN LỊCH',
    desc: 'DỰA TRÊN HỒ SƠ BÉ, ĐỘI NGŨ BÁC SĨ & CHUYÊN VIÊN SẼ GỌI ĐIỆN TƯ VẤN LỘ TRÌNH CHU ĐÁO NHẤT.',
    img: '/imgs/i2.png',
    badgeNum: '2',
    badgePos: 'top-left',
  },
  {
    num: '03.',
    titleLine1: 'GẶP GỠ & KHÁM',
    titleLine2: 'BAN ĐẦU',
    desc: 'BÁC SĨ THÚ Y THĂM KHÁM TOÀN DIỆN, LÀM QUEN THÂN THIỆN ĐỂ BÉ LUÔN CẢM THẤY AN TOÀN NHẤT.',
    img: '/imgs/i3.png',
    badgeNum: '3',
    badgePos: 'mid-left',
  },
  {
    num: '04.',
    titleLine1: 'TRẢI NGHIỆM',
    titleLine2: 'DỊCH VỤ 5 SAO',
    desc: 'BÉ NGHỈ DƯỠNG PHÒNG VIP, LIVE CAM 24/7 VÀ TRỞ VỀ NHÀ VUI KHỎE, THƠM THO TRỌN NIỀM VUI.',
    img: '/imgs/i4.png',
    badgeNum: '4',
    badgePos: 'top-right',
    badgeColor: '#ffe600',
  },
]

// Fan resting positions for 4 cards (fanned left -> right across the viewport)
const FAN = [
  { x: -420, y: 12, rotate: -13, scale: 0.96, zIndex: 10 },
  { x: -140, y: 4,  rotate: -5,  scale: 0.98, zIndex: 20 },
  { x:  140, y: -4, rotate:  3,  scale: 1.00, zIndex: 30 },
  { x:  420, y: -12, rotate: 11, scale: 1.02, zIndex: 40 },
]

export const StackingProcessCards: React.FC = () => {
  const containerRef = useRef<HTMLDivElement>(null)
  const cardsRef = useRef<(HTMLDivElement | null)[]>([])
  const [activeIdx, setActiveIdx] = useState<number>(0)

  useEffect(() => {
    if (typeof window === 'undefined') return

    const ctx = gsap.context(() => {
      const cards = cardsRef.current.filter(Boolean) as HTMLElement[]
      if (cards.length === 0) return

      // Responsive spacing multiplier
      const isLarge = window.innerWidth >= 1440
      const isMedium = window.innerWidth >= 1024
      const scaleFactor = isLarge ? 1 : isMedium ? 0.85 : 0.65

      const fanPositions = FAN.map((f) => ({
        ...f,
        x: f.x * scaleFactor,
        y: f.y * scaleFactor,
      }))

      // Initial state: Card 0 starts in place, Cards 1-3 start below
      gsap.set(cards[0], {
        x: fanPositions[0].x,
        y: fanPositions[0].y,
        rotate: fanPositions[0].rotate,
        scale: fanPositions[0].scale,
        zIndex: fanPositions[0].zIndex,
        opacity: 1,
      })

      for (let i = 1; i < cards.length; i++) {
        gsap.set(cards[i], {
          x: 0,
          y: '105vh',
          rotate: 0,
          scale: 0.95,
          zIndex: fanPositions[i].zIndex,
          opacity: 1,
        })
      }

      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: containerRef.current,
          start: 'top top',
          end: '+=2200',
          pin: true,
          scrub: 0.7,
          anticipatePin: 1,
          refreshPriority: -1,
          snap: {
            snapTo: 1 / (STEPS.length - 1),
            duration: { min: 0.2, max: 0.4 },
            delay: 0.05,
            ease: 'power2.inOut',
          },
          onUpdate: (self) => {
            const idx = Math.min(
              STEPS.length - 1,
              Math.floor(self.progress * STEPS.length)
            )
            setActiveIdx(idx)
          },
        },
      })

      // Cards 1, 2, 3 slide in one by one into their fan slots
      for (let i = 1; i < cards.length; i++) {
        tl.to(cards[i], {
          x: fanPositions[i].x,
          y: fanPositions[i].y,
          rotate: fanPositions[i].rotate,
          scale: fanPositions[i].scale,
          duration: 1,
          ease: 'power2.out',
        })
      }

      // Small pause at the end to admire the full fan
      tl.to({}, { duration: 0.4 })
    }, containerRef)

    return () => ctx.revert()
  }, [])

  return (
    <section
      ref={containerRef}
      className="relative w-full bg-[#faebe4] overflow-hidden flex flex-col justify-between pt-16 sm:pt-20 pb-4"
      style={{ height: '100vh', minHeight: '660px' }}
    >
      {/* ── Background Subtle Watermark Text ── */}
      <div className="absolute inset-0 flex items-center justify-center pointer-events-none select-none z-0">
        <span
          className="font-bayon text-[#ebd1c8]/40 leading-none text-center uppercase tracking-tight"
          style={{ fontSize: 'clamp(70px, 13vw, 190px)' }}
        >
          HOW IT WORKS?
        </span>
      </div>

      {/* ── Center Stage: Giant Portrait Fanned Cards ── */}
      <div
        className="relative z-20 flex-1 w-full flex items-center justify-center"
        style={{ perspective: '1200px' }}
      >
        {STEPS.map((step, i) => (
          <div
            key={step.num}
            ref={(el) => {
              cardsRef.current[i] = el
            }}
            className="absolute transition-shadow duration-300 hover:z-50 cursor-pointer"
            style={{
              width: 'clamp(310px, 23vw, 390px)',
              height: 'clamp(500px, 68vh, 630px)',
              transformOrigin: 'bottom center',
            }}
          >
            {/* Outer card container with badge positioning support */}
            <div className="relative w-full h-full">
              {/* ── Badge / Tab Decorators matching dontboardme exactly ── */}
              {step.badgePos === 'tab' && (
                <>
                  {/* White Top Tab: PROCESS */}
                  <div className="absolute -top-6 left-8 bg-white rounded-t-xl px-4 py-1 border-t border-x border-white/80 shadow-[0_-4px_10px_rgba(0,0,0,0.03)] z-30">
                    <span className="font-bayon text-[11px] tracking-widest text-[#382417]">
                      QUY TRÌNH
                    </span>
                  </div>
                  {/* Terracotta Coral Badge 1 at bottom-left */}
                  <div className="absolute bottom-16 -left-3.5 w-8 h-8 rounded-full bg-[#cf5b47] text-white font-bayon text-sm flex items-center justify-center shadow-lg border-2 border-white z-30">
                    1
                  </div>
                </>
              )}

              {step.badgePos === 'top-left' && (
                <div className="absolute -top-3.5 -left-3.5 w-8 h-8 rounded-full bg-[#cf5b47] text-white font-bayon text-sm flex items-center justify-center shadow-lg border-2 border-white z-30">
                  2
                </div>
              )}

              {step.badgePos === 'mid-left' && (
                <div className="absolute top-1/2 -translate-y-1/2 -left-3.5 w-8 h-8 rounded-full bg-[#cf5b47] text-white font-bayon text-sm flex items-center justify-center shadow-lg border-2 border-white z-30">
                  3
                </div>
              )}

              {step.badgePos === 'top-right' && (
                <div className="absolute -top-4 -right-4 w-10 h-10 rounded-full bg-[#ffe600] text-[#222] font-bayon text-base flex items-center justify-center shadow-xl border-2 border-white z-30 animate-pulse">
                  4
                </div>
              )}

              {/* ── Main Card Body (Pure White + Rounded 28px) ── */}
              <div className="w-full h-full bg-white rounded-[28px] border-2 border-white/90 shadow-[0_28px_60px_rgba(60,25,25,0.12),0_8px_20px_rgba(0,0,0,0.04)] overflow-hidden flex flex-col justify-between">
                {/* ── Top Half: Centered Brown Headlines + Big Number + All-Caps Desc ── */}
                <div className="flex-1 flex flex-col items-center justify-center px-6 pt-8 pb-4 text-center">
                  {/* Title (2 lines, huge, condensed dark brown #382417) */}
                  <h3 className="font-bayon text-[#382417] text-[28px] sm:text-[34px] leading-[0.96] uppercase tracking-tight max-w-[320px]">
                    {step.titleLine1}
                    <br />
                    {step.titleLine2}
                  </h3>

                  {/* Big Number (Dark Brown #382417) */}
                  <div className="font-bayon text-[#382417] text-[54px] sm:text-[66px] leading-none my-3 select-none tracking-tight">
                    {step.num}
                  </div>

                  {/* Description (Uppercase, centered, condensed brown) */}
                  <p className="font-sans text-[11px] sm:text-[12px] font-bold text-[#5a3a29] leading-[1.38] uppercase tracking-wide max-w-[280px]">
                    {step.desc}
                  </p>
                </div>

                {/* ── Bottom Half: Soft Lavender Container with Cutout Illustration ── */}
                <div className="w-full h-[40%] bg-[#e8def8] flex items-center justify-center p-3 shrink-0 overflow-hidden">
                  <img
                    src={step.img}
                    alt={`${step.titleLine1} ${step.titleLine2}`}
                    className="h-full max-h-[190px] w-auto object-contain drop-shadow-sm select-none pointer-events-none"
                    loading="lazy"
                  />
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* ── Bottom Bar / Progress indicator & CTA ── */}
      <div className="relative z-30 w-full px-6 sm:px-12 pb-6 flex items-center justify-between">
        <div className="flex items-center gap-2">
          {STEPS.map((_, i) => (
            <span
              key={i}
              className={`transition-all duration-300 rounded-full ${
                i <= activeIdx
                  ? 'w-6 h-2 bg-[#a43324]'
                  : 'w-2 h-2 bg-[#a43324]/30'
              }`}
            />
          ))}
          <span className="font-bayon text-[#a43324]/70 text-sm tracking-widest uppercase ml-3">
            0{activeIdx + 1} / 04
          </span>
        </div>

        {/* Bottom Right: Red capsule BOOK NOW button */}
        <Link
          to="/booking"
          className="inline-flex items-center gap-2 rounded-full bg-[#a43324] hover:bg-[#89271b] px-6 py-2.5 font-bayon text-base text-white uppercase tracking-wider shadow-[0_6px_20px_rgba(164,51,36,0.35)] transition-transform hover:scale-105 active:scale-95"
        >
          <span>ĐẶT LỊCH NGAY</span>
          <span className="w-2 h-2 rounded-full bg-white"></span>
        </Link>
      </div>

      {/* Seamless transition into NoCagesPhilosophy (#fdf6ec) */}
      <div className="w-full overflow-hidden leading-none absolute bottom-0 left-0 right-0 z-20 pointer-events-none">
        <svg
          viewBox="0 0 1440 32"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-full h-5 sm:h-8 block"
          preserveAspectRatio="none"
        >
          <path
            d="M0,0 C480,32 960,32 1440,0 L1440,32 L0,32 Z"
            fill="#fdf6ec"
          />
        </svg>
      </div>
    </section>
  )
}



