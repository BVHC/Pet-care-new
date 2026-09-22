import {
  Award,
  Check,
  ChevronRight,
  Crown,
  Gift,
  Sparkles,
  Star,
  TrendingUp,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Data.
   ================================================================ */

interface Tier {
  id: string
  name: string
  icon: LucideIcon
  color: string
  threshold: number
  pointMultiplier: number
  perks: string[]
  active?: boolean
}

const TIERS: Tier[] = [
  {
    id: 'silver',
    name: 'Bạc',
    icon: Star,
    color: '#94a3b8',
    threshold: 0,
    pointMultiplier: 1,
    perks: [
      'Tích 1 điểm cho mỗi 10.000đ chi tiêu',
      'Giảm 5% dịch vụ spa lần đầu',
      'Miễn phí đổi trả trong 7 ngày',
    ],
  },
  {
    id: 'gold',
    name: 'Vàng',
    icon: Award,
    color: '#ca8a04',
    threshold: 5_000_000,
    pointMultiplier: 1.5,
    perks: [
      'Tích 1.5 điểm cho mỗi 10.000đ chi tiêu',
      'Giảm 10% dịch vụ spa & grooming',
      'Tặng 1 lượt khám sức khỏe định kỳ/năm',
      'Ưu tiên đặt lịch trong giờ cao điểm',
    ],
    active: true,
  },
  {
    id: 'platinum',
    name: 'Bạch Kim',
    icon: Crown,
    color: '#7c3aed',
    threshold: 20_000_000,
    pointMultiplier: 2,
    perks: [
      'Tích 2 điểm cho mỗi 10.000đ chi tiêu',
      'Giảm 15% toàn bộ dịch vụ & sản phẩm',
      'Tặng 2 lượt khám sức khỏe + tiêm phòng/năm',
      'Lưu trú khách sạn miễn phí 2 đêm/năm',
      'Hỗ trợ CSKH riêng 24/7',
    ],
  },
]

const POINT_HISTORY = [
  { id: 'p1', date: '20/08/2026', desc: 'Đơn hàng ORD-20260800',  points: 360,  type: 'earn' as const },
  { id: 'p2', date: '15/08/2026', desc: 'Đơn hàng ORD-20260729',  points: 445,  type: 'earn' as const },
  { id: 'p3', date: '10/08/2026', desc: 'Đổi 1000 điểm = 50.000đ', points: -1000, type: 'redeem' as const },
  { id: 'p4', date: '02/08/2026', desc: 'Đơn hàng ORD-20260720',  points: 890,  type: 'earn' as const },
]

/* ================================================================
   Main.
   ================================================================ */

export function MembershipPage() {
  const currentTier = TIERS.find((t) => t.active) ?? TIERS[0]
  const nextTier = TIERS[TIERS.indexOf(currentTier) + 1]
  const totalPoints = POINT_HISTORY.reduce((sum, p) => sum + p.points, 0)
  const lifetimeSpend = 8_750_000
  const fmtVnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Hội viên PetCare"
        title="Chương trình hội viên & Điểm thưởng"
        subtitle="Càng mua nhiều, bạn càng được nhiều ưu đãi. Tích điểm, nâng hạng, tận hưởng đặc quyền."
        breadcrumbs={[{ label: 'Hội viên' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          {/* Sidebar */}
          <AccountSidebar active="membership" />

          {/* Content */}
          <div className="min-w-0 space-y-10">
            {/* Tier + Points hero */}
            <div className="grid gap-5 lg:grid-cols-[1fr_280px]">
              <div className="relative overflow-hidden rounded-3xl border border-(--color-border-default) bg-linear-to-br from-(--color-surface-card) via-(--color-surface-card) to-(--color-accent-soft) p-7 shadow-[0_4px_16px_-8px_rgba(164,51,36,0.4)]">
                <div className="absolute right-0 top-0 h-40 w-40 rounded-full bg-(--color-accent-soft) blur-3xl" aria-hidden />
                <div className="relative">
                  <div className="mb-2 flex items-center gap-2 text-[12px] font-bold uppercase tracking-wider text-accent">
                    <Sparkles size={14} /> Hạng hiện tại
                  </div>
                  <div className="flex items-center gap-4">
                    <div
                      className="flex h-16 w-16 items-center justify-center rounded-2xl shadow-[0_4px_12px_-4px_rgba(0,0,0,0.3)]"
                      style={{ background: currentTier.color }}
                    >
                      <currentTier.icon size={32} className="text-white" />
                    </div>
                    <div>
                      <div className="font-friendly text-3xl font-extrabold text-(--color-text-primary)">
                        {currentTier.name}
                      </div>
                      <div className="text-[13px] text-(--color-text-secondary)">
                        Tổng chi tiêu: {fmtVnd(lifetimeSpend)}
                      </div>
                    </div>
                  </div>

                  {nextTier && (
                    <div className="mt-6">
                      <div className="mb-2 flex items-center justify-between text-[12.5px] text-(--color-text-secondary)">
                        <span>Còn {fmtVnd(nextTier.threshold - lifetimeSpend)} để lên hạng {nextTier.name}</span>
                        <span className="font-bold text-accent">
                          {Math.round((lifetimeSpend / nextTier.threshold) * 100)}%
                        </span>
                      </div>
                      <div className="h-2 overflow-hidden rounded-full bg-(--color-surface-2)">
                        <div
                          className="h-full rounded-full bg-linear-to-r from-accent to-accent-hover"
                          style={{ width: `${(lifetimeSpend / nextTier.threshold) * 100}%` }}
                        />
                      </div>
                    </div>
                  )}
                </div>
              </div>

              <div className="flex flex-col rounded-3xl border border-(--color-border-default) bg-(--color-surface-card) p-7 shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]">
                <div className="mb-2 flex items-center gap-2 text-[12px] font-bold uppercase tracking-wider text-accent">
                  <Gift size={14} /> Điểm thưởng
                </div>
                <div className="font-friendly text-4xl font-extrabold text-(--color-text-primary)">
                  {totalPoints.toLocaleString('vi-VN')}
                  <span className="ml-1 text-base font-normal text-(--color-text-secondary)">điểm</span>
                </div>
                <div className="mt-1 text-[12.5px] text-(--color-text-secondary)">
                  Tương đương {(totalPoints * 50).toLocaleString('vi-VN')}đ
                </div>
                <button className="mt-4 inline-flex w-fit items-center gap-1 text-[13px] font-bold text-accent transition-colors hover:text-(--color-accent-hover)">
                  Đổi điểm ngay <ChevronRight size={14} />
                </button>
                <div className="mt-4 flex items-center gap-2 border-t border-(--color-border-default) pt-4 text-[12px] text-(--color-text-secondary)">
                  <TrendingUp size={14} className="text-emerald-600" />
                  <span>+1.5x hệ số tích điểm hạng Vàng</span>
                </div>
              </div>
            </div>

            {/* Tiers */}
            <div>
              <header className="mb-6 text-center">
                <h2 className="font-friendly text-[clamp(24px,3vw,36px)] font-extrabold text-(--color-text-primary)">
                  Bảng hạng &amp; Quyền lợi
                </h2>
                <p className="mt-1 text-[13px] text-(--color-text-secondary)">
                  Hạng chỉ tăng — không bao giờ bị hạ xuống.
                </p>
              </header>
              <div className="grid gap-5 lg:grid-cols-3">
                {TIERS.map((tier) => {
                  const isCurrent = tier.id === currentTier.id
                  return (
                    <div
                      key={tier.id}
                      className={`relative overflow-hidden rounded-3xl border-2 p-6 transition-all ${
                        isCurrent
                          ? 'border-(--color-accent) bg-(--color-surface-card) shadow-[0_8px_24px_-12px_rgba(164,51,36,0.5)]'
                          : 'border-(--color-border-default) bg-(--color-surface-card) hover:border-(--color-accent)'
                      }`}
                    >
                      {isCurrent && (
                        <span className="absolute right-4 top-4 rounded-full bg-accent px-2.5 py-0.5 text-[10px] font-extrabold uppercase tracking-wider text-white">
                          Hạng của bạn
                        </span>
                      )}
                      <div
                        className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl"
                        style={{ background: tier.color }}
                      >
                        <tier.icon size={28} className="text-white" />
                      </div>
                      <h3 className="font-friendly text-2xl font-extrabold text-(--color-text-primary)">
                        {tier.name}
                      </h3>
                      <p className="mt-1 text-[12px] text-(--color-text-secondary)">
                        {tier.threshold === 0 ? 'Hạng khởi điểm' : `Từ ${fmtVnd(tier.threshold)} tổng chi tiêu`}
                      </p>
                      <ul className="mt-4 space-y-2">
                        {tier.perks.map((perk) => (
                          <li key={perk} className="flex items-start gap-2 text-[13px] text-(--color-text-primary)">
                            <Check size={16} className="mt-0.5 shrink-0 text-emerald-600" />
                            <span>{perk}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )
                })}
              </div>
            </div>

            {/* Point history */}
            <div>
              <header className="mb-5 flex items-center justify-between">
                <h2 className="font-friendly text-2xl font-extrabold text-(--color-text-primary)">
                  Lịch sử điểm thưởng
                </h2>
                <button className="text-[13px] font-bold text-accent transition-colors hover:text-(--color-accent-hover)">
                  Xem tất cả
                </button>
              </header>
              <div className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                <table className="w-full text-left text-[13.5px]">
                  <thead className="bg-(--color-surface-2) text-[12px] font-bold uppercase tracking-wider text-(--color-text-secondary)">
                    <tr>
                      <th className="px-5 py-3.5">Ngày</th>
                      <th className="px-5 py-3.5">Mô tả</th>
                      <th className="px-5 py-3.5 text-right">Điểm</th>
                    </tr>
                  </thead>
                  <tbody>
                    {POINT_HISTORY.map((row) => (
                      <tr key={row.id} className="border-t border-(--color-border-default)">
                        <td className="px-5 py-3.5 text-(--color-text-secondary)">{row.date}</td>
                        <td className="px-5 py-3.5 text-(--color-text-primary)">{row.desc}</td>
                        <td className={`px-5 py-3.5 text-right font-bold ${
                          row.type === 'earn' ? 'text-emerald-600' : 'text-accent'
                        }`}>
                          {row.points > 0 ? '+' : ''}{row.points.toLocaleString('vi-VN')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
