import React, { useState, useRef } from 'react'
import { Link } from 'react-router-dom'
import { Sparkles, Calendar, CheckCircle2, ArrowRight, Gift, Crown } from 'lucide-react'
import gsap from 'gsap'

interface RatePlan {
  days: string
  price: string
  unit: string
  badge?: string
  isVip?: boolean
  perk: string
  highlights: string[]
}

const RATES: Record<number, RatePlan> = {
  1: {
    days: '1 Ngày',
    price: '350.000đ',
    unit: '/ ngày',
    perk: 'Tặng 1 suất chải lông thảo mộc & xịt dưỡng thơm mượt',
    highlights: ['Phòng điều hòa riêng 24-26°C', 'Ăn ngày 2 bữa hạt dinh dưỡng cao cấp', 'Dắt dạo sân cỏ 30 phút'],
  },
  2: {
    days: '3 Ngày (Phổ Biến)',
    price: '490.000đ',
    unit: '/ ngày',
    badge: 'Ưa Chuộng Nhất',
    perk: 'Tặng kèm 1 suất Spa Tắm Thơm & Bác Sĩ khám lâm sàng miễn phí!',
    highlights: ['Phòng VIP rộng 3.5m²', 'Live Cam 24/7 xem trực tiếp trên App', 'Thực đơn ức gà và bò tươi áp chảo'],
  },
  3: {
    days: '7 Ngày (1 Tuần)',
    price: '3.200.000đ',
    unit: '/ tuần',
    badge: 'Tiết Kiệm 10%',
    perk: 'Báo cáo nhật ký ăn ngủ kèm clip 4K gửi chủ nuôi mỗi ngày',
    highlights: ['Spa trọn gói 2 lần/tuần', 'Giờ chơi tương tác bóng tennis 2 lần/ngày', 'Bác sĩ kiểm tra sinh hiệu 2 lần'],
  },
  4: {
    days: '15 Ngày',
    price: '5.900.000đ',
    unit: '/ 15 ngày',
    badge: 'Ưu Đãi Đặc Biệt',
    perk: 'Miễn phí đưa đón tận nhà 2 chiều + Tặng voucher phụ kiện 500K',
    highlights: ['Phòng Suite cách âm chống giật mình', 'Huấn luyện kỹ năng nhẹ nhàng', 'Live Cam độ phân giải cao có micro đàm thoại'],
  },
  5: {
    days: '30 Ngày (VIP)',
    price: '9.500.000đ',
    unit: '/ tháng',
    badge: 'Gói Hoàng Gia VIP',
    isVip: true,
    perk: 'VIP Trọn Gói: Phòng Suite riêng + Bác sĩ trực riêng + Spa tuần 2 lần',
    highlights: ['Chế độ dinh dưỡng siêu cấp Organic', 'Khu vườn cỏ riêng vận động không giới hạn', 'Đưa đón tận nhà không giới hạn số lần'],
  },
}

export const StayPriceCalculator: React.FC = () => {
  const [sliderVal, setSliderVal] = useState<number>(2)
  const priceRef = useRef<HTMLSpanElement>(null)
  const currentPlan = RATES[sliderVal]

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = Number(e.target.value)
    setSliderVal(val)

    if (priceRef.current) {
      gsap.fromTo(
        priceRef.current,
        { scale: 1.2, color: '#cf5b47' },
        { scale: 1, color: '#a43324', duration: 0.35, ease: 'back.out(2)' }
      )
    }
  }

  return (
    <div className="w-full max-w-4xl mx-auto rounded-[32px] bg-white p-6 sm:p-10 shadow-[0_18px_50px_rgba(164,51,36,0.08)] border border-accent/20 relative overflow-hidden">
      {/* Decorative Top Accent */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-accent/10 pb-6 mb-6">
        <div>
          <div className="inline-flex items-center gap-2 text-[12px] font-extrabold uppercase tracking-widest text-accent mb-1">
            <Sparkles size={14} /> Dự Toán Chi Phí Khách Sạn Thú Cưng
          </div>
          <h3 className="font-friendly font-bold text-[32px] sm:text-[38px] text-[#3B2A1E] leading-tight">
            GỬI CÀNG DÀI NGÀY — ƯU ĐÃI CÀNG LỚN!
          </h3>
        </div>
        <div className="text-left sm:text-right">
          <div className="text-xs font-bold uppercase tracking-wider text-gray-400">Giá ước tính</div>
          <div className="flex items-baseline gap-1 sm:justify-end">
            <span
              ref={priceRef}
              className="font-friendly font-extrabold text-4xl sm:text-5xl text-accent inline-block will-change-transform"
            >
              {currentPlan.price}
            </span>
            <span className="text-sm font-semibold text-gray-500">{currentPlan.unit}</span>
          </div>
          {currentPlan.badge && (
            <span className="inline-flex items-center gap-1.5 mt-1 text-[11px] font-extrabold uppercase tracking-wider bg-[#faebe4] text-accent border border-accent/20 px-3 py-0.5 rounded-full shadow-xs">
              {currentPlan.isVip ? <Crown size={12} className="text-amber-600 shrink-0" /> : <Gift size={12} className="text-accent shrink-0" />}
              <span>{currentPlan.badge}</span>
            </span>
          )}
        </div>
      </div>

      {/* Slider */}
      <div className="mb-6">
        <div className="flex justify-between items-center text-sm font-bold text-gray-700 mb-2">
          <span>Thời gian lưu trú dự kiến:</span>
          <span className="text-accent font-extrabold text-base">{currentPlan.days}</span>
        </div>
        <input
          type="range"
          min={1}
          max={5}
          step={1}
          value={sliderVal}
          onChange={handleChange}
          aria-label="Chọn thời gian lưu trú"
          className="w-full h-3 bg-[#faebe4] rounded-lg appearance-none cursor-pointer accent-[#a43324] transition-all"
        />
        <div className="flex justify-between text-[11px] font-bold text-gray-400 mt-2 px-1">
          <span>1 Ngày</span>
          <span className={sliderVal === 2 ? 'text-accent font-black' : ''}>3 Ngày</span>
          <span>7 Ngày</span>
          <span>15 Ngày</span>
          <span>30 Ngày VIP</span>
        </div>
      </div>

      {/* Dynamic Perk Alert Box */}
      <div className="rounded-2xl bg-amber-50/80 border border-amber-200/80 p-4 mb-6 flex items-center gap-3 text-sm font-bold text-amber-900 shadow-sm">
        <div className="p-1.5 rounded-full bg-amber-100 text-amber-600 shrink-0">
          <Sparkles size={18} />
        </div>
        <span className="leading-snug">{currentPlan.perk}</span>
      </div>

      {/* Feature Highlights Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-8">
        {currentPlan.highlights.map((h, i) => (
          <div key={i} className="flex items-start gap-2 text-xs font-semibold text-gray-700 bg-[#fdf6ec]/80 border border-accent/10 rounded-xl p-3">
            <CheckCircle2 size={16} className="text-emerald-500 shrink-0 mt-0.5" />
            <span>{h}</span>
          </div>
        ))}
      </div>

      {/* Action CTA */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-4 border-t border-accent/10">
        <div className="text-xs text-gray-500 flex items-center gap-2">
          <Calendar size={15} className="text-accent" />
          <span>Hỗ trợ nhận phòng 24/7 • Giữ phòng trước không mất phí cọc</span>
        </div>
        <Link
          to="/booking"
          className="w-full sm:w-auto inline-flex items-center justify-center gap-2 rounded-full bg-accent px-8 py-3 text-sm font-extrabold text-white uppercase tracking-wider shadow-[0_8px_20px_rgba(164,51,36,0.25)] hover:bg-accent-hover hover:scale-105 active:scale-95 transition-all"
        >
          Đặt Phòng Cho Bé Ngay <ArrowRight size={16} />
        </Link>
      </div>
    </div>
  )
}
