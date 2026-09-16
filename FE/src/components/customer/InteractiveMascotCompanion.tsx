import React, { useState, useRef, useEffect, useCallback } from 'react'
import { PawPrint } from 'lucide-react'
import gsap from 'gsap'

const DIALOGUES = [
  'Gâu gâu! Sen đặt lịch cho em ở PetCare nhé!',
  'Phòng điều hòa ở PetCare mát rượi luôn sen ơi!',
  'Camera 24/7 nét căng, sen ngắm em ngủ cả ngày!',
  'Hôm nay em được ăn ức gà áp chảo thơm lừng!',
  'Em được dắt dạo công viên tung tăng mỗi sáng!',
  'Yêu thương trọn vẹn 5 sao chuẩn quốc tế!',
]

const AUTO_DISMISS_DELAY = 4000 // 4 seconds

export const InteractiveMascotCompanion: React.FC = () => {
  const [dialogueIdx, setDialogueIdx] = useState(0)
  const [showBubble, setShowBubble] = useState(false)
  const mascotRef = useRef<HTMLDivElement>(null)
  const bubbleRef = useRef<HTMLDivElement>(null)
  const dismissTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Clear dismiss timer
  const clearDismissTimer = useCallback(() => {
    if (dismissTimerRef.current) {
      clearTimeout(dismissTimerRef.current)
      dismissTimerRef.current = null
    }
  }, [])

  // Auto-dismiss after 4 seconds
  const scheduleDismiss = useCallback(() => {
    clearDismissTimer()
    dismissTimerRef.current = setTimeout(() => {
      if (bubbleRef.current && showBubble) {
        gsap.to(bubbleRef.current, {
          scale: 0.4,
          opacity: 0,
          y: 10,
          duration: 0.35,
          ease: 'power2.in',
          onComplete: () => setShowBubble(false),
        })
      }
    }, AUTO_DISMISS_DELAY)
  }, [showBubble, clearDismissTimer])

  // Paw click particles anywhere on body
  useEffect(() => {
    const handleGlobalClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('button') || target.closest('a') || target.closest('input')) return

      for (let i = 0; i < 2; i++) {
        const paw = document.createElement('div')
        paw.style.position = 'fixed'
        paw.style.pointerEvents = 'none'
        paw.style.zIndex = '99999'
        paw.style.width = '24px'
        paw.style.height = '24px'
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
    setDialogueIdx((prev) => (prev + 1) % DIALOGUES.length)

    if (!showBubble && mascotRef.current) {
      // First interaction: expand from mascot
      setShowBubble(true)
      requestAnimationFrame(() => {
        if (bubbleRef.current) {
          gsap.fromTo(
            bubbleRef.current,
            { scale: 0.3, opacity: 0, y: 8 },
            { scale: 1, opacity: 1, y: 0, duration: 0.45, ease: 'back.out(2.2)' }
          )
        }
        if (mascotRef.current) {
          gsap.fromTo(
            mascotRef.current,
            { scale: 0.85, rotation: -8 },
            { scale: 1, rotation: 0, duration: 0.5, ease: 'elastic.out(1.5, 0.4)' }
          )
        }
      })
    } else if (mascotRef.current) {
      // Already showing: bounce mascot
      gsap.fromTo(
        mascotRef.current,
        { scale: 0.9, rotation: -6 },
        { scale: 1, rotation: 0, duration: 0.4, ease: 'elastic.out(1.5, 0.4)' }
      )
    }

    scheduleDismiss()
  }

  const handleDismiss = (e: React.MouseEvent) => {
    e.stopPropagation()
    clearDismissTimer()
    if (bubbleRef.current) {
      gsap.to(bubbleRef.current, {
        scale: 0.3,
        opacity: 0,
        y: 8,
        duration: 0.3,
        ease: 'power2.in',
        onComplete: () => setShowBubble(false),
      })
    }
  }

  return (
    <aside
      aria-label="Mascot trợ lý PetCare"
      className="fixed bottom-24 right-6 z-50 flex flex-col items-end pointer-events-none select-none"
    >
      {/* Speech Bubble - Only shows on user interaction */}
      {showBubble && (
        <div
          ref={bubbleRef}
          className="mb-3 max-w-[240px] rounded-2xl rounded-br-sm bg-white px-4 py-3 text-xs font-semibold text-[#3B2A1E] shadow-[0_8px_30px_rgba(0,0,0,0.12)] border border-[#a43324]/15 pointer-events-auto"
          style={{ transformOrigin: 'bottom right' }}
        >
          <div className="flex items-start justify-between gap-2">
            <div className="flex items-start gap-2">
              <PawPrint size={14} className="text-[#a43324] shrink-0 mt-0.5" />
              <span>{DIALOGUES[dialogueIdx]}</span>
            </div>
            <button
              onClick={handleDismiss}
              aria-label="Đóng bóng thoại"
              className="text-[#a43324]/50 hover:text-[#a43324] text-xs leading-none p-1 -mr-1 -mt-0.5"
            >
              ✕
            </button>
          </div>
          {/* Tail pointer */}
          <div className="absolute -bottom-2 right-5 w-4 h-4 bg-white border-r border-b border-[#a43324]/15 transform rotate-45" />
        </div>
      )}

      {/* Mascot Dog Button - Compact 48px circle */}
      <div
        ref={mascotRef}
        onClick={handleMascotClick}
        onMouseEnter={() => {
          if (!showBubble) {
            setShowBubble(true)
            requestAnimationFrame(() => {
              if (bubbleRef.current) {
                gsap.fromTo(
                  bubbleRef.current,
                  { scale: 0.3, opacity: 0, y: 8 },
                  { scale: 1, opacity: 1, y: 0, duration: 0.4, ease: 'back.out(2.2)' }
                )
              }
            })
            scheduleDismiss()
          }
        }}
        onMouseLeave={clearDismissTimer}
        className="w-12 h-12 rounded-full bg-gradient-to-br from-[#faebe4] to-white p-1.5 shadow-[0_8px_24px_rgba(164,51,36,0.2)] border-2 border-white cursor-pointer pointer-events-auto hover:scale-110 active:scale-95 transition-transform duration-200 flex items-center justify-center"
        title="Bấm vào em nè gâu gâu!"
      >
        <img
          src="/imgs/DogSticker.svg"
          alt="PetCare Mascot"
          className="w-full h-full object-contain"
        />
      </div>
    </aside>
  )
}
