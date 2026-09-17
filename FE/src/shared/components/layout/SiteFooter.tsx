import type { MouseEvent, ReactNode } from 'react';
import { Heart } from 'lucide-react';

interface SiteFooterProps {
  onNav: (page: string) => void;
}

export function SiteFooter({ onNav }: SiteFooterProps) {
  const go = (page: string) => (e: MouseEvent) => {
    e.preventDefault();
    onNav(page);
  };

  return (
    <footer className="border-t border-[var(--color-border-default)] px-6 pt-10 pb-7">
      <div className="mx-auto grid max-w-6xl grid-cols-1 gap-8 pb-7 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_1fr]">
        <div>
          <div
            className="mb-2.5 cursor-pointer font-[var(--font-friendly)] text-xl font-extrabold text-[var(--color-text-primary)] flex items-center gap-2"
            onClick={() => onNav('home')}
          >
            <img src="/imgs/DogSticker.svg" alt="PetCare Logo" className="h-6 w-auto object-contain" /> PetCare
          </div>
          <div className="max-w-[260px] text-sm leading-relaxed text-[var(--color-text-secondary)]">
            Mua sắm, khám thú y và lưu trú tại khách sạn thú cưng — tất cả trong một nơi thân thiện.
          </div>
        </div>
        <FooterCol title="Cửa hàng">
          <a href="#" onClick={go('listing')}>Tất cả sản phẩm</a>
          <a href="#" onClick={go('recommend')}>Gợi ý cho thú cưng</a>
          <a href="#" onClick={go('cart')}>Giỏ hàng</a>
        </FooterCol>
        <FooterCol title="Đặt lịch">
          <a href="#" onClick={go('booking')}>Đặt lịch khám</a>
          <a href="#" onClick={go('hotel')}>Khách sạn thú cưng</a>
          <a href="#" onClick={go('journal')}>Nhật ký lưu trú</a>
          <a href="#" onClick={go('petprofile')}>Hồ sơ thú cưng</a>
          <a href="#" onClick={go('orders')}>Lịch sử đơn hàng</a>
          <a href="#" onClick={go('review')}>Viết đánh giá</a>
        </FooterCol>
        <FooterCol title="Tài khoản">
          <a href="#" onClick={go('login')}>Đăng nhập</a>
          <a href="#" onClick={go('signup')}>Đăng ký</a>
          <a href="#" onClick={go('account')}>Tài khoản của tôi</a>
          <a href="#" onClick={go('notifications')}>Thông báo</a>
        </FooterCol>
      </div>
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-5 gap-y-2 border-t border-[var(--color-border-default)] pt-5 text-[13px] text-[var(--color-text-secondary)]">
        <span className="flex items-center gap-1">
          © 2026 PetCare. Thực hiện với <Heart size={14} className="fill-[#843122] text-[#843122]" /> dành cho người yêu thú cưng.
        </span>
        <a href="#" onClick={go('terms')} className="hover:text-[var(--color-accent)]">Điều khoản sử dụng</a>
        <a href="#" onClick={go('privacy')} className="hover:text-[var(--color-accent)]">Chính sách bảo mật</a>
      </div>
    </footer>
  );
}

function FooterCol({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <div className="mb-3.5 text-[13px] font-bold tracking-wide text-[var(--color-text-secondary)] uppercase">
        {title}
      </div>
      <div className="flex flex-col gap-2.5 [&_a]:text-sm [&_a]:text-[var(--color-text-primary)] [&_a]:no-underline [&_a:hover]:text-[var(--color-text-link)]">
        {children}
      </div>
    </div>
  );
}
