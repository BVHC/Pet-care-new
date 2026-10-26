/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL: string
  readonly VITE_APP_TITLE: string
  /** 'true' | 'false'. Không đặt: bật mock khi chạy dev, tắt khi build. */
  readonly VITE_API_MOCK?: string
  /** Chỉ dùng khi VITE_API_MOCK=false: các đường dẫn (cách nhau bằng dấu phẩy) vẫn trả bằng mock. Xem mock/mock-scope.ts. */
  readonly VITE_API_MOCK_ONLY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
