import { useRef, useState } from 'react'
import {
  Activity,
  Calendar,
  Camera,
  Cat,
  Dog,
  Edit2,
  Plus,
  Stethoscope,
  Syringe,
  Trash2,
  X,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Types.
   ================================================================ */

interface Pet {
  id: string
  name: string
  type: 'dog' | 'cat'
  breed: string
  age: string
  weight: string
  image: string
}

interface PetForm {
  name: string
  type: 'dog' | 'cat'
  breed: string
  age: string
  weight: string
  image: string
}

interface PetOrder {
  id: string
  date: string
  service: string
  status: 'delivered' | 'processing' | 'cancelled'
  total: number
}

interface MedicalRecord {
  id: string
  date: string
  clinic: string
  diagnosis: string
  treatment: string
  vet: string
  notes: string
}

interface Vaccination {
  id: string
  name: string
  date: string
  nextDate: string
  batch: string
  status: 'done' | 'upcoming' | 'overdue'
}

/* ================================================================
   Mock data.
   ================================================================ */

const MOCK_PETS: Pet[] = [
  {
    id: 'p1',
    name: 'Milo',
    type: 'dog',
    breed: 'Golden Retriever',
    age: '3 tuổi',
    weight: '25 kg',
    image: '/imgs/hero-dog-clean.png',
  },
  {
    id: 'p2',
    name: 'Luna',
    type: 'cat',
    breed: 'Maine Coon',
    age: '2 tuổi',
    weight: '6 kg',
    image: '/imgs/prod3.jpg',
  },
]

const PET_ORDERS: Record<string, PetOrder[]> = {
  p1: [
    { id: 'o1', date: '20/08/2026', service: 'Tắm & Grooming', status: 'delivered', total: 350_000 },
    { id: 'o2', date: '15/08/2026', service: 'Khám sức khỏe định kỳ', status: 'delivered', total: 200_000 },
    { id: 'o3', date: '01/08/2026', service: 'Tiêm phòng dại', status: 'delivered', total: 150_000 },
    { id: 'o4', date: '28/07/2026', service: 'Cắt tỉa lông', status: 'cancelled', total: 0 },
  ],
  p2: [
    { id: 'o5', date: '18/08/2026', service: 'Tẩy giun', status: 'delivered', total: 120_000 },
    { id: 'o6', date: '05/08/2026', service: 'Khám tai', status: 'delivered', total: 100_000 },
  ],
}

const PET_MEDICAL: Record<string, MedicalRecord[]> = {
  p1: [
    {
      id: 'm1',
      date: '15/08/2026',
      clinic: 'PetCare Nguyễn Trãi',
      diagnosis: 'Viêm da nhẹ',
      treatment: 'Thuốc kháng sinh + kem bôi',
      vet: 'BS. Nguyễn Hoàng Nam',
      notes: 'Cần giữ vệ sinh chỗ ngủ, tránh gãi.',
    },
    {
      id: 'm2',
      date: '01/06/2026',
      clinic: 'PetCare Quận 7',
      diagnosis: 'Kiểm tra sức khỏe định kỳ — Khỏe',
      treatment: 'Không',
      vet: 'BS. Trần Minh Thu',
      notes: 'Cân nặng ổn định, lông bóng mượt.',
    },
  ],
  p2: [
    {
      id: 'm3',
      date: '05/08/2026',
      clinic: 'PetCare Nguyễn Trãi',
      diagnosis: 'Nhiễm nấm tai',
      treatment: 'Thuốc nhỏ tai + vệ sinh tai hàng ngày',
      vet: 'BS. Lê Thị Mai',
      notes: 'Tái khám sau 7 ngày.',
    },
  ],
}

const PET_VACCINATIONS: Record<string, Vaccination[]> = {
  p1: [
    { id: 'v1', name: 'Dại (Rabies)', date: '01/08/2026', nextDate: '01/08/2027', batch: 'RD-2026-0842', status: 'done' },
    { id: 'v2', name: '5 bệnh (5-in-1)', date: '15/03/2026', nextDate: '15/03/2027', batch: 'DHPP-2026-0312', status: 'done' },
    { id: 'v3', name: 'Leptospira', date: '15/03/2026', nextDate: '15/09/2026', batch: 'LEP-2026-0315', status: 'upcoming' },
  ],
  p2: [
    { id: 'v4', name: 'Dại (Rabies)', date: '10/06/2026', nextDate: '10/06/2027', batch: 'RD-2026-0610', status: 'done' },
    { id: 'v5', name: '4 bệnh (4-in-1)', date: '10/06/2026', nextDate: '10/06/2027', batch: 'FVRCP-2026-0610', status: 'done' },
    { id: 'v6', name: 'FeLV (Leukemia)', date: '25/06/2026', nextDate: '25/06/2027', batch: 'FeLV-2026-0625', status: 'done' },
  ],
}

const EMPTY_FORM = { name: '', type: 'dog' as 'dog' | 'cat', breed: '', age: '', weight: '', image: '' }

/* ================================================================
   Tab definitions.
   ================================================================ */

const TABS = [
  { id: 'pets',       label: 'Danh sách',      icon: Activity },
  { id: 'orders',     label: 'Lịch sử đơn',   icon: Calendar },
  { id: 'medical',    label: 'Bệnh án',         icon: Stethoscope },
  { id: 'vaccination',label: 'Lịch tiêm',      icon: Syringe },
] as const

type TabId = typeof TABS[number]['id']

/* ================================================================
   Helpers.
   ================================================================ */

const fmtVnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'

const statusBadge = (s: PetOrder['status']) => {
  if (s === 'delivered') return { label: 'Hoàn thành', cls: 'bg-emerald-100 text-emerald-700' }
  if (s === 'processing') return { label: 'Đang xử lý', cls: 'bg-blue-100 text-blue-700' }
  return { label: 'Đã hủy', cls: 'bg-gray-100 text-gray-600' }
}

const vacBadge = (s: Vaccination['status']) => {
  if (s === 'done')     return { label: 'Đã tiêm',   cls: 'bg-emerald-100 text-emerald-700' }
  if (s === 'upcoming') return { label: 'Sắp tới',  cls: 'bg-blue-100 text-blue-700' }
  return { label: 'Quá hạn', cls: 'bg-red-100 text-red-700' }
}

/* ================================================================
   Pet form modal.
   ================================================================ */

function PetModal({
  editing,
  initial,
  onSave,
  onClose,
}: {
  editing: boolean
  initial: PetForm
  onSave: (data: PetForm) => void
  onClose: () => void
}) {
  const [form, setForm] = useState<PetForm>(initial)
  const [preview, setPreview] = useState<string>(initial.image)
  const fileRef = useRef<HTMLInputElement>(null)
  const set = (patch: Partial<PetForm>) => setForm((f) => ({ ...f, ...patch }))

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const url = URL.createObjectURL(file)
    setPreview(url)
    set({ image: url })
  }

  return (
    <div
      className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center"
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="w-full max-w-md overflow-hidden rounded-3xl border border-(--color-border-default) bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]">
        <div className="flex items-center justify-between border-b border-(--color-border-default) px-6 py-4">
          <h2 className="font-friendly text-lg font-extrabold text-(--color-text-primary)">
            {editing ? 'Sửa thú cưng' : 'Thêm thú cưng'}
          </h2>
          <button onClick={onClose} className="text-(--color-text-secondary) hover:text-(--color-text-primary)">
            <X size={20} />
          </button>
        </div>
        <form
          onSubmit={(e) => { e.preventDefault(); onSave(form) }}
          className="p-6 space-y-4"
        >
          {/* Avatar upload */}
          <div className="flex flex-col items-center">
            <div
              className="relative h-24 w-24 cursor-pointer overflow-hidden rounded-full border-2 border-dashed border-(--color-border-default) bg-(--color-surface-2) transition-colors hover:border-(--color-accent)"
              onClick={() => fileRef.current?.click()}
            >
              {preview ? (
                <img src={preview} alt="Pet preview" className="h-full w-full object-cover" />
              ) : (
                <div className="flex h-full w-full items-center justify-center">
                  <Camera size={28} className="text-(--color-text-secondary) opacity-50" />
                </div>
              )}
              <div className="absolute inset-0 flex items-end justify-center bg-black/40 opacity-0 transition-opacity hover:opacity-100">
                <span className="mb-1.5 text-[10px] font-bold text-white">Đổi ảnh</span>
              </div>
            </div>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={handleFileChange}
            />
            <p className="mt-2 text-[11px] text-(--color-text-secondary)">JPG, PNG · tối đa 5 MB</p>
          </div>

          <div>
            <label className="mb-1 block text-[12.5px] font-bold text-(--color-text-primary)">Tên</label>
            <input
              value={form.name}
              onChange={(e) => set({ name: e.target.value })}
              placeholder="VD: Milo"
              className="w-full rounded-xl border border-(--color-border-default) bg-white px-3.5 py-2.5 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-[12.5px] font-bold text-(--color-text-primary)">Loại</label>
            <div className="flex gap-2">
              {(['dog', 'cat'] as const).map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => set({ type: t })}
                  className={`flex flex-1 items-center justify-center gap-1.5 rounded-xl border py-2.5 text-[13px] font-bold transition-all ${
                    form.type === t
                      ? 'border-(--color-accent) bg-(--color-accent-soft) text-accent'
                      : 'border-(--color-border-default) text-(--color-text-secondary) hover:bg-(--color-surface-2)'
                  }`}
                >
                  {t === 'dog' ? <Dog size={15} /> : <Cat size={15} />}
                  {t === 'dog' ? 'Chó' : 'Mèo'}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label className="mb-1 block text-[12.5px] font-bold text-(--color-text-primary)">Giống</label>
            <input
              value={form.breed}
              onChange={(e) => set({ breed: e.target.value })}
              placeholder="VD: Golden Retriever"
              className="w-full rounded-xl border border-(--color-border-default) bg-white px-3.5 py-2.5 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1 block text-[12.5px] font-bold text-(--color-text-primary)">Tuổi</label>
              <input
                value={form.age}
                onChange={(e) => set({ age: e.target.value })}
                placeholder="VD: 3 tuổi"
                className="w-full rounded-xl border border-(--color-border-default) bg-white px-3.5 py-2.5 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)"
              />
            </div>
            <div>
              <label className="mb-1 block text-[12.5px] font-bold text-(--color-text-primary)">Cân nặng</label>
              <input
                value={form.weight}
                onChange={(e) => set({ weight: e.target.value })}
                placeholder="VD: 25 kg"
                className="w-full rounded-xl border border-(--color-border-default) bg-white px-3.5 py-2.5 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)"
              />
            </div>
          </div>
          <div className="flex gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 rounded-xl border border-(--color-border-default) py-2.5 text-[13px] font-bold text-(--color-text-secondary) transition-colors hover:bg-(--color-surface-2)"
            >
              Hủy
            </button>
            <button
              type="submit"
              className="flex-1 rounded-xl bg-accent py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)"
            >
              {editing ? 'Lưu thay đổi' : 'Thêm thú cưng'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function PetsPage() {
  const [tab, setTab] = useState<TabId>('pets')
  const [pets, setPets] = useState<Pet[]>(MOCK_PETS)
  const [selectedPet, setSelectedPet] = useState<Pet>(MOCK_PETS[0])
  const [showModal, setShowModal] = useState(false)
  const [editingPet, setEditingPet] = useState<Pet | null>(null)

  const handleSave = (data: PetForm) => {
    if (editingPet) {
      setPets((prev) => prev.map((p) => p.id === editingPet.id ? { ...p, ...data } : p))
      setSelectedPet((prev) => prev.id === editingPet.id ? { ...prev, ...data } : prev)
    } else {
      const newPet: Pet = {
        id: `p${Date.now()}`,
        ...data,
        image: data.image || (data.type === 'dog' ? '/imgs/hero-dog-clean.png' : '/imgs/prod3.jpg'),
      }
      setPets((prev) => [...prev, newPet])
      setSelectedPet(newPet)
    }
    setShowModal(false)
    setEditingPet(null)
  }

  const handleDelete = (id: string) => {
    if (!confirm('Xóa thú cưng này?')) return
    setPets((prev) => {
      const next = prev.filter((p) => p.id !== id)
      if (selectedPet.id === id && next.length > 0) setSelectedPet(next[0])
      return next
    })
  }

  const openAdd = () => { setEditingPet(null); setShowModal(true) }
  const openEdit = (p: Pet) => { setEditingPet(p); setShowModal(true) }

  const orders  = PET_ORDERS[selectedPet.id]     ?? []
  const records = PET_MEDICAL[selectedPet.id]     ?? []
  const vacs    = PET_VACCINATIONS[selectedPet.id] ?? []

  const ordersTab = () => (
    <div>
      <div className="mb-5 flex items-center gap-3">
        <img src={selectedPet.image} alt={selectedPet.name} className="h-12 w-12 rounded-full object-cover" />
        <div>
          <div className="text-[14px] font-bold text-(--color-text-primary)">{selectedPet.name}</div>
          <div className="text-[12px] text-(--color-text-secondary)">{selectedPet.breed}</div>
        </div>
      </div>
      {orders.length === 0 ? (
        <EmptyState icon={Calendar} msg="Chưa có đơn hàng nào cho thú cưng này." />
      ) : (
        <div className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
          <table className="w-full text-left text-[13px]">
            <thead className="bg-(--color-surface-2) text-[11.5px] font-bold uppercase tracking-wider text-(--color-text-secondary)">
              <tr>
                <th className="px-5 py-3.5">Ngày</th>
                <th className="px-5 py-3.5">Dịch vụ</th>
                <th className="px-5 py-3.5">Tổng</th>
                <th className="px-5 py-3.5">Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => {
                const b = statusBadge(o.status)
                return (
                  <tr key={o.id} className="border-t border-(--color-border-default)">
                    <td className="px-5 py-3.5 text-(--color-text-secondary)">{o.date}</td>
                    <td className="px-5 py-3.5 font-semibold text-(--color-text-primary)">{o.service}</td>
                    <td className="px-5 py-3.5 font-bold text-(--color-text-primary)">
                      {o.status === 'cancelled' ? '—' : fmtVnd(o.total)}
                    </td>
                    <td className="px-5 py-3.5">
                      <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ${b.cls}`}>{b.label}</span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )

  const medicalTab = () => (
    <div>
      <div className="mb-5 flex items-center gap-3">
        <img src={selectedPet.image} alt={selectedPet.name} className="h-12 w-12 rounded-full object-cover" />
        <div>
          <div className="text-[14px] font-bold text-(--color-text-primary)">{selectedPet.name}</div>
          <div className="text-[12px] text-(--color-text-secondary)">{selectedPet.breed}</div>
        </div>
      </div>
      {records.length === 0 ? (
        <EmptyState icon={Stethoscope} msg="Chưa có bệnh án nào cho thú cưng này." />
      ) : (
        <div className="space-y-4">
          {records.map((r) => (
            <div key={r.id} className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
              <div className="flex items-center justify-between border-b border-(--color-border-default) bg-(--color-surface-2) px-5 py-3">
                <div className="flex items-center gap-2 text-[12.5px] font-bold text-(--color-text-secondary)">
                  <Calendar size={14} /> {r.date}
                </div>
                <div className="flex items-center gap-2 text-[12px] text-(--color-text-secondary)">
                  <Stethoscope size={14} /> {r.clinic}
                </div>
              </div>
              <div className="p-5">
                <div className="mb-2">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-(--color-text-secondary)">Chẩn đoán</span>
                  <p className="mt-0.5 text-[14px] font-bold text-(--color-text-primary)">{r.diagnosis}</p>
                </div>
                <div className="mb-2">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-(--color-text-secondary)">Điều trị</span>
                  <p className="mt-0.5 text-[13px] text-(--color-text-primary)">{r.treatment}</p>
                </div>
                <div className="mb-2">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-(--color-text-secondary)">Bác sĩ</span>
                  <p className="mt-0.5 text-[13px] text-(--color-text-primary)">{r.vet}</p>
                </div>
                {r.notes && (
                  <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-[12.5px] text-amber-800">
                    <strong>Ghi chú:</strong> {r.notes}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )

  const vaccinationTab = () => (
    <div>
      <div className="mb-5 flex items-center gap-3">
        <img src={selectedPet.image} alt={selectedPet.name} className="h-12 w-12 rounded-full object-cover" />
        <div>
          <div className="text-[14px] font-bold text-(--color-text-primary)">{selectedPet.name}</div>
          <div className="text-[12px] text-(--color-text-secondary)">{selectedPet.breed}</div>
        </div>
      </div>
      {vacs.length === 0 ? (
        <EmptyState icon={Syringe} msg="Chưa có lịch tiêm nào cho thú cưng này." />
      ) : (
        <div className="space-y-4">
          {vacs.map((v) => {
            const b = vacBadge(v.status)
            return (
              <div key={v.id} className="flex items-center gap-4 overflow-hidden rounded-2xl border border-(--color-border-default) bg-white p-4 shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-(--color-accent-soft)">
                  <Syringe size={22} className="text-accent" />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-bold text-(--color-text-primary)">{v.name}</span>
                    <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ${b.cls}`}>{b.label}</span>
                  </div>
                  <div className="mt-1 flex flex-wrap gap-x-4 text-[12px] text-(--color-text-secondary)">
                    <span>Tiêm: <strong className="text-(--color-text-primary)">{v.date}</strong></span>
                    <span>Lần tiếp: <strong className="text-(--color-text-primary)">{v.nextDate}</strong></span>
                    <span>Batch: <code className="font-mono text-[11px]">{v.batch}</code></span>
                  </div>
                </div>
                {v.status === 'upcoming' && (
                  <div className="shrink-0">
                    <button className="rounded-full border border-(--color-accent) px-3.5 py-1.5 text-[12px] font-bold text-accent transition-colors hover:bg-(--color-accent-soft)">
                      Đặt lịch
                    </button>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )

  const petsTab = () => (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h2 className="font-friendly text-2xl font-extrabold text-(--color-text-primary)">
          Thú cưng ({pets.length})
        </h2>
        <button
          onClick={openAdd}
          className="inline-flex items-center gap-1.5 rounded-full bg-accent px-4 py-2 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)"
        >
          <Plus size={15} /> Thêm thú cưng
        </button>
      </div>

      {pets.length === 0 ? (
        <EmptyState icon={Activity} msg="Bạn chưa có thú cưng nào. Thêm thú cưng đầu tiên!" />
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {pets.map((p) => (
            <div key={p.id} className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
              <div className="relative h-36 overflow-hidden bg-(--color-surface-2)">
                <img src={p.image} alt={p.name} className="h-full w-full object-cover" />
                <div className="absolute right-2 top-2 flex gap-1.5">
                  <button
                    onClick={() => openEdit(p)}
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-white/90 text-(--color-text-secondary) shadow-sm hover:bg-white hover:text-accent"
                    aria-label={`Sửa ${p.name}`}
                  >
                    <Edit2 size={13} />
                  </button>
                  <button
                    onClick={() => handleDelete(p.id)}
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-white/90 text-(--color-text-secondary) shadow-sm hover:bg-white hover:text-red-500"
                    aria-label={`Xóa ${p.name}`}
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              </div>
              <div className="p-4">
                <div className="mb-2">
                  <h3 className="text-[15px] font-bold text-(--color-text-primary)">{p.name}</h3>
                  <p className="text-[12px] text-(--color-text-secondary)">{p.breed}</p>
                </div>
                <div className="mb-3 flex flex-wrap gap-2 text-[11.5px]">
                  <span className="inline-flex items-center gap-1 rounded-full bg-(--color-surface-2) px-2.5 py-0.5 font-medium text-(--color-text-secondary)">
                    {p.type === 'dog' ? <Dog size={10} /> : <Cat size={10} />}
                    {p.age}
                  </span>
                  <span className="inline-flex items-center gap-1 rounded-full bg-(--color-surface-2) px-2.5 py-0.5 font-medium text-(--color-text-secondary)">
                    <Activity size={10} /> {p.weight}
                  </span>
                </div>
                <button
                  onClick={() => setSelectedPet(p)}
                  className={`w-full rounded-xl border py-2 text-[12.5px] font-bold transition-all ${
                    selectedPet.id === p.id
                      ? 'border-(--color-accent) bg-accent text-white'
                      : 'border-(--color-border-default) text-(--color-text-secondary) hover:border-(--color-accent) hover:text-accent'
                  }`}
                >
                  {selectedPet.id === p.id ? 'Đang chọn' : 'Chọn thú cưng'}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Hồ sơ thú cưng"
        title="Quản lý thú cưng của bạn"
        subtitle="Xem thông tin, lịch sử đơn hàng, bệnh án và lịch tiêm phòng cho từng thú cưng."
        breadcrumbs={[{ label: 'Thú cưng' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="pets" />

          <div className="min-w-0 space-y-6">
            {/* Tab bar */}
            <div className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-(--color-border-default) bg-white p-1.5 shadow-[0_1px_2px_rgba(56,36,23,0.06)]">
              {TABS.map((t) => {
                const active = tab === t.id
                return (
                  <button
                    key={t.id}
                    onClick={() => setTab(t.id)}
                    className={`flex shrink-0 items-center gap-2 rounded-xl px-4 py-2.5 text-[13px] font-bold transition-all ${
                      active
                        ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                        : 'text-(--color-text-secondary) hover:bg-(--color-surface-2) hover:text-(--color-text-primary)'
                    }`}
                  >
                    <t.icon size={15} />
                    {t.label}
                  </button>
                )
              })}
            </div>

            {/* Tab content */}
            {tab === 'pets'       && petsTab()}
            {tab === 'orders'     && ordersTab()}
            {tab === 'medical'    && medicalTab()}
            {tab === 'vaccination'&& vaccinationTab()}
          </div>
        </div>
      </div>

      {showModal && (
        <PetModal
          editing={!!editingPet}
          initial={
            editingPet
              ? { name: editingPet.name, type: editingPet.type, breed: editingPet.breed, age: editingPet.age, weight: editingPet.weight, image: editingPet.image }
              : EMPTY_FORM
          }
          onSave={handleSave}
          onClose={() => { setShowModal(false); setEditingPet(null) }}
        />
      )}
    </div>
  )
}

function EmptyState({ icon: Icon, msg }: { icon: React.ElementType; msg: string }) {
  return (
    <div className="rounded-2xl border border-dashed border-(--color-border-default) bg-white p-12 text-center">
      <Icon size={36} className="mx-auto mb-3 text-(--color-text-secondary) opacity-40" />
      <p className="text-[13px] text-(--color-text-secondary)">{msg}</p>
    </div>
  )
}
