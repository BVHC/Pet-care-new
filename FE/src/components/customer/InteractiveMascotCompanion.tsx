import React, { useState, useRef, useEffect, useCallback } from 'react'
import { PawPrint, X, Sparkles } from 'lucide-react'
import gsap from 'gsap'
import { ChatWindow } from './ai/ChatWindow'
import { useChat } from './ai/useChat'

const DIALOGUES = [
  'Gâu gâu! Sen cần hỏi trợ lý AI về chăm sóc em không?',
  'Phòng điều hòa ở PetCare mát rượi luôn sen ơi!',
  'Bấm vào em để tra cứu lịch tiêm phòng nhé!',
  'Hôm nay em được ăn ức gà áp chảo thơm lừng!',
  'Sen muốn biết lịch tiêm của em? Bấm vào em nè!',
  'Yêu thương trọn vẹn 5 sao chuẩn quốc tế!',
]

/** Mascot nổi ở góc phải; bấm vào để mở khung chat với trợ lý AI (UC90, UC91). Khung chat không gọi API cho tới khi người dùng gửi câu hỏi. */
export const InteractiveMascotCompanion: React.FC = () => {
  const [dialogueIdx, setDialogueIdx] = useState(0)
  const [showBubble, setShowBubble] = useState(false)
  const [isChatOpen, setIsChatOpen] = useState(false)
  const chat = useChat()

  const mascotRef = useRef<HTMLButtonElement>(null)
  const bubbleRef = useRef<HTMLDivElement>(null)
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

  return (
    <aside
      aria-label="Trợ lý AI & Mascot PetCare"
      className="fixed bottom-24 right-6 z-50 flex flex-col items-end pointer-events-none select-none"
    >
      {isChatOpen && <ChatWindow chat={chat} onClose={() => setIsChatOpen(false)} />}

      {/* Speech bubble (khi chat đóng) */}
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
            Hỏi trợ lý AI ngay
          </button>
          {/* Tail pointer */}
          <div className="absolute -bottom-2 right-5 w-4 h-4 bg-white border-r border-b border-accent/20 transform rotate-45" />
        </div>
      )}

      {/* Mascot: nút bật / tắt khung chat */}
      <button
        type="button"
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
        aria-expanded={isChatOpen}
        className="relative w-13 h-13 rounded-full bg-linear-to-br from-[#faebe4] via-white to-[#faebe4] p-1 shadow-[0_10px_26px_rgba(164,51,36,0.28)] border-2 border-white cursor-pointer pointer-events-auto hover:scale-110 active:scale-95 transition-all duration-200 flex items-center justify-center group"
        title="Bấm để chat với trợ lý AI PetCare!"
      >
        <img src="/imgs/DogSticker.svg" alt="Trợ lý AI PetCare" className="w-full h-full object-contain drop-shadow-xs" />
        <span className="absolute -top-1.5 -right-1 bg-linear-to-r from-accent to-[#e33529] text-white text-[9px] font-black px-1.5 py-0.5 rounded-full border border-white shadow-xs flex items-center gap-0.5 tracking-wider animate-pulse">
          <Sparkles size={8} /> AI
        </span>
      </button>
    </aside>
  )
}
