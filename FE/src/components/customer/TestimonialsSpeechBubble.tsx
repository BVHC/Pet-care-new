import React from 'react'
import { Star, Heart } from 'lucide-react'

interface ReviewItem {
  id: string
  title: string
  quote: string
  parent: string
  pet: string
  avatar: string
  rating: number
  bgColor: string
}

const REVIEWS: ReviewItem[] = [
  {
    id: '1',
    title: 'KỲ NGHỈ TUYỆT VỜI NHẤT CỦA BÉ MILO!',
    quote: 'Milo vốn rất nhát người lạ nhưng ở PetCare bé được ở phòng điều hòa rộng, các bạn điều dưỡng cưng nựng suốt ngày. Xem Live Cam thấy bé ngủ ngon lành mà mình mừng rơi nước mắt!',
    parent: 'Chị Mai Linh',
    pet: 'Mẹ của Corgi Milo (2 tuổi)',
    avatar: 'https://images.unsplash.com/photo-1544005313-94ddf0286df2?w=150&auto=format&fit=crop&q=80',
    rating: 5,
    bgColor: '#faede8',
  },
  {
    id: '2',
    title: 'LIVE CAM 24/7 GIÚP MÌNH AN TÂM ĐI CÔNG TÁC',
    quote: 'Đi công tác 1 tuần nhưng lúc nào mở app lên cũng thấy bé Mun được ăn ức gà tươi, được dắt đi dạo công viên cỏ xanh. Không bao giờ mình gửi chỗ khác ngoài PetCare nữa!',
    parent: 'Anh Hoàng Nam',
    pet: 'Bố của Poodle Mun (3 tuổi)',
    avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=150&auto=format&fit=crop&q=80',
    rating: 5,
    bgColor: '#fdf4ee',
  },
  {
    id: '3',
    title: 'SPA TẮM SẤY CẮT TỈA QUÁ ĐỈNH!',
    quote: 'Đón bé Miu về mà lông tơi bồng bềnh, thơm mùi thảo mộc dễ chịu cực kỳ. Các bác sĩ khám tổng quát miễn phí trước khi gửi rất chuyên nghiệp và có tâm.',
    parent: 'Bạn Thu Trang',
    pet: 'Mẹ của Mèo Anh Lông Ngắn (1.5 tuổi)',
    avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150&auto=format&fit=crop&q=80',
    rating: 5,
    bgColor: '#fef7d9',
  },
]

export const TestimonialsSpeechBubble: React.FC = () => {
  return (
    <section className="relative w-full bg-[#fdf6ec] py-16 px-6 sm:px-10 overflow-hidden">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="text-center mb-12">
          <div className="inline-flex items-center gap-2 rounded-full bg-[#faebe4] px-4 py-1.5 text-xs font-black uppercase tracking-widest text-[#a43324] mb-3 border border-[#a43324]/15">
            <Heart size={14} className="fill-current" /> Đánh Giá Từ Khách Hàng (Reviews)
          </div>
          <h2 className="font-bayon text-[#3B2A1E] text-4xl sm:text-5xl md:text-6xl uppercase tracking-tight leading-[0.92]">
            HÀI LÒNG 100% TỪ HƠN 2.500+ CHỦ NUÔI
          </h2>
          <p className="mt-3 text-sm sm:text-base text-[#70584b] font-semibold max-w-xl mx-auto">
            Những chia sẻ chân thật nhất từ các sen sau khi cho boss trải nghiệm dịch vụ tại PetCare.
          </p>
        </div>

        {/* Reviews Cards Grid */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 sm:gap-8">
          {REVIEWS.map((rev) => (
            <div
              key={rev.id}
              className="relative flex flex-col justify-between rounded-[32px] p-6 sm:p-8 shadow-[0_15px_40px_rgba(105,52,19,0.08)] border-2 border-white transition-all duration-300 hover:-translate-y-2 hover:shadow-[0_20px_50px_rgba(105,52,19,0.14)]"
              style={{ backgroundColor: rev.bgColor }}
            >
              <div>
                {/* 5 Stars Rating */}
                <div className="flex items-center gap-1 text-amber-500 mb-4">
                  {[...Array(rev.rating)].map((_, i) => (
                    <Star key={i} size={18} className="fill-current" />
                  ))}
                  <span className="ml-2 text-xs font-black text-[#3B2A1E]">5.0 / 5.0</span>
                </div>

                {/* Review Title */}
                <h3 className="font-bayon text-xl sm:text-2xl text-[#3B2A1E] uppercase leading-snug mb-3">
                  "{rev.title}"
                </h3>

                {/* Body Quote */}
                <p className="text-sm text-[#70584b] leading-relaxed font-medium mb-6">
                  {rev.quote}
                </p>
              </div>

              {/* Author Info */}
              <div className="flex items-center gap-3.5 pt-4 border-t border-black/8">
                <img
                  src={rev.avatar}
                  alt={rev.parent}
                  className="w-12 h-12 rounded-full object-cover border-2 border-white shadow-sm"
                />
                <div>
                  <div className="font-bold text-sm text-[#3B2A1E]">{rev.parent}</div>
                  <div className="text-xs font-semibold text-[#a43324]">{rev.pet}</div>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
