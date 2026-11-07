// Cấu hình phí vận chuyển, phí khám tại nhà và định mức vật tư (UC32) theo docs/api/fees-v1.md #1–6 (mục Schema). Trường `?` có thể vắng hoặc null.

/** Phí của đơn = 0 nếu tiền hàng ≥ `freeThreshold` và ngưỡng > 0, ngược lại bằng `baseFee` (BR-SP-08). Một bộ chung cho cả chuỗi. */
export interface ShippingFeeConfig {
  /** VND, ≥ 0. */
  baseFee: number;
  /** VND, ≥ 0; 0 = không miễn phí. */
  freeThreshold: number;
  updatedAt: string;
}
export interface SaveShippingFeeConfigRequest {
  baseFee: number;
  freeThreshold: number;
}

/** Chi nhánh chưa cấu hình trả `enabled = false`, `fee = 0` (BR-SP-09). */
export interface HomeVisitFee {
  branchId: number;
  enabled: boolean;
  /** VND, ≥ 0. */
  fee: number;
}
export interface SaveHomeVisitFeeRequest {
  enabled: boolean;
  fee: number;
}

export interface MaterialNorm {
  productId: number;
  productName: string;
  unit: string;
  /** Số lượng mỗi lần thực hiện dịch vụ, nguyên ≥ 1 (BR-SP-10). */
  quantity: number;
}
export interface MaterialNormInput {
  productId: number;
  quantity: number;
}
/** Ghi đè toàn bộ định mức của dịch vụ; `items` rỗng = xóa hết. */
export interface SaveMaterialNormsRequest {
  items: MaterialNormInput[];
}
