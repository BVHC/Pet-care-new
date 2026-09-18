import { useState } from 'react'
import {
  AlertCircle,
  Calendar,
  Check,
  ChevronRight,
  CreditCard,
  Gift,
  Package,
  PawPrint,
  Plus,
  Star,
  Tag,
  X,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Types.
   ================================================================ */

interface MyPackage {
  id: string
  name: string
  petName: string
  petImage: string
  petType: 'dog' | 'cat'
  totalSessions: number
  usedSessions: number
  expiresAt: string
  status: 'active' | 'expiring' | 'expired'
  price: number
}

interface StorePackage {
  id: string
  name: string
  desc: string
  originalPrice: number
  salePrice: number
  sessions: number
  badge?: string
  tag?: string
  suitable: string
}

/* ================================================================
   Mock data.
   ================================================================ */

const MY_PACKAGES: MyPackage[] = [
  {
    id: 'pkg1',
    name: 'Gói Spa Cao Cấp',
    petName: 'Milo',
    petImage: '/imgs/hero-dog-clean.png',
    petType: 'dog',
    totalSessions: 5,
    usedSessions: 2,
    expiresAt: '31/12/2026',
    status: 'active',
    price: 1_200_000,
  },
  {
    id: 'pkg2',
    name: 'Gói Tiêm Chủng Năm Đầu',
    petName: 'Milo',
    petImage: '/imgs/hero-dog-clean.png',
    petType: 'dog',
    totalSessions: 4,
    usedSessions: 3,
    expiresAt: '01/06/2027',
    status: 'expiring',
    price: 800_000,
  },
  {
    id: 'pkg3',
    name: 'Gói Grooming Cơ Bản',
    petName: 'Luna',
    petImage: '/imgs/prod3.jpg',
    petType: 'cat',
    totalSessions: 6,
    usedSessions: 1,
    expiresAt: '15/09/2026',
    status: 'active',
    price: 600_000,
  },
]

const STORE_PACKAGES: StorePackage[] = [
  {
    id: 'sp1',
    name: 'Combo Spa 5 Buổi',
    desc: 'Tắm, sấy, cắt tỉa lông, vệ sinh tai, cắt móng. Dành cho chó dưới 15kg.',
    originalPrice: 2_000_000,
    salePrice: 1_600_000,
    sessions: 5,
    badge: 'Tiết kiệm 20%',
    suitable: 'Chó dưới 15kg',
  },
  {
    id: 'sp2',
    name: 'Gói Tiêm Chủng 4 Bệnh',
    desc: 'Bao gồm vaccine dại + 4 bệnh phổ biến. Miễn phí tái khám sau tiêm.',
    originalPrice: 1_200_000,
    salePrice: 980_000,
    sessions: 4,
    tag: 'Phổ biến',
    suitable: 'Chó & Mèo',
  },
  {
    id: 'sp3',
    name: 'Gói Khám Sức Khỏe Premium',
    desc: 'Khám tổng quát, xét nghiệm máu, siêu âm bụng, tư vấn dinh dưỡng.',
    originalPrice: 1_500_000,
    salePrice: 1_200_000,
    sessions: 2,
    badge: 'Bác sĩ Premium',
    suitable: 'Tất cả thú cưng',
  },
  {
    id: 'sp4',
    name: 'Combo Grooming 10 Buổi',
    desc: 'Grooming định kỳ hàng tháng cho mèo. Bao gồm tắm, cắt lông, vệ sinh.',
    originalPrice: 2_500_000,
    salePrice: 1_900_000,
    sessions: 10,
    tag: 'Tặng 1 buổi',
    suitable: 'Mèo',
  },
]

/* ================================================================
   Helpers.
   ================================================================ */

const fmtVnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'
const progressPct = (used: number, total: number) => Math.round((used / total) * 100)

const statusConfig = {
  active:  { label: 'Đang dùng', cls: 'bg-emerald-100 text-emerald-700' },
  expiring:{ label: 'Sắp hết hạn', cls: 'bg-amber-100 text-amber-700' },
  expired: { label: 'Đã hết hạn', cls: 'bg-gray-100 text-gray-600' },
}

/* ================================================================
   Buy modal.
   ================================================================ */

function BuyModal({ pkg, onClose }: { pkg: StorePackage; onClose: () => void }) {
  const [petId, setPetId] = useState('')
  const [note, setNote] = useState('')
  const [submitted, setSubmitted] = useState(false)

  const pets = [
    { id: 'p1', name: 'Milo (Golden Retriever)', image: '/imgs/hero-dog-clean.png' },
    { id: 'p2', name: 'Luna (Maine Coon)', image: '/imgs/prod3.jpg' },
  ]

  const handleBuy = () => {
    if (!petId) return
    setSubmitted(true)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="w-full max-w-md overflow-hidden rounded-3xl border border-[var(--color-border-default)] bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]">
        <div className="flex items-center justify-between border-b border-[var(--color-border-default)] px-6 py-4">
          <h2 className="font-[var(--font-friendly)] text-lg font-extrabold text-[var(--color-text-primary)]">Mua gói dịch vụ</h2>
          <button onClick={onClose} className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"><X size={20} /></button>
        </div>

        {!submitted ? (
          <div className="p-6 space-y-4">
            {/* Package summary */}
            <div className="rounded-xl border border-[var(--color-border-default)] bg-[var(--color-surface-2)] p-4">
              <div className="font-bold text-[var(--color-text-primary)]">{pkg.name}</div>
              <div className="mt-1 flex items-center gap-2 text-[12px] text-[var(--color-text-secondary)]">
                <Tag size={13} /> {pkg.sessions} buổi · {pkg.suitable}
              </div>
              <div className="mt-2 flex items-baseline gap-2">
                <span className="font-[var(--font-friendly)] text-2xl font-extrabold text-[var(--color-accent)]">{fmtVnd(pkg.salePrice)}</span>
                <span className="text-[12px] text-[var(--color-text-secondary)] line-through">{fmtVnd(pkg.originalPrice)}</span>
              </div>
            </div>

            {/* Pet selector */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Chọn thú cưng</label>
              <div className="space-y-1.5">
                {pets.map((p) => (
                  <label key={p.id}
                    className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition-all ${petId === p.id ? 'border-[var(--color-accent)] bg-[var(--color-accent-soft)]' : 'border-[var(--color-border-default)] hover:bg-[var(--color-surface-2)]'}`}>
                    <input type="radio" name="buy-pet" value={p.id} checked={petId === p.id} onChange={() => setPetId(p.id)}
                      className="accent-[var(--color-accent)]" />
                    <img src={p.image} alt={p.name} className="h-8 w-8 rounded-full object-cover" />
                    <span className="text-[13px] font-semibold text-[var(--color-text-primary)]">{p.name}</span>
                  </label>
                ))}
              </div>
            </div>

            {/* Note */}
            <div>
              <label className="mb-1 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Ghi chú (tùy chọn)</label>
              <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="VD: Milo dị ứng sữa tắm..."
                className="w-full resize-none rounded-xl border border-[var(--color-border-default)] bg-white px-3.5 py-2.5 text-[13px] text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)] focus:ring-2 focus:ring-[var(--color-accent-soft)]" />
            </div>

            <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-[12px] text-blue-800">
              Thanh toán qua ví điện tử hoặc thẻ. Gói được kích hoạt ngay sau khi thanh toán thành công.
            </div>

            <div className="flex gap-2">
              <button onClick={onClose} className="flex-1 rounded-xl border border-[var(--color-border-default)] py-2.5 text-[13px] font-bold text-[var(--color-text-secondary)] transition-colors hover:bg-[var(--color-surface-2)]">Hủy</button>
              <button onClick={handleBuy} disabled={!petId}
                className="flex-1 flex items-center justify-center gap-1.5 rounded-xl bg-[var(--color-accent)] py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)] disabled:cursor-not-allowed disabled:opacity-50">
                <CreditCard size={14} /> Thanh toán ngay
              </button>
            </div>
          </div>
        ) : (
          <div className="p-8 text-center">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
              <Check size={32} className="text-emerald-600" />
            </div>
            <h3 className="font-[var(--font-friendly)] text-xl font-extrabold text-[var(--color-text-primary)]">Đặt hàng thành công!</h3>
            <p className="mt-2 text-[13px] text-[var(--color-text-secondary)]">
              Gói {pkg.name} đã được thêm vào tài khoản của bạn. Vui lòng thanh toán để kích hoạt.
            </p>
            <button onClick={onClose}
              className="mt-6 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-accent)] px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)]">
              Đã hiểu <ChevronRight size={14} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function PackagesPage() {
  const [tab, setTab] = useState<'my' | 'store'>('my')
  const [buyPkg, setBuyPkg] = useState<StorePackage | null>(null)

  return (
    <div className="bg-[var(--color-surface-page)] pb-24">
      <CommonPageHero
        eyebrow="Gói dịch vụ"
        title="Gói dịch vụ trả trước"
        subtitle="Mua gói combo tiết kiệm hơn. Theo dõi số lượt còn lại và hạn sử dụng của từng gói dịch vụ đã mua."
        breadcrumbs={[{ label: 'Gói dịch vụ' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="packages" />

          <div className="min-w-0 space-y-6">
            {/* Tab bar */}
            <div className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-[var(--color-border-default)] bg-white p-1.5 shadow-[0_1px_2px_rgba(56,36,23,0.06)]">
              <button onClick={() => setTab('my')}
                className={`flex shrink-0 items-center gap-2 rounded-xl px-5 py-2.5 text-[13px] font-bold transition-all ${
                  tab === 'my'
                    ? 'bg-[var(--color-accent)] text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                    : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)] hover:text-[var(--color-text-primary)]'
                }`}>
                <Package size={15} /> Gói của tôi
              </button>
              <button onClick={() => setTab('store')}
                className={`flex shrink-0 items-center gap-2 rounded-xl px-5 py-2.5 text-[13px] font-bold transition-all ${
                  tab === 'store'
                    ? 'bg-[var(--color-accent)] text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                    : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)] hover:text-[var(--color-text-primary)]'
                }`}>
                <Gift size={15} /> Mua gói mới
              </button>
            </div>

            {/* My packages */}
            {tab === 'my' && (
              MY_PACKAGES.length === 0 ? (
                <EmptyStateMy />
              ) : (
                <div className="space-y-4">
                  {MY_PACKAGES.map((pkg) => {
                    const pct = progressPct(pkg.usedSessions, pkg.totalSessions)
                    const sc = statusConfig[pkg.status]
                    const remaining = pkg.totalSessions - pkg.usedSessions
                    return (
                      <div key={pkg.id} className="overflow-hidden rounded-2xl border border-[var(--color-border-default)] bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                        {/* Header */}
                        <div className="flex items-center justify-between border-b border-[var(--color-border-default)] bg-[var(--color-surface-2)] px-5 py-3">
                          <div className="flex items-center gap-2">
                            <Package size={14} className="text-[var(--color-accent)]" />
                            <span className="text-[13px] font-bold text-[var(--color-text-primary)]">{pkg.name}</span>
                          </div>
                          <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ${sc.cls}`}>{sc.label}</span>
                        </div>
                        <div className="p-5">
                          <div className="flex items-center gap-3">
                            <img src={pkg.petImage} alt={pkg.petName} className="h-12 w-12 rounded-full object-cover" />
                            <div>
                              <div className="flex items-center gap-2">
                                <span className="text-[14px] font-bold text-[var(--color-text-primary)]">{pkg.petName}</span>
                              </div>
                              <div className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-[12px] text-[var(--color-text-secondary)]">
                                <span className="flex items-center gap-1">
                                  <Calendar size={11} /> HSD: <strong className="text-[var(--color-text-primary)]">{pkg.expiresAt}</strong>
                                </span>
                                <span className="flex items-center gap-1">
                                  <Star size={11} /> {pkg.totalSessions} buổi · {fmtVnd(pkg.price)}
                                </span>
                              </div>
                            </div>
                          </div>

                          {/* Progress */}
                          <div className="mt-4">
                            <div className="mb-1.5 flex items-center justify-between text-[12px]">
                              <span className="font-medium text-[var(--color-text-secondary)]">
                                Đã dùng: <strong className="text-[var(--color-text-primary)]">{pkg.usedSessions}/{pkg.totalSessions} buổi</strong>
                              </span>
                              <span className={`font-bold ${remaining === 0 ? 'text-red-600' : 'text-emerald-600'}`}>
                                Còn lại: {remaining} buổi
                              </span>
                            </div>
                            <div className="h-2.5 overflow-hidden rounded-full bg-[var(--color-surface-2)]">
                              <div className="h-full rounded-full bg-gradient-to-r from-[var(--color-accent)] to-[var(--color-accent-hover)] transition-all"
                                style={{ width: `${pct}%` }} />
                            </div>
                          </div>

                          {/* Warning */}
                          {pkg.status === 'expiring' && (
                            <div className="mt-3 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-[12px] text-amber-800">
                              <AlertCircle size={14} className="mt-0.5 shrink-0" />
                              <span>Gói sắp hết hạn vào <strong>{pkg.expiresAt}</strong>. Sử dụng ngay để không mất quyền lợi!</span>
                            </div>
                          )}
                          {pkg.status === 'expired' && (
                            <div className="mt-3 flex items-start gap-2 rounded-xl border border-gray-200 bg-gray-50 p-3 text-[12px] text-gray-600">
                              <AlertCircle size={14} className="mt-0.5 shrink-0" />
                              <span>Gói đã hết hạn từ ngày <strong>{pkg.expiresAt}</strong>. Mua gói mới để tiếp tục sử dụng dịch vụ.</span>
                            </div>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
              )
            )}

            {/* Store packages */}
            {tab === 'store' && (
              <div className="grid gap-5 sm:grid-cols-2">
                {STORE_PACKAGES.map((pkg) => {
                  const discount = Math.round((1 - pkg.salePrice / pkg.originalPrice) * 100)
                  return (
                    <div key={pkg.id} className="overflow-hidden rounded-2xl border border-[var(--color-border-default)] bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                      {/* Top badge */}
                      {(pkg.badge || pkg.tag) && (
                        <div className="bg-[var(--color-accent)] px-4 py-1.5">
                          <span className="text-[11px] font-bold text-white">{pkg.badge || pkg.tag}</span>
                        </div>
                      )}
                      <div className="p-5">
                        <h3 className="font-[var(--font-friendly)] text-lg font-extrabold text-[var(--color-text-primary)]">{pkg.name}</h3>
                        <p className="mt-1.5 text-[12.5px] leading-relaxed text-[var(--color-text-secondary)]">{pkg.desc}</p>

                        <div className="mt-3 flex flex-wrap gap-2 text-[11.5px]">
                          <span className="inline-flex items-center gap-1 rounded-full bg-[var(--color-surface-2)] px-2.5 py-1 font-medium text-[var(--color-text-secondary)]">
                            <PawPrint size={10} /> {pkg.sessions} buổi
                          </span>
                          <span className="inline-flex items-center gap-1 rounded-full bg-[var(--color-surface-2)] px-2.5 py-1 font-medium text-[var(--color-text-secondary)]">
                            {pkg.suitable}
                          </span>
                        </div>

                        <div className="mt-4 flex items-end justify-between">
                          <div>
                            <div className="flex items-baseline gap-2">
                              <span className="font-[var(--font-friendly)] text-2xl font-extrabold text-[var(--color-accent)]">{fmtVnd(pkg.salePrice)}</span>
                              <span className="text-[12px] text-[var(--color-text-secondary)] line-through">{fmtVnd(pkg.originalPrice)}</span>
                            </div>
                            <span className="mt-0.5 inline-block rounded-full bg-red-100 px-2 py-0.5 text-[10.5px] font-bold text-red-600">-{discount}%</span>
                          </div>
                          <button onClick={() => setBuyPkg(pkg)}
                            className="inline-flex items-center gap-1.5 rounded-full bg-[var(--color-accent)] px-4 py-2 text-[13px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)]">
                            <Plus size={14} /> Mua ngay
                          </button>
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      </div>

      {buyPkg && <BuyModal pkg={buyPkg} onClose={() => setBuyPkg(null)} />}
    </div>
  )
}

function EmptyStateMy() {
  return (
    <div className="rounded-3xl border border-dashed border-[var(--color-border-default)] bg-white p-16 text-center">
      <Package size={48} className="mx-auto mb-4 text-[var(--color-text-secondary)] opacity-30" />
      <p className="text-[15px] font-bold text-[var(--color-text-secondary)]">Bạn chưa có gói dịch vụ nào</p>
      <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">Mua gói dịch vụ trả trước để tiết kiệm đến 30%!</p>
      <button onClick={() => {}}
        className="mt-5 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-accent)] px-5 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)]">
        <Gift size={14} /> Khám phá gói dịch vụ
      </button>
    </div>
  )
}
