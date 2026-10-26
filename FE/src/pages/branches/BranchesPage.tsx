import { useEffect, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, ExternalLink, MapPin, PawPrint, Phone, RotateCw, Siren, WifiOff } from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { usePublicBranches, usePublicVets } from '@/shared/hooks/usePublicSite'
import type { PublicBranch } from '@/shared/types/branch'
import type { PublicVet } from '@/shared/types/public-site'
import { weekRows } from './opening-hours'

/* ================================================================
   Data.
   ================================================================ */

// Thẻ, chữ phụ, ô chờ dùng chung token của site (NewsPage); chữ phụ là nâu đậm 70% để đủ tương phản trên nền kem lẫn thẻ trắng.
const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]'
const MUTED = 'text-(--color-dark-gray)/70'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const BUTTON = `inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover ${FOCUS}`
const TEXT_LINK = `font-semibold text-accent underline underline-offset-4 hover:text-(--color-accent-hover) ${FOCUS}`

/** Chỉ tạo link khi có cả hai tọa độ hợp lệ (dữ liệu hỏng như null thì không sinh "null,null"). */
const mapUrl = ({ latitude, longitude }: PublicBranch) =>
  Number.isFinite(latitude) && Number.isFinite(longitude) ? `https://www.google.com/maps/search/?api=1&query=${latitude},${longitude}` : null

/* ================================================================
   States.
   ================================================================ */

function BranchesSkeleton() {
  return (
    <div role="status" aria-label="Đang tải chi nhánh" className="grid gap-6">
      {[0, 1].map((i) => (
        <div key={i} aria-hidden className={`${CARD} grid lg:grid-cols-[1.1fr_1fr]`}>
          <div className="space-y-4 p-6 sm:p-8">
            <div className={`${PULSE} h-8 w-60 max-w-full rounded-lg`} />
            <div className={`${PULSE} h-4 w-80 max-w-full rounded-lg`} />
            <div className={`${PULSE} h-4 w-40 rounded-lg`} />
            <div className={`${PULSE} h-16 w-full rounded-lg`} />
          </div>
          <div className="space-y-3 border-t border-(--color-border-default) p-6 sm:p-8 lg:border-l lg:border-t-0">
            {Array.from({ length: 5 }, (_, row) => (
              <div key={row} className={`${PULSE} h-5 w-full rounded-lg`} />
            ))}
          </div>
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
      <p className="mt-4 font-friendly text-[20px] font-bold text-(--color-surface-dark)">Không tải được danh sách chi nhánh</p>
      <p className={`mt-1 text-[14px] ${MUTED}`}>Kết nối có thể đang gián đoạn. Bạn thử tải lại nhé.</p>
      <button type="button" onClick={onRetry} disabled={retrying} className={`${BUTTON} mt-5 disabled:opacity-60`}>
        <RotateCw size={15} aria-hidden className={retrying ? 'animate-spin motion-reduce:animate-none' : ''} />
        Thử lại
      </button>
    </div>
  )
}

function EmptyNotice() {
  return (
    <div className="rounded-2xl border border-dashed border-(--color-border-default) bg-(--color-surface-card) p-10 text-center">
      <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
        <PawPrint size={22} aria-hidden />
      </span>
      <p className="mt-4 font-friendly text-[20px] font-bold text-(--color-surface-dark)">Hiện chưa có chi nhánh nào đang hoạt động.</p>
      <p className={`mt-1 text-[14px] ${MUTED}`}>Chi nhánh mới sẽ hiện ở đây ngay khi mở cửa. Bạn quay lại sau nhé.</p>
    </div>
  )
}

/* ================================================================
   Pieces.
   ================================================================ */

interface VetsState {
  loading: boolean
  failed: boolean
  retry: () => void
}

function BranchDoctors({ vets, state }: { vets: PublicVet[]; state: VetsState }) {
  return (
    <div className="mt-6 border-t border-(--color-border-default) pt-5">
      <h3 className="font-friendly text-[17px] font-bold text-(--color-surface-dark)">Bác sĩ tại chi nhánh</h3>
      {state.loading ? (
        <div role="status" aria-label="Đang tải bác sĩ" className={`${PULSE} mt-3 h-5 w-48 rounded-lg`} />
      ) : state.failed ? (
        <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1">
          <p className={`text-[14px] ${MUTED}`}>Chưa tải được danh sách bác sĩ.</p>
          <button type="button" onClick={state.retry} className={`text-[14px] ${TEXT_LINK}`}>
            Thử lại
          </button>
        </div>
      ) : vets.length === 0 ? (
        <p className={`mt-2 text-[14px] ${MUTED}`}>Chưa có bác sĩ nào được giới thiệu tại chi nhánh này.</p>
      ) : (
        <>
          <ul className="mt-3 grid gap-2">
            {vets.map((v) => (
              <li key={v.accountId} className="flex flex-wrap items-baseline gap-x-2 text-[14.5px]">
                <span className="font-semibold text-(--color-dark-gray)">{v.fullName}</span>
                {v.specialty && <span className={`text-[13px] ${MUTED}`}>{v.specialty}</span>}
              </li>
            ))}
          </ul>
          <Link to="/doctors" className={`mt-4 inline-flex items-center gap-1.5 text-[14px] ${TEXT_LINK}`}>
            Xem đội ngũ bác sĩ
            <ArrowRight size={15} aria-hidden />
          </Link>
        </>
      )}
    </div>
  )
}

function OpeningHours({ branch }: { branch: PublicBranch }) {
  const rows = weekRows(branch.weeklyHours)
  return (
    <div className="border-t border-(--color-border-default) bg-(--color-surface-1) p-6 sm:p-8 lg:border-l lg:border-t-0">
      <h3 className="font-friendly text-[17px] font-bold text-(--color-surface-dark)">Giờ làm việc</h3>
      {rows.length === 0 ? (
        <p className={`mt-3 text-[14px] ${MUTED}`}>Chưa cập nhật giờ làm việc.</p>
      ) : (
        <table className="mt-3 w-full text-[14px]">
          <caption className="sr-only">Giờ làm việc của {branch.name}</caption>
          <tbody className="divide-y divide-(--color-border-default)">
            {rows.map((r) => (
              <tr key={r.dayOfWeek}>
                <th scope="row" className="whitespace-nowrap py-2.5 pr-4 text-left align-top font-semibold text-(--color-dark-gray)">
                  {r.label}
                </th>
                <td className={`py-2.5 text-right tabular-nums ${r.closed ? MUTED : 'text-(--color-dark-gray)'}`}>{r.text}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {branch.acceptsAfterHoursEmergency && (
        <p className={`mt-4 flex items-start gap-2 text-[13px] leading-relaxed ${MUTED}`}>
          <Siren size={15} aria-hidden className="mt-0.5 shrink-0 text-accent" />
          Nhận cấp cứu ngoài giờ làm việc, vui lòng gọi trước khi đến.
        </p>
      )}
    </div>
  )
}

function BranchCard({ branch, vets, vetsState }: { branch: PublicBranch; vets: PublicVet[]; vetsState: VetsState }) {
  const headingId = `branch-${branch.branchId}`
  const map = mapUrl(branch)
  return (
    <article aria-labelledby={headingId} className={`${CARD} overflow-hidden`}>
      <div className="grid lg:grid-cols-[1.1fr_1fr]">
        <div className="p-6 sm:p-8">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <h2 id={headingId} className="font-friendly text-[26px] font-extrabold leading-tight text-(--color-surface-dark)">
              {branch.name}
            </h2>
            {branch.acceptsAfterHoursEmergency && (
              <span className="inline-flex items-center gap-1.5 rounded-full bg-accent px-3 py-1 text-[12px] font-bold text-white">
                <Siren size={13} aria-hidden />
                Cấp cứu 24/7
              </span>
            )}
          </div>

          <ul className="mt-5 grid gap-3 text-[14.5px] text-(--color-dark-gray)">
            <li className="flex items-start gap-3">
              <MapPin size={18} aria-hidden className="mt-0.5 shrink-0 text-accent" />
              <span>{branch.address}</span>
            </li>
            <li className="flex items-center gap-3">
              <Phone size={18} aria-hidden className="shrink-0 text-accent" />
              <a href={`tel:${branch.phone.replace(/\s+/g, '')}`} className={`font-semibold tabular-nums hover:text-accent ${FOCUS}`}>
                {branch.phone}
              </a>
            </li>
            {map && (
              <li className="flex items-center gap-3">
                <ExternalLink size={18} aria-hidden className="shrink-0 text-accent" />
                <a
                  href={map}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`Xem bản đồ ${branch.name} (mở tab mới)`}
                  className={`font-semibold hover:text-accent ${FOCUS}`}
                >
                  Xem bản đồ
                </a>
              </li>
            )}
          </ul>

          <BranchDoctors vets={vets} state={vetsState} />
        </div>
        <OpeningHours branch={branch} />
      </div>
    </article>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function BranchesPage() {
  const branches = usePublicBranches()
  // Một lần cho cả trang rồi nhóm theo chi nhánh, thay vì mỗi thẻ một request.
  const vets = usePublicVets()

  useEffect(() => {
    const previous = document.title
    document.title = 'Chi nhánh | PetCare'
    return () => {
      document.title = previous
    }
  }, [])

  const vetsByBranch = useMemo(() => {
    const map = new Map<number, PublicVet[]>()
    for (const v of vets.data ?? []) map.set(v.branchId, [...(map.get(v.branchId) ?? []), v])
    return map
  }, [vets.data])
  const vetsState: VetsState = { loading: vets.isPending, failed: vets.isError, retry: () => void vets.refetch() }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Chi nhánh"
        title="Hệ thống chi nhánh"
        subtitle="Gọi trực tiếp số điện thoại chi nhánh để hỏi lịch khám, lưu trú hoặc cấp cứu."
        breadcrumbs={[{ label: 'Chi nhánh' }]}
        actions={[{ label: 'Đặt lịch khám', href: '/booking' }]}
      />

      <div className="mx-auto max-w-[1100px] px-5 pt-12 sm:px-8">
        {branches.isError ? (
          <LoadError retrying={branches.isFetching} onRetry={() => void branches.refetch()} />
        ) : branches.isPending ? (
          <BranchesSkeleton />
        ) : branches.data.length === 0 ? (
          <EmptyNotice />
        ) : (
          <div className="grid gap-6">
            {branches.data.map((b) => (
              <BranchCard key={b.branchId} branch={b} vets={vetsByBranch.get(b.branchId) ?? []} vetsState={vetsState} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
