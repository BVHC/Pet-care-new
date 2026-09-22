import React from 'react'
import { Link } from 'react-router-dom'
import { PhoneCall, MapPin, Clock, ArrowRight, ExternalLink } from 'lucide-react'

// Branch data with Google Maps links
const BRANCHES = [
  {
    id: 'quan-1',
    name: 'PetCare Quận 1',
    address: '123 Đồng Khởi, P. Bến Nghé',
    hours: '08:00 - 21:00',
    mapsUrl: 'https://www.google.com/maps/search/PetCare+Qu%E1%BA%A7n+1+Ho+Chi+Minh',
    coords: '10.7808,106.6980',
  },
  {
    id: 'quan-7',
    name: 'PetCare Quận 7',
    address: '88 Nguyễn Thị Thật, P. Tân Hưng',
    hours: '08:00 - 21:00',
    mapsUrl: 'https://www.google.com/maps/search/PetCare+Qu%E1%BA%A7n+7+Ho+Chi+Minh',
    coords: '10.7329,106.7273',
  },
]

export const ContactVetCTA: React.FC = () => {
  return (
    <section className="relative w-full bg-[#382417] py-14 px-6 sm:px-10 text-white overflow-hidden select-none">
      <div className="max-w-6xl mx-auto flex flex-col lg:flex-row items-center justify-between gap-8">
        <div className="flex-1">
          <div className="inline-block bg-[#faebe4] text-accent border border-accent/20 font-black text-xs px-3.5 py-1 rounded-full uppercase tracking-wider mb-3">
            Hỗ Trợ 24/7 (Contact Us)
          </div>
          <h2 className="font-friendly font-extrabold text-[#fdf6ec] text-4xl sm:text-5xl md:text-6xl uppercase tracking-tight leading-[1.05]">
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

          {/* Branch Location Chips */}
          <div className="flex flex-wrap gap-3 mt-6">
            {BRANCHES.map((branch) => (
              <a
                key={branch.id}
                href={branch.mapsUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="group inline-flex items-center gap-2.5 bg-white/10 hover:bg-white/20 border border-white/20 hover:border-white/30 rounded-full px-4 py-2.5 transition-all duration-200 cursor-pointer"
              >
                <div className="flex items-center justify-center w-8 h-8 rounded-full bg-[#cf5b47]/20 text-[#cf5b47] shrink-0">
                  <MapPin size={14} />
                </div>
                <div className="flex flex-col">
                  <span className="text-xs font-bold text-white leading-tight">{branch.name}</span>
                  <span className="text-[10px] text-white/70 flex items-center gap-1">
                    <Clock size={10} />
                    {branch.hours}
                  </span>
                </div>
                <ExternalLink
                  size={12}
                  className="text-white/50 group-hover:text-white/80 transition-colors ml-1"
                />
              </a>
            ))}
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex flex-col sm:flex-row items-center gap-4 w-full lg:w-auto shrink-0">
          <a
            href="tel:19001234"
            className="w-full sm:w-auto inline-flex items-center justify-center gap-3 rounded-full bg-white text-[#382417] px-8 py-4 font-bold text-sm uppercase tracking-wider shadow-lg hover:bg-[#faebe4] transition-all"
          >
            <PhoneCall size={18} />
            <span>Hotline: 1900 1234</span>
          </a>

          <Link
            to="/booking"
            className="w-full sm:w-auto inline-flex items-center justify-center gap-2 rounded-full bg-accent text-white px-8 py-4 font-friendly font-bold text-xl uppercase tracking-wider shadow-[0_8px_25px_rgba(164,51,36,0.35)] hover:bg-accent-hover hover:scale-105 active:scale-95 transition-all"
          >
            <span>ĐẶT LỊCH NGAY</span>
            <ArrowRight size={18} />
          </Link>
        </div>
      </div>
    </section>
  )
}
