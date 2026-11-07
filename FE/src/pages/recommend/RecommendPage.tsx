import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronRight, Heart, PawPrint, Plus, Sparkles } from 'lucide-react'
import { PetAvatar } from '../../components/customer/pets/pet-presentation'
import { useMyPets } from '../../domains/pets/hooks/useCustomerPet'
import { SPECIES_LABEL } from '../../domains/pets/labels'
import type { PetResponse } from '../../domains/pets/types'
import { plainMessage, toApiError } from '../../shared/api/api-error'
import { useRecommendations } from '../../shared/hooks/useAi'
import { useAccount } from '../../shared/stores/session.store'
import type { RecoGroup, RecommendedItem } from '../../shared/types/ai'
import { vnDate } from '../../shared/utils/vn-time'
import styles from './RecommendPage.module.css'

// UC92: gợi ý dịch vụ / sản phẩm theo thú (GET /me/ai/pets/{petId}/recommendations). Chỉ khách hàng; lý do sinh từ mẫu, không qua LLM (BR-AI-22).

const vnd = new Intl.NumberFormat('vi-VN')
const GROUPS: { key: RecoGroup; title: string; cta: string; to: (i: RecommendedItem) => string }[] = [
  { key: 'GROOMING', title: 'Dịch vụ thẩm mỹ', cta: 'Đặt lịch', to: () => '/booking' },
  { key: 'BOARDING', title: 'Lưu trú', cta: 'Xem lưu trú', to: () => '/hotel' },
  { key: 'PRODUCT', title: 'Sản phẩm', cta: 'Xem sản phẩm', to: (i) => `/shop/${i.itemId}` },
]
const BTN = 'mt-4 inline-flex rounded-full bg-accent px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)'

/** Giá theo BR-CK-02: "Từ X đ"; loại chuồng thêm đơn vị `/ đêm`. */
const priceLabel = (i: RecommendedItem) => `${i.priceIsFrom ? 'Từ ' : ''}${vnd.format(i.price)} đ${i.priceUnit ? ` / ${i.priceUnit}` : ''}`

/** Tuổi từ ngày sinh: dưới 2 năm tính theo tháng. */
function ageLabel(pet: PetResponse): string | null {
  if (!pet.birthDate) return null
  const [by, bm, bd] = pet.birthDate.split('-').map(Number)
  const [ty, tm, td] = vnDate().split('-').map(Number)
  const months = (ty - by) * 12 + (tm - bm) - (td < bd ? 1 : 0)
  if (months < 0) return null
  const text = months < 24 ? `${months} tháng` : `${Math.floor(months / 12)} tuổi`
  return pet.birthDateEstimated ? `khoảng ${text}` : text
}

function EmptyBox({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className={styles.empty}>
      <Sparkles size={40} strokeWidth={1.5} className="text-(--color-border-strong)" aria-hidden />
      <h2 className={styles.emptyTitle}>{title}</h2>
      <div className={styles.emptyDesc}>{children}</div>
    </div>
  )
}

function ItemCard({ item, cta, to }: { item: RecommendedItem; cta: string; to: string }) {
  return (
    <li className={styles.card}>
      <div className={styles.cardMedia}>
        {item.imageUrl ? (
          <img src={item.imageUrl} alt="" loading="lazy" />
        ) : (
          <div aria-hidden className="flex h-full items-center justify-center bg-(--color-surface-2) text-(--text-tertiary)">
            <PawPrint className="h-10 w-10" />
          </div>
        )}
        <span className={styles.cardBadge}>Phù hợp {Math.round(item.score)}%</span>
      </div>
      <div className={styles.cardBody}>
        <h3 className={styles.cardName}>{item.name}</h3>
        <ul className="mb-2 list-disc space-y-0.5 pl-4 text-[12px] text-(--color-text-secondary)">
          {item.reasons.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
        <div className={styles.cardFooter}>
          <span className={styles.cardPrice}>{priceLabel(item)}</span>
        </div>
        <Link to={to} className="mt-2 text-[12.5px] font-bold text-accent underline-offset-4 hover:underline">
          {cta} →
        </Link>
      </div>
    </li>
  )
}

function Recommendations({ pet }: { pet: PetResponse }) {
  const q = useRecommendations(pet.petId)
  if (q.isError) {
    return (
      <EmptyBox title="Chưa tải được gợi ý">
        <p role="alert">{plainMessage(toApiError(q.error))}</p>
        <button type="button" className={BTN} onClick={() => void q.refetch()}>
          Thử lại
        </button>
      </EmptyBox>
    )
  }
  if (!q.data) return <p role="status" className="py-10 text-center text-(--color-text-secondary)">Đang tính gợi ý cho {pet.name}…</p>
  if (q.data.items.length === 0) return <EmptyBox title={`Chưa có gợi ý phù hợp cho ${pet.name}`}>Khi có thêm dịch vụ và sản phẩm phù hợp, gợi ý sẽ hiện ở đây.</EmptyBox>
  return (
    <div className="space-y-8">
      {GROUPS.map((g) => {
        const items = q.data.items.filter((i) => i.group === g.key)
        return (
          items.length > 0 && (
            <section key={g.key} aria-labelledby={`reco-${g.key}`}>
              <h2 id={`reco-${g.key}`} className="mb-3 font-friendly text-[18px] font-extrabold text-(--color-text-primary)">
                {g.title}
              </h2>
              <ul className={styles.grid}>
                {items.map((i) => (
                  <ItemCard key={`${i.itemType}-${i.itemId}`} item={i} cta={g.cta} to={g.to(i)} />
                ))}
              </ul>
            </section>
          )
        )
      })}
    </div>
  )
}

function CustomerArea() {
  const petsQuery = useMyPets()
  // Khách chờ liên kết hồ sơ (BR-TK-19) nhận lỗi 400 ở /me/pets: coi như chưa có thú, như mock AI.
  const pets = (petsQuery.data ?? []).filter((p) => !p.deceasedOn)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const selected = pets.find((p) => p.petId === selectedId) ?? pets[0] ?? null

  if (petsQuery.isLoading) return <p role="status" className="py-20 text-center text-(--color-text-secondary)">Đang tải thú cưng…</p>
  if (!selected) {
    return (
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <EmptyBox title="Bạn chưa có thú cưng nào">
          <p>Thêm thú cưng của bạn để nhận gợi ý dịch vụ và sản phẩm phù hợp với loài, tuổi và cân nặng.</p>
          <Link to="/pets" className={BTN}>
            Thêm thú cưng
          </Link>
        </EmptyBox>
      </div>
    )
  }
  const age = ageLabel(selected)
  return (
    <div className={`${styles.page} mx-auto max-w-[1280px] px-0`}>
      <aside className={styles.sidebar} aria-label="Thú cưng của bạn">
        <div className={styles.sidebarHead}>
          <span className={styles.sidebarTitle}>
            <Heart size={14} aria-hidden />
            Thú cưng của bạn
          </span>
          <Link to="/pets" className="text-[11.5px] font-semibold text-accent underline-offset-4 hover:underline">
            Quản lý
          </Link>
        </div>
        <div className={styles.sidebarList}>
          {pets.map((pet) => (
            <button
              key={pet.petId}
              type="button"
              aria-pressed={pet.petId === selected.petId}
              onClick={() => setSelectedId(pet.petId)}
              className={`${styles.petItem} ${pet.petId === selected.petId ? styles.petItemActive : ''}`}
            >
              <PetAvatar pet={pet} />
              <div className={styles.petInfo}>
                <div className={styles.petName}>{pet.name}</div>
                <div className={styles.petMeta}>{[SPECIES_LABEL[pet.species], pet.breed].filter(Boolean).join(' · ')}</div>
              </div>
              <span className={styles.petDot} aria-hidden />
            </button>
          ))}
          <Link to="/pets" className={styles.addPetBtn}>
            <Plus size={15} aria-hidden />
            Thêm thú cưng
          </Link>
        </div>
      </aside>

      <div className={styles.featured}>
        <div className={styles.featuredInner}>
          <PetAvatar pet={selected} size="lg" />
          <div>
            <h1 className={styles.featuredName}>Gợi ý cho {selected.name}</h1>
            <div className={styles.featuredTags}>
              <span className={styles.featuredTag}>{SPECIES_LABEL[selected.species]}</span>
              {selected.breed && <span className={styles.featuredTag}>{selected.breed}</span>}
              {age && <span className={styles.featuredTag}>{age}</span>}
              {selected.currentWeightKg != null && <span className={styles.featuredTag}>{selected.currentWeightKg} kg</span>}
            </div>
          </div>
        </div>
      </div>

      <div className={styles.main}>
        <Recommendations key={selected.petId} pet={selected} />
      </div>
    </div>
  )
}

export function RecommendPage() {
  const account = useAccount()
  return (
    <div className="bg-(--color-surface-page) pb-24">
      <div className="mx-auto max-w-[1280px] px-5 py-4 sm:px-8">
        <nav aria-label="Đường dẫn" className="flex items-center gap-1.5 text-[12.5px] text-(--color-text-secondary)">
          <Link to="/" className="underline-offset-4 transition-colors hover:text-(--color-text-primary) hover:underline">
            Trang chủ
          </Link>
          <ChevronRight size={13} aria-hidden />
          <span className="font-semibold text-(--color-text-primary)">Gợi ý cho thú cưng</span>
        </nav>
      </div>

      {account?.role === 'CUSTOMER' ? (
        <CustomerArea />
      ) : (
        <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
          <EmptyBox title="Gợi ý dành cho khách hàng của PetCare">
            {account ? (
              <p>Tài khoản nhân viên không có thú cưng để gợi ý. Hãy đăng nhập bằng tài khoản khách hàng.</p>
            ) : (
              <>
                <p>Đăng nhập để xem dịch vụ và sản phẩm phù hợp với từng bé nhà bạn.</p>
                <Link to="/auth/login" state={{ from: '/recommend' }} className={BTN}>
                  Đăng nhập
                </Link>
              </>
            )}
          </EmptyBox>
        </div>
      )}
    </div>
  )
}
