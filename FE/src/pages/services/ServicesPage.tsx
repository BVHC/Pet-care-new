import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { BedDouble, Info, MapPin, PawPrint, RotateCw, Scissors, Stethoscope, WifiOff } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'
import { usePublicBranches, usePublicServiceList } from '@/shared/hooks/usePublicSite'
import { SPECIES_LABELS } from '@/shared/types/catalog'
import type { PublicService, ServiceGroup } from '@/shared/types/catalog'
import { branchNamesByIds, servicePriceLabel } from '@/shared/utils/public-format'

/* ================================================================
   Data.
   ================================================================ */

interface GroupMeta {
  group: ServiceGroup
  title: string
  intro: string
  icon: LucideIcon
  /** BR-CK-02: khám, tiêm, thẩm mỹ ghi chú chi phí thực tế phụ thuộc chỉ định của bác sĩ. */
  doctorNote?: boolean
}

const GROUPS: GroupMeta[] = [
  { group: 'MEDICAL', title: 'Khám & tiêm chủng', intro: 'Khám và tiêm phòng tại chi nhánh, do bác sĩ thú y thực hiện.', icon: Stethoscope, doctorNote: true },
  { group: 'GROOMING', title: 'Thẩm mỹ', intro: 'Tắm, cắt tỉa và chăm sóc vệ sinh cho thú cưng.', icon: Scissors, doctorNote: true },
  { group: 'BOARDING', title: 'Lưu trú', intro: 'Gửi thú cưng theo loại chuồng, tính giá theo đêm.', icon: BedDouble },
]

// Thẻ, chip, ô chờ dùng chung token của site (NewsPage); chữ phụ là nâu đậm 70% để đủ tương phản trên nền kem lẫn thẻ trắng.
const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]'
const MUTED = 'text-(--color-dark-gray)/70'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const PULSE = 'animate-pulse motion-reduce:animate-none bg-(--color-surface-2)'
const BUTTON = `inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover ${FOCUS}`

/** "Dành cho chó, tối đa 10 kg". */
function kennelLabel({ species, maxWeightKg }: NonNullable<PublicService['kennelType']>) {
  const who = species === 'OTHER' ? 'thú cưng khác' : SPECIES_LABELS[species].toLowerCase()
  return `Dành cho ${who}, tối đa ${maxWeightKg} kg`
}

/* ================================================================
   States.
   ================================================================ */

function ServicesSkeleton() {
  return (
    <div role="status" aria-label="Đang tải dịch vụ" className="grid gap-12">
      {[3, 2].map((rows) => (
        <div key={rows} aria-hidden className="grid gap-5 lg:grid-cols-[240px_1fr] lg:gap-10">
          <div className="space-y-3">
            <div className={`${PULSE} h-10 w-10 rounded-full`} />
            <div className={`${PULSE} h-7 w-44 rounded-lg`} />
            <div className={`${PULSE} h-4 w-56 max-w-full rounded-lg`} />
          </div>
          <div className={`${CARD} divide-y divide-(--color-border-default)`}>
            {Array.from({ length: rows }, (_, i) => (
              <div key={i} className="grid gap-3 p-6 sm:grid-cols-[1fr_auto]">
                <div className="space-y-2.5">
                  <div className={`${PULSE} h-5 w-40 rounded-lg`} />
                  <div className={`${PULSE} h-4 w-full max-w-sm rounded-lg`} />
                </div>
                <div className={`${PULSE} h-7 w-28 rounded-lg`} />
              </div>
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
      <p className="mt-4 font-friendly text-[20px] font-bold text-(--color-surface-dark)">Không tải được danh sách dịch vụ</p>
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
    <div role="group" aria-label="Lọc theo chi nhánh" className="mb-10 flex flex-wrap gap-2">
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

function ServiceRow({ service, branchNames }: { service: PublicService; branchNames: string[] }) {
  // Lưu trú không đặt qua lịch hẹn (BookingPage chỉ nhận khám/tiêm và thẩm mỹ).
  const bookable = service.group !== 'BOARDING'
  return (
    <li className="grid gap-4 p-5 sm:grid-cols-[1fr_auto] sm:gap-8 sm:p-6">
      <div className="min-w-0">
        <h3 className="font-friendly text-[19px] font-bold leading-snug text-(--color-surface-dark)">{service.name}</h3>
        {service.description && <p className={`mt-1 max-w-[60ch] text-[14px] leading-relaxed ${MUTED}`}>{service.description}</p>}
        {service.kennelType && (
          <p className="mt-2.5 inline-flex rounded-full bg-(--color-surface-2) px-3 py-1 text-[12.5px] font-semibold text-(--color-dark-gray)">
            {kennelLabel(service.kennelType)}
          </p>
        )}
        {branchNames.length > 0 && (
          <ul aria-label="Chi nhánh có dịch vụ" className="mt-3 flex flex-wrap gap-1.5">
            {branchNames.map((name) => (
              <li key={name} className="inline-flex items-center gap-1 rounded-full border border-(--color-border-default) px-2.5 py-0.5 text-[12px] text-(--color-dark-gray)">
                <MapPin size={11} aria-hidden className="text-accent" />
                {name}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="sm:text-right">
        <p className="whitespace-nowrap font-friendly text-[22px] font-extrabold leading-none text-accent">{servicePriceLabel(service)}</p>
        {bookable && (
          <Link to="/booking" aria-label={`Đặt lịch ${service.name}`} className={`${BUTTON} mt-3`}>
            Đặt lịch
          </Link>
        )}
      </div>
    </li>
  )
}

function GroupSection({ meta, services, branches }: { meta: GroupMeta; services: PublicService[]; branches: { branchId: number; name: string }[] }) {
  const headingId = `services-${meta.group}`
  return (
    <section aria-labelledby={headingId} className="grid gap-5 lg:grid-cols-[240px_1fr] lg:gap-10">
      <header className="lg:sticky lg:top-28 lg:self-start">
        <span className="flex h-11 w-11 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
          <meta.icon size={20} aria-hidden />
        </span>
        <h2 id={headingId} className="mt-3 font-friendly text-[26px] font-extrabold leading-tight text-(--color-surface-dark)">
          {meta.title}
        </h2>
        <p className={`mt-2 text-[14px] leading-relaxed ${MUTED}`}>{meta.intro}</p>
        {meta.doctorNote && (
          <p className={`mt-3 flex items-start gap-2 text-[13px] leading-relaxed ${MUTED}`}>
            <Info size={15} aria-hidden className="mt-0.5 shrink-0 text-accent" />
            Chi phí thực tế phụ thuộc chỉ định của bác sĩ.
          </p>
        )}
        {meta.group === 'BOARDING' && (
          <p className={`mt-3 text-[13px] leading-relaxed ${MUTED}`}>
            Đặt chỗ lưu trú qua số điện thoại chi nhánh.{' '}
            <Link to="/branches" className={`font-semibold text-accent underline underline-offset-4 hover:text-(--color-accent-hover) ${FOCUS}`}>
              Xem số điện thoại chi nhánh
            </Link>
          </p>
        )}
      </header>
      <ul className={`${CARD} divide-y divide-(--color-border-default) overflow-hidden`}>
        {services.map((s) => (
          <ServiceRow key={s.serviceId} service={s} branchNames={branchNamesByIds(s.branchIds, branches)} />
        ))}
      </ul>
    </section>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function ServicesPage() {
  const services = usePublicServiceList()
  const branches = usePublicBranches()
  const [branchId, setBranchId] = useState<number | null>(null)

  useEffect(() => {
    const previous = document.title
    document.title = 'Dịch vụ | PetCare'
    return () => {
      document.title = previous
    }
  }, [])

  const branchList = useMemo(() => branches.data ?? [], [branches.data])
  const all = useMemo(() => services.data ?? [], [services.data])
  // Lọc ở client: mỗi dịch vụ đã kèm `branchIds`, đổi chi nhánh không cần gọi API lại.
  const visible = useMemo(() => (branchId === null ? all : all.filter((s) => s.branchIds.includes(branchId))), [all, branchId])

  const failed = services.isError || branches.isError
  const loading = !failed && (services.isPending || branches.isPending)
  const retry = () => {
    if (services.isError) void services.refetch()
    if (branches.isError) void branches.refetch()
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Dịch vụ"
        title="Dịch vụ tại PetCare"
        subtitle="Khám, tiêm phòng, thẩm mỹ và lưu trú cho thú cưng, giá niêm yết thống nhất toàn chuỗi."
        breadcrumbs={[{ label: 'Dịch vụ' }]}
      />

      <div className="mx-auto max-w-[1100px] px-5 pt-12 sm:px-8">
        {failed ? (
          <LoadError retrying={services.isFetching || branches.isFetching} onRetry={retry} />
        ) : loading ? (
          <ServicesSkeleton />
        ) : all.length === 0 ? (
          <EmptyNotice title="Hiện chưa có dịch vụ nào được công bố." hint="Bạn có thể gọi trực tiếp chi nhánh để được tư vấn.">
            <Link to="/branches" className={BUTTON}>
              Xem chi nhánh để gọi hỏi
            </Link>
          </EmptyNotice>
        ) : (
          <>
            {branchList.length >= 2 && <BranchFilter branches={branchList} value={branchId} onChange={setBranchId} />}
            {visible.length === 0 ? (
              <EmptyNotice title="Chi nhánh này chưa có dịch vụ nào." hint="Chọn chi nhánh khác hoặc xem dịch vụ của cả chuỗi.">
                <button type="button" onClick={() => setBranchId(null)} className={BUTTON}>
                  Xem tất cả chi nhánh
                </button>
              </EmptyNotice>
            ) : (
              <div className="grid gap-14">
                {GROUPS.map((meta) => {
                  const items = visible.filter((s) => s.group === meta.group)
                  return items.length > 0 ? <GroupSection key={meta.group} meta={meta} services={items} branches={branchList} /> : null
                })}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
