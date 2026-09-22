import { useState } from 'react'
import {
  Cat,
  Check,
  Clock,
  Copy,
  Dog,
  Mail,
  PawPrint,
  Plus,
  Send,
  Shield,
  X,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Data.
   ================================================================ */

interface Caregiver {
  id: string
  name: string
  email: string
  status: 'active' | 'pending' | 'expired'
  pets: { name: string; type: 'dog' | 'cat' }[]
  grantedAt?: string
  expiresAt?: string
  invitedAt?: string
}

const CAREGIVERS: Caregiver[] = [
  {
    id: 'c1',
    name: 'Lê Văn An',
    email: 'lean@example.com',
    status: 'active',
    pets: [
      { name: 'Milo', type: 'dog' },
      { name: 'Bông', type: 'cat' },
    ],
    grantedAt: '10/08/2026',
    expiresAt: '10/08/2027',
  },
  {
    id: 'c2',
    name: 'Phạm Thị B',
    email: 'phamb@example.com',
    status: 'pending',
    pets: [{ name: 'Milo', type: 'dog' }],
    invitedAt: '22/08/2026',
  },
]

const MY_PETS = [
  { id: 'p1', name: 'Milo', type: 'dog' as const },
  { id: 'p2', name: 'Bông', type: 'cat' as const },
  { id: 'p3', name: 'Xoài', type: 'dog' as const },
]

/* ================================================================
   Main.
   ================================================================ */

export function CaregiversPage() {
  const [showInvite, setShowInvite] = useState(false)
  const [email, setEmail] = useState('')
  const [selectedPets, setSelectedPets] = useState<string[]>([])
  const [linkCopied, setLinkCopied] = useState(false)

  const handleCopyLink = () => {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText('https://petcare.vn/invite/abc-xyz-123')
    }
    setLinkCopied(true)
    window.setTimeout(() => setLinkCopied(false), 1500)
  }

  const togglePet = (id: string) => {
    setSelectedPets((prev) =>
      prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id],
    )
  }

  const handleSendInvite = () => {
    if (!email) return
    setShowInvite(false)
    setEmail('')
    setSelectedPets([])
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Ủy quyền chăm sóc"
        title="Người chăm sóc thú cưng"
        subtitle="Ủy quyền cho người thân hoặc bạn bè đặt lịch, đưa thú cưng đi khám/spa thay bạn. Quyền hạn có thời hạn và có thể thu hồi bất kỳ lúc nào."
        breadcrumbs={[{ label: 'Người chăm sóc' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="caregivers" />
          <div className="min-w-0 space-y-10">
            {/* Why caregiver */}
            <div className="grid gap-5 sm:grid-cols-3">
              {[
                {
                  icon: Shield,
                  title: 'An toàn & minh bạch',
                  desc: 'Mọi thao tác đều được ghi nhật ký. Bạn biết ai làm gì với thú cưng của mình.',
                },
                {
                  icon: Clock,
                  title: 'Có thời hạn rõ ràng',
                  desc: 'Mỗi lời mời có hiệu lực 7 ngày, mỗi quan hệ ủy quyền có thời hạn tối đa 12 tháng.',
                },
                {
                  icon: X,
                  title: 'Thu hồi tức thì',
                  desc: 'Bạn có thể thu hồi quyền bất kỳ lúc nào. Người được ủy quyền sẽ mất quyền ngay lập tức.',
                },
              ].map((b) => (
                <div
                  key={b.title}
                  className="rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) p-5 shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]"
                >
                  <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-xl bg-(--color-accent-soft) text-accent">
                    <b.icon size={20} />
                  </div>
                  <h3 className="text-[14px] font-bold text-(--color-text-primary)">{b.title}</h3>
                  <p className="mt-1 text-[12.5px] leading-relaxed text-(--color-text-secondary)">{b.desc}</p>
                </div>
              ))}
            </div>

            {/* Caregivers list */}
            <div>
              <header className="mb-6 flex items-center justify-between">
                <h2 className="font-friendly text-2xl font-extrabold text-(--color-text-primary)">
                  Danh sách ({CAREGIVERS.length})
                </h2>
                <button
                  onClick={() => setShowInvite(true)}
                  className="inline-flex items-center gap-1.5 rounded-full bg-accent px-4 py-2 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)"
                >
                  <Plus size={15} /> Mời thêm
                </button>
              </header>

              <div className="space-y-4">
                {CAREGIVERS.map((c) => (
                  <div
                    key={c.id}
                    className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]"
                  >
                    <div className="flex flex-wrap items-start gap-4 p-5">
                      <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-(--color-accent-soft) font-friendly text-xl font-bold text-accent">
                        {c.name.charAt(0)}
                      </div>

                      <div className="flex-1 min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h3 className="text-[15px] font-bold text-(--color-text-primary)">{c.name}</h3>
                          {c.status === 'active' && (
                            <span className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-[10.5px] font-bold uppercase tracking-wider text-emerald-700">
                              Đang hoạt động
                            </span>
                          )}
                          {c.status === 'pending' && (
                            <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-[10.5px] font-bold uppercase tracking-wider text-amber-700">
                              Chờ xác nhận
                            </span>
                          )}
                          {c.status === 'expired' && (
                            <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-[10.5px] font-bold uppercase tracking-wider text-gray-600">
                              Hết hạn
                            </span>
                          )}
                        </div>
                        <p className="mt-0.5 flex items-center gap-1 text-[12.5px] text-(--color-text-secondary)">
                          <Mail size={12} /> {c.email}
                        </p>

                        <div className="mt-3 flex flex-wrap items-center gap-3 text-[12px]">
                          <div className="flex items-center gap-1.5 text-(--color-text-secondary)">
                            <PawPrint size={12} />
                            <span>Chăm sóc:</span>
                            {c.pets.map((p, i) => (
                              <span key={p.name} className="inline-flex items-center gap-0.5 font-bold text-(--color-text-primary)">
                                {p.type === 'dog' ? <Dog size={12} /> : <Cat size={12} />}
                                {p.name}
                                {i < c.pets.length - 1 && <span className="ml-1 mr-1">,</span>}
                              </span>
                            ))}
                          </div>
                        </div>

                        {c.status === 'active' && c.grantedAt && c.expiresAt && (
                          <p className="mt-2 text-[11.5px] text-(--color-text-secondary)">
                            Cấp ngày {c.grantedAt} · Hết hạn {c.expiresAt}
                          </p>
                        )}
                        {c.status === 'pending' && c.invitedAt && (
                          <p className="mt-2 text-[11.5px] text-(--color-text-secondary)">
                            Đã mời ngày {c.invitedAt} · Lời mời hết hạn sau{' '}
                            {7 - Math.floor((Date.now() - new Date('2026-08-22').getTime()) / 86400000)} ngày
                          </p>
                        )}
                      </div>

                      <div className="flex shrink-0 items-center gap-2">
                        {c.status === 'active' ? (
                          <button className="rounded-lg border border-(--color-border-default) px-3 py-1.5 text-[12.5px] font-bold text-accent transition-colors hover:bg-(--color-accent-soft)">
                            Thu hồi
                          </button>
                        ) : c.status === 'pending' ? (
                          <>
                            <button className="rounded-lg border border-(--color-border-default) px-3 py-1.5 text-[12.5px] font-bold text-(--color-text-secondary) hover:bg-(--color-surface-2)">
                              Hủy lời mời
                            </button>
                            <button
                              onClick={handleCopyLink}
                              className="inline-flex items-center gap-1 rounded-lg border border-(--color-border-default) px-3 py-1.5 text-[12.5px] font-bold text-accent hover:bg-(--color-accent-soft)"
                            >
                              {linkCopied ? <Check size={12} /> : <Copy size={12} />}
                              {linkCopied ? 'Đã sao chép' : 'Sao chép link'}
                            </button>
                          </>
                        ) : null}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Invite modal */}
      {showInvite && (
        <div
          className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center"
          onClick={() => setShowInvite(false)}
        >
          <div
            className="w-full max-w-md overflow-hidden rounded-3xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-(--color-border-default) px-6 py-4">
              <h2 className="font-friendly text-lg font-extrabold text-(--color-text-primary)">
                Mời người chăm sóc
              </h2>
              <button
                onClick={() => setShowInvite(false)}
                className="text-(--color-text-secondary) transition-colors hover:text-(--color-text-primary)"
              >
                <X size={20} />
              </button>
            </div>

            <div className="p-6">
              <p className="mb-4 text-[13px] text-(--color-text-secondary)">
                Người được mời sẽ nhận email xác nhận. Lời mời có hiệu lực 7 ngày.
              </p>

              <label className="mb-4 block">
                <span className="mb-1.5 block text-[12.5px] font-bold text-(--color-text-primary)">
                  Email người được mời
                </span>
                <div className="relative">
                  <Mail
                    size={15}
                    className="absolute left-3 top-1/2 -translate-y-1/2 text-(--color-text-secondary)"
                  />
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="email@example.com"
                    className="w-full rounded-xl border border-(--color-border-default) bg-(--color-surface-card) py-2.5 pl-9 pr-3 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)"
                  />
                </div>
              </label>

              <div className="mb-5">
                <span className="mb-2 block text-[12.5px] font-bold text-(--color-text-primary)">
                  Thú cưng được ủy quyền
                </span>
                <div className="space-y-1.5">
                  {MY_PETS.map((p) => (
                    <label
                      key={p.id}
                      className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition-all ${
                        selectedPets.includes(p.id)
                          ? 'border-(--color-accent) bg-(--color-accent-soft)'
                          : 'border-(--color-border-default) hover:bg-(--color-surface-2)'
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={selectedPets.includes(p.id)}
                        onChange={() => togglePet(p.id)}
                        className="h-4 w-4 accent-(--color-accent)"
                      />
                      {p.type === 'dog' ? <Dog size={16} /> : <Cat size={16} />}
                      <span className="text-[13.5px] font-semibold text-(--color-text-primary)">{p.name}</span>
                    </label>
                  ))}
                </div>
              </div>

              <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-[12px] text-amber-800">
                Quyền được ủy quyền: xem hồ sơ Pet, đặt lịch khám/spa, đưa Pet đi check-in/out. Không thể chuyển nhượng quyền sở hữu.
              </div>

              <div className="mt-5 flex gap-2">
                <button
                  onClick={() => setShowInvite(false)}
                  className="flex-1 rounded-xl border border-(--color-border-default) py-2.5 text-[13px] font-bold text-(--color-text-secondary) transition-colors hover:bg-(--color-surface-2)"
                >
                  Hủy
                </button>
                <button
                  onClick={handleSendInvite}
                  disabled={!email || selectedPets.length === 0}
                  className="flex flex-1 items-center justify-center gap-1.5 rounded-xl bg-accent py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover) disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <Send size={14} /> Gửi lời mời
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
