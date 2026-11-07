import { useCallback, useState } from 'react'
import { plainMessage, toApiError } from '../../../shared/api/api-error'
import { useAskChatbot } from '../../../shared/hooks/useAi'
import { useAccount } from '../../../shared/stores/session.store'
import { AI_LIMITS, type ChatResponse, type PetChoice } from '../../../shared/types/ai'

// UC90, UC91: trạng thái khung chat. Không lưu hội thoại phía máy chủ (BR-AI-08): mỗi lượt chỉ gửi tối đa 3 câu hỏi trước CỦA CHÍNH người dùng.

export type ChatEntry =
  | { id: string; from: 'user'; text: string; echo?: boolean }
  | { id: string; from: 'bot'; note: string }
  | { id: string; from: 'bot'; response: ChatResponse }
  | { id: string; from: 'bot'; error: string }
  /** Lịch tiêm của một thú (#3), tải khi thẻ hiện ra. */
  | { id: string; from: 'bot'; vaccinationOf: PetChoice }

const WELCOME =
  'Xin chào! Mình là trợ lý AI của PetCare. Mình trả lời về chăm sóc cơ bản, dinh dưỡng, vệ sinh, hành vi thường gặp và lịch tiêm phòng. Mình không chẩn đoán bệnh hay kê thuốc.'

let seq = 0
const nextId = () => `chat-${++seq}`

export function useChat() {
  const account = useAccount()
  // Chỉ CUSTOMER gọi được /me/ai/chat (nhân viên đăng nhập vẫn dùng endpoint công khai).
  const ask = useAskChatbot(account?.role === 'CUSTOMER')
  const [entries, setEntries] = useState<ChatEntry[]>(() => [{ id: nextId(), from: 'bot', note: WELCOME }])
  // Mascot nằm trong layout nên sống qua đăng nhập / đăng xuất: đổi tài khoản thì bỏ cuộc trò chuyện cũ (câu trả lời cá nhân và các câu hỏi trước không được sang người khác).
  const accountId = account?.id ?? null
  const [owner, setOwner] = useState(accountId)
  if (owner !== accountId) {
    setOwner(accountId)
    setEntries([{ id: nextId(), from: 'bot', note: WELCOME }])
  }

  const append = useCallback((entry: ChatEntry) => setEntries((prev) => [...prev, entry]), [])

  const send = async (raw: string) => {
    const question = raw.trim().slice(0, AI_LIMITS.question)
    if (!question || ask.isPending) return
    const previousQuestions = entries
      .flatMap((e) => (e.from === 'user' && !e.echo ? [e.text] : []))
      .slice(-AI_LIMITS.previousQuestions)
    append({ id: nextId(), from: 'user', text: question })
    try {
      const response = await ask.mutateAsync({ question, ...(previousQuestions.length ? { previousQuestions } : {}) })
      append({ id: nextId(), from: 'bot', response })
    } catch (e) {
      // 429 (quá nhiều lần trong một phút / giờ), 400, mất mạng: báo ngay trong khung, không chặn trang.
      append({ id: nextId(), from: 'bot', error: plainMessage(toApiError(e)) })
    }
  }

  /** NEED_PET_CHOICE: khách chọn thú thì xem lịch tiêm (#3), không gửi thêm câu hỏi lên LLM. */
  const choosePet = (pet: PetChoice) => {
    append({ id: nextId(), from: 'user', text: pet.name, echo: true })
    append({ id: nextId(), from: 'bot', vaccinationOf: pet })
  }

  const reset = () => setEntries([{ id: nextId(), from: 'bot', note: 'Cuộc trò chuyện đã được làm mới. Bạn muốn hỏi gì tiếp theo?' }])

  return { entries, pending: ask.isPending, send, choosePet, reset }
}
