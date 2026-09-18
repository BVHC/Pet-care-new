import {
  Award,
  Calendar,
  Heart,
  Mail,
  MapPin,
  PawPrint,
  Phone,
  Shield,
  ShoppingBag,
  Sparkles,
  Stethoscope,
  Star,
} from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Data.
   ================================================================ */

const STATS = [
  { value: '10K+', label: 'Khách hàng tin tưởng' },
  { value: '15+',  label: 'Năm kinh nghiệm' },
  { value: '50+',  label: 'Bác sĩ chuyên nghiệp' },
  { value: '4.9★', label: 'Đánh giá trung bình' },
]

const VALUES = [
  {
    icon: Heart,
    title: 'Yêu thương',
    desc: 'Mỗi thú cưng đều xứng đáng được yêu thương và chăm sóc tốt nhất.',
  },
  {
    icon: Shield,
    title: 'An toàn',
    desc: 'Tiêu chuẩn vệ sinh, an toàn và chất lượng dịch vụ hàng đầu trong ngành.',
  },
  {
    icon: Award,
    title: 'Chuyên nghiệp',
    desc: 'Đội ngũ bác sĩ và nhân viên được đào tạo bài bản, giàu kinh nghiệm thực tiễn.',
  },
  {
    icon: Star,
    title: 'Chất lượng',
    desc: 'Sản phẩm và dịch vụ luôn đạt tiêu chuẩn cao nhất, cập nhật theo quy chuẩn quốc tế.',
  },
]

const SERVICES = [
  {
    icon: Stethoscope,
    title: 'Khám bệnh & Tiêm phòng',
    desc: 'Khám lâm sàng, xét nghiệm, tiêm vaccine theo phác đồ khoa học.',
  },
  {
    icon: PawPrint,
    title: 'Chăm sóc & Spa',
    desc: 'Tắm, cắt tỉa, chăm sóc lông-da, nail — dịch vụ làm đẹp cho thú cưng.',
  },
  {
    icon: ShoppingBag,
    title: 'Mua sắm thực phẩm & phụ kiện',
    desc: 'Hơn 1000+ sản phẩm chính hãng: thức ăn, đồ chơi, phụ kiện, đồ dùng y tế.',
  },
  {
    icon: Sparkles,
    title: 'Khách sạn lưu trú',
    desc: 'Phòng riêng, camera 24/7, nhân viên trực chiều đêm, vận động 2 lần/ngày.',
  },
]

const TEAM = [
  {
    name: 'Dr. Nguyễn Văn Minh',
    role: 'Bác sĩ Thú y chuyên nghiệp',
    exp: '15 năm kinh nghiệm',
    image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?w=400',
  },
  {
    name: 'Dr. Trần Thị Lan',
    role: 'Chuyên gia Dinh dưỡng',
    exp: '10 năm kinh nghiệm',
    image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?w=400',
  },
  {
    name: 'Ngô Thị Hương',
    role: 'Chuyên gia Chăm sóc & Spa',
    exp: '8 năm kinh nghiệm',
    image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?w=400',
  },
  {
    name: 'Lê Minh Tuấn',
    role: 'Bác sĩ Phẫu thuật',
    exp: '12 năm kinh nghiệm',
    image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?w=400',
  },
]

/* ================================================================
   Main.
   ================================================================ */

export function AboutPage() {
  return (
    <div className="bg-[var(--color-surface-page)] pb-24">
      <CommonPageHero
        eyebrow="Về chúng tôi"
        title="PetCare — Người bạn đồng hành đáng tin cậy"
        subtitle="Hơn 15 năm mang đến dịch vụ chăm sóc thú cưng toàn diện: từ y tế, dinh dưỡng, spa cho đến lưu trú. Chúng tôi tin rằng mỗi thú cưng đều xứng đáng được yêu thương và có cuộc sống khỏe mạnh nhất."
        backgroundImage="/imgs/hero-dog-clean.png"
        breadcrumbs={[{ label: 'Giới thiệu' }]}
        actions={[
          { label: 'Đặt lịch ngay', href: '/booking', variant: 'primary' },
          { label: 'Mua sắm', href: '/shop', variant: 'ghost' },
        ]}
      />

      {/* Stats */}
      <section className="bg-[var(--color-accent)] px-6 py-12">
        <div className="mx-auto grid max-w-[1100px] grid-cols-2 gap-8 text-center text-white sm:grid-cols-4">
          {STATS.map((s) => (
            <div key={s.label}>
              <div className="font-bayon text-4xl font-normal leading-none">{s.value}</div>
              <div className="mt-2 text-[13.5px] text-white/85">{s.label}</div>
            </div>
          ))}
        </div>
      </section>

      {/* Values */}
      <section className="mx-auto max-w-[1100px] px-5 py-20 sm:px-8">
        <header className="mb-12 text-center">
          <h2 className="font-bayon text-[clamp(28px,4vw,42px)] leading-[1.05] font-normal text-[var(--color-text-primary)]">
            Giá trị cốt lõi
          </h2>
          <p className="mt-3 text-[var(--color-text-secondary)]">Những giá trị chúng tôi luôn theo đuổi</p>
        </header>

        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {VALUES.map((v) => (
            <div
              key={v.title}
              className="rounded-2xl border border-[var(--color-border-default)] bg-[var(--color-surface-card)] p-6 text-center shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)] transition-shadow hover:shadow-[0_2px_4px_rgba(56,36,23,0.07),0_12px_28px_-16px_rgba(164,51,36,0.4)]"
            >
              <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-[var(--color-accent-soft)] text-[var(--color-accent)]">
                <v.icon size={26} />
              </div>
              <h3 className="font-bayon text-lg font-normal text-[var(--color-text-primary)]">{v.title}</h3>
              <p className="mt-2 text-[13.5px] leading-relaxed text-[var(--color-text-secondary)]">{v.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Services overview */}
      <section className="bg-[var(--color-surface-1)] px-5 py-20 sm:px-8">
        <div className="mx-auto max-w-[1100px]">
          <header className="mb-12 text-center">
            <h2 className="font-bayon text-[clamp(28px,4vw,42px)] leading-[1.05] font-normal text-[var(--color-text-primary)]">
              Dịch vụ của chúng tôi
            </h2>
            <p className="mt-3 text-[var(--color-text-secondary)]">Tất cả dịch vụ chăm sóc thú cưng tại một nơi duy nhất</p>
          </header>

          <div className="grid gap-5 sm:grid-cols-2">
            {SERVICES.map((s) => (
              <div
                key={s.title}
                className="flex gap-4 rounded-2xl border border-[var(--color-border-default)] bg-[var(--color-surface-card)] p-6 shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)] transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-0.5"
              >
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-[var(--color-accent-soft)] text-[var(--color-accent)]">
                  <s.icon size={24} />
                </div>
                <div>
                  <h3 className="font-bayon text-lg font-normal text-[var(--color-text-primary)]">{s.title}</h3>
                  <p className="mt-2 text-[13.5px] leading-relaxed text-[var(--color-text-secondary)]">{s.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Team */}
      <section className="mx-auto max-w-[1100px] px-5 py-20 sm:px-8">
        <header className="mb-12 text-center">
          <h2 className="font-bayon text-[clamp(28px,4vw,42px)] leading-[1.05] font-normal text-[var(--color-text-primary)]">
            Đội ngũ chuyên gia
          </h2>
          <p className="mt-3 text-[var(--color-text-secondary)]">Những người bạn đồng hành của thú cưng</p>
        </header>

        <div className="grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
          {TEAM.map((m) => (
            <div
              key={m.name}
              className="rounded-2xl border border-[var(--color-border-default)] bg-[var(--color-surface-card)] p-5 text-center shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)] transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-0.5"
            >
              <div className="mx-auto mb-4 h-32 w-32 overflow-hidden rounded-full border-4 border-white shadow-lg">
                <img src={m.image} alt={m.name} className="h-full w-full object-cover" />
              </div>
              <h3 className="font-bayon text-base font-normal text-[var(--color-text-primary)]">{m.name}</h3>
              <p className="text-[13px] font-semibold text-[var(--color-accent)]">{m.role}</p>
              <p className="mt-1 flex items-center justify-center gap-1 text-[12px] text-[var(--color-text-secondary)]">
                <Calendar size={12} /> {m.exp}
              </p>
            </div>
          ))}
        </div>
      </section>

      {/* Contact */}
      <section className="bg-[var(--color-surface-1)] px-5 py-20 sm:px-8">
        <div className="mx-auto max-w-[1100px]">
          <header className="mb-12 text-center">
            <h2 className="font-bayon text-[clamp(28px,4vw,42px)] leading-[1.05] font-normal text-[var(--color-text-primary)]">
              Liên hệ với chúng tôi
            </h2>
            <p className="mt-3 text-[var(--color-text-secondary)]">Sẵn sàng hỗ trợ bạn và thú cưng 24/7</p>
          </header>

          <div className="grid gap-5 sm:grid-cols-3">
            {[
              {
                icon: MapPin,
                title: 'Địa chỉ',
                lines: ['123 Đường Nguyễn Trãi', 'Quận 1, TP. Hồ Chí Minh'],
                meta: 'Có chỗ đậu xe',
              },
              {
                icon: Phone,
                title: 'Hotline',
                lines: ['1900 1234', '0901 234 567'],
                meta: '8:00 – 20:00, Thứ 2 – CN',
              },
              {
                icon: Mail,
                title: 'Email',
                lines: ['contact@petcare.vn', 'hotro@petcare.vn'],
                meta: 'Phản hồi trong 24h',
              },
            ].map((c) => (
              <div
                key={c.title}
                className="flex items-start gap-4 rounded-2xl border border-[var(--color-border-default)] bg-[var(--color-surface-card)] p-6 shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]"
              >
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-[var(--color-accent-soft)] text-[var(--color-accent)]">
                  <c.icon size={22} />
                </div>
                <div>
                  <h3 className="font-bayon text-base font-normal text-[var(--color-text-primary)]">{c.title}</h3>
                  {c.lines.map((l) => (
                    <p key={l} className="text-[13.5px] text-[var(--color-text-primary)]">{l}</p>
                  ))}
                  <p className="mt-1 text-[12px] text-[var(--color-text-secondary)]">{c.meta}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  )
}
