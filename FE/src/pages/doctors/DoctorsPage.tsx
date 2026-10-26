import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { MapPin, PawPrint, RotateCw, WifiOff } from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { usePublicVets } from '@/shared/hooks/usePublicSite'
import type { PublicVet } from '@/shared/types/public-site'
import { doctorInitials } from './initials'

/* ================================================================
   Data.
   ================================================================ */

// Thẻ, chip, ô chờ dùng chung token của site (NewsPage); chữ phụ là nâu đậm 70% để đủ tương phản trên nền kem lẫn thẻ trắng.
const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]'
const MUTED = 'text-(--color-dark-gray)/70'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const BUTTON = `inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover ${FOCUS}`

/* ================================================================
   States.
   ================================================================ */

function DoctorsSkeleton() {
  return (
    <div role="status" aria-label="Đang tải đội ngũ bác sĩ" className="grid gap-5 md:grid-cols-2">
      {[0, 1].map((i) => (
        <div key={i} aria-hidden className={`${CARD} space-y-4 p-5 sm:p-6`}>
          <div className="flex items-center gap-4">
            <div className={`${PULSE} h-20 w-20 shrink-0 rounded-2xl sm:h-24 sm:w-24`} />
            <div className="w-full space-y-2.5">
              <div className={`${PULSE} h-6 w-40 max-w-full rounded-lg`} />
              <div className={`${PULSE} h-4 w-28 rounded-lg`} />
              <div className={`${PULSE} h-4 w-36 max-w-full rounded-lg`} />
            </div>
          </div>
          <div className={`${PULSE} h-12 w-full rounded-lg`} />
        </div>
      ))}
    </div>
  )
}

function LoadError({ retrying, onRetry }: { retrying: boolean; onRetry: () => void }) {
  return (
    <div role="alert" className={`${CARD} mx-auto max-w-[560px] p-8 text-center`}>
      <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
        <WifiOff size={22} aria-hidden />
      </span>
      <p className="mt-4 font-friendly text-[20px] font-bold text-(--color-surface-dark)">Không tải được danh sách bác sĩ</p>
      <p className={`mt-1 text-[14px] ${MUTED}`}>Kết nối có thể đang gián đoạn. Bạn thử tải lại nhé.</p>
      <button type="button" onClick={onRetry} disabled={retrying} className={`${BUTTON} mt-5 disabled:opacity-60`}>
        <RotateCw size={15} aria-hidden className={retrying ? 'animate-spin motion-reduce:animate-none' : ''} />
        Thử lại
      </button>
    </div>
  )
}

function EmptyNotice({ title, hint, children }: { title: string; hint: string; children: React.ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-(--color-border-default) bg-(--color-surface-card) p-10 text-center">
      <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
        <PawPrint size={22} aria-hidden />
      </span>
      <p className="mt-4 font-friendly text-[20px] font-bold text-(--color-surface-dark)">{title}</p>
      <p className={`mt-1 text-[14px] ${MUTED}`}>{hint}</p>
      <div className="mt-5">{children}</div>
    </div>
  )
}

/* ================================================================
   Pieces.
   ================================================================ */

interface BranchFilterProps {
  branches: { branchId: number; name: string }[]
  value: number | null
  onChange: (branchId: number | null) => void
}

function BranchFilter({ branches, value, onChange }: BranchFilterProps) {
  const chip = (selected: boolean) =>
    `min-h-10 rounded-full px-4 text-[13.5px] font-semibold transition-colors ${FOCUS} ${
      selected
        ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
        : 'border border-(--color-border-default) bg-(--color-surface-card) text-(--color-dark-gray) hover:bg-(--color-accent-soft) hover:text-accent'
    }`
  return (
    <div role="group" aria-label="Lọc theo chi nhánh" className="mb-8 flex flex-wrap gap-2">
      <button type="button" aria-pressed={value === null} onClick={() => onChange(null)} className={chip(value === null)}>
        Tất cả chi nhánh
      </button>
      {branches.map((b) => (
        <button key={b.branchId} type="button" aria-pressed={value === b.branchId} onClick={() => onChange(b.branchId)} className={chip(value === b.branchId)}>
          {b.name}
        </button>
      ))}
    </div>
  )
}

/** Ảnh bác sĩ; không có ảnh hoặc ảnh tải lỗi thì hiện chữ cái đầu của tên (tên đã có ngay cạnh nên khối này chỉ trang trí). */
function DoctorAvatar({ vet }: { vet: PublicVet }) {
  const [broken, setBroken] = useState(false)
  const size = 'h-20 w-20 shrink-0 rounded-2xl sm:h-24 sm:w-24'
  if (!vet.avatarUrl || broken) {
    return (
      <div aria-hidden className={`${size} flex items-center justify-center bg-(--color-accent-soft) font-friendly text-[28px] font-extrabold text-accent sm:text-[32px]`}>
        {doctorInitials(vet.fullName)}
      </div>
    )
  }
  return (
    <img
      src={vet.avatarUrl}
      alt=""
      width={96}
      height={96}
      loading="lazy"
      onError={() => setBroken(true)}
      className={`${size} bg-(--color-surface-2) object-cover`}
    />
  )
}

function DoctorCard({ vet }: { vet: PublicVet }) {
  return (
    <article className={`${CARD} flex h-full flex-col gap-4 p-5 sm:p-6`}>
      <div className="flex items-center gap-4">
        <DoctorAvatar vet={vet} />
        <div className="min-w-0">
          <h3 className="font-friendly text-[20px] font-bold leading-tight text-(--color-surface-dark)">{vet.fullName}</h3>
          <p className="mt-1 text-[14px] font-semibold text-accent">{vet.specialty || 'Bác sĩ thú y'}</p>
          <p className={`mt-1.5 flex items-center gap-1.5 text-[13px] ${MUTED}`}>
            <MapPin size={13} aria-hidden className="shrink-0" />
            {vet.branchName}
          </p>
        </div>
      </div>
      {vet.bio && <p className={`line-clamp-4 text-[14px] leading-relaxed ${MUTED}`}>{vet.bio}</p>}
    </article>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function DoctorsPage() {
  const vets = usePublicVets()
  const [branchId, setBranchId] = useState<number | null>(null)

  useEffect(() => {
    const previous = document.title
    document.title = 'Bác sĩ | PetCare'
    return () => {
      document.title = previous
    }
  }, [])

  const all = useMemo(() => vets.data ?? [], [vets.data])
  // Chỉ mời lọc theo chi nhánh có bác sĩ; một lần tải, lọc ở client.
  const branches = useMemo(() => {
    const byId = new Map(all.map((v) => [v.branchId, v.branchName]))
    return [...byId].map(([id, name]) => ({ branchId: id, name })).sort((a, b) => a.name.localeCompare(b.name, 'vi'))
  }, [all])
  const visible = useMemo(() => (branchId === null ? all : all.filter((v) => v.branchId === branchId)), [all, branchId])

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Bác sĩ"
        title="Đội ngũ bác sĩ"
        subtitle="Bác sĩ thú y đang công tác tại các chi nhánh PetCare."
        breadcrumbs={[{ label: 'Bác sĩ' }]}
        actions={[
          { label: 'Đặt lịch khám', href: '/booking' },
          { label: 'Xem chi nhánh', href: '/branches', variant: 'ghost' },
        ]}
      />

      <div className="mx-auto max-w-[1100px] px-5 pt-12 sm:px-8">
        {vets.isError ? (
          <LoadError retrying={vets.isFetching} onRetry={() => void vets.refetch()} />
        ) : vets.isPending ? (
          <DoctorsSkeleton />
        ) : all.length === 0 ? (
          <EmptyNotice title="Hiện chưa có hồ sơ bác sĩ nào được công bố." hint="Bạn có thể gọi trực tiếp chi nhánh để được tư vấn.">
            <Link to="/branches" className={BUTTON}>
              Xem chi nhánh để gọi hỏi
            </Link>
          </EmptyNotice>
        ) : (
          <>
            {branches.length >= 2 && <BranchFilter branches={branches} value={branchId} onChange={setBranchId} />}
            {visible.length === 0 ? (
              <EmptyNotice title="Chi nhánh này chưa có bác sĩ nào được giới thiệu." hint="Chọn chi nhánh khác hoặc xem toàn bộ đội ngũ.">
                <button type="button" onClick={() => setBranchId(null)} className={BUTTON}>
                  Xem tất cả chi nhánh
                </button>
              </EmptyNotice>
            ) : (
              <section aria-labelledby="doctors-list">
                <h2 id="doctors-list" className="sr-only">
                  Danh sách bác sĩ
                </h2>
                <ul className="grid gap-5 md:grid-cols-2">
                  {visible.map((v) => (
                    <li key={v.accountId}>
                      <DoctorCard vet={v} />
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
      </div>
    </div>
  )
}
