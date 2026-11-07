import { useEffect, useRef, useState } from 'react'
import { CalendarClock, CalendarDays, Info, Minimize2, RotateCcw, Send, Sparkles, Stethoscope, Syringe } from 'lucide-react'
import { AI_LIMITS, AI_PROVIDER_NOTICE } from '../../../shared/types/ai'
import { ChatEntryView } from './ChatMessages'
import type { useChat } from './useChat'

// Gợi ý nằm trong phạm vi trả lời (chăm sóc cơ bản, lịch tiêm chung, dữ liệu cá nhân); câu chẩn đoán / sơ cứu bị từ chối theo BR-AI-02, 03.
const QUICK_PROMPTS = [
  { icon: Syringe, query: 'Chó con tiêm phòng dại lúc mấy tuần?' },
  { icon: CalendarDays, query: 'Lịch tiêm của bé nhà tôi' },
  { icon: CalendarClock, query: 'Sắp tới tôi có lịch hẹn không?' },
]

/** Cửa sổ chat của mascot: tin nhắn, gợi ý nhanh, ô nhập và dòng thông báo bắt buộc BR-AI-25. */
export function ChatWindow({ chat, onClose }: { chat: ReturnType<typeof useChat>; onClose: () => void }) {
  const [text, setText] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // jsdom không có scrollIntoView
    bottomRef.current?.scrollIntoView?.({ behavior: 'smooth' })
  }, [chat.entries, chat.pending])

  const submit = (value: string) => {
    if (!value.trim() || chat.pending) return
    setText('')
    void chat.send(value)
  }

  return (
    <section
      aria-label="Trò chuyện với trợ lý AI"
      className="ai-chat-window pointer-events-auto mb-3 w-[360px] sm:w-[400px] h-[520px] max-h-[80vh] flex flex-col rounded-3xl bg-white shadow-[0_20px_50px_rgba(59,42,30,0.22)] border border-(--color-border-default) overflow-hidden"
    >
      <div className="bg-linear-to-r from-accent via-[#b63d2d] to-accent-hover px-4 py-3.5 text-white flex items-center justify-between shadow-sm">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-full bg-white/15 p-1 border border-white/30 flex items-center justify-center">
            <Stethoscope className="w-5 h-5 text-white" aria-hidden />
          </div>
          <div>
            <h3 className="flex items-center gap-1.5 font-bold text-sm tracking-wide leading-none">
              Trợ lý AI PetCare
              <span className="bg-amber-300 text-accent-hover text-[10px] font-extrabold px-1.5 py-0.5 rounded-full uppercase tracking-wider flex items-center gap-0.5">
                <Sparkles size={10} aria-hidden /> AI
              </span>
            </h3>
            <p className="text-[11px] text-white/80 mt-0.5">Chăm sóc cơ bản, dinh dưỡng, lịch tiêm</p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={chat.reset}
            title="Làm mới cuộc trò chuyện"
            aria-label="Làm mới cuộc trò chuyện"
            className="p-1.5 rounded-lg text-white/80 hover:text-white hover:bg-white/10 transition-colors"
          >
            <RotateCcw size={15} aria-hidden />
          </button>
          <button
            type="button"
            onClick={onClose}
            title="Thu nhỏ"
            aria-label="Thu nhỏ khung chat"
            className="p-1.5 rounded-lg text-white/80 hover:text-white hover:bg-white/10 transition-colors"
          >
            <Minimize2 size={16} aria-hidden />
          </button>
        </div>
      </div>

      {/* BR-AI-25: luôn hiện khi còn chạy gói free của nhà cung cấp */}
      <p className="flex items-start gap-1.5 bg-amber-50 px-3 py-2 text-[11px] leading-snug text-amber-900 border-b border-amber-100">
        <Info size={13} className="mt-px shrink-0" aria-hidden />
        <span>{AI_PROVIDER_NOTICE}</span>
      </p>

      <div role="log" aria-live="polite" className="flex-1 overflow-y-auto p-4 space-y-3 bg-[#fdfbf9] text-xs">
        {chat.entries.map((entry) => (
          <ChatEntryView key={entry.id} entry={entry} onChoosePet={chat.choosePet} />
        ))}
        {chat.pending && (
          <div role="status" className="flex items-center gap-2 text-stone-500 text-[11px] bg-white border border-stone-200/80 px-3 py-2 rounded-2xl rounded-bl-xs w-fit shadow-xs">
            <span className="flex gap-1" aria-hidden>
              <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce" />
              <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce [animation-delay:0.2s]" />
              <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce [animation-delay:0.4s]" />
            </span>
            Trợ lý đang soạn câu trả lời…
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="px-3 py-2 bg-stone-50 border-t border-stone-100 flex gap-1.5 overflow-x-auto no-scrollbar shrink-0">
        {QUICK_PROMPTS.map((qp) => (
          <button
            key={qp.query}
            type="button"
            disabled={chat.pending}
            onClick={() => submit(qp.query)}
            className="whitespace-nowrap rounded-full bg-white hover:bg-accent hover:text-white border border-stone-200 px-2.5 py-1 text-[11px] font-medium text-stone-700 transition-colors shadow-2xs shrink-0 disabled:opacity-50"
          >
            <qp.icon aria-hidden className="mr-1 inline h-3 w-3 align-[-1px]" />
            {qp.query}
          </button>
        ))}
      </div>

      <form
        className="p-3 bg-white border-t border-stone-200/80 flex items-center gap-2 shrink-0"
        onSubmit={(e) => {
          e.preventDefault()
          submit(text)
        }}
      >
        <input
          type="text"
          aria-label="Câu hỏi cho trợ lý AI"
          value={text}
          maxLength={AI_LIMITS.question}
          onChange={(e) => setText(e.target.value)}
          placeholder="Hỏi về chăm sóc, dinh dưỡng, lịch tiêm…"
          className="flex-1 bg-stone-100 border border-stone-200/80 focus:border-accent rounded-xl px-3 py-2 text-xs text-(--color-text-primary) outline-hidden transition-all"
        />
        <button
          type="submit"
          disabled={!text.trim() || chat.pending}
          className="p-2 rounded-xl bg-accent text-white disabled:opacity-40 disabled:cursor-not-allowed hover:bg-accent-hover transition-colors shrink-0 shadow-xs"
          title="Gửi câu hỏi"
          aria-label="Gửi câu hỏi"
        >
          <Send size={15} aria-hidden />
        </button>
      </form>
    </section>
  )
}
