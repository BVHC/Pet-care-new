/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL: string
  readonly VITE_APP_TITLE: string
  /** 'true' | 'false'. Không đặt: bật mock khi chạy dev, tắt khi build. */
  readonly VITE_API_MOCK?: string
  /** Chỉ dùng khi VITE_API_MOCK=false: các đường dẫn (cách nhau bằng dấu phẩy) vẫn trả bằng mock. Xem mock/mock-scope.ts. */
  readonly VITE_API_MOCK_ONLY?: string
  /** Client ID OAuth của Google (cùng giá trị với GOOGLE_CLIENT_ID của BE). Trống: ẩn nút Google. */
  readonly VITE_GOOGLE_CLIENT_ID?: string
  /** Cloudinary: tải ảnh sản phẩm lên bằng unsigned preset (ADR-0158). Thiếu một trong hai: nút "Tải ... lên" bị tắt. */
  readonly VITE_CLOUDINARY_CLOUD_NAME?: string
  readonly VITE_CLOUDINARY_UPLOAD_PRESET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
