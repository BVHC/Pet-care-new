import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Book,
  ChevronDown,
  Globe,
  Mail,
  MessageCircle,
  Package,
  PawPrint,
  Phone,
  Search,
  ShoppingBag,
  Stethoscope,
  User,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './HelpPage.module.css'

/* ================================================================
   FAQ data.
   ================================================================ */

interface FaqItem {
  q: string
  a: string
}

const FAQS: FaqItem[] = [
  {
    q: 'Làm sao đặt lịch khám cho thú cưng?',
    a: 'Trang khám phòng bệnh có sẵn lịch bác sĩ. Chọn ngày, giờ và dịch vụ phù hợp, sau đó nhập thông tin thú cưng để xác nhận đặt lịch. Bạn sẽ nhận email xác nhận ngay.',
  },
  {
    q: 'Tôi muốn đổi hoặc trả sản phẩm thì làm thế nào?',
    a: 'Bạn có thể yêu cầu đổi trả trong vòng 30 ngày kể từ ngày nhận hàng. Vào mục "Đơn hàng", chọn đơn cần đổi/trả và nhấn "Yêu cầu đổi trả". Đội ngũ CSKH sẽ liên hệ trong 24 giờ.',
  },
  {
    q: 'Phí giao hàng là bao nhiêu?',
    a: 'Đơn hàng từ 300.000đ trở lên được miễn phí giao hàng toàn quốc. Dưới mức đó, phí giao hàng là 30.000đ. Thời gian giao hàng từ 2–5 ngày tùy khu vực.',
  },
  {
    q: 'Cách nào để thêm thú cưng vào tài khoản?',
    a: 'Vào mục "Tài khoản" → "Thú cưng" → nhấn "Thêm thú cưng". Nhập tên, loại (chó/mèo), giống, tuổi và cân nặng để hệ thống gợi ý sản phẩm phù hợp.',
  },
  {
    q: 'Tôi quên mật khẩu, làm sao lấy lại?',
    a: 'Tại trang đăng nhập, nhấn "Quên mật khẩu", nhập email đã đăng ký và làm theo hướng dẫn trong email để đặt lại mật khẩu mới.',
  },
  {
    q: 'Dịch vụ khách sạn thú cưng có nhận cả chó và mèo không?',
    a: 'Có, khách sạn của Pet Care nhận cả chó và mèo. Mỗi phòng dành cho một thú cưng để đảm bảo an toàn. Giá phòng dao động tùy loại phòng và thời gian lưu trú.',
  },
]

/* ================================================================
   Main.
   ================================================================ */

export function HelpPage() {
  const [openFaq, setOpenFaq] = useState<number | null>(null)

  const toggleFaq = (i: number) => {
    setOpenFaq((prev) => (prev === i ? null : i))
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* Hero slab */}
      <section className={styles.slab}>
        <img src="/imgs/hero-dog-clean.png" alt="" className={styles.slabBg} aria-hidden loading="eager" />
        <div className={styles.slabOverlay} aria-hidden />
        <div className={styles.slabNoise} aria-hidden />

        <div className={`${styles.slabContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="hover:text-white hover:underline underline-offset-4 transition-colors">
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Trợ giúp</span>
          </nav>
          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
            Trung tâm trợ giúp
          </h1>
        </div>
      </section>

      {/* Content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar />

          <div className={styles.main}>
            {/* Search */}
            <div className={styles.searchWrap}>
              <Search size={17} className={styles.searchIcon} />
              <input
                type="search"
                placeholder="Tìm kiếm câu hỏi thường gặp..."
                className={styles.searchInput}
              />
            </div>

            {/* Categories */}
            <div className={styles.catGrid}>
              {[
                { icon: ShoppingBag, cls: styles.catIconBlue,   label: 'Đặt hàng\n& Thanh toán' },
                { icon: Package,      cls: styles.catIconGreen,  label: 'Vận chuyển\n& Giao hàng' },
                { icon: Stethoscope,  cls: styles.catIconOrange,label: 'Khám bệnh\n& Tiêm phòng' },
                { icon: PawPrint,     cls: styles.catIconYellow,label: 'Dịch vụ\n& Đặt lịch' },
                { icon: User,         cls: styles.catIconPurple,label: 'Tài khoản\n& Thú cưng' },
                { icon: Book,        cls: styles.catIconRed,   label: 'Chính sách\n& Đổi trả' },
              ].map((cat, i) => (
                <button key={i} type="button" className={styles.catCard}>
                  <span className={`${styles.catIcon} ${cat.cls}`}>
                    <cat.icon size={24} />
                  </span>
                  <span className={styles.catLabel} style={{ whiteSpace: 'pre-line' }}>
                    {cat.label}
                  </span>
                </button>
              ))}
            </div>

            {/* FAQ */}
            <section className={styles.faqSection}>
              <div className={styles.faqHeader}>
                <h2 className={styles.faqTitle}>Câu hỏi thường gặp</h2>
              </div>
              <div className={styles.faqList}>
                {FAQS.map((faq, i) => (
                  <div key={i} className={styles.faqItem}>
                    <div
                      className={styles.faqQ}
                      onClick={() => toggleFaq(i)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={(e) => e.key === 'Enter' && toggleFaq(i)}
                    >
                      <span>{faq.q}</span>
                      <ChevronDown
                        size={18}
                        className={`${styles.faqChevron} ${openFaq === i ? styles.faqChevronOpen : ''}`}
                      />
                    </div>
                    {openFaq === i && (
                      <div className={`${styles.faqA} ${styles.faqAOpen}`}>
                        {faq.a}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </section>

            {/* Contact */}
            <section className={styles.contactCard}>
              {[
                {
                  icon: MessageCircle,
                  cls: styles.contactIconBlue,
                  label: 'Chat trực tuyến',
                  value: '24/7 Hỗ trợ',
                },
                {
                  icon: Phone,
                  cls: styles.contactIconGreen,
                  label: 'Hotline',
                  value: '1900 1234',
                },
                {
                  icon: Mail,
                  cls: styles.contactIconOrange,
                  label: 'Email',
                  value: 'hotro@petcare.vn',
                },
                {
                  icon: Globe,
                  cls: styles.contactIconBlue,
                  label: 'Fanpage',
                  value: 'Pet Care VN',
                },
              ].map((c, i) => (
                <a key={i} href="#" className={styles.contactItem}>
                  <span className={`${styles.contactIcon} ${c.cls}`}>
                    <c.icon size={20} />
                  </span>
                  <div>
                    <div className={styles.contactLabel}>{c.label}</div>
                    <div className={styles.contactValue}>{c.value}</div>
                  </div>
                </a>
              ))}
            </section>
          </div>
        </div>
      </div>
    </div>
  )
}
