import { useState } from 'react'
import {
  Calendar,
  Camera,
  ChevronLeft,
  Edit3,
  Filter,
  MapPin,
  MessageSquare,
  MoreHorizontal,
  Star,
  ThumbsUp,
  X,
} from 'lucide-react'
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero'

/* ================================================================
   Types.
   ================================================================ */

interface GoogleReview {
  id: string
  name: string
  avatar: string
  rating: number
  date: string
  text: string
  helpful: number
}

interface ServiceReview {
  id: string
  author: string
  authorAvatar: string
  service: string
  branch: string
  rating: number
  date: string
  text: string
  images?: string[]
  helpful: number
}

/* ================================================================
   Mock data.
   ================================================================ */

const GOOGLE_STATS = {
  overall: 4.9,
  total: 847,
  breakdown: [
    { stars: 5, count: 762 },
    { stars: 4, count: 68 },
    { stars: 3, count: 12 },
    { stars: 2, count: 3 },
    { stars: 1, count: 2 },
  ],
}

const GOOGLE_REVIEWS: GoogleReview[] = [
  {
    id: 'gr1',
    name: 'Trần Minh Châu',
    avatar: 'T',
    rating: 5,
    date: '2 ngày trước',
    text: 'Dịch vụ tuyệt vời! Bác sĩ Minh rất tận tâm, giải thích kỹ về tình trạng của bé Miu. Phòng khám sạch sẽ, nhân viên thân thiện. Đã giới thiệu cho nhiều bạn bè rồi!',
    helpful: 24,
  },
  {
    id: 'gr2',
    name: 'Lê Hoàng Nam',
    avatar: 'L',
    rating: 5,
    date: '1 tuần trước',
    text: 'Lần đầu đưa chó đi khám ở đây và rất hài lòng. Đội ngũ bác sĩ chuyên nghiệp, trang thiết bị hiện đại. Đặt lịch online rất tiện, không phải chờ đợi lâu.',
    helpful: 18,
  },
  {
    id: 'gr3',
    name: 'Phạm Thu Hà',
    avatar: 'P',
    rating: 5,
    date: '2 tuần trước',
    text: 'Bé mèo của mình bị viêm da, được tư vấn rất kỹ và điều trị khỏi sau 2 tuần. Giá cả hợp lý hơn so với nhiều chỗ khác. Cảm ơn PetCare!',
    helpful: 31,
  },
  {
    id: 'gr4',
    name: 'Nguyễn Đức Anh',
    avatar: 'N',
    rating: 4,
    date: '3 tuần trước',
    text: 'Dịch vụ tốt, nhân viên nhiệt tình. Khách sạn cho thú cưng cũng rất ok, có camera theo dõi 24/7. Trừ điểm vì lúc cao điểm hơi đông.',
    helpful: 12,
  },
  {
    id: 'gr5',
    name: 'Hoàng Linh Chi',
    avatar: 'H',
    rating: 5,
    date: '1 tháng trước',
    text: 'Combo spa 5 buổi mua cho bé Corgi rất đáng giá! Lông bé mượt và sạch sẽ sau mỗi lần tắm. Nhân viên cắt tỉa lông đẹp lắm.',
    helpful: 15,
  },
]

const SERVICE_REVIEWS: ServiceReview[] = [
  {
    id: 'sr1',
    author: 'Nguyễn Văn A',
    authorAvatar: 'N',
    service: 'Khám sức khỏe định kỳ',
    branch: 'PetCare Nguyễn Trãi',
    rating: 5,
    date: '20/08/2026',
    text: 'Bác sĩ khám rất kỹ, kiểm tra tai, mắt, răng đầy đủ. Tư vấn dinh dưỡng rất chi tiết. Milo nhà em giờ khỏe hơn nhiều sau khi đổi sang thức ăn bác sĩ gợi ý.',
    helpful: 8,
  },
  {
    id: 'sr2',
    author: 'Trần Thị B',
    authorAvatar: 'T',
    service: 'Spa & Grooming cao cấp',
    branch: 'PetCare Quận 7',
    rating: 5,
    date: '18/08/2026',
    text: 'Luna được tắm và cắt lông rất đẹp! Lông mượt, thơm, cắt tỉa gọn gàng. Nhân viên dịu dàng, Luna không sợ. Gói 5 buổi tiết kiệm được 400K.',
    images: ['https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=300', 'https://images.unsplash.com/photo-1573865526739-10659fec78a5?w=300'],
    helpful: 5,
  },
  {
    id: 'sr3',
    author: 'Lê Minh C',
    authorAvatar: 'L',
    service: 'Tiêm phòng dại',
    branch: 'PetCare Tân Bình',
    rating: 4,
    date: '15/08/2026',
    text: 'Quy trình tiêm nhanh gọn, vaccine chính hãng có hóa đơn đầy đủ. Bác sĩ theo dõi 30 phút sau tiêm. Giá hơi cao hơn chỗ khác nhưng yên tâm về chất lượng.',
    helpful: 3,
  },
]

const SERVICES = ['Tất cả', 'Khám bệnh', 'Spa & Grooming', 'Tiêm phòng', 'Lưu trú', 'Hotel', 'Dịch vụ khác']
const SORT_OPTIONS = ['Mới nhất', 'Cao điểm nhất', 'Nhiều đánh giá nhất']

/* ================================================================
   Write review modal.
   ================================================================ */

function WriteReviewModal({ onClose }: { onClose: () => void }) {
  const [service, setService] = useState('')
  const [rating, setRating] = useState(0)
  const [hoverRating, setHoverRating] = useState(0)
  const [text, setText] = useState('')
  const [images, setImages] = useState<string[]>([])
  const [submitted, setSubmitted] = useState(false)

  const branches = ['PetCare Nguyễn Trãi', 'PetCare Quận 7', 'PetCare Quận 3', 'PetCare Tân Bình']

  const handleImageAdd = () => {
    setImages((prev) => [...prev, `https://picsum.photos/seed/${Date.now()}/300/300`])
  }

  const canSubmit = service && rating > 0 && text.trim().length >= 10

  const handleSubmit = () => {
    if (!canSubmit) return
    setSubmitted(true)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-end justify-center bg-black/50 p-4 sm:items-center" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="w-full max-w-lg overflow-hidden rounded-3xl border border-[var(--color-border-default)] bg-white shadow-[0_24px_64px_-16px_rgba(56,36,23,0.5)]">
        <div className="flex items-center justify-between border-b border-[var(--color-border-default)] px-6 py-4">
          <h2 className="font-[var(--font-friendly)] text-lg font-extrabold text-[var(--color-text-primary)]">
            {submitted ? 'Cảm ơn bạn!' : 'Viết đánh giá dịch vụ'}
          </h2>
          <button onClick={onClose} className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"><X size={20} /></button>
        </div>

        {!submitted ? (
          <div className="p-6 space-y-4">
            {/* Service */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Dịch vụ đã sử dụng *</label>
              <div className="flex flex-wrap gap-2">
                {['Khám bệnh', 'Spa & Grooming', 'Tiêm phòng', 'Lưu trú', 'Hotel', 'Khác'].map((s) => (
                  <button key={s} onClick={() => setService(s)}
                    className={`rounded-full border px-3 py-1.5 text-[12.5px] font-semibold transition-all ${service === s ? 'border-[var(--color-accent)] bg-[var(--color-accent)] text-white' : 'border-[var(--color-border-default)] text-[var(--color-text-primary)] hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]'}`}>
                    {s}
                  </button>
                ))}
              </div>
            </div>

            {/* Branch */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Chi nhánh</label>
              <select className="w-full rounded-xl border border-[var(--color-border-default)] bg-white px-3.5 py-2.5 text-[13.5px] text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)] focus:ring-2 focus:ring-[var(--color-accent-soft)]">
                <option value="">Chọn chi nhánh</option>
                {branches.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
            </div>

            {/* Rating */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Đánh giá của bạn *</label>
              <div className="flex items-center gap-1">
                {[1, 2, 3, 4, 5].map((star) => (
                  <button key={star} onClick={() => setRating(star)} onMouseEnter={() => setHoverRating(star)} onMouseLeave={() => setHoverRating(0)}
                    className="transition-transform hover:scale-110">
                    <Star size={32} className={(hoverRating || rating) >= star ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
                  </button>
                ))}
                {rating > 0 && <span className="ml-2 text-[13px] font-bold text-amber-600">{['Rất kém', 'Kém', 'Trung bình', 'Tốt', 'Xuất sắc'][rating - 1]}</span>}
              </div>
            </div>

            {/* Text */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Chia sẻ trải nghiệm * <span className="font-normal text-[var(--color-text-secondary)]">(ít nhất 10 ký tự)</span></label>
              <textarea value={text} onChange={(e) => setText(e.target.value)} rows={4} placeholder="Mô tả chi tiết trải nghiệm của bạn với dịch vụ..."
                className="w-full resize-none rounded-xl border border-[var(--color-border-default)] bg-white px-3.5 py-2.5 text-[13.5px] text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)] focus:ring-2 focus:ring-[var(--color-accent-soft)]" />
              <p className="mt-1 text-right text-[11px] text-[var(--color-text-secondary)]">{text.length} ký tự</p>
            </div>

            {/* Images */}
            <div>
              <label className="mb-2 block text-[12.5px] font-bold text-[var(--color-text-primary)]">Hình ảnh (tùy chọn)</label>
              <div className="flex flex-wrap gap-2">
                {images.map((src, i) => (
                  <div key={i} className="relative h-16 w-16 overflow-hidden rounded-xl border border-[var(--color-border-default)]">
                    <img src={src} alt="" className="h-full w-full object-cover" />
                    <button onClick={() => setImages((p) => p.filter((_, j) => j !== i))}
                      className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-white">
                      <X size={10} />
                    </button>
                  </div>
                ))}
                {images.length < 4 && (
                  <button onClick={handleImageAdd}
                    className="flex h-16 w-16 items-center justify-center rounded-xl border-2 border-dashed border-[var(--color-border-default)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]">
                    <Camera size={18} />
                  </button>
                )}
              </div>
            </div>

            <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-[12px] text-blue-800">
              Đánh giá của bạn sẽ hiển thị sau khi được duyệt (1–2 ngày làm việc). Không đánh giá spam, quảng cáo hoặc nội dung không phù hợp.
            </div>

            <div className="flex gap-2">
              <button onClick={onClose} className="flex-1 rounded-xl border border-[var(--color-border-default)] py-2.5 text-[13px] font-bold text-[var(--color-text-secondary)] transition-colors hover:bg-[var(--color-surface-2)]">Hủy</button>
              <button onClick={handleSubmit} disabled={!canSubmit}
                className="flex-1 flex items-center justify-center gap-1.5 rounded-xl bg-[var(--color-accent)] py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)] disabled:cursor-not-allowed disabled:opacity-50">
                <Edit3 size={14} /> Gửi đánh giá
              </button>
            </div>
          </div>
        ) : (
          <div className="p-8 text-center">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
              <Star size={32} className="fill-amber-400 text-amber-400" />
            </div>
            <h3 className="font-[var(--font-friendly)] text-xl font-extrabold text-[var(--color-text-primary)]">Cảm ơn bạn đã đánh giá!</h3>
            <p className="mt-2 text-[13px] text-[var(--color-text-secondary)]">Đánh giá của bạn giúp PetCare cải thiện dịch vụ mỗi ngày. Cảm ơn sự đóng góp của bạn!</p>
            <button onClick={onClose}
              className="mt-6 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-accent)] px-6 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)]">
              Đã hiểu <ChevronLeft size={14} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/* ================================================================
   Star bar helper.
   ================================================================ */

function RatingBar({ stars, count, total }: { stars: number; count: number; total: number }) {
  const pct = total > 0 ? Math.round((count / total) * 100) : 0
  return (
    <div className="flex items-center gap-2 text-[12.5px]">
      <span className="flex w-10 items-center gap-1 font-semibold text-[var(--color-text-primary)]">
        {stars} <Star size={12} className="fill-amber-400 text-amber-400" />
      </span>
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-200">
        <div className="h-full rounded-full bg-amber-400" style={{ width: `${pct}%` }} />
      </div>
      <span className="w-8 text-right text-[var(--color-text-secondary)]">{count}</span>
    </div>
  )
}

/* ================================================================
   Main.
   ================================================================ */

export function ReviewsPage() {
  const [tab, setTab] = useState<'google' | 'service'>('google')
  const [showWriteModal, setShowWriteModal] = useState(false)
  const [serviceFilter, setServiceFilter] = useState('Tất cả')
  const [sortBy, setSortBy] = useState('Mới nhất')

  const filteredService = serviceFilter === 'Tất cả'
    ? SERVICE_REVIEWS
    : SERVICE_REVIEWS.filter((r) => r.service.includes(serviceFilter.replace('Spa & Grooming', 'Spa').replace('Khám bệnh', 'Khám')))

  return (
    <div className="bg-[var(--color-surface-page)] pb-24">
      <CommonPageHero
        eyebrow="Đánh giá dịch vụ"
        title="Đánh giá từ khách hàng"
        subtitle="Xem đánh giá thực tế từ cộng đồng PetCare. Chia sẻ trải nghiệm của bạn để giúp những pet parents khác có quyết định tốt hơn."
        breadcrumbs={[{ label: 'Đánh giá' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8 space-y-8">

        {/* Google stars summary */}
        <div className="overflow-hidden rounded-3xl border border-[var(--color-border-default)] bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
          <div className="flex flex-wrap items-center gap-8 p-8">
            {/* Overall score */}
            <div className="flex flex-col items-center gap-1 text-center">
              <div className="font-[var(--font-friendly)] text-6xl font-extrabold text-[var(--color-text-primary)]">{GOOGLE_STATS.overall}</div>
              <div className="flex gap-0.5">
                {[1,2,3,4,5].map((s) => (
                  <Star key={s} size={16} className={s <= 5 ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
                ))}
              </div>
              <div className="text-[13px] text-[var(--color-text-secondary)]">{GOOGLE_STATS.total.toLocaleString()} đánh giá trên Google</div>
              <div className="mt-2 flex items-center gap-1 rounded-full bg-[#4285f4] px-3 py-1 text-[11.5px] font-bold text-white">
                <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" /><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" /><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" /><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" /></svg>
                Google
              </div>
            </div>

            {/* Bars */}
            <div className="flex-1 space-y-2 min-w-[200px]">
              {GOOGLE_STATS.breakdown.map((b) => (
                <RatingBar key={b.stars} stars={b.stars} count={b.count} total={GOOGLE_STATS.total} />
              ))}
            </div>

            {/* CTA */}
            <div className="flex flex-col gap-2">
              <button onClick={() => window.open('https://g.page/petcare-vn/review', '_blank')}
                className="inline-flex items-center gap-2 rounded-full bg-[#4285f4] px-5 py-2.5 text-[13px] font-bold text-white transition-colors hover:bg-[#3367d6]">
                <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" /><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" /><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" /><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" /></svg>
                Đánh giá PetCare trên Google
              </button>
              <button onClick={() => setShowWriteModal(true)}
                className="inline-flex items-center gap-2 rounded-full border border-[var(--color-border-default)] px-5 py-2.5 text-[13px] font-bold text-[var(--color-text-primary)] transition-colors hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]">
                <Edit3 size={14} /> Viết đánh giá dịch vụ
              </button>
            </div>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-[var(--color-border-default)] bg-white p-1.5 shadow-[0_1px_2px_rgba(56,36,23,0.06)]">
          <button onClick={() => setTab('google')}
            className={`flex shrink-0 items-center gap-2 rounded-xl px-5 py-2.5 text-[13px] font-bold transition-all ${tab === 'google' ? 'bg-[var(--color-accent)] text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]' : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)] hover:text-[var(--color-text-primary)]'}`}>
            <svg viewBox="0 0 24 24" className={`h-4 w-4 ${tab === 'google' ? '' : 'text-[#4285f4]'}`} fill="currentColor"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" /><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" /><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" /><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" /></svg>
            Đánh giá Google
          </button>
          <button onClick={() => setTab('service')}
            className={`flex shrink-0 items-center gap-2 rounded-xl px-5 py-2.5 text-[13px] font-bold transition-all ${tab === 'service' ? 'bg-[var(--color-accent)] text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]' : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)] hover:text-[var(--color-text-primary)]'}`}>
            <MessageSquare size={15} />
            Đánh giá dịch vụ
          </button>
        </div>

        {/* Google reviews */}
        {tab === 'google' && (
          <div className="space-y-4">
            {GOOGLE_REVIEWS.map((r) => (
              <div key={r.id} className="overflow-hidden rounded-2xl border border-[var(--color-border-default)] bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                <div className="flex items-start gap-3 p-5">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--color-accent-soft)] text-[14px] font-bold text-[var(--color-accent)]">
                    {r.avatar}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <div className="text-[13.5px] font-bold text-[var(--color-text-primary)]">{r.name}</div>
                        <div className="mt-0.5 flex items-center gap-2 text-[11.5px] text-[var(--color-text-secondary)]">
                          <div className="flex gap-0.5">
                            {Array.from({ length: 5 }, (_, i) => (
                              <Star key={i} size={11} className={i < r.rating ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
                            ))}
                          </div>
                          <span>·</span>
                          <span>{r.date}</span>
                          <span>·</span>
                          <span className="flex items-center gap-1">
                            <MapPin size={10} /> Google
                          </span>
                        </div>
                      </div>
                      <button className="flex h-7 w-7 items-center justify-center rounded-full text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)]">
                        <MoreHorizontal size={15} />
                      </button>
                    </div>
                    <p className="mt-2.5 text-[13.5px] leading-relaxed text-[var(--color-text-primary)]">{r.text}</p>
                    <div className="mt-3 flex items-center gap-4">
                      <button className="flex items-center gap-1.5 text-[12px] font-semibold text-[var(--color-text-secondary)] transition-colors hover:text-[var(--color-accent)]">
                        <ThumbsUp size={13} /> Hữu ích ({r.helpful})
                      </button>
                      <button className="text-[12px] font-semibold text-[var(--color-text-secondary)] transition-colors hover:text-[var(--color-accent)]">
                        Trả lời
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Service reviews */}
        {tab === 'service' && (
          <>
            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3">
              <div className="flex items-center gap-2 overflow-x-auto rounded-xl border border-[var(--color-border-default)] bg-white p-1.5">
                {SERVICES.map((s) => (
                  <button key={s} onClick={() => setServiceFilter(s)}
                    className={`shrink-0 rounded-lg px-3.5 py-1.5 text-[12px] font-semibold transition-all ${serviceFilter === s ? 'bg-[var(--color-accent)] text-white' : 'text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)]'}`}>
                    {s}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-2 rounded-xl border border-[var(--color-border-default)] bg-white px-3 py-1.5">
                <Filter size={13} className="text-[var(--color-text-secondary)]" />
                <select value={sortBy} onChange={(e) => setSortBy(e.target.value)}
                  className="bg-transparent text-[12px] font-semibold text-[var(--color-text-primary)] outline-none">
                  {SORT_OPTIONS.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              </div>
              <button onClick={() => setShowWriteModal(true)}
                className="ml-auto flex shrink-0 items-center gap-1.5 rounded-full bg-[var(--color-accent)] px-4 py-2 text-[12.5px] font-bold text-white transition-colors hover:bg-[var(--color-accent-hover)]">
                <Edit3 size={13} /> Viết đánh giá
              </button>
            </div>

            <div className="space-y-4">
              {filteredService.map((r) => (
                <div key={r.id} className="overflow-hidden rounded-2xl border border-[var(--color-border-default)] bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]">
                  <div className="flex items-start gap-3 p-5">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--color-accent-soft)] text-[14px] font-bold text-[var(--color-accent)]">
                      {r.authorAvatar}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div>
                          <div className="text-[13.5px] font-bold text-[var(--color-text-primary)]">{r.author}</div>
                          <div className="mt-0.5 flex flex-wrap items-center gap-2 text-[11.5px] text-[var(--color-text-secondary)]">
                            <div className="flex gap-0.5">
                              {Array.from({ length: 5 }, (_, i) => (
                                <Star key={i} size={11} className={i < r.rating ? 'fill-amber-400 text-amber-400' : 'text-gray-300'} />
                              ))}
                            </div>
                            <span>·</span>
                            <span className="rounded-full bg-[var(--color-accent-soft)] px-2 py-0.5 text-[10.5px] font-bold text-[var(--color-accent)]">{r.service}</span>
                            <span>·</span>
                            <span className="flex items-center gap-1">
                              <MapPin size={10} /> {r.branch}
                            </span>
                            <span>·</span>
                            <span className="flex items-center gap-1">
                              <Calendar size={10} /> {r.date}
                            </span>
                          </div>
                        </div>
                        <button className="flex h-7 w-7 items-center justify-center rounded-full text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)]">
                          <MoreHorizontal size={15} />
                        </button>
                      </div>
                      <p className="mt-2.5 text-[13.5px] leading-relaxed text-[var(--color-text-primary)]">{r.text}</p>

                      {r.images && r.images.length > 0 && (
                        <div className="mt-3 flex gap-2">
                          {r.images.map((src, i) => (
                            <img key={i} src={src} alt="" className="h-16 w-16 rounded-xl object-cover" />
                          ))}
                        </div>
                      )}

                      <div className="mt-3 flex items-center gap-4">
                        <button className="flex items-center gap-1.5 text-[12px] font-semibold text-[var(--color-text-secondary)] transition-colors hover:text-[var(--color-accent)]">
                          <ThumbsUp size={13} /> Hữu ích ({r.helpful})
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              ))}

              {filteredService.length === 0 && (
                <div className="rounded-3xl border border-dashed border-[var(--color-border-default)] bg-white p-16 text-center">
                  <MessageSquare size={48} className="mx-auto mb-4 text-[var(--color-text-secondary)] opacity-30" />
                  <p className="text-[15px] font-bold text-[var(--color-text-secondary)]">Chưa có đánh giá nào cho dịch vụ này</p>
                  <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">Hãy là người đầu tiên chia sẻ trải nghiệm!</p>
                  <button onClick={() => setShowWriteModal(true)} className="mt-5 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-accent)] px-5 py-2.5 text-[13px] font-bold text-white">
                    <Edit3 size={14} /> Viết đánh giá đầu tiên
                  </button>
                </div>
              )}
            </div>
          </>
        )}
      </div>

      {showWriteModal && <WriteReviewModal onClose={() => setShowWriteModal(false)} />}
    </div>
  )
}
