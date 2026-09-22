import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Bell,
  CalendarClock,
  ChevronDown,
  CreditCard,
  Edit3,
  Gift,
  Heart,
  LogIn,
  LogOut,
  Package,
  PawPrint,
  Search,
  Settings,
  ShoppingCart,
  Sparkles,
  Star,
  User,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useAuthStore } from '../../stores/auth.store'
import { isHeaderCollapsed, buildShopMegaMenu } from './header.utils'
import { NotificationBell } from './NotificationBell'

/* ================================================================
   Static data.
   ================================================================ */

const SHOP_CATEGORIES = [
  { label: 'Thức ăn chó', url: '/shop?petType=dog', category: 'Chó', query: { petType: 'dog' } },
  { label: 'Đồ chơi chó', url: '/shop?petType=dog', category: 'Chó', query: { petType: 'dog' } },
  { label: 'Thức ăn mèo', url: '/shop?petType=cat', category: 'Mèo', query: { petType: 'cat' } },
  { label: 'Đồ chơi mèo', url: '/shop?petType=cat', category: 'Mèo', query: { petType: 'cat' } },
]
const SHOP_MEGA_MENU = buildShopMegaMenu(SHOP_CATEGORIES)

const TOP_LINKS = [
  { label: 'Giới thiệu', page: 'about' },
  { label: 'Tin tức', page: 'news' },
]

const MAIN_NAV = [
  { label: 'Trang chủ', page: 'home' },
  { label: 'Cửa hàng', page: 'listing', hasDropdown: true },
  { label: 'Dành cho thú cưng', page: 'recommend' },
  { label: 'Đặt lịch khám', page: 'booking' },
  { label: 'Khách sạn thú cưng', page: 'hotel' },
]

interface SiteHeaderProps {
  currentPage: string
  cartCount?: number
  onNav: (page: string) => void
}

interface DropdownItem {
  label?: string
  page?: string
  href?: string
  badge?: string
  icon?: LucideIcon
  divider?: true
}

const USER_DROPDOWN_AUTHED: DropdownItem[] = [
  { label: 'Tài khoản của tôi', page: 'account',  icon: User },
  { label: 'Hồ sơ thú cưng',  page: 'pets',     icon: PawPrint },
  { label: 'Lịch hẹn',         page: 'appointments', icon: CalendarClock },
  { label: 'Đơn hàng',         page: 'orders',   icon: Package },
  { label: 'Gói dịch vụ',     page: 'packages', icon: Gift },
  { label: 'Voucher',          page: 'vouchers', icon: Star },
  { label: 'Hội viên',         page: 'membership', icon: Sparkles },
  { label: 'Người chăm sóc',   page: 'caregivers', icon: Heart },
  { label: 'Đánh giá dịch vụ', page: 'review',  icon: Edit3 },
  { label: 'Yêu thích',         page: 'favorites', icon: Heart },
  { label: 'Thông báo',         page: 'notifications', icon: Bell },
  { label: 'Thanh toán',        page: 'payment',   icon: CreditCard },
  { divider: true },
  { label: 'Cài đặt',  page: 'settings', icon: Settings },
  { label: 'Trợ giúp',  page: 'help',     icon: Edit3 },
  { divider: true },
  { label: 'Đăng xuất', page: '__logout__', icon: LogOut },
]

/* ================================================================
   Main.
   ================================================================ */

export function SiteHeader({ cartCount = 0, onNav }: SiteHeaderProps) {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const logout = useAuthStore((s) => s.logout)
  const [isScrolled, setIsScrolled] = useState(false)
  const [userMenuOpen, setUserMenuOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const userMenuRef = useRef<HTMLDivElement>(null)

  // Scroll
  useEffect(() => {
    const handleScroll = () => setIsScrolled(isHeaderCollapsed(window.scrollY, 80))
    window.addEventListener('scroll', handleScroll, { passive: true })
    handleScroll()
    return () => window.removeEventListener('scroll', handleScroll)
  }, [])

  // Outside-click → close user menu.
  useEffect(() => {
    if (!userMenuOpen) return
    const handler = (e: MouseEvent) => {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target as Node)) {
        setUserMenuOpen(false)
      }
    }
    const handleScroll = () => setUserMenuOpen(false)
    document.addEventListener('mousedown', handler)
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => {
      document.removeEventListener('mousedown', handler)
      window.removeEventListener('scroll', handleScroll)
    }
  }, [userMenuOpen])

  const handleUserItem = async (item: DropdownItem) => {
    setUserMenuOpen(false)
    if (item.page === '__logout__') {
      await logout()
      onNav('home')
      return
    }
    if (item.page) onNav(item.page)
  }

  const triggerUserMenu = () => setUserMenuOpen((o) => !o)

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    const q = searchQuery.trim()
    if (q) {
      navigate(`/shop?q=${encodeURIComponent(q)}`)
    } else {
      navigate('/shop')
    }
  }

  return (
    <header
      className={`fixed top-0 left-0 right-0 z-50 flex flex-col border-b border-(--color-border-default) transition-all duration-300 bg-[#FDF6EC] ${
        isScrolled ? 'shadow-md' : ''
      }`}
    >
      {/* ── Hàng 1: Logo + Search + User actions ── */}
      <div
        className={`flex items-center justify-between px-8 lg:px-12 transition-all duration-500 relative z-30 ${
          isScrolled ? 'h-0 opacity-0 overflow-hidden pointer-events-none' : 'h-[100px] opacity-100 overflow-visible'
        }`}
      >
        {/* Top links */}
        <div className="flex items-center gap-6 flex-1 min-w-[150px]">
          {TOP_LINKS.map((l) => (
            <a
              key={l.page}
              href="#"
              onClick={(e) => { e.preventDefault(); onNav(l.page) }}
              className="text-[13px] font-bold text-[#3B2A1E] hover:text-accent transition-colors whitespace-nowrap"
            >
              {l.label}
            </a>
          ))}
        </div>

        {/* Logo */}
        <div
          className="flex justify-center cursor-pointer shrink-0 items-center px-4"
          onClick={() => onNav('home')}
        >
          <div className="font-friendly font-black text-[32px] text-[#202945] tracking-tight flex items-center gap-2">
            <img src="/imgs/DogSticker.svg" alt="PetCare Logo" className="h-9 w-auto object-contain" />
            PetCare
          </div>
        </div>

        {/* Right: Search + Auth + User + Bell + Cart */}
        <div className="flex items-center justify-end gap-4 flex-1 min-w-0">
          {/* Search */}
          <form onSubmit={handleSearch} className="relative flex items-center w-[220px] lg:w-[270px] xl:w-[320px] shrink-0">
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Tìm kiếm sản phẩm..."
              className="w-full h-[42px] pl-4 pr-12 rounded-full border border-gray-200 bg-white text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-accent shadow-xs"
            />
            <button
              type="submit"
              className="absolute right-1.5 flex items-center justify-center w-8 h-8 rounded-full bg-[#89271b] text-white hover:bg-[#701f15] transition-colors cursor-pointer"
            >
              <Search size={14} strokeWidth={3} />
            </button>
          </form>

          {!isAuthenticated ? (
            <div className="flex items-center gap-2.5 shrink-0">
              <button
                onClick={() => onNav('login')}
                className="text-[14px] font-bold text-[#3B2A1E] hover:text-accent transition-colors whitespace-nowrap cursor-pointer"
              >
                Đăng nhập
              </button>
              <button
                onClick={() => onNav('register')}
                className="rounded-full bg-accent px-4 py-2 text-[13.5px] font-extrabold text-white transition-[background-color,transform] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:bg-accent-hover active:scale-[0.985] whitespace-nowrap cursor-pointer"
              >
                Đăng ký
              </button>
            </div>
          ) : (
            /* User menu */
            <div ref={userMenuRef} className="relative shrink-0">
              <button
                onClick={triggerUserMenu}
                className="flex items-center gap-2 cursor-pointer text-[#3B2A1E] hover:text-accent transition-colors py-2"
                aria-expanded={userMenuOpen}
                aria-haspopup="true"
              >
                <User size={20} strokeWidth={2.5} />
                <span className="text-[13px] font-bold max-w-[90px] truncate">
                  {user?.name || 'Khách'}
                </span>
                <ChevronDown
                  size={14}
                  strokeWidth={3}
                  className={`transition-transform duration-200 ${userMenuOpen ? 'rotate-180' : ''}`}
                />
              </button>

              {userMenuOpen && (
                <div className="absolute right-0 top-[calc(100%+8px)] w-[240px] overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.35)] z-[70]">
                  <div className="border-b border-(--color-border-default) px-4 py-3">
                    <div className="text-[13px] font-bold text-(--color-text-primary) truncate">
                      {user?.name || 'Khách'}
                    </div>
                    <div className="text-[11.5px] text-(--color-text-secondary) truncate">
                      {user?.email}
                    </div>
                  </div>
                  <div className="py-1.5">
                    {USER_DROPDOWN_AUTHED.map((item, i) =>
                      item.divider ? (
                        <div key={`div-${i}`} className="my-1 border-t border-(--color-border-default)" />
                      ) : (
                        <button
                          key={item.label}
                          onClick={() => handleUserItem(item)}
                          className={`flex w-full items-center gap-3 px-4 py-2.5 text-left text-[13px] font-medium transition-colors cursor-pointer ${
                            item.page === '__logout__'
                              ? 'text-red-600 hover:bg-red-50'
                              : 'text-(--color-text-primary) hover:bg-(--color-surface-2)'
                          }`}
                        >
                          {item.icon && <item.icon size={16} className={item.page === '__logout__' ? '' : 'text-accent shrink-0'} />}
                          {item.label}
                          {item.badge && (
                            <span className="ml-auto rounded-full bg-accent px-1.5 py-0.5 text-[10px] font-bold text-white">
                              {item.badge}
                            </span>
                          )}
                        </button>
                      ),
                    )}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Bell + Cart */}
          <div className="shrink-0">
            <NotificationBell />
          </div>
          <div
            className="relative cursor-pointer text-[#3B2A1E] hover:text-accent transition-colors shrink-0"
            onClick={() => onNav('cart')}
          >
            <ShoppingCart size={22} strokeWidth={2.5} />
            {cartCount > 0 && (
              <span className="absolute -top-2 -right-2 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white z-10">
                {cartCount}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* ── Hàng 2: Logo mini + Main Nav + Right (scroll) ── */}
      <div className={`flex items-center px-8 lg:px-12 transition-all duration-300 relative z-20 ${isScrolled ? 'h-[60px]' : 'h-[50px]'}`}>
        {/* Mini logo (visible on scroll) */}
        <div
          className={`shrink-0 cursor-pointer font-friendly font-black text-xl text-[#202945] tracking-tight flex items-center gap-1.5 transition-all duration-300 ${
            isScrolled ? 'w-[160px] lg:w-[180px] opacity-100 overflow-visible' : 'w-0 opacity-0 overflow-hidden pointer-events-none'
          }`}
          onClick={() => onNav('home')}
        >
          <img src="/imgs/DogSticker.svg" alt="PetCare Logo" className="h-6 w-auto object-contain" />
          PetCare
        </div>

        {/* Main nav */}
        <nav className="flex-1 flex justify-center items-center h-full gap-5 lg:gap-7 min-w-0">
          {MAIN_NAV.map((l) => (
            <div key={l.label} className="group relative flex items-center h-full whitespace-nowrap">
              <a
                href="#"
                onClick={(e) => { e.preventDefault(); onNav(l.page) }}
                className="flex items-center gap-1.5 text-[14px] font-bold text-accent hover:text-[#701f15] transition-colors tracking-wide h-full whitespace-nowrap"
              >
                {l.label}
                {l.hasDropdown && (
                  <ChevronDown size={14} strokeWidth={3} className="group-hover:rotate-180 transition-transform duration-200" />
                )}
              </a>

              {l.hasDropdown && (
                <div className="absolute top-[100%] left-1/2 -translate-x-1/2 hidden group-hover:flex bg-white shadow-xl p-6 gap-10 rounded-b-lg border-t-2 border-accent w-max min-w-[300px]">
                  {SHOP_MEGA_MENU.map((col) => (
                    <div key={col.title}>
                      <div className="font-bold text-[#3B2A1E] mb-3 border-b border-gray-100 pb-2">{col.title}</div>
                      <div className="flex flex-col gap-3">
                        {col.items.map((item) => (
                          <a
                            key={item.label}
                            href={item.url}
                            className="text-[13px] font-medium text-gray-500 hover:text-accent transition-colors"
                          >
                            {item.label}
                          </a>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </nav>

        {/* Right: Search mini + User + Bell + Cart */}
        <div
          className={`flex items-center justify-end gap-3.5 sm:gap-4 transition-all duration-300 shrink-0 ${
            isScrolled ? 'w-auto min-w-[280px] sm:min-w-[340px] opacity-100 pr-2 overflow-visible' : 'w-0 opacity-0 pr-0 overflow-hidden pointer-events-none'
          }`}
        >
          <form onSubmit={handleSearch} className="relative flex items-center w-[170px] sm:w-[210px] lg:w-[230px] shrink-0">
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Tìm kiếm..."
              className="w-full h-[36px] pl-3.5 pr-9 rounded-full border border-gray-200 bg-white text-[13px] placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-accent transition-all"
            />
            <button
              type="submit"
              className="absolute right-1 flex items-center justify-center w-7 h-7 rounded-full bg-[#89271b] text-white hover:bg-[#701f15] transition-colors cursor-pointer"
            >
              <Search size={12} strokeWidth={3} />
            </button>
          </form>

          <button
            className="text-[#3B2A1E] hover:text-accent shrink-0 cursor-pointer"
            title={isAuthenticated ? 'Tài khoản của tôi' : 'Đăng nhập'}
            onClick={() => onNav(isAuthenticated ? 'account' : 'login')}
          >
            {isAuthenticated ? (
              <User size={20} strokeWidth={2.5} />
            ) : (
              <LogIn size={20} strokeWidth={2.5} />
            )}
          </button>

          <div className="shrink-0">
            <NotificationBell />
          </div>

          <div
            className="relative cursor-pointer text-[#3B2A1E] hover:text-accent shrink-0"
            onClick={() => onNav('cart')}
          >
            <ShoppingCart size={20} strokeWidth={2.5} />
            {cartCount > 0 && (
              <span className="absolute -top-2 -right-2 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white z-10">
                {cartCount}
              </span>
            )}
          </div>
        </div>
      </div>
    </header>
  )
}
