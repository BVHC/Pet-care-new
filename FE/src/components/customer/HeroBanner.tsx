import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { PawPrint } from 'lucide-react'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { RotatingBadge } from './RotatingBadge'

gsap.registerPlugin(ScrollTrigger)

// Asset URLs
const ASSETS = {
  logo: 'https://polo-pecan-73837341.figma.site/_assets/v11/0ae29d6d9628bede667f90d57bebe81b8f1ec2bf.svg',
  avatar: 'https://polo-pecan-73837341.figma.site/_assets/v11/e62173d41f91350a59628e8a9a55ae078a886fb9.png?w=128',
  catHouse: 'https://polo-pecan-73837341.figma.site/_assets/v11/3e5158dad63d392ade022e81890edc9f54d750bc.png',
  videoCard: 'https://polo-pecan-73837341.figma.site/_assets/v11/76be6ec3a93a703b15e9cc01e764a4e3f9d7d2c0.png',
  bottomLeft: 'https://polo-pecan-73837341.figma.site/_assets/v11/8d44b25186ef45a5789c74668fb781cea4e1ff49.png',
  bottomCenter: 'https://polo-pecan-73837341.figma.site/_assets/v11/96745c4e72ad5c5208e53a885df797fd82cd854a.png?h=1024',
  bottomRight: 'https://polo-pecan-73837341.figma.site/_assets/v11/81bd2e7a66b58f3d8f3ad78fd1ebf01af8dfdee1.png',
}

// Colors
const COLORS = {
  bgCream: '#fdf6ec',
  primary: '#a43324',
  primaryHover: '#89271b',
  textDark: '#3B2A1E',
  textSub: '#70584b',
}

// Icons
const StarIcon = ({ size = 20, filled = false, className = '' }: { size?: number; filled?: boolean; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? "currentColor" : "none"} stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>
  </svg>
)

const ArrowUpRightIcon = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M7 7h10v10"/><path d="M7 17 17 7"/>
  </svg>
)

const PlayIcon = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
    <polygon points="6 3 20 12 6 21 6 3"/>
  </svg>
)

const ArrowRightIcon = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>
  </svg>
)

const PlusIcon = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
    <path d="M5 12h14"/><path d="M12 5v14"/>
  </svg>
)

// Floating Tennis Ball Component (Don't Board Me signature aesthetic)
const TennisBall = ({
  size = 56,
  className = '',
  style = {},
}: {
  size?: number
  className?: string
  style?: React.CSSProperties
}) => (
  <div
    className={`select-none pointer-events-none will-change-transform ${className}`}
    style={{ width: size, height: size, ...style }}
  >
    <svg
      viewBox="0 0 100 100"
      width="100%"
      height="100%"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className="w-full h-full drop-shadow-[0_12px_24px_rgba(56,36,23,0.22)]"
    >
      <circle cx="50" cy="50" r="47" fill="url(#heroTennisGrad)" stroke="#c6df11" strokeWidth="2.5" />
      <path
        d="M20 18 C38 32 38 68 20 82"
        stroke="#ffffff"
        strokeWidth="5"
        strokeLinecap="round"
        fill="none"
        opacity="0.92"
      />
      <path
        d="M80 18 C62 32 62 68 80 82"
        stroke="#ffffff"
        strokeWidth="5"
        strokeLinecap="round"
        fill="none"
        opacity="0.92"
      />
      <defs>
        <radialGradient id="heroTennisGrad" cx="35%" cy="30%" r="68%">
          <stop offset="0%" stopColor="#f5ff60" />
          <stop offset="52%" stopColor="#d2ee12" />
          <stop offset="100%" stopColor="#9cb805" />
        </radialGradient>
      </defs>
    </svg>
  </div>
)

// Product Card Component
const ProductCard = () => (
  <div className="hero-card-left absolute left-3 sm:left-12 top-[80px] sm:top-[50px] w-[clamp(130px,15vw,220px)] sm:w-[clamp(150px,15vw,250px)] will-change-transform">
    <div className="relative rounded-2xl overflow-hidden shadow-lg border border-amber-100/60 bg-white">
      <img
        src={ASSETS.catHouse}
        alt="Nhà cây cho mèo ấm áp"
        className="w-full aspect-[260/257] object-cover"
      />
      <Link
        to="/shop"
        className="absolute bottom-3 right-3 w-10 h-10 rounded-full bg-[#a43324] hover:bg-[#89271b] flex items-center justify-center text-white transition-all shadow-md hover:scale-105"
        aria-label="Xem sản phẩm"
      >
        <ArrowUpRightIcon size={18} />
      </Link>
    </div>
    <div className="mt-2 sm:mt-3 px-1">
      <p className="text-[#3B2A1E] text-xs sm:text-sm font-bold truncate">Nhà Cây Cho Mèo Ấm Áp</p>
      <p className="text-[#a43324] text-sm sm:text-base font-extrabold">499.000đ</p>
    </div>
  </div>
)

// Video Card Component
const VideoCard = () => (
  <div className="hero-card-right absolute right-3 sm:right-12 top-[80px] sm:top-[50px] w-[clamp(100px,11vw,140px)] sm:w-[clamp(120px,12vw,175px)] will-change-transform">
    <div className="relative rounded-2xl overflow-hidden shadow-lg border border-amber-100/60 bg-white">
      <img
        src={ASSETS.videoCard}
        alt="Video trải nghiệm thực tế"
        className="w-full aspect-[177/287] object-cover"
      />
      <button 
        className="absolute bottom-6 left-1/2 -translate-x-1/2 w-11 h-11 rounded-full bg-[#a43324] hover:bg-[#89271b] flex items-center justify-center text-white transition-all shadow-lg hover:scale-110"
        aria-label="Xem video review"
      >
        <PlayIcon size={16} />
      </button>
    </div>
    <p className="mt-2 sm:mt-3 text-[#70584b] text-[10px] sm:text-xs leading-tight px-1 text-center font-medium">
      Video review thực tế trên TikTok &amp; YouTube
    </p>
  </div>
)

// Bottom Images Component
const BottomImages = () => (
  <div className="hero-bottom-wrap absolute bottom-0 left-0 right-0 z-10 flex items-end overflow-hidden pointer-events-none [&_a]:pointer-events-auto [&_button]:pointer-events-auto will-change-transform" style={{ maxHeight: 'min(440px, 52vh)' }}>
    {/* Left Image */}
    <div
      className="hero-pet-1 flex-1 will-change-transform"
      style={{ maxHeight: 'min(380px, 46vw)' }}
    >
      <img
        src={ASSETS.bottomLeft}
        alt="Thú cưng vui vẻ"
        className="w-full h-auto block object-contain object-bottom"
      />
      <div className="absolute bottom-4 sm:bottom-8 left-4 sm:left-8 pointer-events-auto">
        <div className="flex items-center gap-2 bg-black/35 backdrop-blur-sm px-3 py-1.5 rounded-full">
          <span className="text-white font-bold text-base sm:text-lg drop-shadow">98K+</span>
          <div className="flex -space-x-2">
            <img src={ASSETS.avatar} alt="" className="w-7 h-7 rounded-full border-2 border-white object-cover" />
            <div className="w-7 h-7 rounded-full bg-[#a43324] border-2 border-white flex items-center justify-center">
              <PlusIcon size={12} className="text-white" />
            </div>
          </div>
          <span className="text-white/90 text-[11px] font-semibold hidden xl:inline">Khách hàng tin chọn</span>
        </div>
      </div>
    </div>

    {/* Center Image */}
    <div
      className="hero-pet-2 flex-[1.265] will-change-transform"
      style={{ maxHeight: 'min(440px, 52vw)' }}
    >
      <img
        src={ASSETS.bottomCenter}
        alt="Sản phẩm chăm sóc thú cưng"
        className="w-full h-auto block object-contain object-bottom"
      />
      <div className="absolute bottom-4 sm:bottom-8 left-1/2 -translate-x-1/2 text-center pointer-events-auto w-full px-4">
        <h3 className="text-white text-base sm:text-2xl font-bold mb-2.5 drop-shadow-[0_2px_4px_rgba(0,0,0,0.6)] font-[var(--font-friendly)]">
          Sản Phẩm Tốt Nhất Cho Bé Cưng
        </h3>
        <Link
          to="/shop"
          className="inline-flex items-center gap-2 bg-[#a43324] hover:bg-[#89271b] text-white text-xs sm:text-sm font-bold px-5 sm:px-7 py-2.5 sm:py-3 rounded-full transition-all shadow-lg hover:shadow-xl hover:scale-105"
        >
          Khám Phá Cửa Hàng
          <ArrowRightIcon size={16} />
        </Link>
      </div>
    </div>

    {/* Right Image */}
    <div
      className="hero-pet-3 flex-1 will-change-transform"
      style={{ maxHeight: 'min(380px, 46vw)' }}
    >
      <img
        src={ASSETS.bottomRight}
        alt="Chăm sóc thú cưng chất lượng cao"
        className="w-full h-auto block object-contain object-bottom"
      />
      <div className="absolute bottom-4 sm:bottom-8 right-4 sm:right-8 pointer-events-auto">
        <div className="flex items-center gap-1.5 bg-black/35 backdrop-blur-sm px-3 py-1.5 rounded-full">
          <StarIcon size={18} filled className="text-amber-400 drop-shadow" />
          <span className="text-white font-bold text-base sm:text-lg drop-shadow">4.6/5</span>
          <span className="text-white/90 text-[11px] font-semibold hidden xl:inline">Đánh giá dịch vụ</span>
        </div>
      </div>
    </div>
  </div>
)

// Desktop Hero Content
const DesktopHero = () => (
  <div className="hidden lg:flex flex-col h-full relative min-h-[680px]">
    {/* Center Text Layer */}
    <div className="hero-center-content relative z-20 pt-10 text-center px-4 max-w-3xl mx-auto will-change-transform">
      <div className="hero-badge-elem inline-flex items-center gap-2 rounded-full bg-[#faebe4] border border-[#a43324]/20 px-4 py-1.5 text-[13px] font-bold text-[#a43324] mb-4 shadow-xs">
        <PawPrint size={14} strokeWidth={2.5} /> Hệ Thống Chăm Sóc Thú Cưng Toàn Diện
      </div>
      <h1 className="font-[var(--font-friendly)] text-[clamp(44px,5.2vw,72px)] leading-[1.08] tracking-tight font-black">
        <span className="hero-title-line-1 inline-block text-[#3B2A1E]">
          Yêu Thương Trọn Vẹn
        </span>
        <br />
        <span className="hero-title-line-2 inline-block text-[#a43324]">
          Dành Cho Thú Cưng
        </span>
      </h1>
      <p className="hero-sub-desc mt-3 text-[15px] font-medium text-[#70584b] max-w-lg mx-auto leading-relaxed">
        Dịch vụ khám y tế, spa, khách sạn lưu trú và phụ kiện chính hãng chất lượng cao cho thú cưng của bạn.
      </p>
    </div>

    {/* Floating Left Card */}
    <div className="z-30">
      <ProductCard />
    </div>

    {/* Floating Right Card */}
    <div className="z-30">
      <VideoCard />
    </div>

    {/* Don't Board Me Style 360 Rotating Badge */}
    <div className="hidden lg:block absolute bottom-12 right-10 z-30">
      <RotatingBadge size={126} />
    </div>

    {/* Tennis Balls Hill Horizon Background (Don't Board Me signature curved sphere hill) */}
    <div className="hero-tennis-hill absolute bottom-0 left-0 right-0 z-[5] pointer-events-none overflow-hidden flex justify-center items-end opacity-95">
      <img
        src="/imgs/tennis-balls-hill.svg"
        alt=""
        className="w-full min-w-[1280px] max-w-[1800px] h-auto object-cover object-bottom select-none translate-y-6 sm:translate-y-12"
      />
    </div>

    {/* Floating Tennis Balls with Parallax Depth */}
    <div className="hero-floating-balls pointer-events-none">
      <TennisBall
        size={54}
        className="hero-ball-1 absolute left-[6%] top-[24%] z-20"
      />
      <TennisBall
        size={68}
        className="hero-ball-2 absolute right-[8%] top-[34%] z-20"
      />
      <TennisBall
        size={42}
        className="hero-ball-3 absolute left-[24%] bottom-[24%] z-20"
      />
      <TennisBall
        size={34}
        className="hero-ball-4 absolute right-[22%] top-[14%] z-20"
      />
    </div>

    {/* Bottom Pet Images */}
    <BottomImages />
  </div>
)

// Tablet Hero Content
const TabletHero = () => (
  <div className="hidden md:flex lg:hidden flex-col h-full relative min-h-[580px]">
    <div className="hero-center-content relative z-20 pt-8 text-center px-4 max-w-xl mx-auto">
      <div className="hero-badge-elem inline-flex items-center gap-1.5 rounded-full bg-[#faebe4] px-3.5 py-1 text-[12px] font-bold text-[#a43324] mb-3">
        <PawPrint size={12} strokeWidth={2.5} /> Chăm sóc thú cưng toàn diện
      </div>
      <h1 className="font-[var(--font-friendly)] text-5xl leading-[1.08] tracking-tight font-black">
        <span className="hero-title-line-1 inline-block text-[#3B2A1E]">
          Yêu Thương Trọn Vẹn
        </span>
        <br />
        <span className="hero-title-line-2 inline-block text-[#a43324]">
          Dành Cho Thú Cưng
        </span>
      </h1>
    </div>

    <div className="z-30">
      <ProductCard />
    </div>
    <div className="z-30">
      <VideoCard />
    </div>

    {/* Tennis Balls Hill Horizon Background */}
    <div className="hero-tennis-hill absolute bottom-0 left-0 right-0 z-[5] pointer-events-none overflow-hidden flex justify-center items-end opacity-90">
      <img
        src="/imgs/tennis-balls-hill.svg"
        alt=""
        className="w-full min-w-[900px] h-auto object-cover object-bottom select-none translate-y-6"
      />
    </div>

    {/* Floating Tennis Balls */}
    <TennisBall
      size={46}
      className="hero-ball-1 absolute left-[4%] top-[26%] z-20"
    />
    <TennisBall
      size={54}
      className="hero-ball-2 absolute right-[5%] top-[36%] z-20"
    />

    {/* Bottom Images - Simplified */}
    <div className="hero-bottom-wrap absolute bottom-0 left-0 right-0 z-10 flex items-end">
      <img src={ASSETS.bottomLeft} alt="" className="w-1/3 h-auto block" />
      <img src={ASSETS.bottomCenter} alt="" className="w-[42%] h-auto block" />
      <img src={ASSETS.bottomRight} alt="" className="w-1/3 h-auto block" />
    </div>
  </div>
)

// Mobile Hero Content
const MobileHero = () => (
  <div className="flex md:hidden flex-col h-full relative overflow-hidden">
    {/* Background gradient */}
    <div className="absolute inset-0 bg-gradient-to-b from-[#fdf6ec] via-[#fdf6ec] to-[#f5e9d3] z-0" />

    <div className="relative z-10 flex flex-col h-full p-4">
      {/* Title Section */}
      <div className="hero-center-content text-center pt-8">
        <div className="hero-badge-elem inline-flex items-center gap-1.5 rounded-full bg-[#faebe4] px-3 py-1 text-[11px] font-bold text-[#a43324] mb-3">
          <PawPrint size={11} strokeWidth={2.5} /> Chăm sóc thú cưng toàn diện
        </div>
        <h1 className="font-[var(--font-friendly)] text-[#3B2A1E] text-[32px] leading-[1.15] font-black mb-2">
          Yêu Thương Trọn Vẹn
          <br />
          <span className="text-[#a43324]">Dành Cho Thú Cưng</span>
        </h1>
        <p className="hero-sub-desc text-[#70584b] text-xs mb-4">Sản phẩm &amp; dịch vụ cao cấp cho người bạn bốn chân</p>
        <Link
          to="/shop"
          className="inline-flex items-center gap-2 bg-[#a43324] hover:bg-[#89271b] text-white text-sm font-bold px-6 py-2.5 rounded-full transition-colors shadow-md"
        >
          Khám Phá Cửa Hàng
          <ArrowRightIcon size={16} />
        </Link>
      </div>

      {/* Cards Row */}
      <div className="flex gap-3 mt-6 flex-shrink-0">
        {/* Product Card */}
        <div className="hero-card-left flex-1">
          <div className="relative rounded-xl overflow-hidden shadow-md border border-amber-100 bg-white">
            <img
              src={ASSETS.catHouse}
              alt="Nhà cây cho mèo"
              className="w-full aspect-square object-cover"
            />
            <button className="absolute bottom-2 right-2 w-8 h-8 rounded-full bg-[#a43324] flex items-center justify-center text-white">
              <ArrowUpRightIcon size={14} />
            </button>
          </div>
          <p className="text-[#3B2A1E] text-xs mt-2 font-bold truncate">Nhà Cây Cho Mèo</p>
          <p className="text-[#a43324] text-xs font-black">499.000đ</p>
        </div>

        {/* Video card */}
        <div className="hero-card-right w-[44%]">
          <div className="relative rounded-xl overflow-hidden shadow-md border border-amber-100 bg-white">
            <img
              src={ASSETS.videoCard}
              alt="Video review thú cưng"
              className="w-full aspect-[3/4] object-cover"
            />
            <button className="absolute bottom-4 left-1/2 -translate-x-1/2 w-9 h-9 rounded-full bg-[#a43324] flex items-center justify-center text-white">
              <PlayIcon size={12} />
            </button>
          </div>
        </div>
      </div>

      {/* Stats Row */}
      <div className="flex items-center justify-center gap-6 py-4 mt-auto">
        <div className="flex items-center gap-2">
          <span className="text-[#3B2A1E] font-extrabold text-base">98K+</span>
          <div className="flex -space-x-2">
            <img src={ASSETS.avatar} alt="" className="w-6 h-6 rounded-full border-2 border-white object-cover" />
            <div className="w-6 h-6 rounded-full bg-[#a43324] border-2 border-white flex items-center justify-center">
              <PlusIcon size={10} className="text-white" />
            </div>
          </div>
        </div>

        <div className="w-px h-6 bg-amber-200" />

        <div className="flex items-center gap-1">
          <StarIcon size={16} filled className="text-amber-500" />
          <span className="text-[#3B2A1E] font-extrabold text-base">4.6/5</span>
        </div>
      </div>

      {/* Tennis Balls Hill Horizon Background */}
      <div className="hero-tennis-hill absolute bottom-0 left-0 right-0 z-0 pointer-events-none overflow-hidden opacity-75">
        <img
          src="/imgs/tennis-balls-hill.svg"
          alt=""
          className="w-[180%] max-w-none -translate-x-[20%] h-auto object-cover object-bottom translate-y-4"
        />
      </div>

      {/* Bottom Images */}
      <div className="hero-bottom-wrap flex items-end flex-shrink-0 relative z-10">
        <img src={ASSETS.bottomLeft} alt="" className="w-1/3 h-auto block" />
        <img src={ASSETS.bottomCenter} alt="" className="w-[42%] h-auto block" />
        <img src={ASSETS.bottomRight} alt="" className="w-1/3 h-auto block" />
      </div>
    </div>
  </div>
)

// Main HeroBanner Component
export function HeroBanner() {
  const bannerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (typeof window === 'undefined') return

    const ctx = gsap.context(() => {
      // 1. Entry Animation: Stagger and reveal on page load
      const entryTl = gsap.timeline({ defaults: { ease: 'power3.out' } })

      entryTl
        .fromTo(
          '.hero-badge-elem',
          { opacity: 0, y: -20, scale: 0.9 },
          { opacity: 1, y: 0, scale: 1, duration: 0.6, delay: 0.1 }
        )
        .fromTo(
          ['.hero-title-line-1', '.hero-title-line-2'],
          { opacity: 0, y: 45, rotateX: 20 },
          { opacity: 1, y: 0, rotateX: 0, duration: 0.8, stagger: 0.18 },
          '-=0.3'
        )
        .fromTo(
          '.hero-sub-desc',
          { opacity: 0, y: 20 },
          { opacity: 1, y: 0, duration: 0.6 },
          '-=0.4'
        )
        .fromTo(
          '.hero-card-left',
          { opacity: 0, x: -80, rotate: -8, scale: 0.9 },
          { opacity: 1, x: 0, rotate: 0, scale: 1, duration: 0.9, ease: 'back.out(1.3)' },
          '-=0.5'
        )
        .fromTo(
          '.hero-card-right',
          { opacity: 0, x: 80, rotate: 8, scale: 0.9 },
          { opacity: 1, x: 0, rotate: 0, scale: 1, duration: 0.9, ease: 'back.out(1.3)' },
          '-=0.7'
        )
        .fromTo(
          ['.hero-pet-1', '.hero-pet-2', '.hero-pet-3'],
          { opacity: 0, y: 100, scale: 0.94 },
          { opacity: 1, y: 0, scale: 1, duration: 0.95, stagger: 0.12, ease: 'back.out(1.4)' },
          '-=0.6'
        )
        .fromTo(
          '.hero-tennis-hill',
          { opacity: 0, y: 60 },
          { opacity: 0.95, y: 0, duration: 1.1, ease: 'power3.out' },
          '-=0.9'
        )
        .fromTo(
          ['.hero-ball-1', '.hero-ball-2', '.hero-ball-3', '.hero-ball-4'],
          { opacity: 0, scale: 0, y: 30 },
          { opacity: 1, scale: 1, y: 0, duration: 0.8, stagger: 0.1, ease: 'back.out(1.7)' },
          '-=0.7'
        )

      // 2. Idle floating loop for floating cards (sine float physics)
      gsap.to('.hero-card-left', {
        y: '-=12',
        duration: 2.8,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
        delay: 1.2,
      })
      gsap.to('.hero-card-right', {
        y: '+=12',
        duration: 3.2,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
        delay: 1.5,
      })

      // Floating sine animation for tennis balls
      gsap.to('.hero-ball-1', {
        y: '-=16',
        rotation: 15,
        duration: 3.2,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
      })
      gsap.to('.hero-ball-2', {
        y: '+=18',
        rotation: -20,
        duration: 2.9,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
        delay: 0.3,
      })
      gsap.to('.hero-ball-3', {
        y: '-=12',
        rotation: -10,
        duration: 2.6,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
        delay: 0.6,
      })
      gsap.to('.hero-ball-4', {
        y: '+=10',
        rotation: 25,
        duration: 3.5,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
        delay: 0.2,
      })

      // 3. ScrollTrigger Parallax effect on scroll
      const scrollTl = gsap.timeline({
        scrollTrigger: {
          trigger: bannerRef.current,
          start: 'top top',
          end: 'bottom top',
          scrub: 1.2,
          invalidateOnRefresh: true,
        },
      })

      scrollTl
        .to('.hero-center-content', { y: -50, opacity: 0.1, ease: 'none' }, 0)
        .to('.hero-card-left', { y: -90, x: -35, opacity: 0.35, ease: 'none' }, 0)
        .to('.hero-card-right', { y: -90, x: 35, opacity: 0.35, ease: 'none' }, 0)
        .to('.hero-ball-1', { y: -130, x: -20, ease: 'none' }, 0)
        .to('.hero-ball-2', { y: -150, x: 25, ease: 'none' }, 0)
        .to('.hero-ball-3', { y: -70, ease: 'none' }, 0)
        .to('.hero-ball-4', { y: -100, ease: 'none' }, 0)
        .to('.hero-tennis-hill', { y: 25, ease: 'none' }, 0)
        .to('.hero-bottom-wrap', { y: 35, scale: 0.98, ease: 'none' }, 0)
    }, bannerRef)

    return () => ctx.revert()
  }, [])

  return (
    <div ref={bannerRef} className="flex flex-col overflow-hidden relative" style={{ backgroundColor: COLORS.bgCream }}>
      <main className="flex flex-col relative">
        <DesktopHero />
        <TabletHero />
        <MobileHero />
      </main>

      {/* Seamless curved divider transitioning into OurServicesStage (#fbeee8) */}
      <div className="w-full overflow-hidden leading-none relative z-20 pointer-events-none -mb-[1px]">
        <svg
          viewBox="0 0 1440 48"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-full h-8 sm:h-12 block"
          preserveAspectRatio="none"
        >
          <path
            d="M0,0 C360,48 1080,48 1440,0 L1440,48 L0,48 Z"
            fill="#fbeee8"
          />
        </svg>
      </div>
    </div>
  )
}

export default HeroBanner
