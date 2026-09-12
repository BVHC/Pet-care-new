import React, { useState, useRef, useEffect } from 'react'
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

export const InteractiveMascotCompanion: React.FC = () => {
  const [dialogueIdx, setDialogueIdx] = useState(0)
  const [showBubble, setShowBubble] = useState(true)
  const mascotRef = useRef<HTMLDivElement>(null)
  const bubbleRef = useRef<HTMLDivElement>(null)

  // Paw click particles anywhere on body
  useEffect(() => {
    const handleGlobalClick = (e: MouseEvent) => {
      // Don't trigger if clicked on buttons or links
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
    return () => window.removeEventListener('click', handleGlobalClick)
  }, [])

  const handleMascotClick = (e: React.MouseEvent) => {
    e.stopPropagation()
    setDialogueIdx((prev) => (prev + 1) % DIALOGUES.length)
    setShowBubble(true)

    if (mascotRef.current) {
      gsap.fromTo(
        mascotRef.current,
        { scale: 0.85, rotation: -10 },
        { scale: 1, rotation: 0, duration: 0.6, ease: 'elastic.out(1.5, 0.3)' }
      )
    }

    if (bubbleRef.current) {
      gsap.fromTo(
        bubbleRef.current,
        { scale: 0.4, opacity: 0, y: 15 },
        { scale: 1, opacity: 1, y: 0, duration: 0.4, ease: 'back.out(2)' }
      )
    }
  }

  return (
    <aside
      aria-label="Mascot trợ lý PetCare"
      className="fixed bottom-6 right-6 z-50 flex flex-col items-end pointer-events-none select-none"
    >
      {/* Speech Bubble */}
      {showBubble && (
        <div
          ref={bubbleRef}
          className="mb-2 max-w-[220px] rounded-2xl rounded-br-xs bg-[#faebe4] px-4 py-2.5 text-xs font-bold text-[#3B2A1E] shadow-xl border border-[#a43324]/20 pointer-events-auto transition-all"
        >
          <div className="flex items-start justify-between gap-1">
            <span className="flex items-start gap-1">
              <PawPrint size={14} className="text-[#a43324] shrink-0 mt-0.5" />
              <span>{DIALOGUES[dialogueIdx]}</span>
            </span>
            <button
              onClick={(e) => {
                e.stopPropagation()
                setShowBubble(false)
              }}
              aria-label="Đóng bóng thoại"
              className="text-[#a43324]/60 hover:text-[#a43324] text-xs ml-1"
            >
              ✕
            </button>
          </div>
        </div>
      )}

      {/* Mascot Dog Button */}
      <div
        ref={mascotRef}
        onClick={handleMascotClick}
        className="w-16 h-16 sm:w-18 sm:h-18 rounded-full bg-[#faebe4] p-2.5 shadow-[0_10px_25px_rgba(164,51,36,0.25)] border-2 border-white cursor-pointer pointer-events-auto hover:scale-110 active:scale-95 transition-transform flex items-center justify-center"
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
