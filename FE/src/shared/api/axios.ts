import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { AccountSummary } from '../types/auth'
import { ruleIdOf } from './api-error'
import { installHybridMockApi, installMockApi } from './mock/mock-api'
import { parseMockOnly } from './mock/mock-scope'

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081'

/**
 * Mock theo hợp đồng (./mock): mặc định bật khi `npm run dev`, tắt khi build.
 * Gọi BE thật lúc dev: đặt `VITE_API_MOCK=false` trong FE/.env.
 */
export const API_MOCK = (import.meta.env.VITE_API_MOCK ?? String(import.meta.env.DEV)) === 'true'

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

/**
 * Chế độ hỗn hợp: VITE_API_MOCK=false (BE thật) kèm VITE_API_MOCK_ONLY thì các đường dẫn trong danh sách vẫn trả bằng mock
 * (cho phần BE chưa có), còn lại gọi BE thật. Ví dụ: `/api/public/branches, GET /api/pets/*\/health-record`.
 * Mock hỗn hợp xác thực bằng tài khoản của phiên đăng nhập thật và có dữ liệu riêng, không liên thông với DB của BE.
 */
const MOCK_ONLY = parseMockOnly(import.meta.env.VITE_API_MOCK_ONLY)

/** Nguồn dữ liệu của phiên này: `main.tsx` hiện nó ở góc màn hình lúc dev để biết đang xem dữ liệu thật hay mock. */
export const API_MODE: 'mock' | 'hybrid' | 'real' = API_MOCK ? 'mock' : MOCK_ONLY.length > 0 ? 'hybrid' : 'real'

if (API_MOCK) {
  installMockApi(apiClient)
} else if (MOCK_ONLY.length > 0) {
  installHybridMockApi(apiClient, MOCK_ONLY, () => auth.account?.() ?? null)
  console.info(`[API] Chế độ hỗn hợp: BE thật, riêng ${MOCK_ONLY.length} luật dùng mock (VITE_API_MOCK_ONLY)`)
}

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Request theo nhịp (React Query refetchInterval): không bao giờ làm mới token, 401 không kết thúc phiên (D055, ADR-0091). Dùng `POLL`. */
    poll?: boolean
    /** Đã thử lại một lần sau 401. */
    retried?: boolean
  }
}

/** Truyền cho hàm API của mọi request poll: `visitApi.listQueue(query, POLL)`. */
export const POLL = { poll: true } as const

interface AuthHooks {
  token: () => string | null
  /** Phiên hết thật (làm mới thất bại, hoặc 401 mà không làm mới được): về trang đăng nhập. */
  onUnauthorized: () => void
  /** BE trả 400 BR-TK-17: phải đổi mật khẩu trước. */
  onMustChangePassword: () => void
  /** Tài khoản đang đăng nhập; chế độ hỗn hợp cần để mock biết người gọi (mock không đọc được JWT của BE). */
  account?: () => AccountSummary | null
  /** Trước mỗi request: request của người dùng làm mới token nếu sắp hết, request poll chỉ đợi lượt làm mới đang chạy. Null = không phải đợi gì. Reject = phiên đã kết thúc. */
  prepare: (poll: boolean) => Promise<void> | null
  /** Sau 401: lấy token mới (đọc lại storage; request của người dùng thì làm mới). True nếu token đã khác `sent`, tức thử lại được. */
  recover: (sent: string | null, poll: boolean) => Promise<boolean>
}

let auth: AuthHooks = { token: () => null, onUnauthorized: () => {}, onMustChangePassword: () => {}, prepare: () => null, recover: async () => false }

/** Store phiên tự gắn vào đây, tránh vòng import axios ↔ store. */
export function bindAuth(hooks: AuthHooks) {
  auth = hooks
}

/** Path public (identity-v1 §3.1, ADR-0005): luôn xử lý như chưa đăng nhập nên không làm mới và không thử lại; gồm chính /auth/refresh. */
const PUBLIC_PATH = /^\/api\/(auth\/(register|login|refresh|password)|public)(\/|$)/

// Đường thường (không phải đợi gì) giữ đồng bộ như trước: thêm nhịp microtask làm lệch nhịp của các test kiểm tra ngay sau `await user.click`.
apiClient.interceptors.request.use((config) => {
  const attach = () => {
    const token = auth.token()
    if (token && config.url !== '/api/auth/refresh') config.headers.Authorization = `Bearer ${token}` // refresh xác thực bằng refresh token, không cần bearer
    return config
  }
  const wait = PUBLIC_PATH.test(config.url ?? '') ? null : auth.prepare(!!config.poll)
  return wait ? wait.then(attach) : attach()
})

// 401 (ADR-0091): request mang token cũ vì token vừa đổi thì thử lại đúng một lần; request của người dùng còn làm mới được thì làm mới rồi thử lại.
// Poll không bao giờ kết thúc phiên: lệch đồng hồ máy / BE sẽ đăng xuất người đang rảnh đúng lúc token hết hạn thay vì chỉ tạm dừng poll.
async function recoverFrom401(error: AxiosError, config: InternalAxiosRequestConfig) {
  const sent = String(config.headers?.Authorization ?? '').replace(/^Bearer /, '') || null
  if (!config.retried && (await auth.recover(sent, !!config.poll).catch(() => false))) {
    config.retried = true
    return apiClient.request(config)
  }
  if (!config.poll) auth.onUnauthorized()
  throw error
}

apiClient.interceptors.response.use(undefined, (error: AxiosError) => {
  const status = error.response?.status
  const config = error.config
  if (status === 400 && ruleIdOf(String((error.response?.data as { message?: unknown } | undefined)?.message ?? '')) === 'BR-TK-17') {
    auth.onMustChangePassword()
  }
  if (status === 401 && config && !PUBLIC_PATH.test(config.url ?? '')) return recoverFrom401(error, config)
  return Promise.reject(error)
})

export default apiClient
