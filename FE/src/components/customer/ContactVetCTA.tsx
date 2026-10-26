import React from 'react'
import { Link } from 'react-router-dom'
import { PhoneCall, MapPin, Clock, ArrowRight, ExternalLink } from 'lucide-react'
import { usePublicBranches } from '@/shared/hooks/usePublicSite'

export const ContactVetCTA: React.FC = () => {
  // Chi nhánh thật (UC15): chưa tải xong thì hiện ô chờ, lỗi hoặc chưa có chi nhánh nào thì bỏ hàng chip, các nút bên phải vẫn dùng được.
  const branches = usePublicBranches()
  const list = branches.data ?? []

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
          {(branches.isPending || list.length > 0) && (
            <ul aria-label="Chi nhánh" className="flex flex-wrap gap-3 mt-6">
              {branches.isPending
                ? [0, 1].map((i) => (
                    <li key={i} aria-hidden className="h-[52px] w-48 animate-pulse rounded-full bg-white/10 motion-reduce:animate-none" />
                  ))
                : list.map((branch) => (
                    <li
                      key={branch.branchId}
                      className="inline-flex items-center gap-2.5 bg-white/10 border border-white/20 rounded-full px-4 py-2.5"
                    >
                      <div className="flex items-center justify-center w-8 h-8 rounded-full bg-[#cf5b47]/20 text-[#cf5b47] shrink-0">
                        <MapPin size={14} aria-hidden />
                      </div>
                      <div className="flex flex-col">
                        <a
                          href={`https://www.google.com/maps/search/?api=1&query=${branch.latitude},${branch.longitude}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="group inline-flex items-center gap-1 text-xs font-bold text-white leading-tight hover:underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
                        >
                          {branch.name}
                          <ExternalLink size={11} aria-hidden className="text-white/50 group-hover:text-white/80 transition-colors" />
                        </a>
                        <a
                          href={`tel:${branch.phone}`}
                          className="text-[11px] text-white/70 hover:text-white flex items-center gap-1 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
                        >
                          <PhoneCall size={10} aria-hidden />
                          {branch.phone}
                        </a>
                      </div>
                      {branch.acceptsAfterHoursEmergency && (
                        <span className="ml-1 rounded-full bg-[#cf5b47]/30 px-2 py-0.5 text-[10px] font-bold text-white whitespace-nowrap">
                          Cấp cứu 24/7
                        </span>
                      )}
                    </li>
                  ))}
            </ul>
          )}
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
