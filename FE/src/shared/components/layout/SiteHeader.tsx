import { useEffect, useState } from 'react';
import { Search, ShoppingCart, User, ChevronDown, LogOut, Package, Edit3,  } from 'lucide-react';
import { useAuthStore } from '../../stores/auth.store';
import { isHeaderCollapsed, buildShopMegaMenu } from './header.utils';

const SHOP_CATEGORIES = [
  { label: 'Thức ăn chó', url: '/shop?petType=dog', category: 'Chó', query: { petType: 'dog' } },
  { label: 'Đồ chơi chó', url: '/shop?petType=dog', category: 'Chó', query: { petType: 'dog' } },
  { label: 'Thức ăn mèo', url: '/shop?petType=cat', category: 'Mèo', query: { petType: 'cat' } },
  { label: 'Đồ chơi mèo', url: '/shop?petType=cat', category: 'Mèo', query: { petType: 'cat' } },
];
const SHOP_MEGA_MENU = buildShopMegaMenu(SHOP_CATEGORIES);

const TOP_LINKS = [
  { label: 'Giới thiệu', page: 'about' },
  { label: 'Tin tức', page: 'news' },
];

const MAIN_NAV = [
  { label: 'Trang chủ', page: 'home' },
  { label: 'Cửa hàng', page: 'listing', hasDropdown: true },
  { label: 'Dành cho thú cưng', page: 'recommend' },
  { label: 'Đặt lịch khám', page: 'booking' },
  { label: 'Khách sạn thú cưng', page: 'hotel' },
  { label: 'Đánh giá dịch vụ', page: 'review' },
];

interface SiteHeaderProps {
  currentPage: string;
  cartCount?: number;
  onNav: (page: string) => void;
}

export function SiteHeader({ cartCount = 0, onNav }: SiteHeaderProps) {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const [isScrolled, setIsScrolled] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const handleScroll = () => {
      setIsScrolled(isHeaderCollapsed(window.scrollY, 80));
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    handleScroll();
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  return (
    <header className={`fixed top-0 left-0 right-0 z-50 flex flex-col border-b border-[var(--color-border-default)] transition-all duration-300 bg-[#FDF6EC] ${isScrolled ? 'shadow-md' : ''}`}>
      
      {/* Hàng 1 (Top Row) - Trượt ẩn khi cuộn */}
      <div className={`flex items-center justify-between px-8 lg:px-12 transition-all duration-500 overflow-hidden ${isScrolled ? 'h-0 opacity-0' : 'h-[100px] opacity-100'}`}>
        
        {/* Left: Top Links */}
        <div className="flex gap-6 w-1/3">
          {TOP_LINKS.map(l => (
            <a
              key={l.page}
              href="#"
              onClick={(e) => { e.preventDefault(); onNav(l.page); }}
              className="text-[13px] font-bold text-[#3B2A1E] hover:text-[#843122] transition-colors"
            >
              {l.label}
            </a>
          ))}
        </div>

        {/* Center: Big Logo */}
        <div 
          className="w-1/3 flex justify-center cursor-pointer flex-col items-center"
          onClick={() => onNav('home')}
        >
          <div className="font-[var(--font-friendly)] font-black text-[32px] text-[#202945] tracking-tight flex items-center gap-2">
            <img src="/imgs/DogSticker.svg" alt="PetCare Logo" className="h-9 w-auto object-contain" />
            PetCare
          </div>
        </div>

        {/* Right: Search, User, Cart */}
        <div className="w-1/3 flex items-center justify-end gap-5">
          {/* Search bar */}
          <div className="relative flex items-center w-[260px]">
            <input 
              type="text" 
              placeholder="Tìm kiếm sản phẩm..." 
              className="w-full h-[42px] pl-4 pr-12 rounded-full border-none bg-white text-sm focus:outline-none focus:ring-2 focus:ring-[#843122]"
            />
            <button className="absolute right-1.5 flex items-center justify-center w-8 h-8 rounded-full bg-[#6a2517] text-white hover:bg-[#4a1910] transition-colors">
              <Search size={14} strokeWidth={3} />
            </button>
          </div>

          {/* User Icon & Dropdown */}
          <div className="relative group cursor-pointer text-[#3B2A1E] hover:text-[#843122] transition-colors pb-4 -mb-4">
            <User size={22} strokeWidth={2.5} onClick={() => (user ? onNav('account') : onNav('login'))} />
            {user ? (
              <div className="absolute top-[100%] right-0 hidden group-hover:flex flex-col bg-white shadow-xl p-3 rounded-b-lg border-t-2 border-[#843122] w-48 z-50 pt-3 mt-4 gap-1">
                <div className="text-[13px] font-bold text-[#3B2A1E] mb-1 px-2 border-b pb-2">Chào, {user.name}</div>
                <button
                  onClick={() => onNav('account')}
                  className="flex items-center gap-2 text-[13px] text-gray-700 hover:bg-amber-50 rounded-md px-2 py-1.5 transition-colors w-full text-left font-medium"
                >
                  <User size={15} className="text-[#843122]" /> Tài khoản của tôi
                </button>
                <button
                  onClick={() => onNav('pets')}
                  className="flex items-center gap-2 text-[13px] text-gray-700 hover:bg-amber-50 rounded-md px-2 py-1.5 transition-colors w-full text-left font-medium"
                >
                  <img src="/imgs/DogSticker.svg" alt="" className="h-4 w-4 object-contain" /> Hồ sơ thú cưng
                </button>
                <button
                  onClick={() => onNav('orders')}
                  className="flex items-center gap-2 text-[13px] text-gray-700 hover:bg-amber-50 rounded-md px-2 py-1.5 transition-colors w-full text-left font-medium"
                >
                  <Package size={15} className="text-[#843122]" /> Đơn hàng của tôi
                </button>
                <button
                  onClick={() => onNav('review')}
                  className="flex items-center gap-2 text-[13px] text-gray-700 hover:bg-amber-50 rounded-md px-2 py-1.5 transition-colors w-full text-left font-medium"
                >
                  <Edit3 size={15} className="text-[#843122]" /> Đánh giá dịch vụ
                </button>
                <div className="border-t my-1" />
                <button
                  onClick={() => { logout(); onNav('home'); }}
                  className="flex items-center gap-2 text-[13px] text-red-600 hover:bg-red-50 rounded-md px-2 py-1.5 transition-colors w-full text-left font-semibold"
                >
                  <LogOut size={14} /> Đăng xuất
                </button>
              </div>
            ) : null}
          </div>

          {/* Cart Icon */}
          <div className="relative cursor-pointer text-[#3B2A1E] hover:text-[#843122] transition-colors" onClick={() => onNav('cart')}>
            <ShoppingCart size={22} strokeWidth={2.5} />
            {cartCount > 0 && (
              <span className="absolute -top-2 -right-2 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white z-10">
                {cartCount}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Hàng 2 (Main Nav) - Dính trên cùng khi cuộn */}
      <div className={`flex items-center px-8 lg:px-12 transition-all duration-300 ${isScrolled ? 'h-[60px]' : 'h-[50px]'}`}>
        
        {/* Logo mini hiện ra khi scroll */}
        <div
          className={`flex-shrink-0 cursor-pointer font-[var(--font-friendly)] font-black text-xl text-[#202945] tracking-tight flex items-center gap-1.5 transition-all duration-300 overflow-hidden ${isScrolled ? 'w-[200px] opacity-100' : 'w-0 opacity-0'}`}
          onClick={() => onNav('home')}
        >
          <img src="/imgs/DogSticker.svg" alt="PetCare Logo" className="h-6 w-auto object-contain" /> PetCare
        </div>

        {/* Main Menu (Centered) */}
        <nav className="flex-1 flex justify-center items-center h-full gap-6 min-w-0">
          {MAIN_NAV.map((l) => (
            <div key={l.label} className="group relative flex items-center h-full whitespace-nowrap">
              <a
                href="#"
                onClick={(e) => {
                  e.preventDefault();
                  onNav(l.page);
                }}
                className="flex items-center gap-1.5 text-[14px] font-bold text-[#843122] hover:text-[#5c2116] transition-colors tracking-wide h-full whitespace-nowrap"
              >
                {l.label}
                {l.hasDropdown && <ChevronDown size={14} strokeWidth={3} className="group-hover:rotate-180 transition-transform duration-200" />}
              </a>

              {/* Mega Menu Dropdown */}
              {l.hasDropdown && (
                <div className="absolute top-[100%] left-1/2 -translate-x-1/2 hidden group-hover:flex bg-white shadow-xl p-6 gap-10 rounded-b-lg border-t-2 border-[#843122] w-max min-w-[300px]">
                  {SHOP_MEGA_MENU.map((col) => (
                    <div key={col.title}>
                      <div className="font-bold text-[#3B2A1E] mb-3 border-b border-gray-100 pb-2">{col.title}</div>
                      <div className="flex flex-col gap-3">
                        {col.items.map((item) => (
                          <a 
                            key={item.label}
                            href={item.url}
                            className="text-[13px] font-medium text-gray-500 hover:text-[#843122] transition-colors"
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

        {/* Khối bên phải (hiện ra khi scroll để cân bằng UI hoặc chứa icon) */}
        <div className={`flex items-center justify-end gap-4 transition-all duration-300 overflow-hidden flex-shrink-0 ${isScrolled ? 'w-[200px] opacity-100 pr-2' : 'w-0 opacity-0 pr-0'}`}>
           <div className="relative flex items-center w-[160px]">
             <input 
               type="text" 
               placeholder="Tìm kiếm..." 
               className="w-full h-[36px] pl-4 pr-9 rounded-full border border-gray-200 bg-white text-[13px] focus:outline-none focus:ring-2 focus:ring-[#843122]"
             />
             <button className="absolute right-1 flex items-center justify-center w-7 h-7 rounded-full bg-[#6a2517] text-white hover:bg-[#4a1910] transition-colors">
               <Search size={12} strokeWidth={3} />
             </button>
           </div>
           <button className="text-[#3B2A1E] hover:text-[#843122] flex-shrink-0" onClick={() => (user ? onNav('account') : onNav('login'))}>
             <User size={20} strokeWidth={2.5}/>
           </button>
           <div className="relative cursor-pointer text-[#3B2A1E] hover:text-[#843122] flex-shrink-0" onClick={() => onNav('cart')}>
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
  );
}
