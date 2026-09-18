import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Calendar,
  Check,
  ChevronRight,
  ClipboardCheck,
  Clock,
  Cat,  Dog,
  MapPin,


  Scissors,
  Shield,
  Star,
  Stethoscope,
  Syringe,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import { DatePicker } from '@/components/customer/DatePicker'
import styles from './BookingPage.module.css'

/* ================================================================
   Data.
   ================================================================ */

const SERVICES = [
  {
    id: 'checkup',
    name: 'Khám tổng quát',
    desc: 'Kiểm tra sức khoẻ toàn diện',
    price: 150_000,
    icon: Stethoscope,
  },
  {
    id: 'vaccine',
    name: 'Tiêm phòng',
    desc: 'Vaccine phòng các bệnh phổ biến',
    price: 200_000,
    icon: Syringe,
  },
  {
    id: 'grooming',
    name: 'Cắt tỉa lông',
    desc: 'Làm đẹp, cắt móng, vệ sinh tai',
    price: 250_000,
    icon: Scissors,
  },
  {
    id: 'spa',
    name: 'Spa & tắm',
    desc: 'Tắm, dưỡng lông, massage thư giãn',
    price: 300_000,
    icon: Star,
  },
]

const TIME_SLOTS = [
  { time: '08:00', available: true },
  { time: '09:00', available: true },
  { time: '10:00', available: true },
  { time: '11:00', available: true },
  { time: '13:00', available: true },
  { time: '14:00', available: true },
  { time: '15:00', available: false },
  { time: '16:00', available: true },
  { time: '17:00', available: false },
]

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

/* ================================================================
   Success modal.
   ================================================================ */

function SuccessModal({
  service,
  petName,
  date,
  time,
  onClose,
}: {
  service: (typeof SERVICES)[0]
  petName: string
  date: string
  time: string
  onClose: () => void
}) {
  return (
    <div className={styles.successOverlay} role="dialog" aria-modal="true" aria-label="Đặt lịch thành công">
      <div className={styles.successCard}>
        <div className={styles.successIcon}>
          <Check size={36} strokeWidth={2.5} />
        </div>

        <h2 className={styles.successTitle}>Đặt lịch thành công!</h2>
        <p className={styles.successDesc}>
          Cảm ơn bạn đã tin tưởng PetCare. Đội ngũ bác sĩ sẽ liên hệ xác nhận trong vòng 30 phút.
        </p>

        <div className={styles.successDetails}>
          <div className={styles.successDetail}>
            <span className={styles.successDetailLabel}>Dịch vụ</span>
            <span className={styles.successDetailValue}>{service.name}</span>
          </div>
          <div className={styles.successDetail}>
            <span className={styles.successDetailLabel}>Thú cưng</span>
            <span className={styles.successDetailValue}>{petName}</span>
          </div>
          <div className={styles.successDetail}>
            <span className={styles.successDetailLabel}>Ngày & giờ</span>
            <span className={styles.successDetailValue}>{date} lúc {time}</span>
          </div>
          <div className={styles.successDetail}>
            <span className={styles.successDetailLabel}>Chi phí dự kiến</span>
            <span className={styles.successDetailValue}>{vnd.format(service.price)}</span>
          </div>
        </div>

        <div className={styles.successActions}>
          <Link to="/" className={styles.successSecondary} onClick={onClose}>
            Về trang chủ
          </Link>
          <button className={styles.successPrimary} onClick={onClose}>
            Đặt thêm lịch
          </button>
        </div>

        <button
          onClick={onClose}
          aria-label="Đóng"
          className="absolute top-4 right-4 rounded-full p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)] transition-colors"
        >
          <X size={18} />
        </button>
      </div>
    </div>
  )
}

/* ================================================================
   Main page.
   ================================================================ */

export function BookingPage() {
  const [selectedService, setSelectedService] = useState<string | null>(null)
  const [petType, setPetType] = useState<'dog' | 'cat'>('dog')
  const [petName, setPetName] = useState('')
  const [ownerName, setOwnerName] = useState('')
  const [phone, setPhone] = useState('')
  const [date, setDate] = useState('')
  const [selectedTime, setSelectedTime] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [submitted, setSubmitted] = useState(false)

  const service = SERVICES.find((s) => s.id === selectedService) ?? null

  const minDate = (() => {
    const d = new Date()
    d.setDate(d.getDate() + 1)
    return d.toISOString().split('T')[0]
  })()

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedService) {
      toast.error('Vui lòng chọn dịch vụ.')
      return
    }
    if (!petName.trim()) {
      toast.error('Vui lòng nhập tên thú cưng.')
      return
    }
    if (!ownerName.trim()) {
      toast.error('Vui lòng nhập họ tên chủ nuôi.')
      return
    }
    if (!phone.trim() || !/^[\d\s]{9,}$/.test(phone.replace(/\s/g, ''))) {
      toast.error('Vui lòng nhập số điện thoại hợp lệ.')
      return
    }
    if (!date) {
      toast.error('Vui lòng chọn ngày đặt lịch.')
      return
    }
    if (!selectedTime) {
      toast.error('Vui lòng chọn giờ hẹn.')
      return
    }

    setSubmitted(true)
  }

  const handleClose = () => {
    setSubmitted(false)
    setSelectedService(null)
    setPetName('')
    setOwnerName('')
    setPhone('')
    setDate('')
    setSelectedTime(null)
    setNote('')
  }

  return (
    <div className="bg-[var(--color-surface-page)] pb-24">
      {/* ====== Hero ====== */}
      <section className={styles.hero}>
        <img
          src="/imgs/cta-banner.jpg"
          alt=""
          className={styles.heroImg}
          aria-hidden
          loading="eager"
        />
        <div className={styles.heroOverlay} aria-hidden />
        <div className={styles.heroNoise} aria-hidden />

        <div className={`${styles.heroContent} mx-auto max-w-[1280px] px-5 py-10 sm:px-8 sm:py-14`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link
              to="/"
              className="underline-offset-4 transition-colors hover:text-white hover:underline"
            >
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Đặt lịch khám</span>
          </nav>

          <div className="max-w-[680px]">
            <h1 className="font-bayon text-[clamp(28px,4vw,52px)] leading-[1.02] font-normal text-white">
              Đặt lịch khám cho boss
            </h1>
            <p className="mt-4 max-w-[50ch] text-[15px] leading-relaxed text-white/70">
              Đội ngũ bác sĩ thú y giàu kinh nghiệm, trang thiết bị hiện đại. Đặt lịch trước để được ưu tiên và chủ động thời gian.
            </p>
          </div>
        </div>
      </section>

      {/* ====== Trust strip ====== */}
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <div className={styles.trustStrip}>
          <div className={styles.trustItem}>
            <span className={styles.trustIcon}>
              <Shield size={16} />
            </span>
            <span>
              <span className={styles.trustStrong}>10+ năm</span> kinh nghiệm
            </span>
          </div>
          <div className={styles.trustItem}>
            <span className={styles.trustIcon}>
              <Star size={16} />
            </span>
            <span>
              <span className={styles.trustStrong}>4.9/5</span> điểm hài lòng
            </span>
          </div>
          <div className={styles.trustItem}>
            <span className={styles.trustIcon}>
              <Clock size={16} />
            </span>
            <span>
              Mở cửa <span className={styles.trustStrong}>7:30 – 19:00</span> mỗi ngày
            </span>
          </div>
          <div className={styles.trustItem}>
            <span className={styles.trustIcon}>
              <MapPin size={16} />
            </span>
            <span>
              <span className={styles.trustStrong}>Quận Cầu Giấy</span>, Hà Nội
            </span>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        {/* ====== Service cards ====== */}
        <section className={styles.servicesSection}>
          <p className={styles.sectionLabel}>Chọn dịch vụ</p>
          <div className={styles.servicesGrid}>
            {SERVICES.map((s) => {
              const Icon = s.icon
              const active = selectedService === s.id
              return (
                <button
                  key={s.id}
                  type="button"
                  onClick={() => setSelectedService(active ? null : s.id)}
                  className={`${styles.serviceCard} ${active ? styles.serviceCardActive : ''}`}
                  aria-pressed={active}
                >
                  <span className={styles.serviceIcon}>
                    <Icon size={24} strokeWidth={1.75} />
                  </span>
                  <span className={styles.serviceName}>{s.name}</span>
                  <span className={styles.serviceDesc}>{s.desc}</span>
                  <span className={styles.servicePrice}>{vnd.format(s.price)}</span>
                </button>
              )
            })}
          </div>
        </section>

        {/* ====== Form ====== */}
        <section className={styles.formSection}>
          <form onSubmit={handleSubmit} noValidate>
            <div className={styles.formCard}>
              {/* Form header */}
              <div className={styles.formHeader}>
                <ClipboardCheck size={20} className="text-[var(--color-accent)]" />
                <h2 className={styles.formTitle}>Thông tin đặt lịch</h2>
              </div>

              <div className={styles.formBody}>
                {/* Row 1: Loại + Tên pet */}
                <div className={styles.formGrid}>
                  <div className={styles.field}>
                    <label className={styles.fieldLabel}>Loại thú cưng</label>
                    <div className={styles.petTypePills}>
                      {(['dog', 'cat'] as const).map((t) => (
                        <button
                          key={t}
                          type="button"
                          onClick={() => setPetType(t)}
                          className={`${styles.petTypePill} ${petType === t ? styles.petTypePillActive : ''}`}
                        >
                          {t === 'dog' ? <Dog size={16} /> : <Cat size={16} />}
                          {t === 'dog' ? 'Chó' : 'Mèo'}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className={styles.field}>
                    <label htmlFor="petName" className={styles.fieldLabel}>
                      Tên thú cưng<span>*</span>
                    </label>
                    <input
                      id="petName"
                      type="text"
                      value={petName}
                      onChange={(e) => setPetName(e.target.value)}
                      placeholder="VD: Milo, Luna"
                      className={styles.fieldInput}
                    />
                  </div>
                </div>

                {/* Row 2: Họ tên + SĐT */}
                <div className={styles.formGrid}>
                  <div className={styles.field}>
                    <label htmlFor="ownerName" className={styles.fieldLabel}>
                      Họ tên chủ nuôi<span>*</span>
                    </label>
                    <input
                      id="ownerName"
                      type="text"
                      value={ownerName}
                      onChange={(e) => setOwnerName(e.target.value)}
                      placeholder="Nguyễn Văn A"
                      className={styles.fieldInput}
                    />
                  </div>

                  <div className={styles.field}>
                    <label htmlFor="phone" className={styles.fieldLabel}>
                      Số điện thoại<span>*</span>
                    </label>
                    <input
                      id="phone"
                      type="tel"
                      value={phone}
                      onChange={(e) => setPhone(e.target.value)}
                      placeholder="0901 234 567"
                      className={styles.fieldInput}
                    />
                  </div>
                </div>

                {/* Row 3: Ngày + Giờ */}
                <div className={styles.datetimeGrid}>
                  <div className={styles.field}>
                    <label className={styles.fieldLabel}>
                      Ngày đặt lịch<span>*</span>
                    </label>
                    <DatePicker
                      id="date"
                      value={date || null}
                      onChange={(iso) => setDate(iso)}
                      minDate={minDate}
                      ariaLabel="Ngày đặt lịch"
                    />
                  </div>

                  <div className={styles.field}>
                    <label className={styles.fieldLabel}>
                      Giờ hẹn<span>*</span>
                    </label>
                    <div className={styles.timeGrid}>
                      {TIME_SLOTS.map((slot) => (
                        <button
                          key={slot.time}
                          type="button"
                          disabled={!slot.available}
                          onClick={() => setSelectedTime(slot.time)}
                          className={`${styles.timeSlot} ${
                            selectedTime === slot.time ? styles.timeSlotActive : ''
                          } ${!slot.available ? styles.timeSlotDisabled : ''}`}
                        >
                          {slot.time}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>

                {/* Row 4: Ghi chú */}
                <div className={styles.field}>
                  <label htmlFor="note" className={styles.fieldLabel}>
                    Ghi chú thêm
                  </label>
                  <textarea
                    id="note"
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    placeholder="Triệu chứng, yêu cầu đặc biệt, dị ứng thức ăn..."
                    rows={3}
                    className={`${styles.fieldInput} ${styles.fieldTextarea}`}
                  />
                </div>
              </div>

              {/* Submit */}
              <div className={styles.submitWrap}>
                <button type="submit" className={styles.submitBtn}>
                  <Calendar size={18} />
                  Xác nhận đặt lịch
                  <ChevronRight size={18} />
                </button>
              </div>
            </div>
          </form>
        </section>
      </div>

      {/* ====== Success modal ====== */}
      {submitted && service && (
        <SuccessModal
          service={service}
          petName={petName}
          date={date}
          time={selectedTime ?? ''}
          onClose={handleClose}
        />
      )}
    </div>
  )
}

