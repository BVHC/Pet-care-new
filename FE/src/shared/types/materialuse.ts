// Vật tư tiêu hao theo lượt và thiếu hụt (ST07) theo docs/api/materialuse-v1.md #1–3 (mục Schema). Trường `?` có thể vắng hoặc null.

/** Một dòng `visit_material_usages`: `normQty = issuedQty + shortageQty`. Không phải dòng Order (BR-KB-12). */
export interface MaterialUsage {
  usageId: number;
  visitId: number;
  serviceId: number;
  serviceName: string;
  productId: number;
  productName: string;
  unit: string;
  /** Theo định mức tại lúc hoàn tất lượt (BR-SP-10). */
  normQty: number;
  /** Đã xuất kho. */
  issuedQty: number;
  /** Thiếu tồn lúc hoàn tất; 0 = đủ. */
  shortageQty: number;
  /** Có = đã xử lý thiếu hụt. */
  resolvedAt?: string | null;
  resolvedNote?: string | null;
}

export interface ResolveShortageRequest {
  /** Đã làm gì (điều chỉnh tồn, nhập thêm…), ≤ 300 ký tự. */
  note: string;
}

/** `resolved` vắng = false (chưa xử lý). */
export interface MaterialShortageQuery {
  resolved?: boolean;
  page?: number;
  size?: number;
}

export const MATERIAL_USE_LIMITS = { note: 300 } as const;
