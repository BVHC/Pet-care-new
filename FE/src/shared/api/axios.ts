import axios from 'axios'
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

if (API_MOCK) {
  installMockApi(apiClient)
} else if (MOCK_ONLY.length > 0) {
  installHybridMockApi(apiClient, MOCK_ONLY, () => auth.account?.() ?? null)
  console.info(`[API] Chế độ hỗn hợp: BE thật, riêng ${MOCK_ONLY.length} luật dùng mock (VITE_API_MOCK_ONLY)`)
}

interface AuthHooks {
  token: () => string | null
  /** BE trả 401: phiên bị hủy / hết hạn. */
  onUnauthorized: () => void
  /** BE trả 400 BR-TK-17: phải đổi mật khẩu trước. */
  onMustChangePassword: () => void
  /** Tài khoản đang đăng nhập; chế độ hỗn hợp cần để mock biết người gọi (mock không đọc được JWT của BE). */
  account?: () => AccountSummary | null
}

let auth: AuthHooks = { token: () => null, onUnauthorized: () => {}, onMustChangePassword: () => {} }

/** Store phiên tự gắn vào đây, tránh vòng import axios ↔ store. */
export function bindAuth(hooks: AuthHooks) {
  auth = hooks
}

apiClient.interceptors.request.use((config) => {
  const token = auth.token()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Không có refresh token (ADR-0003): 401 là hết phiên, guard route sẽ đưa về trang đăng nhập.
apiClient.interceptors.response.use(undefined, (error) => {
  const status = error.response?.status
  if (status === 401) auth.onUnauthorized()
  if (status === 400 && ruleIdOf(String(error.response?.data?.message ?? '')) === 'BR-TK-17') {
    auth.onMustChangePassword()
  }
  return Promise.reject(error)
})

export default apiClient
