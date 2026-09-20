import { useState } from 'react'
import { Check, Copy, Ticket } from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Data.
   ================================================================ */

interface Voucher {
  id: string
  code: string
  title: string
  desc: string
  discount: string
  expiresAt: string
  minOrder: number
  scope: 'all' | 'service' | 'product'
  status: 'available' | 'used' | 'expired'
}

const VOUCHERS: Voucher[] = [
  {
    id: 'v1',
    code: 'WELCOME10',
    title: 'Giảm 10% đơn hàng đầu tiên',
    desc: 'Áp dụng cho toàn bộ sản phẩm & dịch vụ. Không cộng dồn với khuyến mãi khác.',
    discount: '-10%',
    expiresAt: '31/12/2026',
    minOrder: 0,
    scope: 'all',
    status: 'available',
  },
  {
    id: 'v2',
    code: 'FREESHIP',
    title: 'Miễn phí vận chuyển',
    desc: 'Áp dụng cho đơn hàng từ 300.000đ. Tối đa 30.000đ phí vận chuyển.',
    discount: 'Freeship',
    expiresAt: '15/10/2026',
    minOrder: 300_000,
    scope: 'product',
    status: 'available',
  },
  {
    id: 'v3',
    code: 'SPA20',
    title: 'Giảm 20% dịch vụ Spa & Grooming',
    desc: 'Áp dụng cho dịch vụ tắm, cắt tỉa, spa cao cấp.',
    discount: '-20%',
    expiresAt: '30/09/2026',
    minOrder: 200_000,
    scope: 'service',
    status: 'available',
  },
  {
    id: 'v4',
    code: 'PETCARE50',
    title: 'Giảm 50K đơn từ 500K',
    desc: 'Đã sử dụng cho đơn hàng ORD-20260729 ngày 15/08/2026.',
    discount: '-50K',
    expiresAt: '30/11/2026',
    minOrder: 500_000,
    scope: 'all',
    status: 'used',
  },
  {
    id: 'v5',
    code: 'SUMMER30',
    title: 'Giảm 30% mùa hè',
    desc: 'Đã hết hạn ngày 31/07/2026.',
    discount: '-30%',
    expiresAt: '31/07/2026',
    minOrder: 0,
    scope: 'all',
    status: 'expired',
  },
]

const TABS = [
  { id: 'available' as const, label: 'Có thể dùng', count: VOUCHERS.filter((v) => v.status === 'available').length },
  { id: 'used' as const,      label: 'Đã dùng',     count: VOUCHERS.filter((v) => v.status === 'used').length },
  { id: 'expired' as const,   label: 'Hết hạn',    count: VOUCHERS.filter((v) => v.status === 'expired').length },
]

/* ================================================================
   Main.
   ================================================================ */

export function VouchersPage() {
  const [tab, setTab] = useState<'available' | 'used' | 'expired'>('available')
  const [copiedId, setCopiedId] = useState<string | null>(null)

  const filtered = VOUCHERS.filter((v) => v.status === tab)

  const handleCopy = (id: string, code: string) => {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText(code)
    }
    setCopiedId(id)
    window.setTimeout(() => setCopiedId(null), 1500)
  }

  const fmtVnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'

  return (
    <div className="bg-[var(--color-surface-page)] pb-24">
      <CommonPageHero
        eyebrow="Kho voucher"
        title="Mã giảm giá của bạn"
        subtitle="Sử dụng voucher khi thanh toán để nhận ưu đãi. Mỗi đơn hàng chỉ áp dụng được 1 voucher."
        breadcrumbs={[{ label: 'Voucher' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="vouchers" />
          <div className="min-w-0 space-y-6">
            {/* Tabs */}
            <div className="inline-flex rounded-full border border-[var(--color-border-default)] bg-[var(--color-surface-card)] p-1 shadow-[0_1px_2px_rgba(56,36,23,0.06)]">
              {TABS.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setTab(t.id)}
                  className={`flex items-center gap-2 rounded-full px-5 py-2 text-[13px] font-bold transition-all ${
                    tab === t.id
                      ? 'bg-[var(--color-accent)] text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                      : 'text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]'
                  }`}
                >
                  {t.label}
                  <span
                    className={`rounded-full px-2 py-0.5 text-[10.5px] font-bold ${
                      tab === t.id ? 'bg-white/20 text-white' : 'bg-[var(--color-surface-2)] text-[var(--color-text-secondary)]'
                    }`}
                  >
                    {t.count}
                  </span>
                </button>
              ))}
            </div>

            {/* Voucher list */}
            {filtered.length === 0 ? (
              <div className="rounded-3xl border border-dashed border-[var(--color-border-default)] bg-[var(--color-surface-card)] p-12 text-center">
                <Ticket size={36} className="mx-auto text-[var(--color-text-secondary)] opacity-40" />
                <p className="mt-3 text-[14px] text-[var(--color-text-secondary)]">
                  Chưa có voucher nào trong mục này.
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {filtered.map((v) => {
                  const inactive = v.status !== 'available'
                  return (
                    <div
                      key={v.id}
                      className={`flex flex-col overflow-hidden rounded-2xl border ${
                        inactive
                          ? 'border-[var(--color-border-default)] bg-[var(--color-surface-1)] opacity-60'
                          : 'border-[var(--color-border-default)] bg-[var(--color-surface-card)] shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)] hover:shadow-[0_2px_4px_rgba(56,36,23,0.07),0_12px_28px_-16px_rgba(164,51,36,0.4)]'
                      } sm:flex-row`}
                    >
                      {/* Left: discount badge */}
                      <div
                        className={`relative flex shrink-0 items-center justify-center px-6 py-5 text-center sm:w-44 ${
                          inactive
                            ? 'bg-[var(--color-surface-2)] text-[var(--color-text-secondary)]'
                            : 'bg-gradient-to-br from-[var(--color-accent)] to-[var(--color-accent-hover)] text-white'
                        }`}
                      >
                        <div className="absolute -right-3 top-1/2 hidden h-6 w-6 -translate-y-1/2 rounded-full bg-[var(--color-surface-page)] sm:block" />
                        <div className="absolute -left-3 top-1/2 hidden h-6 w-6 -translate-y-1/2 rounded-full bg-[var(--color-surface-page)] sm:block" />
                        <div>
                          <div className="font-[var(--font-friendly)] text-3xl font-extrabold leading-none">
                            {v.discount}
                          </div>
                          <div className="mt-1 text-[10.5px] font-bold uppercase tracking-wider opacity-85">
                            {v.scope === 'all' ? 'Tất cả' : v.scope === 'service' ? 'Dịch vụ' : 'Sản phẩm'}
                          </div>
                        </div>
                      </div>

                      <div className="border-dashed border-[var(--color-border-default)] sm:border-l" />

                      {/* Right: details */}
                      <div className="flex flex-1 items-start justify-between gap-4 p-5">
                        <div className="min-w-0 flex-1">
                          <h3 className="text-[15px] font-bold text-[var(--color-text-primary)]">{v.title}</h3>
                          <p className="mt-1 text-[12.5px] leading-relaxed text-[var(--color-text-secondary)]">
                            {v.desc}
                          </p>
                          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11.5px] text-[var(--color-text-secondary)]">
                            <span>
                              Đơn tối thiểu: <strong className="text-[var(--color-text-primary)]">{fmtVnd(v.minOrder)}</strong>
                            </span>
                            <span>·</span>
                            <span>
                              HSD: <strong className="text-[var(--color-text-primary)]">{v.expiresAt}</strong>
                            </span>
                          </div>
                          <div className="mt-3 inline-flex items-center gap-2 rounded-lg border border-dashed border-[var(--color-border-default)] bg-[var(--color-surface-2)] px-3 py-1.5">
                            <Ticket size={13} className="text-[var(--color-accent)]" />
                            <code className="font-mono text-[12.5px] font-bold tracking-wide text-[var(--color-text-primary)]">
                              {v.code}
                            </code>
                          </div>
                        </div>

                        {v.status === 'available' ? (
                          <button
                            onClick={() => handleCopy(v.id, v.code)}
                            className="inline-flex shrink-0 items-center gap-1.5 self-start rounded-lg border border-[var(--color-border-default)] px-3 py-1.5 text-[12.5px] font-bold text-[var(--color-accent)] transition-colors hover:bg-[var(--color-accent-soft)]"
                          >
                            {copiedId === v.id ? <Check size={13} /> : <Copy size={13} />}
                            {copiedId === v.id ? 'Đã sao chép' : 'Sao chép'}
                          </button>
                        ) : (
                          <span className="shrink-0 self-start rounded-full bg-[var(--color-surface-2)] px-2.5 py-1 text-[10.5px] font-bold uppercase tracking-wider text-[var(--color-text-secondary)]">
                            {v.status === 'used' ? 'Đã dùng' : 'Hết hạn'}
                          </span>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
