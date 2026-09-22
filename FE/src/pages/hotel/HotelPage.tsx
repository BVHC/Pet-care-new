import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Bed,
  Calendar,
  Car,
  Cat,
  Check,

  Dog,

  MapPin,
  Search,
  Shield,
  Star,
  Utensils,
  Wifi,
} from 'lucide-react'
import { toast } from 'sonner'
import { StayPriceCalculator } from '@/components/customer/StayPriceCalculator'
import { DatePicker } from '@/components/customer/DatePicker'
import styles from './HotelPage.module.css'

/* ================================================================
   Data.
   ================================================================ */

const ROOMS = [
  {
    id: 1,
    name: 'Phòng Standard',
    desc: 'Không gian yên tĩnh cho thú cưng nghỉ ngơi thoải mái',
    price: 150_000,
    popular: false,
    image: '/imgs/hero-dog.png',
    features: ['Giường êm', 'Bát ăn uống', 'Đèn ngủ'],
  },
  {
    id: 2,
    name: 'Phòng Deluxe',
    desc: 'Phòng rộng với khu vực chơi riêng, camera 24/7',
    price: 250_000,
    popular: true,
    image: '/imgs/cat-spa.png',
    features: ['Giường đôi', 'Khu vực chơi', 'Camera 24/7', 'Bữa ăn sáng'],
  },
  {
    id: 3,
    name: 'Phòng VIP',
    desc: 'Suite cao cấp với dịch vụ đặc biệt cho boss',
    price: 400_000,
    popular: false,
    image: '/imgs/hero-dog-clean.png',
    features: ['Phòng riêng lớn', 'Spa miễn phí', 'Camera 24/7', 'Bữa ăn cao cấp', 'Dắt đi dạo'],
  },
]

const SERVICES = [
  { name: 'Dắt đi dạo', price: '30K/lần', icon: Dog },
  { name: 'Spa & tắm', price: '100K', icon: Utensils },
  { name: 'Chăm sóc lông', price: '80K', icon: Star },
  { name: 'Khám sức khoẻ', price: '150K', icon: Shield },
]

const AMENITIES = [
  { icon: Wifi, label: 'Wifi miễn phí' },
  { icon: Car, label: 'Bãi đỗ xe' },
  { icon: MapPin, label: 'Quận Cầu Giấy' },
  { icon: Shield, label: 'Camera 24/7' },
]

const vnd = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

/* ================================================================
   Main page.
   ================================================================ */

export function HotelPage() {
  const [petType, setPetType] = useState<'dog' | 'cat'>('dog')
  const [checkIn, setCheckIn] = useState('')
  const [checkOut, setCheckOut] = useState('')
  const [selectedRoom, setSelectedRoom] = useState<number | null>(null)

  const handleSearch = () => {
    if (!checkIn || !checkOut) {
      toast.error('Vui lòng chọn ngày nhận và trả phòng.')
      return
    }
    if (checkIn >= checkOut) {
      toast.error('Ngày trả phòng phải sau ngày nhận phòng.')
      return
    }
    toast.success('Đang tìm phòng trống...')
  }

  const handleSelectRoom = (roomId: number) => {
    setSelectedRoom((prev) => (prev === roomId ? null : roomId))
    const room = ROOMS.find((r) => r.id === roomId)
    if (room) toast.info(`Đã chọn ${room.name}`)
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* ====== Hero ====== */}
      <section className={styles.hero}>
        <img
          src="/imgs/cat-spa.png"
          alt=""
          className={styles.heroBg}
          aria-hidden
          loading="eager"
        />
        <div className={styles.heroOverlay} aria-hidden />
        <div className={styles.heroNoise} aria-hidden />

        <div className={`${styles.heroContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          {/* Breadcrumb */}
          <nav
            aria-label="Đường dẫn"
            className="mb-5 text-[12.5px] text-white/50"
          >
            <Link
              to="/"
              className="underline-offset-4 transition-colors hover:text-white hover:underline"
            >
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Khách sạn thú cưng</span>
          </nav>

          {/* Heading */}
          <div className="flex flex-wrap items-end justify-between gap-6">
            <div>
              <div className="mb-4 inline-flex items-center gap-2 rounded-full bg-white/10 px-4 py-1.5 text-[12.5px] font-bold text-white/80 backdrop-blur-sm">
                <Bed size={14} />
                Khách sạn thú cưng
              </div>
              <h1 className="font-friendly font-extrabold text-[clamp(30px,4.5vw,58px)] leading-[1.05] text-white">
                Nơi nghỉ ngơi tuyệt vời
                <br />
                cho boss yêu
              </h1>
              <p className="mt-4 max-w-[50ch] text-[15px] leading-relaxed text-white/65">
                Khu nghỉ dưỡng cao cấp dành riêng cho thú cưng. Đội ngũ chăm sóc tận tâm 24/7, không gian xanh mát, dịch vụ đa dạng.
              </p>
            </div>

            <Link
              to="/booking"
              className="inline-flex items-center gap-2 rounded-full bg-white px-6 py-3 text-[13.5px] font-bold text-accent transition-colors hover:bg-white/90"
            >
              <Calendar size={16} />
              Đặt phòng ngay
            </Link>
          </div>
        </div>
      </section>

      {/* ====== Quick search card ====== */}
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <div className={styles.searchCard}>
          <div className={styles.searchRow}>
            {/* Pet type */}
            <div className={styles.field}>
              <span className={styles.fieldLabel}>Loại thú cưng</span>
              <div className={styles.petTypePills}>
                {(['dog', 'cat'] as const).map((t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => setPetType(t)}
                    className={`${styles.petTypePill} ${petType === t ? styles.petTypePillActive : ''}`}
                  >
                    {t === 'dog' ? <Dog size={15} /> : <Cat size={15} />}
                    {t === 'dog' ? 'Chó' : 'Mèo'}
                  </button>
                ))}
              </div>
            </div>

            {/* Check-in */}
            <div className={styles.field}>
              <span className={styles.fieldLabel}>Nhận phòng</span>
              <DatePicker
                id="checkIn"
                value={checkIn || null}
                onChange={(iso) => setCheckIn(iso)}
                minDate={new Date().toISOString().split('T')[0]}
                ariaLabel="Ngày nhận phòng"
              />
            </div>

            {/* Check-out */}
            <div className={styles.field}>
              <span className={styles.fieldLabel}>Trả phòng</span>
              <DatePicker
                id="checkOut"
                value={checkOut || null}
                onChange={(iso) => setCheckOut(iso)}
                minDate={checkIn || new Date().toISOString().split('T')[0]}
                ariaLabel="Ngày trả phòng"
              />
            </div>

            {/* Search button */}
            <button type="button" onClick={handleSearch} className={styles.searchBtn}>
              <Search size={16} />
              Tìm phòng
            </button>
          </div>
        </div>
      </div>

      {/* ====== Rooms ====== */}
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <section className={styles.section}>
          <p className={styles.sectionLabel}>Chọn phòng</p>
          <h2 className={styles.sectionTitle}>Loại phòng của chúng tôi</h2>

          <div className={styles.roomsGrid}>
            {ROOMS.map((room) => {
              const active = selectedRoom === room.id
              return (
                <div
                  key={room.id}
                  className={`${styles.roomCard} ${active ? styles.roomCardActive : ''}`}
                  onClick={() => handleSelectRoom(room.id)}
                >
                  <div className={styles.roomCardMedia}>
                    <img src={room.image} alt={room.name} loading="lazy" />
                    {room.popular && (
                      <span className={styles.roomRibbon}>
                        <Star size={11} className="fill-white text-white" />
                        Phổ biến nhất
                      </span>
                    )}
                  </div>

                  <div className={styles.roomCardBody}>
                    <h3 className={styles.roomName}>{room.name}</h3>
                    <p className={styles.roomDesc}>{room.desc}</p>

                    <div className={styles.roomFeatures}>
                      {room.features.map((f) => (
                        <span key={f} className={styles.roomFeature}>
                          <Check size={10} />
                          {f}
                        </span>
                      ))}
                    </div>

                    <div className={styles.roomFooter}>
                      <div>
                        <span className={styles.roomPrice}>{vnd.format(room.price)}</span>
                        <span className={styles.roomPriceNight}>/đêm</span>
                      </div>
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation()
                          handleSelectRoom(room.id)
                        }}
                        className={`${styles.roomSelectBtn} ${active ? styles.roomSelectBtnActive : ''}`}
                      >
                        {active ? (
                          <>
                            <Check size={13} />
                            Đã chọn
                          </>
                        ) : (
                          'Chọn phòng'
                        )}
                      </button>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </section>

        {/* ====== Price calculator ====== */}
        <section className={styles.calcSection}>
          <p className={styles.sectionLabel}>Tính giá</p>
          <h2 className={styles.sectionTitle}>Ước tính chi phí</h2>
          <StayPriceCalculator />
        </section>

        {/* ====== Services ====== */}
        <section className={styles.servicesSection}>
          <p className={styles.sectionLabel}>Bổ sung</p>
          <h2 className={styles.sectionTitle}>Dịch vụ thêm</h2>

          <div className={styles.servicesGrid}>
            {SERVICES.map((s) => {
              const Icon = s.icon
              return (
                <div key={s.name} className={styles.serviceCard}>
                  <span className={styles.serviceIcon}>
                    <Icon size={20} />
                  </span>
                  <div>
                    <div className={styles.serviceName}>{s.name}</div>
                    <div className={styles.servicePrice}>{s.price}</div>
                  </div>
                </div>
              )
            })}
          </div>
        </section>

        {/* ====== Amenities ====== */}
        <section className={styles.section}>
          <p className={styles.sectionLabel}>Tiện nghi</p>
          <h2 className={styles.sectionTitle}>Khách sạn mang lại</h2>

          <div className={styles.amenitiesGrid}>
            {AMENITIES.map((a) => {
              const Icon = a.icon
              return (
                <div key={a.label} className={styles.amenityItem}>
                  <span className={styles.amenityIcon}>
                    <Icon size={16} />
                  </span>
                  <span>{a.label}</span>
                </div>
              )
            })}
          </div>
        </section>
      </div>
    </div>
  )
}
