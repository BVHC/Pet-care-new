import { useState } from 'react'
import {
  Calendar,
  CalendarClock,
  Clock,
  Dog,
  MapPin,
  Package,
  Phone,
  RefreshCw,
  Syringe,
  X,
} from 'lucide-react'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Types.
   ================================================================ */

type ApptStatus = 'upcoming' | 'completed' | 'cancelled'
type ApptService = 'checkup' | 'spa' | 'vaccination' | 'hotel' | 'grooming'

interface Appointment {
  id: string
  petName: string
  petType: 'dog' | 'cat'
  petImage: string
  service: ApptService
  serviceLabel: string
  date: string
  time: string
  branch: string
  address: string
  phone: string
  status: ApptStatus
  queueNumber?: number
  notes?: string
  price: number
}

/* ================================================================
   Mock data.
   ================================================================ */

const MOCK_APPOINTMENTS: Appointment[] = [
  {
    id: 'a1',
    petName: 'Milo',
    petType: 'dog',
    petImage: '/imgs/hero-dog-clean.png',
    service: 'checkup',
    serviceLabel: 'Khám sức khỏe định kỳ',
    date: '25/09/2026',
    time: '10:00',
    branch: 'PetCare Nguyễn Trãi',
    address: '123 Nguyễn Trãi, Q.1, TP.HCM',
    phone: '028 3822 1234',
    status: 'upcoming',
    queueNumber: 3,
    notes: 'Pet đang dùng thuốc kháng sinh — thông báo BS trước khi khám.',
    price: 200_000,
  },
  {
    id: 'a2',
    petName: 'Luna',
    petType: 'cat',
    petImage: '/imgs/prod3.jpg',
    service: 'spa',
    serviceLabel: 'Spa & Grooming cao cấp',
    date: '28/09/2026',
    time: '14:30',
    branch: 'PetCare Quận 7',
    address: '456 Đường 9, Q.7, TP.HCM',
    phone: '028 3822 5678',
    status: 'upcoming',
    queueNumber: 7,
    price: 450_000,
  },
  {
    id: 'a3',
    petName: 'Milo',
    petType: 'dog',
    petImage: '/imgs/hero-dog-clean.png',
    service: 'vaccination',
    serviceLabel: 'Tiêm phòng dại hàng năm',
    date: '15/08/2026',
    time: '09:00',
    branch: 'PetCare Nguyễn Trãi',
    address: '123 Nguyễn Trãi, Q.1, TP.HCM',
    phone: '028 3822 1234',
    status: 'completed',
    price: 150_000,
  },
  {
    id: 'a4',
    petName: 'Luna',
    petType: 'cat',
    petImage: '/imgs/prod3.jpg',
    service: 'grooming',
    serviceLabel: 'Cắt tỉa lông mặt',
    date: '10/08/2026',
    time: '11:00',
    branch: 'PetCare Quận 3',
    address: '789 Hoàng Sa, Q.3, TP.HCM',
    phone: '028 3822 9012',
    status: 'completed',
    price: 180_000,
  },
  {
    id: 'a5',
    petName: 'Milo',
    petType: 'dog',
    petImage: '/imgs/hero-dog-clean.png',
    service: 'hotel',
    serviceLabel: 'Lưu trú 2 đêm',
    date: '01/08/2026',
    time: '08:00',
    branch: 'PetCare Tân Bình',
    address: '321 Cộng Hòa, Tân Bình, TP.HCM',
    phone: '028 3822 3456',
    status: 'cancelled',
    notes: 'Hủy do thay đổi kế hoạch.',
    price: 600_000,
  },
]

const TABS = [
  { id: 'upcoming' as const,   label: 'Sắp tới',       count: MOCK_APPOINTMENTS.filter(x => x.status === 'upcoming').length },
  { id: 'completed' as const, label: 'Đã hoàn thành', count: MOCK_APPOINTMENTS.filter(x => x.status === 'completed').length },
  { id: 'cancelled' as const, label: 'Đã hủy',        count: MOCK_APPOINTMENTS.filter(x => x.status === 'cancelled').length },
]

const fmtVnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'

const statusConfig = {
  upcoming:  { label: 'Sắp tới',      cls: 'bg-blue-100 text-blue-700',    dot: 'bg-blue-500' },
  completed: { label: 'Hoàn thành',   cls: 'bg-emerald-100 text-emerald-700', dot: 'bg-emerald-500' },
  cancelled: { label: 'Đã hủy',       cls: 'bg-gray-100 text-gray-600',   dot: 'bg-gray-400' },
}

/* ================================================================
   Reschedule modal.
   ================================================================ */

function RescheduleModal({ appt, onClose, onConfirm }: {
  appt: Appointment
  onClose: () => void
  onConfirm: (date: string, time: string) => void
}) {
  const [date, setDate] = useState(appt.date)
  const [time, setTime] = useState(appt.time)

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="w-full max-w-md overflow-hidden rounded-3xl border border-(--color-border-default) bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]">
        <div className="flex items-center justify-between border-b border-(--color-border-default) px-6 py-4">
          <h2 className="font-friendly text-lg font-extrabold text-(--color-text-primary)">Đổi lịch hẹn</h2>
          <button onClick={onClose} className="text-(--color-text-secondary) hover:text-(--color-text-primary)"><X size={20} /></button>
        </div>
        <div className="p-6 space-y-4">
          <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-[13px]">
            <div className="font-bold text-(--color-text-primary)">{appt.serviceLabel}</div>
            <div className="mt-1 flex items-center gap-2 text-(--color-text-secondary)">
              <img src={appt.petImage} alt={appt.petName} className="h-6 w-6 rounded-full object-cover" />
              <span>{appt.petName} · {appt.branch}</span>
            </div>
          </div>
          <div>
            <label className="mb-1 block text-[12.5px] font-bold text-(--color-text-primary)">Ngày mới</label>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)}
              className="w-full rounded-xl border border-(--color-border-default) bg-white px-3.5 py-2.5 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)" />
          </div>
          <div>
            <label className="mb-1 block text-[12.5px] font-bold text-(--color-text-primary)">Giờ mới</label>
            <input type="time" value={time} onChange={(e) => setTime(e.target.value)}
              className="w-full rounded-xl border border-(--color-border-default) bg-white px-3.5 py-2.5 text-[13.5px] text-(--color-text-primary) outline-none focus:border-(--color-accent) focus:ring-2 focus:ring-(--color-accent-soft)" />
          </div>
          <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-[12px] text-amber-800">
            Việc đổi lịch có thể ảnh hưởng đến số thứ tự queue. Vui lòng đến đúng giờ mới để được ưu tiên.
          </div>
          <div className="flex gap-2">
            <button onClick={onClose} className="flex-1 rounded-xl border border-(--color-border-default) py-2.5 text-[13px] font-bold text-(--color-text-secondary) transition-colors hover:bg-(--color-surface-2)">Hủy</button>
            <button onClick={() => { onConfirm(date, time); onClose() }}
              className="flex-1 rounded-xl bg-accent py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)">Xác nhận đổi lịch</button>
          </div>
        </div>
      </div>
    </div>
  )
}

/* ================================================================
   Cancel modal.
   ================================================================ */

function CancelModal({ appt, onClose, onConfirm }: {
  appt: Appointment
  onClose: () => void
  onConfirm: () => void
}) {
  const [reason, setReason] = useState('')
  const reasons = [
    'Thay đổi kế hoạch',
    'Pet không khỏe',
    'Đặt nhầm lịch',
    'Dịch vụ không còn cần thiết',
    'Khác',
  ]

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="w-full max-w-md overflow-hidden rounded-3xl border border-(--color-border-default) bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]">
        <div className="flex items-center justify-between border-b border-(--color-border-default) px-6 py-4">
          <h2 className="font-friendly text-lg font-extrabold text-(--color-text-primary)">Hủy lịch hẹn</h2>
          <button onClick={onClose} className="text-(--color-text-secondary) hover:text-(--color-text-primary)"><X size={20} /></button>
        </div>
        <div className="p-6 space-y-4">
          <div className="rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-[13px]">
            <div className="font-bold text-(--color-text-primary)">{appt.serviceLabel}</div>
            <div className="mt-1 flex items-center gap-2 text-(--color-text-secondary)">
              <img src={appt.petImage} alt={appt.petName} className="h-6 w-6 rounded-full object-cover" />
              <span>{appt.petName} · {appt.date} lúc {appt.time}</span>
            </div>
          </div>
          <div>
            <label className="mb-2 block text-[12.5px] font-bold text-(--color-text-primary)">Lý do hủy</label>
            <div className="space-y-1.5">
              {reasons.map((r) => (
                <label key={r} className={`flex cursor-pointer items-center gap-2.5 rounded-xl border px-3 py-2.5 text-[13px] transition-all ${reason === r ? 'border-(--color-accent) bg-(--color-accent-soft)' : 'border-(--color-border-default) hover:bg-(--color-surface-2)'}`}>
                  <input type="radio" name="cancel-reason" value={r} checked={reason === r} onChange={() => setReason(r)}
                    className="accent-(--color-accent)" />
                  <span className={reason === r ? 'font-bold text-accent' : 'text-(--color-text-primary)'}>{r}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-[12px] text-blue-800">
            Lưu ý: với đơn hàng đã thanh toán, yêu cầu hoàn tiền sẽ được xử lý trong 3–5 ngày làm việc theo chính sách hoàn tiền của PetCare.
          </div>
          <div className="flex gap-2">
            <button onClick={onClose} className="flex-1 rounded-xl border border-(--color-border-default) py-2.5 text-[13px] font-bold text-(--color-text-secondary) transition-colors hover:bg-(--color-surface-2)">Giữ lịch</button>
            <button onClick={() => { if (reason) { onConfirm(); onClose() } }}
              disabled={!reason} className="flex-1 rounded-xl bg-red-600 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50">Xác nhận hủy</button>
          </div>
        </div>
      </div>
    </div>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function AppointmentsPage() {
  const [tab, setTab] = useState<'upcoming' | 'completed' | 'cancelled'>('upcoming')
  const [appts, setAppts] = useState(MOCK_APPOINTMENTS)
  const [rescheduleAppt, setRescheduleAppt] = useState<Appointment | null>(null)
  const [cancelAppt, setCancelAppt] = useState<Appointment | null>(null)

  const filtered = appts.filter((a) => a.status === tab)

  const handleReschedule = (_appt: Appointment, date: string, time: string) => {
    setAppts((prev) => prev.map((a) => a.id === _appt.id ? { ...a, date, time } : a))
  }

  const handleCancel = (appt: Appointment) => {
    setAppts((prev) => prev.map((a) => a.id === appt.id ? { ...a, status: 'cancelled' } : a))
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Lịch hẹn của tôi"
        title="Quản lý lịch hẹn & Queue"
        subtitle="Xem, đổi lịch hoặc hủy lịch hẹn dịch vụ cho thú cưng. Nhận số thứ tự và đến đúng giờ để được phục vụ nhanh nhất."
        breadcrumbs={[{ label: 'Lịch hẹn' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="appointments" />

          <div className="min-w-0 space-y-6">
            {/* Tabs */}
            <div className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-(--color-border-default) bg-white p-1.5 shadow-[0_1px_2px_rgba(56,36,23,0.06)]">
              {TABS.map((t) => {
                const active = tab === t.id
                return (
                  <button key={t.id} onClick={() => setTab(t.id)}
                    className={`flex shrink-0 items-center gap-2 rounded-xl px-4 py-2.5 text-[13px] font-bold transition-all ${
                      active
                        ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                        : 'text-(--color-text-secondary) hover:bg-(--color-surface-2) hover:text-(--color-text-primary)'
                    }`}>
                    {t.label}
                    <span className={`rounded-full px-2 py-0.5 text-[10.5px] font-bold ${
                      active ? 'bg-white/20 text-white' : 'bg-(--color-surface-2) text-(--color-text-secondary)'
                    }`}>{t.count}</span>
                  </button>
                )
              })}
            </div>

            {/* List */}
            {filtered.length === 0 ? (
              <div className="rounded-3xl border border-dashed border-(--color-border-default) bg-white p-16 text-center">
                <CalendarClock size={48} className="mx-auto mb-4 text-(--color-text-secondary) opacity-30" />
                <p className="text-[15px] font-bold text-(--color-text-secondary)">Không có lịch hẹn nào</p>
                <p className="mt-1 text-[13px] text-(--color-text-secondary)">Đặt lịch ngay để được phục vụ tại PetCare!</p>
                <button className="mt-5 inline-flex items-center gap-1.5 rounded-full bg-accent px-5 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)">
                  <Calendar size={14} /> Đặt lịch khám ngay
                </button>
              </div>
            ) : (
              <div className="space-y-4">
                {filtered.map((appt) => {
                  const sc = statusConfig[appt.status]
                  return (
                    <div key={appt.id} className="overflow-hidden rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                      {/* Header */}
                      <div className="flex items-center justify-between border-b border-(--color-border-default) bg-(--color-surface-2) px-5 py-3">
                        <div className="flex items-center gap-2">
                          <span className={`flex h-2 w-2 rounded-full ${sc.dot}`} />
                          <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ${sc.cls}`}>{sc.label}</span>
                        </div>
                        <span className="text-[12px] font-bold text-accent">#{appt.id.toUpperCase()}</span>
                      </div>

                      <div className="p-5">
                        <div className="flex flex-wrap items-start gap-4">
                          {/* Pet info */}
                          <img src={appt.petImage} alt={appt.petName} className="h-14 w-14 shrink-0 rounded-2xl object-cover" />
                          <div className="min-w-0 flex-1">
                            <h3 className="text-[15px] font-bold text-(--color-text-primary)">{appt.serviceLabel}</h3>
                            <div className="mt-1 flex items-center gap-1.5 text-[12.5px] text-(--color-text-secondary)">
                              {appt.petType === 'dog' ? <Dog size={12} /> : <Syringe size={12} />}
                              <span>{appt.petName}</span>
                            </div>
                            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[12.5px]">
                              <span className="flex items-center gap-1 text-(--color-text-secondary)">
                                <Calendar size={13} /> {appt.date}
                              </span>
                              <span className="flex items-center gap-1 text-(--color-text-secondary)">
                                <Clock size={13} /> {appt.time}
                              </span>
                              <span className="flex items-center gap-1 font-bold text-(--color-text-primary)">
                                <Package size={13} /> {fmtVnd(appt.price)}
                              </span>
                            </div>
                          </div>

                          {/* Queue ticket */}
                          {appt.queueNumber && (
                            <div className="flex flex-col items-center rounded-2xl border-2 border-dashed border-(--color-accent) bg-(--color-accent-soft) px-5 py-3 text-center">
                              <span className="text-[10px] font-bold uppercase tracking-wider text-accent">Số thứ tự</span>
                              <span className="font-friendly text-3xl font-black text-accent">{appt.queueNumber}</span>
                              <span className="text-[10px] text-accent">Queue Ticket</span>
                            </div>
                          )}
                        </div>

                        {/* Branch info */}
                        <div className="mt-4 flex flex-wrap items-start gap-x-6 gap-y-1 rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 text-[12.5px] text-(--color-text-secondary)">
                          <span className="flex items-center gap-1.5">
                            <MapPin size={13} className="shrink-0" />
                            <span>{appt.branch} — {appt.address}</span>
                          </span>
                          <span className="flex items-center gap-1.5">
                            <Phone size={13} className="shrink-0" />
                            <span>{appt.phone}</span>
                          </span>
                        </div>

                        {appt.notes && (
                          <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-[12.5px] text-amber-800">
                            <strong>Ghi chú:</strong> {appt.notes}
                          </div>
                        )}

                        {/* Actions */}
                        {appt.status === 'upcoming' && (
                          <div className="mt-4 flex flex-wrap gap-2">
                            <button onClick={() => setRescheduleAppt(appt)}
                              className="inline-flex items-center gap-1.5 rounded-xl border border-(--color-border-default) px-4 py-2 text-[12.5px] font-bold text-(--color-text-secondary) transition-colors hover:border-(--color-accent) hover:text-accent">
                              <RefreshCw size={13} /> Đổi lịch
                            </button>
                            <button onClick={() => setCancelAppt(appt)}
                              className="inline-flex items-center gap-1.5 rounded-xl border border-red-200 px-4 py-2 text-[12.5px] font-bold text-red-600 transition-colors hover:bg-red-50">
                              <X size={13} /> Hủy lịch
                            </button>
                            <button className="inline-flex items-center gap-1.5 rounded-xl bg-accent px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-(--color-accent-hover)">
                              <CalendarClock size={13} /> Đặt lại nhắc nhở
                            </button>
                          </div>
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

      {rescheduleAppt && (
        <RescheduleModal
          appt={rescheduleAppt}
          onClose={() => setRescheduleAppt(null)}
          onConfirm={(date, time) => handleReschedule(rescheduleAppt, date, time)}
        />
      )}
      {cancelAppt && (
        <CancelModal
          appt={cancelAppt}
          onClose={() => setCancelAppt(null)}
          onConfirm={() => handleCancel(cancelAppt)}
        />
      )}
    </div>
  )
}
