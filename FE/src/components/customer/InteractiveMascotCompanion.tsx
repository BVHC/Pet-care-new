import React, { useState, useRef, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  PawPrint,
  X,
  Send,
  Sparkles,
  Stethoscope,
  AlertTriangle,
  RotateCcw,
  Calendar,
  ChevronRight,
  Minimize2,
} from 'lucide-react'
import gsap from 'gsap'

interface ChatMessage {
  id: string
  sender: 'ai' | 'user'
  text: string
  time: string
  quickBooking?: boolean
  warningLevel?: 'low' | 'medium' | 'high'
}

const DIALOGUES = [
  'Gâu gâu! Sen cần hỏi Bác sĩ AI về sức khỏe của em không?',
  'Phòng điều hòa ở PetCare mát rượi luôn sen ơi!',
  'Bấm vào em để tra cứu lịch tiêm phòng và sơ cứu nhé!',
  'Hôm nay em được ăn ức gà áp chảo thơm lừng!',
  'Sen lo lắng khi em bị biếng ăn hay sốt? Bấm vào em nè!',
  'Yêu thương trọn vẹn 5 sao chuẩn quốc tế!',
]

const QUICK_PROMPTS = [
  { label: '💉 Lịch tiêm phòng cho chó/mèo', query: 'Lịch tiêm phòng chuẩn cho chó và mèo theo độ tuổi?' },
  { label: '🚨 Sơ cứu khi bị hóc dị vật / ngộ độc', query: 'Hướng dẫn sơ cứu khẩn cấp khi thú cưng bị hóc hoặc ngộ độc thức ăn' },
  { label: '🍖 Dinh dưỡng sau khi triệt sản', query: 'Chế độ ăn và dinh dưỡng phù hợp cho thú cưng sau khi triệt sản?' },
  { label: '🤢 Mèo bỏ ăn, nôn dịch vàng', query: 'Mèo bỏ ăn và nôn dịch màu vàng 2 ngày nay thì bị làm sao?' },
  { label: '💩 Chó bị tiêu chảy phân lỏng', query: 'Chó con bị tiêu chảy đi ngoài phân lỏng cần xử lý sơ cứu thế nào?' },
]

function getAIResponse(userText: string): { text: string; warningLevel?: 'low' | 'medium' | 'high'; quickBooking?: boolean } {
  const query = userText.toLowerCase()

  if (query.includes('tiêm') || query.includes('vaccine') || query.includes('chích ngừa')) {
    return {
      text: `📋 **Lịch tiêm phòng chuẩn quốc tế cho thú cưng:**\n\n• **Chó con:**\n  - 6–8 tuần: Mũi 1 (Phòng 5 bệnh: Parvo, Care, Viêm gan...)\n  - 9–11 tuần: Mũi 2 (Phòng 7 bệnh)\n  - 13–15 tuần: Mũi 3 + Tiêm dại (Rabies)\n  - Tái chủng: 1 mũi tổng hợp + 1 mũi dại mỗi năm.\n\n• **Mèo con:**\n  - 8 tuần: Mũi 1 (3 hoặc 4 bệnh: Giảm bạch cầu, Viêm mũi, Calici)\n  - 12 tuần: Mũi 2\n  - 16 tuần: Mũi Dại\n  - Tái chủng: 1 lần hàng năm.\n\n*Lưu ý: Chỉ tiêm khi bé hoàn toàn khỏe mạnh, không sốt, đã tẩy giun trước đó 1 tuần.*`,
      warningLevel: 'low',
      quickBooking: true,
    }
  }

  if (query.includes('ngộ độc') || query.includes('hóc') || query.includes('dị vật') || query.includes('sơ cứu')) {
    return {
      text: `🚨 **HƯỚNG DẪN SƠ CỨU KHẨN CẤP:**\n\n1. **Khi hóc dị vật (thở khò khè, cào miệng):**\n   - Mở miệng bé kiểm tra, chỉ dùng tay gắp nếu thấy rõ dị vật ở đầu họng.\n   - Với chó/mèo nhỏ: Giữ đầu dốc xuống 45 độ, vỗ dứt khoát 3–5 lần giữa 2 bả vai.\n   - Áp dụng thao tác đẩy bụng (Heimlich nhẹ) nếu bé bất tỉnh.\n\n2. **Khi nghi ngờ ngộ độc (sùi bọt mép, co giật, run rẩy):**\n   - **TUYỆT ĐỐI KHÔNG** tự ý gây nôn nếu nuốt hóa chất tẩy rửa, pin hoặc vật sắc nhọn.\n   - Giữ lại mẫu chất độc hoặc bao bì sản phẩm.\n   - Giữ ấm, đặt bé nằm nghiêng tránh sặc đờm.\n\n⚠️ **Mức độ: KHẨN CẤP** — Vui lòng liên hệ hotline chi nhánh PetCare gần nhất hoặc đưa bé đi cấp cứu ngay!`,
      warningLevel: 'high',
      quickBooking: true,
    }
  }

  if (query.includes('nôn') || query.includes('bỏ ăn') || query.includes('dịch vàng')) {
    return {
      text: `⚠️ **Phân tích triệu chứng Nôn dịch vàng & Bỏ ăn:**\n\n• **Nguyên nhân phổ biến:**\n  - Dạ dày bị rỗng quá lâu khiến dịch mật trào ngược.\n  - Búi lông (hairball) ở mèo gây tắc nghẽn.\n  - Viêm dạ dày cấp, nhiễm ký sinh trùng hoặc virus (Parvo, FPV).\n\n• **Chăm sóc tức thời tại nhà:**\n  - Tạm ngưng thức ăn đặc trong 4–6 giờ để ruột nghỉ ngơi.\n  - Cho uống từng ngụm nhỏ nước ấm hoặc nước điện giải Oresol (5ml/lần).\n  - Nếu bé chịu ăn lại: cho ăn cháo ức gà loãng hoặc pate tiêu hóa.\n\n⚠️ **Cảnh báo:** Mèo nhịn ăn quá 24h có nguy cơ cao bị thoái hóa mỡ gan. Nếu nôn kèm sốt, ủ rũ, hãy đưa bé đi khám sớm!`,
      warningLevel: 'medium',
      quickBooking: true,
    }
  }

  if (query.includes('tiêu chảy') || query.includes('phân lỏng') || query.includes('đi ngoài')) {
    return {
      text: `🩺 **Xử lý sơ bộ khi thú cưng bị tiêu chảy:**\n\n1. **Chống mất nước (Quan trọng nhất):** Bổ sung dung dịch bù điện giải Oresol pha đúng tỉ lệ, cho uống từng thìa nhỏ thường xuyên.\n2. **Điều chỉnh thức ăn:** Chuyển sang thực đơn dễ tiêu (cháo gạo nấu ức gà nạc, không nêm gia vị/dầu mỡ).\n3. **Men vi sinh:** Bổ sung Probiotics dành riêng cho thú cưng để phục hồi hệ vi sinh đường ruột.\n4. **Theo dõi phân:** Nếu phân có lẫn máu tươi, mùi tanh hôi nồng nặc hoặc phân đen -> **Đưa đi viện ngay lập tức** vì có thể là dấu hiệu viêm ruột Parvo!`,
      warningLevel: 'medium',
      quickBooking: true,
    }
  }

  if (query.includes('triệt sản') || query.includes('dinh dưỡng') || query.includes('sau khi mổ')) {
    return {
      text: `🍖 **Tư vấn dinh dưỡng & chăm sóc sau triệt sản:**\n\n• **Biến đổi sinh lý:** Sau triệt sản, trao đổi chất giảm 20–30% trong khi sự thèm ăn tăng, dễ dẫn đến béo phì và sỏi tiết niệu.\n• **Khẩu phần khuyên dùng:**\n  - Chọn dòng hạt/pate dành riêng cho thú cưng triệt sản (*Sterilised/Neutered*).\n  - Giảm 15–20% lượng calo nạp vào hàng ngày so với trước.\n  - Khuyến khích uống nhiều nước (dùng đài phun nước) để ngừa sỏi bàng quang.\n  - Đeo loa chống liếm (vòng Elizabeth) ít nhất 7–10 ngày cho đến khi vết khâu lành hẳn.`,
      warningLevel: 'low',
      quickBooking: false,
    }
  }

  return {
    text: `🐾 **Lời khuyên từ Bác sĩ AI PetCare:**\n\nĐối với tình trạng: *"${userText}"*:\n\n1. Sen hãy quan sát kỹ các dấu hiệu: thân nhiệt (bình thường 38–39.2°C), độ hồng hào của niêm mạc nướu, mức độ lanh lợi và lượng nước uống.\n2. Giữ thú cưng ở nơi ấm áp, yên tĩnh, tránh gió lùa và hạn chế vận động mạnh.\n3. Nếu tình trạng bất thường kéo dài trên 12–24 giờ, sen nên đưa bé đến chi nhánh PetCare để bác sĩ thăm khám và làm xét nghiệm cận lâm sàng (máu, siêu âm) chính xác nhất nhé!`,
    warningLevel: 'low',
    quickBooking: true,
  }
}

export const InteractiveMascotCompanion: React.FC = () => {
  const navigate = useNavigate()
  const [dialogueIdx, setDialogueIdx] = useState(0)
  const [showBubble, setShowBubble] = useState(false)
  const [isChatOpen, setIsChatOpen] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 'welcome-1',
      sender: 'ai',
      text: 'Xin chào sen! 🐾 Em là Bác sĩ AI PetCare được trang bị kiến thức thú y RAG. Em có thể hỗ trợ sen giải đáp sức khỏe, hướng dẫn sơ cứu khẩn cấp, lịch tiêm phòng và tư vấn dinh dưỡng cho boss.',
      time: 'Vừa xong',
    },
  ])
  const [inputText, setInputText] = useState('')
  const [isTyping, setIsTyping] = useState(false)

  const mascotRef = useRef<HTMLDivElement>(null)
  const bubbleRef = useRef<HTMLDivElement>(null)
  const chatBottomRef = useRef<HTMLDivElement>(null)
  const dismissTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearDismissTimer = useCallback(() => {
    if (dismissTimerRef.current) {
      clearTimeout(dismissTimerRef.current)
      dismissTimerRef.current = null
    }
  }, [])

  const scheduleDismiss = useCallback(() => {
    clearDismissTimer()
    dismissTimerRef.current = setTimeout(() => {
      if (bubbleRef.current && showBubble && !isChatOpen) {
        gsap.to(bubbleRef.current, {
          scale: 0.4,
          opacity: 0,
          y: 10,
          duration: 0.35,
          ease: 'power2.in',
          onComplete: () => setShowBubble(false),
        })
      }
    }, 4000)
  }, [showBubble, isChatOpen, clearDismissTimer])

  // Scroll chat to bottom
  useEffect(() => {
    if (isChatOpen) {
      chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages, isTyping, isChatOpen])

  // Global paw particle effects on background click
  useEffect(() => {
    const handleGlobalClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('button') || target.closest('a') || target.closest('input') || target.closest('.ai-chat-window')) return

      for (let i = 0; i < 2; i++) {
        const paw = document.createElement('div')
        paw.style.position = 'fixed'
        paw.style.pointerEvents = 'none'
        paw.style.zIndex = '99999'
        paw.style.width = '22px'
        paw.style.height = '22px'
        const color = i === 0 ? '#e33529' : '#fff500'
        paw.innerHTML = `
          <svg viewBox="0 0 24 24" fill="${color}" width="100%" height="100%">
            <circle cx="6" cy="6" r="2.5"/><circle cx="18" cy="6" r="2.5"/>
            <circle cx="4" cy="12" r="2"/><circle cx="20" cy="12" r="2"/>
            <ellipse cx="12" cy="16" rx="5" ry="4"/>
          </svg>
        `
        const offsetX = (Math.random() - 0.5) * 30
        const offsetY = (Math.random() - 0.5) * 30
        paw.style.left = `${e.clientX + offsetX}px`
        paw.style.top = `${e.clientY + offsetY}px`
        paw.style.transform = 'translate(-50%, -50%) scale(0.3)'
        document.body.appendChild(paw)

        gsap.to(paw, {
          scale: 1,
          rotation: (Math.random() - 0.5) * 60,
          y: -25,
          opacity: 0,
          duration: 0.6,
          ease: 'power2.out',
          onComplete: () => paw.remove(),
        })
      }
    }

    window.addEventListener('click', handleGlobalClick)
    return () => {
      window.removeEventListener('click', handleGlobalClick)
      clearDismissTimer()
    }
  }, [clearDismissTimer])

  const handleMascotClick = (e: React.MouseEvent) => {
    e.stopPropagation()
    clearDismissTimer()
    setShowBubble(false)
    setIsChatOpen((prev) => !prev)

    if (mascotRef.current) {
      gsap.fromTo(
        mascotRef.current,
        { scale: 0.85, rotation: -12 },
        { scale: 1, rotation: 0, duration: 0.45, ease: 'elastic.out(1.5, 0.4)' }
      )
    }
  }

  const handleSendMessage = (textToSend?: string) => {
    const query = (textToSend || inputText).trim()
    if (!query) return

    const now = new Date()
    const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`

    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
      sender: 'user',
      text: query,
      time: timeStr,
    }

    setMessages((prev) => [...prev, userMsg])
    setInputText('')
    setIsTyping(true)

    // Simulate RAG LLM response
    setTimeout(() => {
      const response = getAIResponse(query)
      const aiMsg: ChatMessage = {
        id: `ai-${Date.now()}`,
        sender: 'ai',
        text: response.text,
        time: `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`,
        warningLevel: response.warningLevel,
        quickBooking: response.quickBooking,
      }
      setMessages((prev) => [...prev, aiMsg])
      setIsTyping(false)
    }, 850)
  }

  const handleResetChat = () => {
    setMessages([
      {
        id: 'welcome-reset',
        sender: 'ai',
        text: 'Cuộc trò chuyện đã được làm mới. Sen muốn Bác sĩ AI hỗ trợ về sức khỏe hoặc dinh dưỡng cho boss nào ạ? 🐾',
        time: 'Vừa xong',
      },
    ])
  }

  return (
    <aside
      aria-label="Bác sĩ AI & Mascot PetCare"
      className="fixed bottom-24 right-6 z-50 flex flex-col items-end pointer-events-none select-none"
    >
      {/* ================================================================
          1. AI Chatbot Window (RAG / LLM Health & Nutrition Advisor)
          ================================================================ */}
      {isChatOpen && (
        <div
          className="ai-chat-window pointer-events-auto mb-3 w-[360px] sm:w-[400px] h-[520px] max-h-[80vh] flex flex-col rounded-3xl bg-white shadow-[0_20px_50px_rgba(59,42,30,0.22)] border border-(--color-border-default) overflow-hidden transition-all duration-300 animate-in fade-in slide-in-from-bottom-5"
        >
          {/* Header */}
          <div className="bg-linear-to-r from-accent via-[#b63d2d] to-accent-hover px-4 py-3.5 text-white flex items-center justify-between shadow-sm">
            <div className="flex items-center gap-3">
              <div className="relative">
                <div className="w-9 h-9 rounded-full bg-white/15 backdrop-blur-sm p-1 border border-white/30 flex items-center justify-center">
                  <Stethoscope className="w-5 h-5 text-white" />
                </div>
                <span className="absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full bg-emerald-400 ring-2 ring-accent" />
              </div>
              <div>
                <div className="flex items-center gap-1.5">
                  <h3 className="font-bold text-sm tracking-wide leading-none">Bác Sĩ AI PetCare</h3>
                  <span className="bg-amber-300 text-accent-hover text-[10px] font-extrabold px-1.5 py-0.5 rounded-full uppercase tracking-wider flex items-center gap-0.5">
                    <Sparkles size={10} /> RAG
                  </span>
                </div>
                <p className="text-[11px] text-white/80 mt-0.5">Tư vấn sức khỏe & dinh dưỡng 24/7</p>
              </div>
            </div>

            <div className="flex items-center gap-1">
              <button
                onClick={handleResetChat}
                title="Làm mới cuộc trò chuyện"
                className="p-1.5 rounded-lg text-white/80 hover:text-white hover:bg-white/10 transition-colors"
              >
                <RotateCcw size={15} />
              </button>
              <button
                onClick={() => setIsChatOpen(false)}
                title="Thu nhỏ"
                className="p-1.5 rounded-lg text-white/80 hover:text-white hover:bg-white/10 transition-colors"
              >
                <Minimize2 size={16} />
              </button>
            </div>
          </div>

          {/* Chat Messages Body */}
          <div className="flex-1 overflow-y-auto p-4 space-y-3.5 bg-[#fdfbf9] text-xs">
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={`flex flex-col ${msg.sender === 'user' ? 'items-end' : 'items-start'}`}
              >
                <div
                  className={`max-w-[85%] rounded-2xl px-3.5 py-2.5 shadow-sm whitespace-pre-line leading-relaxed ${
                    msg.sender === 'user'
                      ? 'bg-accent text-white rounded-br-xs'
                      : 'bg-white text-(--color-text-primary) border border-stone-200/80 rounded-bl-xs'
                  }`}
                >
                  {msg.warningLevel === 'high' && (
                    <div className="flex items-center gap-1.5 text-red-600 font-bold mb-1.5 pb-1 border-b border-red-100">
                      <AlertTriangle size={14} className="shrink-0" />
                      <span>CẢNH BÁO NGUY HIỂM</span>
                    </div>
                  )}
                  {msg.warningLevel === 'medium' && (
                    <div className="flex items-center gap-1.5 text-amber-600 font-semibold mb-1.5 pb-1 border-b border-amber-100">
                      <AlertTriangle size={14} className="shrink-0" />
                      <span>CẦN THEO DÕI SÁT</span>
                    </div>
                  )}

                  {msg.text}

                  {msg.quickBooking && (
                    <div className="mt-2.5 pt-2 border-t border-stone-100 flex justify-end">
                      <button
                        onClick={() => navigate('/booking')}
                        className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-accent text-white text-[11px] font-semibold hover:bg-accent-hover transition-colors shadow-xs"
                      >
                        <Calendar size={12} />
                        Đặt lịch khám ngay
                        <ChevronRight size={12} />
                      </button>
                    </div>
                  )}
                </div>
                <span className="text-[10px] text-stone-400 mt-1 px-1">{msg.time}</span>
              </div>
            ))}

            {isTyping && (
              <div className="flex items-center gap-2 text-stone-400 text-[11px] bg-white border border-stone-200/80 px-3 py-2 rounded-2xl rounded-bl-xs w-fit shadow-xs">
                <div className="flex gap-1">
                  <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce" />
                  <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce [animation-delay:0.2s]" />
                  <span className="w-1.5 h-1.5 bg-accent rounded-full animate-bounce [animation-delay:0.4s]" />
                </div>
                <span>Bác sĩ AI đang đối chiếu dữ liệu thú y...</span>
              </div>
            )}

            <div ref={chatBottomRef} />
          </div>

          {/* Quick Prompts Carousel */}
          <div className="px-3 py-2 bg-stone-50 border-t border-stone-100 flex gap-1.5 overflow-x-auto no-scrollbar shrink-0">
            {QUICK_PROMPTS.map((qp, idx) => (
              <button
                key={idx}
                onClick={() => handleSendMessage(qp.query)}
                className="whitespace-nowrap rounded-full bg-white hover:bg-accent hover:text-white border border-stone-200 px-2.5 py-1 text-[11px] font-medium text-stone-700 transition-colors shadow-2xs shrink-0"
              >
                {qp.label}
              </button>
            ))}
          </div>

          {/* Input Footer */}
          <div className="p-3 bg-white border-t border-stone-200/80 flex items-center gap-2 shrink-0">
            <input
              type="text"
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSendMessage()}
              placeholder="Hỏi triệu chứng, dinh dưỡng, sơ cứu..."
              className="flex-1 bg-stone-100 border border-stone-200/80 focus:border-accent rounded-xl px-3 py-2 text-xs text-(--color-text-primary) outline-hidden transition-all"
            />
            <button
              onClick={() => handleSendMessage()}
              disabled={!inputText.trim()}
              className="p-2 rounded-xl bg-accent text-white disabled:opacity-40 disabled:cursor-not-allowed hover:bg-accent-hover transition-colors shrink-0 shadow-xs"
              title="Gửi câu hỏi"
            >
              <Send size={15} />
            </button>
          </div>
        </div>
      )}

      {/* ================================================================
          2. Speech Bubble (when chat is closed)
          ================================================================ */}
      {showBubble && !isChatOpen && (
        <div
          ref={bubbleRef}
          className="mb-3 max-w-[250px] rounded-2xl rounded-br-sm bg-white px-4 py-3 text-xs font-semibold text-[#3B2A1E] shadow-[0_8px_30px_rgba(0,0,0,0.14)] border border-accent/20 pointer-events-auto"
          style={{ transformOrigin: 'bottom right' }}
        >
          <div className="flex items-start justify-between gap-2">
            <div className="flex items-start gap-2">
              <PawPrint size={14} className="text-accent shrink-0 mt-0.5" />
              <span>{DIALOGUES[dialogueIdx]}</span>
            </div>
            <button
              onClick={(e) => {
                e.stopPropagation()
                setShowBubble(false)
              }}
              aria-label="Đóng bóng thoại"
              className="text-accent/50 hover:text-accent text-xs leading-none p-1 -mr-1 -mt-0.5"
            >
              <X size={12} />
            </button>
          </div>
          <button
            onClick={() => {
              setShowBubble(false)
              setIsChatOpen(true)
            }}
            className="mt-2 w-full flex items-center justify-center gap-1.5 py-1 px-2 rounded-lg bg-accent text-white text-[11px] font-bold hover:bg-accent-hover transition-colors shadow-xs"
          >
            <Sparkles size={11} />
            Hỏi Bác Sĩ AI ngay
          </button>
          {/* Tail pointer */}
          <div className="absolute -bottom-2 right-5 w-4 h-4 bg-white border-r border-b border-accent/20 transform rotate-45" />
        </div>
      )}

      {/* ================================================================
          3. Mascot Dog Button (Click to toggle Chatbot AI)
          ================================================================ */}
      <div
        ref={mascotRef}
        onClick={handleMascotClick}
        onMouseEnter={() => {
          if (!showBubble && !isChatOpen) {
            setShowBubble(true)
            setDialogueIdx((prev) => (prev + 1) % DIALOGUES.length)
            scheduleDismiss()
          }
        }}
        onMouseLeave={clearDismissTimer}
        className="relative w-13 h-13 rounded-full bg-linear-to-br from-[#faebe4] via-white to-[#faebe4] p-1 shadow-[0_10px_26px_rgba(164,51,36,0.28)] border-2 border-white cursor-pointer pointer-events-auto hover:scale-110 active:scale-95 transition-all duration-200 flex items-center justify-center group"
        title="Bấm để chat với Bác sĩ AI PetCare!"
      >
        <img
          src="/imgs/DogSticker.svg"
          alt="Bác sĩ AI PetCare"
          className="w-full h-full object-contain drop-shadow-xs"
        />
        {/* Floating AI badge */}
        <span className="absolute -top-1.5 -right-1 bg-linear-to-r from-accent to-[#e33529] text-white text-[9px] font-black px-1.5 py-0.5 rounded-full border border-white shadow-xs flex items-center gap-0.5 tracking-wider animate-pulse">
          <Sparkles size={8} /> AI
        </span>
      </div>
    </aside>
  )
}
