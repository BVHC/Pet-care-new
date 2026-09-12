import React from 'react'
import { Link } from 'react-router-dom'
import { PhoneCall, MapPin, Clock, ArrowRight } from 'lucide-react'

export const ContactVetCTA: React.FC = () => {
  return (
    <section className="relative w-full bg-[#382417] py-14 px-6 sm:px-10 text-white overflow-hidden select-none">
      <div className="max-w-6xl mx-auto flex flex-col lg:flex-row items-center justify-between gap-8">
        <div>
          <div className="inline-block bg-[#faebe4] text-[#a43324] border border-[#a43324]/20 font-black text-xs px-3.5 py-1 rounded-full uppercase tracking-wider mb-3">
            Hỗ Trợ 24/7 (Contact Us)
          </div>
          <h2 className="font-bayon text-[#fdf6ec] text-4xl sm:text-5xl md:text-6xl uppercase tracking-tight leading-[0.92]">
            BẠN CẦN TƯ VẤN LỊCH TRÌNH RIÊNG CHO BÉ?
          </h2>
          <p className="mt-2 text-sm sm:text-base text-[#e2d5cc] max-w-xl">
            Đội ngũ bác sĩ thú y và chuyên gia dinh dưỡng PetCare luôn sẵn sàng lắng nghe mọi thói quen, khẩu vị của bé yêu.
          </p>

          <div className="flex flex-wrap items-center gap-6 mt-6 text-xs sm:text-sm font-semibold text-[#e2d5cc]">
            <span className="flex items-center gap-2">
              <Clock size={16} className="text-[#cf5b47]" /> Phục vụ 24/7 không nghỉ lễ
            </span>
            <span className="flex items-center gap-2">
              <MapPin size={16} className="text-[#cf5b47]" /> Đưa đón tận nơi toàn thành phố
            </span>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex flex-col sm:flex-row items-center gap-4 w-full lg:w-auto">
          <a
            href="tel:19001234"
            className="w-full sm:w-auto inline-flex items-center justify-center gap-3 rounded-full bg-white text-[#382417] px-8 py-4 font-bold text-sm uppercase tracking-wider shadow-lg hover:bg-[#faebe4] transition-all"
          >
            <PhoneCall size={18} />
            <span>Hotline: 1900 1234</span>
          </a>

          <Link
            to="/booking"
            className="w-full sm:w-auto inline-flex items-center justify-center gap-2 rounded-full bg-[#a43324] text-white px-8 py-4 font-bayon text-xl uppercase tracking-wider shadow-[0_8px_25px_rgba(164,51,36,0.35)] hover:bg-[#89271b] hover:scale-105 active:scale-95 transition-all"
          >
            <span>ĐẶT LỊCH NGAY</span>
            <ArrowRight size={18} />
          </Link>
        </div>
      </div>
    </section>
  )
}
