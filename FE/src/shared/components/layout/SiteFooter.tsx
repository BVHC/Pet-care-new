import type { MouseEvent, ReactNode } from 'react';
import { Heart, Mail, MapPin, Phone, Send } from 'lucide-react';
import { FacebookIcon, InstagramIcon, YoutubeIcon } from './SocialIcons';

interface SiteFooterProps {
  onNav: (page: string) => void;
}

export function SiteFooter({ onNav }: SiteFooterProps) {
  const go = (page: string) => (e: MouseEvent) => {
    e.preventDefault();
    onNav(page);
  };

  return (
    <footer className="border-t border-(--color-border-default) bg-(--color-surface-1) px-6 pt-14 pb-7">
      {/* Newsletter */}
      <div className="mx-auto mb-10 max-w-6xl rounded-2xl bg-linear-to-br from-accent to-accent-hover p-6 sm:p-8">
        <div className="grid items-center gap-6 sm:grid-cols-2">
          <div className="text-white">
            <h3 className="font-friendly text-xl font-extrabold leading-tight">
              Nhận ưu đãi &amp; mẹo chăm sóc thú cưng
            </h3>
            <p className="mt-1 text-[13.5px] text-white/85">
              Đăng ký email để nhận ngay voucher 10% cho đơn đầu tiên.
            </p>
          </div>
          <form
            onSubmit={(e) => e.preventDefault()}
            className="flex gap-2"
          >
            <div className="relative flex-1">
              <Mail
                size={14}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-(--color-text-secondary)"
              />
              <input
                type="email"
                placeholder="email@example.com"
                className="h-11 w-full rounded-full border-none bg-white pl-9 pr-3 text-[13.5px] text-(--color-text-primary) outline-none focus:ring-2 focus:ring-white/40"
              />
            </div>
            <button
              type="submit"
              className="inline-flex h-11 items-center gap-1.5 rounded-full bg-[#202945] px-5 text-[13px] font-bold text-white transition-transform hover:scale-[1.02]"
            >
              <Send size={14} /> Đăng ký
            </button>
          </form>
        </div>
      </div>

      {/* Main grid */}
      <div className="mx-auto grid max-w-6xl grid-cols-2 gap-8 pb-8 sm:grid-cols-3 lg:grid-cols-[1.4fr_1fr_1fr_1fr_1.1fr]">
        <div className="col-span-2 sm:col-span-3 lg:col-span-1">
          <div
            className="mb-3 cursor-pointer font-friendly text-xl font-extrabold text-(--color-text-primary) flex items-center gap-2"
            onClick={() => onNav('home')}
          >
            <img src="/imgs/DogSticker.svg" alt="PetCare Logo" className="h-6 w-auto object-contain" /> PetCare
          </div>
          <p className="max-w-[260px] text-[13px] leading-relaxed text-(--color-text-secondary)">
            Mua sắm, khám thú y và lưu trú tại khách sạn thú cưng — tất cả trong một nơi thân thiện.
          </p>

          {/* Socials */}
          <div className="mt-4 flex items-center gap-2">
            {[
              { Icon: FacebookIcon, label: 'Facebook' },
              { Icon: InstagramIcon, label: 'Instagram' },
              { Icon: YoutubeIcon, label: 'YouTube' },
            ].map((s) => (
              <a
                key={s.label}
                href="#"
                onClick={(e) => e.preventDefault()}
                aria-label={s.label}
                className="flex h-9 w-9 items-center justify-center rounded-full border border-(--color-border-default) bg-(--color-surface-card) text-(--color-text-secondary) transition-all hover:border-(--color-accent) hover:bg-(--color-accent-soft) hover:text-accent"
              >
                <s.Icon size={15} />
              </a>
            ))}
          </div>
        </div>

        <FooterCol title="Cửa hàng">
          <a href="#" onClick={go('listing')}>Tất cả sản phẩm</a>
          <a href="#" onClick={go('recommend')}>Gợi ý cho thú cưng</a>
          <a href="#" onClick={go('cart')}>Giỏ hàng</a>
          <a href="#" onClick={go('favorites')}>Yêu thích</a>
        </FooterCol>

        <FooterCol title="Đặt lịch">
          <a href="#" onClick={go('booking')}>Đặt lịch khám</a>
          <a href="#" onClick={go('hotel')}>Khách sạn thú cưng</a>
          <a href="#" onClick={go('orders')}>Lịch sử đơn hàng</a>
        </FooterCol>

        <FooterCol title="Hội viên">
          <a href="#" onClick={go('membership')}>Hạng &amp; quyền lợi</a>
          <a href="#" onClick={go('vouchers')}>Voucher của tôi</a>
          <a href="#" onClick={go('about')}>Về PetCare</a>
          <a href="#" onClick={go('news')}>Tin tức &amp; bài viết</a>
        </FooterCol>

        <FooterCol title="Tài khoản">
          <a href="#" onClick={go('login')}>Đăng nhập</a>
          <a href="#" onClick={go('register')}>Đăng ký</a>
          <a href="#" onClick={go('account')}>Tài khoản của tôi</a>
          <a href="#" onClick={go('notifications')}>Thông báo</a>
          <a href="#" onClick={go('caregivers')}>Người chăm sóc</a>
          <a href="#" onClick={go('settings')}>Cài đặt</a>
          <a href="#" onClick={go('help')}>Trợ giúp</a>
        </FooterCol>
      </div>

      {/* Contact strip */}
      <div className="mx-auto mb-6 grid max-w-6xl gap-3 border-y border-(--color-border-default) py-5 text-[12.5px] text-(--color-text-secondary) sm:grid-cols-3">
        <div className="flex items-center gap-2">
          <Phone size={14} className="shrink-0 text-accent" />
          <span>
            Hotline: <strong className="text-(--color-text-primary)">1900 1234</strong> · 8:00–20:00
          </span>
        </div>
        <div className="flex items-center gap-2">
          <Mail size={14} className="shrink-0 text-accent" />
          <span>hotro@petcare.vn</span>
        </div>
        <div className="flex items-center gap-2">
          <MapPin size={14} className="shrink-0 text-accent" />
          <span>123 Nguyễn Trãi, Quận 1, TP. HCM</span>
        </div>
      </div>

      {/* Bottom */}
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-5 gap-y-2 text-[13px] text-(--color-text-secondary)">
        <span className="flex items-center gap-1">
          © 2026 PetCare. Thực hiện với <Heart size={14} className="fill-[#843122] text-[#843122]" /> dành cho người yêu thú cưng.
        </span>
        <a href="#" onClick={go('terms')} className="hover:text-accent">Điều khoản sử dụng</a>
        <a href="#" onClick={go('privacy')} className="hover:text-accent">Chính sách bảo mật</a>
      </div>
    </footer>
  );
}

function FooterCol({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <div className="mb-3.5 text-[13px] font-bold tracking-wide text-(--color-text-secondary) uppercase">
        {title}
      </div>
      <div className="flex flex-col gap-2.5 [&_a]:text-sm [&_a]:text-(--color-text-primary) [&_a]:no-underline [&_a:hover]:text-accent [&_a]:transition-colors">
        {children}
      </div>
    </div>
  );
}
