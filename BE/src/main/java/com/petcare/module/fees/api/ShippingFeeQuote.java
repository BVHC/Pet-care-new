package com.petcare.module.fees.api;

/**
 * Kết quả ST09: phí vận chuyển của một đơn cùng cấu hình đã dùng để tính (BR-SP-08).
 *
 * @param fee           phí áp dụng (đ): 0 nếu miễn phí, ngược lại bằng {@code baseFee}
 * @param baseFee       phí cơ bản đang cấu hình (đ)
 * @param freeThreshold ngưỡng miễn phí đang cấu hình (đ); 0 = không miễn phí
 */
public record ShippingFeeQuote(long fee, long baseFee, long freeThreshold) {
}
