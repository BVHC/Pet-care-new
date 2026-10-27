import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ArrowRight,
  Award,
  BedDouble,
  Heart,
  Mail,
  MapPin,
  Phone,
  Scissors,
  Shield,
  Stethoscope,
  Star,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { doctorInitials } from '@/pages/doctors/initials'
import { PAGE_LIMITS } from '@/shared/constants/page-content'
import { usePublicPage } from '@/shared/hooks/usePageContent'
import { usePublicServiceList, usePublicVets } from '@/shared/hooks/usePublicSite'
import type { ServiceGroup } from '@/shared/types/catalog'
import type { PublicVet } from '@/shared/types/public-site'

/* ================================================================
   Data.
   ================================================================ */

// Số liệu và 4 giá trị cốt lõi do quản trị sửa (UC21, T47, khóa ABOUT); biểu tượng của giá trị theo vị trí. Đội ngũ và dịch vụ đọc từ API công khai (UC15).
const VALUE_ICONS: LucideIcon[] = [Heart, Shield, Award, Star]

const GROUPS: { group: ServiceGroup; icon: LucideIcon; title: string }[] = [
  { group: 'MEDICAL', icon: Stethoscope, title: 'Khám & tiêm chủng' },
  { group: 'GROOMING', icon: Scissors, title: 'Thẩm mỹ' },
  { group: 'BOARDING', icon: BedDouble, title: 'Lưu trú' },
]

/** Tối đa 8 bác sĩ ở trang giới thiệu (2 hàng); đầy đủ ở /doctors. */
const TEAM_LIMIT = 8

const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const SECTION_LINK =
  'inline-flex items-center gap-2 text-[14px] font-bold text-accent transition-colors hover:text-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'

/* ================================================================
   Pieces.
   ================================================================ */

/** Ảnh bác sĩ; chưa có ảnh hoặc ảnh tải lỗi thì hiện chữ cái đầu của tên (tên đã nằm ngay dưới). */
function TeamAvatar({ vet }: { vet: PublicVet }) {
  const [broken, setBroken] = useState(false)
  const frame = 'mx-auto mb-4 h-32 w-32 overflow-hidden rounded-full border-4 border-white shadow-lg'
  if (!vet.avatarUrl || broken) {
    return (
      <div aria-hidden className={`${frame} flex items-center justify-center bg-(--color-accent-soft) font-friendly text-[40px] font-extrabold text-accent`}>
        {doctorInitials(vet.fullName)}
      </div>
    )
  }
  return (
    <div className={frame}>
      <img src={vet.avatarUrl} alt={vet.fullName} loading="lazy" onError={() => setBroken(true)} className="h-full w-full object-cover" />
    </div>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function AboutPage() {
  const vets = usePublicVets()
  const services = usePublicServiceList()
  // Giữ skeleton tới khi có câu trả lời để không nháy từ chữ mặc định sang chữ đã sửa; `{}`, sai dạng hoặc request lỗi thì `ready` vẫn
  // true với chữ mặc định, trang không bao giờ trống.
  const about = usePublicPage('ABOUT')
  const team = (vets.data ?? []).slice(0, TEAM_LIMIT)
  // Nhóm nào có dịch vụ thì một thẻ liệt kê tên dịch vụ (giá và chi tiết ở /services).
  const serviceGroups = GROUPS.map((g) => ({ ...g, names: (services.data ?? []).filter((s) => s.group === g.group).map((s) => s.name) })).filter(
    (g) => g.names.length > 0,
  )

  return (
    <div className="bg-(--color-surface-page) pb-24">
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
      <section className="bg-accent px-6 py-12">
        {about.ready ? (
          <div className="mx-auto grid max-w-[1100px] grid-cols-2 gap-8 text-center text-white sm:grid-cols-4">
            {about.content.stats.map((s, i) => (
              <div key={i}>
                <div className="font-friendly font-extrabold text-4xl leading-none wrap-anywhere">{s.value}</div>
                <div className="mt-2 text-[13.5px] text-white/85 whitespace-pre-line wrap-anywhere">{s.label}</div>
              </div>
            ))}
          </div>
        ) : (
          <div role="status" aria-label="Đang tải số liệu" className="mx-auto grid max-w-[1100px] grid-cols-2 gap-8 sm:grid-cols-4">
            {Array.from({ length: PAGE_LIMITS.statCount }, (_, i) => (
              <div key={i} aria-hidden className="flex flex-col items-center">
                <div className="h-9 w-24 animate-pulse rounded-md bg-white/20 motion-reduce:animate-none" />
                <div className="mt-2 h-[14px] w-32 animate-pulse rounded-md bg-white/20 motion-reduce:animate-none" />
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Values */}
      <section className="mx-auto max-w-[1100px] px-5 py-20 sm:px-8">
        <header className="mb-12 text-center">
          <h2 className="font-friendly font-extrabold text-[clamp(28px,4vw,42px)] leading-[1.05] text-(--color-text-primary)">
            Giá trị cốt lõi
          </h2>
          <p className="mt-3 text-(--color-text-secondary)">Những giá trị chúng tôi luôn theo đuổi</p>
        </header>

        {about.ready ? (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {about.content.values.map((v, i) => {
              const Icon = VALUE_ICONS[i]
              return (
                <div
                  key={i}
                  className="rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) p-6 text-center shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)] transition-shadow hover:shadow-[0_2px_4px_rgba(56,36,23,0.07),0_12px_28px_-16px_rgba(164,51,36,0.4)]"
                >
                  <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
                    <Icon size={26} />
                  </div>
                  <h3 className="font-friendly font-bold text-lg text-(--color-text-primary) wrap-anywhere">{v.title}</h3>
                  <p className="mt-2 text-[13.5px] leading-relaxed text-(--color-text-secondary) whitespace-pre-line wrap-anywhere">{v.description}</p>
                </div>
              )
            })}
          </div>
        ) : (
          <div role="status" aria-label="Đang tải giá trị cốt lõi" className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: PAGE_LIMITS.valueCount }, (_, i) => (
              <div key={i} aria-hidden className={`${PULSE} h-[200px] rounded-2xl`} />
            ))}
          </div>
        )}
      </section>

      {/* Services overview — ẩn khi API lỗi hoặc chưa có dịch vụ nào */}
      {(services.isPending || serviceGroups.length > 0) && (
        <section className="bg-(--color-surface-1) px-5 py-20 sm:px-8">
          <div className="mx-auto max-w-[1100px]">
            <header className="mb-12 text-center">
              <h2 className="font-friendly font-extrabold text-[clamp(28px,4vw,42px)] leading-[1.05] text-(--color-text-primary)">
                Dịch vụ của chúng tôi
              </h2>
              <p className="mt-3 text-(--color-text-secondary)">Tất cả dịch vụ chăm sóc thú cưng tại một nơi duy nhất</p>
            </header>

            {services.isPending ? (
              <div role="status" aria-label="Đang tải dịch vụ" className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {[0, 1, 2].map((i) => (
                  <div key={i} aria-hidden className={`${PULSE} h-[116px] rounded-2xl`} />
                ))}
              </div>
            ) : (
              <>
                <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                  {serviceGroups.map((g) => (
                    <div
                      key={g.group}
                      className={`${CARD} flex gap-4 p-6 transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-0.5 motion-reduce:transition-none motion-reduce:hover:translate-y-0`}
                    >
                      <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-(--color-accent-soft) text-accent">
                        <g.icon size={24} />
                      </div>
                      <div className="min-w-0">
                        <h3 className="font-friendly font-bold text-lg text-(--color-text-primary)">{g.title}</h3>
                        <p className="mt-2 line-clamp-3 text-[13.5px] leading-relaxed text-(--color-text-secondary)">{g.names.join(', ')}</p>
                      </div>
                    </div>
                  ))}
                </div>
                <div className="mt-8 text-center">
                  <Link to="/services" className={SECTION_LINK}>
                    Xem tất cả dịch vụ <ArrowRight size={16} aria-hidden />
                  </Link>
                </div>
              </>
            )}
          </div>
        </section>
      )}

      {/* Team — ẩn khi API lỗi hoặc chưa có hồ sơ bác sĩ nào được công bố */}
      {(vets.isPending || team.length > 0) && (
        <section className="mx-auto max-w-[1100px] px-5 py-20 sm:px-8">
          <header className="mb-12 text-center">
            <h2 className="font-friendly font-extrabold text-[clamp(28px,4vw,42px)] leading-[1.05] text-(--color-text-primary)">
              Đội ngũ bác sĩ
            </h2>
            <p className="mt-3 text-(--color-text-secondary)">Những người bạn đồng hành của thú cưng</p>
          </header>

          {vets.isPending ? (
            <div role="status" aria-label="Đang tải đội ngũ bác sĩ" className="flex flex-wrap justify-center gap-8">
              <div aria-hidden className={`${PULSE} h-[260px] w-full rounded-2xl sm:w-[calc(50%-1rem)] lg:w-[calc(25%-1.5rem)]`} />
            </div>
          ) : (
            <>
              {/* Chịu được 1..8 bác sĩ: thẻ cố định bề rộng theo số cột, dồn giữa thay vì dồn trái. */}
              <div className="flex flex-wrap justify-center gap-8">
                {team.map((m) => (
                  <div
                    key={m.accountId}
                    className={`${CARD} w-full p-5 text-center transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] hover:-translate-y-0.5 motion-reduce:transition-none motion-reduce:hover:translate-y-0 sm:w-[calc(50%-1rem)] lg:w-[calc(25%-1.5rem)]`}
                  >
                    <TeamAvatar vet={m} />
                    <h3 className="font-friendly font-bold text-base text-(--color-text-primary)">{m.fullName}</h3>
                    <p className="text-[13px] font-semibold text-accent">{m.specialty || 'Bác sĩ thú y'}</p>
                    <p className="mt-1 flex items-center justify-center gap-1 text-[12px] text-(--color-text-secondary)">
                      <MapPin size={12} aria-hidden /> {m.branchName}
                    </p>
                  </div>
                ))}
              </div>
              <div className="mt-8 text-center">
                <Link to="/doctors" className={SECTION_LINK}>
                  Xem đội ngũ bác sĩ <ArrowRight size={16} aria-hidden />
                </Link>
              </div>
            </>
          )}
        </section>
      )}

      {/* Contact */}
      <section className="bg-(--color-surface-1) px-5 py-20 sm:px-8">
        <div className="mx-auto max-w-[1100px]">
          <header className="mb-12 text-center">
            <h2 className="font-friendly font-extrabold text-[clamp(28px,4vw,42px)] leading-[1.05] text-(--color-text-primary)">
              Liên hệ với chúng tôi
            </h2>
            <p className="mt-3 text-(--color-text-secondary)">Sẵn sàng hỗ trợ bạn và thú cưng 24/7</p>
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
                className="flex items-start gap-4 rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) p-6 shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]"
              >
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
                  <c.icon size={22} />
                </div>
                <div>
                  <h3 className="font-friendly font-bold text-base text-(--color-text-primary)">{c.title}</h3>
                  {c.lines.map((l) => (
                    <p key={l} className="text-[13.5px] text-(--color-text-primary)">{l}</p>
                  ))}
                  <p className="mt-1 text-[12px] text-(--color-text-secondary)">{c.meta}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  )
}
