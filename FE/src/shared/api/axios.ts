import axios from 'axios'
import { ruleIdOf } from './api-error'
import { installMockApi } from './mock/mock-api'

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

if (API_MOCK) installMockApi(apiClient)

interface AuthHooks {
  token: () => string | null
  /** BE trả 401: phiên bị hủy / hết hạn. */
  onUnauthorized: () => void
  /** BE trả 400 BR-TK-17: phải đổi mật khẩu trước. */
  onMustChangePassword: () => void
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
