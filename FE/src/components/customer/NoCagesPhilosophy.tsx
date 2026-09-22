import React from 'react'
import { Link } from 'react-router-dom'
import { Heart, ShieldCheck, Video, ArrowRight } from 'lucide-react'

export const NoCagesPhilosophy: React.FC = () => {
  return (
    <section className="relative w-full bg-[#fdf6ec] py-16 px-6 sm:px-10 overflow-hidden border-t border-b border-amber-100">
      <div className="max-w-6xl mx-auto">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 items-center">
          {/* Left Column: Bold Philosophy Typography */}
          <div>
            <div className="inline-flex items-center gap-2 rounded-full bg-[#faebe4] px-4 py-1.5 text-xs font-black uppercase tracking-widest text-accent mb-4 border border-accent/15">
              <Heart size={14} className="fill-current" /> Triết Lý PetCare (About Us)
            </div>
            <h2 className="font-bayon text-[#3B2A1E] text-4xl sm:text-5xl md:text-6xl leading-[0.92] tracking-tight uppercase mb-6">
              100% TỰ DO — NÓI KHÔNG VỚI CHUỒNG NHỐT!
            </h2>
            <p className="text-base sm:text-lg text-[#70584b] font-medium leading-relaxed mb-6">
              Khi bạn đi công tác hoặc du lịch, thú cưng của bạn xứng đáng có một kỳ nghỉ tràn ngập tình yêu thương chứ không phải bị giam cầm sau những thanh sắt lạnh lẽo.
            </p>
            <p className="text-sm text-[#8a7565] leading-relaxed mb-8">
              Tại PetCare, mỗi bé đều có phòng riêng có điều hòa 24-26°C, sân cỏ vận động ngoài trời, chế độ dinh dưỡng nấu mới và camera Live Cam 24/7 để bạn ngắm bé bất cứ khi nào nhớ.
            </p>

            {/* Value Badges */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
              <div className="flex items-center gap-3 bg-white p-3.5 rounded-2xl shadow-sm border border-amber-50">
                <div className="w-10 h-10 rounded-xl bg-[#faebe4] text-accent flex items-center justify-center shrink-0">
                  <ShieldCheck size={20} />
                </div>
                <div>
                  <div className="font-bold text-sm text-[#3B2A1E]">Phòng Riêng Điều Hòa</div>
                  <div className="text-xs text-gray-500">Nhiệt độ 24-26°C ấm êm</div>
                </div>
              </div>

              <div className="flex items-center gap-3 bg-white p-3.5 rounded-2xl shadow-sm border border-amber-50">
                <div className="w-10 h-10 rounded-xl bg-yellow-50 text-amber-600 flex items-center justify-center shrink-0">
                  <Video size={20} />
                </div>
                <div>
                  <div className="font-bold text-sm text-[#3B2A1E]">Live Cam 24/7 FHD</div>
                  <div className="text-xs text-gray-500">Xem trực tiếp trên điện thoại</div>
                </div>
              </div>
            </div>

            <Link
              to="/about"
              className="inline-flex items-center gap-2 rounded-full bg-accent px-7 py-3 text-sm font-bold text-white uppercase tracking-wider hover:bg-accent-hover transition-all shadow-md"
            >
              Tìm Hiểu Thêm Về Chúng Tôi <ArrowRight size={16} />
            </Link>
          </div>

          {/* Right Column: Visual Comparison (Cages vs PetCare Home) */}
          <div className="relative">
            <div className="relative rounded-3xl overflow-hidden shadow-2xl border-4 border-white bg-white">
              <img
                src="/imgs/1618027531_13_p_sobaka_i_chelovek_sobaki_krasivo_foto_14_d47fd511c1.jpg"
                alt="Chăm sóc thú cưng tận tâm"
                className="w-full aspect-4/3 object-cover"
                onError={(e) => {
                  // Fallback
                  e.currentTarget.src = '/imgs/dog_3344414_1920_1200x675_2dccbb4050.jpg'
                }}
              />
              <div className="absolute inset-0 bg-linear-to-t from-black/60 via-transparent to-transparent" />
              <div className="absolute bottom-6 left-6 right-6 text-white">
                <div className="inline-block bg-[#fff500] text-accent font-black text-xs px-3 py-1 rounded-full uppercase tracking-wider mb-2">
                  Tiêu Chuẩn 5 Sao
                </div>
                <div className="font-bayon text-2xl sm:text-3xl leading-tight">
                  KHÔNG GIAN NHƯ NGÔI NHÀ THỨ HAI CỦA BÉ
                </div>
              </div>
            </div>

            {/* Floating Dog Sticker Accent */}
            <div className="absolute -bottom-6 -left-6 bg-white p-4 rounded-2xl shadow-xl border border-red-100 flex items-center gap-3">
              <img
                src="/imgs/DogSticker.svg"
                alt="PetCare Mascot"
                className="w-11 h-11 object-contain"
              />
              <div>
                <div className="font-black text-xs text-accent">100% CUN CÚN HÀI LÒNG</div>
                <div className="text-[11px] text-gray-500">Hơn 2.500+ lượt gửi mỗi năm</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
