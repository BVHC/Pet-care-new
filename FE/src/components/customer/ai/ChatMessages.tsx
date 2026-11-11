import { AlertTriangle, Calendar, ChevronRight, LogIn, Phone, WifiOff } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import { plainMessage, toApiError } from '../../../shared/api/api-error'
import { useVaccinationSummary } from '../../../shared/hooks/useAi'
import type { ChatResponse, PetChoice } from '../../../shared/types/ai'
import type { ChatEntry } from './useChat'

// UC90, UC91: giao diện rẽ theo `result` của ChatResponse (07 §10). Câu trả lời là chữ thuần (whitespace-pre-line), không render HTML.

const BUBBLE = 'max-w-[88%] rounded-2xl px-3.5 py-2.5 shadow-sm whitespace-pre-line leading-relaxed'
const BOT = `${BUBBLE} bg-white text-(--color-text-primary) border border-stone-200/80 rounded-bl-xs`
const CTA =
  'inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-accent text-white text-[11px] font-semibold hover:bg-accent-hover transition-colors shadow-xs'

function BookingButton() {
  return (
    <div className="mt-2.5 flex justify-end border-t border-stone-100 pt-2">
      <Link to="/booking" className={CTA}>
        <Calendar size={12} aria-hidden />
        Đặt lịch khám
        <ChevronRight size={12} aria-hidden />
      </Link>
    </div>
  )
}

function LoginButton() {
  const { pathname } = useLocation()
  return (
    <div className="mt-2.5 flex justify-end border-t border-stone-100 pt-2">
      <Link to="/auth/login" state={{ from: pathname }} className={CTA}>
        <LogIn size={12} aria-hidden />
        Đăng nhập
      </Link>
    </div>
  )
}

function PetButtons({ pets, onChoose }: { pets: PetChoice[]; onChoose: (pet: PetChoice) => void }) {
  return (
    <div className="mt-2.5 flex flex-wrap gap-1.5 border-t border-stone-100 pt-2">
      {pets.map((pet) => (
        <button
          key={pet.petId}
          type="button"
          onClick={() => onChoose(pet)}
          className="rounded-full border border-stone-200 bg-white px-3 py-1 text-[11px] font-semibold text-stone-700 transition-colors hover:bg-accent hover:text-white"
        >
          {pet.name}
        </button>
      ))}
    </div>
  )
}

/** Một lượt trả lời của trợ lý: nhánh theo `result`; nút đặt lịch theo `bookingHint`. */
function ResponseView({ response: r, onChoosePet }: { response: ChatResponse; onChoosePet: (pet: PetChoice) => void }) {
  if (r.result === 'EMERGENCY') {
    return (
      <div role="alert" className={`${BUBBLE} border-2 border-red-300 bg-red-50 text-red-900 rounded-bl-xs`}>
        <p className="mb-1.5 flex items-center gap-1.5 border-b border-red-200 pb-1.5 font-bold text-red-700">
          <AlertTriangle size={14} className="shrink-0" aria-hidden />
          TÌNH HUỐNG KHẨN CẤP
        </p>
        <p>{r.answer}</p>
        {r.emergencyBranches.length > 0 && (
          <ul className="mt-2 space-y-1.5">
            {r.emergencyBranches.map((b) => (
              <li key={b.branchId} className="rounded-lg bg-white/80 px-2.5 py-1.5">
                <p className="font-semibold">{b.name}</p>
                <p className="text-[11px]">{b.address}</p>
                <a href={`tel:${b.phone}`} className="mt-0.5 inline-flex items-center gap-1 font-bold text-red-700 underline">
                  <Phone size={11} aria-hidden />
                  {b.phone}
                </a>
              </li>
            ))}
          </ul>
        )}
        {r.bookingHint && <BookingButton />}
      </div>
    )
  }
  return (
    <div className={r.result === 'UNAVAILABLE' ? `${BOT} border-amber-200 bg-amber-50` : BOT}>
      {r.result === 'UNAVAILABLE' && (
        <p className="mb-1 flex items-center gap-1.5 font-semibold text-amber-700">
          <WifiOff size={13} aria-hidden /> Trợ lý đang bận
        </p>
      )}
      {/* BE dùng MEDICAL_REFUSAL cho hai việc (chẩn đoán / liều thuốc, và hồ sơ bệnh án): tiêu đề phải đúng cho cả hai, lý do cụ thể nằm trong `answer`. */}
      {r.result === 'MEDICAL_REFUSAL' && <p className="mb-1 font-semibold text-stone-500">Mình không thể hỗ trợ nội dung này</p>}
      <p>{r.answer}</p>
      {r.result === 'ANSWERED' && (
        <>
          {r.sources.length > 0 && (
            <p className="mt-2 border-t border-stone-100 pt-1.5 text-[11px] text-stone-500">
              Nguồn: {r.sources.map((s) => s.title).join('; ')}
            </p>
          )}
          {r.disclaimer && <p className="mt-1 text-[11px] italic text-stone-500">{r.disclaimer}</p>}
        </>
      )}
      {r.result === 'NEED_PET_CHOICE' && <PetButtons pets={r.pets} onChoose={onChoosePet} />}
      {r.result === 'NEED_LOGIN' && <LoginButton />}
      {r.bookingHint && <BookingButton />}
    </div>
  )
}

/** UC91 #3: lịch tiêm tất định của thú, không qua LLM. */
function VaccinationCard({ pet }: { pet: PetChoice }) {
  const q = useVaccinationSummary(pet.petId)
  if (q.isError) {
    return (
      <div role="alert" className={BOT}>
        <p>{plainMessage(toApiError(q.error))}</p>
        <button type="button" onClick={() => void q.refetch()} className={`${CTA} mt-2`}>
          Thử lại
        </button>
      </div>
    )
  }
  if (!q.data) return <div className={BOT}>Đang tra lịch tiêm của {pet.name}…</div>
  return (
    <div className={BOT}>
      {/* `message` đã liệt kê từng loại vaccine theo mẫu cố định (BR-AI-11): hiện nguyên văn, không dựng lại từ `items`. */}
      <p>{q.data.message}</p>
      <p className="mt-2 text-[11px] italic text-stone-500">{q.data.disclaimer}</p>
    </div>
  )
}

export function ChatEntryView({ entry, onChoosePet }: { entry: ChatEntry; onChoosePet: (pet: PetChoice) => void }) {
  const isUser = entry.from === 'user'
  return (
    <div className={`flex flex-col ${isUser ? 'items-end' : 'items-start'}`}>
      {entry.from === 'user' ? (
        <div className={`${BUBBLE} bg-accent text-white rounded-br-xs`}>{entry.text}</div>
      ) : 'note' in entry ? (
        <div className={BOT}>{entry.note}</div>
      ) : 'error' in entry ? (
        <div role="alert" className={`${BUBBLE} border border-red-200 bg-red-50 text-red-700 rounded-bl-xs`}>
          {entry.error}
        </div>
      ) : 'vaccinationOf' in entry ? (
        <VaccinationCard pet={entry.vaccinationOf} />
      ) : (
        <ResponseView response={entry.response} onChoosePet={onChoosePet} />
      )}
    </div>
  )
}
